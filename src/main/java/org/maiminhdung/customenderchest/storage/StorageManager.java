package org.maiminhdung.customenderchest.storage;

import static org.maiminhdung.customenderchest.EnderChest.ERROR_TRACKER;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.Inventory;
import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.storage.impl.H2Storage;
import org.maiminhdung.customenderchest.storage.impl.MySQLStorage;
import org.maiminhdung.customenderchest.storage.impl.YmlStorage;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

public class StorageManager {

    private final EnderChest plugin;
    private HikariDataSource dataSource;
    private final StorageInterface storageImplementation;

    public StorageManager(EnderChest plugin) {
        this.plugin = plugin;
        String storageType = plugin.config().getString("storage.type", "yml").toLowerCase();

        // Use H2 or MySQL if specified, otherwise default to YML
        switch (storageType) {
            case "mysql":
                plugin.getLogger().info("Using MySQL for data storage.");
                if (connectMySQL()) {
                    this.storageImplementation = new MySQLStorage(this);
                } else {
                    plugin.getLogger()
                            .severe("MySQL connection failed! Falling back to YML storage as a safe default.");
                    this.storageImplementation = new YmlStorage(plugin);
                }
                break;
            case "h2":
                plugin.getLogger().info("Using H2 for data storage.");
                if (connectH2()) {
                    this.storageImplementation = new H2Storage(this);
                } else {
                    plugin.getLogger().severe("H2 connection failed! Falling back to YML storage as a safe default.");
                    this.storageImplementation = new YmlStorage(plugin);
                }
                break;
            case "yml":
            default:
                plugin.getLogger().info("Using YML for data storage.");
                this.dataSource = null;
                this.storageImplementation = new YmlStorage(plugin);
                break;
        }

        this.storageImplementation.init();
    }

    public StorageManager(EnderChest plugin, String forceStorageType) {
        this.plugin = plugin;
        switch (forceStorageType.toLowerCase()) {
            case "mysql":
                plugin.getLogger().info("Migration: Initializing MySQL storage.");
                if (connectMySQL()) {
                    this.storageImplementation = new MySQLStorage(this);
                } else {
                    plugin.getLogger().severe("Migration: MySQL connection failed!");
                    this.storageImplementation = null;
                }
                break;
            case "h2":
                plugin.getLogger().info("Migration: Initializing H2 storage.");
                if (connectH2()) {
                    this.storageImplementation = new H2Storage(this);
                } else {
                    plugin.getLogger().severe("Migration: H2 connection failed!");
                    this.storageImplementation = null;
                }
                break;
            case "yml":
            default:
                plugin.getLogger().info("Migration: Initializing YML storage.");
                this.dataSource = null;
                this.storageImplementation = new YmlStorage(plugin);
                break;
        }
        if (this.storageImplementation != null) {
            this.storageImplementation.init();
        }
    }

    private boolean connectMySQL() {
        try {
            HikariConfig config = new HikariConfig();
            config.setPoolName("CEC-MySQL-Pool");
            config.setDataSourceClassName("com.mysql.cj.jdbc.MysqlDataSource");
            config.addDataSourceProperty("serverName", plugin.config().getString("storage.mysql.host"));
            config.addDataSourceProperty("portNumber", plugin.config().getInt("storage.mysql.port", 3306));
            config.addDataSourceProperty("databaseName", plugin.config().getString("storage.mysql.database"));
            config.addDataSourceProperty("user", plugin.config().getString("storage.mysql.username"));
            config.addDataSourceProperty("password", plugin.config().getString("storage.mysql.password"));
            config.addDataSourceProperty("useSSL", plugin.config().getBoolean("storage.mysql.use-ssl"));

            // Pool settings
            config.setMaximumPoolSize(plugin.config().getInt("storage.pool-settings.max-pool-size", 10));
            config.setMinimumIdle(plugin.config().getInt("storage.pool-settings.min-idle", 5));
            config.setConnectionTimeout(plugin.config().getInt("storage.pool-settings.connection-timeout", 30000));

            // Additional reliability settings
            config.setLeakDetectionThreshold(60000); // Detect connection leaks after 1 minute
            config.setKeepaliveTime(300000); // 5 minutes keepalive

            this.dataSource = new HikariDataSource(config);
            return true;
        } catch (Exception e) {
            String host = plugin.config().getString("storage.mysql.host", "localhost");
            int port = plugin.config().getInt("storage.mysql.port", 3306);
            String database = plugin.config().getString("storage.mysql.database", "");

            plugin.getLogger().severe("========================================================");
            plugin.getLogger().severe("MYSQL CONNECTION FAILED!");
            plugin.getLogger().severe("========================================================");

            // Provide specific hints based on the root cause
            Throwable rootCause = getRootCause(e);
            String rootMsg = rootCause.getMessage() != null ? rootCause.getMessage() : "";

            if (rootCause instanceof java.net.ConnectException || rootMsg.contains("Connection refused")) {
                plugin.getLogger().severe("Cause: Connection refused by " + host + ":" + port);
                plugin.getLogger().severe("Please check:");
                plugin.getLogger().severe("  1. Is MySQL server running?");
                plugin.getLogger().severe("  2. Is the host '" + host + "' correct?");
                plugin.getLogger().severe("  3. Is the port '" + port + "' correct? (default: 3306)");
                plugin.getLogger().severe("  4. Is the firewall blocking port " + port + "?");
                plugin.getLogger().severe("  5. Does MySQL allow remote connections? (check bind-address)");
            } else if (rootMsg.contains("Access denied")) {
                plugin.getLogger().severe("Cause: Access denied - wrong username or password.");
                plugin.getLogger().severe("Please check your MySQL credentials in config.yml.");
            } else if (rootMsg.contains("Unknown database")) {
                plugin.getLogger().severe("Cause: Database '" + database + "' does not exist.");
                plugin.getLogger().severe("Please create the database first:");
                plugin.getLogger().severe("  CREATE DATABASE " + database + ";");
            } else if (rootMsg.contains("Communications link failure") || rootMsg.contains("timed out")) {
                plugin.getLogger().severe("Cause: Connection timed out to " + host + ":" + port);
                plugin.getLogger().severe("The MySQL server may be overloaded or unreachable.");
            } else {
                plugin.getLogger().severe("Cause: " + e.getMessage());
            }

            plugin.getLogger().severe("Config: host=" + host + ", port=" + port + ", database=" + database);
            plugin.getLogger().severe("========================================================");
            ERROR_TRACKER.trackError(e);
            return false;
        }
    }

