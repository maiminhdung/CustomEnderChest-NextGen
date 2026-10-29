# Next-Gen Custom Ender Chest

![GitHub Actions Workflow Status](https://img.shields.io/github/actions/workflow/status/maiminhdung/CustomEnderChest-NextGen/gradle-build.yml)
[![License: CC BY-NC-SA 4.0](https://img.shields.io/badge/License-CC_BY--NC--SA_4.0-lightgrey.svg)](https://creativecommons.org/licenses/by-nc-sa/4.0/)

[![Modrinth](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/compact/available/modrinth_vector.svg)](https://modrinth.com/plugin/custom-ender-chest)
[![Spigot](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/compact/available/spigot_vector.svg)](https://www.spigotmc.org/resources/127090/)
[![Hangar](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/compact/available/hangar_vector.svg)](https://hangar.papermc.io/maiminhdung/Next-Gen-CustomEnderChest)

A high-performance, fully asynchronous, and highly configurable Ender Chest plugin for modern Paper and Folia servers.

## Overview

Next-Gen Custom Ender Chest is a complete rewrite of the classic permission-based Ender Chest concept, engineered from the ground up for performance, flexibility, and compatibility with modern server software. It provides a lag-free experience by handling all data operations asynchronously and offers a robust, multi-backend storage system to fit any server's needs.

Join support discord: https://discord.gg/sGZ2QSMDEg

## ⭐ Key Features

* **🚀 Folia Compatible:** Built with a universal scheduling utility that transparently supports both Paper's standard scheduler and Folia's region-based schedulers.
* **⚡ Fully Asynchronous:** All data I/O (loading/saving) is handled off the main server thread to ensure zero TPS loss, even with many players online.
* **💾 Flexible Storage Backend:** Choose the best storage solution for your server via the config:
    * **MySQL:** For multi-server network synchronization.
    * **H2:** A fast, file-based database for high-performance single-server setups.
    * **YML:** Simple, human-readable files for each player, perfect for small servers.
* **🎨 Highly Configurable:** Customize messages, inventory titles, sounds, and the main command/aliases. Language files use MiniMessage and can be reloaded at runtime.
* **📦 Permission-Based Sizes:** Grant players different Ender Chest sizes (from 1 to 6 rows) using simple and intuitive permission nodes.
* **🏦 Multiple Vaults:** Players can own several Ender Chests. Unlock them with `CustomEnderChest.vaults.<number>` (or `vaults.*`), size each one with `CustomEnderChest.vault.<number>.level.<0-5>`, and pick between them in the configurable vault selector GUI (`/cec vaults`). Existing data automatically becomes Vault 1 — nothing is lost on upgrade.
* **🎨 Resource Pack Friendly:** The vault selector GUI accepts custom items from ItemsAdder or Nexo (`itemadder:<namespace:id>` / `nexo:<item_id>`), and `rich-titles` enables full MiniMessage (fonts, gradients) in inventory titles.
* **👻 Vanish Aware:** Vanished players (EssentialsX, Vanish, etc.) never trigger the custom chest, sounds, or block animation when right-clicking an Ender Chest.
* **🧩 Developer API:** A public Java API (`CustomEnderChestAPI`) and Bukkit events (vault open/close/delete, cancellable) let other plugins read and manage player vaults.
* **🔧 Modern Dependencies:** Uses HikariCP for efficient database connection pooling and is built on the modern Paper API for stability and future-proofing.
* **🔄 Legacy Data Importer:** Includes a command to easily import player data from older, file-based versions of the plugin.

## Custom command

Configure the primary command and aliases in `config.yml`:

```yaml
commands:
  main: "cec"
  aliases:
    - "ec"
    - "customenderchest"
    - "customec"
```

Run the currently active command with `reload` to apply changes immediately. For example, `/cec reload`; if the primary command is changed to `enderstorage`, use `/enderstorage reload` afterward.

## Building

This project is built using Gradle.

* Java 21 or higher is required.
* Run `./gradlew build` to build the plugin. The final JAR will be located in `build/libs/`.
* On first startup, Paper downloads MySQL Connector/J and its transitive dependencies through the
  `libraries` entry in `plugin.yml`. A fresh installation therefore needs network access; Paper
  caches the downloaded libraries for later startups.

## License

This project is licensed under the CC BY-NC-SA 4.0 License. See the `LICENSE` file for details.
