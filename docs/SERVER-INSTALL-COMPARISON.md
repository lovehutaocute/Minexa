# Minecraft 服务端下载与安装流程对比：EdgeCube vs Minexa

> 依据：`edgecube-source/EdgeCube-main`（Flutter + Kotlin）与 `MCServerManager`（Kotlin/Compose）实际源码。
> 不采信 README / UI 文案，所有结论都能落到具体的文件与函数。
> 分析时间：2026-11-28。未修改任何代码。

---

## 0. 结论速览

| 维度 | 胜方 | 一句话 |
|---|---|---|
| 校验强度 | **EdgeCube**（多数核心）/ **Minexa**（PNX、BDS） | EdgeCube 拿 API 给的 sha1/sha256 真校验；Minexa 除 PNX、BDS 外只查「>1KB」 |
| 镜像覆盖 | **Minexa** | 内置 BMCLAPI + 4 个 GitHub 反代 + 阿里/华为 Maven，无需用户开关；EdgeCube 只有 MSL 一家且**默认关闭** |
| 网络容错 | **Minexa** | 多源轮转 + 3~4 次重试 + 逐源超时；EdgeCube 是「试一次，失败弹窗」 |
| 下载性能 | **EdgeCube** | downloadx：8 文件并发、分片、断点续传；Minexa 单文件 4 线程、无续传 |
| installer 可靠性 | **Minexa** | 重试 + 镜像 + 依赖库预下载 + 信任库注入 + 产物复检；EdgeCube 无超时、无重试、无校验 |
| Java 适配 | **Minexa** | 用户显式选环境且安装前硬校验；EdgeCube 的 `_jreIdForMc` 对 1.x 恒返回已废弃的 jre8 |
| 核心覆盖 | 各有取舍 | Minexa 有 Quilt + BDS；EdgeCube 有 Survivalcraft |

一句话总览：**EdgeCube 强在「下载引擎」和「校验纪律」，弱在「装」；Minexa 强在「装」和「镜像」，弱在「下载校验」和「弱网」。**

两边都有真实失败案例，第 6 节单列。

---

## 1. 服务端支持矩阵

`Minexa`：`ServerCore` 枚举，`app/src/main/java/com/mineserve/mobile/data/McConfig.kt:18`，共 17 项。
`EdgeCube`：字符串类型，`lib/instance/server_select_step.dart:107` + `create_download_category_page.dart:19`，共 16 项。

| 服务端 | Minexa | EdgeCube | 备注 |
|---|:---:|:---:|---|
| Vanilla | ✅ | ✅ | |
| Paper | ✅ | ✅ | |
| Purpur | ✅ | ✅ | |
| Leaves | ✅ | ✅ | |
| Leaf | ✅ | ✅ | |
| Spigot | ✅ | ✅ | 两边都用 `cdn.getbukkit.org` |
| CraftBukkit | ✅ | ✅ | 同上 |
| Fabric | ✅ | ✅ | 都是直下打包好的 server jar |
| Forge | ✅ | ✅ | installer 型 |
| NeoForge | ✅ | ✅ | installer 型 |
| Quilt | ✅ | ❌ | |
| Velocity | ✅ | ✅ | |
| BungeeCord | ✅ | ✅ | |
| PowerNukkitX | ✅ | ✅ | |
| PocketMine-MP | ✅ | ✅ | |
| Allay | ✅ | ✅ | |
| BDS（官方基岩） | ✅ | ❌ | 只有 Minexa |
| SurvivalcraftNet | ❌ | ✅ | 只有 EdgeCube |

---

## 2. 逐个核心的下载 / 安装链路

### 2.1 Minexa

统一入口：`McServerController.downloadCore()` → `downloadCoreTo()` → `MultiThreadDownloader.download()`。
`needsInstaller` 的三类（Forge/NeoForge/Quilt）在 `runInstallerWithRetry()` 里分流。

