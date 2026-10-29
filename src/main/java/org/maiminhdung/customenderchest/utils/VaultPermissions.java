package org.maiminhdung.customenderchest.utils;

import org.bukkit.entity.Player;

/**
 * Pure (Bukkit-free) resolution logic for the multi-vault permission system.
 * Kept free of server dependencies so it can be unit tested directly.
 *
 * Permission model:
 * - Vault count: {@code CustomEnderChest.vaults.*} unlocks every vault up to
 *   the configured maximum; {@code CustomEnderChest.vaults.<number>} unlocks
 *   vaults 1..number. Without either, players still have Vault 1.
 * - Vault 1 size (backward compatible): {@code CustomEnderChest.level.*} /
 *   {@code CustomEnderChest.level.0..5}, then the {@code default-player.*}
 *   config fallback.
 * - Vault N >= 2 size: {@code CustomEnderChest.vault.<number>.level.*} /
 *   {@code CustomEnderChest.vault.<number>.level.0..5}; otherwise inherits the
 *   Vault 1 size.
 */
public final class VaultPermissions {

	/** Reports whether a permission node is explicitly set AND granted. */
	@FunctionalInterface
	public interface PermissionProbe {
		boolean granted(String node);
	}

	private VaultPermissions() {
	}

	/** Wraps a player into a probe using isPermissionSet + hasPermission. */
	public static PermissionProbe probe(Player player) {
		return node -> player.isPermissionSet(node) && player.hasPermission(node);
	}

	/**
	 * Resolves how many vaults the player may access (always at least 1).
	 */
	public static int vaultCount(PermissionProbe probe, int maxVaults) {
		int cappedMax = Math.max(1, maxVaults);
		if (probe.granted("CustomEnderChest.vaults.*")) {
			return cappedMax;
		}
		for (int n = cappedMax; n >= 2; n--) {
			if (probe.granted("CustomEnderChest.vaults." + n)) {
				return n;
			}
		}
		return 1;
	}

	/**
	 * Resolves the Vault 1 size using the legacy level permissions, falling
	 * back to the configured default. Returns 0 when the player has no chest.
	 */
	public static int primaryVaultSize(PermissionProbe probe, boolean defaultEnabled, int defaultSize) {
		// Wildcard only if explicitly set so OPs do not implicitly get 54 slots
		if (probe.granted("CustomEnderChest.level.*")) {
			return 54;
		}
		for (int i = 5; i >= 0; i--) {
			if (probe.granted("CustomEnderChest.level." + i)) {
				return (i + 1) * 9;
			}
		}
		if (defaultEnabled && defaultSize > 0 && defaultSize % 9 == 0 && defaultSize <= 54) {
			return defaultSize;
		}
		return 0;
	}

	/**
	 * Resolves the size of a specific vault. Vault 1 uses the legacy rules;
	 * other vaults use ONLY their per-vault overrides
	 * ({@code CustomEnderChest.vault.<n>.level.*}) and otherwise fall back to
	 * the configured default size — the legacy level permissions never leak
	 * into vaults 2+.
	 */
	public static int vaultSize(PermissionProbe probe, int vaultId, boolean defaultEnabled, int defaultSize) {
		if (vaultId <= 1) {
			return primaryVaultSize(probe, defaultEnabled, defaultSize);
		}
		if (probe.granted("CustomEnderChest.vault." + vaultId + ".level.*")) {
			return 54;
		}
		for (int i = 5; i >= 0; i--) {
			if (probe.granted("CustomEnderChest.vault." + vaultId + ".level." + i)) {
				return (i + 1) * 9;
			}
		}
		if (defaultEnabled && defaultSize > 0 && defaultSize % 9 == 0 && defaultSize <= 54) {
			return defaultSize;
		}
		return 0;
	}
}
