package com.mineserve.mobile.server

import java.io.File
import java.util.jar.JarFile

/**
 * installer 的参数能力探测（纯本地、零 JVM 启动）。
 *
 * 为什么需要：`--mirror` 是 jopt 选项，老 installer（如 Forge 1.7.10）根本没有它，
 * 传进去会 `UnrecognizedOptionException` 直接抛栈退出 —— 控制台里一段
 * `Exception in thread "main"` 会吓到用户，还白跑一整轮安装。
 * 靠 MC 版本号猜（上一版 `minor >= 17`）是错的：1.12.2 / 1.16.5 其实都支持。
 *
 * 判定方式：jopt 的选项名是类文件常量池里的字符串常量，直接扫 jar 内所有 .class
 * 的原始字节找 `mirror`。1.16.5 命中 5 个类（SimpleInstaller/DownloadUtils/…），
 * 1.7.10 一个都没有。没有启动 JVM 的成本，结果按 jar 指纹缓存。
 *
 * 判定为「不支持」时只是不传参数（installer 走官方源，功能不受影响）；
 * 判定为「支持」但实际不认的情况由 `runInstallerWithRetry` 的摘镜像重试兜底。
 */
internal object InstallerCapabilities {

    private val mirrorSupportCache = mutableMapOf<String, Boolean>()

    private fun fingerprint(jar: File): String =
        "${jar.absolutePath}|${jar.length()}|${jar.lastModified()}"

    @Synchronized
    fun supportsMirror(installerJar: File): Boolean {
        val key = fingerprint(installerJar)
        mirrorSupportCache[key]?.let { return it }
        val supported = scanForMirror(installerJar)
        mirrorSupportCache[key] = supported
        return supported
    }

    private fun scanForMirror(installerJar: File): Boolean = try {
        JarFile(installerJar).use { jar ->
            jar.entries().asSequence()
                .filter { it.name.endsWith(".class") && it.size > 0 }
                .any { entry ->
                    jar.getInputStream(entry).use { input ->
                        containsMirrorToken(input.readBytes())
                    }
                }
        }
    } catch (_: Exception) {
        // jar 读不动（损坏 / 正在下载）：乐观当作支持，交给运行期重试兜底
        true
    }

    /** 在类文件字节里找 ASCII `mirror`（常量池 UTF8 明文存放，无需解析 CP）。 */
    private fun containsMirrorToken(bytes: ByteArray): Boolean {
        val needle = MIRROR
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
}
