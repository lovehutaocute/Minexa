package com.mineserve.mobile.mcp

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * MCP 文件工具的纯 JVM 辅助函数（可单测）：
 *  - 沙盒路径解析：任何读写都限制在服务器目录内
 *  - 高效日志尾部读取：从文件末尾按块回扫定位行边界，避免整文件加载
 */
object McpFileUtils {

    /**
     * 把相对路径解析到 [root] 内的绝对路径。
     * 越界（`..`、绝对路径、符号链接逃逸）抛 [IllegalArgumentException]。
     */
    fun sandboxedFile(root: File, relPath: String): File {
        val cleanRoot = root.canonicalFile
        val trimmed = relPath.trim().ifEmpty { "." }
        val target = File(cleanRoot, trimmed).canonicalFile
        if (target != cleanRoot && !target.path.startsWith(cleanRoot.path + File.separator)) {
            throw IllegalArgumentException("Path escapes the server directory: $relPath")
        }
        return target
    }

    /**
     * 读取文件末尾最多 [maxLines] 行（UTF-8）。
     * 从文件末尾按 8KB 块回扫定位行边界，只读需要的部分；
     * 超过 [maxBytes] 时进一步裁剪，此时会丢弃被截断的首行。
     */
    fun tailLines(file: File, maxLines: Int, maxBytes: Long = 2L * 1024 * 1024): List<String> {
        if (maxLines <= 0 || !file.isFile || file.length() == 0L) return emptyList()
        RandomAccessFile(file, "r").use { raf ->
            val size = raf.length()
            var pos = size
            var newlines = 0
            var start = 0L
            var found = false
            val buf = ByteArray(8192)
            while (pos > 0 && !found) {
                val read = minOf(buf.size.toLong(), pos).toInt()
                pos -= read
                raf.seek(pos)
                raf.readFully(buf, 0, read)
                for (i in read - 1 downTo 0) {
                    if (buf[i] == '\n'.code.toByte()) {
                        newlines++
                        if (newlines > maxLines) {
                            start = pos + i + 1
                            found = true
                            break
                        }
                    }
                }
            }
            if (found) {
                // start 定位在某个 '\n' 之后：行边界天然完整；仅当字节上限进一步裁剪时才丢首行
                var dropFirst = false
                if (size - start > maxBytes) {
                    start = size - maxBytes
                    dropFirst = true
                }
                return readFrom(raf, start, size, dropFirst).takeLast(maxLines)
            }
            // 行数不足：整读（必要时按字节裁剪并丢弃被截断的首行）
            val clipped = (size - maxBytes).coerceAtLeast(0L)
            return readFrom(raf, clipped, size, dropFirstLine = clipped > 0L).takeLast(maxLines)
        }
    }

    private fun readFrom(raf: RandomAccessFile, from: Long, to: Long, dropFirstLine: Boolean): List<String> {
        if (to <= from) return emptyList()
        val out = ByteArray((to - from).toInt())
        raf.seek(from)
        raf.readFully(out)
        var lines = String(out, Charsets.UTF_8).split('\n')
        // 尾部换行会 split 出一个空串；截断起点可能落在多字节字符中间，首行一并丢弃
        if (lines.lastOrNull()?.isEmpty() == true) lines = lines.dropLast(1)
        if (dropFirstLine && lines.isNotEmpty()) lines = lines.drop(1)
        return lines.map { it.trimEnd('\r') }
    }

    /** 递归删除目录/文件，返回删除的条目数（不含失败的） */
    fun deleteRecursivelyCounted(file: File): Int {
        if (file.isDirectory) {
            var count = 0
            file.listFiles()?.forEach { count += deleteRecursivelyCounted(it) }
            return count + if (file.delete()) 1 else 0
        }
        return if (file.delete()) 1 else 0
    }

    /** 是否疑似二进制内容（前 8KB 出现 NUL 字节） */
    fun looksBinary(file: File): Boolean =
        file.inputStream().use { input ->
            val head = ByteArray(8192)
            val n = input.read(head)
            (0 until (n.coerceAtLeast(0))).any { head[it] == 0.toByte() }
        }

    /**
     * 从远端下载 URL 推导安全的落盘文件名。
     *
     * 为什么必须净化：url 来自 Modrinth / 用户传入等外部数据源，不可信。
     * 若直接取 `url.substringAfterLast('/')` 再 URLDecoder 解码，`%2F` 会解成 `/`、
     * `%2e%2e` 会解成 `..`；下游只补 `.jar` 后缀就 `File(dir, name)`，
     * 可越界写到目标目录之外。
     * 做法：解码后只保留最后一个路径段（同时处理 `/` 与 `\`），再拒绝空串、`.`、`..`；
     * 仍不合法则回退到一个同样经过路径成分剥离的安全默认名（保留 `.jar` 后缀）。
     *
     * @param fallbackStem 回退名用的主干（同样是不可信输入，会再剥一次路径成分）
     */
    fun safeRemoteFileName(url: String, fallbackStem: String): String {
        val decoded = runCatching { java.net.URLDecoder.decode(url.substringAfterLast('/'), "UTF-8") }
            .getOrNull().orEmpty()
        // 解码后仍可能由 %2F / %5C 还原出分隔符，取最后一段彻底消除目录成分
        val candidate = decoded.substringAfterLast('/').substringAfterLast('\\').trim()
        if (candidate.isNotEmpty() && candidate != "." && candidate != "..") return candidate
        val fallback = fallbackStem.substringAfterLast('/').substringAfterLast('\\').trim()
        return if (fallback.isNotEmpty() && fallback != "." && fallback != "..") "$fallback.jar" else "download.jar"
    }

    /** 支持的摘要算法白名单（避免把任意字符串交给 MessageDigest 抛异常） */
    private val DIGEST_ALGORITHMS = mapOf(
        "md5" to "MD5",
        "sha1" to "SHA-1",
        "sha256" to "SHA-256"
    )

    /**
     * 计算文件摘要，返回小写十六进制；算法不支持时返回 null。
     * 流式读取（64KB 缓冲），不会把整个文件载入内存。
     */
    fun digestHex(file: File, algorithm: String): String? {
        val jca = DIGEST_ALGORITHMS[algorithm.lowercase()] ?: return null
        if (!file.isFile) return null
        val digest = java.security.MessageDigest.getInstance(jca)
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 是否受支持的摘要算法名 */
    fun supportsDigest(algorithm: String): Boolean = DIGEST_ALGORITHMS.containsKey(algorithm.lowercase())

    /** 摘要算法名列表（用于错误提示） */
    fun digestAlgorithms(): List<String> = DIGEST_ALGORITHMS.keys.toList()
}
