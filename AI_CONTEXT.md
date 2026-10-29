# CustomEnderChest Next-Gen — AI Context

Technical map for AI/code assistants working on this plugin. Last verified against
`dev` at version `2.2.0-dev` (2026-10, multi-vault release). Keep this file in
sync when architecture changes.

## 1) What this plugin does

CustomEnderChest replaces/extends the vanilla Ender Chest with:

- **Multi-vault system**: every player can own multiple chests ("vaults").
  Vault 1 is the legacy Ender Chest; vaults 2+ are unlocked by permission.
- Permission-based vault sizes (9–54 slots). Vault 1 uses the legacy
  `CustomEnderChest.level.0..5` / `level.*` permissions; vaults 2+ use
  `CustomEnderChest.vault.<n>.level.<0-5>` and inherit the Vault 1 size by
  default. Config `default-player.*` is the fallback.
- Vault selector GUI (`/<main> vaults`), configurable items incl. ItemsAdder /
  Nexo resource-pack items.
- Fully async persistence with three backends: `yml`, `h2`, `mysql` (HikariCP).
- Per-vault overflow storage with GUI claiming, optional Vault fees, login
  warnings, and time-based expiration.
- Automatic legacy data migration into Vault 1 on first load (no player action
  needed, crash-safe/idempotent).
- Structural storage migration between backends (`/cec migrate <from> <to>`),
  migrating every vault.
- Folia/Canvas/Paper-compatible scheduling (`Scheduler`).
- Admin view/edit of other players' vaults (online and offline), two-way sync.
- Vanilla Ender Chest bulk import (`/cec import vanilla`, NBTAPI for offline).
- Backup system (scheduled zips + shutdown backup, retention cleanup).
- Vanished-player protection: right-clicking an Ender Chest block while
  vanished (EssentialsX / vanish metadata) is silently ignored.
- Modrinth update checker, GitHub config updater, bStats + FastStats metrics.

## 2) Repository layout, build, and versioning

- **Multi-module Gradle build**: the root project is the plugin; the `api`
  subproject (`api/src/main/java/...`) holds the public developer API
  (`CustomEnderChestAPI`, `CustomEnderChest`, `api.events.*`) and publishes
  `customenderchest-api-<version>.jar` + javadoc + sources jars
  (`./gradlew publishToMavenLocal`; on JitPack:
  `com.github.maiminhdung.CustomEnderChest-NextGen:customenderchest-api:<tag>`,
  configured via `jitpack.yml`). The plugin depends on `project(":api")` and
  shades the classes into its jar. The api module version follows
  `rootProject.version` — bumping the plugin version covers it.
- Main entrypoint: `org.maiminhdung.customenderchest.EnderChest` (package root
  `src/main/java/org/maiminhdung/customenderchest/`).
- Gradle project, Java 21, Paper API `1.21.1-R0.1-SNAPSHOT`, Lombok.
  Adventure 5.2.0 (`adventure-api`, `-key`, `-text-minimessage`,
  `-text-serializer-legacy`) is pinned via compileOnly — the server provides
  the real classes at runtime (Paper 26.x ships Adventure 5).
- `com.gradleup.shadow` builds the final jar
  (`build/libs/CustomEnderChest-NextGen-<version>.jar`) and relocates shaded
  deps: HikariCP, H2, Gson, bStats, FastStats → `org.maiminhdung.customenderchest.lib.*`.
  MySQL Connector/J is declared in `plugin.yml` `libraries:` and downloaded by
  Paper at first startup (fresh installs need network access).
- `compileOnly` soft deps: NBTAPI, VaultAPI. ItemsAdder/Nexo are integrated via
  **reflection** (no compile dependency, see `CustomItemProvider`).
- **Version gate:** `processResources` fails the build unless `config-version`
  in `src/main/resources/config.yml` exactly equals `version` in `build.gradle`.
- Tests: `src/test/java` (JUnit 5) covers pure logic — `VaultPermissions`
  (permission resolution), `VaultKey`, `UpdateManager.compareVersions`.
  `./gradlew build` runs them. Bukkit-dependent code has no test harness.
- CI: `.github/workflows/gradle-build.yml` builds push/PRs to `master`/`dev`.
- `xyz.jpenilla.run-paper` provides `./gradlew runServer` — downloads a full
  server, do not use for routine verification.

Package map:

