# Minexa 自动任务全量梳理

> 分析对象：Minexa 主仓库（包名 `com.mineserve.mobile`）
> 代码规模：**180 个 Kotlin 文件 / 71,151 行**（`app/src/main/java` 下全部 `.kt`；统计方式 `find … | xargs cat | wc -l`）
> 测试规模：**85 个测试文件 / 13,856 行**（`app/src/test/java`，无 `androidTest` 源码）
> 梳理范围：**所有非用户点击触发的自动逻辑**（不含 onClick / 按钮回调）
> 结论依据：逐条核对当前工作树（含 1.5.0 未提交改动），行号以当前工作树为准

---

## 一、总览：自动任务按风险分级

| 级别 | 数量 | 特征 | 典型代表 |
|---|---|---|---|
| **P0 极高** | 4 | 常驻协程循环；高频仅发生在「界面可见 / 有订阅」时 | 日志逐行解析、日志刷新循环 ×3、30s watchdog |
| **P1 高** | 6 | 定时轮询 / 每次启动执行的重 IO | 资源采集、启动自检修复链、崩溃重启 |
| **P2 中** | 9 | WorkManager 周期、闹钟、事件驱动 | 15 分钟保活、自动备份、周期任务闹钟 |
| **P3 低** | 7 | 一次性 / 每日限频 | 更新检查、使用统计、BDS 环境探测 |

---

## 二、P0 极高风险任务（常驻循环）

### 1. 控制台日志逐行解析

| 维度 | 内容 |
|---|---|
| **检测/修复内容** | 对 MC 输出的**每一行**调用共享解析器 `ConsoleLineParser.parse()`：启动阶段判定、Java 版本兼容校验、在线人数 / TPS 提取；命中玩家进出服的行再走玩家名提取（名单校正、事件记录、通知），结果写回 `ServerState` |
| **功能用途** | 实时驱动 UI 状态（在线玩家、TPS、启动进度），是状态数据的唯一来源 |
| **执行时机** | MC 进程运行期间，产生任意日志行时 |
| **触发条件** | `consoleFlow` 发射 —— MC 每写一行 stdout，或日志文件被写入（`CommandExecutor.startLogWatcher` 的 FileObserver 增量推送） |
| **性能影响** | ⚠️ 高。解析器先做 `contains` 廉价预筛、命中才进正则，玩家名提取只在含 `joined/left the game` 的行上跑。**但 `McViewModel` 与 `McForegroundService` 各订阅一遍 `consoleFlow` 并各自解析一次，App 前台时同一行被解析两次**（见第六节发现 1） |

```kotlin
// McViewModel.kt:6388-6394
viewModelScope.launch(Dispatchers.Default) {
    repo.termuxRuntime.consoleFlow.collect { line ->
        consoleBuffer.add(line)
        pendingConsoleBuffer.add(line)
        parseConsoleLine(line)      // 实现见 McViewModel.kt:1861
    }
}
```

### 2. 控制台批量刷新循环（主循环）

| 维度 | 内容 |
|---|---|
| **检测/修复内容** | 快照待处理缓冲 → 对每行跑 `TerminalLogProcessor.process()`（整行翻译 + 正则 + 短语替换，逐档命中即返回；再由 `classify` 判色调）→ 发布 StateFlow；`terminalDisplayDirty` 时整体重建终端缓冲 |
| **功能用途** | 把原始日志转成中文可读终端输出，并做帧友好的批量刷新（避免逐行触发重组） |
| **执行时机** | ViewModel 创建即常驻（`init` 块） |
| **触发条件** | 界面在前台且控制台 / 预览 / 终端三条流任一有订阅时每 **200ms** 一轮；**空闲（界面不在前台，或三条流订阅数全为 0）时降为每 2s** |
| **性能影响** | ⚠️ 中高。200ms = 每秒 5 轮；字符串匹配是主要 CPU 成本。预览流另有 750ms 发布节流（`CONSOLE_PREVIEW_FLUSH_MS`），且只在内容代次变化时发布 |

