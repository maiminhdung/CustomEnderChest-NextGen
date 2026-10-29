package org.maiminhdung.customenderchest.storage.impl;

import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.data.ItemSerializer;
import org.maiminhdung.customenderchest.storage.StorageInterface;
import org.maiminhdung.customenderchest.storage.StorageManager;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * File-per-player YAML storage with the multi-vault format:
 *
 * <pre>
 * player-name: Notch
 * vaults:
 *   '1':
 *     enderchest-size: 27
 *     enderchest-inventory: [...]
 *     overflow-items: [...]
 *     overflow-created-at: 1690000000000
 * </pre>
 *
 * Files written by pre-multi-vault versions (root-level enderchest-size /
 * enderchest-inventory / overflow keys) are migrated into vaults.1 on the first
 * load of vault 1.
 */
public class YmlStorage implements StorageInterface {

	private final File dataFolder;
	private final ExecutorService ioExecutor;

	public YmlStorage(StorageManager storageManager) {
		EnderChest plugin = storageManager.getPlugin();
		this.ioExecutor = storageManager.getIoExecutor();
		this.dataFolder = new File(plugin.getDataFolder(), "playerdata");
		if (!dataFolder.exists()) {
			dataFolder.mkdirs();
		}
	}

	private File getPlayerFile(UUID playerUUID) {
		return new File(dataFolder, playerUUID.toString() + ".yml");
	}

	private static String vaultBase(int vaultId) {
		return "vaults." + vaultId;
	}

	@Override
	public void init() {
		// Nothing to initialize for YML storage; the playerdata folder is
		// created in the constructor.
	}

	@Override
	public CompletableFuture<VaultData> loadVault(UUID playerUUID, int vaultId) {
		return CompletableFuture.supplyAsync(() -> {
			File playerFile = getPlayerFile(playerUUID);
			if (!playerFile.exists()) {
				return null;
			}

			synchronized (playerUUID.toString().intern()) {
				YamlConfiguration config = loadFile(playerFile);

				String base = vaultBase(vaultId);
				if (config.contains(base + ".enderchest-size") || config.contains(base + ".enderchest-inventory")) {
					return readVaultSection(config, base);
				}

				if (vaultId != 1) {
					return null;
				}

				// Vault 1 missing: migrate legacy root-level data if present
				if (!config.contains("enderchest-size") && !config.contains("enderchest-inventory")) {
					return null;
				}

				VaultData legacy = readVaultSection(config, "");
				migrateLegacyKeys(config, playerFile);
				return legacy;
			}
		}, ioExecutor);
	}

	private VaultData readVaultSection(YamlConfiguration config, String base) {
		int size = config.getInt(base + "enderchest-size", 0);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> serializedItems = (List<Map<String, Object>>) config.getList(base + "enderchest-inventory");

		if (serializedItems == null) {
			// Record exists (size known) but no inventory list stored
			return new VaultData(size, new ItemStack[Math.max(size, 0)]);
		}
		return new VaultData(size, ItemSerializer.deserialize(serializedItems));
	}

	/** Moves legacy root-level keys into vaults.1 and drops them. */
	private void migrateLegacyKeys(YamlConfiguration config, File playerFile) {
		config.set(vaultBase(1) + ".enderchest-size", config.getInt("enderchest-size", 0));
		List<Map<String, Object>> inventory = castList(config.getList("enderchest-inventory"));
		config.set(vaultBase(1) + ".enderchest-inventory",
				inventory != null ? inventory : new ArrayList<Map<String, Object>>());
		if (config.contains("overflow-items")) {
			config.set(vaultBase(1) + ".overflow-items", config.getList("overflow-items"));
		}
		if (config.contains("overflow-created-at")) {
			config.set(vaultBase(1) + ".overflow-created-at", config.getLong("overflow-created-at"));
		}
		config.set("enderchest-size", null);
		config.set("enderchest-inventory", null);
		config.set("overflow-items", null);
		config.set("overflow-created-at", null);
		saveFile(config, playerFile);
		EnderChest.getInstance().getLogger().info("[YmlStorage] Migrated legacy data into vault 1 for "
				+ stripExtension(playerFile.getName()));
	}

