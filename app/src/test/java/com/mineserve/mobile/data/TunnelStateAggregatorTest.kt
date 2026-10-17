package com.mineserve.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [TunnelStateAggregator] 的纯函数测试。
 *
 * 聚合值只服务于「不区分通道」的旧调用方，规则必须确定 ——
 * 尤其是「两条都 Running 时返回哪条」，否则同一个界面在不同机器上显示不同的地址。
 */
class TunnelStateAggregatorTest {

    private fun state(
        status: TunnelStatus,
        url: String = "",
        type: TunnelType? = null,
        error: String = ""
    ) = TunnelState(status = status, publicUrl = url, activeType = type, errorMessage = error)

    @Test
    fun `只有 FRP 在跑时聚合值就是 FRP`() {
        val frp = state(TunnelStatus.Running, url = "frp:25565", type = TunnelType.Frp)
        val stun = state(TunnelStatus.Idle)

        val result = TunnelStateAggregator.aggregate(frp, stun)

        assertEquals(TunnelType.Frp, result.activeType)
        assertEquals("frp:25565", result.publicUrl)
    }

    @Test
    fun `只有 STUN 在跑时聚合值就是 STUN`() {
        val frp = state(TunnelStatus.Stopped)
        val stun = state(TunnelStatus.Running, url = "1.2.3.4:25565", type = TunnelType.Stun)

        val result = TunnelStateAggregator.aggregate(frp, stun)

        assertEquals(TunnelType.Stun, result.activeType)
        assertEquals("1.2.3.4:25565", result.publicUrl)
    }

    @Test
    fun `两条都在跑时优先 FRP`() {
        // FRP 是用户有意配置的主通道，STUN 多半是临时试一下。
        // 固定优先 FRP，聚合值的「哪条地址」才是可预测的。
        val frp = state(TunnelStatus.Running, url = "frp:25565", type = TunnelType.Frp)
        val stun = state(TunnelStatus.Running, url = "1.2.3.4:25565", type = TunnelType.Stun)

        val result = TunnelStateAggregator.aggregate(frp, stun)

        assertEquals(TunnelType.Frp, result.activeType)
    }

    @Test
    fun `都没在跑时优先展示有动静的那条`() {
        // 失败原因必须能传到界面上，所以 Failed 优先于静默的 Idle。
        val frp = state(TunnelStatus.Failed, type = TunnelType.Frp, error = "引擎未安装")
        val stun = state(TunnelStatus.Idle)

        val result = TunnelStateAggregator.aggregate(frp, stun)

        assertEquals(TunnelType.Frp, result.activeType)
        assertEquals("引擎未安装", result.errorMessage)
    }

    @Test
    fun `STUN 失败而 FRP 静默时展示 STUN`() {
        val frp = state(TunnelStatus.Idle)
        val stun = state(TunnelStatus.Failed, type = TunnelType.Stun, error = "打洞失败")

        val result = TunnelStateAggregator.aggregate(frp, stun)

        assertEquals(TunnelType.Stun, result.activeType)
        assertEquals("打洞失败", result.errorMessage)
    }

    @Test
    fun `两条都停止时不会误报运行中`() {
        val frp = state(TunnelStatus.Stopped)
        val stun = state(TunnelStatus.Stopped)

        val result = TunnelStateAggregator.aggregate(frp, stun)

        assertEquals(TunnelStatus.Stopped, result.status)
    }
}
