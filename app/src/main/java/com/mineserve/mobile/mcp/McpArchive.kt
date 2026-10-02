package com.mineserve.mobile.mcp

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream

/**
 * MCP 压缩包解压（纯 JVM，可单测）：
 * 支持 zip / tar / tar.gz(tgz) / tar.bz2(tbz2) / tar.xz(txz)。
 * 解压目标始终限制在指定目录内（canonical 路径校验），跳过符号链接等特殊 tar 条目。
 */
object McpArchive {

    /**
     * 解压累计字节上限（8 GiB）：既覆盖 MC 服务器整合包，又能挡住 zip bomb ——
     * 几 KB 的恶意压缩包可解出几十 TB，把设备存储写满。按累计量而非单条限制，
     * 因为"很多条各自不大"的压缩包同样能撑爆磁盘。
     */
    private const val MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024

    /** 单次解压条目数上限：防止海量小条目耗尽 inode 并拖垮解压循环 */
    private const val MAX_ENTRIES = 200_000

    /** 压缩包超出总量/条目上限；调用方（各 extract 分支）会先回滚本次新建的文件再抛出 */
    private class ArchiveLimitException(message: String) : IOException(message)

    data class Stats(val files: Int, val dirs: Int, val bytes: Long, val skipped: Int) {
        fun describe(): String {
            val parts = mutableListOf("Extracted $files files (${bytes / 1024} KB)")
            if (dirs > 0) parts.add("$dirs dirs")
            if (skipped > 0) parts.add("$skipped entries skipped (unsafe links or path traversal)")
            return parts.joinToString(", ")
        }
    }

    /** 解压 [archive] 到 [dest]（dest 必须已存在且为目录） */
    fun extract(archive: File, dest: File): Stats {
        require(dest.isDirectory) { "Destination must be an existing directory" }
        val name = archive.name.lowercase()
        return when {
            name.endsWith(".zip") -> extractZip(archive, dest)
            name.endsWith(".tar.gz") || name.endsWith(".tgz") ->
                extractTar(archive, dest) { BufferedInputStream(java.util.zip.GZIPInputStream(it)) }
            name.endsWith(".tar.bz2") || name.endsWith(".tbz2") ->
                extractTar(archive, dest) { BufferedInputStream(BZip2CompressorInputStream(it)) }
            name.endsWith(".tar.xz") || name.endsWith(".txz") ->
                extractTar(archive, dest) { BufferedInputStream(XZCompressorInputStream(it)) }
            name.endsWith(".tar") -> extractTar(archive, dest) { BufferedInputStream(it) }
            else -> throw IllegalArgumentException("Unsupported archive type (use .zip / .tar / .tar.gz / .tar.bz2 / .tar.xz)")
        }
    }

    /** 条目目标路径；越界返回 null。条目名统一归一化为 '/'（Windows 工具打包的 zip 用 '\' 分隔） */
    private fun safeTarget(dest: File, entryName: String): File? {
        val normalized = entryName.replace('\\', '/')
        val canonicalDest = dest.canonicalFile
        val target = File(canonicalDest, normalized).canonicalFile
        if (target == canonicalDest) return null
        return target.takeIf { it.path.startsWith(canonicalDest.path + File.separator) }
    }

    private fun extractZip(archive: File, dest: File): Stats {
        var files = 0
        var dirs = 0
        var bytes = 0L
        var skipped = 0
        // 记录本次解压新建的路径，触发上限时用于回滚。
        // 只记"写入前不存在"的文件，避免误删解压前就有的同名文件（原文件可能很大）。
        val created = ArrayList<File>()
        try {
            ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (files + dirs + skipped >= MAX_ENTRIES) {
                        throw ArchiveLimitException("archive has too many entries (limit $MAX_ENTRIES)")
                    }
                    val target = safeTarget(dest, entry.name)
                    if (target == null) {
                        skipped++
                        continue
                    }
                    if (entry.isDirectory) {
                        if (target.mkdirs()) created.add(target)
                        dirs++
                    } else {
                        target.parentFile?.mkdirs()
                        val existedBefore = target.exists()
                        FileOutputStream(target).use { out -> bytes += copyLimited(zip, out, MAX_TOTAL_BYTES - bytes) }
                        if (!existedBefore) created.add(target)
                        files++
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: ArchiveLimitException) {
            rollback(created)
            throw e
        }
        return Stats(files, dirs, bytes, skipped)
    }

    private fun extractTar(archive: File, dest: File, wrap: (FileInputStream) -> java.io.InputStream): Stats {
        var files = 0
        var dirs = 0
        var bytes = 0L
        var skipped = 0
        val created = ArrayList<File>()
        try {
            TarArchiveInputStream(wrap(FileInputStream(archive))).use { tar ->
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    if (files + dirs + skipped >= MAX_ENTRIES) {
                        throw ArchiveLimitException("archive has too many entries (limit $MAX_ENTRIES)")
                    }
                    val target = safeTarget(dest, entry.name)
                    if (target == null) {
                        skipped++
                        continue
                    }
                    when {
                        entry.isDirectory -> {
                            if (target.mkdirs()) created.add(target)
                            dirs++
                        }
                        entry.isSymbolicLink || entry.isLink || !entry.isFile -> skipped++ // 不落地链接等特殊条目
                        else -> {
                            target.parentFile?.mkdirs()
                            val existedBefore = target.exists()
                            FileOutputStream(target).use { out -> bytes += copyLimited(tar, out, MAX_TOTAL_BYTES - bytes) }
                            if (!existedBefore) created.add(target)
                            files++
                        }
                    }
                }
            }
        } catch (e: ArchiveLimitException) {
            rollback(created)
            throw e
        }
        return Stats(files, dirs, bytes, skipped)
    }

    /**
     * 限量拷贝：边读边累计，超过 [maxBytes]（本条目可用的剩余配额）立即抛错。
     *
     * 为什么不复用 [InputStream.copyTo]：copyTo 会一直读到条目末尾，单条超大条目
     * 就足以写满磁盘；事后再用 `target.length()` 统计为时已晚。只有边写边算，
     * 才能在写入过程中就掐断 —— 这是阻止 zip bomb 的关键。
     */
    private fun copyLimited(input: InputStream, output: OutputStream, maxBytes: Long): Long {
        val buf = ByteArray(64 * 1024)
        var written = 0L
        while (true) {
            val n = input.read(buf)
            if (n == -1) break
            if (written + n > maxBytes) {
                throw ArchiveLimitException(
                    "archive expansion exceeds the ${MAX_TOTAL_BYTES / (1024L * 1024 * 1024)} GiB cumulative limit"
                )
            }
            output.write(buf, 0, n)
            written += n
        }
        return written
    }

    /** 回滚本次解压新建的路径；目录均为新建（见写入处），可安全递归删除，空目录残留不影响正确性 */
    private fun rollback(created: List<File>) {
        created.asReversed().forEach { f ->
            runCatching { if (f.isDirectory) f.deleteRecursively() else f.delete() }
        }
    }
}
