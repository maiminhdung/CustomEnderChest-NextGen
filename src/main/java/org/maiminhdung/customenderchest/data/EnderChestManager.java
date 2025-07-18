package org.maiminhdung.customenderchest.data;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.Scheduler;
import org.maiminhdung.customenderchest.utils.EnderChestUtils;
import org.maiminhdung.customenderchest.utils.SoundHandler;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class EnderChestManager {

    private final EnderChest plugin;
    private final SoundHandler soundHandler;
    private final Cache<UUID, Inventory> liveData;
    private final Scheduler.Task autoSaveTask;

    public EnderChestManager(EnderChest plugin) {
        this.plugin = plugin;
        this.soundHandler = plugin.getSoundHandler();

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
        plugin.getStorageManager().getStorage().loadEnderChest(player.getUniqueId())
                .thenAccept(items -> {
                    Scheduler.runEntityTask(player, () -> {
                        int size = EnderChestUtils.getSize(player);
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
                    });
                });
    }

    public void onPlayerQuit(Player player) {
        Inventory inv = liveData.getIfPresent(player.getUniqueId());
        if (inv != null) {
            saveEnderChest(player.getUniqueId(), player.getName(), inv);
        }
        liveData.invalidate(player.getUniqueId());
    }

    public void openEnderChest(Player player) {
        Inventory inv = liveData.getIfPresent(player.getUniqueId());
        if (inv == null) {
            player.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.data-still-loading"));
            return;
        }

        int currentSize = inv.getSize();
        int newSize = EnderChestUtils.getSize(player);

        if (currentSize != newSize) {
            inv = resizeInventory(inv, newSize, player);
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

    public CompletableFuture<Void> saveEnderChest(UUID uuid, String playerName, Inventory inv) {
        return plugin.getStorageManager().getStorage()
                .saveEnderChest(uuid, playerName, inv.getSize(), inv.getContents());
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
        if (liveData.asMap().isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<?>[] futures = liveData.asMap().entrySet().stream()
                .map(entry -> {
                    UUID uuid = entry.getKey();
                    Player p = Bukkit.getPlayer(uuid);
                    String name = (p != null) ? p.getName() : null;
                    return saveEnderChest(uuid, name, entry.getValue());
                })
                .toArray(CompletableFuture[]::new);

        return CompletableFuture.allOf(futures);
    }
}