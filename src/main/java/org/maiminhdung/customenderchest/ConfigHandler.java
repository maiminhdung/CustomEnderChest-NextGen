package org.maiminhdung.customenderchest;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

public class ConfigHandler {

    private final EnderChest plugin;
    private FileConfiguration config;

    public ConfigHandler(EnderChest plugin) {
        this.plugin = plugin;
        plugin.saveDefaultConfig();
        this.reload();
    }

    public void reload() {
        plugin.reloadConfig();
        this.config = plugin.getConfig();
    }

    public String getString(String path) {
        return config.getString(path, "");
    }

    public String getString(String path, String def) {
        return config.getString(path, def);
    }

    public int getInt(String path, int def) {
        return config.getInt(path, def);
    }

    public double getDouble(String path, double def) {
        return config.getDouble(path, def);
    }

    public boolean getBoolean(String path) {
        return config.getBoolean(path);
    }

    public List<String> getStringList(String path) {
        return config.getStringList(path);
    }

    public Component getMessage(String path) {
        String message = config.getString(path, "<red>Message not found: " + path + "</red>");
        return MiniMessage.miniMessage().deserialize(message);
    }

    public Component getPrefixedMessage(String path) {
        String prefix = getString("messages.prefix", "<dark_purple>[<light_purple>EnderChest<dark_purple>] ");
        String message = config.getString(path, "<red>Message not found: " + path + "</red>");
        return MiniMessage.miniMessage().deserialize(prefix + message);
    }
}