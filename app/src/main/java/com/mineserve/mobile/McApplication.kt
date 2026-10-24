package com.mineserve.mobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.StrictMode
import androidx.work.Configuration
import androidx.work.WorkManager
import com.mineserve.mobile.data.DownloadPrefs
import com.mineserve.mobile.data.ServerRepository
import com.mineserve.mobile.data.UsageTracker
import com.mineserve.mobile.mcp.McpServerManager
import com.mineserve.mobile.runtime.TermuxRuntime
import com.mineserve.mobile.server.CrashReportAnalyzer
import com.mineserve.mobile.service.McForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 应用入口：负责初始化
 * - 通知通道（Android 8+ 必须）
 * - WorkManager（手动初始化，便于注入 Repository）
 * - 全局单例 TermuxRuntime 与 ServerRepository
 * - 异步初始化 Termux 环境（bootstrap），UI 可通过 isBootstrapped 观察进度
 */
class McApplication : Application(), Configuration.Provider {

    lateinit var termuxRuntime: TermuxRuntime
        private set
    lateinit var repository: ServerRepository
        private set

    /** Manual dependency graph shared by UI entry points. */
    lateinit var container: AppContainer
        private set

    /** 内嵌 MCP 服务器管理器（局域网 AI 助手接入） */
    lateinit var mcpServerManager: McpServerManager
        private set

    /** Termux 环境初始化完成标志，UI 层可观察 */
    private val _isBootstrapped = MutableStateFlow(false)
    val isBootstrapped: StateFlow<Boolean> = _isBootstrapped.asStateFlow()

    /** Termux 环境初始化错误信息，UI 层可观察 */
    private val _bootstrapError = MutableStateFlow<String?>(null)
    val bootstrapError: StateFlow<String?> = _bootstrapError.asStateFlow()

    /** Bootstrap 下载速度（bytes/s） */
    private val _bootstrapSpeed = MutableStateFlow(0L)
    val bootstrapSpeed: StateFlow<Long> = _bootstrapSpeed.asStateFlow()

    /** 当前 bootstrap 使用的镜像源索引（-1 表示未在下载中） */
    val currentMirrorIndex: StateFlow<Int> get() = termuxRuntime.installer.currentMirrorIndex

    /** 镜像源名称列表 */
    val mirrorSources: List<String> get() = termuxRuntime.installer.mirrorSources

    /** 请求停止当前镜像源下载并切换到下一个 */
    fun switchBootstrapMirror() {
        val idx = currentMirrorIndex.value
        android.util.Log.i("McApplication", "[切换] switchBootstrapMirror 调用: currentMirrorIndex=$idx, 线程=${Thread.currentThread().name}")
        termuxRuntime.installer.requestStopAndSwitch()
    }

    /** 上次崩溃日志路径（APP 内文件管理器可访问） */
    val crashLogFile: java.io.File
        get() = java.io.File(filesDir, "home/crash_log.txt")

    /** 更新通知点击后置 true，McApp 观察到后打开更新对话框 */
    private val _openUpdateRequest = MutableStateFlow(false)
    val openUpdateRequest: StateFlow<Boolean> = _openUpdateRequest.asStateFlow()

    fun requestOpenUpdate() {
        _openUpdateRequest.value = true
    }

