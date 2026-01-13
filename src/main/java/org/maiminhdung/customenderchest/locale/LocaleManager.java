package org.maiminhdung.customenderchest.locale;

import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.utils.Text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

public class LocaleManager {

    private final EnderChest plugin;
    private FileConfiguration localeConfig;

    // List of all available language files
    private static final List<String> AVAILABLE_LOCALES = Arrays.asList("en", "vi", "nl", "zhcn");

    public LocaleManager(EnderChest plugin) {
        this.plugin = plugin;
        saveDefaultLanguageFiles();
        loadLocale();
    }

    /**
     * Saves all default language files from JAR to the lang folder if they don't exist.
     */
    private void saveDefaultLanguageFiles() {
        File langDir = new File(plugin.getDataFolder(), "lang");
        if (!langDir.exists() && !langDir.mkdirs()) {
            plugin.getLogger().warning("Could not create lang directory");
            return;
        }

        for (String locale : AVAILABLE_LOCALES) {
            String fileName = "lang_" + locale + ".yml";
            File langFile = new File(langDir, fileName);

            if (!langFile.exists()) {
                try (InputStream in = plugin.getResource("lang/" + fileName)) {
                    if (in != null) {
                        Files.copy(in, langFile.toPath());
                        plugin.getLogger().info("Created default language file: " + fileName);
                    }
                } catch (IOException e) {
                    plugin.getLogger().warning("Could not create language file: " + fileName);
                }
            }
        }
    }

    public void loadLocale() {
        String localeCode = plugin.config().getString("general.locale", "en");
        String fileName = "lang_" + localeCode + ".yml";
        File langFile = new File(plugin.getDataFolder(), "lang/" + fileName);

        if (!langFile.exists()) {
            plugin.getLogger().warning("Language file '" + fileName + "' not found! Attempting to save default from JAR...");
            File parentDir = langFile.getParentFile();
            if (!parentDir.exists() && !parentDir.mkdirs()) {
                plugin.getLogger().warning("Could not create lang directory");
            }

            try (InputStream in = plugin.getResource("lang/" + fileName)) {
                if (in == null) {
                    plugin.getLogger().severe("FATAL: Default language file '" + fileName + "' not found in the JAR! Falling back to English.");
                    // Fallback to English
                    fileName = "lang_en.yml";
                    langFile = new File(plugin.getDataFolder(), "lang/" + fileName);
                    if (!langFile.exists()) {
                        try (InputStream enIn = plugin.getResource("lang/" + fileName)) {
                            if (enIn != null) {
                                Files.copy(enIn, langFile.toPath());
                            }
                        }
                    }
                } else {
                    Files.copy(in, langFile.toPath());
                    plugin.getLogger().info("Successfully created default language file: " + fileName);
                }
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create default language file: " + fileName + " - " + e.getMessage());
            }
        }
        this.localeConfig = YamlConfiguration.loadConfiguration(langFile);
        plugin.getLogger().info("Loaded language file: " + fileName);
    }

    public String getRawString(String key, String defaultValue) {
        return localeConfig.getString(key, defaultValue);
    }

    public Component getComponent(String key, TagResolver... placeholders) {
        String message = localeConfig.getString(key, "<red>Missing key: '" + key + "'</red>");
        return Text.parse(message, placeholders);
    }

    public Component getPrefixedComponent(String key, TagResolver... placeholders) {
        Component prefix = getComponent("prefix");
        return prefix.append(getComponent(key, placeholders));
    }
}