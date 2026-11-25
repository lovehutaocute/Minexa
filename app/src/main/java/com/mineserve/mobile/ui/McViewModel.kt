package com.mineserve.mobile.ui

// 性能修改理由：日志只在有新增内容时发布不可变快照，并降低资源采样频率以减少界面重组。
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mineserve.mobile.R
import com.mineserve.mobile.McApplication
import android.net.Uri
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.AutomationTask
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
import com.mineserve.mobile.data.BdsEnvStatus
import com.mineserve.mobile.data.BdsChannel
import com.mineserve.mobile.data.BdsCoreStatus
import com.mineserve.mobile.data.BdsUpdateProgress
import com.mineserve.mobile.data.BdsVersionEntry
import com.mineserve.mobile.data.BdsVersionListState
import com.mineserve.mobile.data.InstalledCore
import com.mineserve.mobile.data.ServerRepository
import com.mineserve.mobile.data.ServerState
import com.mineserve.mobile.data.LegacyPrivateStore
import com.mineserve.mobile.data.StartupPhase
import com.mineserve.mobile.runtime.ConsoleLineParser
import com.mineserve.mobile.runtime.NativeLibraryBundler
import com.mineserve.mobile.data.TunnelState
import com.mineserve.mobile.data.FrpProfile
import com.mineserve.mobile.data.DiagnosticCheck
import com.mineserve.mobile.data.DiagnosticReport
import com.mineserve.mobile.data.DiagnosticStatus
import com.mineserve.mobile.data.ServerResourceStats
import com.mineserve.mobile.data.ServerEventNotifier
import com.mineserve.mobile.data.AppRelease
import com.mineserve.mobile.server.BackupManager
import com.mineserve.mobile.server.CrashReportManager
import com.mineserve.mobile.server.CrashReportAnalyzer
import com.mineserve.mobile.server.CrashInsight
import com.mineserve.mobile.server.CrashKnowledgeBase
import com.mineserve.mobile.server.CrashLogView
import com.mineserve.mobile.server.McServerController
import com.mineserve.mobile.server.PlayerManager
import com.mineserve.mobile.server.PlayerNames
import com.mineserve.mobile.server.BdsEnvironmentManager
import com.mineserve.mobile.server.BdsPacks
import com.mineserve.mobile.server.BdsWorldGen
import com.mineserve.mobile.server.JavaResourcePack
import com.mineserve.mobile.server.JavaWorldGen
import com.mineserve.mobile.server.JavaWorlds
import com.mineserve.mobile.server.WorldGenLedger
import com.mineserve.mobile.server.PluginManager
import com.mineserve.mobile.server.ServerPropertiesManager
import com.mineserve.mobile.server.tunnel.StunDiagnostics
import com.mineserve.mobile.server.tunnel.frpc.FrpcEngineConfig
import com.mineserve.mobile.server.PowerNukkitXConfigManager
import com.mineserve.mobile.server.ServerImporter
import com.mineserve.mobile.server.TunnelManager
import com.mineserve.mobile.server.WorldManager
import com.mineserve.mobile.server.Worlds
import com.mineserve.mobile.ui.vm.AppUpdateDelegate
import com.mineserve.mobile.ui.vm.AutomationTasksDelegate
import com.mineserve.mobile.ui.vm.BdsAddonsDelegate
import com.mineserve.mobile.ui.vm.BdsCoreUpdateDelegate
import com.mineserve.mobile.ui.vm.BdsNetworkProbeDelegate
import com.mineserve.mobile.ui.vm.BaseDependenciesDelegate
import com.mineserve.mobile.ui.vm.ConsoleLogDelegate
import com.mineserve.mobile.ui.vm.CoreDownloadDelegate
import com.mineserve.mobile.ui.vm.CoreVersionsDelegate
import com.mineserve.mobile.ui.vm.DiagnosticsDelegate
import com.mineserve.mobile.ui.vm.CrashDetailsDelegate
import com.mineserve.mobile.ui.vm.CrashReportFilesDelegate
import com.mineserve.mobile.ui.vm.CuratedPluginUpdatesDelegate
import com.mineserve.mobile.ui.vm.EasyTierEngineDelegate
import com.mineserve.mobile.ui.vm.ExternalBackupsDelegate
import com.mineserve.mobile.ui.vm.FileBrowserDelegate
import com.mineserve.mobile.ui.vm.FrpcEngineDelegate
import com.mineserve.mobile.ui.vm.InstalledCoresDelegate
import com.mineserve.mobile.ui.vm.JavaAddonsDelegate
import com.mineserve.mobile.ui.vm.JavaRuntimeDelegate
import com.mineserve.mobile.ui.vm.KeepAliveDelegate
import com.mineserve.mobile.ui.vm.LegacyMigrationDelegate
import com.mineserve.mobile.ui.vm.ModrinthDiscoveryDelegate
import com.mineserve.mobile.ui.vm.ModsDelegate
import com.mineserve.mobile.ui.vm.PhpRuntimeDelegate
import com.mineserve.mobile.ui.vm.ProotContainerDelegate
import com.mineserve.mobile.ui.vm.PlayerHistoryDelegate
import com.mineserve.mobile.ui.vm.PlayerListsDelegate
import com.mineserve.mobile.ui.vm.PluginDownloadsDelegate
import com.mineserve.mobile.ui.vm.ServerIconDelegate
import com.mineserve.mobile.ui.vm.ServerImportDelegate
import com.mineserve.mobile.ui.vm.ServerPropertiesDelegate
import com.mineserve.mobile.ui.vm.SnapshotsDelegate
import com.mineserve.mobile.ui.vm.TerminalSessionsDelegate
import com.mineserve.mobile.ui.vm.TextEditorDelegate
import com.mineserve.mobile.ui.vm.TrustStoreRepairDelegate
import com.mineserve.mobile.ui.vm.TunnelControlsDelegate
import com.mineserve.mobile.ui.vm.formatDiagnosticBytes
import com.mineserve.mobile.ui.vm.modrinthLoader
import com.mineserve.mobile.ui.vm.previewLayers as parsePreviewLayers
import com.mineserve.mobile.ui.vm.WorldCreationDelegate
import com.mineserve.mobile.ui.vm.WorldListDelegate
import com.mineserve.mobile.ui.vm.WorldMaintenanceDelegate
import com.mineserve.mobile.ui.vm.WorldTransferDelegate
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.collections.immutable.ImmutableList
import kotlinx.serialization.Serializable
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.InetSocketAddress
import java.net.Socket
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.content.Context
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
    private val tunnelManager: TunnelManager,
    private val bdsEnvironment: BdsEnvironmentManager
) : ViewModel() {
    val config: StateFlow<McConfig> = repo.configFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), McConfig()
    )

    val serverState: StateFlow<ServerState> = repo.serverState

    // ── BDS 专属运行环境 ─────────────────────────────────────────
    //
    // 与 Java / Pojav 那套运行环境**没有任何交集**：它没有 JDK，不进
    // `installedJava` 列表，也不参与 `selectedJavaRuntime` 的选择。
    // 界面上单独一栏「BDS 专属」，正是为了不让用户把它误认成某个 Java 版本。

    /** BDS 运行环境（容器 + box64）的状态；跨页共享，切页不丢进度 */
    val bdsEnvStatus: StateFlow<BdsEnvStatus> = bdsEnvironment.status

    /** 重新读一遍磁盘（进环境页、授权变化、安装结束后调用） */
    fun refreshBdsEnvironment() = bdsEnvironment.refresh()

    /**
     * 用户手动触发初始化。
     *
     * 刻意**不**在启动服务器时自动调用：首次要下几十兆、耗时几分钟，
     * 塞进启动路径只会让用户以为卡死。启动时若未就绪会直接报错并指到这里。
     */
    fun initializeBdsEnvironment() {
        // 未授权写公共存储时初始化必然失败，先给出可照做的提示
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit(str(R.string.bds_env_need_bootstrap))
            return
        }
        bdsEnvironment.startInitialize()
    }

    fun cancelBdsEnvironment() = bdsEnvironment.cancel()

    /** 只删 box64 与就绪标记，容器留给 Java 版继续用 */
    fun removeBdsBox64() = bdsEnvironment.removeBox64()

    // ── 在线模式（正版验证）出网自检 ───────────────────────────────

    private val bdsNetworkProbeDelegate by lazy {
        BdsNetworkProbeDelegate(
            context = app,
            scope = viewModelScope,
            activeDirName = ::activeDirName,
            runProbe = repo.termuxRuntime::probeBdsNetwork,
            emitLog = repo.termuxRuntime::emitLog,
            reportError = { _errorFlow.tryEmit(it) },
            reportMessage = { _messageFlow.tryEmit(it) }
        )
    }

    val bdsProbeRunning: StateFlow<Boolean> get() = bdsNetworkProbeDelegate.bdsProbeRunning

    /**
     * 在容器里探一次 DNS / TCP / CA，把结论写进控制台。
     *
     * 用户开正版服时只会看到 BDS 那句 `Could not connect to Minecraft services`，
     * 完全分不清是 DNS 污染、TCP 被墙、还是容器缺证书。这里替他把三段拆开测，
     * 并给出下一步该改哪里。
     */
    fun probeBdsNetwork() = bdsNetworkProbeDelegate.probeBdsNetwork()

    // ── BDS 核心版本与在线更新 ───────────────────────────────────
    //
    // 与上面「BDS 专属运行环境」是两件独立的事：运行环境是容器 + box64
    // （决定**能不能跑**），这里是官方核心本体（决定**跑的是哪个版本**）。
    // 版本来自 Mojang 官方 links 接口，更新流程见 `BdsUpdater`。

    private val bdsCoreUpdateDelegate by lazy {
        BdsCoreUpdateDelegate(
            app = app,
            scope = viewModelScope,
            controller = controller,
            serverState = serverState,
            activeBdsCore = ::activeBdsCore,
            reportMessage = { _messageFlow.tryEmit(it) },
            reportError = { _errorFlow.tryEmit(it) }
        )
    }

    val bdsCoreStatus: StateFlow<BdsCoreStatus> get() = bdsCoreUpdateDelegate.bdsCoreStatus

    val bdsUpdateState: StateFlow<BdsUpdateProgress> get() = bdsCoreUpdateDelegate.bdsUpdateState

    /** 历史版本清单（「BDS 核心版本 → 历史版本」弹窗的数据源） */
    val bdsVersions: StateFlow<List<BdsVersionEntry>> get() = bdsCoreUpdateDelegate.bdsVersions

    val bdsVersionsState: StateFlow<BdsVersionListState> get() = bdsCoreUpdateDelegate.bdsVersionsState

    /** 当前选中的服务器若为官方 BDS，返回它的登记信息 */
    private fun activeBdsCore(): InstalledCore? {
        val cfg = config.value
        val core = cfg.installedCores.firstOrNull { it.name == cfg.activeCoreName } ?: return null
        return core.takeIf { it.core == ServerCore.Bds }
    }

    /**
     * 检测官方 BDS 版本（当前 / 稳定 / Preview）。
     *
     * @param force true 时绕过 10 分钟缓存（用户点「检查更新」）
     */
    fun checkBdsCoreUpdate(force: Boolean = false) = bdsCoreUpdateDelegate.checkBdsCoreUpdate(force)

    /**
     * 加载 BDS 历史版本清单（进「历史版本」弹窗时调）。
     *
     * 失败不清空已有列表：用户可能只是想刷新一下，弱网下拉空一张白名单
     * 比保留上一次结果 + 一行错误更糟。
     */
    fun loadBdsVersions(force: Boolean = false) = bdsCoreUpdateDelegate.loadBdsVersions(force)

    /**
     * 把当前 BDS 服务器更新到 [channel] 上的官方最新构建。
     *
     * 服务器在运行时先优雅停服（`stop` → 等待退出）；停不下来就放弃更新，
     * 绝不在运行中替换核心 —— 那样进程会继续用旧 inode，界面却显示已更新。
     */
    fun updateBdsCore(channel: BdsChannel) = bdsCoreUpdateDelegate.updateBdsCore(channel)

    /**
     * 把当前 BDS 服务器换成 [version]（任意历史版本，可升可降）。
     *
     * 版本清单里 1.21.62+ 的条目只有版本号，直链与 SHA-256 要按需取一次
     * metadata；这一步在弹窗里点「安装」后立刻发生，失败就只报错、不动磁盘。
     */
    fun installBdsVersion(version: String) = bdsCoreUpdateDelegate.installBdsVersion(version)

    /**
     * 重新下载上次失败的版本。
     *
     * 只对「校验失败 / 下载中断」有意义：这两种情况的服务器目录一个字节都没动，
     * 重来一次是安全的；如果失败原因是「服务器停不下来」或「备份失败」，
     * 重试同样会立刻失败，所以不额外拦截 —— 用户看到的还是同一条原因。
     */
    fun retryBdsInstall() = bdsCoreUpdateDelegate.retryBdsInstall()

    /** 关闭更新结果卡片（成功/失败提示） */
    fun dismissBdsUpdateState() = bdsCoreUpdateDelegate.dismissBdsUpdateState()

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
    val installSpeed: StateFlow<Long> get() = baseDependenciesDelegate.installSpeed

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

    private val consoleLogDelegate by lazy {
        ConsoleLogDelegate(
            app = app,
            scope = viewModelScope,
            termuxRuntime = repo.termuxRuntime,
            activeDirName = ::activeDirName,
            isUiVisible = { uiVisible },
            maxLogLines = MAX_LOG_LINES,
            idleLogFlushMs = IDLE_LOG_FLUSH_MS
        )
    }

    val consoleLines: StateFlow<ImmutableList<String>> get() = consoleLogDelegate.consoleLines

    /** 终端专用的后台预处理日志，避免 Compose 主线程翻译和关键词扫描。 */
    val terminalConsoleLines: StateFlow<ImmutableList<TerminalDisplayLine>> get() = consoleLogDelegate.terminalConsoleLines
    val logTranslationEnabled: StateFlow<Boolean> get() = consoleLogDelegate.logTranslationEnabled

    /** 服务端最近一次运行从启动到退出写入 latest.log 的完整内容。 */
    private val _lastRunLog = MutableStateFlow("")
    val lastRunLog: StateFlow<String> = _lastRunLog.asStateFlow()

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
    val consolePreviewLines: StateFlow<ImmutableList<String>> get() = consoleLogDelegate.consolePreviewLines

    // ── Termux 终端（会话面板） ─────────────────────────────

    val termuxLines: StateFlow<ImmutableList<String>> get() = terminalSessionsDelegate.termuxLines

    val termuxBusy: StateFlow<Boolean> get() = terminalSessionsDelegate.termuxBusy

    private val terminalSessionsDelegate by lazy {
        TerminalSessionsDelegate(
            app = app,
            scope = viewModelScope,
            termuxRuntime = repo.termuxRuntime,
            isBootstrapped = isBootstrapped,
            isUiVisible = { uiVisible },
            maxLogLines = MAX_LOG_LINES,
            logFlushMs = LOG_FLUSH_MS,
            idleLogFlushMs = IDLE_LOG_FLUSH_MS
        )
    }

    val terminalSessions: StateFlow<List<TerminalSession>> get() = terminalSessionsDelegate.terminalSessions
    val activeTerminalSessionId: StateFlow<String> get() = terminalSessionsDelegate.activeTerminalSessionId

    fun createTerminalSession() = terminalSessionsDelegate.createTerminalSession()

    fun selectTerminalSession(id: String) = terminalSessionsDelegate.selectTerminalSession(id)

    fun setLogTranslationEnabled(enabled: Boolean) = consoleLogDelegate.setLogTranslationEnabled(enabled)

    fun closeTerminalSession(id: String) = terminalSessionsDelegate.closeTerminalSession(id)

    fun executeTerminalCommand(id: String, command: String) = terminalSessionsDelegate.executeTerminalCommand(id, command)

    /** 向当前 Termux 会话中的命令发送一行 stdin。 */
    fun sendTerminalInput(id: String, input: String) = terminalSessionsDelegate.sendTerminalInput(id, input)

    /** 清空指定终端会话的显示行（保留会话本身） */
    fun clearTerminalSession(id: String) = terminalSessionsDelegate.clearTerminalSession(id)

    /** 清空 MC 控制台缓冲 */
    fun clearConsole() = consoleLogDelegate.clearConsole()

    /** 进入 MC 终端时异步补读磁盘日志，实时输出仍由 consoleFlow 持续追加。 */
    fun loadRecentTerminalLog() = consoleLogDelegate.loadRecentTerminalLog()

    /** 执行 Termux shell 命令（IO 线程，输出实时追加到 termuxLines，命令回显 $ cmd） */
    fun execTermuxCommand(command: String) = terminalSessionsDelegate.execTermuxCommand(command)

    // ── 服务器图标（server-icon.png） ────────────────────────

    private val serverIconDelegate by lazy {
        ServerIconDelegate(
            context = app,
            scope = viewModelScope,
            activeDirName = ::activeDirName,
            serverDirFor = { dirName -> repo.termuxRuntime.serverDirFor(dirName) },
            reportError = { _errorFlow.tryEmit(it) },
            reportMessage = { _messageFlow.tryEmit(it) }
        )
    }

    /** 图标变更信号（UI 用于重载预览） */
    val serverIconVersion: StateFlow<Int> get() = serverIconDelegate.serverIconVersion

    /** 当前核心的 server-icon.png 文件（不存在返回 null） */
    fun serverIconFile(): File? = serverIconDelegate.serverIconFile()

    /** 更换服务器图标：读取 Uri → 居中裁剪缩放 64×64 → PNG 写入 server-icon.png */
    fun setServerIcon(uri: Uri) = serverIconDelegate.setServerIcon(uri)

    /** 恢复默认：删除 server-icon.png */
    fun removeServerIcon() = serverIconDelegate.removeServerIcon()

    /** 错误消息流，UI 层收集后用 Snackbar 显示 */
    private val _errorFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val errorFlow = _errorFlow.asSharedFlow()

    /** 操作结果消息流，UI 层收集后用 Snackbar 显示 */
    private val _messageFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messageFlow = _messageFlow.asSharedFlow()

    /** 依赖安装中状态，UI 层据此控制按钮和加载动画 */
    private val _isInstalling = MutableStateFlow(false)
    val isInstalling: StateFlow<Boolean> = _isInstalling.asStateFlow()

    val javaOperation: StateFlow<String?> get() = javaRuntimeDelegate.javaOperation
    val javaInFlight: StateFlow<JavaRuntime?> get() = javaRuntimeDelegate.javaInFlight
    val installedJava: StateFlow<Set<JavaRuntime>> get() = javaRuntimeDelegate.installedJava

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

    // ── EasyTier 组网引擎（联网下载） ─────────────────────────────

    /** EasyTier 引擎信息快照，供依赖页展示。结构复用 frpc 的那套。 */
    data class EasyTierEngineInfo(
        val installed: Boolean = false,
        val version: String? = null,
        val sizeBytes: Long = 0,
        val sha256: String? = null
    )

    private val easyTierEngineDelegate by lazy {
        EasyTierEngineDelegate(
            context = app,
            scope = viewModelScope,
            stopEasyTier = tunnelManager::stopEasyTier
        )
    }

    val easyTierEngine: StateFlow<EasyTierEngineInfo> get() = easyTierEngineDelegate.easyTierEngine

    val easyTierEngineMessage: StateFlow<String?> get() = easyTierEngineDelegate.easyTierEngineMessage

    fun clearEasyTierEngineMessage() = easyTierEngineDelegate.clearEasyTierEngineMessage()

    /** 在线下载状态（与 frpc 引擎共用 [DownloadState]）。 */
    val easyTierDownload: StateFlow<DownloadState> get() = easyTierEngineDelegate.easyTierDownload

    /** 刷新 EasyTier 引擎状态（含 SHA-256 计算，走 IO 线程）。 */
    fun refreshEasyTierEngine() = easyTierEngineDelegate.refreshEasyTierEngine()

    fun downloadEasyTierEngine() = easyTierEngineDelegate.downloadEasyTierEngine()

    /** 从用户选中的文件导入 EasyTier 引擎（.so）。 */
    fun importEasyTierEngine(uri: Uri) = easyTierEngineDelegate.importEasyTierEngine(uri)

    fun uninstallEasyTierEngine() = easyTierEngineDelegate.uninstallEasyTierEngine()

    // ── frpc 引擎（内置引擎，不依赖 Termux） ────────────────────────

    /** frpc 引擎信息快照，供隧道页展示「已安装 / 未安装」。 */
    data class FrpcEngineInfo(
        val installed: Boolean = false,
        val version: String? = null,
        val sizeBytes: Long = 0,
        val sha256: String? = null
    )

    private val frpcEngineDelegate by lazy {
        FrpcEngineDelegate(
            context = app,
            scope = viewModelScope,
            frpcRuntime = tunnelManager.frpcRuntime
        )
    }

    val frpcEngine: StateFlow<FrpcEngineInfo> get() = frpcEngineDelegate.frpcEngine

    /** 引擎导入结果提示，一次性消费后清空。 */
    val frpcEngineMessage: StateFlow<String?> get() = frpcEngineDelegate.frpcEngineMessage

    fun clearFrpcEngineMessage() = frpcEngineDelegate.clearFrpcEngineMessage()

    /** 刷新 frpc 引擎状态（含 SHA-256 计算，走 IO 线程）。 */
    fun refreshFrpcEngine() = frpcEngineDelegate.refreshFrpcEngine()

    /** 在线下载状态。未配置下载源时 UI 不显示下载入口，这个状态也就一直是 Idle。 */
    sealed interface DownloadState {
        data object Idle : DownloadState
        data class Downloading(val fraction: Float) : DownloadState
    }

    val frpcDownload: StateFlow<DownloadState> get() = frpcEngineDelegate.frpcDownload

    /** 是否配置了在线下载源；没配置时 UI 只保留本地导入。 */
    val frpcOnlineAvailable: Boolean get() = FrpcEngineConfig.isConfigured

    /** 在线下载并安装引擎。 */
    fun downloadFrpcEngine() = frpcEngineDelegate.downloadFrpcEngine()

    /** 从用户选中的文件导入 frpc 引擎（libfrpc.so）。 */
    fun importFrpcEngine(uri: Uri) = frpcEngineDelegate.importFrpcEngine(uri)

    /** 卸载 frpc 引擎。 */
    fun uninstallFrpcEngine() = frpcEngineDelegate.uninstallFrpcEngine()

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

    private val appUpdateDelegate by lazy {
        AppUpdateDelegate(
            app = app,
            scope = viewModelScope,
            reportMessage = { _messageFlow.tryEmit(it) }
        )
    }

    val updateState: StateFlow<UpdateUiState> get() = appUpdateDelegate.updateState

    /** 更新对话框是否显示 */
    val updateDialogVisible: StateFlow<Boolean> get() = appUpdateDelegate.updateDialogVisible

    /** 最近一次更新检查结果描述（设置页显示），如「已是最新版本 · 08:30」 */
    val lastUpdateCheckResult: StateFlow<String?> get() = appUpdateDelegate.lastUpdateCheckResult

    fun dismissUpdateDialog() = appUpdateDelegate.dismissUpdateDialog()

    /** 检查更新：manual=true 来自设置页（显示检查进度 + 失败提示）；auto=true 启动检查（失败静默 + 有新版发通知） */
    fun checkForUpdate(manual: Boolean = false) = appUpdateDelegate.checkForUpdate(manual)

    fun showUpdateDialog() = appUpdateDelegate.showUpdateDialog()

    fun skipCurrentUpdate() = appUpdateDelegate.skipCurrentUpdate()

    /** 打开浏览器跳到 GitHub Release 页面（供用户手动下载更新） */
    fun openGithubUpdate() = appUpdateDelegate.openGithubUpdate()

    /** 开始下载新版 APK（完成后自动调系统安装器） */
    fun downloadUpdate() = appUpdateDelegate.downloadUpdate()

    /** 调系统安装器安装 APK */
    fun installApk(context: Context, file: File) = appUpdateDelegate.installApk(context, file)

    fun installDownloadedUpdate(path: String) = appUpdateDelegate.installDownloadedUpdate(path)

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
            val dir = active?.let { repo.termuxRuntime.serverDirFor(it.dirName) }
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

    private val diagnosticsDelegate by lazy {
        DiagnosticsDelegate(
            app = app,
            scope = viewModelScope,
            config = config,
            termuxRuntime = repo.termuxRuntime,
            serverState = serverState,
            serverProperties = serverProperties,
            crashReportManager = crashReportManager,
            refreshJava = ::refreshJava,
            refreshDependencies = ::refreshDependencies,
            refreshPhp = ::refreshPhp,
            reportMessage = { _messageFlow.tryEmit(it) },
            reportError = { _errorFlow.tryEmit(it) }
        )
    }

    val diagnosticReport: StateFlow<DiagnosticReport> get() = diagnosticsDelegate.diagnosticReport
    val isDiagnosing: StateFlow<Boolean> get() = diagnosticsDelegate.isDiagnosing
    val isRepairingRuntime: StateFlow<Boolean> get() = diagnosticsDelegate.isRepairingRuntime

    fun runDiagnostics() = diagnosticsDelegate.runDiagnostics()

    fun safeRepairRuntime() = diagnosticsDelegate.safeRepairRuntime()

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

    /**
     * BDS `list` 多行响应的收集状态：头行声明人数，后续每行一个玩家名。
     * 收到头行时把期望条数写进 [bdsListExpected]，收齐后一次性替换在线名单。
     */
    private var bdsListExpected = 0
    private val bdsListBuffer = mutableListOf<String>()

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
            // 官方 BDS 的进服/离服措辞与 Java 完全不同（Player connected / disconnected），
            // 且不像 Java 那样在 "list" 响应里带名单，所以必须单独一条分支维护名单与计数。
            val bdsEvent = playerManager.extractBedrockPlayerEvent(line)
            when {
                bdsEvent != null -> {
                    val name = bdsEvent.name
                    // 重连会先 disconnected 再 connected，可能重复打印；名单是否变化
                    // 由 setPlayerOnline 判定，计数与名单同源，重复行不会让计数漂移
                    setPlayerOnline(name, bdsEvent.joined)
                    playerManager.noteBedrockPlayer(name, bdsEvent.xuid)
                    recordPlayerEvent(name, if (bdsEvent.joined) "进服" else "离服")
                    notifyPlayerEvent(name, bdsEvent.joined)
                    handled = true
                }
                playerManager.extractPowerNukkitPlayerEvent(line) != null -> {
                    val (name, joined) = playerManager.extractPowerNukkitPlayerEvent(line)!!
                    setPlayerOnline(name, joined)
                    recordPlayerEvent(name, if (joined) "进服" else "离服")
                    notifyPlayerEvent(name, joined)
                    handled = true
                }
                line.contains("joined the game") -> {
                    // 仅当提取到真实玩家名（日志前缀 + 合法名字）时才计数与记录，避免聊天消息误报
                    playerManager.extractPlayerName(line)?.let { name ->
                        setPlayerOnline(name, true)
                        recordPlayerEvent(name, "进服")
                        notifyPlayerEvent(name, true)
                    }
                    handled = true
                }
                line.contains("left the game") -> {
                    playerManager.extractPlayerName(line)?.let { name ->
                        setPlayerOnline(name, false)
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
                // 全量校正在线玩家名单（list 命令响应）。
                // BDS 的名单在后续行、本行为空，此时传入人数让解析器自己判断该不该清空。
                playerManager.parseOnlinePlayers(line, p.online)?.let { names ->
                    _onlinePlayerNames.value = names
                }
                // BDS 用 `There are N/M players online:` 开头，名字在后续几行里。
                // 记下期望条数，后面的行交给下面的收集逻辑。
                if (p.online > 0 && line.contains("players online:") && !line.contains(" of a max of ")) {
                    bdsListExpected = p.online
                    synchronized(bdsListBuffer) { bdsListBuffer.clear() }
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
            // BDS 的 list 名单逐行到达：头行只声明人数，名字在随后的行里（`- Steve`）。
            // 这里把它们收齐后再整体替换名单，这样重开 App / 前台服务重启后也能恢复在线列表。
            if (!handled && bdsListExpected > 0) {
                val entry = playerManager.parseBedrockListEntry(line)
                if (entry == null) {
                    bdsListExpected = 0
                } else {
                    val done = synchronized(bdsListBuffer) {
                        bdsListBuffer.add(entry)
                        if (bdsListBuffer.size >= bdsListExpected) bdsListBuffer.toList() else null
                    }
                    if (done != null) {
                        bdsListExpected = 0
                        _onlinePlayerNames.value = done
                        repo.updateServerState { it.copy(onlinePlayers = done.size) }
                    }
                }
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
        if (name.isEmpty()) return str(R.string.vm_name_empty)
        val current = config.value
        val target = current.installedCores.firstOrNull { it.name == oldName }
            ?: return str(R.string.vm_server_not_found, oldName)
        if (current.installedCores.any { it.name == name && it.name != oldName }) {
            return str(R.string.vm_server_duplicate, name)
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

    /** 扫描 servers 目录，自动登记未注册的服务器文件夹。 */
    fun scanUnregisteredServers() = installedCoresDelegate.scanUnregisteredServers()

    /** 已安装核心与磁盘对齐：同步外部删除。 */
    fun syncInstalledCoresWithDisk() = installedCoresDelegate.syncInstalledCoresWithDisk()

    // ── 旧版私有目录迁移 ────────────────────────────────────────────

    /**
     * 旧版目录迁移的界面状态。
     *
     * 不做「不再提示」的持久化标记：只要旧目录里还有服务器文件，每次冷启动都要问
     * （用户明确要求「二次启动后依旧显示是否迁移」）。用户在对话框里点「以后再说」
     * 只关闭本次，进程重启后重新扫描。
     */
    data class LegacyMigrationState(
        val candidates: List<com.mineserve.mobile.server.LegacyServerMigration.Candidate> = emptyList(),
        val running: Boolean = false,
        val scanned: Boolean = false,
        val progressDone: Int = 0,
        val progressTotal: Int = 0,
        val progressName: String = "",
        /**
         * 上次失败仅因为个别源文件读不出来（权限/悬空链接）。
         * 此时对话框多给一个「忽略这些文件继续」的出口，否则用户永远卡在同一个文件上。
         */
        val canSkipFailed: Boolean = false,
        /** 上次失败的详细原因（对话框里直接显示，不必去翻控制台） */
        val lastError: String? = null
    ) {
        val visible: Boolean get() = candidates.isNotEmpty() && !running
    }

    private val legacyMigrationDelegate by lazy {
        LegacyMigrationDelegate(
            context = app,
            scope = viewModelScope,
            currentConfigOrNull = repo::currentConfigOrNull,
            updateAndSaveConfig = { transform -> repo.updateAndSaveConfig(transform) },
            termux3Log = ::termux3Log,
            reportError = { _errorFlow.tryEmit(it) },
            reportMessage = { _messageFlow.tryEmit(it) },
            refreshStoragePermission = ::refreshStoragePermission
        )
    }

    val legacyMigration: StateFlow<LegacyMigrationState> get() = legacyMigrationDelegate.legacyMigration

    /**
     * 扫描旧版私有目录，发现可迁移的服务器就置为可见（由 McApp 在启动时调用）。
     *
     * 扫描本身只读不写：**绝不**因为「发现了旧文件」就自动搬，搬不搬由用户决定。
     */
    fun checkLegacyMigration() = legacyMigrationDelegate.checkLegacyMigration()

    /** 用户选择「以后再说」：只关闭本次提示，下次启动仍会扫描 */
    fun dismissLegacyMigration() = legacyMigrationDelegate.dismissLegacyMigration()

    /**
     * 执行迁移：复制 → 校验 → 改名 → 删源，然后把结果写回配置。
     *
     * 失败的目录会保留在旧位置，并在提示里列出原因；下次启动仍会再问。
     * 复制的临时目录会保留，重试（含「忽略并继续」）只补缺的文件，不用重搬整棵树。
     *
     * @param skipUnreadable 用户已确认「忽略无法读取的文件」后继续
     */
    fun runLegacyMigration(skipUnreadable: Boolean = false) =
        legacyMigrationDelegate.runLegacyMigration(skipUnreadable)

    fun refreshJava() = javaRuntimeDelegate.refreshJava()

    private val baseDependenciesDelegate by lazy {
        BaseDependenciesDelegate(
            app = app,
            scope = viewModelScope,
            controller = controller,
            termuxRuntime = repo.termuxRuntime,
            isBootstrapped = isBootstrapped,
            isInstalling = isInstalling,
            setInstalling = { _isInstalling.value = it },
            updateInstallSteps = { steps ->
                repo.updateServerState { state -> state.copy(installSteps = steps) }
            },
            reportMessage = { _messageFlow.tryEmit(it) },
            reportError = { _errorFlow.tryEmit(it) }
        )
    }

    fun refreshDependencies() = baseDependenciesDelegate.refreshDependencies()

    // 运行环境的选中统一走 setJavaRuntime（来源 + 版本一起写），
    // 不再提供「只改版本、来源沿用」的入口 —— 那会写出「FCL 来源 + Java 8」
    // 这种上游根本不存在的组合。

    private val javaRuntimeDelegate by lazy {
        JavaRuntimeDelegate(
            app = app,
            scope = viewModelScope,
            termuxRuntime = repo.termuxRuntime,
            config = config,
            isBootstrapped = isBootstrapped,
            isInstalling = isInstalling,
            setInstalling = { _isInstalling.value = it },
            updateAndSaveConfig = { transform -> repo.updateAndSaveConfig(transform) },
            reportMessage = { _messageFlow.tryEmit(it) },
            reportError = { _errorFlow.tryEmit(it) }
        )
    }

    // ── PocketMine-MP 专用 PHP 运行时 ─────────────────────────────
    //
    // PMMP 用 PHP 运行（不是 Java），首次启动时按需下载。
    // 依赖管理页的「PHP 版本选择」与启动控制卡片的运行环境列表共用这一份状态。

    private val phpRuntimeDelegate by lazy {
        PhpRuntimeDelegate(
            app = app,
            scope = viewModelScope,
            termuxRuntime = repo.termuxRuntime,
            config = config,
            isBootstrapped = isBootstrapped,
            isInstalling = isInstalling,
            setInstalling = { _isInstalling.value = it },
            setJavaOperation = { javaRuntimeDelegate.setJavaOperation(it) },
            reportMessage = { _messageFlow.tryEmit(it) },
            reportError = { _errorFlow.tryEmit(it) }
        )
    }

    val installedPhp: StateFlow<Set<PhpVersion>> = phpRuntimeDelegate.installedPhp

    /** 当前所选 PHP 版本（来自 config，配置未加载时回退默认） */
    val selectedPhpVersion: StateFlow<PhpVersion> = phpRuntimeDelegate.selectedPhpVersion

    /** 当前 PHP 版本是否已安装 */
    val phpInstalled: StateFlow<Boolean> = phpRuntimeDelegate.phpInstalled

    fun refreshPhp() = phpRuntimeDelegate.refreshPhp()

    /** 选择 PHP 版本（依赖管理页与启动控制卡片共用） */
    fun setPhpVersion(version: PhpVersion) = updateConfig { it.copy(selectedPhpVersion = version) }

    /** 安装指定 PHP 运行环境（~10MB，含 PMMP 必需扩展） */
    fun installPhp(version: PhpVersion = config.value.selectedPhpVersion) = phpRuntimeDelegate.installPhp(version)

    /** 卸载指定 PHP 运行环境（直接删除对应目录，PocketMine 下次启动会重新下载） */
    fun deletePhp(version: PhpVersion = config.value.selectedPhpVersion) = phpRuntimeDelegate.deletePhp(version)

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
        val required = when (core.core) {
            ServerCore.PocketMine -> RuntimeKind.Php
            // 官方 BDS 既不跑 JVM 也不跑 PHP：它是原生可执行文件，
            // arm64 设备上靠 box64 转译（容器由启动流程自行准备）。
            // 若仍按「Java 核心」要求 runtimeKind=Java，之前开过 PocketMine 的用户
            // 会被一条与 BDS 无关的提示挡住启动。
            ServerCore.Bds -> cfg.runtimeKind
            else -> RuntimeKind.Java
        }
        if (cfg.runtimeKind == required) return null
        val requiredLabel = when (required) {
            RuntimeKind.Java -> str(R.string.dash_runtime_java)
            RuntimeKind.Php -> str(R.string.dash_runtime_php)
        }
        return str(R.string.err_runtime_kind_mismatch, core.name, requiredLabel)
    }

    fun setJavaCardAtBottom(atBottom: Boolean) =
        updateConfig { it.copy(javaCardAtBottom = atBottom) }

    fun installJava(runtime: JavaRuntime) = javaRuntimeDelegate.installJava(runtime)

    fun reinstallJava(runtime: JavaRuntime) = javaRuntimeDelegate.reinstallJava(runtime)

    /** FTP 文件管理根目录（含全部服务器与快照） */
    fun ftpRootDir(): java.io.File = repo.termuxRuntime.serversDir.parentFile
        ?: java.io.File(app.filesDir, "home")

    fun deleteJava(runtime: JavaRuntime) = javaRuntimeDelegate.deleteJava(runtime)

    private val prootContainerDelegate by lazy {
        ProotContainerDelegate(
            app = app,
            scope = viewModelScope,
            termuxRuntime = repo.termuxRuntime,
            isInstalling = isInstalling,
            setJavaOperation = javaRuntimeDelegate::setJavaOperation,
            refreshJava = ::refreshJava,
            reportMessage = { _messageFlow.tryEmit(it) },
            reportError = { _errorFlow.tryEmit(it) }
        )
    }

    fun prootContainerSizeBytes(distro: ProotDistro): Long =
        prootContainerDelegate.prootContainerSizeBytes(distro)

    val prootContainerSizes: StateFlow<Map<ProotDistro, Long>>
        get() = prootContainerDelegate.prootContainerSizes

    fun refreshProotContainerSizes() = prootContainerDelegate.refreshProotContainerSizes()

    fun deleteProotContainer(distro: ProotDistro) = prootContainerDelegate.deleteProotContainer(distro)

    /** 卸载依赖（直接删除 wget/frpc/rclone/proot 文件，不动 Termux 环境） */
    fun deleteDependencies() = baseDependenciesDelegate.deleteDependencies()

    /** 重装依赖 = 先删除再安装 */
    fun reinstallDependencies() = baseDependenciesDelegate.reinstallDependencies()

    /**
     * 选择服务端核心。
     *
     * **即使点的是当前已选中的同一个核心，也要把版本号清空**：
     * 用户点磁贴的动作语义是「我要按这个核心重新配一遍」，此时沿用上一次的版本号
     * 属于替他做决定；尤其在一个核心常有十几个版本的情况下，误带旧版本下载出来的
     * 服务端与预期不符，排查成本很高。清空后界面上版本位显示「请选择版本」，
     * 下载按钮随之禁用，直到用户在下拉里重新挑一个。
     */
    fun selectCore(core: ServerCore) {
        val previous = config.value.selectedCore
        val coreChanged = previous != core
        updateConfig {
            // 基岩版核心（PNX/PocketMine/Allay/BDS）走 UDP，默认端口 19132；Java 系 25565。
            // 迁移规则见 [ServerPort.migrateOnCoreSwitch]：只在 Java 系 ↔ 基岩系之间
            // 对调**默认值**，用户自定义端口（如 25562）原样保留。
            // 同一个核心重复点击不动端口 —— 用户没换核心，端口不该被「迁移」一次。
            val port = if (coreChanged) {
                ServerPort.migrateOnCoreSwitch(it.localPort, it.selectedCore, core)
            } else {
                it.localPort
            }
            it.copy(selectedCore = core, mcVersion = "", localPort = port)
        }
        // 版本号已被清空，必须让用户重新选：给一句明确提示，而不是让他对着
        // 「请选择版本」猜是不是加载失败。
        // BDS 例外：它只有官方当前构建这一项，界面上没有可选版本行，
        // 提示「请重新选择版本」等于把用户带进一个点不动的死胡同。
        if (core != ServerCore.Bds) {
            _messageFlow.tryEmit(str(R.string.msg_version_reselect, core.displayName))
        }
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

    private val automationTasksDelegate by lazy {
        AutomationTasksDelegate(
            app = app,
            updateAndSaveConfig = repo::updateAndSaveConfig,
            reportError = { _errorFlow.tryEmit(it) }
        )
    }

    /** 新增任务（重置排期，由更新逻辑重算）。 */
    fun addAutomationTask(task: AutomationTask) = automationTasksDelegate.addAutomationTask(task)

    /** 覆盖编辑一条任务（重置排期）。 */
    fun updateAutomationTask(task: AutomationTask) = automationTasksDelegate.updateAutomationTask(task)

    fun deleteAutomationTask(id: String) = automationTasksDelegate.deleteAutomationTask(id)

    /** 启用/停用任务；重新启用时重置排期。 */
    fun setAutomationTaskEnabled(id: String, enabled: Boolean) = automationTasksDelegate.setAutomationTaskEnabled(id, enabled)

    /** 手动立即执行一次任务（交由前台服务在自身作用域内完成）。 */
    fun runAutomationTaskNow(id: String) = automationTasksDelegate.runAutomationTaskNow(id)

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
                    appendMirrorTest(str(R.string.vm_mirror_selected, mirror.displayName))
                } else {
                    appendMirrorTest(str(R.string.vm_mirror_all_failed))
                }
            } catch (e: Exception) {
                appendMirrorTest(str(R.string.vm_mirror_test_failed, e.message ?: str(R.string.vm_unknown_error)))
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

    fun installDependencies() = baseDependenciesDelegate.installDependencies()

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
                // 从点下按钮这一刻就进入「启动在途」：下面的重读配置、端口探测也是耗时操作，
                // 期间看门狗探不到进程，不能让它把状态改回已停止。
                repo.termuxRuntime.markStartInFlight()
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
                // 启动失败：撤掉在途保护，让看门狗能如实把状态落回已停止。
                repo.termuxRuntime.clearStartInFlight()
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

    private val tunnelControlsDelegate by lazy {
        TunnelControlsDelegate(
            app = app,
            scope = viewModelScope,
            config = config,
            isBootstrapped = isBootstrapped,
            tunnelManager = tunnelManager,
            updateConfig = ::updateConfig,
            refreshServerPropertiesNow = ::refreshServerPropertiesNow,
            realServerPort = ::realServerPort,
            reportMessage = { _messageFlow.tryEmit(it) },
            reportError = { _errorFlow.tryEmit(it) }
        )
    }

    /** 新增或就地更新一份 FRP 配置（按 id 判定是新增还是编辑）。 */
    fun saveFrpProfile(profile: FrpProfile) = tunnelControlsDelegate.saveFrpProfile(profile)

    /** 删除一份 FRP 配置；删的正好是当前配置时自动切到剩下的第一份。 */
    fun deleteFrpProfile(id: String) = tunnelControlsDelegate.deleteFrpProfile(id)

    /** 切换当前 FRP 配置。 */
    fun setActiveFrpProfile(id: String) = tunnelControlsDelegate.setActiveFrpProfile(id)

    /** 当前生效的 FRP 配置；列表为空时返回 null。 */
    fun activeFrpProfile(): FrpProfile? = tunnelControlsDelegate.activeFrpProfile()

    // ── 启停 ─────────────────────────────────────────────────

    fun startFrp() = tunnelControlsDelegate.startFrp()

    fun stopFrp() = tunnelControlsDelegate.stopFrp()

    fun startStun() = tunnelControlsDelegate.startStun()

    fun stopStun() = tunnelControlsDelegate.stopStun()

    // ── EasyTier 组网 ────────────────────────────────────────
    //
    // 与 FRP / STUN 的区别全在「谁掌握角色」上：那两条是「把本机端口暴露到公网」，
    // 玩家用公网地址进；EasyTier 是把服务器和玩家拉进同一个虚拟局域网，
    // 玩家用**局域网地址**进。所以这里没有「公网地址」的概念，
    // 取而代之的是「本机虚拟 IP」——永远同一台网络下同一个地址。

    /** EasyTier 通道的实时状态（网络页组网卡片订阅）。 */
    val easyTierState: StateFlow<TunnelState> = tunnelManager.easyTierState

    /**
     * 引擎是否可用。未下载时为 false —— UI 据此禁用并给出下载指引。
     * 下载完成后再次调用即返回 true（引擎是延迟加载的）。
     */
    val easyTierAvailable: Boolean get() = tunnelControlsDelegate.easyTierAvailable

    /** 引擎文件是否已下载到本地（与"能否加载"是两回事：加载还依赖首次使用）。 */
    val easyTierEngineInstalled: Boolean get() = easyTierEngineDelegate.easyTierEngineInstalled

    /** 补齐组网身份：已有网络名和密钥不覆盖。 */
    fun ensureEasyTierIdentity() = tunnelControlsDelegate.ensureEasyTierIdentity()

    /** 换一套网络密钥。 */
    fun regenerateEasyTierSecret() = tunnelControlsDelegate.regenerateEasyTierSecret()

    /** 本机在虚拟网里的地址（`虚拟IP:转发端口`）。网络名为空时返回空串。 */
    fun easyTierAddress(): String = tunnelControlsDelegate.easyTierAddress()

    /** 给玩家的整段说明。复制按钮与二维码用同一份文本。 */
    fun easyTierShareText(): String = tunnelControlsDelegate.easyTierShareText()

    fun startEasyTier() = tunnelControlsDelegate.startEasyTier()

    fun stopEasyTier() = tunnelControlsDelegate.stopEasyTier()

    /** 用户在系统 VPN 授权框上点了「拒绝」/直接返回。 */
    fun notifyEasyTierVpnDenied() = tunnelControlsDelegate.notifyEasyTierVpnDenied()

    /** 复制给玩家的整段说明（组网卡片与引导页共用）。 */
    fun copyEasyTierGuide(context: android.content.Context) = tunnelControlsDelegate.copyEasyTierGuide(context)

    /** 复制任意公网地址（FRP 与 STUN 卡片各自传入自己那条通道的地址）。 */
    fun copyPublicUrl(context: android.content.Context, url: String) = tunnelControlsDelegate.copyPublicUrl(context, url)

    fun sendCommand(line: String) {
        try {
            controller.sendCommand(line)
        } catch (e: Exception) {
            _errorFlow.tryEmit(str(R.string.s211, e.message))
        }
    }

    /**
     * 「保存世界」统一入口。
     *
     * 基岩版没有 `save-all`：要发 `save hold` + `save resume` 一对，只发 `hold`
     * 世界就此不再落盘（真机实测）。
     */
    fun saveWorld() {
        val core = config.value.installedCores
            .firstOrNull { it.name == config.value.activeCoreName }?.core
        val commands = core?.consoleSaveCommands ?: listOf("save-all")
        sendCommand(commands.first())
        commands.drop(1).forEachIndexed { index, cmd ->
            viewModelScope.launch {
                kotlinx.coroutines.delay(800L * (index + 1))
                sendCommand(cmd)
            }
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

    private val modsDelegate by lazy {
        ModsDelegate(
            app = app,
            scope = viewModelScope,
            pluginManager = pluginManager,
            activeDirName = ::activeDirName,
            requireServerStopped = ::requireServerStopped,
            reportMessage = { _messageFlow.tryEmit(it) },
            reportError = { _errorFlow.tryEmit(it) }
        )
    }

    /** 真实已安装模组列表 */
    val mods: StateFlow<List<PluginManager.ModEntry>> get() = modsDelegate.mods

    val curatedMods: List<PluginManager.CuratedMod> get() = modsDelegate.curatedMods

    private val pluginDownloadsDelegate by lazy {
        PluginDownloadsDelegate(
            app = app,
            scope = viewModelScope,
            pluginManager = pluginManager,
            activeDirName = ::activeDirName,
            currentModrinthLoader = { modrinthLoader(config.value.selectedCore) },
            refreshMods = ::refreshMods,
            refreshInstalledPlugins = ::refreshInstalledPlugins,
            reportMessage = { _messageFlow.tryEmit(it) },
            reportError = { _errorFlow.tryEmit(it) }
        )
    }

    /** 当前正在下载的插件 id → 进度 */
    val pluginDownloadProgress: StateFlow<Map<String, PluginDownloadProgress>> get() = pluginDownloadsDelegate.pluginDownloadProgress

    /** 是否有任意插件正在下载 */
    val isPluginDownloading: StateFlow<Boolean> get() = pluginDownloadsDelegate.isPluginDownloading

    /** 扫描当前核心的 plugins 目录 */
    fun refreshInstalledPlugins() {
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
    fun refreshMods() = modsDelegate.refreshMods()

    /** 切换模组启用状态 */
    fun toggleModEnabled(fileName: String) = modsDelegate.toggleModEnabled(fileName)

    /** 删除模组 */
    fun deleteMod(fileName: String) = modsDelegate.deleteMod(fileName)

    /** 从本地 Uri 上传模组 */
    fun installModFromUri(uri: Uri) = modsDelegate.installModFromUri(uri)

    /** 从精选库下载安装模组（GitHub 动态解析最新版本） */
    fun installCuratedMod(mod: PluginManager.CuratedMod) = modsDelegate.installCuratedMod(mod)

    /** 当前核心的 mods 目录路径 */
    fun currentModsPath(): String? = modsDelegate.currentModsPath()

    // ── Modrinth 模组获取 ────────────────────────────────────────

    private val modrinthDiscoveryDelegate by lazy {
        ModrinthDiscoveryDelegate(
            scope = viewModelScope,
            pluginManager = pluginManager,
            currentServerVersion = ::currentServerVersion,
            reportMessage = { _messageFlow.tryEmit(str(it)) }
        )
    }

    val modrinthResults: StateFlow<List<PluginManager.ModrinthHit>> get() = modrinthDiscoveryDelegate.modrinthResults

    /** Modrinth 全部可用加载器 */
    val modrinthLoaders: StateFlow<List<String>> get() = modrinthDiscoveryDelegate.modrinthLoaders

    /** Modrinth 支持的 MC 游戏版本（供版本筛选下拉） */
    val modrinthGameVersions: StateFlow<List<String>> get() = modrinthDiscoveryDelegate.modrinthGameVersions

    /** 模组筛选选中的游戏版本（默认当前服务器版本） */
    val selectedModVersion: StateFlow<String> get() = modrinthDiscoveryDelegate.selectedModVersion

    /** 加载 Modrinth 可用加载器列表（仅保留模组专用加载器，与插件池完全隔离） */
    fun loadModrinthLoaders() = modrinthDiscoveryDelegate.loadModrinthLoaders()

    /** 加载 Modrinth 游戏版本列表；默认选中当前服务器版本 */
    fun loadModrinthGameVersions() = modrinthDiscoveryDelegate.loadModrinthGameVersions()

    /** 切换模组筛选的 MC 版本 */
    fun setSelectedModVersion(v: String) = modrinthDiscoveryDelegate.setSelectedModVersion(v)

    val isLoadingMoreMods: StateFlow<Boolean> get() = modrinthDiscoveryDelegate.isLoadingMoreMods

    /** 搜索 Modrinth 模组（多加载器 + 版本筛选 + 排序） */
    fun searchModrinthMods(query: String, loaders: List<String>, sort: String, mcVersion: String) =
        modrinthDiscoveryDelegate.searchModrinthMods(query, loaders, sort, mcVersion)

    /** 分页加载更多 Modrinth 模组（追加到现有结果） */
    fun loadMoreModrinthMods() = modrinthDiscoveryDelegate.loadMoreModrinthMods()

    /** 当前激活核心的 MC 版本（用于版本匹配判断） */
    private fun currentServerVersion(): String {
        val cfg = config.value
        return cfg.installedCores.find { it.name == cfg.activeCoreName }?.version
            ?: cfg.mcVersion
    }

    /** 检测已安装插件冲突（同名 / 同主类） */
    fun detectPluginConflicts(plugins: List<PluginManager.InstalledPlugin>): List<String> =
        pluginManager.detectPluginConflicts(plugins)

    /** 一键安装 Modrinth 模组（解析指定版本 release 直链并下载到 mods/） */
    fun installModrinthMod(hit: PluginManager.ModrinthHit, mcVersion: String) =
        pluginDownloadsDelegate.installModrinthMod(hit, mcVersion)

    // ── Modrinth 插件获取（插件页复用同一下载模块） ───────────────

    val pluginModrinthResults: StateFlow<List<PluginManager.ModrinthHit>> get() = modrinthDiscoveryDelegate.pluginModrinthResults

    val pluginModrinthLoaders: StateFlow<List<String>> get() = modrinthDiscoveryDelegate.pluginModrinthLoaders

    val selectedPluginVersion: StateFlow<String> get() = modrinthDiscoveryDelegate.selectedPluginVersion

    fun loadPluginModrinthLoaders() = modrinthDiscoveryDelegate.loadPluginModrinthLoaders()

    fun loadPluginModrinthVersions() = modrinthDiscoveryDelegate.loadPluginModrinthVersions()

    fun setSelectedPluginVersion(v: String) = modrinthDiscoveryDelegate.setSelectedPluginVersion(v)

    val isLoadingMorePlugins: StateFlow<Boolean> get() = modrinthDiscoveryDelegate.isLoadingMorePlugins

    /** 插件页搜索 Modrinth 插件（project_type=plugin，布局与模组页统一） */
    fun searchModrinthPlugin(query: String, loaders: List<String>, sort: String, mcVersion: String) =
        modrinthDiscoveryDelegate.searchModrinthPlugin(query, loaders, sort, mcVersion)

    /** 分页加载更多 Modrinth 插件（追加到现有结果） */
    fun loadMoreModrinthPlugin() = modrinthDiscoveryDelegate.loadMoreModrinthPlugin()

    /** 安装 Modrinth 插件到 plugins/ 目录 */
    fun installModrinthPlugin(hit: PluginManager.ModrinthHit, mcVersion: String) =
        pluginDownloadsDelegate.installModrinthPlugin(hit, mcVersion)

    /** 从精选库下载安装插件 */
    fun installCuratedPlugin(curated: PluginManager.CuratedPlugin) = pluginDownloadsDelegate.installCuratedPlugin(curated)

    /** 从本地 Uri 上传插件 */
    fun installPluginFromUri(uri: Uri) {
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

    private val curatedPluginUpdatesDelegate by lazy {
        CuratedPluginUpdatesDelegate(
            app = app,
            scope = viewModelScope,
            pluginManager = pluginManager,
            activeDirName = ::activeDirName,
            reportMessage = { _messageFlow.tryEmit(it) },
            reportError = { _errorFlow.tryEmit(it) }
        )
    }

    /** 更新检测结果（key = curated.id） */
    val curatedUpdates: StateFlow<Map<String, PluginManager.CuratedUpdateInfo>> get() = curatedPluginUpdatesDelegate.curatedUpdates

    /** 是否正在检测更新 */
    val isCheckingUpdates: StateFlow<Boolean> get() = curatedPluginUpdatesDelegate.isCheckingUpdates

    /** 检测精选插件更新（5 分钟内有缓存不重复请求） */
    fun checkCuratedUpdates() = curatedPluginUpdatesDelegate.checkCuratedUpdates()

    /** 强制重新检测（清空缓存） */
    fun forceRecheckUpdates() = curatedPluginUpdatesDelegate.forceRecheckUpdates()

    // ── 自定义 URL 下载 ──

    /** 从自定义 URL 下载插件 */
    fun installPluginFromUrl(url: String, customFileName: String) =
        pluginDownloadsDelegate.installPluginFromUrl(url, customFileName)

    /** 判断自定义 URL 是否正在下载 */
    fun isCustomUrlDownloading(): Boolean = pluginDownloadsDelegate.isCustomUrlDownloading()

    /** 获取自定义 URL 下载进度（无则 null） */
    fun customUrlDownloadProgress(): PluginDownloadProgress? = pluginDownloadsDelegate.customUrlDownloadProgress()

    /** 获取当前核心的 plugins 目录路径（用于 UI 显示） */
    fun currentPluginsPath(): String? {
        val dirName = activeDirName() ?: return null
        return java.io.File(repo.termuxRuntime.serverDirFor(dirName), "plugins").absolutePath
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

    private val externalBackupsDelegate by lazy {
        ExternalBackupsDelegate(
            app = app,
            scope = viewModelScope,
            backupManager = backupManager,
            config = config,
            activeDirName = ::activeDirName,
            serversDir = { repo.termuxRuntime.serversDir },
            updateConfig = ::updateConfig,
            reportMessage = { _messageFlow.tryEmit(it) },
            reportError = { _errorFlow.tryEmit(it) }
        )
    }

    /** 创建 world 目录快照（zip 打包），返回快照文件路径或 null */
    suspend fun createSnapshot(): String? = externalBackupsDelegate.createSnapshot()

    /** 更新前备份存档（更新对话框「先备份存档」入口） */
    fun backupBeforeUpdate() = externalBackupsDelegate.backupBeforeUpdate()

    /** 外部备份整个世界（world+nether+end → 外部目录） */
    fun backupWorldToExternal() = externalBackupsDelegate.backupWorldToExternal()

    /** 外部备份整个服务器（world+核心+配置+插件 → 外部目录，文件名带核心类型） */
    fun backupServerToExternal() = externalBackupsDelegate.backupServerToExternal()

    // ── 外部备份导入/还原（含重名检测） ─────────────────────────────

    /** 服务器还原重名冲突（UI 弹框询问） */
    data class RestoreConflict(val zipName: String, val dirName: String)

    val restoreConflict: kotlinx.coroutines.flow.StateFlow<RestoreConflict?> get() = externalBackupsDelegate.restoreConflict

    /** 列出外部备份 zip（供备份页展示） */
    fun externalBackups(): List<java.io.File> = externalBackupsDelegate.externalBackups()

    fun externalBackupInfo(file: java.io.File): BackupManager.ExternalBackupInfo =
        externalBackupsDelegate.externalBackupInfo(file)

    /** 删除外部备份文件 */
    fun deleteExternalBackup(name: String) = externalBackupsDelegate.deleteExternalBackup(name)

    /** 从手机其他位置导入备份 zip 到外部备份目录（SAF 选择） */
    fun importBackupToExternal(uri: android.net.Uri) = externalBackupsDelegate.importBackupToExternal(uri)

    /** 重命名外部备份文件 */
    fun renameExternalBackup(oldName: String, newName: String) = externalBackupsDelegate.renameExternalBackup(oldName, newName)

    /** 校验外部备份 zip 完整性 */
    fun validateExternalBackup(name: String) = externalBackupsDelegate.validateExternalBackup(name)

    /** 从外部 zip 还原世界（zip 内 world/ 前缀） */
    fun restoreWorldFromExternal(zipName: String) = externalBackupsDelegate.restoreWorldFromExternal(zipName)

    /** 请求还原服务器：解析目标目录名与核心类型，存在同名则发冲突事件（UI 弹框） */
    fun requestRestoreServer(zipName: String) = externalBackupsDelegate.requestRestoreServer(zipName)

    /** 确认还原服务器（overwrite=true 覆盖同名） */
    fun confirmRestoreServer(overwrite: Boolean) = externalBackupsDelegate.confirmRestoreServer(overwrite)

    fun dismissRestoreConflict() = externalBackupsDelegate.dismissRestoreConflict()

    // ── 导入服务器（文件夹 / 压缩包 / JAR） ────────────────────────

    private val serverImportDelegate by lazy {
        ServerImportDelegate(
            app = app,
            scope = viewModelScope,
            serverImporter = ServerImporter(app, repo.termuxRuntime),
            updateConfig = ::updateConfig,
            reportMessage = { _messageFlow.tryEmit(it) },
            reportError = { _errorFlow.tryEmit(it) }
        )
    }

    val isImportingServer: StateFlow<Boolean> get() = serverImportDelegate.isImportingServer

    val importProgress: StateFlow<Float?> get() = serverImportDelegate.importProgress

    /** 从外部存储文件夹（SAF tree）导入为新的服务器；displayName 为空时自动解析文件夹名 */
    fun importServerFromFolder(uri: android.net.Uri, displayName: String? = null) =
        serverImportDelegate.importServerFromFolder(uri, displayName)

    /** 从外部存储压缩包（zip/tar/tar.gz/tar.xz/tar.bz2/7z）导入为新的服务器；displayName 为空时自动解析 */
    fun importServerFromArchive(uri: android.net.Uri, displayName: String? = null) =
        serverImportDelegate.importServerFromArchive(uri, displayName)

    /** 从单个服务端 JAR 创建标准服务器目录后导入。 */
    fun importServerFromJar(uri: android.net.Uri, displayName: String? = null) =
        serverImportDelegate.importServerFromJar(uri, displayName)

    /** 为导入确认对话框预填名称：kind 为 folder / archive / jar */
    suspend fun proposeImportName(kind: String, uri: android.net.Uri): String? =
        serverImportDelegate.proposeImportName(kind, uri)


    // ── 备份恢复管理 ────────────────────────────────────────────────

    private val backupManager = BackupManager(repo.termuxRuntime)

    private val snapshotsDelegate by lazy {
        SnapshotsDelegate(
            app = app,
            scope = viewModelScope,
            backupManager = backupManager,
            activeDirName = ::activeDirName,
            runtimeRoot = { repo.termuxRuntime.installer.rootDir },
            requireServerStopped = ::requireServerStopped,
            reportMessage = { _messageFlow.tryEmit(it) },
            reportError = { _errorFlow.tryEmit(it) }
        )
    }

    val snapshots: StateFlow<List<BackupManager.SnapshotInfo>> get() = snapshotsDelegate.snapshots

    /** 加载快照列表 */
    fun loadSnapshots() = snapshotsDelegate.loadSnapshots()

    /** 删除当前激活核心的世界文件夹（world / world_nether / world_the_end），不可恢复 */
    fun deleteWorldDirs() = worldMaintenanceDelegate.deleteWorldDirs()

    /** 恢复快照（会先停止服务器） */
    fun restoreSnapshot(name: String) = snapshotsDelegate.restoreSnapshot(name)

    /** 导出快照 zip 到用户选择的 Uri（自定义导出路径） */
    fun exportSnapshotToUri(snapshotName: String, uri: android.net.Uri) = snapshotsDelegate.exportSnapshotToUri(snapshotName, uri)

    /** 删除快照 */
    fun deleteSnapshot(name: String) = snapshotsDelegate.deleteSnapshot(name)

    // ── server.properties 编辑 ─────────────────────────────────────

    private val propertiesManager = ServerPropertiesManager(repo.termuxRuntime)
    private val powerNukkitXConfigManager = PowerNukkitXConfigManager(repo.termuxRuntime)

    private val serverPropertiesDelegate by lazy {
        ServerPropertiesDelegate(
            app = app,
            scope = viewModelScope,
            config = config,
            propertiesManager = propertiesManager,
            powerNukkitXConfigManager = powerNukkitXConfigManager,
            activeDirName = ::activeDirName,
            requireServerStopped = ::requireServerStopped,
            updateLocalPort = { port -> updateConfig { it.copy(localPort = port) } },
            refreshLanIp = ::refreshLanIp,
            reportMessage = { _messageFlow.tryEmit(it) },
            reportError = { _errorFlow.tryEmit(it) }
        )
    }

    fun isPowerNukkitXActive(): Boolean = serverPropertiesDelegate.isPowerNukkitXActive()

    fun supportedServerPropertyKeys(): Set<String> = serverPropertiesDelegate.supportedServerPropertyKeys()

    val serverProperties: StateFlow<Map<String, String>> get() = serverPropertiesDelegate.serverProperties

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
    val serverPort: StateFlow<Int> = serverPropertiesDelegate.serverPort

    private fun realServerPort(): Int = serverPropertiesDelegate.realServerPort()

    /**
     * 配置文件当前是否已生成（服务端首次启动后才会由服务端写出）。
     *
     * null 表示尚未检测完成，UI 应展示加载态而非误判为"未生成"。
     */
    val serverPropertiesExist: StateFlow<Boolean?> get() = serverPropertiesDelegate.serverPropertiesExist

    /**
     * 加载 server.properties，并同步更新配置文件是否已生成的状态。
     *
     * 「发出去就不管」；需要**读完再取端口**的调用方用 [refreshServerPropertiesNow]。
     */
    fun loadServerProperties() = serverPropertiesDelegate.loadServerProperties()

    private suspend fun refreshServerPropertiesNow() = serverPropertiesDelegate.refreshServerPropertiesNow()

    /** 保存 server.properties */
    fun saveServerProperties(props: Map<String, String>) = serverPropertiesDelegate.saveServerProperties(props)

    // ── 玩家管理 ────────────────────────────────────────────────────

    // 注意：playerManager 等属性被 init 中启动的协程（IO 线程）访问，
    // 声明位置在 init 之后，必须用 lazy 延迟初始化，避免构造函数未完成时读到 null
    private val playerManager: PlayerManager by lazy { PlayerManager(repo.termuxRuntime) }

    private val playerListsDelegate by lazy {
        PlayerListsDelegate(
            app = app,
            scope = viewModelScope,
            playerManager = playerManager,
            propertiesManager = propertiesManager,
            powerNukkitXConfigManager = powerNukkitXConfigManager,
            activeDirName = ::activeDirName,
            isBdsActive = { activeBdsCore() != null },
            isPowerNukkitXActive = ::isPowerNukkitXActive,
            onlinePlayerNames = { _onlinePlayerNames.value },
            serverProperties = { serverProperties.value },
            setWhitelistProperty = { key, enabled -> serverPropertiesDelegate.setWhitelistProperty(key, enabled) },
            reportMessage = { _messageFlow.tryEmit(it) },
            reportError = { _errorFlow.tryEmit(it) }
        )
    }

    val ops: StateFlow<List<PlayerManager.OpEntry>> get() = playerListsDelegate.ops

    val whitelist: StateFlow<List<PlayerManager.WhitelistEntry>> get() = playerListsDelegate.whitelist

    val bannedPlayers: StateFlow<List<PlayerManager.BannedEntry>> get() = playerListsDelegate.bannedPlayers

    /** 白名单开关状态（由 server.properties 的 white-list 控制） */
    val whitelistEnabled: StateFlow<Boolean> get() = playerListsDelegate.whitelistEnabled

    /** 当前 server.properties 配置的默认 OP 等级（null 表示未配置，使用 MC 默认 4） */
    val defaultOpLevel: StateFlow<Int?> get() = playerListsDelegate.defaultOpLevel

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

    private val playerHistoryDelegate by lazy {
        PlayerHistoryDelegate(
            app = app,
            scope = viewModelScope,
            activeDirName = ::activeDirName,
            timeNow = ::timeNow,
            reportMessage = { _messageFlow.tryEmit(it) },
            reportError = { _errorFlow.tryEmit(it) }
        )
    }

    val playerHistory: StateFlow<List<PlayerHistoryEntry>> get() = playerHistoryDelegate.playerHistory

    private fun loadPlayerHistory() = playerHistoryDelegate.loadPlayerHistory()

    private fun recordPlayerEvent(player: String, event: String) = playerHistoryDelegate.recordPlayerEvent(player, event)

    fun playerActivitySummaries(): List<PlayerActivitySummary> = playerHistoryDelegate.playerActivitySummaries()

    fun interruptPlayerSessions() = playerHistoryDelegate.interruptPlayerSessions()

    fun clearPlayerHistory() = playerHistoryDelegate.clearPlayerHistory()

    fun exportPlayerHistory(uri: android.net.Uri) = playerHistoryDelegate.exportPlayerHistory(uri)

    private fun timeNow(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())

    /**
     * 玩家上线/下线的**唯一**入口：改名单 + 同步 `onlinePlayers` 计数。
     *
     * 计数只在名单真的变化时才增减，两条链路永远同源 —— 分开写会让横幅人数
     * （`serverState.onlinePlayers`）与「在线 N」（名单 size）分叉，出现
     * 「2 人在线」但列表只有一行的矛盾显示。判定也靠 [PlayerNames] 的等价关系
     * （前缀 + 忽略大小写），别在这里另写一套 `equals`。
     *
     * @return 名单是否真的变化（重复进服行、玩家本不在名单、同名跨平台无法判断删谁 → false）
     */
    private fun setPlayerOnline(name: String, joined: Boolean): Boolean {
        val cur = _onlinePlayerNames.value
        val next = if (joined) PlayerNames.mergeInto(cur, name) else PlayerNames.removeFrom(cur, name)
        if (next === cur) return false
        _onlinePlayerNames.value = next
        repo.updateServerState {
            it.copy(onlinePlayers = (it.onlinePlayers + if (joined) 1 else -1).coerceAtLeast(0))
        }
        return true
    }

    data class TextEditorFile(val path: String, val name: String, val content: String)
    private val textEditorDelegate by lazy {
        TextEditorDelegate(
            app = app,
            scope = viewModelScope,
            fileManagerRoot = { fileManagerRoot },
            refreshFiles = ::refreshFiles,
            reportMessage = { _messageFlow.tryEmit(it) },
            reportError = { _errorFlow.tryEmit(it) }
        )
    }
    val textEditorFile: StateFlow<TextEditorFile?> get() = textEditorDelegate.textEditorFile

    fun openTextFile(file: java.io.File) = textEditorDelegate.openTextFile(file)
    fun closeTextFile() = textEditorDelegate.closeTextFile()
    fun saveTextFile(path: String, text: String) = textEditorDelegate.saveTextFile(path, text)
    fun canEditTextFile(file: java.io.File) = textEditorDelegate.canEditTextFile(file)

    /** 请求在线玩家列表（发送 list 命令，结果通过日志解析全量校正名单） */
    fun refreshOnlinePlayers() {
        if (!repo.termuxRuntime.isMcRunning()) return
        playerManager.requestOnlineList()
    }

    /** 刷新玩家数据（OP/白名单/封禁列表） */
    fun refreshPlayers() = playerListsDelegate.refreshPlayers()

    fun opPlayer(name: String) = playerListsDelegate.opPlayer(name)

    /** 设置 OP 并指定等级（1-4）。手动指定等级需重启服务器才生效到权限系统。 */
    fun opPlayerWithLevel(name: String, level: Int) = playerListsDelegate.opPlayerWithLevel(name, level)

    fun deopPlayer(name: String) = playerListsDelegate.deopPlayer(name)

    fun whitelistAdd(name: String) = playerListsDelegate.whitelistAdd(name)

    fun whitelistRemove(name: String) = playerListsDelegate.whitelistRemove(name)

    /** 切换白名单开关 */
    fun toggleWhitelist(enabled: Boolean) = playerListsDelegate.toggleWhitelist(enabled)

    fun kickPlayer(name: String, reason: String = "") = playerListsDelegate.kickPlayer(name, reason)

    fun banPlayer(name: String, reason: String = "Banned by admin") = playerListsDelegate.banPlayer(name, reason)

    /** 限时封禁 */
    fun tempBanPlayer(name: String, duration: String, reason: String = "") = playerListsDelegate.tempBanPlayer(name, duration, reason)

    fun pardonPlayer(name: String) = playerListsDelegate.pardonPlayer(name)

    /** 请求在线玩家列表（发送 list 命令） */
    fun requestOnlinePlayers() = playerListsDelegate.requestOnlinePlayers()

    /** 设置玩家游戏模式 */
    fun setGameMode(name: String, mode: Int) = playerListsDelegate.setGameMode(name, mode)

    /** 给玩家经验 */
    fun giveXp(name: String, amount: Int) = playerListsDelegate.giveXp(name, amount)

    // ── 世界（存档）管理 ────────────────────────────────────────────

    private val worldManager: WorldManager by lazy { WorldManager(repo.termuxRuntime) }

    private val worldTransferDelegate by lazy {
        WorldTransferDelegate(
            app = app,
            scope = viewModelScope,
            worldManager = worldManager,
            activeDirName = ::activeDirName,
            requireServerStopped = ::requireServerStopped,
            exportPathToUri = ::exportPathToUri,
            loadWorlds = ::loadWorlds,
            reportMessage = { _messageFlow.tryEmit(it) },
            reportError = { _errorFlow.tryEmit(it) }
        )
    }

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

    private val worldListDelegate by lazy {
        WorldListDelegate(
            app = app,
            scope = viewModelScope,
            config = config,
            serverState = repo.serverState,
            worldManagerProvider = { worldManager },
            activeDirName = ::activeDirName,
            isMcRunningIn = { repo.termuxRuntime.isMcRunningIn(it) },
            reportMessage = { _messageFlow.tryEmit(it) },
            reportError = { _errorFlow.tryEmit(it) }
        )
    }

    val worlds: StateFlow<List<Worlds.WorldInfo>> get() = worldListDelegate.worlds

    val worldsLoading: StateFlow<Boolean> get() = worldListDelegate.worldsLoading

    /** 当前 level-name 指向的世界名（可能尚未生成对应目录）。 */
    val activeWorldName: StateFlow<String> get() = worldListDelegate.activeWorldName

    /** 当前世界的基岩版元信息（种子 / 生成类型）；Java 系或还没生成时为 null。 */
    val activeWorldMeta: StateFlow<WorldManager.BedrockMeta?> get() = worldListDelegate.activeWorldMeta

    /** 当前世界的 Java 版元信息（种子 / 生成类型 / 难度 / 数据包数）；基岩系为 null。 */
    val activeJavaWorldMeta: StateFlow<JavaWorlds.Meta?> get() = worldListDelegate.activeJavaWorldMeta

    /**
     * 当前世界的「建世界时的要求」（种子原文 + 生成方式）。
     *
     * 与 [activeWorldMeta] / [activeJavaWorldMeta] 的分工：那两个是**世界真实状态**，
     * 这个是**用户当时的选择**。世界还没生成时只有后者有值，UI 必须分开展示，
     * 否则就会出现「种子：尚未生成 / 生成类型：未知」这种什么都没说的组合。
     */
    val activeWorldPlan: StateFlow<WorldGenLedger.Record?> get() = worldListDelegate.activeWorldPlan

    /** 当前核心类型（UI 靠它决定显示基岩版还是 Java 版的世界/附加包界面）。 */
    val activeCore: StateFlow<ServerCore?> get() = worldListDelegate.activeCore

    private fun refreshActiveCore() = worldListDelegate.refreshActiveCore()

    /** 当前核心是否 Java 版（非 BDS / PNX / PocketMine / Allay）。 */
    fun isJavaCore(): Boolean = worldListDelegate.isJavaCore()

    /** 加载世界列表（含各世界占用大小）。大小统计较慢，放在 IO 线程。 */
    fun loadWorlds() = worldListDelegate.loadWorlds()

    /** 切换当前使用的世界（写入 level-name）。 */
    fun setActiveWorld(name: String) = worldListDelegate.setActiveWorld(name)

    private val worldCreationDelegate by lazy {
        WorldCreationDelegate(
            app = app,
            scope = viewModelScope,
            worldManagerProvider = { worldManager },
            activeDirName = ::activeDirName,
            requireServerStopped = ::requireServerStopped,
            loadWorlds = ::loadWorlds,
            reportMessage = { _messageFlow.tryEmit(it) },
            reportError = { _errorFlow.tryEmit(it) }
        )
    }

    /** 清空 level-seed，让服务端下次生成地图时使用随机种子。 */
    fun clearWorldSeed() = worldCreationDelegate.clearWorldSeed()

    /** 新建一个世界（基岩版）。 */
    fun createWorld(
        name: String,
        seed: String,
        generator: BdsWorldGen.Generator = BdsWorldGen.Generator.NORMAL,
        layers: List<BdsWorldGen.Layer> = BdsWorldGen.DEFAULT_FLAT
    ) = worldCreationDelegate.createWorld(name, seed, generator, layers)

    /** 文本种子会被 BDS 存成这个整数；空输入返回 null（随机）。 */
    fun previewSeed(raw: String): Long? = worldManager.resolveSeed(raw)

    /** 把用户输入的层配置解析成层列表；格式错误返回 null。 */
    fun previewLayers(text: String): List<BdsWorldGen.Layer>? = parsePreviewLayers(text)

    // ── Java 版：世界 / 数据包 / 资源包 ─────────────────────────

    private val javaAddonsDelegate by lazy {
        JavaAddonsDelegate(
            app = app,
            scope = viewModelScope,
            worldManagerProvider = { worldManager },
            activeDirName = ::activeDirName,
            refreshActiveCore = ::refreshActiveCore,
            isJavaCore = ::isJavaCore,
            setJavaDimensions = { worldListDelegate.setJavaDimensions(it) },
            requireServerStopped = ::requireServerStopped,
            reportMessage = { _messageFlow.tryEmit(it) },
            reportError = { _errorFlow.tryEmit(it) }
        )
    }

    val javaDataPacks: StateFlow<List<JavaWorlds.DataPack>> get() = javaAddonsDelegate.javaDataPacks

    val javaAddonsLoading: StateFlow<Boolean> get() = javaAddonsDelegate.javaAddonsLoading

    /** 资源包配置（Java 侧单包模型）；基岩系或读不到时为 null。 */
    val resourcePack: StateFlow<JavaResourcePack.Config?> get() = javaAddonsDelegate.resourcePack

    /** 资源包配置的静态校验问题（空 = 没有明显问题）。 */
    val resourcePackIssues: StateFlow<List<String>> get() = javaAddonsDelegate.resourcePackIssues

    /** 当前世界可单独重置的维度。 */
    val javaDimensions: StateFlow<List<JavaWorlds.DimensionDir>> get() = worldListDelegate.javaDimensions

    /**
     * 只刷新「维度」这一块（数据包 / 资源包不动）。
     *
     * 抽出来是因为它必须跟着**当前世界**走：只靠 [loadJavaAddons] 的
     * `LaunchedEffect(isBootstrapped)` 触发，切世界后维度列表会残留上一个世界的
     * 下界 / 末地，露出一个点了没反应的「重置维度」按钮（真机实测）。
     */
    fun refreshJavaDimensions() {
        val dirName = activeDirName() ?: return
        if (!isJavaCore()) return
        viewModelScope.launch {
            val world = withContext(Dispatchers.IO) { runCatching { worldManager.readLevelName(dirName) }.getOrNull() }
                ?: return@launch
            worldListDelegate.setJavaDimensions(withContext(Dispatchers.IO) {
                runCatching { worldManager.javaDimensionDirs(dirName, world) }.getOrDefault(emptyList())
            })
        }
    }

    /** 读 Java 侧的世界附加内容（数据包 + 资源包 + 维度）。 */
    fun loadJavaAddons() = javaAddonsDelegate.loadJavaAddons()

    /**
     * 新建 Java 版世界：只写 `server.properties` 的四个键，服务端下次启动生成。
     *
     * 生成参数只对新世界生效，已有世界改它们无效（实测 1.20.4 / 26.3 一致）。
     */
    fun createJavaWorld(
        name: String,
        seed: String,
        type: JavaWorldGen.LevelType,
        layers: List<BdsWorldGen.Layer>,
        biome: String
    ) = worldCreationDelegate.createJavaWorld(name, seed, type, layers, biome)

    /** 启用 / 停用一个世界级数据包（改 `level.dat:DataPacks`）。 */
    fun setJavaDataPackEnabled(pack: JavaWorlds.DataPack, enabled: Boolean) =
        javaAddonsDelegate.setJavaDataPackEnabled(pack, enabled)

    /** 从系统选择器导入数据包 zip（拷进 `world/datapacks/`）。 */
    fun importJavaDataPackFromUri(uri: Uri, displayName: String) =
        javaAddonsDelegate.importJavaDataPackFromUri(uri, displayName)

    /** 保存资源包配置（URL / SHA-1 / ID / 提示语 / 是否强制）。 */
    fun saveResourcePack(config: JavaResourcePack.Config) = javaAddonsDelegate.saveResourcePack(config)

    /** 从用户选的文件算 SHA-1 并填进资源包配置。 */
    fun fillResourcePackSha1(uri: Uri) = javaAddonsDelegate.fillResourcePackSha1(uri)

    private val worldMaintenanceDelegate by lazy {
        WorldMaintenanceDelegate(
            app = app,
            scope = viewModelScope,
            worldManagerProvider = { worldManager },
            serverDirFor = { repo.termuxRuntime.serverDirFor(it) },
            activeDirName = ::activeDirName,
            requireServerStopped = ::requireServerStopped,
            loadWorlds = ::loadWorlds,
            loadJavaAddons = ::loadJavaAddons,
            reportMessage = { _messageFlow.tryEmit(it) },
            reportError = { _errorFlow.tryEmit(it) }
        )
    }

    /** 重命名世界（目录 + level.dat 的 LevelName 一起改）。 */
    fun renameWorld(worldName: String, newName: String) = worldMaintenanceDelegate.renameWorld(worldName, newName)

    /** 只重置一个维度（下界 / 末地），先备份。 */
    fun resetDimension(worldName: String, key: String) = worldMaintenanceDelegate.resetDimension(worldName, key)

    // ── 附加包（行为包 / 资源包） ────────────────────────────────

    private val bdsAddonsDelegate by lazy {
        BdsAddonsDelegate(
            app = app,
            scope = viewModelScope,
            worldManagerProvider = { worldManager },
            activeDirName = ::activeDirName,
            refreshActiveCore = ::refreshActiveCore,
            requireServerStopped = ::requireServerStopped,
            reportMessage = { _messageFlow.tryEmit(it) },
            reportError = { _errorFlow.tryEmit(it) }
        )
    }

    val addons: StateFlow<BdsPacks.Scan?> get() = bdsAddonsDelegate.addons

    val addonsLoading: StateFlow<Boolean> get() = bdsAddonsDelegate.addonsLoading

    /**
     * `texturepack-required` 的当前值；null = 当前核心不支持（PNX 走 pnx.yml）。
     *
     * 它是 `server.properties` 里唯一与资源包有关的键，语义是"进服前必须下载本服登记的
     * 全部资源包"。UI 上必须与资源包清单一起看：开着它却一个资源包都没登记，等于什么都没做。
     */
    val texturePackRequired: StateFlow<Boolean?> get() = bdsAddonsDelegate.texturePackRequired

    /** 重新扫描附加包。 */
    fun loadAddons() = bdsAddonsDelegate.loadAddons()

    /** 启用 / 停用某个包：只改当前世界的 `world_*_packs.json`。 */
    fun setAddonEnabled(pack: BdsPacks.Pack, enabled: Boolean) = bdsAddonsDelegate.setAddonEnabled(pack, enabled)

    /** 切换「强制客户端下载资源包」（`texturepack-required`）。 */
    fun setTexturePackRequired(required: Boolean) = bdsAddonsDelegate.setTexturePackRequired(required)

    /** 安装包目录中的压缩包（`.mcpack` / `.mcaddon`）。 */
    fun installAddonArchive(archive: BdsPacks.Archive) = bdsAddonsDelegate.installAddonArchive(archive)

    /** 从系统文件选择器导入 `.mcpack` / `.mcaddon`。 */
    fun importAddonFromUri(uri: Uri, displayName: String) = bdsAddonsDelegate.importAddonFromUri(uri, displayName)

    /**
     * 世界导出的前置检查，**必须在拉起系统保存框之前调用**。
     *
     * 旧流程是先 `CreateDocument` 让用户挑位置（系统立刻建出一个空文件），
     * 到 ViewModel 里才检查运行状态 → 被拒绝时那个 0 字节的 zip 已经留在
     * 用户选的目录里了（实测 `/sdcard/Download/世界_t1.zip` 0 B）。
     */
    fun canExportWorldNow(): Boolean = requireServerStopped("导出世界")

    /** 导出世界到用户选择的位置（先打包到导出目录，再复制到 SAF 目标）。 */
    fun exportWorldToUri(worldName: String, uri: Uri) = worldTransferDelegate.exportWorldToUri(worldName, uri)

    /** 从用户选择的压缩包导入世界。 */
    fun importWorldFromUri(uri: Uri, preferredName: String) = worldTransferDelegate.importWorldFromUri(uri, preferredName)

    /** 重置世界：先自动导出一份安全备份，再删除目录，让服务端下次启动重新生成。 */
    fun resetWorld(worldName: String) = worldMaintenanceDelegate.resetWorld(worldName)

    // ── 崩溃报告 ────────────────────────────────────────────────────

    private val crashReportManager = CrashReportManager(repo.termuxRuntime)

    private val crashReportFilesDelegate by lazy {
        CrashReportFilesDelegate(
            app = app,
            scope = viewModelScope,
            config = config,
            crashReportManager = crashReportManager,
            refreshKnowledgeIfStale = { crashKnowledgeBase.refreshIfStale() },
            reportMessage = { _messageFlow.tryEmit(it) },
            reportError = { _errorFlow.tryEmit(it) }
        )
    }

    val crashReports: StateFlow<List<CrashReportManager.CrashReport>> get() = crashReportFilesDelegate.crashReports

    /** 加载崩溃报告列表 */
    fun loadCrashReports() = crashReportFilesDelegate.loadCrashReports()

    /** 当前查看的崩溃报告全文（供 UI 展示） */
    val currentCrashContent: StateFlow<String?> get() = crashDetailsDelegate.currentCrashContent
    val currentCrashAnalysis: StateFlow<CrashReportAnalyzer.Analysis?> get() = crashDetailsDelegate.currentCrashAnalysis

    /** 结构化报告（分析页主数据源）；生成失败时为 null，UI 退回纯规则归因 */
    val currentCrashInsight: StateFlow<CrashInsight?> get() = crashDetailsDelegate.currentCrashInsight

    private val trustStoreRepairDelegate by lazy {
        TrustStoreRepairDelegate(
            scope = viewModelScope,
            config = config,
            termuxRuntime = repo.termuxRuntime,
            refreshJava = ::refreshJava
        )
    }

    /** 崩溃弹窗「一键修复证书库」的进度与结果（弹窗内就地展示）。 */
    val trustStoreRepair: StateFlow<TrustStoreRepairUi> get() = trustStoreRepairDelegate.trustStoreRepair

    /** 本次启动的日志视图（关键日志 / 全部日志）；与报告 sidecar 一一对应 */
    val currentCrashLog: StateFlow<CrashLogView?> get() = crashDetailsDelegate.currentCrashLog

    private val crashDetailsDelegate by lazy {
        CrashDetailsDelegate(
            app = app,
            scope = viewModelScope,
            config = config,
            crashReportManager = crashReportManager,
            termuxRuntime = repo.termuxRuntime,
            timeNow = ::timeNow,
            crashReportPath = { fileName -> crashReports.value.firstOrNull { it.fileName == fileName }?.path },
            reportError = { _errorFlow.tryEmit(it) }
        )
    }

    /** L3 在线规则包（懒加载，避免拖慢 ViewModel 构建） */
    private val crashKnowledgeBase by lazy { CrashKnowledgeBase(app) }

    /** Shows a structured report when validation fails before a process can create its own crash file. */
    private fun showStartupFailureReport(error: Throwable) = crashDetailsDelegate.showStartupFailureReport(error)

    private fun showStartupFailureReport(failure: McServerController.StartupFailure) =
        crashDetailsDelegate.showStartupFailureReport(failure)

    /** 读取崩溃报告全文。 */
    fun readCrashReport(fileName: String) = crashDetailsDelegate.readCrashReport(fileName)

    /** 用户反馈：回写 L2 案例库，让后续相似崩溃的建议更准。 */
    fun recordCrashFeedback(resolved: Boolean) = crashDetailsDelegate.recordCrashFeedback(resolved)

    /**
     * 崩溃弹窗里的「一键修复」：重装当前 Java + ca-certificates，必要时用本机证书包
     * 原地重建 cacerts。修复期间弹窗保持打开，结果就地展示。
     */
    fun repairJavaTrustStore() = trustStoreRepairDelegate.repairJavaTrustStore()

    /** 手动刷新在线知识库规则包（分析页可选入口，失败静默）。 */
    fun refreshCrashKnowledge() {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = runCatching { crashKnowledgeBase.refresh() }.getOrDefault(false)
            if (updated) _messageFlow.tryEmit(str(R.string.crash_knowledge_updated))
        }
    }

    /** 关闭崩溃报告详情视图 */
    fun clearCrashContent() {
        crashDetailsDelegate.clearCrashContent()
        trustStoreRepairDelegate.clearResult()
    }

    /** 删除崩溃报告 */
    fun deleteCrashReport(fileName: String) = crashReportFilesDelegate.deleteCrashReport(fileName)

    /** 读取应用自身崩溃日志（全局 uncaught handler 写入），无则返回 null */
    fun appCrashLog(): String? = crashReportFilesDelegate.appCrashLog()

    /** 清空应用崩溃日志文件 */
    fun clearAppCrashLog() = crashReportFilesDelegate.clearAppCrashLog()

    /** 清空所有崩溃报告 */
    fun clearCrashReports() = crashReportFilesDelegate.clearCrashReports()

    // ── 定时备份配置 ────────────────────────────────────────────────

    fun setAutoBackupInterval(min: Int) = updateConfig {
        it.copy(autoBackupIntervalMin = if (min <= 0) 0 else min.coerceIn(5, 10080))
    }
    fun setAutoBackupType(type: com.mineserve.mobile.data.AutoBackupType) =
        updateConfig { it.copy(autoBackupType = type) }
    fun setMaxSnapshots(max: Int) = updateConfig { it.copy(maxSnapshots = max.coerceIn(1, 100)) }

    // ── 服务端核心下载相关 ──────────────────────────────────────────

    private val coreVersionsDelegate by lazy {
        CoreVersionsDelegate(
            app = app,
            scope = viewModelScope,
            config = config,
            fetchVersionOptions = controller::fetchVersionOptions,
            updateConfig = ::updateConfig,
            reportMessage = { _messageFlow.tryEmit(it) },
            reportError = { _errorFlow.tryEmit(it) }
        )
    }

    /** 可用版本列表（从 API 获取，供 DownloadScreen 选择） */
    val availableVersions: StateFlow<List<String>> get() = coreVersionsDelegate.availableVersions

    val versionHints: StateFlow<Map<String, String?>> get() = coreVersionsDelegate.versionHints

    fun supportedGameVersion(coreVersion: String): String? = coreVersionsDelegate.supportedGameVersion(coreVersion)

    /** 版本列表加载中 */
    val isLoadingVersions: StateFlow<Boolean> get() = coreVersionsDelegate.isLoadingVersions

    private val coreDownloadDelegate by lazy {
        CoreDownloadDelegate(
            app = app,
            scope = viewModelScope,
            config = config,
            controller = controller,
            serverDirFor = { repo.termuxRuntime.serverDirFor(it) },
            reportMessage = { _messageFlow.tryEmit(it) },
            reportError = { _errorFlow.tryEmit(it) }
        )
    }

    /** 服务端核心下载中 */
    val isDownloadingCore: StateFlow<Boolean> get() = coreDownloadDelegate.isDownloadingCore

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

    val downloadProgress: StateFlow<DownloadProgress> get() = coreDownloadDelegate.downloadProgress

    /** 加载指定核心的可用版本列表，并校正当前版本。force 为 true 时忽略缓存。 */
    fun loadVersions(core: ServerCore, force: Boolean = false) = coreVersionsDelegate.loadVersions(core, force)

    /** 下载服务端核心（使用自定义名称，保存到独立目录），成功返回 true */
    fun downloadCore(customName: String) = coreDownloadDelegate.downloadCore(customName)

    /** 选择要启动的核心（按名称） */
    fun setActiveCore(name: String) {
        // 只切核心，不动运行模式 —— 运行模式由用户在「运行环境」弹窗里决定并完全生效。
        updateConfig { it.copy(activeCoreName = name) }
    }

    /** 删除一个已安装的核心（按名称） */
    /** 校验已安装核心；未知核心只检查原文件，绝不自动下载或覆盖。 */
    fun verifyOrRepairCore(dirName: String) = coreDownloadDelegate.verifyOrRepairCore(dirName)

    private val installedCoresDelegate by lazy {
        InstalledCoresDelegate(
            app = app,
            scope = viewModelScope,
            repo = repo,
            requireServerStopped = ::requireServerStopped,
            termux3Log = ::termux3Log,
            reportMessage = { _messageFlow.tryEmit(it) },
            reportError = { _errorFlow.tryEmit(it) }
        )
    }

    /** 删除一个已安装核心（磁盘目录 + 配置登记）。 */
    fun deleteCore(name: String) = installedCoresDelegate.deleteCore(name)

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

    private val fileBrowserDelegate: FileBrowserDelegate by lazy {
        FileBrowserDelegate(
            app = app,
            scope = viewModelScope,
            openTextFile = { openTextFile(it) },
            reportMessage = { _messageFlow.tryEmit(it) },
            reportError = { _errorFlow.tryEmit(it) }
        )
    }

    val fileList: StateFlow<List<FileEntry>> get() = fileBrowserDelegate.fileList

    val currentPath: StateFlow<String> get() = fileBrowserDelegate.currentPath

    /**
     * 文件列表为什么是空的。
     *
     * 「列表空」有三种完全不同的原因，混成一个空列表会让用户以为文件被删了：
     *  - [NoPermission] 没拿到存储权限 → `listFiles()` 返回 null，界面要引导去授权；
     *  - [RootMissing] 目录还没创建（全新安装）→ 应该是「还没有服务器」，不是「加载中」；
     *  - [Empty] 目录确实空。
     */
    enum class FileListState { Loading, NoPermission, RootMissing, Empty, Ready }

    val fileListState: StateFlow<FileListState> get() = fileBrowserDelegate.fileListState

    /** 文件管理根目录：只有统一存储目录一个根。 */
    val fileManagerRoot: java.io.File get() = fileBrowserDelegate.fileManagerRoot

    /** 文件管理权限状态，直接转发全局存储授权流。 */
    val storageGranted: StateFlow<Boolean> get() = fileBrowserDelegate.storageGranted

    fun refreshStoragePermission() = fileBrowserDelegate.refreshStoragePermission()

    /** 进入文件管理页（或授权状态变化）时刷新。 */
    fun onFileManagerVisible() = fileBrowserDelegate.onFileManagerVisible()

    /** 授权成功后（之前因无权限列表是空的），补一次加载 */
    fun onStoragePermissionGranted() = fileBrowserDelegate.onStoragePermissionGranted()

    /** 加载指定目录的文件列表 */
    fun loadFiles(path: java.io.File) = fileBrowserDelegate.loadFiles(path)

    /** 加载文件管理根目录（不依赖 Termux 环境） */
    fun loadFilesRoot() = fileBrowserDelegate.loadFilesRoot()

    /** 向上返回上级目录 */
    fun navigateUp() = fileBrowserDelegate.navigateUp()

    /** 刷新当前目录文件列表；还没定位到目录时重新回到根目录 */
    fun refreshFiles() = fileBrowserDelegate.refreshFiles()

    /** 打开 MC 终端前初始化日志 */
    fun launchMcConsole() {
        // 确保终端日志色标已处理
        _messageFlow.tryEmit(str(R.string.s303))
    }

    /** 删除文件或目录 */
    fun deleteFile(file: java.io.File) = fileBrowserDelegate.deleteFile(file)

    /** 上传文件：从 Uri 复制到目标目录 */
    fun uploadFile(uri: android.net.Uri, targetDir: java.io.File) = fileBrowserDelegate.uploadFile(uri, targetDir)

    /** 导出文件/文件夹到用户选择的 Uri（文件夹打包为 zip） */
    fun exportPathToUri(source: java.io.File, uri: android.net.Uri) = fileBrowserDelegate.exportPathToUri(source, uri)

    /** 导出整个服务器核心目录（打包 zip）；目录按当前活动核心的实际位置解析 */
    fun exportServerToUri(uri: android.net.Uri) {
        val dirName = activeDirName() ?: return
        val dir = repo.termuxRuntime.serverDirFor(dirName)
        if (!dir.exists()) {
            _errorFlow.tryEmit(str(R.string.s310))
            return
        }
        exportPathToUri(dir, uri)
    }

    /** 创建新目录 */
    fun createDirectory(parent: java.io.File, name: String) = fileBrowserDelegate.createDirectory(parent, name)

    /** 新建空文本文件。 */
    fun createTextFile(parent: java.io.File, name: String) = fileBrowserDelegate.createTextFile(parent, name)

    /** 重命名文件/目录。 */
    fun renameEntry(file: java.io.File, newName: String) = fileBrowserDelegate.renameEntry(file, newName)

    /** 复制文件/目录到目标目录；重名自动加序号，不覆盖已有内容 */
    fun copyEntryTo(src: java.io.File, destDir: java.io.File) = fileBrowserDelegate.copyEntryTo(src, destDir)

    /** 批量删除（多选模式）；部分失败时报出失败数量，不谎报全部成功 */
    fun deleteEntries(entries: List<java.io.File>) = fileBrowserDelegate.deleteEntries(entries)

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
        //
        // 这里**不再等 Termux 初始化完成**：读的是 server.properties 这个纯文本文件，
        // 与运行时毫无关系。等它只会让「没装环境就改过配置」的用户继续看到旧端口。
        viewModelScope.launch { refreshServerPropertiesNow() }
        viewModelScope.launch {
            var wasRunning = false
            serverState.collect { state ->
                if (wasRunning && !state.isRunning) {
                    // 服务端已停：在线名单与计数一起归零。只清一个会留下「列表 0 行、计数 3」，
                    // 而两者下一次对齐要等到重启就绪后那次 list —— 期间界面上就是个假数字。
                    _onlinePlayerNames.value = emptyList()
                    repo.updateServerState { if (it.isRunning) it else it.copy(onlinePlayers = 0) }
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
                consoleLogDelegate.appendLine(line)
                parseConsoleLine(line)
            }
        }
        consoleLogDelegate.startOutputFlush()
        terminalSessionsDelegate.startLegacyOutputFlush()
        terminalSessionsDelegate.startOutputFlush()
    }

    // ── 后台保活（开机自启 / 周期保活） ─────────────────────────────

    /** 本地化字符串（ViewModel 中获取资源） */
    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)

    private val keepAliveDelegate by lazy {
        KeepAliveDelegate(
            app = app,
            reportError = { _errorFlow.tryEmit(it) }
        )
    }

    /** 开机自启动开关状态 */
    fun isBootAutoStart(): Boolean = keepAliveDelegate.isBootAutoStart()

    /** 设置开机自启动：开启时立即拉起前台服务，关闭时停止 */
    fun setBootAutoStart(v: Boolean) = keepAliveDelegate.setBootAutoStart(v)

    /** 设置服务器事件通知等级（0 关闭 / 1 仅重要 / 2 全部）。 */
    fun setEventNotifyLevel(value: Int) {
        ServerEventNotifier.setLevel(app, value.coerceIn(0, 2))
        _eventNotifyLevel.value = value.coerceIn(0, 2)
    }

    /** 后台周期保活开关状态（默认开启） */
    fun isKeepAliveEnabled(): Boolean = keepAliveDelegate.isKeepAliveEnabled()

    /** 设置后台周期保活：开启时调度 WorkManager 周期任务，关闭时取消 */
    fun setKeepAliveEnabled(v: Boolean) = keepAliveDelegate.setKeepAliveEnabled(v)

    /** 拉起前台保活服务 */
    fun startKeepAliveService() = keepAliveDelegate.startKeepAliveService()

    /** 停止前台保活服务 */
    fun stopKeepAliveService() = keepAliveDelegate.stopKeepAliveService()
}

/**
 * 崩溃弹窗里「一键修复证书库」的界面状态。
 *
 * [ok] 为 null 且 [running] 为 false 表示还没点过；`ok=true/false` 是上次修复结果。
 */
data class TrustStoreRepairUi(
    val running: Boolean = false,
    val ok: Boolean? = null,
    val message: String? = null
)
