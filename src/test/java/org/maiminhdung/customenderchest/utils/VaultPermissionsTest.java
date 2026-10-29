package org.maiminhdung.customenderchest.utils;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VaultPermissionsTest {

	private static final VaultPermissions.PermissionProbe NONE = node -> false;

	private static VaultPermissions.PermissionProbe with(String... grantedNodes) {
		Set<String> granted = new HashSet<>();
		for (String node : grantedNodes) {
			granted.add(node);
		}
		return granted::contains;
	}

	// ==================== vaultCount ====================

	@Test
	void vaultCountDefaultsToOneVault() {
		assertEquals(1, VaultPermissions.vaultCount(NONE, 9));
	}

	@Test
	void vaultCountWildcardReachesConfiguredMax() {
		assertEquals(9, VaultPermissions.vaultCount(with("CustomEnderChest.vaults.*"), 9));
	}

	@Test
	void vaultCountWildcardIsCappedByMax() {
		assertEquals(3, VaultPermissions.vaultCount(with("CustomEnderChest.vaults.*"), 3));
	}

	@Test
	void vaultCountPicksHighestGrantedNumber() {
		assertEquals(5, VaultPermissions.vaultCount(with(
				"CustomEnderChest.vaults.2", "CustomEnderChest.vaults.5"), 9));
	}

	@Test
	void vaultCountIgnoresNumbersAboveMax() {
		assertEquals(1, VaultPermissions.vaultCount(with("CustomEnderChest.vaults.7"), 5));
	}

	@Test
	void vaultCountMinimumMaxIsOne() {
		assertEquals(1, VaultPermissions.vaultCount(NONE, 0));
		assertEquals(1, VaultPermissions.vaultCount(NONE, -3));
	}

	// ==================== primaryVaultSize (vault 1, legacy permissions) ====================

	@Test
	void primaryVaultSizeUsesLegacyWildcard() {
		assertEquals(54, VaultPermissions.primaryVaultSize(with("CustomEnderChest.level.*"), false, 27));
	}

	@Test
	void primaryVaultSizeUsesHighestLevel() {
		assertEquals(36, VaultPermissions.primaryVaultSize(with(
				"CustomEnderChest.level.1", "CustomEnderChest.level.3"), false, 27));
	}

	@Test
	void primaryVaultSizeFallsBackToConfiguredDefault() {
		assertEquals(27, VaultPermissions.primaryVaultSize(NONE, true, 27));
	}

	@Test
	void primaryVaultSizeReturnsZeroWithoutPermissionsOrDefault() {
		assertEquals(0, VaultPermissions.primaryVaultSize(NONE, false, 27));
	}

	@Test
	void primaryVaultSizeRejectsInvalidDefaultSizes() {
		// Non-multiple-of-9 defaults are ignored
		assertEquals(0, VaultPermissions.primaryVaultSize(NONE, true, 30));
		assertEquals(0, VaultPermissions.primaryVaultSize(NONE, true, 63));
	}

	// ==================== vaultSize (vault >= 2) ====================

	@Test
	void vaultOneUsesLegacyPermissions() {
		assertEquals(36, VaultPermissions.vaultSize(with("CustomEnderChest.level.3"), 1, false, 27));
	}

	@Test
	void vaultTwoIgnoresLegacyLevelPermissions() {
		// level.3 only ever applies to Vault 1
		assertEquals(27, VaultPermissions.vaultSize(with("CustomEnderChest.level.3"), 2, true, 27));
		assertEquals(0, VaultPermissions.vaultSize(with("CustomEnderChest.level.3"), 2, false, 27));
	}

	@Test
	void vaultWithoutOverrideUsesConfiguredDefault() {
		assertEquals(27, VaultPermissions.vaultSize(NONE, 2, true, 27));
	}

	@Test
	void vaultSpecificOverrideWinsOverDefault() {
		assertEquals(18, VaultPermissions.vaultSize(with(
				"CustomEnderChest.level.3", "CustomEnderChest.vault.2.level.1"), 2, true, 27));
	}

	@Test
	void vaultSpecificWildcardGivesFullSize() {
		assertEquals(54, VaultPermissions.vaultSize(with("CustomEnderChest.vault.3.level.*"), 3, false, 27));
	}

	@Test
	void vaultOverrideOnlyAppliesToItsOwnVault() {
		VaultPermissions.PermissionProbe probe = with("CustomEnderChest.level.2", "CustomEnderChest.vault.4.level.5");
		assertEquals(27, VaultPermissions.vaultSize(probe, 2, true, 27)); // default size
		assertEquals(27, VaultPermissions.vaultSize(probe, 3, true, 27)); // default size
		assertEquals(54, VaultPermissions.vaultSize(probe, 4, true, 27)); // own override
	}

	@Test
	void vaultWithoutAnyPermissionReturnsZeroWhenNoDefault() {
		assertEquals(0, VaultPermissions.vaultSize(NONE, 2, false, 27));
	}
}
