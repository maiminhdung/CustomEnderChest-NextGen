# CustomEnderChest Next-Gen - AI Context

This file is a quick technical map for future AI/code assistants working on this plugin.

## 1) What this plugin does

CustomEnderChest replaces/extends vanilla Ender Chest access with:

- Permission-based chest sizes (`9..54` slots via `CustomEnderChest.level.0..5` or wildcard).
- Async persistence (YML, H2, or MySQL backend).
- Storage format migration (Safely migrate data between YML, H2, and MySQL using `MigrationManager`).
- Folia/Canvas/Paper-compatible scheduling.
- Admin view/edit of other players' chests.
- Overflow item storage when permission size shrinks.
- Optional vanilla Ender Chest import on join or via command.
- Backup system (auto + shutdown backup).

## 2) Core architecture

Main entrypoint: `org.maiminhdung.customenderchest.EnderChest`

Initialization order in `onEnable()`:

1. `ConfigHandler`
2. `DebugLogger`
3. `LocaleManager`
4. `SoundHandler`
5. `DataLockManager`
6. `StorageManager` (selects backend + initializes tables/files)
7. `EnderChestManager` (cache/load/save/open logic)
8. `LegacyImporter`
9. `BackupManager` (starts auto-backup if enabled)
10. Optional update checker
11. Config updater/reload
12. Listener + command registration
13. Optional bStats metrics

Shutdown in `onDisable()`:

- Stop auto-backup
- `EnderChestManager.shutdown()` (save cached data)
- Optional shutdown backup
- Close DB pool

## 3) Threading model (important)

The plugin heavily separates game-thread vs async I/O:

- DB/file work uses `CompletableFuture` async paths.
- Entity/world-sensitive actions are rescheduled with `Scheduler.runEntityTask(...)` or global task methods.
- `Scheduler` auto-detects Folia/Canvas/expanded Paper scheduling and falls back for Bukkit.
- `DataLockManager` (`Set<UUID>`) prevents race conditions between join/load, close/save, quit/save, admin-edit save, etc.

If you modify inventory or player state, prefer entity-thread scheduling on Folia.

## 4) Main runtime flows

### Player join

- `PlayerListener.onJoin` -> `EnderChestManager.onPlayerJoin(player)`.
- Loads player data by UUID; if missing, attempts name-based UUID migration (`findUUIDByName` + migrate to current UUID).
- Builds cached `Inventory` in Guava cache (`expireAfterAccess 30m`).
- If stored data exceeds permission size, extra items are written to overflow storage.
- Optional delayed auto-import from vanilla chest (`LegacyImporter.autoImportOnJoin`).

### Open own chest

- `/cec` or `/cec open` or right-click Ender Chest (depending config/permission).
- `EnderChestManager.openEnderChest(player)` checks lock, cache, permission size.
- Resizes if needed and tracks open inventory in `openInventories`.

### While chest is open

- `checkOpenInventories()` runs every second.
- Detects permission/title changes and safely reopens resized inventory.
- Includes anti-loop cooldown (`5s`) and resizing guard set.

### Close chest / quit

- On close: save async immediately (if not locked).
- On quit: clone/invalidate cache then async save with timeout.

### Admin viewing/editing another player chest

- `/cec open <player>` with `CustomEnderChest.command.open.other`.
- Online target: clone from live cache.
- Offline target: load from storage + size.
- Mapping `adminViewedChests` links admin view inventory -> target UUID.
- Inventory click/drag handlers synchronize admin view and target live inventory both ways.
- On admin close: save target data async with lock protection.

### Overflow restore

- If permission increases and there is free space, overflow items are restored back into chest.
- Remaining overflow is kept; empty overflow is cleared.

## 5) Storage layer

Interface: `StorageInterface`  
Implementations:

- `YmlStorage` -> `plugins/CustomEnderChest/playerdata/<uuid>.yml`
- `H2Storage` -> H2 with HikariCP
- `MySQLStorage` -> MySQL with HikariCP

