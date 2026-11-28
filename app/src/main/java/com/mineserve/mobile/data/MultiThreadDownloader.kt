package com.mineserve.mobile.data

// 性能修改理由：移动网络断流时自动重试，并在分片下载失败后回退单流，避免误判整源不可用。
import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

internal data class DownloadSpeedSample(val bytes: Long, val timestampMs: Long)

/** EdgeCube-style recent-window rate; avoids startup spikes and long-term drag. */
internal fun speedBytesPerSecond(samples: MutableList<DownloadSpeedSample>, nowMs: Long): Long {
    val cutoff = nowMs - 3_000L
    samples.removeAll { it.timestampMs < cutoff }
    if (samples.size < 2) return 0L
    val first = samples.first()
    val last = samples.last()
    val elapsed = last.timestampMs - first.timestampMs
    val bytes = last.bytes - first.bytes
    return if (elapsed > 0L && bytes > 0L) bytes * 1_000L / elapsed else 0L
}

/**
 * 内置多线程下载模块（基于 HTTP Range 分段并行，开源思路：分块下载 + 合并）。
 *
 * 特性：
 *  - 探测 Content-Length 后按线程数切分 [start,end) 区间，每线程一个 Range 请求并行下载到 .part 文件
 *  - 全部片段就绪后顺序合并为最终文件，删除临时片段
 *  - 服务端不支持 Range / 未知大小 / 小文件时自动降级为单流下载
 *  - 明文 http 地址走裸 socket 单流（Android 的明文策略会拒掉 HttpURLConnection，
 *    见 [CleartextHttp] 的说明）；被重定向到 https 时自动改回常规通道
 *  - 统一的进度回调 (已下载, 总字节, 速度 bytes/s) 与日志回调，与各业务层签名一致
 *
 * 调用方通过 [DownloadPrefs] 控制开关与线程数；也可显式传参覆盖。
 */
object MultiThreadDownloader {

    private const val TAG = "MultiThreadDownloader"

    /** 小于该尺寸不启用多线程（收益低），走单流 */
    private const val MIN_MULTI_BYTES = 1 * 1024 * 1024L

    /** 单流下载时的缓冲区 */
    private const val BUFFER = 64 * 1024

    /** 每个分片的最小尺寸，避免分片过多 */
    private const val MIN_CHUNK_BYTES = 256 * 1024L

    /** 所有请求共用的 UA，与明文通道（[CleartextHttp]）保持一致 */
    private const val USER_AGENT = CleartextHttp.DEFAULT_USER_AGENT

    /** A caller-owned download session that can stop every active HTTP stream. */
    class DownloadSession {
        private val cancelled = AtomicBoolean(false)
        private val connections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
        /** 明文通道的裸 socket 连接：没有 disconnect()，只能 close */
        private val streams = ConcurrentHashMap.newKeySet<Closeable>()
        @Volatile private var executor: ExecutorService? = null

        fun cancel() {
            cancelled.set(true)
            connections.forEach { runCatching { it.disconnect() } }
            streams.forEach { runCatching { it.close() } }
            executor?.shutdownNow()
        }

        internal fun isCancelled() = cancelled.get()
        internal fun register(connection: HttpURLConnection) { connections += connection }
        internal fun unregister(connection: HttpURLConnection) { connections -= connection }
        internal fun registerStream(stream: Closeable) { streams += stream }
        internal fun unregisterStream(stream: Closeable) { streams -= stream }
        internal fun setExecutor(value: ExecutorService?) { executor = value }
    }

    /**
     * 下载 url 到 target。自动读取 [DownloadPrefs] 开关与线程数。
     *
     * @param onProgress (已下载, 总字节, 速度 bytes/s)；总字节未知为 -1
     * @param onLog 可选日志回调（失败/降级提示等）
     */
    suspend fun download(
        url: String,
        target: File,
        onProgress: (Long, Long, Long) -> Unit = { _, _, _ -> },
        onLog: ((String) -> Unit)? = null,
        session: DownloadSession? = null
    ) {
        val enabled = DownloadPrefs.isEnabled()
        val threads = DownloadPrefs.threadCount()
        download(url, target, threads, enabled, onProgress, onLog, session)
    }

