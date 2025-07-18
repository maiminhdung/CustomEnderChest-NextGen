package org.maiminhdung.customenderchest.data;

import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.locale.LocaleManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Xử lý việc nhập dữ liệu từ định dạng file YML của phiên bản plugin cũ.
 */
public class LegacyImporter {

    private final EnderChest plugin;
    private final LocaleManager locale;

    public LegacyImporter(EnderChest plugin) {
        this.plugin = plugin;
        this.locale = plugin.getLocaleManager();
    }

    public void runImport(CommandSender sender) {
        // Old data from v3 which called "CustomEnderChest/PlayerData"
        File oldDataFolder = new File(plugin.getDataFolder().getParentFile(), "CustomEnderChest/PlayerData");

        if (!oldDataFolder.exists() || !oldDataFolder.isDirectory()) {
            sender.sendMessage(locale.getPrefixedComponent("import.no-folder"));
            return;
        }

        File[] files = oldDataFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".yml"));
        if (files == null || files.length == 0) {
            sender.sendMessage(locale.getPrefixedComponent("import.no-folder"));
            return;
        }

        sender.sendMessage(locale.getPrefixedComponent("import.started"));

        // Run the import asynchronously to avoid blocking the main thread
        CompletableFuture.runAsync(() -> {
            int total = files.length;
            int imported = 0;

            for (File file : files) {
                try {
                    UUID uuid = UUID.fromString(file.getName().replace(".yml", ""));
                    YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);

                    String name = yml.getString("PlayerLastName", "Unknown");
                    int size = yml.getInt("EnderChestSize", 27);

                    List<ItemStack> items = new ArrayList<>();
                    for (int i = 0; i < size; i++) {
                        items.add(yml.getItemStack("EnderChestInventory." + i));
                    }

                    // Save the ender chest data using the new storage system
                    plugin.getStorageManager().getStorage()
                            .saveEnderChest(uuid, name, size, items.toArray(new ItemStack[0]))
                            .join();
                    imported++;

                } catch (Exception e) {
                    plugin.getLogger().severe("Failed to import legacy file: " + file.getName());
                    e.printStackTrace();
                }
            }

            // After all files are processed, send a completion message
            TagResolver finalPlaceholder = Placeholder.component("imported", Component.text(imported));
            sender.sendMessage(locale.getPrefixedComponent("import.complete", finalPlaceholder));
        });
    }
}