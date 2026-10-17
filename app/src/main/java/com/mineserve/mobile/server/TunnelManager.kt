package com.mineserve.mobile.server

import android.content.Context
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.TunnelLane
import com.mineserve.mobile.data.TunnelState
import com.mineserve.mobile.data.TunnelStateAggregator
import com.mineserve.mobile.data.TunnelStatus
import com.mineserve.mobile.data.TunnelType
import com.mineserve.mobile.server.tunnel.BoreBackend
import com.mineserve.mobile.server.tunnel.FrpBackend
import com.mineserve.mobile.server.tunnel.MeFrpBackend
import com.mineserve.mobile.server.tunnel.SakuraFrpBackend
import com.mineserve.mobile.server.tunnel.StunDiagnostics
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
 * 支持的穿透方式：frp / 各家托管 frp 平台（SakuraFrp / ME Frp）/ STUN 直连 / bore。
 *
 * frp 系后端共用一份**内置引擎**：引擎（libfrpc.so）由用户导入到应用数据目录，
 * 由 [FrpcProcessManager] 以子进程方式拉起，不依赖 Termux 或任何外部运行环境。
 * STUN 与应用内 bore 是自有实现，同样不依赖外部环境。
 *
 * ## 两条独立通道（本次改造的核心）
 *
 * 原实现只有一个 `activeBackend`，启动任意后端都会先停掉上一个 ——
 * 于是 STUN 一开，FRP 就断。现在按 [TunnelLane] 分成两条互不相干的通道：
 *
 * - [TunnelLane.Frp]：frp / SakuraFrp / ME Frp。三者共用同一个 frpc 进程
 *   （[FrpcProcessManager] 是应用级单例），因此**通道内互斥**，换后端会先停旧的；
 * - [TunnelLane.Stun]：自开 socket，与 frpc 进程无关，**可与 FRP 同时运行**。
 *
 * 两条通道各自暴露一个 [StateFlow]，界面分别订阅 —— 这是「两张卡各自显示
 * 真实状态」的前提。此外还保留一个聚合流 [state]，给不区分通道的旧调用方。
 *
 * @param logSink 全局日志出口。隧道层不直接依赖 Termux：调用方把日志接到自己的出口上。
 * @param injectedBackends 仅测试使用：注入假后端以在不起真进程/不联网的前提下
 *   验证「通道互不干扰」。生产路径传 null，由构造器自建真实后端。
 */
