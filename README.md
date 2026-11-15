# Minexa（旧名:MineServe Mobile）

<div align="center">

# ✅ Now Working · 可以正常使用

**Run Minecraft servers on an Android phone, no root required.**

[简体中文](./README.zh-CN.md) · [Architecture](./ARCHITECTURE.md) · [Changelog](./CHANGELOG.md) · [Automation inventory](./docs/AUTOMATION-TASKS.md)

</div>

Minexa is a native Android app that runs Minecraft **Java and Bedrock** servers on a phone: Java-edition cores run on the bundled Termux runtime, a PRoot container, or a native JRE, while Bedrock is served by PowerNukkitX / PocketMine-MP / Allay or by **Mojang's official Bedrock Dedicated Server (BDS)** directly. It manages multiple servers, plugins and mods, integrates with Modrinth, tunnels and meshes networks, backs up automatically, keeps the process alive in the background, schedules tasks, and exposes MCP and FTP endpoints — all without root.

## ✨ Features

- **No root**: bundled Termux bootstrap runtime. Java 11/17/21/25 run directly; Java 8 and servers that need a standard glibc environment use a PRoot Linux container (Ubuntu 20.04 / 22.04).
- **Official Bedrock (BDS)**: a PRoot Ubuntu container plus box64 translates Mojang's x86_64 `bedrock_server` to arm64. box64 and the x86_64 runtime libraries ship inside the APK, so first setup only downloads a ~32 MB container.
- **Selectable runtimes**: Java, PHP, and BDS coexist; the launch uses exactly the environment you picked. PocketMine-MP uses the bundled PHP runtime.
- **18 core types**: Paper, Purpur, Leaves, Leaf, Spigot, CraftBukkit, Fabric, Forge, NeoForge, Quilt, Vanilla, Velocity, BungeeCord, PowerNukkitX, PocketMine-MP, Allay, BDS (stable, Preview, and historical builds).
- **Multi-server management**: servers are isolated from each other and switch with one tap; worlds, configs, plugins, and mods never mix.
- **Import**: separate entries for folders, archives, a single JAR, and Modrinth `.mrpack`, with automatic core and version detection.
- **Plugins and mods**: scan, enable/disable, SAF upload, URL install, and Modrinth search.
- **Networking**: self-hosted frp, SakuraFrp, and ME Frp; plus STUN hole punching and EasyTier mesh as two independent lanes. The dashboard shows both LAN and public addresses.
- **自动化**: daily start/stop, periodic tasks (start / stop / restart / world backup / full backup / broadcast / custom command), and interval-based auto backup.
- **Backup and crash reports**: world and full-server backups with SAF export; abnormal exits capture key log excerpts and native reports, and the crash screen aggregates every server's `crash-reports/`.
- **File manager**: create, rename, copy/paste, multi-select delete, sorting, hidden files, details, and path copy.
- **Remote control**: embedded MCP server (Streamable HTTP, 57 tools, Bearer auth) and an FTP server (default port 2121).
- **Keep-alive**: foreground service, wake locks, boot startup, periodic checks, and an optional status overlay.
- **Home-screen widgets**: overview, console, event log, and mod/plugin widgets with action buttons, refreshed by periodic work.
- **Material 3 UI**: Jetpack Compose, 7 bottom tabs plus a "More" hub, with a real back stack for secondary pages.

## 📋 Requirements

- Android 8.0 (API 26) or newer, `arm64-v8a`.
- Android 11+ requires "All files access" on first use, otherwise the server directory cannot be read or written.
- 4 GB RAM recommended; BDS additionally needs roughly 380 MB of disk (container, box64, core copy).
- Network access is required for first setup, core downloads, and modpack imports.

## 💾 Where servers live

All servers live in shared storage, so reinstalling the app never loses a world and any system file manager can browse them:

```text
/storage/emulated/0/Minexa/Minecraftserver/<server-name>/
```

Upgrading from 1.3.2 or earlier prompts a one-time migration from app-private storage: the tree is copied and verified file by file before the old directory is removed, and a single unreadable file no longer aborts the whole move.

## 📥 Download and build

### Option 1: download the APK

