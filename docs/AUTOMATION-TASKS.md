# MineServe-Mobile 自动任务全量梳理

> 分析对象：MineServe-Mobile 主仓库（包名 `com.mineserve.mobile`）
> 代码规模：**119 个 Kotlin 文件 / 44,080 行**（统计方法：列出 `app/src/main/java` 下全部 `.kt` 文件，逐文件计数后求和；同目录下另有 2 个 `.java` 文件，未计入）
> 梳理范围：**所有非用户点击触发的自动逻辑**（不含 onClick / 按钮回调）
> 结论依据：全部经源码逐行核实（含行号），行号以当前工作树为准

---

## 一、总览：自动任务按风险分级

| 级别 | 数量 | 特征 | 典型代表 |
|---|---|---|---|
| **P0 极高** | 4 | 常驻协程循环；高频仅发生在「界面可见 / 有订阅」时 | 日志逐行解析、日志刷新循环 ×3、30s watchdog |
| **P1 高** | 6 | 定时轮询 / 每次启动执行的重 IO | 资源采集、启动自检修复链 |
| **P2 中** | 8 | WorkManager 周期、事件驱动 | 15 分钟保活、自动备份 |
| **P3 低** | 6 | 一次性 / 每日限频 | 更新检查、使用统计 |

---

## 二、P0 极高风险任务（常驻循环）

### 1. 控制台日志逐行解析

| 维度 | 内容 |
|---|---|
| **检测/修复内容** | 对 MC 输出的**每一行**调用共享解析器 `ConsoleLineParser.parse()`：启动阶段判定、Java 版本兼容校验、在线人数 / TPS 提取；命中玩家进出服的行再走玩家名提取（名单校正、事件记录、通知），结果写回 `ServerState` |
| **功能用途** | 实时驱动 UI 状态（在线玩家、TPS、启动进度），是状态数据的唯一来源 |
| **执行时机** | MC 进程运行期间，产生任意日志行时 |
| **触发条件** | `consoleFlow` 发射 — MC 每写一行 stdout，或日志文件被写入（`CommandExecutor.startLogWatcher` 的 FileObserver 增量推送） |
| **性能影响** | ⚠️ 高。解析器先做 `contains` 廉价预筛、命中才进正则（`ConsoleLineParser.kt:74-94`），玩家名提取正则只在含 `joined/left the game` 的行上跑（`McViewModel.kt:1227-1249`），因此**绝大多数行只做几次 contains**。**但 `McViewModel` 与 `McForegroundService` 各订阅一遍 `consoleFlow` 并各自解析一次，App 前台时同一行被解析两次**（见第六节发现 1） |

```kotlin
// McViewModel.kt:4568-4574
viewModelScope.launch(Dispatchers.Default) {
    repo.termuxRuntime.consoleFlow.collect { line ->
        consoleBuffer.add(line)
        pendingConsoleBuffer.add(line)
        parseConsoleLine(line)      // 内部调用共享解析器（1193-1275）
    }
}
```

---

### 2. 控制台批量刷新循环（主循环）

| 维度 | 内容 |
|---|---|
| **检测/修复内容** | 快照待处理缓冲 → 对每行跑 `TerminalLogProcessor.process()`（`TerminalLogTranslator`：110 条整行翻译 + 3 条正则 + 39 条短语替换，逐档命中即返回；再由 `TerminalLogProcessor.classify` 判色调）→ 发布 StateFlow |
| **功能用途** | 把原始日志转成中文可读终端输出，并做帧友好的批量刷新（避免逐行触发重组） |
| **执行时机** | ViewModel 创建即常驻（`init` 块，`McViewModel.kt:4542`） |
| **触发条件** | 界面在前台且控制台 / 预览 / 终端三条流任一有订阅时每 **200ms** 一轮；**空闲（界面不在前台，或三条流订阅数全为 0）时降为每 2s** |
| **性能影响** | ⚠️ 中高。200ms = 每秒 5 轮；`TerminalLogProcessor` 的字符串匹配是主要 CPU 成本。预览流另有 750ms 发布节流（`CONSOLE_PREVIEW_FLUSH_MS`） |

