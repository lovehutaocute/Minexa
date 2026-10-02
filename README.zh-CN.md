# Minexa（MineServe Mobile）

<div align="center">

# ✅ 可以正常使用

**在安卓手机上直接运行 Minecraft 服务端，无需 root。**

[English](./README.md) · [架构文档](./ARCHITECTURE.md) · [更新日志](./CHANGELOG.md) · [自动任务梳理](./docs/AUTOMATION-TASKS.md)

</div>

Minexa 是一款 Android 原生应用，可在手机上运行 Minecraft Java 版服务端，并通过 PowerNukkitX、PocketMine-MP、Allay 支持基岩版。它提供多核心管理、插件与模组管理、Modrinth 对接、内网穿透、自动备份、崩溃报告、后台保活、MCP 远程管理，以及免 Root 数据访问。

## ✨ 特性

- **无需 root**：内置 Termux bootstrap 运行时；Java 11/17/21/25 直接运行，Java 8 与需要在标准 glibc 环境运行的服务端使用 PRoot Linux 容器（Ubuntu 20.04 / 22.04）。
- **运行环境可选**：Java 与 PHP 两套运行时并存，在界面上选定后启动时即按所选环境执行；PocketMine-MP 使用内置的原生 PHP 运行时，其余核心走 JVM。
- **16 类核心**：Paper、Purpur、Leaves、Leaf、Spigot、CraftBukkit、Fabric、Forge、NeoForge、Quilt、Vanilla、Velocity、BungeeCord、PowerNukkitX、PocketMine-MP、Allay。
- **多核心管理**：多个服务器隔离存放，一键切换；世界、配置、插件和模组互不干扰。
- **导入服务器**：文件夹、压缩包、单个 JAR 与 Modrinth `.mrpack` 独立导入入口，自动识别核心和版本。
- **插件与模组**：扫描、启停、SAF 上传、URL 安装和 Modrinth 搜索下载。
- **内网穿透**：自建 frp 与 bore，以及 SakuraFrp、ME Frp 两家托管平台；概览页展示局域网和公网地址。
- **备份与崩溃报告**：世界/整服备份、SAF 导出；异常退出保存关键日志片段与原生报告，并汇总已安装服务器的原生崩溃报告。报告会完整保留 Watchdog / 线程栈等关键片段，不会被截断。
- **后台保活**：前台服务、唤醒锁、开机自启、周期检查和可选状态悬浮条。
- **桌面小组件**：概览、控制台、事件日志、模组/插件四种组件，带操作按钮，由周期任务刷新。
- **MCP 远程管理**：内嵌 Model Context Protocol 服务器（Streamable HTTP），局域网内 AI 助手可查询状态、开关服、发控制台命令、读日志；Bearer 令牌鉴权。
- **Material 3 UI**：Jetpack Compose 构建，紧凑核心选择和版本列表。

## 📋 系统要求

- Android 8.0（API 26）及以上。
- `arm64-v8a` 架构。
- 建议至少 4 GB RAM，并为运行时、世界和备份预留空间。
- 首次初始化、下载核心和整合包导入需要网络。

## 📥 下载安装

### 方式一：直接下载 APK

