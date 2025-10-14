package org.maiminhdung.customenderchest.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.Scheduler;
import org.maiminhdung.customenderchest.data.ItemSerializer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Command to convert ALL player data in database from old format to new Paper format
 *
 * This command should be run on <1.21.4 server BEFORE upgrading to 1.21.5+
 * It converts all stored enderchest data from legacy BukkitObjectInputStream format
 * to Paper's cross-version compatible serializeAsBytes format.
 *
 * Usage: /cec convertall
 * Permission: CustomEnderChest.admin
 */
public class ConvertAllCommand implements CommandExecutor {

    private final EnderChest plugin;
    private boolean isConverting = false; // Lock to prevent concurrent conversions

    public ConvertAllCommand(EnderChest plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        // Check admin permission
        if (!sender.hasPermission("CustomEnderChest.admin")) {
            sender.sendMessage("§cYou don't have permission to use this command!");
            return true;
        }

        // Prevent concurrent conversions
        if (isConverting) {
            sender.sendMessage("§c[CustomEnderChest] Conversion is already in progress!");
            return true;
        }

        // Display warning and start conversion
        sender.sendMessage("§e[CustomEnderChest] ===============================================");
        sender.sendMessage("§e[CustomEnderChest] Starting database conversion...");
        sender.sendMessage("§e[CustomEnderChest] This will convert ALL player data to new format.");
        sender.sendMessage("§e[CustomEnderChest] §cDO NOT STOP THE SERVER during this process!");
        sender.sendMessage("§e[CustomEnderChest] ===============================================");

        isConverting = true;

        // Run conversion asynchronously to avoid blocking main thread
        Scheduler.runTaskAsync(() -> {
            try {
                convertAllData(sender);
            } catch (Exception e) {
                sender.sendMessage("§c[CustomEnderChest] §4CRITICAL ERROR during conversion!");
                plugin.getLogger().severe("Failed to convert database: " + e.getMessage());
                plugin.getLogger().severe("Stack trace:");
                for (StackTraceElement element : e.getStackTrace()) {
                    plugin.getLogger().severe("  at " + element.toString());
                }
                isConverting = false;
            }
        });

        return true;
    }

