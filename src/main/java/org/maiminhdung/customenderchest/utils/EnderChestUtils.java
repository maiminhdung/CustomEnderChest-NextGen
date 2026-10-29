package org.maiminhdung.customenderchest.utils;

import org.maiminhdung.customenderchest.EnderChest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

public class EnderChestUtils {

    private static EnderChest plugin() {
        return EnderChest.getInstance();
    }

    /**
     * Whether the multi-vault system is enabled (multi-vault.enabled). When
     * disabled the plugin behaves like the pre-2.2 single-chest versions:
     * only Vault 1 exists for every player.
     */
    public static boolean isVaultsEnabled() {
        return plugin().config().getBoolean("multi-vault.enabled", true);
    }

    /**
     * Get the vault count a player may access (always at least 1).
     * Controlled by CustomEnderChest.vaults.<number> / vaults.* and capped by
     * the multi-vault.max-vaults config. Always 1 while the multi-vault
     * system is disabled. Operators use the command permissions but resolve
     * chest sizes and vault counts like any other player.
     */
    public static int getVaultCount(Player player) {
        if (player == null) {
            return 1;
        }
        if (!isVaultsEnabled()) {
            return 1;
        }
        return VaultPermissions.vaultCount(VaultPermissions.probe(player), getMaxVaults());
    }

    /**
     * Get the ender chest size of vault 1 for an OfflinePlayer.
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
        // Return the default size from config, or vanilla size (27) as fallback
        if (plugin().config().getBoolean("default-player.enabled")) {
            int defaultSize = plugin().config().getInt("default-player.size", 27);
            if (defaultSize > 0 && defaultSize % 9 == 0 && defaultSize <= 54) {
                return defaultSize;
            }
        }

        // Return vanilla ender chest size (27 slots = 3 rows) as fallback for offline
        // players
        return 27;
    }

    /** Vault 1 size of an online player (legacy level permissions apply). */
    public static int getSize(Player player) {
        return getSize(player, 1);
    }

    /**
     * Vault size of an online player. Vault 1 uses the legacy level
     * permissions; other vaults use CustomEnderChest.vault.<n>.level.<0-5> and
     * otherwise inherit the Vault 1 size. Operators resolve sizes like any
     * other player (their command permissions are unaffected).
     */
    public static int getSize(Player player, int vaultId) {
        if (player == null) {
            return 0;
        }
        return VaultPermissions.vaultSize(VaultPermissions.probe(player), vaultId,
                plugin().config().getBoolean("default-player.enabled"),
                plugin().config().getInt("default-player.size", 27));
    }

    /** Global cap from config (1..54, default 9). */
    public static int getMaxVaults() {
        int max = plugin().config().getInt("multi-vault.max-vaults", 9);
        return Math.max(1, Math.min(54, max));
    }

    /** Whether the selector GUI is enabled in the config. */
    public static boolean isSelectorGuiEnabled() {
        return plugin().config().getBoolean("multi-vault.selector-gui.enabled", true);
    }

    /**
     * Whether right-clicking an Ender Chest block should open the vault
     * selector GUI instead of vault 1 (only applies while multi-vault is on).
     */
    public static boolean shouldOpenSelectorOnBlockClick() {
        return isVaultsEnabled() && isSelectorGuiEnabled()
                && plugin().config().getBoolean("multi-vault.selector-gui.open-on-block-click", true);
    }

    /**
     * Whether the bare main command (/{main}) should open the vault selector
     * GUI instead of vault 1 (only applies while multi-vault is on;
     * /{main} open and /{main} <number> always open vaults directly).
     */
    public static boolean shouldUseSelectorAsMainCommand() {
        return isVaultsEnabled() && isSelectorGuiEnabled()
                && plugin().config().getBoolean("multi-vault.selector-gui.use-as-main-command", true);
    }

    /** Vault 1 title (backward compatible key). */
    public static Component getTitle(Player player) {
        return getTitle(player, 1);
    }

    public static Component getTitle(Player player, int vaultId) {
        if (player == null) {
            return Component.text("Invalid Player");
        }

        int size = getSize(player, vaultId);

        // Size 9 = 1 row, Size 18 = 2 rows -> index 1
        int levelIndex = (size > 0) ? (size / 9) - 1 : 0;

        String levelNameRaw = plugin().getLocaleManager().getRawString("levels." + levelIndex,
                "Level " + (levelIndex + 1));
        Component levelComponent = Text.parse(levelNameRaw);

        if (vaultId <= 1) {
            String titleFormat = plugin().getLocaleManager().getRawString("titles.enderchest",
                    "<level> - <light_purple><player>'s Chest");
            return Text.parse(titleFormat,
                    Placeholder.component("level", levelComponent),
                    Placeholder.unparsed("player", player.getName()));
        }

        String titleFormat = plugin().getLocaleManager().getRawString("titles.enderchest-vault",
                "<level> - <light_purple><player>'s Vault <vault>");
        return Text.parse(titleFormat,
                Placeholder.component("level", levelComponent),
                Placeholder.unparsed("player", player.getName()),
                Placeholder.unparsed("vault", String.valueOf(vaultId)));
    }

    /** Vault 1 admin title (backward compatible key). */
    public static Component getAdminTitle(String targetName) {
        return getAdminTitle(targetName, 1);
    }

    public static Component getAdminTitle(String targetName, int vaultId) {
        if (targetName == null || targetName.trim().isEmpty()) {
            targetName = "Unknown Player";
        }

        String key = vaultId <= 1 ? "titles.admin_view" : "titles.admin_view_vault";
        String fallback = vaultId <= 1
                ? "<dark_red>Admin View: <player>"
                : "<dark_red>Admin View: <player> [Vault <vault>]";
        String titleFormat = plugin().getLocaleManager().getRawString(key, fallback);
        return Text.parse(titleFormat,
                Placeholder.unparsed("player", targetName),
                Placeholder.unparsed("vault", String.valueOf(vaultId)));
    }
}