    private boolean connectH2() {
        return connectH2Internal(true);
    }

    private boolean connectH2Internal(boolean allowRecovery) {
        try {
            HikariConfig config = new HikariConfig();
            config.setPoolName("CEC-H2-Pool");
            File dbFile = new File(plugin.getDataFolder(), "data/enderchests");
            config.setJdbcUrl(
                    "jdbc:h2:" + dbFile.getAbsolutePath() + ";MODE=MySQL;AUTO_RECONNECT=TRUE;LOCK_TIMEOUT=10000");
            config.setDriverClassName("org.maiminhdung.customenderchest.lib.h2.Driver");

            // Pool size settings
            config.setMaximumPoolSize(plugin.config().getInt("storage.pool-settings.max-pool-size", 10));
            config.setMinimumIdle(2);

            // Timeout settings to prevent hanging
            config.setConnectionTimeout(10000); // 10 seconds to get connection
            config.setValidationTimeout(5000); // 5 seconds to validate connection
            config.setIdleTimeout(600000); // 10 minutes idle timeout
            config.setMaxLifetime(1800000); // 30 minutes max lifetime
            config.setLeakDetectionThreshold(60000); // Detect connection leaks after 1 minute

            // Keep connections alive
            config.setKeepaliveTime(300000); // 5 minutes keepalive

            this.dataSource = new HikariDataSource(config);
            return true;
        } catch (Exception e) {
            // Check if the file is locked (stale lock from crash / reload)
            if (allowRecovery && isH2FileLockError(e)) {
                plugin.getLogger().warning("========================================================");
                plugin.getLogger().warning("H2 DATABASE FILE IS LOCKED!");
                plugin.getLogger().warning("This usually happens after a server crash or /reload.");
                plugin.getLogger().warning("Attempting to remove stale lock file...");
                plugin.getLogger().warning("========================================================");

                if (cleanupStaleLockFile()) {
                    plugin.getLogger().info("Stale lock file removed. Retrying H2 connection...");
                    return connectH2Internal(false);
                } else {
                    plugin.getLogger().severe("Could not remove lock file. The database may be in use by another process.");
                    plugin.getLogger().severe("If no other server is running, manually delete: plugins/CustomEnderChest/data/enderchests.mv.db.lock.db");
                }
            }

            // Check if the root cause is a corrupted H2 database file
            if (allowRecovery && isH2CorruptionError(e)) {
                plugin.getLogger().severe("========================================================");
                plugin.getLogger().severe("H2 DATABASE FILE IS CORRUPTED!");
                plugin.getLogger().severe("This usually happens due to a server crash or forced shutdown.");
                plugin.getLogger().severe("Attempting automatic recovery...");
                plugin.getLogger().severe("========================================================");

                if (backupCorruptedH2File()) {
                    plugin.getLogger().info("Corrupted H2 file has been backed up. Retrying with a fresh database...");
                    return connectH2Internal(false);
                } else {
                    plugin.getLogger().severe("Failed to backup corrupted H2 file. Manual intervention required.");
                    plugin.getLogger().severe("Please manually rename or delete the file: plugins/CustomEnderChest/data/enderchests.mv.db");
                }
            }

            plugin.getLogger().severe("H2 connection error: " + e.getMessage());
            ERROR_TRACKER.trackError(e);
            return false;
        }
    }