| 核心 | 版本来源 | 下载地址 | 完整性校验 | 安装动作 | 关键函数 |
|---|---|---|---|---|---|
| Vanilla | `piston-meta .../version_manifest_v2.json` 过滤 snapshot/pre | 版本 JSON 的 `downloads.server.url`；镜像 `bmclapi2.../version/{v}/server` | 仅 >1KB | 改名为 `server.jar` | `resolveVanillaUrl` / `fetchVanillaVersions` |
| Paper | `fill.papermc.io/v3/projects/paper` 两层 versions 平铺 | `builds` 里按 STABLE>RECOMMENDED>BETA>ALPHA 选 build，取 `server:default.url` | 仅 >1KB（**丢弃了 API 的 checksums.sha256**） | 改名 | `resolvePaperUrl` / `fetchPaperVersions` |
| Purpur | `api.purpurmc.org/v2/purpur` | `/{v}/latest/download`（302 到实际 jar） | 仅 >1KB | 改名 | `resolvePurpurUrl` |
| Leaves | `api.leavesmc.org/v2/projects/leaves/versions/{v}/builds/latest` | `/downloads/{application.name}` | 仅 >1KB（**丢弃 sha256**） | 改名 | `resolveLeavesUrl` |
| Leaf | `api.leafmc.one/.../versions/{v}` 取最大 build | `/builds/{n}/downloads/{primary.name}` | 仅 >1KB（**丢弃 sha256**） | 改名 | `resolveLeafUrl` |
| Spigot | **硬编码** `DEFAULT_MC_VERSIONS`（`McServerController.kt:3129`） | `cdn.getbukkit.org/spigot/spigot-{v}.jar` | 仅 >1KB | 改名 | `resolveDownloadUrl` |
| CraftBukkit | 同 Spigot | `cdn.getbukkit.org/craftbukkit/craftbukkit-{v}.jar` | 仅 >1KB | 改名 | 同上 |
| Fabric | `meta.fabricmc.net/v2/versions/loader` + `/installer`（各取首条） | `.../loader/{mc}/{loader}/{installer}/server/jar`；镜像 `bmclapi2.../fabric-meta/` | 仅 >1KB | 改名 | `resolveFabricUrl` |
| Forge | `files.minecraftforge.net/.../promotions_slim.json` 取 recommended→latest | `maven.minecraftforge.net/.../forge-{mc}-{fv}-installer.jar`；镜像 BMCLAPI forge/download | 仅 >1KB | `java -jar --installServer`，3~4 次重试 | `resolveForgeUrl` / `runInstallerWithRetry` |
| NeoForge | `maven-metadata.xml`（**BMCLAPI 优先**，官方兜底），按 MC 版本段严格匹配 | `maven.neoforged.net/.../{ver}/neoforge-{ver}-installer.jar`；镜像 BMCLAPI maven | 仅 >1KB | **先** `preDownloadInstallerLibraries()` 用 BMCLAPI 预下 70+ 库（sha1 校验），**再**跑 installer | `fetchNeoForgeMavenMetadata` / `selectNeoForgeVersion` / `preDownloadInstallerLibraries` |
| Quilt | `meta.quiltmc.org/v3/versions/installer` | `maven.quiltmc.org/.../quilt-installer-{v}.jar` | 仅 >1KB | `java -jar ... install server {mc} --install-dir=... --download-server` | `resolveQuiltUrl` |
| Velocity | `fill.papermc.io/v3/projects/velocity` 两层结构 | `builds` 末条 `application.url` | 仅 >1KB（**丢弃 sha256**） | 改名 | `resolveVelocityUrl` |
| BungeeCord | 固定 `"latest"` | `ci.md-5.net/job/BungeeCord/lastSuccessfulBuild/artifact/...` | 仅 >1KB | 改名 | `resolveBungeeUrl` |
| PowerNukkitX | GitHub Releases + Legacy Maven（`-r`/`-PNX` 后缀判断） | release asset 或 `repo1.maven.org/.../powernukkitx-{v}-shaded.jar`；镜像 阿里/华为 Maven | **sha256 + 主类存在性**（`org/powernukkitx/Server.class` 等） | 改名 + 改写 `server.properties`（`allow-shaded=true`、端口） | `resolvePowerNukkitXUrl` / `downloadCoreTo` / `updatePowerNukkitXProperties` |
| PocketMine | `api.github.com/repos/pmmp/update.pmmp.io/contents/channels`，**限制 ≤5.33.1** | 通道 JSON 的 `download_url` | 仅 >1KB | `.jar` 改名为 `PocketMine-MP.phar` | `fetchPocketMineVersions` / `PMMP_MAX_COMPAT_VERSION` |
| Allay | GitHub Releases（排除 draft/prerelease） | `browser_download_url`（优先 `allay*` 资产）+ 4 个 GitHub 反代 | 仅 >1KB（未取 asset `digest`） | 改名为 `server.jar` | `resolveAllayUrl` |
| BDS | `BdsVersionCatalogLoader`（官方接口 + 内置历史清单 + 带摘要远端清单） | `BdsVersions.candidates()` 多源（6 个国内镜像 + 官方），`Accept-Encoding: identity` | **三级**：ManifestSha256 / PinnedSha256 / Structural（zip 结构 + 主程序 + 动态库） | `BdsCompatibility.plan()` → `BdsSupport.extractZip()` → `verifyInstalled()` → `checkLaunch()` | `downloadBds` / `BdsPackageDownload.download` / `BdsPackageIntegrity` |

