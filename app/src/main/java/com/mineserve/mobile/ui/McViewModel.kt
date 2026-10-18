package com.mineserve.mobile.ui

// 性能修改理由：日志只在有新增内容时发布不可变快照，并降低资源采样频率以减少界面重组。
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mineserve.mobile.R
import com.mineserve.mobile.BuildConfig
import com.mineserve.mobile.MainActivity
import com.mineserve.mobile.BootReceiver
import com.mineserve.mobile.KeepAliveWorker
import com.mineserve.mobile.McApplication
import com.mineserve.mobile.service.McForegroundService
import android.net.Uri
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.AutomationTask
import com.mineserve.mobile.data.AutomationScheduler
import com.mineserve.mobile.data.JavaVersion
import com.mineserve.mobile.data.PhpVersion
import com.mineserve.mobile.data.ProotDistro
import com.mineserve.mobile.data.isProotGuestRuntime
import com.mineserve.mobile.data.RuntimeKind
import com.mineserve.mobile.data.JavaRuntime
import com.mineserve.mobile.data.JavaVendor
import com.mineserve.mobile.data.ServerCore
import com.mineserve.mobile.data.MinecraftVersionNormalizer
import com.mineserve.mobile.data.ServerPort
import com.mineserve.mobile.data.ServerRepository
import com.mineserve.mobile.data.ServerState
import com.mineserve.mobile.data.StartupPhase
import com.mineserve.mobile.runtime.ConsoleLineParser
import com.mineserve.mobile.runtime.NativeLibraryBundler
import com.mineserve.mobile.data.TunnelState
import com.mineserve.mobile.data.TunnelStatus
import com.mineserve.mobile.data.TunnelType
import com.mineserve.mobile.data.FrpProfile
import com.mineserve.mobile.data.FrpProfileKind
import com.mineserve.mobile.data.FrpLocalTarget
import com.mineserve.mobile.data.FrpProfiles
import com.mineserve.mobile.data.DiagnosticCheck
import com.mineserve.mobile.data.DiagnosticReport
import com.mineserve.mobile.data.DiagnosticStatus
import com.mineserve.mobile.data.ServerResourceStats
import com.mineserve.mobile.data.ServerEventNotifier
import com.mineserve.mobile.data.AppRelease
import com.mineserve.mobile.data.AppUpdateService
import com.mineserve.mobile.server.BackupManager
import com.mineserve.mobile.server.CrashReportManager
import com.mineserve.mobile.server.CrashReportAnalyzer
import com.mineserve.mobile.server.SafeTextFile
import com.mineserve.mobile.server.McServerController
import com.mineserve.mobile.server.PlayerManager
import com.mineserve.mobile.server.PluginManager
import com.mineserve.mobile.server.ServerPropertiesManager
import com.mineserve.mobile.server.tunnel.StunDiagnostics
import com.mineserve.mobile.server.tunnel.frpc.FrpcEngineConfig
import com.mineserve.mobile.server.tunnel.frpc.FrpcRuntimeDownloader
import com.mineserve.mobile.server.PowerNukkitXConfigManager
import com.mineserve.mobile.server.PowerNukkitXLayout
import com.mineserve.mobile.server.ServerImporter
import com.mineserve.mobile.server.TunnelManager
import com.mineserve.mobile.server.WorldManager
import com.mineserve.mobile.server.Worlds
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.InetSocketAddress
import java.net.Socket
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.ClipData
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import android.os.SystemClock
import java.io.File

/**
 * 顶层共享 ViewModel：
 *  - 暴露 McConfig / ServerState / Plugins / ConsoleLog
 *  - 转发用户操作到 Controller / Manager
 *  - 所有操作捕获异常，通过 errorFlow 传递给 UI，不崩溃
 */
