package org.maiminhdung.customenderchest;

import dev.faststats.ErrorTracker;
import dev.faststats.bukkit.BukkitContext;
import dev.faststats.data.Metric;
import lombok.Getter;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.maiminhdung.customenderchest.backup.BackupManager;
import org.maiminhdung.customenderchest.commands.CommandRegistrationManager;
import org.maiminhdung.customenderchest.commands.EnderChestCommand;
import org.maiminhdung.customenderchest.data.EnderChestManager;
import org.maiminhdung.customenderchest.data.MetricsDataProvider;
import org.maiminhdung.customenderchest.data.OverflowManager;
import org.maiminhdung.customenderchest.listeners.PlayerListener;
import org.maiminhdung.customenderchest.locale.LocaleManager;
import org.maiminhdung.customenderchest.storage.StorageManager;
import org.maiminhdung.customenderchest.update.ConfigUpdateManager;
import org.maiminhdung.customenderchest.update.UpdateManager;
import org.maiminhdung.customenderchest.utils.DataLockManager;
import org.maiminhdung.customenderchest.utils.DebugLogger;
import org.maiminhdung.customenderchest.utils.SoundHandler;
import org.maiminhdung.customenderchest.utils.VaultHandler;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class EnderChest extends JavaPlugin {

	private static volatile ErrorTracker errorTracker;

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
	private UpdateManager updateManager;
	@Getter
	private ConfigUpdateManager configUpdateManager;
	@Getter
	private CommandRegistrationManager commandRegistrationManager;
	@Getter
	private BackupManager backupManager;
	@Getter
	private PlayerListener playerListener;
	@Getter
	private MetricsDataProvider metricsDataProvider;
	@Getter
	private Metrics bStatsMetrics;
	@Getter
	private BukkitContext fastStatsContext;
	@Getter
	private VaultHandler vaultHandler;
	@Getter
	private OverflowManager overflowManager;

	@Override
	public void onEnable() {
		instance = this;

		// Initialize configuration. Remote schema updates are checked once per plugin version.
		this.configHandler = new ConfigHandler(this);
		this.configUpdateManager = new ConfigUpdateManager(this);
		if (config().getBoolean("general.faststats-metrics")) {
			try {
				errorTracker = ErrorTracker.contextAware(EnderChest.class.getClassLoader());
			} catch (Throwable error) {
				this.getLogger().log(Level.WARNING, "FastStats error tracking could not be initialized.", error);
			}
		}

		this.debugLogger = new DebugLogger(this);
		this.localeManager = new LocaleManager(this);
		this.soundHandler = new SoundHandler(this);
		this.dataLockManager = new DataLockManager();

		// Initialize Database Manager with HikariCP
		this.storageManager = new StorageManager(this);

		// Initialize the core logic manager
		this.enderChestManager = new EnderChestManager(this);

		// Initialize Vault economy handler (soft dependency)
		this.vaultHandler = new VaultHandler();
		if (vaultHandler.setupEconomy()) {
			this.getLogger().info("Vault economy hooked successfully.");
		} else {
			this.getLogger().info("Vault economy not found. Retrieval fees will be disabled.");
		}

		// Initialize Overflow Manager (expiration + fees)
		this.overflowManager = new OverflowManager(this);
		this.overflowManager.startExpirationTask();

		// Initialize Backup Manager
		this.backupManager = new BackupManager(this);
		this.backupManager.startAutoBackup();

		// Initialize the internal Modrinth update checker.
		if (config().getBoolean("general.update-checker")) {
			this.updateManager = new UpdateManager(this);
			this.updateManager.checkForUpdates();
			this.getLogger().info("Modrinth update checker is enabled.");
		}

		// Only contacts GitHub when config-version is older than this plugin version.
		this.configUpdateManager.updateIfRequired();

		// Register listeners and commands
		this.playerListener = new PlayerListener(this);
		this.getServer().getPluginManager().registerEvents(this.playerListener, this);
		// Register commands and tab completer
		EnderChestCommand commandExecutor = new EnderChestCommand(this);
		PluginCommand command = this.getCommand("customenderchest");
		if (command == null) {
			throw new IllegalStateException("Internal command 'customenderchest' is missing from plugin.yml");
		}
		command.setExecutor(commandExecutor);
		command.setTabCompleter(commandExecutor);
		this.commandRegistrationManager = new CommandRegistrationManager(this, command);
		if (!this.commandRegistrationManager.applyConfiguredCommands()) {
			this.getLogger().warning("Using the fallback /customenderchest command because configured commands could not be registered.");
		}

		// Bstats Metrics
		if (config().getBoolean("general.bstats-metrics")) {
			try {
				setupBtatsMetrics();
				this.getLogger().info("bStats Metrics are enabled. Thank you for your support!");
			} catch (Throwable error) {
				this.bStatsMetrics = null;
				this.getLogger().log(Level.WARNING, "bStats Metrics could not be initialized.", error);
			}
		} else {
			this.getLogger().info("bStats Metrics are disabled.");
		}

		// FastStats Metrics
		this.metricsDataProvider = new MetricsDataProvider(this);
		if (config().getBoolean("general.faststats-metrics") && errorTracker != null) {
			try {
				setupFastStatsMetrics();
				this.getLogger().info("FastStats Metrics are enabled.");
			} catch (Throwable error) {
				shutdownFastStats();
				this.getLogger().log(Level.WARNING, "FastStats Metrics could not be initialized.", error);
			}
		} else if (config().getBoolean("general.faststats-metrics")) {
			this.getLogger().warning("FastStats Metrics are unavailable because error tracking failed to initialize.");
		} else {
			this.getLogger().info("FastStats Metrics are disabled.");
		}

		this.getLogger().info("CustomEnderChest has been enabled successfully!");
	}

	private void setupBtatsMetrics() {
		this.bStatsMetrics = new Metrics(this, 26551);
		this.bStatsMetrics.addCustomChart(new SimplePie("language", () -> getConfig().getString("general.locale")));
		this.bStatsMetrics.addCustomChart(new SimplePie("storage_type", () -> getConfig().getString("storage.type")));
	}

	private void setupFastStatsMetrics() {
		this.fastStatsContext = new BukkitContext.Factory(
				this,
				"a83104b7f8415dbc03ffab67fe273bff"
		)
				.errorTrackerService(errorTracker)
				.metrics(factory -> factory
						.addMetric(Metric.string("locale", () -> getConfig().getString("general.locale")))
						.addMetric(Metric.string("storage_type", () -> getConfig().getString("storage.type")))
						.addMetric(Metric.number("loaded_chests", metricsDataProvider::getLoadedChests))
						.addMetric(Metric.number("open_chests", metricsDataProvider::getOpenChests))
						.addMetric(Metric.number("save_count", metricsDataProvider::getSaveCount))
						.addMetric(Metric.number("load_count", metricsDataProvider::getLoadCount))
						.addMetric(Metric.number("avg_save_time_ms", metricsDataProvider::getAvgSaveTimeMs))
						.create())
				.create();
		if (this.fastStatsContext.errorTrackerService().isEmpty()) {
			detachErrorTracker();
		}
		this.fastStatsContext.ready();
	}

	@Override
	public void onDisable() {
		this.getLogger().info("CustomEnderChest is shutting down...");

		// Stop automatic backup task first
		if (this.backupManager != null) {
			this.backupManager.stopAutoBackup();
			this.getLogger().info("Automatic backup task stopped.");
		}

		// Stop overflow expiration task
		if (this.overflowManager != null) {
			this.overflowManager.stopExpirationTask();
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

		if (this.bStatsMetrics != null) {
			try {
				this.bStatsMetrics.shutdown();
			} catch (Throwable error) {
				this.getLogger().log(Level.WARNING, "bStats did not shut down cleanly.", error);
			} finally {
				this.bStatsMetrics = null;
			}
		}

		// Submit final FastStats data after shutdown operations have completed.
		shutdownFastStats();

		this.getLogger().info("CustomEnderChest has been disabled successfully.");
	}

	private void shutdownFastStats() {
		if (this.fastStatsContext != null) {
			try {
				this.fastStatsContext.shutdown();
			} catch (Throwable error) {
				this.getLogger().log(Level.WARNING, "FastStats did not shut down cleanly.", error);
			} finally {
				this.fastStatsContext = null;
			}
		}
		detachErrorTracker();
	}

	private void detachErrorTracker() {
		if (errorTracker != null && errorTracker.isContextAttached()) {
			errorTracker.detachErrorContext();
		}
		errorTracker = null;
	}

	public boolean reloadRuntimeConfiguration() {
		config().reload();
		boolean localeReloaded = localeManager == null || localeManager.loadLocale();
		boolean commandsReloaded = commandRegistrationManager == null
				|| commandRegistrationManager.applyConfiguredCommands();
		if (debugLogger != null) {
			debugLogger.reload();
		}
		if (overflowManager != null) {
			overflowManager.reloadConfig();
		}
		return localeReloaded && commandsReloaded;
	}

	public String getPrimaryCommand() {
		return commandRegistrationManager != null
				? commandRegistrationManager.getPrimaryCommand()
				: "/customenderchest";
	}

	public static void trackError(Throwable throwable) {
		ErrorTracker tracker = errorTracker;
		if (tracker != null && throwable != null) {
			tracker.trackError(throwable);
		}
	}

	public ConfigHandler config() {
		return configHandler;
	}

}