```kotlin
// McViewModel.kt:4576-4621
while (true) {
    if (!uiVisible ||
        (_consoleLines.subscriptionCount.value <= 0 &&
            _consolePreviewLines.subscriptionCount.value <= 0 &&
            _terminalConsoleLines.subscriptionCount.value <= 0)
    ) {
        delay(IDLE_LOG_FLUSH_MS)      // 2000L
        continue
    }
    delay(200)                        // ← 界面可见时 200ms（4588）
    val batch = pendingConsoleBuffer.snapshotAndClear()
    ...
}
```

---

### 3. 日志环形缓冲刷新循环（×2）

| 维度 | 内容 |
|---|---|
| **检测/修复内容** | 两个独立循环：`legacyTermuxBuffer` 与 `terminalOutputBuffers`，各自快照并重建不可变列表（`takeLast(MAX_LOG_LINES)`，上限 1500 行） |
| **功能用途** | 维护终端历史日志（上限 1500 行），供多会话终端显示 |
| **执行时机** | ViewModel 创建即常驻（`init` 块） |
| **触发条件** | 界面在前台且对应流有订阅时每 200ms；**空闲（界面不在前台 / 无订阅）时降为每 2s**（两处均已加空闲分支） |
| **性能影响** | ⚠️ 中。空闲时不再每 200ms 唤醒做 map/filter；日志仍在持续写入缓冲，只是不发布快照 |

```kotlin
// McViewModel.kt:4622-4638（legacyTermuxBuffer）
while (true) {
    if (!uiVisible || _termuxLines.subscriptionCount.value <= 0) {
        delay(IDLE_LOG_FLUSH_MS)          // 2000L
        continue
    }
    delay(LOG_FLUSH_MS)                   // 200L
    val batch = legacyTermuxBuffer.snapshotAndClear()
    if (batch.isNotEmpty()) {
        _termuxLines.value = (current + batch).takeLast(MAX_LOG_LINES).toImmutableList()
    }
}
// 4642-4647（terminalOutputBuffers）同理：!uiVisible || !terminalVisible → 2s
```

---

### 4. 前台服务健康 Watchdog

| 维度 | 内容 |
|---|---|
| **检测/修复内容** | ① `isMcRunning()` 检查 MC 进程存活并同步 `ServerState.isRunning`；② 每 2 tick 读配置 → `maybeAutoBackup()` → 发 `list` 命令（Paper 额外发 `tps`）；③ **仅当进程存活时**每 tick 刷新桌面组件 |
| **功能用途** | 保证服务状态与真实进程一致；采集玩家数/TPS；驱动自动备份；更新桌面组件 |
| **执行时机** | 前台服务进入前台时开启（`ensureForeground()` → `startWatchdog()`，`McForegroundService.kt:220`、`334`）；App 冷启动延迟 3s 自动拉起服务（`McApplication.kt:191-213`） |
| **触发条件** | 服务存活期间无条件循环；`watchdogStarted` CAS 保证只有一条循环（`McForegroundService.kt:336`） |
| **性能影响** | ⚠️ 低–中。每 30s 一轮（`delay(30_000L)`，`McForegroundService.kt:378`）。`isMcRunning()` 是内存查询（`Process.isAlive`）**并非扫进程**，开销极小；组件刷新已收敛为仅运行中执行（`McForegroundService.kt:375-377`） |

```kotlin
// McForegroundService.kt:337-380（节选）
while (true) {
    val alive = try { termux.isMcRunning() } catch (e: Exception) { false }
    app.repository.updateServerState { if (!alive) it.copy(isRunning = false, runningSinceMs = 0L) else it.copy(isRunning = true) }
    if (alive && tick % 2 == 0) {          // ← 每 60s
        maybeAutoBackup(config)
        termux.sendCommand("list")
        if (config.selectedCore == ServerCore.Paper) termux.sendCommand("tps")
    }
    tick++
    if (alive) WidgetUpdater.refresh(this@McForegroundService)   // ← 仅运行中刷新
    kotlinx.coroutines.delay(30_000L)
}
```