	@Override
	public CompletableFuture<Void> saveVault(UUID playerUUID, String playerName, int vaultId, int size, ItemStack[] items) {
		return CompletableFuture.runAsync(() -> {
			File playerFile = getPlayerFile(playerUUID);
			synchronized (playerUUID.toString().intern()) {
				// Reload the file so other vaults/overflow sections survive the save
				YamlConfiguration config = loadFile(playerFile);
				config.set("player-name", playerName);
				config.set(vaultBase(vaultId) + ".enderchest-size", size);
				config.set(vaultBase(vaultId) + ".enderchest-inventory", ItemSerializer.serialize(items));
				saveFile(config, playerFile);
			}
		}, ioExecutor);
	}

	@Override
	public CompletableFuture<Void> deleteVault(UUID playerUUID, int vaultId) {
		return CompletableFuture.runAsync(() -> {
			File playerFile = getPlayerFile(playerUUID);
			if (!playerFile.exists()) {
				return;
			}
			synchronized (playerUUID.toString().intern()) {
				YamlConfiguration config = loadFile(playerFile);
				config.set(vaultBase(vaultId), null);
				if (config.getConfigurationSection("vaults") != null
						&& config.getConfigurationSection("vaults").getKeys(false).isEmpty()) {
					config.set("vaults", null);
				}
				boolean hasLegacy = config.contains("enderchest-size") || config.contains("enderchest-inventory");
				if (config.getConfigurationSection("vaults") == null && !hasLegacy) {
					playerFile.delete();
				} else {
					saveFile(config, playerFile);
				}
			}
		}, ioExecutor);
	}

	@Override
	public CompletableFuture<List<Integer>> getVaultIds(UUID playerUUID) {
		return CompletableFuture.supplyAsync(() -> {
			File playerFile = getPlayerFile(playerUUID);
			if (!playerFile.exists()) {
				return List.of();
			}
			YamlConfiguration config = loadFile(playerFile);
			TreeSet<Integer> ids = new TreeSet<>();
			org.bukkit.configuration.ConfigurationSection vaults = config.getConfigurationSection("vaults");
			if (vaults != null) {
				for (String key : vaults.getKeys(false)) {
					try {
						ids.add(Integer.parseInt(key));
					} catch (NumberFormatException ignored) {
						// Skip malformed keys
					}
				}
			}
			if (config.contains("enderchest-size") || config.contains("enderchest-inventory")) {
				ids.add(1);
			}
			return new ArrayList<>(ids);
		}, ioExecutor);
	}

	@Override
	public CompletableFuture<Void> saveOverflowItems(UUID playerUUID, int vaultId, ItemStack[] items) {
		return CompletableFuture.runAsync(() -> {
			File playerFile = getPlayerFile(playerUUID);
			synchronized (playerUUID.toString().intern()) {
				YamlConfiguration config = loadFile(playerFile);
				config.set(vaultBase(vaultId) + ".overflow-items", ItemSerializer.serialize(items));
				// Only set created_at on initial creation, not updates
				if (!config.contains(vaultBase(vaultId) + ".overflow-created-at")) {
					config.set(vaultBase(vaultId) + ".overflow-created-at", System.currentTimeMillis());
				}
				saveFile(config, playerFile);
			}
		}, ioExecutor);
	}

