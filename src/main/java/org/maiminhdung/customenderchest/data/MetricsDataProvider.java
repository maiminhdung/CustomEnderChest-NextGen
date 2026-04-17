package org.maiminhdung.customenderchest.data;

import org.maiminhdung.customenderchest.EnderChest;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cached metrics data provider for FastStats.
 * Uses atomic counters for zero-overhead tracking on hot paths.
 * Values are read by FastStats on its own schedule.
 */
public class MetricsDataProvider {

    private final EnderChest plugin;

    // Atomic counters — incremented on hot paths (lock-free, ~5ns each)
    private final AtomicLong saveCount = new AtomicLong(0);
    private final AtomicLong loadCount = new AtomicLong(0);
    private final AtomicLong totalSaveTimeNanos = new AtomicLong(0);
    private final AtomicLong saveTimeSamples = new AtomicLong(0);

    public MetricsDataProvider(EnderChest plugin) {
        this.plugin = plugin;
    }

    // --- Called from hot paths (must be ultra-lightweight) ---

    /** Increment save counter. Call after each successful save. */
    public void recordSave() {
        saveCount.incrementAndGet();
    }

    /** Increment load counter. Call after each successful load. */
    public void recordLoad() {
        loadCount.incrementAndGet();
    }

    /** Record a save duration for average calculation. */
    public void recordSaveTime(long nanos) {
        totalSaveTimeNanos.addAndGet(nanos);
        saveTimeSamples.incrementAndGet();
    }

    // --- Read by FastStats metric suppliers ---

    public long getSaveCount() {
        return saveCount.get();
    }

    public long getLoadCount() {
        return loadCount.get();
    }

    /** Returns loaded chests count from the Guava live cache (O(1)). */
    public int getLoadedChests() {
        try {
            EnderChestManager manager = plugin.getEnderChestManager();
            return manager != null ? (int) manager.getLiveData().size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Returns currently open chests count (O(1)). */
    public int getOpenChests() {
        try {
            EnderChestManager manager = plugin.getEnderChestManager();
            return manager != null ? manager.getOpenInventories().size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Returns average save time in milliseconds since startup. */
    public double getAvgSaveTimeMs() {
        long samples = saveTimeSamples.get();
        if (samples == 0) return 0.0;
        return (totalSaveTimeNanos.get() / (double) samples) / 1_000_000.0;
    }
}
