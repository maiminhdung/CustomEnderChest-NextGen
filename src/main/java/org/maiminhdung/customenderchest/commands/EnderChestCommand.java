package org.maiminhdung.customenderchest.commands;

import org.bukkit.inventory.ItemStack;
import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.api.events.EnderVaultDeleteEvent;
import org.maiminhdung.customenderchest.Scheduler;
import org.maiminhdung.customenderchest.data.EnderChestManager;
import org.maiminhdung.customenderchest.data.BulkImporter;
import org.maiminhdung.customenderchest.storage.StorageInterface;
import org.maiminhdung.customenderchest.utils.DataLockManager;
import org.maiminhdung.customenderchest.locale.LocaleManager;
import org.maiminhdung.customenderchest.storage.migrate.MigrationManager;
import org.maiminhdung.customenderchest.utils.EnderChestUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Main command handler for CustomEnderChest plugin
 * Handles all /<main> subcommands including open, vaults, overflow, reload, delete, stats, etc.
 */
public final class EnderChestCommand implements CommandExecutor, TabCompleter {

    private final EnderChest plugin;
    private final EnderChestManager manager;
    private final MigrationManager migrationManager;
    private final BulkImporter bulkImporter;

    public EnderChestCommand(EnderChest plugin) {
        this.plugin = plugin;
        this.manager = plugin.getEnderChestManager();
        this.migrationManager = new MigrationManager(plugin);
        this.bulkImporter = new BulkImporter(plugin);
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
                 args[0].equalsIgnoreCase("migrate") ||
                 args[0].equalsIgnoreCase("stats"));

