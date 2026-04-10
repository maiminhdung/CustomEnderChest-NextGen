package org.maiminhdung.customenderchest.storage.migrate;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.Scheduler;
import org.maiminhdung.customenderchest.storage.StorageInterface;
import org.maiminhdung.customenderchest.utils.DataLockManager;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

public abstract class AbstractMigrator implements Migrator {
    protected final EnderChest plugin;
    protected final StorageInterface sourceStorage;
    protected final StorageInterface targetStorage;

    public AbstractMigrator(EnderChest plugin, StorageInterface sourceStorage, StorageInterface targetStorage) {
        this.plugin = plugin;
        this.sourceStorage = sourceStorage;
        this.targetStorage = targetStorage;
    }

    @Override
    public CompletableFuture<Void> migrate(CommandSender sender) {
        sendMsg(sender, plugin.getLocaleManager().getPrefixedComponent(
                "command.migrate-start",
                Placeholder.unparsed("source", getSourceName()),
                Placeholder.unparsed("target", getTargetName())));
        plugin.getLogger().info("Started migration from " + getSourceName() + " to " + getTargetName());

        return sourceStorage.getPlayersWithItems().thenComposeAsync(players -> {
            if (players == null || players.isEmpty()) {
                sendMsg(sender, plugin.getLocaleManager().getPrefixedComponent("command.migrate-no-data"));
                return CompletableFuture.completedFuture(null);
            }
            int total = players.size();
            AtomicInteger count = new AtomicInteger(0);
            AtomicInteger failed = new AtomicInteger(0);
            AtomicInteger skipped = new AtomicInteger(0);

            CompletableFuture<?> future = CompletableFuture.completedFuture(null);
            for (StorageInterface.PlayerDataInfo player : players) {
                future = future.thenCompose(v -> migratePlayer(player.playerUUID, player.playerName, player.chestSize))
                        .handle((res, ex) -> {
                            if (ex != null) {
                                failed.incrementAndGet();
                                plugin.getLogger().warning(
                                        "Failed to migrate data for " + player.playerName + ": " + ex.getMessage());
                            } else if (Boolean.FALSE.equals(res)) {
                                skipped.incrementAndGet();
                            } else {
                                int current = count.incrementAndGet();
                                if (current % 50 == 0 || current == total) {
                                    plugin.getLogger().info("Migration progress: " + current + "/" + total + "...");
                                }
                            }
                            return null;
                        });
            }
            return future.thenAccept(v -> {
                int skippedCount = skipped.get();
                sendMsg(sender, plugin.getLocaleManager().getPrefixedComponent(
                        "command.migrate-success",
                        Placeholder.unparsed("success", String.valueOf(count.get())),
                        Placeholder.unparsed("total", String.valueOf(total)),
                        Placeholder.unparsed("failed", String.valueOf(failed.get()))));
                sendMsg(sender, plugin.getLocaleManager().getPrefixedComponent("command.migrate-note"));
                plugin.getLogger().info("Migration finished. Success: " + count.get()
                        + ", Failed: " + failed.get()
                        + ", Skipped: " + skippedCount);
            });
        });
    }

    /**
     * Migrate a single player's data from source to target storage.
     * Returns Boolean.TRUE on success, Boolean.FALSE if skipped.
     */
    private CompletableFuture<Boolean> migratePlayer(java.util.UUID uuid, String name, int chestSize) {
        DataLockManager lockManager = plugin.getDataLockManager();
        if (!lockManager.tryLock(uuid)) {
            plugin.getLogger().warning(
                    "Skipping migration for " + name + " (" + uuid + ") as their data is currently locked/busy.");
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }

        // Auto-save if online to ensure latest data is in sourceStorage before reading
        // IMPORTANT: Save directly to sourceStorage, NOT the main plugin storage
        CompletableFuture<Void> prepareFuture = CompletableFuture.completedFuture(null);
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            org.bukkit.inventory.Inventory cachedInv = plugin.getEnderChestManager().getLoadedEnderChest(uuid);
            if (cachedInv != null) {
                prepareFuture = sourceStorage.saveEnderChest(uuid, name, chestSize, cachedInv.getContents());
            }
            // If cache is null, skip auto-save - sourceStorage already has the latest data on disk
        }

        return prepareFuture.thenCompose(v -> sourceStorage.loadEnderChest(uuid)).thenCompose(items -> {
            if (items == null || items.length == 0) {
                plugin.getLogger().info("No data to migrate for " + name + " (" + uuid + "), skipping.");
                return CompletableFuture.completedFuture(Boolean.FALSE);
            }
            return targetStorage.saveEnderChest(uuid, name, chestSize, items).thenCompose(v -> {
                return sourceStorage.loadOverflowItems(uuid).thenCompose(overflow -> {
                    if (overflow == null || overflow.length == 0)
                        return CompletableFuture.completedFuture(null);
                    return targetStorage.saveOverflowItems(uuid, overflow);
                });
            }).thenApply(v -> Boolean.TRUE);
        }).whenComplete((res, ex) -> {
            lockManager.unlock(uuid);
        });
    }

    private void sendMsg(CommandSender sender, Component msg) {
        Scheduler.runTask(() -> {
            sender.sendMessage(msg);
        });
    }
}
