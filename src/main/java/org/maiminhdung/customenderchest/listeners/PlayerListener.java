package org.maiminhdung.customenderchest.listeners;

import org.bukkit.Bukkit;
import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.Scheduler;
import org.maiminhdung.customenderchest.data.EnderChestManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.maiminhdung.customenderchest.utils.DebugLogger;
import org.maiminhdung.customenderchest.utils.EnderChestUtils;

import java.util.UUID;

public class PlayerListener implements Listener {

    private final EnderChest plugin;
    private final DebugLogger debug;

    public PlayerListener(EnderChest plugin) {
        this.plugin = plugin;
        this.debug = plugin.getDebugLogger();
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

    // NEW: Real-time sync when admin click inventory
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdminInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player admin)) return;
        if (event.isCancelled()) return;

        Inventory clickedInv = event.getInventory();
        EnderChestManager manager = plugin.getEnderChestManager();

        UUID targetUuid = manager.getAdminViewedChests().get(clickedInv);
        if (targetUuid == null) return;

        // If player online, sync immediately
        Player targetPlayer = Bukkit.getPlayer(targetUuid);
        if (targetPlayer != null && targetPlayer.isOnline()) {
            Scheduler.runTaskLater(() -> {
                Inventory targetLiveInv = manager.getLoadedEnderChest(targetUuid);
                if (targetLiveInv != null) {
                    targetLiveInv.setContents(clickedInv.getContents());
                    debug.log("Real-time sync: Admin " + admin.getName() +
                            " modified " + targetPlayer.getName() + "'s enderchest");
                }
            }, 1L);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        EnderChestManager manager = plugin.getEnderChestManager();
        Inventory closedInventory = event.getInventory();

        // Processing admin viewed chests
        if (manager.getAdminViewedChests().containsKey(closedInventory)) {
            UUID targetUUID = manager.getAdminViewedChests().remove(closedInventory);

            if (targetUUID != null) {
                debug.log("Admin " + player.getName() + " finished editing " + targetUUID + "'s chest. Saving data...");

                Player targetPlayer = Bukkit.getPlayer(targetUUID);

                if (targetPlayer != null && targetPlayer.isOnline()) {
                    // === ONLINE PLAYER - Sync and save ==
                    Inventory targetLiveInv = manager.getLoadedEnderChest(targetUUID);
                    if (targetLiveInv != null) {
                        targetLiveInv.setContents(closedInventory.getContents());
                        debug.log("Final sync completed for online player: " + targetPlayer.getName());
                    }

                    // Save to database async
                    String targetName = targetPlayer.getName();
                    Scheduler.runTaskAsync(() -> {
                        manager.saveEnderChest(targetUUID, targetName, closedInventory).join();
                        debug.log("Data for online player " + targetName + " saved successfully by admin.");
                    });
                } else {
                    // === OFFLINE PLAYER - Only save data to database ===
                    String targetName = Bukkit.getOfflinePlayer(targetUUID).getName();
                    Scheduler.runTaskAsync(() -> {
                        manager.saveEnderChest(targetUUID, targetName, closedInventory).join();
                        debug.log("Data for offline player " + targetName + " saved successfully by admin.");
                    });
                }

                plugin.getSoundHandler().playSound(player, "close");
            }
            return;
        }

        Inventory cachedInv = manager.getLoadedEnderChest(player.getUniqueId());
        if (closedInventory.equals(cachedInv)) {
            plugin.getSoundHandler().playSound(player, "close");
        }
    }
}