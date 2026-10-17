package com.mineserve.mobile.server

import android.content.Context
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.TunnelState
import com.mineserve.mobile.data.TunnelStatus
import com.mineserve.mobile.data.TunnelType
import com.mineserve.mobile.server.tunnel.TunnelBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 假后端：不起真进程、不联网，只记录被调用次数并按指令推进状态。
 *
 * 这是「FRP 与 STUN 相互独立」这条需求的唯一可测方式 ——
 * 真后端里 FrpBackend 需要 frpc 引擎、StunTunnelBackend 需要真实 NAT 环境，
 * 两者都不是单元测试能提供的。
 */
private class FakeBackend(
    override val type: TunnelType,
    private val url: String = ""
) : TunnelBackend {

    private val _state = MutableStateFlow(TunnelState())
    override val state: StateFlow<TunnelState> = _state.asStateFlow()

    var startCalls = 0
        private set
    var stopCalls = 0
        private set

    override fun attachLog(logger: (String) -> Unit) = Unit

    override suspend fun start(config: McConfig): Result<Unit> {
        startCalls++
        _state.value = TunnelState(
            status = TunnelStatus.Running,
            publicUrl = url,
            activeType = type
        )
        return Result.success(Unit)
    }

    override suspend fun stop() {
        stopCalls++
        _state.value = TunnelState(status = TunnelStatus.Stopped, activeType = type)
    }
}