---

## 三、P1 高风险任务

### 5. 服务器资源采集轮询

| 维度 | 内容 |
|---|---|
| **检测/修复内容** | 采集磁盘可用空间（`StatFs`）、服务器目录总字节（`walkTopDown`）、MC 进程内存（读 `/proc`）、CPU 占用、Java 是否可用 |
| **功能用途** | 概览页资源卡片数据源；目录膨胀预警 |
| **执行时机** | ViewModel 创建后延迟 15s 首跑，之后常驻（`init` → `McViewModel.kt:4561` → `784-787`） |
| **触发条件** | 界面可见时每 15 秒一轮；**界面不在前台时降到 60 秒，并跳过最昂贵的目录遍历** |
| **性能影响** | ⚠️ 中。真正的重 IO（`walkTopDown()` 递归统计目录字节）被 60s 缓存限制，且后台直接不扫；`StatFs` 开销极小。并发调用被 `tryLock` 挡掉，**值无变化时不发布新快照** |

```kotlin
// McViewModel.kt:784-799
delay(15_000)
while (true) {
    val visible = uiVisible
    withContext(Dispatchers.IO) { collectServerResourcesOnce(includeDirectorySize = visible) }
    delay(if (visible) 15_000 else 60_000)
}

// 865-881：目录扫描 60s 节流 + 后台无缓存则返回 null（宁可显示未知也不扫盘）
val stale = now - cachedDirectoryBytesAtMs >= 60_000L
```

三重缓存：目录 60s（`865-881`）、内存 30s（`883-886`）、Java 校验 60s（`890-893`）；值未变化则不发布快照（`903-914`）。手动刷新走 `refreshServerStatus()`（`815-841`），会清缓存 + 强制发布。

---

### 6. 启动前运行时自检与自动修复链

| 维度 | 内容 |
|---|---|
| **检测/修复内容** | `launchMc` 拉起进程前调用 `autoRepairRuntime()`（`McServerController.kt:1721-1725`），其内部串行执行：修复 dpkg wrapper、usr/bin 归位、rootfs 可执行位、脚本解释器路径、apt 源、apt 配置、`ensureJvmComplete()` 校验 JDK 完整性、Java 符号链接；Forge/NeoForge 另补字体运行库（缺失则 apt 安装 + `fc-cache -f`）。`fixRootfsPermissions()`（命令可执行位 + 脚本路径）是一键安装依赖与 bootstrap 的入口修复（`McServerController.kt:346`、`TermuxRuntime.kt:2428`） |
| **功能用途** | 防止因文件权限/符号链接/JDK 不完整导致的启动失败，是 Termux 环境自愈的核心 |
| **执行时机** | 每次启动服务端前；安装器重试路径每次尝试前也调一次（`McServerController.kt:236`） |
| **触发条件** | FCL 来源（`JavaVendor.Fcl`）直接返回 0 不修；Termux 环境未就绪返回 0；其余无条件执行，各项内部幂等 |
| **性能影响** | ⚠️ 中高（但仅一次）。最重的是 `apt-get --reinstall`（`TermuxRuntime.kt:2314-2317`）与字体安装（`1902-1910`），耗时可达数十秒；正常情况下各项检查快速返回 |

```kotlin
// TermuxRuntime.kt:1894-1897（autoRepairRuntime 内）
var repaired = fixDpkgWrapper() + fixUsrBin() + ensureRootfsExecutable()
repaired += fixScriptsOnce() + fixAptSources() + ensureAptConfigs()
repaired += ensureJvmComplete(javaVersion)
fixJavaSymlinks(javaVersion)
```

