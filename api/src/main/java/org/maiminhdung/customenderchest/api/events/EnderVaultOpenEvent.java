package org.maiminhdung.customenderchest.api.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

/**
 * Fired before a player's own vault inventory is opened (command, block
 * click, or {@code CustomEnderChestAPI#openVault}). Cancelling prevents the
 * open entirely.
 *
 * <p>Admin views of other players' vaults do not fire this event.</p>
 */
public class EnderVaultOpenEvent extends Event implements Cancellable {

	private static final HandlerList HANDLER_LIST = new HandlerList();

	private final Player player;
	private final int vaultId;
	private final Inventory inventory;
	private boolean cancelled;

	public EnderVaultOpenEvent(Player player, int vaultId, Inventory inventory) {
		this.player = player;
		this.vaultId = vaultId;
		this.inventory = inventory;
	}

	/** @return the player about to view the vault */
	public Player getPlayer() {
		return player;
	}

	/** @return the vault being opened (1 = default Ender Chest) */
	public int getVaultId() {
		return vaultId;
	}

	/** @return the vault inventory that will be shown (read-only view of the live data) */
	public Inventory getInventory() {
		return inventory;
	}

	@Override
	public boolean isCancelled() {
		return cancelled;
	}

	@Override
	public void setCancelled(boolean cancel) {
		this.cancelled = cancel;
	}

	@Override
	public @NotNull HandlerList getHandlers() {
		return HANDLER_LIST;
	}

	public static HandlerList getHandlerList() {
		return HANDLER_LIST;
	}
}
