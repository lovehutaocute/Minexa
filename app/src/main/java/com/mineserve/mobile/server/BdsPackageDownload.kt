package com.mineserve.mobile.server

import android.util.Log
import com.mineserve.mobile.data.BdsDigestStore
import com.mineserve.mobile.data.BdsPackageIntegrity
import com.mineserve.mobile.data.BdsVersionEntry
import com.mineserve.mobile.data.BdsVersions
import com.mineserve.mobile.data.DownloadSource
import com.mineserve.mobile.data.MultiSourceDownload
import java.io.File

/**
 * 把一个 [BdsVersionEntry] 变成磁盘上一个**已通过完整性校验**的 zip。
 *
 * 三件事绑在一起做，因为它们互为条件：
 *  1. **直链候选**（[BdsVersions.candidates]）：清单可能记错构建号、老包在新旧
 *     两个 CDN 上各有一份，逐个试比「一条 404 就判死」现实；
 *  2. **完整性校验**：每个源下载完立刻校验，不通过就当这个源失败、换下一个 ——
 *     校验失败的文件永远不会被改名成正式包（[MultiSourceDownload] 先写 `.part`）；
 *  3. **摘要固定**（[BdsDigestStore]）：清单没公布哈希的老版本，校验通过后把
 *     摘要记下来，下次同版本必须一致。
 *
 * 调用方（新装 / 换版本）拿到 [Prepared] 后才能解压；任何失败都抛 [Failed]，
 * 且此时目标路径上没有残留文件，服务器目录一个字节没动。
 */
object BdsPackageDownload {

    private const val TAG = "BdsPackageDownload"

    data class Prepared(
        val file: File,
        val source: DownloadSource,
        val report: BdsPackageIntegrity.Report
    ) {
        val levelLabel: String get() = BdsPackageIntegrity.levelLabel(report.level)
    }

    /**
     * 下载失败。
     *
     * [integrityFailure] 为 true 表示「下完了但校验不过」—— 界面据此提示
     * 「文件校验失败，可重新下载」，与「所有源都连不上」是两种不同的处置。
     */
    class Failed(
        message: String,
        val attempts: List<MultiSourceDownload.Attempt>,
        val integrityFailure: Boolean = false,
        cause: Throwable? = null
    ) : RuntimeException(message, cause)

    suspend fun download(
        entry: BdsVersionEntry,
        dest: File,
        digests: BdsDigestStore? = null,
        onProgress: (Long, Long, Long) -> Unit = { _, _, _ -> },
        onAttempt: (MultiSourceDownload.Attempt) -> Unit = {},
        perSourceTimeoutMs: Long = PER_SOURCE_TIMEOUT_MS,
        overallTimeoutMs: Long = OVERALL_TIMEOUT_MS
    ): Prepared {
        val version = entry.version
        val pinned = digests?.pinned(version)
        val expectedSha = entry.sha256?.takeIf { it.length == 64 } ?: pinned?.sha256
        val level = when {
            entry.hasDigest -> BdsPackageIntegrity.Level.ManifestSha256
            pinned != null -> BdsPackageIntegrity.Level.PinnedSha256
            else -> BdsPackageIntegrity.Level.Structural
        }
        val expectedBytes = entry.sizeBytes ?: pinned?.bytes?.takeIf { it > 0 }
        val sources = BdsVersions.candidates(entry)
        if (sources.isEmpty()) throw Failed("版本 $version 没有可用的下载地址", emptyList())

        var report: BdsPackageIntegrity.Report? = null
        var integrityMessage: String? = null
        var integrityReason: BdsPackageIntegrity.Reason? = null

        dest.parentFile?.mkdirs()
        try {
            val (source, _) = MultiSourceDownload.download(
                sources = sources,
                target = dest,
                onAttempt = onAttempt,
                onProgress = onProgress,
                perSourceTimeoutMs = perSourceTimeoutMs,
                overallTimeoutMs = overallTimeoutMs,
                validate = { file ->
                    // 校验强度只由「这个版本有没有权威摘要」决定，与源无关：
                    // 换源不会让一个没有摘要的包变得可信。
                    runCatching {
                        BdsPackageIntegrity.verify(
                            file = file,
                            expectedSha256 = expectedSha,
                            expectedBytes = expectedBytes,
                            level = level,
                            onProgress = null
                        )
                    }.fold(
                        onSuccess = { r ->
                            report = r
                            integrityMessage = null
                            integrityReason = null
                            null
                        },
                        onFailure = { e ->
                            val failure = e as? BdsPackageIntegrity.Failure
                            integrityMessage = e.message
                            integrityReason = failure?.reason
                            // 摘要不符时不换源重试同一个坏文件没有意义，但换源**可能**拿到
                            // 一份好的：所以仍然交给多源层换下一个源，只是把原因带出去。
                            e.message ?: "安装包校验失败"
                        }
                    )
                }
            )
            val finalReport = report ?: run {
                // 理论上 validate 一定跑过；走到这里说明多源层逻辑变了，必须报错而不是放行
                throw Failed("安装包未经完整性校验，已中止安装", emptyList())
            }
            // 校验通过才落摘要：失败的文件绝不进登记表，否则下次会把坏包当成基准
            runCatching { digests?.pin(version, finalReport.sha256, finalReport.bytes) }
            runCatching {
                Log.i(
                    TAG,
                    "BDS $version 下载完成：${finalReport.bytes} 字节，" +
                        "校验=${BdsPackageIntegrity.levelLabel(finalReport.level)}，源=${source.label}"
                )
            }
            return Prepared(dest, source, finalReport)
        } catch (e: MultiSourceDownload.AllSourcesFailed) {
            throw Failed(
                message = buildMessage(e, integrityReason),
                attempts = e.attempts,
                integrityFailure = e.attempts.any { it.reason?.contains("SHA-256") == true } ||
                    integrityReason != null,
                cause = e
            )
        }
    }

    /**
     * 失败原因要能区分「网络不通」和「内容不对」。
     *
     * 用户看到「所有来源都失败」时最需要知道的是：是换个网络重试，还是这个版本
     * 本身已经不可下载。摘要不符 = 服务器返回了错的东西（可能被劫持/镜像损坏），
     * 与网络完全无关，必须点名。
     */
    private fun buildMessage(
        error: MultiSourceDownload.AllSourcesFailed,
        integrityReason: BdsPackageIntegrity.Reason?
    ): String {
        val shaFailure = error.attempts.firstOrNull { it.reason?.contains("SHA-256") == true }
        return when {
            shaFailure != null ->
                "下载到的安装包 SHA-256 与清单不一致（已中止安装，可重新下载）：${shaFailure.label}"
            integrityReason == BdsPackageIntegrity.Reason.Corrupt ->
                "下载到的安装包已损坏（已中止安装，可重新下载）"
            integrityReason == BdsPackageIntegrity.Reason.Truncated ->
                "下载不完整（已中止安装，可重新下载）"
            error.attempts.isNotEmpty() ->
                "所有下载源都不可用：${error.attempts.joinToString("；") { "${it.label} ${it.reason ?: "失败"}" }}"
            else -> "下载失败：${error.message}"
        }
    }

    /** `1.21.84.1` → 安全的文件名片段（版本号里不会出现分隔符，但仍兜一层） */
    fun safeName(version: String): String =
        version.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }
            .joinToString("")

    /** 官方包现在约 93MB，老版本 30~60MB：单源 15 分钟、总预算 20 分钟 */
    const val PER_SOURCE_TIMEOUT_MS = 15L * 60 * 1000
    const val OVERALL_TIMEOUT_MS = 20L * 60 * 1000
}
