package org.maiminhdung.customenderchest.vault;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.utils.CustomItemProvider;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Loads and exposes the editable GUI layouts from {@code plugins/
 * CustomEnderChest/gui/} (setup.yml, selector.yml). Missing files are
 * extracted from the plugin JAR; files from the old pre-DeluxeMenu format
 * are moved to {@code <name>.old.yml} and re-extracted; {@link #reload()}
 * re-reads everything so admins can apply edits with {@code /<main> reload}.
 */
public class GuiConfigManager {

	private final EnderChest plugin;
	private final MiniMessage miniMessage = MiniMessage.miniMessage();
	private org.bukkit.configuration.ConfigurationSection setup;
	private org.bukkit.configuration.ConfigurationSection selector;

	public GuiConfigManager(EnderChest plugin) {
		this.plugin = plugin;
		reload();
	}

	public void reload() {
		File guiFolder = new File(plugin.getDataFolder(), "gui");
		if (!guiFolder.exists() && !guiFolder.mkdirs()) {
			plugin.getLogger().warning("Could not create the gui folder; using bundled GUI layouts.");
		}
		this.setup = load("setup.yml", guiFolder);
		this.selector = load("selector.yml", guiFolder);
	}

	private org.bukkit.configuration.ConfigurationSection load(String fileName, File guiFolder) {
		File file = new File(guiFolder, fileName);
		if (file.exists() && !hasItemsSection(file, fileName.substring(0, fileName.length() - 4))) {
			// Pre-DeluxeMenu layout: keep the admin's copy and extract the new format
			File backup = new File(guiFolder, fileName + ".old");
			try {
				Files.move(file.toPath(), backup.toPath());
				plugin.getLogger().info("gui/" + fileName + " uses the old layout; moved to "
						+ backup.getName() + " and extracted the new DeluxeMenu-style layout.");
			} catch (Exception e) {
				plugin.getLogger().warning("Could not back up gui/" + fileName + ": " + e.getMessage());
				EnderChest.trackError(e);
			}
		}
		if (!file.exists()) {
			try (InputStream in = plugin.getResource("gui/" + fileName)) {
				if (in != null) {
					Files.copy(in, file.toPath());
					plugin.getLogger().info("Created default GUI layout: gui/" + fileName);
				}
			} catch (Exception e) {
				plugin.getLogger().warning("Could not extract gui/" + fileName + ": " + e.getMessage());
				EnderChest.trackError(e);
			}
		}
		YamlConfiguration config = new YamlConfiguration();
		try {
			config.load(file);
		} catch (Exception e) {
			plugin.getLogger().warning("Invalid gui/" + fileName + ": " + e.getMessage()
					+ " - falling back to bundled layout.");
			EnderChest.trackError(e);
			try (InputStream in = plugin.getResource("gui/" + fileName)) {
				if (in != null) {
					config.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
				}
			} catch (Exception ignored) {
				// Keep the empty config; callers use safe defaults.
			}
		}
		// Accept both flat files and files wrapped in a "setup:"/"selector:" root key
		String base = fileName.substring(0, fileName.length() - 4);
		if (config.isConfigurationSection(base)) {
			return config.getConfigurationSection(base);
		}
		return config;
	}

	private boolean hasItemsSection(File file, String base) {
		YamlConfiguration probe = new YamlConfiguration();
		try {
			probe.load(file);
		} catch (Exception e) {
			return true; // broken files are handled by the fallback loader
		}
		// Flat layout or wrapped in a "setup:"/"selector:" root key
		return probe.isConfigurationSection("items")
				|| probe.isConfigurationSection(base + ".items");
	}

	public org.bukkit.configuration.ConfigurationSection getSetup() {
		return setup;
	}

	public org.bukkit.configuration.ConfigurationSection getSelector() {
		return selector;
	}

	// ==================== schema helpers ====================

	public int rows(org.bukkit.configuration.ConfigurationSection config, int def) {
		int rows = config.getInt("rows", def);
		return Math.max(2, Math.min(6, rows));
	}

	/**
	 * Resolves a GUI text value. Values starting with {@code lang:} are
	 * looked up as keys in the language files (viewer-aware overload
	 * available for per-player locales); lists are joined with newlines;
	 * anything else is raw MiniMessage.
	 */
	public String text(org.bukkit.configuration.ConfigurationSection config, String path, String def) {
		if (config == null) {
			return def;
		}
		if (config.isList(path)) {
			List<String> list = config.getStringList(path);
			List<String> resolved = new ArrayList<>();
			for (String entry : list) {
				if (entry != null && entry.regionMatches(true, 0, "lang:", 0, 5)) {
					resolved.add(plugin.getLocaleManager().getRawString(entry.substring(5), entry));
				} else if (entry != null) {
					resolved.add(entry);
				}
			}
			return String.join("\n", resolved);
		}
		String raw = config.getString(path, def);
		if (raw != null && raw.regionMatches(true, 0, "lang:", 0, 5)) {
			return plugin.getLocaleManager().getRawString(raw.substring(5), def);
		}
		return raw != null ? raw : def;
	}

	/** Viewer-aware variant: {@code lang:} values resolve in the player's locale. */
	public String text(CommandSender viewer, org.bukkit.configuration.ConfigurationSection config, String path, String def) {
		if (config == null) {
			return def;
		}
		if (config.isList(path)) {
			List<String> list = config.getStringList(path);
			List<String> resolved = new ArrayList<>();
			for (String entry : list) {
				if (entry != null && entry.regionMatches(true, 0, "lang:", 0, 5)) {
					resolved.add(plugin.getLocaleManager().getRawString(viewer, entry.substring(5), entry));
				} else if (entry != null) {
					resolved.add(entry);
				}
			}
			return String.join("\n", resolved);
		}
		String raw = config.getString(path, def);
		if (raw != null && raw.regionMatches(true, 0, "lang:", 0, 5)) {
			return plugin.getLocaleManager().getRawString(viewer, raw.substring(5), def);
		}
		return raw != null ? raw : def;
	}

	/** Parses a slot list: "0-8, 45, 53" (1-based rows in files, 0-based slots here). */
	public List<Integer> parseSlots(String spec) {
		List<Integer> slots = new ArrayList<>();
		if (spec == null || spec.isBlank()) {
			return slots;
		}
		for (String part : spec.split(",")) {
			String trimmed = part.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			int dash = trimmed.indexOf('-');
			try {
				if (dash > 0) {
					int from = Integer.parseInt(trimmed.substring(0, dash).trim());
					int to = Integer.parseInt(trimmed.substring(dash + 1).trim());
					for (int slot = Math.min(from, to); slot <= Math.max(from, to); slot++) {
						slots.add(slot);
					}
				} else {
					slots.add(Integer.parseInt(trimmed));
				}
			} catch (NumberFormatException ignored) {
				// Skip malformed segments.
			}
		}
		return slots;
	}

	/** All free-form {@code items.<id>} sections in file order. */
	public List<ConfigurationSection> itemSections(org.bukkit.configuration.ConfigurationSection config) {
		List<ConfigurationSection> sections = new ArrayList<>();
		ConfigurationSection items = config.getConfigurationSection("items");
		if (items != null) {
			for (String id : items.getKeys(false)) {
				ConfigurationSection section = items.getConfigurationSection(id);
				if (section != null) {
					sections.add(section);
				}
			}
		}
		return sections;
	}

	/**
	 * Builds an ItemStack from a material spec + MiniMessage name/lore with
	 * placeholders. Lore splits on real or escaped newlines. Disables default
	 * vanilla italics on display name and lore unless explicitly tagged. Returns null
	 * when the spec is "none" or cannot be resolved.
	 */
	public ItemStack buildItem(CommandSender viewer, String materialSpec, String name, String lore,
			TagResolver... placeholders) {
		ItemStack item = CustomItemProvider.resolve(materialSpec);
		if (item == null) {
			return null;
		}
		item = item.clone();
		ItemMeta meta = item.getItemMeta();
		if (meta != null) {
			if (name != null && !name.isBlank()) {
				meta.displayName(renderComponent(name, placeholders));
			}
			if (lore != null && !lore.isBlank()) {
				List<Component> loreLines = new ArrayList<>();
				for (String line : lore.split("\\\\r\\\\n|\\\\n|\\r\\n|\\n")) {
					loreLines.add(renderComponent(line, placeholders));
				}
				meta.lore(loreLines);
			}
			item.setItemMeta(meta);
		}
		return item;
	}

	/**
	 * Deserializes MiniMessage text while disabling vanilla Minecraft's default
	 * italic styling on item meta, unless the text explicitly enables italics.
	 */
	private Component renderComponent(String text, TagResolver... placeholders) {
		if (text == null || text.isBlank()) {
			return Component.empty().decoration(TextDecoration.ITALIC, false);
		}
		Component parsed = miniMessage.deserialize(text, placeholders);
		return Component.empty().decoration(TextDecoration.ITALIC, false).append(parsed);
	}

	/** Slot index guard: keeps values inside the inventory, silently dropping others. */
	public List<Integer> clampSlots(List<Integer> slots, int size) {
		List<Integer> clamped = new ArrayList<>();
		for (int slot : slots) {
			if (slot >= 0 && slot < size && !clamped.contains(slot)) {
				clamped.add(slot);
			}
		}
		return clamped;
	}

	/** Lowercase action name of an item section ("none" when unset). */
	public String action(ConfigurationSection item) {
		return item.getString("action", "none").trim().toLowerCase(Locale.ROOT);
	}
}