```kotlin
// McViewModel.kt:6396-6441
while (true) {
    if (!uiVisible ||
        (_consoleLines.subscriptionCount.value <= 0 &&
            _consolePreviewLines.subscriptionCount.value <= 0 &&
            _terminalConsoleLines.subscriptionCount.value <= 0)
    ) {
        delay(IDLE_LOG_FLUSH_MS)      // 2000L（McViewModel.kt:1830）
        continue
    }
    delay(200)                        // ← 界面可见时（6408）
    val batch = pendingConsoleBuffer.snapshotAndClear()
    …
}
```

### 3. 日志环形缓冲刷新循环（×2）

| 维度 | 内容 |
|---|---|
| **检测/修复内容** | 两个独立循环：`legacyTermuxBuffer`（6442-6458）与 `terminalOutputBuffers`（6459-6480），各自快照并重建不可变列表（`takeLast(MAX_LOG_LINES)`，上限 1500 行） |
| **功能用途** | 维护终端历史日志（上限 1500 行），供多会话终端显示 |
| **执行时机** | ViewModel 创建即常驻（`init` 块） |
| **触发条件** | 界面在前台且对应流有订阅时每 200ms；**空闲时降为每 2s**（两处均有空闲分支） |
| **性能影响** | ⚠️ 中。空闲时不再每 200ms 唤醒做 map/filter；日志仍在持续写入缓冲，只是不发布快照 |

```kotlin
// McViewModel.kt:6447-6451
if (!uiVisible || _termuxLines.subscriptionCount.value <= 0) {
    delay(IDLE_LOG_FLUSH_MS); continue
}
delay(LOG_FLUSH_MS)                   // 200L（McViewModel.kt:1822）
```

### 4. 前台服务健康 Watchdog

| 维度 | 内容 |
|---|---|
| **检测/修复内容** | ① 按条件探测 `isMcRunning()` 并同步 `ServerState.isRunning`；② 每 2 tick 读配置 → `maybeAutoBackup()` → 发 `list` 命令（Paper 额外发 `tps`）；③ **仅当进程存活时**刷新桌面组件 |
| **功能用途** | 保证服务状态与真实进程一致；采集玩家数/TPS；驱动自动备份；更新桌面组件 |
| **执行时机** | 前台服务进入前台时开启（`ensureForeground()` → `startWatchdog()`，`McForegroundService.kt:253`、`433`）；App 冷启动延迟数秒自动拉起服务 |
| **触发条件** | 服务存活期间无条件循环；`watchdogStarted` CAS 保证只有一条循环（`435`） |
| **性能影响** | ⚠️ 低–中。每 30s 一轮（`488`）。**1.4.x 起不再是每 tick 都探测**：`shouldProbe` 只在「运行中 / 上轮探到存活 / 偶数 tick」成立时才读进程状态（`441`），停止态下省掉一半空唤；`isMcRunning()` 是内存查询（`Process.isAlive`），并非扫 `/proc` |

```kotlin
// McForegroundService.kt:441-488（节选）
val shouldProbe = app.repository.serverState.value.isRunning || mcAlive || tick % 2 == 0
if (shouldProbe) {
    val alive = try { termux.isMcRunning() } catch (e: Exception) { false }
    val startupPending = termux.isStartupPending()   // 启动在途：不能据此判停止
    syncLocks(alive || startupPending)
    …
    if (alive && tick % 2 == 0) { maybeAutoBackup(config); termux.sendCommand("list"); … }
    if (alive) com.mineserve.mobile.data.WidgetUpdater.refresh(this@McForegroundService)
}
tick++
kotlinx.coroutines.delay(30_000L)
```

---

## 三、P1 高风险任务

### 5. 服务器资源采集轮询

| 维度 | 内容 |
|---|---|
| **检测/修复内容** | 采集磁盘可用空间（`StatFs`）、服务器目录总字节（`walkTopDown`）、MC 进程内存（读 `/proc`）、CPU 占用、Java 是否可用 |
| **功能用途** | 概览页资源卡片数据源；目录膨胀预警 |
| **执行时机** | ViewModel 创建后立即采一次轻量数据（不扫目录），1.2s 后补采一次建立 CPU 差值基线，再延迟 15s 进入常驻循环（`startServerResourceCollection`，`McViewModel.kt:1389-1414`） |
| **触发条件** | 界面可见时每 15 秒一轮；**界面不在前台时降到 60 秒，并跳过最昂贵的目录遍历**（`1409-1411`） |
| **性能影响** | ⚠️ 中。真正的重 IO（`walkTopDown()` 递归统计）受 60s 缓存限制，后台直接不扫；`StatFs` 开销极小。并发调用被锁挡掉，**值无变化时不发布新快照** |

