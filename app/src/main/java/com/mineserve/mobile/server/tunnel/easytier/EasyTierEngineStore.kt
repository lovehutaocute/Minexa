package com.mineserve.mobile.server.tunnel.easytier

import android.content.Context
import com.mineserve.mobile.McApplication
import com.mineserve.mobile.data.MultiThreadDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * EasyTier 引擎（libeasytier_android_jni.so）的本地仓库：下载、校验、安装、卸载、加载。
 *
 * ## 为什么引擎不随 APK 内置
 *
 * 16MB 的原生库打进 APK 意味着所有用户（包括不用组网的）都为它付体积，而它只有一个
 * 使用场景。改成按需下载后，APK 减重 16MB，「Android 应用兼容性」的 16KB 对齐清单里
 * 也少一项 —— 这份引擎本身是 16KB 对齐的（CI 用新 NDK 出的），但清单扫描看的是包内文件，
 * 下载到数据目录的它不再参与扫描。
 *
 * ## 托管位置与镜像
 *
 * 引擎托管在独立仓库 `lovehutaocute/EasyTierEngine` 的 Release（tag `v<版本>`）上，
 * 不和 Minexa 主仓库混在一起 —— 引擎有自己独立的发布节奏（跟随上游 EasyTier），
 * 混在主仓库的 release 列表里只会互相干扰。
 * 官方公共节点死掉的教训之一就是"单一来源"：所以这里和 frpc 引擎一样，
 * 直连 + 多个 GitHub 加速镜像逐个试，手机网络千差万别，总得有一条通。
 *
 * ## 校验
 *
 * 下载完先验 SHA-256 再落位（临时文件 + 原子改名）。引擎要被 dlopen，
 * 一个截断/被换过的 .so 轻则加载失败，重则任意代码执行 —— 这一步不能省。
 */
object EasyTierEngineStore {

    /** 当前分发的引擎版本（与 GitHub Release 的 tag 对应）。 */
    const val ENGINE_VERSION = "2.7.0"

    /** 期望大小与指纹：对不上就拒装。 */
    const val EXPECTED_SIZE_BYTES = 16_411_848L
    const val EXPECTED_SHA256 = "7fb3e098d55b93c80fc19e15b364d69d69ba16ba998c53fcfd772366ad176d81"

    private const val RELEASE_BASE =
        "https://github.com/lovehutaocute/EasyTierEngine/releases/download/v$ENGINE_VERSION"
    private const val LIB_NAME = "libeasytier_android_jni.so"

    /**
     * 发行页（人可读的那一页）。
     *
     * 提供给「打开发行页」按钮：自动下载全部候选都被网络拦掉时，用户仍能自己去
     * Release 页手动取一份 .so 再走「导入引擎」。这是下载链路的最后一根救命绳，
     * 所以和 [DOWNLOAD_URLS] 里的直链分开定义 —— 直链会随版本号变，页面地址不会。
     */
    const val RELEASE_PAGE_URL = "https://github.com/lovehutaocute/EasyTierEngine/releases"

    /** 下载候选，按序尝试。GitHub 直连在很多网络下不通，镜像放前面。 */
    val DOWNLOAD_URLS: List<String> = listOf(
        "https://ghfast.top/$RELEASE_BASE/$LIB_NAME",
        "https://gh-proxy.com/$RELEASE_BASE/$LIB_NAME",
        "$RELEASE_BASE/$LIB_NAME"
    )

    /** 引擎安装目录（应用私有数据目录，卸载应用即清空）。 */
    fun installDir(context: Context = McApplication.get()): File =
        File(context.filesDir, "easytier")

    fun engineFile(context: Context = McApplication.get()): File =
        File(installDir(context), LIB_NAME)

    fun versionFile(context: Context = McApplication.get()): File =
        File(installDir(context), "version.txt")

    fun isInstalled(context: Context = McApplication.get()): Boolean {
        val f = engineFile(context)
        return f.isFile && f.length() == EXPECTED_SIZE_BYTES
    }

    /** 已安装引擎的 SHA-256（展示用，走 IO 线程调用）。 */
    fun installedSha256(context: Context = McApplication.get()): String? = runCatching {
        sha256Of(engineFile(context))
    }.getOrNull()