```
org.maiminhdung.customenderchest
├── EnderChest            (bootstrap, reloadRuntimeConfiguration)
├── Scheduler             (Folia/Canvas/Bukkit scheduling facade)
├── ConfigHandler         (config wrapper + legacy block-mode mapping)
├── backup/               BackupManager
├── commands/             EnderChestCommand, CommandRegistrationManager
├── data/                 EnderChestManager, OverflowManager, ItemSerializer,
│                         BulkImporter, NbtImporter (package-private), MetricsDataProvider
├── listeners/            PlayerListener
├── locale/               LocaleManager
├── storage/              StorageManager, StorageInterface (+ VaultData)
│   ├── impl/             YmlStorage, H2Storage, MySQLStorage
│   └── migrate/          MigrationManager, AbstractMigrator, Migrator
├── update/               UpdateManager (Modrinth), ConfigUpdateManager (GitHub config)
├── vault/                VaultSelectorManager (selector GUI)
└── utils/                DataLockManager, EnderChestUtils, VaultKey,
                          VaultPermissions, CustomItemProvider, VanishChecker,
                          Text, SoundHandler, DebugLogger, VaultHandler
```

## 3) Startup and shutdown order

`onEnable()`:

1. `ConfigHandler`, `ConfigUpdateManager` created
2. Optional FastStats `ErrorTracker` init
3. `DebugLogger`, `LocaleManager`, `SoundHandler`, `DataLockManager`
4. `StorageManager` (creates legacy + `_vaults` + `_vaults_overflow` tables)
5. `EnderChestManager` (per-vault cache, auto-save task, open tracker)
6. `VaultSelectorManager` (selector GUI)
7. `VaultHandler` (soft hook) + `OverflowManager` (expiration task)
8. `BackupManager` (auto-backup)
9. Optional `UpdateManager` Modrinth check
10. `configUpdateManager.updateIfRequired()` (only on version mismatch)
11. `PlayerListener`, `EnderChestCommand`, `CommandRegistrationManager`
12. Optional bStats / FastStats metrics

`onDisable()`: selector cleanup → stop backup/overflow tasks →
`EnderChestManager.shutdown()` (30s-bounded save of every cached vault) →
shutdown backup → `StorageManager.close()` (executor drain, H2
`CHECKPOINT SYNC`/`SHUTDOWN`) → metrics shutdown.

## 4) Threading model (important)

- All DB/file work is async via `CompletableFuture` on the bounded
  `CEC-IO-Worker-*` executor (sized by `storage.pool-settings.max-pool-size`).
  Query timeouts: 10s per query, 15s loads/saves, 10s quit/admin saves.
- Entity/world-sensitive actions go through `Scheduler.runEntityTask(...)`.
  `Scheduler` auto-detects Canvas/Folia/expanded-Paper scheduling. If a Folia
  entity task returns null (unloaded region), the runnable re-runs on the
  global scheduler. `Scheduler`'s static init captures
  `EnderChest.getInstance()` — never touch it before the plugin exists.
- Locks (`DataLockManager`, Guava cache, 60s auto-expire) are keyed by
  `UUID` (whole-player operations: quit, auto-save, migration, expiration) or
  `VaultKey` (single vault: join/lazy load, open, resize, close-save).
- Per-player write serialization: `queueStorageOperation(uuid, supplier)`
  chains futures in `saveChains` — storage writes for one player execute in
  order across all vaults. `trackPendingSave` aggregates into `pendingSaves`;
  open flows wait on it.
- Inventory contents are snapshotted (`.clone()`) on the owning thread before
  async save; `cleanInventoryForSave` nulls air slots.
- Rule of thumb: player/entity state → entity thread; storage → through
  `queueStorageOperation`.

## 5) Multi-vault model

### Keys and permission resolution

- **Feature toggle**: `multi-vault.enabled` (`[Reload]`, default true,
  written by the first-run setup GUI). When
  `false` the plugin behaves exactly like the pre-2.2 single-chest versions:
  vault count is always 1, vault permissions are ignored, the selector GUI
  (`/cec vaults`) reports disabled, and every vault argument > 1 is rejected
  with `messages.vault-no-access`. Stored vault 2+ data is untouched and
  becomes accessible again when re-enabled.
- **Operators are regular players for chest access**: sizes and vault counts
  resolve from permissions/config exactly like everyone else (no OP shortcut);
  their extra power is command-level (`CustomEnderChest.admin` etc.).
