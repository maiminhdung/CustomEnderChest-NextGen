package org.maiminhdung.customenderchest.commands;

import org.bukkit.inventory.ItemStack;
import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.Scheduler;
import org.maiminhdung.customenderchest.data.EnderChestManager;
import org.maiminhdung.customenderchest.storage.StorageInterface;
import org.maiminhdung.customenderchest.utils.DataLockManager;
import org.maiminhdung.customenderchest.locale.LocaleManager;
import org.maiminhdung.customenderchest.storage.migrate.MigrationManager;
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
 * Handles all /cec subcommands including open, reload, delete, convertall, stats, etc.
 */
public final class EnderChestCommand implements CommandExecutor, TabCompleter {

    private final EnderChest plugin;
    private final StorageInterface storage;
    private final EnderChestManager manager;
    private final ConvertAllCommand convertAllCommand;
    private final MigrationManager migrationManager;

    public EnderChestCommand(EnderChest plugin) {
        this.plugin = plugin;
        this.storage = plugin.getStorageManager().getStorage();
        this.manager = plugin.getEnderChestManager();
        this.convertAllCommand = new ConvertAllCommand(plugin);
        this.migrationManager = new MigrationManager(plugin);
    }

    public MigrationManager getMigrationManager() {
        return migrationManager;
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
                 args[0].equalsIgnoreCase("convertall") ||
                 args[0].equalsIgnoreCase("migrate") ||
                 args[0].equalsIgnoreCase("stats"));

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
            case "stats":
                handleStats(sender, args);
                break;
            case "migrate":
                handleMigrate(sender, args);
                break;
            default:
                return handleDefaultCommand(sender);
        }
        return true;
    }

    /**
     * Console/terminal senders are allowed to run admin commands.
     * For players, normal permission checks still apply.
     */
    private boolean hasSenderPermission(CommandSender sender, String permission) {
        return !(sender instanceof Player) || sender.hasPermission(permission);
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
            if (migrationManager.isMigrating()) {
                sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.migrate-in-progress"));
                return true;
            }
            plugin.getEnderChestManager().openEnderChest(p);
        } else {
            sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.players-only"));
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

        if (migrationManager.isMigrating()) {
            p.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.migrate-in-progress"));
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
        if (!hasSenderPermission(sender, "CustomEnderChest.admin")) {
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
        if (!hasSenderPermission(sender, "CustomEnderChest.admin")) {
            sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.no-permission"));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.import-usage"));
            return;
        }

        String importType = args[1].toLowerCase();
        if (importType.equals("vanilla")) {
            plugin.getLegacyImporter().runVanillaImportAll(sender);
        } else {
            sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.import-invalid-type"));
        }
    }

    /**
     * Handle /cec delete <player> command
     * Deletes a player's enderchest data completely
     */
    private void handleDelete(CommandSender sender, String[] args, String label) {
        if (!hasSenderPermission(sender, "CustomEnderChest.command.delete")) {
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

                    // Use atomic tryLock to avoid race condition
                    if (!dataLockManager.tryLock(targetUUID)) {
                        sender.sendMessage(localeManager.getPrefixedComponent("messages.data-busy"));
                        return;
                    }


                    int size;
                    if (target.isOnline()) {
                        size = EnderChestUtils.getSize(Objects.requireNonNull(target.getPlayer()));
                    } else {
                        size = storage.loadEnderChestSize(targetUUID).join();
                    }

                    if (size == 0) {
                        dataLockManager.unlock(targetUUID);
                        sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.delete-success",
                                Placeholder.unparsed("player", finalName)));
                        return;
                    }

                    ItemStack[] emptyItems = new ItemStack[size];

                    manager.saveEnderChest(targetUUID, finalName, size, emptyItems)
                            .whenComplete((result, ex) -> {
                                if (target.isOnline()) {
                                    Scheduler.runEntityTask(target.getPlayer(), () -> {
                                        manager.reloadCacheFor(target.getPlayer());
                                        Objects.requireNonNull(target.getPlayer()).closeInventory();
                                    });
                                }

                                dataLockManager.unlock(targetUUID);

                                if (ex != null) {
                                    plugin.getLogger().warning("Failed to delete enderchest data for " + finalName + ": "
                                            + ex.getMessage());
                                    sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.save-error"));
                                    return;
                                }

                                sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent(
                                        "command.delete-success",
                                        Placeholder.unparsed("player", finalName)));
                            });

                });
    }

    /**
     * Handle /cec stats [validate|help]
     * - /cec stats: show storage summary
     * - /cec stats validate: scan stored player entries and report corrupted records
     */
    private void handleStats(CommandSender sender, String[] args) {
        if (!hasSenderPermission(sender, "CustomEnderChest.admin")) {
            sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.no-permission"));
            return;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("help")) {
            sender.sendMessage("§e[CustomEnderChest] Stats commands:");
            sender.sendMessage("§7/cec stats §f- Show storage statistics summary");
            sender.sendMessage("§7/cec stats validate §f- Validate stored player data");
            return;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("validate")) {
            sender.sendMessage("§e[CustomEnderChest] Validating stored data...");

            storage.getPlayersWithItems()
                    .thenAccept(players -> Scheduler.runTask(() -> {
                        long corruptedCount = players.stream().filter(info -> info.isCorrupted).count();
                        long overflowCount = players.stream().filter(info -> info.hasOverflow).count();
                        long playersWithItems = players.stream().filter(info -> info.itemCount > 0).count();

                        sender.sendMessage("§e[CustomEnderChest] ================== Validation ==================");
                        sender.sendMessage("§e[CustomEnderChest] Total records: §f" + players.size());
                        sender.sendMessage("§e[CustomEnderChest] Records with items: §f" + playersWithItems);
                        sender.sendMessage("§e[CustomEnderChest] Records with overflow: §f" + overflowCount);
                        sender.sendMessage("§e[CustomEnderChest] Corrupted records: §f" + corruptedCount);

                        if (corruptedCount > 0) {
                            sender.sendMessage("§c[CustomEnderChest] Corrupted entries (max 10 shown):");
                            int shown = 0;
                            for (StorageInterface.PlayerDataInfo info : players) {
                                if (!info.isCorrupted) {
                                    continue;
                                }
                                sender.sendMessage("§c - " + info.playerName + " (" + info.playerUUID + "): "
                                        + (info.errorMessage == null ? "Unknown error" : info.errorMessage));
                                shown++;
                                if (shown >= 10) {
                                    break;
                                }
                            }
                            if (corruptedCount > 10) {
                                sender.sendMessage("§c... and " + (corruptedCount - 10) + " more corrupted entries.");
                            }
                        }

                        sender.sendMessage("§e[CustomEnderChest] =================================================");
                    }))
                    .exceptionally(ex -> {
                        plugin.getLogger().warning("Failed to validate storage data: " + ex.getMessage());
                        Scheduler.runTask(
                                () -> sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.save-error")));
                        return null;
                    });
            return;
        }

        sender.sendMessage("§e[CustomEnderChest] Collecting storage statistics...");

        storage.getStorageStats()
                .thenAccept(stats -> Scheduler.runTask(() -> {
                    String storageType = plugin.config().getString("storage.type", "yml").toUpperCase();
                    sender.sendMessage("§e[CustomEnderChest] ==================== Stats =====================");
                    sender.sendMessage("§e[CustomEnderChest] Storage type: §f" + storageType);
                    sender.sendMessage("§e[CustomEnderChest] Total player records: §f" + stats.totalPlayers);
                    sender.sendMessage("§e[CustomEnderChest] Players with items: §f" + stats.playersWithItems);
                    sender.sendMessage("§e[CustomEnderChest] Total stored items: §f" + stats.totalItems);
                    sender.sendMessage("§e[CustomEnderChest] Overflow players: §f" + stats.totalOverflowPlayers);
                    sender.sendMessage("§e[CustomEnderChest] Overflow items: §f" + stats.totalOverflowItems);
                    sender.sendMessage("§e[CustomEnderChest] Data size (bytes): §f" + stats.totalDataSize);
                    sender.sendMessage("§e[CustomEnderChest] =================================================");
                }))
                .exceptionally(ex -> {
                    plugin.getLogger().warning("Failed to fetch storage stats: " + ex.getMessage());
                    Scheduler.runTask(
                            () -> sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.save-error")));
                    return null;
                });
    }

    /**
     * Handle /cec migrate command
     */
    private void handleMigrate(CommandSender sender, String[] args) {
        if (!hasSenderPermission(sender, "CustomEnderChest.admin")) {
            sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.no-permission"));
            return;
        }

        if (args.length < 3) {
            sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.migrate-usage"));
            sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.migrate-example"));
            return;
        }

        String sourceType = args[1].toLowerCase();
        String targetType = args[2].toLowerCase();

        migrationManager.startMigration(sender, sourceType, targetType);
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
                completions.add("migrate");
                completions.add("stats");
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
            if (args[0].equalsIgnoreCase("migrate") && sender.hasPermission("CustomEnderChest.admin")) {
                return List.of("yml", "h2", "mysql").stream()
                        .filter(s -> s.startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
            // Stats subcommand completions
            if (args[0].equalsIgnoreCase("stats") && sender.hasPermission("CustomEnderChest.admin")) {
                List<String> statsCompletions = new ArrayList<>();
                statsCompletions.add("validate");
                statsCompletions.add("help");
                return statsCompletions.stream()
                        .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }
        if (args.length == 3) {
            if (args[0].equalsIgnoreCase("migrate") && sender.hasPermission("CustomEnderChest.admin")) {
                return List.of("yml", "h2", "mysql").stream()
                        .filter(s -> s.startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }
        return List.of();
    }
}