    fun consumeOpenUpdateRequest() {
        _openUpdateRequest.value = false
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var bootstrapInitializationInFlight = false
    @Volatile private var backgroundWorkRegistered = false

    /**
     * 语言覆盖的唯一入口。
     *
     * Application 的 base Context 一旦带上所选 Locale，之后所有拿
     * `getApplicationContext()` / Application 实例取字符串的地方（通知、崩溃日志、
     * 组件 RemoteViews、AppWidget）都自动是所选语言，不需要逐个传 Context。
     * 必须在 `onCreate` 之前完成，所以放在 attachBaseContext。
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(com.mineserve.mobile.data.LocaleManager.wrap(base))
    }

    /** 应用级协程作用域（替代 GlobalScope，随进程生命周期，可统一取消） */
    fun scope(): CoroutineScope = appScope

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Debug 构建启用 StrictMode：尽早发现主线程磁盘/网络违规
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .detectActivityLeaks()
                    .penaltyLog()
                    .build()
            )
        }

        // 全局崩溃捕获
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val crashLog = buildString {
                appendLine("========================================")
                appendLine(getString(R.string.s3, java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())))
                appendLine(getString(R.string.s4, thread.name))
                appendLine(getString(R.string.s5, throwable.javaClass.name, throwable.message))
                for (el in throwable.stackTrace.take(30)) {
                    appendLine("  at $el")
                }
                var cause = throwable.cause
                while (cause != null) {
                    appendLine("Caused by: ${cause.javaClass.name}: ${cause.message}")
                    for (el in cause.stackTrace.take(10)) {
                        appendLine("  at $el")
                    }
                    cause = cause.cause
                }
                appendLine("========================================")
            }
            try {
                java.io.File(filesDir, "home").mkdirs()
                java.io.File(filesDir, "home/crash_log.txt").appendText("$crashLog\n")
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // 初始化多线程下载设置持有器（供各下载模块读取）
        DownloadPrefs.init(this)

        // 加载崩溃归因规则（内置 assets/rules/server_errors.json + 用户自定义规则目录）。
        // 同步执行，只读有界的内容（内置 asset 几 KB，自定义规则单个限额 256 KB）；
        // 之后 CrashReportAnalyzer.analyze() 才能在 UI 线程同步调用而无需再做 IO。
        CrashReportAnalyzer.logger = { msg ->
            android.util.Log.w("CrashReportAnalyzer", msg)
        }
        CrashReportAnalyzer.initialize(this)

        termuxRuntime = TermuxRuntime(this)

        // 启动时推送上次崩溃日志到控制台（IO 线程，不阻塞主线程启动）
        if (crashLogFile.exists() && crashLogFile.length() > 0) {
            scope().launch {
                try {
                    val lastCrash = crashLogFile.readText().takeLast(3000)
                    termuxRuntime.emitLog("[crash] 上次崩溃日志:\n$lastCrash")
                    crashLogFile.renameTo(java.io.File(filesDir, "home/crash_log_read.txt"))
                } catch (_: Exception) {}
            }
        }
        repository = ServerRepository(this, termuxRuntime)
        container = AppContainer(this)
        mcpServerManager = McpServerManager(this, repository)
        mcpServerManager.start()
        createNotificationChannel()

        // 1.2.x 的「每日定时开/停服」已并入「更多 → 自动化」。旧字段若原样留着，
        // BootReceiver 与前台服务仍会每天照旧开停服，而设置页已经没有开关可以关掉它 ——
        // 因此启动时一次性迁移成定时任务，并用迁移后的配置重注册/取消旧闹钟。
        scope().launch {
            runCatching {
                val migrated = com.mineserve.mobile.data.AutomationMigration.migrate(repository.currentConfig())
                if (migrated == repository.currentConfig()) return@runCatching
                val saved = repository.updateAndSaveConfig { com.mineserve.mobile.data.AutomationMigration.migrate(it) }
                com.mineserve.mobile.data.ScheduleManager.register(this@McApplication, saved)
                com.mineserve.mobile.data.AutomationScheduler.register(
                    this@McApplication,
                    com.mineserve.mobile.data.AutomationScheduler.withNextRunsAssigned(saved.automationTasks)
                )
                android.util.Log.i("McApplication", "旧版每日定时设置已迁移为自动化任务：${saved.automationTasks.size} 条")
            }
        }

        // 累计使用人数统计：设备标识上报（每天一次，失败静默）
        UsageTracker.maybePulse(this)

        // 设置 bootstrap 日志回调，推送到 consoleFlow
        termuxRuntime.setBootstrapLogCallback { msg ->
            termuxRuntime.emitLog(if (msg.startsWith("[bootstrap]")) msg else "[bootstrap] $msg")
        }

        // 设置 bootstrap 速度回调
        termuxRuntime.installer.onSpeed = { _, speedBps ->
            _bootstrapSpeed.value = speedBps
        }

        // 保活默认开启：应用启动 3 秒内自动拉起前台保活服务并注册周期检查（用户可在保活页关闭）
        scope().launch {
            kotlinx.coroutines.delay(3_000)
            val prefs = getSharedPreferences(BootReceiver.META_PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(BootReceiver.KEY_KEEP_ALIVE, true)) {
                try {
                    startForegroundService(
                        android.content.Intent(this@McApplication, McForegroundService::class.java)
                            .setAction(McForegroundService.ACTION_START)
                    )
                } catch (e: Exception) {
                    android.util.Log.w("McApplication", "自动保活启动失败: ${e.message}")
                }
                runCatching {
                    androidx.work.WorkManager.getInstance(this@McApplication).enqueueUniquePeriodicWork(
                        "keep_alive",
                        androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
                        androidx.work.PeriodicWorkRequestBuilder<KeepAliveWorker>(
                            15, java.util.concurrent.TimeUnit.MINUTES
                        ).build()
                    )
                }
            }
        }

        // 初始化工作移至后台线程，避免阻塞页面创建；环境检查和 bootstrap 不额外延后。
        scope().launch {
            registerBackgroundWork()
            if (termuxRuntime.isReady()) {
                val steps = termuxRuntime.installedDependencySteps()
                _isBootstrapped.value = true
                repository.updateServerState { it.copy(currentProgress = 100, installSteps = steps) }
            } else {
                startBootstrap()
            }
        }
    }

    /** 启动/重试 bootstrap 初始化 */
    fun startBootstrap() {
        if (_isBootstrapped.value) return
        synchronized(this) {
            if (bootstrapInitializationInFlight) return
            bootstrapInitializationInFlight = true
        }
        scope().launch {
            try {
                _bootstrapError.value = null
                // 应用用户选择的下载源偏好（下载前从配置恢复，避免进程重启后丢失）。
                runCatching { termuxRuntime.setDownloadMirror(repository.currentConfig().downloadMirror) }
                val ok = try {
                termuxRuntime.bootstrap { phase, progress ->
                    android.util.Log.i("McApplication", "bootstrap: ${getString(phase.labelRes)} $progress%")
                    repository.updateServerState { it.copy(currentProgress = progress) }
                }
            } catch (t: Throwable) {
                android.util.Log.e("McApplication", "bootstrap crashed", t)
                _bootstrapError.value = t.message ?: getString(R.string.s7)
                false
            }
            if (ok) {
                _isBootstrapped.value = true
                // 新装环境：修复 rootfs 命令可执行权限 + 脚本解释器路径
                try {
                    termuxRuntime.fixRootfsPermissions()
                } catch (e: Exception) {
                    android.util.Log.w("McApplication", "fixRootfsPermissions after bootstrap failed: ${e.message}")
                }
                android.util.Log.i("McApplication", "bootstrap completed")
                repository.updateServerState {
                    it.copy(currentProgress = 100, installSteps = termuxRuntime.installedDependencySteps())
                }
                } else {
                    android.util.Log.e("McApplication", "bootstrap failed")
                    if (_bootstrapError.value == null) {
                        _bootstrapError.value = getString(R.string.s8)
                    }
                }
            } finally {
                synchronized(this@McApplication) {
                    bootstrapInitializationInFlight = false
                }
            }
        }
    }

    private fun registerBackgroundWork() {
        synchronized(this) {
            if (backgroundWorkRegistered) return
            backgroundWorkRegistered = true
        }
        // 只保留 Configuration.Provider 的官方自动初始化，不再手动 WorkManager.initialize：
        // onCreate 里 3 秒后的 keep_alive 分支会先调用 getInstance() 触发自动初始化，
        // 两者无先后保证，手动 initialize 会抛 IllegalStateException 被旧逻辑吞掉，
        // 导致这里唯一的 widget_refresh 周期任务永不入队。
        if (!enqueueWidgetRefresh()) {
            synchronized(this) { backgroundWorkRegistered = false }
            // 初始化竞争属于偶发：短暂等待后重试一次，且失败时明确打错误日志，不再静默吞掉。
            scope().launch {
                kotlinx.coroutines.delay(1_000)
                if (enqueueWidgetRefresh()) {
                    synchronized(this@McApplication) { backgroundWorkRegistered = true }
                } else {
                    android.util.Log.e("McApplication", "widget_refresh 入队失败：WorkManager 不可用，兜底刷新任务未注册")
                }
            }
        }
    }

    /** 入队 widget_refresh（用 Configuration.Provider 自动初始化的 WorkManager 实例）。 */
    private fun enqueueWidgetRefresh(): Boolean = try {
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "widget_refresh",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            androidx.work.PeriodicWorkRequestBuilder<com.mineserve.mobile.widget.WidgetRefreshWorker>(
                15, java.util.concurrent.TimeUnit.MINUTES
            ).build()
        )
        true
    } catch (e: Exception) {
        android.util.Log.w("McApplication", "WorkManager setup failed", e)
        false
    }

    /**
     * 删除 Termux 运行环境并重置状态。
     * 删除后自动重新开始初始化流程。
     */
    fun deleteBootstrap() {
        scope().launch {
            _isBootstrapped.value = false
            _bootstrapError.value = null
            repository.updateServerState {
                it.copy(
                    currentProgress = 0,
                    installSteps = com.mineserve.mobile.data.InstallStep.values().map { step ->
                        com.mineserve.mobile.data.StepState(step, com.mineserve.mobile.data.StepStatus.Wait)
                    }
                )
            }
            termuxRuntime.deleteBootstrap()
            // 重新开始初始化
            startBootstrap()
        }
    }

    /**
     * 强制删除 Termux 依赖：彻底卸载（含缓存与状态标记，删除失败自动重试），
     * 删除后**不自动重新初始化**——等待用户手动触发安装。
     */
    fun forceDeleteBootstrap() {
        scope().launch {
            _isBootstrapped.value = false
            _bootstrapError.value = null
            repository.updateServerState {
                it.copy(
                    currentProgress = 0,
                    installSteps = com.mineserve.mobile.data.InstallStep.values().map { step ->
                        com.mineserve.mobile.data.StepState(step, com.mineserve.mobile.data.StepStatus.Wait)
                    }
                )
            }
            termuxRuntime.deleteBootstrap(force = true)
            // 注意：不调用 startBootstrap()，保持卸载状态，等用户手动初始化
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            getString(R.string.notif_channel_id),
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW // 低优先级，无声不弹
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
        com.mineserve.mobile.data.ServerEventNotifier.createChannel(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    /**
     * 语言切换后需要手动补刷的两处「不随 Activity 重建自动更新」的界面：
     * 1. 常驻通知渠道名（渠道一旦创建，名字由系统记录，重建同 id 渠道才会覆盖）；
     * 2. 桌面组件的 RemoteViews 文案（由 AppWidgetHost 缓存，需要重新推送）。
     *
     * 其余界面随 `Activity.recreate()` 自然更新。
     */
    fun refreshLocalizedSurfaces() {
        runCatching { createNotificationChannel() }
            .onFailure { android.util.Log.w("McApplication", "刷新通知渠道失败: ${it.message}") }
        runCatching { com.mineserve.mobile.data.WidgetUpdater.refresh(this) }
            .onFailure { android.util.Log.w("McApplication", "刷新桌面组件失败: ${it.message}") }
    }

    companion object {
        @Volatile private var instance: McApplication? = null
        fun get(): McApplication = instance!!
        fun get(context: Context): McApplication =
            context.applicationContext as McApplication
    }
}