- `VaultKey = (UUID playerId, int vaultId)`, vaultId ≥ 1. Used as cache key,
  lock key, and admin-view map value.
- Vault count (`EnderChestUtils.getVaultCount`): always 1 while the system
  is disabled; OP → max; otherwise `CustomEnderChest.vaults.*`
  → all vaults up to config `multi-vault.max-vaults` (default 9, hard cap 54);
  else highest `CustomEnderChest.vaults.<n>`; else 1. Resolution lives in
  pure, unit-tested `VaultPermissions`.
- Vault 1 size: legacy `level.*`/`level.0..5` (via `isPermissionSet`, so OPs
  don't implicitly get 54), then `default-player.*`, else 0 (no chest).
- Vault N≥2 size: ONLY `vault.<n>.level.*`/`vault.<n>.level.<0..5>`, else the
  configured `default-player.size` — the legacy level permissions never leak
  into vaults 2+.
- Titles: vault 1 uses `titles.enderchest`; vault 2+ uses
  `titles.enderchest-vault` (`<vault>` placeholder). Admin views use
  `titles.admin_view[_vault]`. With `general.rich-titles: true` titles render
  via `Text.parseRich` (fonts/gradients/hex survive; needed for resource-pack
  fonts).

### Runtime flows

- **Join**: loads **vault 1 only**; other vaults load lazily on first open
  (`ensureVaultLoaded`). Join also runs the optional name-based UUID migration
  (now moves every vault) and the aggregate overflow login warning.
- **Data protection**: `VaultData` with empty items but stored size > 0 means
  deserialization failure → the vault is NOT cached (prevents wiping data);
  player gets `messages.migration-*`.
- **Overflow aggregation (multi-vault disabled)**: `/{main} overflow` opens
  a merged GUI over every vault's overflow (`OverflowView` with `vaultId`
  0 + `aggregateOverflowItems`/`aggregateOverflowLists`); claims remove from
  the source vaults immediately, close persists remainders — nothing becomes
  unreachable after switching modes.
- **Open** (`openEnderChest(player, vaultId)`): pending-save wait → per-vault
  lock check → double-open guard (one own vault open at a time) → entitlement
  check (`messages.vault-no-access`) → permission size check → upgrade path →
  API `EnderVaultOpenEvent` → display-size resize (`getExpectedDisplaySize`
  never truncates stored items) → open + track in `openVaults`. When the
  vault is not cached, it loads and then OPENS automatically (no second
  click needed); a previously "data still loading" message only appears
  while a load is already in flight.
- **Selector entry points** (config `multi-vault.selector-gui.*`): the
  `/cec vaults` command always works when the GUI is enabled;
  `open-on-block-click: true` makes right-clicking an Ender Chest block open
  the selector instead of Vault 1, and `use-as-main-command: true` makes the
  bare `/{main}` command open the selector (`/{main} open` always opens
  Vault 1).
- **Editable GUI layouts (DeluxeMenu-style)**: `plugins/CustomEnderChest/
  gui/setup.yml` and `gui/selector.yml` (extracted from the JAR on first
  run) define every visual element as an `items:` entry with `slots`
  ("0-8, 45, 53" ranges), `material` (Material / itemadder: / nexo: /
  none), MiniMessage or `lang:` name/lore, and a click `action`
  (setup: `choose-single`/`choose-multi`/`close`; selector: `open-vault`
  — the dynamic vault entry maps its slot list positionally to vault ids
  and supports a `locked:` sub-section — plus `close`; `none` =
  decorative). Optional top-level `filler` covers uncovered slots.
  Files may be flat or wrapped in a `setup:`/`selector:` root key (both
  accepted — `GuiConfigManager` unwraps). Pre-DeluxeMenu files (no `items`
  section) are auto-moved to `<name>.old.yml` and re-extracted.
  `GuiConfigManager` owns these files (parseSlots/itemSections/buildItem)
  and falls back to the bundled layout when the disk copy is invalid;
  edited files apply via `/{main} reload`. Note: console boot tests cannot
  exercise GUI rendering — validate GUI changes with a real client or the
  YAML parse path.
- **GUI texts live in the language files**: gui yml values starting with
  `lang:` (e.g. `name: "lang:gui.setup.single-name"`) resolve against the
  `gui:` section of the active `lang_<locale>.yml` (bundled-locale fallback
  applies, so old lang folders never show missing keys); raw MiniMessage
  text is also accepted for quick local tweaks. Lore splits on real newlines
  and supports placeholders `<player> <vault> <size> <items>`.
