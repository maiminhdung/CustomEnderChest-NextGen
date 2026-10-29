package org.maiminhdung.customenderchest.vault;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.utils.CustomItemProvider;
import org.maiminhdung.customenderchest.utils.EnderChestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Configurable vault selector GUI (/{main} vaults), DeluxeMenu-style: every
 * visual element is an {@code items:} entry with slot lists; the dynamic
 * vault entry (action {@code open-vault}) maps its slot list positionally to
 * vault ids. Layout and texts come from {@code gui/selector.yml}; materials
 * may be vanilla or resource-pack items ("itemadder:<namespace:id>" /
 * "nexo:<item_id>").
 */
public class VaultSelectorManager {

	private final EnderChest plugin;
	private final MiniMessage miniMessage = MiniMessage.miniMessage();
	/** Selector inventory -> viewer uuid. */
	private final Map<Inventory, UUID> selectorViews = new ConcurrentHashMap<>();
	/** Per-viewer slot -> vault id (dynamic vault item) and slot -> action. */
	private final Map<UUID, Map<Integer, Integer>> openVaultSlots = new ConcurrentHashMap<>();
	private final Map<UUID, Map<Integer, String>> openSlotActions = new ConcurrentHashMap<>();

	public VaultSelectorManager(EnderChest plugin) {
		this.plugin = plugin;
	}

	public boolean isSelectorInventory(Inventory inventory) {
		return selectorViews.containsKey(inventory);
	}

	/**
	 * Opens the selector GUI for a player.
	 *
	 * @return true when the GUI was opened (or is already open), false when a
	 *         check denied it (disabled, offline, ...)
	 */
	public boolean openSelector(Player player) {
		if (player == null || !player.isOnline()) {
			return false;
		}
		if (!EnderChestUtils.isVaultsEnabled() || !EnderChestUtils.isSelectorGuiEnabled()) {
			player.sendMessage(plugin.getLocaleManager().getPrefixedComponent(player, "vault-selector.disabled"));
			return false;
		}
		// Rapid re-open guard: never stack a second selector on top
		if (selectorViews.containsValue(player.getUniqueId())) {
			return true;
		}
		GuiConfigManager guiManager = plugin.getGuiConfigManager();
		if (guiManager == null) {
			return false;
		}
		org.bukkit.configuration.ConfigurationSection config = guiManager.getSelector();

		int rows = guiManager.rows(config, 6);
		int size = rows * 9;
		int allowed = EnderChestUtils.getVaultCount(player);
		int maxVaults = EnderChestUtils.getMaxVaults();
		boolean showLocked = config.getBoolean("show-locked", true);

		Component title = miniMessage.deserialize(
				guiManager.text(player, config, "title", "<dark_purple><player>'s Vaults"),
				Placeholder.unparsed("player", player.getName()));
		Inventory selector = Bukkit.createInventory(player, size, title);

		Map<Integer, Integer> vaultSlots = new HashMap<>();
		Map<Integer, String> slotActions = new HashMap<>();

		for (ConfigurationSection item : guiManager.itemSections(config)) {
			String action = guiManager.action(item);
			List<Integer> slots = guiManager.clampSlots(
					guiManager.parseSlots(item.getString("slots",
							String.valueOf(item.getInt("slot", -1)))), size);
			if (slots.isEmpty()) {
				continue;
			}

			if (action.equals("open-vault")) {
				// Dynamic vault item: slot position -> vault id
				ConfigurationSection locked = item.getConfigurationSection("locked");
				int vaultId = 1;
				for (int slot : slots) {
					if (vaultId > maxVaults) {
						break;
					}
					boolean unlocked = vaultId <= allowed;
					if (!unlocked && (!showLocked || locked == null)) {
						vaultId++;
						continue;
					}
					ItemStack stack = unlocked
							? vaultItem(guiManager, player, item, vaultId)
							: lockedItem(guiManager, player, locked, vaultId);
					if (stack != null) {
						selector.setItem(slot, stack);
						vaultSlots.put(slot, vaultId);
					}
					vaultId++;
				}
				continue;
			}

			ItemStack stack = guiManager.buildItem(player,
					item.getString("material", "STONE"),
					guiManager.text(player, item, "name", null),
					guiManager.text(player, item, "lore", null));
			if (stack == null) {
				continue;
			}
			for (int slot : slots) {
				selector.setItem(slot, stack);
				slotActions.put(slot, action);
			}
		}

		// Optional filler for slots not covered by any item
		ItemStack filler = CustomItemProvider.resolve(config.getString("filler", "none"));
		if (filler != null) {
			for (int slot = 0; slot < size; slot++) {
				if (selector.getItem(slot) == null) {
					selector.setItem(slot, filler.clone());
				}
			}
		}

		selectorViews.put(selector, player.getUniqueId());
		openVaultSlots.put(player.getUniqueId(), vaultSlots);
		openSlotActions.put(player.getUniqueId(), slotActions);
		player.openInventory(selector);
		plugin.getSoundHandler().playSound(player, "open");
		return true;
	}

