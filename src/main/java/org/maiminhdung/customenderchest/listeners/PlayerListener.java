package org.maiminhdung.customenderchest.listeners;

import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.data.EnderChestManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.maiminhdung.customenderchest.utils.EnderChestUtils;

public class PlayerListener implements Listener {

    private final EnderChest plugin;

    public PlayerListener(EnderChest plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.getEnderChestManager().onPlayerJoin(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getEnderChestManager().onPlayerQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEnderChestInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }

        if (event.getClickedBlock().getType() != Material.ENDER_CHEST) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();

        int size = EnderChestUtils.getSize(player);
        if (size == 0) {
            // If the player has no custom ender chest size, do nothing
            player.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.no-permission"));
            plugin.getSoundHandler().playSound(player, "fail");
            return;
        }

        // If the player has a custom ender chest size, open it
        plugin.getEnderChestManager().openEnderChest(player);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        EnderChestManager manager = plugin.getEnderChestManager();
        Inventory cachedInv = manager.getLoadedEnderChest(player.getUniqueId());

        if (event.getInventory().equals(cachedInv)) {
            plugin.getSoundHandler().playSound(player, "close");
        }
    }
}