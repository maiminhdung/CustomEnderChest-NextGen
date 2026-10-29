package org.maiminhdung.customenderchest.utils;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Manages the "locking" of player data operations to prevent
 * asynchronous conflicts (race conditions).
 *
 * Locks are keyed by {@link UUID} (whole player) or {@link VaultKey}
 * (a single vault of a player).
 */
public class DataLockManager {

    private final Cache<Object, Boolean> lockedKeys = CacheBuilder.newBuilder()
            .expireAfterWrite(60, TimeUnit.SECONDS)
            .build();

    /**
     * Attempts to lock a key atomically.
     * @param key The key to lock (UUID or VaultKey).
     * @return true if the lock was acquired successfully, false if it was already locked.
     */
    public boolean lock(Object key) {
        return lockedKeys.asMap().putIfAbsent(key, Boolean.TRUE) == null;
    }

    /**
     * Unlocks a key.
     * @param key The key to unlock.
     */
    public void unlock(Object key) {
        lockedKeys.invalidate(key);
    }

    /**
     * Checks if a key is currently locked.
     * Note: Prefer using tryLock() or lockAndRun() to avoid race conditions.
     * @param key The key to check.
     * @return true if the key is locked.
     */
    public boolean isLocked(Object key) {
        return lockedKeys.asMap().containsKey(key);
    }

    /**
     * Atomically attempts to lock a key. This is the same as lock()
     * but with clearer intent for try-lock pattern.
     * @param key The key to lock.
     * @return true if the lock was acquired, false if already locked.
     */
    public boolean tryLock(Object key) {
        return lock(key);
    }

    /**
     * Executes a runnable while holding the lock.
     * If the lock cannot be acquired, does nothing and returns false.
     * The lock is always released after the runnable completes.
     * @param key The key to lock.
     * @param runnable The action to execute while holding the lock.
     * @return true if the lock was acquired and the action was executed.
     */
    public boolean lockAndRun(Object key, Runnable runnable) {
        if (!lock(key)) {
            return false;
        }
        try {
            runnable.run();
            return true;
        } finally {
            unlock(key);
        }
    }

    /**
     * Executes a supplier while holding the lock and returns the result.
     * If the lock cannot be acquired, returns null.
     * The lock is always released after the supplier completes.
     * @param <T> The return type.
     * @param key The key to lock.
     * @param supplier The action to execute while holding the lock.
     * @return The result of the supplier, or null if the lock was not acquired.
     */
    public <T> T lockAndGet(Object key, Supplier<T> supplier) {
        if (!lock(key)) {
            return null;
        }
        try {
            return supplier.get();
        } finally {
            unlock(key);
        }
    }
}