### 2.2 EdgeCube

统一入口：各 `SelectVersionPage` → `DownloadProgressPage._run()` 按类型分派 →
`DownloadRunner.fetchDownloadInfo()` → `DownloadEngine.downloadToFile()`。

| 核心 | 版本来源 | 下载地址 | 完整性校验 | 安装动作 | 关键函数 |
|---|---|---|---|---|---|
| Vanilla | `launchermeta.../version_manifest.json` 只留 `type=release` | 版本 JSON 的 `downloads.server.url` | **sha1** | 改名 | `DownloadRunner.fetchVanillaDownloadInfo` |
| Paper | `fill.papermc.io/v3/projects/paper` | `builds/latest` 的 `server:default.url` | **sha256** | 改名 | `DownloadInfoService.fetchPaperDownloadInfo` |
| Purpur | `api.purpurmc.org/v2/purpur/{v}` 取 `builds.latest` | `/{v}/{latest}/download` | 无 | 改名 | `fetchPurpurDownloadInfo` |
| Leaves | `api.leavesmc.org/.../builds/latest` | `/downloads/{application.name}` | **sha256**（可空） | 改名 | `fetchLeavesDownloadInfo` |
| Leaf | `api.leafmc.one/.../versions/{v}` 取 builds 最大值 | `/builds/{n}/downloads/{primary.name}` | **sha256**（可空） | 改名 | `fetchLeafDownloadInfo` |
| Spigot | **抓 `getbukkit.org/download/spigot` 的 HTML**：`<h4>Version</h4><h2>([\d.]+)</h2>` | `cdn.getbukkit.org/spigot/spigot-{v}.jar` | 无 | 改名 | `fetchSpigotVersions` / `fetchSpigotDownloadInfo` |
| CraftBukkit | 同上（`/download/craftbukkit`） | `cdn.getbukkit.org/craftbukkit/...` | 无 | 改名 | 同上 |
| Fabric | `meta.fabricmc.net/v2/versions/game`（stable）+ 用户在 loader 页选 loader 版本 | `.../loader/{mc}/{loader}/{latestInstaller}/server/jar` | 无 | 改名 | `fetchFabricMcVersions` / `fetchFabricDownloadInfo` |
| Forge | `maven.minecraftforge.net/.../maven-metadata.xml`，按第一个 `-` 切分成 `{mc: [forge...]}` | `maven.minecraftforge.net/.../forge-{mc}-{fv}-installer.jar` | 无 | Native/Proot 跑 `--installServer`，**无重试、无超时** | `fetchAllForgeVersions` / `InstallLoaderPage` / `ForgeInstaller.kt` |
| NeoForge | 同 maven-metadata，`parts[0..3]` 当 MC 版本键（`21.1.80` → `21.1`） | `maven.neoforged.net/.../{ver}/neoforge-{ver}-installer.jar` | 无 | 同上；**没有依赖库预下载** | `fetchAllNeoforgeVersions` / `_neoforgeMcToMslVersion` |
| Quilt | — | — | — | 不支持 | — |
| Velocity | `fill.papermc.io/v3/projects/velocity` | `builds/latest` 的 `server:default.url` | **sha256** | 改名 | `fetchVelocityDownloadInfo` |
| BungeeCord | 无版本概念，本页直接建实例 | `ci.md-5.net/.../api/json` 取 buildNumber 后拼 artifact URL | 无 | 改名 `bungeecord.jar` | `fetchBungeeCordDownloadInfo` / `_downloadBungeeCord` |
| PowerNukkitX | `api.github.com/.../releases`（tag + body 里的 MC 版本） | release assets 里 `.jar`，优先含 `shaded` | 无 | 改名 `powernukkitx.jar` | `fetchPowernukkitxDownloadInfo` |
| PocketMine | `pmmp/update.pmmp.io/contents/channels`，正则 `^5(\.\d+)*$`，**无版本上限** | 通道 JSON `download_url` | 无 | 改名 `PocketMine-MP.phar` | `fetchPocketmineDownloadInfo` |
| Allay | GitHub Releases（含 nightly） | `browser_download_url` | **sha256**（GitHub `digest` 字段） | 改名 `allay-server.jar` | `fetchAllayDownloadInfo` |
| Survivalcraft | Gitee Releases（`SC-SPM/SurvivalcraftNet`） | 资产名含「服务端」/`server`，优先 Linux 版 | 无 | 下载 zip → 解压到 proot rootfs `/opt/{id}` → 写 `chmod +x ./Survivalcraft` 启动命令 | `fetchSurvivalcraftDownloadInfo` / `_downloadSurvivalcraft` |

