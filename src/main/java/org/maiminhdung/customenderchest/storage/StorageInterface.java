package org.maiminhdung.customenderchest.storage;

import org.bukkit.inventory.ItemStack;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Persistence contract for the multi-vault storage layer.
 *
 * Every player owns one or more vaults (vault 1 is the default/legacy Ender
 * Chest). Chest data, size and overflow are stored per vault. Implementations
 * migrate pre-multi-vault ("legacy") rows into vault 1 automatically on load,
 * so old data is never lost.
 */
public interface StorageInterface {

	// Initialize tables
	void init();

	// ==================== Vault-aware API ====================

	/**
	 * Loads one vault of a player.
	 *
	 * @return the stored size + items, or null when the player has no data for
	 *         this vault. An empty items array with size &gt; 0 marks a record
	 *         whose data could not be deserialized (data protection path).
	 */
	CompletableFuture<VaultData> loadVault(UUID playerUUID, int vaultId);

	/** Saves one vault (upsert). */
	CompletableFuture<Void> saveVault(UUID playerUUID, String playerName, int vaultId, int size, ItemStack[] items);

	/** Deletes one vault (chest + overflow of that vault). */
	CompletableFuture<Void> deleteVault(UUID playerUUID, int vaultId);

	/**
	 * All vault ids that have any stored data for the player (chest or
	 * overflow), including legacy data reported as vault 1. Sorted ascending.
	 */
	CompletableFuture<List<Integer>> getVaultIds(UUID playerUUID);

	// ==================== Overflow (per vault) ====================

	CompletableFuture<Void> saveOverflowItems(UUID playerUUID, int vaultId, ItemStack[] items);

	/** @return items or null when the vault has no overflow record */
	CompletableFuture<ItemStack[]> loadOverflowItems(UUID playerUUID, int vaultId);

	CompletableFuture<Void> clearOverflowItems(UUID playerUUID, int vaultId);

	CompletableFuture<Boolean> hasOverflowItems(UUID playerUUID, int vaultId);

	/** @return creation timestamp in millis, or null when absent */
	CompletableFuture<Long> getOverflowCreatedAt(UUID playerUUID, int vaultId);

	// ==================== Legacy / shared API ====================

	/**
	 * Deletes pre-multi-vault legacy rows for the player (kept separate from
	 * {@link #deleteVault} so legacy cleanup never removes vault data).
	 */
	CompletableFuture<Void> deleteEnderChest(UUID playerUUID);

	/**
	 * One-time bulk migration of pre-2.2 (2.1.x) legacy rows into vault
	 * storage (vault 1). Runs on the IO executor and is idempotent — players
	 * already migrated (or never on 2.1.x) are skipped. The lazy per-player
	 * migration on vault-1 load remains as a safety net.
	 *
	 * @return the number of migrated players
	 */
	CompletableFuture<Integer> migrateLegacyData();

	// Get player name from UUID (for offline players)
	CompletableFuture<String> getPlayerName(UUID playerUUID);

	// Check if player has any data in the database (any vault or legacy)
	CompletableFuture<Boolean> hasData(UUID playerUUID);

	// Find player UUID by name (for cross online/offline mode compatibility)
	// Returns null if no player with that name is found
	CompletableFuture<UUID> findUUIDByName(String playerName);

	// Statistics methods
	CompletableFuture<StorageStats> getStorageStats();

	CompletableFuture<List<PlayerDataInfo>> getPlayersWithItems();

	/**
	 * Loaded content of one vault.
	 */
	class VaultData {
		public final int size;
		public final ItemStack[] items;

		public VaultData(int size, ItemStack[] items) {
			this.size = size;
			this.items = items;
		}
	}

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
	 * Player data info for validation and migration.
	 * {@code vaultIds} lists every vault with stored data (legacy data counts
	 * as vault 1); itemCount/corrupted aggregate across all vaults.
	 */
	class PlayerDataInfo {
		public final UUID playerUUID;
		public final String playerName;
		public final int chestSize;
		public final int itemCount;
		public final boolean hasOverflow;
		public final boolean isCorrupted;
		public final String errorMessage;
		public final List<Integer> vaultIds;

		public PlayerDataInfo(UUID playerUUID, String playerName, int chestSize, int itemCount,
				boolean hasOverflow, boolean isCorrupted, String errorMessage, List<Integer> vaultIds) {
			this.playerUUID = playerUUID;
			this.playerName = playerName;
			this.chestSize = chestSize;
			this.itemCount = itemCount;
			this.hasOverflow = hasOverflow;
			this.isCorrupted = isCorrupted;
			this.errorMessage = errorMessage;
			this.vaultIds = vaultIds;
		}
	}
}
