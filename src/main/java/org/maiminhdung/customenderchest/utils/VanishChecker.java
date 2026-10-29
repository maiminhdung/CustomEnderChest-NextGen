package org.maiminhdung.customenderchest.utils;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * Detects whether a player is currently vanished.
 *
 * Supported sources:
 * - the "vanished"/"isVanished" metadata keys (set by EssentialsX, SuperVanish,
 *   PremiumVanish, VanishNoPacket and most modern vanish plugins)
 * - direct EssentialsX API check via reflection when the metadata is absent
 */
public final class VanishChecker {

	private static final String[] METADATA_KEYS = { "vanished", "isVanished" };

	private VanishChecker() {
	}

	public static boolean isVanished(Player player) {
		if (player == null) {
			return false;
		}

		for (String key : METADATA_KEYS) {
			for (MetadataValue value : player.getMetadata(key)) {
				try {
					if (value.asBoolean()) {
						return true;
					}
				} catch (Throwable ignored) {
					// Non-boolean metadata value — ignore.
				}
			}
		}

		return isEssentialsVanished(player);
	}

	private static boolean isEssentialsVanished(Player player) {
		Plugin essentials = Bukkit.getPluginManager().getPlugin("Essentials");
		if (essentials == null || !essentials.isEnabled()) {
			return false;
		}
		try {
			Method getUser = essentials.getClass().getMethod("getUser", Player.class);
			Object user = getUser.invoke(essentials, player);
			if (user == null) {
				return false;
			}
			Method isVanished = user.getClass().getMethod("isVanished");
			Object result = isVanished.invoke(user);
			return Boolean.TRUE.equals(result);
		} catch (Throwable ignored) {
			// Swallow entirely — the vanish check must never break opening.
			return false;
		}
	}
}
