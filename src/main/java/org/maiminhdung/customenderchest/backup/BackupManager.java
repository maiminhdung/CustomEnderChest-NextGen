package org.maiminhdung.customenderchest.backup;

import lombok.Getter;
import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.Scheduler;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Manages automatic backups of player data
 * Features:
 * - Automatic scheduled backups
 * - Backup retention policy (auto-cleanup old backups)
 * - Compression support
 * - Backup before server shutdown
 */
public class BackupManager {

    private final EnderChest plugin;
    /**
     * -- GETTER --
     *  Get backup folder
     */
    @Getter
    private final File backupFolder;
    private Scheduler.Task autoBackupTask;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");

    public BackupManager(EnderChest plugin) {
        this.plugin = plugin;
        this.backupFolder = new File(plugin.getDataFolder(), "backups");

        if (!backupFolder.exists()) {
            if (backupFolder.mkdirs()) {
                plugin.getLogger().info("Created backups folder at: " + backupFolder.getAbsolutePath());
            }
        }
    }

    /**
     * Start the automatic backup task
     */
    public void startAutoBackup() {
        if (!plugin.config().getBoolean("backup.enabled")) {
            plugin.getLogger().info("Automatic backups are disabled in config.");
            return;
        }

        long intervalMinutes = plugin.config().getInt("backup.interval-minutes", 60);
        long intervalTicks = intervalMinutes * 60L * 20L; // Convert minutes to ticks

        // Run first backup after 5 minutes, then every interval
        this.autoBackupTask = Scheduler.runTaskTimerAsync(
            this::performBackup,
            5 * 60L * 20L, // Initial delay: 5 minutes
            intervalTicks
        );

        plugin.getLogger().info("Automatic backup system started. Interval: " + intervalMinutes + " minutes");
    }

    /**
     * Stop the automatic backup task
     */
    public void stopAutoBackup() {
        if (autoBackupTask != null) {
            autoBackupTask.cancel();
            autoBackupTask = null;
        }
    }

    /**
     * Perform a backup operation
     */
    public CompletableFuture<Boolean> performBackup() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String timestamp = dateFormat.format(new Date());
                String storageType = plugin.config().getString("storage.type", "yml").toLowerCase();

                plugin.getLogger().info("[Backup] Starting backup process...");
                plugin.getDebugLogger().log("[Backup] Timestamp: " + timestamp);
                plugin.getDebugLogger().log("[Backup] Storage type: " + storageType);
                long startTime = System.currentTimeMillis();

                File backupFile = new File(backupFolder, "backup_" + timestamp + ".zip");
                plugin.getDebugLogger().log("[Backup] Target file: " + backupFile.getAbsolutePath());

                switch (storageType) {
                    case "yml":
                        plugin.getDebugLogger().log("[Backup] Using YML backup method");
                        backupYmlData(backupFile);
                        break;
                    case "h2":
                        plugin.getDebugLogger().log("[Backup] Using H2 backup method");
                        backupH2Data(backupFile);
                        break;
                    case "mysql":
                        plugin.getDebugLogger().log("[Backup] Using MySQL backup method");
                        backupMySQLData(backupFile);
                        break;
                    default:
                        plugin.getLogger().warning("[Backup] Unknown storage type: " + storageType);
                        return false;
                }

                long duration = System.currentTimeMillis() - startTime;
                long fileSizeKB = backupFile.exists() ? backupFile.length() / 1024 : 0;

                plugin.getLogger().info("[Backup] Backup completed successfully in " + duration + "ms");
                plugin.getLogger().info("[Backup] Backup saved to: " + backupFile.getName() + " (Size: " + fileSizeKB + " KB)");
                plugin.getDebugLogger().log("[Backup] Backup file size: " + fileSizeKB + " KB");

                // Clean up old backups
                plugin.getDebugLogger().log("[Backup] Starting cleanup of old backups...");
                cleanupOldBackups();

