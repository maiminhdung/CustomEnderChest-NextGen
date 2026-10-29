package org.maiminhdung.customenderchest.storage.impl;

import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.data.ItemSerializer;
import org.maiminhdung.customenderchest.storage.StorageInterface;
import org.maiminhdung.customenderchest.storage.StorageManager;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * H2 backend with multi-vault tables:
 *
 * <pre>
 * &lt;table&gt;              - legacy pre-multi-vault rows (kept for migration)
 * &lt;table&gt;_overflow     - legacy overflow rows (kept for migration)
 * &lt;table&gt;_vaults       - per-vault chest rows, PK (player_uuid, vault_id)
 * &lt;table&gt;_vaults_overflow - per-vault overflow rows, PK (player_uuid, vault_id)
 * </pre>
 *
 * Legacy rows are migrated into vault 1 automatically the first time vault 1
 * is loaded; the legacy rows are deleted only after the vault rows are in
 * place, making the migration idempotent and crash-safe.
 */
public class H2Storage implements StorageInterface {

	private final StorageManager storageManager;
	private final String tableName;
	private final ExecutorService ioExecutor;

	// Regex pattern for valid SQL table names (alphanumeric and underscores only)
	private static final java.util.regex.Pattern VALID_TABLE_NAME = java.util.regex.Pattern.compile("^[a-zA-Z0-9_]+$");

	public H2Storage(StorageManager storageManager) {
		this.storageManager = storageManager;
		this.ioExecutor = storageManager.getIoExecutor();
		String configTableName = EnderChest.getInstance().config().getString("storage.table_name", "custom_enderchests");

		// Validate table name to prevent SQL injection
		if (!VALID_TABLE_NAME.matcher(configTableName).matches()) {
			EnderChest.getInstance().getLogger().severe("[H2Storage] Invalid table name in config: '" + configTableName +
					"'. Using default 'custom_enderchests'. Table names must only contain letters, numbers, and underscores.");
			this.tableName = "custom_enderchests";
		} else {
			this.tableName = configTableName;
		}
	}

	private String vaultsTable() {
		return tableName + "_vaults";
	}

	private String vaultsOverflowTable() {
		return tableName + "_vaults_overflow";
	}

	@Override
	public void init() {
		// Legacy tables (kept so pre-multi-vault data can be migrated on load)
		String legacySql = "CREATE TABLE IF NOT EXISTS " + tableName + " (" +
				"player_uuid VARCHAR(36) NOT NULL PRIMARY KEY," +
				"player_name VARCHAR(16)," +
				"chest_size INT NOT NULL," +
				"chest_data LONGTEXT," +
				"last_seen BIGINT NOT NULL" +
				")";
		executeInit(legacySql, "Failed to initialize H2 legacy table!");

		String legacyOverflowSql = "CREATE TABLE IF NOT EXISTS " + tableName + "_overflow (" +
				"player_uuid VARCHAR(36) NOT NULL PRIMARY KEY," +
				"overflow_data LONGTEXT," +
				"created_at BIGINT NOT NULL" +
				")";
		executeInit(legacyOverflowSql, "Failed to initialize legacy overflow table!");

		// Multi-vault tables
		String vaultsSql = "CREATE TABLE IF NOT EXISTS " + vaultsTable() + " (" +
				"player_uuid VARCHAR(36) NOT NULL," +
				"vault_id INT NOT NULL," +
				"player_name VARCHAR(16)," +
				"chest_size INT NOT NULL," +
				"chest_data LONGTEXT," +
				"last_seen BIGINT NOT NULL," +
				"PRIMARY KEY (player_uuid, vault_id)" +
				")";
		executeInit(vaultsSql, "Failed to initialize vaults table!");

		String vaultsOverflowSql = "CREATE TABLE IF NOT EXISTS " + vaultsOverflowTable() + " (" +
				"player_uuid VARCHAR(36) NOT NULL," +
				"vault_id INT NOT NULL," +
				"overflow_data LONGTEXT," +
				"created_at BIGINT NOT NULL," +
				"PRIMARY KEY (player_uuid, vault_id)" +
				")";
		executeInit(vaultsOverflowSql, "Failed to initialize vaults overflow table!");
	}

	private void executeInit(String sql, String errorMessage) {
		try (Connection conn = storageManager.getConnection();
				PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.executeUpdate();
		} catch (Exception e) {
			EnderChest.getInstance().getLogger().severe(errorMessage);
			e.printStackTrace();
			EnderChest.trackError(e);
		}
	}

