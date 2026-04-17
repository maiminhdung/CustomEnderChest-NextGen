package org.maiminhdung.customenderchest.storage.migrate;

import org.bukkit.command.CommandSender;

import java.util.concurrent.CompletableFuture;

/**
 * Interface definition for database migration.
 * Strategy pattern is used here to support various types of migrations.
 */
public interface Migrator {

    /**
     * Executes the migration process.
     * @param sender The command sender initiating the migration (to receive progress updates).
     * @return CompletableFuture representing the migration task.
     */
    CompletableFuture<Void> migrate(CommandSender sender);

    /**
     * Gets the name of the source database type.
     */
    String getSourceName();

    /**
     * Gets the name of the target database type.
     */
    String getTargetName();
}