- **Tracker** (`checkOpenInventories`, every 20 ticks / 1s): the open-vault
  resize loop (UP and DOWN, downgrades move overflow) runs only while a
  vault is open, but the closed-vault shrink pass runs **unconditionally** —
  permission changes apply within ~1s without opening anything (the old
  early-return made shrinks wait until the player clicked). 5s per-vault
  cooldown + `resizingVaults` + data locks; overflow notifications reserve
  their dedup slot synchronously so rapid resizes never double-send.
- **Close/quit**: close saves that vault (per-VaultKey lock); quit snapshots
  and saves **all** cached vaults under one player lock.
- **Admin** (`/<main> open <player> [vault]`): `adminViewedChests:
  Map<Inventory, VaultKey>`; uncached online vaults are loaded from storage
  first; two-way click/drag sync; save on close.
- **Overflow** (`/<main> overflow [vault]`, admin `overflow <player> [vault]`):
  per-vault GUI claim flow (fit calculation, Vault fee, immediate persist),
  `overflowViews: Map<Inventory, OverflowView>` where `OverflowView(target,
  vaultId, admin)`. Upgrade detection during claims re-runs
  `checkForUpgradeAndRestore(player, vaultId)`.
- **Import** (`BulkImporter`) writes vanilla data into vault 1.
- **Delete** (`/<main> delete <player> [vault]`) deletes every vault row +
  legacy leftovers, or a single vault when a number is passed and multi-vault
  is on. Fires `EnderVaultDeleteEvent` (cancellable).
- **Block-click flow**: selector opens are delayed 1 tick and the lid
  animation is played around the selector GUI (the clicked block stays
  tracked in `playerEnderChestBlocks` for both open and close) — opening an
  inventory in the same tick as the interact event desyncs the client (aim
  snaps down) and breaks animations on rapid open/close; `openSelector`
  returns boolean and guards against stacking a second selector.
- **Vanished players**: `PlayerListener.onEnderChestInteract` cancels the
  interaction silently when `enderchest-options.ignore-vanished-players` (default)
  and `VanishChecker.isVanished` (metadata `vanished`/`isVanished` + EssentialsX
  reflection).

## 6) Storage layer

Interface `StorageInterface` — vault-aware API: `loadVault` → `VaultData
{size, items}` (null = no data), `saveVault`, `deleteVault`, `getVaultIds`,
plus per-vault overflow CRUD/`getOverflowCreatedAt`; legacy/shared:
`deleteEnderChest` (legacy rows only), `getPlayerName`, `hasData`,
`findUUIDByName`, `getStorageStats`, `getPlayersWithItems` (returns
`vaultIds` per player; legacy counts as vault 1).

SQL schema (H2 & MySQL):

- Legacy `<table>` and `<table>_overflow` — kept **only** so pre-multi-vault
  data can be migrated; no longer written by normal operation.
- `<table>_vaults`: `player_uuid, vault_id, player_name, chest_size,
  chest_data LONGTEXT (Base64), last_seen`, PK `(player_uuid, vault_id)`.
- `<table>_vaults_overflow`: `player_uuid, vault_id, overflow_data,
  created_at`, PK `(player_uuid, vault_id)`. `created_at` is preserved on
  update (UPDATE-then-INSERT).

**Legacy → vault 1 migration (automatic, 2.1.x → 2.2.0)**: on startup,
`StorageInterface.migrateLegacyData()` bulk-migrates every remaining legacy
row into vault 1 (idempotent, logged with a player count). The first
`loadVault(uuid, 1)` also runs `migrateLegacyIfRequired` per player as a
safety net — legacy chest row and legacy overflow row are copied into the
vault 1 rows (idempotent upserts: H2 `MERGE INTO`, MySQL `INSERT IGNORE`) and
only then deleted. Crash at any point leaves legacy data intact and the
migration re-runs next load. YML migrates root-level keys into a
`vaults: {1: {...}}` section the same way. (A never-published 3.0.0-beta
schema existed on dev machines only; 2.2.0 does not support it — those
databases must be reset or downgraded first.)

YML format (one file per player, `playerdata/<uuid>.yml`):

```yaml
player-name: Notch
vaults:
  '1':
    enderchest-size: 27
    enderchest-inventory: [...]   # Bukkit map serialization
    overflow-items: [...]
    overflow-created-at: 1690000000000
```

