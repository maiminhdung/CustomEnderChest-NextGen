package org.maiminhdung.customenderchest;

import lombok.Getter;
import org.maiminhdung.customenderchest.bstats.Metrics;
import org.maiminhdung.customenderchest.bstats.Metrics.SimplePie;
import org.maiminhdung.customenderchest.commands.EnderChestCommand;
import org.maiminhdung.customenderchest.data.EnderChestManager;
import org.maiminhdung.customenderchest.listeners.PlayerListener;
import org.maiminhdung.customenderchest.locale.LocaleManager;
import org.maiminhdung.customenderchest.storage.StorageManager;
import org.maiminhdung.customenderchest.utils.DataLockManager;
import org.maiminhdung.customenderchest.utils.DebugLogger;
import org.maiminhdung.customenderchest.utils.SoundHandler;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.maiminhdung.customenderchest.utils.UpdateChecker;

public final class EnderChest extends JavaPlugin {

	@Getter
    private static EnderChest instance;

	private ConfigHandler configHandler;
	@Getter
    private EnderChestManager enderChestManager;
	@Getter
    private LocaleManager localeManager;
	@Getter
    private SoundHandler soundHandler;
	@Getter
    private StorageManager storageManager;
	@Getter
    private DebugLogger debugLogger;
    @Getter
    private DataLockManager dataLockManager;
    @Getter
    private UpdateChecker updateChecker; // Next update

	@Override
	public void onEnable() {
		instance = this;

		// Initialize configuration
		this.configHandler = new ConfigHandler(this);
		this.debugLogger = new DebugLogger(this);
		this.localeManager = new LocaleManager(this);
		this.soundHandler = new SoundHandler(this);
        this.dataLockManager = new DataLockManager();

		// Initialize Database Manager with HikariCP
		this.storageManager = new StorageManager(this);

		// Initialize the core logic manager
		this.enderChestManager = new EnderChestManager(this);

		// Register listeners and commands
		this.getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
		// Register commands and tab completer
		EnderChestCommand commandExecutor = new EnderChestCommand(this);
		PluginCommand command = this.getCommand("customenderchest");
		if (command != null) {
			command.setExecutor(commandExecutor);
			command.setTabCompleter(commandExecutor);
		}


		// Bstats Metrics
        if (config().getBoolean("general.bstats-metrics")) {
            setupBtatsMetrics();
            this.getLogger().info("bStats Metrics are enabled. Thank you for your support!");
        } else {
            this.getLogger().info("bStats Metrics are disabled.");
        }


        this.getLogger().info("CustomEnderChest has been enabled successfully!");
	}

	private void setupBtatsMetrics() {
		Metrics metrics = new Metrics(this, 26551);
        metrics.addCustomChart(new SimplePie("language", () -> getConfig().getString("general.locale")));
        metrics.addCustomChart(new SimplePie("storage_type", () -> getConfig().getString("storage.type")));
	}

	@Override
	public void onDisable() {
		// Shutdown manager tasks and save all data
		if (this.enderChestManager != null) {
            this.enderChestManager.shutdown();
        }
        // Close database connection pool
        if (this.storageManager != null) {
            this.storageManager.close();
        }
        
        this.getLogger().info("CustomEnderChest has been disabled.");
	}

    public ConfigHandler config() {
		return configHandler;
	}

}