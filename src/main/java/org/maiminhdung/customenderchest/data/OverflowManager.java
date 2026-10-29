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
     * Restarts expiration cleanup using the latest config values.
     */
    public void reloadTasks() {
        stopExpirationTask();
        reloadConfig();
        startExpirationTask();
    }

    /**
     * Reload config values. Call after the configured reload command.
     */
    public void reloadConfig() {
        this.expirationEnabled = plugin.config().getBoolean("overflow.expiration.enabled", false);

        int configuredExpirationDays = plugin.config().getInt("overflow.expiration.days", 30);
        this.expirationDays = Math.max(configuredExpirationDays, 1);
        if (configuredExpirationDays < 1) {
            plugin.getLogger().warning("overflow.expiration.days must be at least 1. Using 1 day.");
        }

        int checkMinutes = plugin.config().getInt("overflow.expiration.check-interval-minutes", 60);
        if (checkMinutes < 5) {
            plugin.getLogger().warning("overflow.expiration.check-interval-minutes must be at least 5. Using 5 minutes.");
        }
        this.checkIntervalTicks = Math.max(checkMinutes, 5) * 60L * 20L;

        this.loginWarningEnabled = plugin.config().getBoolean("overflow.login-warning.enabled", true);

        this.retrievalFeeEnabled = plugin.config().getBoolean("overflow.retrieval-fee.enabled", false);
        double configuredFlatFee = plugin.config().getDouble("overflow.retrieval-fee.flat-fee", 0);
        double configuredPerItemFee = plugin.config().getDouble("overflow.retrieval-fee.per-item-fee", 0);
        this.flatFee = Math.max(configuredFlatFee, 0);
        this.perItemFee = Math.max(configuredPerItemFee, 0);
        if (configuredFlatFee < 0 || configuredPerItemFee < 0) {
            plugin.getLogger().warning("Overflow retrieval fees cannot be negative. Negative values are treated as 0.");
        }
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
     * Runs per vault (each vault has its own overflow record and created_at).
     * Thread-safe.
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

                for (int vaultId : info.vaultIds) {
                    final int vid = vaultId;
                    // Check creation time first to avoid loading items if not expired
                    storage.getOverflowCreatedAt(info.playerUUID, vid).thenAccept(createdAt -> {
                        if (createdAt == null) {
                            return;
                        }

                        if (now - createdAt > expirationMillis) {
                            // Expired! First load to know the count, then clear
                            storage.loadOverflowItems(info.playerUUID, vid).thenAccept(overflowItems -> {
                                int count = (overflowItems != null) ? overflowItems.length : 0;
                                plugin.getEnderChestManager().queueStorageOperation(info.playerUUID,
                                        () -> storage.clearOverflowItems(info.playerUUID, vid)).thenRun(() -> {
                                    plugin.getLogger().info("[Overflow] Expired overflow for " + info.playerName
                                            + " (" + info.playerUUID + ") vault " + vid + " - " + count + " items removed.");

                                    // Notify online player
                                    Player onlinePlayer = Bukkit.getPlayer(info.playerUUID);
                                    if (onlinePlayer != null && onlinePlayer.isOnline()) {
                                        Scheduler.runEntityTask(onlinePlayer, () -> {
                                            LocaleManager locale = plugin.getLocaleManager();
                                            onlinePlayer.sendMessage(locale.getPrefixedComponent(onlinePlayer, 
                                                    "messages.overflow-expired",
                                                    Placeholder.unparsed("count", String.valueOf(count))));
                                        });
                                    }
                                }).exceptionally(ex -> {
                                    EnderChest.trackError(ex);
                                    return null;
                                });
                            }).exceptionally(ex -> {
                                EnderChest.trackError(ex);
                                return null;
                            });
                        }
                    }).exceptionally(ex -> {
                        EnderChest.trackError(ex);
                        return null;
                    });
                }

                // The per-vault operations run async; unlock after a short chain to
                // avoid holding the lock across vault loads. Clearing is queued
                // through the player's save chain, which serializes with saves.
                dataLockManager.unlock(info.playerUUID);
            }
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.WARNING, "[Overflow] Error during expiration cleanup", ex);
            EnderChest.trackError(ex);
            return null;
        });
    }

    /**
     * Send login warning to a player if they have overflow items in any vault.
     * Thread-safe: called from onPlayerJoin which is on entity thread.
     *
     * @param player The player who just joined
     */
    public void sendLoginWarning(Player player) {
        if (!loginWarningEnabled) return;
        if (player == null || !player.isOnline()) return;

        // Don't warn again if already warned this session
        if (warnedThisSession.contains(player.getUniqueId())) return;

        StorageInterface storage = plugin.getStorageManager().getStorage();
        UUID uuid = player.getUniqueId();

        storage.getVaultIds(uuid).thenAccept(vaultIds -> {
            int totalCount = 0;
            long earliestCreatedAt = Long.MAX_VALUE;

            for (int vaultId : vaultIds) {
                ItemStack[] items;
                try {
                    items = storage.loadOverflowItems(uuid, vaultId).join();
                } catch (Exception e) {
                    continue;
                }
                if (items == null || items.length == 0) continue;
                totalCount += items.length;

                if (expirationEnabled) {
                    try {
                        Long createdAt = storage.getOverflowCreatedAt(uuid, vaultId).join();
                        if (createdAt != null && createdAt < earliestCreatedAt) {
                            earliestCreatedAt = createdAt;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

            if (totalCount == 0) return;

            final int count = totalCount;
            final Long earliest = earliestCreatedAt == Long.MAX_VALUE ? null : earliestCreatedAt;

            Scheduler.runEntityTask(player, () -> {
                if (!player.isOnline()) return;

                LocaleManager locale = plugin.getLocaleManager();
                if (expirationEnabled && earliest != null) {
                    long expirationMillis = (long) expirationDays * 24 * 60 * 60 * 1000L;
                    long now = System.currentTimeMillis();
                    long elapsed = now - earliest;
                    long remaining = expirationMillis - elapsed;
                    int daysLeft = Math.max(1, (int) (remaining / (24 * 60 * 60 * 1000L)));

                    player.sendMessage(locale.getPrefixedComponent(player, 
                            "messages.overflow-login-warning-expiring",
                            Placeholder.unparsed("count", String.valueOf(count)),
                            Placeholder.unparsed("days", String.valueOf(daysLeft)),
                            Placeholder.unparsed("command", plugin.getPrimaryCommand() + " overflow")));
                } else {
                    player.sendMessage(locale.getPrefixedComponent(player, 
                            "messages.overflow-login-warning",
                            Placeholder.unparsed("count", String.valueOf(count)),
                            Placeholder.unparsed("command", plugin.getPrimaryCommand() + " overflow")));
                }
                warnedThisSession.add(player.getUniqueId());
            });
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
            player.sendMessage(locale.getPrefixedComponent(player, 
                    "messages.overflow-fee-insufficient",
                    Placeholder.unparsed("amount", vaultHandler.format(totalFee)),
                    Placeholder.unparsed("balance", vaultHandler.format(vaultHandler.getBalance(player)))));
            return false;
        }

        // Charge the fee
        boolean success = vaultHandler.withdraw(player, totalFee, "CustomEnderChest overflow retrieval");
        if (success) {
            LocaleManager locale = plugin.getLocaleManager();
            player.sendMessage(locale.getPrefixedComponent(player, 
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
