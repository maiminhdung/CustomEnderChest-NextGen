package org.maiminhdung.customenderchest.vault;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.locale.LocaleManager;
import org.maiminhdung.customenderchest.utils.CustomItemProvider;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * First-run setup experience: the first time an admin joins a fresh
 * installation, a one-time GUI lets them choose between the classic single
 * Ender Chest and the Multi-Vault system. The layout is fully data-driven
 * (DeluxeMenu-style {@code items:} sections with slot lists and click
 * actions) from {@code gui/setup.yml}. The choice is applied to the live
 * config, and a marker file ensures the GUI is never shown automatically
 * again. {@code /<main> setup} reopens it manually for admins.
 */
public class FirstRunSetupManager {

	private final EnderChest plugin;
	private final File markerFile;
	/** Setup inventory -> viewer uuid. */
	private final Map<Inventory, UUID> openSetupGuis = new ConcurrentHashMap<>();
	/** Per-viewer slot -> action map of the currently open setup GUI. */
	private final Map<UUID, Map<Integer, String>> openSetupActions = new ConcurrentHashMap<>();

	public FirstRunSetupManager(EnderChest plugin) {
		this.plugin = plugin;
		this.markerFile = new File(new File(plugin.getDataFolder(), "data"), ".setup-completed");
	}

	/** @return true once an admin has completed the first-run choice */
	public boolean isSetupCompleted() {
		return markerFile.exists();
	}

	/**
	 * @return true when the player is an admin and the one-time setup GUI
	 *         should still be shown (fresh installation, no choice made yet)
	 */
	public boolean shouldAutoShow(Player player) {
		return !isSetupCompleted() && player != null && player.isOnline()
				&& (player.isOp() || player.hasPermission("CustomEnderChest.admin"));
	}

	public boolean isSetupInventory(Inventory inventory) {
		return openSetupGuis.containsKey(inventory);
	}

	/** Opens the one-time setup GUI for an admin. */
	public void openSetupGui(Player admin) {
		if (admin == null || !admin.isOnline()) {
			return;
		}
		GuiConfigManager guiManager = plugin.getGuiConfigManager();
		if (guiManager == null) {
			return;
		}
		org.bukkit.configuration.ConfigurationSection config = guiManager.getSetup();

		int rows = guiManager.rows(config, 6);
		int size = rows * 9;
		Component title = MiniMessage.miniMessage().deserialize(
				guiManager.text(admin, config, "title", "Choose your Ender Chest mode"),
				Placeholder.unparsed("player", admin.getName()));
		Inventory gui = Bukkit.createInventory(admin, size, title);

		// Free-form items (DeluxeMenu style)
		Map<Integer, String> slotActions = new HashMap<>();
		for (ConfigurationSection item : guiManager.itemSections(config)) {
			String action = guiManager.action(item);
			List<Integer> slots = guiManager.clampSlots(
					guiManager.parseSlots(item.getString("slots",
							String.valueOf(item.getInt("slot", -1)))), size);
			if (slots.isEmpty()) {
				continue;
			}
			ItemStack stack = guiManager.buildItem(admin,
					item.getString("material", "STONE"),
					guiManager.text(admin, item, "name", null),
					guiManager.text(admin, item, "lore", null));
			if (stack == null) {
				continue;
			}
			for (int slot : slots) {
				gui.setItem(slot, stack);
				slotActions.put(slot, action);
			}
		}

		// Optional filler for slots not covered by any item
		ItemStack filler = CustomItemProvider.resolve(config.getString("filler", "none"));
		if (filler != null) {
			for (int slot = 0; slot < size; slot++) {
				if (gui.getItem(slot) == null) {
					gui.setItem(slot, filler.clone());
				}
			}
		}

		openSetupGuis.put(gui, admin.getUniqueId());
		openSetupActions.put(admin.getUniqueId(), slotActions);
		admin.openInventory(gui);
		plugin.getSoundHandler().playSound(admin, "open");
	}

	/** Handles a click inside the setup GUI. Always cancels the event. */
	public void handleClick(Player admin, InventoryClickEvent event) {
		event.setCancelled(true);

		if (event.getClickedInventory() == null
				|| !openSetupGuis.containsKey(event.getClickedInventory())) {
			return;
		}

		String action = openSetupActions
				.getOrDefault(admin.getUniqueId(), Map.of())
				.get(event.getSlot());

		switch (action == null ? "none" : action) {
			case "choose-single" -> applyChoice(admin, false);
			case "choose-multi" -> applyChoice(admin, true);
			case "close" -> {
				admin.closeInventory();
				plugin.getSoundHandler().playSound(admin, "close");
			}
			default -> {
				// Decorative item — nothing to do.
			}
		}
	}

	public void handleClose(Inventory inventory) {
		openSetupGuis.remove(inventory);
	}

	/** Clears tracked views (plugin disable). */
	public void clear() {
		openSetupGuis.clear();
		openSetupActions.clear();
	}

	private void applyChoice(Player admin, boolean multiVault) {
		// Persist the choice into config.yml (Bukkit preserves comments on
		// modern Paper when saving a loaded config).
		try {
			plugin.getConfig().set("multi-vault.enabled", multiVault);
			plugin.saveConfig();
			plugin.config().reload();
		} catch (Exception error) {
			plugin.getLogger().warning("[Setup] Could not persist the choice to config.yml: "
					+ error.getMessage() + " - applying for this session only.");
			EnderChest.trackError(error);
		}

		markSetupCompleted();

		plugin.getLogger().info("[Setup] " + admin.getName() + " chose "
				+ (multiVault ? "Multi-Vault mode." : "single Ender Chest mode (previous versions' behavior)."));

		admin.closeInventory();
		LocaleManager locale = plugin.getLocaleManager();
		String modeName = MiniMessage.miniMessage().stripTags(locale.getRawString(admin,
				"gui.setup." + (multiVault ? "multi" : "single") + "-name",
				multiVault ? "Multi-Vault" : "Single Ender Chest"));
		admin.sendMessage(locale.getPrefixedComponent(admin, "setup.applied",
				Placeholder.unparsed("mode", modeName)));
		admin.sendMessage(locale.getPrefixedComponent(admin, "setup.reminder"));
		plugin.getSoundHandler().playSound(admin, "close");
	}

	private void markSetupCompleted() {
		try {
			File parent = markerFile.getParentFile();
			if ((parent.exists() || parent.mkdirs()) && !markerFile.exists()) {
				Files.writeString(markerFile.toPath(), "completed=true\n");
			}
		} catch (IOException error) {
			plugin.getLogger().warning("[Setup] Could not write the setup marker: " + error.getMessage());
			EnderChest.trackError(error);
		}
	}
}