                return true;
            } catch (Exception e) {
                plugin.getLogger().severe("[Backup] Failed to create backup: " + e.getMessage());
                plugin.getDebugLogger().log("[Backup] Exception details: " + e.getClass().getName() + " - " + e.getMessage());
                if (plugin.config().getBoolean("general.debug")) {
                    e.printStackTrace();
                }
                return false;
            }
        });
    }

    /**
     * Backup YML storage data
     */
    private void backupYmlData(File backupFile) throws IOException {
        plugin.getDebugLogger().log("[Backup] Starting YML backup...");

        File playerdataFolder = new File(plugin.getDataFolder(), "playerdata");

        if (!playerdataFolder.exists() || !playerdataFolder.isDirectory()) {
            plugin.getLogger().warning("[Backup] No playerdata folder found for YML storage");
            plugin.getDebugLogger().log("[Backup] Expected folder: " + playerdataFolder.getAbsolutePath());
            return;
        }

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(backupFile.toPath()))) {
            File[] files = playerdataFolder.listFiles((dir, name) -> name.endsWith(".yml"));

            if (files == null || files.length == 0) {
                plugin.getLogger().warning("[Backup] No player data files found to backup");
                plugin.getDebugLogger().log("[Backup] Checked folder: " + playerdataFolder.getAbsolutePath());
                return;
            }

            plugin.getDebugLogger().log("[Backup] Found " + files.length + " YML files to backup");

            for (File file : files) {
                plugin.getDebugLogger().log("[Backup] Adding to archive: " + file.getName());
                ZipEntry entry = new ZipEntry("playerdata/" + file.getName());
                zos.putNextEntry(entry);
                Files.copy(file.toPath(), zos);
                zos.closeEntry();
            }

            plugin.getLogger().info("[Backup] Backed up " + files.length + " player data files");
            plugin.getDebugLogger().log("[Backup] YML backup completed successfully");
        }
    }

    /**
     * Backup H2 database data using SQL BACKUP command
     * This avoids file locking issues that occur when copying .mv.db files directly
     */
    private void backupH2Data(File backupFile) throws IOException {
        plugin.getDebugLogger().log("[Backup] Starting H2 database backup using SQL BACKUP command...");

        File dataFolder = new File(plugin.getDataFolder(), "data");
        if (!dataFolder.exists() || !dataFolder.isDirectory()) {
            plugin.getLogger().warning("[Backup] No data folder found for H2 storage");
            return;
        }

        // Create a temporary backup using H2's BACKUP SQL command
        File tempBackupFile = new File(plugin.getDataFolder(), "temp_h2_backup.zip");

        try {
            // Use H2's BACKUP TO command to create a consistent backup
            // This command locks the database briefly and creates a consistent snapshot
            java.sql.Connection conn = plugin.getStorageManager().getConnection();
            java.sql.Statement stmt = conn.createStatement();

            String backupPath = tempBackupFile.getAbsolutePath().replace("\\", "/");
            String sql = "BACKUP TO '" + backupPath + "'";

            plugin.getDebugLogger().log("[Backup] Executing H2 BACKUP command: " + sql);
            stmt.execute(sql);
            stmt.close();
            conn.close();

            plugin.getDebugLogger().log("[Backup] H2 BACKUP command completed successfully");

            // Now copy the temporary backup to the final backup file
            if (tempBackupFile.exists()) {
                try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(backupFile.toPath()))) {
                    ZipEntry entry = new ZipEntry("data/h2_database_backup.zip");
                    zos.putNextEntry(entry);
                    Files.copy(tempBackupFile.toPath(), zos);
                    zos.closeEntry();

                    plugin.getDebugLogger().log("[Backup] H2 backup added to backup archive");
                }

                // Clean up temporary file
                if (tempBackupFile.delete()) {
                    plugin.getDebugLogger().log("[Backup] Temporary backup file deleted");
                }

                plugin.getLogger().info("[Backup] H2 database backed up successfully using SQL BACKUP command");
            } else {
                plugin.getLogger().warning("[Backup] H2 BACKUP command did not create expected file");
            }

        } catch (Exception e) {
            plugin.getLogger().warning("[Backup] H2 SQL BACKUP failed, trying file copy method as fallback...");
            plugin.getDebugLogger().log("[Backup] Error: " + e.getMessage());

            // Fallback: Try to copy files directly (may fail if database is active)
            try {
                backupH2DataFileCopy(backupFile);
            } catch (Exception e2) {
                plugin.getLogger().severe("[Backup] Both H2 backup methods failed!");
                plugin.getLogger().severe("[Backup] This usually means the database is locked by another process.");
                plugin.getLogger().severe("[Backup] Consider increasing backup interval or using MySQL for better backup support.");
                throw new IOException("Failed to backup H2 database: " + e2.getMessage(), e2);
            }
        } finally {
            // Ensure temp file is cleaned up
            if (tempBackupFile.exists()) {
                tempBackupFile.delete();
            }
        }
    }

    /**
     * Fallback method: Direct file copy for H2 backup
     * May fail if database is locked
     */
    private void backupH2DataFileCopy(File backupFile) throws IOException {
        plugin.getDebugLogger().log("[Backup] Attempting H2 file copy backup...");

        File dataFolder = new File(plugin.getDataFolder(), "data");

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(backupFile.toPath()))) {
            File[] files = dataFolder.listFiles((dir, name) ->
                name.startsWith("enderchests") && (name.endsWith(".mv.db") || name.endsWith(".trace.db"))
            );

            if (files == null || files.length == 0) {
                plugin.getLogger().warning("[Backup] No H2 database files found to backup");
                return;
            }

            for (File file : files) {
                try {
                    plugin.getDebugLogger().log("[Backup] Copying file: " + file.getName());
                    ZipEntry entry = new ZipEntry("data/" + file.getName());
                    zos.putNextEntry(entry);

                    // Use a buffered copy to avoid locking issues
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = fis.read(buffer)) > 0) {
                            zos.write(buffer, 0, len);
                        }
                    }

                    zos.closeEntry();
                    plugin.getDebugLogger().log("[Backup] Successfully copied: " + file.getName());
                } catch (IOException e) {
                    plugin.getLogger().warning("[Backup] Failed to copy " + file.getName() + ": " + e.getMessage());
                    // Continue with other files
                }
            }

            plugin.getLogger().info("[Backup] H2 file copy backup completed");
        }
    }

    /**
     * Backup MySQL data (export SQL dump)
     */
    private void backupMySQLData(File backupFile) throws IOException {
        // For MySQL, we'll create a simple text file with backup info
        // Full MySQL backup should be done at database level using mysqldump

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(backupFile.toPath()))) {
            String info = "MySQL Backup Information\n" +
                    "========================\n" +
                    "Timestamp: " + new Date() + "\n" +
                    "Host: " + plugin.config().getString("storage.mysql.host") + "\n" +
                    "Database: " + plugin.config().getString("storage.mysql.database") + "\n" +
                    "Table: " + plugin.config().getString("storage.table_name") + "\n\n" +
                    "Note: For full MySQL backup, please use mysqldump or your database backup solution.\n" +
                    "Example: mysqldump -u username -p database_name > backup.sql\n";

            ZipEntry entry = new ZipEntry("mysql_backup_info.txt");
            zos.putNextEntry(entry);
            zos.write(info.getBytes());
            zos.closeEntry();

            plugin.getLogger().info("[Backup] Created MySQL backup info file");
            plugin.getLogger().warning("[Backup] For full MySQL backup, use mysqldump at database level");
        }
    }

    /**
     * Clean up old backups based on retention policy
     */
    private void cleanupOldBackups() {
        try {
            int maxBackups = plugin.config().getInt("backup.max-backups", 10);
            int retentionDays = plugin.config().getInt("backup.retention-days", 7);

            plugin.getDebugLogger().log("[Backup] Cleanup policy - Max backups: " + maxBackups + ", Retention days: " + retentionDays);

            File[] backupFiles = backupFolder.listFiles((dir, name) ->
                name.startsWith("backup_") && name.endsWith(".zip")
            );

            if (backupFiles == null || backupFiles.length == 0) {
                plugin.getDebugLogger().log("[Backup] No backup files found for cleanup");
                return;
            }

            plugin.getDebugLogger().log("[Backup] Found " + backupFiles.length + " backup files");

            if (backupFiles.length <= maxBackups) {
                plugin.getDebugLogger().log("[Backup] Backup count (" + backupFiles.length + ") is within limit (" + maxBackups + "), no cleanup needed");
                return; // No cleanup needed
            }

            // Sort by last modified date (oldest first)
            Arrays.sort(backupFiles, Comparator.comparingLong(File::lastModified));

            Instant cutoffTime = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
            plugin.getDebugLogger().log("[Backup] Cutoff time for retention: " + cutoffTime);

            int deleted = 0;

            // Delete old backups
            for (int i = 0; i < backupFiles.length - maxBackups; i++) {
                File file = backupFiles[i];
                if (file.lastModified() < cutoffTime.toEpochMilli()) {
                    plugin.getDebugLogger().log("[Backup] Deleting old backup: " + file.getName() + " (Age: " + ((System.currentTimeMillis() - file.lastModified()) / (1000 * 60 * 60 * 24)) + " days)");
                    if (file.delete()) {
                        deleted++;
                    } else {
                        plugin.getLogger().warning("[Backup] Failed to delete backup: " + file.getName());
                    }
                } else {
                    plugin.getDebugLogger().log("[Backup] Keeping backup: " + file.getName() + " (within retention period)");
                }
            }

            if (deleted > 0) {
                plugin.getLogger().info("[Backup] Cleaned up " + deleted + " old backup(s)");
            } else {
                plugin.getDebugLogger().log("[Backup] No backups needed to be deleted");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Backup] Failed to cleanup old backups: " + e.getMessage());
            plugin.getDebugLogger().log("[Backup] Cleanup error: " + e.getClass().getName() + " - " + e.getMessage());
        }
    }

    /**
     * Create a backup before shutdown
     */
    public void createShutdownBackup() {
        // Check if shutdown backup is enabled (default to true if not set)
        if (plugin.config().getString("backup.backup-on-shutdown", "true").equalsIgnoreCase("false")) {
            return;
        }

        plugin.getLogger().info("[Backup] Creating shutdown backup...");
        try {
            // Use join() to wait for backup to complete before shutdown
            performBackup().join();
        } catch (Exception e) {
            plugin.getLogger().severe("[Backup] Failed to create shutdown backup: " + e.getMessage());
            if (plugin.config().getBoolean("general.debug")) {
                e.printStackTrace();
            }
        }
    }

    /**
     * List all available backups
     */
    public List<File> listBackups() {
        File[] files = backupFolder.listFiles((dir, name) ->
            name.startsWith("backup_") && name.endsWith(".zip")
        );

        if (files == null || files.length == 0) {
            return new ArrayList<>();
        }

        return Arrays.stream(files)
                .sorted(Comparator.comparingLong(File::lastModified).reversed())
                .collect(Collectors.toList());
    }

}

