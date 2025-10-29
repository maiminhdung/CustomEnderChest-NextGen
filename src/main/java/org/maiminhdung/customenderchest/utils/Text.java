package org.maiminhdung.customenderchest.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class Text {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacyAmpersand();

    public static Component parse(String text, TagResolver... placeholders) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }

        Component component = MINI_MESSAGE.deserialize(text, placeholders);

        return LEGACY_SERIALIZER.deserialize(LEGACY_SERIALIZER.serialize(component));
    }
}