	@Override
	public CompletableFuture<ItemStack[]> loadOverflowItems(UUID playerUUID, int vaultId) {
		return CompletableFuture.supplyAsync(() -> {
			File playerFile = getPlayerFile(playerUUID);
			if (!playerFile.exists()) {
				return null;
			}
			YamlConfiguration config = loadFile(playerFile);

			@SuppressWarnings("unchecked")
			List<Map<String, Object>> serializedItems = (List<Map<String, Object>>) config.getList(vaultBase(vaultId) + ".overflow-items");
			if (serializedItems == null) {
				// Legacy fallback for vault 1: root-level overflow keys
				if (vaultId == 1 && config.contains("overflow-items")) {
					@SuppressWarnings("unchecked")
					List<Map<String, Object>> legacy = (List<Map<String, Object>>) config.getList("overflow-items");
					return legacy != null ? ItemSerializer.deserialize(legacy) : null;
				}
				return null;
			}
			return ItemSerializer.deserialize(serializedItems);
		}, ioExecutor);
	}

	@Override
	public CompletableFuture<Void> clearOverflowItems(UUID playerUUID, int vaultId) {
		return CompletableFuture.runAsync(() -> {
			File playerFile = getPlayerFile(playerUUID);
			if (!playerFile.exists()) {
				return;
			}
			synchronized (playerUUID.toString().intern()) {
				YamlConfiguration config = loadFile(playerFile);
				config.set(vaultBase(vaultId) + ".overflow-items", null);
				config.set(vaultBase(vaultId) + ".overflow-created-at", null);
				if (vaultId == 1) {
					// Legacy root-level overflow cleanup
					config.set("overflow-items", null);
					config.set("overflow-created-at", null);
				}
				saveFile(config, playerFile);
			}
		}, ioExecutor);
	}

	@Override
	public CompletableFuture<Boolean> hasOverflowItems(UUID playerUUID, int vaultId) {
		return CompletableFuture.supplyAsync(() -> {
			File playerFile = getPlayerFile(playerUUID);
			if (!playerFile.exists()) {
				return false;
			}
			YamlConfiguration config = loadFile(playerFile);
			return config.contains(vaultBase(vaultId) + ".overflow-items")
					|| (vaultId == 1 && config.contains("overflow-items"));
		}, ioExecutor);
	}

	@Override
	public CompletableFuture<Long> getOverflowCreatedAt(UUID playerUUID, int vaultId) {
		return CompletableFuture.supplyAsync(() -> {
			File playerFile = getPlayerFile(playerUUID);
			if (!playerFile.exists()) {
				return null;
			}
			YamlConfiguration config = loadFile(playerFile);
			if (config.contains(vaultBase(vaultId) + ".overflow-created-at")) {
				return config.getLong(vaultBase(vaultId) + ".overflow-created-at");
			}
			if (vaultId == 1 && config.contains("overflow-created-at")) {
				return config.getLong("overflow-created-at");
			}
			return null;
		}, ioExecutor);
	}

	@Override
	public CompletableFuture<Integer> migrateLegacyData() {
		return CompletableFuture.supplyAsync(() -> {
			File[] files = dataFolder.listFiles((dir, name) -> name.endsWith(".yml"));
			if (files == null) {
				return 0;
			}
			int migrated = 0;
			for (File file : files) {
				String uuidStr = stripExtension(file.getName());
				UUID uuid;
				try {
					uuid = UUID.fromString(uuidStr);
				} catch (IllegalArgumentException e) {
					continue; // not a player file
				}
				synchronized (uuid.toString().intern()) {
					try {
						YamlConfiguration config = loadFile(file);
						boolean hasVault1 = config.contains(vaultBase(1) + ".enderchest-size")
								|| config.contains(vaultBase(1) + ".enderchest-inventory");
						boolean hasLegacy = config.contains("enderchest-size")
								|| config.contains("enderchest-inventory");
						if (!hasVault1 && hasLegacy) {
							migrateLegacyKeys(config, file);
							migrated++;
						}
					} catch (Exception ignored) {
						// Unreadable file: skip; the per-load migration surfaces real errors
					}
				}
			}
			return migrated;
		}, ioExecutor);
	}

