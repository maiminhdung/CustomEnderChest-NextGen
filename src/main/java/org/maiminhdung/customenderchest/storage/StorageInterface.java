package org.maiminhdung.customenderchest.storage;

import org.bukkit.inventory.ItemStack;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface StorageInterface {

    // Initialize tables
    void init();

    // Load player data
    CompletableFuture<ItemStack[]> loadEnderChest(UUID playerUUID);

    CompletableFuture<Integer> loadEnderChestSize(UUID playerUUID);

    // Save player data
    CompletableFuture<Void> saveEnderChest(UUID playerUUID, String playerName, int size, ItemStack[] items);

    // Delete player data
    CompletableFuture<Void> deleteEnderChest(UUID playerUUID);

    // Get player name from UUID (for offline players)
    CompletableFuture<String> getPlayerName(UUID playerUUID);

    // Check if player has any data in the database
    CompletableFuture<Boolean> hasData(UUID playerUUID);

    // Find player UUID by name (for cross online/offline mode compatibility)
    // Returns null if no player with that name is found
    CompletableFuture<UUID> findUUIDByName(String playerName);

    // Overflow storage for items beyond permission limit
    CompletableFuture<Void> saveOverflowItems(UUID playerUUID, ItemStack[] items);

    CompletableFuture<ItemStack[]> loadOverflowItems(UUID playerUUID);

    CompletableFuture<Void> clearOverflowItems(UUID playerUUID);

    CompletableFuture<Boolean> hasOverflowItems(UUID playerUUID);

    // Statistics methods
    CompletableFuture<StorageStats> getStorageStats();

    CompletableFuture<java.util.List<PlayerDataInfo>> getPlayersWithItems();

    /**
     * Statistics data class
     */
    class StorageStats {
        public final int totalPlayers;
        public final int playersWithItems;
        public final int totalItems;
        public final int totalOverflowPlayers;
        public final int totalOverflowItems;
        public final long totalDataSize; // in bytes

        public StorageStats(int totalPlayers, int playersWithItems, int totalItems,
                int totalOverflowPlayers, int totalOverflowItems, long totalDataSize) {
            this.totalPlayers = totalPlayers;
            this.playersWithItems = playersWithItems;
            this.totalItems = totalItems;
            this.totalOverflowPlayers = totalOverflowPlayers;
            this.totalOverflowItems = totalOverflowItems;
            this.totalDataSize = totalDataSize;
        }
    }

    /**
     * Player data info for validation
     */
    class PlayerDataInfo {
        public final UUID playerUUID;
        public final String playerName;
        public final int chestSize;
        public final int itemCount;
        public final boolean hasOverflow;
        public final boolean isCorrupted;
        public final String errorMessage;

        public PlayerDataInfo(UUID playerUUID, String playerName, int chestSize, int itemCount,
                boolean hasOverflow, boolean isCorrupted, String errorMessage) {
            this.playerUUID = playerUUID;
            this.playerName = playerName;
            this.chestSize = chestSize;
            this.itemCount = itemCount;
            this.hasOverflow = hasOverflow;
            this.isCorrupted = isCorrupted;
            this.errorMessage = errorMessage;
        }
    }
}