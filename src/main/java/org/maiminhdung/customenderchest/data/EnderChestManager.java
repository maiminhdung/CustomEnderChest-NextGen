package org.maiminhdung.customenderchest.data;

import static org.maiminhdung.customenderchest.EnderChest.ERROR_TRACKER;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.Getter;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.Scheduler;
import org.maiminhdung.customenderchest.locale.LocaleManager;
import org.maiminhdung.customenderchest.utils.DataLockManager;
import org.maiminhdung.customenderchest.utils.EnderChestUtils;
import org.maiminhdung.customenderchest.utils.SoundHandler;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

public class EnderChestManager {

    private final EnderChest plugin;
    private final SoundHandler soundHandler;
    private final DataLockManager dataLockManager;
    @Getter
    private final Cache<UUID, Inventory> liveData;
    private final Scheduler.Task autoSaveTask;
    private final Scheduler.Task inventoryTrackerTask;

    @Getter
    private final Map<Inventory, UUID> adminViewedChests = new ConcurrentHashMap<>();
    @Getter
    private final Map<UUID, Inventory> openInventories = new ConcurrentHashMap<>();
    private final Set<UUID> resizingPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> notifiedOverflowPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> resizeCooldowns = new ConcurrentHashMap<>();
    private static final long RESIZE_COOLDOWN_MS = 5000; // 5 second cooldown between resizes

    public EnderChestManager(EnderChest plugin) {
        this.plugin = plugin;
        this.soundHandler = plugin.getSoundHandler();
        this.dataLockManager = plugin.getDataLockManager();

        // Use Guava Cache to automatically clean up data for players who have been
        // offline for a while.
        this.liveData = CacheBuilder.newBuilder()
                .expireAfterAccess(30, TimeUnit.MINUTES)
                .build();

        // Start the auto-save task to prevent data loss on server crash.
        long autoSaveIntervalTicks = plugin.config().getInt("storage.auto-save-interval-seconds", 300) * 20L;
        if (autoSaveIntervalTicks > 0) {
            this.autoSaveTask = Scheduler.runTaskTimerAsync(
                    this::autoSaveAll,
                    autoSaveIntervalTicks,
                    autoSaveIntervalTicks);
        } else {
            this.autoSaveTask = null;
        }
        // Start the inventory tracker task
        this.inventoryTrackerTask = Scheduler.runTaskTimer(this::checkOpenInventories, 20L, 20L);
    }

