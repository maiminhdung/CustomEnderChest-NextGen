package org.maiminhdung.customenderchest.commands;

import org.bukkit.inventory.ItemStack;
import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.Scheduler;
import org.maiminhdung.customenderchest.data.EnderChestManager;
import org.maiminhdung.customenderchest.data.LegacyImporter;
import org.maiminhdung.customenderchest.storage.StorageInterface;
import org.maiminhdung.customenderchest.utils.DataLockManager;
import org.maiminhdung.customenderchest.locale.LocaleManager;
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

/**
 * Main command handler for CustomEnderChest plugin
 * Handles all /cec subcommands including open, reload, delete, convertall, etc.
 */
public final class EnderChestCommand implements CommandExecutor, TabCompleter {

    private final EnderChest plugin;
    private final LegacyImporter legacyImporter;
    private final StorageInterface storage;
    private final EnderChestManager manager;
    private final ConvertAllCommand convertAllCommand;

    public EnderChestCommand(EnderChest plugin) {
        this.plugin = plugin;
        this.legacyImporter = new LegacyImporter(plugin);
        this.storage = plugin.getStorageManager().getStorage();
        this.manager = plugin.getEnderChestManager();
        this.convertAllCommand = new ConvertAllCommand(plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        // Check command permission for players (except for admin commands)
        if (sender instanceof Player p) {
            // Admin commands always require specific permissions (handled in their methods)
            boolean isAdminCommand = args.length > 0 &&
                (args[0].equalsIgnoreCase("reload") ||
                 args[0].equalsIgnoreCase("import") ||
                 args[0].equalsIgnoreCase("delete") ||
                 args[0].equalsIgnoreCase("convertall"));

            if (!isAdminCommand && !hasCommandPermission(p)) {
                p.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.no-permission"));
                return true;
            }
        }

        // Default: open own enderchest
        if (args.length == 0) {
            return handleDefaultCommand(sender);
        }

        // Handle subcommands
        String subCommand = args[0].toLowerCase();
        switch (subCommand) {
            case "open":
                handleOpen(sender, args);
                break;
            case "reload":
                handleReload(sender);
                break;
            case "import":
                handleImport(sender, args);
                break;
            case "delete":
                handleDelete(sender, args, label);
                break;
            case "convertall":
                convertAllCommand.onCommand(sender, command, label, args);
                break;
            default:
                return handleDefaultCommand(sender);
        }
        return true;
    }

    /**
     * Check if player has permission to use commands
     * Returns true if player is OP, has CustomEnderChest.commands permission
     * OR if default-player.allow-command is enabled in config
     */
    private boolean hasCommandPermission(Player player) {
        // OP players always have permission
        if (player.isOp()) {
            return true;
        }

        if (player.hasPermission("CustomEnderChest.commands")) {
            return true;
        }

        return plugin.config().getBoolean("default-player.allow-command");
    }

    /**
     * Handle default command (no args) - opens player's own enderchest
     */
    private boolean handleDefaultCommand(CommandSender sender) {
        if (sender instanceof Player p) {
            plugin.getEnderChestManager().openEnderChest(p);
        } else {
            sender.sendMessage(plugin.getLocaleManager().getComponent("messages.players-only"));
        }
        return true;
    }

    /**
     * Handle /cec open [player] command
     * Opens own enderchest or another player's enderchest (admin)
     */
    private void handleOpen(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(plugin.getLocaleManager().getComponent("messages.players-only"));
            return;
        }

        // Open own enderchest
        if (args.length == 1) {
            plugin.getEnderChestManager().openEnderChest(p);
            return;
        }

        // Open other player's enderchest (admin only)
        if (args.length >= 2) {
            if (!p.hasPermission("CustomEnderChest.command.open.other")) {
                p.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.no-permission"));
                return;
            }
            String targetName = args[1].trim();
            if (targetName.isEmpty()) {
                p.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.invalid-player"));
                return;
            }
            openOtherPlayerChest(p, targetName);
        }
    }

    /**
     * Opens another player's enderchest for admin viewing/editing
     * Handles both online and offline players
     */
    private void openOtherPlayerChest(Player admin, String targetName) {
        if (admin == null || !admin.isOnline()) {
            return;
        }

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

    /**
     * Handle /cec reload command
     * Reloads plugin configuration and locale files
     */
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

    /**
     * Handle /cec import command
     * Imports vanilla ender chest data for all online players (admin only)
     */
    private void handleImport(CommandSender sender, String[] args) {
        if (!sender.hasPermission("CustomEnderChest.admin")) {
            sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.no-permission"));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.import-usage"));
            return;
        }

        String importType = args[1].toLowerCase();
        switch (importType) {
            case "vanilla":
                legacyImporter.runVanillaImportAll(sender);
                break;
            default:
                sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.import-invalid-type"));
                break;
        }
    }

    /**
     * Handle /cec delete <player> command
     * Deletes a player's enderchest data completely
     */
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
                    LocaleManager localeManager = plugin.getLocaleManager();

                    if (dataLockManager.isLocked(targetUUID)) {
                        sender.sendMessage(localeManager.getPrefixedComponent("messages.data-busy"));
                        return;
                    }
                    dataLockManager.lock(targetUUID);


                    int size;
                    if (target.isOnline()) {
                        size = EnderChestUtils.getSize(Objects.requireNonNull(target.getPlayer()));
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
                                        Objects.requireNonNull(target.getPlayer()).closeInventory();
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
                completions.add("import");
                completions.add("delete");
                completions.add("convertall");
                completions.add("open");
            }
            return completions.stream()
                    .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("open") || args[0].equalsIgnoreCase("delete")) {
                if (sender.hasPermission("CustomEnderChest.command.open.other")) {
                    return null;
                }
            }
        }
        return List.of();
    }
}