# Minexa（MineServe Mobile）

<div align="center">

# ✅ Now Working · 可以正常使用

**Run a Minecraft server directly on your Android phone — no root required.**

[中文文档](./README.zh-CN.md) · [Architecture](./ARCHITECTURE.md) · [Changelog](./CHANGELOG.md) · [Background Tasks](./docs/AUTOMATION-TASKS.md)

</div>

Minexa is a native Android app for Minecraft Java Edition servers, with Bedrock Edition support through PowerNukkitX, PocketMine-MP, and Allay. It includes multi-server management, plugins and mods, Modrinth integration, tunneling, backups, crash reports, keep-alive tools, MCP remote control, and no-root data access.

## ✨ Features

- **No root required** — built-in Termux bootstrap runtime; Java 11/17/21/25 run directly, while Java 8 and servers that need a standard glibc environment use a PRoot Linux container (Ubuntu 20.04 / 22.04).
- **Choice of runtime** — Java and PHP runtimes coexist. You pick the environment in the UI and that choice is honoured at launch; PocketMine-MP uses a bundled native PHP runtime, everything else runs on the JVM.
- **16 core types** — Paper, Purpur, Leaves, Leaf, Spigot, CraftBukkit, Fabric, Forge, NeoForge, Quilt, Vanilla, Velocity, BungeeCord, PowerNukkitX, PocketMine-MP, and Allay.
- **Multi-server management** — isolated cores, worlds, configuration, plugins, and mods with one-tap switching.
- **Server import** — independent folder, archive, JAR, and Modrinth `.mrpack` actions with core/version detection.
- **Plugins and mods** — scan, enable/disable, SAF upload, URL install, and Modrinth search/download.
- **Tunneling** — self-hosted frp and bore, plus the SakuraFrp and ME Frp hosted platforms, with LAN and public address display.
- **Backup and crash reports** — world/full-server backup, SAF export, abnormal-exit logs, native-report aggregation, and offline analysis. Reports keep Watchdog/thread-dump highlights so the real blocking point is never truncated away.
- **Keep-alive** — foreground service, wake locks, boot/periodic recovery, and an optional status overlay.
- **Home-screen widgets** — overview, console, event log, and mod/plugin widgets with action buttons, refreshed by a periodic worker.
- **MCP remote control** — embedded Model Context Protocol server (Streamable HTTP) so LAN AI assistants can check status, start/stop the server, send console commands, and read logs; protected by a bearer token.
- **Material 3 UI** — Jetpack Compose with compact core and version selection.

## 📋 Requirements

- Android 8.0 (API 26) or newer.
- `arm64-v8a` device.
- At least 4 GB RAM and enough storage for the runtime, worlds, and backups are recommended.
- Network access is required for initial setup, core download, and modpack import.

## 📥 Download and build

