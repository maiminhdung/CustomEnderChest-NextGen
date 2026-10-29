package org.maiminhdung.customenderchest.api.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

/**
 * Fired after a player closed one of their own vaults. The save has been
 * scheduled (asynchronously) but may not be flushed to storage yet.
 */
public class EnderVaultCloseEvent extends Event {

	private static final HandlerList HANDLER_LIST = new HandlerList();

	private final Player player;
	private final int vaultId;
	private final Inventory inventory;

	public EnderVaultCloseEvent(Player player, int vaultId, Inventory inventory) {
		this.player = player;
		this.vaultId = vaultId;
		this.inventory = inventory;
	}

	/** @return the player who closed the vault */
	public Player getPlayer() {
		return player;
	}

	/** @return the vault that was closed (1 = default Ender Chest) */
	public int getVaultId() {
		return vaultId;
	}

	/** @return the closed vault inventory (contents already persisted-pending) */
	public Inventory getInventory() {
		return inventory;
	}

	@Override
	public @NotNull HandlerList getHandlers() {
		return HANDLER_LIST;
	}

	public static HandlerList getHandlerList() {
		return HANDLER_LIST;
	}
}
