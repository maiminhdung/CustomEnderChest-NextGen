package org.maiminhdung.customenderchest.locale;

import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.utils.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class LocaleManager {

    private final EnderChest plugin;
    private FileConfiguration localeConfig;

    public LocaleManager(EnderChest plugin) {
        this.plugin = plugin;
        loadLocale();
    }

    public void loadLocale() {
        String localeCode = plugin.config().getString("general.locale", "en");
        String fileName = "lang_" + localeCode + ".yml";
        File langFile = new File(plugin.getDataFolder(), "lang/" + fileName);

        if (!langFile.exists()) {
            plugin.getLogger().warning("Language file '" + fileName + "' not found! Attempting to save default from JAR...");
            File parentDir = langFile.getParentFile();
            if (!parentDir.exists()) {
                parentDir.mkdirs();
            }

            try (InputStream in = plugin.getResource("lang/" + fileName)) {
                if (in == null) {
                    plugin.getLogger().severe("FATAL: Default language file '" + fileName + "' not found in the JAR! Please ensure it exists at 'src/main/resources/lang/" + fileName + "' in your project.");
                    langFile.createNewFile(); // Tạo một file trống để tránh lỗi khi load
                } else {
                    Files.copy(in, langFile.toPath());
                    plugin.getLogger().info("Successfully created default language file: " + fileName);
                }
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create default language file: " + fileName);
                e.printStackTrace();
            }
        }
        this.localeConfig = YamlConfiguration.loadConfiguration(langFile);
    }

    public String getRawString(String key, String defaultValue) {
        return localeConfig.getString(key, defaultValue);
    }

    /**
     * Gets a parsed Component from the locale file.
     * @param key The key of the message.
     * @param placeholders Placeholders to apply to the message.
     * @return A parsed Component.
     */
    public Component getComponent(String key, TagResolver... placeholders) {
        String message = localeConfig.getString(key, "<red>Missing key: '" + key + "'</red>");
        return MiniMessage.miniMessage().deserialize(message, placeholders);
    }

    /**
     * Gets a list of parsed Components from the locale file.
     * @param key The key of the message list.
     * @param placeholders Placeholders to apply to each line.
     * @return A list of parsed Components.
     */
    public List<Component> getComponentList(String key, TagResolver... placeholders) {
        List<String> messages = localeConfig.getStringList(key);
        if (messages.isEmpty()) {
            return Collections.singletonList(getComponent(key, placeholders));
        }
        return messages.stream()
                .map(line -> Text.parse(line, placeholders))
                .collect(Collectors.toList());
    }

    /**
     * Gets a component with the default plugin prefix.
     * @param key The key of the message.
     * @param placeholders Placeholders to apply.
     * @return The prefixed Component.
     */
    public Component getPrefixedComponent(String key, TagResolver... placeholders) {
        Component prefix = getComponent("messages.prefix");
        return prefix.append(getComponent(key, placeholders));
    }
}