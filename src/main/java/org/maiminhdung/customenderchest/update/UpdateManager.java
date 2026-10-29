package org.maiminhdung.customenderchest.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.command.CommandSender;
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

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

/**
 * Checks Modrinth for newer plugin releases.
 *
 * Project: https://modrinth.com/plugin/custom-ender-chest (id AipGDIso).
 * A version counts as compatible only when it targets a server-plugin loader
 * (paper/folia/purpur), is listed in the configured update channel
 * ({@code general.update-channel}: release &lt; beta &lt; alpha) and lists the
 * running Minecraft version in its game_versions. The check runs once on
 * startup; admins are notified in-game on join while an update is pending.
 */
public final class UpdateManager {

	private static final String PROJECT_ID = "AipGDIso";
	private static final String MODRINTH_PAGE = "https://modrinth.com/plugin/custom-ender-chest";
	private static final URI VERSIONS_URI = URI.create("https://api.modrinth.com/v2/project/" + PROJECT_ID + "/version");

	/** Loader names under which this plugin is published on Modrinth. */
	private static final List<String> PLUGIN_LOADERS = List.of("paper", "folia", "purpur");

	/** Release-type rank: a version is allowed when its rank &lt;= channel rank. */
	private static final int RANK_RELEASE = 0;
	private static final int RANK_BETA = 1;
	private static final int RANK_ALPHA = 2;

	private final EnderChest plugin;
	private final HttpClient httpClient;

	/** Latest compatible release found by the last check (null before it finishes). */
	private volatile UpdateResult lastResult;

	/** Everything the UI needs to know about the latest compatible release. */
	public record UpdateResult(String versionNumber, String versionId, String downloadUrl, String serverGameVersion) {
	}

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

					UpdateResult latest = findLatestCompatible(response.body());
					if (latest == null) {
						plugin.getLogger().warning("Modrinth returned no release matching this server "
								+ "(channel '" + configuredChannel() + "', loader paper/folia/purpur, Minecraft "
								+ serverGameVersion() + "). See " + MODRINTH_PAGE + " for all versions.");
						return;
					}
					lastResult = latest;