    // Load player data when they join the server.
    public void onPlayerJoin(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        // Check if data is already cached to avoid unnecessary database calls
        if (getLoadedEnderChest(player.getUniqueId()) != null) {
            plugin.getDebugLogger().log("Data for " + player.getName() + " is already cached. Skipping load.");
            return;
        }

        if (!dataLockManager.lock(player.getUniqueId())) {
            plugin.getDebugLogger()
                    .log("Attempted to load data for " + player.getName() + ", but their data is currently locked.");
            return;
        }

        plugin.getDebugLogger().log("Data lock acquired for " + player.getName() + ". Checking storage...");
        long startTime = System.nanoTime(); // DEBUG: Start timer

        final UUID currentUUID = player.getUniqueId();
        final String playerName = player.getName();

        plugin.getStorageManager().getStorage().loadEnderChest(currentUUID)
                .orTimeout(15, TimeUnit.SECONDS)
                .thenCompose(items -> {
                    // If no data found for current UUID, try to find data by player name
                    // This handles the case where player switches between online/offline mode
                    if (items == null && plugin.config().getBoolean("storage.migrate-uuid-by-name", false)) {
                        plugin.getDebugLogger().log("No data found for UUID " + currentUUID + ", searching by name: " + playerName);
                        return plugin.getStorageManager().getStorage().findUUIDByName(playerName)
                                .thenCompose(oldUUID -> {
                                    if (oldUUID != null && !oldUUID.equals(currentUUID)) {
                                        plugin.getDebugLogger().log("Found existing data under old UUID: " + oldUUID + " for player " + playerName);
                                        // Load data from old UUID and migrate it
                                        return plugin.getStorageManager().getStorage().loadEnderChest(oldUUID)
                                                .thenApply(oldItems -> {
                                                    if (oldItems != null && oldItems.length > 0) {
                                                        // Schedule migration of data to new UUID
                                                        migratePlayerData(player, oldUUID, currentUUID, oldItems);
                                                    }
                                                    return oldItems;
                                                });
                                    }
                                    return CompletableFuture.completedFuture(null);
                                });
                    }
                    return CompletableFuture.completedFuture(items);
                })
                .whenComplete((items, error) -> {
                    // Check if player is still online before processing
                    if (!player.isOnline()) {
                        dataLockManager.unlock(player.getUniqueId());
                        return;
                    }

                    Scheduler.runEntityTask(player, () -> {
                        try {
                            if (error != null) {
                                plugin.getLogger().log(Level.SEVERE, "Failed to load data for " + player.getName(),
                                        error);
                                dataLockManager.unlock(player.getUniqueId());
                                return;
                            }

                            // If items is null, it means the player has NO data in storage
                            // We should NOT cache an empty inventory as that would overwrite their data on next save
                            // Instead, create the inventory but don't put it in cache until they actually interact
                            if (items == null) {
                                plugin.getDebugLogger().log("No existing data found for " + player.getName() + " - new player or first join");
                                // For new players, we can safely create an empty inventory
                                int size = EnderChestUtils.getSize(player);
                                if (size > 0) {
                                    Component title = EnderChestUtils.getTitle(player);
                                    Inventory inv = Bukkit.createInventory(player, size, title);
                                    liveData.put(player.getUniqueId(), inv);
                                    plugin.getDebugLogger().log("Created new empty enderchest for " + player.getName());
                                }
                                dataLockManager.unlock(player.getUniqueId());
                                plugin.getDebugLogger().log("Data lock released for " + player.getName());
                                return;
                            }

                            int size = EnderChestUtils.getSize(player);
                            Component title = EnderChestUtils.getTitle(player);
                            Inventory inv = Bukkit.createInventory(player, (size > 0 ? size : 9), title);

                            // Check if items is empty array (indicating deserialization failure)
                            if (items.length == 0) {
                                //  Empty array means deserialization failed - DO NOT cache empty inventory!
                                // Check if player actually had data in database
                                plugin.getStorageManager().getStorage().loadEnderChestSize(player.getUniqueId())
                                        .thenAccept(savedSize -> {
                                            if (savedSize > 0) {
                                                // Player had data, but it couldn't be loaded (version incompatibility)
                                                // DO NOT put empty inventory in cache - this would delete their data!
                                                plugin.getLogger().warning("[DATA PROTECTION] Player " + player.getName() +
                                                        " has corrupted/incompatible data (saved size: " + savedSize +
                                                        "). NOT loading empty inventory to prevent data loss.");
                                                Scheduler.runEntityTask(player, () -> {
                                                    LocaleManager locale = plugin.getLocaleManager();
                                                    player.sendMessage(locale.getPrefixedComponent(
                                                            "messages.migration-data-incompatible"));
                                                    player.sendMessage(locale
                                                            .getPrefixedComponent("messages.migration-data-cleared"));
                                                    player.sendMessage(locale
                                                            .getPrefixedComponent("messages.migration-contact-admin"));
                                                });
                                            } else {
                                                // Player truly has no data, safe to create empty inventory
                                                plugin.getDebugLogger().log("Player " + player.getName() + " has no saved data, creating empty inventory");
                                                liveData.put(player.getUniqueId(), inv);
                                            }
                                        });
                                // Don't put in cache yet - wait for the size check above
                                dataLockManager.unlock(player.getUniqueId());
                                plugin.getDebugLogger().log("Data lock released for " + player.getName());
                                return;
                            } else if (size > 0) {
                                if (items.length <= size) {
                                    // Properly set items - ensure array matches inventory size
                                    ItemStack[] properSizedItems = new ItemStack[size];
                                    System.arraycopy(items, 0, properSizedItems, 0, items.length);
                                    inv.setContents(properSizedItems);
                                    plugin.getDebugLogger().log("Loaded " + items.length + " items for " + player.getName() + " into inventory of size " + size);
                                } else {
                                    // Player has items beyond their current permission limit
                                    // Save the accessible items to inventory
                                    for (int i = 0; i < size; i++) {
                                        inv.setItem(i, items[i]);
                                    }

                                    // Save overflow items to storage
                                    List<ItemStack> overflowItems = new ArrayList<>();
                                    for (int i = size; i < items.length; i++) {
                                        ItemStack item = items[i];
                                        if (item != null && item.getType() != Material.AIR) {
                                            overflowItems.add(item);
                                        }
                                    }

                                    if (!overflowItems.isEmpty()) {
                                        ItemStack[] overflowArray = overflowItems.toArray(new ItemStack[0]);
                                        plugin.getStorageManager().getStorage()
                                                .saveOverflowItems(player.getUniqueId(), overflowArray)
                                                .thenRun(() -> {
                                                    plugin.getDebugLogger().log("Saved " + overflowItems.size()
                                                            + " overflow items for " + player.getName() + " on join");

                                                    // Notify player about overflow items
                                                    Scheduler.runEntityTask(player, () -> {
                                                        LocaleManager locale = plugin.getLocaleManager();
                                                        player.sendMessage(locale
                                                                .getPrefixedComponent("messages.overflow-items-saved"));
                                                        player.sendMessage(locale.getPrefixedComponent(
                                                                "messages.overflow-will-restore"));
                                                    });
                                                });
                                    }
                                }
                            }
                            liveData.put(player.getUniqueId(), inv);

                            long duration = (System.nanoTime() - startTime) / 1_000_000; // DEBUG: End timer
                            plugin.getDebugLogger().log(
                                    "Cache is ready for " + player.getName() + ". (Load time: " + duration + "ms)");
                            
                            // Metrics tracking
                            if (plugin.getMetricsDataProvider() != null) {
                                plugin.getMetricsDataProvider().recordLoad();
                            }
                        } finally {
                            dataLockManager.unlock(player.getUniqueId());
                            plugin.getDebugLogger().log("Data lock released for " + player.getName());
                        }
                    });
                });
    }