	@Override
	public CompletableFuture<Void> deleteEnderChest(UUID playerUUID) {
		return CompletableFuture.runAsync(() -> {
			File playerFile = getPlayerFile(playerUUID);
			if (playerFile.exists()) {
				playerFile.delete();
			}
		}, ioExecutor);
	}

	@Override
	public CompletableFuture<String> getPlayerName(UUID playerUUID) {
		return CompletableFuture.supplyAsync(() -> {
			File playerFile = getPlayerFile(playerUUID);
			if (!playerFile.exists()) {
				return null;
			}
			String name = loadFile(playerFile).getString("player-name");
			if (name != null) {
				return name;
			}
			return loadFile(playerFile).getString(vaultBase(1) + ".player-name");
		}, ioExecutor);
	}

	@Override
	public CompletableFuture<UUID> findUUIDByName(String playerName) {
		return CompletableFuture.supplyAsync(() -> {
			File[] files = dataFolder.listFiles((dir, name) -> name.endsWith(".yml"));
			if (files == null) {
				return null;
			}

			File latestFile = null;
			long latestTime = -1;

			for (File file : files) {
				try {
					YamlConfiguration config = loadFile(file);
					String storedName = config.getString("player-name");
					if (storedName != null && storedName.equalsIgnoreCase(playerName)) {
						long modified = file.lastModified();
						if (modified > latestTime) {
							latestTime = modified;
							latestFile = file;
						}
					}
				} catch (Exception e) {
					// Skip invalid files
				}
			}

			if (latestFile != null) {
				String filename = latestFile.getName();
				String uuidStr = filename.substring(0, filename.length() - 4);
				return UUID.fromString(uuidStr);
			}
			return null;
		}, ioExecutor);
	}

