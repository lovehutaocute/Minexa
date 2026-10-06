package com.mineserve.mobile.server

import android.content.Context
import com.mineserve.mobile.data.TunnelState
import com.mineserve.mobile.data.TunnelStatus
import com.mineserve.mobile.data.TunnelType
import com.mineserve.mobile.data.McConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 端到端复现真机故障：隧道实际已启动（引擎日志 `start proxy success`），
 * 但 [TunnelManager.state] 一直停在 Starting，界面卡在「启动中」。
 *
 * 真机日志证据（自建 frp）：
 * ```
 * [frpc] 进程状态变更(Starting)，但没有后端认领本进程，界面不会更新
 * ... login to server success → proxy added → start proxy success
 * [frpc] 进程状态变更(Running)，但没有后端认领本进程，界面不会更新
 * ```
 *
 * 本测试从 `TunnelManager` 的公开入口出发，观察它对外暴露的 state 是否
 * 能反映后端的状态推进，不依赖真机与账号。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TunnelManagerStatePropagationTest {

    private fun ctx(): Context = RuntimeEnvironment.getApplication()

    /**
     * 只验证「TunnelManager 对外暴露的 state 能反映后端状态」这件事本身。
     *
     * 用一个可控的假后端替换真实后端不现实（backends 是 private val 且
     * 在构造时固定），所以这里直接检查 TunnelManager 在启动前后的状态一致性：
     * `start()` 若因缺少引擎而失败，state 必须变成 Failed —— 而不是永远停在
     * Starting。后者正是真机现象。
     */
    @Test
    fun `start 失败时 state 不得停留在 Starting`() = runBlocking {
        val manager = TunnelManager(ctx(), logSink = {})

        // 未导入引擎时启动必然失败（FrpcBackend.start 会抛 IllegalStateException）。
        val config = McConfig(tunnelType = TunnelType.Frp)

        manager.start(config)

        val st = manager.state.value
        assertEquals(
            "tunnelType 应被记录，便于界面显示是哪条隧道",
            TunnelType.Frp,
            st.activeType
        )
        assertNotEquals(
            "start() 返回后 state 不得仍停留在 Starting —— 真机上界面卡在「启动中」" +
                "就是这个现象。当前状态=${st.status}，errorMessage=${st.errorMessage}",
            TunnelStatus.Starting,
            st.status
        )
    }

    @Test
    fun `start 之后 state 必须是终态或明确的进行态`() = runBlocking {
        val manager = TunnelManager(ctx(), logSink = {})
        manager.start(McConfig(tunnelType = TunnelType.Frp))

        val st = manager.state.value
        assertTrue(
            "state 必须是 Failed / Running / Stopped 之一，不能是 Starting。" +
                "当前=${st.status}",
            st.status == TunnelStatus.Failed ||
                st.status == TunnelStatus.Running ||
                st.status == TunnelStatus.Stopped
        )
    }

    private fun McConfig(tunnelType: TunnelType): McConfig =
        com.mineserve.mobile.data.McConfig().copy(tunnelType = tunnelType)
}
