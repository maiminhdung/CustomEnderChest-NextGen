package org.maiminhdung.customenderchest.utils;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultKeyTest {

	@Test
	void equalsAndHashCodeWorkAsMapKey() {
		UUID uuid = UUID.randomUUID();
		VaultKey a = new VaultKey(uuid, 1);
		VaultKey b = new VaultKey(uuid, 1);
		VaultKey otherVault = new VaultKey(uuid, 2);
		VaultKey otherPlayer = new VaultKey(UUID.randomUUID(), 1);

		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
		assertNotEquals(a, otherVault);
		assertNotEquals(a, otherPlayer);

		Map<VaultKey, String> map = new HashMap<>();
		map.put(a, "chest");
		assertEquals("chest", map.get(b));

		Set<VaultKey> set = new HashSet<>();
		set.add(a);
		set.add(b);
		set.add(otherVault);
		assertEquals(2, set.size());
	}

	@Test
	void rejectsInvalidInput() {
		assertThrows(IllegalArgumentException.class, () -> new VaultKey(null, 1));
		assertThrows(IllegalArgumentException.class, () -> new VaultKey(UUID.randomUUID(), 0));
		assertThrows(IllegalArgumentException.class, () -> new VaultKey(UUID.randomUUID(), -1));
	}

	@Test
	void toStringIsReadable() {
		UUID uuid = UUID.randomUUID();
		String rendered = new VaultKey(uuid, 3).toString();
		assertTrue(rendered.contains(uuid.toString()));
		assertTrue(rendered.contains("v3"));
	}
}
