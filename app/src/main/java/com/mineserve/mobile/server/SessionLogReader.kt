package com.mineserve.mobile.server

import java.io.File
import java.io.RandomAccessFile

/**
 * 会话日志切片读取。
 *
 * 「本次启动」是崩溃排查的硬边界：只允许读取 `[startOffset, endOffset]` 这一段字节，
 * 上一次启动留在同一个文件里的内容必须完全排除。这里刻意做成**纯 java.io** 实现
 * （不依赖 Android），以便在 JVM 单测里用临时文件证明隔离成立。
 *
 * @param startOffset 启动瞬间的文件长度（会话起点）
 * @param endOffset 进程退出时冻结的终点；`null` 表示读到当前文件末尾（运行中场景）
 * @param maxBytes 单次读取上限，超出时从尾部截断（崩溃现场在尾部，保留尾部更有价值）
 */
object SessionLogReader {

    const val DEFAULT_MAX_BYTES: Long = 4L * 1024L * 1024L

    data class Slice(
        val text: String,
        /** 本次切片实际覆盖的字节数（截断后） */
        val bytes: Long,
        /** 是否因为 [maxBytes] 被截断 */
        val truncated: Boolean,
        /** 被丢弃的头部字符数，用于「已省略前 N 字节」提示 */
        val droppedBytes: Long
    )

    fun read(
        file: File,
        startOffset: Long,
        endOffset: Long?,
        maxBytes: Long = DEFAULT_MAX_BYTES
    ): Slice? {
        if (!file.isFile) return null
        val length = file.length()
        val start = startOffset.coerceIn(0L, length)
        val end = (endOffset ?: length).coerceIn(start, length)
        if (end <= start) return Slice("", 0L, false, 0L)

        val total = end - start
        val readStart = if (total > maxBytes) end - maxBytes else start
        val bytes = ByteArray((end - readStart).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(readStart)
            raf.readFully(bytes)
        }
        var text = String(bytes, Charsets.UTF_8)
        // 截断点可能落在多字节字符或行中间：丢掉首个残行，避免日志出现半截乱码行。
        if (readStart > start) {
            val nl = text.indexOf('\n')
            if (nl >= 0) text = text.substring(nl + 1)
        }
        return Slice(text, total, readStart > start, readStart - start)
    }
}
