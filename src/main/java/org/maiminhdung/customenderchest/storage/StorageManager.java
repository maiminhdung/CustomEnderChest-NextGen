package org.maiminhdung.customenderchest.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.storage.impl.H2Storage;
import org.maiminhdung.customenderchest.storage.impl.MySQLStorage;
import org.maiminhdung.customenderchest.storage.impl.YmlStorage;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;

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
                    plugin.getLogger().severe("MySQL connection failed! Falling back to YML storage as a safe default.");
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

            this.dataSource = new HikariDataSource(config);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private boolean connectH2() {
        try {
            HikariConfig config = new HikariConfig();
            config.setPoolName("CEC-H2-Pool");
            File dbFile = new File(plugin.getDataFolder(), "data/enderchests");
            config.setJdbcUrl("jdbc:h2:" + dbFile.getAbsolutePath());
            config.setDriverClassName("org.maiminhdung.customenderchest.lib.h2.Driver");

            config.setMaximumPoolSize(plugin.config().getInt("storage.pool-settings.max-pool-size", 10));

            this.dataSource = new HikariDataSource(config);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
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
}