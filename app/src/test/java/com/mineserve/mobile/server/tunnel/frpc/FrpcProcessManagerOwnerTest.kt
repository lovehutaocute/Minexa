package com.mineserve.mobile.server.tunnel.frpc

import android.content.Context
import com.mineserve.mobile.data.TunnelStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

/**
 * 复现真机故障：`start()` 之后，引擎推送的 Starting/Running 状态
 * 落到「但没有后端认领本进程，界面不会更新」这条分支上。
 *
 * 真机日志（自建 frp，配置 frpc.toml）：
 * ```
 * [frpc] 引擎（版本未知），配置 frpc.toml
 * [frpc] 进程状态变更(Starting)，但没有后端认领本进程，界面不会更新
 * ... login to server success / proxy added / start proxy success
 * [frpc] 进程状态变更(Running)，但没有后端认领本进程，界面不会更新
 * ```
 * 隧道其实已经跑起来了，界面却停在「启动中」。
 *
 * 这条日志来自 [FrpcProcessManager.dispatchState] 中 `sink == null` 的分支，
 * 即 `stateSinks[stateOwner]` 取不到。本测试直接钉住认领关系本身。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FrpcProcessManagerOwnerTest {

    private fun ctx(): Context = RuntimeEnvironment.getApplication()

    /** 模拟一个后端：只关心自己被推送了什么状态。 */
    private class FakeBackend(val name: String) {
        val received = mutableListOf<TunnelStatus?>()
        override fun toString() = "FakeBackend($name)"
    }

    @Test
    fun `attach 过的后端能收到 dispatchState 推送的状态`() {
        // 直接用单例，和真机一致。
        val pm = FrpcProcessManager.getInstance(ctx())
        val backend = FakeBackend("A")

        pm.attach(owner = backend, logSink = null, stateSink = { status, _ ->
            backend.received.add(status)
        })

        // 模拟 start() 认领：真机里 FrpcProcessManager.start(owner = 该后端)。
        // 这里不能真跑进程，但 stateOwner 的赋值就是 start() 的第一步，
        // 因此用反射/私有入口不可行 —— 改为直接验证 attach 建立的映射是否可用。
        //
        // 关键：dispatchState 是私有的，只能通过 start() 触达。为了在不启动
        // 真实进程的前提下验证「已 attach 的后端能被认领」，这里检查
        // attach 之后 stateSinks 中确实登记了该 owner。
        val sinks = readPrivateField<MutableMap<Any, Any?>>(pm, "stateSinks")
        assertNotNull("stateSinks 不应为 null", sinks)
        assertTrue(
            "attach 之后该后端必须登记在 stateSinks 中，否则 dispatchState 找不到它。" +
                "当前登记的 key：${sinks!!.keys}",
            sinks.keys.any { it === backend }
        )
    }

    @Test
    fun `未 attach 的后端即使被设为 owner 也收不到状态`() {
        val pm = FrpcProcessManager.getInstance(ctx())
        val attached = FakeBackend("attached")
        val neverAttached = FakeBackend("neverAttached")

        pm.attach(owner = attached, logSink = null, stateSink = { status, _ ->
            attached.received.add(status)
        })

        val sinks = readPrivateField<MutableMap<Any, Any?>>(pm, "stateSinks")!!
        assertTrue(sinks.keys.any { it === attached })
        assertTrue(
            "从未 attach 的后端不可能在 stateSinks 里 —— 若它成了 stateOwner，" +
                "dispatchState 就会走到「没有后端认领本进程」分支。",
            sinks.keys.none { it === neverAttached }
        )
    }

    @Test
    fun `TunnelManager 构造的每个后端都必须完成 attach`() {
        // 真机故障的另一条可能路径：TunnelManager 新建后，其内部后端
        // 没有走到 attach（例如 onEach 未执行），于是 start() 时
        // owner 指向一个未登记的后端。
        val pm = FrpcProcessManager.getInstance(ctx())
        val manager = com.mineserve.mobile.server.TunnelManager(ctx(), logSink = {})

        val sinks = readPrivateField<MutableMap<Any, Any?>>(pm, "stateSinks")!!
        val frpBackend = readPrivateField<Any>(manager, "backends")
            ?.let { it as Map<*, *> }
            ?.get(com.mineserve.mobile.data.TunnelType.Frp)

        assertNotNull("应能取到自建 frp 后端", frpBackend)
        assertTrue(
            "TunnelManager 构造后，其自建 frp 后端必须已登记进 stateSinks。" +
                "当前登记的 key：${sinks.keys}",
            sinks.keys.any { it === frpBackend }
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> readPrivateField(target: Any, name: String): T? {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            try {
                val f = cls.getDeclaredField(name)
                f.isAccessible = true
                return f.get(target) as T
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        return null
    }
}
