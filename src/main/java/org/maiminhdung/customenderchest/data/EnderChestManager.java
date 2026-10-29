package org.maiminhdung.customenderchest.data;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalCause;
import com.google.common.cache.RemovalListener;
import lombok.Getter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.maiminhdung.customenderchest.api.events.EnderVaultOpenEvent;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.Scheduler;
import org.maiminhdung.customenderchest.locale.LocaleManager;
import org.maiminhdung.customenderchest.storage.StorageInterface;
import org.maiminhdung.customenderchest.utils.DataLockManager;
import org.maiminhdung.customenderchest.utils.EnderChestUtils;
import org.maiminhdung.customenderchest.utils.SoundHandler;
import org.maiminhdung.customenderchest.utils.VaultKey;
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
import java.util.function.Supplier;

/**
 * Cache + load/save + resize + overflow + open-state tracking for the
 * multi-vault system. Vault 1 is the legacy Ender Chest.
 */
public class EnderChestManager {

    private final EnderChest plugin;
    private final SoundHandler soundHandler;
    private final DataLockManager dataLockManager;
    @Getter
    private final Cache<VaultKey, Inventory> liveData;
    private final Scheduler.Task autoSaveTask;
    private final Scheduler.Task inventoryTrackerTask;

    /** Admin view inventory -> viewed vault. */
    @Getter
    private final Map<Inventory, VaultKey> adminViewedChests = new ConcurrentHashMap<>();
    /** Player -> vault id of their currently open own vault. */
    @Getter
    private final Map<UUID, Integer> openVaults = new ConcurrentHashMap<>();

    /** Describes an open overflow GUI. */
    public record OverflowView(UUID targetPlayer, int vaultId, boolean admin) {
    }

    @Getter
    private final Map<Inventory, OverflowView> overflowViews = new ConcurrentHashMap<>();
    private final Map<VaultKey, List<ItemStack>> activeOverflowItems = new ConcurrentHashMap<>();
    /** Aggregate overflow session (multi-vault disabled): merged list + per-vault sources. */
    private final Map<UUID, List<ItemStack>> aggregateOverflowItems = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Integer, List<ItemStack>>> aggregateOverflowLists = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Void>> pendingSaves = new ConcurrentHashMap<>();
    private final Set<VaultKey> resizingVaults = ConcurrentHashMap.newKeySet();
    private final Set<VaultKey> notifiedOverflowVaults = ConcurrentHashMap.newKeySet();
    private final Map<UUID, CompletableFuture<Void>> saveChains = new ConcurrentHashMap<>();
    private final Map<VaultKey, Long> resizeCooldowns = new ConcurrentHashMap<>();
    private static final long RESIZE_COOLDOWN_MS = 5000; // 5 second cooldown between resizes

