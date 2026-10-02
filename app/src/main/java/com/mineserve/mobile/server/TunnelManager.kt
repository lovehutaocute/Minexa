package com.mineserve.mobile.server

import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.TunnelState
import com.mineserve.mobile.data.TunnelStatus
import com.mineserve.mobile.data.TunnelType
import com.mineserve.mobile.runtime.TermuxRuntime
import com.mineserve.mobile.server.tunnel.BinaryManager
import com.mineserve.mobile.server.tunnel.BoreBackend
import com.mineserve.mobile.server.tunnel.FrpBackend
import com.mineserve.mobile.server.tunnel.MeFrpBackend
import com.mineserve.mobile.server.tunnel.SakuraFrpBackend
import com.mineserve.mobile.server.tunnel.TunnelBackend
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
 * 支持的穿透方式: frp / bore / 各家托管 frp 平台（SakuraFrp / ME Frp）
 */
class TunnelManager(private val termux: TermuxRuntime) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val binaryManager = BinaryManager(termux)

    private val backends: Map<TunnelType, TunnelBackend> = mapOf(
        TunnelType.Frp to FrpBackend(termux, binaryManager),
        TunnelType.Bore to BoreBackend(),
        TunnelType.SakuraFrp to SakuraFrpBackend(termux, binaryManager),
        TunnelType.MeFrp to MeFrpBackend(termux, binaryManager)
    ).onEach { (_, backend) ->
        backend.attachLog { msg -> termux.emitLog("[tunnel] $msg") }
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
            termux.emitLog("[tunnel] 错误: ${config.tunnelType.displayName} 后端尚未实现")
            return
        }

        activeBackend = backend
        _state.value = backend.state.value
        val result = backend.start(config)
        result.onFailure { e ->
            termux.emitLog("[tunnel] ${config.tunnelType.displayName} 启动失败: ${e.message}")
        }
    }

    suspend fun stop() {
        activeBackend?.stop()
        activeBackend = null
        _state.value = TunnelState(status = TunnelStatus.Stopped)
    }

    fun destroy() {
        // 先停所有 backend，再取消作用域：scope.cancel() 之后 scope.launch 会以已取消
        // 状态启动，协程体根本不会执行，frpc/frps 子进程不会被停。用 runBlocking +
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