    /**
     * Migrate player data from old UUID to new UUID.
     * This handles the case where a player switches between online/offline mode servers.
     */
    private void migratePlayerData(Player player, UUID oldUUID, UUID newUUID, ItemStack[] items) {
        plugin.getLogger().info("[Migration] Migrating data for " + player.getName() + " from UUID " + oldUUID + " to " + newUUID);

        // Get the size from old data
        plugin.getStorageManager().getStorage().loadEnderChestSize(oldUUID).thenAccept(oldSize -> {
            int size = oldSize > 0 ? oldSize : EnderChestUtils.getSize(player);

            // Save data under new UUID
            plugin.getStorageManager().getStorage().saveEnderChest(newUUID, player.getName(), size, items)
                    .thenRun(() -> {
                        plugin.getLogger().info("[Migration] Successfully migrated enderchest data for " + player.getName());

                        // Also migrate overflow items if any
                        plugin.getStorageManager().getStorage().loadOverflowItems(oldUUID).thenAccept(overflowItems -> {
                            if (overflowItems != null && overflowItems.length > 0) {
                                plugin.getStorageManager().getStorage().saveOverflowItems(newUUID, overflowItems)
                                        .thenRun(() -> {
                                            plugin.getDebugLogger().log("[Migration] Migrated overflow items for " + player.getName());
                                            // Clear old overflow data
                                            plugin.getStorageManager().getStorage().clearOverflowItems(oldUUID);
                                        });
                            }
                        });

                        // Rename old data to prevent it from being migrated again and stealing items
                        plugin.getStorageManager().getStorage().saveEnderChest(oldUUID, "[Migrated] " + player.getName(), size, items);

                        // Notify player about migration
                        Scheduler.runEntityTask(player, () -> {
                            if (player.isOnline()) {
                                player.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.data-migrated"));
                            }
                        });
                    });
        });
    }