    public EnderChestManager(EnderChest plugin) {
        this.plugin = plugin;
        this.soundHandler = plugin.getSoundHandler();
        this.dataLockManager = plugin.getDataLockManager();

        // Use Guava Cache to automatically clean up data for players who have been
        // offline for a while.
        this.liveData = CacheBuilder.newBuilder()
                .expireAfterAccess(30, TimeUnit.MINUTES)
                .removalListener((RemovalListener<VaultKey, Inventory>) notification -> {
                    if (notification.getCause() == RemovalCause.EXPIRED) {
                        VaultKey key = notification.getKey();
                        if (key != null) {
                            Player player = Bukkit.getPlayer(key.playerId());
                            if (player != null && player.isOnline()) {
                                // Player is online, re-cache to prevent eviction
                                Inventory inv = notification.getValue();
                                if (inv != null) {
                                    Scheduler.runEntityTask(player, () -> {
                                        if (player.isOnline()) {
                                            getLiveData().put(key, inv);
                                            plugin.getDebugLogger().log("Prevented cache eviction of online player: "
                                                    + player.getName() + " vault " + key.vaultId());
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
        // Runs every second so permission changes apply quickly
        this.inventoryTrackerTask = Scheduler.runTaskTimer(this::checkOpenInventories, 20L, 20L);
    }

    // ==================== Join / lazy load ====================

    // Load vault 1 when the player joins the server.
    public void onPlayerJoin(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        VaultKey key = new VaultKey(player.getUniqueId(), 1);

        // Check if data is already cached to avoid unnecessary database calls
        if (getLiveData().getIfPresent(key) != null) {
            plugin.getDebugLogger().log("Data for " + player.getName() + " (vault 1) is already cached. Skipping load.");
            return;
        }

        if (!dataLockManager.lock(key)) {
            plugin.getDebugLogger()
                    .log("Attempted to load data for " + player.getName() + ", but their data is currently locked.");
            return;
        }

        plugin.getDebugLogger().log("Data lock acquired for " + player.getName() + ". Checking storage...");
        long startTime = System.nanoTime();

        final UUID currentUUID = player.getUniqueId();
        final String playerName = player.getName();

        plugin.getStorageManager().getStorage().loadVault(currentUUID, 1)
                .orTimeout(15, TimeUnit.SECONDS)
                .thenCompose(vaultData -> {
                    // If no data found for current UUID, try to find data by player name
                    // This handles the case where player switches between online/offline mode
                    if (vaultData == null && plugin.config().getBoolean("storage.migrate-uuid-by-name", false)) {
                        plugin.getDebugLogger().log("No data found for UUID " + currentUUID + ", searching by name: " + playerName);
                        return plugin.getStorageManager().getStorage().findUUIDByName(playerName)
                                .thenCompose(oldUUID -> {
                                    if (oldUUID != null && !oldUUID.equals(currentUUID)) {
                                        plugin.getDebugLogger().log("Found existing data under old UUID: " + oldUUID + " for player " + playerName);
                                        return migrateVaultsBetweenUuids(player, oldUUID, currentUUID)
                                                .thenCompose(v -> plugin.getStorageManager().getStorage().loadVault(currentUUID, 1));
                                    }
                                    return CompletableFuture.completedFuture((StorageInterface.VaultData) null);
                                });
                    }
                    return CompletableFuture.completedFuture(vaultData);
                })
                .whenComplete((vaultData, error) -> {
                    // Check if player is still online before processing
                    if (!player.isOnline()) {
                        dataLockManager.unlock(key);
                        return;
                    }

                    Scheduler.runEntityTask(player, () -> {
                        try {
                            if (error != null) {
                                plugin.getLogger().log(Level.SEVERE, "Failed to load data for " + player.getName(), error);
                                return;
                            }

                            applyVaultData(player, 1, vaultData, startTime);

                            // Send overflow login warning (async, non-blocking)
                            if (plugin.getOverflowManager() != null) {
                                plugin.getOverflowManager().sendLoginWarning(player);
                            }
                        } finally {
                            dataLockManager.unlock(key);
                            plugin.getDebugLogger().log("Data lock released for " + player.getName());
                        }
                    });
                });
    }

    /**
     * Applies loaded storage data for one vault on the player's entity thread.
     * Caller must hold the {@link VaultKey} data lock.
     */
    private void applyVaultData(Player player, int vaultId, StorageInterface.VaultData vaultData, long startTime) {
        VaultKey key = new VaultKey(player.getUniqueId(), vaultId);

        // null means the player has NO data for this vault. We should NOT cache an
        // empty inventory unconditionally, but for a new player/vault it is safe.
        if (vaultData == null) {
            plugin.getDebugLogger().log("No existing data found for " + player.getName() + " vault " + vaultId
                    + " - new player or unused vault");
            int size = EnderChestUtils.getSize(player, vaultId);
            if (size > 0) {
                Component title = EnderChestUtils.getTitle(player, vaultId);
                Inventory inv = Bukkit.createInventory(player, size, title);
                liveData.put(key, inv);
                plugin.getDebugLogger().log("Created new empty vault " + vaultId + " for " + player.getName());
            }
            return;
        }

        int size = EnderChestUtils.getSize(player, vaultId);
        Component title = EnderChestUtils.getTitle(player, vaultId);
        Inventory inv = Bukkit.createInventory(player, (size > 0 ? size : 9), title);
        ItemStack[] items = vaultData.items;

        // Empty items with a stored size means deserialization failed - DO NOT cache
        // an empty inventory, that would delete their data on the next save!
        if (items == null || items.length == 0) {
            if (vaultData.size > 0) {
                plugin.getLogger().warning("[DATA PROTECTION] Player " + player.getName()
                        + " has corrupted/incompatible data in vault " + vaultId + " (saved size: " + vaultData.size
                        + "). NOT loading empty inventory to prevent data loss.");
                LocaleManager locale = plugin.getLocaleManager();
                player.sendMessage(locale.getPrefixedComponent(player, "messages.migration-data-incompatible"));
                player.sendMessage(locale.getPrefixedComponent(player, "messages.migration-data-cleared"));
                player.sendMessage(locale.getPrefixedComponent(player, "messages.migration-contact-admin"));
            } else {
                // Player truly has no data, safe to create empty inventory
                plugin.getDebugLogger().log("Player " + player.getName() + " vault " + vaultId
                        + " has no saved items, creating empty inventory");
                liveData.put(key, inv);
            }
            return;
        }

        if (size > 0) {
            if (items.length <= size) {
                // Properly set items - ensure array matches inventory size
                ItemStack[] properSizedItems = new ItemStack[size];
                System.arraycopy(items, 0, properSizedItems, 0, items.length);
                inv.setContents(properSizedItems);
                plugin.getDebugLogger().log("Loaded " + items.length + " items for " + player.getName()
                        + " vault " + vaultId + " into inventory of size " + size);
            } else {
                // Player has items beyond their current permission limit
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
                            () -> plugin.getStorageManager().getStorage()
                                    .saveOverflowItems(player.getUniqueId(), vaultId, overflowArray))
                            .thenRun(() -> {
                                plugin.getDebugLogger().log("Saved " + overflowItems.size()
                                        + " overflow items for " + player.getName() + " vault " + vaultId + " on join");

                                Scheduler.runEntityTask(player, () -> {
                                    LocaleManager locale = plugin.getLocaleManager();
                                    player.sendMessage(locale.getPrefixedComponent(player, "messages.overflow-items-saved"));
                                    player.sendMessage(locale.getPrefixedComponent(player, "messages.overflow-will-restore",
                                            Placeholder.unparsed("command", plugin.getPrimaryCommand() + " overflow")));
                                });
                            });
                }
            }
        }
        liveData.put(key, inv);

        long duration = (System.nanoTime() - startTime) / 1_000_000;
        plugin.getDebugLogger().log(
                "Cache is ready for " + player.getName() + " vault " + vaultId + ". (Load time: " + duration + "ms)");

        // Metrics tracking
        if (plugin.getMetricsDataProvider() != null) {
            plugin.getMetricsDataProvider().recordLoad();
        }
    }

    /**
     * Moves every stored vault (chest + overflow) from oldUUID to the player's
     * current UUID. Handles online/offline mode switches.
     */
    private CompletableFuture<Void> migrateVaultsBetweenUuids(Player player, UUID oldUUID, UUID newUUID) {
        plugin.getLogger().info("[Migration] Migrating data for " + player.getName() + " from UUID " + oldUUID + " to " + newUUID);
        StorageInterface storage = plugin.getStorageManager().getStorage();

        return storage.getVaultIds(oldUUID).thenCompose(vaultIds -> {
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (int vaultId : vaultIds) {
                chain = chain.thenCompose(v -> storage.loadVault(oldUUID, vaultId)
                        .thenCompose(vaultData -> {
                            if (vaultData == null || vaultData.items == null || vaultData.items.length == 0) {
                                return CompletableFuture.completedFuture(null);
                            }
                            return storage.saveVault(newUUID, player.getName(), vaultId, vaultData.size, vaultData.items)
                                    .thenCompose(v2 -> storage.loadOverflowItems(oldUUID, vaultId))
                                    .thenCompose(oldOverflow -> {
                                        if (oldOverflow != null && oldOverflow.length > 0) {
                                            return storage.saveOverflowItems(newUUID, vaultId, oldOverflow)
                                                    .thenCompose(v3 -> storage.clearOverflowItems(oldUUID, vaultId));
                                        }
                                        return CompletableFuture.completedFuture(null);
                                    });
                        })
                        .thenCompose(ignored -> storage.deleteVault(oldUUID, vaultId)));
            }
            // Legacy row cleanup under the old UUID
            return chain.thenCompose(v -> storage.deleteEnderChest(oldUUID));
        }).thenRun(() -> {
            plugin.getLogger().info("[Migration] Successfully migrated vault data for " + player.getName());
            Scheduler.runEntityTask(player, () -> {
                if (player.isOnline()) {
                    player.sendMessage(plugin.getLocaleManager().getPrefixedComponent(player, "messages.data-migrated"));
                }
            });
        });
    }

    // ==================== Quit / shutdown ====================

    // Save player data when they leave the server.
    // IMPORTANT: This method MUST NOT block the main/region thread to prevent
    // deadlocks!
    public void onPlayerQuit(Player player) {
        // Stop tracking this inventory as it's being closed
        final UUID playerUuid = player.getUniqueId();
        openVaults.remove(playerUuid);
        resizingVaults.removeIf(key -> key.playerId().equals(playerUuid));
        resizeCooldowns.keySet().removeIf(key -> key.playerId().equals(playerUuid));
        notifiedOverflowVaults.removeIf(key -> key.playerId().equals(playerUuid));

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

        // Snapshot all cached vaults of this player
        Map<Integer, Inventory> cachedVaults = getCachedVaults(playerUuid);
        if (!cachedVaults.isEmpty()) {
            // Invalidate cache immediately to prevent double-save
            for (int vaultId : cachedVaults.keySet()) {
                liveData.invalidate(new VaultKey(playerUuid, vaultId));
            }

            Scheduler.runTask(() -> {
                List<CompletableFuture<Void>> saves = new ArrayList<>();
                for (Map.Entry<Integer, Inventory> entry : cachedVaults.entrySet()) {
                    int vaultId = entry.getKey();
                    Inventory inv = entry.getValue();
                    int size = inv.getSize();
                    ItemStack[] contents = cleanInventoryForSave(inv.getContents().clone());
                    saves.add(saveVault(playerUuid, playerName, vaultId, size, contents)
                            .orTimeout(10, TimeUnit.SECONDS));
                }
                CompletableFuture.allOf(saves.toArray(new CompletableFuture[0]))
                        .whenComplete((result, ex) -> {
                            if (ex != null) {
                                Throwable cause = ex instanceof TimeoutException ? ex
                                        : (ex.getCause() != null ? ex.getCause() : ex);
                                if (cause instanceof TimeoutException) {
                                    plugin.getLogger().warning("Quit-save for " + playerName
                                            + " timed out. Data may require manual verification.");
                                } else {
                                    plugin.getLogger().severe("Failed to save data for " + playerName
                                            + " on quit: " + cause.getMessage());
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
            inventoryTrackerTask.cancel();

        plugin.getLogger().info("Auto-save task cancelled. Saving all cached player data before shutting down...");

        try {
            // Use timeout to prevent hanging during shutdown
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

    // ==================== Open / resize ====================

    /** Opens vault 1 (backward compatible). */
    public boolean openEnderChest(Player player) {
        return openEnderChest(player, 1);
    }

    // Open the given vault for the player, loading data if necessary.
    // Returns true if the inventory was (or will be) successfully opened.
    public boolean openEnderChest(Player player, int vaultId) {
        if (player == null || !player.isOnline()) {
            return false;
        }

        int maxVaults = EnderChestUtils.getMaxVaults();
        if (vaultId < 1 || vaultId > maxVaults) {
            player.sendMessage(plugin.getLocaleManager().getPrefixedComponent(player, "messages.vault-invalid",
                    Placeholder.unparsed("max", String.valueOf(maxVaults))));
            soundHandler.playSound(player, "fail");
            return false;
        }

        UUID uuid = player.getUniqueId();
        CompletableFuture<Void> pendingSave = pendingSaves.get(uuid);
        if (pendingSave != null && !pendingSave.isDone()) {
            plugin.getDebugLogger().log("Waiting for pending saves before opening vault " + vaultId + " for " + player.getName());
            pendingSave.thenRun(() -> Scheduler.runEntityTask(player, () -> openEnderChest(player, vaultId)));
            return true;
        }

        VaultKey key = new VaultKey(uuid, vaultId);

        // Check if data is currently being loaded to prevent loops
        if (dataLockManager.isLocked(key)) {
            player.sendMessage(plugin.getLocaleManager().getPrefixedComponent(player, "messages.data-still-loading"));
            return false;
        }

        // Prevent opening concurrent views (mitigates double-open race conditions)
        if (openVaults.containsKey(uuid)) {
            return false;
        }

        // Entitlement to this vault number
        if (vaultId > EnderChestUtils.getVaultCount(player)) {
            player.sendMessage(plugin.getLocaleManager().getPrefixedComponent(player, "messages.vault-no-access",
                    Placeholder.unparsed("vault", String.valueOf(vaultId))));
            soundHandler.playSound(player, "fail");
            return false;
        }

        Inventory inv = getLiveData().getIfPresent(key);
        if (inv == null) {
            // Load the vault, then open it automatically once ready so the
            // player never has to click twice.
            if (dataLockManager.isLocked(key)) {
                player.sendMessage(plugin.getLocaleManager().getPrefixedComponent(player, "messages.data-still-loading"));
                return false;
            }
            long startTime = System.nanoTime();
            plugin.getStorageManager().getStorage().loadVault(player.getUniqueId(), vaultId)
                    .orTimeout(15, TimeUnit.SECONDS)
                    .whenComplete((vaultData, error) -> {
                        if (!player.isOnline()) {
                            dataLockManager.unlock(key);
                            return;
                        }
                        Scheduler.runEntityTask(player, () -> {
                            try {
                                if (error != null) {
                                    plugin.getLogger().log(Level.SEVERE,
                                            "Failed to load vault " + vaultId + " for " + player.getName(), error);
                                    player.sendMessage(plugin.getLocaleManager()
                                            .getPrefixedComponent(player, "messages.save-error"));
                                    return;
                                }
                                applyVaultData(player, vaultId, vaultData, startTime);
                            } finally {
                                dataLockManager.unlock(key);
                            }
                            if (player.isOnline() && error == null) {
                                openEnderChest(player, vaultId);
                            }
                        });
                    });
            return true;
        }

        int permissionSize = EnderChestUtils.getSize(player, vaultId);
        if (permissionSize == 0) {
            player.sendMessage(plugin.getLocaleManager().getPrefixedComponent(player, "messages.no-permission"));
            soundHandler.playSound(player, "fail");
            return false;
        }

        // If there's an upgrade, run checkForUpgradeAndRestore first!
        if (permissionSize > inv.getSize()) {
            checkForUpgradeAndRestore(player, vaultId).thenRun(() -> {
                Scheduler.runEntityTask(player, () -> openEnderChest(player, vaultId));
            });
            return true;
        }

        // Calculate expected display size based on permission and item positions
        int expectedDisplaySize = getExpectedDisplaySize(inv, permissionSize);

        if (inv.getSize() != expectedDisplaySize) {
            inv = resizeVault(player, inv, permissionSize, vaultId);
            liveData.put(key, inv);
        }

        // Developer API hook: other plugins may veto the open
        EnderVaultOpenEvent openEvent = new EnderVaultOpenEvent(player, vaultId, inv);
        if (!openEvent.callEvent()) {
            plugin.getDebugLogger().log("Vault " + vaultId + " open for " + player.getName()
                    + " was cancelled by an API listener.");
            return false;
        }

        player.openInventory(inv);
        openVaults.put(uuid, vaultId); // Start tracking this open vault
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

    // Resize a vault - items beyond the new size go to that vault's overflow
    private Inventory resizeVault(Player player, Inventory oldInv, int newSize, int vaultId) {
        plugin.getDebugLogger().log(
                "Resizing " + player.getName() + "'s vault " + vaultId + ". Old: " + oldInv.getSize() + ", New Size: " + newSize);
        ItemStack[] oldContents = oldInv.getContents();

        Component title = EnderChestUtils.getTitle(player, vaultId);
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
            VaultKey key = new VaultKey(player.getUniqueId(), vaultId);
            // Load existing overflow items and merge them, tracked to prevent race conditions
            CompletableFuture<Void> overflowFuture = plugin.getStorageManager().getStorage()
                    .loadOverflowItems(player.getUniqueId(), vaultId)
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
                                () -> plugin.getStorageManager().getStorage()
                                        .saveOverflowItems(player.getUniqueId(), vaultId, mergedArray))
                                .thenRun(() -> {
                                    plugin.getDebugLogger().log("Saved " + mergedOverflow.size()
                                            + " total overflow items for " + player.getName() + " vault " + vaultId);

                                    // Notify player once per vault — reserve the
                                    // slot synchronously so concurrent resizes
                                    // can never double-send.
                                    if (notifiedOverflowVaults.add(key)) {
                                        Scheduler.runEntityTask(player, () -> {
                                            LocaleManager locale = plugin.getLocaleManager();
                                            player.sendMessage(locale.getPrefixedComponent(player, "messages.overflow-items-saved"));
                                            player.sendMessage(locale.getPrefixedComponent(player, "messages.overflow-will-restore",
                                                    Placeholder.unparsed("command", plugin.getPrimaryCommand() + " overflow")));
                                        });
                                    }
                                });
                    });
            trackPendingSave(player.getUniqueId(), overflowFuture);
        }
        plugin.getDebugLogger().log("Resize complete. New vault " + vaultId + " size: " + newInv.getSize()
                + ", Overflow items: " + overflowItems.size());
        return newInv;
    }

    // ==================== Save plumbing ====================

    /**
     * Track a pending database save operation for a player (across all vaults).
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

    public CompletableFuture<Void> queueStorageOperation(UUID uuid, Supplier<CompletableFuture<Void>> operation) {
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

    // ==================== Upgrade / overflow restore ====================

    /** Backward compatible upgrade check for vault 1. */
    public CompletableFuture<Void> checkForUpgradeAndRestore(Player player) {
        return checkForUpgradeAndRestore(player, 1);
    }

    /**
     * Checks whether the player's permission size for the given vault is larger
     * than the loaded vault. If so, resizes the vault and restores that vault's
     * overflow items.
     */
    public CompletableFuture<Void> checkForUpgradeAndRestore(Player player, int vaultId) {
        if (player == null || !player.isOnline()) {
            return CompletableFuture.completedFuture(null);
        }

        VaultKey key = new VaultKey(player.getUniqueId(), vaultId);
        if (resizingVaults.contains(key)) {
            // Already checking/resizing
            return CompletableFuture.completedFuture(null);
        }

        int permissionSize = EnderChestUtils.getSize(player, vaultId);
        Inventory loadedChest = liveData.getIfPresent(key);

        if (loadedChest != null && permissionSize > loadedChest.getSize()) {
            resizingVaults.add(key);
            plugin.getDebugLogger().log("Upgrade detected for " + player.getName() + " vault " + vaultId
                    + " (Permission size: " + permissionSize + " > Loaded: " + loadedChest.getSize()
                    + "). Resizing and restoring...");
            Inventory newInv = resizeVault(player, loadedChest, permissionSize, vaultId);
            liveData.put(key, newInv);
            CompletableFuture<Void> overallFuture = restoreOverflowToEnderChest(player, newInv, vaultId);
            trackPendingSave(player.getUniqueId(), overallFuture);
            return overallFuture.whenComplete((v, ex) -> resizingVaults.remove(key));
        }

        return CompletableFuture.completedFuture(null);
    }

    /**
     * Restores overflow items of one vault back into the upgraded slots.
     */
    private CompletableFuture<Void> restoreOverflowToEnderChest(Player player, Inventory inv, int vaultId) {
        UUID uuid = player.getUniqueId();
        VaultKey key = new VaultKey(uuid, vaultId);
        CompletableFuture<Void> overallFuture = new CompletableFuture<>();

        plugin.getStorageManager().getStorage().loadOverflowItems(uuid, vaultId)
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

                            // Try to add item to the vault
                            if (inv.firstEmpty() != -1) {
                                inv.addItem(item);
                                restoredItems.add(item);
                                plugin.getDebugLogger().log("Restored overflow item " + item.getType() + " to "
                                        + player.getName() + "'s vault " + vaultId + " due to upgrade.");
                            } else {
                                remainingOverflow.add(item);
                            }
                        }

                        final int count = restoredItems.size();
                        CompletableFuture<Void> chestSaveFuture;
                        if (count > 0) {
                            LocaleManager locale = plugin.getLocaleManager();
                            player.sendMessage(locale.getPrefixedComponent(player, "messages.overflow-items-restored")
                                    .replaceText(builder -> builder.matchLiteral("<count>")
                                            .replacement(String.valueOf(count))));

                            // Save the updated vault immediately
                            chestSaveFuture = saveVault(uuid, player.getName(), vaultId, inv);
                        } else {
                            chestSaveFuture = CompletableFuture.completedFuture(null);
                        }

                        chestSaveFuture.thenCompose(v -> {
                            // Update or clear overflow storage
                            CompletableFuture<Void> saveFuture;
                            ItemStack[] remainingArray = remainingOverflow.toArray(new ItemStack[0]);
                            if (remainingOverflow.isEmpty()) {
                                saveFuture = queueStorageOperation(uuid,
                                        () -> plugin.getStorageManager().getStorage().clearOverflowItems(uuid, vaultId));
                                notifiedOverflowVaults.remove(key);
                            } else {
                                saveFuture = queueStorageOperation(uuid,
                                        () -> plugin.getStorageManager().getStorage()
                                                .saveOverflowItems(uuid, vaultId, remainingArray));
                            }
                            return saveFuture;
                        }).thenRun(() -> {
                            // Sync the in-memory activeOverflowItems
                            if (activeOverflowItems.containsKey(key)) {
                                activeOverflowItems.put(key, remainingOverflow);

                                // If they have the overflow GUI open, refresh/sync the slots
                                Scheduler.runEntityTask(player, () -> {
                                    Inventory openInv = player.getOpenInventory().getTopInventory();
                                    OverflowView view = overflowViews.get(openInv);
                                    if (view != null && !view.admin() && view.vaultId() == vaultId) {
                                        if (remainingOverflow.isEmpty()) {
                                            player.closeInventory();
                                            player.sendMessage(plugin.getLocaleManager().getPrefixedComponent(player, "command.overflow-empty"));
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

    // ==================== Auto-save / shutdown save ====================

    // Force-save all cached data during server shutdown to prevent data loss.
    private CompletableFuture<Void> shutdownSave() {
        Set<Map.Entry<VaultKey, Inventory>> cacheSnapshot = new java.util.HashSet<>(liveData.asMap().entrySet());
        if (cacheSnapshot.isEmpty())
            return CompletableFuture.completedFuture(null);
        plugin.getLogger().info("Force-saving data for " + cacheSnapshot.size() + " vaults...");

        // During shutdown, we clone inventory contents immediately
        // This is safer because we're on the main/global thread during shutdown
        CompletableFuture<?>[] futures = cacheSnapshot.stream()
                .map(entry -> {
                    VaultKey key = entry.getKey();
                    Player p = Bukkit.getPlayer(key.playerId());
                    String name = (p != null) ? p.getName() : Bukkit.getOfflinePlayer(key.playerId()).getName();
                    Inventory inv = entry.getValue();
                    int size = inv.getSize();
                    if (Scheduler.isFolia() && p != null && p.isOnline()) {
                        CompletableFuture<ItemStack[]> snapshotFuture = new CompletableFuture<>();
                        Scheduler.runEntityTask(p, () -> snapshotFuture
                                .complete(cleanInventoryForSave(inv.getContents().clone())));
                        return snapshotFuture
                                .thenCompose(contents -> saveVault(key.playerId(), name, key.vaultId(), size, contents));
                    }
                    ItemStack[] contents = cleanInventoryForSave(inv.getContents().clone());
                    return saveVault(key.playerId(), name, key.vaultId(), size, contents);
                })
                .filter(Objects::nonNull).toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures);
    }

    // Auto-save all cached data periodically to prevent data loss.
    private void autoSaveAll() {
        // Snapshot the cache and group vaults per player so each player lock is
        // acquired once for all of their vaults.
        Map<UUID, Map<Integer, Inventory>> byPlayer = new LinkedHashMap<>();
        for (Map.Entry<VaultKey, Inventory> entry : liveData.asMap().entrySet()) {
            byPlayer.computeIfAbsent(entry.getKey().playerId(), u -> new LinkedHashMap<>())
                    .put(entry.getKey().vaultId(), entry.getValue());
        }
        if (byPlayer.isEmpty()) {
            return;
        }
        plugin.getDebugLogger().log("Auto-saving data for " + byPlayer.size() + " cached players...");

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Map.Entry<UUID, Map<Integer, Inventory>> playerEntry : byPlayer.entrySet()) {
            UUID uuid = playerEntry.getKey();

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
                    List<CompletableFuture<Void>> saves = new ArrayList<>();
                    for (Map.Entry<Integer, Inventory> vaultEntry : playerEntry.getValue().entrySet()) {
                        final Inventory inv = vaultEntry.getValue();
                        final int vaultId = vaultEntry.getKey();
                        final int size = inv.getSize();
                        ItemStack[] contents = cleanInventoryForSave(inv.getContents().clone());
                        saves.add(saveVault(uuid, name, vaultId, size, contents)
                                .whenComplete((result, ex) -> {
                                    if (ex != null) {
                                        plugin.getLogger().warning("Failed to auto-save vault " + vaultId
                                                + " for " + name + ": " + ex.getMessage());
                                    } else {
                                        plugin.getDebugLogger().log("Auto-saved vault " + vaultId + " for " + name);
                                    }
                                }));
                    }
                    CompletableFuture.allOf(saves.toArray(new CompletableFuture[0])).whenComplete((v, ex) -> {
                        dataLockManager.unlock(uuid);
                        future.complete(null);
                    });
                });
                futures.add(future);
            } else {
                List<CompletableFuture<Void>> saves = new ArrayList<>();
                for (Map.Entry<Integer, Inventory> vaultEntry : playerEntry.getValue().entrySet()) {
                    final Inventory inv = vaultEntry.getValue();
                    final int vaultId = vaultEntry.getKey();
                    final int size = inv.getSize();
                    CompletableFuture<Void> future = Scheduler
                            .supplySync(() -> cleanInventoryForSave(inv.getContents().clone()))
                            .thenCompose(contents -> saveVault(uuid, name, vaultId, size, contents))
                            .whenComplete((result, ex) -> {
                                if (ex != null) {
                                    plugin.getLogger().warning("Failed to auto-save vault " + vaultId + " for "
                                            + name + ": " + ex.getMessage());
                                } else {
                                    plugin.getDebugLogger().log("Auto-saved vault " + vaultId + " for " + name);
                                }
                            });
                    saves.add(future);
                }
                CompletableFuture.allOf(saves.toArray(new CompletableFuture[0]))
                        .whenComplete((v, ex) -> dataLockManager.unlock(uuid));
                futures.add(CompletableFuture.completedFuture(null));
            }
        }

        // Log completion for all players
        if (!futures.isEmpty()) {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenRun(() -> {
                        plugin.getDebugLogger().log("Auto-save completed for all players.");
                    });
        }
    }

    // ==================== Save entry points ====================

    /** Saves vault 1 from an inventory object (backward compatible). */
    public CompletableFuture<Void> saveEnderChest(UUID uuid, String playerName, Inventory inv) {
        return saveVault(uuid, playerName, 1, inv);
    }

    // Save one vault with inventory object, used for online players.
    public CompletableFuture<Void> saveVault(UUID uuid, String playerName, int vaultId, Inventory inv) {
        // Snapshot the Bukkit inventory here; cleaning is done once by the array overload.
        ItemStack[] contents = inv.getContents().clone();
        return saveVault(uuid, playerName, vaultId, inv.getSize(), contents);
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

    // Save one vault with specified size and items.
    public CompletableFuture<Void> saveVault(UUID uuid, String playerName, int vaultId, int size, ItemStack[] items) {
        ItemStack[] cleanItems = cleanInventoryForSave(items.clone());
        return queueStorageOperation(uuid, () -> {
            // Start timing when the database operation begins, excluding save-chain wait time.
            long startTime = System.nanoTime();
            return plugin.getStorageManager().getStorage()
                    .saveVault(uuid, playerName, vaultId, size, cleanItems)
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

    // ==================== Cache access ====================

    /** Cached vault 1 inventory (backward compatible). */
    public Inventory getLoadedEnderChest(UUID uuid) {
        return getLoadedVault(uuid, 1);
    }

    // Get the cached inventory for a player's vault, or null if not loaded.
    public Inventory getLoadedVault(UUID uuid, int vaultId) {
        return liveData.getIfPresent(new VaultKey(uuid, vaultId));
    }

    /** All cached vaults of a player: vault id -> inventory. */
    public Map<Integer, Inventory> getCachedVaults(UUID uuid) {
        Map<Integer, Inventory> result = new LinkedHashMap<>();
        for (Map.Entry<VaultKey, Inventory> entry : liveData.asMap().entrySet()) {
            if (entry.getKey().playerId().equals(uuid)) {
                result.put(entry.getKey().vaultId(), entry.getValue());
            }
        }
        return result;
    }

    // ==================== Open inventory tracker ====================

    private void checkOpenInventories() {
        if (!openVaults.isEmpty()) {
            checkOpenVaults();
        }

        // Live permission shrink for cached vaults that are NOT currently
        // open: resize on the holder's thread, move overflow, save — so a
        // lowered permission applies without a relog. This deliberately runs
        // even when nothing is open, unlike the open-vault loop.
        for (Map.Entry<VaultKey, Inventory> entry : new ArrayList<>(liveData.asMap().entrySet())) {
            VaultKey key = entry.getKey();
            Player holder = Bukkit.getPlayer(key.playerId());
            if (holder == null || !holder.isOnline() || openVaults.containsKey(key.playerId())) {
                continue;
            }
            Inventory cached = entry.getValue();
            int permissionSize = EnderChestUtils.getSize(holder, key.vaultId());
            if (permissionSize == 0 || permissionSize >= cached.getSize()) {
                continue;
            }
            Long lastShrink = resizeCooldowns.get(key);
            if (lastShrink != null && System.currentTimeMillis() - lastShrink < RESIZE_COOLDOWN_MS) {
                continue;
            }
            if (dataLockManager.isLocked(key) || resizingVaults.contains(key) || !dataLockManager.tryLock(key)) {
                continue;
            }
            resizeCooldowns.put(key, System.currentTimeMillis());
            resizingVaults.add(key);
            Scheduler.runEntityTask(holder, () -> {
                try {
                    if (!holder.isOnline()) {
                        return;
                    }
                    plugin.getLogger().info("[Resize] Permission shrink detected for " + holder.getName()
                            + " vault " + key.vaultId() + " (size: " + cached.getSize() + " -> " + permissionSize
                            + "). Moving excess items to overflow.");
                    Inventory resized = resizeVault(holder, cached, permissionSize, key.vaultId());
                    liveData.put(key, resized);
                    saveVault(key.playerId(), holder.getName(), key.vaultId(), resized)
                            .whenComplete((result, ex) -> {
                                if (ex != null) {
                                    plugin.getLogger().warning("Failed to save shrunk vault " + key.vaultId()
                                            + " for " + holder.getName() + ": " + ex.getMessage());
                                } else {
                                    plugin.getDebugLogger().log("Saved shrunk vault " + key.vaultId()
                                            + " for " + holder.getName());
                                }
                            });
                } finally {
                    resizingVaults.remove(key);
                    dataLockManager.unlock(key);
                }
            });
        }
    }

    /** Resize/title checks for the vault the player currently has open. */
    private void checkOpenVaults() {
        for (Map.Entry<UUID, Integer> entry : new ArrayList<>(openVaults.entrySet())) {
            UUID uuid = entry.getKey();
            int vaultId = entry.getValue();
            Player player = Bukkit.getPlayer(uuid);

            // Stop tracking if player is offline or not viewing our inventory anymore
            if (player == null || !player.isOnline()) {
                openVaults.remove(uuid);
                resizingVaults.remove(new VaultKey(uuid, vaultId));
                continue;
            }

            VaultKey key = new VaultKey(uuid, vaultId);
            Inventory cachedInv = liveData.getIfPresent(key);
            Inventory openInv = player.getOpenInventory().getTopInventory();

            if (cachedInv == null || openInv != cachedInv) {
                openVaults.remove(uuid);
                resizingVaults.remove(key);
                continue;
            }

            // Skip if already resizing to prevent double-resize
            if (resizingVaults.contains(key)) {
                continue;
            }

            // CRITICAL FIX: Check cooldown to prevent rapid resize loops that melt the server
            Long lastResize = resizeCooldowns.get(key);
            if (lastResize != null && System.currentTimeMillis() - lastResize < RESIZE_COOLDOWN_MS) {
                continue; // Still in cooldown, skip this player
            }

            int currentPermissionSize = EnderChestUtils.getSize(player, vaultId);

            // CRITICAL FIX: If player has no permission (size 0), don't try to resize
            // This can cause infinite loops and server meltdown
            if (currentPermissionSize == 0) {
                plugin.getDebugLogger().log("Player " + player.getName() + " has no permission for vault " + vaultId
                        + ", skipping resize check");
                continue;
            }

            // Skip checking/resizing if player data is currently locked (loading or saving)
            if (dataLockManager.isLocked(key)) {
                continue;
            }

            // Check size mismatch - only compare against permission size
            // This prevents infinite loops when cached size differs from displayed size
            boolean sizeMismatched = openInv.getSize() != currentPermissionSize; // resize up AND down live

            Component expectedTitleComponent = EnderChestUtils.getTitle(player, vaultId);
            Component actualTitleComponent = player.getOpenInventory().title();

            String expectedTitle = LegacyComponentSerializer.legacySection().serialize(expectedTitleComponent);
            String actualTitle = LegacyComponentSerializer.legacySection().serialize(actualTitleComponent);
            boolean titleMismatched = !expectedTitle.equals(actualTitle);

            // Only resize if there's an actual mismatch and permission changed significantly
            if (sizeMismatched || titleMismatched) {
                // Acquire lock to prevent concurrent database operations during resize
                if (!dataLockManager.lock(key)) {
                    plugin.getDebugLogger().log("Skipping resize for " + player.getName() + " vault " + vaultId
                            + " - failed to acquire data lock");
                    continue;
                }

                // Log at INFO level so admins can see this without debug mode
                plugin.getLogger().info("[Resize] Permission/title change detected for " + player.getName()
                        + " vault " + vaultId + " (size: " + openInv.getSize() + " -> " + currentPermissionSize
                        + ", titleMismatch: " + titleMismatched + "). Triggering inventory refresh.");

                // Mark set cooldown to prevent rapid loops
                resizeCooldowns.put(key, System.currentTimeMillis());

                // Remove from tracking first to prevent loops
                openVaults.remove(uuid);

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
                    checkForUpgradeAndRestore(player, vaultId).thenRun(() -> {
                        Scheduler.runEntityTask(player, () -> {
                            if (player.isOnline()) {
                                Inventory newResized = getLoadedVault(uuid, vaultId);
                                if (newResized != null) {
                                    player.openInventory(newResized);
                                    openVaults.put(uuid, vaultId);
                                    soundHandler.playSound(player, "open");
                                    player.setItemOnCursor(cursorItem);
                                }
                            }
                        });
                    }).whenComplete((v, ex) -> {
                        dataLockManager.unlock(key);
                        if (ex != null) {
                            plugin.getLogger().warning("Error during upgrade restore for " + player.getName()
                                    + " vault " + vaultId + ": " + ex.getMessage());
                        }
                    });
                } else {
                    // Just a title change or downgrade:
                    // Mark as resizing for the duration of this task
                    resizingVaults.add(key);

                    // Resize the inventory and update cache
                    Inventory resizedInv = resizeVault(player, cachedInv, currentPermissionSize, vaultId);
                    liveData.put(key, resizedInv);
                    plugin.getDebugLogger().log("Resized vault " + vaultId + " cached. New size: " + resizedInv.getSize());

                    // Save the resized inventory immediately to prevent data loss (async, non-blocking)
                    saveVault(uuid, player.getName(), vaultId, resizedInv)
                            .whenComplete((result, ex) -> {
                                if (ex != null) {
                                    plugin.getLogger().warning("Failed to save resized vault " + vaultId + " for "
                                            + player.getName() + ": " + ex.getMessage());
                                } else {
                                    plugin.getDebugLogger().log("Saved resized vault " + vaultId + " for " + player.getName());
                                }
                                dataLockManager.unlock(key);
                            });

                    // Use a delayed task to prevent issues with immediate reopening
                    Scheduler.runTaskLater(() -> {
                        if (player.isOnline()) {
                            player.openInventory(resizedInv);
                            openVaults.put(uuid, vaultId);
                            soundHandler.playSound(player, "open");
                            player.setItemOnCursor(cursorItem);
                        }

                        // Clear resizing flag AFTER reopening
                        Scheduler.runTaskLater(() -> resizingVaults.remove(key), 5L);
                    }, 2L); // Wait 2 ticks before reopening
                }
            }
        }
    }

    // ==================== Cache reload / import ====================

    // Reload the vault 1 cache for a player, useful after delete/import.
    public void reloadCacheFor(Player player) {
        reloadCacheFor(player, 1);
    }

    public void reloadCacheFor(Player player, int vaultId) {
        int size = EnderChestUtils.getSize(player, vaultId);
        VaultKey key = new VaultKey(player.getUniqueId(), vaultId);
        if (size == 0) {
            liveData.invalidate(key);
            return;
        }
        Component title = EnderChestUtils.getTitle(player, vaultId);
        Inventory newInv = Bukkit.createInventory(player, size, title);
        liveData.put(key, newInv);
        plugin.getDebugLogger().log("Cache reloaded for player " + player.getName() + " vault " + vaultId);
    }

    /**
     * Update the vault 1 cache for a player with specific items.
     * Used by import functionality to update cache with imported items.
     *
     * @param player The player to update cache for
     * @param items  The items to set in the cache
     */
    public void updateCacheWithItems(Player player, ItemStack[] items) {
        int size = EnderChestUtils.getSize(player);
        VaultKey key = new VaultKey(player.getUniqueId(), 1);
        if (size == 0) {
            liveData.invalidate(key);
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

        liveData.put(key, newInv);
        plugin.getDebugLogger().log("Cache updated with items for player " + player.getName());
    }

    // ==================== Admin chest views ====================

    /** Backward compatible admin open for vault 1. */
    public void openOtherPlayerChest(Player admin, String targetName) {
        openOtherPlayerChest(admin, targetName, 1);
    }

    /**
     * Opens another player's vault for admin viewing/editing. Handles both
     * online and offline players; loads the vault from storage when it is not
     * in the live cache.
     */
    public void openOtherPlayerChest(Player admin, String targetName, int vaultId) {
        if (admin == null || !admin.isOnline()) {
            return;
        }

        int maxVaults = EnderChestUtils.getMaxVaults();
        if (vaultId < 1 || vaultId > maxVaults) {
            admin.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.vault-invalid",
                    Placeholder.unparsed("max", String.valueOf(maxVaults))));
            return;
        }

        if (vaultId > 1 && !EnderChestUtils.isVaultsEnabled()) {
            admin.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.vault-no-access",
                    Placeholder.unparsed("vault", String.valueOf(vaultId))));
            return;
        }

        admin.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.loading-chest",
                Placeholder.unparsed("player", targetName)));

        Player targetOnline = Bukkit.getPlayerExact(targetName);
        if (targetOnline != null) {
            // --- For online player ---
            Inventory liveInv = getLoadedVault(targetOnline.getUniqueId(), vaultId);
            if (liveInv != null) {
                openAdminViewInventory(admin, targetOnline.getUniqueId(),
                        targetOnline.getName() != null ? targetOnline.getName() : targetName,
                        vaultId, liveInv.getSize(), liveInv.getContents());
                return;
            }
            // Vault not cached: load from storage, then open
            plugin.getStorageManager().getStorage().loadVault(targetOnline.getUniqueId(), vaultId)
                    .thenAccept(vaultData -> Scheduler.runEntityTask(admin, () -> {
                        if (!admin.isOnline())
                            return;
                        if (vaultData == null) {
                            admin.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.player-not-found",
                                    Placeholder.unparsed("player", targetName)));
                            return;
                        }
                        openAdminViewInventory(admin, targetOnline.getUniqueId(),
                                targetOnline.getName() != null ? targetOnline.getName() : targetName,
                                vaultId, vaultData.size, vaultData.items);
                    })).exceptionally(ex -> {
                        EnderChest.trackError(ex);
                        admin.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.save-error"));
                        return null;
                    });
            return;
        }

        // For offline player
        Scheduler.supplyAsync(() -> Bukkit.getOfflinePlayer(targetName))
                .thenAccept(target -> {
                    if (!target.hasPlayedBefore()) {
                        admin.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.player-not-found",
                                Placeholder.unparsed("player", targetName)));
                        return;
                    }

                    StorageInterface storage = plugin.getStorageManager().getStorage();
                    storage.loadVault(target.getUniqueId(), vaultId).thenAccept(vaultData -> {
                        if (vaultData == null || vaultData.size == 0) {
                            admin.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.player-not-found",
                                    Placeholder.unparsed("player", targetName)));
                            return;
                        }
                        String displayName = target.getName() != null ? target.getName() : targetName;
                        Scheduler.runEntityTask(admin, () -> {
                            if (!admin.isOnline())
                                return;
                            openAdminViewInventory(admin, target.getUniqueId(), displayName,
                                    vaultId, vaultData.size, vaultData.items);
                        });
                    }).exceptionally(ex -> {
                        EnderChest.trackError(ex);
                        admin.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.save-error"));
                        return null;
                    });
                });
    }

    private void openAdminViewInventory(Player admin, UUID targetUuid, String targetName,
            int vaultId, int size, ItemStack[] items) {
        if (!admin.isOnline()) {
            return;
        }
        Component title = EnderChestUtils.getAdminTitle(targetName, vaultId);
        int invSize = size > 0 ? size : 9;
        Inventory inv = Bukkit.createInventory(admin, invSize, title);
        if (items != null) {
            for (int i = 0; i < Math.min(items.length, invSize); i++) {
                inv.setItem(i, items[i]);
            }
        }

        adminViewedChests.put(inv, new VaultKey(targetUuid, vaultId));
        admin.openInventory(inv);
        plugin.getSoundHandler().playSound(admin, "open");
    }

    // ==================== Overflow GUI ====================

    /** Backward compatible overflow GUI for vault 1. */
    public void openOverflowGUI(Player player) {
        if (!EnderChestUtils.isVaultsEnabled()) {
            // Multi-vault off: merge every vault's overflow into one GUI so
            // nothing becomes unreachable after switching modes.
            openAggregateOverflowGUI(player);
            return;
        }
        openOverflowGUI(player, 1);
    }

    /**
     * Opens an aggregate overflow GUI merging the overflow of ALL vaults
     * (used while multi-vault is disabled). Claims remove items from the
     * source vault's overflow immediately; closing persists the rest.
     */
    public void openAggregateOverflowGUI(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        CompletableFuture<Void> pendingSave = pendingSaves.get(uuid);
        if (pendingSave != null && !pendingSave.isDone()) {
            pendingSave.thenRun(() -> Scheduler.runEntityTask(player, () -> openAggregateOverflowGUI(player)));
            return;
        }

        StorageInterface storage = plugin.getStorageManager().getStorage();
        storage.getVaultIds(uuid).thenAccept(vaultIds -> {
            Map<Integer, List<ItemStack>> perVault = new LinkedHashMap<>();
            List<ItemStack> merged = new ArrayList<>();
            CompletableFuture<?> chain = CompletableFuture.completedFuture(null);
            for (int vaultId : vaultIds) {
                final int vid = vaultId;
                chain = chain.thenCompose(v -> storage.loadOverflowItems(uuid, vid)
                        .thenAccept(items -> {
                            if (items == null) {
                                return;
                            }
                            List<ItemStack> valid = new ArrayList<>();
                            for (ItemStack item : items) {
                                if (item != null && item.getType() != Material.AIR) {
                                    valid.add(item);
                                    merged.add(item);
                                }
                            }
                            if (!valid.isEmpty()) {
                                perVault.put(vid, valid);
                            }
                        }));
            }
            final List<ItemStack> mergedList = merged;
            chain.thenRun(() -> Scheduler.runEntityTask(player, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (mergedList.isEmpty()) {
                    player.sendMessage(plugin.getLocaleManager().getPrefixedComponent(player, "command.overflow-empty"));
                    return;
                }
                int invSize = Math.min(54, Math.max(9, (int) Math.ceil(mergedList.size() / 9.0) * 9));
                Component title = plugin.getLocaleManager().getComponent(player, "command.overflow-all-title",
                        Placeholder.unparsed("player", player.getName()));
                Inventory overflowInv = Bukkit.createInventory(player, invSize, title);
                for (int i = 0; i < Math.min(mergedList.size(), invSize); i++) {
                    overflowInv.setItem(i, mergedList.get(i));
                }
                overflowViews.put(overflowInv, new OverflowView(uuid, 0, false));
                aggregateOverflowItems.put(uuid, mergedList);
                aggregateOverflowLists.put(uuid, perVault);
                player.openInventory(overflowInv);
                plugin.getSoundHandler().playSound(player, "open");
            })).exceptionally(ex -> {
                EnderChest.trackError(ex);
                player.sendMessage(plugin.getLocaleManager().getPrefixedComponent(player, "messages.save-error"));
                return null;
            });
        });
    }

    /**
     * Open the overflow GUI for a player showing the overflow items of one
     * vault. Player can click items to claim them (with fee check).
     */
    public void openOverflowGUI(Player player, int vaultId) {
        if (player == null || !player.isOnline())
            return;

        if (vaultId > 1 && !EnderChestUtils.isVaultsEnabled()) {
            player.sendMessage(plugin.getLocaleManager().getPrefixedComponent(player, "messages.vault-no-access",
                    Placeholder.unparsed("vault", String.valueOf(vaultId))));
            plugin.getSoundHandler().playSound(player, "fail");
            return;
        }

        UUID uuid = player.getUniqueId();
        CompletableFuture<Void> pendingSave = pendingSaves.get(uuid);
        if (pendingSave != null && !pendingSave.isDone()) {
            plugin.getDebugLogger().log("Waiting for pending saves before opening overflow GUI for " + player.getName());
            pendingSave.thenRun(() -> Scheduler.runEntityTask(player, () -> openOverflowGUI(player, vaultId)));
            return;
        }

        checkForUpgradeAndRestore(player, vaultId).thenRun(() -> {
            plugin.getStorageManager().getStorage().loadOverflowItems(player.getUniqueId(), vaultId)
                    .thenAccept(overflowItems -> {
                        Scheduler.runEntityTask(player, () -> {
                            if (!player.isOnline())
                                return;

                            if (overflowItems == null || overflowItems.length == 0) {
                                player.sendMessage(plugin.getLocaleManager().getPrefixedComponent(player, "command.overflow-empty"));
                                return;
                            }

                            // Filter out null/air items
                            List<ItemStack> validItems = new ArrayList<>();
                            for (ItemStack item : overflowItems) {
                                if (item != null && item.getType() != Material.AIR) {
                                    validItems.add(item);
                                }
                            }

                            if (validItems.isEmpty()) {
                                player.sendMessage(plugin.getLocaleManager().getPrefixedComponent(player, "command.overflow-empty"));
                                return;
                            }

                            // Calculate inventory size (rows of 9, minimum 9, max 54)
                            int invSize = Math.min(54, Math.max(9, (int) Math.ceil(validItems.size() / 9.0) * 9));

                            // Create title
                            Component title = plugin.getLocaleManager().getComponent("command.overflow-title",
                                    Placeholder.unparsed("player", player.getName()),
                                    Placeholder.unparsed("vault", String.valueOf(vaultId)));

                            Inventory overflowInv = Bukkit.createInventory(player, invSize, title);

                            // Fill inventory with overflow items
                            for (int i = 0; i < Math.min(validItems.size(), invSize); i++) {
                                overflowInv.setItem(i, validItems.get(i));
                            }

                            // Track as overflow inventory (not admin)
                            overflowViews.put(overflowInv, new OverflowView(player.getUniqueId(), vaultId, false));
                            activeOverflowItems.put(new VaultKey(player.getUniqueId(), vaultId), validItems);

                            player.openInventory(overflowInv);
                            plugin.getSoundHandler().playSound(player, "open");
                        });
                    }).exceptionally(ex -> {
                        EnderChest.trackError(ex);
                        player.sendMessage(plugin.getLocaleManager().getPrefixedComponent(player, "messages.save-error"));
                        return null;
                    });
        })
        .exceptionally(ex -> {
            EnderChest.trackError(ex);
            player.sendMessage(plugin.getLocaleManager().getPrefixedComponent(player, "messages.save-error"));
            return null;
        });
    }

    /** Backward compatible admin overflow view for vault 1. */
    public void openAdminOverflowGUI(Player admin, String targetName) {
        openAdminOverflowGUI(admin, targetName, 1);
    }

    /**
     * Open the overflow GUI for admin viewing another player's vault overflow.
     * Admin cannot take items - read-only view.
     */
    public void openAdminOverflowGUI(Player admin, String targetName, int vaultId) {
        if (admin == null || !admin.isOnline())
            return;

        if (vaultId > 1 && !EnderChestUtils.isVaultsEnabled()) {
            admin.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.vault-no-access",
                    Placeholder.unparsed("vault", String.valueOf(vaultId))));
            return;
        }

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
                        Placeholder.unparsed("player", targetName)));
                return;
            }
            targetUUID = target.getUniqueId();
            displayName = target.getName() != null ? target.getName() : targetName;
        }

        String finalDisplayName = displayName;
        plugin.getStorageManager().getStorage().loadOverflowItems(targetUUID, vaultId)
                .thenAccept(overflowItems -> {
                    Scheduler.runEntityTask(admin, () -> {
                        if (!admin.isOnline())
                            return;

                        if (overflowItems == null || overflowItems.length == 0) {
                            admin.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.overflow-admin-empty",
                                    Placeholder.unparsed("player", finalDisplayName)));
                            return;
                        }

                        // Filter out null/air items
                        List<ItemStack> validItems = new ArrayList<>();
                        for (ItemStack item : overflowItems) {
                            if (item != null && item.getType() != Material.AIR) {
                                validItems.add(item);
                            }
                        }

                        if (validItems.isEmpty()) {
                            admin.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.overflow-admin-empty",
                                    Placeholder.unparsed("player", finalDisplayName)));
                            return;
                        }

                        int invSize = Math.min(54, Math.max(9, (int) Math.ceil(validItems.size() / 9.0) * 9));

                        Component title = plugin.getLocaleManager().getComponent("command.overflow-admin-title",
                                Placeholder.unparsed("player", finalDisplayName),
                                Placeholder.unparsed("vault", String.valueOf(vaultId)));

                        Inventory overflowInv = Bukkit.createInventory(admin, invSize, title);

                        for (int i = 0; i < Math.min(validItems.size(), invSize); i++) {
                            overflowInv.setItem(i, validItems.get(i));
                        }

                        // Track as admin overflow view
                        overflowViews.put(overflowInv, new OverflowView(targetUUID, vaultId, true));

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
     * For own overflow: move clicked item to the player's inventory, charge fee.
     * For admin overflow: cancel the click (read-only).
     *
     * @return true if this was an overflow click (event should be cancelled), false otherwise
     */
    public boolean handleOverflowClick(Player player, Inventory clickedInv, InventoryClickEvent event) {
        OverflowView view = overflowViews.get(clickedInv);
        if (view == null)
            return false; // Not an overflow inventory

        // Always cancel the click first
        event.setCancelled(true);

        // Admin view is read-only
        if (view.admin()) {
            return true;
        }

        // Aggregate overflow session (multi-vault disabled, vaultId 0)
        if (view.vaultId() == 0) {
            handleAggregateOverflowClick(player, event);
            return true;
        }

        // Own overflow - claim the clicked item
        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || clickedItem.getType() == Material.AIR) {
            return true;
        }

        UUID uuid = player.getUniqueId();
        int vaultId = view.vaultId();
        VaultKey key = new VaultKey(uuid, vaultId);
        List<ItemStack> activeOverflowList = activeOverflowItems.get(key);
        if (activeOverflowList == null) {
            return true;
        }

        // Ensure player data is fully loaded
        Inventory loadedChest = liveData.getIfPresent(key);
        if (loadedChest == null) {
            player.sendMessage(plugin.getLocaleManager().getPrefixedComponent(player, "messages.data-still-loading"));
            return true;
        }

        // Check if player permission size is upgraded
        int permissionSize = EnderChestUtils.getSize(player, vaultId);
        if (permissionSize > loadedChest.getSize()) {
            // Upgrade detected! Run the upgrade restore process and cancel the click
            checkForUpgradeAndRestore(player, vaultId);
            return true;
        }

        // Clone the item to safely calculate fit space
        ItemStack fitItem = clickedItem.clone();
        int originalAmount = fitItem.getAmount();

        // Calculate how much fits in player's inventory only
        int totalFitAmount = calculateFitAmount(player.getInventory(), fitItem);
        if (totalFitAmount <= 0) {
            player.sendMessage(plugin.getLocaleManager().getPrefixedComponent(player, "command.overflow-enderchest-full"));
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
                    () -> plugin.getStorageManager().getStorage().clearOverflowItems(uuid, vaultId));
            notifiedOverflowVaults.remove(key);
        } else {
            ItemStack[] overflowArray = cleanOverflow.toArray(new ItemStack[0]);
            overflowSaveFuture = queueStorageOperation(uuid,
                    () -> plugin.getStorageManager().getStorage().saveOverflowItems(uuid, vaultId, overflowArray));
        }
        overflowSaveFuture.exceptionally(ex -> {
            EnderChest.trackError(ex);
            return null;
        });
        trackPendingSave(uuid, overflowSaveFuture);

