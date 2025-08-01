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
        // Run the table creation asynchronously
        CompletableFuture.runAsync(() -> {
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
        });
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
                    return ItemSerializer.fromBase64(rs.getString("chest_data"));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return null; // Return null if no data found
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
            return 0; // Size 0 if not found
        });
    }

    @Override
    public CompletableFuture<Void> saveEnderChest(UUID playerUUID, String playerName, int size, ItemStack[] items) {
        return CompletableFuture.runAsync(() -> {
            // This SQL statement uses ON DUPLICATE KEY UPDATE to handle both insert and update in one query
            String sql = "INSERT INTO `" + tableName + "` (player_uuid, player_name, chest_size, chest_data, last_seen) " +
                    "VALUES(?, ?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE " +
                    "player_name = ?, chest_size = ?, chest_data = ?, last_seen = ?";
            try (Connection conn = storageManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {

                String data = ItemSerializer.toBase64(items);
                long timestamp = System.currentTimeMillis();

                // Query parameters for the INSERT
                ps.setString(1, playerUUID.toString());
                ps.setString(2, playerName);
                ps.setInt(3, size);
                ps.setString(4, data);
                ps.setLong(5, timestamp);

                // Query parameters for the UPDATE
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
}