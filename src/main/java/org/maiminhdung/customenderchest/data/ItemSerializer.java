package org.maiminhdung.customenderchest.data;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.Scheduler;
import org.maiminhdung.customenderchest.utils.Text;

import net.kyori.adventure.text.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * ItemSerializer using Paper's data component API
 * This uses serializeAsBytes/deserializeBytes which is cross-version compatible
 * and handles component changes between versions (e.g., 1.21.4 -> 1.21.5)
 */
public final class ItemSerializer {

    private static final Logger LOGGER = Logger.getLogger(ItemSerializer.class.getName());

    /**
     * Serialize ItemStack array to Base64 string using Paper's data component API
     * This method converts ItemStack components to raw bytes, which Paper's DataFixer handles automatically
     *
     * @param items Array of ItemStack to serialize
     * @return Base64 encoded string containing serialized items
     * @throws IOException if serialization fails
     */
    public static String toBase64(ItemStack[] items) throws IOException {
        if (items == null || items.length == 0) {
            return "";
        }

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             DataOutputStream dataOutput = new DataOutputStream(outputStream)) {

            // Write array length
            dataOutput.writeInt(items.length);

            // Serialize each ItemStack using Paper's component serializer
            for (ItemStack item : items) {
                if (item == null || item.getType().isAir()) {
                    // Null or air item - write false flag
                    dataOutput.writeBoolean(false);
                } else {
                    // Valid item - write true flag and serialize
                    dataOutput.writeBoolean(true);

                    // Use Paper's serializeAsBytes - this handles all components properly
                    byte[] itemBytes = item.serializeAsBytes();
                    dataOutput.writeInt(itemBytes.length);
                    dataOutput.write(itemBytes);
                }
            }

            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        }
    }

    /**
     * Deserialize ItemStack array from Base64 string using Paper's data component API
     * This method uses Paper's DataFixer to automatically upgrade old component formats
     *
     * @param data Base64 encoded string containing serialized items
     * @return Array of deserialized ItemStacks, or empty array if data is invalid
     * @throws IOException if deserialization fails
     */
    public static ItemStack[] fromBase64(String data) throws IOException {
        if (data == null || data.isEmpty()) {
            return new ItemStack[0];
        }

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException e) {
            LOGGER.log(Level.WARNING, "Invalid Base64 data, returning empty inventory");
            return new ItemStack[0];
        }

        // Try to detect format by reading first 4 bytes (array length)
        try (ByteArrayInputStream peekStream = new ByteArrayInputStream(bytes);
             DataInputStream peekInput = new DataInputStream(peekStream)) {

            int firstInt = peekInput.readInt();

            // If the first integer is reasonable (0-256), it's likely new format
            // If it's unreasonable (like -1393754107), it's old BukkitObjectInputStream format
            if (firstInt >= 0 && firstInt <= 256) {
                // Try new format first
                try {
                    return deserializeNewFormat(bytes);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "New format failed, attempting legacy migration: " + e.getMessage());
                    return deserializeLegacyFormat(bytes);
                }
            } else {
                // Definitely old format
                LOGGER.log(Level.INFO, "Detected legacy data format, migrating...");
                return deserializeLegacyFormat(bytes);
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to detect data format", e);
            return new ItemStack[0];
        }
    }

    /**
     * Deserialize new Paper format (raw bytes)
     */
    private static ItemStack[] deserializeNewFormat(byte[] bytes) throws IOException {
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes);
             DataInputStream dataInput = new DataInputStream(inputStream)) {

            int length = dataInput.readInt();

            // Sanity check - reasonable inventory size (max 256 slots)
            if (length < 0 || length > 256) {
                throw new IOException("Invalid array length: " + length);
            }

            ItemStack[] items = new ItemStack[length];

            // Deserialize each ItemStack
            for (int i = 0; i < length; i++) {
                boolean hasItem = dataInput.readBoolean();

                if (hasItem) {
                    int itemBytesLength = dataInput.readInt();

                    // Sanity check for item data size (max 1MB per item)
                    if (itemBytesLength < 0 || itemBytesLength > 1_000_000) {
                        LOGGER.log(Level.WARNING, "Invalid item data size at slot " + i + ": " + itemBytesLength + ", skipping item");
                        items[i] = null;
                        continue;
                    }

                    byte[] itemBytes = new byte[itemBytesLength];
                    dataInput.readFully(itemBytes);

                    try {
                        // Use Paper's deserializeBytes - this automatically applies DataFixer
                        // to upgrade old component formats (e.g., <1.21.4 -> 1.21.5)
                        items[i] = ItemStack.deserializeBytes(itemBytes);
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Failed to deserialize item at slot " + i + ": " + e.getMessage());
                        items[i] = null;
                    }
                } else {
                    items[i] = null;
                }
            }

            return items;
        }
    }

    /**
     * Deserialize legacy BukkitObjectInputStream format and migrate to Paper format
     * This handles data from old plugin versions (pre-Paper migration)
     * <p>
     * IMPORTANT: For data from <1.21.4 -> 1.21.5+ migration:
     * If you get errors, you MUST convert data on <1.21.4 server first before upgrading to 1.21.5+
     */
    private static ItemStack[] deserializeLegacyFormat(byte[] bytes) throws IOException {
        LOGGER.log(Level.WARNING, "=================================================================");
        LOGGER.log(Level.WARNING, "Detected legacy data format - attempting migration...");
        LOGGER.log(Level.WARNING, "=================================================================");

        // Don't spam OPs on every legacy data load - only notify if migration fails

        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes);
             BukkitObjectInputStream objectInput = new BukkitObjectInputStream(inputStream)) {

            Object obj = objectInput.readObject();

            if (obj instanceof ItemStack[] legacyItems) {
                LOGGER.log(Level.INFO, "Successfully loaded " + legacyItems.length + " slots from legacy format");

                // Items are already loaded, Paper has already applied DataFixer during deserialization
                // Just return them - they will be re-saved in new format on next save
                int successCount = 0;
                int nullCount = 0;

                for (ItemStack item : legacyItems) {
                    if (item != null) {
                        successCount++;
                    } else {
                        nullCount++;
                    }
                }

                LOGGER.log(Level.INFO, "Legacy migration completed: " + successCount + " items loaded, " + nullCount + " empty slots");
                return legacyItems;

            } else {
                throw new IOException("Unexpected object type in legacy data: " + (obj != null ? obj.getClass() : "null"));
            }

        } catch (ClassNotFoundException e) {
            throw new IOException("ClassNotFoundException during legacy migration", e);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "=================================================================");
            LOGGER.log(Level.SEVERE, "FAILED TO MIGRATE LEGACY DATA FROM <1.21.4 TO 1.21.5+");
            LOGGER.log(Level.SEVERE, "=================================================================");
            LOGGER.log(Level.SEVERE, "This error occurs because item component format changed between");
            LOGGER.log(Level.SEVERE, "Minecraft <1.21.4 and 1.21.5, making old data incompatible.");
            LOGGER.log(Level.SEVERE, "");
            LOGGER.log(Level.SEVERE, "TO FIX THIS ISSUE:");
            LOGGER.log(Level.SEVERE, "1. Downgrade your server back to <1.21.4");
            LOGGER.log(Level.SEVERE, "2. Install this plugin on <1.21.4 server");
            LOGGER.log(Level.SEVERE, "3. Run command: /cec convertall");
            LOGGER.log(Level.SEVERE, "4. Wait for conversion to complete");
            LOGGER.log(Level.SEVERE, "5. Then upgrade server to 1.21.5+");
            LOGGER.log(Level.SEVERE, "");
            LOGGER.log(Level.SEVERE, "CURRENT STATUS:");
            LOGGER.log(Level.SEVERE, "Player will receive EMPTY enderchest.");
            LOGGER.log(Level.SEVERE, "Old data is preserved in database but cannot be loaded.");
            LOGGER.log(Level.SEVERE, "=================================================================");
            LOGGER.log(Level.SEVERE, "Error details:", e);

            // Notify OPs about the failure
            notifyOpsAboutConversionFailure();

            return new ItemStack[0];
        }
    }


    /**
     * Notifies all online OPs about conversion failure
     * Tells them the data couldn't be migrated automatically
     */
    private static void notifyOpsAboutConversionFailure() {
        try {
            Scheduler.runTask(() -> Bukkit.getOnlinePlayers().stream()
                    .filter(org.bukkit.entity.Player::isOp)
                    .forEach(op -> {
                        // Get plugin instance
                        EnderChest plugin = (EnderChest) Bukkit.getPluginManager().getPlugin("CustomEnderChest");
                        if (plugin == null) return;

                        // Get prefix from lang file and use Text utility for parsing
                        Component prefix = plugin.getLocaleManager().getComponent("prefix");

                        op.sendMessage(prefix.append(Text.parse("<bold><dark_red>CRITICAL ERROR!")));
                        op.sendMessage(prefix.append(Text.parse("<red>Failed to migrate player data from old format!")));
                        op.sendMessage(prefix.append(Text.parse("<red>Player received empty enderchest.")));
                        op.sendMessage(Component.empty());
                        op.sendMessage(prefix.append(Text.parse("<bold><yellow>TO FIX:")));
                        op.sendMessage(prefix.append(Text.parse("<yellow>1. Downgrade server to 1.21.4")));
                        op.sendMessage(prefix.append(Text.parse("<yellow>2. Run: /cec convertall")));
                        op.sendMessage(prefix.append(Text.parse("<yellow>3. Then upgrade to 1.21.5+")));
                    })
            );
        } catch (Exception ignored) {
            // Ignore if we can't notify
        }
    }

    // --- Methods for YAML file storage (unchanged) ---

    /**
     * Convert an array of ItemStack to a list of Maps for YAML storage
     *
     * @param items Array of ItemStack to convert
     * @return A list of Maps suitable for YAML serialization
     */
    public static List<Map<String, Object>> serialize(ItemStack[] items) {
        return Stream.of(items)
                .map(item -> (item != null && !item.getType().isAir()) ? item.serialize() : null)
                .collect(Collectors.toList());
    }

    /**
     * Convert a list of Maps (from YAML) back to an array of ItemStack
     *
     * @param mapList List of maps to convert
     * @return An array of ItemStack
     */
    public static ItemStack[] deserialize(List<Map<String, Object>> mapList) {
        if (mapList == null) {
            return new ItemStack[0];
        }
        return mapList.stream()
                .map(map -> (map != null) ? ItemStack.deserialize(map) : null)
                .toArray(ItemStack[]::new);
    }
}
