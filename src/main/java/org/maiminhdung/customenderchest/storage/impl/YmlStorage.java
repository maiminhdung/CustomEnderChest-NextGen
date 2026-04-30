package org.maiminhdung.customenderchest.storage.impl;

import static org.maiminhdung.customenderchest.EnderChest.ERROR_TRACKER;

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
            YamlConfiguration config = new YamlConfiguration();
            try {
                config.load(playerFile);
            } catch (Exception e) {
                ERROR_TRACKER.trackError(e);
                throw new java.util.concurrent.CompletionException("Failed to load yml", e);
            }

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
                ERROR_TRACKER.trackError(e);
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
            YamlConfiguration config = new YamlConfiguration();
            try {
                config.load(playerFile);
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
            return config.getInt("enderchest-size", 0);
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
    public CompletableFuture<UUID> findUUIDByName(String playerName) {
        return CompletableFuture.supplyAsync(() -> {
            File[] files = dataFolder.listFiles((dir, name) -> name.endsWith(".yml"));
            if (files == null) return null;

            File latestFile = null;
            long latestTime = -1;

            for (File file : files) {
                try {
                    YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
                    String storedName = config.getString("player-name");
                    if (storedName != null && storedName.equalsIgnoreCase(playerName)) {
                        long modified = file.lastModified();
                        if (modified > latestTime) {
                            latestTime = modified;
                            latestFile = file;
                        }
                    }
                } catch (Exception e) {
                    // Skip invalid files
                }
            }

            if (latestFile != null) {
                String filename = latestFile.getName();
                String uuidStr = filename.substring(0, filename.length() - 4);
                return UUID.fromString(uuidStr);
            }
            return null;
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
                ERROR_TRACKER.trackError(e);
            }
        });
    }

    @Override
    public CompletableFuture<ItemStack[]> loadOverflowItems(UUID playerUUID) {
        return CompletableFuture.supplyAsync(() -> {
            File playerFile = getPlayerFile(playerUUID);
            if (!playerFile.exists()) return null;

            YamlConfiguration config = new YamlConfiguration();
            try {
                config.load(playerFile);
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
            
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
                ERROR_TRACKER.trackError(e);
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

    @Override
    public CompletableFuture<Boolean> hasData(UUID playerUUID) {
        return CompletableFuture.supplyAsync(() -> {
            File playerFile = getPlayerFile(playerUUID);
            return playerFile.exists();
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

            File[] files = dataFolder.listFiles((dir, name) -> name.endsWith(".yml"));
            if (files == null) {
                return new StorageStats(0, 0, 0, 0, 0, 0);
            }

            for (File file : files) {
                totalPlayers++;
                totalDataSize += file.length();

                try {
                    YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> serializedItems = (List<Map<String, Object>>) config.getList("enderchest-inventory");

                    if (serializedItems != null && !serializedItems.isEmpty()) {
                        ItemStack[] items = ItemSerializer.deserialize(serializedItems);
                        if (items != null) {
                            boolean hasAnyItem = false;
                            for (ItemStack item : items) {
                                if (item != null && !item.getType().isAir()) {
                                    totalItems++;
                                    hasAnyItem = true;
                                }
                            }
                            if (hasAnyItem) {
                                playersWithItems++;
                            }
                        }
                    }

                    // Check overflow
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> overflowItems = (List<Map<String, Object>>) config.getList("overflow-items");
                    if (overflowItems != null && !overflowItems.isEmpty()) {
                        totalOverflowPlayers++;
                        ItemStack[] items = ItemSerializer.deserialize(overflowItems);
                        if (items != null) {
                            for (ItemStack item : items) {
                                if (item != null && !item.getType().isAir()) {
                                    totalOverflowItems++;
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    EnderChest.getInstance().getLogger().warning("[YmlStorage] Failed to read file " + file.getName() + ": " + e.getMessage());
                    ERROR_TRACKER.trackError(e);
                }
            }

            return new StorageStats(totalPlayers, playersWithItems, totalItems,
                    totalOverflowPlayers, totalOverflowItems, totalDataSize);
        });
    }

    @Override
    public CompletableFuture<java.util.List<PlayerDataInfo>> getPlayersWithItems() {
        return CompletableFuture.supplyAsync(() -> {
            java.util.List<PlayerDataInfo> result = new java.util.ArrayList<>();

            File[] files = dataFolder.listFiles((dir, name) -> name.endsWith(".yml"));
            if (files == null) {
                return result;
            }

            for (File file : files) {
                try {
                    String fileName = file.getName().replace(".yml", "");
                    UUID uuid = UUID.fromString(fileName);

                    YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
                    String name = config.getString("player-name", "Unknown");
                    int size = config.getInt("enderchest-size", 0);

                    int itemCount = 0;
                    boolean isCorrupted = false;
                    String errorMessage = null;

                    try {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> serializedItems = (List<Map<String, Object>>) config.getList("enderchest-inventory");

                        if (serializedItems != null) {
                            ItemStack[] items = ItemSerializer.deserialize(serializedItems);
                            if (items != null) {
                                for (ItemStack item : items) {
                                    if (item != null && !item.getType().isAir()) {
                                        itemCount++;
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        isCorrupted = true;
                        errorMessage = e.getMessage();
                    }

                    // Check for overflow
                    boolean hasOverflow = config.contains("overflow-items");

                    result.add(new PlayerDataInfo(uuid, name, size, itemCount, hasOverflow, isCorrupted, errorMessage));
                } catch (Exception e) {
                    EnderChest.getInstance().getLogger().warning("[YmlStorage] Failed to parse file " + file.getName() + ": " + e.getMessage());
                }
            }

            return result;
        });
    }
}