package org.maiminhdung.customenderchest.storage.impl;

import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.data.ItemSerializer;
import org.maiminhdung.customenderchest.storage.StorageInterface;
import org.maiminhdung.customenderchest.storage.StorageManager;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class H2Storage implements StorageInterface {

    private final StorageManager storageManager;
    private final String tableName;

    // Regex pattern for valid SQL table names (alphanumeric and underscores only)
    private static final java.util.regex.Pattern VALID_TABLE_NAME = java.util.regex.Pattern.compile("^[a-zA-Z0-9_]+$");

    public H2Storage(StorageManager storageManager) {
        this.storageManager = storageManager;
        String configTableName = EnderChest.getInstance().config().getString("storage.table_name", "custom_enderchests");
        
        // Validate table name to prevent SQL injection
        if (!VALID_TABLE_NAME.matcher(configTableName).matches()) {
            EnderChest.getInstance().getLogger().severe("[H2Storage] Invalid table name in config: '" + configTableName + 
                    "'. Using default 'custom_enderchests'. Table names must only contain letters, numbers, and underscores.");
            this.tableName = "custom_enderchests";
        } else {
            this.tableName = configTableName;
        }
    }

    @Override
    public void init() {
        // Run synchronously to ensure tables exist before any queries
        // Main table
        String sql = "CREATE TABLE IF NOT EXISTS " + tableName + " (" +
                "player_uuid VARCHAR(36) NOT NULL PRIMARY KEY," +
                "player_name VARCHAR(16)," +
                "chest_size INT NOT NULL," +
                "chest_data LONGTEXT," +
                "last_seen BIGINT NOT NULL" +
                ")";
        try (Connection conn = storageManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (Exception e) {
            EnderChest.getInstance().getLogger().severe("Failed to initialize H2 table!");
            e.printStackTrace();
        }

        // Overflow storage table
        String overflowSql = "CREATE TABLE IF NOT EXISTS " + tableName + "_overflow (" +
                "player_uuid VARCHAR(36) NOT NULL PRIMARY KEY," +
                "overflow_data LONGTEXT," +
                "created_at BIGINT NOT NULL" +
                ")";
        try (Connection conn = storageManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(overflowSql)) {
            ps.executeUpdate();
            EnderChest.getInstance().getLogger().info("Overflow storage table initialized successfully.");
        } catch (Exception e) {
            EnderChest.getInstance().getLogger().severe("Failed to initialize overflow table!");
            e.printStackTrace();
        }
    }

    @Override
    public CompletableFuture<ItemStack[]> loadEnderChest(UUID playerUUID) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT chest_data FROM " + tableName + " WHERE player_uuid = ?";
            try (Connection conn = storageManager.getConnection();
                    PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setQueryTimeout(10); // 10 second query timeout
                ps.setString(1, playerUUID.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String data = rs.getString("chest_data");
                        try {
                            ItemStack[] items = ItemSerializer.fromBase64(data);

                            // Auto-save migrated data in new format
                            if (items != null && items.length > 0) {
                                try {
                                    String newData = ItemSerializer.toBase64(items);
                                    if (!newData.equals(data)) {
                                        EnderChest.getInstance().getLogger().info(
                                                "[Migration] Auto-saving migrated data for player " + playerUUID);
                                        autoSaveMigratedData(playerUUID, newData);
                                    }
                                } catch (Exception e) {
                                    // Ignore save errors, data is already loaded successfully
                                }
                            }

                            return items;
                        } catch (Exception e) {
                            EnderChest.getInstance().getLogger().warning(
                                    "Failed to deserialize enderchest data for player " + playerUUID + ": "
                                            + e.getMessage());
                            return new ItemStack[0];
                        }
                    }
                }
            } catch (Exception e) {
                // Log detailed error for database issues
                String errorMsg = e.getMessage();
                if (errorMsg != null && (errorMsg.contains("corrupted") || errorMsg.contains("MVStoreException"))) {
                    EnderChest.getInstance().getLogger().severe(
                            "[H2Storage] Database file appears to be corrupted! Player: " + playerUUID +
                                    ". Consider restoring from backup or deleting the database file to recreate.");
                } else {
                    EnderChest.getInstance().getLogger().severe(
                            "[H2Storage] Failed to load enderchest for " + playerUUID + ": " + errorMsg);
                }
                // Only print full stack trace in debug mode
                if (EnderChest.getInstance().config().getBoolean("general.debug")) {
                    e.printStackTrace();
                }
            }
            return null;
        });
    }

    /**
     * Auto-save migrated data in background
     */
    private void autoSaveMigratedData(UUID playerUUID, String newData) {
        CompletableFuture.runAsync(() -> {
            try (Connection conn = storageManager.getConnection();
                    PreparedStatement ps = conn.prepareStatement(
                            "UPDATE " + tableName + " SET chest_data = ? WHERE player_uuid = ?")) {
                ps.setString(1, newData);
                ps.setString(2, playerUUID.toString());
                ps.executeUpdate();
            } catch (Exception e) {
                EnderChest.getInstance().getLogger().warning("Failed to auto-save migrated data: " + e.getMessage());
            }
        });
    }

    @Override
    public CompletableFuture<Integer> loadEnderChestSize(UUID playerUUID) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT chest_size FROM " + tableName + " WHERE player_uuid = ?";
            try (Connection conn = storageManager.getConnection();
                    PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setQueryTimeout(10);
                ps.setString(1, playerUUID.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt("chest_size");
                    }
                }
            } catch (Exception e) {
                EnderChest.getInstance().getLogger().warning(
                        "[H2Storage] Failed to load chest size for " + playerUUID + ": " + e.getMessage());
            }
            return 0;
        });
    }

    @Override
    public CompletableFuture<Void> saveEnderChest(UUID playerUUID, String playerName, int size, ItemStack[] items) {
        return CompletableFuture.runAsync(() -> {
            String sql = "MERGE INTO " + tableName + " (player_uuid, player_name, chest_size, chest_data, last_seen) " +
                    "KEY(player_uuid) " +
                    "VALUES(?, ?, ?, ?, ?)";
            try (Connection conn = storageManager.getConnection();
                    PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setQueryTimeout(10);
                String data = ItemSerializer.toBase64(items);
                ps.setString(1, playerUUID.toString());
                ps.setString(2, playerName);
                ps.setInt(3, size);
                ps.setString(4, data);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (Exception e) {
                EnderChest.getInstance().getLogger().severe(
                        "[H2Storage] Failed to save enderchest for " + playerName + " (" + playerUUID + "): "
                                + e.getMessage());
                throw new RuntimeException("Failed to save enderchest data", e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> deleteEnderChest(UUID playerUUID) {
        return CompletableFuture.runAsync(() -> {
            String sql = "DELETE FROM " + tableName + " WHERE player_uuid = ?";
            try (Connection conn = storageManager.getConnection();
                    PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerUUID.toString());
                ps.executeUpdate();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    @Override
    public CompletableFuture<String> getPlayerName(UUID playerUUID) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT player_name FROM " + tableName + " WHERE player_uuid = ?";
            try (Connection conn = storageManager.getConnection();
                    PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setQueryTimeout(10);
                ps.setString(1, playerUUID.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("player_name");
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<UUID> findUUIDByName(String playerName) {
        return CompletableFuture.supplyAsync(() -> {
            // Case-insensitive search for player name
            String sql = "SELECT player_uuid FROM " + tableName + " WHERE LOWER(player_name) = LOWER(?)";
            try (Connection conn = storageManager.getConnection();
                    PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setQueryTimeout(10);
                ps.setString(1, playerName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return UUID.fromString(rs.getString("player_uuid"));
                    }
                }
            } catch (Exception e) {
                EnderChest.getInstance().getLogger().warning(
                        "[H2Storage] Failed to find UUID by name for " + playerName + ": " + e.getMessage());
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> saveOverflowItems(UUID playerUUID, ItemStack[] items) {
        return CompletableFuture.runAsync(() -> {
            String sql = "MERGE INTO " + tableName + "_overflow (player_uuid, overflow_data, created_at) " +
                    "KEY(player_uuid) VALUES(?, ?, ?)";
            try (Connection conn = storageManager.getConnection();
                    PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setQueryTimeout(10);
                String data = ItemSerializer.toBase64(items);
                ps.setString(1, playerUUID.toString());
                ps.setString(2, data);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (Exception e) {
                EnderChest.getInstance().getLogger().severe("Failed to save overflow items for " + playerUUID);
                throw new RuntimeException("Failed to save overflow items", e);
            }
        });
    }

    @Override
    public CompletableFuture<ItemStack[]> loadOverflowItems(UUID playerUUID) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT overflow_data FROM " + tableName + "_overflow WHERE player_uuid = ?";
            try (Connection conn = storageManager.getConnection();
                    PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setQueryTimeout(10);
                ps.setString(1, playerUUID.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String data = rs.getString("overflow_data");
                        try {
                            return ItemSerializer.fromBase64(data);
                        } catch (Exception e) {
                            EnderChest.getInstance().getLogger().warning("Failed to load overflow items for " + playerUUID);
                            return new ItemStack[0];
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> clearOverflowItems(UUID playerUUID) {
        return CompletableFuture.runAsync(() -> {
            String sql = "DELETE FROM " + tableName + "_overflow WHERE player_uuid = ?";
            try (Connection conn = storageManager.getConnection();
                    PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setQueryTimeout(10);
                ps.setString(1, playerUUID.toString());
                ps.executeUpdate();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> hasOverflowItems(UUID playerUUID) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT COUNT(*) FROM " + tableName + "_overflow WHERE player_uuid = ?";
            try (Connection conn = storageManager.getConnection();
                    PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setQueryTimeout(10);
                ps.setString(1, playerUUID.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt(1) > 0;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return false;
        });
    }

    @Override
    public CompletableFuture<Boolean> hasData(UUID playerUUID) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT 1 FROM " + tableName + " WHERE player_uuid = ? LIMIT 1";
            try (Connection conn = storageManager.getConnection();
                    PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setQueryTimeout(10);
                ps.setString(1, playerUUID.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            } catch (Exception e) {
                EnderChest.getInstance().getLogger().warning(
                        "[H2Storage] Failed to check data existence for " + playerUUID + ": " + e.getMessage());
            }
            return false;
        });
    }

    @Override
    public CompletableFuture<StorageStats> getStorageStats() {
        return CompletableFuture.supplyAsync(() -> {
            int totalPlayers = 0;
            int playersWithItems = 0;
            int totalItems = 0;
            int totalOverflowPlayers = 0;
            int totalOverflowItems = 0;
            long totalDataSize = 0;

            // Count total players and players with items
            String countSql = "SELECT COUNT(*) as total, " +
                    "SUM(CASE WHEN chest_data IS NOT NULL AND chest_data != '' THEN 1 ELSE 0 END) as with_items, " +
                    "SUM(COALESCE(LENGTH(chest_data), 0)) as data_size " +
                    "FROM " + tableName;
            try (Connection conn = storageManager.getConnection();
                    PreparedStatement ps = conn.prepareStatement(countSql)) {
                ps.setQueryTimeout(30);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        totalPlayers = rs.getInt("total");
                        playersWithItems = rs.getInt("with_items");
                        totalDataSize = rs.getLong("data_size");
                    }
                }
            } catch (Exception e) {
                EnderChest.getInstance().getLogger()
                        .warning("[H2Storage] Failed to get player counts: " + e.getMessage());
            }

            // Count total items by iterating through all players
            String itemsSql = "SELECT chest_data FROM " + tableName
                    + " WHERE chest_data IS NOT NULL AND chest_data != ''";
            try (Connection conn = storageManager.getConnection();
                    PreparedStatement ps = conn.prepareStatement(itemsSql)) {
                ps.setQueryTimeout(60);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String data = rs.getString("chest_data");
                        try {
                            ItemStack[] items = ItemSerializer.fromBase64(data);
                            if (items != null) {
                                for (ItemStack item : items) {
                                    if (item != null && !item.getType().isAir()) {
                                        totalItems++;
                                    }
                                }
                            }
                        } catch (Exception ignored) {
                            // Skip corrupted data
                        }
                    }
                }
            } catch (Exception e) {
                EnderChest.getInstance().getLogger().warning("[H2Storage] Failed to count items: " + e.getMessage());
            }

            // Count overflow data
            String overflowCountSql = "SELECT COUNT(*) as total FROM " + tableName
                    + "_overflow WHERE overflow_data IS NOT NULL";
            try (Connection conn = storageManager.getConnection();
                    PreparedStatement ps = conn.prepareStatement(overflowCountSql)) {
                ps.setQueryTimeout(30);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        totalOverflowPlayers = rs.getInt("total");
                    }
                }
            } catch (Exception e) {
                EnderChest.getInstance().getLogger()
                        .warning("[H2Storage] Failed to count overflow players: " + e.getMessage());
            }

            // Count overflow items
            String overflowItemsSql = "SELECT overflow_data FROM " + tableName
                    + "_overflow WHERE overflow_data IS NOT NULL";
            try (Connection conn = storageManager.getConnection();
                    PreparedStatement ps = conn.prepareStatement(overflowItemsSql)) {
                ps.setQueryTimeout(60);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String data = rs.getString("overflow_data");
                        try {
                            ItemStack[] items = ItemSerializer.fromBase64(data);
                            if (items != null) {
                                for (ItemStack item : items) {
                                    if (item != null && !item.getType().isAir()) {
                                        totalOverflowItems++;
                                    }
                                }
                            }
                        } catch (Exception ignored) {
                            // Skip corrupted data
                        }
                    }
                }
            } catch (Exception e) {
                EnderChest.getInstance().getLogger()
                        .warning("[H2Storage] Failed to count overflow items: " + e.getMessage());
            }

            return new StorageStats(totalPlayers, playersWithItems, totalItems,
                    totalOverflowPlayers, totalOverflowItems, totalDataSize);
        });
    }

    @Override
    public CompletableFuture<java.util.List<PlayerDataInfo>> getPlayersWithItems() {
        return CompletableFuture.supplyAsync(() -> {
            java.util.List<PlayerDataInfo> result = new java.util.ArrayList<>();

            // Pre-load overflow UUIDs to avoid N+1 queries
            java.util.Set<UUID> overflowUUIDs = new java.util.HashSet<>();
            try (Connection connOvf = storageManager.getConnection();
                    PreparedStatement psOvf = connOvf.prepareStatement(
                            "SELECT player_uuid FROM " + tableName + "_overflow")) {
                psOvf.setQueryTimeout(30);
                try (ResultSet rsOvf = psOvf.executeQuery()) {
                    while (rsOvf.next()) {
                        overflowUUIDs.add(UUID.fromString(rsOvf.getString("player_uuid")));
                    }
                }
            } catch (Exception ignored) {
            }

            String sql = "SELECT player_uuid, player_name, chest_size, chest_data FROM " + tableName;
            try (Connection conn = storageManager.getConnection();
                    PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setQueryTimeout(60);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID uuid = UUID.fromString(rs.getString("player_uuid"));
                        String name = rs.getString("player_name");
                        int size = rs.getInt("chest_size");
                        String data = rs.getString("chest_data");

                        int itemCount = 0;
                        boolean isCorrupted = false;
                        String errorMessage = null;

                        if (data != null && !data.isEmpty()) {
                            try {
                                ItemStack[] items = ItemSerializer.fromBase64(data);
                                if (items != null) {
                                    for (ItemStack item : items) {
                                        if (item != null && !item.getType().isAir()) {
                                            itemCount++;
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                isCorrupted = true;
                                errorMessage = e.getMessage();
                            }
                        }

                        boolean hasOverflow = overflowUUIDs.contains(uuid);
                        result.add(new PlayerDataInfo(uuid, name, size, itemCount, hasOverflow, isCorrupted, errorMessage));
                    }
                }
            } catch (Exception e) {
                EnderChest.getInstance().getLogger()
                        .warning("[H2Storage] Failed to get players with items: " + e.getMessage());
            }

            return result;
        });
    }
}
