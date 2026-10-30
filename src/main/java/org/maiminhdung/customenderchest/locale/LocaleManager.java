package org.maiminhdung.customenderchest.locale;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.maiminhdung.customenderchest.EnderChest;
import org.maiminhdung.customenderchest.utils.Text;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.translation.GlobalTranslator;
import net.kyori.adventure.translation.TranslationStore;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Language management using Minecraft-standard locale codes
 * ({@code en_us}, {@code vi_vn}, {@code zh_cn}, ...). Each code maps to
 * exactly one {@code lang_<code>.yml} — the file on disk when present,
 * otherwise the copy bundled in the plugin JAR — so the code matches
 * {@code player.locale()} directly.
 *
 * <p>Resolution order: the player's client locale (when
 * {@code general.player-locale} is enabled and a matching file exists) →
 * the configured locale → English → the hardcoded default. The flattened
 * stores are also registered with Adventure's {@link GlobalTranslator}
 * (namespace {@code customenderchest:}) so
 * {@code Component.translatable("customenderchest.<key>")} renders
 * per-player wherever the platform applies translators.</p>
 */
public class LocaleManager {

	/** Bundled locales, Minecraft locale codes. */
	public static final List<String> AVAILABLE_LOCALES = Arrays.asList(
			"en_us", "vi_vn", "zh_cn", "nl_nl", "es_es");

	private static final String DEFAULT_LOCALE = "en_us";
	private static final String TRANSLATION_NAMESPACE = "customenderchest";

	/** Old pre-standardization codes → Minecraft codes. */
	private static final Map<String, String> LEGACY_CODES = Map.of(
			"en", "en_us", "vi", "vi_vn", "zhcn", "zh_cn", "nl", "nl_nl", "es", "es_es");

	private final EnderChest plugin;
	/** locale code -> configuration (disk file preferred, bundled JAR fallback). */
	private final Map<String, FileConfiguration> localeConfigs = new HashMap<>();
	private volatile String activeLocale = DEFAULT_LOCALE;
	/** Adventure 5 translation store registered with the GlobalTranslator. */
	private TranslationStore<Component> translationStore;

	public LocaleManager(EnderChest plugin) {
		this.plugin = plugin;
		migrateLegacyFiles();
		saveDefaultLanguageFiles();
		if (!loadLocale()) {
			plugin.getLogger().severe("Unable to load the configured language file. Attempting English fallback.");
			if (!loadLocaleFile(DEFAULT_LOCALE)) {
				throw new IllegalStateException("Unable to load any language file");
			}
		}
	}

	/** Maps a configured/file code to a Minecraft locale code (legacy-aware). */
	public static String normalizeCode(String code) {
		if (code == null) {
			return null;
		}
		String normalized = code.trim().toLowerCase(Locale.ROOT).replace('-', '_');
		return LEGACY_CODES.getOrDefault(normalized, normalized);
	}

	/**
	 * Renames pre-standardization lang files (lang_en.yml etc.) to their
	 * Minecraft-locale names so community edits survive the upgrade.
	 */
	private void migrateLegacyFiles() {
		File langDir = new File(plugin.getDataFolder(), "lang");
		if (!langDir.isDirectory()) {
			return;
		}
		for (Map.Entry<String, String> legacy : LEGACY_CODES.entrySet()) {
			File oldFile = new File(langDir, "lang_" + legacy.getKey() + ".yml");
			File newFile = new File(langDir, "lang_" + legacy.getValue() + ".yml");
			if (oldFile.exists() && !newFile.exists()) {
				try {
					Files.move(oldFile.toPath(), newFile.toPath());
					plugin.getLogger().info("Renamed lang_" + legacy.getKey() + ".yml to lang_"
							+ legacy.getValue() + ".yml (Minecraft locale codes).");
				} catch (IOException error) {
					plugin.getLogger().warning("Could not rename lang_" + legacy.getKey() + ".yml: "
							+ error.getMessage());
				}
			}
		}
	}

