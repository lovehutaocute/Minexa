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
 * 判定为「不支持」时只是不传参数（installer 走官方源，功能不受影响）；
 * 判定为「支持」但实际不认的情况由 `runInstallerWithRetry` 的摘镜像重试兜底。
 */
internal object InstallerCapabilities {

    /** 缓存上限：每个服务器目录的 server.jar 都是新 key，条目小但要封顶。 */
    private const val MAX_CACHE_ENTRIES = 64
    private val mirrorSupportCache = LinkedHashMap<String, Boolean>()

    private fun fingerprint(jar: File): String =
        "${jar.absolutePath}|${jar.length()}|${jar.lastModified()}"

    @Synchronized
    fun supportsMirror(installerJar: File): Boolean {
        val key = fingerprint(installerJar)
        mirrorSupportCache[key]?.let { return it }
        val supported = scanForMirror(installerJar)
        if (mirrorSupportCache.size >= MAX_CACHE_ENTRIES) mirrorSupportCache.clear()
        mirrorSupportCache[key] = supported
        return supported
    }

    private fun scanForMirror(installerJar: File): Boolean = try {
        JarFile(installerJar).use { jar ->
            jar.entries().asSequence()
                .filter { it.name.endsWith(".class") && it.size > 0 }
                .any { entry ->
                    jar.getInputStream(entry).use { input ->
                        val bytes = input.readBytes()
                        // 先匹配命中率更低但更具区分度的 installServer，再匹配 mirror（短路省 IO）
                        contains(bytes, INSTALL_SERVER) && contains(bytes, MIRROR)
                    }
                }
        }
    } catch (_: Exception) {
        // jar 读不动（损坏 / 正在下载）：乐观当作支持，交给运行期重试兜底
        true
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
