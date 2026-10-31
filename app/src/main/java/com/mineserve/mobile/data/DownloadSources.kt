package com.mineserve.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.SocketTimeoutException
import java.security.MessageDigest

/**
 * 一个下载源：给人看的名字 + 可直接 GET 的地址。
 *
 * 名字要能独立定位到「哪台服务器」，因为界面上要告诉用户
 * 「清华镜像失败，正在改用中科大镜像」——只写 URL 用户读不出区别。
 */
data class DownloadSource(val label: String, val url: String)

/**
 * 多源下载：按顺序尝试，任一源失败自动换下一个。
 *
 * ## 为什么要单独一层
 *
 * 项目里原本有五种「多源」写法（bootstrap 的 `DownloadMirror`、rootfs 的两条 URL 循环、
 * frpc 的 GitHub→Gitee、EasyTier 的镜像回退、MCP 的单流重试），每种都有各自的
 * 失败处理，且**都不校验内容**：拿到一个 200 的错误页或半截文件就当成功。
 * BDS 运行环境要下两样东西（Ubuntu rootfs、box64 的 .deb），其中 .deb 只有
 * 第三方源，网络环境差异极大，因此统一到这里：
 *
 *  - 每个源都带 [validate] 钩子，内容不合法（HTML 错误页、被截断、哈希不符）
 *    同样算这个源失败，继续换下一个；
 *  - 每次失败都记录「源 + 原因」，全部失败时整体抛出，界面能如实列出试过哪些源；
 *  - 下载一律先写临时文件，校验通过才 rename 到目标位置 —— 半截文件永远不会
 *    出现在目标路径上，用户重试时也不会读到上一次的残骸。
 *
 * 新增来源只需要往调用方的列表里加一条 [DownloadSource]，不动这里。
 */
object MultiSourceDownload {

    private const val TAG = "MultiSourceDownload"

    /** 单次尝试的结果，用于失败时向用户交代 */
    data class Attempt(val label: String, val ok: Boolean, val reason: String? = null)

    /**
     * 所有来源都失败。
     *
     * [attempts] 里保留每个源的失败原因 —— 界面直接展示，用户能一眼看出
     * 是「全都没网」还是「某个源返回了坏文件」。
     */
    class AllSourcesFailed(
        val attempts: List<Attempt>,
        cause: Throwable? = null
    ) : RuntimeException(
        attempts.joinToString("；") { "${it.label}: ${it.reason ?: "失败"}" },
        cause
    )

    /**
     * 取文本内容（如 apt 的 `Packages` 索引、`SHA256SUMS`）。
     *
     * @param validate 内容校验；返回 null 表示通过，否则返回失败原因
     * @return 内容与命中的来源
     */
    suspend fun fetchText(
        sources: List<DownloadSource>,
        tempDir: File,
        onAttempt: (Attempt) -> Unit = {},
        perSourceTimeoutMs: Long = TEXT_TIMEOUT_MS,
        validate: (String) -> String? = { null }
    ): Pair<String, DownloadSource> = withContext(Dispatchers.IO) {
        val attempts = mutableListOf<Attempt>()
        var lastError: Throwable? = null
        for (source in sources) {
            val temp = File(tempDir, "probe-${System.nanoTime()}.tmp")
            try {
                downloadRaw(source.url, temp, timeoutMs = perSourceTimeoutMs)
                val text = temp.readText()
                val invalid = if (text.isBlank()) "返回内容为空" else validate(text)
                if (invalid != null) {
                    val attempt = Attempt(source.label, false, invalid)
                    attempts += attempt
                    onAttempt(attempt)
                    continue
                }
                val ok = Attempt(source.label, true)
                attempts += ok
                onAttempt(ok)
                return@withContext text to source
            } catch (e: Throwable) {
                lastError = e
                val attempt = Attempt(source.label, false, e.message ?: e.javaClass.simpleName)
                attempts += attempt
                onAttempt(attempt)
            } finally {
                temp.delete()
            }
        }
        throw AllSourcesFailed(attempts, lastError)
    }