```kotlin
// McViewModel.kt:1409-1411
val visible = uiVisible
withContext(Dispatchers.IO) { collectServerResourcesOnce(includeDirectorySize = visible) }
delay(if (visible) 15_000 else 60_000)
```

三重缓存：目录 60s、内存 30s、Java 校验 60s；值未变化则不发布快照。手动刷新走 `refreshServerStatus()`（`1416` 起），会清缓存 + 强制发布。

### 6. 启动前运行时自检与自动修复链

| 维度 | 内容 |
|---|---|
| **检测/修复内容** | `launchMc` 拉起进程前调用 `autoRepairRuntime()`（`McServerController.kt:2333`），内部串行执行：修复 dpkg wrapper、usr/bin 归位、rootfs 可执行位、脚本解释器路径、apt 源、apt 配置、`ensureJvmComplete()` 校验 JDK 完整性、Java 符号链接；Forge/NeoForge 另补字体运行库。`fixRootfsPermissions()`（`TermuxRuntime.kt:2605`）是一键安装依赖与 bootstrap 的入口修复 |
| **功能用途** | 防止因权限/符号链接/JDK 不完整导致启动失败，是 Termux 环境自愈的核心 |
| **执行时机** | 每次启动服务端前；安装器重试路径每次尝试前也调一次（`McServerController.kt:279`） |
| **触发条件** | FCL 来源（`JavaVendor.Fcl`）直接返回 0 不修；Termux 环境未就绪返回 0；其余无条件执行，各项内部幂等 |
| **性能影响** | ⚠️ 中高（但仅一次）。最重的是 `apt-get --reinstall` 与字体安装，耗时可达数十秒；正常情况下各项检查快速返回 |

```kotlin
// TermuxRuntime.kt:2631（autoRepairRuntime 内）
var repaired = fixDpkgWrapper() + fixUsrBin() + ensureRootfsExecutable()
repaired += fixScriptsOnce() + fixAptSources() + ensureAptConfigs()
repaired += ensureJvmComplete(javaVersion)   // 实现在 3100
fixJavaSymlinks(javaVersion)
```

> `ensureJvmComplete` 只修「**已安装但不完整**」的 JDK（缺 `libjli.so` 等）：「未安装的 JDK 不会被隐式安装」。

### 7. Bootstrap 环境初始化

| 维度 | 内容 |
|---|---|
| **检测/修复内容** | 下载并解压 rootfs（`installer.ensureInstalled`）、`fixRootfsPermissions()`、apt 安装 `wget`/`curl`、`fixJavaSymlinks()`（`TermuxRuntime.bootstrap`，`TermuxRuntime.kt:3208`） |
| **功能用途** | 首次运行或环境损坏时构建完整运行环境 |
| **执行时机** | App 冷启动，`isReady()` 为 false 时（`McApplication.kt:266-272` → `startBootstrap()`，`278`）；已就绪则整段跳过 |
| **触发条件** | `isReady()` 为 false；`bootstrapInitializationInFlight` 防重入 |
| **性能影响** | ⚠️ 高（仅首次/损坏时）。大文件下载 + 解压，有明显的磁盘 IO 与网络占用 |

> JDK 不在 bootstrap 内安装：它只 apt 安装 `wget`/`curl`，JDK 由 Java 安装流程与 `ensureJvmComplete` 负责。

### 8. 崩溃检测与自动重启

| 维度 | 内容 |
|---|---|
| **检测/修复内容** | MC 进程退出回调中读 exit code →（判定异常退出时）生成崩溃报告 → 若开启自动重启且未达上限，按**指数退避**重新拉起 |
| **功能用途** | 服务意外崩溃后自动恢复；留存崩溃证据 |
| **执行时机** | MC 进程退出瞬间（`onExit` 回调） |
| **触发条件** | `config.autoRestartOnCrash`（**默认 false**）且 `restartAttempts < maxRestartAttempts`（=3，`McServerController.kt:311`）且判定为异常退出：`!userStoppedRecently && (code != 0 || failedDuringStartup)`；用户主动停止后 5 分钟内退出按正常停止处理 |
| **性能影响** | ⚠️ 低（事件驱动）。启动风暴已被指数退避抑制：3s → 6s → 12s…上限 60s（`restartBackoffMs`，`322`）；稳定运行 5 分钟后清零计数（`2481`、`2504-2540`），退避期间收到停止请求则取消 |

