package org.maiminhdung.customenderchest.data;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ItemSerializer {

    // --- Method for Database (MySQL/H2) ---

    public static String toBase64(ItemStack[] items) throws IOException {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream)) {
            dataOutput.writeObject(items);
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        }
    }

    public static ItemStack[] fromBase64(String data) throws IOException, ClassNotFoundException {
        if (data == null || data.isEmpty()) return null;
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
             BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream)) {
            return (ItemStack[]) dataInput.readObject();
        }
    }

    // --- Method for YML ---

    /**
     * Convert an array of ItemStack to a list of Maps,
     * this format is safe to save into a YAML file.
     * @param items Array of ItemStack to convert.
     * @return A list of Maps.
     */
    public static List<Map<String, Object>> serialize(ItemStack[] items) {
        return Stream.of(items)
                .map(item -> (item != null) ? item.serialize() : null)
                .collect(Collectors.toList());
    }

    /**
     * Convert a list of Maps (read from YAML file) back to an array of ItemStack.
     * @param mapList All list map to convert.
     * @return An array of ItemStack.
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