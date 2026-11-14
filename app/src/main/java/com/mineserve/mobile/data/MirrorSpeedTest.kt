package com.mineserve.mobile.data

import com.mineserve.mobile.runtime.BootstrapInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 镜像测速：「小闪电」弹窗背后的唯一实现。
 *
 * 主界面安装卡片和设置页「下载源设置」共用同一份结果与同一个弹窗 —— 两处各写一套
 * 探测逻辑的话，同一台手机上会出现两个不同的「最快镜像」，用户无从判断信哪个。
 *
 * ## 为什么测「首字节延迟」而不是下载速度
 *
 * 探测只发一个 `Range: bytes=0-1`（GitHub 包）或取一个几十 KB 的 `Release`（APT）。
 * 用户真正关心的排序依据是「哪个源点下去有反应」，而速度会随文件大小、分片策略、
 * 运营商出口变化，同一个源在两分钟内能给出完全不同的数字。延迟稳定得多，也快得多：
 * 12 个源并发 3 轮就测完，不会让用户对着弹窗等一分钟。
 */
object MirrorSpeedTest {

    /** 探测分组：决定展示顺序，也决定选中后写回哪个配置字段。 */
    enum class Group { GitHub, Apt }

    /** 一个待探测的目标。[key] 是枚举名 —— 界面靠它把结果对回配置项。 */
    data class Target(
        val group: Group,
        val key: String,
        val label: String,
        val url: String,
        val rangeProbe: Boolean
    )

    /** 单个目标的测速结果。[latencyMs] 为 null 表示不可用，原因见 [error]。 */
    data class Result(
        val group: Group,
        val key: String,
        val label: String,
        val latencyMs: Long?,
        val error: String? = null
    ) {
        val ok: Boolean get() = latencyMs != null
    }

    /** 单源超时。超过这个数就不值得再等了 —— 下载本身的单源预算也是 8 秒级。 */
    private const val TIMEOUT_MS = 8_000

    /** 同时探测的目标数。太高会让本就拥塞的移动网络把每个连接都拖到超时。 */
    private const val MAX_CONCURRENT = 6

    private const val USER_AGENT = "Minexa/1.0 (Android)"

    /** GitHub 加速镜像（不含已失效项，也不含 Auto）。 */
    fun githubTargets(): List<Target> = DownloadMirror.active.map { mirror ->
        Target(
            group = Group.GitHub,
            key = mirror.name,
            label = mirror.displayName,
            url = BootstrapInstaller.probeUrl(mirror),
            rangeProbe = true
        )
    }

    /** Termux APT 软件源（取 dists/stable/Release，几十 KB，能同时代表连通性与吞吐）。 */
    fun aptTargets(): List<Target> = AptMirror.active.map { mirror ->
        Target(
            group = Group.Apt,
            key = mirror.name,
            label = mirror.displayName,
            url = "${mirror.url}/dists/stable/Release",
            rangeProbe = false
        )
    }

    fun allTargets(): List<Target> = githubTargets() + aptTargets()

    /**
     * 并发测速，每测完一批就回调一次（[onResult] 在调用方的线程上执行）。
     *
     * 分批而不是一次性全发：12 条并发 TLS 在弱网下会互相拖慢，测出来的延迟全都虚高。
     */
    suspend fun run(onResult: (Result) -> Unit) = withContext(Dispatchers.IO) {
        allTargets().chunked(MAX_CONCURRENT).forEach { chunk ->
            val done = coroutineScope {
                chunk.map { target -> async { probe(target) } }.awaitAll()
            }
            withContext(Dispatchers.Main) { done.forEach(onResult) }
        }
    }

    /** 探测单个目标，返回毫秒延迟；失败时延迟为 null。 */
    private fun probe(target: Target): Result {
        val startedAt = System.nanoTime()
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(target.url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                // 别让服务器压缩：透明解压会把首字节延迟算成「解压时间」，
                // 而且 Range 请求一旦被 gzip 包一层，Content-Length 就对不上了。
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", USER_AGENT)
                if (target.rangeProbe) setRequestProperty("Range", "bytes=0-1")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Result(target.group, target.key, target.label, null, "HTTP $code")
            } else {
                // 真的读一点：只拿到响应头就断开的话，代理层可能还没开始回源，
                // 「网关在线但上游不通」的源会被误判为可用。
                conn.inputStream.use { it.read(ByteArray(1)) }
                val ms = (System.nanoTime() - startedAt) / 1_000_000
                Result(target.group, target.key, target.label, ms)
            }
        } catch (e: Throwable) {
            Result(target.group, target.key, target.label, null, e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
