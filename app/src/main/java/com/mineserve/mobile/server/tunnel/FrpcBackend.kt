package com.mineserve.mobile.server.tunnel

import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.TunnelState
import com.mineserve.mobile.data.TunnelStatus
import com.mineserve.mobile.data.TunnelType
import com.mineserve.mobile.server.tunnel.frpc.FrpcProcessManager
import com.mineserve.mobile.server.tunnel.frpc.FrpcRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 基于内置 frpc 引擎的隧道后端基类。
 *
 * 三个 frp 系后端（自建 [FrpBackend]、[SakuraFrpBackend]、[MeFrpBackend]）的差异只在
 * 「配置从哪来」，运行方式完全一致：写配置到文件 → 交给 [FrpcProcessManager] 拉起
 * 引擎进程 → 读输出推进状态。公共部分收在这里。
 *
 * **不依赖 Termux**：这是本次改造的核心 —— 引擎是随应用分发的独立二进制，
 * 进程由 [FrpcProcessManager] 直接以 ProcessBuilder 拉起，不经过任何 shell、
 * 不经过 Termux 的 prefix，也不需要 apt。
 */
abstract class FrpcBackend(
    protected val runtime: FrpcRuntime,
    protected val processManager: FrpcProcessManager,
    final override val type: TunnelType
) : TunnelBackend {

    private val _state = MutableStateFlow(TunnelState())
    final override val state: StateFlow<TunnelState> = _state.asStateFlow()

    @Volatile
    private var logger: ((String) -> Unit)? = null

    /** 存放配置文件的目录。 */
    protected val tunnelDir: File
        get() = File(runtime.engineDir.parentFile ?: runtime.engineDir, "tunnel").apply { mkdirs() }

    final override fun attachLog(logger: (String) -> Unit) {
        this.logger = logger
        // 管理器常驻（应用级单例），界面重建后要能重新接到当前日志流上，
        // 所以每次 attach 都把日志回调重挂一遍，并回放历史。
        //
        // owner = this：三个 frp 系后端共用同一个进程管理器，状态回调必须记清归属，
        // 否则初始化时最后 attach 的那个会永久劫持回调（见 FrpcProcessManager.attach）。
        processManager.attach(
            owner = this,
            logSink = { line -> logger(line) },
            stateSink = { status, exitCode -> onProcessState(status, exitCode) }
        )
    }

    protected fun log(message: String) {
        logger?.invoke(message)
    }

    /**
     * 管理器推送的进程状态 → 隧道状态。
     *
     * 子类可覆盖以补充公网地址等展示信息，但停止/失败的基本语义在基类统一处理，
     * 避免三个后端各写一遍还写得不一样。
     */
    protected open fun onProcessState(status: TunnelStatus?, exitCode: Int?) {
        when (status) {
            null -> {
                // 进程退出。主动 stop() 时不报错，否则用户点了「停止」还会看到一条失败提示。
                if (stoppingIntentionally) {
                    updateState(TunnelStatus.Stopped)
                } else {
                    val output = processManager.logSnapshot().joinToString("\n")
                    updateState(
                        TunnelStatus.Failed,
                        errorMessage = diagnoseFailure(exitCode ?: -1, output)
                    )
                }
            }
            else -> updateState(status)
        }
    }

    @Volatile
    private var stoppingIntentionally = false

    final override suspend fun start(config: McConfig): Result<Unit> = runCatching {
        updateState(TunnelStatus.Starting, errorMessage = "正在准备 ${type.displayName}...")

        if (!runtime.isInstalled()) {
            throw IllegalStateException(
                "frpc 引擎未安装。请在「依赖管理 → 内网穿透引擎」导入 libfrpc.so（arm64 版）后重试。"
            )
        }

        processManager.clearLog()
        stoppingIntentionally = false

        val configFile = prepareConfig(config)
        log("配置已写入 ${configFile.name}")

        processManager.start(configFile, runtime, extraEnv(config), owner = this)
        onProcessStarted(config)
    }.onFailure { e ->
        log("启动失败：${e.message}")
        updateState(TunnelStatus.Failed, errorMessage = e.message ?: "未知错误")
    }

    final override suspend fun stop() {
        stoppingIntentionally = true
        // 等它真的退出：不等的话紧接着切到另一条隧道时，新旧进程会短暂争同一个本地端口。
        processManager.stopAndWait()
        updateState(TunnelStatus.Stopped)
    }

    protected fun updateState(
        status: TunnelStatus,
        publicUrl: String? = null,
        errorMessage: String? = null
    ) {
        _state.value = TunnelState(
            status = status,
            // Running 时没显式给地址就沿用上一次的：子类推进状态与补地址是两步，
            // 中途不能把已经拿到的公网地址抹掉。
            publicUrl = publicUrl
                ?: _state.value.publicUrl.takeIf { status == TunnelStatus.Running }
                ?: "",
            errorMessage = errorMessage ?: "",
            activeType = type
        )
    }

    /** 写好并返回本后端的配置文件。子类各自决定配置内容从哪来。 */
    protected abstract fun prepareConfig(config: McConfig): File

    /** 平台专用环境变量；默认无。 */
    protected open fun extraEnv(config: McConfig): Map<String, String> = emptyMap()

    /** 进程启动后的回调（如立即设置公网地址）。 */
    protected open fun onProcessStarted(config: McConfig) {}

    /**
     * 把引擎输出的错误翻译成用户能照做的建议。
     *
     * 消息里出现的 `unknown field` / `dlopen` 这类底层字样，用户看不懂也无法行动，
     * 所以逐类给出「下一步做什么」。
     */
    protected open fun diagnoseFailure(exitCode: Int, output: String): String {
        return when {
            output.contains("dlopen") || output.contains("dlsym") ->
                "frpc 引擎加载失败：导入的 libfrpc.so 可能已损坏或不是 frp 官方 c-shared 构建产物，" +
                    "请在「依赖管理」重新导入。"
            output.contains("unknown field") || output.contains("unmarshal") ->
                "本地 frpc 引擎不认识配置里的字段（见上方引擎日志）。通常是引擎版本比平台下发的配置旧，" +
                    "请导入更新版本的 libfrpc.so。"
            output.contains("connection refused") ->
                "无法连接 frp 服务端，请检查配置里的 serverAddr / serverPort"
            output.contains("timeout") || output.contains("i/o timeout") ->
                "连接超时，请检查服务端地址是否可达、网络是否正常"
            output.contains("token") || output.contains("auth") ->
                "认证失败：Token 与服务端不匹配"
            output.contains("bind") || output.contains("address already in use") ->
                "端口已被占用，请更换远程端口"
            else -> "隧道进程退出 (code=$exitCode)"
        }
    }
}
