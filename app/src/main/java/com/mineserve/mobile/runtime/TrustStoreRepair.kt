package com.mineserve.mobile.runtime

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** 「一键修复证书库」的结果，直接用于崩溃弹窗的进度/结果展示。 */
data class TrustStoreRepair(
    val ok: Boolean,
    val summary: String,
    val steps: List<String> = emptyList()
)

/** 信任库的实际落盘格式。Termux JDK 的 `cacerts` 可能是 JKS/PKCS12，也可能是指向 PEM 证书包的软链。 */
enum class TrustStoreFormat { PEM, KEYSTORE, MISSING }

/** 信任库体检结果。 */
data class TrustStoreHealth(
    val format: TrustStoreFormat,
    val certificates: Int,
    val error: String? = null
) {
    val readable: Boolean get() = error == null
    val usable: Boolean get() = readable && certificates >= TrustStoreRepairer.MIN_USABLE_CERTIFICATES

    fun describe(): String = when {
        !readable -> "无法读取（${error}）"
        format == TrustStoreFormat.MISSING -> "0 张证书（缺失或空文件）"
        usable -> "$certificates 张证书"
        else -> "只有 $certificates 张证书（低于 ${TrustStoreRepairer.MIN_USABLE_CERTIFICATES}，判为残缺）"
    }
}

/** 写入结果：本次新导入张数、写后总张数、写后体检。 */
data class TrustStoreWrite(
    val imported: Int,
    val health: TrustStoreHealth,
    val error: String? = null
)

/**
 * Java 信任库（cacerts）修复。
 *
 * 为什么要有这一步：`apt-get --reinstall openjdk-*` 依赖软件源可用，而 cacerts 损坏
 * 往往正是「网络/证书」这条链出问题的场景，重装经常自己就下不下来。只要本机还留着
 * 任意一份 PEM 证书包（Termux 的 `etc/tls/cacerts.pem`、`etc/ssl/certs/ca-certificates.crt`、
 * Android 系统 `/system/etc/security/cacerts`），就能纯本地重建，不依赖网络。
 *
 * ## 三条不能违反的约束（都是踩过的坑）
 *
 * 1. **不能只看「文件存在且非空」**。PKIX 报错时 cacerts 往往是**能读、但缺根证书**：
 *    早期版本只判存在与长度，于是「一键修复」直接把结论判成「已恢复」、什么都不做，
 *    用户反馈就是「点了修复也不行」。
 * 2. **格式必须沿用原文件**。Termux 的 JDK 里 `lib/security/cacerts` 可能是指向
 *    `etc/tls/cacerts.pem` 的软链（PEM 文本）。无脑写 PKCS12 会把 JDK 期望的格式换掉，
 *    还可能顺手把与 ca-certificates 共享的 PEM 写坏。
 * 3. **不能只读第一份证书包**。设备上常出现第一份 PEM 被截断/只剩几张证书，
 *    用它重建出来的库「能读但没用」。所有来源合并去重，并校验总张数。
 *
 * 全程不破坏原文件：写前备份为 `cacerts.bak`，写入走临时文件 + rename（rename 失败时
 * 先删软链再复制，避免顺着链接把 PKCS12 二进制写进 PEM）。
 */
object TrustStoreRepairer {

    /** JDK 默认 cacerts 口令；JDK 读 cacerts 时用的就是它。 */
    const val DEFAULT_PASSWORD = "changeit"

    /**
     * 一份「有用」的信任库最少应有的证书数。
     *
     * 桌面 JDK 自带 100+、Android 系统库 140+；低于 [MIN_USABLE_CERTIFICATES] 基本只有
     * 两种可能：来源 PEM 被截断，或旧库被清过只补回几张。判「残缺」而不是「损坏」，
     * 处理方式都是补齐。
     */
    const val MIN_USABLE_CERTIFICATES = 40

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

    /** 多份证书包合并后按指纹去重（顺序稳定：先出现的来源优先）。 */
    fun collectCertificates(sources: List<String>): List<X509Certificate> {
        val seen = HashSet<String>()
        val result = mutableListOf<X509Certificate>()
        sources.forEach { text ->
            parseCertificates(text).forEach { cert ->
                if (seen.add(fingerprint(cert))) result += cert
            }
        }
        return result
    }

    private fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { byte -> "%02x".format(byte) }

    /**
     * 落盘目标：软链一律解析到真实文件（Termux 的 cacerts 常是指向 PEM 的软链，
     * 顺着链路改才能真正影响 JDK 读到的内容）；悬空软链或普通路径原样返回。
     */
    fun resolveTarget(target: File): File =
        runCatching { if (target.exists()) target.canonicalFile else target }.getOrDefault(target)

