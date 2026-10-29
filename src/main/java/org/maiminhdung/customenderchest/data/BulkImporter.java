package org.maiminhdung.customenderchest.data;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.Scheduler;
import org.maiminhdung.customenderchest.locale.LocaleManager;
import org.maiminhdung.customenderchest.utils.EnderChestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles bulk import of vanilla enderchest data for all players.
 * Online players are processed via Bukkit API, offline players via NBTAPI (if available).
 */
public class BulkImporter {

    private final EnderChest plugin;
    private final LocaleManager locale;
    private final boolean nbtApiAvailable;

    public BulkImporter(EnderChest plugin) {
        this.plugin = plugin;
        this.locale = plugin.getLocaleManager();
        this.nbtApiAvailable = Bukkit.getPluginManager().getPlugin("NBTAPI") != null;
    }

    /**
     * Bulk import vanilla enderchest data for all players (online + offline).
     * Online players are imported via Bukkit API.
     * Offline players are imported via NBTAPI reading .dat files.
     *
     * @param sender The command sender who initiated the import
     */
    public void importAll(CommandSender sender) {
        if (!nbtApiAvailable) {
            sender.sendMessage(locale.getPrefixedComponent("import.nbtapi-missing"));
            return;
        }

        sender.sendMessage(locale.getPrefixedComponent("import.started"));

        AtomicInteger imported = new AtomicInteger(0);
        AtomicInteger skipped = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        List<String> failedPlayers = new ArrayList<>();

        // Step 1: Collect online players' vanilla data on main thread
        List<PlayerData> onlinePlayers = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            int permittedSize = EnderChestUtils.getSize(player);
            if (permittedSize == 0) continue;

            Inventory vanillaChest = player.getEnderChest();
            if (vanillaChest == null) continue;

            ItemStack[] contents = vanillaChest.getContents();
            if (!hasAnyItems(contents)) continue;

            // Clone to avoid concurrent modification
            ItemStack[] cloned = cloneItems(contents);
            onlinePlayers.add(new PlayerData(player.getUniqueId(), player.getName(), cloned, permittedSize));
        }

        // Step 2: Run all imports async
        CompletableFuture.runAsync(() -> {
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            // Import online players
            for (PlayerData data : onlinePlayers) {
                CompletableFuture<Void> future = importPlayer(data, imported, skipped, failed, failedPlayers);
                futures.add(future);
            }

            // Import offline players via NBTAPI if available
            if (nbtApiAvailable) {
                List<PlayerData> offlinePlayers = NbtImporter.loadOfflinePlayers(plugin);
                for (PlayerData data : offlinePlayers) {
                    CompletableFuture<Void> future = importPlayer(data, imported, skipped, failed, failedPlayers);
                    futures.add(future);
                }
            }

            // Wait for all imports to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // Send completion message
            Scheduler.runTask(() -> {
                sender.sendMessage(locale.getPrefixedComponent("import.complete",
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("imported", String.valueOf(imported.get())),
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("skipped", String.valueOf(skipped.get())),
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("failed", String.valueOf(failed.get()))));

                if (!failedPlayers.isEmpty()) {
                    sender.sendMessage(locale.getPrefixedComponent("import.failed-details",
                            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("players", String.join(", ", failedPlayers))));
                }
            });
        });
    }

    /**
     * Import a single player's data.
     */
    private CompletableFuture<Void> importPlayer(PlayerData data, AtomicInteger imported,
                                                  AtomicInteger skipped, AtomicInteger failed,
                                                  List<String> failedPlayers) {
        return plugin.getStorageManager().getStorage()
                .hasData(data.uuid)
                .thenCompose(hasExisting -> {
                    if (hasExisting) {
                        plugin.getDebugLogger().log("Player " + data.name + " already has data, skipping");
                        skipped.incrementAndGet();
                        return CompletableFuture.completedFuture(null);
                    }

                    return plugin.getStorageManager().getStorage()
                            .saveVault(data.uuid, data.name, 1, data.size, data.items)
                            .thenRun(() -> {
                                imported.incrementAndGet();
                                plugin.getDebugLogger().log("Imported vanilla data for " + data.name);

                                // Update cache if player is online
                                Player onlinePlayer = Bukkit.getPlayer(data.uuid);
                                if (onlinePlayer != null && onlinePlayer.isOnline()) {
                                    Scheduler.runEntityTask(onlinePlayer, () ->
                                            plugin.getEnderChestManager().updateCacheWithItems(onlinePlayer, data.items));
                                }
                            });
                })
                .exceptionally(e -> {
                    failed.incrementAndGet();
                    failedPlayers.add(data.name);
                    plugin.getLogger().warning("Failed to import vanilla data for " + data.name + ": " + e.getMessage());
                    return null;
                });
    }

    private boolean hasAnyItems(ItemStack[] items) {
        if (items == null) return false;
        for (ItemStack item : items) {
            if (item != null && item.getType() != Material.AIR) return true;
        }
        return false;
    }

    private ItemStack[] cloneItems(ItemStack[] items) {
        ItemStack[] cloned = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            if (items[i] != null) cloned[i] = items[i].clone();
        }
        return cloned;
    }

    /**
     * Data holder for a player's vanilla enderchest items.
     */
    static class PlayerData {
        final UUID uuid;
        final String name;
        final ItemStack[] items;
        final int size;

        PlayerData(UUID uuid, String name, ItemStack[] items, int size) {
            this.uuid = uuid;
            this.name = name;
            this.items = items;
            this.size = size;
        }
    }
}