    /**
     * 下载 url 到 target，显式指定线程数与开关（覆盖 [DownloadPrefs]）。
     */
    suspend fun download(
        url: String,
        target: File,
        threads: Int,
        enabled: Boolean,
        onProgress: (Long, Long, Long) -> Unit = { _, _, _ -> },
        onLog: ((String) -> Unit)? = null,
        session: DownloadSession? = null
    ) {
        checkCancelled(session)
        // 明文 http 走裸 socket 单流：Android 的明文策略会让 HttpURLConnection 直接失败
        // （连 127.0.0.1 都拒），而这类地址基本都是内网/环回的文件，来源是本机或局域网里
        // 临时起的 http 服务，Range 支持也不可靠，不值得为它再实现一套分片逻辑。
        if (CleartextHttp.isCleartext(url)) {
            onLog?.invoke("明文 http 地址，使用单流下载（不经系统的明文策略）")
            val redirected = downloadCleartext(url, target, onProgress, onLog, session)
            if (redirected != null) {
                // 明文地址 301/302 升级到 https：改走常规通道（可能是多线程，也可能是另一个明文地址）
                onLog?.invoke("地址重定向到 $redirected，改用常规通道")
                download(redirected, target, threads, enabled, onProgress, onLog, session)
            }
            return
        }
        val threadCount = threads.coerceAtLeast(1)
        val (total, entity) = probeLengthAndTag(url, session)
        if (!enabled || threadCount <= 1 || total <= 0 || total < MIN_MULTI_BYTES) {
            if (!enabled) onLog?.invoke("多线程下载未启用，使用单流下载")
            if (total in 1 until MIN_MULTI_BYTES) onLog?.invoke("文件较小，使用单流下载")
            singleStreamWithRetry(url, target, total, onProgress, onLog, session)
            return
        }

        // 计算分片数量（受最小分片尺寸约束）
        val chunkCount = minOf(threadCount, ((total + MIN_CHUNK_BYTES - 1) / MIN_CHUNK_BYTES).toInt())
        val ranges = buildRanges(total, chunkCount)
        target.parentFile?.mkdirs()
        onLog?.invoke("多线程下载：$chunkCount 线程，总大小 ${total / 1024 / 1024}MB")

        // ── 断点续传 ───────────────────────────────────────────────
        // 只有在能证明「上次下的是同一个实体」时才复用已下载的分片：分片里是上次
        // 落盘的字节，本次无法再校验其内容；如果 CDN 中途换过文件，把新旧两版拼在
        // 一起会得到「长度正确、内容错乱」的坏包 —— 比从头下载危险得多。
        // 判据用 ETag（拿不到 ETag 的源一律不续传，宁可重下）。
        val resumeMeta = readPartMeta(target)
        val canResume = canResumeFrom(resumeMeta?.total, resumeMeta?.etag, total, entity.etag)
        val resumeBytes = if (canResume) {
            ranges.sumOf { (start, end) ->
                partFile(target, start).length().coerceIn(0L, end - start + 1)
            }
        } else 0L
        if (canResume && resumeBytes > 0) {
            onLog?.invoke("续传：复用已下载的 ${resumeBytes / 1024 / 1024}MB 分片")
        } else {
            clearParts(target, ranges)
            writePartMeta(target, PartMeta(total, entity.etag, entity.lastModified))
        }

        val pool: ExecutorService = Executors.newFixedThreadPool(chunkCount)
        session?.setExecutor(pool)
        val done = AtomicLong(resumeBytes)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Exception>()
        val speedSamples = mutableListOf<DownloadSpeedSample>()
        var merged = false

        try {
            ranges.forEach { range ->
                pool.execute {
                    try {
                        val resumeFrom = if (canResume) {
                            partFile(target, range.first).length()
                                .coerceIn(0L, range.second - range.first + 1)
                        } else 0L
                        downloadRange(
                            url, target, total, range.first, range.second,
                            onProgress, done, session, entity, resumeFrom
                        )
                    } catch (e: Exception) {
                        errors.add(e)
                        Log.w(TAG, "range ${range.first}-${range.second} failed: ${e.message}")
                    }
                }
            }
            // 等待所有分片完成
            pool.shutdown()
            while (!pool.awaitTermination(200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                checkCancelled(session)
                reportSpeed(done.get(), total, speedSamples, onProgress)
            }
            checkCancelled(session)
            if (!errors.isEmpty()) {
                val mismatch = errors.firstOrNull { it is RangeMismatchException }
                // 已经有进展、且不是「协议层面不一致」→ 分片本身是好的，保留下来给
                // 下一次重试续传；回退单流会把已下的几 MB 全部作废。
                if (mismatch == null && done.get() > 0) {
                    onLog?.invoke("分片下载中断，已保留 ${done.get() / 1024 / 1024}MB，重试时继续")
                    throw RuntimeException(
                        "多线程下载中断：${errors.peek()?.message ?: "未知错误"}"
                    )
                }
                // 协议不一致或毫无进展：分片不可信，回退单流全量重下。
                val reason = mismatch?.message ?: errors.peek()?.message ?: "未知错误"
                onLog?.invoke("多线程下载中断，改用单流下载：$reason")
                target.delete()
                clearParts(target, ranges)
                singleStreamWithRetry(url, target, total, onProgress, onLog, session)
                return
            }

            // 合并分片为最终文件
            mergeRanges(target, ranges)
            if (target.length() != total) {
                throw RuntimeException("多线程下载文件不完整: ${target.length()}/$total 字节")
            }
            merged = true
            onProgress(target.length(), total, 0L)
        } finally {
            pool.shutdownNow()
            session?.setExecutor(null)
            // 成功才清分片；失败时保留，下一次同一个源会接着下。
            if (merged) clearParts(target, ranges)
        }
    }

    // ── 断点续传的落盘状态 ────────────────────────────────────────

    /**
     * 上次分片下载的实体指纹。
     *
     * 只有 [total] 与 [etag] 都对得上才允许续传 —— 这条规则宁可让续传失效，
     * 也不允许把两次不同 revision 的字节拼成一个文件。
     */
    private data class PartMeta(val total: Long, val etag: String?, val lastModified: String?)

    /**
     * 是否允许复用上次落盘的分片。
     *
     * 判据刻意保守：**当前响应没有 ETag 就一律不续传**。分片里是上次的字节，
     * 本次无法校验其内容；一旦 CDN 中途换过文件，拼接会得到「长度正确、内容错乱」
     * 的坏包 —— 这比多下一次更危险。长度相同只是必要条件，不是充分条件。
     */
    internal fun canResumeFrom(
        previousTotal: Long?,
        previousEtag: String?,
        total: Long,
        currentEtag: String?
    ): Boolean {
        if (previousTotal == null || previousEtag == null || currentEtag == null) return false
        return previousTotal == total && previousEtag == currentEtag
    }

    private fun metaFile(target: File): File = File(target.parentFile, "${target.name}.partmeta")

    private fun readPartMeta(target: File): PartMeta? = runCatching {
        val f = metaFile(target)
        if (!f.isFile) return null
        val lines = f.readLines()
        if (lines.size < 3) return null
        val total = lines[0].trim().toLongOrNull() ?: return null
        PartMeta(total, lines[1].takeIf { it.isNotBlank() }, lines[2].takeIf { it.isNotBlank() })
    }.getOrNull()

    private fun writePartMeta(target: File, meta: PartMeta) {
        runCatching {
            metaFile(target).writeText(
                "${meta.total}\n${meta.etag.orEmpty()}\n${meta.lastModified.orEmpty()}\n"
            )
        }
    }

    private fun clearParts(target: File, ranges: List<Pair<Long, Long>>) {
        ranges.forEach { (start, _) -> runCatching { partFile(target, start).delete() } }
        runCatching { metaFile(target).delete() }
    }

    /**
     * 丢弃某个目标的所有续传状态（分片 + 指纹）。
     *
     * 调用方在「校验失败」时必须用它：分片已经完整合并过一次却校验不过，
     * 说明这份内容本身就是坏的；留着它下一次会续传出同一个坏文件。
     */
    fun clearResumeState(target: File) {
        val dir = target.parentFile ?: return
        runCatching {
            dir.listFiles { f ->
                f.name.startsWith("${target.name}.part") || f.name == "${target.name}.partmeta"
            }?.forEach { runCatching { it.delete() } }
        }
    }

    // ── 分片辅助 ──────────────────────────────────────────────

    internal fun buildRanges(total: Long, count: Int): List<Pair<Long, Long>> {
        val chunk = total / count
        val ranges = mutableListOf<Pair<Long, Long>>()
        var start = 0L
        for (i in 0 until count) {
            val end = if (i == count - 1) total - 1 else start + chunk - 1
            ranges.add(start to end)
            start = end + 1
        }
        return ranges
    }

    private fun partFile(target: File, start: Long): File =
        File(target.parentFile, "${target.name}.part$start")

    /**
     * 下载内容的「实体标识」（ETag / Last-Modified）。
     *
     * 为什么必须校验它：分片是**各自独立的 HTTP 请求**，如果镜像在探测和取分片之间
     * 回源换了一次文件（发版、CDN 刷新），各分片就会来自不同 revision。
     * 只比 Content-Length / Content-Range 完全看不出来——长度一样、偏移也都对，
     * 合并出来却是一个「长度正确、内容错乱」的坏文件（JAR 校验失败甚至静默损坏）。
     * 有了实体标识，任何一片与探测结果不一致就整体回退单流重下。
     */
    internal data class EntityTag(val etag: String?, val lastModified: String?) {
        val isEmpty: Boolean get() = etag == null && lastModified == null

        /** 与另一份响应头是否指向同一实体。两者都取不到时视为「无法判断」，返回 true。 */
        fun matches(other: EntityTag): Boolean {
            if (isEmpty || other.isEmpty) return true
            if (etag != null && other.etag != null) return etag == other.etag
            if (lastModified != null && other.lastModified != null) return lastModified == other.lastModified
            return true
        }
    }

    private fun entityTagOf(conn: HttpURLConnection): EntityTag =
        EntityTag(conn.getHeaderField("ETag"), conn.getHeaderField("Last-Modified"))

    /**
     * 探测 Content-Length（Range: bytes=0-0 + Content-Range 响应头），
     * 同时给出实体标识用于后续分片校验。
     */
    private fun probeLengthAndTag(urlStr: String, session: DownloadSession?): Pair<Long, EntityTag> {
        var conn: HttpURLConnection? = null
        return try {
            conn = openConn(urlStr).apply {
                setRequestProperty("Range", "bytes=0-0")
            }
            session?.register(conn)
            checkCancelled(session)
            val code = conn.responseCode
            if (code == 206) {
                // Content-Range: bytes 0-0/12345
                val cr = conn.getHeaderField("Content-Range") ?: return -1L to EntityTag(null, null)
                val slash = cr.lastIndexOf('/')
                if (slash < 0) return -1L to EntityTag(null, null)
                val len = cr.substring(slash + 1).trim().toLongOrNull() ?: -1L
                len to entityTagOf(conn)
            } else if (code in 200..299) {
                // A 200 response ignored the Range header; use one stream so a mirror
                // cannot be mistaken for a range-capable endpoint and corrupt the JAR.
                -1L to EntityTag(null, null)
            } else {
                -1L to EntityTag(null, null)
            }
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "probeLength failed: ${e.message}")
            -1L to EntityTag(null, null)
        } finally {
            conn?.let { session?.unregister(it) }
            conn?.disconnect()
        }
    }

