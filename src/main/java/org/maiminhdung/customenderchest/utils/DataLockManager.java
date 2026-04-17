package org.maiminhdung.customenderchest.utils;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Manages the "locking" of player data operations to prevent
 * asynchronous conflicts (race conditions).
 */
public class DataLockManager {

    private final Set<UUID> lockedPlayers = ConcurrentHashMap.newKeySet();

    /**
     * Attempts to lock a UUID atomically.
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
     * Note: Prefer using tryLock() or lockAndRun() to avoid race conditions.
     * @param uuid The UUID to check.
     * @return true if the UUID is locked.
     */
    public boolean isLocked(UUID uuid) {
        return lockedPlayers.contains(uuid);
    }

    /**
     * Atomically attempts to lock a UUID. This is the same as lock()
     * but with clearer intent for try-lock pattern.
     * @param uuid The UUID to lock.
     * @return true if the lock was acquired, false if already locked.
     */
    public boolean tryLock(UUID uuid) {
        return lock(uuid);
    }

    /**
     * Executes a runnable while holding the lock.
     * If the lock cannot be acquired, does nothing and returns false.
     * The lock is always released after the runnable completes.
     * @param uuid The UUID to lock.
     * @param runnable The action to execute while holding the lock.
     * @return true if the lock was acquired and the action was executed.
     */
    public boolean lockAndRun(UUID uuid, Runnable runnable) {
        if (!lock(uuid)) {
            return false;
        }
        try {
            runnable.run();
            return true;
        } finally {
            unlock(uuid);
        }
    }

    /**
     * Executes a supplier while holding the lock and returns the result.
     * If the lock cannot be acquired, returns null.
     * The lock is always released after the supplier completes.
     * @param <T> The return type.
     * @param uuid The UUID to lock.
     * @param supplier The action to execute while holding the lock.
     * @return The result of the supplier, or null if the lock was not acquired.
     */
    public <T> T lockAndGet(UUID uuid, Supplier<T> supplier) {
        if (!lock(uuid)) {
            return null;
        }
        try {
            return supplier.get();
        } finally {
            unlock(uuid);
        }
    }
}