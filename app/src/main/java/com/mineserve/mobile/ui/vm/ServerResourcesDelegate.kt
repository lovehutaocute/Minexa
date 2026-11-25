package com.mineserve.mobile.ui.vm

import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.ServerResourceStats
import com.mineserve.mobile.data.ServerState
import com.mineserve.mobile.runtime.TermuxRuntime
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

internal class ServerResourcesDelegate(
    private val scope: CoroutineScope,
    private val uiVisible: () -> Boolean,
    private val termuxRuntime: TermuxRuntime,
    private val config: StateFlow<McConfig>,
    private val updateServerState: ((ServerState) -> ServerState) -> Unit,
    private val isActiveCorePaper: () -> Boolean
) {
    @Volatile private var cachedResourceDir: String? = null
    @Volatile private var cachedDirectoryBytes: Long? = null
    @Volatile private var cachedDirectoryBytesAtMs = 0L
    @Volatile private var cachedMemoryAtMs = 0L
    @Volatile private var cachedMemoryMb = 0L
    @Volatile private var cachedJavaAvailable: Boolean? = null
    @Volatile private var cachedJavaAvailableAtMs = 0L

    /**
     * 采集串行化锁。
     *
     * 周期采集（每 15s）与手动刷新（[refreshServerStatus]）可能并发调用
     * [collectServerResourcesOnce]，而采集内部有 `walkTopDown` 全目录求和，
     * 并发执行会把同一目录扫两遍、两个协程互相覆盖上面的采样缓存。
     * 用 tryLock 语义：已在采集就直接跳过本轮，不做重复工作。
     *
     * @return 是否真的采集了。跳过时必须让调用方知道——以前返回 Unit，
     *         [refreshServerStatus] 抢不到锁照样置 `_lastStatusRefreshAtMs`，
     *         UI 显示「刚刚更新」但拿到的还是上一次的旧数据。
     */
    private val resourceCollectionMutex = Mutex()

    /** 使所有采样缓存失效，让下一次采集拿到真实的最新值。 */
    private fun invalidateResourceCaches() {
        cachedDirectoryBytesAtMs = 0L
        cachedMemoryAtMs = 0L
        cachedJavaAvailableAtMs = 0L
    }

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
            withContext(Dispatchers.IO) { collectServerResourcesOnce(includeDirectorySize = false) }
            // 整机 CPU 走 /proc/stat 两次采样求差值，首次调用只建基线、拿不到读数。
            // 若等到 15s 后的下一轮才采第二次，进界面后会有十几秒显示 `--`；
            // 这里隔 1.2s 补采一次（采样窗口 ≥1s 才有意义），让主路径尽快出真实值，
            // 期间 UI 会由 cpufreq 回退路径先给一个粗略值兜底。
            delay(1_200)
            withContext(Dispatchers.IO) { collectServerResourcesOnce(includeDirectorySize = false) }
            // Defer the first recursive server-directory scan until the first screen settles.
            delay(15_000)
            while (true) {
                // 界面不在前台时降频，并且**跳过最昂贵的服务端目录遍历**
                // （walkTopDown 递归求和世界文件，动辄几万个小文件）：锁屏/切后台后
                // 还在每 15 秒扫一遍既耗电，又会和正在写区块的 MC 抢 IO。
                // 其余采样（/proc 内存与 CPU、StatFs）很廉价且悬浮窗/通知/组件要用，
                // 所以后台仍然保留，只是放慢到 1 分钟。
                val visible = uiVisible()
                withContext(Dispatchers.IO) { collectServerResourcesOnce(includeDirectorySize = visible) }
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
                sampled = collectServerResourcesOnce(force = true)
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
    /**
     * 采集一次服务器资源快照。
     *
     * @param force 为 true 时忽略「值未变化就不发布」的优化，强制发布一次。
     *        手动刷新走这条路：即使用户在缓存窗口内点击，UI 也必定收到新快照，
     *        并且能通过 [ServerResourceStats.sampledAtMs] 观察到刷新确实生效了。
     */
    /**
     * @param includeDirectorySize 是否允许执行服务端目录的递归求和（最昂贵的一步）。
     *        界面不在前台时传 false：缓存命中（同一目录）就沿用旧值，
     *        缓存不可用则返回 null（未知）而不是去扫一遍。
     */
    private fun collectServerResourcesOnce(force: Boolean = false, includeDirectorySize: Boolean = true): Boolean {
        // 已在采集则跳过本轮：并发采集会重复全目录扫描并互相覆盖采样缓存。
        if (!resourceCollectionMutex.tryLock()) return false
        try {
            val cfg = config.value
            val active = cfg.installedCores.find { it.name == cfg.activeCoreName }
            val dir = active?.let { termuxRuntime.serverDirFor(it.dirName) }
            val available = dir?.takeIf { it.exists() }?.let { android.os.StatFs(it.path).availableBytes }
            val now = System.currentTimeMillis()
            val running = termuxRuntime.isMcRunning()
            val directoryBytes = dir?.takeIf { running && it.exists() }?.let { serverDir ->
                val sameDir = cachedResourceDir == serverDir.path
                val stale = now - cachedDirectoryBytesAtMs >= 60_000L
                when {
                    // 允许扫描且（换了目录 或 缓存过期）：真正遍历一次
                    includeDirectorySize && (!sameDir || stale) -> {
                        cachedResourceDir = serverDir.path
                        cachedDirectoryBytes = serverDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                        cachedDirectoryBytesAtMs = now
                        cachedDirectoryBytes
                    }
                    // 同目录的缓存可直接沿用（后台路径主要走这里）
                    sameDir -> cachedDirectoryBytes
                    // 后台且无可用缓存：宁可显示未知，也不去扫盘
                    else -> null
                }
            }
            // 进程内存采样遍历 /proc，降频到 30s（运行中才需要），窗口内沿用旧值。
            if (running && now - cachedMemoryAtMs >= 30_000L) {
                cachedMemoryAtMs = now
                cachedMemoryMb = termuxRuntime.mcProcessMemoryMb()
            }
            val effectiveMemory = cachedMemoryMb.takeIf { running && it > 0L }
            // 服务端进程自身 CPU（支持多线程超过 100%），不可用或未运行时为 null。
            val cpu = if (running) termuxRuntime.mcProcessCpuPercent() else null
            // 整机 CPU 总使用率：与 running 解耦，服务端未运行时也要有读数；
            // 首次调用只建立基线返回 null，下一次周期采样即拿到真实值。
            val deviceCpu = termuxRuntime.deviceCpuPercent()
            if (running && now - cachedJavaAvailableAtMs >= 60_000L) {
                cachedJavaAvailable = termuxRuntime.isJavaInstalled(cfg.selectedJavaVersion, cfg.selectedJavaVendor)
                cachedJavaAvailableAtMs = now
            }
            val javaAvailable = cachedJavaAvailable ?: true
            val newStats = ServerResourceStats(
                processMemoryMb = effectiveMemory,
                cpuPercent = cpu,
                deviceCpuPercent = deviceCpu,
                availableBytes = available,
                directoryBytes = directoryBytes,
                javaAvailable = javaAvailable,
                sampledAtMs = now
            )
            publishResources(newStats, force)
            updateServerState { state ->
                if (!force &&
                    state.usedMemoryMb == (effectiveMemory ?: 0L) &&
                    state.cpuPercent == cpu
                ) {
                    state
                } else {
                    state.copy(usedMemoryMb = effectiveMemory ?: 0L, cpuPercent = cpu)
                }
            }
            return true
        } catch (e: Exception) {
            // Keep the last known snapshot when Android or PRoot denies a probe.
            return false
        } finally {
            resourceCollectionMutex.unlock()
        }
    }

}