> 注意 `ensureJvmComplete` 只修「**已安装但不完整**」的 JDK（缺 `libjli.so` 等）；注释明确「未安装的 JDK 不会被隐式安装」（`TermuxRuntime.kt:2298-2304`）。

---

### 7. Bootstrap 环境初始化

| 维度 | 内容 |
|---|---|
| **检测/修复内容** | 下载并解压 rootfs（`installer.ensureInstalled`）、`fixRootfsPermissions()`、apt 安装 `wget`/`curl`、`fixJavaSymlinks()`（`TermuxRuntime.bootstrap`，`TermuxRuntime.kt:2410-2439`） |
| **功能用途** | 首次运行或环境损坏时构建完整运行环境 |
| **执行时机** | App 冷启动，`isReady()` 为 false 时（`McApplication.kt:216-224`）；已就绪则整段跳过（`TermuxRuntime.kt:2412-2421`） |
| **触发条件** | `isReady()` 为 false；`bootstrapInitializationInFlight` 防重入（`McApplication.kt:230-234`） |
| **性能影响** | ⚠️ 高（仅首次/损坏时）。大文件下载 + 解压，有明显的磁盘 IO 与网络占用 |

> JDK 不在 bootstrap 内安装：`TermuxRuntime.kt:2429-2431` 只 apt 安装 `wget`/`curl`，JDK 由 Java 安装流程与 `ensureJvmComplete` 负责。

```kotlin
// McApplication.kt:216-225
scope().launch {
    registerBackgroundWork()
    if (termuxRuntime.isReady()) {
        ...
    } else {
        startBootstrap()
    }
}
```

---

### 8. 崩溃检测与自动重启

| 维度 | 内容 |
|---|---|
| **检测/修复内容** | MC 进程退出回调中读取 exit code →（判定为异常退出时）生成崩溃报告（含关键片段提取）→ 若开启自动重启且未达上限，**按指数退避**重新拉起 |
| **功能用途** | 服务意外崩溃后自动恢复可用性；留存崩溃证据 |
| **执行时机** | MC 进程退出瞬间（`onExit` 回调：`McServerController.kt:1562`、`1779`） |
| **触发条件** | `config.autoRestartOnCrash`（**默认 false**，`McConfig.kt:348`）且 `restartAttempts < maxRestartAttempts`（=3，`McServerController.kt:263`）且判定为异常退出：`!userStoppedRecently && (code != 0 \|\| failedDuringStartup)`（`1835`，启动窗口 20s，`1555/1768`）；用户主动停止后 5 分钟内退出按正常停止处理（`1833-1834`、`2134`） |
| **性能影响** | ⚠️ 低（事件驱动）。**启动风暴已被指数退避抑制**：3s → 6s → 12s…上限 60s（`274-279`、`2122`），稳定运行 5 分钟后清零计数（`282-288`、`2137`），退避期间收到停止请求则取消本次重启（`1910-1913`） |

```kotlin
// McServerController.kt:1890-1905（节选）
if (config.autoRestartOnCrash && abnormalExit) {
    if (restartAttempts < maxRestartAttempts) {
        restartAttempts++
        val backoffMs = restartBackoffMs(restartAttempts)   // 3s<<(n-1)，封顶 60s
        termux.emitLog("[restart] 服务端异常退出，${backoffMs / 1000}s 后自动重启（第 $restartAttempts/$maxRestartAttempts 次）")
        Thread { Thread.sleep(backoffMs); ... launchMc(config, dirName, jarPath) }.start()
    } else { ... 停止自动重启 ... }
}
```

---

### 9. 启动时配置文件自动改写