	@Override
	public CompletableFuture<VaultData> loadVault(UUID playerUUID, int vaultId) {
		return CompletableFuture.supplyAsync(() -> {
			String sql = "SELECT chest_size, chest_data FROM " + vaultsTable()
					+ " WHERE player_uuid = ? AND vault_id = ?";
			try (Connection conn = storageManager.getConnection()) {
				if (vaultId == 1) {
					migrateLegacyIfRequired(conn, playerUUID);
				}
				try (PreparedStatement ps = conn.prepareStatement(sql)) {
					ps.setQueryTimeout(10);
					ps.setString(1, playerUUID.toString());
					ps.setInt(2, vaultId);
					try (ResultSet rs = ps.executeQuery()) {
						if (rs.next()) {
							int size = rs.getInt("chest_size");
							String data = rs.getString("chest_data");
							if (data == null || data.isEmpty()) {
								return new VaultData(size, new ItemStack[Math.max(size, 0)]);
							}

							ItemStack[] items = ItemSerializer.fromBase64(data);

							// Auto-save migrated data in new format
							if (items.length > 0) {
								try {
									String newData = ItemSerializer.toBase64(items);
									if (!newData.equals(data)) {
										EnderChest.getInstance().getLogger().info(
												"[Migration] Auto-saving migrated data for player " + playerUUID);
										autoSaveMigratedData(conn, playerUUID, vaultId, newData);
									}
								} catch (Exception e) {
									// Ignore save errors, data is already loaded successfully
								}
							}

							return new VaultData(size, items);
						}
					}
				}
			} catch (Exception e) {
				String errorMsg = e.getMessage();
				if (errorMsg != null && (errorMsg.contains("corrupted") || errorMsg.contains("MVStoreException"))) {
					EnderChest.getInstance().getLogger().severe(
							"[H2Storage] Database file appears to be corrupted! Player: " + playerUUID +
									". Consider restoring from backup or deleting the database file to recreate.");
				} else {
					EnderChest.getInstance().getLogger().severe(
							"[H2Storage] Failed to load vault " + vaultId + " for " + playerUUID + ": " + errorMsg);
				}
				if (EnderChest.getInstance().config().getBoolean("general.debug")) {
					e.printStackTrace();
				}
				EnderChest.trackError(e);
				throw new java.util.concurrent.CompletionException(e);
			}
			return null;
		}, ioExecutor);
	}

	/**
	 * Moves pre-multi-vault rows of the player into vault 1 and removes them.
	 * Uses upserts so concurrent or interrupted runs stay idempotent; legacy
	 * rows are deleted only after the vault rows were written.
	 */
	private void migrateLegacyIfRequired(Connection conn, UUID playerUUID) {
		try {
			String legacyName = null;
			int legacySize = 0;
			String legacyData = null;
			boolean hasLegacy = false;
			try (PreparedStatement ps = conn.prepareStatement(
					"SELECT player_name, chest_size, chest_data FROM " + tableName + " WHERE player_uuid = ?")) {
				ps.setQueryTimeout(10);
				ps.setString(1, playerUUID.toString());
				try (ResultSet rs = ps.executeQuery()) {
					if (rs.next()) {
						hasLegacy = true;
						legacyName = rs.getString("player_name");
						legacySize = rs.getInt("chest_size");
						legacyData = rs.getString("chest_data");
					}
				}
			}

			String legacyOverflowData = null;
			long legacyOverflowCreated = 0;
			boolean hasLegacyOverflow = false;
			try (PreparedStatement ps = conn.prepareStatement(
					"SELECT overflow_data, created_at FROM " + tableName + "_overflow WHERE player_uuid = ?")) {
				ps.setQueryTimeout(10);
				ps.setString(1, playerUUID.toString());
				try (ResultSet rs = ps.executeQuery()) {
					if (rs.next()) {
						hasLegacyOverflow = true;
						legacyOverflowData = rs.getString("overflow_data");
						legacyOverflowCreated = rs.getLong("created_at");
					}
				}
			}

			if (hasLegacy) {
				upsertVault(conn, playerUUID, legacyName != null ? legacyName : "Unknown", 1, legacySize, legacyData);
			}
			if (hasLegacyOverflow) {
				insertOverflowIfAbsent(conn, playerUUID, 1, legacyOverflowData, legacyOverflowCreated);
			}
			if (hasLegacy || hasLegacyOverflow) {
				try (PreparedStatement ps = conn.prepareStatement(
						"DELETE FROM " + tableName + " WHERE player_uuid = ?")) {
					ps.setString(1, playerUUID.toString());
					ps.executeUpdate();
				}
				try (PreparedStatement ps = conn.prepareStatement(
						"DELETE FROM " + tableName + "_overflow WHERE player_uuid = ?")) {
					ps.setString(1, playerUUID.toString());
					ps.executeUpdate();
				}
				EnderChest.getInstance().getLogger().info(
						"[H2Storage] Migrated legacy data into vault 1 for " + playerUUID);
			}
		} catch (Exception e) {
			EnderChest.getInstance().getLogger().warning(
					"[H2Storage] Legacy migration check failed for " + playerUUID + ": " + e.getMessage());
		}
	}

