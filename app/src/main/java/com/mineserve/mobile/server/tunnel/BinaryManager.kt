package com.mineserve.mobile.server.tunnel

import com.mineserve.mobile.runtime.RemoteArtifact
import com.mineserve.mobile.runtime.TermuxRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 隧道二进制管理。
 * frpc 优先级：缓存版 > apt 版 > GitHub 下载（走镜像源）
 */
class BinaryManager(private val termux: TermuxRuntime) {

    private val binDir: File
        get() = File(termux.installer.rootDir, "home/tunnel/bin").apply { mkdirs() }

    private val arch: String
        get() = if (android.os.Build.SUPPORTED_ABIS.any { it.startsWith("arm64") }) "arm64" else "amd64"

    /** frp GitHub release 固定版本（避免 /latest/download/ 404） */
    private val frpVersion = "0.61.2"
    private val frpReleaseUrl: String
        get() = "https://github.com/fatedier/frp/releases/download/v$frpVersion/frp_${frpVersion}_linux_$arch.tar.gz"

    /**
     * frp 官方 SHA-256（取自 release 里的 `frp_sha256_checksums.txt`）。
     *
     * 为什么必须校验：这个 tarball 会被解包、`chmod 755`、然后由 [TermuxBackend] 直接执行，
     * 而 [mirrors] 里除了第一项直连以外全是第三方 GitHub 反代，对内容不作任何承诺。
     * 旧代码只判「HTTP 200 且长度 > 1000」就放行——等于把「执行任意二进制」的权限
     * 交给镜像站的可用性/诚信度。
     *
     * frp 版本在代码里是固定的（[frpVersion]），所以摘要可以写死，不会因上游发新版而失效。
     */
    private val frpSha256 = mapOf(
        "arm64" to "6c80eb8549899e4a6f0d1c04cda58bfba47be949c308f6e55662f20b807296c2",
        "amd64" to "4738edbd4bf88db5fe0ccee946d63da3b498c9cc50b0c7317d017fe7d28a05ea"
    )

    /** GitHub 镜像源（依次尝试） */
    private val mirrors = listOf(
        "",  // 直连
        "https://gh-proxy.com/",
        "https://mirror.ghproxy.com/",
        "https://ghproxy.net/",
        "https://ghfast.top/"
    )

    /**
     * 确保 frpc 可用：
     * 1) 缓存版存在 → 直接返回
     * 2) apt 版存在 → 直接返回（不再触发下载）
     * 3) 都没有 → GitHub 下载（走镜像） → 失败回退 apt-get install
     */
    fun ensureFrp(): String? {
        val prefix = termux.installer.rootDir.absolutePath

        // 1. GitHub 缓存版
        val cached = File(binDir, "frpc")
        if (cached.exists() && cached.canExecute()) return cached.absolutePath

        // 2. apt 版
        findAptBinary(prefix, "frpc")?.let {
            termux.emitLog("[tunnel] frpc (apt): $it")
            return it
        }

        // 3. 下载（同步阻塞调用内部使用 runBlocking 等待并行结果）
        termux.emitLog("[tunnel] frpc 未安装，从 GitHub 下载...")
        return runBlocking { downloadFrp() } ?: installViaApt()
    }

    /** 检查 frpc 是否已安装（供一键依赖模块幂等判断） */
    fun isFrpInstalled(): Boolean {
        val prefix = termux.installer.rootDir.absolutePath
        return File(binDir, "frpc").canExecute() || findAptBinary(prefix, "frpc") != null
    }

    // ── 内部实现 ──────────────────────────────────────────────

    /**
     * 镜像并行竞速：**第一个下载并校验通过的就赢**，其余立刻取消。
     *
     * 旧写法是 `coroutineScope { mirrors.map { async { … } } }` 然后顺序 `await`，
     * 注释写的是"首个成功即返回"，但 `coroutineScope` 必须等**所有**子协程结束才返回——
     * 实际耗时等于最慢的那个镜像。直连 GitHub 卡满 8s 连接超时 + 30s 读超时时，
     * 隧道启动就被拖几十秒，而且 5 份全量 tgz 会一直下到底。
     */
    private suspend fun downloadFrp(): String? = withContext(Dispatchers.IO) {
        val winner = CompletableDeferred<String>()
        val jobs = mirrors.map { mirror ->
            val url = if (mirror.isEmpty()) frpReleaseUrl else "$mirror$frpReleaseUrl"
            launch(Dispatchers.IO) {
                val path = runCatching { tryDownloadFrp(url, mirror) }.getOrNull()
                if (path != null) winner.complete(path)
            }
        }
        // 兜底：全部失败时也要让 winner 有结果（空串代表"都没成"），否则这里会一直挂着。
        val guard = launch {
            jobs.joinAll()
            if (!winner.isCompleted) winner.complete("")
        }
        val result = winner.await()
        guard.cancel()
        // 拿到赢家后立刻取消其余镜像继续下载/解包。
        jobs.forEach { it.cancel() }
        result.ifEmpty { null }
    }

