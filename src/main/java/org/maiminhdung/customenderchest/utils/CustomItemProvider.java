package org.maiminhdung.customenderchest.utils;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.logging.Level;

/**
 * Resolves configured GUI item specs into ItemStacks.
 *
 * Supported spec formats:
 * - {@code itemadder:<namespace:id>} — custom item from ItemsAdder
 * - {@code nexo:<item_id>} — custom item from Nexo
 * - a plain {@link org.bukkit.Material} name (case-insensitive)
 * - {@code none} / {@code air} / empty — no item (null)
 *
 * ItemsAdder/Nexo are integrated through reflection so they remain optional
 * runtime dependencies.
 */
public final class CustomItemProvider {

	private CustomItemProvider() {
	}

	/**
	 * Resolves a spec into an ItemStack, or null when the spec means "no item"
	 * or nothing could be resolved.
	 */
	public static ItemStack resolve(String spec) {
		if (spec == null || spec.isBlank()) {
			return null;
		}
		String trimmed = spec.trim();
		if (trimmed.equalsIgnoreCase("none") || trimmed.equalsIgnoreCase("air")) {
			return null;
		}

		ItemStack custom = null;
		String materialCandidate = trimmed;
		if (trimmed.length() > 11 && trimmed.regionMatches(true, 0, "itemadder:", 0, 11)) {
			custom = itemsAdderItem(trimmed.substring(11));
			materialCandidate = trimmed.substring(11);
		} else if (trimmed.length() > 5 && trimmed.regionMatches(true, 0, "nexo:", 0, 5)) {
			custom = nexoItem(trimmed.substring(5));
			materialCandidate = trimmed.substring(5);
		}

		if (custom != null) {
			return custom;
		}

		// Fallback: treat the spec (or the part after a namespace prefix) as a
		// vanilla material so a missing plugin degrades gracefully.
		int colon = materialCandidate.indexOf(':');
		String materialName = colon >= 0 ? materialCandidate.substring(colon + 1) : materialCandidate;
		org.bukkit.Material material = org.bukkit.Material.matchMaterial(materialName);
		return material != null ? new ItemStack(material) : null;
	}

	private static ItemStack itemsAdderItem(String namespacedId) {
		Plugin plugin = Bukkit.getPluginManager().getPlugin("ItemAdder");
		if (plugin == null || !plugin.isEnabled()) {
			return null;
		}
		try {
			Class<?> api = Class.forName("dev.lone.itemsadder.api.ItemsAdder");
			Method getCustomItem = api.getMethod("getCustomItem", String.class);
			Object result = getCustomItem.invoke(null, namespacedId);
			return result instanceof ItemStack item ? item : null;
		} catch (Throwable error) {
			logFailure("ItemsAdder", namespacedId, error);
			return null;
		}
	}

	private static ItemStack nexoItem(String itemId) {
		Plugin plugin = Bukkit.getPluginManager().getPlugin("Nexo");
		if (plugin == null || !plugin.isEnabled()) {
			return null;
		}
		try {
			Class<?> api = Class.forName("com.nexomc.nexo.api.NexoItems");
			for (String methodName : new String[] { "itemFromId", "getItemFromId" }) {
				Optional<Method> found = findStaticMethod(api, methodName);
				if (found.isEmpty()) {
					continue;
				}
				Object result = found.get().invoke(null, itemId);
				if (result instanceof ItemStack item) {
					return item;
				}
				if (result instanceof Optional<?> optional) {
					return optional.filter(ItemStack.class::isInstance).map(ItemStack.class::cast).orElse(null);
				}
			}
			return null;
		} catch (Throwable error) {
			logFailure("Nexo", itemId, error);
			return null;
		}
	}

	private static Optional<Method> findStaticMethod(Class<?> clazz, String name) {
		for (Method method : clazz.getMethods()) {
			if (method.getName().equals(name) && method.getParameterCount() == 1
					&& method.getParameterTypes()[0] == String.class) {
				return Optional.of(method);
			}
		}
		return Optional.empty();
	}

	private static void logFailure(String pluginName, String itemId, Throwable error) {
		EnderChestSafeLogger.warning("Could not resolve " + pluginName + " item '" + itemId + "': " + error.getMessage());
	}

	/** Minimal logger access that never throws, even in unit-test contexts. */
	private static final class EnderChestSafeLogger {
		static void warning(String message) {
			try {
				org.maiminhdung.customenderchest.EnderChest.getInstance()
						.getLogger().log(Level.WARNING, message);
			} catch (Throwable ignored) {
				// No plugin instance (e.g. unit tests) — swallow.
			}
		}
	}
}
