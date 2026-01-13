package org.maiminhdung.customenderchest.data;

import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.Scheduler;
import org.maiminhdung.customenderchest.locale.LocaleManager;
import org.maiminhdung.customenderchest.utils.EnderChestUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles importing ender chest data from vanilla Minecraft ender chest.
 */
public class LegacyImporter {

    private final EnderChest plugin;
    private final LocaleManager locale;
    // Track players who have already been auto-imported to prevent duplicate imports
    private final Set<UUID> autoImportedPlayers = ConcurrentHashMap.newKeySet();

    public LegacyImporter(EnderChest plugin) {
        this.plugin = plugin;
        this.locale = plugin.getLocaleManager();
    }

    /**
     * Check if auto-import is enabled in config.
     * @return true if auto-import on join is enabled
     */
    public boolean isAutoImportEnabled() {
        return plugin.config().getBoolean("import.auto-import-on-join");
    }

    /**
     * Auto-import vanilla ender chest data for a player when they join.
     * This method is called from PlayerListener on player join.
     *
     * @param player The player who just joined
     */
    public void autoImportOnJoin(Player player) {
        if (!isAutoImportEnabled()) {
            return;
        }

        // Skip if already auto-imported this session
        if (autoImportedPlayers.contains(player.getUniqueId())) {
            plugin.getDebugLogger().log("Player " + player.getName() + " already auto-imported this session, skipping");
            return;
        }

        plugin.getDebugLogger().log("Checking auto-import for player: " + player.getName());

        int permittedSize = EnderChestUtils.getSize(player);
        if (permittedSize == 0) {
            plugin.getDebugLogger().log("Player " + player.getName() + " has no permitted size, skipping auto-import");
            return;
        }

        Inventory vanillaEnderChest = player.getEnderChest();
        if (vanillaEnderChest == null) {
            plugin.getDebugLogger().log("Player " + player.getName() + " has null vanilla ender chest, skipping auto-import");
            return;
        }

        ItemStack[] vanillaContents = vanillaEnderChest.getContents();

        // Check if vanilla ender chest has any items
        boolean hasItems = false;
        int itemCount = 0;
        for (ItemStack item : vanillaContents) {
            if (item != null) {
                hasItems = true;
                itemCount++;
            }
        }

        if (!hasItems) {
            plugin.getDebugLogger().log("Player " + player.getName() + " has empty vanilla ender chest, skipping auto-import");
            return;
        }

        // Clone the items to avoid concurrent modification
        ItemStack[] clonedContents = new ItemStack[vanillaContents.length];
        for (int i = 0; i < vanillaContents.length; i++) {
            if (vanillaContents[i] != null) {
                clonedContents[i] = vanillaContents[i].clone();
            }
        }

        plugin.getDebugLogger().log("Starting auto-import for " + player.getName() + " with " + itemCount + " items");

        final int finalPermittedSize = permittedSize;
        final int finalItemCount = itemCount;

        // First check if player already has data in database using hasData method
        plugin.getStorageManager().getStorage()
                .hasData(player.getUniqueId())
                .thenCompose(hasExistingData -> {
                    // If player already has data in database, skip auto-import to prevent duplication
                    if (hasExistingData) {
                        plugin.getDebugLogger().log("Player " + player.getName() + " already has data in database, skipping auto-import to prevent duplication");
                        // Mark as imported to prevent future checks
                        autoImportedPlayers.add(player.getUniqueId());
                        return CompletableFuture.completedFuture((Void) null);
                    }

                    // Send starting message to player (only if we're actually importing)
                    if (player.isOnline()) {
                        Scheduler.runEntityTask(player, () ->
                            player.sendMessage(locale.getPrefixedComponent("import.auto-import-started"))
                        );
                    }

                    plugin.getDebugLogger().log("Player " + player.getName() + " has no existing database record, proceeding with import of " + finalItemCount + " items");

                    // Create new items array for import
                    ItemStack[] newItems = new ItemStack[finalPermittedSize];

                    // Add vanilla items to the array
                    int importedCount = 0;
                    for (ItemStack vanillaItem : clonedContents) {
                        if (vanillaItem == null) continue;

                        for (int i = 0; i < finalPermittedSize; i++) {
                            if (newItems[i] == null) {
                                newItems[i] = vanillaItem.clone();
                                importedCount++;
                                break;
                            }
                        }
                    }

                    final int finalImportedCount = importedCount;
                    final ItemStack[] finalNewItems = newItems;

                    // Save to storage
                    return plugin.getStorageManager().getStorage()
                            .saveEnderChest(player.getUniqueId(), player.getName(), finalPermittedSize, finalNewItems)
                            .thenRun(() -> {
                                // Mark as auto-imported
                                autoImportedPlayers.add(player.getUniqueId());

                                // Update cache on main thread with the new items
                                if (player.isOnline()) {
                                    Scheduler.runEntityTask(player, () -> {
                                        plugin.getEnderChestManager().updateCacheWithItems(player, finalNewItems);

                                        // Send completion message
                                        TagResolver countPlaceholder = Placeholder.component("count", Component.text(finalImportedCount));
                                        player.sendMessage(locale.getPrefixedComponent("import.auto-import-complete", countPlaceholder));
                                    });
                                }

                                plugin.getDebugLogger().log("Auto-import complete for " + player.getName() + ": " + finalImportedCount + " items imported");
                            });
                })
                .exceptionally(e -> {
                    plugin.getLogger().severe("Failed to auto-import vanilla data for " + player.getName() + ": " + e.getMessage());
                    if (player.isOnline()) {
                        Scheduler.runEntityTask(player, () ->
                            player.sendMessage(locale.getPrefixedComponent("import.auto-import-failed"))
                        );
                    }
                    return null;
                });
    }

