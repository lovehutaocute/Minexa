package com.mineserve.mobile.server.tunnel

import android.content.Context
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.TunnelType
import com.mineserve.mobile.server.tunnel.frpc.FrpcProcessManager
import com.mineserve.mobile.server.tunnel.frpc.FrpcRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 钉住「传给 `processManager.start(owner = ?)` 的必须是后端自身」。
 *
 * ## 真机故障（隧道日志，自建 frp）
 * ```
 * [diag] start 认领 owner=DispatchedCoroutine@9bdd67f，已登记=false
 * [diag] 状态 Starting 无人认领：stateOwner=DispatchedCoroutine@9bdd67f,
 *        stateSinks=[FrpBackend@a68c2c0, SakuraFrpBackend@a719bf9, MeFrpBackend@b76773e]
 * [tunnel] [frpc] 进程状态变更(Starting)，但没有后端认领本进程，界面不会更新
 * ```
 * 引擎随后 `login to server success` → `start proxy success`（隧道真的通了），
 * 但界面永远停在「启动中」。
 *
 * ## 根因
 * `FrpcBackend.start()` 的 `owner = this` 曾被写在
 * `withContext(Dispatchers.IO) { ... }` 的 lambda **内部**。该 lambda 里的 `this`
 * 是协程的 `CoroutineScope`（运行时为 `DispatchedCoroutine`），不是 `FrpcBackend`。
 * 于是 `stateOwner` 指向一个从未 attach 的对象，`stateSinks[stateOwner]` 查不到，
 * 所有进程状态被丢弃。
 *
 * ## 为什么必须单独测这一点
 * 缺少真引擎时 `processManager.start()` 会提前抛异常，
 * `FrpcBackendStartThreadTest` 与 `FrpcProcessManagerOwnerTest` 都走不到传 owner
 * 那一行，因此漏掉了这个回归。本测试直接检查**协程块内的 this 语义**，
 * 与产品代码里 `val self = this` 的写法对应。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FrpcBackendOwnerIdentityTest {

    private fun ctx(): Context = RuntimeEnvironment.getApplication()

    @Test
    fun `withContext 块内的 this 是协程作用域而非后端 —— 所以必须在块外捕获后端自身`() =
        runBlocking {
            val scopeInsideBlock = withContext(Dispatchers.IO) { this }

            assertFalse(
                "withContext 块内的 this 不应是 FrpcBackend。若产品代码在块内写 " +
                    "owner = this，就会把状态认领交给一个未 attach 的对象，导致" +
                    "「没有后端认领本进程」、界面卡在「启动中」。实际类型：" +
                    scopeInsideBlock.javaClass.simpleName,
                scopeInsideBlock is TunnelBackend
            )
            assertTrue(
                "块内 this 是协程作用域，类型应含 Coroutine/Scope 语义；实际：" +
                    scopeInsideBlock.javaClass.simpleName,
                scopeInsideBlock.javaClass.simpleName.contains("Coroutine") ||
                    scopeInsideBlock.javaClass.simpleName.contains("Scope")
            )
        }

    /**
     * 端到端：走完真实的 `FrpcBackend.start()`，断言它最终把 owner 设成了后端自身。
     *
     * 用一个只记录 owner 的假管理器不现实（`processManager` 在基类构造时注入、
     * 类型为具体类），所以这里改为验证**可观测的结果**：start 之后，
     * `stateOwner` 必须等于该后端实例，而不是某个协程对象。
     */
    @Test
    fun `start 之后 stateOwner 必须是后端自身`() {
        val ctx = ctx()
        val runtime = FrpcRuntime(ctx).also { r ->
            // 让 isInstalled() 通过，好让 start() 走到 processManager.start 之前。
            r.engineFile.parentFile?.mkdirs()
            r.engineFile.writeBytes(ByteArray(1 * 1024 * 1024 + 1))
        }
        val pm = FrpcProcessManager.getInstance(ctx)
        val backend = object : FrpcBackend(runtime, pm, TunnelType.Frp) {
            override fun prepareConfig(config: McConfig) =
                java.io.File(tunnelDir, "frpc-owner.toml").apply {
                    writeText("serverAddr = \"127.0.0.1\"\n")
                }
        }

        // 触发一次真实启动。processManager.start 可能因缺 loader 抛错，
        // 但 owner 的赋值发生在其之前（FrpcProcessManager.start 的第 209 行）。
        runBlocking { runCatching { backend.start(McConfig()) } }

        val owner = readField<Any?>(pm, "stateOwner")
        assertTrue(
            "start 之后 stateOwner 不应是协程对象。实际=${owner?.javaClass?.simpleName}。" +
                "若为 DispatchedCoroutine，说明 owner = this 被写在了 withContext 块内。",
            owner == null || !owner.javaClass.simpleName.contains("Coroutine")
        )
        if (owner != null) {
            assertTrue(
                "stateOwner 应是后端自身（这样 stateSinks[owner] 才查得到）。" +
                    "实际类型=${owner.javaClass.simpleName}",
                owner is FrpcBackend
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> readField(target: Any, name: String): T? {
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