    /**
     * 分片响应与探测结果不一致（偏移、实体、长度）。
     *
     * 单独一个类型是因为它决定了「能不能续传」：这类不一致说明分片本身不可信，
     * 必须清掉重下；而普通的网络中断只说明这次没下完，分片还是好的。
     */
    private class RangeMismatchException(message: String) : RuntimeException(message)

    /** 下载单个分片 [start,end]（含端点），从 [resumeFrom] 字节处续写 .part 文件 */
    private fun downloadRange(
        urlStr: String,
        target: File,
        total: Long,
        start: Long,
        end: Long,
        onProgress: (Long, Long, Long) -> Unit,
        done: AtomicLong,
        session: DownloadSession?,
        expectedEntity: EntityTag = EntityTag(null, null),
        resumeFrom: Long = 0L
    ) {
        val expectedLength = end - start + 1
        val have = resumeFrom.coerceIn(0L, expectedLength)
        // 这一片上次已经下完了，不需要再发请求（done 里已经算过它）。
        if (have >= expectedLength) return
        val reqStart = start + have

        var conn: HttpURLConnection? = null
        try {
            conn = openConn(urlStr).apply {
                setRequestProperty("Range", "bytes=$reqStart-$end")
                // If-Range：让服务器在实体已变更时**返回 200 全量**而不是 206 分片，
                // 这样我们能立刻察觉并整体回退，而不是把两个 revision 拼在一起。
                // ETag 优先（更精确）；只有 Last-Modified 时用它。
                expectedEntity.etag?.let { setRequestProperty("If-Range", it) }
                    ?: expectedEntity.lastModified?.let { setRequestProperty("If-Range", it) }
            }
            session?.register(conn)
            checkCancelled(session)
            val code = conn.responseCode
            if (code != 206) {
                throw RangeMismatchException("Range 请求返回 HTTP $code")
            }
            // 实体一致性：探测时拿到的 ETag/Last-Modified 必须与本片响应一致，
            // 否则说明镜像中途换了文件，各分片可能来自不同 revision。
            val actualEntity = entityTagOf(conn)
            if (!expectedEntity.matches(actualEntity)) {
                throw RangeMismatchException(
                    "分片内容实体已变更: 期望 ETag=${expectedEntity.etag} Last-Modified=${expectedEntity.lastModified}，" +
                        "实际 ETag=${actualEntity.etag} Last-Modified=${actualEntity.lastModified}"
                )
            }
            // 仅校验长度不够：有缺陷的镜像/缓存会对任何 Range 都返回长度相同的 206，
            // 但偏移不同，合并后就是「长度正确、内容错乱」的坏文件。这里逐片比对
            // Content-Range 的 start/end/total；缺失或不匹配一律抛异常，交给上层
            // 决定是回退单流还是保留分片等下次续传。
            val contentRange = conn.getHeaderField("Content-Range")
            val parsed = parseContentRange(contentRange)
                ?: throw RangeMismatchException("分片响应缺少可解析的 Content-Range 头: $contentRange")
            if (parsed.first != reqStart || parsed.second != end || parsed.third != total) {
                throw RangeMismatchException(
                    "分片偏移不匹配: 期望 bytes $reqStart-$end/$total，实际 $contentRange"
                )
            }
            val part = partFile(target, start)
            part.parentFile?.mkdirs()
            val output = RandomAccessFile(part, "rw")
            try {
                // 截到「上次确认写好的长度」再续写：进程被杀时最后几 KB 可能只写了
                // 一半，FileOutputStream 的 length 会大于真实内容，直接 append 会错位。
                // 截断到 have 是安全的 —— have 来自 meta 校验通过后的文件长度。
                output.setLength(have)
                output.seek(have)
                val buf = ByteArray(BUFFER)
                conn.inputStream.use { input ->
                    while (true) {
                        checkCancelled(session)
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                        done.addAndGet(read.toLong())
                    }
                }
            } finally {
                output.close()
            }
            if (part.length() != expectedLength) {
                throw RangeMismatchException("分片大小不完整: ${part.length()}/$expectedLength 字节")
            }
        } finally {
            conn?.let { session?.unregister(it) }
            conn?.disconnect()
        }
    }

