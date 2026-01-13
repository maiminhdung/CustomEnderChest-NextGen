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
        Player player = event.getPlayer();
        plugin.getEnderChestManager().onPlayerJoin(player);

        // Trigger auto-import of vanilla ender chest data after a short delay
        // to ensure player data is fully loaded
        Scheduler.runEntityTaskLater(player, () -> {
            if (player.isOnline()) {
                plugin.getLegacyImporter().autoImportOnJoin(player);
            }
        }, 40L); // 2 second delay to ensure data is loaded
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getEnderChestManager().onPlayerQuit(event.getPlayer());
        // Clear auto-import tracking when player quits
        plugin.getLegacyImporter().clearAutoImportTracking(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEnderChestInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null)
            return;
        if (event.getClickedBlock().getType() != Material.ENDER_CHEST)
            return;
        if (plugin.getConfig().getBoolean("enderchest-options.disable-plugin-on-endechest-block"))
            return;

        // Cancel the event immediately to prevent vanilla enderchest GUI from opening
        event.setCancelled(true);

        if (plugin.getConfig().getBoolean("enderchest-options.disable-enderchest-click")) {
            player.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.enderchest-click-disabled"));
            plugin.getSoundHandler().playSound(player, "fail");
            return;
        }

        // Check permission to open ender chest via block
        if (!hasBlockOpenPermission(player)) {
            player.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.no-permission-block-open"));
            plugin.getSoundHandler().playSound(player, "fail");
            return;
        }

        if (!player.isOnline()) {
            return;
        }
        // Let the EnderChestManager handle the permission check logic now
        plugin.getEnderChestManager().openEnderChest(player);
    }

    /**
     * Check if player has permission to open ender chest via block interaction.
     * Returns true if player is OP, has CustomEnderChest.block.open permission,
     * OR if default-player.allow-open-enderchest is enabled in config.
     */
    private boolean hasBlockOpenPermission(Player player) {
        // OP players always have permission
        if (player.isOp()) {
            return true;
        }

        // Check explicit permission
        if (player.hasPermission("CustomEnderChest.block.open")) {
            return true;
        }

        // Check default-player config setting
        return plugin.getConfig().getBoolean("default-player.allow-open-enderchest", true);
    }

    // Handle inventory clicks to enforce slot restrictions and admin sync
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player))
            return;
        if (event.isCancelled())
            return;

        syncInventoryChange(player, event.getInventory());
    }

    // Handle inventory drag events (shift-click, drag multiple items, etc.)
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player))
            return;
        if (event.isCancelled())
            return;

        syncInventoryChange(player, event.getInventory());
    }

    /**
     * Syncs inventory changes bidirectionally between admin views and player
     * inventories
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
        if (!(event.getPlayer() instanceof Player player))
            return;
        if (!player.isOnline())
            return;

        EnderChestManager manager = plugin.getEnderChestManager();
        Inventory closedInventory = event.getInventory();

        LocaleManager localeManager = plugin.getLocaleManager();

        manager.getOpenInventories().remove(player.getUniqueId());

        if (manager.getAdminViewedChests().containsKey(closedInventory)) {
            UUID targetUUID = manager.getAdminViewedChests().remove(closedInventory);

            if (targetUUID == null)
                return;

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

                // Save to database async with timeout - DO NOT block!
                String finalTargetName = targetName;
                manager.saveEnderChest(targetUUID, finalTargetName, closedInventory)
                        .orTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .whenComplete((result, ex) -> {
                            if (ex != null) {
                                plugin.getLogger().warning(
                                        "Failed to save admin edit for " + finalTargetName + ": " + ex.getMessage());
                            } else {
                                debug.log("Data for player " + finalTargetName + " saved successfully by admin.");
                            }
                            // Unlock in whenComplete to guarantee it's called
                            dataLockManager.unlock(targetUUID);
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

            // Save data immediately when player closes their ender chest to prevent data
            // loss
            DataLockManager dataLockManager = plugin.getDataLockManager();

            // Only save if not currently locked (prevent double-save)
            if (!dataLockManager.isLocked(player.getUniqueId())) {
                debug.log("Player " + player.getName() + " closed their ender chest. Saving data...");

                // Save asynchronously without blocking - let CompletableFuture handle it
                manager.saveEnderChest(player.getUniqueId(), player.getName(), closedInventory)
                        .exceptionally(ex -> {
                            plugin.getLogger().severe("Failed to save data for " + player.getName() +
                                    " after closing inventory: " + ex.getMessage());
                            return null;
                        })
                        .thenRun(() -> {
                            debug.log("Data for " + player.getName() + " saved after closing inventory.");
                        });
            }
        }
    }
}