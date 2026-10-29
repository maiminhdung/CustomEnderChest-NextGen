package org.maiminhdung.customenderchest.api;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Public API for other plugins to read and manage CustomEnderChest vaults.
 * Obtain it with {@link CustomEnderChestAPI#get()}.
 *
 * <p>Storage-touching methods return {@link CompletableFuture}s that complete
 * on asynchronous threads — never block the main/server thread waiting for
 * them. For online players with a loaded vault, content reads return a
 * snapshot of the live cache; writes update the cache first and then persist
 * asynchronously through the plugin's save chain.</p>
 *
 * <p>Vault 1 is the default/legacy Ender Chest. Valid vault ids are
 * {@code 1..getMaxVaults()}.</p>
 */
public interface CustomEnderChest {

	// ==================== Vault contents ====================

	/**
	 * Reads the contents of one vault. For online players with a loaded vault
	 * this snapshots the live cache; otherwise the stored data is loaded
	 * (loading vault 1 also runs the automatic 2.1.x data migration).
	 *
	 * @param playerId the player UUID
	 * @param vaultId  the vault number (1 = default Ender Chest)
	 * @return future with a copy of the items (empty array = empty vault),
	 *         or null when the player has no data for this vault
	 */
	CompletableFuture<ItemStack[]> getVaultContents(UUID playerId, int vaultId);

	/**
	 * Replaces the contents of one vault. If the vault is currently loaded
	 * for an online player, the live inventory is updated and saved through
	 * the normal save chain; otherwise the storage record is written
	 * directly. The player's permissions are NOT re-checked — the caller is
	 * responsible for authorization.
	 *
	 * @param playerId the player UUID
	 * @param vaultId  the vault number
	 * @param items    the new contents (null entries = empty slots); null
	 *                 clears the vault
	 * @return future completing when the write has been queued
	 */
	CompletableFuture<Void> setVaultContents(UUID playerId, int vaultId, ItemStack[] items);

	/**
	 * @param playerId the player UUID
	 * @param vaultId  the vault number
	 * @return future with the stored size in slots (0 = no data), preferring
	 *         the live cache for online players
	 */
	CompletableFuture<Integer> getVaultSize(UUID playerId, int vaultId);

	/**
	 * Deletes one vault (chest + overflow of that vault).
	 *
	 * @param playerId the player UUID
	 * @param vaultId  the vault number
	 * @return future completing exceptionally with {@link IllegalStateException}
	 *         while the player's data is busy (join/save in progress)
	 */
	CompletableFuture<Void> deleteVault(UUID playerId, int vaultId);

	/**
	 * @param playerId the player UUID
	 * @return future with every vault id that has stored data (chest or
	 *         overflow), sorted ascending; legacy 2.1.x data counts as vault 1
	 */
	CompletableFuture<List<Integer>> getVaultIds(UUID playerId);

	// ==================== Overflow ====================

	/**
	 * Reads the overflow items stored for one vault.
	 *
	 * @param playerId the player UUID
	 * @param vaultId  the vault number
	 * @return future with the items, or null when the vault has no overflow
	 */
	CompletableFuture<ItemStack[]> getOverflowItems(UUID playerId, int vaultId);

	/**
	 * Replaces the overflow items of one vault. Setting an empty/null array
	 * clears the overflow record.
	 *
	 * @param playerId the player UUID
	 * @param vaultId  the vault number
	 * @param items    the new overflow contents, or null/empty to clear
	 * @return future completing when the write has been queued
	 */
	CompletableFuture<Void> setOverflowItems(UUID playerId, int vaultId, ItemStack[] items);

	// ==================== Runtime info ====================

	/** @return true while the multi-vault system is enabled in the config */
	boolean isVaultsEnabled();

	/**
	 * @return the configured global cap on vaults
	 *         ({@code vaults.max-vaults}, 1..54)
	 */
	int getMaxVaults();

	/**
	 * @param player an online player
	 * @return how many vaults this player may access (always at least 1)
	 */
	int getUnlockedVaultCount(Player player);

	// ==================== GUI ====================

	/**
	 * Opens one of the player's own vaults, applying the same checks as the
	 * in-game command (entitlement, size permission, data loading state).
	 *
	 * @param player  an online player
	 * @param vaultId the vault number
	 * @return true when the vault was opened or is being opened (e.g. while
	 *         data finishes loading); false when a check denied the open
	 */
	boolean openVault(Player player, int vaultId);

	/**
	 * Opens the vault selector GUI for an online player (no-op while the
	 * selector or the multi-vault system is disabled).
	 *
	 * @param player an online player
	 */
	void openVaultSelector(Player player);
}
