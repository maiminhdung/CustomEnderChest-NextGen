package org.maiminhdung.customenderchest.data;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.bukkit.inventory.ItemStack;
import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.Scheduler;
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

public class EnderChestManager {

    private final EnderChest plugin;
    private final SoundHandler soundHandler;
    private final Cache<UUID, Inventory> liveData;
    private final Scheduler.Task autoSaveTask;
    private final Map<Inventory, UUID> adminViewedChests = new HashMap<>();
    private final DebugLogger debug;

    public EnderChestManager(EnderChest plugin) {
        this.plugin = plugin;
        this.soundHandler = plugin.getSoundHandler();
        this.debug = plugin.getDebugLogger();

        this.liveData = CacheBuilder.newBuilder()
                .expireAfterAccess(30, TimeUnit.MINUTES)
                .build();

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
        debug.log("Player " + player.getName() + " is joining. Starting to load data...");
        plugin.getStorageManager().getStorage().loadEnderChest(player.getUniqueId())
                .thenAccept(items -> {
                    Scheduler.runEntityTask(player, () -> {
                        int size = EnderChestUtils.getSize(player);
                        if (size == 0) {
                            return;
                        }
                        Component title = EnderChestUtils.getTitle(player);
                        Inventory inv = Bukkit.createInventory(player, size, title);

                        if (items != null && items.length > 0) {
                            if (items.length <= size) {
                                inv.setContents(items);
                            } else {
                                for (int i = 0; i < size; i++) {
                                    inv.setItem(i, items[i]);
                                }
                            }
                        }
                        liveData.put(player.getUniqueId(), inv);
                        debug.log("Data for " + player.getName() + " loaded into cache. Size: " + size);
                    });
                });
    }

    public void onPlayerQuit(Player player) {
        Inventory inv = liveData.getIfPresent(player.getUniqueId());
        if (inv != null) {
            debug.log("Player " + player.getName() + " is quitting. Saving data...");
            saveEnderChest(player.getUniqueId(), player.getName(), inv)
                    .thenRun(() -> {
                        liveData.invalidate(player.getUniqueId());
                        debug.log("Data for " + player.getName() + " saved and removed from cache.");
                    });
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

    public void clearCacheFor(UUID uuid) {
        Inventory inv = liveData.getIfPresent(uuid);
        if (inv != null) {
            // Clear the inventory contents
            inv.clear();
        }
        // Clear the cache entry
        liveData.invalidate(uuid);
        debug.log("Cache cleared for UUID: " + uuid);
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

        debug.log("Auto-saving data for " + cacheSnapshot.size() + " online players...");

        CompletableFuture<?>[] futures = cacheSnapshot.stream()
                .map(entry -> {
                    UUID uuid = entry.getKey();
                    Inventory inv = entry.getValue();
                    // Use Bukkit API to get player name. May return null if player is offline.
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