### 9. 启动时配置文件自动改写

| 维度 | 内容 |
|---|---|
| **检测/修复内容** | PowerNukkitX：每次启动重写 `server.properties`（端口同步）；Allay：**仅在确实绑不上 IPv6 时**把 `enablev6` 改成 false，端口每次同步；核心目录名非 ASCII 时 `renameTo` 迁移为 ASCII 名并回写配置 |
| **功能用途** | 规避 Android 平台缺陷（IPv6 绑定崩溃）与文件系统编码问题（Forge 的 ZipFileSystem 无法解析非 ASCII 工作路径） |
| **执行时机** | 每次启动（配置改写与 Java 选择同在 `launchMc`） |
| **触发条件** | 按核心类型条件执行（PNX / Allay 分支）；目录迁移仅在 `sanitizeDirName` 结果与现名不同时发生 |
| **性能影响** | 低。小文件读写 |

### 10. Java 版本自动切换

| 维度 | 内容 |
|---|---|
| **检测/修复内容** | 按 MC 版本 + 核心类型推荐所需 JDK（`recommendedJavaForMinecraft`，`McServerController.kt:2072`），当前选择不足时升级并按来源可用性修正 vendor，原子回写配置；Allay 另有 Java 21 兜底 |
| **功能用途** | 避免「Java 版本过低」导致的启动失败 |
| **执行时机** | 每次启动前（`launchMc`，`2120`；判定在 `2228`） |
| **触发条件** | `config.selectedJavaVersion.ordinal < recommended.ordinal`；Allay 低于 Java 21 时切换（未安装则直接报错） |
| **性能影响** | 低（判断），落盘走异步 debounce 队列 |

---

## 四、P2 中风险任务

| # | 任务 | 位置 | 时机 | 条件 | 性能影响 |
|---|---|---|---|---|---|
| 11 | **保活 Worker** | `KeepAliveWorker.kt`（31 行）<br>注册 `McApplication.kt:256` | 每 **15 分钟** | `KEY_KEEP_ALIVE` 为真且前台服务未运行才动作 | 低。仅拉起前台服务 |
| 12 | **组件兜底刷新 Worker** | `WidgetRefreshWorker.kt`（47 行）<br>注册 `McApplication.kt:353` | 每 **15 分钟**，App 进程死后仍调度 | 扫 `/proc` 判断孤儿进程 | 低，但**进程已死时唤醒** |
| 13 | **自动备份** | `McForegroundService.kt:494`（由 watchdog 于 `461-467` 每 60s **检查**） | 按配置间隔**执行** | `autoBackupIntervalMin > 0` + 外部存储权限 + 距上次超间隔 | ⚠️ 中高（执行时）。打包大世界磁盘 IO 峰值明显；已有 0–10 分钟错峰偏移，失败也推进时间戳以避免每 60s 重跑 |
| 14 | **每日定时开/停服（旧通道）** | `ScheduleManager.kt`（requestCode 3001/3002）<br>`ScheduleAlarmReceiver.kt`（32 行） | 每日定点 | 用户已开启 + 已注册闹钟 | 低。Receiver 内同步重注册次日，动作转交前台服务执行 |
| 15 | **周期任务闹钟** | `AutomationScheduler.kt:38-53`（requestCode 3120）<br>`AutomationAlarmReceiver.kt`（38 行）<br>执行 `AutomationRunner.kt:24` | 按任务频率：间隔 / 每 N 天 / 每周指定日 / 5 段 cron | 任务启用且 `nextRunMs` 有效 | 低。`setExactAndAllowWhileIdle`，无精确闹钟权限时降级 `setWindow`（120s 窗口，`53`）；执行后回写 `lastRunMs`/`nextRunMs` 并重排（`McForegroundService.kt:337-343`） |
| 16 | **开机广播自启** | `BootReceiver.kt`（76 行，含 `QUICKBOOT_POWERON`） | 每次开机 | 广播匹配即重注册两套闹钟 + 刷组件；`KEY_BOOT_AUTO_START` 决定是否拉起服务 | 低 |
| 17 | **任务划掉后闹钟重启** | `McForegroundService.onTaskRemoved` / `scheduleRestart` | 划掉任务后 2s | 服务被划掉即执行（`setAndAllowWhileIdle`，非精确） | 低 |
| 18 | **MCP 服务器自动启停** | `McpServerManager.kt:84-101`（`applyConfig`） | 配置变化时 | `configFlow` 变更（enabled / port / token / allowLan 任一不同即重挂） | 低。启停本地 HTTP 服务 |
| 19 | **状态变更 → 组件刷新** | `ServerRepository.kt:398-411`（`updateServerState`） | 每次状态写入 | **7 个字段** diff 命中：`isRunning` / `onlinePlayers` / `maxPlayers` / `tps` / `usedMemoryMb` / `cpuPercent` / `startupPhase` | ⚠️ 中。**被日志解析高频驱动**，靠 `WidgetUpdater` 的 300ms 防抖合并 |

