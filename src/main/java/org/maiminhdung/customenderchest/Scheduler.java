package org.maiminhdung.customenderchest;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Universal scheduler utility that supports both traditional Bukkit scheduling
 * and Folia's region-based scheduling system.
 * <p>
 * Based on UniversalScheduler by Anon8281 with customizations for CustomEnderChest.
 * <p>
 * This class automatically detects which server implementation is being used
 * and provides appropriate scheduling methods.
 */
public final class Scheduler {

    private static final Plugin plugin;
    private static final boolean isFolia;
    private static final boolean isCanvas;
    private static final boolean isExpandedSchedulingAvailable;

    static {
        plugin = EnderChest.getInstance();

        // Check for Canvas (Folia fork)
        isCanvas = classExists("io.canvasmc.canvas.server.ThreadedServer");
        
        // Check if we're running on Folia
        isFolia = classExists("io.papermc.paper.threadedregions.RegionizedServer");
        
        // Check for expanded scheduling (Paper 1.20+)
        isExpandedSchedulingAvailable = classExists("io.papermc.paper.threadedregions.scheduler.ScheduledTask");

        if (isFolia || isCanvas) {
            plugin.getLogger().info((isCanvas ? "Canvas" : "Folia") + " detected! Using region-based threading system.");
        } else if (isExpandedSchedulingAvailable) {
            plugin.getLogger().info("Paper with expanded scheduling detected.");
        } else {
            plugin.getLogger().info("Running on standard Bukkit/Spigot server.");
        }
    }