---

## 3. 分类型深入：三种安装模型的差异

### 3.1 单 jar 直下（12 种）

两边模型相同：解析直链 → 下载 → 改名 → 写配置。

差异只有三处：

1. **校验**。EdgeCube 在 Vanilla/Paper/Velocity/Leaf/Leaves/Allay 拿 API 给的哈希真校验，失败会删文件并抛 `DownloadHashMismatch`（`download_engine.dart:377`）。Minexa 只在 `downloadCoreTo` 里查 `length < 1024`（`McServerController.kt:1966`）。Paper v3 的响应里就有 `checksums.sha256`，Minexa 读到了 `downloads` 却没往下取。
2. **镜像**。Minexa 把镜像写死在 `resolveDownloadUrls`，用户无感；EdgeCube 只有一个 MSL，且 `NetworkStore.loadUseMirror()` **默认 false**（`lib/config/network_store.dart:28`），国内用户开箱即用等于没有镜像。
3. **重试**。Minexa `downloadCoreTo` 是 `maxAttempts = maxOf(3, urls.size)` 的轮转重试 + `1.5s * n` 退避；EdgeCube 是「这个源失败 → 直接进错误页，用户手点重试」。

### 3.2 installer 型（Forge / NeoForge / Quilt）

这是两边差距最大的一类，也是实际失败最多的一类。

**Minexa**（`runInstallerWithRetry`，`McServerController.kt:308`）：

- 执行序列 M→N→M→M：先试镜像，失败摘一次镜像做能力探测，再恢复镜像继续。
- NeoForge 额外做 `preDownloadInstallerLibraries`：解出 `install_profile.json` + `version.json` 合并的库清单（70+ 条），逐条走 BMCLAPI `maven` 镜像下载并校验 sha1，已存在且 sha1 命中直接跳过。这是绕开「LegacyInstaller 不认 `--mirror`」的关键手段。
- 信任库：`installerTrustStoreProps()` 无条件构建并集信任库 + keytool 复检；PRoot 分支用 `stageGuestTrustStore()` 把它铺到 `/srv/mineserve/.mineserve/ca/` 并在容器内复检。
- 产物复检：`requireInstallerLaunchArtifacts()`，失败清目录。
- 超时：PRoot / FCL 分支有 900s 硬超时（`runProotInstaller` / `runFclJavaCommand`），但**宿主分支走 `execOnce` → `process.waitFor()`，没有超时**（`CommandExecutor.kt:161`）。installer 卡在下载时界面会一直停在「安装中」。

**EdgeCube**（`InstallLoaderPage` + `ForgeInstaller.kt`）：

- 只有一次尝试。`p.waitFor()` 无超时（`ForgeInstaller.kt:140`），installer 卡住就永远卡住。
- 没有依赖库预下载。NeoForge 直接从 `maven.neoforged.net` 拉库，国内基本失败。
- 没有信任库处理。`runInstaller` 只设 `LD_PRELOAD=libtagfix.so` + `JAVA_HOME`，`runInstallerProot` 只透传 proot 环境。
- 安装成功后靠 `_findServerJar`（文件名前缀 `forge-`/`neoforge-`）或 `ForgeLaunch.detectArgfile`（解析 `run.sh` 里的 `@libraries/.../unix_args.txt`）定位启动方式。`@argfile` 哨兵设计很干净。

### 3.3 基岩 / 非 Java（PNX / PocketMine / Allay / BDS / Survivalcraft）

