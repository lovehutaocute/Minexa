package com.mineserve.mobile.server.tunnel

import android.content.Context
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.TunnelState
import com.mineserve.mobile.data.TunnelStatus
import com.mineserve.mobile.data.TunnelType
import com.mineserve.mobile.server.tunnel.easytier.EasyTierEngine
import com.mineserve.mobile.server.tunnel.easytier.EasyTierSettings
import com.mineserve.mobile.server.tunnel.easytier.EasyTierVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * EasyTier 组网的隧道后端。
 *
 * ## 为什么它比别的后端"多一层"
 *
 * 其它后端（frpc / STUN）在自己进程里同步地知道"起来了没有"。EasyTier 不行：
 * 真正的状态在 [EasyTierVpnService] 里（实例启动 + 建 TUN + 交 fd 三段式，跨组件），
 * 后端只能**观察**它。所以这里用轮询把 Service 的状态翻成 [TunnelState]，
 * 并在 [start] / [stop] 里等到状态落定再返回 —— 否则上层会把"刚发出启动指令"当成"已在运行"。
 *
 * ## 地址与密钥从哪来
 *
 * 全部落在 [McConfig]：网络名/密钥由用户填，虚拟 IP 留空则按网络名派生
 * （同一个网络永远是同一个地址，玩家抄过一次就一直有效）。
 */
class EasyTierBackend(private val context: Context) : TunnelBackend {

    override val type: TunnelType = TunnelType.EasyTier

    private val _state = MutableStateFlow(TunnelState())
    override val state: StateFlow<TunnelState> = _state.asStateFlow()

    private var logger: ((String) -> Unit)? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollJob: Job? = null

    /** 最近一次启动用的配置，[stop] 之后仍留着，供界面显示"玩家该连哪个地址"。 */
    @Volatile
    var lastSettings: EasyTierSettings? = null
        private set

    override fun attachLog(logger: (String) -> Unit) {
        this.logger = logger
    }

    override suspend fun start(config: McConfig): Result<Unit> {
        if (!EasyTierEngine.available) {
            return Result.failure(IllegalStateException("EasyTier 引擎未加载（缺少 libeasytier_android_jni.so）"))
        }
        val networkName = config.easyTierNetworkName.trim()
        if (networkName.isBlank()) {
            return Result.failure(IllegalStateException("请先填写网络名"))
        }
        val secret = config.easyTierNetworkSecret.trim()
        if (secret.isBlank()) {
            return Result.failure(IllegalStateException("请先生成或填写网络密钥"))
        }
        val virtualIp = config.easyTierVirtualIp.trim()
            .ifBlank { EasyTierSettings.deriveVirtualIp(networkName) }

        val settings = EasyTierSettings(
            instanceName = INSTANCE_NAME,
            hostname = "minexa",
            virtualIp = virtualIp,
            networkName = networkName,
            networkSecret = secret,
            serverPort = config.localPort
        )
        lastSettings = settings

        _state.value = TunnelState(
            isRunning = false,
            status = TunnelStatus.Starting,
            activeType = TunnelType.EasyTier,
            // 玩家连的是**转发端口**，不是服务端端口（两者必须不同，否则 bind 冲突）
            publicUrl = EasyTierSettings.playerAddress(settings.virtualIp, settings.serverPort)
        )
        logger?.invoke("EasyTier 启动：网络 $networkName，本机地址 ${settings.virtualIp}")

        // 授权缺失时 establish() 只会返回 null，Service 会把它翻成一条明确原因，
        // 所以这里只要把失败原因原样带回上层即可。
        EasyTierEngine.start(context, settings)
        startPolling()

        return awaitSettled(
            timeoutMs = START_TIMEOUT_MS,
            onRunning = {
                _state.value = _state.value.copy(isRunning = true, status = TunnelStatus.Running)
                logger?.invoke(
                    "EasyTier 已就绪：玩家连接 " +
                        EasyTierSettings.playerAddress(settings.virtualIp, settings.serverPort) +
                        "（转发到本机 127.0.0.1:${settings.serverPort}）"
                )
                Result.success(Unit)
            },
            onFailed = { reason ->
                _state.value = _state.value.copy(isRunning = false, status = TunnelStatus.Failed, errorMessage = reason)
                logger?.invoke("EasyTier 启动失败：$reason")
                Result.failure(IllegalStateException(reason))
            },
            onTimeout = {
                val reason = "启动超时（未在 ${START_TIMEOUT_MS / 1000}s 内建立虚拟网卡）"
                _state.value = _state.value.copy(isRunning = false, status = TunnelStatus.Failed, errorMessage = reason)
                Result.failure(IllegalStateException(reason))
            }
        )
    }

    override suspend fun stop() {
        EasyTierEngine.stop(context)
        // 等接口真正释放：Service 里先关 TUN 再停 core，接口消失需要一拍
        repeat(STOP_POLLS) {
            delay(POLL_INTERVAL_MS)
            if (EasyTierVpnService.state is EasyTierVpnService.State.Stopped) {
                pollJob?.cancel()
                _state.value = _state.value.copy(isRunning = false, status = TunnelStatus.Stopped)
                logger?.invoke("EasyTier 已停止")
                return
            }
        }
        pollJob?.cancel()
        _state.value = _state.value.copy(isRunning = false, status = TunnelStatus.Stopped)
    }

    /** 把 Service 的实时状态映射到 [state]，供界面在对端变化/失败时刷新。 */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                when (val s = EasyTierVpnService.state) {
                    is EasyTierVpnService.State.Running -> {
                        if (!_state.value.isRunning) {
                            _state.value = _state.value.copy(isRunning = true, status = TunnelStatus.Running)
                        }
                    }

                    is EasyTierVpnService.State.Failed -> {
                        _state.value = _state.value.copy(
                            isRunning = false,
                            status = TunnelStatus.Failed,
                            errorMessage = s.reason
                        )
                    }

                    EasyTierVpnService.State.Stopped -> {
                        if (_state.value.status == TunnelStatus.Running) {
                            _state.value = _state.value.copy(isRunning = false, status = TunnelStatus.Stopped)
                        }
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun awaitSettled(
        timeoutMs: Long,
        onRunning: () -> Result<Unit>,
        onFailed: (String) -> Result<Unit>,
        onTimeout: () -> Result<Unit>
    ): Result<Unit> {
        val steps = (timeoutMs / POLL_INTERVAL_MS).toInt()
        repeat(steps) {
            delay(POLL_INTERVAL_MS)
            when (val s = EasyTierVpnService.state) {
                is EasyTierVpnService.State.Running -> return onRunning()
                is EasyTierVpnService.State.Failed -> return onFailed(s.reason)
                EasyTierVpnService.State.Stopped -> Unit // 还没起来，继续等
            }
        }
        return onTimeout()
    }

    companion object {
        /** 与 TOML 里的 `instance_name` 必须一致（`setTunFd` 按名字找实例）。 */
        const val INSTANCE_NAME = "minexa"

        private const val POLL_INTERVAL_MS = 400L
        private const val START_TIMEOUT_MS = 20_000L
        private const val STOP_POLLS = 25
    }
}
