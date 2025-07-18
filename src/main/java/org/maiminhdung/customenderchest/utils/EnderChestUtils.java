package org.maiminhdung.customenderchest.utils;

import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.locale.LocaleManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;

public final class EnderChestUtils {

    // Private constructor to prevent instantiation
    private EnderChestUtils() {}

    /**
     * Gets the inventory size based on the player's permission.
     * The logic checks from highest to lowest and returns immediately.
     * @param p The player.
     * @return The size of the Ender Chest (9, 18, 27, 36, 45, 54), or 0 if no permission.
     */
    public static int getSize(Player p) {
        if (p.hasPermission("CustomEnderChest.level.5")) return 54;
        if (p.hasPermission("CustomEnderChest.level.4")) return 45;
        if (p.hasPermission("CustomEnderChest.level.3")) return 36;
        if (p.hasPermission("CustomEnderChest.level.2")) return 27;
        if (p.hasPermission("CustomEnderChest.level.1")) return 18;
        if (p.hasPermission("CustomEnderChest.level.0")) return 9;
        return 0; // No permission
    }

    /**
     * Gets the dynamic, translatable title for a player's Ender Chest.
     * This method now uses the LocaleManager for full customization.
     *
     * @param p The player.
     * @return The formatted Component title.
     */
    public static Component getTitle(Player p) {
        LocaleManager locale = EnderChest.getInstance().getLocaleManager();
        int levelIndex = getSize(p) / 9 - 1; // Converts size (9,18..) to level index (0,1..)
        if (levelIndex < 0) levelIndex = 0;

        // Get the raw string for the level name from lang file (e.g., "&aLevel 2" or "<green>Level 2")
        String levelNameString = locale.getRawString("levels." + levelIndex, "Level " + (levelIndex + 1));

        // SỬA LỖI: Parse levelNameString thành một Component riêng biệt TRƯỚC
        Component levelComponent = Text.parse(levelNameString);

        // Tạo placeholders bằng cách sử dụng Placeholder.component() an toàn hơn
        TagResolver placeholders = TagResolver.builder()
                .resolver(Placeholder.component("player", p.displayName()))
                .resolver(Placeholder.component("level", levelComponent)) // <-- THAY ĐỔI Ở ĐÂY
                .build();

        // Lấy mẫu tiêu đề từ file lang và áp dụng các placeholder đã được xử lý
        return locale.getComponent("titles.enderchest", placeholders);
    }

    /**
     * Gets a generic title for an Ender Chest opened by an admin for another player.
     *
     * @param targetName The name of the target player.
     * @return The formatted Component title.
     */
    public static Component getAdminTitle(String targetName) {
        LocaleManager locale = EnderChest.getInstance().getLocaleManager();

        // SỬA LỖI: Sử dụng Placeholder.component() với một Component text đơn giản để tương thích tối đa
        TagResolver placeholder = Placeholder.component("player", Component.text(targetName)); // <-- THAY ĐỔI Ở ĐÂY

        return locale.getComponent("titles.admin_view", placeholder);
    }
}