    /**
     * Clear auto-import tracking for a player (called when they quit).
     * @param playerUuid The player's UUID
     */
    public void clearAutoImportTracking(UUID playerUuid) {
        autoImportedPlayers.remove(playerUuid);
    }

    /**
     * Import vanilla ender chest data for all online players (admin command).
     * Note: Only online players can have their vanilla ender chest imported
     * because accessing offline player data requires NMS which is not available.
     *
     * @param sender The command sender
     */
    public void runVanillaImportAll(CommandSender sender) {
        sender.sendMessage(locale.getPrefixedComponent("import.started"));

        // Collect vanilla data on main thread first
        Map<UUID, ItemStack[]> vanillaData = new ConcurrentHashMap<>();
        Map<UUID, String> playerNames = new ConcurrentHashMap<>();
        Map<UUID, Integer> playerSizes = new ConcurrentHashMap<>();

        // Only process online players - offline players cannot have their vanilla ender chest accessed
        for (Player player : Bukkit.getOnlinePlayers()) {
            plugin.getDebugLogger().log("Processing online player: " + player.getName());

            int permittedSize = EnderChestUtils.getSize(player);
            if (permittedSize == 0) {
                plugin.getDebugLogger().log("Player " + player.getName() + " has no permitted size, skipping");
                continue;
            }

            Inventory vanillaEnderChest = player.getEnderChest();
            if (vanillaEnderChest == null) {
                plugin.getDebugLogger().log("Player " + player.getName() + " has null ender chest, skipping");
                continue;
            }

            ItemStack[] vanillaContents = vanillaEnderChest.getContents();

            // Check if vanilla ender chest has any items
            boolean hasItems = false;
            for (ItemStack item : vanillaContents) {
                if (item != null) {
                    hasItems = true;
                    break;
                }
            }

            if (hasItems) {
                // Clone the items to avoid concurrent modification
                ItemStack[] clonedContents = new ItemStack[vanillaContents.length];
                for (int i = 0; i < vanillaContents.length; i++) {
                    if (vanillaContents[i] != null) {
                        clonedContents[i] = vanillaContents[i].clone();
                    }
                }
                vanillaData.put(player.getUniqueId(), clonedContents);
                playerNames.put(player.getUniqueId(), player.getName());
                playerSizes.put(player.getUniqueId(), permittedSize);
                plugin.getDebugLogger().log("Added player " + player.getName() + " to import queue with " + clonedContents.length + " potential items");
            } else {
                plugin.getDebugLogger().log("Player " + player.getName() + " has empty vanilla ender chest, skipping");
            }
        }

        int totalOnline = Bukkit.getOnlinePlayers().size();

        if (vanillaData.isEmpty()) {
            plugin.getDebugLogger().log("No players with vanilla ender chest data to import");
            TagResolver importedPlaceholder = Placeholder.component("imported", Component.text(0));
            TagResolver skippedPlaceholder = Placeholder.component("skipped", Component.text(totalOnline));
            sender.sendMessage(locale.getPrefixedComponent("import.complete", importedPlaceholder, skippedPlaceholder));
            return;
        }

        plugin.getDebugLogger().log("Starting async import for " + vanillaData.size() + " players");

        // Process imports asynchronously
        AtomicInteger imported = new AtomicInteger(0);
        AtomicInteger skipped = new AtomicInteger(totalOnline - vanillaData.size());

        CompletableFuture.runAsync(() -> {
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (Map.Entry<UUID, ItemStack[]> entry : vanillaData.entrySet()) {
                UUID uuid = entry.getKey();
                ItemStack[] vanillaContents = entry.getValue();
                String playerName = playerNames.get(uuid);
                int permittedSize = playerSizes.get(uuid);

                // First check if player already has data in database
                CompletableFuture<Void> future = plugin.getStorageManager().getStorage()
                        .hasData(uuid)
                        .thenCompose(hasExistingData -> {
                            // If player already has data, skip to prevent duplication
                            if (hasExistingData) {
                                plugin.getDebugLogger().log("Player " + playerName + " already has data in database, skipping import to prevent duplication");
                                skipped.incrementAndGet();
                                return CompletableFuture.completedFuture((Void) null);
                            }

                            // Create new items array for import
                            ItemStack[] newItems = new ItemStack[permittedSize];

                            // Add vanilla items to the array
                            for (ItemStack vanillaItem : vanillaContents) {
                                if (vanillaItem == null) continue;

                                for (int i = 0; i < permittedSize; i++) {
                                    if (newItems[i] == null) {
                                        newItems[i] = vanillaItem.clone();
                                        break;
                                    }
                                }
                            }

                            // Save to storage
                            return plugin.getStorageManager().getStorage()
                                    .saveEnderChest(uuid, playerName, permittedSize, newItems)
                                    .thenRun(() -> {
                                        // Update cache on main thread with the new items
                                        Player onlinePlayer = Bukkit.getPlayer(uuid);
                                        if (onlinePlayer != null && onlinePlayer.isOnline()) {
                                            final ItemStack[] finalNewItems = newItems;
                                            Scheduler.runEntityTask(onlinePlayer, () -> plugin.getEnderChestManager().updateCacheWithItems(onlinePlayer, finalNewItems));
                                        }
                                        imported.incrementAndGet();
                                        plugin.getDebugLogger().log("Successfully imported vanilla data for " + playerName);
                                    });
                        })
                        .exceptionally(e -> {
                            plugin.getLogger().severe("Failed to import vanilla data for " + playerName + ": " + e.getMessage());
                            skipped.incrementAndGet();
                            return null;
                        });

                futures.add(future);
            }

            // Wait for all imports to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // Send completion message
            Scheduler.runTask(() -> {
                TagResolver importedPlaceholder = Placeholder.component("imported", Component.text(imported.get()));
                TagResolver skippedPlaceholder = Placeholder.component("skipped", Component.text(skipped.get()));
                sender.sendMessage(locale.getPrefixedComponent("import.complete", importedPlaceholder, skippedPlaceholder));
            });
        });
    }
}
