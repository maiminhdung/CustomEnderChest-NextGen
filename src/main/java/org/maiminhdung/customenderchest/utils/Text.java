package org.maiminhdung.customenderchest.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class Text {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    /**
     * Parses a string into a Component, supporting both MiniMessage and legacy (&) color codes.
     * @param text The string to parse.
     * @param placeholders Dynamic placeholders for MiniMessage.
     * @return The parsed Component.
     */
    public static Component parse(String text, TagResolver... placeholders) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        // First, parse legacy codes, then parse the result as MiniMessage.
        // This allows mixing, e.g., "&c<rainbow>Hello</rainbow>"
        Component legacyParsed = LEGACY_SERIALIZER.deserialize(text);

        // We re-serialize to a string that MiniMessage can understand and then parse it.
        // This is a robust way to combine both syntaxes.
        String intermediate = MINI_MESSAGE.serialize(legacyParsed);

        return MINI_MESSAGE.deserialize(intermediate, placeholders);
    }
}