    /**
     * Main conversion logic - processes all player data in database
     * Reads old format data, re-serializes it using Paper's format, and updates database
     */
    private void convertAllData(CommandSender sender) {
        String tableName = plugin.config().getString("storage.table_name", "custom_enderchests");

        try (Connection conn = plugin.getStorageManager().getConnection()) {
            // Step 1: Load all player data from database
            String selectSql = "SELECT player_uuid, player_name, chest_data FROM " + tableName;
            PreparedStatement selectPs = conn.prepareStatement(selectSql);
            ResultSet rs = selectPs.executeQuery();

            List<PlayerData> playerDataList = new ArrayList<>();
            sender.sendMessage("§e[CustomEnderChest] Loading player data from database...");

            while (rs.next()) {
                String uuidStr = rs.getString("player_uuid");
                String playerName = rs.getString("player_name");
                String oldData = rs.getString("chest_data");

                // Skip empty data
                if (oldData == null || oldData.isEmpty()) {
                    continue;
                }

                playerDataList.add(new PlayerData(UUID.fromString(uuidStr), playerName, oldData));
            }

            rs.close();
            selectPs.close();

            sender.sendMessage("§e[CustomEnderChest] Found " + playerDataList.size() + " players with data.");
            sender.sendMessage("§e[CustomEnderChest] Starting conversion process...");

            // Tracking counters
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);
            AtomicInteger skippedCount = new AtomicInteger(0);
            AtomicInteger processedCount = new AtomicInteger(0);

            // Step 2: Process each player's data
            for (PlayerData playerData : playerDataList) {
                try {
                    // Deserialize old format (handles both old and new formats automatically)
                    ItemStack[] items = ItemSerializer.fromBase64(playerData.oldData);

                    if (items == null || items.length == 0) {
                        skippedCount.incrementAndGet();
                        plugin.getLogger().warning("Skipped empty data for player: " + playerData.playerName);
                        continue;
                    }

                    // Re-serialize using Paper's new format
                    String newData = ItemSerializer.toBase64(items);

                    // Check if data actually changed
                    if (newData.equals(playerData.oldData)) {
                        // Data is already in new format, skip update
                        skippedCount.incrementAndGet();
                        plugin.getLogger().info("Data already in new format for: " + playerData.playerName);
                    } else {
                        // Update database with converted data
                        String updateSql = "UPDATE " + tableName + " SET chest_data = ? WHERE player_uuid = ?";
                        PreparedStatement updatePs = conn.prepareStatement(updateSql);
                        updatePs.setString(1, newData);
                        updatePs.setString(2, playerData.uuid.toString());
                        updatePs.executeUpdate();
                        updatePs.close();

                        successCount.incrementAndGet();
                        plugin.getLogger().info("Converted data for: " + playerData.playerName + " (" + items.length + " slots)");
                    }

                } catch (Exception e) {
                    failCount.incrementAndGet();
                    plugin.getLogger().severe("Failed to convert data for " + playerData.playerName + ": " + e.getMessage());
                }

                // Step 3: Send progress updates every 10 players
                int processed = processedCount.incrementAndGet();
                if (processed % 10 == 0 || processed == playerDataList.size()) {
                    Scheduler.runTask(() ->
                        sender.sendMessage("§e[CustomEnderChest] Progress: " + processed + "/" + playerDataList.size() +
                            " (Success: " + successCount.get() + ", Failed: " + failCount.get() + ", Skipped: " + skippedCount.get() + ")")
                    );
                }
            }

            // Step 4: Display final report
            Scheduler.runTask(() -> {
                sender.sendMessage("§e[CustomEnderChest] ===============================================");
                sender.sendMessage("§a[CustomEnderChest] §2CONVERSION COMPLETED!");
                sender.sendMessage("§a[CustomEnderChest] Total players: " + playerDataList.size());
                sender.sendMessage("§a[CustomEnderChest] §2Successfully converted: " + successCount.get());
                sender.sendMessage("§e[CustomEnderChest] §6Skipped (already new format): " + skippedCount.get());
                sender.sendMessage("§c[CustomEnderChest] §4Failed: " + failCount.get());
                sender.sendMessage("§e[CustomEnderChest] ===============================================");

                if (successCount.get() > 0) {
                    sender.sendMessage("§a[CustomEnderChest] Your database has been converted to new format!");
                    sender.sendMessage("§a[CustomEnderChest] You can now safely upgrade to Minecraft 1.21.5+");
                }

                if (failCount.get() > 0) {
                    sender.sendMessage("§c[CustomEnderChest] Some players failed to convert.");
                    sender.sendMessage("§c[CustomEnderChest] Check console for details.");
                }

                isConverting = false;
            });

        } catch (Exception e) {
            // Handle critical errors
            Scheduler.runTask(() -> {
                sender.sendMessage("§c[CustomEnderChest] §4CRITICAL ERROR during conversion!");
                sender.sendMessage("§c[CustomEnderChest] Error: " + e.getMessage());
                sender.sendMessage("§c[CustomEnderChest] Check console for full error details.");
            });
            plugin.getLogger().severe("Critical error during database conversion:");
            plugin.getLogger().severe("Error: " + e.getMessage());
            for (StackTraceElement element : e.getStackTrace()) {
                plugin.getLogger().severe("  at " + element.toString());
            }
            isConverting = false;
        }
    }

    /**
     * Data class to hold player information during conversion
     */
    private static class PlayerData {
        final UUID uuid;
        final String playerName;
        final String oldData;

        PlayerData(UUID uuid, String playerName, String oldData) {
            this.uuid = uuid;
            this.playerName = playerName;
            this.oldData = oldData;
        }
    }
}