    /**
     * 解析 `Content-Range: bytes <start>-<end>/<total>`，返回 (start, end, total)。
     * 格式不符（含 total 为 `*`）时返回 null，由调用方按不可信处理并回退单流。
     */
    internal fun parseContentRange(header: String?): Triple<Long, Long, Long>? {
        if (header == null) return null
        val idx = header.indexOf("bytes")
        if (idx < 0) return null
        val value = header.substring(idx + 5).trim()
        val slash = value.indexOf('/')
        if (slash < 0) return null
        val rangePart = value.substring(0, slash).trim()
        val dash = rangePart.indexOf('-')
        if (dash < 0) return null
        val rangeStart = rangePart.substring(0, dash).trim().toLongOrNull() ?: return null
        val rangeEnd = rangePart.substring(dash + 1).trim().toLongOrNull() ?: return null
        val rangeTotal = value.substring(slash + 1).trim().toLongOrNull() ?: return null
        return Triple(rangeStart, rangeEnd, rangeTotal)
    }

    /**
     * 顺序合并分片到目标文件。
     *
     * 先合并到 `xxx.merge` 再 rename 到 [target]：直接对 target 写的话，任何一步失败
     * （分片缺失 / 长度不符 / 进程被杀）都会在 target 上留下「长度正确但内容错乱」
     * 或半截的文件，把调用方（如 `PluginManager.downloadTo`）原来可用的旧版本覆盖掉
     * —— 而失败路径只删 `.part`、不删 target。
     */
    private fun mergeRanges(target: File, ranges: List<Pair<Long, Long>>) {
        val merged = File(target.parentFile, "${target.name}.merge")
        try {
            FileOutputStream(merged).use { out ->
                for ((start, end) in ranges) {
                    val part = partFile(target, start)
                    if (!part.exists()) {
                        throw RuntimeException("分片缺失: ${part.name}")
                    }
                    // 分片自身长度也要对：写盘途中异常退出可能留下短分片，
                    // 只靠合并后的总长度检查会漏掉「某片短、另一片长」相互抵消的情况。
                    val expectedLen = end - start + 1
                    if (part.length() != expectedLen) {
                        throw RuntimeException("分片长度不符: ${part.name} ${part.length()}/$expectedLen 字节")
                    }
                    part.inputStream().use { input ->
                        val buf = ByteArray(BUFFER)
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            out.write(buf, 0, read)
                        }
                    }
                }
            }
            // 某些 FUSE 实现的 rename 不能覆盖已存在文件，先删再试一次
            if (!merged.renameTo(target)) {
                target.delete()
                if (!merged.renameTo(target)) {
                    throw RuntimeException("合并结果无法落盘: ${merged.name}")
                }
            }
        } finally {
            if (merged.exists()) merged.delete()
        }
    }

    /** 每 500ms 报告一次总进度与速度（total 用真实长度，避免进度无法计算） */
    private fun reportSpeed(downloaded: Long, total: Long, samples: MutableList<DownloadSpeedSample>, onProgress: (Long, Long, Long) -> Unit) {
        val now = System.currentTimeMillis()
        samples += DownloadSpeedSample(downloaded, now)
        val speed = speedBytesPerSecond(samples, now)
        onProgress(downloaded, total, speed)
    }

    /** 单流下载（Range 不支持 / 未启用 / 小文件时的降级路径） */
    private fun singleStream(urlStr: String, target: File, total: Long, onProgress: (Long, Long, Long) -> Unit, session: DownloadSession?) {
        var conn: HttpURLConnection? = null
        try {
            conn = openConn(urlStr)
            session?.register(conn)
            checkCancelled(session)
            val code = conn.responseCode
            if (code !in 200..299) {
                throw RuntimeException("HTTP $code 下载失败: $urlStr")
            }
            val actualTotal = if (total > 0) total else conn.contentLengthLong
            target.parentFile?.mkdirs()
            var downloaded = 0L
            val speedSamples = mutableListOf<DownloadSpeedSample>()
            conn.inputStream.use { input ->
                FileOutputStream(target).use { fos ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        checkCancelled(session)
                        val read = input.read(buffer)
                        if (read <= 0) break
                        fos.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (speedSamples.isEmpty() || now - speedSamples.last().timestampMs >= 500) {
                            speedSamples += DownloadSpeedSample(downloaded, now)
                            val speed = speedBytesPerSecond(speedSamples, now)
                            onProgress(downloaded, actualTotal, speed)
                        }
                    }
                }
            }
            onProgress(downloaded, actualTotal, 0L)
            // 单流也必须校验长度：连接被中途掐断时 read() 返回 -1 就正常退出了循环，
            // 于是「下载完成」的文件其实是截断的（旧实现只在多线程合并后查长度）。
            if (actualTotal > 0 && downloaded != actualTotal) {
                throw RuntimeException("下载不完整: $downloaded/$actualTotal 字节（连接可能被中断）")
            }
        } finally {
            conn?.let { session?.unregister(it) }
            conn?.disconnect()
        }
    }

    /**
     * 明文 http 的单流下载（[CleartextHttp] 裸 socket，带重试）。
     *
     * @return null 表示已下载完成；非 null 表示重定向到了非明文地址，
     *         调用方应改用该地址走常规通道重下。
     */
    private fun downloadCleartext(
        urlStr: String,
        target: File,
        onProgress: (Long, Long, Long) -> Unit,
        onLog: ((String) -> Unit)?,
        session: DownloadSession?
    ): String? {
        var lastError: Exception? = null
        repeat(SINGLE_STREAM_RETRIES) { attempt ->
            var exchange: CleartextHttp.Exchange? = null
            try {
                checkCancelled(session)
                if (attempt > 0) onLog?.invoke("单流下载重试 ${attempt + 1}/$SINGLE_STREAM_RETRIES")
                target.delete()
                val conn = CleartextHttp.get(urlStr, READ_TIMEOUT_MS, USER_AGENT)
                exchange = conn
                session?.registerStream(conn)
                checkCancelled(session)
                if (conn.code !in 200..299) {
                    throw RuntimeException("HTTP ${conn.code} 下载失败: $urlStr")
                }
                val total = conn.contentLength
                target.parentFile?.mkdirs()
                var downloaded = 0L
                val speedSamples = mutableListOf<DownloadSpeedSample>()
                conn.body.use { input ->
                    FileOutputStream(target).use { fos ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            checkCancelled(session)
                            val read = input.read(buffer)
                            if (read <= 0) break
                            fos.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (speedSamples.isEmpty() || now - speedSamples.last().timestampMs >= 500) {
                                speedSamples += DownloadSpeedSample(downloaded, now)
                                onProgress(downloaded, total, speedBytesPerSecond(speedSamples, now))
                            }
                        }
                    }
                }
                onProgress(downloaded, total, 0L)
                if (total > 0 && target.length() != total) {
                    throw RuntimeException("文件大小不完整: ${target.length()}/$total 字节")
                }
                // total < 0 表示长度未知（chunked 已由 ChunkedInputStream 保证完整；
                // 但「既无 Content-Length 也无 chunked、靠 Connection: close 定界」的响应
                // 没有任何完整性判据——正常结束与服务端提前发 FIN 在读循环里都是 read()<=0）。
                // 曾经这里直接 return null，于是半截 jar/zip 被当成下载成功，
                // 下游只判「文件够大」就会继续解压安装。长度未知时必须另找个判据。
                verifyUnknownLength(target, downloaded, urlStr)
                return null
            } catch (e: CleartextHttp.RedirectNotCleartext) {
                target.delete()
                return e.target
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "cleartext attempt ${attempt + 1} failed: ${e.message}")
            } finally {
                exchange?.let { session?.unregisterStream(it) }
                runCatching { exchange?.close() }
            }
        }
        // 最后一次尝试失败也会留下半截文件（每次尝试开头才删），会污染下一次判据。
        target.delete()
        throw RuntimeException("明文 http 下载失败: ${lastError?.message ?: "未知错误"}", lastError)
    }

    /** 文件名看起来是 zip 系产物（jar / zip / apk / whl 都是同一个容器格式）。 */
    internal fun isZipLike(name: String): Boolean {
        val lower = name.lowercase(java.util.Locale.US)
        return lower.endsWith(".zip") || lower.endsWith(".jar") || lower.endsWith(".apk") ||
            lower.endsWith(".whl") || lower.endsWith(".aar")
    }

    /**
     * 长度未知时的完整性判据。
     *
     * 硬规矩：把外部产物落盘的操作，成功判据至少得是「长度 / 摘要 / 能打开」之一，
     * 不能是「读循环跑完了」。这里只能做到前一半：
     *  - 一字节都没收到必然是失败；
     *  - zip 系（jar/apk/mod）能用 [java.util.zip.ZipFile] 打开，说明尾部中央目录完整，
     *    也就排除了最常见的「传到一半连接被关」。
     * 其余格式（tar.gz 等）确实无从判断，保持放行——那总比让 ZipFile 假装成功安全。
     */
    internal fun verifyUnknownLength(target: File, downloaded: Long, urlStr: String) {
        if (downloaded <= 0L || !target.isFile || target.length() == 0L) {
            throw RuntimeException("下载为空，连接可能已断开: $urlStr")
        }
        if (!isZipLike(target.name)) return
        try {
            java.util.zip.ZipFile(target).close()
        } catch (e: Exception) {
            target.delete()
            throw RuntimeException(
                "下载不完整（${target.name} 无法作为压缩包打开，多半是传输中途断开）: $urlStr", e
            )
        }
    }

    private fun singleStreamWithRetry(
        urlStr: String,
        target: File,
        total: Long,
        onProgress: (Long, Long, Long) -> Unit,
        onLog: ((String) -> Unit)?,
        session: DownloadSession?
    ) {
        var lastError: Exception? = null
        // 走单流说明分片这条路已经不可用（不支持 Range / 协议不一致 / 无进展）。
        // 残留的分片与续传指纹必须清掉，否则下一次调用会误以为「有可续传的进度」，
        // 拿一批永远不会被用到的旧字节去拼文件。
        clearResumeState(target)
        repeat(SINGLE_STREAM_RETRIES) { attempt ->
            try {
                if (attempt > 0) onLog?.invoke("单流下载重试 ${attempt + 1}/$SINGLE_STREAM_RETRIES")
                target.delete()
                checkCancelled(session)
                singleStream(urlStr, target, total, onProgress, session)
                if (total > 0L) {
                    if (target.length() != total) throw RuntimeException("文件大小不完整: ${target.length()}/$total 字节")
                } else {
                    // 同明文通道：长度未知时不能算成功（服务端提前关连接在读循环里看不出来）
                    verifyUnknownLength(target, target.length(), urlStr)
                }
                return
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "single stream attempt ${attempt + 1} failed: ${e.message}")
            }
        }
        // 最后一次尝试失败留下的半截文件会让下一次调用误判「已下载」
        target.delete()
        throw RuntimeException("单流下载失败: ${lastError?.message ?: "未知错误"}", lastError)
    }

    private fun checkCancelled(session: DownloadSession?) {
        if (session?.isCancelled() == true) throw java.util.concurrent.CancellationException("Download cancelled")
    }

    private fun openConn(urlStr: String): HttpURLConnection {
        return (URL(urlStr).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            // 移动网络可能短暂无数据，避免正常的 CDN 停顿被误判为断流。
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            // 必须显式关掉压缩协商。
            //
            // Android 的 HttpURLConnection（OkHttp）默认自动带上 `Accept-Encoding: gzip`
            // 并**透明解压**响应体，但响应头里的长度是**压缩后**的：实测同一个
            // `Packages` 文件，`Content-Range` 不带 gzip 是 `bytes 0-0/22530`，
            // 带 gzip 变成 `bytes 0-0/3206`。于是「探到的总长 3206、实际读出 22530」
            // 会被完整性校验判成「下载不完整」，每个源都白下 10MB 再丢弃重试 ——
            // 现象就是「一直换源、进度来回跳、永远装不完」。
            // 关掉协商后长度与实际字节数一致，校验才有意义。
            setRequestProperty("Accept-Encoding", "identity")
        }
    }

    private const val SINGLE_STREAM_RETRIES = 2
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
}
