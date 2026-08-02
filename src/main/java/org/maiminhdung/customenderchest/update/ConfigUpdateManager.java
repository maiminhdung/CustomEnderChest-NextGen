package org.maiminhdung.customenderchest.update;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.maiminhdung.customenderchest.EnderChest;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Checks config.yml once when its version differs from the running plugin version.
 */
public final class ConfigUpdateManager {

    private static final String REMOTE_CONFIG_PATTERN =
            "https://raw.githubusercontent.com/maiminhdung/CustomEnderChest-NextGen/%s/src/main/resources/config.yml";


    private final EnderChest plugin;
    private final HttpClient httpClient;
    private final Path configPath;

    public ConfigUpdateManager(EnderChest plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.configPath = new File(plugin.getDataFolder(), "config.yml").toPath();
    }

    /**
     * Checks GitHub only when local config-version differs from the running plugin version.
     * The downloaded template is applied only when its config-version exactly matches
     * the plugin version. A successful update makes subsequent startups skip the request.
     */
    public CompletableFuture<Void> updateIfRequired() {
        String pluginVersion = plugin.getPluginMeta().getVersion();
        String configVersion = plugin.config().getString("config-version", "0.0.0");

        if (configVersion.equals(pluginVersion)) {
            plugin.getLogger().info("config.yml is up to date (config-version " + configVersion + ").");
            return CompletableFuture.completedFuture(null);
        }

        plugin.getLogger().info("config-version " + configVersion + " does not match plugin version "
                + pluginVersion + "; checking GitHub once for an exact matching config.yml.");

        HttpRequest request = HttpRequest.newBuilder(remoteConfigUri(pluginVersion))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "text/plain")
                .header("User-Agent", userAgent())
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenAccept(response -> {
                    if (!plugin.isEnabled()) {
                        return;
                    }
                    if (response.statusCode() != 200) {
                        logMatchingConfigRequired(pluginVersion,
                                "GitHub returned HTTP " + response.statusCode() + " for the versioned config.yml.");
                        return;
                    }

                    try {
                        if (applyTemplate(response.body(), configVersion, pluginVersion)) {
                            plugin.getLogger().warning("config.yml was updated to version " + pluginVersion
                                    + ". Restart the server to apply all changes safely.");
                        }
                    } catch (IOException | InvalidConfigurationException error) {
                        plugin.getLogger().log(Level.WARNING,
                                "Unable to update config.yml from GitHub. The check will retry next startup.", error);
                    }
                })
                .exceptionally(error -> {
                    plugin.getLogger().log(Level.WARNING,
                            "Unable to check GitHub config: " + rootMessage(error)
                                    + ". The check will retry next startup.");
                    return null;
                });
    }

    private synchronized boolean applyTemplate(String templateText, String oldVersion, String pluginVersion)
            throws IOException, InvalidConfigurationException {
        YamlConfiguration template = loadYaml(templateText);
        String templateVersion = template.getString("config-version", "").trim();
        if (!pluginVersion.equals(templateVersion)) {
            String displayedVersion = templateVersion.isBlank() ? "missing" : templateVersion;
            logMatchingConfigRequired(pluginVersion, "GitHub config-version is '" + displayedVersion
                    + "', but the running plugin version is '" + pluginVersion + "'.");
            return false;
        }

        YamlConfiguration userConfig = loadConfigFile();
        preserveUserValues(userConfig, template);
        template.set("config-version", pluginVersion);
        saveAtomically(template, oldVersion, pluginVersion);

        plugin.getLogger().info("Updated config.yml from " + oldVersion + " to " + pluginVersion + ".");
        return true;
    }

    private URI remoteConfigUri(String pluginVersion) {
        String encodedVersion = URLEncoder.encode(pluginVersion, StandardCharsets.UTF_8).replace("+", "%20");
        return URI.create(REMOTE_CONFIG_PATTERN.formatted(encodedVersion));
    }

    private void logMatchingConfigRequired(String pluginVersion, String reason) {
        plugin.getLogger().warning("Config update cancelled: " + reason);
        plugin.getLogger().warning("Please find and install a config.yml whose config-version exactly matches "
                + "plugin version " + pluginVersion + ".");
        plugin.getLogger().warning("Expected config source: " + remoteConfigUri(pluginVersion));
    }

    private YamlConfiguration loadConfigFile() throws IOException, InvalidConfigurationException {
        if (!Files.exists(configPath)) {
            return new YamlConfiguration();
        }
        return loadYaml(Files.readString(configPath, StandardCharsets.UTF_8));
    }

    private YamlConfiguration loadYaml(String contents) throws InvalidConfigurationException {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.options().parseComments(true);
        configuration.loadFromString(contents);
        return configuration;
    }

    private void preserveUserValues(YamlConfiguration userConfig, YamlConfiguration template) {
        Set<String> keys = userConfig.getKeys(true);
        for (String path : keys) {
            if (path.equals("config-version") || userConfig.isConfigurationSection(path)) {
                continue;
            }

            Object value = userConfig.get(path);
            if (value == null || value instanceof ConfigurationSection) {
                continue;
            }

            boolean knownSetting = template.contains(path);
            template.set(path, value);
            if (!knownSetting) {
                template.setComments(path, userConfig.getComments(path));
                template.setInlineComments(path, userConfig.getInlineComments(path));
            }
        }
    }

    private void saveAtomically(YamlConfiguration configuration, String oldVersion, String newVersion)
            throws IOException {
        Files.createDirectories(configPath.getParent());

        if (Files.exists(configPath)) {
            String backupName = "config.yml.backup-" + safeVersion(oldVersion) + "-to-"
                    + safeVersion(newVersion) + "-" + Instant.now().toEpochMilli();
            Files.copy(configPath, configPath.resolveSibling(backupName));
        }

        Path temporaryPath = configPath.resolveSibling("config.yml.tmp");
        Files.writeString(temporaryPath, configuration.saveToString(), StandardCharsets.UTF_8);
        try {
            Files.move(temporaryPath, configPath,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporaryPath, configPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String safeVersion(String version) {
        String safe = version == null ? "unknown" : version.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isBlank() ? "unknown" : safe;
    }

    private String userAgent() {
        return "CustomEnderChest/" + plugin.getPluginMeta().getVersion()
                + " (+https://github.com/maiminhdung/CustomEnderChest-NextGen)";
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() != null ? current.getMessage() : current.getClass().getSimpleName();
    }
}