    /**
     * 下载二进制并校验。
     *
     * 先落到 `target.part`，[validate] 通过才改名到 [target]；
     * 校验不通过时删掉临时文件并换下一个源。
     *
     * @param validate 返回 null 表示通过，否则返回失败原因
     * @return 命中的来源与全部尝试记录
     */
    suspend fun download(
        sources: List<DownloadSource>,
        target: File,
        onAttempt: (Attempt) -> Unit = {},
        onProgress: (Long, Long, Long) -> Unit = { _, _, _ -> },
        perSourceTimeoutMs: Long = BINARY_TIMEOUT_MS,
        overallTimeoutMs: Long = OVERALL_TIMEOUT_MS,
        validate: (File) -> String? = { null }
    ): Pair<DownloadSource, List<Attempt>> = withContext(Dispatchers.IO) {
        val attempts = mutableListOf<Attempt>()
        // 每个源用**独立的**临时文件：超时的那个源可能还在后台写，共用一个
        // `target.part` 会和下一个源同时写同一个文件，产出一个混合了两次传输的坏文件。
        var part = File(target.absolutePath + ".part")
        var lastError: Throwable? = null
        val startedAt = System.nanoTime()
        target.parentFile?.mkdirs()
        try {
            for ((index, source) in sources.withIndex()) {
                // 单源时限之外还要有总预算：6 个源各 3 分钟就是 18 分钟，
                // 用户不可能接受「点了初始化然后去睡觉」。总预算用完就明确报错。
                val remainingMs = overallTimeoutMs - (System.nanoTime() - startedAt) / 1_000_000
                if (overallTimeoutMs > 0 && remainingMs <= 2_000) {
                    lastError = SocketTimeoutException("所有来源累计超过 ${overallTimeoutMs / 1000} 秒仍未完成")
                    break
                }
                val budget = if (perSourceTimeoutMs <= 0) remainingMs else minOf(perSourceTimeoutMs, remainingMs)
                part = File(target.absolutePath + ".part$index")
                part.delete()
                try {
                    downloadRaw(source.url, part, onProgress, budget)
                    val invalid = validate(part)
                    if (invalid != null) {
                        val attempt = Attempt(source.label, false, invalid)
                        attempts += attempt
                        onAttempt(attempt)
                        part.delete()
                        continue
                    }
                    if (target.exists()) target.delete()
                    if (!part.renameTo(target)) {
                        // 极少数文件系统不允许 rename 覆盖：退回复制
                        part.copyTo(target, overwrite = true)
                        part.delete()
                    }
                    val ok = Attempt(source.label, true)
                    attempts += ok
                    onAttempt(ok)
                    return@withContext source to attempts
                } catch (e: Throwable) {
                    // 用户取消要原样抛出去：吞掉的话「取消」会变成「换下一个源继续下」
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    lastError = e
                    val attempt = Attempt(source.label, false, e.message ?: e.javaClass.simpleName)
                    attempts += attempt
                    onAttempt(attempt)
                    part.delete()
                }
            }
            throw AllSourcesFailed(attempts, lastError)
        } finally {
            // 用户取消 / 进程被杀时也要带走半截文件：否则下次会读到上一次的残骸
            if (!target.isFile) part.delete()
        }
    }

    /**
     * 单源下载，带**硬性总超时**。
     *
     * 为什么不能只靠 HttpURLConnection 的 connect/read timeout：那两个是「每次系统调用」
     * 的时限，一条慢慢滴数据、或者分片线程逐个卡 60s 的连接，加起来可以是几分钟。
     * 而调用方（BDS 初始化卡片）在此期间只能看到进度条不动 —— 真实反馈就是「卡死」。
     *
     * 做法是把下载放进子协程，到点后调 [MultiThreadDownloader.DownloadSession.cancel]
     * 主动断开所有连接：阻塞中的 socket 读会立刻抛异常退出，不会留下一个还在后台
     * 往临时文件里写的僵尸任务。
     */
    private suspend fun downloadRaw(
        url: String,
        target: File,
        onProgress: (Long, Long, Long) -> Unit = { _, _, _ -> },
        timeoutMs: Long = BINARY_TIMEOUT_MS
    ) {
        if (timeoutMs <= 0) {
            downloadRawUnbounded(url, target, onProgress)
            return
        }
        val session = MultiThreadDownloader.DownloadSession()
        coroutineScope {
            val job = async(Dispatchers.IO) { downloadRawUnbounded(url, target, onProgress, session) }
            val finished = withTimeoutOrNull(timeoutMs) {
                job.await()
                true
            }
            if (finished == null) {
                // 先断连接（唤醒阻塞的读），再短暂等它收尾，最后才判定超时
                session.cancel()
                withTimeoutOrNull(5_000) { runCatching { job.join() } }
                target.delete()
                throw SocketTimeoutException("下载超时（${timeoutMs / 1000} 秒无进展）")
            }
        }
        if (!target.isFile || target.length() == 0L) {
            throw IllegalStateException("下载结果为空")
        }
    }

    private suspend fun downloadRawUnbounded(
        url: String,
        target: File,
        onProgress: (Long, Long, Long) -> Unit = { _, _, _ -> },
        session: MultiThreadDownloader.DownloadSession? = null
    ) {
        MultiThreadDownloader.download(
            url = url,
            target = target,
            onProgress = onProgress,
            session = session
        )
        if (!target.isFile || target.length() == 0L) {
            throw IllegalStateException("下载结果为空")
        }
    }

    /** 文件 SHA-256（小写十六进制），用于和索引里的校验值比对 */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 单个源的默认时限。
     *
     * 索引这类文本只有 20KB 出头，15 秒下不完就说明这个入口对当前网络不通；
     * 二进制包（box64 的 .deb 10MB、rootfs 32MB）给 3 分钟 —— 相当于要求
     * 至少 60KB/s，低于这个速度换源比死等更快。
     */
    const val TEXT_TIMEOUT_MS = 15_000L
    const val BINARY_TIMEOUT_MS = 180_000L

    /** 单次多源下载的总预算：超过就判失败，不再无限换源 */
    const val OVERALL_TIMEOUT_MS = 600_000L
}
