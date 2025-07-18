# Next-Gen Custom Ender Chest

![Build Status](https://img.shields.io/github/actions/workflow/status/maiminhdung/CustomEnderChest-NextGen/build.yml?branch=main)
[![License: CC BY-NC-SA 4.0](https://img.shields.io/badge/License-CC_BY--NC--SA_4.0-lightgrey.svg)](https://creativecommons.org/licenses/by-nc-sa/4.0/)

[![Modrinth](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/compact/available/modrinth_vector.svg)](https://modrinth.com/plugin/smart-spawner-plugin)
[![Spigot](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/compact/available/spigot_vector.svg)]([https://www.spigotmc.org/resources/120743/](https://www.spigotmc.org/resources/127090/))
[![Hangar](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/compact/available/hangar_vector.svg)](https://hangar.papermc.io/maiminhdung/Next-Gen-CustomEnderChest)

A high-performance, fully asynchronous, and highly configurable Ender Chest plugin for modern Paper and Folia servers.

## Overview

Next-Gen Custom Ender Chest is a complete rewrite of the classic permission-based Ender Chest concept, engineered from the ground up for performance, flexibility, and compatibility with modern server software. It provides a lag-free experience by handling all data operations asynchronously and offers a robust, multi-backend storage system to fit any server's needs.

## ⭐ Key Features

* **🚀 Folia Compatible:** Built with a universal scheduling utility that transparently supports both Paper's standard scheduler and Folia's region-based schedulers.
* **⚡ Fully Asynchronous:** All data I/O (loading/saving) is handled off the main server thread to ensure zero TPS loss, even with many players online.
* **💾 Flexible Storage Backend:** Choose the best storage solution for your server via the config:
    * **MySQL:** For multi-server network synchronization.
    * **H2:** A fast, file-based database for high-performance single-server setups.
    * **YML:** Simple, human-readable files for each player, perfect for small servers.
* **🎨 Highly Configurable:** Customize all messages, inventory titles, and sounds using the powerful MiniMessage format and a multi-locale language system (`lang/` folder).
* **📦 Permission-Based Sizes:** Grant players different Ender Chest sizes (from 1 to 6 rows) using simple and intuitive permission nodes.
* **🔧 Modern Dependencies:** Uses HikariCP for efficient database connection pooling and is built on the modern Paper API for stability and future-proofing.
* **🔄 Legacy Data Importer:** Includes a command to easily import player data from older, file-based versions of the plugin.

## Building

This project is built using Gradle.

* Java 21 or higher is required.
* Run `./gradlew build` to build the plugin. The final JAR will be located in `build/libs/`.

## License

This project is licensed under the CC BY-NC-SA 4.0 License. See the `LICENSE` file for details.