        player.sendMessage(plugin.getLocaleManager().getPrefixedComponent(player, "command.overflow-claimed",
                Placeholder.unparsed("count", String.valueOf(originalAmount - newAmount))));

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
     * Handle overflow inventory close - clean up tracking and save the vault's
     * remaining overflow data.
     */
    /** Claim flow for the aggregate overflow session (multi-vault disabled). */
    private void handleAggregateOverflowClick(Player player, InventoryClickEvent event) {
        UUID uuid = player.getUniqueId();
        List<ItemStack> merged = aggregateOverflowItems.get(uuid);
        if (merged == null) {
            return;
        }

        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || clickedItem.getType() == Material.AIR) {
            return;
        }

        ItemStack fitItem = clickedItem.clone();
        int originalAmount = fitItem.getAmount();

        int totalFitAmount = calculateFitAmount(player.getInventory(), fitItem);
        if (totalFitAmount <= 0) {
            player.sendMessage(plugin.getLocaleManager().getPrefixedComponent(player, "command.overflow-enderchest-full"));
            return;
        }

        OverflowManager overflowMgr = plugin.getOverflowManager();
        if (overflowMgr != null && overflowMgr.shouldChargeFee()) {
            if (!overflowMgr.chargeRetrievalFee(player, totalFitAmount)) {
                return;
            }
        }

