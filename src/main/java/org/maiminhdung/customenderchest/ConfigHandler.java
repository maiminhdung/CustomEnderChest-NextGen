package org.maiminhdung.customenderchest;

import org.bukkit.configuration.file.FileConfiguration;

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

}