/**
 * 两条通道互不干扰。
 *
 * 改造前 [TunnelManager] 只有一个「当前后端」，`start()` 一律先停掉上一个 ——
 * 于是 STUN 一开，FRP 就被掐断。本测试锁死改造后的行为：
 * 两条通道各自启停，停一条不影响另一条。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TunnelLaneIndependenceTest {

    private fun ctx(): Context = RuntimeEnvironment.getApplication()

    private fun manager(frp: FakeBackend, stun: FakeBackend): TunnelManager = TunnelManager(
        context = ctx(),
        logSink = {},
        injectedBackends = mapOf(
            TunnelType.Frp to frp,
            TunnelType.Stun to stun
        )
    )

    private fun frpConfig() = McConfig(tunnelType = TunnelType.Frp)
    private fun stunConfig() = McConfig(tunnelType = TunnelType.Stun)

    @Test
    fun `启动 STUN 不会停掉 FRP`() = runBlocking {
        val frp = FakeBackend(TunnelType.Frp, url = "frp.example.com:25565")
        val stun = FakeBackend(TunnelType.Stun, url = "1.2.3.4:25565")
        val manager = manager(frp, stun)

        manager.startFrp(frpConfig())
        assertEquals(TunnelStatus.Running, manager.frpState.value.status)

        manager.startStun(stunConfig())

        // 核心断言：FRP 的 stop 一次都没被调用过。
        assertEquals("启动 STUN 不该停掉 FRP", 0, frp.stopCalls)
        assertEquals(TunnelStatus.Running, manager.frpState.value.status)
        assertEquals(TunnelStatus.Running, manager.stunState.value.status)

        manager.destroy()
    }

    @Test
    fun `两条通道各自暴露自己的公网地址`() = runBlocking {
        val frp = FakeBackend(TunnelType.Frp, url = "frp.example.com:25565")
        val stun = FakeBackend(TunnelType.Stun, url = "1.2.3.4:25565")
        val manager = manager(frp, stun)

        manager.startFrp(frpConfig())
        manager.startStun(stunConfig())

        // 聚合值只能表达一条，所以界面必须分别订阅这两条流。
        assertEquals("frp.example.com:25565", manager.frpState.value.publicUrl)
        assertEquals("1.2.3.4:25565", manager.stunState.value.publicUrl)

        manager.destroy()
    }

    @Test
    fun `停止 FRP 不影响 STUN`() = runBlocking {
        val frp = FakeBackend(TunnelType.Frp)
        val stun = FakeBackend(TunnelType.Stun)
        val manager = manager(frp, stun)

        manager.startFrp(frpConfig())
        manager.startStun(stunConfig())
        val stunStopsBefore = stun.stopCalls

        manager.stopFrp()

        assertEquals(TunnelStatus.Stopped, manager.frpState.value.status)
        assertEquals(TunnelStatus.Running, manager.stunState.value.status)
        assertEquals("停止 FRP 不该顺手停掉 STUN", stunStopsBefore, stun.stopCalls)
        assertEquals(1, frp.stopCalls)

        manager.destroy()
    }

    @Test
    fun `停止 STUN 不影响 FRP`() = runBlocking {
        val frp = FakeBackend(TunnelType.Frp)
        val stun = FakeBackend(TunnelType.Stun)
        val manager = manager(frp, stun)

        manager.startFrp(frpConfig())
        manager.startStun(stunConfig())
        val frpStopsBefore = frp.stopCalls

        manager.stopStun()

        assertEquals(TunnelStatus.Running, manager.frpState.value.status)
        assertEquals(TunnelStatus.Stopped, manager.stunState.value.status)
        assertEquals("停止 STUN 不该顺手停掉 FRP", frpStopsBefore, frp.stopCalls)

        manager.destroy()
    }

    @Test
    fun `重复启动同一条通道会先停掉上一个后端`() = runBlocking {
        // frp 家族共用一个引擎进程，不停旧的就会出现两个 frpc 抢同一个本地端口。
        val frp = FakeBackend(TunnelType.Frp)
        val stun = FakeBackend(TunnelType.Stun)
        val manager = manager(frp, stun)

        manager.startFrp(frpConfig())
        manager.startFrp(frpConfig())

        assertEquals(2, frp.startCalls)
        assertEquals("第二次启动前应先停掉第一个后端", 1, frp.stopCalls)

        manager.destroy()
    }

    @Test
    fun `旧入口 start 按 tunnelType 路由到对应通道`() = runBlocking {
        val frp = FakeBackend(TunnelType.Frp)
        val stun = FakeBackend(TunnelType.Stun)
        val manager = manager(frp, stun)

        manager.start(stunConfig())
        assertEquals(1, stun.startCalls)
        assertEquals(0, frp.startCalls)

        manager.start(frpConfig())
        assertEquals(1, frp.startCalls)
        // STUN 仍在跑：启动 FRP 没有把它关掉
        assertNotEquals(TunnelStatus.Stopped, manager.stunState.value.status)

        manager.destroy()
    }

    @Test
    fun `启动 STUN 不依赖 config 里的 tunnelType`() = runBlocking {
        // 回归测试：界面改成「两张卡各自启停」之后，不再有「穿透方式」单选，
        // 也就没人去把 config.tunnelType 设成 Stun 了 —— 它的默认值是 Frp。
        // 如果通道选择读的是 config.tunnelType，STUN 会去取 FrpBackend，
        // 然后死在「不属于 Stun 通道」上，表现就是「点启动没反应」。
        val frp = FakeBackend(TunnelType.Frp)
        val stun = FakeBackend(TunnelType.Stun)
        val manager = manager(frp, stun)

        val result = manager.startStun(McConfig()) // tunnelType 保持默认的 Frp

        assertEquals("STUN 必须被启动", 1, stun.startCalls)
        assertEquals("不能误启动 FRP", 0, frp.startCalls)
        assertEquals(true, result.isSuccess)
        assertEquals(TunnelStatus.Running, manager.stunState.value.status)

        manager.destroy()
    }

    @Test
    fun `聚合状态在两条都运行时优先 FRP`() = runBlocking {
        val frp = FakeBackend(TunnelType.Frp, url = "frp.example.com:25565")
        val stun = FakeBackend(TunnelType.Stun, url = "1.2.3.4:25565")
        val manager = manager(frp, stun)

        manager.startFrp(frpConfig())
        manager.startStun(stunConfig())

        assertEquals(TunnelType.Frp, manager.state.value.activeType)

        manager.destroy()
    }
}
