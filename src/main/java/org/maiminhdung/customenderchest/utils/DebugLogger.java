package org.maiminhdung.customenderchest.utils;

import org.maiminhdung.customenderchest.EnderChest;

import java.util.logging.Logger;

public class DebugLogger {

    private final EnderChest plugin;
    private final Logger logger;
    private boolean isDebugMode;

    public DebugLogger(EnderChest plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.isDebugMode = plugin.config().getBoolean("general.debug");
    }

    /**
     * Reload debug status from config file.
     */
    public void reload() {
        this.isDebugMode = plugin.config().getBoolean("general.debug");
    }

    /**
     * Send message when debug is true.
     * @param message message.
     */
    public void log(String message) {
        if (isDebugMode) {
            logger.info("[DEBUG] " + message);
        }
    }
}
