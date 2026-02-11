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

public class MySQLStorage implements StorageInterface {

    private final StorageManager storageManager;
    private final String tableName;

    public MySQLStorage(StorageManager storageManager) {
        this.storageManager = storageManager;
        this.tableName = EnderChest.getInstance().config().getString("storage.table_name", "custom_enderchests");
    }

    @Override
    public void init() {
        // Run synchronously to ensure tables exist before any queries
        // Main table
        String sql = "CREATE TABLE IF NOT EXISTS `" + tableName + "` (" +
                "`player_uuid` VARCHAR(36) NOT NULL PRIMARY KEY," +
                "`player_name` VARCHAR(16)," +
                "`chest_size` INT NOT NULL," +
                "`chest_data` LONGTEXT," +
                "`last_seen` BIGINT NOT NULL" +
                ")";
        try (Connection conn = storageManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (Exception e) {
            EnderChest.getInstance().getLogger().severe("Failed to initialize MySQL table!");
            e.printStackTrace();
        }

        // Overflow storage table
        String overflowSql = "CREATE TABLE IF NOT EXISTS `" + tableName + "_overflow` (" +
                "`player_uuid` VARCHAR(36) NOT NULL PRIMARY KEY," +
                "`overflow_data` LONGTEXT," +
                "`created_at` BIGINT NOT NULL" +
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
            String sql = "SELECT chest_data FROM `" + tableName + "` WHERE `player_uuid` = ?";
            try (Connection conn = storageManager.getConnection();
                    PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerUUID.toString());
                ResultSet rs = ps.executeQuery();
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
                                "Failed to load enderchest data for player " + playerUUID + ": " + e.getMessage());
                        return new ItemStack[0];
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
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
                            "UPDATE `" + tableName + "` SET `chest_data` = ? WHERE `player_uuid` = ?")) {
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
            String sql = "SELECT chest_size FROM `" + tableName + "` WHERE `player_uuid` = ?";
            try (Connection conn = storageManager.getConnection();
                    PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerUUID.toString());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    return rs.getInt("chest_size");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return 0;
        });
    }

    @Override
    public CompletableFuture<Void> saveEnderChest(UUID playerUUID, String playerName, int size, ItemStack[] items) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO `" + tableName
                    + "` (player_uuid, player_name, chest_size, chest_data, last_seen) " +
                    "VALUES(?, ?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE " +
                    "player_name = ?, chest_size = ?, chest_data = ?, last_seen = ?";
            try (Connection conn = storageManager.getConnection();
                    PreparedStatement ps = conn.prepareStatement(sql)) {

                String data = ItemSerializer.toBase64(items);
                long timestamp = System.currentTimeMillis();

                ps.setString(1, playerUUID.toString());
                ps.setString(2, playerName);
                ps.setInt(3, size);
                ps.setString(4, data);
                ps.setLong(5, timestamp);

                ps.setString(6, playerName);
                ps.setInt(7, size);
                ps.setString(8, data);
                ps.setLong(9, timestamp);

                ps.executeUpdate();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    @Override
    public CompletableFuture<Void> deleteEnderChest(UUID playerUUID) {
        return CompletableFuture.runAsync(() -> {
            String sql = "DELETE FROM `" + tableName + "` WHERE `player_uuid` = ?";
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
            String sql = "SELECT player_name FROM `" + tableName + "` WHERE `player_uuid` = ?";
            try (Connection conn = storageManager.getConnection();
                    PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerUUID.toString());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    return rs.getString("player_name");
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
            String sql = "SELECT `player_uuid` FROM `" + tableName + "` WHERE LOWER(`player_name`) = LOWER(?)";
            try (Connection conn = storageManager.getConnection();
                    PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerName);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    return UUID.fromString(rs.getString("player_uuid"));
                }
            } catch (Exception e) {
                EnderChest.getInstance().getLogger().warning(
                        "[MySQLStorage] Failed to find UUID by name for " + playerName + ": " + e.getMessage());
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> saveOverflowItems(UUID playerUUID, ItemStack[] items) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO `" + tableName + "_overflow` (player_uuid, overflow_data, created_at) " +
                    "VALUES(?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE overflow_data = ?, created_at = ?";
            try (Connection conn = storageManager.getConnection();
                    PreparedStatement ps = conn.prepareStatement(sql)) {
                String data = ItemSerializer.toBase64(items);
                long timestamp = System.currentTimeMillis();

                ps.setString(1, playerUUID.toString());
                ps.setString(2, data);
                ps.setLong(3, timestamp);
                ps.setString(4, data);
                ps.setLong(5, timestamp);
                ps.executeUpdate();
            } catch (Exception e) {
                EnderChest.getInstance().getLogger().severe("Failed to save overflow items for " + playerUUID);
                e.printStackTrace();
            }
        });
    }

    @Override
    public CompletableFuture<ItemStack[]> loadOverflowItems(UUID playerUUID) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT overflow_data FROM `" + tableName + "_overflow` WHERE `player_uuid` = ?";
            try (Connection conn = storageManager.getConnection();
                    PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerUUID.toString());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    String data = rs.getString("overflow_data");
                    try {
                        return ItemSerializer.fromBase64(data);
                    } catch (Exception e) {
                        EnderChest.getInstance().getLogger().warning("Failed to load overflow items for " + playerUUID);
                        return new ItemStack[0];
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
            String sql = "DELETE FROM `" + tableName + "_overflow` WHERE `player_uuid` = ?";
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
    public CompletableFuture<Boolean> hasOverflowItems(UUID playerUUID) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT COUNT(*) FROM `" + tableName + "_overflow` WHERE `player_uuid` = ?";
            try (Connection conn = storageManager.getConnection();
                    PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerUUID.toString());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    return rs.getInt(1) > 0;
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
            String sql = "SELECT 1 FROM `" + tableName + "` WHERE `player_uuid` = ? LIMIT 1";
            try (Connection conn = storageManager.getConnection();
                    PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerUUID.toString());
                ResultSet rs = ps.executeQuery();
                return rs.next();
            } catch (Exception e) {
                EnderChest.getInstance().getLogger().warning(
                        "[MySQLStorage] Failed to check data existence for " + playerUUID + ": " + e.getMessage());
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
                    "FROM `" + tableName + "`";
            try (Connection conn = storageManager.getConnection();
                    PreparedStatement ps = conn.prepareStatement(countSql)) {
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    totalPlayers = rs.getInt("total");
                    playersWithItems = rs.getInt("with_items");
                    totalDataSize = rs.getLong("data_size");
                }
            } catch (Exception e) {
                EnderChest.getInstance().getLogger()
                        .warning("[MySQLStorage] Failed to get player counts: " + e.getMessage());
            }

            // Count total items by iterating through all players
            String itemsSql = "SELECT chest_data FROM `" + tableName
                    + "` WHERE chest_data IS NOT NULL AND chest_data != ''";
            try (Connection conn = storageManager.getConnection();
                    PreparedStatement ps = conn.prepareStatement(itemsSql)) {
                ResultSet rs = ps.executeQuery();
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
            } catch (Exception e) {
                EnderChest.getInstance().getLogger().warning("[MySQLStorage] Failed to count items: " + e.getMessage());
            }

            // Count overflow data
            String overflowCountSql = "SELECT COUNT(*) as total FROM `" + tableName
                    + "_overflow` WHERE overflow_data IS NOT NULL";
            try (Connection conn = storageManager.getConnection();
                    PreparedStatement ps = conn.prepareStatement(overflowCountSql)) {
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    totalOverflowPlayers = rs.getInt("total");
                }
            } catch (Exception e) {
                EnderChest.getInstance().getLogger()
                        .warning("[MySQLStorage] Failed to count overflow players: " + e.getMessage());
            }

            // Count overflow items
            String overflowItemsSql = "SELECT overflow_data FROM `" + tableName
                    + "_overflow` WHERE overflow_data IS NOT NULL";
            try (Connection conn = storageManager.getConnection();
                    PreparedStatement ps = conn.prepareStatement(overflowItemsSql)) {
                ResultSet rs = ps.executeQuery();
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
            } catch (Exception e) {
                EnderChest.getInstance().getLogger()
                        .warning("[MySQLStorage] Failed to count overflow items: " + e.getMessage());
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
                            "SELECT `player_uuid` FROM `" + tableName + "_overflow`")) {
                ResultSet rsOvf = psOvf.executeQuery();
                while (rsOvf.next()) {
                    overflowUUIDs.add(UUID.fromString(rsOvf.getString("player_uuid")));
                }
            } catch (Exception ignored) {
            }

            String sql = "SELECT player_uuid, player_name, chest_size, chest_data FROM `" + tableName + "`";
            try (Connection conn = storageManager.getConnection();
                    PreparedStatement ps = conn.prepareStatement(sql)) {
                ResultSet rs = ps.executeQuery();
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
            } catch (Exception e) {
                EnderChest.getInstance().getLogger()
                        .warning("[MySQLStorage] Failed to get players with items: " + e.getMessage());
            }

            return result;
        });
    }
}
