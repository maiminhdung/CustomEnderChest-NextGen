package org.maiminhdung.customenderchest.locale;

import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.utils.Text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class LocaleManager {

    private final EnderChest plugin;
    private volatile FileConfiguration localeConfig;

    // List of all available language files
    private static final List<String> AVAILABLE_LOCALES = Arrays.asList("en", "vi", "nl", "zhcn");

    public LocaleManager(EnderChest plugin) {
        this.plugin = plugin;
        saveDefaultLanguageFiles();
        if (!loadLocale()) {
            plugin.getLogger().severe("Unable to load the configured language file. Attempting English fallback.");
            if (!loadLocaleFile("en")) {
                throw new IllegalStateException("Unable to load any language file");
            }
        }
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

    /**
     * Reloads the configured language file from disk.
     * The currently active locale remains unchanged if the new file is invalid.
     *
     * @return true when a language file was loaded successfully
     */
    public boolean loadLocale() {
        String localeCode = plugin.config().getString("general.locale", "en").trim().toLowerCase(Locale.ROOT);
        if (!localeCode.matches("[a-z0-9_-]+")) {
            plugin.getLogger().warning("Invalid locale code '" + localeCode + "'.");
            return false;
        }

        return ensureLanguageFile(localeCode) && loadLocaleFile(localeCode);
    }

    private boolean ensureLanguageFile(String localeCode) {
        String fileName = "lang_" + localeCode + ".yml";
        File langFile = new File(plugin.getDataFolder(), "lang/" + fileName);
        if (langFile.exists()) {
            return langFile.isFile();
        }

        plugin.getLogger().warning("Language file '" + fileName + "' not found. Attempting to copy it from the plugin JAR.");
        File parentDir = langFile.getParentFile();
        if (!parentDir.exists() && !parentDir.mkdirs()) {
            plugin.getLogger().warning("Could not create language directory: " + parentDir);
            return false;
        }

        try (InputStream input = plugin.getResource("lang/" + fileName)) {
            if (input == null) {
                plugin.getLogger().warning("No bundled language file exists for locale '" + localeCode + "'.");
                return false;
            }
            Files.copy(input, langFile.toPath());
            plugin.getLogger().info("Created default language file: " + fileName);
            return true;
        } catch (IOException error) {
            plugin.getLogger().severe("Could not create language file '" + fileName + "': " + error.getMessage());
            return false;
        }
    }

    private boolean loadLocaleFile(String localeCode) {
        String fileName = "lang_" + localeCode + ".yml";
        File langFile = new File(plugin.getDataFolder(), "lang/" + fileName);
        YamlConfiguration candidate = new YamlConfiguration();
        candidate.options().parseComments(true);

        try {
            candidate.load(langFile);
            if (!candidate.isString("prefix") || !candidate.isString("messages.reload-success")) {
                throw new InvalidConfigurationException(
                        "Required keys 'prefix' and 'messages.reload-success' must be strings");
            }
        } catch (IOException | InvalidConfigurationException error) {
            plugin.getLogger().severe("Could not load language file '" + fileName + "': " + error.getMessage());
            return false;
        }

        this.localeConfig = candidate;
        plugin.getLogger().info("Loaded language file: " + fileName);
        return true;
    }

    public String getRawString(String key, String defaultValue) {
        return localeConfig.getString(key, defaultValue);
    }

    public Component getComponent(String key, TagResolver... placeholders) {
        return getComponent(key, "<red>Missing key: '" + key + "'</red>", placeholders);
    }

    public Component getComponent(String key, String defaultValue, TagResolver... placeholders) {
        FileConfiguration activeLocale = this.localeConfig;
        String message = activeLocale != null ? activeLocale.getString(key, defaultValue) : defaultValue;
        return Text.parse(message, placeholders);
    }

    public Component getPrefixedComponent(String key, TagResolver... placeholders) {
        return getPrefixedComponent(key, "<red>Missing key: '" + key + "'</red>", placeholders);
    }

    public Component getPrefixedComponent(String key, String defaultValue, TagResolver... placeholders) {
        FileConfiguration activeLocale = this.localeConfig;
        String prefixText = activeLocale != null ? activeLocale.getString("prefix", "") : "";
        String message = activeLocale != null ? activeLocale.getString(key, defaultValue) : defaultValue;
        return Text.parse(prefixText).append(Text.parse(message, placeholders));
    }
}