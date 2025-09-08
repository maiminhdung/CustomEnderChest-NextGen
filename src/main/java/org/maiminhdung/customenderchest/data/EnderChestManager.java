package org.maiminhdung.customenderchest.data;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.Getter;
import org.bukkit.inventory.ItemStack;
import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.Scheduler;
import org.maiminhdung.customenderchest.utils.DataLockManager;
import org.maiminhdung.customenderchest.utils.EnderChestUtils;
import org.maiminhdung.customenderchest.utils.SoundHandler;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class EnderChestManager {

    private final EnderChest plugin;
    private final SoundHandler soundHandler;
    private final DataLockManager dataLockManager;
    private final Cache<UUID, Inventory> liveData;
    private final Scheduler.Task autoSaveTask;

    @Getter
    private final Map<Inventory, UUID> adminViewedChests = new HashMap<>();

    public EnderChestManager(EnderChest plugin) {
        this.plugin = plugin;
        this.soundHandler = plugin.getSoundHandler();
        this.dataLockManager = plugin.getDataLockManager();

        // Use Guava Cache to automatically clean up data for players who have been offline for a while.
        this.liveData = CacheBuilder.newBuilder()
                .expireAfterAccess(30, TimeUnit.MINUTES)
                .build();

        // Start the auto-save task to prevent data loss on server crash.
        long autoSaveIntervalTicks = plugin.config().getInt("storage.auto-save-interval-seconds", 300) * 20L;
        if (autoSaveIntervalTicks > 0) {
            this.autoSaveTask = Scheduler.runTaskTimerAsync(
                    this::autoSaveAll,
                    autoSaveIntervalTicks,
                    autoSaveIntervalTicks
            );
        } else {
            this.autoSaveTask = null;
        }
    }
    // Load player data when they join the server.
    public void onPlayerJoin(Player player) {
        if (!dataLockManager.lock(player.getUniqueId())) {
            plugin.getDebugLogger().log("Attempted to load data for " + player.getName() + ", but their data is currently locked.");
            return;
        }

        plugin.getDebugLogger().log("Data lock acquired for " + player.getName() + ". Checking storage...");

        plugin.getStorageManager().getStorage().loadEnderChest(player.getUniqueId())
                .whenComplete((items, error) -> {
                    Scheduler.runEntityTask(player, () -> {
                        try {
                            if (error != null) {
                                plugin.getLogger().log(Level.SEVERE, "Failed to load data for " + player.getName(), error);
                                return;
                            }

                            int size = EnderChestUtils.getSize(player);
                            Component title = EnderChestUtils.getTitle(player);
                            Inventory inv = Bukkit.createInventory(player, (size > 0 ? size : 9), title);

                            if (items != null && size > 0) {
                                plugin.getDebugLogger().log("Data found for " + player.getName() + ". Populating inventory.");
                                if (items.length <= size) {
                                    inv.setContents(items);
                                } else {
                                    for (int i = 0; i < size; i++) {
                                        inv.setItem(i, items[i]);
                                    }
                                }
                            } else {
                                plugin.getDebugLogger().log("No data found for " + player.getName() + ". Cache initialized for new player.");
                            }

                            liveData.put(player.getUniqueId(), inv);
                            plugin.getDebugLogger().log("Cache is ready for " + player.getName());

                        } finally {
                            dataLockManager.unlock(player.getUniqueId());
                            plugin.getDebugLogger().log("Data lock released for " + player.getName());
                        }
                    });
                });
    }
    // Save player data when they leave the server.
    public void onPlayerQuit(Player player) {
        if (!dataLockManager.lock(player.getUniqueId())) {
            plugin.getDebugLogger().log("Player " + player.getName() + " quit, but data is locked. Skipping quit-save.");
            return;
        }

        plugin.getDebugLogger().log("Player " + player.getName() + " quit. Data lock acquired for saving.");
        Inventory inv = liveData.getIfPresent(player.getUniqueId());
        if (inv != null) {
            saveEnderChest(player.getUniqueId(), player.getName(), inv).whenComplete((v, ex) -> {
                liveData.invalidate(player.getUniqueId());
                dataLockManager.unlock(player.getUniqueId());
                plugin.getDebugLogger().log("Quit-save for " + player.getName() + " complete. Lock released.");
            });
        } else {
            dataLockManager.unlock(player.getUniqueId());
        }
    }

    public void shutdown() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
        }
        plugin.getLogger().info("Auto-save task cancelled. Saving all cached player data before shutting down...");
        shutdownSave().join();
        plugin.getLogger().info("All player data has been saved successfully.");
    }
    // Open the ender chest for the player, loading data if necessary.
    public void openEnderChest(Player player) {
        Inventory inv = getLoadedEnderChest(player.getUniqueId());
        if (inv == null) {
            player.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.data-loading"));
            onPlayerJoin(player);
            return;
        }

        int newSize = EnderChestUtils.getSize(player);
        if (inv.getSize() != newSize) {
            inv = resizeInventory(player, inv, newSize);
            liveData.put(player.getUniqueId(), inv);
        }

        player.openInventory(inv);
        soundHandler.playSound(player, "open");
    }
    // Resize the inventory while preserving existing items.
    private Inventory resizeInventory(Player player, Inventory oldInv, int newSize) {
        plugin.getDebugLogger().log("Resizing " + player.getName() + "'s inventory from " + oldInv.getSize() + " to " + newSize);
        Component title = EnderChestUtils.getTitle(player);
        Inventory newInv = Bukkit.createInventory(player, newSize, title);

        ItemStack[] contents = oldInv.getContents();
        for (int i = 0; i < Math.min(contents.length, newSize); i++) {
            newInv.setItem(i, contents[i]);
        }
        return newInv;
    }
    // Force-save all cached data during server shutdown to prevent data loss.
    private CompletableFuture<Void> shutdownSave() {
        Set<Map.Entry<UUID, Inventory>> cacheSnapshot = new java.util.HashSet<>(liveData.asMap().entrySet());

        if (cacheSnapshot.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        plugin.getLogger().info("Force-saving data for " + cacheSnapshot.size() + " players...");

        CompletableFuture<?>[] futures = cacheSnapshot.stream()
                .map(entry -> {
                    UUID uuid = entry.getKey();
                    Player p = Bukkit.getPlayer(uuid);
                    String name = (p != null) ? p.getName() : Bukkit.getOfflinePlayer(uuid).getName();

                    // Even if data is locked, we force save it during shutdown to prevent data loss.
                    return saveEnderChest(uuid, name, entry.getValue());
                })
                .filter(Objects::nonNull)
                .toArray(CompletableFuture[]::new);

        return CompletableFuture.allOf(futures);
    }
    // Force-save all cached data during server shutdown to prevent data loss.
    private CompletableFuture<Void> autoSaveAll() {
        Set<Map.Entry<UUID, Inventory>> cacheSnapshot = new java.util.HashSet<>(liveData.asMap().entrySet());
        if (cacheSnapshot.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        plugin.getDebugLogger().log("Auto-saving data for " + cacheSnapshot.size() + " online players...");
        CompletableFuture<?>[] futures = cacheSnapshot.stream()
                .map(entry -> {
                    UUID uuid = entry.getKey();
                    if (dataLockManager.isLocked(uuid)) {
                        return null;
                    }
                    Player p = Bukkit.getPlayer(uuid);
                    String name = (p != null) ? p.getName() : null;
                    return saveEnderChest(uuid, name, entry.getValue());
                })
                .filter(Objects::nonNull)
                .toArray(CompletableFuture[]::new);

        return CompletableFuture.allOf(futures);
    }
    // Save ender chest data with inventory object, used for online players.
    public CompletableFuture<Void> saveEnderChest(UUID uuid, String playerName, Inventory inv) {
        return plugin.getStorageManager().getStorage()
                .saveEnderChest(uuid, playerName, inv.getSize(), inv.getContents());
    }
    // Save ender chest data with specified size and items, used for offline players.
    public CompletableFuture<Void> saveEnderChest(UUID uuid, String playerName, int size, ItemStack[] items) {
        return plugin.getStorageManager().getStorage()
                .saveEnderChest(uuid, playerName, size, items);
    }
    // Get the cached inventory for a player, or null if not loaded.
    public Inventory getLoadedEnderChest(UUID uuid) {
        return liveData.getIfPresent(uuid);
    }
    // Reload the cache for a player, useful when their ender chest size or title changes.
    public void reloadCacheFor(Player player) {
        int size = EnderChestUtils.getSize(player);
        if (size == 0) {
            liveData.invalidate(player.getUniqueId());
            return;
        }
        Component title = EnderChestUtils.getTitle(player);
        Inventory newInv = Bukkit.createInventory(player, size, title);
        liveData.put(player.getUniqueId(), newInv);
        plugin.getDebugLogger().log("Cache reloaded for player " + player.getName());
    }
}