    /** 识别现有文件格式；只看头部 4KB，避免把几十 MB 的文件读进来。 */
    fun detectFormat(file: File): TrustStoreFormat {
        if (!file.isFile || file.length() == 0L) return TrustStoreFormat.MISSING
        val head = runCatching { readHead(file, 4096) }.getOrDefault(ByteArray(0))
        val text = String(head, Charsets.ISO_8859_1)
        return if (text.contains(BEGIN)) TrustStoreFormat.PEM else TrustStoreFormat.KEYSTORE
    }

    private fun readHead(file: File, limit: Int): ByteArray {
        file.inputStream().use { input ->
            val buffer = ByteArray(limit)
            var read = 0
            while (read < limit) {
                val n = input.read(buffer, read, limit - read)
                if (n <= 0) break
                read += n
            }
            return if (read == limit) buffer else buffer.copyOf(read)
        }
    }

    /** 体检：能不能读、有多少张证书。**这是判断「修好了没有」的唯一依据**。 */
    fun inspect(target: File, password: String = DEFAULT_PASSWORD): TrustStoreHealth {
        val file = resolveTarget(target)
        val format = detectFormat(file)
        if (format == TrustStoreFormat.MISSING) {
            return TrustStoreHealth(format, 0, if (file.exists()) "文件为 0 字节" else "文件不存在")
        }
        if (format == TrustStoreFormat.PEM) {
            val count = runCatching { parseCertificates(file.readText()).size }.getOrNull()
                ?: return TrustStoreHealth(format, 0, "PEM 证书包读不出来")
            return TrustStoreHealth(format, count)
        }
        val store = loadStore(file, password)
            ?: return TrustStoreHealth(format, 0, "PKCS12/JKS 都解析失败，可能已损坏")
        val count = runCatching {
            java.util.Collections.list(store.aliases()).count { alias -> store.getCertificate(alias) != null }
        }.getOrDefault(0)
        return TrustStoreHealth(format, count)
    }

    private fun loadStore(file: File, password: String): KeyStore? {
        for (type in listOf("PKCS12", "JKS")) {
            val store = runCatching {
                KeyStore.getInstance(type).apply {
                    file.inputStream().use { load(it, password.toCharArray()) }
                }
            }.getOrNull()
            if (store != null) return store
        }
        return null
    }

    /**
     * 把 [sources] 里本机缺失的证书补进 [target]，**保留原有条目与原有格式**。
     *
     * 之所以是「补」不是「重写」：JDK 自带的 cacerts 有它自己的一套根证书，
     * 整体重建会把 JDK 认可、而本机 PEM 没有的锚点丢掉。
     *
     * @return 新导入张数与写后体检；[TrustStoreWrite.error] 非空表示磁盘层面失败。
     */
    fun topUp(target: File, sources: List<String>, password: String = DEFAULT_PASSWORD): TrustStoreWrite {
        val file = resolveTarget(target)
        val incoming = collectCertificates(sources)
        if (incoming.isEmpty()) {
            return TrustStoreWrite(0, inspect(file, password), "本机证书包解析不到任何证书")
        }
        return when (detectFormat(file)) {
            TrustStoreFormat.PEM -> topUpPem(file, incoming)
            TrustStoreFormat.KEYSTORE -> topUpKeystore(file, incoming, password)
            TrustStoreFormat.MISSING -> writeNewStore(file, incoming, password)
        }
    }

    /** PEM 形态：原文本 + 追加缺失证书块，仍写成 PEM。 */
    private fun topUpPem(file: File, incoming: List<X509Certificate>): TrustStoreWrite {
        val existingText = runCatching { file.readText() }.getOrDefault("")
        val existing = parseCertificates(existingText)
        val seen = existing.map { fingerprint(it) }.toMutableSet()
        val additions = incoming.filter { seen.add(fingerprint(it)) }
        if (additions.isEmpty()) return TrustStoreWrite(0, inspect(file))

        val pem = buildString {
            append(existingText.trimEnd())
            if (isNotEmpty()) appendLine()
            additions.forEach { cert ->
                appendLine(BEGIN)
                appendLine(
                    java.util.Base64.getEncoder().encodeToString(cert.encoded)
                        .chunked(64).joinToString("\n")
                )
                appendLine(END)
            }
        }
        if (!writeAtomically(file, pem.toByteArray(Charsets.US_ASCII))) {
            return TrustStoreWrite(0, inspect(file), "写入失败（磁盘只读或无权限）")
        }
        return TrustStoreWrite(additions.size, inspect(file))
    }

