package com.mineserve.mobile.data

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * BDS 安装包的完整性校验。
 *
 * ## 为什么单开一层
 *
 * 原来只有「体积够大 + zip 能打开 + 主程序条目在」三条结构性判断
 * （[com.mineserve.mobile.server.BdsUpdater] 旧实现）。这对「拿错文件」有效，
 * 对「文件被改了」无效：镜像返回一个**能正常打开的**、把 `bedrock_server`
 * 换成其它二进制的包，结构判断全部通过。历史版本下载把可选的源从 1 个
 * 扩到十几个（老包在新旧两个 CDN、清单里还记着构建号变体），
 * 「内容是不是官方那一份」必须由哈希回答，不能再靠形状。
 *
 * ## 三级结论
 *
 *  - [Level.ManifestSha256]：清单公布了 SHA-256，逐字节比对，不符即失败；
 *  - [Level.PinnedSha256]：清单没公布，但这个版本在本机**装成功过**，
 *    用当时记下的摘要比对（防止同一个版本号第二次下到不一样的东西）；
 *  - [Level.Structural]：两者都没有（老版本）。此时必须通过 zip 全条目 CRC
 *    扫描 + 结构检查 —— 这是「没有权威摘要」时能做到的最强判据。
 *
 * 校验失败**一律抛 [Failure]**，调用方据此中止安装并允许重下。
 * 绝不返回「警告但仍继续」：装一个半截的核心，用户看到的是启动时崩溃，
 * 那时已经很难追回是下载环节的问题。
 */
object BdsPackageIntegrity {

    private const val TAG = "BdsIntegrity"

    /** 校验强度，界面要如实显示用的是哪一档 */
    enum class Level { ManifestSha256, PinnedSha256, Structural }

    /** 失败原因（界面按它决定文案与「重试」按钮） */
    enum class Reason {
        /** 文件为空：连接被中断或取到了 0 字节响应 */
        Empty,

        /** 体积与清单/响应头不符：典型的半截文件 */
        Truncated,

        /** SHA-256 不符：内容被改、镜像文件损坏或被中间人替换 */
        ShaMismatch,

        /** zip 结构损坏 / 条目 CRC 不通过 */
        Corrupt,

        /** 读文件本身失败 */
        Unreadable
    }

    class Failure(
        val reason: Reason,
        message: String,
        val expectedSha256: String? = null,
        val actualSha256: String? = null
    ) : RuntimeException(message)

    data class Report(
        val level: Level,
        val sha256: String,
        val expectedSha256: String?,
        val bytes: Long,
        /** 结构校验时扫过的 zip 条目数；有摘要时为 0（省掉一次全量解压） */
        val crcEntries: Int = 0
    ) {
        val strict: Boolean get() = level != Level.Structural
    }