    /**
     * Check if the exception chain contains an H2 file lock error.
     * Error 90020 = DATABASE_ALREADY_OPEN, or MVStoreException "file is locked".
     */
    private boolean isH2FileLockError(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            String msg = cause.getMessage();
            if (msg != null && (msg.contains("file is locked") || msg.contains("The file is locked")
                    || msg.contains("90020"))) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * Check if the exception chain contains an H2 corruption error (MVStoreException).
     */
    private boolean isH2CorruptionError(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            String msg = cause.getMessage();
            String className = cause.getClass().getName();
            if (className.contains("MVStoreException") && msg != null
                    && (msg.contains("corrupted") || msg.contains("File is corrupted"))) {
                return true;
            }
            if (msg != null && msg.contains("90030")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * Backup the corrupted H2 database file by renaming it with a timestamp.
     * This preserves the corrupted data for potential manual recovery.
     *
     * @return true if the backup was successful or no file existed
     */
    private boolean backupCorruptedH2File() {
        File dataFolder = new File(plugin.getDataFolder(), "data");
        File mvDbFile = new File(dataFolder, "enderchests.mv.db");
        File traceDbFile = new File(dataFolder, "enderchests.trace.db");

        if (!mvDbFile.exists()) {
            return true; // Nothing to backup
        }

        String timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new java.util.Date());

        try {
            // Rename the corrupted .mv.db file
            File backupMvDb = new File(dataFolder, "enderchests_corrupted_" + timestamp + ".mv.db");
            if (mvDbFile.renameTo(backupMvDb)) {
                plugin.getLogger().warning("Corrupted database backed up to: " + backupMvDb.getName());
            } else {
                plugin.getLogger().severe("Could not rename corrupted file: " + mvDbFile.getAbsolutePath());
                return false;
            }

            // Also rename the trace file if it exists
            if (traceDbFile.exists()) {
                File backupTrace = new File(dataFolder, "enderchests_corrupted_" + timestamp + ".trace.db");
                if (traceDbFile.renameTo(backupTrace)) {
                    plugin.getLogger().info("Trace file backed up to: " + backupTrace.getName());
                }
            }

            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to backup corrupted H2 file: " + e.getMessage());
            ERROR_TRACKER.trackError(e);
            return false;
        }
    }

    /**
     * Remove the stale H2 lock file (.lock.db) that may remain after a server crash.
     * H2 creates this file to prevent concurrent access. If the server crashes,
     * the lock file is not properly cleaned up, blocking future connections.
     *
     * @return true if the lock file was removed or didn't exist
     */
    private boolean cleanupStaleLockFile() {
        File dataFolder = new File(plugin.getDataFolder(), "data");
        File lockFile = new File(dataFolder, "enderchests.mv.db.lock.db");

        if (!lockFile.exists()) {
            // No lock file found, the lock might be OS-level file lock
            plugin.getLogger().warning("No .lock.db file found. The file may be locked by another running server instance.");
            return false;
        }

        try {
            if (lockFile.delete()) {
                plugin.getLogger().info("Successfully removed stale lock file: " + lockFile.getName());
                return true;
            } else {
                plugin.getLogger().severe("Could not delete lock file: " + lockFile.getAbsolutePath());
                return false;
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to remove lock file: " + e.getMessage());
            ERROR_TRACKER.trackError(e);
            return false;
        }
    }

    /**
     * Close connection when turn off.
     */
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().info("Database connection pool closed.");
        }
    }

    /**
     * Use connection from pool (For H2/MySQL).
     */
    public Connection getConnection() throws SQLException {
        if (dataSource == null) {
            throw new SQLException("Database is not connected (currently using YML storage).");
        }
        return dataSource.getConnection();
    }

    /**
     * Use storage currently working.
     */
    public StorageInterface getStorage() {
        return this.storageImplementation;
    }

    public Inventory getVanillaEnderChest(UUID playerUUID) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerUUID);
        if (!player.hasPlayedBefore()) {
            plugin.getDebugLogger().log("Player has never played before: " + playerUUID);
            return null;
        }

        // If the player is online, get their ender chest directly
        if (player.isOnline() && player.getPlayer() != null) {
            plugin.getDebugLogger().log("Player is online, getting ender chest directly: " + player.getName());
            return player.getPlayer().getEnderChest();
        }

        // For offline players, we cannot access their vanilla ender chest without NMS
        // The player needs to be online for vanilla import
        plugin.getDebugLogger().log("Player is offline, cannot access vanilla ender chest: " + player.getName() +
                " (UUID: " + playerUUID + "). Player must be online for vanilla import.");
        return null;
    }

    /**
     * Traverse the exception chain to find the deepest root cause.
     */
    private Throwable getRootCause(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}