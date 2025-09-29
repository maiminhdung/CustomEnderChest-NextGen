package org.maiminhdung.customenderchest.data;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.Getter;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
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
import java.util.logging.Level;

public class EnderChestManager {

    private final EnderChest plugin;
    private final SoundHandler soundHandler;
    private final DataLockManager dataLockManager;
    private final Cache<UUID, Inventory> liveData;
    private final Scheduler.Task autoSaveTask;
    private final Scheduler.Task inventoryTrackerTask;
    private final NamespacedKey lockedItemKey;

    @Getter private final Map<Inventory, UUID> adminViewedChests = new ConcurrentHashMap<>();
    @Getter private final Map<UUID, Inventory> openInventories = new ConcurrentHashMap<>();
    private final Set<UUID> resizingPlayers = ConcurrentHashMap.newKeySet();

    public EnderChestManager(EnderChest plugin) {
        this.plugin = plugin;
        this.soundHandler = plugin.getSoundHandler();
        this.dataLockManager = plugin.getDataLockManager();
        this.lockedItemKey = new NamespacedKey(plugin, "locked_item_tag");

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
        // Start the inventory tracker task
        this.inventoryTrackerTask = Scheduler.runTaskTimer(this::checkOpenInventories, 20L, 20L); // Run every second
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
            plugin.getDebugLogger().log("Attempted to load data for " + player.getName() + ", but their data is currently locked.");
            return;
        }

        plugin.getDebugLogger().log("Data lock acquired for " + player.getName() + ". Checking storage...");
        long startTime = System.nanoTime(); // DEBUG: Start timer

