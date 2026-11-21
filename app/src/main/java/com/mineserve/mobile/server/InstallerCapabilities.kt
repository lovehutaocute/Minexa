package com.mineserve.mobile.server

import java.io.File
import java.util.jar.JarFile

/**
 * installer 的参数能力探测（纯本地、零 JVM 启动）。
 *
 * 为什么需要：`--mirror` 是 jopt 选项，老 installer 根本没有它，传进去会
 * `UnrecognizedOptionException` 直接抛栈退出 —— 控制台里一段
 * `Exception in thread "main"` 会吓到用户，还白跑一整轮安装。
 * 靠 MC 版本号猜（早期实现 `minor >= 17`）是错的：1.12.2 / 1.16.5 其实都支持。
 * 跑 `--help` 探测也不行：Proot 容器分支没有宿主 java 可用。
 *
 * 判定方式：jopt 的选项名是类文件常量池里的字符串常量，扫 jar 内 .class 原始字节。
 *
 * ⚠ 判据必须同时命中两个串，且必须在**同一个类**里：
 * jopt 的选项表把 `accepts("mirror")` 与 `accepts("installServer")` 写在同一个
 * `SimpleInstaller` 类里；而 Forge 1.7.10 / 1.8.9 / 1.10.2 虽然整包都有 `mirror`
 * 字样，但那只是 `MirrorData` 的横幅文案（`Data kindly mirrored by %s`）和
 * `VersionInfo.mirrorList` 字段 —— 它们的选项表里并没有 `--mirror`。
 * 只按「任意 class 含 mirror」判会把这三代老 installer 误判为支持（2026-11-21 审查实测）。
 *
 * 已知取舍：判据依赖 jopt 选项表与 `installServer` 同处一类。若上游把选项表拆到父类，
 * 判定会退化成 false —— 方向是安全的（不传参数不影响安装，只是少了镜像加速）。
 *
 * 判定为「不支持」时只是不传参数（installer 走官方源，功能不受影响）；
 * 判定为「支持」但实际不认的情况由 `runInstallerWithRetry` 的摘镜像重试兜底。
 */
internal object InstallerCapabilities {

    /** 缓存上限：每个服务器目录的 server.jar 都是新 key，条目小但要封顶。 */
    private const val MAX_CACHE_ENTRIES = 64

    /**
     * 探测结果。
     *
     * [readFailure] 非空表示 jar 根本读不动（半下载 / 损坏 / 权限），此时 [supported]
     * 乐观取 true 交给运行期重试兜底；把原因带出来是为了让调用方能在控制台说清
     * 「是 jar 坏了」而不是让用户去怀疑镜像。
     */
    data class MirrorProbe(val supported: Boolean, val readFailure: String? = null)

    private val cache = LinkedHashMap<String, MirrorProbe>()

    private fun fingerprint(jar: File): String =
        "${jar.absolutePath}|${jar.length()}|${jar.lastModified()}"

    @Synchronized
    fun probe(installerJar: File): MirrorProbe {
        val key = fingerprint(installerJar)
        cache[key]?.let { return it }
        val result = scanForMirror(installerJar)
        // 逐条淘汰最旧（插入序）而不是 clear() 全清：全清会让仍在用的 jar 反复重扫，
        // 而重扫是「读完整包内所有 class」的开销。
        while (cache.size >= MAX_CACHE_ENTRIES) {
            val oldest = cache.keys.firstOrNull() ?: break
            cache.remove(oldest)
        }
        cache[key] = result
        return result
    }

    /** 只要布尔结论的便捷入口（调用方不关心诊断信息时用）。 */
    fun supportsMirror(installerJar: File): Boolean = probe(installerJar).supported

    private fun scanForMirror(installerJar: File): MirrorProbe = try {
        JarFile(installerJar).use { jar ->
            val found = jar.entries().asSequence()
                // size == 0 的空条目没有内容可扫；-1（未知）要保留，zip 里确实存在。
                .filter { it.name.endsWith(".class") && it.size != 0L }
                .any { entry ->
                    jar.getInputStream(entry).use { input ->
                        val bytes = input.readBytes()
                        // 先匹配命中率更低但更具区分度的 installServer，再匹配 mirror（短路省 IO）
                        contains(bytes, INSTALL_SERVER) && contains(bytes, MIRROR)
                    }
                }
            MirrorProbe(found)
        }
    } catch (e: Exception) {
        // jar 读不动（损坏 / 正在下载）：乐观当作支持，交给运行期重试兜底；
        // 原因带出去让调用方提示「jar 可能损坏」，别把用户引向镜像排查。
        MirrorProbe(true, "${e.javaClass.simpleName}: ${e.message}")
    }

    /** 在类文件字节里找 ASCII 串（常量池 UTF8 明文存放，无需解析 CP 结构）。 */
    private fun contains(bytes: ByteArray, needle: ByteArray): Boolean {
        if (bytes.size < needle.size) return false
        var i = 0
        while (i <= bytes.size - needle.size) {
            if (bytes[i] == needle[0]) {
                var j = 1
                while (j < needle.size && bytes[i + j] == needle[j]) j++
                if (j == needle.size) return true
            }
            i++
        }
        return false
    }

    private val MIRROR = "mirror".toByteArray(Charsets.US_ASCII)
    private val INSTALL_SERVER = "installServer".toByteArray(Charsets.US_ASCII)
}