| 维度 | 内容 |
|---|---|
| **检测/修复内容** | PowerNukkitX：每次启动重写 `server.properties`（端口同步）；Allay：**仅在确实绑不上 IPv6 时**把 `enablev6` 改成 false（能绑则首次生成 true、已有配置不再改写），端口每次都同步；核心目录名非 ASCII 时 `renameTo` 迁移为 ASCII 名并回写配置 |
| **功能用途** | 规避 Android 平台缺陷（如 IPv6 绑定崩溃）与文件系统编码问题（Forge 的 ZipFileSystem 无法解析非 ASCII 工作路径） |
| **执行时机** | 每次启动（目录迁移在 `start()` 阶段，`McServerController.kt:1416`；配置改写与 Java 选择同在 `launchMc`） |
| **触发条件** | 按核心类型条件执行：PNX / Allay 分支见 `McServerController.kt:1583-1626`；目录迁移仅在 `sanitizeDirName` 结果与现名不同时发生（`1462-1487`） |
| **性能影响** | 低。小文件读写 |

---

### 10. Java 版本自动切换

| 维度 | 内容 |
|---|---|
| **检测/修复内容** | 按 MC 版本 + 核心类型推荐所需 JDK（`recommendedJavaForMinecraft`，`McServerController.kt:1498-1509`），当前选择不足时升级并按来源可用性修正 vendor，原子回写配置；Allay 另有 Java 21 兜底 |
| **功能用途** | 避免"Java 版本过低"导致的启动失败 |
| **执行时机** | 每次启动前（`launchMc` 内） |
| **触发条件** | `config.selectedJavaVersion.ordinal < recommended.ordinal`（如 MC 26.1+ 要求 Java 25）；Allay 低于 Java 21 时切换（未安装则直接报错） |
| **性能影响** | 低（判断），落盘走异步 debounce 队列（`ServerRepository.kt:313`） |

```kotlin
// McServerController.kt:1666-1676
val launchJava = if (recommended != null && config.selectedJavaVersion.ordinal < recommended.ordinal) {
    termux.emitLog("[startMc] ... 已自动切换")
    launchVendor = vendorFor(config.selectedJavaVendor, recommended)
    runCatching { repo.updateAndSaveConfig { it.copy(selectedJavaVersion = recommended, selectedJavaVendor = launchVendor) } }
    recommended
} else config.selectedJavaVersion
```

---

## 四、P2 中风险任务

| # | 任务 | 位置 | 时机 | 条件 | 性能影响 |
|---|---|---|---|---|---|
| 11 | **保活 Worker** | `KeepAliveWorker.kt:14-30`<br>注册 `McApplication.kt:203-211` | 每 **15 分钟** | `KEY_KEEP_ALIVE` 为真（默认 true）且 `McForegroundService.isRunning == false` 才动作（`KeepAliveWorker.kt:18`） | 低。仅拉起前台服务 |
| 12 | **组件兜底刷新 Worker** | `WidgetRefreshWorker.kt:17-35`<br>注册 `McApplication.kt:298-310` | 每 **15 分钟**，App 进程死后仍调度 | 扫 `/proc` 判断孤儿进程（`WidgetRefreshWorker.kt:38-46`） | 低，但**进程已死时唤醒** |
| 13 | **自动备份** | `McForegroundService.kt:386-439`（由 watchdog 于 `358` 每 60s **检查**） | 按配置间隔**执行** | `autoBackupIntervalMin > 0`<br>+ 外部存储权限（`394`）<br>+ 距上次超间隔（`400`） | ⚠️ 中高（执行时）。打包大世界的磁盘 IO 峰值明显；已有 0–10 分钟错峰偏移（`390-393`），失败也推进时间戳以避免每 60s 重跑（`423-425`、`433`） |
| 14 | **每日定时开/停服** | `ScheduleManager.kt:23-35`（`scheduleDaily` 47-61）<br>`ScheduleAlarmReceiver.kt:23-31` | 每日定点（`setExactAndAllowWhileIdle`，`54`；无权限时降级 `setWindow`，`57`） | 用户已开启 + 已注册闹钟 | 低。Receiver 内同步重注册次日（`27-29`），动作转交前台服务执行（`30`） |
| 15 | **开机广播自启** | `BootReceiver.kt:21-60` | 每次开机（含 `QUICKBOOT_POWERON`，`27-29`） | 广播匹配即重注册闹钟 + 刷组件；`KEY_BOOT_AUTO_START` 决定是否拉起服务（`46-59`） | 低 |
| 16 | **任务划掉后闹钟重启** | `McForegroundService.kt:497-508`（`onTaskRemoved`）<br>`510-525`（`scheduleRestart`） | 划掉任务后 2s（`522`） | 服务被划掉即执行（`setAndAllowWhileIdle`，非精确） | 低 |
| 17 | **MCP 服务器自动启停** | `McpServerManager.kt:84-88` → `101`（`applyConfig`） | 配置变化时 | `configFlow` 变更（enabled / port / token / allowLan 任一不同即重挂） | 低。启停本地 HTTP 服务 |
| 18 | **状态变更 → 组件刷新** | `ServerRepository.kt:357-372` | 每次 `updateServerState` | **7 个字段** diff 命中：`isRunning` / `onlinePlayers` / `maxPlayers` / `tps` / `usedMemoryMb` / `cpuPercent` / `startupPhase` | ⚠️ 中。**被日志解析高频驱动**，靠 `WidgetUpdater` 的 300ms 防抖合并（`WidgetUpdater.kt:34`、`42-55`） |