    private fun tryDownloadFrp(url: String, mirror: String): String? {
        termux.emitLog("[tunnel] 尝试下载 frpc: ${url.take(80)}...")
        val tag = Integer.toHexString(mirror.hashCode())
        val tgz = File(binDir, "frp-$tag.tar.gz")
        // 每个镜像用自己的暂存目录：多个镜像并发解包时若都往 binDir 里写 frpc 会互相踩。
        val stage = File(binDir, "frp-stage-$tag")
        try {
            if (!downloadFile(url, tgz)) return null

            // 完整性校验必须在解包/执行之前。校验不过就丢弃这个来源，换下一个镜像。
            val expected = frpSha256[arch]
            if (expected == null) {
                termux.emitLog("[tunnel] 未知架构 $arch，无可用校验值，拒绝使用下载的 frpc")
                return null
            }
            if (!RemoteArtifact.matches(tgz, expected)) {
                termux.emitLog("[tunnel] $mirror 校验失败（内容与官方不符），已丢弃该来源")
                return null
            }

            stage.deleteRecursively()
            stage.mkdirs()
            termux.execOnce("tar", "-xzf", tgz.absolutePath, "-C", stage.absolutePath,
                "--strip-components=1", "frp_${frpVersion}_linux_$arch/frpc")
            val staged = File(stage, "frpc")
            if (!staged.exists() || staged.length() == 0L) {
                termux.emitLog("[tunnel] $mirror 解包后未见 frpc，已丢弃")
                return null
            }
            val frpc = File(binDir, "frpc")
            frpc.delete()
            if (!staged.renameTo(frpc)) staged.copyTo(frpc, overwrite = true)
            termux.execOnce("chmod", "755", frpc.absolutePath)
            termux.emitLog("[tunnel] frpc 校验通过并安装: ${frpc.absolutePath}")
            return frpc.absolutePath
        } catch (e: Exception) {
            termux.emitLog("[tunnel] $mirror 下载失败: ${e.message}")
            return null
        } finally {
            runCatching { tgz.delete() }
            runCatching { stage.deleteRecursively() }
        }
    }

    private fun installViaApt(): String? {
        termux.emitLog("[tunnel] GitHub 下载失败，尝试 apt 安装...")
        termux.execOnce("apt-get", "update", "-y")
        // 同上：不关掉 apt 自身的包签名校验。
        termux.execOnce("apt-get", "install", "-y", "frp")
        return findAptBinary(termux.installer.rootDir.absolutePath, "frpc")
    }

    private fun findAptBinary(prefix: String, binaryName: String): String? {
        val candidates = listOf(
            File("$prefix/bin/$binaryName"),
            File("$prefix/data/data/com.termux/files/usr/bin/$binaryName"),
            File("$prefix/usr/bin/$binaryName")
        )
        val found = candidates.firstOrNull { it.exists() && it.canExecute() } ?: return null
        val link = File("$prefix/bin/$binaryName")
        if (found.absolutePath != link.absolutePath) {
            link.parentFile?.mkdirs()
            if (!link.exists()) {
                try { termux.execOnce("ln", "-sf", found.absolutePath, link.absolutePath) }
                catch (_: Exception) { termux.execOnce("cp", found.absolutePath, link.absolutePath); termux.execOnce("chmod", "755", link.absolutePath) }
            }
        }
        return if (link.exists() && link.canExecute()) link.absolutePath else found.absolutePath
    }

    /**
     * 手动跟随重定向下载。
     *
     * 重定向目标要做**主机白名单**：镜像站可以返回任意 `Location`，
     * 不限制的话一个被投毒的镜像就能把我们引到任何地址去（下到的内容仍会过 sha256，
     * 但这层限制能挡掉"把你导去内网/其它站点"这类副作用，也让排查更清楚）。
     */
    private fun downloadFile(urlStr: String, target: File): Boolean {
        if (!isAllowedDownloadHost(urlStr)) {
            termux.emitLog("[tunnel] 非白名单主机，跳过: ${urlStr.take(80)}")
            return false
        }
        var currentUrl = urlStr
        repeat(5) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 8000
                readTimeout = 30000
                connect()
            }
            when (conn.responseCode) {
                in 301..308 -> {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location.isNullOrBlank() || !isAllowedDownloadHost(location)) {
                        termux.emitLog("[tunnel] 重定向到非白名单主机，已中止: ${location?.take(80)}")
                        return false
                    }
                    currentUrl = location
                }
                200 -> {
                    conn.inputStream.use { i -> FileOutputStream(target).use { o -> i.copyTo(o) } }
                    conn.disconnect()
                    return target.exists() && target.length() > 1000
                }
                else -> { conn.disconnect(); return false }
            }
        }
        return false
    }

    /** 允许下载/重定向落到的主机：GitHub 本体 + 我们自己配的那几个镜像。 */
    private fun isAllowedDownloadHost(url: String): Boolean {
        val host = runCatching { URL(url).host?.lowercase() }.getOrNull() ?: return false
        return host == "github.com" ||
            host == "codeload.github.com" ||
            host.endsWith(".githubusercontent.com") ||
            host == "gh-proxy.com" || host.endsWith(".gh-proxy.com") ||
            host == "ghproxy.com" || host.endsWith(".ghproxy.com") ||
            host == "mirror.ghproxy.com" ||
            host == "ghproxy.net" || host.endsWith(".ghproxy.net") ||
            host == "ghfast.top" || host.endsWith(".ghfast.top")
    }
}