    /**
     * 卸载：**先停实例，再删文件**。
     *
     * 顺序不能反：JNI 一旦 `System.load`，`.so` 就已经在进程内存里了，删文件不会让
     * 正在跑的实例消失 —— 虚拟网卡还挂着、转发端口还占着，用户看到的是
     * 「引擎都删了，组网还在跑 / 还在报错」。同理，删完文件也不能把
     * [EasyTierJNI.loaded] 置回 false：那样再下载一次引擎会因「同一路径已加载」
     * 而加载失败，反而必须重启进程才能用。
     */
    fun uninstall(context: Context = McApplication.get()): Boolean {
        runCatching { EasyTierEngine.stop(context) }
        runCatching { com.easytier.jni.EasyTierJNI.stopAllInstances() }
        val f = engineFile(context)
        val deleted = !f.exists() || f.delete()
        versionFile(context).delete()
        return deleted
    }

    /**
     * 在线下载并安装。
     *
     * 候选地址逐个试；失败的是**全部**候选才抛异常，错误信息聚合每一条的原因，
     * 否则用户只看到最后一条"连接超时"，不知道还试过什么。
     *
     * @param onProgress 0f..1f；-1f 表示在试新地址、进度未知
     */
    suspend fun download(onProgress: (Float) -> Unit = {}): String = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        // createTempFile 的目录必须真实存在，否则报的是文件系统错误（No such file or directory），
        // 会被误当成"下载源不对"—— 三条镜像都白试。首次下载时目录还不存在。
        installDir().mkdirs()
        for (url in DOWNLOAD_URLS) {
            try {
                onProgress(-1f)
                val tmp = File.createTempFile("easytier-", ".so", installDir())
                try {
                    downloadTo(url, tmp, onProgress)
                    install(tmp)
                } finally {
                    tmp.delete()
                }
                return@withContext ENGINE_VERSION
            } catch (e: Exception) {
                errors.add("${url.substringBefore("/releases")}: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        throw IOException(errors.joinToString("；"))
    }

    /** 从用户选中的文件导入（升级/离线场景）。校验失败不覆盖已有引擎。 */
    fun install(input: java.io.InputStream): String {
        installDir().mkdirs()
        val tmp = File.createTempFile("easytier-", ".so", installDir())
        try {
            input.use { tmp.outputStream().use { out -> input.copyTo(out, 64 * 1024) } }
            return install(tmp)
        } finally {
            tmp.delete()
        }
    }

    /**
     * 校验并落位。大小、SHA-256、ELF 魔数三关都过才动正式文件 ——
     * **校验失败时绝不覆盖已装引擎**：用户手上未必有第二份。
     */
    private fun install(tmp: File): String {
        if (tmp.length() != EXPECTED_SIZE_BYTES) {
            throw IOException("文件大小不符（${tmp.length()}，应为 $EXPECTED_SIZE_BYTES）")
        }
        val head = ByteArray(4)
        FileInputStream(tmp).use { input ->
            var filled = 0
            while (filled < head.size) {
                val n = input.read(head, filled, head.size - filled)
                if (n < 0) break
                filled += n
            }
        }
        if (!head.decodeToString().startsWith("\u007fELF")) {
            throw IOException("不是有效的 ELF 库文件")
        }
        val actual = sha256Of(tmp)
        if (!actual.equals(EXPECTED_SHA256, ignoreCase = true)) {
            throw IOException("SHA-256 不符（$actual）")
        }
        installDir().mkdirs()
        val dest = engineFile()
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            // rename 跨不了文件系统，走复制兜底
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        versionFile().writeText(ENGINE_VERSION)
        return ENGINE_VERSION
    }

    private suspend fun downloadTo(url: String, dest: File, onProgress: (Float) -> Unit) {
        // 走全局下载器：明文/https 分流、断点续传、多线程这些都在里面
        MultiThreadDownloader.download(
            url = url,
            target = dest,
            onProgress = { downloaded: Long, total: Long, _ ->
                val frac = if (total > 0) (downloaded.toDouble() / total).toFloat() else -1f
                onProgress(frac.coerceIn(-1f, 1f))
            }
        )
    }

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buf).also { read = it } >= 0) md.update(buf, 0, read)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