- **PocketMine**：Minexa 把上限钉在 `PMMP_MAX_COMPAT_VERSION = 5.33.1`（`McServerController.kt:3104`），因为 5.34.0 起要求 `pmmp/ext-encoding ~1.0`，而内置 PHP 运行时是 0.4.x。EdgeCube 没有这个上限 —— 用户装 5.34+ 会得到「PHP 扩展版本不满足」启动失败。这是 Minexa 的隐性优势。
- **PowerNukkitX**：只有 Minexa 做 sha256 + 主类存在性双校验，也是唯一会把 `allow-shaded=true` 写进 `server.properties` 的实现。EdgeCube 只挑资产。
- **BDS**：只有 Minexa。`MultiSourceDownload`（`data/DownloadSources.kt`）是项目里唯一「源即数据」的下载层：每源独立 `*.part<N>`、单源硬超时（文本 15s / 二进制 180s / 总 10min）、`validate` 钩子把 HTML 错误页也算失败、全部失败时把每个源的失败原因带回 UI。比核心下载那条路径严格得多。
- **Survivalcraft**：只有 EdgeCube。下载 zip 到临时目录 → `ArchiveService.extractWithProgress` 解压到 rootfs `/opt/{id}`，配置 `runtime=proot` + `chmod +x` 启动。

---

## 4. 横向对比

| 维度 | Minexa | EdgeCube |
|---|---|---|
| 下载实现 | 自研 `MultiThreadDownloader`：HTTP Range 分片，默认 4 线程（可调 1~16），`<1MB` 或服务端不支持 Range 时降级单流，明文 http 走裸 socket | `downloadx` 库：`maxParallel=8`（跨文件）、`targetChunkCount=4`、`minChunkSize=1MiB`、`requestTimeout=30s`、**断点续传**、分片级重试 |
| 断点续传 | ❌ 失败即删重下 | ✅ 内置（但 `_clearOrphans()` 启动时清空，跨会话不续） |
| 跨卷问题 | 无（下载直接写目标） | 先下到内部缓存目录再 `_moveTo()`，`rename` 失败回落 `copy+delete`（处理 EXDEV） |
| 速度展示 | 3 秒滑动窗口算速（`speedBytesPerSecond`） | 同样 3 秒窗口 + ETA |
| 镜像源 | BMCLAPI（Vanilla/Forge/Fabric/NeoForge）、ghfast/gh-proxy×2/ghproxy.net、阿里/华为 Maven、BDS 6 源 | MSL 单一镜像，**默认关闭** |
| 多源回退 | 核心下载：URL 列表轮转；BDS：`MultiSourceDownload` 逐源 | `downloadToFileMultiSource` 存在，但只有少数路径用 |
| 下载校验 | 仅 PNX（sha256）、BDS（三级）；其余长度阈值 | Vanilla(sha1)/Paper/Velocity/Leaf/Leaves/Allay(sha256)；其余无 |
| 网络超时 | 元数据 15s（重试时递增）、库 20s/60s、BDS 逐源硬超时；**宿主 installer 无超时**（见 7.8） | 请求 30s；installer 进程**无超时** |
| 失败重试 | 下载 3 次、installer 3~4 次、库 2 轮 | 无自动重试，靠用户点「重新选择」 |
| 半成品处理 | installer/BDS 失败删整个目录；`requireInstallerLaunchArtifacts` 兜底 | `dispose()` 里删空实例（未完成时）；不需要 `_completed` 标记 |
| Java 环境适配 | 用户显式选 `vendor+version`；installer 前 `isJavaInstalled` 硬拦截；`recommendedJavaForMinecraft` 推荐；Forge ≤1.16 强制 Java 8 | `jreIdForMc` 自动推断 + 不可用回退 `available.first`；**`InstallLoaderPage._jreIdForMc` 有 bug（见 6.1）** |
| 异常可读性 | 逐行 `emitLog` 进控制台 + `server_errors.json` 规则引擎（cacerts/PKIX 等 20+ 条）+ 崩溃报告 | 安装器日志实时流（`ForgeInstallingStep`），错误文案走 i18n；日志导出按钮已实现但**未接线**（`onExportLogs` 未传入） |

---

## 5. EdgeCube 值得借鉴 / 不建议照搬

### 值得借鉴