            if (!isAdminCommand && !hasCommandPermission(p)) {
                p.sendMessage(plugin.getLocaleManager().getPrefixedComponent(p, "messages.no-permission"));
                return true;
            }
        }

        // Default: open own enderchest (or the vault selector per config)
        if (args.length == 0) {
            return handleDefaultCommand(sender);
        }

        // /<main> <number> - open that vault directly (multi-vault)
        if (args[0].chars().allMatch(Character::isDigit)) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.players-only"));
                return true;
            }
            if (migrationManager.isMigrating()) {
                p.sendMessage(plugin.getLocaleManager().getPrefixedComponent(p, "command.migrate-in-progress"));
                return true;
            }
            if (!p.isOp() && !p.hasPermission("CustomEnderChest.command.open.self")) {
                p.sendMessage(plugin.getLocaleManager().getPrefixedComponent(p, "messages.no-permission"));
                return true;
            }
            Integer directVault = parseVaultArg(p, args[0]);
            if (directVault == null) {
                return true;
            }
            plugin.getEnderChestManager().openEnderChest(p, directVault);
            return true;
        }

        // Handle subcommands
        String subCommand = args[0].toLowerCase();
        switch (subCommand) {
            case "open":
                handleOpen(sender, args);
                break;
            case "vaults":
                handleVaults(sender);
                break;
            case "setup":
                handleSetup(sender);
                break;
            case "reload":
                handleReload(sender);
                break;
            case "import":
                handleImport(sender, args, label);
                break;
            case "delete":
                handleDelete(sender, args, label);
                break;
            case "stats":
                handleStats(sender, args);
                break;
            case "migrate":
                handleMigrate(sender, args, label);
                break;
            case "overflow":
                handleOverflow(sender, args);
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

    /** Parses a positive vault number argument, or null when absent/invalid. */
    private Integer parseVaultArg(CommandSender sender, String arg) {
        try {
            int vaultId = Integer.parseInt(arg);
            if (vaultId >= 1 && vaultId <= EnderChestUtils.getMaxVaults()) {
                return vaultId;
            }
        } catch (NumberFormatException ignored) {
            // Not a number - treat as player name
        }
        if (arg.chars().allMatch(Character::isDigit)) {
            // Numeric but out of range
            sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.vault-invalid",
                    Placeholder.unparsed("max", String.valueOf(EnderChestUtils.getMaxVaults()))));
        }
        return null;
    }

    /**
     * Handle default command (no args) - opens the player's own enderchest,
     * or the vault selector GUI when the config option is enabled.
     */
    private boolean handleDefaultCommand(CommandSender sender) {
        if (sender instanceof Player p) {
            if (migrationManager.isMigrating()) {
                sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.migrate-in-progress"));
                return true;
            }

            if (p.isOp() || p.hasPermission("CustomEnderChest.command.open.self")) {
                if (EnderChestUtils.shouldUseSelectorAsMainCommand()) {
                    plugin.getVaultSelectorManager().openSelector(p);
                } else {
                    plugin.getEnderChestManager().openEnderChest(p);
                }
            }
            else {
                p.sendMessage(plugin.getLocaleManager().getPrefixedComponent(p, "messages.no-permission"));
            }
        } else {
            sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.players-only"));
        }
        return true;
    }

    /**
     * Handle /<main> setup - reopens the first-run mode chooser GUI (admins
     * only; the GUI itself is one-time on fresh installations).
     */
    private void handleSetup(CommandSender sender) {
        if (!hasSenderPermission(sender, "CustomEnderChest.admin")) {
            sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.no-permission"));
            return;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage(plugin.getLocaleManager().getComponent("messages.players-only"));
            return;
        }
        if (plugin.getFirstRunSetupManager() != null) {
            plugin.getFirstRunSetupManager().openSetupGui(p);
        }
    }

    /**
     * Handle /<main> open [player] [vault] command
     * Forms:
     * - open            -> own vault 1
     * - open <number>   -> own vault <number>
     * - open <player>   -> admin view of player's vault 1
     * - open <player> <number> -> admin view of player's vault <number>
     */
    private void handleOpen(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(plugin.getLocaleManager().getComponent("messages.players-only"));
            return;
        }

        if (migrationManager.isMigrating()) {
            p.sendMessage(plugin.getLocaleManager().getPrefixedComponent(p, "command.migrate-in-progress"));
            return;
        }

        // Open own enderchest
        if (args.length == 1) {
            if (p.isOp() || p.hasPermission("CustomEnderChest.command.open.self")) {
                plugin.getEnderChestManager().openEnderChest(p);
                return;
            }
            else {
                p.sendMessage(plugin.getLocaleManager().getPrefixedComponent(p, "messages.no-permission"));
                return;
            }
        }

        // /<main> open <number> - own vault
        if (args.length == 2 && args[1].chars().allMatch(Character::isDigit)) {
            Integer ownVault = parseVaultArg(p, args[1]);
            if (ownVault == null) {
                return; // parseVaultArg already sent the invalid message
            }
            if (!p.isOp() && !p.hasPermission("CustomEnderChest.command.open.self")) {
                p.sendMessage(plugin.getLocaleManager().getPrefixedComponent(p, "messages.no-permission"));
                return;
            }
            plugin.getEnderChestManager().openEnderChest(p, ownVault);
            return;
        }

        // Open other player's vault (admin only)
        if (!p.hasPermission("CustomEnderChest.command.open.other")) {
            p.sendMessage(plugin.getLocaleManager().getPrefixedComponent(p, "messages.no-permission"));
            return;
        }
        String targetName = args[1].trim();
        if (targetName.isEmpty()) {
            p.sendMessage(plugin.getLocaleManager().getPrefixedComponent(p, "messages.invalid-player"));
            return;
        }

        int vaultId = 1;
        if (args.length >= 3) {
            Integer parsed = parseVaultArg(p, args[2]);
            if (parsed == null && args[2].chars().allMatch(Character::isDigit)) {
                return; // invalid range message already sent
            }
            if (parsed != null) {
                vaultId = parsed;
            }
        }
        plugin.getEnderChestManager().openOtherPlayerChest(p, targetName, vaultId);
    }

    /**
     * Handle /<main> vaults (alias: menu) - opens the vault selector GUI
     */
    private void handleVaults(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(plugin.getLocaleManager().getComponent("messages.players-only"));
            return;
        }

        if (migrationManager.isMigrating()) {
            p.sendMessage(plugin.getLocaleManager().getPrefixedComponent(p, "command.migrate-in-progress"));
            return;
        }

        boolean allowed = p.isOp()
                || p.hasPermission("CustomEnderChest.command.vaults")
                || p.hasPermission("CustomEnderChest.command.open.self");
        if (!allowed) {
            p.sendMessage(plugin.getLocaleManager().getPrefixedComponent(p, "messages.no-permission"));
            return;
        }

        plugin.getVaultSelectorManager().openSelector(p);
    }

    /**
     * Handle /<main> reload command
     * Reloads plugin configuration and locale files
     */
    private void handleReload(CommandSender sender) {
        if (!hasSenderPermission(sender, "CustomEnderChest.admin")) {
            sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.no-permission"));
            return;
        }
        Scheduler.runTask(() -> {
            boolean reloaded = plugin.reloadRuntimeConfiguration();
            Runnable feedback = () -> {
                if (reloaded) {
                    sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.reload-success"));
                } else {
                    sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent(
                            "messages.reload-failed",
                            "<red>Reload partially failed. Previous values remain active for failed sections; check the console.</red>"));
                }
            };

            if (sender instanceof Entity entity) {
                Scheduler.runEntityTask(entity, feedback);
            } else if (sender instanceof BlockCommandSender blockSender) {
                Scheduler.runLocationTask(blockSender.getBlock().getLocation(), feedback);
            } else {
                feedback.run();
            }
        });
    }

    /**
     * Handle /<main> import vanilla command
     * Bulk imports vanilla enderchest data for all players (online + offline)
     */
    private void handleImport(CommandSender sender, String[] args, String label) {
        if (!hasSenderPermission(sender, "CustomEnderChest.admin")) {
            sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.no-permission"));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.import-usage",
                    Placeholder.unparsed("label", label)));
            return;
        }

        String importType = args[1].toLowerCase();
        if (importType.equals("vanilla")) {
            bulkImporter.importAll(sender);
        } else {
            sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.import-invalid-type"));
        }
    }

    /**
     * Handle /<main> delete &lt;player&gt; [vault] command.
     * Without a vault number (or while multi-vault is disabled) it deletes
     * every vault of the player; with a number it deletes just that vault.
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

        // Optional vault number (multi-vault only)
        Integer targetVault = null;
        if (args.length >= 3 && args[2].chars().allMatch(Character::isDigit)) {
            if (!EnderChestUtils.isVaultsEnabled()) {
                sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.vault-no-access",
                        Placeholder.unparsed("vault", args[2])));
                return;
            }
            Integer parsed = parseVaultArg(sender, args[2]);
            if (parsed == null) {
                return; // invalid range message already sent
            }
            targetVault = parsed;
        }

        final Integer singleVault = targetVault;
        String targetName = args[1];
        Scheduler.supplyAsync(() -> Bukkit.getOfflinePlayer(targetName))
                .thenAccept(target -> {
                    if (!target.hasPlayedBefore()) {
                        sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.player-not-found",
                                Placeholder.unparsed("player", targetName)));
                        return;
                    }

                    UUID targetUUID = target.getUniqueId();
                    String finalName = target.getName() != null ? target.getName() : targetName;
                    DataLockManager dataLockManager = plugin.getDataLockManager();
                    LocaleManager localeManager = plugin.getLocaleManager();
                    StorageInterface storage = plugin.getStorageManager().getStorage();

                    // Use atomic tryLock to avoid race condition
                    if (!dataLockManager.tryLock(targetUUID)) {
                        sender.sendMessage(localeManager.getPrefixedComponent("messages.data-busy"));
                        return;
                    }

                    storage.getVaultIds(targetUUID).thenAccept(vaultIds -> {
                        List<Integer> toDelete = singleVault != null
                                ? (vaultIds.contains(singleVault) ? List.of(singleVault) : List.<Integer>of())
                                : vaultIds;

                        if (toDelete.isEmpty()) {
                            dataLockManager.unlock(targetUUID);
                            sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent(sender,
                                    "command.player-not-found",
                                    Placeholder.unparsed("player", finalName)));
                            return;
                        }

                        // Bukkit events must fire synchronously — hop to the
                        // main thread before calling the API hook.
                        Scheduler.runTask(() -> {
                            // Developer API hook: plugins may veto the deletion
                            EnderVaultDeleteEvent deleteEvent = new EnderVaultDeleteEvent(sender, targetUUID, toDelete);
                            if (!deleteEvent.callEvent()) {
                                dataLockManager.unlock(targetUUID);
                                sender.sendMessage(plugin.getLocaleManager()
                                        .getPrefixedComponent(sender, "command.delete-cancelled"));
                                return;
                            }

                            List<java.util.concurrent.CompletableFuture<Void>> deletions = new ArrayList<>();
                            for (int vaultId : toDelete) {
                                deletions.add(storage.deleteVault(targetUUID, vaultId));
                            }
                            if (singleVault == null) {
                                // Full wipe: also clean up legacy rows
                                deletions.add(storage.deleteEnderChest(targetUUID));
                            }
                            java.util.concurrent.CompletableFuture.allOf(
                                    deletions.toArray(new java.util.concurrent.CompletableFuture[0]))
                                    .whenComplete((result, ex) -> {
                                        dataLockManager.unlock(targetUUID);

                                        if (target.isOnline()) {
                                            Scheduler.runEntityTask(target.getPlayer(), () -> {
                                                manager.reloadCacheFor(target.getPlayer());
                                                Objects.requireNonNull(target.getPlayer()).closeInventory();
                                            });
                                        }

                                        if (ex != null) {
                                            plugin.getLogger().warning("Failed to delete vault data for "
                                                    + finalName + ": " + ex.getMessage());
                                            sender.sendMessage(plugin.getLocaleManager()
                                                    .getPrefixedComponent(sender, "messages.save-error"));
                                            return;
                                        }

                                        sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent(sender,
                                                "command.delete-success",
                                                Placeholder.unparsed("player", finalName)));
                                    });
                        });
                    });
                });
    }

    /**
     * Handle /<main> stats [validate|help]
     * - /<main> stats: show storage summary
     * - /<main> stats validate: scan stored player entries and report corrupted records
     */
    private void handleStats(CommandSender sender, String[] args) {
        if (!hasSenderPermission(sender, "CustomEnderChest.admin")) {
            sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.no-permission"));
            return;
        }

        LocaleManager locale = plugin.getLocaleManager();

        if (args.length >= 2 && args[1].equalsIgnoreCase("help")) {
            sender.sendMessage(locale.getComponent("stats.help-header"));
            sender.sendMessage(locale.getComponent("stats.help-summary",
                    Placeholder.unparsed("command", plugin.getPrimaryCommand() + " stats")));
            sender.sendMessage(locale.getComponent("stats.help-validate",
                    Placeholder.unparsed("command", plugin.getPrimaryCommand() + " stats validate")));
            return;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("validate")) {
            sender.sendMessage(locale.getPrefixedComponent("stats.validating"));

            plugin.getStorageManager().getStorage().getPlayersWithItems()
                    .thenAccept(players -> Scheduler.runTask(() -> {
                        long corruptedCount = players.stream().filter(info -> info.isCorrupted).count();
                        long overflowCount = players.stream().filter(info -> info.hasOverflow).count();
                        long playersWithItems = players.stream().filter(info -> info.itemCount > 0).count();
                        long vaultRecords = players.stream().mapToLong(info -> info.vaultIds.size()).sum();

                        sender.sendMessage(locale.getComponent("stats.validation-header"));
                        sender.sendMessage(locale.getComponent("stats.validation-total-records",
                                Placeholder.unparsed("value", String.valueOf(players.size()))));
                        sender.sendMessage(locale.getComponent("stats.validation-records-with-items",
                                Placeholder.unparsed("value", String.valueOf(playersWithItems))));
                        sender.sendMessage(locale.getComponent("stats.validation-records-with-overflow",
                                Placeholder.unparsed("value", String.valueOf(overflowCount))));
                        sender.sendMessage(locale.getComponent("stats.validation-corrupted",
                                Placeholder.unparsed("value", String.valueOf(corruptedCount))));
                        sender.sendMessage(locale.getComponent("stats.vault-records",
                                Placeholder.unparsed("value", String.valueOf(vaultRecords))));

                        if (corruptedCount > 0) {
                            sender.sendMessage(locale.getComponent("stats.corrupted-list-header"));
                            int shown = 0;
                            for (StorageInterface.PlayerDataInfo info : players) {
                                if (!info.isCorrupted) {
                                    continue;
                                }
                                sender.sendMessage(locale.getComponent("stats.corrupted-entry",
                                        Placeholder.unparsed("player", info.playerName),
                                        Placeholder.unparsed("uuid", info.playerUUID.toString()),
                                        Placeholder.unparsed("error",
                                                info.errorMessage == null ? "Unknown error" : info.errorMessage)));
                                shown++;
                                if (shown >= 10) {
                                    break;
                                }
                            }
                            if (corruptedCount > 10) {
                                sender.sendMessage(locale.getComponent("stats.corrupted-more",
                                        Placeholder.unparsed("count", String.valueOf(corruptedCount - 10))));
                            }
                        }

                        sender.sendMessage(locale.getComponent("stats.footer"));
                    }))
                    .exceptionally(ex -> {
                        plugin.getLogger().warning("Failed to validate storage data: " + ex.getMessage());
                        Scheduler.runTask(
                                () -> sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.save-error")));
                        return null;
                    });
            return;
        }

        sender.sendMessage(locale.getPrefixedComponent("stats.collecting"));

        plugin.getStorageManager().getStorage().getStorageStats()
                .thenAccept(stats -> Scheduler.runTask(() -> {
                    String storageType = plugin.config().getString("storage.type", "yml").toUpperCase();
                    sender.sendMessage(locale.getComponent("stats.header"));
                    sender.sendMessage(locale.getComponent("stats.storage-type",
                            Placeholder.unparsed("value", storageType)));
                    sender.sendMessage(locale.getComponent("stats.total-records",
                            Placeholder.unparsed("value", String.valueOf(stats.totalPlayers))));
                    sender.sendMessage(locale.getComponent("stats.records-with-items",
                            Placeholder.unparsed("value", String.valueOf(stats.playersWithItems))));
                    sender.sendMessage(locale.getComponent("stats.total-items",
                            Placeholder.unparsed("value", String.valueOf(stats.totalItems))));
                    sender.sendMessage(locale.getComponent("stats.overflow-players",
                            Placeholder.unparsed("value", String.valueOf(stats.totalOverflowPlayers))));
                    sender.sendMessage(locale.getComponent("stats.overflow-items",
                            Placeholder.unparsed("value", String.valueOf(stats.totalOverflowItems))));
                    sender.sendMessage(locale.getComponent("stats.data-size",
                            Placeholder.unparsed("value", String.valueOf(stats.totalDataSize))));
                    sender.sendMessage(locale.getComponent("stats.footer"));
                }))
                .exceptionally(ex -> {
                    plugin.getLogger().warning("Failed to fetch storage stats: " + ex.getMessage());
                    Scheduler.runTask(
                            () -> sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.save-error")));
                    return null;
                });
    }

    /**
     * Handle /<main> migrate command
     */
    private void handleMigrate(CommandSender sender, String[] args, String label) {
        if (!hasSenderPermission(sender, "CustomEnderChest.admin")) {
            sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.no-permission"));
            return;
        }

        if (args.length < 3) {
            sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.migrate-usage",
                    Placeholder.unparsed("label", label)));
            sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.migrate-example",
                    Placeholder.unparsed("label", label)));
            return;
        }

        String sourceType = args[1].toLowerCase();
        String targetType = args[2].toLowerCase();

        migrationManager.startMigration(sender, sourceType, targetType);
    }

    /**
     * Handle /<main> overflow [player] [vault] command
     * Forms:
     * - overflow                 -> own vault 1 overflow
     * - overflow <number>        -> own vault <number> overflow
     * - overflow <player>        -> admin view of player's vault 1 overflow
     * - overflow <player> <number> -> admin view of player's vault <number> overflow
     */
    private void handleOverflow(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(plugin.getLocaleManager().getComponent("messages.players-only"));
            return;
        }

        // /<main> overflow - open own vault 1 overflow
        if (args.length == 1) {
            if (!p.isOp() && !p.hasPermission("CustomEnderChest.command.overflow")) {
                p.sendMessage(plugin.getLocaleManager().getPrefixedComponent(p, "messages.no-permission"));
                return;
            }
            if (migrationManager.isMigrating()) {
                p.sendMessage(plugin.getLocaleManager().getPrefixedComponent(p, "command.migrate-in-progress"));
                return;
            }
            manager.openOverflowGUI(p, 1);
            return;
        }

        // /<main> overflow <number> - own vault overflow
        if (args.length == 2 && args[1].chars().allMatch(Character::isDigit)) {
            Integer vaultId = parseVaultArg(p, args[1]);
            if (vaultId == null) {
                return; // invalid range message already sent
            }
            if (!p.isOp() && !p.hasPermission("CustomEnderChest.command.overflow")) {
                p.sendMessage(plugin.getLocaleManager().getPrefixedComponent(p, "messages.no-permission"));
                return;
            }
            if (migrationManager.isMigrating()) {
                p.sendMessage(plugin.getLocaleManager().getPrefixedComponent(p, "command.migrate-in-progress"));
                return;
            }
            manager.openOverflowGUI(p, vaultId);
            return;
        }

        // /<main> overflow <player> [vault] - admin view of another player's overflow
        if (!p.isOp() && !p.hasPermission("CustomEnderChest.command.overflow.other")) {
            p.sendMessage(plugin.getLocaleManager().getPrefixedComponent(p, "messages.no-permission"));
            return;
        }
        String targetName = args[1].trim();
        if (targetName.isEmpty()) {
            p.sendMessage(plugin.getLocaleManager().getPrefixedComponent(p, "messages.invalid-player"));
            return;
        }
        int vaultId = 1;
        if (args.length >= 3) {
            Integer parsed = parseVaultArg(p, args[2]);
            if (parsed == null && args[2].chars().allMatch(Character::isDigit)) {
                return;
            }
            if (parsed != null) {
                vaultId = parsed;
            }
        }
        manager.openAdminOverflowGUI(p, targetName, vaultId);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            if (sender.hasPermission("CustomEnderChest.command.open.self")) completions.add("open");
            if (sender.hasPermission("CustomEnderChest.command.overflow")) completions.add("overflow");
            if (sender.hasPermission("CustomEnderChest.admin")) {
                completions.add("reload");
                completions.add("import");
                completions.add("delete");
                completions.add("migrate");
                completions.add("stats");
                completions.add("open");
                completions.add("overflow");
                completions.add("setup");
            }
            if (EnderChestUtils.isVaultsEnabled()
                    && (sender.hasPermission("CustomEnderChest.command.open.self")
                            || sender.hasPermission("CustomEnderChest.command.vaults"))) {
                completions.add("vaults");
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
            if (args[0].equalsIgnoreCase("overflow")) {
                if (sender.hasPermission("CustomEnderChest.command.overflow.other")) {
                    return null; // Return online player names
                }
                return vaultNumberCompletions(args[1]);
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
            // Import subcommand completions
            if (args[0].equalsIgnoreCase("import") && sender.hasPermission("CustomEnderChest.admin")) {
                return List.of("vanilla").stream()
                        .filter(s -> s.startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }
        if (args.length == 3) {
            if (args[0].equalsIgnoreCase("migrate") && sender.hasPermission("CustomEnderChest.admin")) {
                return List.of("yml", "h2", "mysql").stream()
                        .filter(s -> s.startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
            }
            // Vault number completions for open/overflow/delete <player> <vault>
            if ((args[0].equalsIgnoreCase("open") && sender.hasPermission("CustomEnderChest.command.open.other"))
                    || (args[0].equalsIgnoreCase("overflow") && sender.hasPermission("CustomEnderChest.command.overflow.other"))
                    || (args[0].equalsIgnoreCase("delete") && sender.hasPermission("CustomEnderChest.command.delete"))) {
                return vaultNumberCompletions(args[2]);
            }
        }
        return List.of();
    }

    private List<String> vaultNumberCompletions(String prefix) {
        if (!EnderChestUtils.isVaultsEnabled()) {
            return List.of();
        }
        return IntStream.rangeClosed(1, EnderChestUtils.getMaxVaults())
                .mapToObj(String::valueOf)
                .filter(s -> s.startsWith(prefix))
                .collect(Collectors.toList());
    }
}