	@Override
	public CompletableFuture<Boolean> hasData(UUID playerUUID) {
		return CompletableFuture.supplyAsync(() -> getPlayerFile(playerUUID).exists(), ioExecutor);
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

			File[] files = dataFolder.listFiles((dir, name) -> name.endsWith(".yml"));
			if (files == null) {
				return new StorageStats(0, 0, 0, 0, 0, 0);
			}

			for (File file : files) {
				totalPlayers++;
				totalDataSize += file.length();

				try {
					YamlConfiguration config = loadFile(file);
					boolean hasAnyItem = false;

					for (String[] section : sectionsOf(config)) {
						ItemStack[] items = safeDeserialize(castList(config.getList(section[0])));
						if (items != null) {
							for (ItemStack item : items) {
								if (item != null && !item.getType().isAir()) {
									totalItems++;
									hasAnyItem = true;
								}
							}
						}
						ItemStack[] overflow = safeDeserialize(castList(config.getList(section[1])));
						if (overflow != null) {
							boolean overflowHasItem = false;
							for (ItemStack item : overflow) {
								if (item != null && !item.getType().isAir()) {
									totalOverflowItems++;
									overflowHasItem = true;
								}
							}
							if (overflowHasItem) {
								totalOverflowPlayers++;
							}
						}
					}

					if (hasAnyItem) {
						playersWithItems++;
					}
				} catch (Exception e) {
					EnderChest.getInstance().getLogger().warning("[YmlStorage] Failed to read file " + file.getName() + ": " + e.getMessage());
					EnderChest.trackError(e);
				}
			}

			return new StorageStats(totalPlayers, playersWithItems, totalItems,
					totalOverflowPlayers, totalOverflowItems, totalDataSize);
		}, ioExecutor);
	}

	@Override
	public CompletableFuture<List<PlayerDataInfo>> getPlayersWithItems() {
		return CompletableFuture.supplyAsync(() -> {
			List<PlayerDataInfo> result = new ArrayList<>();

			File[] files = dataFolder.listFiles((dir, name) -> name.endsWith(".yml"));
			if (files == null) {
				return result;
			}

			for (File file : files) {
				try {
					String fileName = file.getName().replace(".yml", "");
					UUID uuid = UUID.fromString(fileName);

					YamlConfiguration config = loadFile(file);
					String name = config.getString("player-name", "Unknown");

					int itemCount = 0;
					int maxSize = 0;
					boolean isCorrupted = false;
					String errorMessage = null;
					boolean hasOverflow = false;
					TreeSet<Integer> vaultIds = new TreeSet<>();

					org.bukkit.configuration.ConfigurationSection vaults = config.getConfigurationSection("vaults");
					if (vaults != null) {
						for (String key : vaults.getKeys(false)) {
							try {
								vaultIds.add(Integer.parseInt(key));
							} catch (NumberFormatException ignored) {
								// Skip malformed keys
							}
						}
					}

					for (String[] section : sectionsOf(config)) {
						vaultIds.add(Integer.parseInt(section[2]));
						maxSize = Math.max(maxSize, config.getInt(section[3], 0));
						if (config.contains(section[1])) {
							hasOverflow = true;
						}
						try {
							ItemStack[] items = ItemSerializer.deserialize(
									requireList(castList(config.getList(section[0]))));
							if (items != null) {
								for (ItemStack item : items) {
									if (item != null && !item.getType().isAir()) {
										itemCount++;
									}
								}
							}
						} catch (Exception e) {
							isCorrupted = true;
							errorMessage = e.getMessage();
						}
					}

					result.add(new PlayerDataInfo(uuid, name, maxSize, itemCount, hasOverflow,
							isCorrupted, errorMessage, new ArrayList<>(vaultIds)));
				} catch (Exception e) {
					EnderChest.getInstance().getLogger().warning("[YmlStorage] Failed to parse file " + file.getName() + ": " + e.getMessage());
				}
			}

			return result;
		}, ioExecutor);
	}

	/**
	 * Returns the readable sections of a file: for the legacy layout and every
	 * stored vault, the triple of inventory path / overflow path / vault id and
	 * the matching size path.
	 */
	private List<String[]> sectionsOf(YamlConfiguration config) {
		List<String[]> sections = new ArrayList<>();
		if (config.contains("enderchest-size") || config.contains("enderchest-inventory")) {
			sections.add(new String[] { "enderchest-inventory", "overflow-items", "1", "enderchest-size" });
		}
		org.bukkit.configuration.ConfigurationSection vaults = config.getConfigurationSection("vaults");
		if (vaults != null) {
			for (String key : vaults.getKeys(false)) {
				try {
					Integer.parseInt(key);
				} catch (NumberFormatException e) {
					continue;
				}
				String base = "vaults." + key + ".";
				sections.add(new String[] { base + "enderchest-inventory", base + "overflow-items", key,
						base + "enderchest-size" });
			}
		}
		return sections;
	}

	private ItemStack[] safeDeserialize(List<Map<String, Object>> list) {
		if (list == null) {
			return null;
		}
		try {
			return ItemSerializer.deserialize(list);
		} catch (Exception e) {
			return null;
		}
	}

	private static List<Map<String, Object>> requireList(List<Map<String, Object>> list) {
		return list != null ? list : new ArrayList<>();
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> castList(Object raw) {
		if (raw instanceof List<?> list) {
			return (List<Map<String, Object>>) list;
		}
		return null;
	}

	private YamlConfiguration loadFile(File file) {
		YamlConfiguration config = new YamlConfiguration();
		try {
			config.load(file);
		} catch (Exception e) {
			EnderChest.trackError(e);
			throw new java.util.concurrent.CompletionException("Failed to load " + file.getName(), e);
		}
		return config;
	}

	private void saveFile(YamlConfiguration config, File file) {
		try {
			config.save(file);
		} catch (Exception e) {
			EnderChest.getInstance().getLogger().severe("[YmlStorage] Failed to save " + file.getName() + ": " + e.getMessage());
			e.printStackTrace();
			EnderChest.trackError(e);
		}
	}

	private static String stripExtension(String fileName) {
		return fileName.endsWith(".yml") ? fileName.substring(0, fileName.length() - 4) : fileName;
	}
}
