package com.mineserve.mobile.server

import android.content.Context
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.TunnelState
import com.mineserve.mobile.data.TunnelStatus
import com.mineserve.mobile.data.TunnelType
import com.mineserve.mobile.server.tunnel.BoreBackend
import com.mineserve.mobile.server.tunnel.FrpBackend
import com.mineserve.mobile.server.tunnel.MeFrpBackend
import com.mineserve.mobile.server.tunnel.SakuraFrpBackend
import com.mineserve.mobile.server.tunnel.StunTunnelBackend
import com.mineserve.mobile.server.tunnel.TunnelBackend
import com.mineserve.mobile.server.tunnel.frpc.FrpcProcessManager
import com.mineserve.mobile.server.tunnel.frpc.FrpcRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 内网穿透统一管理器。
 *
 * 支持的穿透方式：frp / bore / 各家托管 frp 平台（SakuraFrp / ME Frp）
 *
 * frp 系后端共用一份**内置引擎**：引擎（libfrpc.so）由用户导入到应用数据目录，
 * 由 [FrpcProcessManager] 以子进程方式拉起，不依赖 Termux 或任何外部运行环境。
 * bore 与 STUN 是应用内自有实现，同样不依赖外部环境。
 *
 * @param logSink 全局日志出口。以前这里直接拿 TermuxRuntime 来 emitLog，导致隧道层
 *   与 Termux 绑定；改为注入回调后，隧道不再需要 Termux 存在。Termux 目前仍被
 *   服务端运行时使用，所以由调用方把它的日志出口接进来。
 */
class TunnelManager(
    context: Context,
    private val logSink: (String) -> Unit = {}
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 内置 frpc 引擎的存储与校验。UI 层（依赖管理页）也需要它来展示/导入引擎。 */
    val frpcRuntime = FrpcRuntime(context)

    val frpcProcessManager = FrpcProcessManager.getInstance(context)

    private val backends: Map<TunnelType, TunnelBackend> = mapOf(
        TunnelType.Frp to FrpBackend(frpcRuntime, frpcProcessManager),
        TunnelType.Bore to BoreBackend(),
        TunnelType.Stun to StunTunnelBackend(),
        TunnelType.SakuraFrp to SakuraFrpBackend(frpcRuntime, frpcProcessManager),
        TunnelType.MeFrp to MeFrpBackend(frpcRuntime, frpcProcessManager)
    ).onEach { (_, backend) ->
        backend.attachLog { msg -> logSink("[tunnel] $msg") }
    }

    private var activeBackend: TunnelBackend? = null

    private val _state = MutableStateFlow(TunnelState())
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    init {
        backends.values.forEach { backend ->
            scope.launch {
                backend.state.collect { backendState ->
                    if (backend == activeBackend) {
                        _state.value = backendState
                    }
                }
            }
        }
    }

    suspend fun start(config: McConfig) {
        if (activeBackend != null) {
            activeBackend?.stop()
        }

        val backend = backends[config.tunnelType]
        if (backend == null) {
            _state.value = TunnelState(
                status = TunnelStatus.Failed,
                errorMessage = "${config.tunnelType.displayName} 后端尚未实现",
                activeType = config.tunnelType
            )
            logSink("[tunnel] 错误: ${config.tunnelType.displayName} 后端尚未实现")
            return
        }

        activeBackend = backend
        _state.value = backend.state.value
        val result = backend.start(config)
        result.onFailure { e ->
            logSink("[tunnel] ${config.tunnelType.displayName} 启动失败: ${e.message}")
        }
    }

    suspend fun stop() {
        activeBackend?.stop()
        activeBackend = null
        _state.value = TunnelState(status = TunnelStatus.Stopped)
    }

    fun destroy() {
        // 先停所有 backend，再取消作用域：scope.cancel() 之后 scope.launch 会以已取消
        // 状态启动，协程体根本不会执行，frpc 子进程不会被停。用 runBlocking +
        // NonCancellable 确保 stop() 真正执行完，再取消状态收集协程。
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                backends.values.forEach { runCatching { it.stop() } }
            }
        }
        activeBackend = null
        scope.cancel()
    }
}
