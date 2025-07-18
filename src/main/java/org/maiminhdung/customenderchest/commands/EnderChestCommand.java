package org.maiminhdung.customenderchest.commands;

import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.Scheduler;
import org.maiminhdung.customenderchest.data.LegacyImporter;
import org.maiminhdung.customenderchest.storage.StorageInterface;
import org.maiminhdung.customenderchest.utils.EnderChestUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class EnderChestCommand implements CommandExecutor, TabCompleter {

    private final EnderChest plugin;
    private final LegacyImporter legacyImporter;
    private final StorageInterface storage;

    public EnderChestCommand(EnderChest plugin) {
        this.plugin = plugin;
        this.legacyImporter = new LegacyImporter(plugin);
        this.storage = plugin.getStorageManager().getStorage();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player p) {
                if (!p.hasPermission("CustomEnderChest.command.open.self")) {
                    p.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.no-permission"));
                    return true;
                }
                plugin.getEnderChestManager().openEnderChest(p);
            } else {
                sender.sendMessage(plugin.getLocaleManager().getComponent("messages.players-only"));
            }
            return true;
        }

        String subCommand = args[0].toLowerCase();
        switch (subCommand) {
            case "open" -> handleOpen(sender, args);
            case "reload" -> handleReload(sender);
            case "importlegacy" -> handleImport(sender);
            case "delete" -> handleDelete(sender, args);
            default -> sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.no-permission")); // Hoặc tin nhắn help
        }
        return true;
    }

    private void handleOpen(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(plugin.getLocaleManager().getComponent("messages.players-only"));
            return;
        }
        if (args.length == 1) {
            if (!p.hasPermission("CustomEnderChest.command.open.self")) {
                p.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.no-permission"));
                return;
            }
            plugin.getEnderChestManager().openEnderChest(p);
        } else {
            if (!p.hasPermission("CustomEnderChest.command.open.other")) {
                p.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.no-permission"));
                return;
            }
            openOtherPlayerChest(p, args[1]);
        }
    }

    private void openOtherPlayerChest(Player admin, String targetName) {
        admin.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.loading-chest", Placeholder.unparsed("player", targetName)));

        // Use UUID from offline player lookup
        Scheduler.supplyAsync(() -> Bukkit.getOfflinePlayer(targetName))
                .thenAccept(target -> {
                    if (target == null || !target.hasPlayedBefore()) {
                        admin.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.player-not-found", Placeholder.unparsed("player", targetName)));
                        return;
                    }

                    Scheduler.supplyAsync(() -> {
                        ItemStack[] items = storage.loadEnderChest(target.getUniqueId()).join();
                        int size = storage.loadEnderChestSize(target.getUniqueId()).join();
                        if (items == null || size == 0) return null;

                        // Tạo inventory trong thread async
                        Component title = EnderChestUtils.getAdminTitle(target.getName() != null ? target.getName() : targetName);
                        Inventory inv = Bukkit.createInventory(admin, size, title);
                        inv.setContents(items);
                        return inv;
                    }).thenAccept(inv -> {
                        if (inv != null) {
                            Scheduler.runEntityTask(admin, () -> {
                                admin.openInventory(inv);
                                plugin.getSoundHandler().playSound(admin, "open");
                            });
                        } else {
                            admin.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.player-not-found", Placeholder.unparsed("player", targetName)));
                        }
                    });
                });
    }
    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("CustomEnderChest.admin")) {
            sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.no-permission"));
            return;
        }
        plugin.config().reload();
        plugin.getLocaleManager().loadLocale();
        sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.reload-success"));
    }

    private void handleImport(CommandSender sender) {
        if (!sender.hasPermission("CustomEnderChest.admin")) {
            sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.no-permission"));
            return;
        }
        legacyImporter.runImport(sender);
    }

    private void handleDelete(CommandSender sender, String[] args) {
        // Next update: Implement delete functionality
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            if (sender.hasPermission("CustomEnderChest.command.open.self")) completions.add("open");
            if (sender.hasPermission("CustomEnderChest.admin")) {
                completions.add("reload");
                completions.add("importlegacy");
                completions.add("delete");
            }
            return completions.stream()
                    .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("open") || args[0].equalsIgnoreCase("delete"))) {
            if (sender.hasPermission("CustomEnderChest.command.open.other")) {
                return null;
            }
        }
        return List.of();
    }
}