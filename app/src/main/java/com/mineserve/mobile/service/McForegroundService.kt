package com.mineserve.mobile.service

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.net.wifi.WifiManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mineserve.mobile.MainActivity
import com.mineserve.mobile.McApplication
import com.mineserve.mobile.R
import com.mineserve.mobile.data.EventLogStore
import com.mineserve.mobile.data.InstallStep
import com.mineserve.mobile.data.StartupPhase
import com.mineserve.mobile.data.StepStatus
import com.mineserve.mobile.data.WidgetEventType
import com.mineserve.mobile.runtime.ConsoleLineParser
import com.mineserve.mobile.runtime.TermuxRuntime
import com.mineserve.mobile.server.BackupManager
import com.mineserve.mobile.server.ExternalBackupStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 前台服务：MC 进程托管 + 日志 socket 服务端 + 保活
 *
 * 关键点：
 * 1. onStartCommand 返回 START_STICKY → 系统在资源充足时会自动重启 Service
 * 2. foregroundServiceType=specialUse (Android 13+) 已在 manifest 声明
 * 3. WakeLock + WifiLock 防止 CPU/网络休眠
 * 4. onTaskRemoved：用户划掉任务时尝试通过非精确闹钟重启（Android 12+ 不允许精确闹钟）
 * 5. onCreate 中检测 tmux session 是否存在 → 判断是否有"上次存活"的 MC 进程
 */
class McForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var termux: TermuxRuntime
    private lateinit var backupManager: BackupManager

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    // ponytail: one active server process, so one service-wide backup lock is enough.
    @Volatile private var autoBackupRunning = false
    @Volatile private var cachedDirName: String? = null
    /** 是否已调用过 startForeground（幂等护栏，见 [ensureForeground]） */
    @Volatile private var foregroundStarted = false
    private val watchdogStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    private val chatRegex = Regex("<([A-Za-z0-9_]{1,16})>\\s*(.+)$")
    private val joinRegex = Regex(":\\s*([A-Za-z0-9_]{1,16})(?:\\[[^\\]]*\\])?\\s+(?:has\\s+)?joined the game")
    private val leaveRegex = Regex(":\\s*([A-Za-z0-9_]{1,16})(?:\\[[^\\]]*\\])?\\s+(?:has\\s+)?left the game")
    private val pnxJoinRegex = Regex("(?:^|[: ]\\s*)([A-Za-z0-9_]{1,16})(?:\\[[^]]*])?\\s+logged in with entity id", RegexOption.IGNORE_CASE)
    private val pnxLeaveRegex = Regex("(?:^|[: ]\\s*)([A-Za-z0-9_]{1,16})(?:\\[[^]]*])?\\s+disconnected(?:[: ]|$)", RegexOption.IGNORE_CASE)

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        termux = McApplication.get(this).termuxRuntime
        backupManager = BackupManager(termux)
        scope.launch {
            McApplication.get(this@McForegroundService).repository.configFlow
                .map { it.keepStatusOverlay }
                .distinctUntilChanged()
                .collect { enabled -> syncOverlay(enabled) }
        }
        scope.launch {
            val app = McApplication.get(this@McForegroundService)
            combine(app.repository.configFlow, app.repository.serverState) { config, state -> config.keepStatusOverlay to state }
                .collect { (enabled, state) -> if (enabled) StatusOverlay.update(state.cpuPercent, state.usedMemoryMb) }
        }
        // 移到 IO 线程，避免主线程阻塞导致 ANR
        scope.launch { detectSurvivingProcess() }
        // 解析 list/tps 命令回复并写入 serverState：
        // 该解析原先只在 UI 层（McViewModel）进行，App 退后台后桌面组件数据会停更。
        startConsoleParsing()
    }

    private fun startConsoleParsing() {
        scope.launch {
            McApplication.get(this@McForegroundService).repository.configFlow.collect { config ->
                cachedDirName = config.installedCores.find { it.name == config.activeCoreName }?.dirName
            }
        }
        scope.launch {
            termux.consoleFlow.collect { line ->
                try {
                    // 状态类解析统一走共享解析器，与 ViewModel 用同一份逻辑、同一份正则。
                    val signals = ConsoleLineParser.parse(line)
                    val repo = McApplication.get(this@McForegroundService).repository
                    signals.players?.let { p ->
                        repo.updateServerState { it.copy(onlinePlayers = p.online, maxPlayers = p.max) }
                    }
                    signals.tps?.let { value ->
                        repo.updateServerState {
                            it.copy(tps = value, healthPercent = ((value / 20.0) * 100).toInt().coerceIn(0, 100))
                        }
                    }
                    // 启动完成检测下沉：App 退后台后无人设置 runningSinceMs，
                    // 桌面组件会一直停留在"启动中"。
                    if (signals.isReady) {
                        repo.updateServerState { st ->
                            if (!st.isRunning || st.startupPhase == StartupPhase.Ready) st
                            else st.copy(
                                startupPhase = StartupPhase.Ready,
                                runningSinceMs = st.runningSinceMs.takeIf { it > 0L }
                                    ?: android.os.SystemClock.elapsedRealtime()
                            )
                        }
                    }
                    recordWidgetEvents(line)
                } catch (_: Exception) {
                }
            }
        }
    }

    /** 玩家进离服与聊天公告写入事件日志组件的数据源。 */
    private fun recordWidgetEvents(line: String) {
        val dir = cachedDirName ?: return
        val context = this@McForegroundService
        chatRegex.find(line)?.let {
            EventLogStore.add(context, dir, WidgetEventType.CHAT, it.groupValues[1], it.groupValues[2])
        }
        joinRegex.find(line)?.let {
            EventLogStore.add(context, dir, WidgetEventType.JOIN, it.groupValues[1])
        }
        leaveRegex.find(line)?.let {
            EventLogStore.add(context, dir, WidgetEventType.LEAVE, it.groupValues[1])
        }
        pnxJoinRegex.find(line)?.let {
            EventLogStore.add(context, dir, WidgetEventType.JOIN, it.groupValues[1])
        }
        pnxLeaveRegex.find(line)?.let {
            EventLogStore.add(context, dir, WidgetEventType.LEAVE, it.groupValues[1])
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> ensureForeground()
            ACTION_REFRESH_KEEP_ALIVE -> {
                // 这两个 action（尤其 REFRESH_STATUS）也是经 startForegroundService 进来的：
                // 任何一条进来后 5 秒内没调 startForeground，系统会抛 RemoteServiceException
                // 并杀掉 App 进程 —— 连带杀掉正在跑的 MC。所以必须先抬到前台再做正事。
                ensureForeground()
                scope.launch { syncOverlayFromConfig() }
            }
            ACTION_REFRESH_STATUS -> {
                ensureForeground()
                scope.launch { refreshServerStatusNow() }
            }
            ACTION_WIDGET_ACTION -> {
                // 桌面组件/定时闹钟的长任务统一在这里执行。
                //
                // 为什么不能让 BroadcastReceiver 自己 goAsync() 跑完：启动服务端（含下载、
                // apt 安装）、打包世界备份都是分钟级操作，而 BroadcastReceiver 的
                // PendingResult 有硬性时限（前台 10s / 后台 60s），超时系统会判定 ANR
                // 并杀掉 App 进程——正在运行的 MC 会跟着一起死。
                // 交给前台服务后既没有时限，也顺带拿到了前台保活。
                ensureForeground()
                val action = intent.getStringExtra(EXTRA_WIDGET_ACTION)
                val scheduleAction = intent.getStringExtra(EXTRA_SCHEDULE_ACTION)
                scope.launch {
                    runWidgetOrScheduleAction(action, scheduleAction)
                }
            }
            ACTION_STOP -> {
                scope.launch {
                    termux.stopMc()
                    stopForegroundInternal()
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            else -> {
                // null intent（系统重启）或其他 action：仍需启动前台通知
                ensureForeground()
            }
        }
        // START_STICKY：进程被杀后系统会在资源充足时尝试重启 Service
        return START_STICKY
    }

    /**
     * 抬到前台（幂等）。
     *
     * 为什么不直接延用旧的单次调用：`ACTION_REFRESH_STATUS` 这类 action 同样走
     * `startForegroundService`，而它过去从不调 `startForeground`——targetSdk ≥ 26 时
     * 系统判定「5 秒内未进前台」，在 App 进程里抛 RemoteServiceException 把它整个杀掉，
     * 正在跑的 MC 随之一起没。做成幂等后任意 action 先进来都会补齐这一步。
     */
    private fun ensureForeground() {
        if (foregroundStarted) return
        val notif = buildNotification(getString(R.string.notif_title_running), getString(R.string.notif_text_running))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ 必须显式传入 FGS 类型
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        foregroundStarted = true
        // 配置读取（DataStore）移到 IO 线程，避免主线程磁盘 I/O
        scope.launch { acquireLocks() }
        // 启动 socket 监听 + 健康 watchdog
        startWatchdog()
        // 授权页返回、厂商系统延迟刷新 AppOps 时，前一次挂载可能发生得太早。
        scope.launch {
            kotlinx.coroutines.delay(500L)
            syncOverlayFromConfig()
        }
    }

    private fun stopForegroundInternal() {
        StatusOverlay.hide(this)
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    /**
     * 在服务的作用域内执行组件动作 / 定时任务。
     *
     * 这些动作都是分钟级的（启动服务端含下载与 apt 安装、打包世界备份、
     * 定时开停服），绝不能留在 BroadcastReceiver 的 goAsync 里跑——见
     * [ACTION_WIDGET_ACTION] 的说明。这里失败一律只记日志 + 发事件通知，
     * 不让异常冒出去杀掉服务进程。
     */
    private suspend fun runWidgetOrScheduleAction(widgetAction: String?, scheduleAction: String?) {
        val app = McApplication.get(this)
        try {
            if (widgetAction != null) {
                com.mineserve.mobile.widget.WidgetActionReceiver.dispatchWidgetAction(this, widgetAction)
            } else if (scheduleAction != null) {
                com.mineserve.mobile.widget.WidgetActionReceiver.dispatchScheduleAction(this, scheduleAction)
                // 触发后按最新配置重注册下一次（配置可能已被修改）。
                // 放在动作之后：与用户操作路径一致，且此时读到的就是最新配置。
                runCatching {
                    com.mineserve.mobile.data.ScheduleManager.register(
                        this, app.repository.configFlow.first()
                    )
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "组件动作执行失败: $widgetAction$scheduleAction", e)
            runCatching {
                com.mineserve.mobile.data.ServerEventNotifier.notify(
                    app,
                    app.getString(R.string.notif_schedule_start_fail_title),
                    app.getString(R.string.notif_schedule_start_fail_text, e.message ?: ""),
                    com.mineserve.mobile.data.ServerEventNotifier.ID_SCHEDULE_FAIL, 1
                )
            }
        }
    }

    /**
     * 启动后检测：APP 重启后是否有"上次存活"的 MC 进程
     */
    private fun detectSurvivingProcess() {
        val running = try { termux.isMcRunning() } catch (e: Exception) { false }
        if (running) {
            Log.i(TAG, "Detected surviving MC process, will reattach to log stream")
            McApplication.get(this).repository.updateServerState {
                // A user-triggered start already owns the startup state. Do not
                // turn it into "running" before the console reports readiness.
                if (it.isRunning && it.runningSinceMs == 0L) return@updateServerState it
                // The original launch log is not replayed after an app restart.
                // Restore the timestamp so the dashboard cannot remain in the
                // perpetual "starting" state after reattaching.
                it.copy(
                    isRunning = true,
                    runningSinceMs = it.runningSinceMs.takeIf { since -> since > 0L }
                        ?: android.os.SystemClock.elapsedRealtime()
                )
            }
        } else {
            // 服务被系统重启后新进程的 ServerState 是初始“已停止”，状态无变化
            // 不会触发 diff 钩子；而组件上仍是陈旧的“运行中”，这里必须主动补刷。
            com.mineserve.mobile.data.WidgetUpdater.refresh(this)
        }
    }

    /** 立即同步一次服务端状态：更新存活状态，并向运行中的服务端发起 list/tps 查询。 */
    private suspend fun refreshServerStatusNow() {
        val alive = try { termux.isMcRunning() } catch (e: Exception) { false }
        val app = McApplication.get(this@McForegroundService)
        app.repository.updateServerState {
            if (!alive) {
                it.copy(isRunning = false, runningSinceMs = 0L, tps = 0.0, cpuPercent = null, usedMemoryMb = 0L)
            } else {
                it.copy(isRunning = true)
            }
        }
        if (alive) {
            try {
                val config = runCatching { app.repository.configFlow.first() }.getOrNull()
                if (config != null) {
                    termux.sendCommand("list")
                    if (config.installedCores.any {
                            it.name == config.activeCoreName && it.core == com.mineserve.mobile.data.ServerCore.Paper
                        }
                    ) {
                        termux.sendCommand("tps")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "refreshServerStatusNow query failed: ${e.message}")
            }
        }
        // 状态可能没变化（diff 钩子不触发），这里主动补刷组件。
        com.mineserve.mobile.data.WidgetUpdater.refresh(this@McForegroundService)
    }

    /**
     * 健康 watchdog：每 30 秒检查 MC 进程是否存活，更新 StateFlow。
     * 每 60 秒发送 list 命令获取玩家数，Paper 核心发送 tps 命令获取 TPS。
     */
    private fun startWatchdog() {
        // 只跑一条 watchdog：每条重复启动都会多一个「每 30 秒 list/tps」的轮询源。
        if (!watchdogStarted.compareAndSet(false, true)) return
        scope.launch {
            var tick = 0
            while (true) {
                val alive = try { termux.isMcRunning() } catch (e: Exception) { false }
                val app = McApplication.get(this@McForegroundService)
                app.repository.updateServerState {
                    if (!alive) {
                        it.copy(isRunning = false, runningSinceMs = 0L)
                    } else {
                        // A live Java process is not proof that the server has finished loading.
                        // The console's ready signal sets runningSinceMs once the core is usable.
                        it.copy(isRunning = true)
                    }
                }
                // 服务器运行时，每 60 秒（2 个 tick）发送一次查询命令获取真实数据
                if (alive && tick % 2 == 0) {
                    try {
                        val config = runCatching {
                            app.repository.configFlow.first()
                        }.getOrNull()
                        if (config != null) {
                            maybeAutoBackup(config)
                            // list 命令获取在线玩家数（所有核心支持）
                            termux.sendCommand("list")
                            // Paper 核心额外发送 tps 命令
                            if (config.selectedCore == com.mineserve.mobile.data.ServerCore.Paper) {
                                termux.sendCommand("tps")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "watchdog query failed: ${e.message}")
                    }
                }
                tick++
                // 仅在服务器运行中周期刷新组件：组件展示的是 TPS/内存/在线人数等运行时数据，
                // 停止状态下这些值不会变，盲刷只是白白重建 4 个 RemoteViews。
                // 停止、崩溃、状态切换等事件已由 ServerRepository.updateServerState 的
                // 字段 diff 钩子驱动刷新，这里不刷新也不会漏掉状态变化。
                if (alive) {
                    com.mineserve.mobile.data.WidgetUpdater.refresh(this@McForegroundService)
                }
                kotlinx.coroutines.delay(30_000L)
            }
        }
    }

    /**
     * 唤醒锁 + Wi-Fi 锁，确保 MC 进程在屏幕熄灭时仍可接收玩家连接
     */
    private fun maybeAutoBackup(config: com.mineserve.mobile.data.McConfig) {
        val active = config.installedCores.find { it.name == config.activeCoreName } ?: return
        val baseIntervalMs = config.autoBackupIntervalMin * 60_000L
        if (baseIntervalMs <= 0 || autoBackupRunning) return
        // 多服务器自动备份错峰：每个服务器在基础间隔上叠加 0–10 分钟的唯一偏移，
        // 避免多个服务器在同一时刻同时打包造成 IO 高峰
        val staggerOffset = (active.dirName.hashCode() and 0x7fffffff) % 600_000L
        val intervalMs = baseIntervalMs + staggerOffset
        if (!ExternalBackupStore.hasPermission(this)) {
            termux.emitLog("[backup] 自动备份跳过：未授予外部存储访问权限")
            return
        }
        val prefs = getSharedPreferences("auto_backup", MODE_PRIVATE)
        val key = "last_${active.dirName}_${config.autoBackupType.name}"
        if (System.currentTimeMillis() - prefs.getLong(key, 0L) < intervalMs) return
        autoBackupRunning = true
        // 连续失败计数：失败日志降频（仅首次失败打控制台），避免外部目录不可写时刷屏
        val failKey = "fail_${active.dirName}_${config.autoBackupType.name}"
        scope.launch {
            try {
                val typeName = config.autoBackupType.displayName
                termux.emitLog("[backup] 正在自动$typeName: ${active.name}")
                termux.sendCommand(active.core.consoleSaveCommand)
                kotlinx.coroutines.delay(1_000L)
                val path = when (config.autoBackupType) {
                    com.mineserve.mobile.data.AutoBackupType.World -> backupManager.backupWorldToExternal(
                        active.dirName,
                        BackupManager.BackupOrigin.Automatic,
                        config.maxSnapshots
                    )
                    com.mineserve.mobile.data.AutoBackupType.Server -> backupManager.backupServerToExternal(
                        active.dirName,
                        active.core.displayName,
                        BackupManager.BackupOrigin.Automatic,
                        config.maxSnapshots
                    )
                }
                // 无论成败都推进节流时间戳：原先只在成功时写入，失败路径每个 watchdog
                // tick（约 60s）都会完整重跑一次打包，造成周期性 IO 峰值。
                prefs.edit().putLong(key, System.currentTimeMillis()).apply()
                if (path != null) {
                    prefs.edit().putInt(failKey, 0).apply()
                    termux.emitLog("[backup] 自动备份完成: ${java.io.File(path).name}")
                } else {
                    reportAutoBackupFailure(prefs, failKey, "外部目录不可写或没有可备份的数据")
                }
            } catch (e: Exception) {
                prefs.edit().putLong(key, System.currentTimeMillis()).apply()
                reportAutoBackupFailure(prefs, failKey, e.message ?: "未知错误")
            } finally {
                autoBackupRunning = false
            }
        }
    }

    /** 记录一次自动备份失败；仅首次失败打控制台，后续降频到 logcat，避免日志刷屏。 */
    private fun reportAutoBackupFailure(prefs: android.content.SharedPreferences, failKey: String, reason: String) {
        val fails = prefs.getInt(failKey, 0) + 1
        prefs.edit().putInt(failKey, fails).apply()
        val message = "[backup] 自动备份失败: $reason"
        if (fails == 1) {
            termux.emitLog(message)
        } else {
            Log.w(TAG, "$message（连续第 $fails 次，已降频）")
        }
    }

    private suspend fun acquireLocks() {
        val app = McApplication.get(this)
        val config = runCatching {
            app.repository.configFlow.first()
        }.getOrNull()
        val keepCpu = config?.keepCpuWakelock ?: true
        val keepWifi = config?.keepWifiLock ?: true
        syncOverlay(config?.keepStatusOverlay == true)

        if (keepCpu && wakeLock?.isHeld != true) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "MineServeMobile::McWake"
            ).apply { acquire() }
        }
        if (keepWifi && wifiLock?.isHeld != true) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(
                mode,
                "MineServeMobile::McWifi"
            ).apply { acquire() }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
    }

    private suspend fun syncOverlayFromConfig() {
        val enabled = McApplication.get(this).repository.configFlow.first().keepStatusOverlay
        syncOverlay(enabled)
    }

    private fun syncOverlay(enabled: Boolean) {
        if (enabled) StatusOverlay.show(this) else StatusOverlay.hide(this)
    }

    /**
     * 用户从最近任务划掉 APP 时触发
     * Android 12+ 不允许在后台启动 FGS，使用非精确闹钟延迟重启
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 划掉任务时进程即将终止：按 MC 实际存活状态落一次组件终态，
        // 避免组件在进程死后长期显示“运行中”。
        val alive = runCatching { termux.isMcRunning() }.getOrDefault(false)
        McApplication.get(this).repository.updateServerState { st ->
            if (alive) st else st.copy(isRunning = false, runningSinceMs = 0L)
        }
        com.mineserve.mobile.data.WidgetUpdater.refresh(this)
        Log.i(TAG, "onTaskRemoved: mcAlive=$alive, scheduling restart via inexact alarm")
        scheduleRestart()
    }

    private fun scheduleRestart() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, McForegroundService::class.java).apply {
            action = ACTION_START
        }
        val pi = PendingIntent.getService(
            this, 0, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        // Android 12+：使用 setAndAllowWhileIdle（非精确，规避 SCHEDULE_EXACT_ALARM 限制）
        am.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 2000L,
            pi
        )
    }

    private fun buildNotification(title: String, content: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, getString(R.string.notif_channel_id))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        scope.cancel()
        releaseLocks()
        StatusOverlay.hide(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIF_ID = 1001
        const val ACTION_START = "com.mineserve.mobile.action.START"
        const val ACTION_STOP = "com.mineserve.mobile.action.STOP"
        const val ACTION_REFRESH_KEEP_ALIVE = "com.mineserve.mobile.action.REFRESH_KEEP_ALIVE"
        const val ACTION_REFRESH_STATUS = "com.mineserve.mobile.action.REFRESH_STATUS"
        /** 执行一个桌面组件动作（组件/闹钟 Receiver 把长任务转交到这里）。 */
        const val ACTION_WIDGET_ACTION = "com.mineserve.mobile.action.WIDGET_ACTION"
        /** 要执行的组件动作名（[com.mineserve.mobile.widget.WidgetActionReceiver] 的 ACTION_*）。 */
        const val EXTRA_WIDGET_ACTION = "widget_action"
        /** 定时任务动作名（[com.mineserve.mobile.data.ScheduleManager] 的 ACTION_*）。 */
        const val EXTRA_SCHEDULE_ACTION = "schedule_action"
        private const val TAG = "McForegroundService"

        /** 服务是否在运行（供保活检查） */
        @Volatile
        var isRunning: Boolean = false

        /**
         * 把一个桌面组件动作交给前台服务执行。
         *
         * 组件 Receiver 的 onReceive 必须立刻返回：这些动作是分钟级的，
         * 留在广播回调（含 goAsync 的 PendingResult）里会被系统判定超时并杀进程。
         */
        fun enqueueWidgetAction(context: Context, action: String) {
            val intent = Intent(context, McForegroundService::class.java)
                .setAction(ACTION_WIDGET_ACTION)
                .putExtra(EXTRA_WIDGET_ACTION, action)
            startServiceSafely(context, intent)
        }

        /** 把一个定时任务动作交给前台服务执行（理由同 [enqueueWidgetAction]）。 */
        fun enqueueScheduleAction(context: Context, scheduleAction: String) {
            val intent = Intent(context, McForegroundService::class.java)
                .setAction(ACTION_WIDGET_ACTION)
                .putExtra(EXTRA_SCHEDULE_ACTION, scheduleAction)
            startServiceSafely(context, intent)
        }

        /**
         * 拉起服务：已在前台就 startService，否则 startForegroundService。
         * 厂商限制导致抛异常时只记日志，不让广播/闹钟回调崩溃。
         */
        private fun startServiceSafely(context: Context, intent: Intent) {
            try {
                if (isRunning) context.startService(intent)
                else context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "无法启动前台服务执行动作: ${e.message}")
            }
        }
    }
}
