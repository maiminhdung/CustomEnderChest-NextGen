package org.maiminhdung.customenderchest;

import dev.faststats.bukkit.BukkitMetrics;
import dev.faststats.core.ErrorTracker;
import dev.faststats.core.data.Metric;
import io.github.pluginupdatecore.updater.ConfigUpdater;
import io.github.pluginupdatecore.updater.UpdateChecker;
import lombok.Getter;
import org.maiminhdung.customenderchest.backup.BackupManager;
import org.maiminhdung.customenderchest.bstats.Metrics;
import org.maiminhdung.customenderchest.bstats.Metrics.SimplePie;
import org.maiminhdung.customenderchest.commands.EnderChestCommand;
import org.maiminhdung.customenderchest.data.EnderChestManager;
import org.maiminhdung.customenderchest.data.LegacyImporter;
import org.maiminhdung.customenderchest.data.MetricsDataProvider;
import org.maiminhdung.customenderchest.listeners.PlayerListener;
import org.maiminhdung.customenderchest.locale.LocaleManager;
import org.maiminhdung.customenderchest.storage.StorageManager;
import org.maiminhdung.customenderchest.utils.DataLockManager;
import org.maiminhdung.customenderchest.utils.DebugLogger;
import org.maiminhdung.customenderchest.utils.SoundHandler;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class EnderChest extends JavaPlugin {

	public static final ErrorTracker ERROR_TRACKER = ErrorTracker.contextAware();

	@Getter
	private static EnderChest instance;
	@Getter
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
	private UpdateChecker updateChecker;
	@Getter
	private BackupManager backupManager;
	@Getter
	private LegacyImporter legacyImporter;
	@Getter
	private MetricsDataProvider metricsDataProvider;
	@Getter
	private BukkitMetrics fastStatsMetrics;

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

		// Initialize Legacy Importer for vanilla data import
		this.legacyImporter = new LegacyImporter(this);

		// Initialize Backup Manager
		this.backupManager = new BackupManager(this);
		this.backupManager.startAutoBackup();

		// Initialize Update Checker
		if (config().getBoolean("general.update-checker")) {
			this.updateChecker = new UpdateChecker(this, "AipGDIso");
			this.getLogger().info("Update checker is enabled.");
		}

		// Update config.yml and reload config if necessary
		ConfigUpdater configUpdater = new ConfigUpdater(this);
		configUpdater.checkAndUpdateConfig();
		reloadConfig();

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

		// FastStats Metrics
		this.metricsDataProvider = new MetricsDataProvider(this);
		if (config().getBoolean("general.faststats-metrics")) {
			setupFastStatsMetrics();
			this.getLogger().info("FastStats Metrics are enabled.");
		} else {
			this.getLogger().info("FastStats Metrics are disabled.");
		}

		this.getLogger().info("CustomEnderChest has been enabled successfully!");
	}

	private void setupBtatsMetrics() {
		Metrics metrics = new Metrics(this, 26551);
		metrics.addCustomChart(new SimplePie("language", () -> getConfig().getString("general.locale")));
		metrics.addCustomChart(new SimplePie("storage_type", () -> getConfig().getString("storage.type")));
	}

	private void setupFastStatsMetrics() {
		this.fastStatsMetrics = BukkitMetrics.factory()
				.token("a83104b7f8415dbc03ffab67fe273bff")
				.errorTracker(ERROR_TRACKER)
				.addMetric(Metric.string("locale", () -> getConfig().getString("general.locale")))
				.addMetric(Metric.string("storage_type", () -> getConfig().getString("storage.type")))
				.addMetric(Metric.number("loaded_chests", () -> metricsDataProvider.getLoadedChests()))
				.addMetric(Metric.number("open_chests", () -> metricsDataProvider.getOpenChests()))
				.addMetric(Metric.number("save_count", () -> metricsDataProvider.getSaveCount()))
				.addMetric(Metric.number("load_count", () -> metricsDataProvider.getLoadCount()))
				.addMetric(Metric.number("avg_save_time_ms", () -> metricsDataProvider.getAvgSaveTimeMs()))
				.create(this);
		this.fastStatsMetrics.ready();
	}

	@Override
	public void onDisable() {
		this.getLogger().info("CustomEnderChest is shutting down...");

		// Shutdown FastStats metrics
		if (this.fastStatsMetrics != null) {
			this.fastStatsMetrics.shutdown();
		}

		// Stop automatic backup task first
		if (this.backupManager != null) {
			this.backupManager.stopAutoBackup();
			this.getLogger().info("Automatic backup task stopped.");
		}

		// Shutdown manager tasks and save all data
		if (this.enderChestManager != null) {
			this.getLogger().info("Saving all player data...");
			this.enderChestManager.shutdown();
			this.getLogger().info("All player data saved successfully.");
		}

		// Create a final backup before shutdown
		if (this.backupManager != null) {
			this.backupManager.createShutdownBackup();
		}

		// Close database connection pool
		if (this.storageManager != null) {
			this.storageManager.close();
		}

		this.getLogger().info("CustomEnderChest has been disabled successfully.");
	}

	public ConfigHandler config() {
		return configHandler;
	}

}