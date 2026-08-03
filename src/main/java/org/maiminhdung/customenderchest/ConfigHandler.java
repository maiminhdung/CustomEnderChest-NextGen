package org.maiminhdung.customenderchest;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.Locale;

public class ConfigHandler {

    private final EnderChest plugin;
    private FileConfiguration config;
    private String blockInteractionMode;

    public ConfigHandler(EnderChest plugin) {
        this.plugin = plugin;
        plugin.saveDefaultConfig();
        this.reload();
    }

    public void reload() {
        plugin.reloadConfig();
        this.config = plugin.getConfig();
        this.blockInteractionMode = resolveBlockInteractionMode();
    }

    public String getBlockInteractionMode() {
        return blockInteractionMode;
    }

    private String resolveBlockInteractionMode() {
        String configuredMode = config.getString("enderchest-options.block-interaction-mode", "")
                .trim()
                .toLowerCase(Locale.ROOT);
        if (!configuredMode.isEmpty()) {
            if (configuredMode.equals("custom")
                    || configuredMode.equals("permission")
                    || configuredMode.equals("vanilla")) {
                return configuredMode;
            }

            plugin.getLogger().warning("Invalid enderchest-options.block-interaction-mode '"
                    + configuredMode + "'. Using 'permission'. Valid values: custom, permission, vanilla.");
            return "permission";
        }

        // Compatibility with config.yml files from versions before block-interaction-mode.
        if (config.getBoolean("enderchest-options.vanilla-enderchest-block", false)) {
            return "vanilla";
        }
        return config.getBoolean("enderchest-options.disable-enderchest-click", false)
                ? "custom"
                : "permission";
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

    public boolean getBoolean(String path, boolean def) {
        return config.getBoolean(path, def);
    }

}