    private static boolean classExists(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Check if running on Folia or Canvas (region-based threading).
     * @return true if Folia/Canvas is detected
     */
    public static boolean isFolia() {
        return isFolia || isCanvas;
    }

    /**
     * Check if current thread is the global tick thread (Folia) or primary thread (Bukkit).
     * @return true if on global/primary thread
     */
    public static boolean isGlobalThread() {
        if (isFolia || isCanvas) {
            try {
                return (boolean) Bukkit.getServer().getClass().getMethod("isGlobalTickThread").invoke(Bukkit.getServer());
            } catch (Exception e) {
                return Bukkit.getServer().isPrimaryThread();
            }
        }
        return Bukkit.getServer().isPrimaryThread();
    }

    /**
     * Check if current thread owns the specified entity's region.
     * @param entity The entity to check
     * @return true if current thread owns the entity's region
     */
    public static boolean isEntityThread(Entity entity) {
        if ((isFolia || isCanvas) && entity != null) {
            return Bukkit.getServer().isOwnedByCurrentRegion(entity);
        }
        return Bukkit.getServer().isPrimaryThread();
    }

    /**
     * Check if current thread owns the specified location's region.
     * @param location The location to check
     * @return true if current thread owns the location's region
     */
    public static boolean isRegionThread(Location location) {
        if ((isFolia || isCanvas) && location != null && location.getWorld() != null) {
            return Bukkit.getServer().isOwnedByCurrentRegion(location);
        }
        return Bukkit.getServer().isPrimaryThread();
    }

    // ==================== GLOBAL REGION TASKS ====================

    /**
     * Runs a task on the main thread (or global region in Folia).
     *
     * @param runnable The task to run
     * @return A Task object representing the scheduled task
     */
    public static Task runTask(Runnable runnable) {
        if (isFolia || isCanvas) {
            try {
                io.papermc.paper.threadedregions.scheduler.ScheduledTask task =
                        Bukkit.getGlobalRegionScheduler().run(plugin, scheduledTask -> runnable.run());
                return new Task(task);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error scheduling task in Folia/Canvas", e);
                return new Task(null);
            }
        } else if (isExpandedSchedulingAvailable) {
            try {
                io.papermc.paper.threadedregions.scheduler.ScheduledTask task =
                        Bukkit.getGlobalRegionScheduler().run(plugin, scheduledTask -> runnable.run());
                return new Task(task);
            } catch (Exception e) {
                return new Task(Bukkit.getScheduler().runTask(plugin, runnable));
            }
        } else {
            return new Task(Bukkit.getScheduler().runTask(plugin, runnable));
        }
    }

    /**
     * Runs a task after a specified delay.
     *
     * @param runnable   The task to run
     * @param delayTicks The delay in ticks before running the task
     * @return A Task object representing the scheduled task
     */
    public static Task runTaskLater(Runnable runnable, long delayTicks) {
        // Folia exception: Delay ticks may not be <= 0
        if (delayTicks <= 0) {
            return runTask(runnable);
        }
        
        if (isFolia || isCanvas) {
            try {
                io.papermc.paper.threadedregions.scheduler.ScheduledTask task =
                        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, scheduledTask -> runnable.run(), delayTicks);
                return new Task(task);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error scheduling delayed task in Folia/Canvas", e);
                return new Task(null);
            }
        } else if (isExpandedSchedulingAvailable) {
            try {
                io.papermc.paper.threadedregions.scheduler.ScheduledTask task =
                        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, scheduledTask -> runnable.run(), delayTicks);
                return new Task(task);
            } catch (Exception e) {
                return new Task(Bukkit.getScheduler().runTaskLater(plugin, runnable, delayTicks));
            }
        } else {
            return new Task(Bukkit.getScheduler().runTaskLater(plugin, runnable, delayTicks));
        }
    }

    /**
     * Runs a task repeatedly at fixed intervals.
     *
     * @param runnable    The task to run
     * @param delayTicks  The initial delay in ticks before the first execution
     * @param periodTicks The period in ticks between subsequent executions
     * @return A Task object representing the scheduled task
     */
    public static Task runTaskTimer(Runnable runnable, long delayTicks, long periodTicks) {
        // Folia exception: Delay ticks may not be <= 0
        if (delayTicks <= 0) {
            delayTicks = 1;
        }
        
        if (isFolia || isCanvas) {
            try {
                io.papermc.paper.threadedregions.scheduler.ScheduledTask task =
                        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, scheduledTask -> runnable.run(),
                                delayTicks, periodTicks);
                return new Task(task);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error scheduling timer task in Folia/Canvas", e);
                return new Task(null);
            }
        } else if (isExpandedSchedulingAvailable) {
            try {
                io.papermc.paper.threadedregions.scheduler.ScheduledTask task =
                        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, scheduledTask -> runnable.run(),
                                delayTicks, periodTicks);
                return new Task(task);
            } catch (Exception e) {
                return new Task(Bukkit.getScheduler().runTaskTimer(plugin, runnable, delayTicks, periodTicks));
            }
        } else {
            return new Task(Bukkit.getScheduler().runTaskTimer(plugin, runnable, delayTicks, periodTicks));
        }
    }

    // ==================== ASYNC TASKS ====================

    /**
     * Runs a task asynchronously.
     * <p>
     * WARNING: On Folia, excessive async usage can cause race conditions.
     * For data-critical operations, prefer entity/region tasks.
     *
     * @param runnable The task to run
     * @return A Task object representing the scheduled task
     */
    public static Task runTaskAsync(Runnable runnable) {
        if (isFolia || isCanvas || isExpandedSchedulingAvailable) {
            try {
                io.papermc.paper.threadedregions.scheduler.ScheduledTask task =
                        Bukkit.getAsyncScheduler().runNow(plugin, scheduledTask -> runnable.run());
                return new Task(task);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error scheduling async task", e);
                return new Task(null);
            }
        } else {
            return new Task(Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable));
        }
    }

    /**
     * Runs a task asynchronously after a specified delay.
     *
     * @param runnable   The task to run
     * @param delayTicks The delay in ticks before running the task
     * @return A Task object representing the scheduled task
     */
    public static Task runTaskLaterAsync(Runnable runnable, long delayTicks) {
        // Folia exception: Delay ticks may not be <= 0
        if (delayTicks <= 0) {
            delayTicks = 1;
        }
        
        if (isFolia || isCanvas || isExpandedSchedulingAvailable) {
            try {
                long delayMs = delayTicks * 50L; // Convert ticks to milliseconds
                io.papermc.paper.threadedregions.scheduler.ScheduledTask task =
                        Bukkit.getAsyncScheduler().runDelayed(plugin, scheduledTask -> runnable.run(),
                                delayMs, TimeUnit.MILLISECONDS);
                return new Task(task);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error scheduling delayed async task", e);
                return new Task(null);
            }
        } else {
            return new Task(Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, runnable, delayTicks));
        }
    }

    /**
     * Runs a task repeatedly at fixed intervals asynchronously.
     *
     * @param runnable    The task to run
     * @param delayTicks  The initial delay in ticks before the first execution
     * @param periodTicks The period in ticks between subsequent executions
     * @return A Task object representing the scheduled task
     */
    public static Task runTaskTimerAsync(Runnable runnable, long delayTicks, long periodTicks) {
        // Folia exception: Delay ticks may not be <= 0
        if (delayTicks <= 0) {
            delayTicks = 1;
        }
        
        if (isFolia || isCanvas || isExpandedSchedulingAvailable) {
            try {
                // Convert ticks to milliseconds (1 tick = 50ms)
                long delayMs = delayTicks * 50L;
                long periodMs = periodTicks * 50L;

                io.papermc.paper.threadedregions.scheduler.ScheduledTask task =
                        Bukkit.getAsyncScheduler().runAtFixedRate(plugin, scheduledTask -> runnable.run(),
                                delayMs, periodMs, TimeUnit.MILLISECONDS);
                return new Task(task);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error scheduling timer async task", e);
                return new Task(null);
            }
        } else {
            return new Task(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, runnable, delayTicks, periodTicks));
        }
    }

    // ==================== ENTITY TASKS ====================

    /**
     * Runs a task in the region of a specific entity.
     * <p>
     * CRITICAL FOR FOLIA: This ensures the task runs on the correct region thread
     * that owns the entity. This is essential for data safety on Folia.
     * <p>
     * Falls back to global scheduler if entity is in unloaded region or on non-Folia servers.
     *
     * @param entity   The entity in whose region to run the task
     * @param runnable The task to run
     * @return A Task object representing the scheduled task
     */
    public static Task runEntityTask(Entity entity, Runnable runnable) {
        if ((isFolia || isCanvas) && entity != null) {
            try {
                io.papermc.paper.threadedregions.scheduler.ScheduledTask task =
                        entity.getScheduler().run(plugin, scheduledTask -> runnable.run(), null);
                // In Folia, run() returns null if entity is in an unloaded region
                // The runnable will NEVER execute in this case, causing silent data loss!
                if (task == null) {
                    plugin.getLogger().warning("[Folia] Entity task returned null for " + entity.getName() + 
                            " (entity may be in unloaded region). Executing on global scheduler.");
                    return runTask(runnable);
                }
                return new Task(task);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error scheduling entity task, falling back to global scheduler", e);
                return runTask(runnable);
            }
        } else if (isExpandedSchedulingAvailable && entity != null) {
            try {
                io.papermc.paper.threadedregions.scheduler.ScheduledTask task =
                        entity.getScheduler().run(plugin, scheduledTask -> runnable.run(), null);
                if (task == null) {
                    return runTask(runnable);
                }
                return new Task(task);
            } catch (Exception e) {
                return runTask(runnable);
            }
        } else {
            return runTask(runnable);
        }
    }

    /**
     * Runs a delayed task in the region of a specific entity.
     *
     * @param entity     The entity in whose region to run the task
     * @param runnable   The task to run
     * @param delayTicks The delay in ticks before running the task
     * @return A Task object representing the scheduled task
     */
    public static Task runEntityTaskLater(Entity entity, Runnable runnable, long delayTicks) {
        // Folia exception: Delay ticks may not be <= 0
        if (delayTicks <= 0) {
            return runEntityTask(entity, runnable);
        }
        
        if ((isFolia || isCanvas) && entity != null) {
            try {
                io.papermc.paper.threadedregions.scheduler.ScheduledTask task =
                        entity.getScheduler().runDelayed(plugin, scheduledTask -> runnable.run(), null, delayTicks);
                if (task == null) {
                    plugin.getLogger().warning("[Folia] Delayed entity task returned null. Executing on global scheduler.");
                    return runTaskLater(runnable, delayTicks);
                }
                return new Task(task);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error scheduling delayed entity task, falling back to global scheduler", e);
                return runTaskLater(runnable, delayTicks);
            }
        } else if (isExpandedSchedulingAvailable && entity != null) {
            try {
                io.papermc.paper.threadedregions.scheduler.ScheduledTask task =
                        entity.getScheduler().runDelayed(plugin, scheduledTask -> runnable.run(), null, delayTicks);
                if (task == null) {
                    return runTaskLater(runnable, delayTicks);
                }
                return new Task(task);
            } catch (Exception e) {
                return runTaskLater(runnable, delayTicks);
            }
        } else {
            return runTaskLater(runnable, delayTicks);
        }
    }

    /**
     * Runs a repeated task in the region of a specific entity.
     *
     * @param entity     The entity in whose region to run the task
     * @param runnable   The task to run
     * @param delayTicks The initial delay in ticks before the first execution
     * @param periodTicks The period in ticks between subsequent executions
     * @return A Task object representing the scheduled task
     */
    public static Task runEntityTaskTimer(Entity entity, Runnable runnable, long delayTicks, long periodTicks) {
        // Folia exception: Delay ticks may not be <= 0
        if (delayTicks <= 0) {
            delayTicks = 1;
        }
        
        if ((isFolia || isCanvas) && entity != null) {
            try {
                io.papermc.paper.threadedregions.scheduler.ScheduledTask task =
                        entity.getScheduler().runAtFixedRate(plugin, scheduledTask -> runnable.run(), null,
                                delayTicks, periodTicks);
                if (task == null) {
                    plugin.getLogger().warning("[Folia] Timer entity task returned null. Falling back to global scheduler.");
                    return runTaskTimer(runnable, delayTicks, periodTicks);
                }
                return new Task(task);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error scheduling timer entity task, falling back to global scheduler", e);
                return runTaskTimer(runnable, delayTicks, periodTicks);
            }
        } else if (isExpandedSchedulingAvailable && entity != null) {
            try {
                io.papermc.paper.threadedregions.scheduler.ScheduledTask task =
                        entity.getScheduler().runAtFixedRate(plugin, scheduledTask -> runnable.run(), null,
                                delayTicks, periodTicks);
                if (task == null) {
                    return runTaskTimer(runnable, delayTicks, periodTicks);
                }
                return new Task(task);
            } catch (Exception e) {
                return runTaskTimer(runnable, delayTicks, periodTicks);
            }
        } else {
            return runTaskTimer(runnable, delayTicks, periodTicks);
        }
    }

    // ==================== LOCATION/REGION TASKS ====================

    /**
     * Runs a task in the region of a specific location.
     * Falls back to regular scheduling on non-Folia servers.
     *
     * @param location The location in whose region to run the task
     * @param runnable The task to run
     * @return A Task object representing the scheduled task
     */
    public static Task runLocationTask(Location location, Runnable runnable) {
        if ((isFolia || isCanvas) && location != null && location.getWorld() != null) {
            try {
                io.papermc.paper.threadedregions.scheduler.ScheduledTask task =
                        Bukkit.getRegionScheduler().run(plugin, location, scheduledTask -> runnable.run());
                return new Task(task);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error scheduling location task, falling back to global scheduler", e);
                return runTask(runnable);
            }
        } else if (isExpandedSchedulingAvailable && location != null && location.getWorld() != null) {
            try {
                io.papermc.paper.threadedregions.scheduler.ScheduledTask task =
                        Bukkit.getRegionScheduler().run(plugin, location, scheduledTask -> runnable.run());
                return new Task(task);
            } catch (Exception e) {
                return runTask(runnable);
            }
        } else {
            return runTask(runnable);
        }
    }

    /**
     * Runs a delayed task in the region of a specific location.
     *
     * @param location   The location in whose region to run the task
     * @param runnable   The task to run
     * @param delayTicks The delay in ticks before running the task
     * @return A Task object representing the scheduled task
     */
    public static Task runLocationTaskLater(Location location, Runnable runnable, long delayTicks) {
        // Folia exception: Delay ticks may not be <= 0
        if (delayTicks <= 0) {
            return runLocationTask(location, runnable);
        }
        
        if ((isFolia || isCanvas) && location != null && location.getWorld() != null) {
            try {
                io.papermc.paper.threadedregions.scheduler.ScheduledTask task =
                        Bukkit.getRegionScheduler().runDelayed(plugin, location, scheduledTask -> runnable.run(), delayTicks);
                return new Task(task);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error scheduling delayed location task, falling back to global scheduler", e);
                return runTaskLater(runnable, delayTicks);
            }
        } else if (isExpandedSchedulingAvailable && location != null && location.getWorld() != null) {
            try {
                io.papermc.paper.threadedregions.scheduler.ScheduledTask task =
                        Bukkit.getRegionScheduler().runDelayed(plugin, location, scheduledTask -> runnable.run(), delayTicks);
                return new Task(task);
            } catch (Exception e) {
                return runTaskLater(runnable, delayTicks);
            }
        } else {
            return runTaskLater(runnable, delayTicks);
        }
    }

    /**
     * Runs a repeated task in the region of a specific location.
     *
     * @param location   The location in whose region to run the task
     * @param runnable   The task to run
     * @param delayTicks The initial delay in ticks before the first execution
     * @param periodTicks The period in ticks between subsequent executions
     * @return A Task object representing the scheduled task
     */
    public static Task runLocationTaskTimer(Location location, Runnable runnable, long delayTicks, long periodTicks) {
        // Folia exception: Delay ticks may not be <= 0
        if (delayTicks <= 0) {
            delayTicks = 1;
        }
        
        if ((isFolia || isCanvas) && location != null && location.getWorld() != null) {
            try {
                io.papermc.paper.threadedregions.scheduler.ScheduledTask task =
                        Bukkit.getRegionScheduler().runAtFixedRate(plugin, location, scheduledTask -> runnable.run(),
                                delayTicks, periodTicks);
                return new Task(task);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error scheduling timer location task, falling back to global scheduler", e);
                return runTaskTimer(runnable, delayTicks, periodTicks);
            }
        } else if (isExpandedSchedulingAvailable && location != null && location.getWorld() != null) {
            try {
                io.papermc.paper.threadedregions.scheduler.ScheduledTask task =
                        Bukkit.getRegionScheduler().runAtFixedRate(plugin, location, scheduledTask -> runnable.run(),
                                delayTicks, periodTicks);
                return new Task(task);
            } catch (Exception e) {
                return runTaskTimer(runnable, delayTicks, periodTicks);
            }
        } else {
            return runTaskTimer(runnable, delayTicks, periodTicks);
        }
    }

    /**
     * Alias for runLocationTask for backward compatibility.
     */
    public static Task runWorldTask(Location location, Runnable runnable) {
        return runLocationTask(location, runnable);
    }

    /**
     * Alias for runLocationTaskLater for backward compatibility.
     */
    public static Task runWorldTaskLater(Location location, Runnable runnable, long delayTicks) {
        return runLocationTaskLater(location, runnable, delayTicks);
    }

    // ==================== EXECUTE (Fire and forget) ====================

    /**
     * Schedules a task to be executed on the global region (fire and forget).
     * @param runnable The task to execute
     */
    public static void execute(Runnable runnable) {
        if (isFolia || isCanvas || isExpandedSchedulingAvailable) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, runnable);
        } else {
            Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, runnable);
        }
    }

    /**
     * Schedules a task to be executed on the region which owns the location (fire and forget).
     * @param location The location which the region executing should own
     * @param runnable The task to execute
     */
    public static void execute(Location location, Runnable runnable) {
        if ((isFolia || isCanvas || isExpandedSchedulingAvailable) && location != null && location.getWorld() != null) {
            Bukkit.getRegionScheduler().execute(plugin, location, runnable);
        } else {
            execute(runnable);
        }
    }

    /**
     * Schedules a task to be executed on the region which owns the entity (fire and forget).
     * @param entity The entity which location the region executing should own
     * @param runnable The task to execute
     */
    public static void execute(Entity entity, Runnable runnable) {
        if ((isFolia || isCanvas || isExpandedSchedulingAvailable) && entity != null) {
            entity.getScheduler().execute(plugin, runnable, null, 1L);
        } else {
            execute(runnable);
        }
    }

    // ==================== COMPLETABLE FUTURE HELPERS ====================

    /**
     * Creates a CompletableFuture that will be completed on the main thread or global region.
     *
     * @param <T>      The type of the result
     * @param supplier The supplier providing the result
     * @return A CompletableFuture that will be completed with the result
     */
    public static <T> CompletableFuture<T> supplySync(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();

        try {
            if (isFolia || isCanvas || isExpandedSchedulingAvailable) {
                Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                    try {
                        future.complete(supplier.get());
                    } catch (Throwable t) {
                        future.completeExceptionally(t);
                        plugin.getLogger().log(Level.SEVERE, "Error while executing sync task", t);
                    }
                });
            } else {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        future.complete(supplier.get());
                    } catch (Throwable t) {
                        future.completeExceptionally(t);
                        plugin.getLogger().log(Level.SEVERE, "Error while executing sync task", t);
                    }
                });
            }
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }

        return future;
    }

    /**
     * Creates a CompletableFuture that will be completed asynchronously.
     * <p>
     * WARNING: On Folia, async operations should be used carefully.
     * Results should be processed on the correct region thread using runEntityTask.
     *
     * @param <T>      The type of the result
     * @param supplier The supplier providing the result
     * @return A CompletableFuture that will be completed with the result
     */
    public static <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();

        try {
            if (isFolia || isCanvas || isExpandedSchedulingAvailable) {
                Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                    try {
                        future.complete(supplier.get());
                    } catch (Throwable t) {
                        future.completeExceptionally(t);
                        plugin.getLogger().log(Level.SEVERE, "Error while executing async task", t);
                    }
                });
            } else {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        future.complete(supplier.get());
                    } catch (Throwable t) {
                        future.completeExceptionally(t);
                        plugin.getLogger().log(Level.SEVERE, "Error while executing async task", t);
                    }
                });
            }
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }

        return future;
    }

    // ==================== CANCEL TASKS ====================

    /**
     * Cancels all tasks scheduled by this plugin.
     */
    public static void cancelTasks() {
        if (isFolia || isCanvas || isExpandedSchedulingAvailable) {
            Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
            Bukkit.getAsyncScheduler().cancelTasks(plugin);
        } else {
            Bukkit.getScheduler().cancelTasks(plugin);
        }
    }

    // ==================== TASK WRAPPER CLASS ====================

    /**
     * Wrapper class for both Bukkit and Folia tasks.
     */
    public static class Task {
        private final Object task;

        /**
         * Creates a new Task.
         *
         * @param task The underlying task object
         */
        Task(Object task) {
            this.task = task;
        }

        /**
         * Cancels the task.
         */
        public void cancel() {
            if (task == null) {
                return;
            }

            try {
                if (isFolia || isCanvas || isExpandedSchedulingAvailable) {
                    if (task instanceof io.papermc.paper.threadedregions.scheduler.ScheduledTask) {
                        ((io.papermc.paper.threadedregions.scheduler.ScheduledTask) task).cancel();
                    }
                } else {
                    if (task instanceof BukkitTask) {
                        ((BukkitTask) task).cancel();
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to cancel task", e);
            }
        }

        /**
         * Gets the underlying task object.
         *
         * @return The underlying task object
         */
        public Object getTask() {
            return task;
        }

        /**
         * Checks if this task is cancelled.
         *
         * @return true if the task is cancelled
         */
        public boolean isCancelled() {
            if (task == null) {
                return true;
            }

            try {
                if (isFolia || isCanvas || isExpandedSchedulingAvailable) {
                    if (task instanceof io.papermc.paper.threadedregions.scheduler.ScheduledTask) {
                        return ((io.papermc.paper.threadedregions.scheduler.ScheduledTask) task).isCancelled();
                    }
                } else {
                    if (task instanceof BukkitTask) {
                        return ((BukkitTask) task).isCancelled();
                    }
                }
            } catch (Exception ignored) {
                // Task may have already been garbage collected or is invalid
            }

            return true;
        }

        /**
         * Checks if this task is currently running.
         *
         * @return true if the task is currently running
         */
        public boolean isCurrentlyRunning() {
            if (task == null) {
                return false;
            }

            try {
                if (isFolia || isCanvas || isExpandedSchedulingAvailable) {
                    if (task instanceof io.papermc.paper.threadedregions.scheduler.ScheduledTask scheduledTask) {
                        io.papermc.paper.threadedregions.scheduler.ScheduledTask.ExecutionState state = scheduledTask.getExecutionState();
                        return state == io.papermc.paper.threadedregions.scheduler.ScheduledTask.ExecutionState.RUNNING ||
                               state == io.papermc.paper.threadedregions.scheduler.ScheduledTask.ExecutionState.CANCELLED_RUNNING;
                    }
                } else {
                    if (task instanceof BukkitTask bukkitTask) {
                        return Bukkit.getScheduler().isCurrentlyRunning(bukkitTask.getTaskId());
                    }
                }
            } catch (Exception ignored) {
            }

            return false;
        }

        /**
         * Checks if this task is a repeating task.
         *
         * @return true if the task is repeating
         */
        public boolean isRepeatingTask() {
            if (task == null) {
                return false;
            }

            try {
                if (isFolia || isCanvas || isExpandedSchedulingAvailable) {
                    if (task instanceof io.papermc.paper.threadedregions.scheduler.ScheduledTask scheduledTask) {
                        return scheduledTask.isRepeatingTask();
                    }
                }
            } catch (Exception ignored) {
            }

            return false;
        }

        /**
         * Gets the owning plugin for this task.
         *
         * @return The plugin that owns this task, or null
         */
        public Plugin getOwningPlugin() {
            if (task == null) {
                return null;
            }

            try {
                if (isFolia || isCanvas || isExpandedSchedulingAvailable) {
                    if (task instanceof io.papermc.paper.threadedregions.scheduler.ScheduledTask scheduledTask) {
                        return scheduledTask.getOwningPlugin();
                    }
                } else {
                    if (task instanceof BukkitTask bukkitTask) {
                        return bukkitTask.getOwner();
                    }
                }
            } catch (Exception ignored) {
            }

            return null;
        }
    }
}