					String currentVersion = currentVersion();
					if (compareVersions(latest.versionNumber(), currentVersion) > 0) {
						plugin.getLogger().warning("A new CustomEnderChest version is available: "
								+ latest.versionNumber() + " (current: " + currentVersion + ").");
						plugin.getLogger().warning("Download: " + latest.downloadUrl());
						plugin.getLogger().warning("Admins receive this notice in-game on join.");
					} else {
						plugin.getLogger().info("CustomEnderChest is up to date (" + currentVersion
								+ ", latest matching: " + latest.versionNumber() + ").");
					}
				})
				.exceptionally(error -> {
					plugin.getLogger().log(Level.WARNING,
							"Unable to check Modrinth for updates: " + rootMessage(error));
					return null;
				});
	}

	/** True when the last check found a newer compatible release. */
	public boolean isUpdateAvailable() {
		UpdateResult result = lastResult;
		return result != null && compareVersions(result.versionNumber(), currentVersion()) > 0;
	}

	public UpdateResult getLastResult() {
		return lastResult;
	}

	/** Sends the localized update notice to a player or the console. */
	public void sendUpdateNotice(CommandSender sender) {
		UpdateResult result = lastResult;
		if (result == null) {
			return;
		}
		sender.sendMessage(plugin.getLocaleManager().getPrefixedComponent("messages.update-available",
				Placeholder.unparsed("latest", result.versionNumber()),
				Placeholder.unparsed("current", currentVersion()),
				Placeholder.unparsed("link", result.downloadUrl())));
	}

	// ==================== candidate filtering ====================

	/**
	 * Picks the highest compatible release from the Modrinth versions payload.
	 */
	UpdateResult findLatestCompatible(String json) {
		JsonElement root = JsonParser.parseString(json);
		if (!root.isJsonArray()) {
			return null;
		}

		int channelRank = channelRank(configuredChannel());
		String serverGameVersion = serverGameVersion();

		UpdateResult best = null;
		for (JsonElement element : root.getAsJsonArray()) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject version = element.getAsJsonObject();

			if (!"listed".equalsIgnoreCase(getString(version, "status"))) {
				continue;
			}
			if (!matchesChannel(getString(version, "version_type"), channelRank)) {
				continue;
			}
			if (!hasSupportedLoader(version.getAsJsonArray("loaders"))) {
				continue;
			}
			if (!supportsGameVersion(toStringList(version.getAsJsonArray("game_versions")), serverGameVersion)) {
				continue;
			}

			String versionNumber = getString(version, "version_number");
			String versionId = getString(version, "id");
			if (versionNumber.isBlank() || versionId.isBlank()) {
				continue;
			}

			if (best == null || compareVersions(versionNumber, best.versionNumber()) > 0) {
				best = new UpdateResult(versionNumber, versionId,
						MODRINTH_PAGE + "/version/" + versionId, serverGameVersion);
			}
		}
		return best;
	}

	/**
	 * Whether a release of the given type is visible in the configured channel.
	 * A stricter channel never shows looser releases (release is visible in
	 * every channel; alpha only in the alpha channel).
	 */
	static boolean matchesChannel(String versionType, int channelRank) {
		int rank = releaseRank(versionType);
		if (rank < 0) {
			return false;
		}
		return rank <= channelRank;
	}

	static int releaseRank(String versionType) {
		if (versionType == null) {
			return -1;
		}
		return switch (versionType.toLowerCase(Locale.ROOT)) {
			case "release" -> RANK_RELEASE;
			case "beta" -> RANK_BETA;
			case "alpha" -> RANK_ALPHA;
			default -> -1;
		};
	}

	static int channelRank(String channel) {
		int rank = releaseRank(channel);
		return rank < 0 ? RANK_RELEASE : rank;
	}

	/** True for the loader names this plugin is published under. */
	static boolean isSupportedLoader(String loader) {
		if (loader == null) {
			return false;
		}
		String normalized = loader.toLowerCase(Locale.ROOT);
		return PLUGIN_LOADERS.contains(normalized);
	}

	private boolean hasSupportedLoader(JsonArray loaders) {
		if (loaders == null) {
			return false;
		}
		for (JsonElement loader : loaders) {
			if (loader.isJsonPrimitive() && isSupportedLoader(loader.getAsString())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether the release lists the running Minecraft version. An empty list is
	 * treated as compatible (metadata missing). A bare minor entry (e.g.
	 * "1.21") covers every patch of that series, but only while the release
	 * lists no more specific patch (once "1.21.1" is listed, the list is
	 * trusted as exact).
	 */
	static boolean supportsGameVersion(List<String> gameVersions, String serverVersion) {
		if (gameVersions == null || gameVersions.isEmpty()) {
			return true;
		}
		if (serverVersion == null || serverVersion.isBlank()) {
			return true;
		}
		if (gameVersions.contains(serverVersion)) {
			return true;
		}
		int lastDot = serverVersion.lastIndexOf('.');
		if (lastDot <= 0) {
			return false;
		}
		String minorSeries = serverVersion.substring(0, lastDot); // "1.21.1" -> "1.21"
		boolean listsSpecificPatch = gameVersions.stream().anyMatch(v -> v.startsWith(minorSeries + "."));
		if (listsSpecificPatch) {
			return false;
		}
		return gameVersions.contains(minorSeries);
	}

	// ==================== small helpers ====================

	private String configuredChannel() {
		String configured = plugin.config().getString("general.update-channel", "release")
				.trim().toLowerCase(Locale.ROOT);
		if (releaseRank(configured) < 0) {
			plugin.getLogger().warning("Invalid general.update-channel '" + configured
					+ "'. Using 'release'. Valid values: release, beta, alpha.");
			return "release";
		}
		return configured;
	}

	/** Server Minecraft version, e.g. "1.21.1" (patch/snapshot suffix stripped). */
	private String serverGameVersion() {
		String bukkitVersion = plugin.getServer().getBukkitVersion(); // e.g. "1.21.1-R0.1-SNAPSHOT"
		int dash = bukkitVersion.indexOf('-');
		return dash > 0 ? bukkitVersion.substring(0, dash) : bukkitVersion;
	}

	private String currentVersion() {
		return plugin.getPluginMeta().getVersion();
	}

	private static List<String> toStringList(JsonArray array) {
		List<String> values = new ArrayList<>();
		if (array == null) {
			return values;
		}
		for (JsonElement element : array) {
			if (element.isJsonPrimitive()) {
				values.add(element.getAsString());
			}
		}
		return values;
	}

	private static String getString(JsonObject object, String key) {
		JsonElement value = object.get(key);
		return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
	}

	// ==================== version comparison (unit tested) ====================

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
		return "CustomEnderChest/" + currentVersion()
				+ " (+https://github.com/maiminhdung/CustomEnderChest-NextGen)";
	}

	private String rootMessage(Throwable throwable) {
		Throwable current = throwable;
		while (current.getCause() != null) {
			current = current.getCause();
		}
		return current.getMessage() != null ? current.getMessage() : current.getClass().getSimpleName();
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