### Storage Migration

- Handled by `MigrationManager` and `AbstractMigrator` (Strategy pattern).
- Spins up temporary `StorageManager` instances for `source` and `target` to avoid polluting the main pool.
- Reads data with `getPlayersWithItems()` and safely transfers each UUID over async.
- Respects `DataLockManager` per UUID during migration.
- Auto-saves the online player's live cache before reading to ensure no item duplication/loss.
- Blocks opening EnderChests globally via `/cec` or block interaction while migration runs.

Common data fields:

- Main: `player_uuid`, `player_name`, `chest_size`, `chest_data`, `last_seen`
- Overflow: `player_uuid`, `overflow_data`, `created_at`

Security hardening present:

- SQL table name is validated (`^[a-zA-Z0-9_]+$`) and defaults to `custom_enderchests` if invalid.

### Serialization

`ItemSerializer` handles:

- New format: Paper byte-based `ItemStack.serializeAsBytes()/deserializeBytes()`.
- Legacy format detection and migration path for old Bukkit object stream data.
- YAML map/list serialization helpers for file backend.
- OP notifications if critical legacy migration fails.

## 6) Commands and permissions

Main command: `/customenderchest` (aliases: `cec`, `ec`, `customec`)

Implemented subcommands:

- `/cec` or `/cec open` -> open own chest
- `/cec open <player>` -> admin view/edit target chest
- `/cec reload`
- `/cec import vanilla`
- `/cec delete <player>`
- `/cec convertall` (bulk data format conversion)
- `/cec migrate <source> <target>` (structural data migration between storage types)
- `/cec stats [validate]` (shows storage numbers or validates corrupted records)

Key permissions from `plugin.yml`:

- `CustomEnderChest.level.0..5`
- `CustomEnderChest.level.*`
- `CustomEnderChest.commands`
- `CustomEnderChest.block.open`
- `CustomEnderChest.command.open.self`
- `CustomEnderChest.command.open.other`
- `CustomEnderChest.command.delete`
- `CustomEnderChest.admin`

## 7) Config and locale

Main config: `src/main/resources/config.yml`  
Language files: `src/main/resources/lang/lang_{en,vi,nl,zhcn}.yml`

High-impact config keys:

- `storage.type` (`yml|h2|mysql`)
- `storage.table_name`
- `storage.auto-save-interval-seconds`
- `backup.*`
- `general.locale`, `general.debug`, `general.bstats-metrics`, `general.update-checker`
- `enderchest-options.disable-enderchest-click`
- `enderchest-options.disable-plugin-on-endechest-block`
- `default-player.enabled`, `default-player.size`, `default-player.allow-command`
- `import.auto-import-on-join`
- `sounds.*`

Text rendering uses MiniMessage via `Text.parse(...)` with legacy serialization compatibility.

## 8) Key classes quick map

- `EnderChest`: plugin bootstrap/shutdown wiring.
- `Scheduler`: universal scheduler abstraction for Folia/Canvas/Bukkit.
- `EnderChestManager`: cache + load/save + resize + overflow + open-state tracking.
- `PlayerListener`: join/quit/interact/click/drag/close orchestration.
- `StorageManager`: backend selection + pool setup.
- `MigrationManager`: safely run structural migrations between storage formats.
- `H2Storage`, `MySQLStorage`, `YmlStorage`: persistence implementations.
- `LegacyImporter`: vanilla chest import flows.
- `BackupManager`: archive backups + retention cleanup.
- `LocaleManager`: language file management and message components.
- `DataLockManager`: per-player operation lock.
- `ConvertAllCommand`: batch convert old serialized data.

## 9) Known quirks to remember

- `hasBlockOpenPermission(...)` currently returns config value of `disable-enderchest-click`; naming is easy to misread.

If you touch these areas, re-check behavior carefully around race conditions and lock lifecycle.