1. **「下载完立刻校验哈希，不匹配删文件换源」**。`DownloadEngine.downloadToFile(sha1/sha256)` 把校验做成引擎的一等公民，调用方不用记得做。Minexa 应该照这个模型改：把 `validate` 变成 `downloadCoreTo` 的必填参数，而不是可选后置检查。
2. **下载目录与目标目录分离 + `_moveTo()` 处理 EXDEV**。Minexa 在 Android 公共存储（FUSE）上直接写目标文件，弱网中断会留下半截 `server.jar`；EdgeCube 那套「先落到私有缓存再搬运」天然免疫。
3. **`ForgeLaunch` 的 `@argfile` 哨兵**。用一个 `serverFile` 字符串前缀表达「用 `java @libraries/.../unix_args.txt` 启动」，不改实例结构就兼容了 1.17+ 的新布局。Minexa 是另起一套 `forgeLaunchArguments()`，逻辑更重。
4. **断点续传**。Minexa 的 `MultiThreadDownloader` 分片失败会整体回退单流**全量重下**（`singleStreamWithRetry`），100MB 的 BDS 包在弱网下代价极高。顺带一提，它「分片 ETag/长度与探测结果不一致就整体回退」的设计（`MultiThreadDownloader.kt:214`）值得保留 —— 那是防止两次 CDN revision 被拼成一个文件的正确做法，不要为了续传把它去掉。
5. **`DownloadProgress` 带 `fraction=-1` 表达「总大小未知」**。Minexa 用 `-1` 传 `totalBytes`，语义相同但没形成约定；明确成 `hasTotal` 属性更好用。

### 不建议照搬

1. **MSL 单镜像且默认关闭**。国内网络下等于没有镜像，还把「开不开镜像」的决策丢给用户。Minexa 的「数据化多源 + 每源硬超时」是对的，不要退化成单镜像开关。
2. **`getbukkit.org` HTML 正则抓版本**。页面改版即断，且 getbukkit 的 jar 本身是二次分发，时效性差。Minexa 的硬编码列表虽然会 404，但至少不会因为解析失败整个列表空掉 —— 两边都该换成可靠的版本源（见 P1）。
3. **installer 无超时**。`p.waitFor()` 裸调用在手机上就是「永远转圈」。Minexa 的 `900_000ms` 硬超时是必须保留的。
4. **Java 版本推断的两份实现**。EdgeCube 在 `download_runner.dart:241` 有一份正确的，在 `create_download_install_loader_page.dart:311` 又抄了一份错的 —— 这正是 Minexa 记忆里「判定统一走同一个谓词」要防的事。不要引入第二处推导。
5. **用 `available.first` 兜底 Java 版本**。装 Forge 1.20.1 却拿 Java 25 跑 installer，失败信息完全指不到根因。Minexa 的「硬拦截 + 明确报错」更诚实。

---

## 6. EdgeCube 的实际失败点（源码依据）

### 6.1 Forge/NeoForge 安装必然拿错 Java（P0 级 bug）

`create_download_install_loader_page.dart:311`：

```dart
static String _jreIdForMc(String mcVersion) {
  final major = int.tryParse(mcVersion.split('.').first) ?? 0;  // "1.21.1" → "1" → 1
  if (major >= 26) return 'jre25';
  if (major >= 21) return 'jre21';   // 永远进不来
  ...
  return 'jre8';                     // 实际永远返回这个
}
```

而 `download_runner.dart:241` 的同名函数会正确解析 minor/patch。前者用于 **installer 环境选择**，后者用于**下载后写配置**。
结果：装任何 1.x 的 Forge/NeoForge，preferred 都是已经不存在的 `jre8` → `resolveJavaEnv` 回退到 `nativeJreIds.first`（`java_env_resolver.dart:71`）→ 用了哪套 JRE 取决于安装顺序。

### 6.2 NeoForge 依赖库直连 maven.neoforged.net

`ForgeInstaller.runInstaller` 只是把 installer 跑起来，库下载全靠 installer 自己。没有 Minexa 那种 `preDownloadInstallerLibraries`。国内网络下这一步是主要失败源。

### 6.3 安装器无超时 / 无重试

`ForgeInstaller.kt:140` `p.waitFor()`，以及 `InstallLoaderPage._downloadJar` 失败就 `setState(error)`，没有自动重试。

### 6.4 Spigot / CraftBukkit 链路脆弱

版本列表来自 `getbukkit.org` 的 HTML（`version_fetch_service.dart:45`），jar 来自 `cdn.getbukkit.org`（`download_info_service.dart:62`）。两个域名都在境外，HTML 结构变更即断。

### 6.5 镜像默认关闭

`network_store.dart:28` `?? false`。用户不主动开，「镜像」这个功能就不存在。

### 6.6 PocketMine 无兼容上限

对比 Minexa 的 `PMMP_MAX_COMPAT_VERSION`，EdgeCube 会把 5.34+ 也列出来，装完启动时报 PHP 扩展版本不满足。

