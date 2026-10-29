package org.maiminhdung.customenderchest.api;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.Scheduler;
import org.maiminhdung.customenderchest.data.EnderChestManager;
import org.maiminhdung.customenderchest.storage.StorageInterface;
import org.maiminhdung.customenderchest.utils.DataLockManager;
import org.maiminhdung.customenderchest.utils.EnderChestUtils;
import org.maiminhdung.customenderchest.utils.VaultKey;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Implementation of the public API. Registered by the plugin on enable —
 * not part of the API surface itself.
 */
public final class CustomEnderChestImpl implements CustomEnderChest {

	private final EnderChest plugin;

	public CustomEnderChestImpl(EnderChest plugin) {
		this.plugin = plugin;
	}

	private EnderChestManager manager() {
		return plugin.getEnderChestManager();
	}

	private StorageInterface storage() {
		return plugin.getStorageManager().getStorage();
	}

	private void validateVaultId(int vaultId) {
		if (vaultId < 1 || vaultId > EnderChestUtils.getMaxVaults()) {
			throw new IllegalArgumentException("vaultId must be between 1 and " + EnderChestUtils.getMaxVaults());
		}
	}

	private String playerName(UUID playerId) {
		OfflinePlayer offline = Bukkit.getOfflinePlayer(playerId);
		return offline.getName() != null ? offline.getName() : playerId.toString();
	}

	@Override
	public CompletableFuture<ItemStack[]> getVaultContents(UUID playerId, int vaultId) {
		Objects.requireNonNull(playerId, "playerId");
		validateVaultId(vaultId);

		Inventory cached = manager().getLoadedVault(playerId, vaultId);
		if (cached != null) {
			// Snapshot on the global/main thread for consistency with the
			// plugin's own inventory access rules.
			return Scheduler.supplySync(() -> cached.getContents().clone());
		}
		return storage().loadVault(playerId, vaultId)
				.thenApply(vaultData -> vaultData != null ? vaultData.items : null);
	}

	@Override
	public CompletableFuture<Void> setVaultContents(UUID playerId, int vaultId, ItemStack[] items) {
		Objects.requireNonNull(playerId, "playerId");
		validateVaultId(vaultId);

		ItemStack[] clean = items == null ? new ItemStack[0] : items.clone();
		Inventory cached = manager().getLoadedVault(playerId, vaultId);
		if (cached != null) {
			// Update the live inventory on the main/global thread, then save
			// through the normal save chain.
			String name = playerName(playerId);
			return Scheduler.supplySync(() -> {
				cached.setContents(clean);
				return (Void) null;
			}).thenCompose(v -> manager().saveVault(playerId, name, vaultId, cached));
		}

		int size = Math.max(9, (int) Math.ceil(clean.length / 9.0) * 9);
		return manager().saveVault(playerId, playerName(playerId), vaultId, size, clean);
	}

	@Override
	public CompletableFuture<Integer> getVaultSize(UUID playerId, int vaultId) {
		Objects.requireNonNull(playerId, "playerId");
		validateVaultId(vaultId);

		Inventory cached = manager().getLoadedVault(playerId, vaultId);
		if (cached != null) {
			return CompletableFuture.completedFuture(cached.getSize());
		}
		return storage().loadVault(playerId, vaultId).thenApply(vaultData -> vaultData != null ? vaultData.size : 0);
	}

	@Override
	public CompletableFuture<Void> deleteVault(UUID playerId, int vaultId) {
		Objects.requireNonNull(playerId, "playerId");
		validateVaultId(vaultId);

		DataLockManager locks = plugin.getDataLockManager();
		if (!locks.tryLock(playerId)) {
			CompletableFuture<Void> failed = new CompletableFuture<>();
			failed.completeExceptionally(new IllegalStateException(
					"Player data for " + playerId + " is busy (join/save in progress)."));
			return failed;
		}

		manager().getLiveData().invalidate(new VaultKey(playerId, vaultId));
		return manager().queueStorageOperation(playerId, () -> storage().deleteVault(playerId, vaultId))
				.whenComplete((v, ex) -> locks.unlock(playerId));
	}

	@Override
	public CompletableFuture<List<Integer>> getVaultIds(UUID playerId) {
		Objects.requireNonNull(playerId, "playerId");
		return storage().getVaultIds(playerId);
	}

	@Override
	public CompletableFuture<ItemStack[]> getOverflowItems(UUID playerId, int vaultId) {
		Objects.requireNonNull(playerId, "playerId");
		validateVaultId(vaultId);
		return storage().loadOverflowItems(playerId, vaultId);
	}

	@Override
	public CompletableFuture<Void> setOverflowItems(UUID playerId, int vaultId, ItemStack[] items) {
		Objects.requireNonNull(playerId, "playerId");
		validateVaultId(vaultId);

		ItemStack[] clean = items == null ? new ItemStack[0] : items.clone();
		if (clean.length == 0) {
			return manager().queueStorageOperation(playerId,
					() -> storage().clearOverflowItems(playerId, vaultId));
		}
		return manager().queueStorageOperation(playerId,
				() -> storage().saveOverflowItems(playerId, vaultId, clean));
	}

	@Override
	public boolean isVaultsEnabled() {
		return EnderChestUtils.isVaultsEnabled();
	}

	@Override
	public int getMaxVaults() {
		return EnderChestUtils.getMaxVaults();
	}

	@Override
	public int getUnlockedVaultCount(Player player) {
		return EnderChestUtils.getVaultCount(player);
	}

	@Override
	public boolean openVault(Player player, int vaultId) {
		Objects.requireNonNull(player, "player");
		validateVaultId(vaultId);
		return manager().openEnderChest(player, vaultId);
	}

	@Override
	public void openVaultSelector(Player player) {
		Objects.requireNonNull(player, "player");
		if (plugin.getVaultSelectorManager() != null) {
			plugin.getVaultSelectorManager().openSelector(player);
		}
	}
}