Note: `YmlStorage.saveVault` re-reads the file before writing, which also
fixes a pre-2.2 bug where a chest save wiped the overflow section.

Backends: H2 (`MERGE INTO` upsert, `MODE=MySQL`), MySQL
(`INSERT ... ON DUPLICATE KEY UPDATE`), YML (writes synchronized on the
interned UUID string). Security: table name validated `^[a-zA-Z0-9_]+$`;
prepared statements everywhere. H2 lock/corruption recovery unchanged
(loud warning / timestamped backup + fresh retry). Storage failure at startup
is fatal — no silent backend fallback.

### Serialization (`ItemSerializer`)

- DB backends store Base64 of: `int length` + per slot
  `[bool present][int byte len][Paper serializeAsBytes()]` — cross-version
  safe via Paper's DataFixer.
- Legacy `BukkitObjectOutputStream` payloads are detected heuristically and
  migrated on read; H2/MySQL auto re-save the record in the new format.
- Hard failures (pre-1.21.4 data on 1.21.5+) log a recovery runbook and
  notify online OPs; the vault stays unloaded (data preserved).
- YML files use Bukkit map serialization instead.

### Structural migration (`/cec migrate`)

`MigrationManager` spins up temporary `StorageManager`s for source/target.
`AbstractMigrator` iterates players **sequentially**; per player: tryLock →
auto-save online cached vaults to source → for each `getVaultIds`: copy vault
+ overflow → unlock. Opening chests is blocked while migrating.

## 7) Commands and permissions

Internal command `customenderchest`; runtime labels from `commands.main` +
`commands.aliases` (defaults `cec`/`ec`/`customenderchest`/`customec`),
re-registered transactionally by `CommandRegistrationManager`; `/<main> reload`
re-applies labels.

Subcommands (`<main>` = configured primary label):

- `/<main>` or `/<main> open` — own vault 1 (`CustomEnderChest.command.open.self`)
- `/<main> open <number>` — own vault N
- `/<main> open <player> [number]` — admin view/edit (`command.open.other`)
- `/<main> <number>` — open that vault directly (multi-vault, `open.self`)
- `/<main> vaults` — selector GUI (`command.vaults` or `open.self`); the
  bare `/{main}` opens the selector by default when multi-vault is on
  (`multi-vault.selector-gui.use-as-main-command`, default true)
- `/<main> overflow [number]` — own vault overflow (`command.overflow`)
- `/<main> overflow <player> [number]` — admin read-only (`command.overflow.other`)
- `/<main> setup` (`CustomEnderChest.admin`, player-only) — reopens the
  first-run mode chooser GUI
- `/<main> reload`, `/<main> import vanilla`, `/<main> migrate <from> <to>`,
  `/<main> stats [validate|help]` (all `CustomEnderChest.admin` except delete)
- `/<main> delete <player> [number]` (`command.delete`) — deletes all vaults,
  or just the given vault while multi-vault is enabled

Permission notes:

- Base command use: OP, `CustomEnderChest.commands`, or
  `default-player.allow-command`. Console is always allowed for admin subcommands.
- Vault perms: `CustomEnderChest.vaults.<n>` / `vaults.*` (count, registered in
  plugin.yml), `CustomEnderChest.vault.<n>.level.<0-5>` / `.level.*`
  (per-vault size, runtime-only).
- Block open: mode `custom` → everyone; `permission` → OP or `block.open`;
  `vanilla` → plugin ignores the click. Vanished players are silently blocked
  when `ignore-vanished-players` is on.
- Offline players cannot be permission-checked; sizes fall back to the stored
  `VaultData.size` (admin views) or `default-player.size` (fallback 27).

## 8) Config and locale

Main config: `src/main/resources/config.yml`. Comment conventions: per-key
`[Reload]`/`[Restart]` tags, plus `[RELOAD]`/`[RESTART]` tags in section
header comments when the tag applies to every key of the section (`general:`
is mixed and keeps per-key tags only). `config-version` must equal the Gradle
version (build gate).

Key sections:

- `storage.*` (type, table_name, mysql, pool, auto-save interval,
  `migrate-uuid-by-name`)