---

## 7. Minexa 当前的问题

按「是否直接导致装不上 / 装坏」排序。

### 7.1 核心下载几乎没有完整性校验（最严重）

`downloadCoreTo` 只有：

```kotlin
if (fileSize < 1024) throw RuntimeException("下载文件过小 ...")
```

后果：被运营商劫持返回的 HTML 错误页、CDN 截断的 jar、反代返回的 302 落地页，只要 >1KB 就当成下载成功。要等到用户点启动才炸，且报错位置离真因很远（`Invalid or corrupt jarfile`）。
而 Paper/Velocity 的 v3 API（`checksums.sha256`）、Leaves/Leaf 的 API（`sha256`）、Mojang 的 version JSON（`downloads.server.sha1`）、GitHub 的 asset `digest` —— **哈希就在响应里，只是没取**。`resolveAllayUrl` 甚至已经在解析 GitHub release 的 assets，却没顺手带上 `digest`。

### 7.2 Spigot / CraftBukkit 的版本列表是硬编码

`ServerCore.Spigot, ServerCore.CraftBukkit -> DEFAULT_MC_VERSIONS`（`McServerController.kt:1513`）。
用户选了 26.3，`cdn.getbukkit.org/spigot/spigot-26.3.jar` 必然 404，然后走完 3 次重试才报「下载失败（已重试3次）」—— 用户无法从文案判断是「这个版本本来就没有」。

### 7.3 installer 失败只报退出码

`throw RuntimeException("Forge installer 执行失败 (exit=$code)")`。NeoForge 那条稍好（会带上 `report.failed`），Forge/Quilt 没有。用户拿到 `exit=1` 无从下手。

### 7.4 大文件无断点续传

`MultiThreadDownloader` 失败后 `outFile.delete()`，下一次从 0 开始。BDS 包 100MB+、弱网下反复失败会持续消耗流量且永远装不完。

### 7.5 NeoForge 依赖库预下载是串行的

`preDownloadInstallerLibraries` 里 `for ((index, lib) in libraries.withIndex())`，70+ 个库逐个下。EdgeCube 的 downloadx 是 8 并发。这一项直接决定 NeoForge 安装时长（真机 3~8 分钟）。

### 7.6 镜像缺乏健康度记忆

`resolveDownloadUrls` 每次都把镜像排在官方前面。BMCLAPI 某天挂了，用户每次下载都要先白等一次超时。没有「上次这个源超时了，这次排后面 / 跳过」的短期记忆（BDS 那条链路有，核心下载没有）。

### 7.7 基岩核心的 GitHub 直连

PocketMine（`raw.githubusercontent.com`）、PNX、Allay（`api.github.com`）都只有 GitHub 一条链路（Allay 有 4 个反代，PNX 有 Maven 镜像，PocketMine 最裸）。

### 7.8 宿主 installer 没有超时（和 7.3 是两个独立问题）

`execOnce` 收尾是裸 `process.waitFor()`（`CommandExecutor.kt:161`）。PRoot/FCL 两条分支都有 `timeoutMs = 900_000`，唯独最常用的「Termux 宿主 JDK」这条没有。
后果：installer 在拉某个慢库时挂住，用户看到的是「安装中」永远不动，控件不可用，只能杀 App。这也是「三次尝试」类反馈里最容易被误读成「卡死」的形态。

---

## 8. 改进优先级

### P0 — 直接决定「装得上 / 装不上」

| # | 事项 | 落点 |
|---|---|---|
| P0-1 | 核心下载补哈希校验：Paper/Velocity 取 `checksums.sha256`，Vanilla 取 `downloads.server.sha1`，Leaves/Leaf/Allay 取 API/asset digest，Forge/NeoForge installer 取 maven 的 `.sha1`；不匹配删文件换源 | `resolveDownloadUrls` 返回值改为带 digest 的数据类；`downloadCoreTo` 加 `validate` |
| P0-2 | Spigot/CraftBukkit 版本列表改为「真实可用性」：先探测 `HEAD cdn.getbukkit.org/...`，或整体换到有版本 API 的源；不可用时明确报「该版本无 Spigot 构建」而不是重试 3 次 | `fetchVersionOptions` |
| P0-3 | installer 失败附带原因归因：解析 installer 输出尾部的关键字（`Could not connect` / `trustAnchors` / `Unsupported class file` / `No such file`），映射成一句话写进异常文案 | `runInstallerWithRetry` 复用 `CrashInsightEngine` 的规则表 |
| P0-4 | 大文件断点续传：`.part` 分片保留 + `Range` 续传，失败不删已下部分 | `MultiThreadDownloader` |
| P0-5 | 宿主 installer 加硬超时（复刻 PRoot/FCL 的 900s），超时后强杀进程并保留已下产物 | `CommandExecutor.execOnce` 增加 `execOnceWithTimeout` 版本，`runInstallerWithRetry` 改用它 |