从 [Releases 页面](https://github.com/lovehutaocute/MineServe-Mobile/releases) 下载适用于手机的 `arm64-v8a` APK。

### 方式二：自行编译

```powershell
git clone https://github.com/lovehutaocute/MineServe-Mobile.git
cd MineServe-Mobile
.\gradlew.bat :app:assembleDebug
```

APK 位于 `app/build/outputs/apk/debug/`。

## 🚀 快速开始

1. **初始化环境**：首次打开应用，完成运行时初始化并安装所需 Java。
2. **下载或导入**：在“下载”页选择核心与版本，或打开“导入服务器”。
3. **接受 EULA**：阅读并接受服务端生成的 `eula.txt`。
4. **启动并连接**：在概览页启动；局域网玩家使用显示的手机 IP 与端口连接。

公网访问请配置 frp/bore 或自行完成路由器端口映射。

## 🧩 支持的核心

| 核心 | 插件 | 模组 | 说明 |
|---|---|---|---|
| Paper | ✅ | ❌ | 高性能 Java 插件服 |
| Purpur | ✅ | ❌ | Paper 分支，更多特性开关 |
| Leaves | ✅ | ❌ | Paper 分支，面向高版本生存服的性能优化 |
| Leaf | ✅ | ❌ | Paper 分支，兼容 Bukkit 插件的性能优化 |
| Spigot | ✅ | ❌ | 经典 Java 插件服 |
| CraftBukkit | ✅ | ❌ | Spigot 上游，插件服 |
| Fabric | ❌ | ✅ | 轻量级模组加载器 |
| Forge | ❌ | ✅ | 传统模组加载器 |
| NeoForge | ❌ | ✅ | Forge 继任者，installer 流程 |
| Quilt | ❌ | ✅ | Fabric 分支，installer 流程 |
| Vanilla | ❌ | ❌ | 官方原版 |
| Velocity | ❌ | ❌ | Java 代理端 |
| BungeeCord | ❌ | ❌ | Java 代理端 |
| PowerNukkitX | ✅ | ❌ | 基岩版 Nukkit 核心，使用 UDP |
| PocketMine-MP | ❌ | ❌ | 基岩版 PHP 服务端（自动下载 PHP 运行环境） |
| Allay | ❌ | ❌ | 新一代基岩版服务端，需要 Java 21+ |

## 📖 功能详解

### 🧩 服务端核心与下载

- 版本列表**全部**从各核心官方 API 实时获取（含 Fabric、Forge、NeoForge、Quilt），上游一发布新版本即可检测到；同时支持自定义版本字符串。
- 下载页默认显示最近 8 个版本，可展开完整列表。
- 切换核心会立即清空旧版本，并忽略晚到的旧请求结果。
- Forge、NeoForge、Quilt 会在下载后执行对应 installer。
- 版本排序按数字段比较，`26.2` 能正确排在 `1.21.11` 之前。
- 核心名称不允许重名，重名会在下载前直接拦截。

### 📥 导入服务器与整合包

- **文件夹 / JAR**：导入已有服务端并自动识别核心与版本。
- **压缩包**：支持 ZIP、TAR、TAR.GZ/TGZ、TAR.XZ/TXZ、TAR.BZ2/TBZ2、TAR.ZST/TZST、TAR.LZ4、7z；自动剥离单层包装目录并防止路径穿越。
- **Modrinth `.mrpack`**：下载服务端所需文件、校验 SHA-1、跳过不适用于服务端的文件，并应用 `overrides/` 与 `server-overrides/`。

> CurseForge 整合包依赖外部元数据/API，当前暂不支持直接导入。

### 🎮 服务器控制与终端

- 一键启停、JVM 堆内存设置、可选崩溃自动重启。
- 彩色 MC 终端、快捷指令、日志复制和智能自动滚动。
- 实时展示运行时长、在线玩家、TPS 与内存等服务状态。

### ⚙️ 运行环境

- 概览页的「运行环境」入口可切换 Java / PHP，并列出该环境下的可用版本。
- **你的选择完全生效**，应用不会私自改写。若所选环境与当前核心不匹配，启动时会明确提示，而不是悄悄替你更换。
- 环境与依赖管理页把 Java、依赖、Termux 环境、PHP 分为独立分类，各自提供安装/卸载与实时状态。

#### 三种 Java 来源

| 分类 | 说明 | 可安装版本 |
|---|---|---|
| **Termux JDK** | Termux 环境里的 openjdk，apt 安装，直接在 Termux 中运行 | Java 11 / 17 / 21 / 25 |
| **Pojav JRE** | PojavLauncher 构建的 Android 原生 JRE，自带 IPv6 支持 | Java 17 / 21 / 25 |
| **PRoot 容器** | 标准 glibc Linux 容器，App 内下载 rootfs 后用 apt 安装 | 见下表 |

**Java 8 只出现在「PRoot 容器」分类下** —— 它本来就运行在容器里，列在 Termux 分组会让人以为有两份。

#### PRoot 容器可安装的 Java 版本

| 发行版 | 可安装 Java |
|---|---|
| Ubuntu 20.04 (focal) | Java 8 / 11 / 17 |
| Ubuntu 22.04 (jammy) | Java 11 / 17 / 21 |

- **Java 8 只有 focal 提供**；发行版切到 22.04 后这一项会从列表里消失（已装的其他版本不受影响）。切换发行版用分组内的 **Ubuntu 20.04 / 22.04 胶囊按钮**。
- 同一个容器里可以装多个 Java 版本，之后再装别的版本**不需要重新下载 rootfs**。
- **卸载某个 Java 版本只影响那一版**：同容器里的其他版本会保留。若想回收整个容器的空间，用独立的「删除容器」按钮。
- 容器内**不提供 Root 权限**（PRoot 是用户态兼容层）。
- **Java 25 在容器中不可用** —— Ubuntu 的 arm64 软件源不提供该包。
- 服务端目录以 `/srv/mineserve` 进入容器，世界、插件、备份与文件管理的位置和用法完全不变。

- PHP 运行时为 Android aarch64 原生构建，含 PocketMine-MP 必需扩展，约 10MB 按需下载，多个 PocketMine 服务端共享同一份。

**分组默认折叠**：三个分类共 11 条版本行，全展开会把「卸载 / 重装 / 安装」三键顶出屏幕。折叠时分组标题末尾会标出「已选 Java 17」，因为那三个按钮作用于当前选中项。

> **运行环境不会被自动改掉。** 启动时若按 MC 版本需要调整 Java，应用**只在你在「运行环境」里选的来源内换版本**。若该来源确实提供不了（例如 PRoot 容器没有 Java 25），会明确报错并给出可行做法（如「把 Ubuntu 20.04 切到 22.04 即可用 Java 21」），**不会把 PRoot 换成 Termux**。

### 🔌 插件、模组与文件

- 插件和模组扫描、启停、删除、SAF 上传、URL 安装。
- Modrinth 搜索与按 Minecraft 版本、加载器匹配下载。
- 浏览服务端文件、创建目录、编辑文本、导入导出与整服压缩备份。
- 通过内置 DocumentsProvider，兼容的文件管理器可在免 Root 条件下访问应用数据。
- 服务端配置按核心区分检测：Java 系读取 `server.properties`，PowerNukkitX 读取 `pnx.yml`。首次启动生成之前，页面给出引导提示，而不是展示一个空的可编辑表单。

### 💾 备份、崩溃与网络

- 主世界、地狱、末地的快照备份、还原、删除和 SAF 导出。
- 异常退出时保存关键日志片段（Watchdog 转储、异常栈、致命错误）与 `latest.log` 最后 800 行，并附最新原生报告；报告页面汇总全部服务器的原生 `crash-reports/` 并提供离线分析。
- frp、bore、SakuraFrp 与 ME Frp 隧道支持；局域网地址和公网地址均可一键复制。

### 🛡️ 后台保活

- 前台服务、常驻通知、CPU/Wi-Fi 唤醒锁、开机自启和 WorkManager 周期检查。
- 可选可拖动状态悬浮条，展示运行状态、CPU 和内存。

> Android 与厂商省电策略仍可能终止后台进程；请关闭本应用的电池优化并保持供电。CPU 读数受 Android/PRoot 的 `/proc` 限制，部分设备上可能不可用。

## 📱 手机是如何运行 MC 服务端的？

首次初始化时，应用会在私有目录部署 Termux 兼容运行时和 Java。Java 11/17/21/25 服务端通过 Android shell 启动；需要在标准 glibc 环境运行的服务端（含 Java 8）则在 PRoot Linux 容器中运行；PocketMine-MP 则由内置的原生 PHP 运行时执行。它们都是应用管理的普通子进程，不需要 Root。

控制台命令写入进程 stdin，输出实时显示并保存为 `logs/latest.log`。服务端监听手机正常网络端口：局域网使用手机 IP 连接；公网则依赖隧道或端口映射，无法绕过 NAT、CGNAT 和防火墙。

详细实现见 [架构文档](./ARCHITECTURE.md)。

## 📄 许可证

本项目采用 [GNU GPL v3.0 或更高版本](LICENSE)。
