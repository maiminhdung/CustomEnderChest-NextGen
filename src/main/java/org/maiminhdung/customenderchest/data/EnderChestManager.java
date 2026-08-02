package org.maiminhdung.customenderchest.data;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalCause;
import com.google.common.cache.RemovalListener;
import lombok.Getter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
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
import org.bukkit.event.inventory.InventoryClickEvent;

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
    @Getter
    private final Map<Inventory, Boolean> overflowInventories = new ConcurrentHashMap<>(); // true = admin view
    private final Map<UUID, List<ItemStack>> activeOverflowItems = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Void>> pendingSaves = new ConcurrentHashMap<>();
    private final Set<UUID> resizingPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> notifiedOverflowPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, CompletableFuture<Void>> saveChains = new ConcurrentHashMap<>();
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
                .removalListener((RemovalListener<UUID, Inventory>) notification -> {
                    if (notification.getCause() == RemovalCause.EXPIRED) {
                        UUID uuid = notification.getKey();
                        if (uuid != null) {
                            Player player = Bukkit.getPlayer(uuid);
                            if (player != null && player.isOnline()) {
                                // Player is online, re-cache to prevent eviction
                                Inventory inv = notification.getValue();
                                if (inv != null) {
                                    Scheduler.runEntityTask(player, () -> {
                                        if (player.isOnline()) {
                                            getLiveData().put(uuid, inv);
                                            plugin.getDebugLogger().log("Prevented cache eviction of online player: " + player.getName());
                                        }
                                    });
                                }
                            }
                        }
                    }
                })
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
        this.inventoryTrackerTask = Scheduler.runTaskTimer(this::checkOpenInventories, 100L, 100L);
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
                                                .thenCompose(oldItems -> {
                                                    if (oldItems != null && oldItems.length > 0) {
                                                         // Chain migration and resolve with oldItems once complete
                                                         return migratePlayerData(player, oldUUID, currentUUID, oldItems)
                                                                 .thenApply(v -> oldItems);
                                                    }
                                                    return CompletableFuture.completedFuture(oldItems);
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
                        boolean unlockDelegated = false;
                        try {
                            if (error != null) {
                                plugin.getLogger().log(Level.SEVERE, "Failed to load data for " + player.getName(),
                                        error);
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
                                return;
                            }

                            int size = EnderChestUtils.getSize(player);
                            Component title = EnderChestUtils.getTitle(player);
                            Inventory inv = Bukkit.createInventory(player, (size > 0 ? size : 9), title);

                            // Check if items is empty array (indicating deserialization failure)
                            if (items.length == 0) {
                                //  Empty array means deserialization failed - DO NOT cache empty inventory!
                                // Check if player actually had data in database
                                unlockDelegated = true;
                                plugin.getStorageManager().getStorage().loadEnderChestSize(player.getUniqueId())
                                        .orTimeout(10, TimeUnit.SECONDS)
                                        .whenComplete((savedSize, sizeError) -> {
                                            try {
                                                if (sizeError != null) {
                                                    plugin.getLogger().log(Level.SEVERE, "Failed to load enderchest size for " + player.getName(), sizeError);
                                                    return;
                                                }
                                                if (savedSize > 0) {
                                                    // Player had data, but it couldn't be loaded (version incompatibility)
                                                    // DO NOT put empty inventory in cache - this would delete their data!
                                                    plugin.getLogger().warning("[DATA PROTECTION] Player " + player.getName() +
                                                            " has corrupted/incompatible data (saved size: " + savedSize +
                                                            "). NOT loading empty inventory to prevent data loss.");
                                                    Scheduler.runEntityTask(player, () -> {
                                                        if (player.isOnline()) {
                                                            LocaleManager locale = plugin.getLocaleManager();
                                                            player.sendMessage(locale.getPrefixedComponent(
                                                                    "messages.migration-data-incompatible"));
                                                            player.sendMessage(locale
                                                                    .getPrefixedComponent("messages.migration-data-cleared"));
                                                            player.sendMessage(locale
                                                                    .getPrefixedComponent("messages.migration-contact-admin"));
                                                        }
                                                    });
                                                } else {
                                                    // Player truly has no data, safe to create empty inventory
                                                    plugin.getDebugLogger().log("Player " + player.getName() + " has no saved data, creating empty inventory");
                                                    liveData.put(player.getUniqueId(), inv);
                                                }
                                            } finally {
                                                dataLockManager.unlock(player.getUniqueId());
                                                plugin.getDebugLogger().log("Data lock released for " + player.getName() + " after size check");
                                            }
                                        });
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
                                        queueStorageOperation(player.getUniqueId(),
                                                () -> plugin.getStorageManager().getStorage().saveOverflowItems(player.getUniqueId(), overflowArray))
                                                .thenRun(() -> {
                                                    plugin.getDebugLogger().log("Saved " + overflowItems.size()
                                                            + " overflow items for " + player.getName() + " on join");

                                                    // Notify player about overflow items
                                                    Scheduler.runEntityTask(player, () -> {
                                                        LocaleManager locale = plugin.getLocaleManager();
                                                        player.sendMessage(locale
                                                                .getPrefixedComponent("messages.overflow-items-saved"));
                                                        player.sendMessage(locale.getPrefixedComponent(
                                                                "messages.overflow-will-restore",
                                                                Placeholder.unparsed("command", plugin.getPrimaryCommand() + " overflow")));
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

                            // Send overflow login warning (async, non-blocking)
                            if (plugin.getOverflowManager() != null) {
                                plugin.getOverflowManager().sendLoginWarning(player);
                            }
                        } finally {
                            if (!unlockDelegated) {
                                dataLockManager.unlock(player.getUniqueId());
                                plugin.getDebugLogger().log("Data lock released for " + player.getName());
                            }
                        }
                    });
                });
    }

    /**
     * Migrate player data from old UUID to new UUID.
     * This handles the case where a player switches between online/offline mode servers.
     */
    private CompletableFuture<Void> migratePlayerData(Player player, UUID oldUUID, UUID newUUID, ItemStack[] items) {
        plugin.getLogger().info("[Migration] Migrating data for " + player.getName() + " from UUID " + oldUUID + " to " + newUUID);

        // Get the size from old data
        return plugin.getStorageManager().getStorage().loadEnderChestSize(oldUUID).thenCompose(oldSize -> {
            int size = oldSize > 0 ? oldSize : EnderChestUtils.getSize(player);

            // Save data under new UUID
            return plugin.getStorageManager().getStorage().saveEnderChest(newUUID, player.getName(), size, items)
                    .thenCompose(v -> {
                        plugin.getLogger().info("[Migration] Successfully migrated enderchest data for " + player.getName());

                        // Chain overflow migration and old data renaming
                        CompletableFuture<Void> overflowFuture = plugin.getStorageManager().getStorage().loadOverflowItems(oldUUID)
                                .thenCompose(overflowItems -> {
                                    if (overflowItems != null && overflowItems.length > 0) {
                                        return queueStorageOperation(newUUID,
                                                () -> plugin.getStorageManager().getStorage().saveOverflowItems(newUUID, overflowItems))
                                                .thenCompose(v2 -> queueStorageOperation(oldUUID,
                                                        () -> plugin.getStorageManager().getStorage().clearOverflowItems(oldUUID)));
                                    }
                                    return CompletableFuture.completedFuture(null);
                                });

                        CompletableFuture<Void> renameOldFuture = plugin.getStorageManager().getStorage()
                                .saveEnderChest(oldUUID, "[Migrated] " + player.getName(), size, items);

                        // Notify player about migration
                        Scheduler.runEntityTask(player, () -> {
                            if (player.isOnline()) {
                                player.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.data-migrated"));
                            }
                        });

                        return CompletableFuture.allOf(overflowFuture, renameOldFuture);
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

        // Clear overflow login warning flag
        if (plugin.getOverflowManager() != null) {
            plugin.getOverflowManager().clearWarnedFlag(playerUuid);
        }

        final String playerName = player.getName();

        if (!dataLockManager.lock(playerUuid)) {
            plugin.getDebugLogger().log("Player " + playerName + " quit while data is busy. Scheduling save after current operation.");
            CompletableFuture<Void> pending = pendingSaves.get(playerUuid);
            if (pending != null && !pending.isDone()) {
                pending.whenComplete((v, ex) -> onPlayerQuit(player));
            }
            return;
        }

        plugin.getDebugLogger().log("Player " + playerName + " quit. Data lock acquired for saving.");
        Inventory inv = liveData.getIfPresent(playerUuid);
        if (inv != null) {
            int size = inv.getSize();

            // Invalidate cache immediately to prevent double-save
            liveData.invalidate(playerUuid);

            Scheduler.runTask(() -> {
                ItemStack[] contents = cleanInventoryForSave(inv.getContents().clone());
                saveEnderChest(playerUuid, playerName, size, contents)
                        .orTimeout(10, TimeUnit.SECONDS)
                        .whenComplete((result, ex) -> {
                            if (ex != null) {
                                if (ex instanceof TimeoutException) {
                                    plugin.getLogger().warning("Quit-save for " + playerName
                                            + " timed out. Data may require manual verification.");
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
            EnderChest.trackError(e);
        } catch (Exception e) {
            plugin.getLogger().severe("Error during shutdown save: " + e.getMessage());
            e.printStackTrace();
            EnderChest.trackError(e);
        }
    }

    // Open the ender chest for the player, loading data if necessary.
    // Returns true if the inventory was successfully opened, false otherwise.
    public boolean openEnderChest(Player player) {
        if (player == null || !player.isOnline()) {
            return false;
        }

        UUID uuid = player.getUniqueId();
        CompletableFuture<Void> pendingSave = pendingSaves.get(uuid);
        if (pendingSave != null && !pendingSave.isDone()) {
            plugin.getDebugLogger().log("Waiting for pending saves before opening Ender Chest for " + player.getName());
            pendingSave.thenRun(() -> Scheduler.runEntityTask(player, () -> openEnderChest(player)));
            return true;
        }

        // Check if data is currently being loaded to prevent loops
        if (dataLockManager.isLocked(uuid)) {
            player.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.data-still-loading"));
            return false;
        }

        // Prevent opening concurrent views of the same inventory (mitigates double-open race conditions)
        if (openInventories.containsKey(player.getUniqueId())) {
            return false;
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
            return false;
        }

        int permissionSize = EnderChestUtils.getSize(player);
        if (permissionSize == 0) {
            player.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.no-permission"));
            soundHandler.playSound(player, "fail");
            return false;
        }

        // If there's an upgrade, run checkForUpgradeAndRestore first!
        if (permissionSize > inv.getSize()) {
            checkForUpgradeAndRestore(player).thenRun(() -> {
                Scheduler.runEntityTask(player, () -> openEnderChest(player));
            });
            return true;
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
        return true;
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
            // Load existing overflow items and merge them, tracked to prevent race conditions
            CompletableFuture<Void> overflowFuture = plugin.getStorageManager().getStorage().loadOverflowItems(player.getUniqueId())
                    .thenCompose(existingOverflow -> {
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
                        return queueStorageOperation(player.getUniqueId(),
                                () -> plugin.getStorageManager().getStorage().saveOverflowItems(player.getUniqueId(), mergedArray))
                                .thenRun(() -> {
                                    plugin.getDebugLogger().log("Saved " + mergedOverflow.size()
                                            + " total overflow items for " + player.getName());

                                    // Notify player once
                                    if (!notifiedOverflowPlayers.contains(player.getUniqueId())) {
                                        Scheduler.runEntityTask(player, () -> {
                                            LocaleManager locale = plugin.getLocaleManager();
                                            player.sendMessage(
                                                    locale.getPrefixedComponent("messages.overflow-items-saved"));
                                            player.sendMessage(locale.getPrefixedComponent(
                                                    "messages.overflow-will-restore",
                                                    Placeholder.unparsed("command", plugin.getPrimaryCommand() + " overflow")));
                                        });
                                        notifiedOverflowPlayers.add(player.getUniqueId());
                                    }
                                });
                    });
            trackPendingSave(player.getUniqueId(), overflowFuture);
        }
        plugin.getDebugLogger().log("Resize complete. New inventory size: " + newInv.getSize() + ", Overflow items: "
                + overflowItems.size());
        return newInv;
    }

    /**
     * Track a pending database save operation for a player.
     */
    private void trackPendingSave(UUID uuid, CompletableFuture<Void> future) {
        pendingSaves.compute(uuid, (k, oldFuture) -> {
            if (oldFuture == null || oldFuture.isDone()) {
                return future;
            } else {
                return CompletableFuture.allOf(oldFuture, future);
            }
        });
        future.whenComplete((v, ex) -> pendingSaves.computeIfPresent(uuid, (k, current) -> current == future ? null : current));
    }

    public CompletableFuture<Void> queueStorageOperation(UUID uuid, java.util.function.Supplier<CompletableFuture<Void>> operation) {
        CompletableFuture<Void> gate = new CompletableFuture<>();
        CompletableFuture<Void> chained = saveChains.compute(uuid, (key, previous) -> {
            CompletableFuture<Void> base = previous == null ? CompletableFuture.completedFuture(null) : previous;
            CompletableFuture<Void> next = base.handle((v, ex) -> null)
                    .thenCompose(ignored -> operation.get());
            next.whenComplete((v, ex) -> {
                if (ex != null) {
                    gate.completeExceptionally(ex);
                } else {
                    gate.complete(null);
                }
            });
            return next;
        });
        chained.whenComplete((v, ex) -> saveChains.remove(uuid, chained));
        trackPendingSave(uuid, gate);
        return gate;
    }

    /**
     * Check if the player's permission size is upgraded compared to their currently loaded Ender Chest size.
     * If upgraded, resizes the chest and automatically restores overflow items.
     */
    public CompletableFuture<Void> checkForUpgradeAndRestore(Player player) {
        if (player == null || !player.isOnline()) {
            return CompletableFuture.completedFuture(null);
        }

        UUID uuid = player.getUniqueId();
        if (resizingPlayers.contains(uuid)) {
            // Already checking/resizing
            return CompletableFuture.completedFuture(null);
        }

        int permissionSize = EnderChestUtils.getSize(player);
        Inventory loadedChest = liveData.getIfPresent(uuid);

        if (loadedChest != null && permissionSize > loadedChest.getSize()) {
            resizingPlayers.add(uuid);
            plugin.getDebugLogger().log("Upgrade detected for " + player.getName() + " (Permission size: " + permissionSize + " > Loaded: " + loadedChest.getSize() + "). Resizing and restoring...");
            Inventory newInv = resizeInventory(player, loadedChest, permissionSize);
            liveData.put(uuid, newInv);
            CompletableFuture<Void> overallFuture = restoreOverflowToEnderChest(player, newInv);
            trackPendingSave(uuid, overallFuture);
            return overallFuture.whenComplete((v, ex) -> resizingPlayers.remove(uuid));
        }

        return CompletableFuture.completedFuture(null);
    }

    /**
     * Restores overflow items directly to the newly upgraded slots of the player's Ender Chest.
     */
    private CompletableFuture<Void> restoreOverflowToEnderChest(Player player, Inventory inv) {
        UUID uuid = player.getUniqueId();
        CompletableFuture<Void> overallFuture = new CompletableFuture<>();

        plugin.getStorageManager().getStorage().loadOverflowItems(uuid)
                .thenAccept(overflowItems -> {
                    if (overflowItems == null || overflowItems.length == 0) {
                        overallFuture.complete(null);
                        return;
                    }

                    Scheduler.runEntityTask(player, () -> {
                        if (!player.isOnline()) {
                            overallFuture.complete(null);
                            return;
                        }

                        List<ItemStack> remainingOverflow = new ArrayList<>();
                        List<ItemStack> restoredItems = new ArrayList<>();

                        for (ItemStack item : overflowItems) {
                            if (item == null || item.getType() == Material.AIR)
                                continue;

                            // Try to add item to Ender Chest (inv)
                            if (inv.firstEmpty() != -1) {
                                inv.addItem(item);
                                restoredItems.add(item);
                                plugin.getDebugLogger().log("Restored overflow item " + item.getType() + " to " + player.getName() + "'s Ender Chest due to upgrade.");
                            } else {
                                remainingOverflow.add(item);
                            }
                        }

                        final int count = restoredItems.size();
                        CompletableFuture<Void> chestSaveFuture;
                        if (count > 0) {
                            LocaleManager locale = plugin.getLocaleManager();
                            player.sendMessage(locale.getPrefixedComponent("messages.overflow-items-restored")
                                    .replaceText(builder -> builder.matchLiteral("<count>")
                                            .replacement(String.valueOf(count))));

                            // Save the updated Ender Chest immediately
                            chestSaveFuture = saveEnderChest(uuid, player.getName(), inv);
                        } else {
                            chestSaveFuture = CompletableFuture.completedFuture(null);
                        }

                        chestSaveFuture.thenCompose(v -> {
                            // Update or clear overflow storage
                            CompletableFuture<Void> saveFuture;
                            ItemStack[] remainingArray = remainingOverflow.toArray(new ItemStack[0]);
                            if (remainingOverflow.isEmpty()) {
                                saveFuture = queueStorageOperation(uuid,
                                        () -> plugin.getStorageManager().getStorage().clearOverflowItems(uuid));
                                notifiedOverflowPlayers.remove(uuid);
                            } else {
                                saveFuture = queueStorageOperation(uuid,
                                        () -> plugin.getStorageManager().getStorage().saveOverflowItems(uuid, remainingArray));
                            }
                            return saveFuture;
                        }).thenRun(() -> {
                            // Sync the in-memory activeOverflowItems
                            if (activeOverflowItems.containsKey(uuid)) {
                                activeOverflowItems.put(uuid, remainingOverflow);

                                // If they have the overflow GUI open, refresh/sync the slots
                                Scheduler.runEntityTask(player, () -> {
                                    Inventory openInv = player.getOpenInventory().getTopInventory();
                                    if (overflowInventories.containsKey(openInv)) {
                                        if (remainingOverflow.isEmpty()) {
                                            player.closeInventory();
                                            player.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.overflow-empty"));
                                        } else {
                                            // Clear and refresh GUI slots
                                            openInv.clear();
                                            for (int i = 0; i < Math.min(remainingOverflow.size(), openInv.getSize()); i++) {
                                                openInv.setItem(i, remainingOverflow.get(i));
                                            }
                                        }
                                    }
                                    overallFuture.complete(null);
                                });
                            } else {
                                overallFuture.complete(null);
                            }
                        }).exceptionally(ex -> {
                            EnderChest.trackError(ex);
                            overallFuture.completeExceptionally(ex);
                            return null;
                        });
                    });
                }).exceptionally(ex -> {
                    EnderChest.trackError(ex);
                    overallFuture.completeExceptionally(ex);
                    return null;
                });

        return overallFuture;
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
                    int size = inv.getSize();
                    if (Scheduler.isFolia() && p != null && p.isOnline()) {
                        CompletableFuture<ItemStack[]> snapshotFuture = new CompletableFuture<>();
                        Scheduler.runEntityTask(p, () -> snapshotFuture.complete(cleanInventoryForSave(inv.getContents().clone())));
                        return snapshotFuture.thenCompose(contents -> saveEnderChest(uuid, name, size, contents));
                    }
                    ItemStack[] contents = cleanInventoryForSave(inv.getContents().clone());
                    return saveEnderChest(uuid, name, size, contents);
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

            // Skip if data is locked (being processed elsewhere) or lock acquisition fails
            if (!dataLockManager.lock(uuid)) {
                plugin.getDebugLogger().log("Skipping auto-save for " + uuid + " - failed to acquire lock");
                continue;
            }

            Player p = Bukkit.getPlayer(uuid);

            // CRITICAL: Only auto-save for ONLINE players
            // Offline players' data was already saved on quit, don't overwrite
            if (p == null || !p.isOnline()) {
                plugin.getDebugLogger().log("Skipping auto-save for " + uuid + " - player is offline");
                dataLockManager.unlock(uuid);
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
                        dataLockManager.unlock(uuid);
                        future.complete(null);
                        return;
                    }
                    ItemStack[] contents = cleanInventoryForSave(inv.getContents().clone());
                    // Now save asynchronously
                    saveEnderChest(uuid, name, size, contents)
                            .whenComplete((result, ex) -> {
                                if (ex != null) {
                                    plugin.getLogger().warning("Failed to auto-save data for " + name + ": " + ex.getMessage());
                                } else {
                                    plugin.getDebugLogger().log("Auto-saved data for " + name);
                                }
                                dataLockManager.unlock(uuid);
                                future.complete(null);
                            });
                });
                futures.add(future);
            } else {
                CompletableFuture<Void> future = Scheduler.supplySync(() -> cleanInventoryForSave(inv.getContents().clone()))
                        .thenCompose(contents -> saveEnderChest(uuid, name, size, contents))
                        .whenComplete((result, ex) -> {
                            if (ex != null) {
                                plugin.getLogger().warning("Failed to auto-save data for " + name + ": " + ex.getMessage());
                            } else {
                                plugin.getDebugLogger().log("Auto-saved data for " + name);
                            }
                            dataLockManager.unlock(uuid);
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
        // Snapshot the Bukkit inventory here; cleaning is done once by the array overload.
        ItemStack[] contents = inv.getContents().clone();
        return saveEnderChest(uuid, playerName, inv.getSize(), contents);
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
        ItemStack[] cleanItems = cleanInventoryForSave(items.clone());
        return queueStorageOperation(uuid, () -> {
            // Start timing when the database operation begins, excluding save-chain wait time.
            long startTime = System.nanoTime();
            return plugin.getStorageManager().getStorage()
                    .saveEnderChest(uuid, playerName, size, cleanItems)
                    .orTimeout(15, TimeUnit.SECONDS)
                    .thenRun(() -> recordSaveMetrics(playerName, startTime));
        });
    }

    private void recordSaveMetrics(String playerName, long startTime) {
        long elapsedNanos = System.nanoTime() - startTime;
        long duration = elapsedNanos / 1_000_000;
        plugin.getDebugLogger().log("Data for " + playerName + " saved in " + duration + "ms.");
        if (plugin.getMetricsDataProvider() != null) {
            plugin.getMetricsDataProvider().recordSave();
            plugin.getMetricsDataProvider().recordSaveTime(elapsedNanos);
        }
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

            // Skip checking/resizing if player data is currently locked (loading or saving)
            if (dataLockManager.isLocked(uuid)) {
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
                // Acquire lock to prevent concurrent database operations during resize
                if (!dataLockManager.lock(uuid)) {
                    plugin.getDebugLogger().log("Skipping resize for " + player.getName() + " - failed to acquire data lock");
                    continue;
                }

                // Log at INFO level so admins can see this without debug mode
                plugin.getLogger().info("[Resize] Permission/title change detected for " + player.getName() +
                        " (size: " + openInv.getSize() + " -> " + currentPermissionSize +
                        ", titleMismatch: " + titleMismatched + "). Triggering inventory refresh.");

                // Mark set cooldown to prevent rapid loops
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

                if (currentPermissionSize > cachedInv.getSize()) {
                    // This is an upgrade! Run upgrade restore process
                    checkForUpgradeAndRestore(player).thenRun(() -> {
                        Scheduler.runEntityTask(player, () -> {
                            if (player.isOnline()) {
                                Inventory newResized = getLoadedEnderChest(uuid);
                                if (newResized != null) {
                                    player.openInventory(newResized);
                                    openInventories.put(uuid, newResized);
                                    soundHandler.playSound(player, "open");
                                    player.setItemOnCursor(cursorItem);
                                }
                            }
                        });
                    }).whenComplete((v, ex) -> {
                        dataLockManager.unlock(uuid);
                        if (ex != null) {
                            plugin.getLogger().warning("Error during upgrade restore for " + player.getName() + ": " + ex.getMessage());
                        }
                    });
                } else {
                    // Just a title change or downgrade:
                    // Mark as resizing for the duration of this task
                    resizingPlayers.add(uuid);

                    // Resize the inventory and update cache
                    Inventory resizedInv = resizeInventory(player, cachedInv, currentPermissionSize);
                    liveData.put(uuid, resizedInv);
                    plugin.getDebugLogger().log("Resized inventory cached. New size: " + resizedInv.getSize());

                    // Save the resized inventory immediately to prevent data loss (async, non-blocking)
                    saveEnderChest(uuid, player.getName(), resizedInv)
                            .whenComplete((result, ex) -> {
                                if (ex != null) {
                                    plugin.getLogger().warning("Failed to save resized inventory for " + player.getName() + ": "
                                            + ex.getMessage());
                                } else {
                                    plugin.getDebugLogger().log("Saved resized inventory for " + player.getName());
                                }
                                dataLockManager.unlock(uuid);
                            });

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

    // ==================== Overflow GUI ====================

    /**
     * Open the overflow GUI for a player showing their own overflow items.
     * Player can click items to claim them (with fee check).
     */
    public void openOverflowGUI(Player player) {
        if (player == null || !player.isOnline()) return;

        UUID uuid = player.getUniqueId();
        CompletableFuture<Void> pendingSave = pendingSaves.get(uuid);
        if (pendingSave != null && !pendingSave.isDone()) {
            plugin.getDebugLogger().log("Waiting for pending saves before opening overflow GUI for " + player.getName());
            pendingSave.thenRun(() -> Scheduler.runEntityTask(player, () -> openOverflowGUI(player)));
            return;
        }

        checkForUpgradeAndRestore(player).thenRun(() -> {
            plugin.getStorageManager().getStorage().loadOverflowItems(player.getUniqueId())
                    .thenAccept(overflowItems -> {
                        Scheduler.runEntityTask(player, () -> {
                            if (!player.isOnline()) return;

                            if (overflowItems == null || overflowItems.length == 0) {
                                player.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.overflow-empty"));
                                return;
                            }

                        // Filter out null/air items
                        java.util.List<ItemStack> validItems = new java.util.ArrayList<>();
                        for (ItemStack item : overflowItems) {
                            if (item != null && item.getType() != Material.AIR) {
                                validItems.add(item);
                            }
                        }

                        if (validItems.isEmpty()) {
                            player.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.overflow-empty"));
                            return;
                        }

                        // Calculate inventory size (rows of 9, minimum 9, max 54)
                        int invSize = Math.min(54, Math.max(9, (int) Math.ceil(validItems.size() / 9.0) * 9));

                        // Create title
                        Component title = plugin.getLocaleManager().getComponent("command.overflow-title",
                                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("player", player.getName()));

                        Inventory overflowInv = Bukkit.createInventory(player, invSize, title);

                        // Fill inventory with overflow items
                        for (int i = 0; i < Math.min(validItems.size(), invSize); i++) {
                            overflowInv.setItem(i, validItems.get(i));
                        }

                        // Track as overflow inventory (not admin)
                        overflowInventories.put(overflowInv, false);
                        activeOverflowItems.put(player.getUniqueId(), validItems);

                        player.openInventory(overflowInv);
                        plugin.getSoundHandler().playSound(player, "open");
                        });
                    }).exceptionally(ex -> {
                        EnderChest.trackError(ex);
                        player.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.save-error"));
                        return null;
                    });
        })
        .exceptionally(ex -> {
            EnderChest.trackError(ex);
            player.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.save-error"));
            return null;
        });
    }

    /**
     * Open the overflow GUI for admin viewing another player's overflow.
     * Admin cannot take items - read-only view.
     */
    public void openAdminOverflowGUI(Player admin, String targetName) {
        if (admin == null || !admin.isOnline()) return;

        // Try online player first
        Player targetOnline = Bukkit.getPlayerExact(targetName);
        UUID targetUUID;
        String displayName;

        if (targetOnline != null) {
            targetUUID = targetOnline.getUniqueId();
            displayName = targetOnline.getName();
        } else {
            // Try offline player
            org.bukkit.OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
            if (!target.hasPlayedBefore()) {
                admin.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.player-not-found",
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("player", targetName)));
                return;
            }
            targetUUID = target.getUniqueId();
            displayName = target.getName() != null ? target.getName() : targetName;
        }

        String finalDisplayName = displayName;
        plugin.getStorageManager().getStorage().loadOverflowItems(targetUUID)
                .thenAccept(overflowItems -> {
                    Scheduler.runEntityTask(admin, () -> {
                        if (!admin.isOnline()) return;

                        if (overflowItems == null || overflowItems.length == 0) {
                            admin.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.overflow-admin-empty",
                                    net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("player", finalDisplayName)));
                            return;
                        }

                        // Filter out null/air items
                        java.util.List<ItemStack> validItems = new java.util.ArrayList<>();
                        for (ItemStack item : overflowItems) {
                            if (item != null && item.getType() != Material.AIR) {
                                validItems.add(item);
                            }
                        }

                        if (validItems.isEmpty()) {
                            admin.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.overflow-admin-empty",
                                    net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("player", finalDisplayName)));
                            return;
                        }

                        int invSize = Math.min(54, Math.max(9, (int) Math.ceil(validItems.size() / 9.0) * 9));

                        Component title = plugin.getLocaleManager().getComponent("command.overflow-admin-title",
                                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("player", finalDisplayName));

                        Inventory overflowInv = Bukkit.createInventory(admin, invSize, title);

                        for (int i = 0; i < Math.min(validItems.size(), invSize); i++) {
                            overflowInv.setItem(i, validItems.get(i));
                        }

                        // Track as admin overflow view
                        overflowInventories.put(overflowInv, true);

                        admin.openInventory(overflowInv);
                        plugin.getSoundHandler().playSound(admin, "open");
                    });
                }).exceptionally(ex -> {
                    EnderChest.trackError(ex);
                    admin.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.save-error"));
                    return null;
                });
    }

    /**
     * Handle a click in an overflow inventory.
     * For own overflow: move clicked item to enderchest/inventory, charge fee.
     * For admin overflow: cancel the click (read-only).
     *
     * @return true if this was an overflow click (event should be cancelled), false otherwise
     */
    public boolean handleOverflowClick(Player player, Inventory clickedInv, InventoryClickEvent event) {
        Boolean isAdminView = overflowInventories.get(clickedInv);
        if (isAdminView == null) return false; // Not an overflow inventory

        // Always cancel the click first
        event.setCancelled(true);

        // Admin view is read-only
        if (isAdminView) {
            return true;
        }

        // Own overflow - claim the clicked item
        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || clickedItem.getType() == Material.AIR) {
            return true;
        }

        UUID uuid = player.getUniqueId();
        List<ItemStack> activeOverflowList = activeOverflowItems.get(uuid);
        if (activeOverflowList == null) {
            return true;
        }

        // Ensure player data is fully loaded
        Inventory loadedChest = liveData.getIfPresent(uuid);
        if (loadedChest == null) {
            player.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.data-still-loading"));
            return true;
        }

        // Check if player permission size is upgraded
        int permissionSize = EnderChestUtils.getSize(player);
        if (permissionSize > loadedChest.getSize()) {
            // Upgrade detected! Run the upgrade restore process and cancel the click
            checkForUpgradeAndRestore(player);
            return true;
        }

        // Clone the item to safely calculate fit space
        ItemStack fitItem = clickedItem.clone();
        int originalAmount = fitItem.getAmount();

        // Calculate how much fits in player's inventory only
        int totalFitAmount = calculateFitAmount(player.getInventory(), fitItem);
        if (totalFitAmount <= 0) {
            player.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.overflow-enderchest-full"));
            return true;
        }

        // Charge retrieval fee if enabled
        OverflowManager overflowMgr = plugin.getOverflowManager();
        if (overflowMgr != null && overflowMgr.shouldChargeFee()) {
            if (!overflowMgr.chargeRetrievalFee(player, totalFitAmount)) {
                // Can't afford - don't claim
                return true;
            }
        }

        // Add to player's inventory
        ItemStack toInventory = fitItem.clone();
        toInventory.setAmount(totalFitAmount);
        player.getInventory().addItem(toInventory);

        // Update the GUI inventory slot
        int newAmount = originalAmount - totalFitAmount;
        if (newAmount <= 0) {
            clickedInv.setItem(event.getSlot(), null);
        } else {
            ItemStack remainingGuiItem = clickedItem.clone();
            remainingGuiItem.setAmount(newAmount);
            clickedInv.setItem(event.getSlot(), remainingGuiItem);
        }

        // Update the in-memory active overflow list
        for (int i = 0; i < activeOverflowList.size(); i++) {
            ItemStack item = activeOverflowList.get(i);
            if (item != null && item.isSimilar(clickedItem)) {
                int listAmount = item.getAmount();
                if (listAmount <= totalFitAmount) {
                    activeOverflowList.remove(i);
                    totalFitAmount -= listAmount;
                    i--; // adjust index after removal
                } else {
                    item.setAmount(listAmount - totalFitAmount);
                    totalFitAmount = 0;
                }
                if (totalFitAmount <= 0) {
                    break;
                }
            }
        }

        // Save updated overflow to database immediately to prevent duplication on crash
        List<ItemStack> cleanOverflow = new ArrayList<>();
        for (ItemStack item : activeOverflowList) {
            if (item != null && item.getType() != Material.AIR) {
                cleanOverflow.add(item);
            }
        }
        CompletableFuture<Void> overflowSaveFuture;
        if (cleanOverflow.isEmpty()) {
            overflowSaveFuture = queueStorageOperation(uuid,
                    () -> plugin.getStorageManager().getStorage().clearOverflowItems(uuid));
            notifiedOverflowPlayers.remove(uuid);
        } else {
            ItemStack[] overflowArray = cleanOverflow.toArray(new ItemStack[0]);
            overflowSaveFuture = queueStorageOperation(uuid,
                    () -> plugin.getStorageManager().getStorage().saveOverflowItems(uuid, overflowArray));
        }
        overflowSaveFuture.exceptionally(ex -> {
            EnderChest.trackError(ex);
            return null;
        });
        trackPendingSave(uuid, overflowSaveFuture);

        player.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.overflow-claimed",
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("count", String.valueOf(originalAmount - newAmount))));

        return true;
    }

    /**
     * Calculate how many items of a given ItemStack can fit in the storage contents of an inventory.
     */
    private int calculateFitAmount(Inventory inv, ItemStack item) {
        int maxStack = item.getMaxStackSize();
        int space = 0;
        for (ItemStack slotItem : inv.getStorageContents()) {
            if (slotItem == null || slotItem.getType() == Material.AIR) {
                space += maxStack;
            } else if (slotItem.isSimilar(item)) {
                space += Math.max(0, maxStack - slotItem.getAmount());
            }
        }
        return Math.min(item.getAmount(), space);
    }

    /**
     * Handle overflow inventory close - clean up tracking and save player data.
     */
    public void handleOverflowClose(Player player, Inventory closedInv) {
        overflowInventories.remove(closedInv);
        UUID uuid = player.getUniqueId();
        List<ItemStack> remaining = activeOverflowItems.remove(uuid);
        if (remaining != null) {
            // Save remaining overflow items to storage
            // Filter out null/air items
            List<ItemStack> cleanRemaining = new ArrayList<>();
            for (ItemStack item : remaining) {
                if (item != null && item.getType() != Material.AIR) {
                    cleanRemaining.add(item);
                }
            }

            CompletableFuture<Void> saveFuture;
            if (cleanRemaining.isEmpty()) {
                saveFuture = queueStorageOperation(uuid,
                        () -> plugin.getStorageManager().getStorage().clearOverflowItems(uuid));
            } else {
                ItemStack[] arr = cleanRemaining.toArray(new ItemStack[0]);
                saveFuture = queueStorageOperation(uuid,
                        () -> plugin.getStorageManager().getStorage().saveOverflowItems(uuid, arr));
            }
            saveFuture.exceptionally(ex -> {
                EnderChest.trackError(ex);
                return null;
            });
            trackPendingSave(uuid, saveFuture);

            // Immediately save Ender Chest contents to database
            Inventory enderchestInv = liveData.getIfPresent(uuid);
            if (enderchestInv != null) {
                saveEnderChest(uuid, player.getName(), enderchestInv)
                        .exceptionally(ex -> {
                            EnderChest.trackError(ex);
                            return null;
                        });
            }
        }
    }
}