- `multi-vault.enabled|max-vaults` + `multi-vault.selector-gui.*`
  (`enabled`, `show-locked`, `open-on-block-click`, `use-as-main-command`,
  `item`, `items` per-vault overrides, `locked-item`, `fill-item`) — item
  specs accept `Material`, `itemadder:<namespace:id>`, `nexo:<item_id>`, or
  `none`. Configs from earlier 2.2.0-dev builds with the old `vaults.*`
  section fall back to the defaults (section was renamed before release).
- `general.rich-titles` (MiniMessage titles without legacy flattening)
- `enderchest-options.block-interaction-mode`, `play-block-animation`,
  `ignore-vanished-players`
- `backup.*`, `overflow.*` (expiration, login-warning, retrieval-fee),
  `default-player.*`, `sounds.*`

`/<main> reload` applies: config, locale, debug, command labels, overflow and
backup tasks. Restart required: storage backend/pool, metrics, update checker.

Locales: `src/main/resources/lang/lang_<minecraft-locale>.yml` —
`lang_en_us.yml`, `lang_vi_vn.yml`, `lang_zh_cn.yml`, `lang_nl_nl.yml`,
`lang_es_es.yml` (codes match `player.locale()` directly; legacy files
`lang_en.yml` etc. are auto-renamed on startup and old config values are
mapped). Key lookups fall back: disk locale → bundled JAR copy
of the same locale → bundled English → hardcoded default, so servers with
lang files from older versions never render "Missing key" for new entries.

**Per-player localization** (`general.player-locale`, `[Reload]`, default
true): `LocaleManager` flattens every locale into a store and registers an
Adventure 5 `TranslationStore` (component-based, namespace
`customenderchest:`) with `GlobalTranslator` — so
`Component.translatable("customenderchest.<key>")` renders in each player's
client locale wherever Paper applies translators (chat, action bar, titles).
Because Paper does NOT translate ItemMeta/lore, all player-facing lookups
use viewer-aware overloads (`getPrefixedComponent(player, key, ...)` /
`getComponent(player, ...)` / `getRawString(player, ...)`) that resolve the
string in the player's locale (exact tag match, then language-only) with
English/configured-locale fallback, and GUIs pass the viewer into
`GuiConfigManager.text(viewer, ...)`. Reloads rebuild the stores from
scratch (old store dropped, then re-registered) so stale keys never
survive; `LocaleManager.shutdown()` unregisters the source on disable.
Vault-related keys: `titles.enderchest-vault`,
`titles.admin_view_vault`, `titles.vault-selector`, `vault-selector.*`,
`messages.vault-no-access`, `messages.vault-invalid`, `stats.vault-records`.
`Text.parse` is pure MiniMessage (full gradients/hex/fonts everywhere since
the legacy pipeline was removed; `general.rich-titles` no longer exists).
Language files from older versions with legacy `&` codes in `levels` are
converted to MiniMessage tags in memory on load.

## 9) Developer API & events

Public API for other plugins under `org.maiminhdung.customenderchest.api`:

- `CustomEnderChestAPI.get()` → `CustomEnderChest` interface (instance exists
  only while the plugin is enabled; registered right after the selector
  manager in `onEnable`, unregistered first in `onDisable`).
- Methods: `getVaultContents` / `setVaultContents` (cache-aware: online
  players' live inventories are updated on the global thread then persisted
  through the save chain), `getVaultSize`, `deleteVault` (tryLock-guarded,
  fails with `IllegalStateException` while data is busy), `getVaultIds`,
  `getOverflowItems` / `setOverflowItems` (empty clears), runtime info
  (`isVaultsEnabled`, `getMaxVaults`, `getUnlockedVaultCount`), and GUI
  (`openVault`, `openVaultSelector`). Storage calls are async
  (`CompletableFuture`); vault ids are validated 1..max.
- Bukkit events in `api.events`: `EnderVaultOpenEvent` (cancellable, fired in
  `EnderChestManager.openEnderChest` right before showing the inventory),
  `EnderVaultCloseEvent` (fired in `PlayerListener` after the close-save was
  scheduled), `EnderVaultDeleteEvent` (cancellable, fired in the delete
  command before any row is touched; cancelling sends locale key
  `command.delete-cancelled`).

## 10) Update & metrics