    /** JKS/PKCS12 形态：加载原库、补缺失条目、整体回写。 */
    private fun topUpKeystore(file: File, incoming: List<X509Certificate>, password: String): TrustStoreWrite {
        val store = loadStore(file, password)
            ?: return TrustStoreWrite(0, inspect(file, password), "原证书库解析失败，无法补齐")
        val seen = runCatching {
            java.util.Collections.list(store.aliases())
                .mapNotNull { alias -> store.getCertificate(alias) as? X509Certificate }
                .map { fingerprint(it) }
                .toMutableSet()
        }.getOrDefault(mutableSetOf())
        var imported = 0
        incoming.forEach { cert ->
            if (!seen.add(fingerprint(cert))) return@forEach
            var alias = "minexa-ca-$imported"
            var suffix = 0
            while (store.containsAlias(alias)) alias = "minexa-ca-$imported-${suffix++}"
            store.setCertificateEntry(alias, cert)
            imported++
        }
        if (imported == 0) return TrustStoreWrite(0, inspect(file, password))
        val ok = runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(tmp).use { store.store(it, password.toCharArray()) }
            replace(tmp, file)
        }.getOrDefault(false)
        if (!ok) return TrustStoreWrite(0, inspect(file, password), "写入失败（磁盘只读或无权限）")
        return TrustStoreWrite(imported, inspect(file, password))
    }

    /** 原文件不可用（缺失/空/解析不了）时新建一份 PKCS12 信任库。 */
    fun writeNewStore(file: File, certs: List<X509Certificate>, password: String = DEFAULT_PASSWORD): TrustStoreWrite {
        if (certs.isEmpty()) return TrustStoreWrite(0, inspect(file, password), "没有可用证书")
        val store = runCatching {
            KeyStore.getInstance("PKCS12").apply { load(null, password.toCharArray()) }
        }.getOrNull() ?: return TrustStoreWrite(0, inspect(file, password), "系统不支持 PKCS12")
        certs.forEachIndexed { index, cert -> store.setCertificateEntry("minexa-ca-$index", cert) }
        val ok = runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(tmp).use { store.store(it, password.toCharArray()) }
            replace(tmp, file)
        }.getOrDefault(false)
        if (!ok) return TrustStoreWrite(0, inspect(file, password), "写入失败（磁盘只读或无权限）")
        return TrustStoreWrite(certs.size, inspect(file, password))
    }

    /**
     * 用 [pemText] 整体重建 [target]；返回成功导入的证书数（0 表示没解析到任何证书，未改动磁盘）。
     * 保留给纯逻辑测试与「原库已不可用」的兜底路径。
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
            backup(target)
            val tmp = File(target.parentFile, target.name + ".tmp")
            FileOutputStream(tmp).use { store.store(it, password.toCharArray()) }
            replace(tmp, target)
            certs.size
        }.getOrDefault(0)
    }

    /**
     * 把信任库里的证书导出成 PEM 文本；PEM 形态直接返回原文，读不出来返回 null。
     *
     * 用途：给 installer 单独准备一份「并集」信任库时，要把 JDK 自带的 cacerts 也
     * 作为来源之一 —— JDK 认可的锚点本机 PEM 未必都有（反之亦然）。
     */
    fun exportPem(target: File, password: String = DEFAULT_PASSWORD): String? {
        val file = resolveTarget(target)
        return when (detectFormat(file)) {
            TrustStoreFormat.MISSING -> null
            TrustStoreFormat.PEM -> runCatching { file.readText() }.getOrNull()
            TrustStoreFormat.KEYSTORE -> {
                val store = loadStore(file, password) ?: return null
                runCatching {
                    java.util.Collections.list(store.aliases())
                        .mapNotNull { alias -> store.getCertificate(alias) }
                        .joinToString("\n") { pemOf(it) }
                }.getOrNull()?.takeIf { it.contains(BEGIN) }
            }
        }
    }

    private fun pemOf(cert: java.security.cert.Certificate): String = buildString {
        appendLine(BEGIN)
        appendLine(
            java.util.Base64.getEncoder().encodeToString(cert.encoded)
                .chunked(64).joinToString("\n")
        )
        appendLine(END)
    }

    /** 备份原文件：修复失败时用户/客服还能回退，不至于连「坏的」都没了。 */
    private fun backup(target: File) {
        if (!target.isFile || target.length() == 0L) return
        runCatching { target.copyTo(File(target.parentFile, target.name + ".bak"), overwrite = true) }
    }
    private fun writeAtomically(target: File, bytes: ByteArray): Boolean = runCatching {
        target.parentFile?.mkdirs()
        backup(target)
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { it.write(bytes) }
        replace(tmp, target)
    }.getOrDefault(false)

    /**
     * tmp → target 的原子替换。
     *
     * 不用 `tmp.renameTo(target)` 的兜底复制：某些 FUSE/公共存储上 rename 不覆盖，
     * 而 `copy` 会**顺着软链**写进链接目标（把 PKCS12 二进制灌进 PEM 文件）。
     * 所以兜底路径先删（删的是软链本身）再复制。
     */
    private fun replace(tmp: File, target: File): Boolean {
        if (tmp.renameTo(target)) return true
        runCatching { target.delete() }
        return runCatching { tmp.copyTo(target, overwrite = true).let { true } }.getOrDefault(false)
            .also { runCatching { tmp.delete() } }
    }
}