### P1 — 决定「装得快 / 装得稳」

| # | 事项 | 落点 |
|---|---|---|
| P1-1 | `preDownloadInstallerLibraries` 并发化（4~6 路），保留 sha1 跳过与两轮重试 | 同函数，改 `forEach` + 信号量 |
| P1-2 | 下载源健康度短期记忆：每个源记录上次失败类型与时间（内存 + 落盘），排序时降权 | `resolveDownloadUrls` 前置一层 `SourceRanker` |
| P1-3 | PocketMine 补 GitHub 反代（复用 `githubMirrors`） | `resolvePocketMineUrl` |
| P1-4 | PRoot 容器 installer 的兜底再加一层：并集库也被容器 keytool 判为不可读时，容器内跑一次 `update-ca-certificates` 并重建 `$JAVA_HOME/lib/security/cacerts` | `stageGuestTrustStore` |
| P1-5 | 安装/下载失败保留现场：把本次 `[install]`/`[download]` 段落单独落盘为 `install-<时间>.log`，崩溃报告页可一键导出 | `downloadCore` 外层 |

### P2 — 体验与长期维护

| # | 事项 | 落点 |
|---|---|---|
| P2-1 | 下载前测速选源（并发探 3 个源的速度，选最快的），替代固定顺序 | `MultiThreadDownloader` 上层 |
| P2-2 | 版本列表接口失败时给「离线兜底列表 + 明示是兜底」的 UI 标识（现在兜底发生了但用户不知道） | `fetchVersionOptions` |
| P2-3 | installer 依赖库下载进度并入主进度条（现在只打日志，用户以为卡住） | `preDownloadInstallerLibraries` |
| P2-4 | 统一「安装完成」判定：所有核心都跑一次产物冒烟检查（jar 能否打开、主类是否存在），而不是只对 PNX/Forge 系做 | `requireInstallerLaunchArtifacts` 扩展 |
| P2-5 | BDS 的 `MultiSourceDownload` 模式反哺核心下载（源即数据 + validate 钩子 + 每源硬超时），让两条链路共用一套 | 抽象下载层 |

---

## 附：引用索引

**Minexa**
- `server/McServerController.kt`：`downloadCore`(713) / `downloadBds`(929) / `resolveDownloadUrls`(1114) / 各 `resolve*Url` / `downloadCoreTo`(1936) / `runInstallerWithRetry`(308) / `preDownloadInstallerLibraries`(453) / `requireInstallerLaunchArtifacts`(2960) / `recommendedJavaForMinecraft`(2401)
- `runtime/TermuxRuntime.kt`：`runProotInstaller`(2164) / `runFclJavaCommand`(2224) / `installerTrustStoreProps` / `stageGuestTrustStore` / `ensureRuntimeTrustStore`(3814)
- `data/MultiThreadDownloader.kt`、`data/DownloadSources.kt`、`data/DownloadPrefs.kt`
- `server/BdsPackageDownload.kt`、`BdsPackageIntegrity`、`BdsCompatibility`、`server/InstallerLibraryManifest.kt`

**EdgeCube**
- `lib/instance/download_runner.dart`：`fetchDownloadInfo` / `downloadAndConfigure` / `jreIdForMc`(241) / `resolveJavaVersion`(260)
- `lib/instance/download_info_service.dart`：13 个 `fetch*DownloadInfo`
- `lib/instance/version_fetch_service.dart`：`fetchAllForgeVersions` / `fetchAllNeoforgeVersions` / 其余
- `lib/instance/create_download_progress_page.dart`：`_run` 分派
- `lib/instance/create_download_install_loader_page.dart`：`_jreIdForMc`(311，错误版) / `_runInstaller`(172)
- `lib/net/download_engine.dart`、`lib/net/msl_mirror.dart`、`lib/config/download_store.dart`
- `android/.../server/ForgeInstaller.kt`、`lib/instance/forge_launch.dart`、`lib/server/java_env_resolver.dart`