	private void upsertVault(Connection conn, UUID playerUUID, String playerName, int vaultId, int size, String data)
			throws java.sql.SQLException {
		String sql = "MERGE INTO " + vaultsTable()
				+ " (player_uuid, vault_id, player_name, chest_size, chest_data, last_seen) "
				+ "KEY(player_uuid, vault_id) VALUES(?, ?, ?, ?, ?, ?)";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setQueryTimeout(10);
			ps.setString(1, playerUUID.toString());
			ps.setInt(2, vaultId);
			ps.setString(3, playerName);
			ps.setInt(4, size);
			ps.setString(5, data);
			ps.setLong(6, System.currentTimeMillis());
			ps.executeUpdate();
		}
	}

	private void insertOverflowIfAbsent(Connection conn, UUID playerUUID, int vaultId, String data, long createdAt)
			throws java.sql.SQLException {
		String sql = "MERGE INTO " + vaultsOverflowTable()
				+ " (player_uuid, vault_id, overflow_data, created_at) KEY(player_uuid, vault_id) VALUES(?, ?, ?, ?)";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setQueryTimeout(10);
			ps.setString(1, playerUUID.toString());
			ps.setInt(2, vaultId);
			ps.setString(3, data);
			ps.setLong(4, createdAt > 0 ? createdAt : System.currentTimeMillis());
			ps.executeUpdate();
		}
	}

	private void autoSaveMigratedData(Connection conn, UUID playerUUID, int vaultId, String newData) {
		CompletableFuture.runAsync(() -> {
			try (Connection asyncConn = storageManager.getConnection();
					PreparedStatement ps = asyncConn.prepareStatement(
							"UPDATE " + vaultsTable() + " SET chest_data = ? WHERE player_uuid = ? AND vault_id = ?")) {
				ps.setString(1, newData);
				ps.setString(2, playerUUID.toString());
				ps.setInt(3, vaultId);
				ps.executeUpdate();
			} catch (Exception e) {
				EnderChest.getInstance().getLogger().warning("Failed to auto-save migrated data: " + e.getMessage());
			}
		}, ioExecutor);
	}

	@Override
	public CompletableFuture<Void> saveVault(UUID playerUUID, String playerName, int vaultId, int size, ItemStack[] items) {
		return CompletableFuture.runAsync(() -> {
			try (Connection conn = storageManager.getConnection()) {
				String data = ItemSerializer.toBase64(items);
				upsertVault(conn, playerUUID, playerName, vaultId, size, data);
			} catch (Exception e) {
				EnderChest.getInstance().getLogger().severe(
						"[H2Storage] Failed to save vault " + vaultId + " for " + playerName
								+ " (" + playerUUID + "): " + e.getMessage());
				EnderChest.trackError(e);
				throw new RuntimeException("Failed to save vault data", e);
			}
		}, ioExecutor);
	}

	@Override
	public CompletableFuture<Void> deleteVault(UUID playerUUID, int vaultId) {
		return CompletableFuture.runAsync(() -> {
			try (Connection conn = storageManager.getConnection();
					PreparedStatement ps = conn.prepareStatement(
							"DELETE FROM " + vaultsTable() + " WHERE player_uuid = ? AND vault_id = ?")) {
				ps.setString(1, playerUUID.toString());
				ps.setInt(2, vaultId);
				ps.executeUpdate();
			} catch (Exception e) {
				EnderChest.getInstance().getLogger().warning(
						"[H2Storage] Failed to delete vault " + vaultId + " for " + playerUUID + ": " + e.getMessage());
			}
			try (Connection conn = storageManager.getConnection();
					PreparedStatement ps = conn.prepareStatement(
							"DELETE FROM " + vaultsOverflowTable() + " WHERE player_uuid = ? AND vault_id = ?")) {
				ps.setString(1, playerUUID.toString());
				ps.setInt(2, vaultId);
				ps.executeUpdate();
			} catch (Exception e) {
				EnderChest.getInstance().getLogger().warning(
						"[H2Storage] Failed to delete vault overflow " + vaultId + " for " + playerUUID + ": " + e.getMessage());
			}
		}, ioExecutor);
	}

	@Override
	public CompletableFuture<List<Integer>> getVaultIds(UUID playerUUID) {
		return CompletableFuture.supplyAsync(() -> {
			TreeSet<Integer> ids = new TreeSet<>();
			try (Connection conn = storageManager.getConnection();
					PreparedStatement ps = conn.prepareStatement(
							"SELECT vault_id FROM " + vaultsTable() + " WHERE player_uuid = ?")) {
				ps.setQueryTimeout(10);
				ps.setString(1, playerUUID.toString());
				try (ResultSet rs = ps.executeQuery()) {
					while (rs.next()) {
						ids.add(rs.getInt("vault_id"));
					}
				}
			} catch (Exception e) {
				EnderChest.getInstance().getLogger().warning(
						"[H2Storage] Failed to list vault ids for " + playerUUID + ": " + e.getMessage());
			}
			// Legacy rows count as vault 1
			if (!ids.contains(1)) {
				try (Connection conn = storageManager.getConnection();
						PreparedStatement ps = conn.prepareStatement(
								"SELECT 1 FROM " + tableName + " WHERE player_uuid = ? LIMIT 1")) {
					ps.setString(1, playerUUID.toString());
					try (ResultSet rs = ps.executeQuery()) {
						if (rs.next()) {
							ids.add(1);
						}
					}
				} catch (Exception ignored) {
				}
			}
			return new ArrayList<>(ids);
		}, ioExecutor);
	}

	@Override
	public CompletableFuture<Void> saveOverflowItems(UUID playerUUID, int vaultId, ItemStack[] items) {
		return CompletableFuture.runAsync(() -> {
			try (Connection conn = storageManager.getConnection()) {
				String data = ItemSerializer.toBase64(items);

				// Try UPDATE first to preserve original created_at timestamp
				String updateSql = "UPDATE " + vaultsOverflowTable()
						+ " SET overflow_data = ? WHERE player_uuid = ? AND vault_id = ?";
				int updated;
				try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
					ps.setQueryTimeout(10);
					ps.setString(1, data);
					ps.setString(2, playerUUID.toString());
					ps.setInt(3, vaultId);
					updated = ps.executeUpdate();
				}

				if (updated == 0) {
					// New entry — insert with current timestamp
					String insertSql = "INSERT INTO " + vaultsOverflowTable()
							+ " (player_uuid, vault_id, overflow_data, created_at) VALUES(?, ?, ?, ?)";
					try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
						ps.setQueryTimeout(10);
						ps.setString(1, playerUUID.toString());
						ps.setInt(2, vaultId);
						ps.setString(3, data);
						ps.setLong(4, System.currentTimeMillis());
						ps.executeUpdate();
					}
				}
			} catch (Exception e) {
				EnderChest.getInstance().getLogger().severe(
						"[H2Storage] Failed to save overflow items (vault " + vaultId + ") for " + playerUUID);
				EnderChest.trackError(e);
				throw new RuntimeException("Failed to save overflow items", e);
			}
		}, ioExecutor);
	}

	@Override
	public CompletableFuture<ItemStack[]> loadOverflowItems(UUID playerUUID, int vaultId) {
		return CompletableFuture.supplyAsync(() -> {
			String sql = "SELECT overflow_data FROM " + vaultsOverflowTable()
					+ " WHERE player_uuid = ? AND vault_id = ?";
			try (Connection conn = storageManager.getConnection();
					PreparedStatement ps = conn.prepareStatement(sql)) {
				ps.setQueryTimeout(10);
				ps.setString(1, playerUUID.toString());
				ps.setInt(2, vaultId);
				try (ResultSet rs = ps.executeQuery()) {
					if (rs.next()) {
						String data = rs.getString("overflow_data");
						return ItemSerializer.fromBase64(data);
					}
				}
			} catch (Exception e) {
				EnderChest.getInstance().getLogger().severe(
						"[H2Storage] Failed to load overflow (vault " + vaultId + ") for " + playerUUID + ": "
								+ e.getMessage());
				EnderChest.trackError(e);
				throw new java.util.concurrent.CompletionException(e);
			}
			return null;
		}, ioExecutor);
	}

	@Override
	public CompletableFuture<Void> clearOverflowItems(UUID playerUUID, int vaultId) {
		return CompletableFuture.runAsync(() -> {
			String sql = "DELETE FROM " + vaultsOverflowTable() + " WHERE player_uuid = ? AND vault_id = ?";
			try (Connection conn = storageManager.getConnection();
					PreparedStatement ps = conn.prepareStatement(sql)) {
				ps.setQueryTimeout(10);
				ps.setString(1, playerUUID.toString());
				ps.setInt(2, vaultId);
				ps.executeUpdate();
			} catch (Exception e) {
				EnderChest.getInstance().getLogger().warning(
						"[H2Storage] Failed to clear overflow (vault " + vaultId + ") for " + playerUUID + ": "
								+ e.getMessage());
			}
		}, ioExecutor);
	}

	@Override
	public CompletableFuture<Boolean> hasOverflowItems(UUID playerUUID, int vaultId) {
		return CompletableFuture.supplyAsync(() -> {
			String sql = "SELECT COUNT(*) FROM " + vaultsOverflowTable()
					+ " WHERE player_uuid = ? AND vault_id = ? AND overflow_data IS NOT NULL";
			try (Connection conn = storageManager.getConnection();
					PreparedStatement ps = conn.prepareStatement(sql)) {
				ps.setQueryTimeout(10);
				ps.setString(1, playerUUID.toString());
				ps.setInt(2, vaultId);
				try (ResultSet rs = ps.executeQuery()) {
					if (rs.next()) {
						return rs.getInt(1) > 0;
					}
				}
			} catch (Exception e) {
				EnderChest.getInstance().getLogger().warning(
						"[H2Storage] Failed to check overflow (vault " + vaultId + ") for " + playerUUID + ": "
								+ e.getMessage());
			}
			return false;
		}, ioExecutor);
	}

	@Override
	public CompletableFuture<Long> getOverflowCreatedAt(UUID playerUUID, int vaultId) {
		return CompletableFuture.supplyAsync(() -> {
			String sql = "SELECT created_at FROM " + vaultsOverflowTable()
					+ " WHERE player_uuid = ? AND vault_id = ?";
			try (Connection conn = storageManager.getConnection();
					PreparedStatement ps = conn.prepareStatement(sql)) {
				ps.setQueryTimeout(10);
				ps.setString(1, playerUUID.toString());
				ps.setInt(2, vaultId);
				try (ResultSet rs = ps.executeQuery()) {
					if (rs.next()) {
						return rs.getLong("created_at");
					}
				}
			} catch (Exception e) {
				EnderChest.getInstance().getLogger().warning(
						"[H2Storage] Failed to get overflow created_at (vault " + vaultId + ") for " + playerUUID + ": "
								+ e.getMessage());
			}
			return null;
		}, ioExecutor);
	}

	@Override
	public CompletableFuture<Integer> migrateLegacyData() {
		return CompletableFuture.supplyAsync(() -> {
			List<UUID> legacyPlayers = new ArrayList<>();
			try (Connection conn = storageManager.getConnection()) {
				try (PreparedStatement ps = conn.prepareStatement(
						"SELECT player_uuid FROM " + tableName
								+ " UNION SELECT player_uuid FROM " + tableName + "_overflow")) {
					ps.setQueryTimeout(60);
					try (ResultSet rs = ps.executeQuery()) {
						while (rs.next()) {
							legacyPlayers.add(UUID.fromString(rs.getString(1)));
						}
					}
				}
				int migrated = 0;
				for (UUID uuid : legacyPlayers) {
					migrateLegacyIfRequired(conn, uuid);
					migrated++;
				}
				return migrated;
			} catch (Exception e) {
				EnderChest.getInstance().getLogger()
						.warning("[H2Storage] Bulk 2.1.x migration failed: " + e.getMessage());
				return 0;
			}
		}, ioExecutor);
	}

	@Override
	public CompletableFuture<Void> deleteEnderChest(UUID playerUUID) {
		return CompletableFuture.runAsync(() -> {
			try (Connection conn = storageManager.getConnection();
					PreparedStatement ps = conn.prepareStatement(
							"DELETE FROM " + tableName + " WHERE player_uuid = ?")) {
				ps.setString(1, playerUUID.toString());
				ps.executeUpdate();
			} catch (Exception e) {
				e.printStackTrace();
				EnderChest.trackError(e);
			}
			try (Connection conn = storageManager.getConnection();
					PreparedStatement ps = conn.prepareStatement(
							"DELETE FROM " + tableName + "_overflow WHERE player_uuid = ?")) {
				ps.setString(1, playerUUID.toString());
				ps.executeUpdate();
			} catch (Exception e) {
				e.printStackTrace();
				EnderChest.trackError(e);
			}
		}, ioExecutor);
	}

	@Override
	public CompletableFuture<String> getPlayerName(UUID playerUUID) {
		return CompletableFuture.supplyAsync(() -> {
			try (Connection conn = storageManager.getConnection();
					PreparedStatement ps = conn.prepareStatement(
							"SELECT player_name FROM " + vaultsTable() + " WHERE player_uuid = ? LIMIT 1")) {
				ps.setQueryTimeout(10);
				ps.setString(1, playerUUID.toString());
				try (ResultSet rs = ps.executeQuery()) {
					if (rs.next()) {
						return rs.getString("player_name");
					}
				}
			} catch (Exception ignored) {
			}
			// Legacy fallback
			try (Connection conn = storageManager.getConnection();
					PreparedStatement ps = conn.prepareStatement(
							"SELECT player_name FROM " + tableName + " WHERE player_uuid = ?")) {
				ps.setQueryTimeout(10);
				ps.setString(1, playerUUID.toString());
				try (ResultSet rs = ps.executeQuery()) {
					if (rs.next()) {
						return rs.getString("player_name");
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
				EnderChest.trackError(e);
			}
			return null;
		}, ioExecutor);
	}

	@Override
	public CompletableFuture<UUID> findUUIDByName(String playerName) {
		return CompletableFuture.supplyAsync(() -> {
			// Case-insensitive search, most recently seen first (vaults, then legacy)
			try (Connection conn = storageManager.getConnection();
					PreparedStatement ps = conn.prepareStatement(
							"SELECT player_uuid FROM " + vaultsTable()
									+ " WHERE LOWER(player_name) = LOWER(?) ORDER BY last_seen DESC LIMIT 1")) {
				ps.setQueryTimeout(10);
				ps.setString(1, playerName);
				try (ResultSet rs = ps.executeQuery()) {
					if (rs.next()) {
						return UUID.fromString(rs.getString("player_uuid"));
					}
				}
			} catch (Exception ignored) {
			}
			try (Connection conn = storageManager.getConnection();
					PreparedStatement ps = conn.prepareStatement(
							"SELECT player_uuid FROM " + tableName
									+ " WHERE LOWER(player_name) = LOWER(?) ORDER BY last_seen DESC LIMIT 1")) {
				ps.setQueryTimeout(10);
				ps.setString(1, playerName);
				try (ResultSet rs = ps.executeQuery()) {
					if (rs.next()) {
						return UUID.fromString(rs.getString("player_uuid"));
					}
				}
			} catch (Exception e) {
				EnderChest.getInstance().getLogger().warning(
						"[H2Storage] Failed to find UUID by name for " + playerName + ": " + e.getMessage());
			}
			return null;
		}, ioExecutor);
	}

	@Override
	public CompletableFuture<Boolean> hasData(UUID playerUUID) {
		return CompletableFuture.supplyAsync(() -> {
			try (Connection conn = storageManager.getConnection();
					PreparedStatement ps = conn.prepareStatement(
							"SELECT 1 FROM " + vaultsTable() + " WHERE player_uuid = ? LIMIT 1")) {
				ps.setQueryTimeout(10);
				ps.setString(1, playerUUID.toString());
				try (ResultSet rs = ps.executeQuery()) {
					if (rs.next()) {
						return true;
					}
				}
			} catch (Exception ignored) {
			}
			try (Connection conn = storageManager.getConnection();
					PreparedStatement ps = conn.prepareStatement(
							"SELECT 1 FROM " + tableName + " WHERE player_uuid = ? LIMIT 1")) {
				ps.setQueryTimeout(10);
				ps.setString(1, playerUUID.toString());
				try (ResultSet rs = ps.executeQuery()) {
					return rs.next();
				}
			} catch (Exception e) {
				EnderChest.getInstance().getLogger().warning(
						"[H2Storage] Failed to check data existence for " + playerUUID + ": " + e.getMessage());
			}
			return false;
		}, ioExecutor);
	}

	@Override
	public CompletableFuture<StorageStats> getStorageStats() {
		return CompletableFuture.supplyAsync(() -> {
			int totalPlayers = 0;
			int playersWithItems = 0;
			int totalItems = 0;
			int totalOverflowPlayers = 0;
			int totalOverflowItems = 0;
			long totalDataSize = 0;

			// Player / item aggregates across vaults and legacy rows
			String countSql = "SELECT COUNT(*) AS total, " +
					"SUM(CASE WHEN chest_data IS NOT NULL AND chest_data != '' THEN 1 ELSE 0 END) AS with_items, " +
					"SUM(COALESCE(LENGTH(chest_data), 0)) AS data_size " +
					"FROM (SELECT player_uuid, chest_data FROM " + vaultsTable() +
					" UNION ALL SELECT player_uuid, chest_data FROM " + tableName + ")";
			try (Connection conn = storageManager.getConnection();
					PreparedStatement ps = conn.prepareStatement(countSql)) {
				ps.setQueryTimeout(30);
				try (ResultSet rs = ps.executeQuery()) {
					if (rs.next()) {
						totalPlayers = rs.getInt("total");
						playersWithItems = rs.getInt("with_items");
						totalDataSize = rs.getLong("data_size");
					}
				}
			} catch (Exception e) {
				EnderChest.getInstance().getLogger()
						.warning("[H2Storage] Failed to get player counts: " + e.getMessage());
			}

			String itemsSql = "SELECT chest_data FROM " + vaultsTable() + " WHERE chest_data IS NOT NULL AND chest_data != '' "
					+ "UNION ALL SELECT chest_data FROM " + tableName + " WHERE chest_data IS NOT NULL AND chest_data != ''";
			totalItems += countItems(itemsSql);

			String overflowCountSql = "SELECT COUNT(*) FROM " + vaultsOverflowTable() + " WHERE overflow_data IS NOT NULL";
			totalOverflowPlayers += countRows(overflowCountSql);

			String overflowItemsSql = "SELECT overflow_data FROM " + vaultsOverflowTable()
					+ " WHERE overflow_data IS NOT NULL";
			totalOverflowItems += countItems(overflowItemsSql);

			return new StorageStats(totalPlayers, playersWithItems, totalItems,
					totalOverflowPlayers, totalOverflowItems, totalDataSize);
		}, ioExecutor);
	}

	private int countItems(String unionSql) {
		int total = 0;
		try (Connection conn = storageManager.getConnection();
				PreparedStatement ps = conn.prepareStatement(unionSql)) {
			ps.setQueryTimeout(60);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					String data = rs.getString(1);
					try {
						ItemStack[] items = ItemSerializer.fromBase64(data);
						if (items != null) {
							for (ItemStack item : items) {
								if (item != null && !item.getType().isAir()) {
									total++;
								}
							}
						}
					} catch (Exception ignored) {
						// Skip corrupted data
					}
				}
			}
		} catch (Exception e) {
			EnderChest.getInstance().getLogger()
					.warning("[H2Storage] Failed to count items: " + e.getMessage());
		}
		return total;
	}

	private int countRows(String sql) {
		try (Connection conn = storageManager.getConnection();
				PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setQueryTimeout(30);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					return rs.getInt(1);
				}
			}
		} catch (Exception e) {
			EnderChest.getInstance().getLogger()
					.warning("[H2Storage] Failed to count rows: " + e.getMessage());
		}
		return 0;
	}

	@Override
	public CompletableFuture<List<PlayerDataInfo>> getPlayersWithItems() {
		return CompletableFuture.supplyAsync(() -> {
			java.util.Map<UUID, MutableInfo> byUuid = new TreeMap<>();

			// Vault rows
			String vaultsSql = "SELECT player_uuid, vault_id, player_name, chest_size, chest_data FROM " + vaultsTable();
			try (Connection conn = storageManager.getConnection();
					PreparedStatement ps = conn.prepareStatement(vaultsSql)) {
				ps.setQueryTimeout(60);
				try (ResultSet rs = ps.executeQuery()) {
					while (rs.next()) {
						UUID uuid = UUID.fromString(rs.getString("player_uuid"));
						int vaultId = rs.getInt("vault_id");
						MutableInfo info = byUuid.computeIfAbsent(uuid, u -> new MutableInfo());
						if (info.name == null) {
							info.name = rs.getString("player_name");
						}
						info.maxSize = Math.max(info.maxSize, rs.getInt("chest_size"));
						info.vaultIds.add(vaultId);
						accumulateItems(info, rs.getString("chest_data"));
					}
				}
			} catch (Exception e) {
				EnderChest.getInstance().getLogger()
						.warning("[H2Storage] Failed to read vaults table: " + e.getMessage());
			}

			// Vault overflow rows
			String overflowSql = "SELECT player_uuid, vault_id FROM " + vaultsOverflowTable();
			try (Connection conn = storageManager.getConnection();
					PreparedStatement ps = conn.prepareStatement(overflowSql)) {
				ps.setQueryTimeout(30);
				try (ResultSet rs = ps.executeQuery()) {
					while (rs.next()) {
						UUID uuid = UUID.fromString(rs.getString("player_uuid"));
						MutableInfo info = byUuid.computeIfAbsent(uuid, u -> new MutableInfo());
						info.vaultIds.add(rs.getInt("vault_id"));
						info.hasOverflow = true;
					}
				}
			} catch (Exception ignored) {
			}

			// Legacy rows (count as vault 1)
			String legacySql = "SELECT player_uuid, player_name, chest_size, chest_data FROM " + tableName;
			try (Connection conn = storageManager.getConnection();
					PreparedStatement ps = conn.prepareStatement(legacySql)) {
				ps.setQueryTimeout(60);
				try (ResultSet rs = ps.executeQuery()) {
					while (rs.next()) {
						UUID uuid = UUID.fromString(rs.getString("player_uuid"));
						MutableInfo info = byUuid.computeIfAbsent(uuid, u -> new MutableInfo());
						if (info.name == null) {
							info.name = rs.getString("player_name");
						}
						info.maxSize = Math.max(info.maxSize, rs.getInt("chest_size"));
						info.vaultIds.add(1);
						accumulateItems(info, rs.getString("chest_data"));
					}
				}
			} catch (Exception e) {
				EnderChest.getInstance().getLogger()
						.warning("[H2Storage] Failed to read legacy table: " + e.getMessage());
			}

			// Legacy overflow rows
			String legacyOverflowSql = "SELECT player_uuid FROM " + tableName + "_overflow";
			try (Connection conn = storageManager.getConnection();
					PreparedStatement ps = conn.prepareStatement(legacyOverflowSql)) {
				ps.setQueryTimeout(30);
				try (ResultSet rs = ps.executeQuery()) {
					while (rs.next()) {
						UUID uuid = UUID.fromString(rs.getString("player_uuid"));
						MutableInfo info = byUuid.computeIfAbsent(uuid, u -> new MutableInfo());
						info.vaultIds.add(1);
						info.hasOverflow = true;
					}
				}
			} catch (Exception ignored) {
			}

			List<PlayerDataInfo> result = new ArrayList<>();
			for (java.util.Map.Entry<UUID, MutableInfo> entry : byUuid.entrySet()) {
				MutableInfo info = entry.getValue();
				result.add(new PlayerDataInfo(entry.getKey(),
						info.name != null ? info.name : "Unknown", info.maxSize, info.itemCount,
						info.hasOverflow, info.corrupted, info.errorMessage,
						new ArrayList<>(info.vaultIds)));
			}
			return result;
		}, ioExecutor);
	}

	private void accumulateItems(MutableInfo info, String data) {
		if (data == null || data.isEmpty()) {
			return;
		}
		try {
			ItemStack[] items = ItemSerializer.fromBase64(data);
			if (items != null) {
				for (ItemStack item : items) {
					if (item != null && !item.getType().isAir()) {
						info.itemCount++;
					}
				}
			}
		} catch (Exception e) {
			info.corrupted = true;
			if (info.errorMessage == null) {
				info.errorMessage = e.getMessage();
			}
		}
	}

	private static class MutableInfo {
		String name;
		int maxSize;
		int itemCount;
		boolean hasOverflow;
		boolean corrupted;
		String errorMessage;
		TreeSet<Integer> vaultIds = new TreeSet<>();
	}
}
