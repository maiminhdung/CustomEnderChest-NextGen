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
                    langFile.createNewFile();
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

    public Component getComponent(String key, TagResolver... placeholders) {
        String message = localeConfig.getString(key, "<red>Missing key: '" + key + "'</red>");
        return Text.parse(message, placeholders);
    }

    public Component getPrefixedComponent(String key, TagResolver... placeholders) {
        Component prefix = getComponent("messages.prefix");
        return prefix.append(getComponent(key, placeholders));
    }
}