# AGENTS.md

This repository is **Next-Gen Custom EnderChest**, a Paper/Folia Minecraft
plugin (Java 21, Gradle). It is a normal plugin project — build it, edit it,
and keep the docs truthful.

## Start here

- `AI_CONTEXT.md` is the technical map of the plugin (architecture, threading,
  storage, flows, quirks). Read it before making non-trivial changes and
  **update it** when you change architecture or behavior.
- Main sources: `src/main/java/org/maiminhdung/customenderchest/`.
- Resources: `src/main/resources/` (`config.yml`, `plugin.yml`, `lang/`).

## Build & verify

- `./gradlew build` produces `build/libs/CustomEnderChest-NextGen-<version>.jar`
  (shadowed + relocated deps). There is no test suite — a clean build is the
  verification step.
- `./gradlew runServer` downloads and starts a real Minecraft server; do not use
  it for routine verification.
- CI (`.github/workflows/gradle-build.yml`) builds pushes/PRs to `master`/`dev`.

## Versioning rule

`version` in `build.gradle` and `config-version` in
`src/main/resources/config.yml` must be **identical** — the build fails
otherwise. Bump both together.

## Code conventions

- Java 21, Paper API 1.21.x, Lombok where handy. Tabs for Java indentation,
  UTF-8.
- Threading: never touch `Inventory`/`Player` state off the owning thread — use
  `Scheduler.runEntityTask(...)`; storage writes go through
  `EnderChestManager.queueStorageOperation(...)`. See AI_CONTEXT §4.
- Messages: use `LocaleManager` + MiniMessage keys, never hardcoded `§` strings.
  When adding/changing lang keys, update **all five** files in
  `src/main/resources/lang/` (`en`, `vi`, `nl`, `zhcn`, `es`).
- Keep `config.yml` comments in the `[Reload]` / `[Restart]` style and document
  new keys. Uniformly-reloadable/restart-required sections carry a `[RELOAD]` /
  `[RESTART]` tag in their header comment; mixed sections (e.g. `general:`)
  keep per-key tags instead.

## Boundaries

- Do not commit build artifacts (`build/`, `.gradle/` are ignored).
- Do not change the data schema or serialization format without a migration
  path — player data loss is the worst-case bug class here.
- License is CC BY-NC-SA 4.0; keep third-party deps compatible with it.