    // Save player data when they leave the server.
    // IMPORTANT: This method MUST NOT block the main/region thread to prevent
    // deadlocks!
    public void onPlayerQuit(Player player) {
        // Stop tracking this inventory as it's being closed
        final UUID playerUuid = player.getUniqueId();
        openInventories.remove(playerUuid);
        resizingPlayers.remove(playerUuid);
        resizeCooldowns.remove(playerUuid);
        notifiedOverflowPlayers.remove(playerUuid);

        final String playerName = player.getName();

        if (!dataLockManager.lock(playerUuid)) {
            plugin.getDebugLogger().log("Player " + playerName + " quit, but data is locked. Skipping quit-save.");
            return;
        }

        plugin.getDebugLogger().log("Player " + playerName + " quit. Data lock acquired for saving.");
        Inventory inv = liveData.getIfPresent(playerUuid);
        if (inv != null) {
            // Clone inventory contents to prevent concurrent modification
            ItemStack[] contents = inv.getContents().clone();
            int size = inv.getSize();

            // Invalidate cache immediately to prevent double-save
            liveData.invalidate(playerUuid);

            // Save asynchronously with timeout - DO NOT BLOCK the main thread!
            plugin.getStorageManager().getStorage()
                    .saveEnderChest(playerUuid, playerName, size, cleanInventoryForSave(contents))
                    .orTimeout(10, TimeUnit.SECONDS)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            if (ex instanceof TimeoutException) {
                                plugin.getLogger().warning("Quit-save for " + playerName
                                        + " timed out. Data will be recovered from auto-save.");
                            } else {
                                plugin.getLogger().severe(
                                        "Failed to save data for " + playerName + " on quit: " + ex.getMessage());
                            }
                        } else {
                            plugin.getDebugLogger().log("Quit-save for " + playerName + " complete.");
                        }
                        dataLockManager.unlock(playerUuid);
                        plugin.getDebugLogger().log("Lock released for " + playerName);
                    });
        } else {
            dataLockManager.unlock(playerUuid);
        }
    }

    public void shutdown() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
        }
        if (inventoryTrackerTask != null)
            inventoryTrackerTask.cancel(); // Cancel the inventory tracker task

        plugin.getLogger().info("Auto-save task cancelled. Saving all cached player data before shutting down...");

        try {
            // Use timeout to prevent hanging during shutdown
            // 30 seconds should be enough for most cases, but won't block forever
            shutdownSave().get(30, TimeUnit.SECONDS);
            plugin.getLogger().info("All player data has been saved successfully.");
        } catch (TimeoutException e) {
            plugin.getLogger().warning("Shutdown save timed out after 30 seconds. Some data may not have been saved.");
            ERROR_TRACKER.trackError(e);
        } catch (Exception e) {
            plugin.getLogger().severe("Error during shutdown save: " + e.getMessage());
            e.printStackTrace();
            ERROR_TRACKER.trackError(e);
        }
    }

    // Open the ender chest for the player, loading data if necessary.
    public void openEnderChest(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        // Check if data is currently being loaded to prevent loops
        if (dataLockManager.isLocked(player.getUniqueId())) {
            player.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.data-still-loading"));
            return;
        }

        Inventory inv = getLoadedEnderChest(player.getUniqueId());
        if (inv == null) {
            // Trigger data loading asynchronously to prevent blocking
            Scheduler.runTaskAsync(() -> {
                if (player.isOnline()) {
                    onPlayerJoin(player);
                }
            });
            player.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.data-still-loading"));
            return;
        }

        int permissionSize = EnderChestUtils.getSize(player);
        if (permissionSize == 0) {
            player.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.no-permission"));
            soundHandler.playSound(player, "fail");
            return;
        }

        // Calculate expected display size based on permission and item positions
        int expectedDisplaySize = getExpectedDisplaySize(inv, permissionSize);

        if (inv.getSize() != expectedDisplaySize) {
            inv = resizeInventory(player, inv, permissionSize);
            liveData.put(player.getUniqueId(), inv);
        }

        player.openInventory(inv);
        openInventories.put(player.getUniqueId(), inv); // Start tracking this inventory
        soundHandler.playSound(player, "open");
    }

    private static int getExpectedDisplaySize(Inventory inv, int permissionSize) {
        ItemStack[] contents = inv.getContents();
        int lastItemIndex = -1;
        for (int i = contents.length - 1; i >= 0; i--) {
            ItemStack item = contents[i];
            if (item != null && item.getType() != Material.AIR) {
                lastItemIndex = i;
                break;
            }
        }

        int expectedDisplaySize = permissionSize;
        if (lastItemIndex >= permissionSize) {
            expectedDisplaySize = (int) (Math.ceil((lastItemIndex + 1) / 9.0)) * 9;
        }
        expectedDisplaySize = Math.max(permissionSize, expectedDisplaySize);
        return expectedDisplaySize;
    }

    // Resize the inventory - Use overflow storage for items beyond permission
    private Inventory resizeInventory(Player player, Inventory oldInv, int newSize) {
        plugin.getDebugLogger().log(
                "Resizing " + player.getName() + "'s inventory. Old: " + oldInv.getSize() + ", New Size: " + newSize);
        ItemStack[] oldContents = oldInv.getContents();

        Component title = EnderChestUtils.getTitle(player);
        Inventory newInv = Bukkit.createInventory(player, newSize, title);

        // Separate items into accessible and overflow
        List<ItemStack> overflowItems = new ArrayList<>();

        for (int i = 0; i < oldContents.length; i++) {
            ItemStack item = oldContents[i];

            // Skip null and air
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }

            // If within new size, add to inventory
            if (i < newSize) {
                newInv.setItem(i, item);
                plugin.getDebugLogger().log("  Slot " + i + ": Kept item " + item.getType());
            } else {
                // Item is beyond permission - add to overflow
                overflowItems.add(item);
                plugin.getDebugLogger().log("  Slot " + i + ": Moved " + item.getType() + " to overflow storage");
            }
        }

        // Save overflow items to storage if any exist
        if (!overflowItems.isEmpty()) {
            // Load existing overflow items and merge them
            plugin.getStorageManager().getStorage().loadOverflowItems(player.getUniqueId())
                    .thenAccept(existingOverflow -> {
                        List<ItemStack> mergedOverflow = new ArrayList<>(overflowItems);

                        // Add existing overflow items to the list
                        if (existingOverflow != null && existingOverflow.length > 0) {
                            for (ItemStack item : existingOverflow) {
                                if (item != null && item.getType() != Material.AIR) {
                                    mergedOverflow.add(item);
                                }
                            }
                            plugin.getDebugLogger().log("Merged " + existingOverflow.length
                                    + " existing overflow items with " + overflowItems.size() + " new items");
                        }

                        // Save merged overflow items
                        ItemStack[] mergedArray = mergedOverflow.toArray(new ItemStack[0]);
                        plugin.getStorageManager().getStorage().saveOverflowItems(player.getUniqueId(), mergedArray)
                                .thenRun(() -> {
                                    plugin.getDebugLogger().log("Saved " + mergedOverflow.size()
                                            + " total overflow items for " + player.getName());

                                    // Notify player once
                                    if (!notifiedOverflowPlayers.contains(player.getUniqueId())) {
                                        Scheduler.runEntityTask(player, () -> {
                                            LocaleManager locale = plugin.getLocaleManager();
                                            player.sendMessage(
                                                    locale.getPrefixedComponent("messages.overflow-items-saved"));
                                            player.sendMessage(
                                                    locale.getPrefixedComponent("messages.overflow-will-restore"));
                                        });
                                        notifiedOverflowPlayers.add(player.getUniqueId());
                                    }
                                });
                    });
        } else {
            // Try to restore overflow items if player has enough space now
            restoreOverflowItems(player, newInv);
        }

        plugin.getDebugLogger().log("Resize complete. New inventory size: " + newInv.getSize() + ", Overflow items: "
                + overflowItems.size());
        return newInv;
    }

    // Restore overflow items when player gains more space
    private void restoreOverflowItems(Player player, Inventory inv) {
        plugin.getStorageManager().getStorage().loadOverflowItems(player.getUniqueId())
                .thenAccept(overflowItems -> {
                    if (overflowItems == null || overflowItems.length == 0) {
                        return;
                    }

                    Scheduler.runEntityTask(player, () -> {
                        List<ItemStack> remainingOverflow = new ArrayList<>();
                        final List<ItemStack> restoredItems = new ArrayList<>();

                        for (ItemStack item : overflowItems) {
                            if (item == null || item.getType() == Material.AIR)
                                continue;

                            // Try to add item to inventory
                            if (inv.firstEmpty() != -1) {
                                inv.addItem(item);
                                restoredItems.add(item);
                                plugin.getDebugLogger()
                                        .log("Restored overflow item " + item.getType() + " to " + player.getName());
                            } else {
                                remainingOverflow.add(item);
                            }
                        }

                        final int count = restoredItems.size();
                        if (count > 0) {
                            // Update cache
                            liveData.put(player.getUniqueId(), inv);

                            LocaleManager locale = plugin.getLocaleManager();
                            player.sendMessage(locale.getPrefixedComponent("messages.overflow-items-restored")
                                    .replaceText(builder -> builder.matchLiteral("<count>")
                                            .replacement(String.valueOf(count))));
                        }

                        // Update or clear overflow storage
                        if (remainingOverflow.isEmpty()) {
                            plugin.getStorageManager().getStorage().clearOverflowItems(player.getUniqueId());
                            notifiedOverflowPlayers.remove(player.getUniqueId());
                        } else {
                            ItemStack[] remaining = remainingOverflow.toArray(new ItemStack[0]);
                            plugin.getStorageManager().getStorage().saveOverflowItems(player.getUniqueId(), remaining);
                        }
                    });
                });
    }

    // Force-save all cached data during server shutdown to prevent data loss.
    private CompletableFuture<Void> shutdownSave() {
        Set<Map.Entry<UUID, Inventory>> cacheSnapshot = new java.util.HashSet<>(liveData.asMap().entrySet());
        if (cacheSnapshot.isEmpty())
            return CompletableFuture.completedFuture(null);
        plugin.getLogger().info("Force-saving data for " + cacheSnapshot.size() + " players...");
        
        // During shutdown, we clone inventory contents immediately
        // This is safer because we're on the main/global thread during shutdown
        CompletableFuture<?>[] futures = cacheSnapshot.stream()
                .map(entry -> {
                    UUID uuid = entry.getKey();
                    Player p = Bukkit.getPlayer(uuid);
                    String name = (p != null) ? p.getName() : Bukkit.getOfflinePlayer(uuid).getName();
                    Inventory inv = entry.getValue();
                    // Clone contents immediately to avoid thread safety issues
                    ItemStack[] contents = cleanInventoryForSave(inv.getContents().clone());
                    int size = inv.getSize();
                    return plugin.getStorageManager().getStorage()
                            .saveEnderChest(uuid, name, size, contents);
                })
                .filter(Objects::nonNull).toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures);
    }

    // Auto-save all cached data periodically to prevent data loss.
    private void autoSaveAll() {
        // On Folia, we need to get inventory contents from the correct region thread
        // So we run this on global thread first to collect data safely
        Set<Map.Entry<UUID, Inventory>> cacheSnapshot = new java.util.HashSet<>(liveData.asMap().entrySet());
        if (cacheSnapshot.isEmpty()) {
            return;
        }
        plugin.getDebugLogger().log("Auto-saving data for " + cacheSnapshot.size() + " cached players...");

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Map.Entry<UUID, Inventory> entry : cacheSnapshot) {
            UUID uuid = entry.getKey();

            // Skip if data is locked (being processed elsewhere)
            if (dataLockManager.isLocked(uuid)) {
                plugin.getDebugLogger().log("Skipping auto-save for " + uuid + " - data is locked");
                continue;
            }

            Player p = Bukkit.getPlayer(uuid);

            // CRITICAL: Only auto-save for ONLINE players
            // Offline players' data was already saved on quit, don't overwrite
            if (p == null || !p.isOnline()) {
                plugin.getDebugLogger().log("Skipping auto-save for " + uuid + " - player is offline");
                continue;
            }

            final String name = p.getName();
            final Inventory inv = entry.getValue();
            final int size = inv.getSize();
            
            // On Folia, we need to clone the inventory contents on the correct entity thread
            // to avoid cross-region thread access violations
            if (Scheduler.isFolia()) {
                CompletableFuture<Void> future = new CompletableFuture<>();
                final Player finalPlayer = p;
                Scheduler.runEntityTask(p, () -> {
                    // Now we're on the correct region thread for this player
                    if (!finalPlayer.isOnline()) {
                        future.complete(null);
                        return;
                    }
                    ItemStack[] contents = cleanInventoryForSave(inv.getContents().clone());
                    // Now save asynchronously
                    plugin.getStorageManager().getStorage()
                            .saveEnderChest(uuid, name, size, contents)
                            .whenComplete((result, ex) -> {
                                if (ex != null) {
                                    plugin.getLogger().warning("Failed to auto-save data for " + name + ": " + ex.getMessage());
                                } else {
                                    plugin.getDebugLogger().log("Auto-saved data for " + name);
                                }
                                future.complete(null);
                            });
                });
                futures.add(future);
            } else {
                // On non-Folia servers, we can safely access inventory from async thread
                CompletableFuture<Void> future = saveEnderChest(uuid, name, inv)
                        .exceptionally(ex -> {
                            plugin.getLogger().warning("Failed to auto-save data for " + name + ": " + ex.getMessage());
                            return null;
                        });
                futures.add(future);
            }
        }

        // Wait for all saves to complete to ensure data consistency
        if (!futures.isEmpty()) {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenRun(() -> {
                        plugin.getDebugLogger().log("Auto-save completed for all players.");
                    });
        }
    }

    // Save ender chest data with inventory object, used for online players.
    public CompletableFuture<Void> saveEnderChest(UUID uuid, String playerName, Inventory inv) {
        long startTime = System.nanoTime(); // DEBUG: Start timer

        // Clean the inventory before saving - remove barriers and unlock locked items
        ItemStack[] cleanedContents = cleanInventoryForSave(inv.getContents());

        return plugin.getStorageManager().getStorage()
                .saveEnderChest(uuid, playerName, inv.getSize(), cleanedContents)
                .orTimeout(15, TimeUnit.SECONDS)
                .thenRun(() -> {
                    long elapsedNanos = System.nanoTime() - startTime;
                    long duration = elapsedNanos / 1_000_000; // DEBUG: End timer
                    plugin.getDebugLogger().log("Data for " + playerName + " saved in " + duration + "ms.");
                    
                    // Metrics tracking
                    if (plugin.getMetricsDataProvider() != null) {
                        plugin.getMetricsDataProvider().recordSave();
                        plugin.getMetricsDataProvider().recordSaveTime(elapsedNanos);
                    }
                });
    }

    // Clean inventory contents for saving - remove null and air items
    private ItemStack[] cleanInventoryForSave(ItemStack[] contents) {
        ItemStack[] cleaned = new ItemStack[contents.length];

        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];

            if (item == null || item.getType() == Material.AIR) {
                cleaned[i] = null;
            } else {
                cleaned[i] = item;
            }
        }

        return cleaned;
    }

    // Save ender chest data with specified size and items, used for offline
    // players.
    public CompletableFuture<Void> saveEnderChest(UUID uuid, String playerName, int size, ItemStack[] items) {
        return plugin.getStorageManager().getStorage().saveEnderChest(uuid, playerName, size, items)
                .orTimeout(15, TimeUnit.SECONDS);
    }

    // Get the cached inventory for a player, or null if not loaded.
    public Inventory getLoadedEnderChest(UUID uuid) {
        return liveData.getIfPresent(uuid);
    }

    // Tracker for currently open ender chest inventories.
    private void checkOpenInventories() {
        if (openInventories.isEmpty())
            return;

        for (UUID uuid : new ArrayList<>(openInventories.keySet())) {
            Player player = Bukkit.getPlayer(uuid);

            // Stop tracking if player is offline or not viewing our inventory anymore
            if (player == null || !player.isOnline()) {
                openInventories.remove(uuid);
                resizingPlayers.remove(uuid);
                continue;
            }

            Inventory openInv = player.getOpenInventory().getTopInventory();
            Inventory trackedInv = openInventories.get(uuid);

            if (openInv != trackedInv) {
                openInventories.remove(uuid);
                resizingPlayers.remove(uuid);
                continue;
            }

            // Skip if already resizing to prevent double-resize
            if (resizingPlayers.contains(uuid)) {
                continue;
            }

            // CRITICAL FIX: Check cooldown to prevent rapid resize loops that melt the server
            Long lastResize = resizeCooldowns.get(uuid);
            if (lastResize != null && System.currentTimeMillis() - lastResize < RESIZE_COOLDOWN_MS) {
                continue; // Still in cooldown, skip this player
            }

            int currentPermissionSize = EnderChestUtils.getSize(player);

            // CRITICAL FIX: If player has no permission (size 0), don't try to resize
            // This can cause infinite loops and server meltdown
            if (currentPermissionSize == 0) {
                plugin.getDebugLogger().log("Player " + player.getName() + " has no permission for enderchest, skipping resize check");
                continue;
            }

            // Get the cached inventory to check against
            Inventory cachedInv = liveData.getIfPresent(uuid);
            if (cachedInv == null) {
                continue;
            }

            // IMPORTANT: Only check if we need to resize, don't resize while player is
            // actively using inventory
            // This prevents race conditions and item duplication

            // Check size mismatch - only compare against permission size, not cached size
            // This prevents infinite loops when cached size differs from displayed size
            boolean sizeMismatched = openInv.getSize() != currentPermissionSize &&
                                     openInv.getSize() < currentPermissionSize; // Only resize UP, not down while open

            Component expectedTitleComponent = EnderChestUtils.getTitle(player);
            Component actualTitleComponent = player.getOpenInventory().title();

            String expectedTitle = LegacyComponentSerializer.legacySection().serialize(expectedTitleComponent);
            String actualTitle = LegacyComponentSerializer.legacySection().serialize(actualTitleComponent);
            boolean titleMismatched = !expectedTitle.equals(actualTitle);

            // Only resize if there's an actual mismatch and permission changed significantly
            // CRITICAL: Add extra check to prevent rapid resize loops
            if (sizeMismatched || titleMismatched) {
                // Log at INFO level so admins can see this without debug mode
                plugin.getLogger().info("[Resize] Permission/title change detected for " + player.getName() +
                        " (size: " + openInv.getSize() + " -> " + currentPermissionSize +
                        ", titleMismatch: " + titleMismatched + "). Triggering inventory refresh.");

                // Mark as resizing and set cooldown to prevent rapid loops
                resizingPlayers.add(uuid);
                resizeCooldowns.put(uuid, System.currentTimeMillis());

                // Remove from tracking first to prevent loops
                openInventories.remove(uuid);

                // Save current cursor item
                ItemStack cursorItem = player.getItemOnCursor();
                player.setItemOnCursor(null);

                // CRITICAL: Sync cached inventory with current open inventory BEFORE closing
                // This prevents item loss when player was moving items
                cachedInv.setContents(openInv.getContents());

                // Close current inventory
                player.closeInventory();

                // Resize the inventory and update cache
                Inventory resizedInv = resizeInventory(player, cachedInv, currentPermissionSize);
                liveData.put(uuid, resizedInv);
                plugin.getDebugLogger().log("Resized inventory cached. New size: " + resizedInv.getSize());

                // Save the resized inventory immediately to prevent data loss (async,
                // non-blocking)
                saveEnderChest(uuid, player.getName(), resizedInv)
                        .exceptionally(ex -> {
                            plugin.getLogger().warning("Failed to save resized inventory for " + player.getName() + ": "
                                    + ex.getMessage());
                            return null;
                        })
                        .thenRun(() -> plugin.getDebugLogger().log("Saved resized inventory for " + player.getName()));

                // Use a delayed task to prevent issues with immediate reopening
                Scheduler.runTaskLater(() -> {
                    if (player.isOnline()) {
                        player.openInventory(resizedInv);
                        openInventories.put(uuid, resizedInv);
                        soundHandler.playSound(player, "open");
                        player.setItemOnCursor(cursorItem);
                    }

                    // Clear resizing flag AFTER reopening
                    Scheduler.runTaskLater(() -> resizingPlayers.remove(uuid), 5L);
                }, 2L); // Wait 2 ticks before reopening
            }
        }
    }

    // Reload the cache for a player, useful when their ender chest size or title
    // changes.
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

    /**
     * Update the cache for a player with specific items.
     * Used by import functionality to update cache with imported items.
     *
     * @param player The player to update cache for
     * @param items The items to set in the cache
     */
    public void updateCacheWithItems(Player player, ItemStack[] items) {
        int size = EnderChestUtils.getSize(player);
        if (size == 0) {
            liveData.invalidate(player.getUniqueId());
            return;
        }
        Component title = EnderChestUtils.getTitle(player);
        Inventory newInv = Bukkit.createInventory(player, size, title);

        // Set items, respecting the inventory size
        if (items != null) {
            for (int i = 0; i < Math.min(items.length, size); i++) {
                if (items[i] != null) {
                    newInv.setItem(i, items[i]);
                }
            }
        }

        liveData.put(player.getUniqueId(), newInv);
        plugin.getDebugLogger().log("Cache updated with items for player " + player.getName());
    }
}
