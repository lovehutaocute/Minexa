package com.mineserve.mobile.data

/**
 * 相互独立的隧道通道。
 *
 * ## 为什么要有这个概念
 *
 * [com.mineserve.mobile.server.TunnelManager] 原先只有一个「当前后端」，
 * 启动任意一条隧道都会先停掉上一条 —— 于是 STUN 一开，FRP 就被关掉。
 * 但这两者的运行方式完全不同：
 *
 * - [Frp]：拉起 frpc 子进程（由单例 `FrpcProcessManager` 管理，**全局只有一个进程**，
 *   所以 Frp / SakuraFrp / MeFrp 天生互斥）；
 * - [Stun]：应用内自己开 socket 做打洞与转发，不占用那个进程。
 *
 * - [EasyTier]：App 进程内的 VpnService + JNI 组网，既不是子进程也不占 frp 的进程，
 *   所以同样可以与前两条并存。
 *
 * 所以「多条隧道可以同时运行」在技术上是成立的，需要的只是把
 * 「一个当前后端」拆成「每条通道一个当前后端」。
 */
enum class TunnelLane { Frp, Stun, EasyTier }

/**
 * 两条通道的聚合视图。
 *
 * 给「只关心有没有隧道在跑」的地方用（例如概览页的历史逻辑、以及
 * `TunnelManager.state` 这个旧入口）。网络页本身不读聚合值，
 * 它分别订阅 [TunnelLane.Frp] 和 [TunnelLane.Stun]，那样两张卡才可能各自显示。
 *
 * 规则（按优先级）：
 * 1. 谁 Running 用谁；两条都 Running 时优先 FRP（它是用户有意配置的主通道）；
 * 2. 都没 Running 时，取「有动静」的那条（Failed / Starting），
 *    这样界面上能看到失败原因，而不是一片空白；
 * 3. 都没动静时给 STUN 的空状态。
 */
object TunnelStateAggregator {

    fun aggregate(frp: TunnelState, stun: TunnelState): TunnelState =
        aggregate(frp, stun, TunnelState())

    /**
     * 三条通道的聚合。优先级：Frp > Stun > EasyTier。
     *
     * EasyTier 排最后是因为它面向「固定小圈子」——对外可访问性最弱的一条，
     * 聚合值主要用于"有没有路能进服务器"这类判断，不宜由它充当代表。
     */
    fun aggregate(frp: TunnelState, stun: TunnelState, easyTier: TunnelState): TunnelState {
        val running = listOf(frp, stun, easyTier).firstOrNull { it.status == TunnelStatus.Running }
        if (running != null) return running
        val active = listOf(frp, stun, easyTier).firstOrNull { isActive(it.status) }
        if (active != null) return active
        if (frp.status != TunnelStatus.Idle) return frp
        if (stun.status != TunnelStatus.Idle) return stun
        return easyTier
    }

    /** 是否处于「用户看得见动静」的状态（既没在跑、也不算彻底静默）。 */
    private fun isActive(status: TunnelStatus): Boolean =
        status != TunnelStatus.Idle && status != TunnelStatus.Stopped
}
