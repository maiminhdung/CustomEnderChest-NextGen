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

        Player player = event.getPlayer();

        if (player.hasPermission("customec.level.0")) {
            event.setCancelled(true);

            plugin.getEnderChestManager().openEnderChest(player);
            plugin.getSoundHandler().playSound(player, "open");
        }
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