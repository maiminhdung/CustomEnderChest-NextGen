package org.maiminhdung.customenderchest.utils;

import org.maiminhdung.customenderchest.EnderChest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

public class EnderChestUtils {

    private static final EnderChest plugin = EnderChest.getInstance();

    /**
     * Get the ender chest size for an OfflinePlayer.
     * If the player is online, permission checks are performed.
     * If offline, returns the default size from config.
     *
     * @param offlinePlayer The offline player
     * @return The permitted ender chest size
     */
    public static int getSize(OfflinePlayer offlinePlayer) {
        if (offlinePlayer == null) {
            return 0;
        }

        // If the player is online, use the online player method with permission checks
        if (offlinePlayer.isOnline() && offlinePlayer.getPlayer() != null) {
            return getSize(offlinePlayer.getPlayer());
        }

        // For offline players, we cannot check permissions
        // Return the default size from config, or max size (54) as fallback
        if (plugin.config().getBoolean("default-player.enabled")) {
            int defaultSize = plugin.config().getInt("default-player.size", 27);
            if (defaultSize > 0 && defaultSize % 9 == 0 && defaultSize <= 54) {
                return defaultSize;
            }
        }

        // Return vanilla ender chest size (27 slots = 3 rows) as fallback for offline players
        return 27;
    }

    public static int getSize(Player player) {
        if (player == null) {
            return 0;
        }

        if (player.hasPermission("CustomEnderChest.level.*")) {
            return 54;
        }

        // Check specific level permissions in descending order (only valid levels)
        int[] validLevels = {54, 45, 36, 27, 18, 9}; // Common enderchest sizes (6, 5, 4, 3, 2, 1 rows)
        for (int level : validLevels) {
            if (player.hasPermission("CustomEnderChest.level." + (level / 9 - 1))) {
                return level;
            }
        }

        // Check for numbered permissions (0-5)
        for (int i = 5; i >= 0; i--) {
            if (player.hasPermission("CustomEnderChest.level." + i)) {
                return (i + 1) * 9; // Convert level index to slot count
            }
        }

        // Check for default player size from config
        if (plugin.config().getBoolean("default-player.enabled")) {
            int defaultSize = plugin.config().getInt("default-player.size", 0);
            if (defaultSize > 0 && defaultSize % 9 == 0 && defaultSize <= 54) {
                return defaultSize;
            }
        }

        return 0; // Return 0 if no permissions are found
    }

    public static Component getTitle(Player player) {
        if (player == null) {
            return Component.text("Invalid Player");
        }

        int size = getSize(player);
        // Size 9 = 1 row, Size 18 = 2 rows -> index 1
        int levelIndex = (size > 0) ? (size / 9) - 1 : 0;

        String levelNameRaw = plugin.getLocaleManager().getRawString("levels." + levelIndex, "Level " + (levelIndex + 1));
        Component levelComponent = Text.parse(levelNameRaw);

        String titleFormat = plugin.getLocaleManager().getRawString("titles.enderchest", "<level> - <light_purple><player>'s Chest");

        return Text.parse(titleFormat,
                Placeholder.component("level", levelComponent),
                Placeholder.unparsed("player", player.getName())
        );
    }

    public static Component getAdminTitle(String targetName) {
        if (targetName == null || targetName.trim().isEmpty()) {
            targetName = "Unknown Player";
        }

        String titleFormat = plugin.getLocaleManager().getRawString("titles.admin_view", "<dark_red>Admin View: <player>");
        return Text.parse(titleFormat,
                Placeholder.unparsed("player", targetName));
    }
}