class TunnelManager internal constructor(
    context: Context,
    private val logSink: (String) -> Unit = {},
    injectedBackends: Map<TunnelType, TunnelBackend>? = null
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 内置 frpc 引擎的存储与校验。UI 层（依赖管理页）也需要它来展示/导入引擎。 */
    val frpcRuntime = FrpcRuntime(context)

    val frpcProcessManager = FrpcProcessManager.getInstance(context)

    /**
     * STUN 后端实例。单独持有引用（而不是只从 [backends] 里取），
     * 是为了把它的诊断流稳定地暴露给界面：诊断只在 STUN 卡上展示，
     * 不应该因为「当前后端换人了」而消失。
     */
    private val stunBackendImpl: StunTunnelBackend = StunTunnelBackend()

    /**
     * STUN 直连的实时诊断：公网地址、探测耗时、空连接回收计数。
     * 网络页的「STUN 直连」卡片订阅它。
     */
    val stunDiagnostics: StateFlow<StunDiagnostics> = stunBackendImpl.diagnostics

    /**
     * 全部后端实例，按类型索引。
     *
     * 保留「一个 Map 装齐所有后端」的结构（而不是两条通道各写各的构造调用），
     * 是因为初始化时需要对**每一个**后端调 [TunnelBackend.attachLog] ——
     * 少挂一个，该后端启动后就把状态推给一个从未登记的对象，界面永远停在「启动中」。
     */
    private val backends: Map<TunnelType, TunnelBackend> = injectedBackends ?: mapOf(
        TunnelType.Frp to FrpBackend(frpcRuntime, frpcProcessManager),
        TunnelType.Bore to BoreBackend(),
        TunnelType.Stun to stunBackendImpl,
        TunnelType.SakuraFrp to SakuraFrpBackend(frpcRuntime, frpcProcessManager),
        TunnelType.MeFrp to MeFrpBackend(frpcRuntime, frpcProcessManager)
    ).onEach { (_, backend) ->
        backend.attachLog { msg -> logSink("[tunnel] $msg") }
    }

    /** FRP 通道当前后端（Frp / SakuraFrp / MeFrp 之一）；null 表示该通道从未启动过。 */
    @Volatile
    private var frpBackend: TunnelBackend? = null

    // ── 对外状态 ──────────────────────────────────────────────

    private val _frpState = MutableStateFlow(TunnelState())

    /** FRP 通道的实时状态（网络页 FRP 卡片订阅）。 */
    val frpState: StateFlow<TunnelState> = _frpState.asStateFlow()

    private val _stunState = MutableStateFlow(TunnelState())

    /** STUN 通道的实时状态（网络页 STUN 卡片订阅）。 */
    val stunState: StateFlow<TunnelState> = _stunState.asStateFlow()

    /**
     * 两条通道的聚合状态，保留给不区分通道的旧调用方。
     * 网络页不使用它 —— 它无法表达「两条同时在跑」。
     *
     * 同步维护（而不是 `combine(...).stateIn(...)`）：聚合值必须是
     * 「刚写入 _frpState/_stunState 的那一拍」的即时结果。用 combine 的话
     * 传播发生在 scope 上的另一个协程里，`start()` 一返回就读 `state.value`
     * 会读到初始值 —— 既有测试正是这么读的，会变成随机失败。
     */
    private val _state = MutableStateFlow(TunnelState())
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    init {
        // 每个后端的状态流都收，但只把「本通道当前后端」的状态写进对应通道流：
        // 例如从 Frp 切到 SakuraFrp 后，旧后端的迟到状态不能再污染 frpState。
        backends.values.forEach { backend ->
            scope.launch {
                backend.state.collect { backendState ->
                    when (laneOf(backend)) {
                        TunnelLane.Frp ->
                            if (backend === frpBackend) publish(TunnelLane.Frp, backendState)

                        TunnelLane.Stun -> publish(TunnelLane.Stun, backendState)
                    }
                }
            }
        }
    }

    /** 后端所属通道。按 [TunnelType] 判定，而不是按对象身份 —— 便于测试注入替身。 */
    private fun laneOf(backend: TunnelBackend): TunnelLane =
        if (backend.type == TunnelType.Stun) TunnelLane.Stun else TunnelLane.Frp

    // ── 启动 / 停止 ────────────────────────────────────────────

    /**
     * 启动 FRP 通道。
     *
     * @param config 必须是**已投影**的配置：调用方先用 FrpProfiles.applyTo 把选中的
     *   FrpProfile 摊进 `frpConfigText` / `sakura*` / `meFrp*` 字段，
     *   并把 `tunnelType` 设成对应的类型。
     */
    suspend fun startFrp(config: McConfig): Result<Unit> = startOnLane(TunnelLane.Frp, config)

    /** 启动 STUN 通道。与 [startFrp] 完全独立，可同时运行。 */
    suspend fun startStun(config: McConfig): Result<Unit> = startOnLane(TunnelLane.Stun, config)

    /** 停止 FRP 通道。不影响 STUN。 */
    suspend fun stopFrp() = stopLane(TunnelLane.Frp)

    /** 停止 STUN 通道。不影响 FRP。 */
    suspend fun stopStun() = stopLane(TunnelLane.Stun)

    /**
     * 兼容旧入口：按 `config.tunnelType` 决定启动哪条通道。
     *
     * 网络页已改为显式调用 [startFrp] / [startStun]（因为界面上不再有
     * 「穿透方式」单选）。这个入口保留，是给「只有一份整体配置」的场景
     * （以及既有测试）用的。
     */
    suspend fun start(config: McConfig): Result<Unit> =
        if (config.tunnelType == TunnelType.Stun) startStun(config) else startFrp(config)

    /** 停止全部通道。 */
    suspend fun stop() {
        stopFrp()
        stopStun()
    }

    private suspend fun startOnLane(lane: TunnelLane, config: McConfig): Result<Unit> {
        // 同一条通道上换后端必须先停旧的：frp 家族共用一个引擎进程，
        // 不停旧的就会出现两个 frpc 抢同一个本地端口。STUN 通道只有它自己，
        // 这里也先停一下，保证「重复点启动」不会留下两个监听。
        currentBackend(lane)?.let { runCatching { it.stop() } }

        val backend = backends[config.tunnelType]
        if (backend == null) {
            val msg = "${config.tunnelType.displayName} 后端尚未实现"
            publish(lane, TunnelState(
                status = TunnelStatus.Failed,
                errorMessage = msg,
                activeType = config.tunnelType
            ))
            logSink("[tunnel] 错误: $msg")
            return Result.failure(IllegalStateException(msg))
        }
        if (laneOf(backend) != lane) {
            val msg = "${config.tunnelType.displayName} 不属于 ${lane.name} 通道"
            logSink("[tunnel] 错误: $msg")
            return Result.failure(IllegalArgumentException(msg))
        }

        if (lane == TunnelLane.Frp) frpBackend = backend
        publish(lane, backend.state.value)

        val result = backend.start(config)

        // start() 返回后必须**同步**把后端的最新状态搬过来。
        //
        // init 里虽然为每个后端起了收集协程，但那是 `scope.launch` 异步启动的，
        // 且 FRP 通道以 `backend === frpBackend` 为条件 —— 判定可能发生在
        // frpBackend 赋值之前的那一轮。后端在 start() 内部同步推进的状态
        // （Starting → Failed / Running）可能赶在收集协程就绪之前就过去了，
        // 于是通道流永远停在 Starting，界面一直显示「启动中」。
        //
        // 真机表现：引擎日志已经 `login to server success` → `start proxy success`，
        // 隧道实际已通，界面却卡在「启动中」；失败时也同样是停在「启动中」。
        publish(lane, backend.state.value)

        result.onFailure { e ->
            // 与 FrpcBackend.onFailure 同样的回退：message 为 null 的异常
            // （如 NetworkOnMainThreadException）不能只打印 "null"，否则无法定位。
            val reason = e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName
            logSink("[tunnel] ${config.tunnelType.displayName} 启动失败: $reason")
        }
        return result
    }

    private suspend fun stopLane(lane: TunnelLane) {
        val backend = currentBackend(lane) ?: return
        backend.stop()
        if (lane == TunnelLane.Frp) frpBackend = null
        publish(lane, backend.state.value)
    }

    private fun currentBackend(lane: TunnelLane): TunnelBackend? = when (lane) {
        TunnelLane.Frp -> frpBackend
        TunnelLane.Stun -> backends[TunnelType.Stun]
    }

    private fun publish(lane: TunnelLane, state: TunnelState) {
        when (lane) {
            TunnelLane.Frp -> _frpState.value = state
            TunnelLane.Stun -> _stunState.value = state
        }
        // 通道流一改动就同步重算聚合值，避免聚合值落后一拍。
        _state.value = TunnelStateAggregator.aggregate(_frpState.value, _stunState.value)
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
        frpBackend = null
        scope.cancel()
    }
}
