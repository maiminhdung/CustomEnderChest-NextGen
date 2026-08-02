package org.maiminhdung.customenderchest.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.maiminhdung.customenderchest.EnderChest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Checks Modrinth for newer stable plugin releases.
 */
public final class UpdateManager {

    private static final URI VERSIONS_URI = URI.create("https://api.modrinth.com/v2/project/AipGDIso/version");
    private static final String DOWNLOAD_URL = "https://modrinth.com/plugin/custom-ender-chest/version/";

    private final EnderChest plugin;
    private final HttpClient httpClient;

    public UpdateManager(EnderChest plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public CompletableFuture<Void> checkForUpdates() {
        HttpRequest request = HttpRequest.newBuilder(VERSIONS_URI)
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("User-Agent", userAgent())
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (!plugin.isEnabled()) {
                        return;
                    }
                    if (response.statusCode() != 200) {
                        plugin.getLogger().warning("Unable to check Modrinth for updates (HTTP "
                                + response.statusCode() + ").");
                        return;
                    }

                    Release latest = findLatestStableRelease(response.body());
                    if (latest == null) {
                        plugin.getLogger().warning("Modrinth returned no stable Paper/Folia release.");
                        return;
                    }

                    String currentVersion = plugin.getPluginMeta().getVersion();
                    if (compareVersions(latest.versionNumber(), currentVersion) > 0) {
                        plugin.getLogger().warning("A new CustomEnderChest version is available: "
                                + latest.versionNumber() + " (current: " + currentVersion + ").");
                        plugin.getLogger().warning("Download: " + DOWNLOAD_URL + latest.versionId());
                    } else {
                        plugin.getLogger().info("CustomEnderChest is up to date (" + currentVersion + ").");
                    }
                })
                .exceptionally(error -> {
                    plugin.getLogger().log(Level.WARNING,
                            "Unable to check Modrinth for updates: " + rootMessage(error));
                    return null;
                });
    }

    private Release findLatestStableRelease(String json) {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonArray()) {
            return null;
        }

        Release latest = null;
        JsonArray versions = root.getAsJsonArray();
        for (JsonElement element : versions) {
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject version = element.getAsJsonObject();
            if (!"release".equalsIgnoreCase(getString(version, "version_type"))) {
                continue;
            }
            if (!"listed".equalsIgnoreCase(getString(version, "status"))) {
                continue;
            }
            if (!supportsServerPluginLoader(version.getAsJsonArray("loaders"))) {
                continue;
            }

            String versionNumber = getString(version, "version_number");
            String versionId = getString(version, "id");
            if (versionNumber.isBlank() || versionId.isBlank()) {
                continue;
            }

            Release candidate = new Release(versionNumber, versionId);
            if (latest == null || compareVersions(candidate.versionNumber(), latest.versionNumber()) > 0) {
                latest = candidate;
            }
        }
        return latest;
    }

    private boolean supportsServerPluginLoader(JsonArray loaders) {
        if (loaders == null) {
            return false;
        }
        for (JsonElement loader : loaders) {
            String name = loader.getAsString().toLowerCase(Locale.ROOT);
            if (name.equals("paper") || name.equals("folia") || name.equals("purpur")) {
                return true;
            }
        }
        return false;
    }

    private String getString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    static int compareVersions(String left, String right) {
        ParsedVersion leftVersion = ParsedVersion.parse(left);
        ParsedVersion rightVersion = ParsedVersion.parse(right);

        int length = Math.max(leftVersion.numbers().size(), rightVersion.numbers().size());
        for (int index = 0; index < length; index++) {
            int leftNumber = index < leftVersion.numbers().size() ? leftVersion.numbers().get(index) : 0;
            int rightNumber = index < rightVersion.numbers().size() ? rightVersion.numbers().get(index) : 0;
            int comparison = Integer.compare(leftNumber, rightNumber);
            if (comparison != 0) {
                return comparison;
            }
        }

        if (leftVersion.qualifier().isEmpty() && rightVersion.qualifier().isEmpty()) {
            return 0;
        }
        if (leftVersion.qualifier().isEmpty()) {
            return 1;
        }
        if (rightVersion.qualifier().isEmpty()) {
            return -1;
        }
        return leftVersion.qualifier().compareToIgnoreCase(rightVersion.qualifier());
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

    private record Release(String versionNumber, String versionId) {
    }

    private record ParsedVersion(List<Integer> numbers, String qualifier) {
        private static ParsedVersion parse(String value) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("v")) {
                normalized = normalized.substring(1);
            }

            String[] parts = normalized.split("-", 2);
            String[] numericParts = parts[0].split("\\.");
            List<Integer> numbers = new ArrayList<>(numericParts.length);
            for (String part : numericParts) {
                String digits = part.replaceAll("[^0-9].*$", "");
                if (digits.isEmpty()) {
                    numbers.add(0);
                    continue;
                }
                try {
                    numbers.add(Integer.parseInt(digits));
                } catch (NumberFormatException ignored) {
                    numbers.add(0);
                }
            }
            return new ParsedVersion(numbers, parts.length > 1 ? parts[1] : "");
        }
    }
}