	private ItemStack vaultItem(GuiConfigManager guiManager, Player player,
			ConfigurationSection item, int vaultId) {
		Inventory cached = plugin.getEnderChestManager().getLoadedVault(player.getUniqueId(), vaultId);
		int items = -1;
		if (cached != null) {
			items = 0;
			for (ItemStack content : cached.getContents()) {
				if (content != null && !content.getType().isAir()) {
					items++;
				}
			}
		}
		String itemsText = items < 0
				? plugin.getLocaleManager().getRawString(player, "vault-selector.items-unknown", "?")
				: String.valueOf(items);
		return guiManager.buildItem(player,
				item.getString("material", "ENDER_CHEST"),
				guiManager.text(player, item, "name", "<bold><light_purple>Vault <vault>"),
				guiManager.text(player, item, "lore", ""),
				resolvers(player, vaultId, itemsText));
	}

	private ItemStack lockedItem(GuiConfigManager guiManager, Player player,
			ConfigurationSection locked, int vaultId) {
		return guiManager.buildItem(player,
				locked.getString("material", "GRAY_DYE"),
				guiManager.text(player, locked, "name", "<gray><bold>Vault <vault> <dark_gray>(locked)"),
				guiManager.text(player, locked, "lore", ""),
				resolvers(player, vaultId, "-"));
	}

	private TagResolver[] resolvers(Player player, int vaultId, String itemsText) {
		return new TagResolver[] {
				Placeholder.unparsed("vault", String.valueOf(vaultId)),
				Placeholder.unparsed("size", String.valueOf(EnderChestUtils.getSize(player, vaultId))),
				Placeholder.unparsed("items", itemsText)
		};
	}

	/** Routes a click inside a selector inventory. Always cancels the event. */
	public void handleClick(Player player, InventoryClickEvent event) {
		event.setCancelled(true);

		// Block shift-clicks from the player inventory into the selector
		if (event.getClickedInventory() == null
				|| !selectorViews.containsKey(event.getClickedInventory())) {
			return;
		}

		// Dynamic vault slot?
		Integer vaultId = openVaultSlots
				.getOrDefault(player.getUniqueId(), Map.of())
				.get(event.getSlot());
		if (vaultId != null) {
			int allowed = EnderChestUtils.getVaultCount(player);
			if (vaultId > allowed) {
				player.sendMessage(plugin.getLocaleManager().getPrefixedComponent(player,
						"vault-selector.locked-message",
						Placeholder.unparsed("vault", String.valueOf(vaultId))));
				plugin.getSoundHandler().playSound(player, "fail");
				return;
			}
			player.closeInventory();
			plugin.getEnderChestManager().openEnderChest(player, vaultId);
			return;
		}

		String action = openSlotActions
				.getOrDefault(player.getUniqueId(), Map.of())
				.get(event.getSlot());
		if ("close".equals(action)) {
			player.closeInventory();
			plugin.getSoundHandler().playSound(player, "close");
		}
	}

	public void handleClose(Inventory inventory) {
		UUID viewer = selectorViews.remove(inventory);
		if (viewer != null) {
			openVaultSlots.remove(viewer);
			openSlotActions.remove(viewer);
		}
	}

	/** Clears all tracked views (used on plugin disable). */
	public void clear() {
		selectorViews.clear();
		openVaultSlots.clear();
		openSlotActions.clear();
	}
}
