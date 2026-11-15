# Minexa（旧名:MineServe Mobile）

<div align="center">

# ✅ 可以正常使用

**在安卓手机上直接运行 Minecraft 服务端，无需 root。**

[English](./README.md) · [架构文档](./ARCHITECTURE.md) · [更新日志](./CHANGELOG.md) · [自动任务梳理](./docs/AUTOMATION-TASKS.md)

</div>

Minexa 是一款 Android 原生应用，可在手机上运行 Minecraft **Java 版与基岩版**服务端：Java 系走 Termux 运行时、PRoot 容器或原生 JRE，基岩版既可用 PowerNukkitX / PocketMine-MP / Allay，也可以直接跑 **Mojang 官方基岩版专用服务器（BDS）**。它提供多核心管理、插件与模组管理、Modrinth 对接、内网穿透与组网、自动备份、崩溃报告、后台保活、定时任务、MCP 远程管理与 FTP 服务，全程免 Root。

## ✨ 特性

- **无需 root**：内置 Termux bootstrap 运行时；Java 11/17/21/25 直接运行，Java 8 与需要标准 glibc 的服务端使用 PRoot Linux 容器（Ubuntu 20.04 / 22.04）。
- **官方基岩版（BDS）**：PRoot Ubuntu + box64 把 Mojang 的 x86_64 `bedrock_server` 转译到 arm64 运行；box64 与 x86_64 运行库随 APK 内置，首次初始化只下约 32MB 容器。
- **运行环境可选**：Java / PHP / BDS 三套运行时并存，界面上选定后启动即按所选环境执行；PocketMine-MP 使用内置 PHP 运行时。
- **18 类核心**：Paper、Purpur、Leaves、Leaf、Spigot、CraftBukkit、Fabric、Forge、NeoForge、Quilt、Vanilla、Velocity、BungeeCord、PowerNukkitX、PocketMine-MP、Allay、BDS（含 Preview 通道与历史版本）。
- **多核心管理**：多个服务器隔离存放，一键切换；世界、配置、插件和模组互不干扰。
- **导入服务器**：文件夹、压缩包、单个 JAR 与 Modrinth `.mrpack` 独立导入入口，自动识别核心和版本。
- **插件与模组**：扫描、启停、SAF 上传、URL 安装和 Modrinth 搜索下载。
- **网络**：自建 frp、SakuraFrp、ME Frp 三家隧道，STUN 打洞直连与 EasyTier 组网两条独立通道；概览页同时展示局域网与公网地址。
- **自动化**：每日定时开停服、任意周期任务（开服 / 停服 / 重启 / 世界备份 / 整服备份 / 广播 / 自定义命令）、按间隔自动备份。
- **备份与崩溃报告**：世界/整服备份、SAF 导出；异常退出保存关键日志片段与原生报告，并汇总各服务器的 `crash-reports/`。
- **文件管理**：内置完整文件管理器 —— 新建、重命名、复制粘贴、批量删除、排序、隐藏文件、详情与路径复制。
- **远程管理**：内嵌 MCP 服务器（Streamable HTTP，57 个工具，Bearer 鉴权）与 FTP 服务器（默认端口 2121）。
- **后台保活**：前台服务、唤醒锁、开机自启、周期检查和可选状态悬浮条。
- **桌面小组件**：概览、控制台、事件日志、模组/插件四种组件，带操作按钮，由周期任务刷新。
- **Material 3 UI**：Jetpack Compose 构建，底部 7 个主 Tab + 「更多」二级页，二级页有完整返回栈。

## 📋 系统要求

- Android 8.0（API 26）及以上，`arm64-v8a`。
- Android 11 及以上首次使用需授予「所有文件访问」权限，否则无法读写服务器目录。
- 建议至少 4 GB RAM；BDS 额外需要约 380 MB 磁盘（容器 + box64 + 核心副本）。
- 首次初始化、下载核心和整合包导入需要网络。

## 💾 服务器存放位置

所有服务器统一放在公共目录，卸载重装应用不再丢存档，也能用系统文件管理器直接翻看：

```text
/storage/emulated/0/Minexa/Minecraftserver/<服务器名>/
```

从 1.3.2 及更早版本升级时，检测到私有目录里有旧服务器会询问是否迁移：先完整复制并逐文件校验，通过后才删旧目录；单个读不出来的文件不会中断整棵树。

## 📥 下载安装

### 方式一：直接下载 APK

