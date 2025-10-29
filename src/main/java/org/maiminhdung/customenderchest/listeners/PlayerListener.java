package org.maiminhdung.customenderchest.listeners;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.maiminhdung.customenderchest.Scheduler;
import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.data.EnderChestManager;
import org.maiminhdung.customenderchest.locale.LocaleManager;
import org.maiminhdung.customenderchest.utils.DataLockManager;
import org.maiminhdung.customenderchest.utils.DebugLogger;

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
        Player player = event.getPlayer();

        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        if (event.getClickedBlock().getType() != Material.ENDER_CHEST) return;
        if (plugin.getConfig().getBoolean("enderchest-options.disable-plugin-on-endechest-block")) return;

        // Cancel the event immediately to prevent vanilla enderchest GUI from opening
        event.setCancelled(true);

        if (plugin.getConfig().getBoolean("enderchest-options.disable-enderchest-click")) {
            player.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.enderchest-click-disabled"));
            plugin.getSoundHandler().playSound(player, "fail");
            return;
        }


        if (!player.isOnline()) {
            return;
        }
        // Let the EnderChestManager handle the permission check logic now
        plugin.getEnderChestManager().openEnderChest(player);
    }

    // Handle inventory clicks to enforce slot restrictions and admin sync
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.isCancelled()) return;

        syncInventoryChange(player, event.getInventory());
    }

    // Handle inventory drag events (shift-click, drag multiple items, etc.)
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.isCancelled()) return;

        syncInventoryChange(player, event.getInventory());
    }

    /**
     * Syncs inventory changes bidirectionally between admin views and player inventories
     */
    private void syncInventoryChange(Player player, Inventory clickedInv) {
        EnderChestManager manager = plugin.getEnderChestManager();

        // === CASE 1: Admin is viewing someone's enderchest ===
        UUID targetUuid = manager.getAdminViewedChests().get(clickedInv);
        if (targetUuid != null) {
            // Admin clicked in their view - sync changes to target player's live inventory
            Player targetPlayer = Bukkit.getPlayer(targetUuid);
            if (targetPlayer != null && targetPlayer.isOnline()) {
                Scheduler.runTaskLater(() -> {
                    Inventory targetLiveInv = manager.getLoadedEnderChest(targetUuid);
                    if (targetLiveInv != null) {
                        targetLiveInv.setContents(clickedInv.getContents());
                        debug.log("Admin->Player sync: " + player.getName() +
                                " modified " + targetPlayer.getName() + "'s enderchest");
                    }
                }, 1L);
            }
            return;
        }

        // === CASE 2: Player clicked their own inventory while admin is viewing ===
        // Check if this player's inventory is being viewed by any admin
        Inventory playerLiveInv = manager.getLoadedEnderChest(player.getUniqueId());
        if (playerLiveInv != null && clickedInv.equals(playerLiveInv)) {
            // Find if any admin is viewing this player's inventory
            for (var entry : manager.getAdminViewedChests().entrySet()) {
                if (entry.getValue().equals(player.getUniqueId())) {
                    Inventory adminView = entry.getKey();
                    // Sync changes from player's inventory to admin's view
                    Scheduler.runTaskLater(() -> {
                        adminView.setContents(playerLiveInv.getContents());
                        debug.log("Player->Admin sync: " + player.getName() +
                                "'s changes synced to admin view");
                    }, 1L);
                    break;
                }
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!player.isOnline()) return;

        EnderChestManager manager = plugin.getEnderChestManager();
        Inventory closedInventory = event.getInventory();

        LocaleManager localeManager = plugin.getLocaleManager();

        manager.getOpenInventories().remove(player.getUniqueId());

        if (manager.getAdminViewedChests().containsKey(closedInventory)) {
            UUID targetUUID = manager.getAdminViewedChests().remove(closedInventory);

            if (targetUUID == null) return;

            DataLockManager dataLockManager = plugin.getDataLockManager();

            if (dataLockManager.isLocked(targetUUID)) {
                player.sendMessage(localeManager.getPrefixedComponent("messages.data-still-loading"));
                return;
            }
            dataLockManager.lock(targetUUID); // Lock data before processing

            try {
                debug.log("Admin " + player.getName() + " finished editing " + targetUUID + "'s chest. Saving data...");

                Player targetPlayer = Bukkit.getPlayer(targetUUID);
                String targetName;

                if (targetPlayer != null && targetPlayer.isOnline()) {
                    // === ONLINE PLAYER - Sync and save ==
                    targetName = targetPlayer.getName();
                    Inventory targetLiveInv = manager.getLoadedEnderChest(targetUUID);
                    if (targetLiveInv != null) {
                        targetLiveInv.setContents(closedInventory.getContents());
                        debug.log("Final sync completed for online player: " + targetName);
                    }
                } else {
                    // === OFFLINE PLAYER - Prepare name for saving ===
                    targetName = Bukkit.getOfflinePlayer(targetUUID).getName();
                }

                // Save to database async
                String finalTargetName = targetName;
                Scheduler.runTaskAsync(() -> {
                    try {
                        manager.saveEnderChest(targetUUID, finalTargetName, closedInventory).join();
                        debug.log("Data for player " + finalTargetName + " saved successfully by admin.");
                    } finally {
                        // Unlock in a finally block to guarantee it's called
                        dataLockManager.unlock(targetUUID);
                    }
                });

                plugin.getSoundHandler().playSound(player, "close");

            } catch (Exception e) {
                // In case of any unexpected error, make sure to unlock
                dataLockManager.unlock(targetUUID);
                player.sendMessage(localeManager.getPrefixedComponent("messages.save-error"));
                e.printStackTrace();
            }
            return;
        }

        Inventory cachedInv = manager.getLoadedEnderChest(player.getUniqueId());
        if (closedInventory.equals(cachedInv)) {
            plugin.getSoundHandler().playSound(player, "close");
        }
    }
}