        ItemStack toInventory = fitItem.clone();
        toInventory.setAmount(totalFitAmount);
        player.getInventory().addItem(toInventory);

        int newAmount = originalAmount - totalFitAmount;
        if (newAmount <= 0) {
            event.getClickedInventory().setItem(event.getSlot(), null);
        } else {
            ItemStack remainingGuiItem = clickedItem.clone();
            remainingGuiItem.setAmount(newAmount);
            event.getClickedInventory().setItem(event.getSlot(), remainingGuiItem);
        }

        // Remove the claimed amount from the merged list + per-vault sources
        int remaining = originalAmount;
        for (int i = 0; i < merged.size() && remaining > 0; i++) {
            ItemStack item = merged.get(i);
            if (item != null && item.isSimilar(clickedItem)) {
                int amount = item.getAmount();
                if (amount <= remaining) {
                    merged.remove(i);
                    remaining -= amount;
                    i--;
                } else {
                    item.setAmount(amount - remaining);
                    remaining = 0;
                }
            }
        }
        Set<Integer> changedVaults = removeFromAggregateSources(uuid, clickedItem, totalFitAmount);

        // Persist every affected source vault immediately (crash safety)
        if (aggregateOverflowLists.containsKey(uuid)) {
            for (int vaultId : changedVaults) {
                List<ItemStack> remainingList = cleanList(aggregateOverflowLists.get(uuid).get(vaultId));
                final int vid = vaultId;
                if (remainingList.isEmpty()) {
                    queueStorageOperation(uuid, () -> plugin.getStorageManager().getStorage()
                            .clearOverflowItems(uuid, vid));
                } else {
                    queueStorageOperation(uuid, () -> plugin.getStorageManager().getStorage()
                            .saveOverflowItems(uuid, vid, remainingList.toArray(new ItemStack[0])));
                }
            }
        }

