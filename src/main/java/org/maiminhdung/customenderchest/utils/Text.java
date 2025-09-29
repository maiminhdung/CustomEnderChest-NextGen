package org.maiminhdung.customenderchest.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public class Text {

    public static Component parse(String text, TagResolver... placeholders) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        // First, translate legacy '&' codes into MiniMessage tags
        String legacyTranslated = LegacyComponentSerializer.legacyAmpersand().serialize(
                LegacyComponentSerializer.legacyAmpersand().deserialize(text)
        );
        // Then, parse the result with MiniMessage
        return MiniMessage.miniMessage().deserialize(legacyTranslated, placeholders);
    }
}