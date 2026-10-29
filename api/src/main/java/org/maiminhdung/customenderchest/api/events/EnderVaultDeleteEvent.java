package org.maiminhdung.customenderchest.api.events;

import org.bukkit.command.CommandSender;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Fired before {@code /<main> delete <player>} removes a player's vault data.
 * Cancelling aborts the deletion (no data is touched).
 */
public class EnderVaultDeleteEvent extends Event implements Cancellable {

	private static final HandlerList HANDLER_LIST = new HandlerList();

	private final CommandSender initiator;
	private final UUID targetPlayer;
	private final List<Integer> vaultIds;
	private boolean cancelled;

	public EnderVaultDeleteEvent(CommandSender initiator, UUID targetPlayer, List<Integer> vaultIds) {
		this.initiator = initiator;
		this.targetPlayer = targetPlayer;
		this.vaultIds = List.copyOf(vaultIds);
	}

	/** @return who ran the delete command (player or console) */
	public CommandSender getInitiator() {
		return initiator;
	}

	/** @return the UUID of the player whose data is being deleted */
	public UUID getTargetPlayer() {
		return targetPlayer;
	}

	/** @return the vault ids about to be deleted (includes legacy data as vault 1) */
	public List<Integer> getVaultIds() {
		return vaultIds;
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
