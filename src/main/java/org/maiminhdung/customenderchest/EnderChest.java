package org.maiminhdung.customenderchest;

import org.maiminhdung.customenderchest.bstats.Metrics;
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

public final class EnderChest extends JavaPlugin {

	private static EnderChest instance;

	private ConfigHandler configHandler;
	private EnderChestManager enderChestManager;
	private LocaleManager localeManager;
	private SoundHandler soundHandler;
	private StorageManager storageManager;
	private DebugLogger debugLogger;
    private DataLockManager dataLockManager;

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
		setupBtatsMetrics();

		this.getLogger().info("CustomEnderChest has been enabled successfully!");
	}

	private void setupBtatsMetrics() {
		Metrics metrics = new Metrics(this, 26551);
	}

	@Override
	public void onDisable() {
		// Shutdown manager tasks and save all data
		if (this.enderChestManager != null) {
			this.enderChestManager.shutdown();
		}
		// Close database connections
		this.getLogger().info("CustomEnderChest has been disabled.");
	}

	public static EnderChest getInstance() {
		return instance;
	}

	public ConfigHandler config() {
		return configHandler;
	}

	public EnderChestManager getEnderChestManager() {
		return enderChestManager;
	}

	public LocaleManager getLocaleManager() {
		return localeManager;
	}

	public SoundHandler getSoundHandler() {
		return soundHandler;
	}

	public StorageManager getStorageManager() {
		return storageManager;
	}

	public DebugLogger getDebugLogger() {
		return debugLogger;
	}

    public DataLockManager getDataLockManager() {
        return dataLockManager;
    }
}