---

## 五、P3 低频任务

| # | 任务 | 位置 | 时机 | 性能影响 |
|---|---|---|---|---|
| 20 | **启动检查更新** | `McApp.kt:165` → `McViewModel.kt:1179`（`AppUpdateService.latest`） | 冷启动一次 | 低。GitHub API 多端点并发 + APK 多源 fallback；自动检查失败不弹窗 |
| 21 | **每日使用统计上报** | `UsageTracker.kt`（68 行）<br>调用 `McApplication.kt:227` | 启动时检查，**每天最多 1 次** | 低。1 次 HTTPS POST，失败静默 |
| 22 | **崩溃日志启动上报** | `McApplication.kt:165-189` | 冷启动且 `crash_log.txt` 非空 | 低。一次性读入并改名 |
| 23 | **BDS 环境状态探测** | `McApplication.kt:194-200`（`bdsEnvironment.refresh()`） | 每次冷启动，后台协程 | 低。读就绪标记 + 目录存在性；`staleGeneration` 时只置状态位，不会自己下载安装 |
| 24 | **服务器目录扫描登记** | `McViewModel.kt:2142-2205` | VM 创建时 + 每次进入管理页 | 低。`listFiles` + 核心探测；`scanningServers` 防重入，空目录跳过 |
| 25 | **旧服务器迁移检查** | `McViewModel.checkLegacyMigration`（`2295`）/ `runLegacyMigration`（`2331`）<br>UI 触发 `McApp.kt:204-217` | 冷启动一次 + 用户点击 | ⚠️ 中高（仅迁移时）。逐文件复制 + 逐文件比对；临时目录保留，重试只补缺 |
| 26 | **进入页面自动加载** | `ui/screens` 下各 `LaunchedEffect` | 每次进入页面 | ⚠️ 中。多数只是 snackbar 收集（廉价）；有实际 IO / 网络的：`DiagnosticsScreen`（打开即全量自检）、`CrashReportsScreen`、`BackupScreen`、`FileManagerScreen`、`PluginsScreen`（拉 Modrinth 加载器与版本）、网卡枚举 4 处（Dashboard / Network / Ftp / Mcp） |
| 27 | **日志文件 FileObserver** | `CommandExecutor.kt:309-317`（`MODIFY or MOVED_TO`） | 文件写入事件 | 低。增量读取推送 |

---

## 六、关键发现与建议

### 🔴 发现 1：日志双份解析（仍存在，可优化 50%）

`McViewModel.kt:6389` 与 `McForegroundService.kt:124` **各订阅一遍 `consoleFlow`**，并各自调用 `ConsoleLineParser.parse()`（`McViewModel.parseConsoleLine`，`1861`）。共享解析器统一了实现与正则，但**没有消除重复执行**——App 在前台时同一条日志仍被解析两次。建议把解析结果做成共享的 `SharedFlow<ConsoleSignals>`，Service 解析一次、VM 只消费。

### ✅ 三个 200ms 循环都有空闲降频

