package org.maiminhdung.customenderchest.storage.migrate;

import org.bukkit.command.CommandSender;
import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.Scheduler;
import org.maiminhdung.customenderchest.storage.StorageManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

public class MigrationManager {

    private final EnderChest plugin;
    private boolean isMigrating = false;

    public boolean isMigrating() {
        return isMigrating;
    }

    public MigrationManager(EnderChest plugin) {
        this.plugin = plugin;
    }

    public void startMigration(CommandSender sender, String sourceType, String targetType) {
        if (isMigrating) {
            sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.migrate-running"));
            return;
        }

        if (sourceType.equalsIgnoreCase(targetType)) {
            sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.migrate-same-type"));
            return;
        }

        isMigrating = true;
        sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("command.migrate-init"));

        Scheduler.runTaskAsync(() -> {
            StorageManager sourceManager = null;
            StorageManager targetManager = null;
            try {
                sourceManager = new StorageManager(plugin, sourceType);
                targetManager = new StorageManager(plugin, targetType);

                if (sourceManager.getStorage() == null) {
                    Scheduler.runTask(() -> sender.sendMessage(
                            plugin.getLocaleManager().getPrefixedComponent("command.migrate-conn-source-err")));
                    isMigrating = false;
                    closeManagers(sourceManager, targetManager);
                    return;
                }
                if (targetManager.getStorage() == null) {
                    Scheduler.runTask(() -> sender.sendMessage(
                            plugin.getLocaleManager().getPrefixedComponent("command.migrate-conn-target-err")));
                    isMigrating = false;
                    closeManagers(sourceManager, targetManager);
                    return;
                }

                StorageManager finalSourceManager = sourceManager;
                StorageManager finalTargetManager = targetManager;

                AbstractMigrator migrator = new AbstractMigrator(plugin, sourceManager.getStorage(),
                        targetManager.getStorage()) {
                    @Override
                    public String getSourceName() {
                        return sourceType.toUpperCase();
                    }

                    @Override
                    public String getTargetName() {
                        return targetType.toUpperCase();
                    }
                };

                migrator.migrate(sender).whenCompleteAsync((res, ex) -> {
                    if (ex != null) {
                        plugin.getLogger().severe("Migration error: " + ex.getMessage());
                        Scheduler.runTask(() -> sender.sendMessage(
                                plugin.getLocaleManager().getPrefixedComponent("command.migrate-error-unknown")));
                    }
                    isMigrating = false;
                    closeManagers(finalSourceManager, finalTargetManager);
                });

            } catch (Exception e) {
                plugin.getLogger().severe("Migration exception: " + e.getMessage());
                Scheduler.runTask(() -> sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent(
                        "command.migrate-error-unknown",
                        Placeholder.unparsed("error", e.getMessage() != null ? e.getMessage() : "Unknown"))));
                isMigrating = false;
                closeManagers(sourceManager, targetManager);
            }
        });
    }

    private void closeManagers(StorageManager m1, StorageManager m2) {
        if (m1 != null) {
            try {
                m1.close();
            } catch (Exception ignored) {
            }
        }
        if (m2 != null) {
            try {
                m2.close();
            } catch (Exception ignored) {
            }
        }
    }
}