        plugin.getStorageManager().getStorage().loadEnderChest(player.getUniqueId())
                .whenComplete((items, error) -> {
                    // Check if player is still online before processing
                    if (!player.isOnline()) {
                        dataLockManager.unlock(player.getUniqueId());
                        return;
                    }

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
                                if (items.length <= size) {
                                    inv.setContents(items);
                                } else {
                                    for (int i = 0; i < size; i++) {
                                        inv.setItem(i, items[i]);
                                    }
                                }
                            }
                            liveData.put(player.getUniqueId(), inv);

                            long duration = (System.nanoTime() - startTime) / 1_000_000; // DEBUG: End timer
                            plugin.getDebugLogger().log("Cache is ready for " + player.getName() + ". (Load time: " + duration + "ms)");
                        } finally {
                            dataLockManager.unlock(player.getUniqueId());
                            plugin.getDebugLogger().log("Data lock released for " + player.getName());
                        }
                    });
                });
    }

    // Save player data when they leave the server.
    public void onPlayerQuit(Player player) {
        // Stop tracking this inventory as it's being closed
        openInventories.remove(player.getUniqueId());

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
        if (autoSaveTask != null) { autoSaveTask.cancel();}
        if (inventoryTrackerTask != null) inventoryTrackerTask.cancel(); // Cancel the inventory tracker task
        plugin.getLogger().info("Auto-save task cancelled. Saving all cached player data before shutting down...");
        shutdownSave().join();
        plugin.getLogger().info("All player data has been saved successfully.");
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
        ItemStack[] contents = inv.getContents();
        int lastItemIndex = -1;
        for (int i = contents.length - 1; i >= 0; i--) {
            ItemStack item = contents[i];
            if (item != null && item.getType() != Material.AIR) {
                ItemMeta meta = item.getItemMeta();
                if (item.getType() != Material.BARRIER && (meta == null || !meta.getPersistentDataContainer().has(lockedItemKey))) {
                    lastItemIndex = i;
                    break;
                }
            }
        }

        int expectedDisplaySize = permissionSize;
        if (lastItemIndex >= permissionSize) {
            expectedDisplaySize = (int) (Math.ceil((lastItemIndex + 1) / 9.0)) * 9;
        }
        expectedDisplaySize = Math.max(permissionSize, expectedDisplaySize);

        if (inv.getSize() != expectedDisplaySize) {
            inv = resizeInventory(player, inv, permissionSize);
            liveData.put(player.getUniqueId(), inv);
        }

        player.openInventory(inv);
        openInventories.put(player.getUniqueId(), inv); // Start tracking this inventory
        soundHandler.playSound(player, "open");
    }

    // Resize the inventory while preserving existing items.
    private Inventory resizeInventory(Player player, Inventory oldInv, int newSize) {
        plugin.getDebugLogger().log("Resizing " + player.getName() + "'s inventory. Old: " + oldInv.getSize() + ", New Size: " + newSize);
        ItemStack[] oldContents = oldInv.getContents();

        // Debug: Log all items in old inventory
        plugin.getDebugLogger().log("Old inventory contents:");
        for (int i = 0; i < oldContents.length; i++) {
            ItemStack item = oldContents[i];
            if (item != null && item.getType() != Material.AIR) {
                ItemMeta meta = item.getItemMeta();
                boolean isLocked = meta != null && meta.getPersistentDataContainer().has(lockedItemKey);
                plugin.getDebugLogger().log("  Slot " + i + ": " + item.getType() + " x" + item.getAmount() + (isLocked ? " [LOCKED]" : ""));
            }
        }

        // Find last real item (not barrier or locked)
        int lastRealItemIndex = -1;
        // Find last non-barrier item (including locked items)
        int lastNonBarrierItemIndex = -1;

        for (int i = oldContents.length - 1; i >= 0; i--) {
            ItemStack item = oldContents[i];
            if (item != null && item.getType() != Material.AIR) {
                ItemMeta meta = item.getItemMeta();
                boolean isBarrier = item.getType() == Material.BARRIER;
                boolean isLocked = meta != null && meta.getPersistentDataContainer().has(lockedItemKey);

                // Track last non-barrier item (could be locked or unlocked real item)
                if (lastNonBarrierItemIndex == -1 && !isBarrier) {
                    lastNonBarrierItemIndex = i;
                }

                // Track last real unlocked item
                if (!isBarrier && !isLocked) {
                    lastRealItemIndex = i;
                    break;
                }
            }
        }

        int displaySize = newSize;

        // If there are real items beyond permission size, expand to fit them
        if (lastRealItemIndex >= newSize) {
            displaySize = (int) (Math.ceil((lastRealItemIndex + 1) / 9.0)) * 9;
        }
        // If there are locked items beyond permission size, keep the old size to preserve them
        else if (lastNonBarrierItemIndex >= newSize) {
            displaySize = oldInv.getSize();
        }

        displaySize = Math.max(newSize, displaySize); // Ensure display size is at least the new permission size

        plugin.getDebugLogger().log("Last real item index: " + lastRealItemIndex + ", Last non-barrier item index: " + lastNonBarrierItemIndex + ", Display size: " + displaySize + ", Permission size: " + newSize);

        Component title = EnderChestUtils.getTitle(player);
        Inventory newInv = Bukkit.createInventory(player, displaySize, title);

        for (int i = 0; i < displaySize; i++) {
            boolean isAccessible = i < newSize;
            ItemStack oldItem = (i < oldContents.length) ? oldContents[i] : null;

            if (isAccessible) {
                // For accessible slots, only set real items (remove barriers and unlock locked items)
                if (oldItem != null && oldItem.getType() != Material.AIR) {
                    if (oldItem.getType() == Material.BARRIER) {
                        // Don't carry over barriers to accessible slots
                        plugin.getDebugLogger().log("  Slot " + i + ": Skipping barrier (accessible slot)");
                        continue;
                    }
                    ItemStack unlockedItem = createUnlockedItem(oldItem);
                    if (unlockedItem != null) {
                        newInv.setItem(i, unlockedItem);
                        plugin.getDebugLogger().log("  Slot " + i + ": Added unlocked item " + unlockedItem.getType());
                    }
                }
            } else {
                // For locked slots, preserve existing items as locked or add barriers
                if (oldItem != null && oldItem.getType() != Material.AIR) {
                    ItemMeta meta = oldItem.getItemMeta();
                    // Check if item is already a barrier or locked item - keep as is
                    if (oldItem.getType() == Material.BARRIER || (meta != null && meta.getPersistentDataContainer().has(lockedItemKey))) {
                        newInv.setItem(i, oldItem);
                        plugin.getDebugLogger().log("  Slot " + i + ": Kept existing locked item/barrier " + oldItem.getType());
                    } else {
                        // Convert regular items to locked items
                        ItemStack lockedItem = createLockedItem(oldItem);
                        newInv.setItem(i, lockedItem);
                        plugin.getDebugLogger().log("  Slot " + i + ": Converted to locked item " + lockedItem.getType());
                    }
                } else {
                    // Add barrier for empty locked slots
                    newInv.setItem(i, createBarrierItem());
                    plugin.getDebugLogger().log("  Slot " + i + ": Added barrier (empty locked slot)");
                }
            }
        }

        plugin.getDebugLogger().log("Resize complete. New inventory size: " + newInv.getSize());
        return newInv;
    }

    // Create a locked item with lore indicating it's in a locked slot.
    private ItemStack createLockedItem(ItemStack original) {
        if (original.getType() == Material.BARRIER) return original; // Don't process barriers
        ItemMeta meta = original.getItemMeta();
        if (meta == null) return original;

        // Check for the PDC tag to prevent duplicate lore
        if (meta.getPersistentDataContainer().has(lockedItemKey, PersistentDataType.BYTE)) {
            return original;
        }

        ItemStack lockedItem = original.clone();
        meta = lockedItem.getItemMeta(); // Get meta from the clone
        LocaleManager localeManager = plugin.getLocaleManager();

        List<Component> newLore = new ArrayList<>(localeManager.getComponentList("items.locked-item-lore"));
        List<Component> oldLore = meta.hasLore() ? meta.lore() : new ArrayList<>();
        if (oldLore != null && !oldLore.isEmpty()) {
            oldLore.addAll(newLore);
            newLore = oldLore;
        }
        meta.lore(newLore);

        // Add the PDC tag
        meta.getPersistentDataContainer().set(lockedItemKey, PersistentDataType.BYTE, (byte) 1);
        lockedItem.setItemMeta(meta);
        return lockedItem;
    }

    private ItemStack createUnlockedItem(ItemStack potentiallyLockedItem) {
        if (potentiallyLockedItem == null || potentiallyLockedItem.getType() == Material.AIR) {
            return null; // Nothing to unlock
        }

        if (potentiallyLockedItem.getType() == Material.BARRIER) {
            return null; // Remove barriers from accessible slots
        }

        ItemMeta meta = potentiallyLockedItem.getItemMeta();
        if (meta == null || !meta.getPersistentDataContainer().has(lockedItemKey, PersistentDataType.BYTE)) {
            return potentiallyLockedItem; // Not a locked item, return as is
        }

        ItemStack unlockedItem = potentiallyLockedItem.clone();
        meta = unlockedItem.getItemMeta(); // Get meta from the clone

        // Remove the PDC tag
        meta.getPersistentDataContainer().remove(lockedItemKey);

        // Remove the lore
        if (meta.hasLore()) {
            List<Component> currentLore = Objects.requireNonNull(meta.lore());
            List<Component> loreToRemove = plugin.getLocaleManager().getComponentList("items.locked-item-lore");
            currentLore.removeAll(loreToRemove);
            meta.lore(currentLore);
        }

        unlockedItem.setItemMeta(meta);
        return unlockedItem;
    }

    private ItemStack createBarrierItem() {
        ItemStack barrier = new ItemStack(Material.BARRIER);
        ItemMeta meta = barrier.getItemMeta();
        LocaleManager localeManager = plugin.getLocaleManager();
        if (meta != null) {
            meta.displayName(localeManager.getComponent("items.locked-barrier-name"));
            meta.lore(localeManager.getComponentList("items.locked-item-lore"));
            meta.getPersistentDataContainer().set(lockedItemKey, PersistentDataType.BYTE, (byte) 1);
            barrier.setItemMeta(meta);
        }
        return barrier;
    }

    // Force-save all cached data during server shutdown to prevent data loss.
    private CompletableFuture<Void> shutdownSave() {
        Set<Map.Entry<UUID, Inventory>> cacheSnapshot = new java.util.HashSet<>(liveData.asMap().entrySet());
        if (cacheSnapshot.isEmpty()) return CompletableFuture.completedFuture(null);
        plugin.getLogger().info("Force-saving data for " + cacheSnapshot.size() + " players...");
        CompletableFuture<?>[] futures = cacheSnapshot.stream()
                .map(entry -> {
                    UUID uuid = entry.getKey();
                    Player p = Bukkit.getPlayer(uuid);
                    String name = (p != null) ? p.getName() : Bukkit.getOfflinePlayer(uuid).getName();
                    return saveEnderChest(uuid, name, entry.getValue());
                })
                .filter(Objects::nonNull).toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures);
    }

    // Force-save all cached data during server shutdown to prevent data loss.
    private CompletableFuture<Void> autoSaveAll() {
        Set<Map.Entry<UUID, Inventory>> cacheSnapshot = new java.util.HashSet<>(liveData.asMap().entrySet());
        if (cacheSnapshot.isEmpty()) return CompletableFuture.completedFuture(null);
        plugin.getDebugLogger().log("Auto-saving data for " + cacheSnapshot.size() + " online players...");
        CompletableFuture<?>[] futures = cacheSnapshot.stream()
                .map(entry -> {
                    UUID uuid = entry.getKey();
                    if (dataLockManager.isLocked(uuid)) return null;
                    Player p = Bukkit.getPlayer(uuid);
                    String name = (p != null) ? p.getName() : null;
                    return saveEnderChest(uuid, name, entry.getValue());
                })
                .filter(Objects::nonNull).toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures);
    }

    // Save ender chest data with inventory object, used for online players.
    public CompletableFuture<Void> saveEnderChest(UUID uuid, String playerName, Inventory inv) {
        long startTime = System.nanoTime(); // DEBUG: Start timer

        // Clean the inventory before saving - remove barriers and unlock locked items
        ItemStack[] cleanedContents = cleanInventoryForSave(inv.getContents());

        return plugin.getStorageManager().getStorage()
                .saveEnderChest(uuid, playerName, inv.getSize(), cleanedContents)
                .thenRun(() -> {
                    long duration = (System.nanoTime() - startTime) / 1_000_000; // DEBUG: End timer
                    plugin.getDebugLogger().log("Data for " + playerName + " saved in " + duration + "ms.");
                });
    }

    // Clean inventory contents for saving - remove barriers and unlock locked items
    private ItemStack[] cleanInventoryForSave(ItemStack[] contents) {
        ItemStack[] cleaned = new ItemStack[contents.length];

        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];

            if (item == null || item.getType() == Material.AIR) {
                cleaned[i] = null;
                continue;
            }

            // Remove barriers completely
            if (item.getType() == Material.BARRIER) {
                cleaned[i] = null;
                continue;
            }

            // Unlock locked items
            ItemMeta meta = item.getItemMeta();
            if (meta != null && meta.getPersistentDataContainer().has(lockedItemKey)) {
                cleaned[i] = createUnlockedItem(item);
            } else {
                cleaned[i] = item;
            }
        }

        return cleaned;
    }

    // Save ender chest data with specified size and items, used for offline players.
    public CompletableFuture<Void> saveEnderChest(UUID uuid, String playerName, int size, ItemStack[] items) {
        return plugin.getStorageManager().getStorage().saveEnderChest(uuid, playerName, size, items);
    }

    // Get the cached inventory for a player, or null if not loaded.
    public Inventory getLoadedEnderChest(UUID uuid) {
        return liveData.getIfPresent(uuid);
    }

    // Tracker for currently open ender chest inventories.
    private void checkOpenInventories() {
        if (openInventories.isEmpty()) return;

        for (UUID uuid : new ArrayList<>(openInventories.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            // Stop tracking if player is offline or not viewing our inventory anymore
            if (player == null || !player.isOnline() || player.getOpenInventory().getTopInventory() != openInventories.get(uuid)) {
                openInventories.remove(uuid);
                resizingPlayers.remove(uuid);
                continue;
            }

            // Skip if already resizing to prevent double-resize
            if (resizingPlayers.contains(uuid)) {
                continue;
            }

            Inventory openInv = openInventories.get(uuid);
            int currentPermissionSize = EnderChestUtils.getSize(player);

            // Get the cached inventory to check against, not the open one
            Inventory cachedInv = liveData.getIfPresent(uuid);
            if (cachedInv == null) {
                continue; // No cached data, skip
            }

            // Calculate what the display size should be based on current permissions and items in CACHE
            // This logic MUST match the logic in resizeInventory()
            ItemStack[] contents = cachedInv.getContents();

            int lastRealItemIndex = -1;
            int lastNonBarrierItemIndex = -1;

            for (int i = contents.length - 1; i >= 0; i--) {
                ItemStack item = contents[i];
                if (item != null && item.getType() != Material.AIR) {
                    ItemMeta meta = item.getItemMeta();
                    boolean isBarrier = item.getType() == Material.BARRIER;
                    boolean isLocked = meta != null && meta.getPersistentDataContainer().has(lockedItemKey);

                    if (lastNonBarrierItemIndex == -1 && !isBarrier) {
                        lastNonBarrierItemIndex = i;
                    }

                    if (!isBarrier && !isLocked) {
                        lastRealItemIndex = i;
                        break;
                    }
                }
            }

            int expectedDisplaySize = currentPermissionSize;

            // If there are real items beyond permission size, expand to fit them
            if (lastRealItemIndex >= currentPermissionSize) {
                expectedDisplaySize = (int) (Math.ceil((lastRealItemIndex + 1) / 9.0)) * 9;
            }
            // If there are locked items beyond permission size, keep the old size to preserve them
            else if (lastNonBarrierItemIndex >= currentPermissionSize) {
                expectedDisplaySize = cachedInv.getSize();
            }

            expectedDisplaySize = Math.max(currentPermissionSize, expectedDisplaySize);

            // Check 1: Size Mismatch (Reliable)
            boolean sizeMismatched = cachedInv.getSize() != expectedDisplaySize;

            // Check 2: Title Mismatch (More reliable string comparison)
            Component expectedTitleComponent = EnderChestUtils.getTitle(player);
            Component actualTitleComponent = player.getOpenInventory().title();

            // Serialize to plain string to avoid object comparison issues
            String expectedTitle = LegacyComponentSerializer.legacySection().serialize(expectedTitleComponent);
            String actualTitle = LegacyComponentSerializer.legacySection().serialize(actualTitleComponent);
            boolean titleMismatched = !expectedTitle.equals(actualTitle);

            if (sizeMismatched || titleMismatched) {
                plugin.getDebugLogger().log("State change for " + player.getName() + ". Refreshing inventory. Size mismatch: " + sizeMismatched + ", Title mismatch: " + titleMismatched);

                // Mark as resizing to prevent double-resize
                resizingPlayers.add(uuid);

                // Remove from tracking first to prevent infinite loops
                openInventories.remove(uuid);

                ItemStack cursorItem = player.getItemOnCursor();
                player.setItemOnCursor(null);

                // Close current inventory first
                player.closeInventory();

                // Resize the inventory and update cache - use cached inventory as source!
                Inventory resizedInv = resizeInventory(player, cachedInv, currentPermissionSize);
                liveData.put(uuid, resizedInv);
                plugin.getDebugLogger().log("Resized inventory cached. New size: " + resizedInv.getSize() + ", Expected: " + expectedDisplaySize);

                // Use a delayed task to prevent issues with immediate reopening
                Scheduler.runTaskLater(() -> {
                    if (player.isOnline()) {
                        player.openInventory(resizedInv);
                        openInventories.put(uuid, resizedInv);
                        soundHandler.playSound(player, "open");
                        player.setItemOnCursor(cursorItem);
                    }

                    // Clear resizing flag AFTER reopening and add extra delay to prevent tracker interference
                    Scheduler.runTaskLater(() -> {
                        resizingPlayers.remove(uuid);
                    }, 5L);
                }, 2L); // Wait 2 ticks before reopening
            }
        }
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

