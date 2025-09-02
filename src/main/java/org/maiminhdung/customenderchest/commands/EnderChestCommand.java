package org.maiminhdung.customenderchest.commands;

import org.bukkit.inventory.ItemStack;
import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.Scheduler;
import org.maiminhdung.customenderchest.data.EnderChestManager;
import org.maiminhdung.customenderchest.data.LegacyImporter;
import org.maiminhdung.customenderchest.storage.StorageInterface;
import org.maiminhdung.customenderchest.utils.DataLockManager;
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
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

public final class EnderChestCommand implements CommandExecutor, TabCompleter {

    private final EnderChest plugin;
    private final LegacyImporter legacyImporter;
    private final StorageInterface storage;
    private final EnderChestManager manager;

    public EnderChestCommand(EnderChest plugin) {
        this.plugin = plugin;
        this.legacyImporter = new LegacyImporter(plugin);
        this.storage = plugin.getStorageManager().getStorage();
        this.manager = plugin.getEnderChestManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player p) {
                plugin.getEnderChestManager().openEnderChest(p);
            } else {
                sender.sendMessage(plugin.getLocaleManager().getComponent("messages.players-only"));
            }
            return true;
        }

        String subCommand = args[0].toLowerCase();
        switch (subCommand) {
            case "open":
                handleOpen(sender, args);
                break;
            case "reload":
                handleReload(sender);
                break;
            case "importlegacy":
                handleImport(sender);
                break;
            case "delete":
                handleDelete(sender, args, label);
                break;
            default:
                if (sender instanceof Player p) {
                    plugin.getEnderChestManager().openEnderChest(p);
                } else {
                    sender.sendMessage(plugin.getLocaleManager().getComponent("messages.players-only"));
                }
                break;
        }
        return true;
    }

    private void handleOpen(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(plugin.getLocaleManager().getComponent("messages.players-only"));
            return;
        }
        if (args.length == 1) {
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

        Player targetOnline = Bukkit.getPlayerExact(targetName);
        if (targetOnline != null) {
            // --- For online player ---
            Inventory liveInv = plugin.getEnderChestManager().getLoadedEnderChest(targetOnline.getUniqueId());
            if (liveInv == null) {
                admin.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.player-not-found", Placeholder.unparsed("player", targetName)));
                return;
            }

            Component title = EnderChestUtils.getAdminTitle(targetOnline.getName());
            Inventory adminView = Bukkit.createInventory(admin, liveInv.getSize(), title);
            adminView.setContents(liveInv.getContents());

            plugin.getEnderChestManager().getAdminViewedChests().put(adminView, targetOnline.getUniqueId());
            admin.openInventory(adminView);
            plugin.getSoundHandler().playSound(admin, "open");
            return;
        }

        // For offline player
        Scheduler.supplyAsync(() -> Bukkit.getOfflinePlayer(targetName))
                .thenAccept(target -> {
                    if (!target.hasPlayedBefore()) {
                        admin.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.player-not-found", Placeholder.unparsed("player", targetName)));
                        return;
                    }

                    storage.loadEnderChest(target.getUniqueId()).thenCombine(storage.loadEnderChestSize(target.getUniqueId()), (items, size) -> {
                        if (items == null || size == 0) {
                            admin.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.player-not-found", Placeholder.unparsed("player", targetName)));
                            return null;
                        }

                        Component title = EnderChestUtils.getAdminTitle(target.getName() != null ? target.getName() : targetName);
                        Inventory inv = Bukkit.createInventory(admin, size, title);
                        inv.setContents(items);

                        plugin.getEnderChestManager().getAdminViewedChests().put(inv, target.getUniqueId());
                        Scheduler.runEntityTask(admin, () -> {
                            admin.openInventory(inv);
                            plugin.getSoundHandler().playSound(admin, "open");
                        });
                        return null;
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
        plugin.getDebugLogger().reload();
        sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.reload-success"));
    }

    private void handleImport(CommandSender sender) {
        if (!sender.hasPermission("CustomEnderChest.admin")) {
            sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.no-permission"));
            return;
        }
        legacyImporter.runImport(sender);
    }

    private void handleDelete(CommandSender sender, String[] args, String label) {
        if (!sender.hasPermission("CustomEnderChest.command.delete")) {
            sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.no-permission"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.delete-usage",
                    Placeholder.unparsed("label", label)));
            return;
        }

        String targetName = args[1];
        Scheduler.supplyAsync(() -> Bukkit.getOfflinePlayer(targetName))
                .thenAccept(target -> {
                    if (!target.hasPlayedBefore()) {
                        sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.player-not-found",
                                Placeholder.unparsed("player", targetName)));
                        return;
                    }

                    UUID targetUUID = target.getUniqueId ();
                    String finalName = target.getName() != null ? target.getName() : targetName;
                    DataLockManager dataLockManager = plugin.getDataLockManager();

                    if (dataLockManager.isLocked(targetUUID)) {
                        sender.sendMessage(Component.text("Player data is currently busy. Please try again in a moment."));
                        return;
                    }
                    dataLockManager.lock(targetUUID);


                    int size = 0;
                    if (target.isOnline()) {
                        size = EnderChestUtils.getSize(target.getPlayer());
                    } else {
                        size = storage.loadEnderChestSize(targetUUID).join();
                    }

                    if (size == 0) {
                        sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.delete-success",
                                Placeholder.unparsed("player", finalName)));
                        return;
                    }

                    ItemStack[] emptyItems = new ItemStack[size];

                    manager.saveEnderChest(targetUUID, finalName, size, emptyItems)
                            .thenRun(() -> {
                                if (target.isOnline()) {
                                    Scheduler.runEntityTask(target.getPlayer(), () -> {
                                        manager.reloadCacheFor(target.getPlayer());
                                        target.getPlayer().closeInventory();
                                    });
                                }

                                dataLockManager.unlock(targetUUID);
                                sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.delete-success",
                                        Placeholder.unparsed("player", finalName)));
                            });

                });
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