    /**
     * 计算 SHA-256（小写十六进制）。
     *
     * 流式读，256KB 一块：安装包 90MB 起、老版本也有 30MB，
     * 一次读进内存会在低端机上直接 OOM。
     */
    fun sha256(file: File, onProgress: ((Long, Long) -> Unit)? = null): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val total = file.length()
        var read = 0L
        var lastReport = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
                read += n
                // 每 8MB 报一次，避免进度回调本身成为瓶颈
                if (onProgress != null && read - lastReport >= 8L * 1024 * 1024) {
                    lastReport = read
                    onProgress(read, total)
                }
            }
        }
        onProgress?.invoke(read, total)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 校验一个已下载到临时路径的安装包。
     *
     * @param expectedSha256 清单/固定摘要给出的期望值；null 表示只能做结构校验
     * @param expectedBytes  清单给出的体积（可选，用于提前发现截断）
     * @param level          与 [expectedSha256] 配套的强度标记
     * @throws Failure 任何一项不满足
     */
    fun verify(
        file: File,
        expectedSha256: String?,
        expectedBytes: Long? = null,
        level: Level = if (expectedSha256.isNullOrBlank()) Level.Structural else Level.ManifestSha256,
        onProgress: ((Long, Long) -> Unit)? = null
    ): Report {
        if (!file.isFile) throw Failure(Reason.Unreadable, "安装包不存在：${file.name}")
        val bytes = file.length()
        if (bytes <= 0L) throw Failure(Reason.Empty, "安装包为空（0 字节），下载被中断")

        if (expectedBytes != null && expectedBytes > 0 && bytes != expectedBytes) {
            throw Failure(
                Reason.Truncated,
                "安装包体积不符：期望 $expectedBytes 字节，实际 $bytes 字节（下载不完整）",
                expectedSha256, null
            )
        }

        val actual = try {
            sha256(file, onProgress)
        } catch (e: Throwable) {
            throw Failure(Reason.Unreadable, "读取安装包失败：${e.message ?: e.javaClass.simpleName}")
        }

        if (!expectedSha256.isNullOrBlank() && !actual.equals(expectedSha256, ignoreCase = true)) {
            // 单测里 android.util.Log 是桩（调用即抛），日志本身必须被兜住，
            // 否则「记录一条不匹配」会把校验流程变成 RuntimeError。
            runCatching { Log.w(TAG, "SHA-256 不匹配：期望 $expectedSha256 实际 $actual（${file.name}）") }
            throw Failure(
                Reason.ShaMismatch,
                "SHA-256 校验失败（下载内容与官方不一致，可能损坏或被替换）",
                expectedSha256, actual
            )
        }

        // 没有权威摘要时补一次 zip 全条目 CRC：能抓出「central directory 完好、
        // 但某个条目的数据流被截断/改坏」这种哈希以外、结构检查以内的损坏。
        val crcEntries = if (expectedSha256.isNullOrBlank()) scanZip(file) else 0
        return Report(
            level = if (expectedSha256.isNullOrBlank()) Level.Structural else level,
            sha256 = actual,
            expectedSha256 = expectedSha256,
            bytes = bytes,
            crcEntries = crcEntries
        )
    }

    /**
     * 逐条目读完整个 zip，靠 [ZipFile] 自带的 CRC 校验发现损坏。
     *
     * Java 的 `ZipFile` 在条目流读到末尾时会比对 CRC-32 与声明长度，不符抛
     * `ZipException`。所以「读完」本身就是校验动作 —— 不需要另算一遍 CRC。
     *
     * @return 扫过的条目数（0 表示这个 zip 里没有任何文件，一定不是 BDS 包）
     */
    fun scanZip(file: File): Int {
        var entries = 0
        try {
            ZipFile(file).use { zip ->
                val buffer = ByteArray(128 * 1024)
                val list = zip.entries()
                while (list.hasMoreElements()) {
                    val entry = list.nextElement()
                    if (entry.isDirectory) continue
                    zip.getInputStream(entry).use { input ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n <= 0) break
                        }
                    }
                    entries++
                }
            }
        } catch (e: ZipException) {
            throw Failure(Reason.Corrupt, "安装包已损坏：${e.message ?: "zip 结构不完整"}")
        } catch (e: Throwable) {
            throw Failure(Reason.Corrupt, "安装包无法读取：${e.message ?: e.javaClass.simpleName}")
        }
        if (entries == 0) throw Failure(Reason.Corrupt, "安装包里没有任何文件，不是 BDS 安装包")
        return entries
    }

    /** 给界面用的一句话摘要（「清单 SHA-256 校验通过」/「结构校验通过」） */
    fun levelLabel(level: Level): String = when (level) {
        Level.ManifestSha256 -> "清单 SHA-256"
        Level.PinnedSha256 -> "本地记录 SHA-256"
        Level.Structural -> "结构 + CRC"
    }
}

/**
 * 「这个版本的包长什么样」的本地记录。
 *
 * 用途只有一个，但很关键：清单没公布哈希的老版本，第一次装成功后把摘要记下来，
 * 之后再下载同一版本就必须与它一致。否则「清单记错构建号」会被下载层的
 * 换源逻辑悄悄换成另一个构建号的包，界面显示的还是用户选的那个版本 ——
 * 这类错位在日志里几乎看不出来。
 *
 * 存 SharedPreferences 而不是文件：内容只有几十条 `版本 → 64 位十六进制`。
 */
class BdsDigestStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("bds_package_digests", Context.MODE_PRIVATE)

    data class Pinned(val sha256: String, val bytes: Long)

    fun pinned(version: String): Pinned? {
        if (version.isBlank()) return null
        val raw = runCatching { prefs.getString(key(version), null) }.getOrNull() ?: return null
        val sha = raw.substringBefore('|')
        if (sha.length != 64) return null
        return Pinned(sha, raw.substringAfter('|', "0").toLongOrNull() ?: 0L)
    }

    fun pin(version: String, sha256: String, bytes: Long) {
        if (version.isBlank() || sha256.length != 64) return
        runCatching { prefs.edit().putString(key(version), "$sha256|$bytes").apply() }
    }

    fun forget(version: String) {
        runCatching { prefs.edit().remove(key(version)).apply() }
    }

    /** 版本键按前三段归一：`1.21.84` 与 `1.21.84.1` 是同一个包 */
    private fun key(version: String) = "v_" + BdsVersions.releaseKey(version.trim())
}
