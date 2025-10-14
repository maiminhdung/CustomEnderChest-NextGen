package org.maiminhdung.customenderchest.storage;

import org.bukkit.inventory.ItemStack;
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

    // Overflow storage for items beyond permission limit
    CompletableFuture<Void> saveOverflowItems(UUID playerUUID, ItemStack[] items);
    CompletableFuture<ItemStack[]> loadOverflowItems(UUID playerUUID);
    CompletableFuture<Void> clearOverflowItems(UUID playerUUID);
    CompletableFuture<Boolean> hasOverflowItems(UUID playerUUID);
}