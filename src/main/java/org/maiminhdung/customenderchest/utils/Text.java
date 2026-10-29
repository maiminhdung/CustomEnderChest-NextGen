package org.maiminhdung.customenderchest.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

public final class Text {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    /**
     * Parses MiniMessage with full feature support (gradients, hex colors,
     * fonts) — no legacy round-trip anywhere in the plugin.
     */
    public static Component parse(String text, TagResolver... placeholders) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        return MINI_MESSAGE.deserialize(text, placeholders);
    }
}