---

## 五、P3 低频任务

| # | 任务 | 位置 | 时机 | 性能影响 |
|---|---|---|---|---|
| 19 | **启动检查更新** | `McApp.kt:94-98` → `McViewModel.kt:568-601` | 冷启动延迟 1s，一次 | 低。GitHub API 3 源 + APK 下载 6 源 fallback（`AppUpdateService.kt:113-126`）；自动检查失败不弹窗（`McViewModel.kt:592-599`） |
| 20 | **每日使用统计上报** | `UsageTracker.kt:24-51`（`McApplication.kt:178` 调用） | 启动时检查，**每天最多 1 次** | 低。1 次 HTTPS POST，失败静默 |
| 21 | **崩溃日志启动上报** | `McApplication.kt:162-170` | 冷启动且 `crash_log.txt` 非空 | 低。一次性读入并改名（`167`） |
| 22 | **服务器目录扫描登记** | `McViewModel.kt:1426-1484`（`init` 内 `4544` / 进入管理页 `ServerManagementScreen.kt:55`） | VM 创建时 + 每次进入管理页 | 低。`listFiles` + 核心探测；`scanningServers` 防重入（`1427-1428`），空目录跳过（`1441-1456`） |
| 23 | **进入页面自动加载** | `ui/screens` 下 27 个文件中的 `LaunchedEffect` | 每次进入页面 | ⚠️ 中。多数 `LaunchedEffect(Unit)` 只是 snackbar 收集（廉价）；有实际 IO / 网络的：`DiagnosticsScreen.kt:58`（**打开即全量自检**）、`CrashReportsScreen.kt:40`（列崩溃报告目录）、`BackupScreen.kt:100`（外部备份目录）+ `139`（快照目录）、`FileManagerScreen.kt:139`（根目录）、`PluginsScreen.kt:248-259`（进入模组/插件分类时拉 Modrinth 加载器与版本）、网卡枚举 4 处（`DashboardScreen.kt:798`、`NetworkScreen.kt:113`、`FtpScreen.kt:90`、`McpScreen.kt:80`） |
| 24 | **日志文件 FileObserver** | `CommandExecutor.kt:276-311`（FileObserver 创建于 `284`） | 文件写入事件（`MODIFY` / `MOVED_TO`） | 低。增量读取推送 |

---

## 六、关键发现与建议

### 🔴 发现 1：日志双份解析（仍存在，可优化 50%）

`McViewModel.kt:4569` 与 `McForegroundService.kt:98` **各订阅一遍 `consoleFlow`**，并各自调用 `ConsoleLineParser.parse()`（`McViewModel.kt:1196`、`McForegroundService.kt:101`）。共享解析器（`ConsoleLineParser.kt:38-49`）统一了实现与正则，但**没有消除重复执行**——App 在前台时同一条日志仍被解析两次。建议统一到一处（如 Service 解析后由 VM 订阅结果），或把解析结果做成共享的 `SharedFlow<ConsoleSignals>`。

