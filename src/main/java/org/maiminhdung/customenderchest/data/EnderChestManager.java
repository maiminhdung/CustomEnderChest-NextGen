package org.maiminhdung.customenderchest.data;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.bukkit.inventory.ItemStack;
import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.Scheduler;
import org.maiminhdung.customenderchest.utils.DataLockManager;
import org.maiminhdung.customenderchest.utils.DebugLogger;
import org.maiminhdung.customenderchest.utils.EnderChestUtils;
import org.maiminhdung.customenderchest.utils.SoundHandler;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class EnderChestManager {

    private final EnderChest plugin;
    private final SoundHandler soundHandler;
    private final DataLockManager dataLockManager;
    private final Cache<UUID, Inventory> liveData;
    private final Scheduler.Task autoSaveTask;
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

    public void onPlayerJoin(Player player) {

        plugin.getDebugLogger().log("Player " + player.getName() + " is joining. Starting to load data...");

        if (!dataLockManager.lock(player.getUniqueId())) {
            plugin.getDebugLogger().log("Attempted to load data for " + player.getName() + ", but their data is currently locked. Will retry on next interaction.");
            return;
        }

        plugin.getDebugLogger().log("Data lock acquired for " + player.getName() + ". Loading...");
        Scheduler.supplyAsync(() -> {
            try {
                return plugin.getStorageManager().getStorage().loadEnderChest(player.getUniqueId()).join();
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load data for " + player.getName(), e);
                return null;
            }
        }).thenAccept(items -> {
            Scheduler.runEntityTask(player, () -> {
                try {
                    int size = EnderChestUtils.getSize(player);
                    if (size == 0) {
                        plugin.getDebugLogger().log(player.getName() + " has no permission. Creating empty cache entry.");
                    }
                    Component title = EnderChestUtils.getTitle(player);
                    // If size = 0 then create size inventory = 9.
                    Inventory inv = Bukkit.createInventory(player, (size > 0 ? size : 9), title);

                    if (items != null && size > 0) {
                        if (items.length <= size) {
                            inv.setContents(items);
                        } else {
                            for (int i = 0; i < size; i++) {
                                inv.setItem(i, items[i]);
                            }
                        }
                        liveData.put(player.getUniqueId(), inv);
                    }
                } finally {
                    dataLockManager.unlock(player.getUniqueId());
                    plugin.getDebugLogger().log("Data lock released for " + player.getName());
                }
            });
        });
    }

    public void onPlayerQuit(Player player) {
        if (!dataLockManager.lock(player.getUniqueId())) {
            plugin.getDebugLogger().log("Player " + player.getName() + " quit, but data is locked (likely being saved). Skipping quit-save.");
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

    public void openEnderChest(Player player) {
        int size = EnderChestUtils.getSize(player);
        if (size == 0) {
            player.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.no-permission"));
            soundHandler.playSound(player, "fail");
            return;
        }

        Inventory inv = liveData.getIfPresent(player.getUniqueId());
        if (inv == null) {
            player.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.data-still-loading"));
            return;
        }

        if (inv.getSize() != size) {
            inv = resizeInventory(inv, size, player);
            liveData.put(player.getUniqueId(), inv);
        }

        player.openInventory(inv);
        plugin.getSoundHandler().playSound(player, "open");
    }

    private Inventory resizeInventory(Inventory oldInv, int newSize, Player holder) {
        Component title = EnderChestUtils.getTitle(holder);
        Inventory newInv = Bukkit.createInventory(holder, newSize, title);
        int copyLimit = Math.min(oldInv.getSize(), newSize);
        for (int i = 0; i < copyLimit; i++) {
            newInv.setItem(i, oldInv.getItem(i));
        }
        return newInv;
    }

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

    public CompletableFuture<Void> saveEnderChest(UUID uuid, String playerName, Inventory inv) {
        return plugin.getStorageManager().getStorage()
                .saveEnderChest(uuid, playerName, inv.getSize(), inv.getContents());
    }

    public CompletableFuture<Void> saveEnderChest(UUID uuid, String playerName, int size, ItemStack[] items) {
        return plugin.getStorageManager().getStorage()
                .saveEnderChest(uuid, playerName, size, items);
    }

    public Inventory getLoadedEnderChest(UUID uuid) {
        return liveData.getIfPresent(uuid);
    }

    public void shutdown() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
        }

        plugin.getLogger().info("Auto-save task cancelled. Saving all online players' data...");
        autoSaveAll().join();
        plugin.getLogger().info("All player data has been saved.");
    }

    private CompletableFuture<Void> autoSaveAll() {
        Set<Map.Entry<UUID, Inventory>> cacheSnapshot = new java.util.HashSet<>(liveData.asMap().entrySet());

        if (cacheSnapshot.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        this.plugin.getDebugLogger().log("Auto-saving data for " + cacheSnapshot.size() + " online players...");

        CompletableFuture<?>[] futures = cacheSnapshot.stream()
                .map(entry -> {
                    UUID uuid = entry.getKey();
                    Inventory inv = entry.getValue();
                    Player p = Bukkit.getPlayer(uuid);
                    String name = (p != null) ? p.getName() : null;
                    return saveEnderChest(uuid, name, inv);
                })
                .toArray(CompletableFuture[]::new);

        return CompletableFuture.allOf(futures);
    }

    public Map<Inventory, UUID> getAdminViewedChests() {
        return adminViewedChests;
    }
}