从 [Releases 页面](https://github.com/lovehutaocute/Minexa/releases) 下载适用于手机的 `arm64-v8a` APK。

### 方式二：自行编译

```powershell
git clone https://github.com/lovehutaocute/Minexa.git
cd Minexa
.\gradlew.bat :app:assembleDebug
```

APK 位于 `app/build/outputs/apk/debug/`。

## 🚀 快速开始

1. **授权存储**：首次打开按提示授予文件访问权限。
2. **初始化运行时**：Java 版在「依赖与环境管理」装 JDK；基岩版 BDS 在「BDS」一栏点「初始化环境」。
3. **下载或导入**：在「下载」页选核心与版本，或从「导入服务器」进入。
4. **接受 EULA**（仅 Java 版）：阅读并接受生成的 `eula.txt`。
5. **启动并连接**：在概览页启动；局域网玩家用显示的手机 IP 与端口连接，基岩版默认 UDP `19132`。

公网访问请配置隧道（frp / SakuraFrp / ME Frp / STUN / EasyTier）或自行做路由器端口映射。

## 🧩 支持的核心

下载页一共 18 个核心选项：下表 17 个可下载，另有「未知」占位，用于识别失败的导入或还原。

| 核心 | 类型 | 插件 | 模组 | 说明 |
|---|---|---|---|---|
| Paper | Java | ✅ | ❌ | 高性能插件服，版本从 PaperMC v3 API 获取 |
| Purpur | Java | ✅ | ❌ | Paper 分支，更多可调项 |
| Leaves | Java | ✅ | ❌ | Paper 分支，高版本生存优化 |
| Leaf | Java | ✅ | ❌ | Paper 分支，兼容 Bukkit 插件 |
| Spigot | Java | ✅ | ❌ | 经典插件服 |
| CraftBukkit | Java | ✅ | ❌ | Spigot 上游 |
| Fabric | Java | ❌ | ✅ | 轻量模组加载器 |
| Forge | Java | ❌ | ✅ | 传统模组加载器，installer 流程 |
| NeoForge | Java | ❌ | ✅ | Forge 继任者，installer 流程 |
| Quilt | Java | ❌ | ✅ | Fabric 分支，installer 流程 |
| Vanilla | Java | ❌ | ❌ | 官方原版 |
| Velocity | Java | ❌ | ❌ | 代理端，模块市场支持 velocity 加载器 |
| BungeeCord | Java | ❌ | ❌ | 代理端，模块市场支持 bungeecord 加载器 |
| PowerNukkitX | 基岩 | ✅ | ❌ | Nukkit 核心，JVM 承载，UDP |
| PocketMine-MP | 基岩 | ❌ | ❌ | PHP 服务端，自动下载 PHP 运行时 |
| Allay | 基岩 | ❌ | ❌ | 新一代基岩服务端，需 Java 21+ |
| BDS | 基岩 | ❌ | ❌ | Mojang 官方 `bedrock_server`，box64 转译 |

## 📖 功能详解

### 🧩 核心与下载

- 版本列表从各核心官方 API 实时获取（含 Fabric、Forge、NeoForge、Quilt），支持自定义版本字符串；切换核心会立即清空旧版本并忽略晚到的旧请求。
- Forge、NeoForge、Quilt 下载后自动执行 installer；版本排序按数字段比较（`26.2` 排在 `1.21.11` 之前）。
- 下载统一走多源下载层：按顺序尝试镜像，失败自动换源并在控制台说明换到了哪个源，每源硬超时 + 整体总预算。
- 核心名称不允许重名，重名在下载前直接拦截。

### 🪨 官方基岩版（BDS）

- **历史版本**：内置 123 条版本清单（1.7.0.13 → 1.21.100.6），与 Mojang 官方 links 接口、远端带 SHA-256 的清单三路合并；「历史版本」弹窗支持搜索、稳定/预览筛选、降级二次确认。
- **完整性校验三档**：有清单摘要按 SHA-256 校验，有本地固定摘要按固定值校验，都没有则做 zip 结构 + 全条目 CRC 校验；不通过可原地重新下载。
- **跨版本解压**：按 zip 真实结构生成安装计划（顶层目录、动态库、调试符号、是否自带 `server.properties`），不按版本号写死。
- **升级不毁数据**：只备份用户数据面，合并安装只写不删，旧核心保留为 `bedrock_server.old-<版本>`，落地复检失败自动回滚。
- **依赖自愈**：解析 ELF 的 `DT_NEEDED`，按 SONAME 补别名软链，启动前报告缺失库；历史版本的 x86_64 兼容库按依赖闭包自动补齐。
- 如实说明：官方只发布 Linux x86_64 构建，转译启动比原生慢、性能约为原生一半，属正常现象。

### ⚙️ 运行环境

- 概览页「运行环境」入口可切换 Java / PHP，并列出该环境下的可用版本；你的选择完全生效，应用不会私自把 PRoot 换成 Termux。
- 「依赖与环境管理」把 Java、依赖、Termux 环境、PHP、BDS 分为独立分类，各自提供安装/卸载与实时状态。

#### Java 来源

| 分类 | 说明 | 可安装版本 |
|---|---|---|
| **Termux JDK** | Termux 环境内的 openjdk，apt 安装 | Java 11 / 17 / 21 / 25 |
| **Pojav JRE** | 原生 Android JRE（`.ecpkg`），自带 IPv6 支持 | Java 17 / 21 / 25 |
| **PRoot 容器** | 标准 glibc 容器，App 内下载 rootfs 后 apt 安装 | Java 8 / 11 / 17 / 21（随发行版） |

- Ubuntu 20.04 (focal)：Java 8 / 11 / 17；Ubuntu 22.04 (jammy)：Java 11 / 17 / 21。**Java 8 只在 focal 提供**，Java 25 在容器中不可用（arm64 源没有该包）。
- 同一容器可并存多个 Java 版本，再装别的版本不需要重下 rootfs；卸载某一版只影响那一版。
- 容器内不提供 Root 权限（PRoot 是用户态兼容层）；服务端目录以 `/srv/mineserve` 进入容器，文件位置与用法不变。

- PHP 运行时为 Android aarch64 原生构建，约 10MB 按需下载，多个 PocketMine 服务端共享同一份。
- **BDS 环境独立**：不属于任何 Java 来源，也不出现在 Java 版本列表里；初始化分「容器 → box64 → 实测」三段显示进度，失败按类型给原因，可原地重试或移除重装，另提供「环境体检」（DNS / TCP / TLS / CA 证书）。

### 🔌 插件、模组与文件

- 插件和模组扫描、启停、删除、SAF 上传、URL 安装；Modrinth 搜索按 Minecraft 版本与加载器匹配。
- 文件页是完整的文件管理器：新建文件/文件夹、行内重命名、复制 + 粘贴、多选批量删除、按名称/时间/大小排序、显示隐藏文件、详情面板、复制路径、一键回根目录。
- 空列表会区分「未授权 / 根目录未创建 / 只有隐藏文件 / 确实是空」，不再一律显示空白；首次启动 Java 环境没装好时也能浏览磁盘上的服务器。
- 通过内置 DocumentsProvider，兼容的文件管理器可在免 Root 下访问应用数据。
- 配置页按当前服务器目录里真实存在的配置文件生成界面：Java 系读 `server.properties`、PowerNukkitX 读 `pnx.yml`、BDS 读并中文化基岩专属键；未收录的键落到「其它」区按原始键名展示，可搜索、可单项还原、可按范围校验，保存只提交变动键后合并写回。

### 💾 备份、崩溃与网络

- 主世界/下界/末地快照备份、还原、删除与 SAF 导出；BDS 升级前自动备份 `worlds/` 与三份配置。
- 异常退出保存关键日志片段（Watchdog 转储、异常栈、致命错误）与 `latest.log` 最后 800 行，并汇总全部服务器的原生 `crash-reports/`。
- 隧道：自建 frp（支持导入 `.ecpkg` 引擎）、SakuraFrp、ME Frp（Token 直连官方 API）；另有 STUN 直连与 EasyTier 组网两条独立通道，互不抢占。bore 后端仍在代码里，但网络页没有入口，不要按旧教程找。
- 局域网地址与公网地址均可一键复制；端口真源变更后自动重读，不会把隧道送到旧端口。

### 🤖 自动化与远程管理

- 每日定时开服 / 停服（精确闹钟，无权限时降级为窗口闹钟）。
- 周期任务：间隔 / 每 N 天 / 每周指定日 / 自定义 cron，动作包括开服、停服、重启、世界备份、整服备份、广播、自定义命令。
- 按间隔自动备份（多服务器错峰，失败也推进时间戳，避免每分钟重试）。
- **MCP 服务器**：`POST /mcp` 单端点 Streamable HTTP，Bearer 鉴权，默认只绑 `127.0.0.1:8931`，带 `Origin` 的请求一律拒绝；工具覆盖服务器控制、文件、模组插件、运行时安装、备份、配置、诊断与长任务。
- **FTP 服务器**：默认端口 2121，支持匿名/账号密码、可写开关与 IPv6 双栈。

### 🛡️ 后台保活

- 前台服务、常驻通知、CPU/Wi-Fi 唤醒锁、开机自启和 WorkManager 周期检查；任务被划掉后 2 秒重新拉起。
- 可选可拖动状态悬浮条，展示运行状态、CPU 和内存。

> Android 与厂商省电策略仍可能终止后台进程；请关闭本应用的电池优化并保持供电。CPU 读数受 `/proc` 限制，部分设备上可能不可用。

## 📱 手机是如何运行 MC 服务端的？

Java 11/17/21/25 服务端通过 Android shell 启动内置 Termux 运行时；需要标准 glibc 的服务端（含 Java 8）在 PRoot Linux 容器中运行；PocketMine-MP 由内置 PHP 运行时执行；BDS 由 PRoot Ubuntu + box64 转译执行。它们都是应用管理的普通子进程，不需要 Root。

控制台命令写入进程 stdin，输出实时显示并保存为 `logs/latest.log`。服务端监听手机网络端口：Java 版 TCP（默认 25565），基岩版 UDP（默认 19132）；局域网用手机 IP 连接，公网依赖隧道或端口映射，无法绕过 NAT、CGNAT 与防火墙。

详细实现见 [架构文档](./ARCHITECTURE.md)。

## 📄 许可证

本项目采用 [GNU GPL v3.0 或更高版本](LICENSE)。