### ✅ 两个 200ms 环形缓冲循环已有空闲降频

`McViewModel.kt:4622-4638`（`legacyTermuxBuffer`）与 `4639-4660`（`terminalOutputBuffers`）**都带空闲分支**：

```kotlin
if (!uiVisible || _termuxLines.subscriptionCount.value <= 0) { delay(IDLE_LOG_FLUSH_MS); continue }   // 4627-4630
...
if (!uiVisible || !terminalVisible) { delay(IDLE_LOG_FLUSH_MS); continue }                            // 4644-4647
```

`IDLE_LOG_FLUSH_MS = 2000L`（`McViewModel.kt:1169`）。同时 `uiVisible` 由 `McApp` 用 `LifecycleResumeEffect` 显式推送（`McApp.kt:88-91`、`McViewModel.kt:177-182`），修掉了「`collectAsState` 订阅数恒 > 0，后台降频永不生效」的老问题（注释见 `McViewModel.kt:169-176`）。

**剩余可优化点**：主循环在界面可见时仍是固定的 `delay(200)`（`4588`，未复用 `LOG_FLUSH_MS` 常量）；三个循环各自独立定时，合并定时器可进一步减少唤醒。

### ✅ 组件刷新只在服务器运行中执行

`McForegroundService.kt:375-377` 是 `if (alive) WidgetUpdater.refresh(...)`，注释明确「停止状态下这些值不会变，盲刷只是白白重建 4 个 RemoteViews」，不会漏掉状态变化（停止/崩溃由 `ServerRepository` 的字段 diff 钩子驱动）。真实成本仍是 `pushNow` 里 4 个组件的 RemoteViews 重建 + 2 次 `notifyAppWidgetViewDataChanged`（`WidgetUpdater.kt:65-72`），已有 300ms 防抖（`34`）。

### ✅ 崩溃重启已用指数退避，启动风暴风险基本消除

`createExitHandler` 不再固定 3s 重试：`restartBackoffMs()` 实现 3s → 6s → 12s… 上限 60s（`McServerController.kt:274-279`、`2122`），另有两处护栏——稳定运行 5 分钟后清零计数（`282-288`）、退避期间收到停止请求则取消重启（`1910-1913`）。剩余风险：`maxRestartAttempts = 3`（`263`）用尽后停止重启，需要用户介入。

### ✅ 已做得好的地方

- 资源采集有三重缓存节流（60s/30s/60s）、值无变化不发布快照，后台还会跳过最贵的目录遍历（`McViewModel.kt:794-796`、`865-914`）
- 自动备份有多服务器错峰偏移（0–10 分钟哈希偏移，`McForegroundService.kt:390-393`）
- `WidgetUpdater` 有 300ms 防抖（`WidgetUpdater.kt:34`）
- 配置落盘有 300ms debounce（`ServerRepository.kt:305`、`313`）；ViewModel 侧旧的「合并窗口」已移除，改由仓库层原子读改写根治竞态（`McViewModel.kt:1177`）
- 无网络广播监听、无 JDK `Timer`、无 `ContentObserver`，机制相对克制（全仓库仅 `AlarmManager` 一类系统定时器）

---

## 七、纠正两处常见误判

| 误判 | 实际情况 |
|---|---|
| "watchdog 每 30s **扫进程**" | ❌ 错。`isMcRunning()` = `mcProcess?.isAlive ?: false`（`TermuxRuntime.kt:3535-3537`），是内存查询，**开销极小** |
| "资源采集每 15s **递归扫描目录**" | ❌ 不准确。`walkTopDown()` 受 60s 缓存限制（`McViewModel.kt:865-881`），界面不在前台时还会被 `includeDirectorySize = false` 整体跳过（`794-796`）——并非每 15s 都扫 |
