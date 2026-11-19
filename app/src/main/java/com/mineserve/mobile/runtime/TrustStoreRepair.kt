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

    /**
     * 「JDK 能用」的唯一判据。
     *
     * **PEM 文本一律算不可用**：JDK 只从 JKS/PKCS12 读信任锚，`cacerts` 是 PEM 时
     * 它一个锚点都读不到，报的正是 `trustAnchors parameter must be non-empty`。
     * 而 PEM 文件「存在、非空、还能解析出 150 张证书」—— 只判存在/非空的检查会
     * 放过它，这正是「点了修复也不行」的主因之一。
     */
    val usable: Boolean get() = readable && format == TrustStoreFormat.KEYSTORE &&
        certificates >= TrustStoreRepairer.MIN_USABLE_CERTIFICATES

    fun describe(): String = when {
        !readable -> "无法读取（${error}）"
        format == TrustStoreFormat.MISSING -> "0 张证书（缺失或空文件）"
        format == TrustStoreFormat.PEM ->
            "PEM 文本 $certificates 张证书（JDK 不认这个格式 → 信任锚为空，必须转换）"
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
 * ## 四条不能违反的约束（都是踩过的坑）
 *
 * 1. **不能只看「文件存在且非空」**。真实崩溃日志里的报错是
 *    `trustAnchors parameter must be non-empty` —— 这是「库能读、但锚点为空」，
 *    文件本身存在且非空。早期版本判存在即「已恢复」，一键修复等于什么都没做。
 * 2. **`cacerts` 必须是 JDK 能读的 JKS/PKCS12**。它是指向 PEM 证书包的软链时，
 *    JDK 读到的锚点数是 0，必须**转换成 PKCS12**（转换后仍保留 PEM 里的全部证书）。
 *    转换时**不能顺着软链写**，否则会把 apt/curl 共用的 `ca-certificates.crt`
 *    灌成二进制，把系统其它组件的证书链一起搞坏。
 * 3. **不能只读第一份证书包**。设备上常出现第一份 PEM 被截断/只剩几张证书，
 *    用它重建出来的库「能读但没用」。所有来源合并去重，并校验总张数。
 * 4. **补而不是覆盖**。JDK 自带的 cacerts 有它自己的一套根证书，整体重建会把
 *    JDK 认可、而本机 PEM 没有的锚点丢掉。
 *
 * 全程不破坏原文件：写前备份为 `cacerts.bak`，写入走临时文件 + rename。
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
     * 读取用的落点：软链解析到真实文件（Termux 的 cacerts 常是指向 PEM 的软链，
     * 读的时候要读到证书内容）；悬空软链或普通路径原样返回。
     */
    fun resolveTarget(target: File): File =
        runCatching { if (target.exists()) target.canonicalFile else target }.getOrDefault(target)

    /**
     * 写入用的落点。
     *
     * 软链**只有指向 keystore 时**才顺着写（那是 JDK 与其他 JVM 共用的库，就地补最省事）；
     * 指向 PEM / 悬空时返回软链本身 —— 写入会替换掉这个链接、留下一个新文件，
     * 链接目标（apt/curl 用的 PEM 证书包）保持原样。
     */
    fun writeDestination(target: File): File {
        val link = runCatching { java.nio.file.Files.isSymbolicLink(target.toPath()) }
            .getOrDefault(false)
        if (!link) return target
        val real = runCatching { target.canonicalFile }.getOrNull() ?: return target
        return if (detectFormat(real) == TrustStoreFormat.KEYSTORE) real else target
    }

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

    /**
     * 体检：能不能读、有多少张证书。这是「修好了没有」的第一依据；
     * 第二依据是让 JVM 自己读一遍（见 `TermuxRuntime.verifyStoreWithJvm`）。
     */
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
            ?: return TrustStoreHealth(format, 0, "PKCS12/JKS 都解析失败，可能已损坏或口令不符")
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

    /** 取出库里的全部证书（PEM 与 keystore 都支持）；读不出来返回空表。 */
    fun certificatesOf(target: File, password: String = DEFAULT_PASSWORD): List<X509Certificate> {
        val file = resolveTarget(target)
        return when (detectFormat(file)) {
            TrustStoreFormat.MISSING -> emptyList()
            TrustStoreFormat.PEM -> runCatching { parseCertificates(file.readText()) }.getOrDefault(emptyList())
            TrustStoreFormat.KEYSTORE -> {
                val store = loadStore(file, password) ?: return emptyList()
                runCatching {
                    java.util.Collections.list(store.aliases())
                        .mapNotNull { alias -> store.getCertificate(alias) as? X509Certificate }
                }.getOrDefault(emptyList())
            }
        }
    }

    /**
     * 把 [sources] 里的证书并进 [target]，并保证结果是 **JDK 能读的 keystore**。
     *
     * - 目标是 keystore：保留原条目，只补缺的（见 [topUpKeystore]）；
     * - 目标是 PEM / 缺失 / 空：把「原文件里的证书 + 来源证书」去重后写成 PKCS12
     *   （PEM 必须转换，否则 JDK 读到的信任锚是 0）。
     *
     * @return 新导入张数与写后体检；[TrustStoreWrite.error] 非空表示没写成功。
     */
    fun topUp(target: File, sources: List<String>, password: String = DEFAULT_PASSWORD): TrustStoreWrite {
        val incoming = collectCertificates(sources)
        val dest = writeDestination(target)
        val existing = certificatesOf(target, password)

        if (existing.isEmpty() && incoming.isEmpty()) {
            return TrustStoreWrite(0, inspect(target, password), "本机证书包解析不到任何证书")
        }
        return if (detectFormat(dest) == TrustStoreFormat.KEYSTORE) {
            if (incoming.isEmpty()) TrustStoreWrite(0, inspect(dest, password), "本机证书包解析不到任何证书")
            else topUpKeystore(dest, incoming, password)
        } else {
            // PEM / 缺失 / 空：统一转成 PKCS12。原 PEM 里的证书与来源证书都算「导入」。
            writeNewStore(dest, existing + incoming, password)
        }
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

    /** 新建一份 PKCS12 信任库（按证书指纹去重）。 */
    fun writeNewStore(file: File, certs: List<X509Certificate>, password: String = DEFAULT_PASSWORD): TrustStoreWrite {
        val unique = LinkedHashMap<String, X509Certificate>()
        certs.forEach { unique.putIfAbsent(fingerprint(it), it) }
        val values = unique.values.toList()
        if (values.isEmpty()) return TrustStoreWrite(0, inspect(file, password), "没有可用证书")
        val store = runCatching {
            KeyStore.getInstance("PKCS12").apply { load(null, password.toCharArray()) }
        }.getOrNull() ?: return TrustStoreWrite(0, inspect(file, password), "系统不支持 PKCS12")
        values.forEachIndexed { index, cert -> store.setCertificateEntry("minexa-ca-$index", cert) }
        val ok = runCatching {
            file.parentFile?.mkdirs()
            backup(file)
            val tmp = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(tmp).use { store.store(it, password.toCharArray()) }
            replace(tmp, file)
        }.getOrDefault(false)
        if (!ok) return TrustStoreWrite(0, inspect(file, password), "写入失败（磁盘只读或无权限）")
        return TrustStoreWrite(values.size, inspect(file, password))
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
     * 把信任库里的证书导出成 PEM 文本；读不出来返回 null。
     *
     * 用途：给 JVM 单独准备一份「并集」信任库时，要把 JDK 自带的 cacerts 也
     * 作为来源之一 —— JDK 认可的锚点本机 PEM 未必都有（反之亦然）。
     */
    fun exportPem(target: File, password: String = DEFAULT_PASSWORD): String? {
        val certs = certificatesOf(target, password)
        if (certs.isEmpty()) return null
        return certs.joinToString("\n") { pemOf(it) }
    }

    private fun pemOf(cert: X509Certificate): String = buildString {
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

    /**
     * tmp → target 的原子替换。
     *
     * 不用 `tmp.renameTo(target)` 的兜底复制：某些 FUSE/公共存储上 rename 不覆盖，
     * 而 `copyTo` 会**顺着软链**写进链接目标（把 PKCS12 二进制灌进 PEM 文件）。
     * 所以兜底路径先删（删的是软链本身）再复制。
     */
    private fun replace(tmp: File, target: File): Boolean {
        if (tmp.renameTo(target)) return true
        runCatching { target.delete() }
        return runCatching { tmp.copyTo(target, overwrite = true).let { true } }.getOrDefault(false)
            .also { runCatching { tmp.delete() } }
    }
}