class McViewModel(
    private val app: McApplication,
    private val repo: ServerRepository,
    private val controller: McServerController,
    private val pluginManager: PluginManager,
    private val tunnelManager: TunnelManager
) : ViewModel() {
    val config: StateFlow<McConfig> = repo.configFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), McConfig()
    )

    val serverState: StateFlow<ServerState> = repo.serverState

    /** Termux 环境是否初始化完成 */
    val isBootstrapped: StateFlow<Boolean> = app.isBootstrapped

    /** Termux 环境初始化错误信息 */
    val bootstrapError: StateFlow<String?> = app.bootstrapError

    /** bootstrap 下载速度（bytes/s） */
    val bootstrapSpeed: StateFlow<Long> = app.bootstrapSpeed

    /** bootstrap 当前镜像源索引 */
    val currentMirrorIndex: StateFlow<Int> = app.currentMirrorIndex

    /** 镜像源名称列表 */
    val mirrorSources: List<String> get() = app.mirrorSources

    /** 请求切换到下一个镜像源 */
    fun switchBootstrapMirror() {
        android.util.Log.i("McViewModel", "[切换] switchBootstrapMirror 调用, 线程=${Thread.currentThread().name}")
        app.switchBootstrapMirror()
    }

    /** apt 安装下载速度（bytes/s） */
    private val _installSpeed = MutableStateFlow(0L)
    val installSpeed: StateFlow<Long> = _installSpeed.asStateFlow()

    /** 重试 Termux 环境初始化 */
    fun retryBootstrap() {
        app.startBootstrap()
    }

    /** 删除 Termux 运行环境（会自动重新初始化） */
    fun deleteBootstrap() {
        app.deleteBootstrap()
    }

    /** 强制删除 Termux 依赖：彻底卸载，删除后不自动重新初始化（需手动安装） */
    fun forceDeleteBootstrap() {
        app.forceDeleteBootstrap()
    }

    private val _consoleLines = MutableStateFlow<ImmutableList<String>>(persistentListOf())
    val consoleLines: StateFlow<ImmutableList<String>> = _consoleLines.asStateFlow()

    /** 终端专用的后台预处理日志，避免 Compose 主线程翻译和关键词扫描。 */
    private val _terminalConsoleLines = MutableStateFlow<ImmutableList<TerminalDisplayLine>>(persistentListOf())
    val terminalConsoleLines: StateFlow<ImmutableList<TerminalDisplayLine>> = _terminalConsoleLines.asStateFlow()
    private val _logTranslationEnabled = MutableStateFlow(true)
    val logTranslationEnabled: StateFlow<Boolean> = _logTranslationEnabled.asStateFlow()

    /** 服务端最近一次运行从启动到退出写入 latest.log 的完整内容。 */
    private val _lastRunLog = MutableStateFlow("")
    val lastRunLog: StateFlow<String> = _lastRunLog.asStateFlow()
    @Volatile private var terminalHistoryLoading = false

    /**
     * 界面是否处于前台可见。
     *
     * 原先用 `StateFlow.subscriptionCount <= 0` 当作「UI 不可见」来降频，但订阅方用的是
     * `collectAsState`（非生命周期感知）：Activity 停止后 Compose 组合不销毁、订阅数恒 > 0，
     * 于是注释里宣称的「后台降为 2s」永不生效，待机耗电偏高。
     * 这里改为显式标志位，由 McApp 通过生命周期事件推送真实前台/后台状态。
     */
    @Volatile private var uiVisible = true

    /** 由 McApp 在 Activity 前台/后台切换时调用，供日志刷新循环据此降频。 */
    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
    }

    /** 事件通知等级：0=关闭 1=仅重要 2=全部（含玩家上下线）。 */
    private val _eventNotifyLevel = MutableStateFlow(ServerEventNotifier.level(app))
    val eventNotifyLevel: StateFlow<Int> = _eventNotifyLevel.asStateFlow()

    /** Small, stable preview for cards that only show the latest few server messages. */
    private val _consolePreviewLines = MutableStateFlow<ImmutableList<String>>(persistentListOf())
    val consolePreviewLines: StateFlow<ImmutableList<String>> = _consolePreviewLines.asStateFlow()

    // ── Termux 终端（会话面板） ─────────────────────────────

    private val _termuxLines = MutableStateFlow<ImmutableList<String>>(persistentListOf())
    val termuxLines: StateFlow<ImmutableList<String>> = _termuxLines.asStateFlow()

    private val _termuxBusy = MutableStateFlow(false)
    val termuxBusy: StateFlow<Boolean> = _termuxBusy.asStateFlow()

    private val terminalMutex = Mutex()
    @Volatile private var interactiveTerminalSessionId: String? = null
    private val _terminalSessions = MutableStateFlow(
        listOf(
            TerminalSession("minecraft", str(R.string.mc_console_label), TerminalSessionType.Minecraft),
            TerminalSession("termux-1", "Termux 1", TerminalSessionType.Termux)
        )
    )
    val terminalSessions: StateFlow<List<TerminalSession>> = _terminalSessions.asStateFlow()
    private val _activeTerminalSessionId = MutableStateFlow("minecraft")
    val activeTerminalSessionId: StateFlow<String> = _activeTerminalSessionId.asStateFlow()

    fun createTerminalSession() {
        val next = (_terminalSessions.value.count { it.type == TerminalSessionType.Termux } + 1)
        val id = "termux-${System.nanoTime()}"
        _terminalSessions.update { it + TerminalSession(id, "Termux $next", TerminalSessionType.Termux) }
        _activeTerminalSessionId.value = id
    }

    fun selectTerminalSession(id: String) { _activeTerminalSessionId.value = id }

    fun setLogTranslationEnabled(enabled: Boolean) {
        _logTranslationEnabled.value = enabled
        terminalDisplayDirty = true
    }

    fun closeTerminalSession(id: String) {
        val session = _terminalSessions.value.firstOrNull { it.id == id } ?: return
        if (session.type == TerminalSessionType.Minecraft) return
        // 用 update 原子读改写：终端刷新协程（Dispatchers.Default）会对同一 StateFlow 做 map 写回，
        // 原先「读 remaining → 整条写回」的跨线程读改写会互相覆盖，导致新建会话整条消失或本轮输出被丢。
        var nextActive: String? = null
        _terminalSessions.update { list ->
            val remaining = list.filterNot { it.id == id }
            if (remaining.none { it.type == TerminalSessionType.Termux }) {
                val fallback = TerminalSession("termux-${System.nanoTime()}", "Termux 1", TerminalSessionType.Termux)
                nextActive = fallback.id
                remaining + fallback
            } else {
                if (_activeTerminalSessionId.value == id) nextActive = remaining.first().id
                remaining
            }
        }
        // 回收该会话的输出缓冲：此前只有 clearTerminalSession 会清，反复新建/关闭会让条目永久残留
        synchronized(terminalOutputBuffers) { terminalOutputBuffers.remove(id) }
        nextActive?.let { _activeTerminalSessionId.value = it }
    }

    fun executeTerminalCommand(id: String, command: String) {
        if (command.isBlank()) return
        val session = _terminalSessions.value.firstOrNull { it.id == id } ?: return
        if (session.type != TerminalSessionType.Termux || !isBootstrapped.value) return
        viewModelScope.launch {
            terminalMutex.withLock {
                updateTerminalSession(id) { it.copy(busy = true) }
                enqueueTerminalLine(id, "$ $command")
                interactiveTerminalSessionId = id
                try {
                    withContext(Dispatchers.IO) { repo.termuxRuntime.refreshTermux() }
                    val exit = withContext(Dispatchers.IO) {
                        repo.termuxRuntime.execTermux(command) { line -> enqueueTerminalLine(id, line) }
                    }
                    if (exit != 0) enqueueTerminalLine(id, str(R.string.term_exit_code, exit))
                } catch (e: Exception) {
                    enqueueTerminalLine(id, str(R.string.term_exec_error, e.message))
                } finally {
                    interactiveTerminalSessionId = null
                    updateTerminalSession(id) { it.copy(busy = false) }
                }
            }
        }
    }

    /** 向当前 Termux 会话中的命令发送一行 stdin。 */
    fun sendTerminalInput(id: String, input: String) {
        if (input.isBlank() || interactiveTerminalSessionId != id) return
        if (repo.termuxRuntime.sendTermuxInput(input)) {
            enqueueTerminalLine(id, "> $input")
        }
    }

    /** 清空指定终端会话的显示行（保留会话本身） */
    fun clearTerminalSession(id: String) {
        synchronized(terminalOutputBuffers) { terminalOutputBuffers.remove(id) }
        updateTerminalSession(id) { it.copy(lines = kotlinx.collections.immutable.persistentListOf()) }
    }

    /** 清空 MC 控制台缓冲 */
    fun clearConsole() {
        consoleBuffer.clear()
        pendingConsoleBuffer.clear()
        terminalConsoleBuffer.clear()
        consoleGeneration++
        terminalGeneration++
        consoleUiGeneration = consoleGeneration
        terminalUiGeneration = terminalGeneration
        previewUiGeneration = consoleGeneration
        _consoleLines.value = persistentListOf()
        _consolePreviewLines.value = persistentListOf()
        _terminalConsoleLines.value = persistentListOf()
    }

    /** 进入 MC 终端时异步补读磁盘日志，实时输出仍由 consoleFlow 持续追加。 */
    fun loadRecentTerminalLog() {
        if (terminalHistoryLoading) return
        val dirName = activeDirName() ?: return
        terminalHistoryLoading = true
        viewModelScope.launch {
            try {
                val history = withContext(Dispatchers.IO) {
                    repo.termuxRuntime.readRecentMcLog(dirName)
                }
                if (history.isNotEmpty()) {
                    val live = consoleBuffer.snapshot()
                    val overlap = (minOf(history.size, live.size) downTo 1).firstOrNull { size ->
                        history.takeLast(size) == live.take(size)
                    } ?: 0
                    val merged = (history + live.drop(overlap)).takeLast(MAX_LOG_LINES)
                    consoleBuffer.replace(merged)
                    terminalConsoleBuffer.replace(
                        merged.map { TerminalLogProcessor.process(it, _logTranslationEnabled.value) }
                    )
                    consoleGeneration++
                    terminalGeneration++
                    _consoleLines.value = consoleBuffer.snapshot()
                    _terminalConsoleLines.value = terminalConsoleBuffer.snapshot()
                    consoleUiGeneration = consoleGeneration
                    terminalUiGeneration = terminalGeneration
                }
            } finally {
                terminalHistoryLoading = false
            }
        }
    }

    private fun updateTerminalSession(id: String, transform: (TerminalSession) -> TerminalSession) {
        // update 原子读改写：调用方既有 Dispatchers.Default 的行刷新循环，也有主线程的会话增删，
        // 直接 `value = value.map{}` 会跨线程丢更新（新建的会话整条消失 / 本轮终端输出被丢弃）。
        _terminalSessions.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    private val terminalOutputBuffers = mutableMapOf<String, LogBuffer<String>>()

    private fun enqueueTerminalLine(id: String, line: String) {
        synchronized(terminalOutputBuffers) {
            terminalOutputBuffers.getOrPut(id) { LogBuffer(MAX_LOG_LINES) }.add(line)
        }
    }

    /** 执行 Termux shell 命令（IO 线程，输出实时追加到 termuxLines，命令回显 $ cmd） */
    fun execTermuxCommand(command: String) {
        if (command.isBlank() || _termuxBusy.value) return
        // 环境未初始化完成时禁止输入（bootstrap 下载/解压/装依赖中，命令必然失败）
        if (!isBootstrapped.value) {
            appendTermux(str(R.string.termux_not_ready))
            return
        }
        viewModelScope.launch {
            _termuxBusy.value = true
            try {
                // 命令执行前快速自愈：apt/pkg 新装包命令立即可用，无需重启
                withContext(Dispatchers.IO) {
                    repo.termuxRuntime.refreshTermux()
                }
                appendTermux("$ " + command)
                val exit = withContext(Dispatchers.IO) {
                    repo.termuxRuntime.execTermux(command) { line ->
                        appendTermux(line)
                    }
                }
                if (exit != 0) {
                    val hint = when (exit) {
                        126 -> str(R.string.termux_hint_126)
                        127 -> str(R.string.termux_hint_127)
                        else -> ""
                    }
                    appendTermux(str(R.string.term_exit_code, exit) + hint)
                    // 失败时输出环境诊断，便于定位根因
                    appendTermux(repo.termuxRuntime.diagnoseCommand(command))
                }
            } catch (e: Exception) {
                appendTermux(str(R.string.term_exec_error, e.message))
            } finally {
                _termuxBusy.value = false
            }
        }
    }

    private val legacyTermuxBuffer = LogBuffer<String>(MAX_LOG_LINES)

    /** 追加到旧版日志缓冲，由后台批处理协程发布，避免逐行触发重组。 */
    private fun appendTermux(line: String) {
        legacyTermuxBuffer.add(line)
    }

    // ── 服务器图标（server-icon.png） ────────────────────────

    /** 图标变更信号（UI 用于重载预览） */
    private val _serverIconVersion = MutableStateFlow(0)
    val serverIconVersion: StateFlow<Int> = _serverIconVersion.asStateFlow()

    /** 当前核心的 server-icon.png 文件（不存在返回 null） */
    fun serverIconFile(): File? {
        val dirName = activeDirName() ?: return null
        val dir = repo.termuxRuntime.serverDirFor(dirName)
        return if (PowerNukkitXLayout.isPowerNukkitX(dir)) null
        else File(dir, "server-icon.png")
            .takeIf { it.exists() }
    }

    /** 更换服务器图标：读取 Uri → 居中裁剪缩放 64×64 → PNG 写入 server-icon.png */
    fun setServerIcon(uri: Uri) {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit(str(R.string.s192))
            return
        }
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit(str(R.string.s212))
            return
        }
        if (PowerNukkitXLayout.isPowerNukkitX(repo.termuxRuntime.serverDirFor(dirName))) {
            _errorFlow.tryEmit(str(R.string.err_pnx_icon_full))
            return
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val input = app.contentResolver.openInputStream(uri)
                        ?: throw RuntimeException(str(R.string.err_read_image))
                    val src = input.use { android.graphics.BitmapFactory.decodeStream(it) }
                        ?: throw RuntimeException(str(R.string.err_parse_image))
                    try {
                        // 居中裁剪为正方形后缩放到 64×64
                        val size = minOf(src.width, src.height)
                        val x = (src.width - size) / 2
                        val y = (src.height - size) / 2
                        val square = android.graphics.Bitmap.createBitmap(src, x, y, size, size)
                        try {
                            val icon = android.graphics.Bitmap.createScaledBitmap(square, 64, 64, true)
                            try {
                                val target = File(repo.termuxRuntime.serverDirFor(dirName), "server-icon.png")
                                target.parentFile?.mkdirs()
                                java.io.FileOutputStream(target).use { fos ->
                                    icon.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos)
                                }
                            } finally {
                                if (!icon.isRecycled) icon.recycle()
                            }
                        } finally {
                            if (square !== src && !square.isRecycled) square.recycle()
                        }
                    } finally {
                        if (!src.isRecycled) src.recycle()
                    }
                }
                _messageFlow.tryEmit(str(R.string.ui_server_icon_updated))
                _serverIconVersion.value++
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.ui_server_icon_fail, e.message))
            }
        }
    }

    /** 恢复默认：删除 server-icon.png */
    fun removeServerIcon() {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit(str(R.string.s192))
            return
        }
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit(str(R.string.s212))
            return
        }
        if (PowerNukkitXLayout.isPowerNukkitX(repo.termuxRuntime.serverDirFor(dirName))) {
            _errorFlow.tryEmit(str(R.string.err_pnx_icon))
            return
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    File(repo.termuxRuntime.serverDirFor(dirName), "server-icon.png").delete()
                }
                _messageFlow.tryEmit(str(R.string.ui_server_icon_reset_done))
                _serverIconVersion.value++
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.ui_server_icon_fail, e.message))
            }
        }
    }

    /** 控制台行环形缓冲，避免每行 O(n) 拷贝 */
    private val consoleBuffer = LogBuffer<String>(MAX_LOG_LINES)
    private val pendingConsoleBuffer = LogBuffer<String>(MAX_LOG_LINES)
    private val terminalConsoleBuffer = LogBuffer<TerminalDisplayLine>(MAX_LOG_LINES)
    @Volatile private var terminalDisplayDirty = false
    private var consoleGeneration = 0L
    private var terminalGeneration = 0L
    private var consoleUiGeneration = -1L
    private var terminalUiGeneration = -1L
    private var previewUiGeneration = -1L
    private var lastPreviewPublishedAtMs = 0L

    /** 错误消息流，UI 层收集后用 Snackbar 显示 */
    private val _errorFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val errorFlow = _errorFlow.asSharedFlow()

    /** 操作结果消息流，UI 层收集后用 Snackbar 显示 */
    private val _messageFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messageFlow = _messageFlow.asSharedFlow()

    /** 依赖安装中状态，UI 层据此控制按钮和加载动画 */
    private val _isInstalling = MutableStateFlow(false)
    val isInstalling: StateFlow<Boolean> = _isInstalling.asStateFlow()

    private val _javaOperation = MutableStateFlow<String?>(null)
    val javaOperation: StateFlow<String?> = _javaOperation.asStateFlow()

    /**
     * 正在安装的那一项运行环境（来源 + 版本），用于把进度圈钉在对应的版本行上。
     *
     * 进度是长任务（FCL 那套要下 37–62MB），只给一句全局文案的话，
     * 用户无从判断「现在到底在下哪一套」。
     */
    private val _javaInFlight = MutableStateFlow<JavaRuntime?>(null)
    val javaInFlight: StateFlow<JavaRuntime?> = _javaInFlight.asStateFlow()

    /**
     * 已安装的运行环境集合。
     *
     * 用 [JavaRuntime]（来源 + 版本）而不是裸版本号：Termux 的 Java 17 与 FCL 的
     * Java 17 是两份不同的文件，只按版本号记会把两者混成一个。
     */
    private val _installedJava = MutableStateFlow<Set<JavaRuntime>>(emptySet())
    val installedJava: StateFlow<Set<JavaRuntime>> = _installedJava.asStateFlow()

    /** 局域网 IP（IPv4，非 loopback），用于 Network Tab 展示和一键复制 */
    private val _lanIp = MutableStateFlow("--")
    val lanIp: StateFlow<String> = _lanIp.asStateFlow()
    /**
     * 聚合隧道状态（两条通道取其一），保留给不区分通道的地方。
     * 网络页**不要**用它 —— 两张卡片各自订阅 [frpState] / [stunState]，
     * 聚合值无法表达「两条同时在跑」。
     */
    val tunnelState: StateFlow<TunnelState> = tunnelManager.state

    /** FRP 通道状态（frp / SakuraFrp / ME Frp 共用同一通道）。 */
    val frpState: StateFlow<TunnelState> = tunnelManager.frpState

    /** STUN 通道状态。与 FRP 相互独立，可同时运行。 */
    val stunState: StateFlow<TunnelState> = tunnelManager.stunState

    /**
     * STUN 直连专属诊断：公网地址、探测耗时、空连接回收计数。
     * 通用状态要与多种后端共用，这些只对 STUN 有意义，故分开放。
     */
    val stunDiagnostics: StateFlow<StunDiagnostics> = tunnelManager.stunDiagnostics

    // ── frpc 引擎（内置引擎，不依赖 Termux） ────────────────────────

    /** frpc 引擎信息快照，供隧道页展示「已安装 / 未安装」。 */
    data class FrpcEngineInfo(
        val installed: Boolean = false,
        val version: String? = null,
        val sizeBytes: Long = 0,
        val sha256: String? = null
    )

    private val _frpcEngine = MutableStateFlow(FrpcEngineInfo())
    val frpcEngine: StateFlow<FrpcEngineInfo> = _frpcEngine.asStateFlow()

    /** 引擎导入结果提示，一次性消费后清空。 */
    private val _frpcEngineMessage = MutableStateFlow<String?>(null)
    val frpcEngineMessage: StateFlow<String?> = _frpcEngineMessage.asStateFlow()

    fun clearFrpcEngineMessage() {
        _frpcEngineMessage.value = null
    }

    private val frpcRuntime get() = tunnelManager.frpcRuntime

    /** 刷新 frpc 引擎状态（含 SHA-256 计算，走 IO 线程）。 */
    fun refreshFrpcEngine() {
        viewModelScope.launch(Dispatchers.IO) {
            val installed = frpcRuntime.isInstalled()
            _frpcEngine.value = FrpcEngineInfo(
                installed = installed,
                version = if (installed) frpcRuntime.installedVersion else null,
                sizeBytes = if (installed) frpcRuntime.engineFile.length() else 0,
                sha256 = if (installed) frpcRuntime.engineSha256() else null
            )
        }
    }

    /** 在线下载状态。未配置下载源时 UI 不显示下载入口，这个状态也就一直是 Idle。 */
    sealed interface DownloadState {
        data object Idle : DownloadState
        data class Downloading(val fraction: Float) : DownloadState
    }

    private val _frpcDownload = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val frpcDownload: StateFlow<DownloadState> = _frpcDownload.asStateFlow()

    /** 是否配置了在线下载源；没配置时 UI 只保留本地导入。 */
    val frpcOnlineAvailable: Boolean get() = FrpcEngineConfig.isConfigured

    /**
     * 在线下载并安装引擎。
     *
     * 下载失败**不动**已有引擎（[FrpcRuntimeDownloader] 保证）：用户可能只是想升级，
     * 升级失败却把当前可用的版本弄没了，那是最糟的结果。所以这里失败只提示，不清理。
     */
    fun downloadFrpcEngine() {
        if (_frpcDownload.value is DownloadState.Downloading) return
        viewModelScope.launch(Dispatchers.IO) {
            _frpcDownload.value = DownloadState.Downloading(-1f)
            try {
                val downloader = FrpcRuntimeDownloader(frpcRuntime)
                val manifest = downloader.fetchManifest()
                val result = downloader.downloadAndInstall(manifest) { fraction ->
                    _frpcDownload.value = DownloadState.Downloading(fraction)
                }
                _frpcEngineMessage.value = "引擎下载完成" +
                    (result.version?.let { "（版本 $it）" } ?: "")
            } catch (e: Exception) {
                _frpcEngineMessage.value = "下载失败：${e.message ?: "网络不可用"}"
            } finally {
                _frpcDownload.value = DownloadState.Idle
                refreshFrpcEngine()
            }
        }
    }

    /**
     * 从用户选中的文件导入 frpc 引擎（libfrpc.so）。
     *
     * 校验在 [com.mineserve.mobile.server.tunnel.frpc.FrpcRuntime.install] 内完成
     * （体积 / ELF 魔数 / arm64 架构）。校验失败时**不覆盖**已装引擎 ——
     * 用户手上未必还有第二份可用的引擎。
     */
    fun importFrpcEngine(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val name = queryDisplayName(uri)
                app.contentResolver.openInputStream(uri)?.use { input ->
                    frpcRuntime.install(input, name)
                } ?: throw IllegalStateException("无法读取所选文件")
                _frpcEngineMessage.value = "引擎导入成功" +
                    (frpcRuntime.installedVersion?.let { "（版本 $it）" } ?: "")
                refreshFrpcEngine()
            } catch (e: Exception) {
                _frpcEngineMessage.value = "导入失败：${e.message}"
            }
        }
    }

    /** 卸载 frpc 引擎。 */
    fun uninstallFrpcEngine() {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = frpcRuntime.uninstall()
            _frpcEngineMessage.value = if (ok) "引擎已卸载" else "卸载失败，请重试"
            refreshFrpcEngine()
        }
    }

    /** 取 SAF 文件的显示名，仅用于从文件名里认引擎版本号。 */
    private fun queryDisplayName(uri: Uri): String? = runCatching {
        app.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    // ── 软件更新（GitHub Releases） ──────────────────────────────────

    /** 更新界面状态 */
    sealed interface UpdateUiState {
        data object Idle : UpdateUiState
        data object Checking : UpdateUiState
        data class Available(val release: AppRelease) : UpdateUiState
        data class Downloading(val progress: Float, val release: AppRelease) : UpdateUiState
        data class Downloaded(val release: AppRelease, val apkPath: String) : UpdateUiState
        data class Failed(val message: String, val release: AppRelease? = null) : UpdateUiState
    }

    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    /** 更新对话框是否显示 */
    private val _updateDialogVisible = MutableStateFlow(false)
    val updateDialogVisible: StateFlow<Boolean> = _updateDialogVisible.asStateFlow()

    /** 最近一次更新检查结果描述（设置页显示），如「已是最新版本 · 08:30」 */
    private val _lastUpdateCheckResult = MutableStateFlow<String?>(null)
    val lastUpdateCheckResult: StateFlow<String?> = _lastUpdateCheckResult.asStateFlow()
    private var skippedReleaseTag: String? = null

    fun dismissUpdateDialog() { _updateDialogVisible.value = false }

    /** 检查更新：manual=true 来自设置页（显示检查进度 + 失败提示）；auto=true 启动检查（失败静默 + 有新版发通知） */
    fun checkForUpdate(manual: Boolean = false) {
        if (_updateState.value is UpdateUiState.Checking) return
        _updateState.value = UpdateUiState.Checking
        if (manual) _updateDialogVisible.value = true // 手动检查：立即显示检查中对话框
        viewModelScope.launch {
            try {
                val release = AppUpdateService.latest(BuildConfig.VERSION_NAME)
                if (release == null) {
                    _updateState.value = UpdateUiState.Idle
                    _lastUpdateCheckResult.value =
                        "${app.getString(R.string.update_already_latest)} · ${nowTime()}"
                    if (manual) {
                        _updateDialogVisible.value = false
                        _messageFlow.tryEmit(app.getString(R.string.update_already_latest))
                    }
                } else {
                    _updateState.value = UpdateUiState.Available(release)
                    _lastUpdateCheckResult.value =
                        "${app.getString(R.string.update_available, release.tag)} · ${nowTime()}"
                    if (manual || skippedReleaseTag != release.tag) {
                        _updateDialogVisible.value = true
                        if (!manual) showUpdateNotification(app, release)
                    }
                }
            } catch (e: Exception) {
                    _updateState.value = UpdateUiState.Failed(e.message ?: str(R.string.msg_update_check_failed))
                    _lastUpdateCheckResult.value =
                        "${app.getString(R.string.update_check_failed)} · ${nowTime()}"
                    if (manual) {
                        _updateDialogVisible.value = true
                    }
            }
        }
    }

    private fun nowTime(): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())

    fun showUpdateDialog() {
        if (_updateState.value is UpdateUiState.Available) _updateDialogVisible.value = true
    }

    fun skipCurrentUpdate() {
        val state = _updateState.value as? UpdateUiState.Available ?: return
        skippedReleaseTag = state.release.tag
        dismissUpdateDialog()
    }

    /** 打开浏览器跳到 GitHub Release 页面（供用户手动下载更新） */
    fun openGithubUpdate() {
        val url = (_updateState.value as? UpdateUiState.Available)?.release?.releaseUrl
            ?: com.mineserve.mobile.data.AppUpdateService.PROJECT_URL
        try {
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(url)
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
        } catch (e: Exception) {
            _messageFlow.tryEmit(str(R.string.msg_open_browser_fail, e.message))
        }
    }

    /** 开始下载新版 APK（完成后自动调系统安装器） */
    fun downloadUpdate() {
        val state = _updateState.value
        val release = when (state) {
            is UpdateUiState.Available -> state.release
            is UpdateUiState.Failed -> state.release
            else -> null
        } ?: return
        _updateState.value = UpdateUiState.Downloading(0f, release)
        viewModelScope.launch {
            try {
                val target = File(app.cacheDir, "update/MineServeMobile-latest.apk")
                AppUpdateService.download(release.apkUrls, target) { p ->
                    _updateState.value = UpdateUiState.Downloading(p, release)
                }
                _updateState.value = UpdateUiState.Downloaded(release, target.absolutePath)
            } catch (e: Exception) {
                _updateState.value = UpdateUiState.Failed(e.message ?: app.getString(R.string.update_download_failed), release)
            }
        }
    }

    /** 调系统安装器安装 APK */
    fun installApk(context: Context, file: File) {
        try {
            if (!AppUpdateService.isApk(file)) {
                throw IllegalStateException(str(R.string.err_apk_corrupt))
            }
            val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                ?: throw IllegalStateException(str(R.string.err_apk_read_pkg))
            if (archive.packageName != context.packageName) {
                throw IllegalStateException(str(R.string.err_apk_pkg_mismatch))
            }
            // 签名要比对：下载源全是第三方 GitHub 反代，包名对了还不够。
            // 同签名才是"我们自己发的那个更新"，不同签名一律拒绝。
            if (!AppUpdateService.verifySignature(context, file)) {
                throw IllegalStateException(str(R.string.err_apk_sig_mismatch))
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                !context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                _messageFlow.tryEmit(str(R.string.msg_allow_unknown_install))
                return
            }
            val uri = FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                clipData = ClipData.newRawUri("update-apk", uri)
            }
            val installers = context.packageManager.queryIntentActivities(
                intent,
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
            )
            if (installers.isEmpty()) {
                throw IllegalStateException(str(R.string.err_no_installer))
            }
            installers.forEach { handler ->
                context.grantUriPermission(
                    handler.activityInfo.packageName,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            _updateState.value = UpdateUiState.Failed(e.message ?: "install error")
        }
    }

    fun installDownloadedUpdate(path: String) = installApk(app, File(path))

    /** 自动检查发现新版时发系统通知，点击进入更新对话框 */
    private fun showUpdateNotification(app: Context, info: AppRelease) {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val contentIntent = PendingIntent.getActivity(
            app, 1001,
            Intent(app, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("open_update", true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(app, app.getString(R.string.notif_channel_id))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(app.getString(R.string.update_notif_title, info.tag))
            .setContentText(app.getString(R.string.update_notif_text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        try { nm.notify(1001, notification) } catch (_: Exception) {}
    }

    // ── 服务端资源状态 ───────────────────────────────────────────────

    private val _serverResources = MutableStateFlow(ServerResourceStats())
    val serverResources: StateFlow<ServerResourceStats> = _serverResources.asStateFlow()
    private val _statusRefreshInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 手动刷新是否正在进行（供 UI 显示进度/禁用按钮）。
     *
     * 加上这个状态是因为：采集本身很快，而各采样项都有 30s/60s 缓存，
     * 点一次刷新往往「值没变」——用户无法区分「没反应」和「刷了但没变化」。
     * 有了明确的进行中状态 + 完成回调，点击就是有反馈的。
     */
    private val _isRefreshingStatus = MutableStateFlow(false)
    val isRefreshingStatus: StateFlow<Boolean> = _isRefreshingStatus.asStateFlow()

    /** 最近一次手动刷新的完成时间（供 UI 显示「刚刚更新」）。 */
    private val _lastStatusRefreshAtMs = MutableStateFlow(0L)
    val lastStatusRefreshAtMs: StateFlow<Long> = _lastStatusRefreshAtMs.asStateFlow()

    // 采样缓存的读写可能发生在不同线程（周期采集 / 手动刷新各是一条协程），
    // 标 @Volatile 保证可见性，避免一边写入、另一边长期读到旧值导致数值来回跳。
    @Volatile private var cachedResourceDir: String? = null
    @Volatile private var cachedDirectoryBytes: Long? = null
    @Volatile private var cachedDirectoryBytesAtMs = 0L
    @Volatile private var cachedMemoryAtMs = 0L
    @Volatile private var cachedMemoryMb = 0L
    @Volatile private var cachedJavaAvailable: Boolean? = null
    @Volatile private var cachedJavaAvailableAtMs = 0L

    /**
     * 采集串行化锁。
     *
     * 周期采集（每 15s）与手动刷新（[refreshServerStatus]）可能并发调用
     * [collectServerResourcesOnce]，而采集内部有 `walkTopDown` 全目录求和，
     * 并发执行会把同一目录扫两遍、两个协程互相覆盖上面的采样缓存。
     * 用 tryLock 语义：已在采集就直接跳过本轮，不做重复工作。
     *
     * @return 是否真的采集了。跳过时必须让调用方知道——以前返回 Unit，
     *         [refreshServerStatus] 抢不到锁照样置 `_lastStatusRefreshAtMs`，
     *         UI 显示「刚刚更新」但拿到的还是上一次的旧数据。
     */
    private val resourceCollectionMutex = Mutex()

    /** 使所有采样缓存失效，让下一次采集拿到真实的最新值。 */
    private fun invalidateResourceCaches() {
        cachedDirectoryBytesAtMs = 0L
        cachedMemoryAtMs = 0L
        cachedJavaAvailableAtMs = 0L
    }

    /** Samples values that belong to the selected server. */
    private fun startServerResourceCollection() {
        viewModelScope.launch {
            // 首屏尽快出值：原来开局先 delay(15s)，加上「首次采样只建基线」的语义，
            // 用户进界面后可能半分钟都看不到 CPU/内存。这里先立即采一次轻量数据
            // （不扫服务端目录），再做原来的 15s 周期循环。
            withContext(Dispatchers.IO) { collectServerResourcesOnce(includeDirectorySize = false) }
            // 整机 CPU 走 /proc/stat 两次采样求差值，首次调用只建基线、拿不到读数。
            // 若等到 15s 后的下一轮才采第二次，进界面后会有十几秒显示 `--`；
            // 这里隔 1.2s 补采一次（采样窗口 ≥1s 才有意义），让主路径尽快出真实值，
            // 期间 UI 会由 cpufreq 回退路径先给一个粗略值兜底。
            delay(1_200)
            withContext(Dispatchers.IO) { collectServerResourcesOnce(includeDirectorySize = false) }
            // Defer the first recursive server-directory scan until the first screen settles.
            delay(15_000)
            while (true) {
                // 界面不在前台时降频，并且**跳过最昂贵的服务端目录遍历**
                // （walkTopDown 递归求和世界文件，动辄几万个小文件）：锁屏/切后台后
                // 还在每 15 秒扫一遍既耗电，又会和正在写区块的 MC 抢 IO。
                // 其余采样（/proc 内存与 CPU、StatFs）很廉价且悬浮窗/通知/组件要用，
                // 所以后台仍然保留，只是放慢到 1 分钟。
                val visible = uiVisible
                withContext(Dispatchers.IO) { collectServerResourcesOnce(includeDirectorySize = visible) }
                delay(if (visible) 15_000 else 60_000)
            }
        }
    }

    /**
     * 手动刷新：立即重新采集服务器状态并请求前台服务查询最新数据。
     *
     * ## 为什么原先「点了没反应」
     * 采集函数里各采样项都有缓存（内存 30s、目录大小 60s、Java 可用性 60s），
     * 且有一段「值未变化就不发布新快照」的优化。用户点刷新时通常都在缓存窗口内，
     * 于是采出的值与上次完全相同 → 不发布 → UI 毫无变化，看起来就是「按钮坏了」。
     *
     * 现在手动刷新会：
     *   1. **清空采样缓存**，强制重新读取 /proc 与目录
     *   2. 置位 [isRefreshingStatus]，让按钮立刻有视觉反馈
     *   3. 结束后记录 [lastStatusRefreshAtMs]，UI 可以显示「刚刚更新」
     *   4. 无论值是否变化都强制发布一次快照，确保订阅方至少收到一次
     */
    fun refreshServerStatus() {
        if (!_statusRefreshInFlight.compareAndSet(false, true)) return
        _isRefreshingStatus.value = true
        viewModelScope.launch(Dispatchers.IO) {
            var sampled = false
            try {
                invalidateResourceCaches()
                sampled = collectServerResourcesOnce(force = true)
                if (sampled) {
                    // 运行中的服务器异步回复 list/tps，由现有解析器更新在线人数与 TPS。
                    val rt = repo.termuxRuntime
                    if (rt.isMcRunning()) {
                        rt.sendCommand("list")
                        if (isActiveCorePaper()) {
                            rt.sendCommand("tps")
                        }
                    }
                }
            } finally {
                _statusRefreshInFlight.set(false)
                _isRefreshingStatus.value = false
                // 本轮没采到（并发锁被占 / 探针失败）就不记录「刚刚更新」——
                // 否则 UI 显示刚刷新过、拿到的却还是上一次的旧快照。
                if (sampled) _lastStatusRefreshAtMs.value = System.currentTimeMillis()
            }
        }
    }

    /**
     * 采集一次服务器资源快照。
     *
     * @param force 为 true 时忽略「值未变化就不发布」的优化，强制发布一次。
     *        手动刷新走这条路：即使用户在缓存窗口内点击，UI 也必定收到新快照，
     *        并且能通过 [ServerResourceStats.sampledAtMs] 观察到刷新确实生效了。
     */
    /**
     * @param includeDirectorySize 是否允许执行服务端目录的递归求和（最昂贵的一步）。
     *        界面不在前台时传 false：缓存命中（同一目录）就沿用旧值，
     *        缓存不可用则返回 null（未知）而不是去扫一遍。
     */
    private fun collectServerResourcesOnce(force: Boolean = false, includeDirectorySize: Boolean = true): Boolean {
        // 已在采集则跳过本轮：并发采集会重复全目录扫描并互相覆盖采样缓存。
        if (!resourceCollectionMutex.tryLock()) return false
        try {
            val cfg = config.value
            val active = cfg.installedCores.find { it.name == cfg.activeCoreName }
            val dir = active?.let { File(repo.termuxRuntime.installer.rootDir, "home/servers/${it.dirName}") }
            val available = dir?.takeIf { it.exists() }?.let { android.os.StatFs(it.path).availableBytes }
            val now = System.currentTimeMillis()
            val running = repo.termuxRuntime.isMcRunning()
            val directoryBytes = dir?.takeIf { running && it.exists() }?.let { serverDir ->
                val sameDir = cachedResourceDir == serverDir.path
                val stale = now - cachedDirectoryBytesAtMs >= 60_000L
                when {
                    // 允许扫描且（换了目录 或 缓存过期）：真正遍历一次
                    includeDirectorySize && (!sameDir || stale) -> {
                        cachedResourceDir = serverDir.path
                        cachedDirectoryBytes = serverDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                        cachedDirectoryBytesAtMs = now
                        cachedDirectoryBytes
                    }
                    // 同目录的缓存可直接沿用（后台路径主要走这里）
                    sameDir -> cachedDirectoryBytes
                    // 后台且无可用缓存：宁可显示未知，也不去扫盘
                    else -> null
                }
            }
            // 进程内存采样遍历 /proc，降频到 30s（运行中才需要），窗口内沿用旧值。
            if (running && now - cachedMemoryAtMs >= 30_000L) {
                cachedMemoryAtMs = now
                cachedMemoryMb = repo.termuxRuntime.mcProcessMemoryMb()
            }
            val effectiveMemory = cachedMemoryMb.takeIf { running && it > 0L }
            // 服务端进程自身 CPU（支持多线程超过 100%），不可用或未运行时为 null。
            val cpu = if (running) repo.termuxRuntime.mcProcessCpuPercent() else null
            // 整机 CPU 总使用率：与 running 解耦，服务端未运行时也要有读数；
            // 首次调用只建立基线返回 null，下一次周期采样即拿到真实值。
            val deviceCpu = repo.termuxRuntime.deviceCpuPercent()
            if (running && now - cachedJavaAvailableAtMs >= 60_000L) {
                cachedJavaAvailable = repo.termuxRuntime.isJavaInstalled(cfg.selectedJavaVersion, cfg.selectedJavaVendor)
                cachedJavaAvailableAtMs = now
            }
            val javaAvailable = cachedJavaAvailable ?: true
            val newStats = ServerResourceStats(
                processMemoryMb = effectiveMemory,
                cpuPercent = cpu,
                deviceCpuPercent = deviceCpu,
                availableBytes = available,
                directoryBytes = directoryBytes,
                javaAvailable = javaAvailable,
                sampledAtMs = now
            )
            // 值无变化时不发布新快照：避免每 15 秒无谓的重组（后台游戏挤压 CPU 时尤其明显）。
            // 但 [force]（手动刷新）时必须无条件发布，否则用户会觉得「点了没反应」。
            val prev = _serverResources.value
            if (force ||
                prev.processMemoryMb != newStats.processMemoryMb ||
                prev.cpuPercent != newStats.cpuPercent ||
                prev.deviceCpuPercent != newStats.deviceCpuPercent ||
                prev.availableBytes != newStats.availableBytes ||
                prev.directoryBytes != newStats.directoryBytes ||
                prev.javaAvailable != newStats.javaAvailable
            ) {
                _serverResources.value = newStats
            }
            repo.updateServerState { state ->
                if (!force &&
                    state.usedMemoryMb == (effectiveMemory ?: 0L) &&
                    state.cpuPercent == cpu
                ) {
                    state
                } else {
                    state.copy(usedMemoryMb = effectiveMemory ?: 0L, cpuPercent = cpu)
                }
            }
            return true
        } catch (e: Exception) {
            // Keep the last known snapshot when Android or PRoot denies a probe.
            return false
        } finally {
            resourceCollectionMutex.unlock()
        }
    }

    private val _diagnosticReport = MutableStateFlow(DiagnosticReport())
    val diagnosticReport: StateFlow<DiagnosticReport> = _diagnosticReport.asStateFlow()
    private val _isDiagnosing = MutableStateFlow(false)
    val isDiagnosing: StateFlow<Boolean> = _isDiagnosing.asStateFlow()
    private val _isRepairingRuntime = MutableStateFlow(false)
    val isRepairingRuntime: StateFlow<Boolean> = _isRepairingRuntime.asStateFlow()

    fun runDiagnostics() {
        if (_isDiagnosing.value || _isRepairingRuntime.value) return
        _isDiagnosing.value = true
        viewModelScope.launch {
            try {
                _diagnosticReport.value = withContext(Dispatchers.IO) { buildDiagnosticReport() }
            } catch (e: Exception) {
                _diagnosticReport.value = DiagnosticReport(
                    checks = listOf(DiagnosticCheck(
                        "scan", str(R.string.diag_check_scan), e.message ?: str(R.string.diag_check_scan_fail), DiagnosticStatus.Failed
                    )),
                    generatedAtMs = System.currentTimeMillis()
                )
            } finally {
                _isDiagnosing.value = false
            }
        }
    }

    fun safeRepairRuntime() {
        if (_isRepairingRuntime.value || _isDiagnosing.value) return
        _isRepairingRuntime.value = true
        _diagnosticReport.value = _diagnosticReport.value.copy(isRunning = true)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val cfg = config.value
                    val core = cfg.installedCores.find { it.name == cfg.activeCoreName }
                    repo.termuxRuntime.fixRootfsPermissions()
                    repo.termuxRuntime.repairInstalledCommands()
                    if (repo.termuxRuntime.isReady()) {
                        repo.termuxRuntime.autoRepairRuntime(
                            cfg.selectedJavaVersion,
                            core?.let { repo.termuxRuntime.needsFontRuntime(it.core) } == true
                        )
                    }
                }
                refreshJava()
                refreshDependencies()
                refreshPhp()
                _messageFlow.tryEmit(str(R.string.msg_repair_done))
            } catch (e: Exception) {
                _errorFlow.tryEmit(e.message ?: str(R.string.msg_repair_fail))
            } finally {
                _isRepairingRuntime.value = false
                runDiagnostics()
            }
        }
    }

    private fun buildDiagnosticReport(): DiagnosticReport {
        val cfg = config.value
        val runtime = repo.termuxRuntime
        val active = cfg.installedCores.find { it.name == cfg.activeCoreName }
        val checks = mutableListOf<DiagnosticCheck>()
        val runtimeReady = runtime.isReady()
        checks += DiagnosticCheck(
            "runtime", str(R.string.diag_check_runtime),
            if (runtimeReady) str(R.string.diag_check_runtime_ok) else str(R.string.diag_check_runtime_bad),
            if (runtimeReady) DiagnosticStatus.Pass else DiagnosticStatus.Failed, !runtimeReady
        )
        val javaCheck = runtime.javaRuntimeDiagnostic(cfg.selectedJavaVersion, cfg.selectedJavaVendor)
        checks += DiagnosticCheck(
            "java", str(R.string.diag_check_java, cfg.selectedJavaVersion.displayName), javaCheck.second,
            if (javaCheck.first) DiagnosticStatus.Pass else DiagnosticStatus.Failed, !javaCheck.first
        )
        // PRoot 容器分层状态：把「rootfs 没下好」与「容器好了但 Java 没装」区分开。
        // 只报一句「未安装」会把两种代价完全不同的情况混为一谈 ——
        // 前者要重新跑一次几百兆的下载，后者只需重装一个 apt 包。
        ProotDistro.entries.forEach { distro ->
            val state = runtime.prootContainerState(distro)
            val title = str(R.string.diag_check_proot_container, distro.displayName)
            if (!state.rootfsPresent && !state.readyMarkerPresent) {
                // 完全没碰过这个发行版：不算问题，避免刷出两条无意义的红色告警
                checks += DiagnosticCheck(
                    "proot-${distro.name}", title,
                    str(R.string.diag_proot_none),
                    DiagnosticStatus.NotApplicable
                )
            } else {
                // installedVersions 非空时 joinToString 必然非空，无需再兜 "—"
                val versions = state.installedVersions.joinToString(" / ") { it.displayName }
                val (detail, status) = when {
                    !state.shellUsable -> str(R.string.diag_proot_shell_broken) to DiagnosticStatus.Failed
                    state.installedVersions.isEmpty() -> str(R.string.diag_proot_ready_empty) to DiagnosticStatus.Warning
                    else -> str(R.string.diag_proot_ready_installed, versions) to DiagnosticStatus.Pass
                }
                checks += DiagnosticCheck(
                    "proot-${distro.name}", title, detail, status,
                    // 只有「解压不完整」值得走一键修复；缺 Java 属于用户选择，不该替他装
                    repairable = !state.shellUsable && state.rootfsPresent
                )
            }
        }
        if (active?.core == ServerCore.NeoForge && cfg.selectedJavaVersion != JavaVersion.Java8) {
            checks += DiagnosticCheck(
                "native-jna", str(R.string.diag_check_native),
                str(R.string.diag_check_native_detail),
                DiagnosticStatus.Warning
            )
        }
        // proot 是所有来宾容器链路的必需命令：Termux 的 Java 8 与 Proot 的全部版本
        // 都要靠它启动。早先这里只判 `Termux && Java8`，于是选中「PRoot 容器」来源时
        // prootRequired 为 false —— 诊断会报告「无需 proot」并标为 NotApplicable，
        // 而 proot 正是这条链路赖以启动的东西。容器起不来时这条会把用户引向错误方向。
        // 统一走路由谓词，将来新增来宾组合也不会漏判。
        val prootRequired = isProotGuestRuntime(cfg.selectedJavaVendor, cfg.selectedJavaVersion)
        val downloaderRequired = active?.core?.needsInstaller == true
        val requiredCommandsReady = (!prootRequired || runtime.isCommandInstalled("proot")) &&
            (!downloaderRequired || runtime.isCommandInstalled("wget"))
        checks += DiagnosticCheck(
            "commands", str(R.string.diag_check_commands),
            when {
                !prootRequired && !downloaderRequired -> str(R.string.diag_check_commands_none)
                requiredCommandsReady -> str(R.string.diag_check_commands_ready, if (prootRequired) "proot" else "wget")
                else -> str(R.string.diag_check_commands_missing2, listOfNotNull(if (prootRequired) "proot" else null, if (downloaderRequired) "wget" else null).joinToString(" / "))
            },
            if (!prootRequired && !downloaderRequired) DiagnosticStatus.NotApplicable else if (requiredCommandsReady) DiagnosticStatus.Pass else DiagnosticStatus.Warning,
            !requiredCommandsReady
        )
        if (active == null) {
            checks += DiagnosticCheck(
                "core", str(R.string.diag_check_core), str(R.string.diag_check_core_none), DiagnosticStatus.Warning
            )
            checks += DiagnosticCheck(
                "storage", str(R.string.diag_check_storage), str(R.string.diag_check_storage_none), DiagnosticStatus.NotApplicable
            )
            checks += DiagnosticCheck(
                "port", str(R.string.diag_check_port), str(R.string.diag_check_port_none), DiagnosticStatus.NotApplicable
            )
        } else {
            val dir = File(runtime.installer.rootDir, "home/servers/${active.dirName}")
            val entryName = active.serverFile?.trim()?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() }
            val jar = entryName?.let { File(dir, it) }
            val dirReady = dir.isDirectory && dir.canRead() && dir.canWrite()
            checks += DiagnosticCheck(
                "core", str(R.string.diag_check_core),
                if (!dirReady) str(R.string.diag_check_core_dir_bad) else if (jar?.isFile != true) str(R.string.diag_check_core_entry_missing, entryName ?: "未指定") else str(R.string.diag_check_core_ok, active.name),
                if (dirReady && jar?.isFile == true) DiagnosticStatus.Pass else DiagnosticStatus.Failed
            )
            val fontNeeded = runtime.needsFontRuntime(active.core)
            // 用接收 vendor 的版本：Proot / Termux Java 8 的字体在容器内，
            // 只看宿主文件会把「容器缺字体」误报成「已就绪」。
            val fontsReady = fontNeeded &&
                runtime.fontRuntimeDiagnostic(cfg.selectedJavaVendor, cfg.selectedJavaVersion)
            checks += DiagnosticCheck(
                "fonts", str(R.string.diag_check_fonts),
                when {
                    !fontNeeded -> str(R.string.diag_check_fonts_not_needed)
                    fontsReady -> str(R.string.diag_check_fonts_ready)
                    else -> str(R.string.diag_check_fonts_need)
                },
                when {
                    !fontNeeded -> DiagnosticStatus.NotApplicable
                    fontsReady -> DiagnosticStatus.Pass
                    else -> DiagnosticStatus.Warning
                }, fontNeeded
            )
            val available = try { if (dir.exists()) android.os.StatFs(dir.path).availableBytes else null } catch (_: Exception) { null }
            checks += DiagnosticCheck(
                "storage", str(R.string.diag_check_storage),
                available?.let { str(R.string.diag_check_storage_avail, formatDiagnosticBytes(it)) } ?: str(R.string.diag_check_storage_unreadable),
                if (available != null) DiagnosticStatus.Pass else DiagnosticStatus.Warning
            )
            val running = runtime.isMcRunning()
            val bedrock = active.core == ServerCore.PowerNukkitX
            // 端口取真源：配置文件里的 server-port（PNX 为 pnx.yml 的 settings.port），
            // 未生成时退回 localPort。与地址栏同源，避免「诊断说 19132、服务端其实在 25562」。
            val port = ServerPort.resolve(_serverProperties.value["server-port"], active?.core, cfg.localPort)
            val portOk = if (running) {
                if (bedrock) probeBedrockUdpPort(port) else probeLoopbackPort(port)
            } else false
            val runningSince = repo.serverState.value.runningSinceMs
            val starting = running && runningSince > 0L &&
                android.os.SystemClock.elapsedRealtime() - runningSince < 15_000L
            checks += DiagnosticCheck(
                "port", str(R.string.diag_check_port_title, if (bedrock) "UDP" else "TCP", port),
                when { !running -> str(R.string.diag_check_port_not_running); portOk -> str(R.string.diag_check_port_ok, port); starting -> str(R.string.diag_check_port_starting); else -> str(R.string.diag_check_port_bad2) },
                when { !running -> DiagnosticStatus.NotApplicable; portOk -> DiagnosticStatus.Pass; else -> DiagnosticStatus.Warning }
            )
            val crash = (crashReportManager.listCrashReports() + crashReportManager.listNativeCrashReports(active.dirName))
                .maxByOrNull { it.createdTime }
            checks += DiagnosticCheck(
                "crash", str(R.string.diag_check_crash),
                crash?.let { str(R.string.diag_check_crash_found, it.fileName) } ?: str(R.string.diag_check_crash_none),
                if (crash == null) DiagnosticStatus.Pass else DiagnosticStatus.Warning
            )
        }
        return DiagnosticReport(checks, System.currentTimeMillis(), repo.termuxRuntime.isMcRunning())
    }

    private fun probeLoopbackPort(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1_500); true }
    } catch (_: Exception) { false }

    private fun probeBedrockUdpPort(port: Int): Boolean = try {
        DatagramSocket().use { socket ->
            socket.soTimeout = 1_500
            val packet = ByteBuffer.allocate(33).order(ByteOrder.BIG_ENDIAN)
                .put(0x01.toByte())
                .putLong(System.currentTimeMillis())
                .put(byteArrayOf(0x00, 0xff.toByte(), 0xff.toByte(), 0x00, 0xfe.toByte(), 0xfe.toByte(), 0xfe.toByte(), 0xfe.toByte(), 0xfd.toByte(), 0xfd.toByte(), 0xfd.toByte(), 0xfd.toByte(), 0x12, 0x34, 0x56, 0x78))
                .putLong(System.nanoTime()).array()
            socket.send(DatagramPacket(packet, packet.size, java.net.InetAddress.getByName("127.0.0.1"), port))
            val response = DatagramPacket(ByteArray(2048), 2048)
            socket.receive(response)
            response.length > 0
        }
    } catch (_: Exception) { false }

    private fun formatDiagnosticBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1_024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1_024.0)
        else -> "$bytes B"
    }

    /** 刷新局域网 IP：在 IO 线程遍历网络接口，取第一个非 loopback 的 IPv4 地址 */
    fun refreshLanIp() {
        viewModelScope.launch(Dispatchers.IO) {
            _lanIp.value = queryLanIp()
        }
    }

    private fun queryLanIp(): String {
        return try {
            NetworkInterface.getNetworkInterfaces()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress }
                .map { it.hostAddress ?: "" }
                .firstOrNull { it.isNotEmpty() }
                ?: "--"
        } catch (e: Exception) {
            "--"
        }
    }

    /** 一键复制服务器连接地址到剪贴板，格式：IP:端口 */
    fun copyServerAddress(context: android.content.Context) {
        val ip = _lanIp.value
        // 端口取真源（服务端配置文件里的 server-port），不读 config.localPort ——
        // 后者在配置文件被外部改动时可能停在旧值。
        // 用 [realServerPort] 而不是 [serverPort] 这个 StateFlow：复制出去的是要发给别人的
        // 地址，不能受订阅状态影响而拿到缓存里的旧端口。
        val port = realServerPort()
        val address = if (ip == "--") {
            _errorFlow.tryEmit(str(R.string.s187))
            return
        } else {
            "$ip:$port"
        }
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MC Server", address))
        _messageFlow.tryEmit(str(R.string.s188, address))
    }

    companion object {
        private const val MAX_LOG_LINES = 1500
        private const val LOG_FLUSH_MS = 200L

        /**
         * 无订阅者时日志刷新循环的空闲周期。
         *
         * 200ms 周期只在界面真正可见时才有意义；后台时降为 2s，
         * 让待机期间的 CPU 唤醒次数从每秒 10 次降到 0.5 次（与原主循环的降频策略一致）。
         */
        private const val IDLE_LOG_FLUSH_MS = 2000L
        private const val CONSOLE_PREVIEW_FLUSH_MS = 750L
        // 阶段推进采用了"只增不减"策略，因此不再需要连续命中计数（PHASE_CONFIRM_HITS）
        // 与单步回退容差（DOUBLE_STEP_TOLERANCE）：前者会因为
        // "启动 Java / 启动网络"这类只出现一行的阶段被整段吞掉，
        // 后者只是把震荡的幅度调小而不是消除震荡。
        // 噪声（异常堆栈）改由 McConfig.isStackFrameLine 在判定源头拦掉。
        // 玩家数/TPS 等状态正则已收敛到 ConsoleLineParser，此处不再重复持有。
        // 配置写入的合并窗口已移除：改由 ServerRepository 的原子读改写根治竞态。

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return McApplication.get().container.viewModelFactory.create(modelClass)
            }
        }
    }

    /**
     * 解析 MC 控制台输出，提取 TPS / 玩家数 / 启动状态等运行时信息。
     * 使用快速前缀检查避免不必要的正则匹配。
     */
    private var lastJavaCompatibilityWarning: String? = null

    private fun parseConsoleLine(line: String) {
        try {
            // 状态类信号统一由共享解析器提取（正则只跑一次，与前台服务共用同一份实现）。
            val signals = ConsoleLineParser.parse(line)
            val startupPhase = signals.startupPhase
            updateStartupPhaseFromLog(startupPhase)
            val requiredJava = signals.requiredJavaVersion
            if (requiredJava != null) {
                val selected = when (config.value.selectedJavaVersion) {
                    JavaVersion.Java8 -> 8; JavaVersion.Java11 -> 11; JavaVersion.Java17 -> 17; JavaVersion.Java21 -> 21; JavaVersion.Java25 -> 25
                }
                if (selected < requiredJava) {
                    val core = config.value.installedCores.firstOrNull { it.name == config.value.activeCoreName }?.core?.displayName ?: str(R.string.diag_core_unknown_name)
                    val warning = str(R.string.msg_java_warning, core, selected, requiredJava)
                    if (warning != lastJavaCompatibilityWarning) {
                        lastJavaCompatibilityWarning = warning
                        _errorFlow.tryEmit(str(R.string.err_java_unsupported, warning))
                    }
                }
            }
            // 玩家进出服需要副作用（名单、记录、通知），仍在本层处理；
            // 但只在日志确实提到进出服时才做正则，避免每行都跑。
            // 玩家事件与「服务端就绪」原本是互斥的 when 分支：某一行只要命中了玩家进出服，
            // 就不会再去触发就绪。下面用 handled 标记维持这一语义，避免行为漂移。
            var handled = false
            when {
                playerManager.extractPowerNukkitPlayerEvent(line) != null -> {
                    val (name, joined) = playerManager.extractPowerNukkitPlayerEvent(line)!!
                    repo.updateServerState { it.copy(onlinePlayers = (it.onlinePlayers + if (joined) 1 else -1).coerceAtLeast(0)) }
                    if (joined) addOnlinePlayer(name) else removeOnlinePlayer(name)
                    recordPlayerEvent(name, if (joined) "进服" else "离服")
                    notifyPlayerEvent(name, joined)
                    handled = true
                }
                line.contains("joined the game") -> {
                    // 仅当提取到真实玩家名（日志前缀 + 合法名字）时才计数与记录，避免聊天消息误报
                    playerManager.extractPlayerName(line)?.let { name ->
                        repo.updateServerState {
                            it.copy(onlinePlayers = (it.onlinePlayers + 1).coerceAtLeast(0))
                        }
                        addOnlinePlayer(name)
                        recordPlayerEvent(name, "进服")
                        notifyPlayerEvent(name, true)
                    }
                    handled = true
                }
                line.contains("left the game") -> {
                    playerManager.extractPlayerName(line)?.let { name ->
                        repo.updateServerState {
                            it.copy(onlinePlayers = (it.onlinePlayers - 1).coerceAtLeast(0))
                        }
                        removeOnlinePlayer(name)
                        recordPlayerEvent(name, "离服")
                        notifyPlayerEvent(name, false)
                    }
                    handled = true
                }
            }
            if (!handled && signals.players != null) {
                val p = signals.players
                repo.updateServerState { it.copy(onlinePlayers = p.online, maxPlayers = p.max) }
                // A valid list response proves the server is already accepting commands.
                markServerReady()
                // 全量校正在线玩家名单（list 命令响应）
                playerManager.parseOnlinePlayers(line)?.let { names ->
                    _onlinePlayerNames.value = names
                }
                handled = true
            }
            if (!handled && signals.tps != null) {
                val value = signals.tps
                val health = ((value / 20.0) * 100).toInt().coerceIn(0, 100)
                repo.updateServerState {
                    it.copy(tps = value, healthPercent = health,
                        maxMemoryMb = config.value.maxHeapMb.toLong())
                }
                handled = true
            }
            if (!handled && startupPhase == StartupPhase.Ready) markServerReady()
        } catch (e: Exception) {
            // 解析失败不影响正常运行
        }
    }

    /** 将控制台明确的就绪信号统一转换为运行中。 */
    private fun markServerReady() {
        var becameReady = false
        repo.updateServerState { state ->
            if (!state.isRunning) return@updateServerState state
            becameReady = state.runningSinceMs == 0L
            state.copy(
                tps = 20.0,
                healthPercent = 100,
                maxMemoryMb = config.value.maxHeapMb.toLong(),
                startupPhase = StartupPhase.Ready,
                runningSinceMs = state.runningSinceMs.takeIf { it > 0L }
                    ?: SystemClock.elapsedRealtime()
            )
        }
        // 启动完成时主动请求一次 list，全量校正在线玩家名单。
        if (becameReady && repo.termuxRuntime.isMcRunning()) playerManager.requestOnlineList()
        if (becameReady) {
            ServerEventNotifier.notify(
                app,
                str(R.string.notif_server_started_title),
                str(R.string.notif_server_started_text),
                ServerEventNotifier.ID_STARTED, 1
            )
        }
    }

    /** 玩家进出服系统通知（仅“全部”档发布）。 */
    private fun notifyPlayerEvent(name: String, joined: Boolean) {
        ServerEventNotifier.notify(
            app,
            if (joined) str(R.string.notif_player_joined_title, name) else str(R.string.notif_player_left_title, name),
            if (joined) str(R.string.notif_player_joined_text) else str(R.string.notif_player_left_text),
            ServerEventNotifier.ID_PLAYER, 2
        )
    }

    /**
     * 在后台日志解析线程推进启动阶段，UI 不扫描原始日志。
     *
     * ## 唯一的不变量：**进度只增不减**
     *
     * 这条规则是花了四个版本才收敛到的最简形式。前几版分别试过
     * 「下载锁」「只许前进」「越档回退需连续证据」「下载单向」……
     * 全都是**局部补丁**，每修一处又漏一处。用户真实日志把这一层彻底暴露了。
     *
     * 最典型的两个症状（用户原话）：
     *   - **"下载核心与加载核心两个左右跳"**：日志里 `Loading Minecraft …`
     *     这类核心探针行**夹在下载段中间**，随后下载又继续，于是进度条
     *     在 40% ↔ 62% 之间反复横跳。任何"如实跟随每一行"的实现都必然这样。
     *   - **"直接进度条完成"**：`Starting Minecraft server on *:25565`（启动网络）
     *     与 `Preparing level`（创建世界）这类行**各自只出现一两次**。
     *     之前用"连续 N 行才采纳"的防抖门槛去滤噪声，结果是把这两整档
     *     直接吞掉，进度条从 62% 一步蹦到 100%。
     *
     * 所以这里反过来做：**噪声在判定源头就掐掉，阶段推进则不做防抖**。
     *   - 源头：`McConfig.isStackFrameLine` 把异常堆栈行（形如
     *     `at net.fabricmc.installer.ServerLauncher.main(...)`）判为"不参与阶段"。
     *     这些行含 `server` / `libraries` / `minecraft` 之类的路径片段，
     *     曾被误判成"下载依赖"，把进度从 82% 拖回 40%。
     *   - 推进：只要算出来的阶段比当前**高**，立刻采纳；平级或倒退一律忽略。
     *
     * 至于 App 自身的引导日志（`[bootstrap] …` → 准备环境 7%）：它会在
     * `[startMc] java 路径` 之后才打印，如实跟随同样会把进度条拽回 7%。
     * "只增不减"顺手把这个问题一并解决了。
     *
     * 因此这里不再需要连续命中计数（PHASE_CONFIRM_HITS）与单步回退容差
     * （DOUBLE_STEP_TOLERANCE），两者均已删除。
     */
    private fun updateStartupPhaseFromLog(phase: StartupPhase?) {
        phase ?: return
        repo.updateServerState { state ->
            if (!state.isRunning || state.startupPhase == StartupPhase.Ready) return@updateServerState state

            // ══════════════════════════════════════════════════════════════
            // 唯一的不变量：**进度只增不减**。理由详见本函数的 KDoc。
            // ══════════════════════════════════════════════════════════════
            // - 低于或等于当前档位 → 忽略（这就是"不回退"，同时消灭 40%↔62% 横跳）
            // - 高于当前档位       → 立即采纳（不设防抖，所以"只出现 1 行"的
            //                       启动 Java / 启动网络 不会被吞，进度条不再
            //                       从 62% 直接蹦到 100%）
            if (phase.progress <= state.startupPhase.progress) return@updateServerState state

            state.copy(startupPhase = phase)
        }
    }

    /**
     * 修改配置。
     *
     * 竞态的根治：读改写与落盘排队合并为 [ServerRepository.updateAndSaveConfig]，
     * 基准是仓库内的权威内存态，而不是 DataStore 流的最新发射值。
     * 因此连续快速修改会正确叠加，不再依赖"上次修改是否还在 debounce 窗口内"这一时间假设
     * （旧实现用 800ms pendingConfig 窗口绕过该问题，窗口边界仍是脆弱假设）。
     *
     * 注意这里**不能**拆成「先 updateConfig 拿到快照，再异步 saveConfig(快照)」：
     * 落盘入队发生在另一个调度器上，中间插进来的另一次修改会先入队，
     * CONFLATED 通道最终保留的可能反而是更旧的那份。
     */
    fun updateConfig(transform: (McConfig) -> McConfig) {
        try {
            repo.updateAndSaveConfig(transform)
        } catch (e: Exception) {
            _errorFlow.tryEmit(str(R.string.s191, e.message))
        }
    }

    /** 修改服务器信息。名称/版本号仅影响显示；核心类型会改变应用对该服务器的处理方式。返回 null 表示成功。 */
    fun updateServerDisplayInfo(oldName: String, newName: String, newVersion: String, newCore: com.mineserve.mobile.data.ServerCore? = null): String? {
        val name = newName.trim()
        val version = newVersion.trim()
        if (name.isEmpty()) return "服务器名称不能为空"
        val current = config.value
        val target = current.installedCores.firstOrNull { it.name == oldName }
            ?: return "未找到服务器：$oldName"
        if (current.installedCores.any { it.name == name && it.name != oldName }) {
            return "已存在同名服务器：$name"
        }
        updateConfig { cfg ->
            cfg.copy(
                installedCores = cfg.installedCores.map {
                    if (it.name == oldName) it.copy(name = name, version = version, core = newCore ?: it.core) else it
                },
                activeCoreName = if (cfg.activeCoreName == oldName) name else cfg.activeCoreName
            )
        }
        return null
    }

    @Volatile private var scanningServers = false

    /**
     * 扫描 servers 目录，自动登记未注册的服务器文件夹。
     *
     * 覆盖 MT 管理器等外部方式直接复制进目录的场景；识别不出核心时按
     * 「导入/未知核心」登记（降低识别门槛）。
     *
     * ## 为什么要跳过空目录
     * 早先只判断「目录名是否已登记」，不判断目录内容，结果**空目录也会被
     * 登记成一个核心**。空目录的常见来源是删除流程中断或历史残留，
     * 把它登记进列表只会让用户看到一个点进去什么都没有的幽灵条目，
     * 而且它会带着 `Unknown` 核心类型、参与「当前选中核心」等逻辑。
     *
     * 现在明确区分两种情况：
     *   - **空目录**（含只有隐藏文件/子目录壳）：不登记，仅记日志。用户可用
     *     文件管理页自行清理；App 不擅自删用户目录。
     *   - **有文件但识别不出核心**：仍然登记为「未知核心」。这可能是用户
     *     手动塞进去的整合包，贸然忽略反而会让人以为导入失败。
     */
    fun scanUnregisteredServers() {
        if (scanningServers) return
        scanningServers = true
        viewModelScope.launch {
            try {
                val dirs = withContext(Dispatchers.IO) {
                    repo.termuxRuntime.serversDir.listFiles { f -> f.isDirectory }
                        ?.sortedBy { it.name.lowercase() } ?: emptyList()
                }
                if (dirs.isEmpty()) return@launch
                val cfg = repo.currentConfig()
                val known = cfg.installedCores.map { it.dirName }.toHashSet()
                val fresh = dirs.filter { it.name !in known }
                if (fresh.isEmpty()) return@launch

                // 空目录：跳过登记。用递归判断，避免「只剩一层空壳子目录」也通过。
                val (emptyDirs, realDirs) = withContext(Dispatchers.IO) {
                    fresh.partition { dir -> dir.isEffectivelyEmpty() }
                }
                if (emptyDirs.isNotEmpty()) {
                    termux3Log(
                        "扫描跳过 ${emptyDirs.size} 个空目录（未登记）: " +
                            emptyDirs.joinToString(", ") { it.name }
                    )
                }
                if (realDirs.isEmpty()) {
                    _messageFlow.tryEmit(
                        str(R.string.msg_scan_skipped_empty, emptyDirs.size)
                    )
                    return@launch
                }

                val registered = realDirs.map { dir ->
                    val detected = withContext(Dispatchers.IO) {
                        com.mineserve.mobile.server.ServerCoreDetector.detect(dir)
                    }
                    com.mineserve.mobile.data.InstalledCore(
                        name = dir.name,
                        core = detected.core ?: com.mineserve.mobile.data.ServerCore.Unknown,
                        version = detected.version ?: str(R.string.ver_imported),
                        dirName = dir.name,
                        serverFile = detected.serverFile
                    )
                }
                val registeredNames = registered.map { it.dirName }.toHashSet()
                repo.updateAndSaveConfig { current ->
                    // 二次去重：登记期间可能有别处已登记同名目录，避免出现重复条目
                    val merged = current.installedCores +
                        registered.filter { it.dirName !in current.installedCores.map { c -> c.dirName } }
                    current.copy(installedCores = merged)
                }
                _messageFlow.tryEmit(str(R.string.msg_scan_registered, registeredNames.size))
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s191, e.message))
            } finally {
                scanningServers = false
            }
        }
    }

    /**
     * 目录是否「实质为空」：忽略 `.` 开头的隐藏条目后没有任何子项。
     *
     * 递归深度设上限，避免异常深/循环软链接把扫描拖死。
     */
    private fun java.io.File.isEffectivelyEmpty(depth: Int = 0): Boolean {
        if (depth > 4) return false        // 太深则按「有内容」处理，宁可登记也不错杀
        val children = listFiles() ?: return true
        val visible = children.filterNot { it.name.startsWith(".") }
        if (visible.isEmpty()) return true
        // 只有目录壳（没有文件）时也视为空
        return visible.all { it.isDirectory && it.isEffectivelyEmpty(depth + 1) }
    }

    fun refreshJava() {
        // 刻意不检查 isBootstrapped：FCL 来源的 JRE 与 Termux 环境无关，
        // 环境还没初始化时它也可能已经装好了（装了就该显示出来）。
        viewModelScope.launch(Dispatchers.IO) {
            _installedJava.value = repo.termuxRuntime.installedJavaRuntimes()
        }
    }

    fun refreshDependencies() {
        if (!isBootstrapped.value) return
        viewModelScope.launch(Dispatchers.IO) {
            val steps = repo.termuxRuntime.installedDependencySteps()
            repo.updateServerState { state ->
                state.copy(installSteps = steps)
            }
        }
    }

    // 运行环境的选中统一走 setJavaRuntime（来源 + 版本一起写），
    // 不再提供「只改版本、来源沿用」的入口 —— 那会写出「FCL 来源 + Java 8」
    // 这种上游根本不存在的组合。

    // ── PocketMine-MP 专用 PHP 运行时 ─────────────────────────────
    //
    // PMMP 用 PHP 运行（不是 Java），首次启动时按需下载。
    // 依赖管理页的「PHP 版本选择」与启动控制卡片的运行环境列表共用这一份状态。

    private val _installedPhp = MutableStateFlow<Set<PhpVersion>>(emptySet())
    val installedPhp: StateFlow<Set<PhpVersion>> = _installedPhp.asStateFlow()

    /** 当前所选 PHP 版本（来自 config，配置未加载时回退默认） */
    val selectedPhpVersion: StateFlow<PhpVersion> = config
        .map { it.selectedPhpVersion }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, PhpVersion.Default)

    /** 当前 PHP 版本是否已安装 */
    val phpInstalled: StateFlow<Boolean> = combine(_installedPhp, selectedPhpVersion) { installed, selected ->
        selected in installed
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun refreshPhp() {
        if (!isBootstrapped.value) { _installedPhp.value = emptySet(); return }
        viewModelScope.launch(Dispatchers.IO) {
            _installedPhp.value = repo.termuxRuntime.installedPhpVersions()
        }
    }

    /** 选择 PHP 版本（依赖管理页与启动控制卡片共用） */
    fun setPhpVersion(version: PhpVersion) = updateConfig { it.copy(selectedPhpVersion = version) }

    /** 安装指定 PHP 运行环境（~10MB，含 PMMP 必需扩展） */
    fun installPhp(version: PhpVersion = config.value.selectedPhpVersion) {
        if (!isBootstrapped.value || _isInstalling.value) return
        if (repo.termuxRuntime.isMcRunning()) {
            _errorFlow.tryEmit(str(R.string.msg_php_running))
            return
        }
        _isInstalling.value = true
        _javaOperation.value = str(R.string.msg_php_op_installing)
        viewModelScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    repo.termuxRuntime.installPhp(version) { line -> repo.termuxRuntime.emitLog(line) }
                }
                refreshPhp()
                if (ok) _messageFlow.tryEmit(str(R.string.msg_php_installed, version.displayName))
                else _errorFlow.tryEmit(str(R.string.err_php_install_fail, version.displayName))
            } catch (e: Exception) {
                _errorFlow.tryEmit(e.message ?: str(R.string.err_php_install_fail, version.displayName))
            } finally {
                _javaOperation.value = null
                _isInstalling.value = false
            }
        }
    }

    /** 卸载指定 PHP 运行环境（直接删除对应目录，PocketMine 下次启动会重新下载） */
    fun deletePhp(version: PhpVersion = config.value.selectedPhpVersion) {
        if (!isBootstrapped.value || _isInstalling.value) return
        _isInstalling.value = true
        _javaOperation.value = str(R.string.msg_php_op_deleting)
        viewModelScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { repo.termuxRuntime.deletePhp(version) }
                refreshPhp()
                if (ok) _messageFlow.tryEmit(str(R.string.msg_php_deleted, version.displayName))
                else _errorFlow.tryEmit(str(R.string.err_php_delete_fail, version.displayName))
            } catch (e: Exception) {
                _errorFlow.tryEmit(e.message ?: str(R.string.err_php_delete_fail, version.displayName))
            } finally {
                _javaOperation.value = null
                _isInstalling.value = false
            }
        }
    }

    // ── 运行模式（Java / PHP）─────────────────────────────────────
    //
    // 运行模式完全由用户决定，不再被核心类型反向纠正。
    // 用户选了 PHP，就用 PHP；选了 Java，就用 Java —— 即便当前核心是 PocketMine
    // （反之亦然）。启动时若所选运行时与该核心不匹配，由启动流程给出明确报错，
    // 而不是悄悄替用户改掉选择。

    /**
     * 切换运行模式。
     *
     * 立即写入 config（UI 会自动重组刷新运行环境按钮与版本列表，无需刷新页面），
     * 并确保当前所选版本属于该模式 —— 不属于时回退到该模式的默认版本。
     */
    fun setRuntimeKind(kind: RuntimeKind) {
        updateConfig { cfg ->
            when (kind) {
                RuntimeKind.Java -> {
                    // Java 模式下 selectedJavaVersion 本身就是 JavaVersion，天然合法，
                    // 只需保证 runtimeKind 落位。
                    cfg.copy(runtimeKind = RuntimeKind.Java)
                }
                RuntimeKind.Php -> {
                    val valid = cfg.selectedPhpVersion.takeIf { it in PhpVersion.entries } ?: PhpVersion.Default
                    cfg.copy(runtimeKind = RuntimeKind.Php, selectedPhpVersion = valid)
                }
            }
        }
    }

    /**
     * 切换运行环境（来源 + 版本），见 [JavaRuntime]。
     *
     * 只影响下一次启动时用哪套 JDK、挂不挂兼容层；不改动任何已装 JDK，对**正在运行**
     * 的服务端也没有影响（config 只在启动那一刻读一次），UI 侧只是在运行中禁用入口，
     * 避免用户误以为切换会立刻生效。
     *
     * 来源与版本必须一次写入：分两次 set 会因为 config 落盘的 300ms debounce 撞在一起，
     * 第二次读到的还是旧快照（与 [applyRuntimeSelection] 注释里那个竞态同源）。
     */
    fun setJavaRuntime(runtime: JavaRuntime) = updateConfig { cfg ->
        // Proot 来源的版本必须落在**当前发行版**提供的范围内。界面上点不到
        // 置灰项（见 JavaRuntimeGroupList），这里是第二道保险：
        // 写进 config 的组合一旦装不上，用户要到启动失败时才会发现。
        val version = if (runtime.vendor == JavaVendor.Proot) {
            runtime.version.takeIf { it in cfg.prootDistro.availableJavaVersions }
                ?: cfg.prootDistro.availableJavaVersions.firstOrNull()
                ?: runtime.version
        } else {
            runtime.version
        }
        cfg.copy(selectedJavaVendor = runtime.vendor, selectedJavaVersion = version)
    }

    /**
     * 兼容层依赖的 `libipv6fix.so` 是否真的随 APK 打包。
     *
     * 预加载库不落盘到 Termux 目录，只能看 nativeLibraryDir 下有没有这个文件。
     * Termux 来源的 IPv6 能力完全取决于它 —— 启动日志里那行「兼容层已注入」
     * 与这里的判断是同一件事，缺库时不会静默变成「什么都没发生」。
     */
    val ipv6CompatReady: Boolean
        get() = File(
            app.applicationInfo.nativeLibraryDir,
            NativeLibraryBundler.IPV6_FIX_LIB
        ).isFile

    /**
     * 一次性写入运行模式与对应版本。
     *
     * **必须用这个方法来设置运行环境**：`setRuntimeKind` 与 `setJavaRuntime`/`setPhpVersion`
     * 是两次独立的 read-modify-write，而 config 的落盘带 300ms debounce，
     * 第二次调用读到的 config.value 仍是旧快照，会把第一次写入的 runtimeKind 覆盖掉
     * （表现为「切到 PHP 后按钮又变回 Java」）。这里合并为单次 copy，杜绝竞态。
     *
     * @param prootDistro 仅在选择 Proot 来源时有意义；为 null 表示沿用当前发行版。
     *   发行版与版本必须一起校验：focal 不提供 Java 21、jammy 不提供 Java 8，
     *   分开写会出现「先切成 focal，版本还停在 21」这种中间态。
     */
    fun applyRuntimeSelection(
        kind: RuntimeKind,
        javaRuntime: JavaRuntime,
        phpVersion: PhpVersion,
        prootDistro: ProotDistro? = null
    ) {
        updateConfig { cfg ->
            val vendor = javaRuntime.vendor
            val distro = prootDistro ?: cfg.prootDistro
            // Proot 来源的可用版本由**发行版**决定，而不是来源的并集：
            // 并集里同时有 8（仅 focal）与 21（仅 jammy），照并集放行会写出
            // 一个该发行版装不上的版本，用户点安装才看到 apt 报「找不到软件包」。
            val allowed = if (vendor == JavaVendor.Proot) {
                distro.availableJavaVersions
            } else {
                vendor.versions
            }
            // 版本必须落在允许列表内，否则会出现「FCL 来源 + Java 8」这种
            // 不存在的组合（FCL 只发布 17/21/25）。
            val version = javaRuntime.version.takeIf { it in allowed }
                ?: allowed.firstOrNull()
                ?: JavaRuntime.Default.version
            cfg.copy(
                runtimeKind = kind,
                selectedJavaVendor = vendor,
                selectedJavaVersion = version,
                prootDistro = distro,
                selectedPhpVersion = phpVersion.takeIf { it in PhpVersion.entries } ?: PhpVersion.Default
            )
        }
    }

    /**
     * 校验运行模式与当前核心是否匹配，返回不匹配时的提示文案，匹配则返回 null。
     *
     * 只用于启动前提醒，**不修改**用户的任何选择 —— 用户选择优先。
     */
    fun runtimeKindMismatchMessage(): String? {
        val cfg = config.value
        val core = cfg.installedCores.firstOrNull { it.name == cfg.activeCoreName } ?: return null
        val required = if (core.core == ServerCore.PocketMine) RuntimeKind.Php else RuntimeKind.Java
        if (cfg.runtimeKind == required) return null
        val requiredLabel = when (required) {
            RuntimeKind.Java -> str(R.string.dash_runtime_java)
            RuntimeKind.Php -> str(R.string.dash_runtime_php)
        }
        return str(R.string.err_runtime_kind_mismatch, core.name, requiredLabel)
    }

    fun setJavaCardAtBottom(atBottom: Boolean) =
        updateConfig { it.copy(javaCardAtBottom = atBottom) }

    /**
     * 安装运行环境。
     *
     * 刻意不要求 Termux 环境已初始化：FCL 来源的 JRE 是 Android 原生二进制，
     * 与 Termux 前缀无关，环境没装好时也完全可以先把它下载下来（Termux 来源的
     * 安装会自己在运行时里抛「环境未初始化」）。
     *
     * 装完直接把它设为当前选用 —— 用户点安装就是为了用它，装完还要再点一次
     * 选中是没必要的往返；启动前本就会校验 java 是否找得到，所以不存在
     * 「选中了一个装不上的东西」的风险。
     */
    fun installJava(runtime: JavaRuntime) {
        if (_isInstalling.value) return
        _isInstalling.value = true
        _javaInFlight.value = runtime
        _javaOperation.value = str(R.string.msg_java_op_installing, runtime.displayName)
        viewModelScope.launch {
            try {
                val ok = repo.termuxRuntime.installJava(
                    version = runtime.version,
                    vendor = runtime.vendor,
                    // PRoot 来源必须带上当前发行版：装哪个发行版里的 JDK
                    // 由用户的选择决定，不能用默认值顶替。
                    distro = config.value.prootDistro,
                    onProgress = { percent ->
                        // FCL 按下载字节数回调；PRoot 按阶段回调（rootfs 下载解压 0–60、
                        // 容器内 apt 安装 60–100）。两者都是数分钟级的长任务，
                        // 不显示进度会让用户以为卡死了。Termux 宿主 apt 无细粒度进度。
                        _javaOperation.value =
                            str(R.string.msg_java_op_installing, runtime.displayName) + " $percent%"
                    }
                )
                if (ok) {
                    // 用原子读改写并落盘：安装耗时长，期间用户其他配置修改不能被这份旧快照冲掉
                    repo.updateAndSaveConfig {
                        it.copy(selectedJavaVendor = runtime.vendor, selectedJavaVersion = runtime.version)
                    }
                    refreshJava()
                    _messageFlow.tryEmit(str(R.string.msg_java_installed, runtime.displayName))
                } else _errorFlow.tryEmit(str(R.string.err_java_install_fail, runtime.displayName))
            } catch (e: Exception) { _errorFlow.tryEmit(e.message ?: str(R.string.msg_java_install_fail2)) }
            finally {
                _javaInFlight.value = null
                _javaOperation.value = null
                _isInstalling.value = false
            }
        }
    }

    /**
     * 重装**指定的这一份**运行环境：先删掉它，再重新安装。
     *
     * 刻意按 (来源, 版本) 精确重装，而不是沿用旧 UI 那个「清空全部 Java 再装回」
     * 的粗粒度路径 —— 现在有两套来源、五加三共八种组合，一次重装把别的版本一起
     * 删掉，与依赖页那句「操作作用于所选版本」的提示自相矛盾，也白白浪费用户
     * 已经下好的 37–62MB。Termux 那套仍然要求环境已初始化；FCL 那套与 Termux
     * 前缀无关，环境没装好也能重装。
     */
    fun reinstallJava(runtime: JavaRuntime) {
        if (_isInstalling.value) return
        if (runtime.vendor == JavaVendor.Termux && !isBootstrapped.value) return
        if (repo.termuxRuntime.isMcRunning()) {
            _errorFlow.tryEmit(str(R.string.err_java_running))
            return
        }
        if (runtime !in _installedJava.value) {
            // 没有可删的旧文件，直接走安装
            installJava(runtime)
            return
        }
        _isInstalling.value = true
        _javaInFlight.value = runtime
        _javaOperation.value = str(R.string.msg_java_op_reinstall)
        viewModelScope.launch {
            var removed = false
            try {
                removed = withContext(Dispatchers.IO) {
                    repo.termuxRuntime.deleteJava(runtime.version, runtime.vendor)
                }
                if (removed) refreshJava()
                else _errorFlow.tryEmit(str(R.string.err_java_reinstall_fail))
            } catch (e: Exception) {
                _errorFlow.tryEmit(e.message ?: str(R.string.msg_java_reinstall_fail2))
            } finally {
                // 先解除删除阶段的占用，installJava 会把三个状态重新置位
                _isInstalling.value = false
                _javaInFlight.value = null
                _javaOperation.value = null
            }
            if (removed) installJava(runtime)
        }
    }

    /** FTP 文件管理根目录（含全部服务器与快照） */
    fun ftpRootDir(): java.io.File = repo.termuxRuntime.serversDir.parentFile
        ?: java.io.File(app.filesDir, "home")

    /** 卸载指定运行环境（Proot 走容器内 apt purge，只删该版本；其余来源删文件） */
    fun deleteJava(runtime: JavaRuntime) {
        if (_isInstalling.value) return
        _javaOperation.value = str(R.string.msg_java_op_deleting, runtime.displayName)
        viewModelScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    repo.termuxRuntime.deleteJava(runtime.version, runtime.vendor)
                }
                refreshJava()
                if (ok) _messageFlow.tryEmit(str(R.string.msg_java_deleted, runtime.displayName))
                else _errorFlow.tryEmit(str(R.string.err_java_delete_fail, runtime.displayName))
            } catch (e: Exception) {
                _errorFlow.tryEmit(e.message ?: str(R.string.err_java_delete_fail, runtime.displayName))
            } finally {
                _javaOperation.value = null
            }
        }
    }

    /** 容器占用空间（字节），供删除确认弹窗显示「可回收多少」。 */
    fun prootContainerSizeBytes(distro: ProotDistro): Long =
        repo.termuxRuntime.prootContainerSizeBytes(distro)

    /**
     * 各发行版容器占用空间（字节），**异步**填充。
     *
     * 为什么不直接在 Composable 里调 [prootContainerSizeBytes]：
     * 它内部是 `walkTopDown()` 全量递归 rootfs（数万个文件），属于重量级 IO。
     * 旧代码在 EnvManagerScreen 里同步调用，且 remember 的 key 含每次刷新
     * 都会换新实例的 `installed`，于是进页面、切发行版、每次刷新 Java 状态
     * 都会重新扫一遍整个 rootfs —— 这就是依赖管理页卡顿的主因。
     *
     * 现在改为：进页面时在 IO 线程算一次，结果放进 StateFlow 供 UI 读取。
     */
    private val _prootContainerSizes = MutableStateFlow<Map<ProotDistro, Long>>(emptyMap())
    val prootContainerSizes: StateFlow<Map<ProotDistro, Long>> = _prootContainerSizes.asStateFlow()

    /** 刷新所有发行版容器占用（IO 线程，供依赖管理页展示「删除可回收多少空间」）。 */
    fun refreshProotContainerSizes() {
        viewModelScope.launch(Dispatchers.IO) {
            val sizes = ProotDistro.entries.associateWith { distro ->
                repo.termuxRuntime.prootContainerSizeBytes(distro)
            }
            _prootContainerSizes.value = sizes
        }
    }

    /**
     * 删除整个 PRoot 容器（连同容器内已装的全部 Java 版本）。
     *
     * 与 [deleteJava] 是**两个不同粒度**的操作，所以入口也分开：
     * - [deleteJava]：卸载某一个 Java 版本，容器与其余版本保留（apt purge）
     * - 本方法：回收整个容器（直接删 rootfs），用于磁盘空间吃紧时一次性清干净
     *
     * 若混成一个按钮，用户点「卸载 Java 17」却把 Java 8/11 一起清掉，
     * 那是数据静默丢失级别的问题。
     */
    fun deleteProotContainer(distro: ProotDistro) {
        if (_isInstalling.value) return
        if (repo.termuxRuntime.isMcRunning()) {
            _errorFlow.tryEmit(str(R.string.err_java_running))
            return
        }
        _javaOperation.value = str(R.string.msg_java_op_deleting, distro.displayName)
        viewModelScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    repo.termuxRuntime.deleteProotContainer(distro)
                }
                refreshJava()
                if (ok) _messageFlow.tryEmit(str(R.string.msg_proot_container_deleted, distro.displayName))
                else _errorFlow.tryEmit(str(R.string.err_proot_container_delete_fail, distro.displayName))
            } catch (e: Exception) {
                _errorFlow.tryEmit(e.message ?: str(R.string.err_proot_container_delete_fail, distro.displayName))
            } finally {
                _javaOperation.value = null
            }
        }
    }

    /** 卸载依赖（直接删除 wget/frpc/rclone/proot 文件，不动 Termux 环境） */
    fun deleteDependencies() {
        if (!isBootstrapped.value || _isInstalling.value) return
        _isInstalling.value = true
        viewModelScope.launch {
            try {
                val removed = withContext(Dispatchers.IO) { repo.termuxRuntime.deleteDependencies() }
                refreshDependencies()
                _messageFlow.tryEmit(str(R.string.msg_deps_deleted, removed))
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s195, e.message))
            } finally {
                _isInstalling.value = false
            }
        }
    }

    /** 重装依赖 = 先删除再安装 */
    fun reinstallDependencies() {
        if (!isBootstrapped.value || _isInstalling.value) return
        _isInstalling.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repo.termuxRuntime.deleteDependencies() }
            } catch (_: Exception) {}
            _isInstalling.value = false
            installDependencies()
        }
    }

    fun selectCore(core: ServerCore) = updateConfig {
        // 基岩版核心（PNX/PocketMine/Allay）走 UDP，默认端口 19132；Java 系 25565。
        // 迁移规则见 [ServerPort.migrateOnCoreSwitch]：只在 Java 系 ↔ 基岩系之间
        // 对调**默认值**，用户自定义端口（如 25562）原样保留。
        // 旧实现是单向的 `if (localPort == 25565) 19132 else localPort`：
        // 切到基岩后 19132 再也回不去，而当时界面上已没有端口编辑框可以改回来。
        val port = ServerPort.migrateOnCoreSwitch(it.localPort, it.selectedCore, core)
        if (core.isBedrock) it.copy(selectedCore = core, mcVersion = "latest", localPort = port)
        else it.copy(selectedCore = core, localPort = port)
    }

    fun setMcVersion(version: String) {
        val trimmed = version.trim()
        val core = config.value.selectedCore
        val normalized = MinecraftVersionNormalizer.forCore(core, trimmed)
        updateConfig { it.copy(mcVersion = normalized) }
        // 版本纠正提示移到 updateConfig 之后：底层 _config.updateAndGet 是 CAS 循环，
        // transform 失败会被重放，副作用写在里面会在并发写配置时重复弹窗，也破坏重放语义。
        if (normalized != trimmed) {
            _messageFlow.tryEmit(str(R.string.msg_version_corrected, core.displayName, trimmed, normalized))
        }
    }

    fun setLocalPort(port: Int) = updateConfig { it.copy(localPort = port) }
    fun setDomain(d: String) = updateConfig { it.copy(customDomain = d) }
    fun setMaxHeap(mb: Int) = updateConfig { it.copy(maxHeapMb = mb) }
    fun setAutoRestart(v: Boolean) = updateConfig { it.copy(autoRestartOnCrash = v) }

    /** 跳过「启动中」阶段进度展示（见 [McConfig.skipStartupProgress]）。 */
    fun setSkipStartupProgress(v: Boolean) = updateConfig { it.copy(skipStartupProgress = v) }

    // ── 定时开/停服与停止备份 ───────────────────────────────────

    fun setDailyStartEnabled(v: Boolean) = updateScheduleConfig { it.copy(dailyStartEnabled = v) }

    fun setDailyStartTime(hour: Int, minute: Int) = updateScheduleConfig {
        it.copy(dailyStartHour = hour.coerceIn(0, 23), dailyStartMinute = minute.coerceIn(0, 59))
    }

    fun setDailyStopEnabled(v: Boolean) = updateScheduleConfig { it.copy(dailyStopEnabled = v) }

    fun setDailyStopTime(hour: Int, minute: Int) = updateScheduleConfig {
        it.copy(dailyStopHour = hour.coerceIn(0, 23), dailyStopMinute = minute.coerceIn(0, 59))
    }

    fun setStopAutoBackup(v: Boolean) = updateConfig { it.copy(stopAutoBackup = v) }

    // ── 自动化定时任务（更多 → 自动化） ──────────────────────────

    /**
     * 变更任务列表并落盘、重排下一次闹钟。
     *
     * 未排期的启用任务在此统一补上 [AutomationTask.nextRunMs]，保证调度器拿到的
     * 就是落盘后的最终值（与 [updateScheduleConfig] 同样的读改写策略，避免陈旧快照覆盖）。
     */
    private fun updateAutomationTasks(transform: (List<AutomationTask>) -> List<AutomationTask>) {
        val updated = try {
            repo.updateAndSaveConfig { config ->
                config.copy(
                    automationTasks = AutomationScheduler.withNextRunsAssigned(transform(config.automationTasks))
                )
            }
        } catch (e: Exception) {
            _errorFlow.tryEmit(str(R.string.s191, e.message))
            return
        }
        AutomationScheduler.register(app, updated.automationTasks)
    }

    /** 新增任务（重置排期，由更新逻辑重算）。 */
    fun addAutomationTask(task: AutomationTask) =
        updateAutomationTasks { it + task.copy(nextRunMs = 0L) }

    /** 覆盖编辑一条任务（重置排期）。 */
    fun updateAutomationTask(task: AutomationTask) = updateAutomationTasks { list ->
        list.map { if (it.id == task.id) task.copy(nextRunMs = 0L) else it }
    }

    fun deleteAutomationTask(id: String) = updateAutomationTasks { list -> list.filterNot { it.id == id } }

    /** 启用/停用任务；重新启用时重置排期。 */
    fun setAutomationTaskEnabled(id: String, enabled: Boolean) = updateAutomationTasks { list ->
        list.map { if (it.id == id) it.copy(enabled = enabled, nextRunMs = if (enabled) 0L else it.nextRunMs) else it }
    }

    /** 手动立即执行一次任务（交由前台服务在自身作用域内完成）。 */
    fun runAutomationTaskNow(id: String) {
        com.mineserve.mobile.service.McForegroundService.enqueueAutomationTask(app, id)
    }

    /** 变更定时计划时同步保存并重注册下一次闹钟。 */
    private fun updateScheduleConfig(transform: (McConfig) -> McConfig) {
        // 基于仓库权威内存态读改写并原子入队，不用 UI 侧 config.value（可能是陈旧快照，
        // 写回会覆盖用户此间做的其他修改）。ScheduleManager 用的是落盘后的最终值。
        val updated = try {
            repo.updateAndSaveConfig(transform)
        } catch (e: Exception) {
            _errorFlow.tryEmit(str(R.string.s191, e.message))
            return
        }
        com.mineserve.mobile.data.ScheduleManager.register(app, updated)
    }
    fun setKeepWifiLock(v: Boolean) = updateConfig { it.copy(keepWifiLock = v) }
    fun setKeepCpuWakelock(v: Boolean) = updateConfig { it.copy(keepCpuWakelock = v) }
    fun setKeepScreenOnWhileRunning(v: Boolean) = updateConfig { it.copy(keepScreenOnWhileRunning = v) }
    fun setKeepStatusOverlay(v: Boolean) = updateConfig { it.copy(keepStatusOverlay = v) }
    fun setAptMirror(mirror: com.mineserve.mobile.data.AptMirror) =
        viewModelScope.launch {
            val effective = if (mirror == com.mineserve.mobile.data.AptMirror.Official) {
                com.mineserve.mobile.data.AptMirror.Tuna
            } else mirror
            // 原子读改写 + 落盘，不用 config.value 陈旧快照整份写回（会覆盖并发的其他修改）
            repo.updateAndSaveConfig { it.copy(aptMirror = effective) }
            withContext(Dispatchers.IO) { repo.termuxRuntime.setAptMirror(effective) }
        }
    fun setDownloadMirror(mirror: com.mineserve.mobile.data.DownloadMirror) {
        updateConfig { it.copy(downloadMirror = mirror) }
        // 立即应用到 bootstrap 下载器（下次下载生效；App 重启后由 McApplication 从配置恢复）
        repo.termuxRuntime.setDownloadMirror(mirror)
    }

    /** 「依次尝试下载源」的运行状态与结果文本，供设置页展示。 */
    private val _mirrorTestRunning = MutableStateFlow(false)
    val mirrorTestRunning: StateFlow<Boolean> = _mirrorTestRunning.asStateFlow()

    private val _mirrorTestResult = MutableStateFlow<String?>(null)
    val mirrorTestResult: StateFlow<String?> = _mirrorTestResult.asStateFlow()

    private fun appendMirrorTest(line: String) {
        val cur = _mirrorTestResult.value
        _mirrorTestResult.value = if (cur.isNullOrEmpty()) line else "$cur\n$line"
    }

    /**
     * 「依次尝试下载源」：按顺序逐个探测各下载源，直到找到**第一个可用**的为止。
     *
     * 测试通过后自动把该源设为当前下载源（下次 bootstrap 下载生效）。过程中每测一个源
     * 就把结果追加到 [mirrorTestResult]；全部失败时提示检查网络。
     */
    fun testDownloadSources() {
        if (_mirrorTestRunning.value) return
        _mirrorTestRunning.value = true
        _mirrorTestResult.value = null
        viewModelScope.launch {
            try {
                val installer = repo.termuxRuntime.installer
                val idx = installer.probeMirrors { line -> appendMirrorTest(line) }
                val mirror = if (idx >= 0) installer.mirrorAt(idx) else null
                if (mirror != null) {
                    updateConfig { it.copy(downloadMirror = mirror) }
                    repo.termuxRuntime.setDownloadMirror(mirror)
                    appendMirrorTest("已选用可用下载源：${mirror.displayName}")
                } else {
                    appendMirrorTest("全部下载源均不可用，请检查网络后重试")
                }
            } catch (e: Exception) {
                appendMirrorTest("测试失败：${e.message ?: "未知错误"}")
            } finally {
                _mirrorTestRunning.value = false
            }
        }
    }

    /** 多线程下载是否启用（内置下载模块开关，默认启用） */
    fun isMultiThreadDownloadEnabled(): Boolean = com.mineserve.mobile.data.DownloadPrefs.isEnabled()

    /** 下载线程数 */
    fun downloadThreadCount(): Int = com.mineserve.mobile.data.DownloadPrefs.threadCount()

    /** 切换多线程下载开关 */
    fun setMultiThreadDownloadEnabled(enabled: Boolean) {
        com.mineserve.mobile.data.DownloadPrefs.setEnabled(enabled)
        _messageFlow.tryEmit(if (enabled) str(R.string.msg_mt_enabled) else str(R.string.msg_mt_disabled))
    }

    /** 设置下载线程数 */
    fun setDownloadThreadCount(count: Int) {
        com.mineserve.mobile.data.DownloadPrefs.setThreadCount(count)
    }

    fun installDependencies() {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit(str(R.string.s192))
            return
        }
        if (_isInstalling.value) return
        _isInstalling.value = true
        viewModelScope.launch {
            try {
                val ok = controller.installDependencies { speedBps ->
                    _installSpeed.value = speedBps
                }
                _installSpeed.value = 0L
                if (ok) {
                    refreshDependencies()
                    _messageFlow.tryEmit(str(R.string.s193))
                } else {
                    _errorFlow.tryEmit(str(R.string.s194))
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s195, e.message))
            } finally {
                _isInstalling.value = false
            }
        }
    }

    /**
     * 「停止」请求是否仍在飞行中。
     *
     * 优雅停止可能要等十几秒（服务端要落盘），期间界面必须能区分
     * 「还没停完」与「停完了但仍显示运行」。旧实现把这个状态放在 Composable 的
     * 局部 `remember` 里，靠 isRunning 翻转来复位；现在停止可能**失败**（超时不强杀），
     * isRunning 不翻转，局部态会永远卡住 —— 因此上提到 VM。
     */
    private val _stopInFlight = MutableStateFlow(false)
    val stopInFlight: StateFlow<Boolean> = _stopInFlight.asStateFlow()

    /** 「强制停止」请求是否仍在飞行中（强杀很快，仅用于禁用重复点击）。 */
    private val _forceStopInFlight = MutableStateFlow(false)
    val forceStopInFlight: StateFlow<Boolean> = _forceStopInFlight.asStateFlow()

    fun startServer() {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit(str(R.string.s192))
            return
        }
        // 运行模式由用户选择，启动前只做一致性校验：不匹配就报错，绝不替用户改选择。
        runtimeKindMismatchMessage()?.let {
            _errorFlow.tryEmit(it)
            return
        }
        viewModelScope.launch {
            try {
                lastJavaCompatibilityWarning = null
                // 阶段推进已是"只增不减"的无状态规则，无需清理任何防抖残留；
                // 新一次启动的阶段由 updateServerState 里显式重置为 PreparingEnvironment。
                _lastRunLog.value = ""
                // 启动前重读配置文件里的端口：用户可能刚在文件管理器里改过 server-port
                // （或 pnx.yml 的 settings.port），而 [McConfig.localPort] 还停在旧值。
                // 不重读的话，PNX / Allay 的启动链路会拿旧值**覆写**配置文件里的新端口，
                // 等于用户改了白改；地址栏与穿透也会指向服务端并不监听的端口。
                refreshServerPropertiesNow()
                val current = config.value
                // localPort 是「镜像字段」，以配置文件为准（[realServerPort] 现算，不经缓存）。
                val detectedPort = realServerPort()
                if (detectedPort != current.localPort) {
                    repo.termuxRuntime.emitLog(
                        "[startMc] 配置文件端口 $detectedPort 与本地记录 ${current.localPort} 不一致，本次按配置文件启动"
                    )
                }
                val startConfig = current.copy(localPort = detectedPort)
                repo.updateServerState {
                    it.copy(
                        isRunning = true,
                        runningSinceMs = 0L,
                        startupPhase = StartupPhase.PreparingEnvironment
                    )
                }
                _messageFlow.tryEmit(str(R.string.s196))
                controller.start(startConfig, userInitiated = true)
                // 准备阶段被用户取消时 start() 会直接返回（此时状态已被复位为未运行），
                // 不能再把保活服务拉起来——否则会出现「没在跑却常驻前台服务」。
                if (repo.serverState.value.isRunning) {
                    startKeepAliveService()
                }
            } catch (e: Exception) {
                repo.updateServerState {
                    it.copy(isRunning = false, runningSinceMs = 0L, startupPhase = StartupPhase.Failed)
                }
                repo.termuxRuntime.emitLog("[startMc] 启动失败: ${e.message}")
                showStartupFailureReport(e)
                _errorFlow.tryEmit(str(R.string.s197, e.message))
            }
        }
    }

    /**
     * 停止服务器（优雅停止，不强杀）。
     *
     * 停止期间 `stopInFlight = true`，界面显示「停止中…」；若服务端在宽限期内
     * 仍未退出，则**保持运行态**并提示用户可改用「强制停止」——
     * 是否要承担未落盘数据的风险由用户决定（见 [forceStopServer]）。
     */
    fun stopServer() {
        if (_stopInFlight.value) return
        _stopInFlight.value = true
        viewModelScope.launch {
            try {
                val stopped = controller.stopGracefully()
                if (!stopped) {
                    _errorFlow.tryEmit(str(R.string.dash_stop_timeout_hint))
                    return@launch
                }
                _messageFlow.tryEmit(str(R.string.s198))
                if (config.value.stopAutoBackup) {
                    val dirName = activeDirName() ?: return@launch
                    val path = withContext(Dispatchers.IO) {
                        backupManager.backupWorldToExternal(
                            dirName, BackupManager.BackupOrigin.Automatic, config.value.maxSnapshots
                        )
                    }
                    if (path != null) _messageFlow.tryEmit(str(R.string.msg_stop_backup_done))
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s199, e.message))
            } finally {
                _stopInFlight.value = false
            }
        }
    }

    /**
     * 强制停止服务器：立即结束进程，不等待世界落盘。
     *
     * 调用方必须先拿到用户的显式确认（UI 上是警告弹窗）。这里不再做二次询问，
     * 因为「用户已经点过确认」这件事只有 UI 知道。
     */
    fun forceStopServer() {
        if (_forceStopInFlight.value) return
        _forceStopInFlight.value = true
        viewModelScope.launch {
            try {
                controller.forceStop()
                _messageFlow.tryEmit(str(R.string.dash_force_stop_done))
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s199, e.message))
            } finally {
                _forceStopInFlight.value = false
                // 强杀也会让正在等待的优雅停止立刻结束（进程没了），复位以免界面卡在「停止中…」
                _stopInFlight.value = false
            }
        }
    }

    // ── 内网穿透 ──────────────────────────────────────────────
    //
    // FRP 与 STUN 是两条相互独立的通道（见 TunnelLane）：各自启停、可同时运行。
    // 因此这里没有「统一的一键启动」，而是四条分开的入口。

    // ── FRP 配置（可保存多份，同一平台也可存多个令牌） ────────

    /** 新增或就地更新一份 FRP 配置（按 id 判定是新增还是编辑）。 */
    fun saveFrpProfile(profile: FrpProfile) {
        updateConfig { cfg ->
            val exists = cfg.frpProfiles.any { it.id == profile.id }
            val list = if (exists) {
                cfg.frpProfiles.map { if (it.id == profile.id) profile else it }
            } else {
                cfg.frpProfiles + profile
            }
            cfg.copy(
                frpProfiles = list,
                // 第一份配置自动成为当前配置：省掉「存完还得自己再选一次」这一步。
                activeFrpProfileId = cfg.activeFrpProfileId.ifBlank { profile.id }
            )
        }
    }

    /** 删除一份 FRP 配置；删的正好是当前配置时自动切到剩下的第一份。 */
    fun deleteFrpProfile(id: String) {
        updateConfig { cfg ->
            val left = cfg.frpProfiles.filterNot { it.id == id }
            cfg.copy(
                frpProfiles = left,
                activeFrpProfileId =
                    if (cfg.activeFrpProfileId == id) left.firstOrNull()?.id.orEmpty()
                    else cfg.activeFrpProfileId
            )
        }
    }

    /** 切换当前 FRP 配置。 */
    fun setActiveFrpProfile(id: String) = updateConfig { it.copy(activeFrpProfileId = id) }

    /** 当前生效的 FRP 配置；列表为空时返回 null。 */
    fun activeFrpProfile(): FrpProfile? = FrpProfiles.active(config.value)

    // ── 启停 ─────────────────────────────────────────────────

    fun startFrp() {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit(str(R.string.s192))
            return
        }
        if (frpState.value.status == TunnelStatus.Starting) {
            _errorFlow.tryEmit(str(R.string.s200))
            return
        }
        val cfg = config.value
        val profile = FrpProfiles.active(cfg)
        if (profile == null) {
            _errorFlow.tryEmit(str(R.string.msg_frp_profile_required))
            return
        }
        FrpProfiles.validationError(profile)?.let {
            _errorFlow.tryEmit(it)
            return
        }
        viewModelScope.launch {
            try {
                // 先把端口真源读回来：用户可能刚在配置文件里改了端口，
                // 不重读的话下面拿到的还是上一次的值（隧道会打到旧端口）。
                refreshServerPropertiesNow()
                val realPort = realServerPort()

                // 投影：把选中的配置摊进后端认识的老字段，后端一行都不用改。
                var runtime = FrpProfiles.applyTo(profile, cfg)
                if (profile.kind == FrpProfileKind.CustomToml) {
                    // 自建 frp 的转发端口写在用户粘的那份文本里，多半还停在改端口之前的旧值。
                    // 这里对齐**运行期副本**（保存的原文不动），否则隧道通了玩家也进不来。
                    val aligned = FrpLocalTarget.align(runtime.frpConfigText, realPort)
                    if (aligned.changed > 0) {
                        runtime = runtime.copy(frpConfigText = aligned.text)
                        tunnelManager.note(
                            "配置里的转发端口 ${aligned.from} 不是游戏真实端口 $realPort，" +
                                "本次已按 $realPort 启动（你的配置原文没有被改）"
                        )
                    }
                }
                tunnelManager.startFrp(runtime)
                reportStart(frpState.value)
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s206, e.message))
            }
        }
    }

    fun stopFrp() {
        viewModelScope.launch {
            try {
                tunnelManager.stopFrp()
                _messageFlow.tryEmit(str(R.string.s207))
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s208, e.message))
            }
        }
    }

    fun startStun() {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit(str(R.string.s192))
            return
        }
        if (stunState.value.status == TunnelStatus.Starting) {
            _errorFlow.tryEmit(str(R.string.s200))
            return
        }
        viewModelScope.launch {
            try {
                // 端口真源：StunTunnelBackend 默认取 config.localPort（镜像字段），
                // 这里显式覆盖成真源 serverPort。否则配置文件被外部改动后，
                // STUN 会把玩家流量转到服务端并未监听的旧端口。
                //
                // 真源必须在取用**之前**读回来，而且要现算（[realServerPort]）：
                // 用户刚改完端口就来开穿透时，读与不读就是「能进」和「connection refused」的区别。
                //
                // tunnelType 也一并写成 Stun：界面上已经没有「穿透方式」单选了，
                // 不写的话它会停在默认的 Frp，配置本身自相矛盾（虽然
                // TunnelManager 现在按通道取后端、不再依赖这个字段）。
                refreshServerPropertiesNow()
                val targetPort = realServerPort()
                tunnelManager.startStun(
                    config.value.copy(localPort = targetPort, tunnelType = TunnelType.Stun)
                )
                reportStart(stunState.value)
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s206, e.message))
            }
        }
    }

    fun stopStun() {
        viewModelScope.launch {
            try {
                tunnelManager.stopStun()
                _messageFlow.tryEmit(str(R.string.s207))
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s208, e.message))
            }
        }
    }

    /**
     * 启动调用返回后的统一提示。
     *
     * 注意：STUN 后端的 start() 是**异步就绪**的 —— 它立即返回，实际探测在后台
     * 进行，就绪/失败稍后经状态流反映。所以这里只能提示「正在启动」，
     * 不能按 Running 判定成功，否则网络慢时会误报失败。结果由界面观察状态流得出。
     */
    private fun reportStart(st: TunnelState) {
        when (st.status) {
            TunnelStatus.Running -> {
                val url = st.publicUrl
                _messageFlow.tryEmit(
                    if (url.isNotBlank()) str(R.string.msg_tunnel_started_url, url)
                    else str(R.string.msg_tunnel_started)
                )
            }
            TunnelStatus.Starting -> _messageFlow.tryEmit(str(R.string.s203))
            TunnelStatus.Failed -> _errorFlow.tryEmit(str(R.string.s204, st.errorMessage))
            else -> _messageFlow.tryEmit(str(R.string.s205))
        }
    }

    /** 复制任意公网地址（FRP 与 STUN 卡片各自传入自己那条通道的地址）。 */
    fun copyPublicUrl(context: android.content.Context, url: String) {
        if (url.isBlank()) {
            _errorFlow.tryEmit(str(R.string.s209))
            return
        }
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Tunnel URL", url))
        _messageFlow.tryEmit(str(R.string.s210, url))
    }

    fun sendCommand(line: String) {
        try {
            controller.sendCommand(line)
        } catch (e: Exception) {
            _errorFlow.tryEmit(str(R.string.s211, e.message))
        }
    }

    // ── 插件管理（新版） ──────────────────────────────────────────

    /** 插件下载进度（结构复用 DownloadProgress） */
    data class PluginDownloadProgress(
        val pluginId: String,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = -1L,
        val speedBytesPerSec: Long = 0L
    ) {
        val percent: Int
            get() = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
        val speedText: String
            get() = when {
                speedBytesPerSec <= 0 -> "—"
                speedBytesPerSec >= 1024 * 1024 ->
                    String.format(java.util.Locale.US, "%.2f MB/s", speedBytesPerSec / (1024.0 * 1024.0))
                speedBytesPerSec >= 1024 ->
                    String.format(java.util.Locale.US, "%.1f KB/s", speedBytesPerSec / 1024.0)
                else -> "$speedBytesPerSec B/s"
            }
    }

    /** 精选插件库（直接暴露给 UI） */
    val curatedPlugins: List<PluginManager.CuratedPlugin>
        get() = pluginManager.curatedPlugins

    /** 真实已安装插件列表 */
    private val _installedPlugins = MutableStateFlow<List<PluginManager.InstalledPlugin>>(emptyList())
    val installedPlugins: StateFlow<List<PluginManager.InstalledPlugin>> = _installedPlugins.asStateFlow()

    /** 真实已安装模组列表 */
    private val _mods = MutableStateFlow<List<PluginManager.ModEntry>>(emptyList())
    val mods: StateFlow<List<PluginManager.ModEntry>> = _mods.asStateFlow()

    val curatedMods: List<PluginManager.CuratedMod>
        get() = pluginManager.curatedMods

    /** 当前正在下载的插件 id → 进度 */
    private val _pluginDownloadProgress = MutableStateFlow<Map<String, PluginDownloadProgress>>(emptyMap())
    val pluginDownloadProgress: StateFlow<Map<String, PluginDownloadProgress>> = _pluginDownloadProgress.asStateFlow()

    /** 是否有任意插件正在下载 */
    val isPluginDownloading: StateFlow<Boolean> = _pluginDownloadProgress
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 扫描当前核心的 plugins 目录 */
    fun refreshInstalledPlugins() {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit(str(R.string.s192))
            return
        }
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit(str(R.string.s212))
            return
        }
        viewModelScope.launch {
            try {
                _installedPlugins.value = pluginManager.scan(dirName)
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s213, e.message))
            }
        }
    }

    // ── 模组管理 ──────────────────────────────────────────────────

    /** 扫描当前核心的 mods 目录 */
    fun refreshMods() {
        if (!isBootstrapped.value) return
        val dirName = activeDirName() ?: return
        viewModelScope.launch {
            try {
                _mods.value = withContext(Dispatchers.IO) { pluginManager.readMods(dirName) }
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s214, e.message))
            }
        }
    }

    /** 切换模组启用状态 */
    fun toggleModEnabled(fileName: String) {
        // VM 侧兜底：UI 前台虽已拦截，但其他调用入口不能绕过——运行中改 mods 目录会导致本轮加载状态错乱
        if (!requireServerStopped("启用或停用模组")) return
        val dirName = activeDirName() ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { pluginManager.toggleModEnabled(fileName, dirName) }
                refreshMods()
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s215, e.message))
            }
        }
    }

    /** 删除模组 */
    fun deleteMod(fileName: String) {
        if (!requireServerStopped("删除模组")) return
        val dirName = activeDirName() ?: return
        viewModelScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { pluginManager.deleteMod(fileName, dirName) }
                if (ok) _messageFlow.tryEmit(str(R.string.s216, fileName))
                refreshMods()
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s217, e.message))
            }
        }
    }

    /** 从本地 Uri 上传模组 */
    fun installModFromUri(uri: Uri) {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit(str(R.string.s192))
            return
        }
        val dirName = activeDirName() ?: return
        viewModelScope.launch {
            try {
                val name = withContext(Dispatchers.IO) { pluginManager.installModFromUri(uri, dirName) }
                _messageFlow.tryEmit(str(R.string.s218, name))
                refreshMods()
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s219, e.message))
            }
        }
    }

    /** 从精选库下载安装模组（GitHub 动态解析最新版本） */
    fun installCuratedMod(mod: PluginManager.CuratedMod) {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit(str(R.string.s192))
            return
        }
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit(str(R.string.s212))
            return
        }
        viewModelScope.launch {
            try {
                val url = withContext(Dispatchers.IO) {
                    pluginManager.resolveLatestAsset(mod.repo, mod.githubAssetPattern)
                } ?: throw RuntimeException(str(R.string.err_parse_latest_url))
                withContext(Dispatchers.IO) { pluginManager.installModFromUrl(url, mod.targetFileName, dirName) }
                _messageFlow.tryEmit(str(R.string.s221, mod.name))
                refreshMods()
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s222, mod.name, e.message))
            }
        }
    }

    /** 当前核心的 mods 目录路径 */
    fun currentModsPath(): String? {
        val dirName = activeDirName() ?: return null
        return pluginManager.currentModsPath(dirName)
    }

    // ── Modrinth 模组获取 ────────────────────────────────────────

    private val _modrinthResults = MutableStateFlow<List<PluginManager.ModrinthHit>>(emptyList())
    val modrinthResults: StateFlow<List<PluginManager.ModrinthHit>> = _modrinthResults.asStateFlow()

    /** Modrinth 全部可用加载器 */
    private val _modrinthLoaders = MutableStateFlow<List<String>>(emptyList())
    val modrinthLoaders: StateFlow<List<String>> = _modrinthLoaders.asStateFlow()

    /** Modrinth 支持的 MC 游戏版本（供版本筛选下拉） */
    private val _modrinthGameVersions = MutableStateFlow<List<String>>(emptyList())
    val modrinthGameVersions: StateFlow<List<String>> = _modrinthGameVersions.asStateFlow()

    /** 模组筛选选中的游戏版本（默认当前服务器版本） */
    private val _selectedModVersion = MutableStateFlow("")
    val selectedModVersion: StateFlow<String> = _selectedModVersion.asStateFlow()

    /** 当前核心对应的 Modrinth 加载器名 */
    private fun modrinthLoader(core: com.mineserve.mobile.data.ServerCore): String =
        if (core == com.mineserve.mobile.data.ServerCore.Fabric || core == com.mineserve.mobile.data.ServerCore.Quilt) "fabric"
        else "forge"

    /** 当前激活核心的 MC 版本（用于版本匹配判断） */
    private fun currentServerVersion(): String {
        val cfg = config.value
        return cfg.installedCores.find { it.name == cfg.activeCoreName }?.version
            ?: cfg.mcVersion
    }

    /** 加载 Modrinth 可用加载器列表（仅保留模组专用加载器，与插件池完全隔离） */
    fun loadModrinthLoaders() {
        viewModelScope.launch {
            val all = withContext(Dispatchers.IO) { pluginManager.fetchModrinthLoaders() }
            _modrinthLoaders.value = pluginManager.filterModLoaders(all)
        }
    }

    /** 加载 Modrinth 游戏版本列表；默认选中当前服务器版本 */
    fun loadModrinthGameVersions() {
        viewModelScope.launch {
            val versions = withContext(Dispatchers.IO) { pluginManager.fetchModrinthGameVersions() }
            _modrinthGameVersions.value = versions
            val serverVer = currentServerVersion()
            _selectedModVersion.value = serverVer.takeIf { it.isNotBlank() && it in versions } ?: versions.firstOrNull().orEmpty()
        }
    }

    /** 切换模组筛选的 MC 版本 */
    fun setSelectedModVersion(v: String) {
        _selectedModVersion.value = v
    }

    /** Modrinth 搜索参数（用于分页加载更多） */
    private data class ModrinthQueryArgs(
        val query: String,
        val loaders: List<String>,
        val sort: String,
        val mcVersion: String
    )

    private var lastModrinthModQuery: ModrinthQueryArgs? = null
    private val _isLoadingMoreMods = MutableStateFlow(false)
    val isLoadingMoreMods: StateFlow<Boolean> = _isLoadingMoreMods.asStateFlow()

    /**
     * 模组搜索的代际号。
     *
     * 搜索请求是异步的，用户快速改关键字/加载器时会发出多次请求，而**响应到达顺序
     * 不保证与发出顺序一致**（网络抖动、后一次请求更小更先返回）。没有代际校验时，
     * 晚到的旧响应会覆盖新结果——列表显示的内容与实际筛选条件不符。
     * 每次新搜索/加载更多都递增，回写前比对，只有最新一代才允许写入。
     */
    private var modrinthModSearchToken = 0L

    /** 搜索 Modrinth 模组（多加载器 + 版本筛选 + 排序） */
    fun searchModrinthMods(query: String, loaders: List<String>, sort: String, mcVersion: String) {
        lastModrinthModQuery = ModrinthQueryArgs(query, loaders, sort, mcVersion)
        val token = ++modrinthModSearchToken
        viewModelScope.launch {
            val hits = withContext(Dispatchers.IO) {
                pluginManager.searchModrinth(query, loaders, sort, projectType = "mod", mcVersion = mcVersion)
            }
            // 已被更新的一次搜索取代：本次结果整体丢弃，不覆盖新列表。
            if (token != modrinthModSearchToken) return@launch
            _modrinthResults.value = hits
            if (hits.isEmpty()) {
                _messageFlow.tryEmit(str(R.string.s223))
            }
        }
    }

    /** 分页加载更多 Modrinth 模组（追加到现有结果） */
    fun loadMoreModrinthMods() {
        val args = lastModrinthModQuery ?: run {
            _messageFlow.tryEmit("请先执行搜索")
            return
        }
        if (_isLoadingMoreMods.value) return
        val token = ++modrinthModSearchToken
        viewModelScope.launch {
            _isLoadingMoreMods.value = true
            try {
                val offset = _modrinthResults.value.size
                val more = withContext(Dispatchers.IO) {
                    pluginManager.searchModrinth(args.query, args.loaders, args.sort, projectType = "mod", mcVersion = args.mcVersion, offset = offset)
                }
                // 期间若发生过新的搜索，这一页属于旧查询，追加进去会得到混合列表。
                if (token != modrinthModSearchToken) return@launch
                if (more.isEmpty()) {
                    _messageFlow.tryEmit("没有更多结果")
                } else {
                    _modrinthResults.value = (_modrinthResults.value + more).distinctBy { it.slug }
                }
            } finally {
                _isLoadingMoreMods.value = false
            }
        }
    }

    /** 检测已安装插件冲突（同名 / 同主类） */
    fun detectPluginConflicts(plugins: List<PluginManager.InstalledPlugin>): List<String> =
        pluginManager.detectPluginConflicts(plugins)

    /** 一键安装 Modrinth 模组（解析指定版本 release 直链并下载到 mods/） */
    fun installModrinthMod(hit: PluginManager.ModrinthHit, mcVersion: String) {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit(str(R.string.s192))
            return
        }
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit(str(R.string.s212))
            return
        }
        val loader = modrinthLoader(config.value.selectedCore)
        viewModelScope.launch {
            try {
                val url = withContext(Dispatchers.IO) {
                    pluginManager.resolveModrinthDownload(hit.slug, mcVersion, loader)
                } ?: throw RuntimeException(str(R.string.err_mod_incompatible))
                val fileName = "${hit.slug}.jar"
                withContext(Dispatchers.IO) {
                    // 下载开始前先放占位进度，保证进度弹窗一定显示（下载瞬间完成也不会漏）
                    _pluginDownloadProgress.update { it + (hit.slug to PluginDownloadProgress(
                        pluginId = hit.slug, downloadedBytes = 0, totalBytes = 0, speedBytesPerSec = 0
                    )) }
                    pluginManager.installModFromUrl(url, fileName, dirName) { d, t, s ->
                        _pluginDownloadProgress.update { it + (hit.slug to PluginDownloadProgress(
                            pluginId = hit.slug, downloadedBytes = d, totalBytes = t, speedBytesPerSec = s
                        )) }
                    }
                }
                _pluginDownloadProgress.update { it - hit.slug }
                _messageFlow.tryEmit(str(R.string.s225, hit.title))
                refreshMods()
            } catch (e: Exception) {
                _pluginDownloadProgress.update { it - hit.slug }
                _errorFlow.tryEmit(str(R.string.s226, hit.title, e.message))
            }
        }
    }

    // ── Modrinth 插件获取（插件页复用同一下载模块） ───────────────

    private val _pluginModrinthResults = MutableStateFlow<List<PluginManager.ModrinthHit>>(emptyList())
    val pluginModrinthResults: StateFlow<List<PluginManager.ModrinthHit>> = _pluginModrinthResults.asStateFlow()

    private val _pluginModrinthLoaders = MutableStateFlow<List<String>>(emptyList())
    val pluginModrinthLoaders: StateFlow<List<String>> = _pluginModrinthLoaders.asStateFlow()

    private val _selectedPluginVersion = MutableStateFlow("")
    val selectedPluginVersion: StateFlow<String> = _selectedPluginVersion.asStateFlow()

    fun loadPluginModrinthLoaders() {
        viewModelScope.launch {
            val all = withContext(Dispatchers.IO) { pluginManager.fetchModrinthLoaders() }
            _pluginModrinthLoaders.value = pluginManager.filterPluginLoaders(all)
        }
    }

    fun loadPluginModrinthVersions() {
        viewModelScope.launch {
            val versions = withContext(Dispatchers.IO) { pluginManager.fetchModrinthGameVersions() }
            if (_pluginModrinthResults.value.isEmpty()) {
                val serverVer = currentServerVersion()
                _selectedPluginVersion.value = serverVer.takeIf { it.isNotBlank() && it in versions } ?: versions.firstOrNull().orEmpty()
            }
        }
    }

    fun setSelectedPluginVersion(v: String) {
        _selectedPluginVersion.value = v
    }

    private var lastModrinthPluginQuery: ModrinthQueryArgs? = null
    private val _isLoadingMorePlugins = MutableStateFlow(false)
    val isLoadingMorePlugins: StateFlow<Boolean> = _isLoadingMorePlugins.asStateFlow()

    /** 插件搜索的代际号；理由同 [modrinthModSearchToken]。 */
    private var modrinthPluginSearchToken = 0L

    /** 插件页搜索 Modrinth 插件（project_type=plugin，布局与模组页统一） */
    fun searchModrinthPlugin(query: String, loaders: List<String>, sort: String, mcVersion: String) {
        lastModrinthPluginQuery = ModrinthQueryArgs(query, loaders, sort, mcVersion)
        val token = ++modrinthPluginSearchToken
        viewModelScope.launch {
            val hits = withContext(Dispatchers.IO) {
                pluginManager.searchModrinth(query, loaders, sort, projectType = "plugin", mcVersion = mcVersion)
            }
            // 已被更新的一次搜索取代：丢弃本次结果，避免旧响应覆盖新列表。
            if (token != modrinthPluginSearchToken) return@launch
            _pluginModrinthResults.value = hits
            if (hits.isEmpty()) {
                _messageFlow.tryEmit(str(R.string.s223))
            }
        }
    }

    /** 分页加载更多 Modrinth 插件（追加到现有结果） */
    fun loadMoreModrinthPlugin() {
        val args = lastModrinthPluginQuery ?: run {
            _messageFlow.tryEmit("请先执行搜索")
            return
        }
        if (_isLoadingMorePlugins.value) return
        val token = ++modrinthPluginSearchToken
        viewModelScope.launch {
            _isLoadingMorePlugins.value = true
            try {
                val offset = _pluginModrinthResults.value.size
                val more = withContext(Dispatchers.IO) {
                    pluginManager.searchModrinth(args.query, args.loaders, args.sort, projectType = "plugin", mcVersion = args.mcVersion, offset = offset)
                }
                // 期间若发生过新的搜索，这一页属于旧查询，追加进去会得到混合列表。
                if (token != modrinthPluginSearchToken) return@launch
                if (more.isEmpty()) {
                    _messageFlow.tryEmit("没有更多结果")
                } else {
                    _pluginModrinthResults.value = (_pluginModrinthResults.value + more).distinctBy { it.slug }
                }
            } finally {
                _isLoadingMorePlugins.value = false
            }
        }
    }

    /** 安装 Modrinth 插件到 plugins/ 目录 */
    fun installModrinthPlugin(hit: PluginManager.ModrinthHit, mcVersion: String) {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit(str(R.string.s192))
            return
        }
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit(str(R.string.s212))
            return
        }
        val loader = "bukkit"
        viewModelScope.launch {
            try {
                val url = withContext(Dispatchers.IO) {
                    pluginManager.resolveModrinthDownload(hit.slug, mcVersion, loader)
                } ?: throw RuntimeException(str(R.string.err_plugin_incompatible))
                val fileName = "${hit.slug}.jar"
                withContext(Dispatchers.IO) {
                    // 下载开始前先放占位进度，保证进度弹窗一定显示
                    _pluginDownloadProgress.update { it + (hit.slug to PluginDownloadProgress(
                        pluginId = hit.slug, downloadedBytes = 0, totalBytes = 0, speedBytesPerSec = 0
                    )) }
                    pluginManager.installFromUrl(url, fileName, dirName) { d, t, s ->
                        _pluginDownloadProgress.update { it + (hit.slug to PluginDownloadProgress(
                            pluginId = hit.slug, downloadedBytes = d, totalBytes = t, speedBytesPerSec = s
                        )) }
                    }
                }
                _pluginDownloadProgress.update { it - hit.slug }
                _messageFlow.tryEmit(str(R.string.s229, hit.title))
                refreshInstalledPlugins()
            } catch (e: Exception) {
                _pluginDownloadProgress.update { it - hit.slug }
                _errorFlow.tryEmit(str(R.string.s230, hit.title, e.message))
            }
        }
    }

    /** 从精选库下载安装插件 */
    fun installCuratedPlugin(curated: PluginManager.CuratedPlugin) {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit(str(R.string.s192))
            return
        }
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit(str(R.string.s212))
            return
        }
        if (_pluginDownloadProgress.value.containsKey(curated.id)) {
            _errorFlow.tryEmit(str(R.string.s227, curated.name))
            return
        }
        viewModelScope.launch {
            try {
                // asset 文件名带版本号的精选资源（如 ViaVersion），先经 GitHub API 解析最新直链；
                // 解析失败直接报错，不静默回退到 HTML 页面
                val resolvedUrl = if (curated.githubAssetPattern != null) {
                    withContext(Dispatchers.IO) {
                        pluginManager.resolveLatestAsset(curated.repo, curated.githubAssetPattern)
                    } ?: throw RuntimeException(str(R.string.err_parse_curated_url, curated.name))
                } else {
                    curated.downloadUrl
                }
                // 下载开始前先放占位进度，保证进度弹窗一定显示（下载瞬间完成也不会漏）
                _pluginDownloadProgress.update { it + (curated.id to PluginDownloadProgress(
                    pluginId = curated.id, downloadedBytes = 0, totalBytes = 0, speedBytesPerSec = 0
                )) }
                pluginManager.installFromUrl(
                    resolvedUrl,
                    curated.targetFileName,
                    dirName
                ) { downloaded, total, speed ->
                    _pluginDownloadProgress.update { it + (curated.id to PluginDownloadProgress(
                        pluginId = curated.id,
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        speedBytesPerSec = speed
                    )) }
                }
                _messageFlow.tryEmit(str(R.string.s229, curated.name))
                _pluginDownloadProgress.update { it - curated.id }
                refreshInstalledPlugins()
            } catch (e: Exception) {
                _pluginDownloadProgress.update { it - curated.id }
                _errorFlow.tryEmit(str(R.string.s230, curated.name, e.message))
            }
        }
    }

    /** 从本地 Uri 上传插件 */
    fun installPluginFromUri(uri: Uri) {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit(str(R.string.s192))
            return
        }
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit(str(R.string.s212))
            return
        }
        viewModelScope.launch {
            try {
                val fileName = pluginManager.installFromUri(uri, dirName)
                _messageFlow.tryEmit(str(R.string.s231, fileName))
                refreshInstalledPlugins()
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s232, e.message))
            }
        }
    }

    /** 删除插件 */
    fun deletePlugin(fileName: String, alsoRemoveDataDir: Boolean = false) {
        // VM 侧兜底：运行中删除插件（尤其含 removeDataDir）会与正在加载的插件文件竞争
        if (!requireServerStopped("删除插件")) return
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit(str(R.string.s212))
            return
        }
        viewModelScope.launch {
            try {
                val ok = pluginManager.delete(fileName, dirName, alsoRemoveDataDir)
                if (ok) {
                    val msg = if (alsoRemoveDataDir) str(R.string.msg_deleted_with_data, fileName) else str(R.string.msg_deleted, fileName)
                    _messageFlow.tryEmit(msg)
                    refreshInstalledPlugins()
                } else {
                    _errorFlow.tryEmit(str(R.string.s235))
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s236, e.message))
            }
        }
    }

    /** 切换插件启用/禁用 */
    fun togglePluginEnabled(fileName: String) {
        // VM 侧兜底：运行中重命名 jar 可能让服务端读到半截文件
        if (!requireServerStopped("启用或停用插件")) return
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit(str(R.string.s212))
            return
        }
        viewModelScope.launch {
            try {
                val newName = pluginManager.toggleEnabled(fileName, dirName)
                if (newName != null) {
                    val action = if (newName.startsWith("-")) str(R.string.msg_disabled) else str(R.string.msg_enabled)
                    _messageFlow.tryEmit("$action $fileName")
                    refreshInstalledPlugins()
                } else {
                    _errorFlow.tryEmit(str(R.string.s239))
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s240, e.message))
            }
        }
    }

    /** 判断精选插件是否已安装（用于 UI 显示徽章） */
    fun isCuratedPluginInstalled(curated: PluginManager.CuratedPlugin): Boolean {
        return pluginManager.isCuratedInstalled(curated, _installedPlugins.value)
    }

    // ── 精选插件更新检测 ──

    /** 更新检测结果（key = curated.id） */
    private val _curatedUpdates = MutableStateFlow<Map<String, PluginManager.CuratedUpdateInfo>>(emptyMap())
    val curatedUpdates: StateFlow<Map<String, PluginManager.CuratedUpdateInfo>> = _curatedUpdates.asStateFlow()

    /** 是否正在检测更新 */
    private val _isCheckingUpdates = MutableStateFlow(false)
    val isCheckingUpdates: StateFlow<Boolean> = _isCheckingUpdates.asStateFlow()

    /** 检测精选插件更新（5 分钟内有缓存不重复请求） */
    fun checkCuratedUpdates() {
        if (_isCheckingUpdates.value) return
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit(str(R.string.s192))
            return
        }
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit(str(R.string.s212))
            return
        }
        viewModelScope.launch {
            _isCheckingUpdates.value = true
            try {
                val installed = pluginManager.scan(dirName)
                val updates = pluginManager.checkCuratedUpdates(installed)
                _curatedUpdates.value = updates.associateBy { it.curated.id }
                val updateCount = updates.count { it.hasUpdate }
                _messageFlow.tryEmit(
                    if (updateCount > 0) str(R.string.msg_updates_found, updateCount)
                    else str(R.string.msg_updates_all_latest)
                )
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s243, e.message))
            } finally {
                _isCheckingUpdates.value = false
            }
        }
    }

    /** 强制重新检测（清空缓存） */
    fun forceRecheckUpdates() {
        pluginManager.invalidateUpdateCache()
        checkCuratedUpdates()
    }

    // ── 自定义 URL 下载 ──

    private val URL_DOWNLOAD_ID = "__custom_url__"

    /** 从自定义 URL 下载插件 */
    fun installPluginFromUrl(url: String, customFileName: String) {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit(str(R.string.s192))
            return
        }
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit(str(R.string.s212))
            return
        }
        if (url.isBlank() || customFileName.isBlank()) {
            _errorFlow.tryEmit(str(R.string.s244))
            return
        }
        if (_pluginDownloadProgress.value.containsKey(URL_DOWNLOAD_ID)) {
            _errorFlow.tryEmit(str(R.string.s245))
            return
        }
        viewModelScope.launch {
            try {
                // 下载开始前先放占位进度，保证进度弹窗一定显示（下载瞬间完成也不会漏）
                _pluginDownloadProgress.update { it + (URL_DOWNLOAD_ID to PluginDownloadProgress(
                    pluginId = URL_DOWNLOAD_ID, downloadedBytes = 0, totalBytes = 0, speedBytesPerSec = 0
                )) }
                pluginManager.installFromUrl(url, customFileName, dirName) { downloaded, total, speed ->
                    _pluginDownloadProgress.update { it + (URL_DOWNLOAD_ID to PluginDownloadProgress(
                        pluginId = URL_DOWNLOAD_ID,
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        speedBytesPerSec = speed
                    )) }
                }
                _messageFlow.tryEmit(str(R.string.s246, customFileName))
                _pluginDownloadProgress.update { it - URL_DOWNLOAD_ID }
                refreshInstalledPlugins()
            } catch (e: Exception) {
                _pluginDownloadProgress.update { it - URL_DOWNLOAD_ID }
                _errorFlow.tryEmit(str(R.string.s247, e.message))
            }
        }
    }

    /** 判断自定义 URL 是否正在下载 */
    fun isCustomUrlDownloading(): Boolean = _pluginDownloadProgress.value.containsKey(URL_DOWNLOAD_ID)

    /** 获取自定义 URL 下载进度（无则 null） */
    fun customUrlDownloadProgress(): PluginDownloadProgress? =
        _pluginDownloadProgress.value[URL_DOWNLOAD_ID]

    /** 获取当前核心的 plugins 目录路径（用于 UI 显示） */
    fun currentPluginsPath(): String? {
        val dirName = activeDirName() ?: return null
        return java.io.File(repo.termuxRuntime.installer.rootDir, "home/servers/$dirName/plugins").absolutePath
    }


    /** 获取当前选用核心的 dirName，未选择时返回 null */
    private fun activeDirName(): String? {
        return config.value.installedCores.find { it.name == config.value.activeCoreName }?.dirName
    }

    /** 当前核心是否 Paper（含分叉核心均支持 tps 指令）。 */
    private fun isActiveCorePaper(): Boolean =
        config.value.installedCores
            .firstOrNull { it.name == config.value.activeCoreName }
            ?.core == ServerCore.Paper

    /** 创建 world 目录快照（zip 打包），返回快照文件路径或 null */
    suspend fun createSnapshot(): String? {
        if (!isBootstrapped.value) return null
        val dirName = activeDirName() ?: return null
        return withContext(Dispatchers.IO) {
            backupManager.backupWorldToExternal(dirName, BackupManager.BackupOrigin.Manual)
        }
    }

    /** 更新前备份存档（更新对话框「先备份存档」入口） */
    fun backupBeforeUpdate() {
        viewModelScope.launch {
            try {
                val path = createSnapshot()
                if (path != null) {
                    _messageFlow.tryEmit(str(R.string.update_backup_done, java.io.File(path).name))
                } else {
                    _messageFlow.tryEmit(str(R.string.update_backup_empty))
                }
            } catch (e: Exception) {
                _messageFlow.tryEmit(str(R.string.err_backup_fail, e.message))
            }
        }
    }

    /** 外部备份整个世界（world+nether+end → 外部目录） */
    fun backupWorldToExternal() {        if (!isBootstrapped.value) { _errorFlow.tryEmit(str(R.string.s192)); return }
        val dirName = activeDirName() ?: run { _errorFlow.tryEmit(str(R.string.s212)); return }
        viewModelScope.launch {
            try {
                val path = withContext(Dispatchers.IO) {
                    backupManager.backupWorldToExternal(dirName, BackupManager.BackupOrigin.Manual)
                }
                if (path != null) {
                    _messageFlow.tryEmit(str(R.string.msg_world_backup_done, java.io.File(path).name))
                } else {
                    _errorFlow.tryEmit(str(R.string.err_world_backup_empty))
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.err_world_backup_fail, e.message))
            }
        }
    }

    /** 外部备份整个服务器（world+核心+配置+插件 → 外部目录，文件名带核心类型） */
    fun backupServerToExternal() {
        if (!isBootstrapped.value) { _errorFlow.tryEmit(str(R.string.s192)); return }
        val dirName = activeDirName() ?: run { _errorFlow.tryEmit(str(R.string.s212)); return }
        // 当前服务器核心类型写入文件名（还原时据此识别核心）
        val coreTag = config.value.installedCores
            .firstOrNull { it.dirName == dirName }?.core?.displayName
        viewModelScope.launch {
            try {
                val path = withContext(Dispatchers.IO) {
                    backupManager.backupServerToExternal(dirName, coreTag, BackupManager.BackupOrigin.Manual)
                }
                if (path != null) {
                    _messageFlow.tryEmit(str(R.string.msg_server_backup_done, java.io.File(path).name))
                } else {
                    _errorFlow.tryEmit(str(R.string.err_server_backup_fail))
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.err_server_backup_fail_msg, e.message))
            }
        }
    }

    // ── 外部备份导入/还原（含重名检测） ─────────────────────────────

    /** 服务器还原重名冲突（UI 弹框询问） */
    data class RestoreConflict(val zipName: String, val dirName: String)

    private val _restoreConflict = kotlinx.coroutines.flow.MutableStateFlow<RestoreConflict?>(null)
    val restoreConflict: kotlinx.coroutines.flow.StateFlow<RestoreConflict?> = _restoreConflict.asStateFlow()

    /** 列出外部备份 zip（供备份页展示） */
    fun externalBackups(): List<java.io.File> = com.mineserve.mobile.server.ExternalBackupStore.listBackups()

    fun externalBackupInfo(file: java.io.File): BackupManager.ExternalBackupInfo =
        backupManager.parseExternalBackup(file.name, file.lastModified())

    /** 删除外部备份文件 */
    fun deleteExternalBackup(name: String) {
        viewModelScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    backupManager.deleteExternalBackup(name)
                }
                if (ok) _messageFlow.tryEmit(str(R.string.msg_backup_deleted, name))
                else _errorFlow.tryEmit(str(R.string.err_backup_delete_fail, name))
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.err_backup_delete_fail, e.message))
            }
        }
    }

    /** 从手机其他位置导入备份 zip 到外部备份目录（SAF 选择） */
    fun importBackupToExternal(uri: android.net.Uri) {        viewModelScope.launch {
            try {
                if (!com.mineserve.mobile.server.ExternalBackupStore.ensure()) {
                    _errorFlow.tryEmit(str(R.string.err_ext_dir_unavailable))
                    return@launch
                }
                val fileName = withContext(Dispatchers.IO) {
                    app.contentResolver.query(
                        uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
                    )?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: "backup_${System.currentTimeMillis()}.zip"
                }
                val safeName = fileName?.takeIf { it.endsWith(".zip") } ?: "backup_${System.currentTimeMillis()}.zip"
                val target = java.io.File(com.mineserve.mobile.server.ExternalBackupStore.rootDir, safeName)
                withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                _messageFlow.tryEmit(str(R.string.msg_backup_imported, safeName))
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.err_backup_import_fail, e.message))
            }
        }
    }

    /** 重命名外部备份文件 */
    fun renameExternalBackup(oldName: String, newName: String) {
        viewModelScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { backupManager.renameExternalBackup(oldName, newName) }
                if (ok) _messageFlow.tryEmit(str(R.string.bk_rename_done, newName))
                else _errorFlow.tryEmit(str(R.string.bk_rename_fail))
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.bk_rename_fail))
            }
        }
    }

    /** 校验外部备份 zip 完整性 */
    fun validateExternalBackup(name: String) {
        viewModelScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { backupManager.validateZip(name) }
                if (ok) _messageFlow.tryEmit(str(R.string.bk_validate_done, name))
                else _errorFlow.tryEmit(str(R.string.bk_validate_fail, name))
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.bk_validate_fail, name))
            }
        }
    }

    /** 从外部 zip 还原世界（zip 内 world/ 前缀） */
    fun restoreWorldFromExternal(zipName: String) {        if (!isBootstrapped.value) { _errorFlow.tryEmit(str(R.string.s192)); return }
        val dirName = activeDirName() ?: run { _errorFlow.tryEmit(str(R.string.s212)); return }
        val file = java.io.File(com.mineserve.mobile.server.ExternalBackupStore.rootDir, zipName)
        viewModelScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { backupManager.restoreWorldFromExternal(file, dirName) }
                if (ok) _messageFlow.tryEmit(str(R.string.msg_world_restored, zipName))
                else _errorFlow.tryEmit(str(R.string.err_world_restore_fail))
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.err_world_restore_fail_msg, e.message))
            }
        }
    }

    /** 请求还原服务器：解析目标目录名与核心类型，存在同名则发冲突事件（UI 弹框） */
    fun requestRestoreServer(zipName: String) {
        if (!isBootstrapped.value) { _errorFlow.tryEmit(str(R.string.s192)); return }
        val (dirName, coreTag) = backupManager.parseServerZipInfo(zipName)
        val target = java.io.File(repo.termuxRuntime.serversDir, dirName)
        if (target.exists()) {
            _restoreConflict.value = RestoreConflict(zipName, dirName)
        } else {
            performRestoreServer(zipName, dirName, overwrite = false, coreTag = coreTag)
        }
    }

    /** 确认还原服务器（overwrite=true 覆盖同名） */
    fun confirmRestoreServer(overwrite: Boolean) {
        val conflict = _restoreConflict.value ?: return
        _restoreConflict.value = null
        val coreTag = backupManager.parseServerZipInfo(conflict.zipName).second
        performRestoreServer(conflict.zipName, conflict.dirName, overwrite, coreTag)
    }

    fun dismissRestoreConflict() { _restoreConflict.value = null }

    private fun performRestoreServer(zipName: String, dirName: String, overwrite: Boolean, coreTag: String? = null) {
        val file = java.io.File(com.mineserve.mobile.server.ExternalBackupStore.rootDir, zipName)
        viewModelScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    backupManager.restoreServerFromExternal(file, dirName, overwrite)
                }
                if (ok) {
                    // 注册到已安装核心列表并设为当前选中（否则概览/核心列表不显示）
                    registerRestoredCore(dirName, coreTag)
                    _messageFlow.tryEmit(str(R.string.msg_server_restored, zipName))
                } else {
                    _errorFlow.tryEmit(str(R.string.err_server_restore_fail))
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.err_server_restore_fail_msg, e.message))
            }
        }
    }

    /**
     * 把还原的服务器注册进已安装核心列表（config.installedCores）并设为当前选中。
     * 核心类型从备份文件名识别（coreTag），无法识别时标记 Unknown。
     */
    private fun registerRestoredCore(dirName: String, coreTag: String? = null) {
        val cfg = config.value
        if (cfg.installedCores.any { it.dirName == dirName }) {
            // 已存在：只确保设为当前选中
            if (cfg.activeCoreName != dirName) {
                updateConfig { it.copy(activeCoreName = dirName) }
            }
            return
        }
        // 从 coreTag（核心 displayName）匹配枚举，未知则 Unknown
        val core = com.mineserve.mobile.data.ServerCore.entries
            .firstOrNull { it.displayName == coreTag }
            ?: com.mineserve.mobile.data.ServerCore.Unknown
        updateConfig {
            it.copy(
                installedCores = it.installedCores + com.mineserve.mobile.data.InstalledCore(
                    name = dirName,
                    core = core,
                    version = str(R.string.ver_restored),
                    dirName = dirName
                ),
                activeCoreName = dirName
            )
        }
    }

    // ── 导入服务器（文件夹 / 压缩包 / JAR） ────────────────────────

    private val serverImporter = ServerImporter(app, repo.termuxRuntime)

    private val _isImportingServer = MutableStateFlow(false)
    val isImportingServer: StateFlow<Boolean> = _isImportingServer.asStateFlow()

    private val _importProgress = MutableStateFlow<Float?>(null)
    val importProgress: StateFlow<Float?> = _importProgress.asStateFlow()

    /** 从外部存储文件夹（SAF tree）导入为新的服务器；displayName 为空时自动解析文件夹名 */
    fun importServerFromFolder(uri: android.net.Uri, displayName: String? = null) {
        if (!isBootstrapped.value) { _errorFlow.tryEmit(str(R.string.s192)); return }
        viewModelScope.launch {
            _isImportingServer.value = true
            _importProgress.value = null
            try {
                val imported = withContext(Dispatchers.IO) {
                    serverImporter.importFromFolder(uri, displayName) { done, total ->
                        _importProgress.value = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
                    }
                }
                registerImportedServer(imported)
                _messageFlow.tryEmit(importServerMessage(imported))
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.import_server_failed, e.message))
            } finally {
                _isImportingServer.value = false
                _importProgress.value = null
            }
        }
    }

    /** 从外部存储压缩包（zip/tar/tar.gz/tar.xz/tar.bz2/7z）导入为新的服务器；displayName 为空时自动解析 */
    fun importServerFromArchive(uri: android.net.Uri, displayName: String? = null) {
        if (!isBootstrapped.value) { _errorFlow.tryEmit(str(R.string.s192)); return }
        viewModelScope.launch {
            _isImportingServer.value = true
            _importProgress.value = null
            try {
                val imported = withContext(Dispatchers.IO) {
                    serverImporter.importFromArchive(uri, displayName) { done, total ->
                        _importProgress.value = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
                    }
                }
                registerImportedServer(imported)
                _messageFlow.tryEmit(importServerMessage(imported))
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.import_server_failed, e.message))
            } finally {
                _isImportingServer.value = false
                _importProgress.value = null
            }
        }
    }

    /** 从单个服务端 JAR 创建标准服务器目录后导入。 */
    fun importServerFromJar(uri: android.net.Uri, displayName: String? = null) {
        if (!isBootstrapped.value) { _errorFlow.tryEmit(str(R.string.s192)); return }
        viewModelScope.launch {
            _isImportingServer.value = true
            _importProgress.value = null
            try {
                val imported = withContext(Dispatchers.IO) {
                    serverImporter.importFromJar(uri, displayName) { done, total ->
                        _importProgress.value = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
                    }
                }
                registerImportedServer(imported)
                _messageFlow.tryEmit(importServerMessage(imported))
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.import_server_failed, e.message))
            } finally {
                _isImportingServer.value = false
                _importProgress.value = null
            }
        }
    }

    /** 为导入确认对话框预填名称：kind 为 folder / archive / jar */
    suspend fun proposeImportName(kind: String, uri: android.net.Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            when (kind) {
            "folder" -> serverImporter.proposeFolderName(uri)
            "jar" -> serverImporter.proposeJarName(uri)
            "modpack" -> serverImporter.proposeArchiveName(uri)
            else -> serverImporter.proposeArchiveName(uri)
            }
        }.getOrNull()
    }

    /** 把导入的服务器注册进已安装核心列表并设为当前选中（核心/版本为自动识别结果） */
    private fun registerImportedServer(imported: ServerImporter.ImportedServer) {
        updateConfig {
            val core = imported.core ?: com.mineserve.mobile.data.ServerCore.Unknown
            it.copy(
                installedCores = it.installedCores.filter { c -> c.dirName != imported.dirName } +
                    com.mineserve.mobile.data.InstalledCore(
                        name = imported.displayName,
                        core = core,
                        version = imported.version ?: str(R.string.ver_imported),
                        dirName = imported.dirName,
                        serverFile = imported.serverFile
                    ),
                activeCoreName = imported.displayName
            )
        }
    }

    /** 导入完成提示（含自动识别的核心与版本） */
    private fun importServerMessage(imported: ServerImporter.ImportedServer): String {
        val coreLabel = if (imported.core != null && imported.core != com.mineserve.mobile.data.ServerCore.Unknown) {
            listOf(imported.core.displayName, imported.version).filter { !it.isNullOrBlank() }.joinToString(" ")
        } else null
        val suffix = if (coreLabel != null) {
            str(R.string.import_server_core, coreLabel)
        } else {
            str(R.string.import_server_core_unknown)
        }
        return str(R.string.import_server_done, imported.displayName) + "（" + suffix + "）"
    }


    // ── 备份恢复管理 ────────────────────────────────────────────────

    private val backupManager = BackupManager(repo.termuxRuntime)

    private val _snapshots = MutableStateFlow<List<BackupManager.SnapshotInfo>>(emptyList())
    val snapshots: StateFlow<List<BackupManager.SnapshotInfo>> = _snapshots.asStateFlow()

    /** 加载快照列表 */
    fun loadSnapshots() {
        if (!isBootstrapped.value) return
        viewModelScope.launch {
            try {
                _snapshots.value = withContext(Dispatchers.IO) { backupManager.listSnapshots() }
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s248, e.message))
            }
        }
    }

    /** 删除当前激活核心的世界文件夹（world / world_nether / world_the_end），不可恢复 */
    fun deleteWorldDirs() {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit(str(R.string.s192))
            return
        }
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit(str(R.string.s249))
            return
        }
        viewModelScope.launch {
            try {
                val deleted = withContext(Dispatchers.IO) {
                    val base = File(repo.termuxRuntime.installer.rootDir, "home/servers/$dirName")
                    var count = 0
                    for (name in listOf("world", "world_nether", "world_the_end")) {
                        val dir = File(base, name)
                        if (dir.exists() && dir.deleteRecursively()) count++
                    }
                    count
                }
                _messageFlow.tryEmit(str(R.string.ui_world_deleted, deleted))
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.ui_world_delete_failed))
            }
        }
    }

    /** 恢复快照（会先停止服务器） */
    fun restoreSnapshot(name: String) {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit(str(R.string.s192))
            return
        }
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit(str(R.string.s249))
            return
        }
        viewModelScope.launch {
            try {
                _messageFlow.tryEmit(str(R.string.s250))
                val ok = withContext(Dispatchers.IO) { backupManager.restoreSnapshot(name, dirName) }
                if (ok) {
                    _messageFlow.tryEmit(str(R.string.s251))
                    loadSnapshots()
                } else {
                    _errorFlow.tryEmit(str(R.string.s252))
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s253, e.message))
            }
        }
    }

    /** 导出快照 zip 到用户选择的 Uri（自定义导出路径） */
    fun exportSnapshotToUri(snapshotName: String, uri: android.net.Uri) {
        val src = java.io.File(repo.termuxRuntime.installer.rootDir, "home/snapshots/$snapshotName")
        if (!src.exists()) {
            _errorFlow.tryEmit(str(R.string.s254))
            return
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = app.contentResolver.openOutputStream(uri)
                        ?: throw RuntimeException(str(R.string.err_open_export))
                    output.use { os -> src.inputStream().use { it.copyTo(os) } }
                }
                _messageFlow.tryEmit(str(R.string.s256, snapshotName))
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s257, e.message))
            }
        }
    }

    /** 删除快照 */
    fun deleteSnapshot(name: String) {
        // 兜底：运行中删除快照会毁掉「出事可回滚」的备份，与服务端活动期解耦更安全
        if (!requireServerStopped("删除快照")) return
        viewModelScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { backupManager.deleteSnapshot(name) }
                if (ok) {
                    _messageFlow.tryEmit(str(R.string.s258, name))
                    loadSnapshots()
                } else {
                    _errorFlow.tryEmit(str(R.string.s259))
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s260, e.message))
            }
        }
    }

    // ── server.properties 编辑 ─────────────────────────────────────

    private val propertiesManager = ServerPropertiesManager(repo.termuxRuntime)
    private val powerNukkitXConfigManager = PowerNukkitXConfigManager(repo.termuxRuntime)

    fun isPowerNukkitXActive(): Boolean = config.value.installedCores
        .firstOrNull { it.name == config.value.activeCoreName }?.core == ServerCore.PowerNukkitX

    fun supportedServerPropertyKeys(): Set<String> =
        if (isPowerNukkitXActive()) powerNukkitXConfigManager.supportedKeys()
        else emptySet()

    private val _serverProperties = MutableStateFlow<Map<String, String>>(emptyMap())
    val serverProperties: StateFlow<Map<String, String>> = _serverProperties.asStateFlow()

    /**
     * 服务端真正监听的端口（端口真源）。
     *
     * 以服务端配置文件为准：Java 系取 `server.properties` 的 `server-port`，
     * PowerNukkitX 取 `pnx.yml` 的 `settings.port`（见 [loadServerProperties]）。
     * 配置文件尚未生成时退回 [McConfig.localPort]。
     *
     * 地址栏、复制地址、诊断检查都读这里，不再直接读 [McConfig.localPort] ——
     * 后者可能停在旧值（历史上「配置 25562、显示 19132」就是这么来的）。
     */
    val serverPort: StateFlow<Int> = combine(_serverProperties, config) { props, cfg ->
        ServerPort.resolve(props["server-port"], activeCoreType() ?: cfg.selectedCore, cfg.localPort)
    }.distinctUntilChanged().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000),
        ServerPort.DEFAULT_JAVA
    )

    /** 当前激活核心的类型；未选择时返回 null。 */
    private fun activeCoreType(): ServerCore? = config.value.installedCores
        .firstOrNull { it.name == config.value.activeCoreName }?.core

    /**
     * **此刻**服务端真正在监听的端口。
     *
     * 与 [serverPort] 的区别：那个是 `WhileSubscribed(5s)` 的 StateFlow，没有订阅者时
     * `.value` 会退化成初始值，拿去启动穿透就会打到错误的端口上。启动链路上的调用方
     * 一律用这个函数 —— 它每次现算，不经过任何缓存。
     */
    private fun realServerPort(): Int = ServerPort.resolve(
        _serverProperties.value["server-port"],
        activeCoreType() ?: config.value.selectedCore,
        config.value.localPort
    )

    /**
     * 配置文件当前是否已生成（服务端首次启动后才会由服务端写出）。
     *
     * null 表示尚未检测完成，UI 应展示加载态而非误判为"未生成"。
     */
    private val _serverPropertiesExist = MutableStateFlow<Boolean?>(null)
    val serverPropertiesExist: StateFlow<Boolean?> = _serverPropertiesExist.asStateFlow()

    /**
     * 加载 server.properties，并同步更新配置文件是否已生成的状态。
     *
     * 「发出去就不管」；需要**读完再取端口**的调用方用 [refreshServerPropertiesNow]。
     */
    fun loadServerProperties() {
        viewModelScope.launch { refreshServerPropertiesNow() }
    }

    /**
     * 重新读一遍服务端配置文件，并**等它读完**。
     *
     * 为什么必须能「等」：端口真源历史上只在进入「服务器配置」页时才被读一次。
     * 用户在配置文件里把端口从 25565 改成别的，然后直接去开穿透 —— 隧道拿到的还是
     * 上一次的值，于是把玩家送到 25565，一个没人监听的端口。界面上隧道「已就绪」，
     * 日志里只有一句 `connect: connection refused`，从字面完全看不出是端口对不上。
     * 启动前先把真源读回来，这条坑就不存在了。
     */
    private suspend fun refreshServerPropertiesNow() {
        if (!isBootstrapped.value) return
        val dirName = activeDirName() ?: return
        try {
            val pnx = isPowerNukkitXActive()
            // PNX 的真实配置是 pnx.yml，Java 系是 server.properties，两者要分别判定，
            // 否则 PNX 下永远看 server.properties 的存在性，导致 pnx.yml 还没生成就放行表单。
            val exists = withContext(Dispatchers.IO) {
                if (pnx) powerNukkitXConfigManager.exists(dirName)
                else propertiesManager.exists(dirName)
            }
            _serverPropertiesExist.value = exists
            val loaded = withContext(Dispatchers.IO) {
                if (pnx) powerNukkitXConfigManager.read(dirName)
                else propertiesManager.readProperties(dirName)
            }
            _serverProperties.value = loaded
            // 端口真源镜像：配置文件已生成且含合法端口时，把它同步回 [McConfig.localPort]。
            // 「服务器配置」页是端口唯一编辑入口（网络页的输入框已在 1.2.8 移除），
            // 不同步的话地址栏会一直停在旧值 —— 这正是「配置 25562、显示 19132」的成因。
            // 只在 exists 为真时同步：文件不存在时 loaded 是空 Map，会把 localPort 误覆盖成默认值。
            if (exists) ServerPort.parse(loaded["server-port"])?.let { port ->
                if (config.value.localPort != port) updateConfig { it.copy(localPort = port) }
            }
        } catch (e: Exception) {
            _errorFlow.tryEmit(str(R.string.s261, e.message))
        }
    }

    /** 保存 server.properties */
    fun saveServerProperties(props: Map<String, String>) {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit(str(R.string.s192))
            return
        }
        // PowerNukkitX 的配置落在 pnx.yml，而服务端退出时会整份回写该文件，
        // 运行期间的修改必然被覆盖；PowerNukkitXConfigManager.write 也会主动拒绝运行中写入。
        // 在这里提前拦截是为了给出明确原因，而不是让用户看到笼统的「保存失败」。
        if (isPowerNukkitXActive() && !requireServerStopped("修改 PowerNukkitX 配置")) return
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit(str(R.string.s249))
            return
        }
        viewModelScope.launch {
            try {
                // 返回值第二个元素是「本次实际生效的端口」：Java 系取提交值，缺省时退回
                // 文件原值再退回核心默认端口；PNX 系还要同时写 server.properties，
                // 那里的端口就是最终生效值。保存成功后据此回写 [McConfig.localPort]。
                val result = withContext(Dispatchers.IO) {
                    val current = propertiesManager.readProperties(dirName)
                    if (isPowerNukkitXActive()) {
                        val supported = props.filterKeys { it in powerNukkitXConfigManager.supportedKeys() }
                        val yamlOk = powerNukkitXConfigManager.write(dirName, supported)
                        val effectivePort = props["server-port"] ?: current["server-port"]
                            ?: ServerPort.defaultFor(ServerCore.PowerNukkitX).toString()
                        val serverProps = current.toMutableMap()
                        serverProps["server-port"] = effectivePort
                        (yamlOk && propertiesManager.writeProperties(serverProps, dirName)) to effectivePort
                    } else {
                        val core = activeCoreType() ?: config.value.selectedCore
                        val effectivePort = props["server-port"] ?: current["server-port"]
                            ?: ServerPort.defaultFor(core).toString()
                        propertiesManager.writeProperties(props, dirName) to effectivePort
                    }
                }
                if (result.first) {
                    _messageFlow.tryEmit(str(R.string.s262))
                    _serverProperties.value = props
                    // 端口真源回写：「服务器配置」页是端口唯一编辑入口（网络页的输入框已在
                    // 1.2.8 移除）。不回写的话地址栏/穿透默认端口会停在旧值 ——
                    // 用户改了 25562 而界面仍显示 19132 就是这么来的。
                    ServerPort.parse(result.second)?.let { port ->
                        if (config.value.localPort != port) updateConfig { it.copy(localPort = port) }
                    }
                    refreshLanIp()
                } else {
                    _errorFlow.tryEmit(str(R.string.s263))
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s264, e.message))
            }
        }
    }

    // ── 玩家管理 ────────────────────────────────────────────────────

    // 注意：playerManager 等属性被 init 中启动的协程（IO 线程）访问，
    // 声明位置在 init 之后，必须用 lazy 延迟初始化，避免构造函数未完成时读到 null
    private val playerManager: PlayerManager by lazy { PlayerManager(repo.termuxRuntime) }

    private val _ops = MutableStateFlow<List<PlayerManager.OpEntry>>(emptyList())
    val ops: StateFlow<List<PlayerManager.OpEntry>> = _ops.asStateFlow()

    private val _whitelist = MutableStateFlow<List<PlayerManager.WhitelistEntry>>(emptyList())
    val whitelist: StateFlow<List<PlayerManager.WhitelistEntry>> = _whitelist.asStateFlow()

    private val _bannedPlayers = MutableStateFlow<List<PlayerManager.BannedEntry>>(emptyList())
    val bannedPlayers: StateFlow<List<PlayerManager.BannedEntry>> = _bannedPlayers.asStateFlow()

    /** 白名单开关状态（由 server.properties 的 white-list 控制） */
    private val _whitelistEnabled = MutableStateFlow(false)
    val whitelistEnabled: StateFlow<Boolean> = _whitelistEnabled.asStateFlow()

    /** 当前 server.properties 配置的默认 OP 等级（null 表示未配置，使用 MC 默认 4） */
    private val _defaultOpLevel = MutableStateFlow<Int?>(null)
    val defaultOpLevel: StateFlow<Int?> = _defaultOpLevel.asStateFlow()

    /** 在线玩家名列表（从日志解析） */
    private val _onlinePlayerNames: MutableStateFlow<List<String>> by lazy { MutableStateFlow(emptyList()) }
    val onlinePlayerNames: StateFlow<List<String>> by lazy { _onlinePlayerNames.asStateFlow() }

    /** 玩家进服/离服历史记录（最新在前，上限 500 条），持久化到 app 私有目录 */
    @Serializable
    data class PlayerHistoryEntry(
        val player: String,
        val event: String,
        val time: String,
        val sessionStart: Long? = null,
        val sessionEnd: Long? = null,
        val interrupted: Boolean = false
    )
    data class PlayerActivitySummary(
        val player: String,
        val sessions: Int,
        val totalSeconds: Long,
        val active: Boolean,
        val lastJoinTime: String? = null,
        val lastLeaveTime: String? = null,
        val lastExitReason: String? = null
    )

    private val _playerHistory: MutableStateFlow<List<PlayerHistoryEntry>> by lazy { MutableStateFlow(emptyList()) }
    val playerHistory: StateFlow<List<PlayerHistoryEntry>> by lazy { _playerHistory.asStateFlow() }

    private val legacyPlayerHistoryFile: java.io.File get() = java.io.File(app.filesDir, "player_history.json")
    private fun playerHistoryFile() = java.io.File(app.filesDir, "player-history/${activeDirName() ?: "unselected"}.json")

    private val historyJson: Json by lazy { Json { ignoreUnknownKeys = true } }

    /** 历史记录文件读写互斥，避免多人进出服时并发写导致 JSON 损坏 */
    private val historyMutex: Mutex by lazy { Mutex() }

    /** 启动时异步加载历史记录文件（文件缺失/损坏时从空历史开始） */
    private fun loadPlayerHistory() {
        // Activity data is isolated by active server. Do not merge a previous server's snapshot.
        _playerHistory.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) {
            historyMutex.withLock {
                try {
                    val f = playerHistoryFile()
                    if (!f.exists() && legacyPlayerHistoryFile.exists()) {
                        f.parentFile?.mkdirs()
                        legacyPlayerHistoryFile.copyTo(f, overwrite = false)
                    }
                    if (f.exists()) {
                        val fileList = historyJson.decodeFromString<List<PlayerHistoryEntry>>(f.readText())
                        val merged = fileList.distinct().sortedByDescending { it.time }
                        _playerHistory.value = merged
                        // A process restart has no trustworthy leave timestamp. Preserve it as interrupted.
                        val interrupted = merged.map { if (it.event == "进服" && it.sessionEnd == null) it.copy(interrupted = true) else it }
                        _playerHistory.value = interrupted
                        f.writeText(historyJson.encodeToString(interrupted))
                    }
                } catch (e: Exception) {
                    // 忽略损坏文件
                }
            }
        }
    }

    /** 追加一条进服/离服事件并异步持久化（保留最近 500 条） */
    private fun recordPlayerEvent(player: String, event: String) {
        val now = System.currentTimeMillis()
        val previous = _playerHistory.value
        val open = previous.firstOrNull { it.player.equals(player, true) && it.event == "进服" && it.sessionEnd == null && !it.interrupted }
        val entry = if (event == "离服" && open != null) {
            PlayerHistoryEntry(player, event, timeNow(), open.sessionStart ?: now, now)
        } else {
            PlayerHistoryEntry(player, event, timeNow(), if (event == "进服") now else null, null)
        }
        _playerHistory.value = listOf(entry) + previous.map {
            if (event == "离服" && it == open) it.copy(sessionEnd = now) else it
        }
        val snapshot = _playerHistory.value
        viewModelScope.launch(Dispatchers.IO) {
            historyMutex.withLock {
                try {
                    val file = playerHistoryFile(); file.parentFile?.mkdirs()
                    file.writeText(historyJson.encodeToString(snapshot))
                } catch (e: Exception) {
                    // 写入失败不阻断运行
                }
            }
        }
    }

    fun playerActivitySummaries(): List<PlayerActivitySummary> {
        val entries = _playerHistory.value
        return entries.groupBy { it.player }.map { (player, rows) ->
            val seconds = rows.filter { it.event == "离服" && !it.interrupted }.sumOf { ((it.sessionEnd ?: 0L) - (it.sessionStart ?: 0L)).coerceAtLeast(0L) / 1000 }
            val lastJoin = rows.filter { it.event == "进服" }.maxByOrNull { it.time }
            val lastLeave = rows.filter { it.event == "离服" }.maxByOrNull { it.time }
            PlayerActivitySummary(
                player = player,
                sessions = rows.count { it.event == "离服" },
                totalSeconds = seconds,
                active = rows.any { it.event == "进服" && it.sessionEnd == null },
                lastJoinTime = lastJoin?.time,
                lastLeaveTime = lastLeave?.time,
                lastExitReason = lastLeave?.let {
                    if (it.interrupted) "服务停止或应用重启导致中断" else "日志未提供退出原因"
                }
            )
        }.sortedByDescending { it.totalSeconds }
    }

    fun interruptPlayerSessions() {
        val open = _playerHistory.value.filter { it.event == "进服" && it.sessionEnd == null }
        if (open.isEmpty()) return
        val replacements = open.associateWith { it.copy(interrupted = true) }
        _playerHistory.value = _playerHistory.value.map { replacements[it] ?: it }
        val snapshot = _playerHistory.value
        viewModelScope.launch(Dispatchers.IO) { historyMutex.withLock {
            try {
                val file = playerHistoryFile(); file.parentFile?.mkdirs(); file.writeText(historyJson.encodeToString(snapshot))
            } catch (e: Exception) {
                // 写入失败不阻断运行（与 recordPlayerEvent 一致）：viewModelScope 无 CoroutineExceptionHandler，
                // 磁盘满/目录不可写时异常逸出协程会直接崩溃 App。
            }
        } }
    }

    fun clearPlayerHistory() {
        _playerHistory.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) { historyMutex.withLock {
            try {
                playerHistoryFile().delete()
            } catch (e: Exception) {
                // 删除失败不阻断运行
            }
        } }
    }

    fun exportPlayerHistory(uri: android.net.Uri) = viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) {
                app.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { out ->
                    out.appendLine("player,event,time,session_start,session_end,duration_seconds,interrupted")
                    _playerHistory.value.forEach { row ->
                        fun csv(v: String) = "\"${v.replace("\"", "\"\"")}\""
                        val duration = if (row.sessionStart != null && row.sessionEnd != null && !row.interrupted) ((row.sessionEnd - row.sessionStart) / 1000).toString() else ""
                        out.appendLine(listOf(csv(row.player), csv(row.event), csv(row.time), row.sessionStart ?: "", row.sessionEnd ?: "", duration, row.interrupted).joinToString(","))
                    }
                } ?: error(str(R.string.err_open_export))
            }
            _messageFlow.tryEmit(str(R.string.msg_history_exported))
        } catch (e: Exception) { _errorFlow.tryEmit(e.message ?: str(R.string.err_export_fail)) }
    }

    private fun timeNow(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())

    /** 在线玩家集合维护（进服：增量添加，去重） */
    private fun addOnlinePlayer(name: String) {
        val cur = _onlinePlayerNames.value
        if (name !in cur) _onlinePlayerNames.value = cur + name
    }

    /** 在线玩家集合维护（离服：移除） */
    private fun removeOnlinePlayer(name: String) {
        _onlinePlayerNames.value = _onlinePlayerNames.value.filter { it != name }
    }

    data class TextEditorFile(val path: String, val name: String, val content: String)
    private val _textEditorFile = MutableStateFlow<TextEditorFile?>(null)
    val textEditorFile: StateFlow<TextEditorFile?> = _textEditorFile.asStateFlow()

    fun openTextFile(file: java.io.File) = viewModelScope.launch {
        try {
            val content = withContext(Dispatchers.IO) { SafeTextFile.read(fileManagerRoot, file) }
            _textEditorFile.value = TextEditorFile(content.file.absolutePath, content.file.name, content.text)
        } catch (e: Exception) { _errorFlow.tryEmit(e.message ?: str(R.string.err_open_text)) }
    }
    fun closeTextFile() { _textEditorFile.value = null }
    fun saveTextFile(path: String, text: String) = viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) { SafeTextFile.write(fileManagerRoot, java.io.File(path), text) }
            _textEditorFile.value = TextEditorFile(path, java.io.File(path).name, text)
            _messageFlow.tryEmit(str(R.string.msg_file_saved))
            refreshFiles()
        } catch (e: Exception) { _errorFlow.tryEmit(e.message ?: str(R.string.err_save_text)) }
    }
    fun canEditTextFile(file: java.io.File) = file.isFile && SafeTextFile.isSupported(file)

    /** 请求在线玩家列表（发送 list 命令，结果通过日志解析全量校正名单） */
    fun refreshOnlinePlayers() {
        if (!repo.termuxRuntime.isMcRunning()) return
        playerManager.requestOnlineList()
    }

    /** 刷新玩家数据（OP/白名单/封禁列表） */
    fun refreshPlayers() {
        if (!isBootstrapped.value) return
        val dirName = activeDirName() ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    _ops.value = playerManager.readOps(dirName)
                    _whitelist.value = playerManager.readWhitelist(dirName)
                    _bannedPlayers.value = playerManager.readBanned(dirName)
                    // 同步白名单开关状态（从 server.properties 读取）
                    val props = if (isPowerNukkitXActive()) powerNukkitXConfigManager.read(dirName)
                    else propertiesManager.readProperties(dirName)
                    _whitelistEnabled.value = props["white-list"]?.equals("true", ignoreCase = true) == true
                    // 同步默认 OP 等级（op-permission-level）
                    _defaultOpLevel.value = props["op-permission-level"]?.toIntOrNull()
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s265, e.message))
            }
        }
    }

    /**
     * 命令发送后延迟刷新，等待 MC 完成回写 JSON 文件
     * @param msg 操作成功消息
     * @param refresh 是否需要刷新列表（kick 不改列表无需刷新）
     */
    private fun afterCmd(sent: Boolean, msg: String, refresh: Boolean = true) {
        if (!sent) {
            _errorFlow.tryEmit(str(R.string.s266))
            return
        }
        _messageFlow.tryEmit(msg)
        if (refresh) {
            viewModelScope.launch {
                delay(600)  // 等待 MC 处理命令并回写 JSON
                refreshPlayers()
            }
        }
    }

    fun opPlayer(name: String) {
        if (name.isBlank()) return
        val sent = playerManager.opPlayer(name)
        afterCmd(sent, str(R.string.s1055, name))
    }

    /** 设置 OP 并指定等级（1-4）。手动指定等级需重启服务器才生效到权限系统。 */
    fun opPlayerWithLevel(name: String, level: Int) {
        if (name.isBlank()) return
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit(str(R.string.s266))
            return
        }
        viewModelScope.launch {
            val sent = withContext(Dispatchers.IO) {
                playerManager.opPlayerWithLevel(name, level, dirName)
            }
            afterCmd(sent, str(R.string.s1056, name, level))
        }
    }

    fun deopPlayer(name: String) {
        if (name.isBlank()) return
        val sent = playerManager.deopPlayer(name)
        afterCmd(sent, str(R.string.msg_op_cancelled, name))
    }

    fun whitelistAdd(name: String) {
        if (name.isBlank()) return
        val sent = playerManager.whitelistAdd(name)
        afterCmd(sent, str(R.string.msg_whitelist_added, name))
    }

    fun whitelistRemove(name: String) {
        if (name.isBlank()) return
        val sent = playerManager.whitelistRemove(name)
        afterCmd(sent, str(R.string.msg_whitelist_removed, name))
    }

    /** 切换白名单开关 */
    fun toggleWhitelist(enabled: Boolean) {
        val sent = if (enabled) playerManager.whitelistOn() else playerManager.whitelistOff()
        if (sent) {
            _whitelistEnabled.value = enabled
            // 同步镜像：概览页的白名单提示读的是 [serverProperties]，只改 _whitelistEnabled
            // 的话要等下一次读配置文件才会更新，提示会慢半拍。
            _serverProperties.value = _serverProperties.value.toMutableMap()
                .apply { put("white-list", enabled.toString()) }
            _messageFlow.tryEmit(if (enabled) str(R.string.msg_whitelist_toggled_on) else str(R.string.msg_whitelist_toggled_off))
        } else {
            _errorFlow.tryEmit(str(R.string.s274))
        }
    }

    fun kickPlayer(name: String, reason: String = "") {
        if (name.isBlank()) return
        val sent = playerManager.kickPlayer(name, reason)
        afterCmd(sent, str(R.string.msg_kicked, name), refresh = false)
    }

    fun banPlayer(name: String, reason: String = "Banned by admin") {
        if (name.isBlank()) return
        val sent = playerManager.banPlayer(name, reason)
        afterCmd(sent, str(R.string.msg_banned, name))
    }

    /** 限时封禁 */
    fun tempBanPlayer(name: String, duration: String, reason: String = "") {
        if (name.isBlank() || duration.isBlank()) return
        val sent = playerManager.tempBanPlayer(name, duration, reason)
        afterCmd(sent, str(R.string.msg_banned_temp, name, duration))
    }

    fun pardonPlayer(name: String) {
        if (name.isBlank()) return
        val sent = playerManager.pardonPlayer(name)
        afterCmd(sent, str(R.string.msg_pardoned, name))
    }

    /** 请求在线玩家列表（发送 list 命令） */
    fun requestOnlinePlayers() {
        val sent = playerManager.requestOnlineList()
        if (sent) {
            _messageFlow.tryEmit(str(R.string.s279))
        } else {
            _errorFlow.tryEmit(str(R.string.s280))
        }
    }

    /** 设置玩家游戏模式 */
    fun setGameMode(name: String, mode: Int) {
        if (name.isBlank()) return
        val sent = playerManager.setGameMode(name, mode)
        val modeNameRes = when (mode.coerceIn(0, 3)) {
            0 -> R.string.s281; 1 -> R.string.s282; 2 -> R.string.s283; 3 -> R.string.s284; else -> R.string.s281
        }
        afterCmd(sent, str(R.string.s285, name, str(modeNameRes)), refresh = false)
    }

    /** 给玩家经验 */
    fun giveXp(name: String, amount: Int) {
        if (name.isBlank() || amount <= 0) return
        val sent = playerManager.giveXp(name, amount)
        afterCmd(sent, str(R.string.msg_xp_given, name, amount), refresh = false)
    }

    // ── 世界（存档）管理 ────────────────────────────────────────────

    private val worldManager: WorldManager by lazy { WorldManager(repo.termuxRuntime) }

    /** 世界导入包体积上限。导入会先整体落到 cacheDir，不设上限会把设备存储填满。 */
    private val maxWorldImportBytes = 2L * 1024 * 1024 * 1024

    /**
     * 世界目录相关操作的前置校验：服务端运行中时不允许碰存档目录。
     *
     * 运行中删改世界目录会得到部分删除的存档或损坏的 level.dat，而且不可逆；
     * 读取（导出）也会打出撕裂的快照，正好让「出事后用来恢复的备份」失去价值。
     *
     * @return true 表示可以继续执行
     */
    private fun requireServerStopped(action: String): Boolean {
        if (!repo.serverState.value.isRunning) return true
        _errorFlow.tryEmit("请先停止服务端，再$action")
        return false
    }

    private val _worlds = MutableStateFlow<List<Worlds.WorldInfo>>(emptyList())
    val worlds: StateFlow<List<Worlds.WorldInfo>> = _worlds.asStateFlow()

    private val _worldsLoading = MutableStateFlow(false)
    val worldsLoading: StateFlow<Boolean> = _worldsLoading.asStateFlow()

    /** 当前 level-name 指向的世界名（可能尚未生成对应目录）。 */
    private val _activeWorldName = MutableStateFlow("")
    val activeWorldName: StateFlow<String> = _activeWorldName.asStateFlow()

    /** 加载世界列表（含各世界占用大小）。大小统计较慢，放在 IO 线程。 */
    fun loadWorlds() {
        if (!isBootstrapped.value) return
        val dirName = activeDirName() ?: return
        viewModelScope.launch {
            _worldsLoading.value = true
            try {
                val list = withContext(Dispatchers.IO) { worldManager.listWorlds(dirName) }
                _worlds.value = list
                _activeWorldName.value = withContext(Dispatchers.IO) { worldManager.readLevelName(dirName) }
            } catch (e: Exception) {
                _errorFlow.tryEmit("读取世界列表失败：${e.message}")
            } finally {
                _worldsLoading.value = false
            }
        }
    }

    /**
     * 切换当前使用的世界（写入 level-name）。
     *
     * 服务端运行中时改动不会立刻生效，需要重启，这里明确提示而不是静默写入。
     */
    fun setActiveWorld(name: String) {
        if (!isBootstrapped.value) { _errorFlow.tryEmit(str(R.string.s192)); return }
        val dirName = activeDirName() ?: return
        viewModelScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { worldManager.setLevelName(dirName, name) }
                if (ok) {
                    _activeWorldName.value = name
                    _messageFlow.tryEmit(
                        if (repo.serverState.value.isRunning) "已切换到世界「$name」，重启服务端后生效"
                        else "已切换到世界「$name」"
                    )
                    loadWorlds()
                } else {
                    // setLevelName 现在能区分「写不进去」与「目标实例正在运行」，别再一律
                    // 报成配置不可写——用户会去翻存储权限，而真正原因可能只是要停服。
                    _errorFlow.tryEmit(
                        if (repo.termuxRuntime.isMcRunningIn(dirName)) {
                            str(R.string.world_switch_blocked_running)
                        } else {
                            str(R.string.world_switch_failed)
                        }
                    )
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit("切换世界失败：${e.message}")
            }
        }
    }

    /** 清空 level-seed，让服务端下次生成地图时使用随机种子。 */
    fun clearWorldSeed() {
        if (!isBootstrapped.value) { _errorFlow.tryEmit(str(R.string.s192)); return }
        val dirName = activeDirName() ?: return
        viewModelScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { worldManager.clearLevelSeed(dirName) }
                if (ok) _messageFlow.tryEmit("已清空世界种子，下次生成地图时使用随机种子")
                else _errorFlow.tryEmit("清空种子失败：当前服务端类型不支持该设置")
            } catch (e: Exception) {
                _errorFlow.tryEmit("清空种子失败：${e.message}")
            }
        }
    }

    /** 导出世界到用户选择的位置（先打包到导出目录，再复制到 SAF 目标）。 */
    fun exportWorldToUri(worldName: String, uri: Uri) {
        if (!isBootstrapped.value) { _errorFlow.tryEmit(str(R.string.s192)); return }
        val dirName = activeDirName() ?: return
        if (!requireServerStopped("导出世界")) return
        viewModelScope.launch {
            try {
                val path = worldManager.exportWorld(dirName, worldName)
                if (path == null) {
                    _errorFlow.tryEmit("导出失败：世界目录不存在或存储不可写")
                    return@launch
                }
                exportPathToUri(java.io.File(path), uri)
            } catch (e: Exception) {
                _errorFlow.tryEmit("导出失败：${e.message}")
            }
        }
    }

    /**
     * 从用户选择的压缩包导入世界。
     *
     * 先复制到 cache 再解压：SAF 的 InputStream 只能顺序读一次，而解压需要随机读取
     * 条目头部（[Worlds.inspectZip] 会独立打开一次）。
     */
    fun importWorldFromUri(uri: Uri, preferredName: String) {
        if (!isBootstrapped.value) { _errorFlow.tryEmit(str(R.string.s192)); return }
        val dirName = activeDirName() ?: return
        if (!requireServerStopped("导入世界")) return
        viewModelScope.launch {
            var temp: java.io.File? = null
            try {
                temp = withContext(Dispatchers.IO) {
                    val f = java.io.File(app.cacheDir, "world_import_${System.currentTimeMillis()}.zip")
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        f.outputStream().use { output ->
                            // 边拷边计数：先全量落盘再判断大小，设备已经被填满了
                            val buf = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val read = input.read(buf)
                                if (read < 0) break
                                total += read
                                if (total > maxWorldImportBytes) {
                                    throw RuntimeException("压缩包超过 2 GB，已中止导入")
                                }
                                output.write(buf, 0, read)
                            }
                        }
                    } ?: throw RuntimeException("无法读取所选文件")
                    f
                }
                val imported = withContext(Dispatchers.IO) {
                    worldManager.importWorld(dirName, temp, preferredName)
                }
                if (imported != null) {
                    _messageFlow.tryEmit("世界已导入为「$imported」")
                    loadWorlds()
                } else {
                    _errorFlow.tryEmit("导入失败：压缩包内没有找到世界（缺少 level.dat / region / db）")
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit("导入失败：${e.message}")
            } finally {
                // NonCancellable：协程已取消时 finally 里的 withContext 会立刻抛出取消异常，
                // 直接调用会让临时 zip 残留在 cacheDir 里。
                withContext(NonCancellable + Dispatchers.IO) { temp?.delete() }
            }
        }
    }

    /**
     * 重置世界：先自动导出一份安全备份，再删除目录，让服务端下次启动重新生成。
     *
     * 备份失败时不会删除任何文件（由 [WorldManager.resetWorld] 保证），这里按
     * backupFailed -> success 的顺序判断，避免把「没删成」提示成「已重置」，
     * 也避免把「已删除但没备份」提示成「已中止」。
     */
    fun resetWorld(worldName: String) {
        if (!isBootstrapped.value) { _errorFlow.tryEmit(str(R.string.s192)); return }
        val dirName = activeDirName() ?: return
        if (!requireServerStopped("重置世界")) return
        viewModelScope.launch {
            try {
                val result = worldManager.resetWorld(dirName, worldName, backupFirst = true)
                val backupPath = result.backupPath
                when {
                    result.backupFailed -> _errorFlow.tryEmit("未能在删除前创建安全备份，已中止重置（存档未改动）")
                    !result.success || backupPath == null -> _errorFlow.tryEmit("重置失败：世界目录不存在或无法删除")
                    else -> _messageFlow.tryEmit(
                        "世界「$worldName」已重置，备份保存在 ${java.io.File(backupPath).parentFile?.absolutePath ?: "导出目录"}"
                    )
                }
                loadWorlds()
            } catch (e: Exception) {
                _errorFlow.tryEmit("重置失败：${e.message}")
            }
        }
    }

    // ── 崩溃报告 ────────────────────────────────────────────────────

    private val crashReportManager = CrashReportManager(repo.termuxRuntime)

    private val _crashReports = MutableStateFlow<List<CrashReportManager.CrashReport>>(emptyList())
    val crashReports: StateFlow<List<CrashReportManager.CrashReport>> = _crashReports.asStateFlow()

    /** 加载崩溃报告列表 */
    fun loadCrashReports() {
        if (!isBootstrapped.value) return
        viewModelScope.launch {
            try {
                _crashReports.value = withContext(Dispatchers.IO) {
                    val native = config.value.installedCores.flatMap { core ->
                        crashReportManager.listNativeCrashReports(core.dirName)
                    }
                    (crashReportManager.listCrashReports() + native).sortedByDescending { it.createdTime }
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s287, e.message))
            }
        }
    }

    /** 当前查看的崩溃报告全文（供 UI 展示） */
    private val _currentCrashContent = MutableStateFlow<String?>(null)
    val currentCrashContent: StateFlow<String?> = _currentCrashContent.asStateFlow()
    private val _currentCrashAnalysis = MutableStateFlow<CrashReportAnalyzer.Analysis?>(null)
    val currentCrashAnalysis: StateFlow<CrashReportAnalyzer.Analysis?> = _currentCrashAnalysis.asStateFlow()

    /** Shows a structured report when validation fails before a process can create its own crash file. */
    private fun showStartupFailureReport(error: Throwable) {
        val text = buildString {
            appendLine(str(R.string.startup_report_title))
            appendLine(str(R.string.startup_report_time, timeNow()))
            appendLine(str(R.string.startup_report_exception, error::class.java.name, error.message ?: str(R.string.startup_report_no_detail)))
            appendLine()
            append(error.stackTraceToString())
        }
        _currentCrashContent.value = text
        _currentCrashAnalysis.value = CrashReportAnalyzer.analyze(text)
    }

    private fun showStartupFailureReport(failure: McServerController.StartupFailure) {
        viewModelScope.launch(Dispatchers.IO) {
            val report = failure.reportPath?.let { runCatching { File(it).readText() }.getOrNull() }
            val text = report ?: buildString {
                appendLine(str(R.string.startup_report_title))
                appendLine(str(R.string.startup_report_time, timeNow()))
                appendLine(str(R.string.startup_report_exitcode, failure.code))
                appendLine(str(R.string.startup_report_reason, failure.detail))
                appendLine(str(R.string.startup_report_hint))
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                _currentCrashContent.value = text
                _currentCrashAnalysis.value = CrashReportAnalyzer.analyze(text)
            }
        }
    }

    /** 读取崩溃报告全文 */
    fun readCrashReport(fileName: String) {
        viewModelScope.launch {
            try {
                _currentCrashContent.value = withContext(Dispatchers.IO) { java.io.File(_crashReports.value.firstOrNull { it.fileName == fileName }?.path ?: "").takeIf { it.isFile }?.readText() }
                _currentCrashAnalysis.value = _currentCrashContent.value?.let(CrashReportAnalyzer::analyze)
                if (_currentCrashContent.value == null) {
                    _errorFlow.tryEmit(str(R.string.s288))
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s289, e.message))
            }
        }
    }

    /** 关闭崩溃报告详情视图 */
    fun clearCrashContent() {
        _currentCrashContent.value = null
        _currentCrashAnalysis.value = null
    }

    /** 删除崩溃报告 */
    fun deleteCrashReport(fileName: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { crashReportManager.deleteCrashReport(fileName) }
                _messageFlow.tryEmit(str(R.string.s290, fileName))
                loadCrashReports()
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s291, e.message))
            }
        }
    }

    /** 清空所有崩溃报告 */
    /** 读取应用自身崩溃日志（全局 uncaught handler 写入），无则返回 null */
    fun appCrashLog(): String? {
        val dir = java.io.File(app.filesDir, "home")
        val candidates = listOf(
            java.io.File(dir, "crash_log.txt"),
            java.io.File(dir, "crash_log_read.txt")
        )
        val file = candidates.firstOrNull { it.exists() && it.length() > 0 } ?: return null
        return try {
            val text = file.readText()
            if (text.length > 4000) text.takeLast(4000) else text
        } catch (_: Exception) {
            null
        }
    }

    /** 清空应用崩溃日志文件 */
    fun clearAppCrashLog() {
        try {
            val dir = java.io.File(app.filesDir, "home")
            java.io.File(dir, "crash_log.txt").delete()
            java.io.File(dir, "crash_log_read.txt").delete()
        } catch (_: Exception) {
        }
    }

    fun clearCrashReports() {
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) { crashReportManager.clearAllCrashReports() }
                _messageFlow.tryEmit(str(R.string.s292, count))
                loadCrashReports()
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s293, e.message))
            }
        }
    }

    // ── 定时备份配置 ────────────────────────────────────────────────

    fun setAutoBackupInterval(min: Int) = updateConfig {
        it.copy(autoBackupIntervalMin = if (min <= 0) 0 else min.coerceIn(5, 10080))
    }
    fun setAutoBackupType(type: com.mineserve.mobile.data.AutoBackupType) =
        updateConfig { it.copy(autoBackupType = type) }
    fun setMaxSnapshots(max: Int) = updateConfig { it.copy(maxSnapshots = max.coerceIn(1, 100)) }

    // ── 服务端核心下载相关 ──────────────────────────────────────────

    /** 可用版本列表（从 API 获取，供 DownloadScreen 选择） */
    private val _availableVersions = MutableStateFlow<List<String>>(emptyList())
    val availableVersions: StateFlow<List<String>> = _availableVersions.asStateFlow()
    private var versionsLoadToken = 0L
    private val _versionHints = MutableStateFlow<Map<String, String?>>(emptyMap())
    val versionHints: StateFlow<Map<String, String?>> = _versionHints.asStateFlow()

    fun supportedGameVersion(coreVersion: String): String? = _versionHints.value[coreVersion]

    /** 版本列表加载中 */
    private val _isLoadingVersions = MutableStateFlow(false)
    val isLoadingVersions: StateFlow<Boolean> = _isLoadingVersions.asStateFlow()

    /** 服务端核心下载中 */
    private val _isDownloadingCore = MutableStateFlow(false)
    val isDownloadingCore: StateFlow<Boolean> = _isDownloadingCore.asStateFlow()

    /** 核心下载进度信息（已下载字节、总字节、速度 bytes/s），仅在下载中有效 */
    data class DownloadProgress(
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = -1L,
        val speedBytesPerSec: Long = 0L
    ) {
        /** 速度的格式化文本，如 "2.35 MB/s" 或 "128 KB/s" */
        val speedText: String
            get() = formatSpeed(speedBytesPerSec)
        /** 进度百分比（0-100），总字节未知时为 0 */
        val percent: Int
            get() = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
        /** 已下载大小文本，如 "12.5 MB" */
        val downloadedText: String
            get() = formatSize(downloadedBytes)
        /** 总大小文本，如 "58.0 MB"，未知时为 "?" */
        val totalText: String
            get() = if (totalBytes > 0) formatSize(totalBytes) else "?"

        private fun formatSpeed(bytesPerSec: Long): String {
            val mb = bytesPerSec / 1024.0 / 1024.0
            val kb = bytesPerSec / 1024.0
            return when {
                mb >= 1.0 -> String.format("%.2f MB/s", mb)
                kb >= 1.0 -> String.format("%.0f KB/s", kb)
                else -> "$bytesPerSec B/s"
            }
        }
        private fun formatSize(bytes: Long): String {
            val mb = bytes / 1024.0 / 1024.0
            val kb = bytes / 1024.0
            return when {
                mb >= 1.0 -> String.format("%.1f MB", mb)
                kb >= 1.0 -> String.format("%.0f KB", kb)
                else -> "$bytes B"
            }
        }
    }

    private val _downloadProgress = MutableStateFlow(DownloadProgress())
    val downloadProgress: StateFlow<DownloadProgress> = _downloadProgress.asStateFlow()

    /** 加载指定核心的可用版本列表 */
    fun loadVersions(core: ServerCore) {
        val token = ++versionsLoadToken
        _availableVersions.value = emptyList()
        _versionHints.value = emptyMap()
        _isLoadingVersions.value = true
        viewModelScope.launch {
            try {
                val options = controller.fetchVersionOptions(core)
                if (token != versionsLoadToken) return@launch
                val versions = options.map { it.version }
                _availableVersions.value = versions
                _versionHints.value = options.associate { it.version to it.supportedGameVersion }
                if (core == ServerCore.PowerNukkitX && config.value.selectedCore == core &&
                    config.value.mcVersion == "latest" && versions.isNotEmpty()) {
                    updateConfig { it.copy(mcVersion = versions.first()) }
                }
            } catch (e: Exception) {
                if (token != versionsLoadToken) return@launch
                _errorFlow.tryEmit(str(R.string.s294, e.message))
                _availableVersions.value = emptyList()
                _versionHints.value = emptyMap()
            } finally {
                if (token == versionsLoadToken) _isLoadingVersions.value = false
            }
        }
    }

    /** 下载服务端核心（使用自定义名称，保存到独立目录），成功返回 true */
    fun downloadCore(customName: String) {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit(str(R.string.s192))
            return
        }
        if (_isDownloadingCore.value) return
        if (customName.isBlank()) {
            _errorFlow.tryEmit(str(R.string.s295))
            return
        }
        // 重名拦截：重名会让列表里出现两个无法区分的条目，且历史上会共用同一目录。
        // 这里在下载前直接拒绝，并提示改用别的名字（目录名即便被去重，显示名仍会撞车）。
        controller.duplicateNameOf(customName, config.value)?.let { existing ->
            _errorFlow.tryEmit(str(R.string.dl_duplicate_name, existing))
            return
        }
        _isDownloadingCore.value = true
        _downloadProgress.value = DownloadProgress()
        viewModelScope.launch {
            try {
                controller.downloadCore(config.value, customName.trim()) { downloaded, total, speed ->
                    _downloadProgress.value = DownloadProgress(downloaded, total, speed)
                }
                _messageFlow.tryEmit(str(R.string.s296, customName.trim()))
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s297, e.message))
            } finally {
                _isDownloadingCore.value = false
                _downloadProgress.value = DownloadProgress()
            }
        }
    }

    /** 选择要启动的核心（按名称） */
    fun setActiveCore(name: String) {
        // 只切核心，不动运行模式 —— 运行模式由用户在「运行环境」弹窗里决定并完全生效。
        updateConfig { it.copy(activeCoreName = name) }
    }

    /** 删除一个已安装的核心（按名称） */
    /** 校验已安装核心；未知核心只检查原文件，绝不自动下载或覆盖。 */
    fun verifyOrRepairCore(dirName: String) {
        val cfg = config.value
        val core = cfg.installedCores.firstOrNull { it.dirName == dirName } ?: return
        if (!isBootstrapped.value) { _errorFlow.tryEmit(str(R.string.s192)); return }
        viewModelScope.launch {
            try {
                val serverDir = repo.termuxRuntime.serverDirFor(dirName)
                val entryName = core.serverFile?.trim()?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() }
                if (entryName == null) {
                    _errorFlow.tryEmit(str(R.string.err_core_unknown_no_repair, "未指定启动文件"))
                    return@launch
                }
                val jar = java.io.File(serverDir, entryName)
                val valid = withContext(Dispatchers.IO) {
                    if (!jar.isFile) false
                    else if (core.core == com.mineserve.mobile.data.ServerCore.Unknown) true
                    else runCatching {
                        java.util.jar.JarFile(jar).use { jf -> jf.manifest != null }
                    }.getOrDefault(false)
                }
                if (valid) {
                    _messageFlow.tryEmit(str(R.string.msg_core_verify_ok, core.name, formatCoreSize(jar.length())))
                    return@launch
                }
                if (core.core == com.mineserve.mobile.data.ServerCore.Unknown) {
                    _errorFlow.tryEmit(str(R.string.err_core_unknown_no_repair, entryName))
                    return@launch
                }
                _messageFlow.tryEmit(str(R.string.msg_core_repairing, core.name))
                _isDownloadingCore.value = true
                try {
                    val downloadConfig = cfg.copy(selectedCore = core.core, mcVersion = core.version)
                    withContext(Dispatchers.IO) {
                        controller.downloadCoreTo(jar.absolutePath, downloadConfig, dirName) { _, _, _ -> }
                    }
                    _messageFlow.tryEmit(str(R.string.msg_core_repaired, core.name))
                } finally {
                    _isDownloadingCore.value = false
                }
            } catch (e: Exception) {
                _isDownloadingCore.value = false
                _errorFlow.tryEmit(str(R.string.err_core_repair_fail, e.message))
            }
        }
    }

    private fun formatCoreSize(bytes: Long): String =
        if (bytes >= 1024 * 1024) "%.1f MB".format(bytes / 1024.0 / 1024.0)
        else if (bytes >= 1024) "%.1f KB".format(bytes / 1024.0)
        else "$bytes B"

    /**
     * 删除一个已安装核心（磁盘目录 + 配置登记）。
     *
     * ## 必须用仓库的权威内存态，不能用 UI 侧快照
     * 原先这里读的是 [config]`.value`（`WhileSubscribed(5s)` 的 StateFlow）。
     * 用户离开下载页超过 5 秒后该流停止收集，`config.value` 会停留在旧值；
     * 此时执行删除，写回的就是一份「把已删核心又带回来」的陈旧列表。
     *
     * 现在改为 [ServerRepository.updateAndSaveConfig]，读改写全程在仓库的
     * 权威内存态上原子完成，不存在陈旧快照覆盖新状态的窗口。
     *
     * ## 目录不存在时也要清理登记
     * 磁盘目录可能因删除中断、外部文件管理器操作、或历史 bug 残留而先一步消失。
     * 这时若直接报错返回，配置里的幽灵记录会永远清不掉（本函数的存在意义之一
     * 就是让用户能手动清掉这类残留）。因此：目录不存在视为「已经删干净」，
     * 继续清理登记信息。
     */
    fun deleteCore(name: String) {
        // 兜底：下面是对含世界存档的目录做整目录 deleteRecursively()，运行中执行会得到部分删除的存档且不可逆
        if (!requireServerStopped("删除服务器核心")) return
        viewModelScope.launch {
            try {
                val cfg = repo.currentConfig()
                val core = cfg.installedCores.find { it.name == name }
                    ?: throw RuntimeException(str(R.string.msg_core_not_found, name))
                val dir = repo.termuxRuntime.serverDirFor(core.dirName)
                val existed = withContext(Dispatchers.IO) { dir.exists() }
                if (existed) {
                    val deleted = withContext(Dispatchers.IO) { dir.deleteRecursively() }
                    if (!deleted) {
                        _errorFlow.tryEmit(str(R.string.s299, dir.absolutePath))
                        return@launch
                    }
                } else {
                    termux3Log("deleteCore: 目录已不存在，仅清理登记 ${dir.absolutePath}")
                }
                // 原子读改写 + 落盘；based on 权威态，不会把陈旧列表写回去
                repo.updateAndSaveConfig { current ->
                    val updated = current.installedCores.filter { it.name != name }
                    current.copy(
                        installedCores = updated,
                        activeCoreName = if (current.activeCoreName == name) {
                            updated.firstOrNull()?.name
                        } else {
                            current.activeCoreName
                        }
                    )
                }
                _messageFlow.tryEmit(str(R.string.s300, name))
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s301, e.message))
            }
        }
    }

    /** 轻量日志：删除等关键操作留痕，便于用户排查状态残留问题。 */
    private fun termux3Log(message: String) {
        runCatching { repo.termuxRuntime.emitLog("[core] $message") }
    }

    // ── 文件管理 ────────────────────────────────────────────────────

    data class FileEntry(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val isEditable: Boolean,
        val sizeBytes: Long,
        val sizeText: String,
        val lastModified: Long,
        val modifiedText: String
    )

    private val _fileList = MutableStateFlow<List<FileEntry>>(emptyList())
    val fileList: StateFlow<List<FileEntry>> = _fileList.asStateFlow()

    private val _currentPath = MutableStateFlow("")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    /** 文件管理根目录（home/servers/） */
    val fileManagerRoot: java.io.File
        get() = java.io.File(repo.termuxRuntime.installer.rootDir, "home/servers")

    /** 加载指定目录的文件列表 */
    fun loadFiles(path: java.io.File) {
        viewModelScope.launch {
            try {
                _currentPath.value = path.absolutePath
                _fileList.value = withContext(Dispatchers.IO) {
                    if (!path.exists() || !path.isDirectory) {
                        emptyList()
                    } else {
                        // SimpleDateFormat 创建较贵，且非线程安全；在单线程的 map 内复用一个实例，
                        // 避免大目录（成百上千文件）下每个文件都 new 一次带来的 GC 与构造开销。
                        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                        path.listFiles()?.sortedWith(
                            compareByDescending<java.io.File> { it.isDirectory }
                                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                        )?.map { f ->
                            FileEntry(
                                name = f.name,
                                path = f.absolutePath,
                                isDirectory = f.isDirectory,
                                isEditable = f.isFile && SafeTextFile.isSupported(f),
                                sizeBytes = if (f.isFile) f.length() else 0L,
                                sizeText = if (f.isFile) formatBytes(f.length()) else "",
                                lastModified = f.lastModified(),
                                modifiedText = formatter.format(java.util.Date(f.lastModified()))
                            )
                        } ?: emptyList()
                    }
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s302, e.message))
            }
        }
    }

    /** 加载文件管理根目录 */
    fun loadFilesRoot() {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit(str(R.string.s192))
            return
        }
        loadFiles(fileManagerRoot)
    }

    /** 向上返回上级目录 */
    fun navigateUp() {
        val current = java.io.File(_currentPath.value)
        val parent = current.parentFile
        if (parent != null && parent.absolutePath.startsWith(fileManagerRoot.absolutePath)) {
            loadFiles(parent)
        }
    }

    /** 刷新当前目录文件列表 */
    fun refreshFiles() {
        loadFiles(java.io.File(_currentPath.value))
    }

    /** 打开 MC 终端前初始化日志 */
    fun launchMcConsole() {
        // 确保终端日志色标已处理
        _messageFlow.tryEmit(str(R.string.s303))
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    /** 删除文件或目录 */
    fun deleteFile(file: java.io.File) {
        viewModelScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    if (file.isDirectory) file.deleteRecursively() else file.delete()
                }
                if (ok) {
                    _messageFlow.tryEmit(str(R.string.s304, file.name))
                    loadFiles(java.io.File(_currentPath.value))
                } else {
                    _errorFlow.tryEmit(str(R.string.s305, file.name))
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s236, e.message))
            }
        }
    }

    /** 上传文件：从 Uri 复制到目标目录 */
    fun uploadFile(uri: android.net.Uri, targetDir: java.io.File) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val input = app.contentResolver.openInputStream(uri)
                        ?: throw RuntimeException(str(R.string.err_open_file))
                    val fileName = queryFileName(uri) ?: "uploaded_${System.currentTimeMillis()}"
                    val targetFile = java.io.File(targetDir, fileName)
                    input.use { ins ->
                        java.io.FileOutputStream(targetFile).use { fos ->
                            ins.copyTo(fos)
                        }
                    }
                }
                _messageFlow.tryEmit(str(R.string.s307))
                loadFiles(java.io.File(_currentPath.value))
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s308, e.message))
            }
        }
    }

    /** 导出文件/文件夹到用户选择的 Uri（文件夹打包为 zip） */
    fun exportPathToUri(source: java.io.File, uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = app.contentResolver.openOutputStream(uri)
                        ?: throw RuntimeException(str(R.string.err_open_export))
                    output.use { os ->
                        if (source.isDirectory) {
                            // 文件夹打包为 zip
                            java.util.zip.ZipOutputStream(os).use { zos ->
                                source.walkTopDown().filter { it.isFile }.forEach { f ->
                                    val entryName = source.toURI().relativize(f.toURI()).path
                                    zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                                    f.inputStream().use { it.copyTo(zos) }
                                    zos.closeEntry()
                                }
                            }
                        } else {
                            source.inputStream().use { it.copyTo(os) }
                        }
                    }
                }
                _messageFlow.tryEmit(str(R.string.s309))
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s257, e.message))
            }
        }
    }

    /** 导出整个服务器核心目录（打包 zip） */
    fun exportServerToUri(uri: android.net.Uri) {
        val dirName = activeDirName() ?: return
        val dir = java.io.File(fileManagerRoot, dirName)
        if (!dir.exists()) {
            _errorFlow.tryEmit(str(R.string.s310))
            return
        }
        exportPathToUri(dir, uri)
    }

    /** 从 Uri 查询文件名 */
    private fun queryFileName(uri: android.net.Uri): String? {
        return try {
            val cursor = app.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && it.moveToFirst()) it.getString(nameIndex) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 创建新目录 */
    fun createDirectory(parent: java.io.File, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    java.io.File(parent, name).mkdirs()
                }
                if (ok) {
                    _messageFlow.tryEmit(str(R.string.s311, name))
                    loadFiles(java.io.File(_currentPath.value))
                } else {
                    _errorFlow.tryEmit(str(R.string.s312))
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s313, e.message))
            }
        }
    }

    /**
     * init 块必须放在类体末尾：这里启动的协程（Dispatchers.IO/Default）会在
     * 构造函数完成前抢先执行，若 init 在类体前部，会读到尚未赋值的字段
     * （historyMutex/playerManager 等），导致启动时 NullPointerException 崩溃。
     */
    init {
        loadPlayerHistory()
        scanUnregisteredServers()
        viewModelScope.launch {
            config.map { it.activeCoreName }.distinctUntilChanged().collect { loadPlayerHistory() }
        }
        // 把「当前 Java 来源 + PRoot 发行版」投影到运行时。
        //
        // 运行时是启动链路（含 MCP、桌面组件、定时任务等拿不到 McConfig 的调用方）
        // 唯一的判定依据，因此这份同步必须跟着 config 走；只在这里写一次，
        // 避免每个调用点各自从 config 里取一遍（那种分散读取正是「换个入口行为就变了」
        // 的来源）。用 distinctUntilChanged 避免无谓写入。
        viewModelScope.launch {
            config
                .map { it.selectedJavaVendor to it.prootDistro }
                .distinctUntilChanged()
                .collect { (vendor, distro) ->
                    repo.termuxRuntime.selectedProotVendor = vendor
                    repo.termuxRuntime.selectedProotDistro = distro
                }
        }
        // 起动完成后先把端口真源读一次。
        //
        // 历史上这份真源只在进入「服务器配置」页时才被读 —— 于是用户在配置文件里
        // 把端口改掉之后，只要没再进过那个页面，地址栏、复制出来的地址、以及 STUN 的
        // 转发目标就全部停在旧端口上（默认 25565）。用户看到的「本地地址没跟着变」
        // 就是这个时间差。
        viewModelScope.launch {
            app.isBootstrapped.collect { ready -> if (ready) refreshServerPropertiesNow() }
        }
        viewModelScope.launch {
            var wasRunning = false
            serverState.collect { state ->
                if (wasRunning && !state.isRunning) {
                    interruptPlayerSessions()
                    _lastRunLog.value = withContext(Dispatchers.IO) {
                        delay(150)
                        repo.termuxRuntime.readLastMcRunLog()
                    }
                }
                wasRunning = state.isRunning
            }
        }
        startServerResourceCollection()
        // 订阅 serverExitFailures 前先补弹最近的失败（覆盖 App 重启/ViewModel 重建时已错过的事件）
        controller.recentStartupFailure()?.let { showStartupFailureReport(it) }
        // 订阅 consoleFlow，使用环形缓冲 + 批量刷新，避免每行 O(n) 拷贝。
        viewModelScope.launch(Dispatchers.Default) {
            controller.startupFailures.collect { showStartupFailureReport(it) }
        }
        viewModelScope.launch(Dispatchers.Default) {
            repo.termuxRuntime.consoleFlow.collect { line ->
                consoleBuffer.add(line)
                pendingConsoleBuffer.add(line)
                parseConsoleLine(line)
            }
        }
        // Process only newly received lines. Older terminal rows keep their identity across UI flushes.
        viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                // 无 UI 订阅（后台/无页面），或界面不可见（Activity 已停止但 collectAsState 不销毁、订阅数仍 >0）
                // 时长时间睡眠；控制台可见时以帧友好的频率刷新。
                if (!uiVisible ||
                    (_consoleLines.subscriptionCount.value <= 0 &&
                        _consolePreviewLines.subscriptionCount.value <= 0 &&
                        _terminalConsoleLines.subscriptionCount.value <= 0)
                ) {
                    delay(IDLE_LOG_FLUSH_MS)
                    continue
                }
                delay(200)
                val batch = pendingConsoleBuffer.snapshotAndClear()
                if (batch.isNotEmpty()) {
                    consoleGeneration++
                }
                if (terminalDisplayDirty) {
                    terminalConsoleBuffer.replace(
                        consoleBuffer.snapshot().map { TerminalLogProcessor.process(it, _logTranslationEnabled.value) }
                    )
                    terminalDisplayDirty = false
                    terminalGeneration++
                } else if (batch.isNotEmpty()) {
                    batch.forEach { terminalConsoleBuffer.add(TerminalLogProcessor.process(it, _logTranslationEnabled.value)) }
                    terminalGeneration++
                }
                if (_consoleLines.subscriptionCount.value > 0 && consoleUiGeneration != consoleGeneration) {
                    _consoleLines.value = consoleBuffer.snapshot()
                    consoleUiGeneration = consoleGeneration
                }
                if (_terminalConsoleLines.subscriptionCount.value > 0 && terminalUiGeneration != terminalGeneration) {
                    _terminalConsoleLines.value = terminalConsoleBuffer.snapshot()
                    terminalUiGeneration = terminalGeneration
                }
                val now = System.currentTimeMillis()
                if (_consolePreviewLines.subscriptionCount.value > 0 &&
                    previewUiGeneration != consoleGeneration &&
                    now - lastPreviewPublishedAtMs >= CONSOLE_PREVIEW_FLUSH_MS
                ) {
                    _consolePreviewLines.value = consoleBuffer.last(8)
                    previewUiGeneration = consoleGeneration
                    lastPreviewPublishedAtMs = now
                }
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                // 空闲降频：LogsScreen 不在前台、或 Activity 已停止（collectAsState 不销毁订阅数仍 >0）时，
                // 每 200ms 醒一次纯属浪费（App 退到后台仍每秒唤醒 5~10 次，是待机耗电的主要来源）。
                // 注意这里只延长"发布"的间隔，buffer 仍在持续接收，不会丢日志。
                if (!uiVisible || _termuxLines.subscriptionCount.value <= 0) {
                    delay(IDLE_LOG_FLUSH_MS)
                    continue
                }
                delay(LOG_FLUSH_MS)
                val batch = legacyTermuxBuffer.snapshotAndClear()
                if (batch.isNotEmpty()) {
                    val current = _termuxLines.value
                    _termuxLines.value = (current + batch).takeLast(MAX_LOG_LINES).toImmutableList()
                }
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                // 同上：终端会话面板不可见时降频；界面不在前台（uiVisible=false）同样降频。
                val terminalVisible = _terminalSessions.subscriptionCount.value > 0 ||
                    _activeTerminalSessionId.subscriptionCount.value > 0
                if (!uiVisible || !terminalVisible) {
                    delay(IDLE_LOG_FLUSH_MS)
                    continue
                }
                delay(LOG_FLUSH_MS)
                val batch = synchronized(terminalOutputBuffers) {
                    terminalOutputBuffers.mapValues { (_, buffer) -> buffer.snapshotAndClear() }
                        .filterValues { it.isNotEmpty() }
                }
                batch.forEach { (id, lines) ->
                    updateTerminalSession(id) { session ->
                        val additions = lines.map { TerminalLogProcessor.process(it, false) }
                        session.copy(lines = (session.lines + additions).takeLast(MAX_LOG_LINES).toImmutableList())
                    }
                }
            }
        }
    }

    // ── 后台保活（开机自启 / 周期保活） ─────────────────────────────

    /** 本地化字符串（ViewModel 中获取资源） */
    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)

    private fun metaPrefs() =
        app.getSharedPreferences(BootReceiver.META_PREFS, android.content.Context.MODE_PRIVATE)

    /** 开机自启动开关状态 */
    fun isBootAutoStart(): Boolean = metaPrefs().getBoolean(BootReceiver.KEY_BOOT_AUTO_START, false)

    /** 设置开机自启动：开启时立即拉起前台服务，关闭时停止 */
    fun setBootAutoStart(v: Boolean) {
        metaPrefs().edit().putBoolean(BootReceiver.KEY_BOOT_AUTO_START, v).apply()
        if (v) startKeepAliveService() else stopKeepAliveService()
    }

    /** 设置服务器事件通知等级（0 关闭 / 1 仅重要 / 2 全部）。 */
    fun setEventNotifyLevel(value: Int) {
        ServerEventNotifier.setLevel(app, value.coerceIn(0, 2))
        _eventNotifyLevel.value = value.coerceIn(0, 2)
    }

    /** 后台周期保活开关状态（默认开启） */
    fun isKeepAliveEnabled(): Boolean = metaPrefs().getBoolean(BootReceiver.KEY_KEEP_ALIVE, true)

    /** 设置后台周期保活：开启时调度 WorkManager 周期任务，关闭时取消 */
    fun setKeepAliveEnabled(v: Boolean) {
        metaPrefs().edit().putBoolean(BootReceiver.KEY_KEEP_ALIVE, v).apply()
        if (v) scheduleKeepAlive() else cancelKeepAlive()
    }

    /** 拉起前台保活服务 */
    fun startKeepAliveService() {
        try {
            val intent = android.content.Intent(app, McForegroundService::class.java)
                .apply { action = McForegroundService.ACTION_START }
            app.startForegroundService(intent)
        } catch (e: Exception) {
            _errorFlow.tryEmit(str(R.string.s314, e.message))
        }
    }

    /** 停止前台保活服务 */
    fun stopKeepAliveService() {
        try {
            val intent = android.content.Intent(app, McForegroundService::class.java)
                .apply { action = McForegroundService.ACTION_STOP }
            app.startService(intent)
        } catch (e: Exception) {
            // 忽略
        }
    }

    private fun scheduleKeepAlive() {
        val request = androidx.work.PeriodicWorkRequestBuilder<KeepAliveWorker>(15, java.util.concurrent.TimeUnit.MINUTES).build()
        androidx.work.WorkManager.getInstance(app).enqueueUniquePeriodicWork(
            "keep_alive", androidx.work.ExistingPeriodicWorkPolicy.UPDATE, request
        )
    }

    private fun cancelKeepAlive() {
        androidx.work.WorkManager.getInstance(app).cancelUniqueWork("keep_alive")
    }
}