	/** Copies every bundled language file to the lang folder when missing. */
	private void saveDefaultLanguageFiles() {
		File langDir = new File(plugin.getDataFolder(), "lang");
		if (!langDir.exists() && !langDir.mkdirs()) {
			plugin.getLogger().warning("Could not create lang directory");
			return;
		}
		for (String locale : AVAILABLE_LOCALES) {
			File langFile = new File(langDir, "lang_" + locale + ".yml");
			if (!langFile.exists()) {
				extractBundledLocale(locale, langFile);
			}
		}
	}

	private void extractBundledLocale(String locale, File target) {
		try (InputStream in = plugin.getResource("lang/lang_" + locale + ".yml")) {
			if (in != null) {
				Files.copy(in, target.toPath());
				plugin.getLogger().info("Created default language file: lang_" + locale + ".yml");
			}
		} catch (IOException error) {
			plugin.getLogger().warning("Could not create language file lang_" + locale + ".yml: " + error.getMessage());
		}
	}

	/**
	 * Loads the configured language file, builds the per-locale configs for
	 * every bundled locale and refreshes the GlobalTranslator store. The
	 * previous store is dropped first so stale keys never survive a reload.
	 * The currently active locale remains unchanged if the new file is
	 * invalid.
	 *
	 * @return true when a language file was loaded successfully
	 */
	public boolean loadLocale() {
		String configured = plugin.config().getString("general.locale", DEFAULT_LOCALE);
		String localeCode = normalizeCode(configured);
		if (localeCode == null || !localeCode.matches("[a-z0-9_]+")) {
			plugin.getLogger().warning("Invalid locale code '" + configured + "'. Using " + DEFAULT_LOCALE + ".");
			localeCode = DEFAULT_LOCALE;
		}
		if (!LEGACY_CODES.getOrDefault(configured, "").isEmpty()
				&& !configured.trim().toLowerCase(Locale.ROOT).equals(localeCode)) {
			plugin.getLogger().info("Locale '" + configured.trim() + "' mapped to '" + localeCode
					+ "' (Minecraft locale codes).");
		}

		boolean loaded = ensureLanguageFile(localeCode) && loadLocaleFile(localeCode);
		if (loaded) {
			this.activeLocale = localeCode;
			plugin.getLogger().info("Loaded language file: lang_" + localeCode + ".yml");
			rebuildTranslationStores();
			registerGlobalTranslator();
		}
		return loaded;
	}

	private boolean ensureLanguageFile(String localeCode) {
		File langFile = langFile(localeCode);
		if (langFile.exists()) {
			return langFile.isFile();
		}

		plugin.getLogger().warning("Language file 'lang_" + localeCode + ".yml' not found. Attempting to copy it from the plugin JAR.");
		File parentDir = langFile.getParentFile();
		if (!parentDir.exists() && !parentDir.mkdirs()) {
			plugin.getLogger().warning("Could not create language directory: " + parentDir);
			return false;
		}
		extractBundledLocale(localeCode, langFile);
		return langFile.exists();
	}

	private File langFile(String localeCode) {
		return new File(plugin.getDataFolder(), "lang/lang_" + localeCode + ".yml");
	}

	/**
	 * Loads one locale into the config map: the disk file when present
	 * (preferred), otherwise the bundled JAR copy.
	 */
	private boolean loadLocaleFile(String localeCode) {
		File langFile = langFile(localeCode);
		YamlConfiguration config = new YamlConfiguration();
		try {
			if (langFile.exists()) {
				config.load(langFile);
			} else {
				try (InputStream in = plugin.getResource("lang/lang_" + localeCode + ".yml")) {
					if (in == null) {
						plugin.getLogger().warning("No bundled language file exists for locale '" + localeCode + "'.");
						return false;
					}
					config.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
				}
			}
		} catch (IOException | InvalidConfigurationException error) {
			plugin.getLogger().severe("Could not load language file lang_" + localeCode + ".yml: " + error.getMessage());
			return false;
		}

		if (!config.isString("prefix") || !config.isString("messages.reload-success")) {
			plugin.getLogger().severe("Invalid language file lang_" + localeCode
					+ ".yml: required keys 'prefix' and 'messages.reload-success' must be strings");
			return false;
		}

		migrateLegacyColorCodes(localeCode, config);
		localeConfigs.put(localeCode, config);
		return true;
	}