Download an `arm64-v8a` APK from the [Releases page](https://github.com/lovehutaocute/MineServe-Mobile/releases), or build locally:

```powershell
git clone https://github.com/lovehutaocute/MineServe-Mobile.git
cd MineServe-Mobile
.\gradlew.bat :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`.

## 🚀 Quick Start

1. **Initialize** — complete runtime setup and install the required Java version.
2. **Download or import** — select a core/version on Download, or open Import Server.
3. **Accept EULA** — read and accept the generated `eula.txt`.
4. **Start and join** — start from Dashboard; LAN players join using the displayed phone IP and port.

Use frp/bore or router port forwarding for public access.

## 🧩 Supported Cores

| Core | Plugins | Mods | Notes |
|---|---|---|---|
| Paper | ✅ | ❌ | High-performance Java plugin server |
| Purpur | ✅ | ❌ | Paper fork with more feature toggles |
| Leaves | ✅ | ❌ | Paper fork optimized for high-version survival servers |
| Leaf | ✅ | ❌ | Paper fork with Bukkit plugin compatibility and performance work |
| Spigot | ✅ | ❌ | Classic Bukkit-compatible plugin server |
| CraftBukkit | ✅ | ❌ | Official Bukkit implementation |
| Fabric | ❌ | ✅ | Lightweight mod loader |
| Forge | ❌ | ✅ | Traditional mod loader |
| NeoForge | ❌ | ✅ | Forge successor with installer flow |
| Quilt | ❌ | ✅ | Fabric fork with installer flow |
| Vanilla | ❌ | ❌ | Official server |
| Velocity | ❌ | ❌ | Java proxy |
| BungeeCord | ❌ | ❌ | Java proxy |
| PowerNukkitX | ✅ | ❌ | Bedrock Nukkit core using UDP |
| PocketMine-MP | ❌ | ❌ | Bedrock PHP server (downloads its own PHP runtime) |
| Allay | ❌ | ❌ | Next-gen Bedrock server, requires Java 21+ |

## 📖 Features in Detail

### 🧩 Cores and downloads

- Version lists come from official core APIs for every core — Fabric, Forge, NeoForge, and Quilt included, so brand-new releases show up as soon as upstream publishes them. Custom version strings are also supported.
- The Download screen shows the newest eight versions first and can reveal all versions.
- Changing a core clears the old versions and rejects late responses from the previous request.
- Forge, NeoForge, and Quilt run their matching installer after download.
- Version ordering compares numeric segments, so `26.2` correctly ranks above `1.21.11`.
- Core names must be unique — duplicate names are rejected before download starts.

### 📥 Server and modpack import

- **Folder / JAR** — import an existing server and detect its core/version.
- **Archive** — ZIP, TAR, TAR.GZ/TGZ, TAR.XZ/TXZ, TAR.BZ2/TBZ2, TAR.ZST/TZST, TAR.LZ4, and 7z; removes one wrapper folder and blocks path traversal.
- **Modrinth `.mrpack`** — downloads server-required files, verifies SHA-1, skips client-only files, and applies `overrides/` and `server-overrides/`.

> CurseForge packs require external metadata/API handling and cannot be imported directly yet.

### 🎮 Server control and console

- One-tap start/stop, Java heap settings, and optional crash auto-restart.
- Colored MC console, quick commands, copy, and smart auto-scroll.
- Runtime state for uptime, players, TPS, and memory.

### ⚙️ Runtime environments

- A single "Runtime environment" entry on the Dashboard switches between Java and PHP and lists the versions available in each.
- Your selection is authoritative — the app never silently rewrites it. If the choice does not match the active core, the launch reports it instead of changing anything behind your back.
- Environment & dependency management groups Java, dependencies, the Termux environment, and PHP into separate tabs, with install/uninstall and live status for each.

#### The three Java sources

| Group | What it is | Installable versions |
|---|---|---|
| **Termux JDK** | openjdk inside the Termux environment, installed via apt | Java 11 / 17 / 21 / 25 |
| **Pojav JRE** | Android-native JRE built by PojavLauncher, IPv6 supported | Java 17 / 21 / 25 |
| **PRoot container** | A standard glibc Linux container; rootfs downloaded in-app, JDK installed via apt | see below |

**Java 8 appears only under "PRoot container"** — it runs inside a container anyway, and listing it under Termux made it look like there were two copies.

#### Java versions available in PRoot containers

| Distro | Installable Java |
|---|---|
| Ubuntu 20.04 (focal) | Java 8 / 11 / 17 |
| Ubuntu 22.04 (jammy) | Java 11 / 17 / 21 |

- **Java 8 is provided by focal only**; it disappears from the list after switching the distro to 22.04 (other installed versions are unaffected). Switch with the **Ubuntu 20.04 / 22.04 pill button** inside the group.
- One container can hold several Java versions. Installing another version later does **not** re-download the rootfs.
- **Uninstalling one Java version affects that version only**; the other versions in the same container are kept. Use the separate "Delete container" button to reclaim the whole container's space.
- Containers do **not** grant root — PRoot is a pure userspace compatibility layer.
- **Java 25 is unavailable in containers** — the Ubuntu arm64 repositories do not ship that package.
- The server directory enters the container as `/srv/mineserve`, so worlds, plugins, backups and file management keep their exact locations and behaviour.

- The PHP runtime is an Android aarch64 native build with the extensions PocketMine-MP requires. It downloads on demand (~10 MB) and is shared by every PocketMine server.

**Groups are collapsed by default**: the three categories hold 11 version rows in total, and expanding them all pushes the "Uninstall / Reinstall / Install" buttons off the screen. While collapsed, the group title shows "Selected: Java 17", because those buttons act on the current selection.

> **Your runtime choice is never changed for you.** If a Minecraft version needs a different Java at startup, the app only switches the version **within the source you picked under "Runtime"**. When that source genuinely cannot provide it (for example, PRoot containers have no Java 25), it reports an error with a concrete fix (such as "switch Ubuntu 20.04 to 22.04 to get Java 21") instead of silently replacing PRoot with Termux.

### 🔌 Plugins, mods, and files

- Plugin/mod scan, enable/disable, delete, SAF upload, and URL install.
- Modrinth search with Minecraft-version and loader matching.
- Server file browse, directory creation, text editing, import/export, and full-server archive backup.
- The bundled DocumentsProvider gives compatible file managers no-root access to app data.
- Server properties are detected per core (`server.properties` for Java, `pnx.yml` for PowerNukkitX). Before the first launch generates them, the screen shows guidance instead of an empty editable form.

### 💾 Backup, crash reports, and network

- Backup, restore, delete, and SAF export for overworld, nether, and end.
- On abnormal exit, saves key log excerpts (Watchdog dumps, exception stacks, fatal errors) plus the most recent 800 `latest.log` lines, together with the newest native report; the crash screen aggregates native reports from all installed servers and analyzes them locally.
- frp, bore, SakuraFrp, and ME Frp tunnels, with one-tap LAN/public address copy.

### 🛡️ Keep-alive

- Foreground service, persistent notification, CPU/Wi-Fi wake locks, boot startup, and WorkManager periodic checks.
- Optional draggable status overlay for state, CPU, and memory.

> Android and OEM battery policies can still terminate background work. Disable battery optimization and keep the device powered. CPU values are best effort and may be unavailable where Android/PRoot restricts `/proc`.

## 📱 How does a phone run the server?

First setup installs a Termux-compatible runtime and Java into app-private storage. Java 11/17/21/25 servers are started by the Android shell; servers that need a standard glibc environment (including Java 8) run inside a PRoot Linux container. PocketMine-MP instead runs on a bundled native PHP runtime. All of them are ordinary child processes managed by the app, not root processes.

Console commands go to process stdin and output is displayed live and saved to `logs/latest.log`. Minecraft listens on normal device network ports: use the phone LAN IP locally, or a tunnel/port mapping publicly. The app cannot bypass NAT, carrier CGNAT, or firewalls.

See the [architecture document](./ARCHITECTURE.md) for implementation details.

## 📄 License

Licensed under [GNU GPL v3.0 or later](LICENSE).
