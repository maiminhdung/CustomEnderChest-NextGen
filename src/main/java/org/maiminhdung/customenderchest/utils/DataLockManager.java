package org.maiminhdung.customenderchest.utils;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Manages the "locking" of player data operations to prevent
 * asynchronous conflicts (race conditions).
 */
public class DataLockManager {

    private final Set<UUID> lockedPlayers = Collections.synchronizedSet(new HashSet<>());

    /**
     * Attempts to lock a UUID.
     * @param uuid The UUID to lock.
     * @return true if the lock was acquired successfully, false if it was already locked.
     */
    public boolean lock(UUID uuid) {
        return lockedPlayers.add(uuid);
    }

    /**
     * Unlocks a UUID.
     * @param uuid The UUID to unlock.
     */
    public void unlock(UUID uuid) {
        lockedPlayers.remove(uuid);
    }

    /**
     * Checks if a UUID is currently locked.
     * @param uuid The UUID to check.
     * @return true if the UUID is locked.
     */
    public boolean isLocked(UUID uuid) {
        return lockedPlayers.contains(uuid);
    }
}