package org.maiminhdung.customenderchest.utils;

import org.maiminhdung.customenderchest.EnderChest;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

public class SoundHandler {

    private final EnderChest plugin;

    public SoundHandler(EnderChest plugin) {
        this.plugin = plugin;
    }

    public void playSound(Player player, String configPath) {
        if (plugin.config().getBoolean("sounds.disable-all")) return;

        String soundName = plugin.config().getString("sounds." + configPath + ".name");
        if (soundName == null || soundName.isEmpty()) return;

        try {
            Sound sound = Sound.valueOf(soundName.toUpperCase());
            float volume = (float) plugin.config().getDouble("sounds." + configPath + ".volume", 1.0);
            float pitch = (float) plugin.config().getDouble("sounds." + configPath + ".pitch", 1.0);
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid sound name in config: '" + soundName + "' at path 'sounds." + configPath + "'");
        }
    }
}