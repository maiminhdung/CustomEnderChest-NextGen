package org.maiminhdung.customenderchest.data;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.maiminhdung.customenderchest.EnderChest;

import org.maiminhdung.customenderchest.Scheduler;
import org.maiminhdung.customenderchest.locale.LocaleManager;
import org.maiminhdung.customenderchest.storage.StorageInterface;
import org.maiminhdung.customenderchest.utils.DataLockManager;
import org.maiminhdung.customenderchest.utils.VaultHandler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Manages overflow item expiration and retrieval fees.
 * Thread-safe: uses DataLockManager for player data operations,
 * and schedules main-thread tasks for Vault economy calls.
 */
public class OverflowManager {

    private final EnderChest plugin;
    private final DataLockManager dataLockManager;
    private final VaultHandler vaultHandler;
    private Scheduler.Task expirationTask;

    // Config values (cached on reload)
    private boolean expirationEnabled;
    private int expirationDays;
    private long checkIntervalTicks;

    private boolean loginWarningEnabled;

    private boolean retrievalFeeEnabled;
    private double flatFee;
    private double perItemFee;

    // Track players already warned this session to avoid spam
    private final Set<UUID> warnedThisSession = ConcurrentHashMap.newKeySet();

    public OverflowManager(EnderChest plugin) {
        this.plugin = plugin;
        this.dataLockManager = plugin.getDataLockManager();
        this.vaultHandler = plugin.getVaultHandler();
        reloadConfig();
    }

    /**
     * Reload config values. Call after the configured reload command.
     */
    public void reloadConfig() {
        this.expirationEnabled = plugin.config().getBoolean("overflow.expiration.enabled", false);
        this.expirationDays = plugin.config().getInt("overflow.expiration.days", 30);
        int checkMinutes = plugin.config().getInt("overflow.expiration.check-interval-minutes", 60);
        this.checkIntervalTicks = Math.max(checkMinutes, 5) * 60L * 20L; // Min 5 minutes

        this.loginWarningEnabled = plugin.config().getBoolean("overflow.login-warning.enabled", true);

        this.retrievalFeeEnabled = plugin.config().getBoolean("overflow.retrieval-fee.enabled", false);
        this.flatFee = plugin.config().getDouble("overflow.retrieval-fee.flat-fee", 0);
        this.perItemFee = plugin.config().getDouble("overflow.retrieval-fee.per-item-fee", 0);
    }

    /**
     * Start the expiration cleanup task. Call during plugin enable.
     */
    public void startExpirationTask() {
        if (!expirationEnabled) {
            plugin.getDebugLogger().log("Overflow expiration is disabled.");
            return;
        }

        // Run first check after 5 minutes, then every interval
        this.expirationTask = Scheduler.runTaskTimerAsync(
                this::cleanupExpiredOverflow,
                5 * 60L * 20L, // Initial delay: 5 minutes
                checkIntervalTicks);

        plugin.getLogger().info("Overflow expiration task started. Days: " + expirationDays
                + ", Check interval: " + (checkIntervalTicks / 20 / 60) + " minutes");
    }

    /**
     * Stop the expiration cleanup task. Call during plugin disable.
     */
    public void stopExpirationTask() {
        if (expirationTask != null) {
            expirationTask.cancel();
            expirationTask = null;
        }
    }

