package org.maiminhdung.customenderchest.listeners;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
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
import org.maiminhdung.customenderchest.api.events.EnderVaultCloseEvent;
import org.maiminhdung.customenderchest.data.EnderChestManager;
import org.maiminhdung.customenderchest.locale.LocaleManager;
import org.maiminhdung.customenderchest.utils.DataLockManager;
import org.maiminhdung.customenderchest.utils.DebugLogger;
import org.maiminhdung.customenderchest.utils.VanishChecker;
import org.maiminhdung.customenderchest.utils.VaultKey;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerListener implements Listener {

    private final EnderChest plugin;
    private final DebugLogger debug;

    /**
     * Tracks which enderchest block each player interacted with.
     * Used to play the close animation when the inventory is closed.
     * Only populated when player opens via block click (not command).
     */
    private final Map<UUID, Block> playerEnderChestBlocks = new ConcurrentHashMap<>();

    public PlayerListener(EnderChest plugin) {
        this.plugin = plugin;
        this.debug = plugin.getDebugLogger();
    }

    /**
     * Checks if the player has an entry in playerEnderChestBlocks (meaning they opened via block click).
     */
    public boolean hasInteractedWithBlock(UUID uuid) {
        return playerEnderChestBlocks.containsKey(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getEnderChestManager().onPlayerJoin(player);

        // Notify admins in-game when a newer version was found by the startup check
        org.maiminhdung.customenderchest.update.UpdateManager updateManager = plugin.getUpdateManager();
        if (updateManager != null && updateManager.isUpdateAvailable()
                && player.hasPermission("CustomEnderChest.admin")) {
            Scheduler.runEntityTaskLater(player, () -> {
                if (player.isOnline()) {
                    updateManager.sendUpdateNotice(player);
                }
            }, 40L); // 2s delay so it lands after the join messages
        }

        // First-run setup: the first admin to join a fresh installation gets
        // the one-time mode chooser GUI (3s delay so it lands after everything)
        if (plugin.getFirstRunSetupManager() != null
                && plugin.getFirstRunSetupManager().shouldAutoShow(player)) {
            Scheduler.runEntityTaskLater(player, () -> {
                if (player.isOnline() && plugin.getFirstRunSetupManager().shouldAutoShow(player)) {
                    plugin.getFirstRunSetupManager().openSetupGui(player);
                }
            }, 60L);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getEnderChestManager().onPlayerQuit(event.getPlayer());
        // Clean up block tracking and close the physical block animation if it was left open
        Block enderChestBlock = playerEnderChestBlocks.remove(event.getPlayer().getUniqueId());
        if (enderChestBlock != null && plugin.getConfig().getBoolean("enderchest-options.play-block-animation", true)) {
            try {
                if (enderChestBlock.getType() == Material.ENDER_CHEST) {
                    org.bukkit.block.EnderChest chestBlock = (org.bukkit.block.EnderChest) enderChestBlock.getState();
                    chestBlock.close();
                    chestBlock.update();
                }
            } catch (Exception e) {
                debug.log("Failed to send enderchest close animation on quit: " + e.getMessage());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEnderChestInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null)
            return;
        if (event.getClickedBlock().getType() != Material.ENDER_CHEST)
            return;

        String interactionMode = plugin.config().getBlockInteractionMode();
        if (interactionMode.equals("vanilla"))
            return;

        // Vanished players (EssentialsX / Vanish plugins) stay silent: no chest,
        // no sound, no block animation. The interaction is cancelled.
        if (plugin.config().getBoolean("enderchest-options.ignore-vanished-players", true)
                && VanishChecker.isVanished(player)) {
            event.setCancelled(true);
            return;
        }

        // Do not open if player is sneaking and holding an item (allows block placement)
        if (player.isSneaking() && (!player.getInventory().getItemInMainHand().getType().isAir() || !player.getInventory().getItemInOffHand().getType().isAir())) {
            return;
        }

        // Cancel the event immediately to prevent vanilla enderchest GUI from opening
        event.setCancelled(true);

        // Check permission to open ender chest via block
        if (!hasBlockOpenPermission(player)) {
            player.sendMessage(plugin.getLocaleManager().getPrefixedComponent(player, "messages.no-permission-block-open"));
            plugin.getSoundHandler().playSound(player, "fail");
            return;
        }

        if (!player.isOnline()) {
            return;
        }
        if (isMigrating(player)) {
            return;
        }

        // Store the clicked block for animation tracking first so that the SoundHandler can detect if it's a block click
        Block clickedBlock = event.getClickedBlock();
        playerEnderChestBlocks.put(player.getUniqueId(), clickedBlock);

        // Config option: right-clicking an Ender Chest opens the vault
        // selector GUI instead of vault 1 while multi-vault is active.
        // The open is delayed by 1 tick: opening an inventory in the same
        // tick as the interact event desyncs the client (rubber-band/aim
        // snaps) and breaks the lid animation on rapid open/close.
        if (org.maiminhdung.customenderchest.utils.EnderChestUtils.shouldOpenSelectorOnBlockClick()) {
            Scheduler.runEntityTaskLater(player, () -> {
                if (!player.isOnline()) {
                    playerEnderChestBlocks.remove(player.getUniqueId());
                    return;
                }
                boolean opened = plugin.getVaultSelectorManager().openSelector(player);
                if (opened) {
                    playLidAnimation(clickedBlock, true);
                } else {
                    playerEnderChestBlocks.remove(player.getUniqueId());
                }
            }, 1L);
            return;
        }

        // Let the EnderChestManager handle the permission check logic and open the inventory.
        // We only animate the physical block lid open if the inventory opened successfully.
        if (plugin.getEnderChestManager().openEnderChest(player)) {
            playLidAnimation(clickedBlock, true);
        } else {
            // If opening failed (e.g. locked, loading, etc.), remove from block tracking
            playerEnderChestBlocks.remove(player.getUniqueId());
        }
    }

    /**
     * Plays the physical block lid animation (open or close) using the
     * Bukkit block state API, like VariableEnderChests does.
     */
    private void playLidAnimation(Block block, boolean open) {
        if (!plugin.getConfig().getBoolean("enderchest-options.play-block-animation", true)) {
            return;
        }
        try {
            if (block.getType() != Material.ENDER_CHEST) {
                return;
            }
            org.bukkit.block.EnderChest chestBlock = (org.bukkit.block.EnderChest) block.getState();
            if (open) {
                chestBlock.open();
            } else {
                chestBlock.close();
            }
            chestBlock.update();
        } catch (Exception e) {
            debug.log("Failed to send enderchest " + (open ? "open" : "close") + " animation: " + e.getMessage());
        }
    }

    /**
     * Checks whether the configured block interaction mode allows this player to
     * open the custom chest. Vanilla mode is handled before this method is called.
     */
    private boolean hasBlockOpenPermission(Player player) {
        if (plugin.config().getBlockInteractionMode().equals("custom")) {
            return true;
        }
        return player.isOp() || player.hasPermission("CustomEnderChest.block.open");
    }

    private boolean isMigrating(Player player) {
        if (plugin.getCommand("customenderchest") != null
                && plugin.getCommand("customenderchest").getExecutor() instanceof org.maiminhdung.customenderchest.commands.EnderChestCommand cmd) {
            if (cmd.getMigrationManager().isMigrating()) {
                player.sendMessage(plugin.getLocaleManager().getPrefixedComponent(player, "command.migrate-in-progress"));
                return true;
            }
        }
        return false;
    }

    // Handle inventory clicks to enforce slot restrictions and admin sync
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player))
            return;
        if (event.isCancelled())
            return;

        Inventory clickedInv = event.getInventory();

        // First-run setup GUI clicks are fully handled by the setup manager
        if (plugin.getFirstRunSetupManager() != null
                && plugin.getFirstRunSetupManager().isSetupInventory(clickedInv)) {
            plugin.getFirstRunSetupManager().handleClick(player, event);
            return;
        }

        // Vault selector GUI clicks are fully handled by the selector
        if (plugin.getVaultSelectorManager() != null
                && plugin.getVaultSelectorManager().isSelectorInventory(clickedInv)) {
            plugin.getVaultSelectorManager().handleClick(player, event);
            return;
        }

        // Handle overflow inventory clicks next
        if (plugin.getEnderChestManager().handleOverflowClick(player, clickedInv, event)) {
            return; // Was an overflow click, already handled
        }

        syncInventoryChange(player, clickedInv);
    }

    // Handle inventory drag events (shift-click, drag multiple items, etc.)
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player))
            return;
        if (event.isCancelled())
            return;

        // Drags inside the setup, selector or overflow GUIs must be cancelled
        if (plugin.getFirstRunSetupManager() != null
                && plugin.getFirstRunSetupManager().isSetupInventory(event.getInventory())) {
            event.setCancelled(true);
            return;
        }
        if (plugin.getVaultSelectorManager() != null
                && plugin.getVaultSelectorManager().isSelectorInventory(event.getInventory())) {
            event.setCancelled(true);
            return;
        }
        if (plugin.getEnderChestManager().getOverflowViews().containsKey(event.getInventory())) {
            event.setCancelled(true);
            return;
        }

        syncInventoryChange(player, event.getInventory());
    }

    /**
     * Syncs inventory changes bidirectionally between admin views and player
     * vault inventories
     */
    private void syncInventoryChange(Player player, Inventory clickedInv) {
        EnderChestManager manager = plugin.getEnderChestManager();

        // === CASE 1: Admin is viewing someone's vault ===
        VaultKey targetKey = manager.getAdminViewedChests().get(clickedInv);
        if (targetKey != null) {
            // Admin clicked in their view - sync changes to target player's live inventory
            Player targetPlayer = Bukkit.getPlayer(targetKey.playerId());
            if (targetPlayer != null && targetPlayer.isOnline()) {
                Scheduler.runTaskLater(() -> {
                    Inventory targetLiveInv = manager.getLoadedVault(targetKey.playerId(), targetKey.vaultId());
                    if (targetLiveInv != null) {
                        targetLiveInv.setContents(clickedInv.getContents());
                        debug.log("Admin->Player sync: " + player.getName() +
                                " modified " + targetPlayer.getName() + "'s vault " + targetKey.vaultId());
                    }
                }, 1L);
            }
            return;
        }

        // === CASE 2: Player clicked their own vault while admin is viewing ===
        Inventory playerLiveInv = manager.getLoadedVault(player.getUniqueId(),
                manager.getOpenVaults().getOrDefault(player.getUniqueId(), 1));
        if (playerLiveInv != null && clickedInv.equals(playerLiveInv)) {
            // Find if any admin is viewing this player's inventory
            for (var entry : manager.getAdminViewedChests().entrySet()) {
                if (entry.getValue().playerId().equals(player.getUniqueId())) {
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

        // Vault selector close - untrack and play the lid close animation
        // when the selector was opened by clicking an Ender Chest block
        if (plugin.getVaultSelectorManager() != null
                && plugin.getVaultSelectorManager().isSelectorInventory(closedInventory)) {
            plugin.getVaultSelectorManager().handleClose(closedInventory);
            Block enderChestBlock = playerEnderChestBlocks.remove(player.getUniqueId());
            if (enderChestBlock != null) {
                java.util.List<org.bukkit.entity.HumanEntity> viewers = new java.util.ArrayList<>(event.getViewers());
                viewers.remove(player);
                if (viewers.isEmpty()) {
                    playLidAnimation(enderChestBlock, false);
                }
            }
            plugin.getSoundHandler().playSound(player, "close");
            return;
        }

        // First-run setup close - untrack (a closed GUI without a choice can
        // be reopened via /<main> setup or the next admin join)
        if (plugin.getFirstRunSetupManager() != null
                && plugin.getFirstRunSetupManager().isSetupInventory(closedInventory)) {
            plugin.getFirstRunSetupManager().handleClose(closedInventory);
            plugin.getSoundHandler().playSound(player, "close");
            return;
        }

        // Overflow inventory close
        if (manager.getOverflowViews().containsKey(closedInventory)) {
            manager.handleOverflowClose(player, closedInventory);
            plugin.getSoundHandler().playSound(player, "close");
            return;
        }

        if (manager.getAdminViewedChests().containsKey(closedInventory)) {
            VaultKey targetKey = manager.getAdminViewedChests().remove(closedInventory);

            if (targetKey == null)
                return;

            DataLockManager dataLockManager = plugin.getDataLockManager();

            if (!dataLockManager.tryLock(targetKey)) {
                player.sendMessage(localeManager.getPrefixedComponent(player, "messages.data-still-loading"));
                return;
            }

            try {
                debug.log("Admin " + player.getName() + " finished editing " + targetKey + ". Saving data...");

                Player targetPlayer = Bukkit.getPlayer(targetKey.playerId());
                String targetName;

                if (targetPlayer != null && targetPlayer.isOnline()) {
                    // === ONLINE PLAYER - Sync and save ==
                    targetName = targetPlayer.getName();
                    Inventory targetLiveInv = manager.getLoadedVault(targetKey.playerId(), targetKey.vaultId());
                    if (targetLiveInv != null) {
                        targetLiveInv.setContents(closedInventory.getContents());
                        debug.log("Final sync completed for online player: " + targetName);
                    }
                } else {
                    // === OFFLINE PLAYER - Prepare name for saving ===
                    targetName = Bukkit.getOfflinePlayer(targetKey.playerId()).getName();
                }

                // Save to database async with timeout - DO NOT block!
                String finalTargetName = targetName;
                manager.saveVault(targetKey.playerId(), finalTargetName, targetKey.vaultId(), closedInventory)
                        .orTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .whenComplete((result, ex) -> {
                            if (ex != null) {
                                plugin.getLogger().warning(
                                        "Failed to save admin edit for " + finalTargetName + " vault "
                                                + targetKey.vaultId() + ": " + ex.getMessage());
                            } else {
                                debug.log("Data for player " + finalTargetName + " vault " + targetKey.vaultId()
                                        + " saved successfully by admin.");
                            }
                            // Unlock in whenComplete to guarantee it's called
                            dataLockManager.unlock(targetKey);
                        });

                plugin.getSoundHandler().playSound(player, "close");

            } catch (Exception e) {
                // In case of any unexpected error, make sure to unlock
                dataLockManager.unlock(targetKey);
                player.sendMessage(localeManager.getPrefixedComponent(player, "messages.save-error"));
                e.printStackTrace();
                EnderChest.trackError(e);
            }
            return;
        }

        // Own vault close: figure out which vault it was
        Integer openVaultId = manager.getOpenVaults().get(player.getUniqueId());
        if (openVaultId != null) {
            Inventory cachedInv = manager.getLoadedVault(player.getUniqueId(), openVaultId);
            if (cachedInv != null && closedInventory.equals(cachedInv)) {
                plugin.getSoundHandler().playSound(player, "close");

                // Instantly stop tracking this open inventory in real-time
                manager.getOpenVaults().remove(player.getUniqueId());

                // Send close animation if player opened via block click
                Block enderChestBlock = playerEnderChestBlocks.remove(player.getUniqueId());
                if (enderChestBlock != null) {
                    // Close the lid only when the last viewer closes (like VariableEnderChests)
                    // event.getViewers() still includes the closing player, so remove them first
                    java.util.List<org.bukkit.entity.HumanEntity> viewers = new java.util.ArrayList<>(event.getViewers());
                    viewers.remove(player);
                    if (viewers.isEmpty()) {
                        if (plugin.getConfig().getBoolean("enderchest-options.play-block-animation", true)) {
                            try {
                                org.bukkit.block.EnderChest chestBlock = (org.bukkit.block.EnderChest) enderChestBlock.getState();
                                chestBlock.close();
                                chestBlock.update();
                            } catch (Exception e) {
                                debug.log("Failed to send enderchest close animation: " + e.getMessage());
                            }
                        }
                    }
                }

                // Save data immediately when player closes their vault to prevent data
                // loss
                DataLockManager dataLockManager = plugin.getDataLockManager();
                VaultKey key = new VaultKey(player.getUniqueId(), openVaultId);

                // Lock to prevent double-save race conditions
                if (dataLockManager.tryLock(key)) {
                    debug.log("Player " + player.getName() + " closed vault " + openVaultId + ". Saving data...");

                    // Save asynchronously without blocking - let CompletableFuture handle it
                    manager.saveVault(player.getUniqueId(), player.getName(), openVaultId, closedInventory)
                            .whenComplete((result, ex) -> {
                                if (ex != null) {
                                    plugin.getLogger().severe("Failed to save vault " + openVaultId + " for "
                                            + player.getName() + " after closing inventory: " + ex.getMessage());
                                } else {
                                    debug.log("Vault " + openVaultId + " for " + player.getName()
                                            + " saved after closing inventory.");
                                }
                                dataLockManager.unlock(key);
                            });

                    // Developer API hook: notify listeners after the close save was scheduled
                    new EnderVaultCloseEvent(player, openVaultId, closedInventory).callEvent();
                }
            }
        }
    }
}