Grab the `arm64-v8a` APK from the [Releases page](https://github.com/lovehutaocute/Minexa/releases).

### Option 2: build it yourself

```powershell
git clone https://github.com/lovehutaocute/Minexa.git
cd Minexa
.\gradlew.bat :app:assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

## 🚀 Quick Start

1. **Grant storage access** as prompted on first launch.
2. **Set up a runtime**: install a JDK under "Dependencies & environment" for Java cores, or tap "Initialize environment" in the **BDS** section for Bedrock.
3. **Download or import**: pick a core and version on the Download page, or use Import.
4. **Accept the EULA** (Java editions only) in the generated `eula.txt`.
5. **Start and connect**: launch from the dashboard. LAN players use the displayed phone IP and port; Bedrock defaults to UDP `19132`.

For public access, configure a tunnel (frp / SakuraFrp / ME Frp / STUN / EasyTier) or set up port forwarding on your router.

## 🧩 Supported Cores

The Download page offers 18 core options: the 17 below are downloadable, plus an "Unknown" placeholder used when an import or restore cannot be identified.

| Core | Kind | Plugins | Mods | Notes |
|---|---|---|---|---|
| Paper | Java | ✅ | ❌ | High-performance plugin server, versions from the PaperMC v3 API |
| Purpur | Java | ✅ | ❌ | Paper fork with extra knobs |
| Leaves | Java | ✅ | ❌ | Paper fork tuned for modern survival |
| Leaf | Java | ✅ | ❌ | Paper fork, Bukkit plugin compatible |
| Spigot | Java | ✅ | ❌ | Classic plugin server |
| CraftBukkit | Java | ✅ | ❌ | Upstream of Spigot |
| Fabric | Java | ❌ | ✅ | Lightweight mod loader |
| Forge | Java | ❌ | ✅ | Classic mod loader, installer flow |
| NeoForge | Java | ❌ | ✅ | Forge successor, installer flow |
| Quilt | Java | ❌ | ✅ | Fabric fork, installer flow |
| Vanilla | Java | ❌ | ❌ | Official vanilla |
| Velocity | Java | ❌ | ❌ | Proxy; the mod marketplace supports the velocity loader |
| BungeeCord | Java | ❌ | ❌ | Proxy; the mod marketplace supports the bungeecord loader |
| PowerNukkitX | Bedrock | ✅ | ❌ | Nukkit core on the JVM, UDP |
| PocketMine-MP | Bedrock | ❌ | ❌ | PHP server with an auto-downloaded PHP runtime |
| Allay | Bedrock | ❌ | ❌ | New-generation Bedrock server, needs Java 21+ |
| BDS | Bedrock | ❌ | ❌ | Mojang's official `bedrock_server`, translated by box64 |

## 📖 Features in Detail

### 🧩 Cores and downloads

- Version lists come live from each project's official API (including Fabric, Forge, NeoForge, and Quilt), and custom version strings are accepted. Switching cores clears the old list immediately and ignores late responses.
- Forge, NeoForge, and Quilt run their installers after download. Version sorting compares numeric segments, so `26.2` sorts before `1.21.11`.
- Every download goes through one multi-source layer: mirrors are tried in order, a failure switches to the next source with a console line naming it, and both per-source and overall budgets are enforced.
- Duplicate core names are rejected before the download starts.

### 🪨 Official Bedrock (BDS)

- **Historical versions**: a bundled catalog of 123 builds (1.7.0.13 → 1.21.100.6) merged with Mojang's official links endpoint and a remote manifest carrying SHA-256 digests. The history dialog offers search, stable/Preview filtering, and a confirmation step before downgrading.
- **Three-level integrity checks**: SHA-256 from the manifest, a locally pinned digest, or a structural zip check with per-entry CRC. A failure can be retried with a fresh download.
- **Cross-version extraction**: the install plan is derived from the real zip layout (top-level directory, shared libraries, debug symbols, bundled `server.properties`) instead of being hard-coded per version.
- **Upgrades never destroy data**: only user data is backed up, merging is write-only, the previous core is kept as `bedrock_server.old-<version>`, and a failed post-install check rolls everything back.
- **Dependency self-healing**: `DT_NEEDED` entries are parsed from the ELF, SONAME aliases are symlinked, missing libraries are reported before launch, and historical builds get their x86_64 compatibility libraries resolved as a dependency closure.
- Honest disclosure: Mojang ships Linux x86_64 builds only. Translation makes startup slower and performance roughly half of native — that is expected, not a fault.

### ⚙️ Runtime environments

- The dashboard's "Runtime environment" entry switches between Java and PHP and lists the versions available for each. Your choice is authoritative: the app never silently replaces a PRoot runtime with Termux.
- "Environment & dependency management" separates Java, dependencies, the Termux environment, PHP, and BDS, each with install/uninstall and live status.

#### The three Java sources

| Group | What it is | Installable versions |
|---|---|---|
| **Termux JDK** | openjdk inside the Termux environment, installed via apt | Java 11 / 17 / 21 / 25 |
| **Pojav JRE** | Android-native JRE packaged as `.ecpkg`, IPv6 supported | Java 17 / 21 / 25 |
| **PRoot container** | Standard glibc container; rootfs downloaded in-app, JDK via apt | Java 8 / 11 / 17 / 21 (per distro) |

- Ubuntu 20.04 (focal): Java 8 / 11 / 17; Ubuntu 22.04 (jammy): Java 11 / 17 / 21. **Java 8 is focal-only**, and **Java 25 is unavailable in containers** (no arm64 package upstream).
- One container can hold several Java versions; installing another later does not re-download the rootfs, and uninstalling one version leaves the others untouched.
- Containers grant no root — PRoot is a userspace layer. The server directory enters the container as `/srv/mineserve`, so paths and behaviour stay identical.

- The PHP runtime is an Android aarch64 native build with the extensions PocketMine-MP needs, downloaded on demand (~10 MB) and shared by every PocketMine server.
- **BDS is a separate runtime**, not a Java source: setup shows container → box64 → verification stages with typed failure reasons, in-place retry, remove/reinstall, and an environment health check covering DNS, TCP, TLS, and CA certificates.

### 🔌 Plugins, mods, and files

- Plugin/mod scan, enable/disable, delete, SAF upload, URL install, and Modrinth search matched by Minecraft version and loader.
- The Files tab is a full file manager: create files and folders, inline rename, copy and paste, multi-select delete, sort by name/time/size, toggle hidden files, details, copy path, and jump back to the root.
- Empty states distinguish "no permission", "root not created yet", "only hidden files", and "genuinely empty" instead of showing a blank list; browsing works even before the Java runtime is installed.
- The bundled DocumentsProvider lets compatible file managers reach app data without root.
- The properties screen is generated from the config files that actually exist in the server directory: `server.properties` for Java cores, `pnx.yml` for PowerNukkitX, and a localized Bedrock key set for BDS. Unknown keys land in an "Other" section under their raw names, searchable, individually revertible, range-validated, and saved as a merge of changed keys only.

### 💾 Backup, crash reports, and network

- Snapshot backup, restore, delete, and SAF export for overworld, nether, and end. BDS upgrades back up `worlds/` and the three config files first.
- Abnormal exits store key log excerpts (Watchdog dumps, exception stacks, fatal errors) plus the last 800 `latest.log` lines, and the crash screen aggregates native `crash-reports/` from all servers.
- Tunnels: self-hosted frp (importable `.ecpkg` engine), SakuraFrp, and ME Frp (token → official API). The bore backend still exists in code but has no UI entry. STUN hole punching and EasyTier mesh run as independent lanes that never contend.
- LAN and public addresses copy with one tap; the port source is re-read automatically so tunnels are never pointed at a stale port.

### 🤖 Automation and remote control

- Daily start/stop on exact alarms, degrading to window alarms without the permission.
- Periodic tasks: interval, every N days, weekly days, or a custom cron expression, with actions for start, stop, restart, world backup, full backup, broadcast, and custom command.
- Interval-based auto backup, staggered across servers, with the timestamp advanced even on failure so nothing retries every minute.
- **MCP server**: single `POST /mcp` Streamable HTTP endpoint, Bearer auth, bound to `127.0.0.1:8931` by default, and any request carrying `Origin` is rejected. Tools cover server control, files, plugins/mods, runtime install, backups, config, diagnostics, and long-running jobs.
- **FTP server**: default port 2121, anonymous or user/password, write toggle, and IPv6 dual stack.

### 🛡️ Keep-alive

- Foreground service, persistent notification, CPU/Wi-Fi wake locks, boot startup, and WorkManager periodic checks; the service is revived ~2 s after the task is swiped away.
- Optional draggable overlay showing state, CPU, and memory.

> Android and OEM battery policies can still terminate background work. Disable battery optimization and keep the device powered. CPU values are best effort and may be unavailable where `/proc` is restricted.

## 📱 How does a phone run the server?

Java 11/17/21/25 servers start through the Android shell into the bundled Termux runtime; servers that need standard glibc (including Java 8) run inside a PRoot Linux container; PocketMine-MP runs on the bundled PHP runtime; BDS runs through a PRoot Ubuntu container plus box64 translation. All of them are ordinary child processes managed by the app, not root processes.

Console commands go to process stdin, output is displayed live and saved to `logs/latest.log`. Servers listen on normal device ports — TCP 25565 for Java, UDP 19132 for Bedrock by default. Use the phone's LAN IP locally, or a tunnel/port mapping publicly; the app cannot bypass NAT, carrier CGNAT, or firewalls.

See the [architecture document](./ARCHITECTURE.md) for implementation details.

## 📄 License

Licensed under [GNU GPL v3.0 or later](LICENSE).