	/**
	 * Converts pre-MiniMessage legacy color codes (e.g. "&fLevel 1") in the
	 * levels section to MiniMessage tags in memory, so language files from
	 * older versions keep rendering after the legacy pipeline was removed.
	 */
	private void migrateLegacyColorCodes(String localeCode, YamlConfiguration config) {
		org.bukkit.configuration.ConfigurationSection levels = config.getConfigurationSection("levels");
		if (levels == null) {
			return;
		}
		boolean changed = false;
		for (String key : levels.getKeys(false)) {
			String value = levels.getString(key);
			if (value != null && value.contains("&") && value.matches(".*&[0-9a-fk-or].*")) {
				config.set("levels." + key, value
						.replace("&0", "<black>").replace("&1", "<dark_blue>")
						.replace("&2", "<dark_green>").replace("&3", "<dark_aqua>")
						.replace("&4", "<dark_red>").replace("&5", "<dark_purple>")
						.replace("&6", "<gold>").replace("&7", "<gray>")
						.replace("&8", "<dark_gray>").replace("&9", "<blue>")
						.replace("&a", "<green>").replace("&b", "<aqua>")
						.replace("&c", "<red>").replace("&d", "<light_purple>")
						.replace("&e", "<yellow>").replace("&f", "<white>")
						.replace("&l", "<bold>").replace("&m", "<st>")
						.replace("&n", "<under>").replace("&o", "<italic>")
						.replace("&k", "<obf>").replace("&r", "<reset>"));
				changed = true;
			}
		}
		if (changed) {
			plugin.getLogger().info("lang_" + localeCode + ".yml still uses legacy color codes in 'levels'; "
					+ "converted to MiniMessage in memory (update the file at your leisure).");
		}
	}

	/** Rebuilds every locale config and the Adventure translation store. */
	private void rebuildTranslationStores() {
		// Drop the previous store first so old keys never survive a reload
		if (translationStore != null) {
			GlobalTranslator.translator().removeSource(translationStore);
			translationStore = null;
		}
		localeConfigs.clear();

		Set<String> codes = new LinkedHashSet<>(AVAILABLE_LOCALES);
		codes.add(activeLocale);
		for (String code : codes) {
			loadLocaleFile(code);
		}

		TranslationStore<Component> store = TranslationStore.component(Key.key(TRANSLATION_NAMESPACE, "translations"));
		for (Map.Entry<String, FileConfiguration> entry : localeConfigs.entrySet()) {
			Locale locale = Locale.forLanguageTag(entry.getKey().replace('_', '-'));
			for (Map.Entry<String, String> keyEntry : flatten(entry.getValue()).entrySet()) {
				store.register(TRANSLATION_NAMESPACE + "." + keyEntry.getKey(), locale,
						Text.parse(keyEntry.getValue()));
			}
		}
		translationStore = store;
		plugin.getDebugLogger().log("Translation stores rebuilt for " + localeConfigs.size() + " locale(s).");
	}

	/** Flattens a YAML config into dotted key -> string entries. */
	private Map<String, String> flatten(FileConfiguration config) {
		Map<String, String> flat = new LinkedHashMap<>();
		for (String key : config.getKeys(false)) {
			if (config.isConfigurationSection(key)) {
				flattenSection(key, config.getConfigurationSection(key), flat);
			} else if (config.isList(key)) {
				flat.put(key, String.join("\n", config.getStringList(key)));
			} else if (config.isString(key)) {
				flat.put(key, config.getString(key));
			}
		}
		return flat;
	}

	private void flattenSection(String prefix, org.bukkit.configuration.ConfigurationSection section,
			Map<String, String> flat) {
		for (String key : section.getKeys(false)) {
			String path = prefix + "." + key;
			if (section.isConfigurationSection(key)) {
				flattenSection(path, section.getConfigurationSection(key), flat);
			} else if (section.isList(key)) {
				flat.put(path, String.join("\n", section.getStringList(key)));
			} else if (section.isString(key)) {
				flat.put(path, section.getString(key));
			}
		}
	}

	/** Registers the store with the GlobalTranslator when player-locale is on. */
	private void registerGlobalTranslator() {
		if (translationStore != null && plugin.config().getBoolean("general.player-locale", true)) {
			GlobalTranslator.translator().addSource(translationStore);
		}
	}

