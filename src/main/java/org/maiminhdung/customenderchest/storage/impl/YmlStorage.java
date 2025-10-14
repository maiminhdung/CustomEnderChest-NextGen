package org.maiminhdung.customenderchest.storage.impl;

import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.data.ItemSerializer;
import org.maiminhdung.customenderchest.storage.StorageInterface;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class YmlStorage implements StorageInterface {

    private final File dataFolder;

    public YmlStorage(EnderChest plugin) {
        this.dataFolder = new File(plugin.getDataFolder(), "playerdata");
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
    }

    private File getPlayerFile(UUID playerUUID) {
        return new File(dataFolder, playerUUID.toString() + ".yml");
    }

    @Override
    public CompletableFuture<ItemStack[]> loadEnderChest(UUID playerUUID) {
        return CompletableFuture.supplyAsync(() -> {
            File playerFile = getPlayerFile(playerUUID);
            if (!playerFile.exists()) {
                return null;
            }
            YamlConfiguration config = YamlConfiguration.loadConfiguration(playerFile);

            // Take size from config
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> serializedItems = (List<Map<String, Object>>) config.getList("enderchest-inventory");

            return ItemSerializer.deserialize(serializedItems);
        });
    }

    @Override
    public CompletableFuture<Void> saveEnderChest(UUID playerUUID, String playerName, int size, ItemStack[] items) {
        return CompletableFuture.runAsync(() -> {
            File playerFile = getPlayerFile(playerUUID);
            YamlConfiguration config = new YamlConfiguration();
            config.set("player-name", playerName);
            config.set("enderchest-size", size);
            config.set("enderchest-inventory", ItemSerializer.serialize(items));
            try {
                config.save(playerFile);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    // --- Another method ---

    @Override
    public void init() {
        // Don't need to do anything for YML storage
    }

    @Override
    public CompletableFuture<Integer> loadEnderChestSize(UUID playerUUID) {
        return CompletableFuture.supplyAsync(() -> {
            File playerFile = getPlayerFile(playerUUID);
            if (!playerFile.exists()) return 0;
            return YamlConfiguration.loadConfiguration(playerFile).getInt("enderchest-size", 0);
        });
    }

    @Override
    public CompletableFuture<Void> deleteEnderChest(UUID playerUUID) {
        return CompletableFuture.runAsync(() -> {
            File playerFile = getPlayerFile(playerUUID);
            if (playerFile.exists()) {
                playerFile.delete();
            }
        });
    }

    @Override
    public CompletableFuture<String> getPlayerName(UUID playerUUID) {
        return CompletableFuture.supplyAsync(() -> {
            File playerFile = getPlayerFile(playerUUID);
            if (!playerFile.exists()) return null;
            return YamlConfiguration.loadConfiguration(playerFile).getString("player-name");
        });
    }

    @Override
    public CompletableFuture<Void> saveOverflowItems(UUID playerUUID, ItemStack[] items) {
        return CompletableFuture.runAsync(() -> {
            File playerFile = getPlayerFile(playerUUID);
            YamlConfiguration config = YamlConfiguration.loadConfiguration(playerFile);
            config.set("overflow-items", ItemSerializer.serialize(items));
            config.set("overflow-created-at", System.currentTimeMillis());
            try {
                config.save(playerFile);
            } catch (Exception e) {
                EnderChest.getInstance().getLogger().severe("Failed to save overflow items for " + playerUUID);
                e.printStackTrace();
            }
        });
    }

    @Override
    public CompletableFuture<ItemStack[]> loadOverflowItems(UUID playerUUID) {
        return CompletableFuture.supplyAsync(() -> {
            File playerFile = getPlayerFile(playerUUID);
            if (!playerFile.exists()) return null;

            YamlConfiguration config = YamlConfiguration.loadConfiguration(playerFile);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> serializedItems = (List<Map<String, Object>>) config.getList("overflow-items");

            if (serializedItems == null) return null;
            return ItemSerializer.deserialize(serializedItems);
        });
    }

    @Override
    public CompletableFuture<Void> clearOverflowItems(UUID playerUUID) {
        return CompletableFuture.runAsync(() -> {
            File playerFile = getPlayerFile(playerUUID);
            if (!playerFile.exists()) return;

            YamlConfiguration config = YamlConfiguration.loadConfiguration(playerFile);
            config.set("overflow-items", null);
            config.set("overflow-created-at", null);
            try {
                config.save(playerFile);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> hasOverflowItems(UUID playerUUID) {
        return CompletableFuture.supplyAsync(() -> {
            File playerFile = getPlayerFile(playerUUID);
            if (!playerFile.exists()) return false;

            YamlConfiguration config = YamlConfiguration.loadConfiguration(playerFile);
            return config.contains("overflow-items");
        });
    }
}