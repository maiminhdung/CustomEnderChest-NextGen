package org.maiminhdung.customenderchest.utils;

import java.util.UUID;

/**
 * Identifies one vault of one player: (player UUID, vault id).
 * Vault 1 is the default/legacy Ender Chest.
 */
public record VaultKey(UUID playerId, int vaultId) {

	public VaultKey {
		if (playerId == null) {
			throw new IllegalArgumentException("playerId must not be null");
		}
		if (vaultId < 1) {
			throw new IllegalArgumentException("vaultId must be >= 1");
		}
	}

	@Override
	public String toString() {
		return playerId + "#v" + vaultId;
	}
}
