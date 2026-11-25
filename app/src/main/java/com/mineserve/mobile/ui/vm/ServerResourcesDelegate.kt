package com.mineserve.mobile.ui.vm

import com.mineserve.mobile.data.ServerResourceStats
import com.mineserve.mobile.runtime.TermuxRuntime
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ServerResourcesDelegate(
    private val scope: CoroutineScope,
    private val uiVisible: () -> Boolean,
    private val termuxRuntime: TermuxRuntime,
    private val collectResourcesOnce: (Boolean, Boolean) -> Boolean,
    private val invalidateResourceCaches: () -> Unit,
    private val isActiveCorePaper: () -> Boolean
) {
    private val _serverResources = MutableStateFlow(ServerResourceStats())
    val serverResources: StateFlow<ServerResourceStats> = _serverResources.asStateFlow()
    private val statusRefreshInFlight = AtomicBoolean(false)
    private val _isRefreshingStatus = MutableStateFlow(false)
    val isRefreshingStatus: StateFlow<Boolean> = _isRefreshingStatus.asStateFlow()
    private val _lastStatusRefreshAtMs = MutableStateFlow(0L)
    val lastStatusRefreshAtMs: StateFlow<Long> = _lastStatusRefreshAtMs.asStateFlow()

    fun publishResources(newStats: ServerResourceStats, force: Boolean) {
        // 值无变化时不发布新快照：避免每 15 秒无谓的重组（后台游戏挤压 CPU 时尤其明显）。
        // 但 [force]（手动刷新）时必须无条件发布，否则用户会觉得「点了没反应」。
        val prev = _serverResources.value
        if (force ||
            prev.processMemoryMb != newStats.processMemoryMb ||
            prev.cpuPercent != newStats.cpuPercent ||
            prev.deviceCpuPercent != newStats.deviceCpuPercent ||
            prev.availableBytes != newStats.availableBytes ||
            prev.directoryBytes != newStats.directoryBytes ||
            prev.javaAvailable != newStats.javaAvailable
        ) {
            _serverResources.value = newStats
        }
    }

    /** Samples values that belong to the selected server. */
    fun startServerResourceCollection() {
        scope.launch {
            // 首屏尽快出值：原来开局先 delay(15s)，加上「首次采样只建基线」的语义，
            // 用户进界面后可能半分钟都看不到 CPU/内存。这里先立即采一次轻量数据
            // （不扫服务端目录），再做原来的 15s 周期循环。
            withContext(Dispatchers.IO) { collectResourcesOnce(false, false) }
            // 整机 CPU 走 /proc/stat 两次采样求差值，首次调用只建基线、拿不到读数。
            // 若等到 15s 后的下一轮才采第二次，进界面后会有十几秒显示 `--`；
            // 这里隔 1.2s 补采一次（采样窗口 ≥1s 才有意义），让主路径尽快出真实值，
            // 期间 UI 会由 cpufreq 回退路径先给一个粗略值兜底。
            delay(1_200)
            withContext(Dispatchers.IO) { collectResourcesOnce(false, false) }
            // Defer the first recursive server-directory scan until the first screen settles.
            delay(15_000)
            while (true) {
                // 界面不在前台时降频，并且**跳过最昂贵的服务端目录遍历**
                // （walkTopDown 递归求和世界文件，动辄几万个小文件）：锁屏/切后台后
                // 还在每 15 秒扫一遍既耗电，又会和正在写区块的 MC 抢 IO。
                // 其余采样（/proc 内存与 CPU、StatFs）很廉价且悬浮窗/通知/组件要用，
                // 所以后台仍然保留，只是放慢到 1 分钟。
                val visible = uiVisible()
                withContext(Dispatchers.IO) { collectResourcesOnce(false, visible) }
                delay(if (visible) 15_000 else 60_000)
            }
        }
    }

    /**
     * 手动刷新：立即重新采集服务器状态并请求前台服务查询最新数据。
     */
    fun refreshServerStatus() {
        if (!statusRefreshInFlight.compareAndSet(false, true)) return
        _isRefreshingStatus.value = true
        scope.launch(Dispatchers.IO) {
            var sampled = false
            try {
                invalidateResourceCaches()
                sampled = collectResourcesOnce(true, true)
                if (sampled && termuxRuntime.isMcRunning()) {
                    // 运行中的服务器异步回复 list/tps，由现有解析器更新在线人数与 TPS。
                    termuxRuntime.sendCommand("list")
                    if (isActiveCorePaper()) termuxRuntime.sendCommand("tps")
                }
            } finally {
                statusRefreshInFlight.set(false)
                _isRefreshingStatus.value = false
                // 本轮没采到（并发锁被占 / 探针失败）就不记录「刚刚更新」——
                // 否则 UI 显示刚刷新过、拿到的却还是上一次的旧快照。
                if (sampled) _lastStatusRefreshAtMs.value = System.currentTimeMillis()
            }
        }
    }
}