	/** Unregisters the GlobalTranslator source (plugin disable). */
	public void shutdown() {
		if (translationStore != null) {
			GlobalTranslator.translator().removeSource(translationStore);
			translationStore = null;
		}
		localeConfigs.clear();
	}

	// ==================== Lookups ====================

	private FileConfiguration configFor(String localeCode) {
		return localeCode != null ? localeConfigs.get(localeCode) : null;
	}

	/** The player's locale code when a matching language config is loaded, else null. */
	private String codeFor(CommandSender viewer) {
		if (viewer instanceof Player player
				&& plugin.config().getBoolean("general.player-locale", true)) {
			String code = normalizeCode(player.locale().toString());
			return localeConfigs.containsKey(code) ? code : null;
		}
		return null;
	}

	/** Locale-aware value chain: viewer locale → configured locale → English → default. */
	private String resolveString(CommandSender viewer, String key, String defaultValue) {
		FileConfiguration viewerConfig = configFor(codeFor(viewer));
		String value = viewerConfig != null ? getStringOrList(viewerConfig, key) : null;
		if (value == null) {
			value = resolveString(key, defaultValue);
		}
		return value != null ? value : defaultValue;
	}

	/** Configured-locale value chain: configured locale → English → default. */
	private String resolveString(String key, String defaultValue) {
		FileConfiguration activeConfig = configFor(activeLocale);
		String value = activeConfig != null ? getStringOrList(activeConfig, key) : null;
		if (value == null) {
			FileConfiguration defConfig = configFor(DEFAULT_LOCALE);
			value = defConfig != null ? getStringOrList(defConfig, key) : null;
		}
		return value != null ? value : defaultValue;
	}

	private String getStringOrList(FileConfiguration config, String key) {
		if (config == null || key == null) {
			return null;
		}
		if (config.isList(key)) {
			return String.join("\n", config.getStringList(key));
		}
		return config.getString(key);
	}

	public String getRawString(String key, String defaultValue) {
		return resolveString(key, defaultValue);
	}

	/** Player-locale-aware raw lookup (used for proactive ItemMeta/lore rendering). */
	public String getRawString(CommandSender viewer, String key, String defaultValue) {
		return resolveString(viewer, key, defaultValue);
	}

	public Component getComponent(String key, TagResolver... placeholders) {
		return getComponent(key, "<red>Missing key: '" + key + "'</red>", placeholders);
	}

	public Component getComponent(String key, String defaultValue, TagResolver... placeholders) {
		return Text.parse(resolveString(key, defaultValue), placeholders);
	}

	/** Renders a message in the viewer's locale when available. */
	public Component getComponent(CommandSender viewer, String key, TagResolver... placeholders) {
		return getComponent(viewer, key, "<red>Missing key: '" + key + "'</red>", placeholders);
	}

	public Component getComponent(CommandSender viewer, String key, String defaultValue, TagResolver... placeholders) {
		return Text.parse(resolveString(viewer, key, defaultValue), placeholders);
	}

	public Component getPrefixedComponent(String key, TagResolver... placeholders) {
		return getPrefixedComponent(key, "<red>Missing key: '" + key + "'</red>", placeholders);
	}

	public Component getPrefixedComponent(String key, String defaultValue, TagResolver... placeholders) {
		return Text.parse(resolveString("prefix", ""))
				.append(Text.parse(resolveString(key, defaultValue), placeholders));
	}

	/** Renders a prefixed message in the viewer's locale when available. */
	public Component getPrefixedComponent(CommandSender viewer, String key, TagResolver... placeholders) {
		return getPrefixedComponent(viewer, key, "<red>Missing key: '" + key + "'</red>", placeholders);
	}

	public Component getPrefixedComponent(CommandSender viewer, String key, String defaultValue, TagResolver... placeholders) {
		return Text.parse(resolveString(viewer, "prefix", ""))
				.append(Text.parse(resolveString(viewer, key, defaultValue), placeholders));
	}

	/** Locale codes with a loaded config (debug/diagnostics). */
	public List<String> getLoadedLocales() {
		return new ArrayList<>(localeConfigs.keySet());
	}
}
