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
import org.maiminhdung.customenderchest.locale.LocaleManager;
import org.maiminhdung.customenderchest.utils.DataLockManager;
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
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        if (event.getClickedBlock().getType() != Material.ENDER_CHEST) return;

        event.setCancelled(true);
        Player player = event.getPlayer();

        if (player == null || !player.isOnline()) {
            return;
        }

        // Let the EnderChestManager handle the permission check logic now
        plugin.getEnderChestManager().openEnderChest(player);
    }

    // Handle inventory clicks to enforce slot restrictions and admin sync
    @EventHandler(priority = EventPriority.LOW)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Inventory clickedInv = event.getInventory();
        if (clickedInv == null) return;

        EnderChestManager manager = plugin.getEnderChestManager();
        Inventory cachedInv = manager.getLoadedEnderChest(player.getUniqueId());

        if (cachedInv != null && clickedInv.equals(cachedInv)) {
            int slot = event.getRawSlot();
            int permissionSize = EnderChestUtils.getSize(player);

            // Check if clicking outside permission range
            if (slot >= permissionSize && slot < clickedInv.getSize()) {
                event.setCancelled(true);
                player.sendMessage(plugin.getLocaleManager().getComponent("messages.slot-locked"));
                plugin.getSoundHandler().playSound(player, "fail");
            }
        }

        // Logic for admin sync can go here, but separated for clarity
        onAdminInventoryClick(event);
    }

    // Separate method to handle admin inventory click syncing
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
        if (!player.isOnline()) return;

        EnderChestManager manager = plugin.getEnderChestManager();
        Inventory closedInventory = event.getInventory();

        if (closedInventory == null) return;

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