    /**
     * Check if overflow items have expired for all players and delete them.
     * Runs on async thread. Thread-safe.
     */
    private void cleanupExpiredOverflow() {
        StorageInterface storage = plugin.getStorageManager().getStorage();
        long expirationMillis = (long) expirationDays * 24 * 60 * 60 * 1000L;
        long now = System.currentTimeMillis();

        storage.getPlayersWithItems().thenAccept(players -> {
            for (StorageInterface.PlayerDataInfo info : players) {
                if (!info.hasOverflow) continue;

                // Try to acquire lock to prevent race conditions during join/save
                if (!dataLockManager.tryLock(info.playerUUID)) {
                    plugin.getDebugLogger().log("[Overflow] Skipping cleanup for " + info.playerName + " (data locked)");
                    continue;
                }

                // Check creation time first to avoid loading all items if not expired
                getOverflowCreatedAt(info.playerUUID).thenAccept(createdAt -> {
                    if (createdAt == null) {
                        dataLockManager.unlock(info.playerUUID);
                        return;
                    }

                    if (now - createdAt > expirationMillis) {
                        // Expired! First load to know the count, then clear
                        storage.loadOverflowItems(info.playerUUID).thenAccept(overflowItems -> {
                            int count = (overflowItems != null) ? overflowItems.length : 0;
                            plugin.getEnderChestManager().queueStorageOperation(info.playerUUID,
                                    () -> storage.clearOverflowItems(info.playerUUID)).thenRun(() -> {
                                plugin.getLogger().info("[Overflow] Expired overflow for " + info.playerName
                                        + " (" + info.playerUUID + ") - " + count + " items removed.");

                                // Notify online player
                                Player onlinePlayer = Bukkit.getPlayer(info.playerUUID);
                                if (onlinePlayer != null && onlinePlayer.isOnline()) {
                                    Scheduler.runEntityTask(onlinePlayer, () -> {
                                        LocaleManager locale = plugin.getLocaleManager();
                                        onlinePlayer.sendMessage(locale.getPrefixedComponent(
                                                "messages.overflow-expired",
                                                Placeholder.unparsed("count", String.valueOf(count))));
                                    });
                                }
                                dataLockManager.unlock(info.playerUUID);
                            }).exceptionally(ex -> {
                                dataLockManager.unlock(info.playerUUID);
                                EnderChest.trackError(ex);
                                return null;
                            });
                        }).exceptionally(ex -> {
                            dataLockManager.unlock(info.playerUUID);
                            EnderChest.trackError(ex);
                            return null;
                        });
                    } else {
                        // Not expired
                        dataLockManager.unlock(info.playerUUID);
                    }
                }).exceptionally(ex -> {
                    dataLockManager.unlock(info.playerUUID);
                    EnderChest.trackError(ex);
                    return null;
                });
            }
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.WARNING, "[Overflow] Error during expiration cleanup", ex);
            EnderChest.trackError(ex);
            return null;
        });
    }

    /**
     * Get the overflow creation timestamp for a player.
     * This queries the storage layer for the created_at field.
     *
     * @param playerUUID The player's UUID
     * @return The creation timestamp in millis, or null if not found
     */
    private CompletableFuture<Long> getOverflowCreatedAt(UUID playerUUID) {
        return plugin.getStorageManager().getStorage().getOverflowCreatedAt(playerUUID);
    }

    /**
     * Send login warning to a player if they have overflow items.
     * Thread-safe: called from onPlayerJoin which is on entity thread.
     *
     * @param player The player who just joined
     */
    public void sendLoginWarning(Player player) {
        if (!loginWarningEnabled) return;
        if (player == null || !player.isOnline()) return;

        // Don't warn again if already warned this session
        if (warnedThisSession.contains(player.getUniqueId())) return;

        plugin.getStorageManager().getStorage().loadOverflowItems(player.getUniqueId())
                .thenAccept(overflowItems -> {
                    if (overflowItems == null || overflowItems.length == 0) return;

                    int count = overflowItems.length;

                    if (expirationEnabled) {
                        // Check how many days left
                        getOverflowCreatedAt(player.getUniqueId()).thenAccept(createdAt -> {
                            Scheduler.runEntityTask(player, () -> {
                                if (!player.isOnline()) return;

                                LocaleManager locale = plugin.getLocaleManager();
                                if (createdAt != null) {
                                    long expirationMillis = (long) expirationDays * 24 * 60 * 60 * 1000L;
                                    long now = System.currentTimeMillis();
                                    long elapsed = now - createdAt;
                                    long remaining = expirationMillis - elapsed;
                                    int daysLeft = Math.max(1, (int) (remaining / (24 * 60 * 60 * 1000L)));

                                    player.sendMessage(locale.getPrefixedComponent(
                                            "messages.overflow-login-warning-expiring",
                                            Placeholder.unparsed("count", String.valueOf(count)),
                                            Placeholder.unparsed("days", String.valueOf(daysLeft)),
                                            Placeholder.unparsed("command", plugin.getPrimaryCommand() + " overflow")));
                                } else {
                                    // No timestamp, just generic warning
                                    player.sendMessage(locale.getPrefixedComponent(
                                            "messages.overflow-login-warning",
                                            Placeholder.unparsed("count", String.valueOf(count)),
                                            Placeholder.unparsed("command", plugin.getPrimaryCommand() + " overflow")));
                                }
                                warnedThisSession.add(player.getUniqueId());
                            });
                        });
                    } else {
                        // No expiration, just generic warning
                        Scheduler.runEntityTask(player, () -> {
                            if (!player.isOnline()) return;

                            LocaleManager locale = plugin.getLocaleManager();
                            player.sendMessage(locale.getPrefixedComponent(
                                    "messages.overflow-login-warning",
                                    Placeholder.unparsed("count", String.valueOf(count)),
                                    Placeholder.unparsed("command", plugin.getPrimaryCommand() + " overflow")));
                            warnedThisSession.add(player.getUniqueId());
                        });
                    }
                });
    }

    /**
     * Clear the warned-this-session flag for a player (on quit).
     *
     * @param uuid The player's UUID
     */
    public void clearWarnedFlag(UUID uuid) {
        warnedThisSession.remove(uuid);
    }

    /**
     * Calculate the total retrieval fee for a given number of overflow items.
     *
     * @param itemCount Number of items in overflow
     * @return Total fee (flat + per-item * count)
     */
    public double calculateFee(int itemCount) {
        if (!retrievalFeeEnabled) return 0;
        if (itemCount <= 0) return 0;
        return flatFee + (perItemFee * itemCount);
    }

    /**
     * Check if retrieval fees are enabled and Vault is available.
     *
     * @return true if fees should be charged
     */
    public boolean shouldChargeFee() {
        return retrievalFeeEnabled && vaultHandler.isEnabled();
    }

    /**
     * Charge a retrieval fee to a player. MUST be called on the main thread.
     *
     * @param player    The player to charge
     * @param itemCount Number of items being retrieved
     * @return true if the fee was charged successfully (or no fee needed), false if insufficient funds
     */
    public boolean chargeRetrievalFee(Player player, int itemCount) {
        if (!shouldChargeFee()) return true; // No fee, allow

        double totalFee = calculateFee(itemCount);
        if (totalFee <= 0) return true; // Free

        if (!vaultHandler.canAfford(player, totalFee)) {
            // Insufficient funds
            LocaleManager locale = plugin.getLocaleManager();
            player.sendMessage(locale.getPrefixedComponent(
                    "messages.overflow-fee-insufficient",
                    Placeholder.unparsed("amount", vaultHandler.format(totalFee)),
                    Placeholder.unparsed("balance", vaultHandler.format(vaultHandler.getBalance(player)))));
            return false;
        }

        // Charge the fee
        boolean success = vaultHandler.withdraw(player, totalFee, "CustomEnderChest overflow retrieval");
        if (success) {
            LocaleManager locale = plugin.getLocaleManager();
            player.sendMessage(locale.getPrefixedComponent(
                    "messages.overflow-fee-charged",
                    Placeholder.unparsed("amount", vaultHandler.format(totalFee)),
                    Placeholder.unparsed("count", String.valueOf(itemCount))));
        }
        return success;
    }

    // Getters for config state
    public boolean isExpirationEnabled() { return expirationEnabled; }
    public int getExpirationDays() { return expirationDays; }
    public boolean isRetrievalFeeEnabled() { return retrievalFeeEnabled; }
    public double getFlatFee() { return flatFee; }
    public double getPerItemFee() { return perItemFee; }
}