主循环（`6400-6408`）、`legacyTermuxBuffer`（`6447-6451`）、`terminalOutputBuffers`（`6462-6468`）都会在「界面不可见」或「对应流无订阅」时切到 `IDLE_LOG_FLUSH_MS = 2000L`。`uiVisible` 由 `McApp` 通过 `LifecycleResumeEffect` 显式推送（`McApp.kt:157`、`161`），修掉了「`collectAsState` 订阅数恒 > 0，后台降频永不生效」的老问题。

**剩余可优化点**：主循环在界面可见时仍是字面量 `delay(200)`（`6408`），未复用 `LOG_FLUSH_MS`；三个循环各自独立定时，合并定时器可进一步减少唤醒。

### ✅ Watchdog 已从「每 tick 都探」改成按需探测

`shouldProbe`（`McForegroundService.kt:441`）在停止态下隔一个 tick 才读一次进程状态；`startupPending`（`446`）把「装依赖 / 修 rootfs / 下载核心 / 刚 spawn」这段进程本不存在的窗口排除在「判停止」之外，同时不释放唤醒锁 —— 这是修「启动中突然被判停止」的关键。

### ⚠️ 发现 2：两套定时体系并存

- 旧通道 `ScheduleManager`（3001/3002，每日开/停服）仍在 `McApplication.kt:214`、`BootReceiver.kt:40`、`McForegroundService.kt:285` 注册；
- 新通道 `AutomationScheduler`（3120，任意周期任务）在 `McApplication.kt:215`、`BootReceiver.kt:42`、`McForegroundService.kt:319` 注册。

`AutomationTaskType.StartServer` / `StopServer` 的注释写明「用于承接原每日定时开/停服」，即新 UI 已用周期任务表达同一件事。**两套若同时有值，会在同一时刻各触发一次**。建议：确认旧字段在新 UI 已不可写入后，把 `ScheduleManager` 降级为「只读兼容」，或在注册前判断「已有等价周期任务则跳过」。

### ✅ 组件刷新只在服务器运行中执行

`McForegroundService.kt:483-485` 是 `if (alive) WidgetUpdater.refresh(...)`：停止态下这些值不会变，盲刷只是白白重建 4 个 RemoteViews。停止/崩溃/状态切换由 `ServerRepository.updateServerState` 的字段 diff 钩子驱动（`398-411`），不会漏掉状态变化（`WidgetUpdater` 另有 300ms 防抖）。

### ✅ 崩溃重启已用指数退避，启动风暴风险基本消除

`restartBackoffMs()` = 3s → 6s → 12s… 上限 60s（`McServerController.kt:322`），另有两处护栏：稳定运行 5 分钟后清零计数、退避期间收到停止请求则取消重启。剩余风险：`maxRestartAttempts = 3`（`311`）用尽后停止重启，需要用户介入。

### ✅ 已做得好的地方

- 资源采集有三重缓存节流（目录 60s / 内存 30s / Java 60s），值无变化不发布快照，后台跳过目录遍历
- 自动备份有多服务器错峰偏移（0–10 分钟哈希偏移），失败也推进时间戳
- `WidgetUpdater` 有 300ms 防抖；配置落盘有 300ms debounce（`ServerRepository.kt:342-354`，`Channel.CONFLATED`）
- 配置落盘异常被收集协程吞掉且保留内存态，不会升级为进程崩溃；解析失败会归档原文件并拒绝落盘
- 无网络广播监听、无 JDK `Timer`、无 `ContentObserver`；系统定时器只有 `AlarmManager` 一类

---

## 七、纠正三处常见误判

| 误判 | 实际情况 |
|---|---|
| 「watchdog 每 30s **扫进程**」 | ❌ 错。`isMcRunning()` = `mcProcess?.isAlive ?: false`（`TermuxRuntime.kt:5153`），是内存查询，**开销极小** |
| 「资源采集每 15s **递归扫描目录**」 | ❌ 不准确。`walkTopDown()` 受 60s 缓存限制，界面不在前台时还会被 `includeDirectorySize = false` 整体跳过（`McViewModel.kt:1409-1411`）—— 并非每 15s 都扫 |
| 「BDS 环境探测会在启动时**自动安装**」 | ❌ 错。`bdsEnvironment.refresh()`（`McApplication.kt:200`）只读就绪标记与目录；初始化**只能**由用户在依赖管理页手动点。`startBds` 用 `isReady(prefix)` 做硬闸门，绝不临时装 |
