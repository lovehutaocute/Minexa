package com.mineserve.mobile.runtime

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** 「一键修复证书库」的结果，直接用于崩溃弹窗的进度/结果展示。 */
data class TrustStoreRepair(
    val ok: Boolean,
    val summary: String,
    val steps: List<String> = emptyList()
)

/**
 * Java 信任库（cacerts）重建。
 *
 * 为什么要有这一步：`apt-get --reinstall openjdk-*` 依赖软件源可用，而 cacerts 损坏
 * 往往正是「网络/证书」这条链出问题的场景，重装经常自己就下不下来。只要本机还留着
 * 任意一份 PEM 证书包（Termux 的 `etc/tls/cacerts.pem`、`etc/ssl/certs/ca-certificates.crt`，
 * 或 Android 系统 `/system/etc/security/cacerts`），就能纯本地重建，不依赖网络。
 *
 * 全程不破坏原文件：先备份为 `cacerts.bak`，写入走临时文件 + rename。
 */
object TrustStoreRepairer {

    /** JDK 默认 cacerts 口令；JDK 读 cacerts 时用的就是它。 */
    const val DEFAULT_PASSWORD = "changeit"

    private const val BEGIN = "-----BEGIN CERTIFICATE-----"
    private const val END = "-----END CERTIFICATE-----"

    /** 从任意文本里拆出所有 PEM 证书块（拼接后交给 CertificateFactory 解析）。 */
    fun extractPemBlocks(text: String): List<String> {
        val blocks = mutableListOf<String>()
        var cursor = 0
        while (true) {
            val begin = text.indexOf(BEGIN, cursor)
            if (begin < 0) break
            val end = text.indexOf(END, begin)
            if (end < 0) break
            val blockEnd = end + END.length
            blocks += text.substring(begin, blockEnd)
            cursor = blockEnd
        }
        return blocks
    }

    /** 解析 PEM 里的全部证书。单张损坏只跳过它，不影响其余证书。 */
    fun parseCertificates(pemText: String): List<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509")
        return extractPemBlocks(pemText).mapNotNull { block ->
            runCatching {
                factory.generateCertificate(ByteArrayInputStream(block.toByteArray(Charsets.US_ASCII)))
                    as? X509Certificate
            }.getOrNull()
        }
    }

    /**
     * 用 [pemText] 重建 [target]；返回成功导入的证书数（0 表示没解析到任何证书，未改动磁盘）。
     */
    fun rebuild(target: File, pemText: String, password: String = DEFAULT_PASSWORD): Int {
        val certs = parseCertificates(pemText)
        if (certs.isEmpty()) return 0
        return runCatching {
            val store = KeyStore.getInstance("PKCS12")
            store.load(null, password.toCharArray())
            certs.forEachIndexed { index, cert ->
                store.setCertificateEntry("minexa-ca-$index", cert)
            }
            target.parentFile?.mkdirs()
            // 备份原文件：修复失败时用户/客服还能回退，不至于连「坏的」都没了。
            if (target.isFile && target.length() > 0) {
                runCatching { target.copyTo(File(target.parentFile, target.name + ".bak"), overwrite = true) }
            }
            val tmp = File(target.parentFile, target.name + ".tmp")
            FileOutputStream(tmp).use { store.store(it, password.toCharArray()) }
            if (!tmp.renameTo(target)) {
                // 某些 FUSE/公共存储不支持 rename 覆盖，退回复制。
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            certs.size
        }.getOrDefault(0)
    }
}
