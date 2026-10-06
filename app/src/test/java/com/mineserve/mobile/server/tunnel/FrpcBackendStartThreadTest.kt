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
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * 钉住「隧道启动不得在主线程上做网络/文件 IO」这条契约。
 *
 * ## 背景（1.2.8 的真实故障）
 *
 * `FrpcBackend.start()` 会调用 `prepareConfig()`，而 SakuraFrp / MeFrp 两家的
 * `prepareConfig` 里是**同步** HTTP 请求（`SakuraFrpApi.tunnelConfig` /
 * `MeFrpApi.proxyConfig`，内部是阻塞的 `HttpURLConnection`）。
 *
 * 调用方 `McViewModel.startTunnel()` 用的是 `viewModelScope.launch`（默认
 * `Dispatchers.Main`），启动路径**没有** `withContext(Dispatchers.IO)`，
 * 于是网络请求落在主线程，被 Android 抛 `NetworkOnMainThreadException`。
 * 该异常在 AOSP 里没有 message，日志只剩「启动失败：null」—— 用户与开发者
 * 都无从定位，实际排查中一度被误判为服务商 / 引擎 / 配置的问题。
 *
 * 对照：同一个界面里「拉取隧道列表」的调用点（NetworkScreen）是包了
 * `withContext(Dispatchers.IO)` 的，唯独启动路径漏了。
 *
 * ## 这个测试怎么测
 *
 * 用一个名字叫 `main` 的**单线程执行器**模拟主线程，在其中调用
 * `FrpcBackend.start()`，记录 `prepareConfig()` 实际执行时所在的线程名：
 *
 * - 修复前：`prepareConfig` 就在 `main` 上跑 → 断言失败。
 * - 修复后：基类把它切到 IO 线程 → 线程名不是 `main`。
 *
 * 之所以能测：`FrpcBackend.start()` 是 `final` 的真实产品代码，测试子类只覆写
 * `prepareConfig` 来**观察**线程，不改变被测逻辑。
 *
 * ## 为什么必须用 Robolectric
 *
 * `FrpcBackend` 依赖 `android.content.Context`（`FrpcRuntime` 要 `filesDir`）。
 * 纯 JVM 单测里未 mock 的 Android 类型会抛 "not mocked"，
 * 因此拿不到真实的 `start()` 行为。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FrpcBackendStartThreadTest {

    /** 记录 prepareConfig 在哪个线程上执行。 */
    private class ThreadRecordingBackend(
        runtime: FrpcRuntime,
        processManager: FrpcProcessManager,
        val onPrepare: () -> File
    ) : FrpcBackend(runtime, processManager, TunnelType.SakuraFrp) {

        val prepareThreadName = AtomicReference<String?>(null)

        override fun prepareConfig(config: McConfig): File {
            prepareThreadName.set(Thread.currentThread().name)
            return onPrepare()
        }
    }

    private fun context(): Context = RuntimeEnvironment.getApplication()

    /**
     * 造一个「看起来已安装」的引擎：`FrpcRuntime.isInstalled()` 要求文件存在且
     * 大于 1 MiB，所以这里真的写一个够大的文件（内容无关紧要，不会被执行）。
     */
    private fun runtimeWithFakeEngine(ctx: Context): FrpcRuntime {
        val runtime = FrpcRuntime(ctx)
        val engine = runtime.engineFile
        engine.parentFile?.mkdirs()
        engine.writeBytes(ByteArray(1 * 1024 * 1024 + 1))
        check(runtime.isInstalled()) { "测试前置条件失败：伪造的引擎未被识别为已安装" }
        return runtime
    }

    @Test
    fun `prepareConfig 不在主线程上执行`() {
        val ctx = context()
        val runtime = runtimeWithFakeEngine(ctx)
        val processManager = FrpcProcessManager.getInstance(ctx)

        val configFile = File(runtime.engineDir.parentFile, "tunnel/frpc-sakura.toml")
        configFile.parentFile?.mkdirs()
        val backend = ThreadRecordingBackend(runtime, processManager) { configFile }

        // 名字叫 "main" 的单线程执行器 —— 模拟 viewModelScope.launch 的 Main 调度器。
        val mainExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "main")
        }
        try {
            // 在"主线程"上调用 start()，正如 McViewModel.startTunnel() 所做的。
            val result = mainExecutor.submit<Result<Unit>> {
                runBlocking { backend.start(McConfig()) }
            }.get()

            val observed = backend.prepareThreadName.get()
            assertTrue("prepareConfig 必须被调用到（否则测试没测到东西）", observed != null)
            // 注意不能直接和 "main" 做相等比较：Kotlin 协程会把线程名改写成
            // "main @coroutine#41"，字符串并不等于 "main"，那样断言会**假通过**。
            // （这正是本测试第一版的真实缺陷，靠 probe 才发现。）
            assertFalse(
                "prepareConfig 在主线程上执行（观测到线程名 \"$observed\"）：网络请求会被 Android " +
                    "拦下并抛 NetworkOnMainThreadException，其 message 为 null，日志只会显示" +
                    "「启动失败：null」。FrpcBackend.start() 必须用 withContext(Dispatchers.IO) " +
                    "包住 prepareConfig 的调用。",
                observed!!.startsWith("main")
            )
            // 不关心 start 的成败：processManager.start 会因为缺少真引擎/启动器而失败，
            // 那是预期内的（本测试只钉线程归属，不钉进程能否拉起）。
            result.getOrNull()
        } finally {
            mainExecutor.shutdownNow()
        }
    }

    @Test
    fun `prepareConfig 在 IO 调度器线程上执行`() {
        val ctx = context()
        val runtime = runtimeWithFakeEngine(ctx)
        val processManager = FrpcProcessManager.getInstance(ctx)

        val configFile = File(runtime.engineDir.parentFile, "tunnel/frpc-me.toml")
        configFile.parentFile?.mkdirs()
        val backend = ThreadRecordingBackend(runtime, processManager) { configFile }

        // 直接用 IO 调度器调用：这条路径本来就是安全的，用作对照。
        runBlocking {
            withContext(Dispatchers.IO) { backend.start(McConfig()) }
        }

        val observed = backend.prepareThreadName.get()
        assertTrue("prepareConfig 必须被调用到", observed != null)
        assertFalse(
            "即便从 IO 调度器调用，也不该跑在 main 上",
            observed!!.startsWith("main")
        )
        // Dispatchers.IO 的线程名形如 "DefaultDispatcher-worker-N"
        assertTrue(
            "期望跑在 IO 线程池上，实际是 $observed",
            observed!!.contains("DefaultDispatcher") || observed.contains("worker")
        )
    }
}