        player.sendMessage(plugin.getLocaleManager().getPrefixedComponent(player, "command.overflow-claimed",
                Placeholder.unparsed("count", String.valueOf(originalAmount - newAmount))));
    }

    /** Removes up to {@code amount} of the item from every per-vault source list. */
    private Set<Integer> removeFromAggregateSources(UUID uuid, ItemStack clicked, int amount) {
        Set<Integer> changed = new HashSet<>();
        Map<Integer, List<ItemStack>> sources = aggregateOverflowLists.get(uuid);
        if (sources == null) {
            return changed;
        }
        int remaining = amount;
        for (Map.Entry<Integer, List<ItemStack>> entry : sources.entrySet()) {
            List<ItemStack> list = entry.getValue();
            for (int i = 0; i < list.size() && remaining > 0; i++) {
                ItemStack item = list.get(i);
                if (item != null && item.isSimilar(clicked)) {
                    int itemAmount = item.getAmount();
                    if (itemAmount <= remaining) {
                        list.remove(i);
                        remaining -= itemAmount;
                        i--;
                        changed.add(entry.getKey());
                    } else {
                        item.setAmount(itemAmount - remaining);
                        remaining = 0;
                        changed.add(entry.getKey());
                    }
                }
            }
        }
        return changed;
    }

    private List<ItemStack> cleanList(List<ItemStack> list) {
        List<ItemStack> clean = new ArrayList<>();
        if (list == null) {
            return clean;
        }
        for (ItemStack item : list) {
            if (item != null && item.getType() != Material.AIR) {
                clean.add(item);
            }
        }
        return clean;
    }

    public void handleOverflowClose(Player player, Inventory closedInv) {
        OverflowView view = overflowViews.remove(closedInv);
        if (view == null) {
            return;
        }
        if (view.admin()) {
            return; // Admin views save nothing on close
        }

        // Aggregate overflow session: persist every source vault's remainder
        if (view.vaultId() == 0) {
            UUID aggregateUuid = player.getUniqueId();
            Map<Integer, List<ItemStack>> sources = aggregateOverflowLists.remove(aggregateUuid);
            aggregateOverflowItems.remove(aggregateUuid);
            if (sources != null) {
                for (Map.Entry<Integer, List<ItemStack>> entry : sources.entrySet()) {
                    List<ItemStack> remainingList = cleanList(entry.getValue());
                    final int vid = entry.getKey();
                    if (remainingList.isEmpty()) {
                        queueStorageOperation(aggregateUuid, () -> plugin.getStorageManager().getStorage()
                                .clearOverflowItems(aggregateUuid, vid));
                    } else {
                        queueStorageOperation(aggregateUuid, () -> plugin.getStorageManager().getStorage()
                                .saveOverflowItems(aggregateUuid, vid, remainingList.toArray(new ItemStack[0])));
                    }
                }
            }
            return;
        }

        UUID uuid = player.getUniqueId();
        int vaultId = view.vaultId();
        VaultKey key = new VaultKey(uuid, vaultId);
        List<ItemStack> remaining = activeOverflowItems.remove(key);
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
                        () -> plugin.getStorageManager().getStorage().clearOverflowItems(uuid, vaultId));
            } else {
                ItemStack[] arr = cleanRemaining.toArray(new ItemStack[0]);
                saveFuture = queueStorageOperation(uuid,
                        () -> plugin.getStorageManager().getStorage().saveOverflowItems(uuid, vaultId, arr));
            }
            saveFuture.exceptionally(ex -> {
                EnderChest.trackError(ex);
                return null;
            });
            trackPendingSave(uuid, saveFuture);

            // Immediately save vault contents to database
            Inventory enderchestInv = liveData.getIfPresent(key);
            if (enderchestInv != null) {
                saveVault(uuid, player.getName(), vaultId, enderchestInv)
                        .exceptionally(ex -> {
                            EnderChest.trackError(ex);
                            return null;
                        });
            }
        }
    }
}
