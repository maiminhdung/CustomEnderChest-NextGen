package org.maiminhdung.customenderchest.storage.migrate;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.Scheduler;
import org.maiminhdung.customenderchest.storage.StorageInterface;
import org.maiminhdung.customenderchest.utils.DataLockManager;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

public abstract class AbstractMigrator implements Migrator {
	protected final EnderChest plugin;
	protected final StorageInterface sourceStorage;
	protected final StorageInterface targetStorage;

	public AbstractMigrator(EnderChest plugin, StorageInterface sourceStorage, StorageInterface targetStorage) {
		this.plugin = plugin;
		this.sourceStorage = sourceStorage;
		this.targetStorage = targetStorage;
	}

	@Override
	public CompletableFuture<Void> migrate(CommandSender sender) {
		sendMsg(sender, plugin.getLocaleManager().getPrefixedComponent(
				"command.migrate-start",
				Placeholder.unparsed("source", getSourceName()),
				Placeholder.unparsed("target", getTargetName())));
		plugin.getLogger().info("Started migration from " + getSourceName() + " to " + getTargetName());

		return sourceStorage.getPlayersWithItems().thenComposeAsync(players -> {
			if (players == null || players.isEmpty()) {
				sendMsg(sender, plugin.getLocaleManager().getPrefixedComponent("command.migrate-no-data"));
				return CompletableFuture.completedFuture(null);
			}
			int total = players.size();
			AtomicInteger count = new AtomicInteger(0);
			AtomicInteger failed = new AtomicInteger(0);
			AtomicInteger skipped = new AtomicInteger(0);

			CompletableFuture<?> future = CompletableFuture.completedFuture(null);
			for (StorageInterface.PlayerDataInfo player : players) {
				future = future.thenCompose(v -> migratePlayer(player.playerUUID, player.playerName))
						.handle((res, ex) -> {
							if (ex != null) {
								failed.incrementAndGet();
								plugin.getLogger().warning(
										"Failed to migrate data for " + player.playerName + ": " + ex.getMessage());
							} else if (Boolean.FALSE.equals(res)) {
								skipped.incrementAndGet();
							} else {
								int current = count.incrementAndGet();
								if (current % 50 == 0 || current == total) {
									plugin.getLogger().info("Migration progress: " + current + "/" + total + "...");
								}
							}
							return null;
						});
			}
			return future.thenAccept(v -> {
				int skippedCount = skipped.get();
				sendMsg(sender, plugin.getLocaleManager().getPrefixedComponent(
						"command.migrate-success",
						Placeholder.unparsed("success", String.valueOf(count.get())),
						Placeholder.unparsed("total", String.valueOf(total)),
						Placeholder.unparsed("failed", String.valueOf(failed.get()))));
				sendMsg(sender, plugin.getLocaleManager().getPrefixedComponent("command.migrate-note"));
				plugin.getLogger().info("Migration finished. Success: " + count.get()
						+ ", Failed: " + failed.get()
						+ ", Skipped: " + skippedCount);
			});
		});
	}

	/**
	 * Migrates every vault (chest + overflow) of a single player from source to
	 * target storage. Returns Boolean.TRUE on success, Boolean.FALSE if skipped.
	 */
	private CompletableFuture<Boolean> migratePlayer(java.util.UUID uuid, String name) {
		DataLockManager lockManager = plugin.getDataLockManager();
		if (!lockManager.tryLock(uuid)) {
			plugin.getLogger().warning(
					"Skipping migration for " + name + " (" + uuid + ") as their data is currently locked/busy.");
			return CompletableFuture.completedFuture(Boolean.FALSE);
		}

		// Auto-save online cached vaults to sourceStorage before reading so the
		// migration copies the latest live data.
		// IMPORTANT: Save directly to sourceStorage, NOT the main plugin storage
		CompletableFuture<Void> prepareFuture = CompletableFuture.completedFuture(null);
		Player player = Bukkit.getPlayer(uuid);
		if (player != null && player.isOnline()) {
			Map<Integer, Inventory> cachedVaults = plugin.getEnderChestManager().getCachedVaults(uuid);
			for (Map.Entry<Integer, Inventory> entry : cachedVaults.entrySet()) {
				Inventory cachedInv = entry.getValue();
				prepareFuture = prepareFuture.thenCompose(v -> sourceStorage.saveVault(
						uuid, name, entry.getKey(), cachedInv.getSize(), cachedInv.getContents()));
			}
			// If cache is empty, skip auto-save - sourceStorage already has the latest data on disk
		}

		CompletableFuture<Boolean> migration = prepareFuture
				.thenCompose(v -> sourceStorage.getVaultIds(uuid))
				.thenCompose(vaultIds -> {
					if (vaultIds == null || vaultIds.isEmpty()) {
						plugin.getLogger().info("No data to migrate for " + name + " (" + uuid + "), skipping.");
						return CompletableFuture.completedFuture(Boolean.FALSE);
					}
					CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
					boolean[] migratedAny = { false };
					for (int vaultId : vaultIds) {
						chain = chain.thenCompose(ignored -> sourceStorage.loadVault(uuid, vaultId)
								.thenCompose(vaultData -> {
									if (vaultData == null || vaultData.items == null || vaultData.items.length == 0) {
										return CompletableFuture.<Void>completedFuture(null);
									}
									migratedAny[0] = true;
									return targetStorage.saveVault(uuid, name, vaultId, vaultData.size, vaultData.items)
											.thenCompose(v2 -> sourceStorage.loadOverflowItems(uuid, vaultId))
											.thenCompose(overflow -> {
												if (overflow == null || overflow.length == 0)
													return CompletableFuture.<Void>completedFuture(null);
												return targetStorage.saveOverflowItems(uuid, vaultId, overflow);
											});
								}));
					}
					return chain.thenApply(ignored -> Boolean.valueOf(migratedAny[0]));
				});

		return migration.whenComplete((res, ex) -> lockManager.unlock(uuid));
	}

	private void sendMsg(CommandSender sender, Component msg) {
		Scheduler.runTask(() -> {
			sender.sendMessage(msg);
		});
	}
}
