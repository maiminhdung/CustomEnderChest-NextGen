package org.maiminhdung.customenderchest.api;

/**
 * Entry point of the CustomEnderChest developer API.
 *
 * <pre>{@code
 * CustomEnderChest api = CustomEnderChestAPI.get();
 * api.getVaultContents(playerId, 1).thenAccept(items -> ...);
 * }</pre>
 *
 * The instance exists while the plugin is enabled; {@link #get()} throws
 * outside of that window.
 */
public final class CustomEnderChestAPI {

	private static volatile CustomEnderChest instance;

	private CustomEnderChestAPI() {
	}

	/**
	 * Internal: called by the plugin on enable/disable. Do not call from
	 * other plugins.
	 *
	 * @param implementation the running implementation, or null on disable
	 */
	public static void setInstance(CustomEnderChest implementation) {
		instance = implementation;
	}

	/**
	 * @return the running API implementation
	 * @throws IllegalStateException when the plugin is not enabled
	 */
	public static CustomEnderChest get() {
		CustomEnderChest current = instance;
		if (current == null) {
			throw new IllegalStateException(
					"The CustomEnderChest API is not available (plugin disabled or not installed).");
		}
		return current;
	}

	/** @return true while the plugin is enabled and the API is usable */
	public static boolean isAvailable() {
		return instance != null;
	}
}