- `UpdateManager` (rewritten in 2.2.0): checks Modrinth project `AipGDIso`
  (https://modrinth.com/plugin/custom-ender-chest) once on startup via the
  Labrinth v2 versions endpoint. A release counts as compatible only when it
  is `listed`, targets a server-plugin loader (paper/folia/purpur), fits the
  configured channel (`general.update-channel`: release < beta < alpha,
  default release), and lists the running Minecraft version in
  `game_versions` (bare-minor entries like "1.21" cover the whole series only
  when no specific patch is listed; empty lists count as compatible). The
  best candidate (semver-ish compare, qualifier < stable) is stored in a
  volatile `UpdateResult`; console gets a warning with the download link, and
  admins (`CustomEnderChest.admin`) get the localized
  `messages.update-available` notice in-game on join (2s delay). Filtering
  and comparison are pure static methods covered by unit tests
  (`UpdateManagerVersionTest`).
- `ConfigUpdateManager`: fetches the version-tagged config.yml from GitHub
  only on version mismatch, preserves user values, migrates legacy
  block-mode keys, atomic save with backup.
- bStats (id 26551) + FastStats counters (`loaded_chests` = cached vaults,
  `open_chests`, save/load counts, avg save time) via `MetricsDataProvider`.

## 11) Key classes quick map

| Class | Responsibility |
| --- | --- |
| `EnderChest` | Bootstrap/shutdown wiring, runtime reload, error tracking |
| `Scheduler` | Universal scheduler facade |
| `ConfigHandler` | Config wrapper, block-mode resolution + legacy mapping |
| `EnderChestManager` | Per-vault cache/load/save/resize/overflow, open-state, admin + overflow GUIs, write chaining |
| `PlayerListener` | Join/quit/interact/click/drag/close, vanish check, admin sync |
| `StorageManager` | Backend selection, pools, IO executor, H2 recovery |
| `StorageInterface` + `impl/*` | Vault-aware persistence (YML/H2/MySQL) + legacy migration |
| `MigrationManager` / `AbstractMigrator` | Backend migration across all vaults |
| `OverflowManager` | Per-vault expiration, aggregate login warnings, Vault fees |
| `VaultSelectorManager` | Configurable selector GUI (ItemsAdder/Nexo aware) |
| `CustomEnderChestAPI` / `CustomEnderChestImpl` | Public developer API + events (see §9) |
| `VaultKey` / `VaultPermissions` | Vault identity + pure permission resolution (unit tested) |
| `CustomItemProvider` | Resolves Material / ItemsAdder / Nexo item specs (reflection) |
| `VanishChecker` | EssentialsX / metadata vanish detection |
| `BulkImporter` / `NbtImporter` | Vanilla chest import into vault 1 |
| `BackupManager` | Scheduled/shutdown zip backups + retention |
| `LocaleManager` / `Text` | Transactional locale files; legacy + rich MiniMessage |
| `CommandRegistrationManager` | Configurable command labels/aliases |
| `DataLockManager` | UUID/VaultKey operation locks (60s auto-expire) |
| `ItemSerializer` | Base64/Paper serialization, legacy detection + migration |
| `UpdateManager` / `ConfigUpdateManager` | Modrinth update check / GitHub config updater |
| `VaultHandler` / `MetricsDataProvider` | Vault economy hook / FastStats counters |

## 12) Known quirks to remember

- Legacy config mapping (pre `block-interaction-mode`): old vanilla toggle →
  `vanilla`; `disable-enderchest-click: true` → `custom`; else `permission`.
- `storage.pool-settings.max-pool-size` controls both the DB pool and the IO
  executor thread count.
- Inventory tracker: 100 ticks (5s); resize cooldown 5s; resize only goes
  **up** while open.
- `DataLockManager` locks expire after 60s regardless of progress.
- Only one own vault can be open per player at a time (`openVaults`).
- Legacy SQL tables are still created on fresh installs (cheap, empty) so the
  migration read path never hits missing-table errors.
- Legacy YML overflow keys at the root are read as vault 1 overflow until the
  file is migrated on first vault-1 load.
- Deleting a player removes all vault rows (no empty-chest placeholder anymore).
- MySQL Connector/J from `plugin.yml` `libraries:` needs internet on first start.
- The build hard-fails when `config-version` ≠ Gradle `version`.
- `SoundHandler` skips plugin open/close sounds for block-click opens when
  `play-block-animation` is on.
- Vanished players get a silently cancelled interaction (no vanilla chest
  either) — intentional stealth behavior.

If you touch `EnderChestManager`, `PlayerListener`, the storage backends, or
any lock lifecycle, re-check race conditions, lock acquisition/release pairs,
and Folia thread ownership — most historical data-loss bugs live there.
