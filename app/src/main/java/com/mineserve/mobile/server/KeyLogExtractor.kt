package com.mineserve.mobile.server

/**
 * 「关键日志」抽取：从**本次启动**的日志切片里挑出崩溃排查真正需要的片段。
 *
 * 与崩溃报告里的 `scanLatestLog` 同源但更严格：
 *  - 输入已经是会话切片（不含历史启动），这里只负责「挑重点 + 记行号」；
 *  - 输出带 1 基行号，供 UI 从「分析/关键日志」跳转到「全部日志」并高亮；
 *  - 双上限（行数 + 字符数）保证在崩溃回调线程上也不会 OOM。
 */
object KeyLogExtractor {

    enum class Reason {
        /** 首个异常 / Caused by 链 / 栈块 */
        EXCEPTION,
        /** Paper Watchdog 卡顿转储 */
        WATCHDOG,
        /** FATAL / OOM / 缺类缺库等单行致命信息 */
        SEVERE,
        /** 崩溃前最后若干行上下文 */
        CRASH_TAIL
    }

    data class Segment(
        /** 1 基起始行号（相对传入的日志切片） */
        val startLine: Int,
        /** 1 基结束行号（含） */
        val endLine: Int,
        val reason: Reason,
        val lines: List<String>
    ) {
        val lineCount: Int get() = lines.size
    }

    const val MAX_LINES = 600
    const val MAX_CHARS = 256 * 1024
    private const val STACK_BUDGET = 60
    private const val TAIL_LINES = 20

    /** 需要连同后续缩进栈一起保留的标志行 */
    private val BLOCK_MARKERS = listOf(
        "Paper Watchdog Thread/ERROR",
        "The server has not responded",
        "Current Thread: Server thread",
        "Current Thread: \"Server thread\"",
        "Server thread dump",
        "Exception in thread",
        "Caused by:",
        "Failed to start the minecraft server",
        "Failed to start"
    )

    private val WATCHDOG_MARKERS = listOf(
        "Paper Watchdog Thread/ERROR",
        "The server has not responded",
        "Server thread dump",
        "Current Thread: Server thread"
    )

    /** 单独成行的严重错误标志 */
    private val SEVERE_MARKERS = listOf(
        "OutOfMemoryError",
        "StackOverflowError",
        "NoSuchMethodError",
        "NoClassDefFoundError",
        "ClassNotFoundException",
        "UnsatisfiedLinkError",
        "ExceptionInInitializerError",
        "FATAL",
        "/WARN]: Failed to",
        "/WARN]: Could not",
        "Could not create the Java Virtual Machine",
        "Error occurred during initialization of VM",
        "Invalid or corrupt jarfile",
        "Server stop requested",
        "Quit correctly"
    )

    fun extract(
        lines: List<String>,
        maxLines: Int = MAX_LINES,
        maxChars: Int = MAX_CHARS
    ): List<Segment> {
        if (lines.isEmpty()) return emptyList()
        val segments = mutableListOf<Segment>()
        var blockStart = -1
        var blockReason = Reason.EXCEPTION
        var stackBudget = 0
        var blockChars = 0

        fun flushBlock(endExclusive: Int) {
            if (blockStart >= 0 && endExclusive > blockStart) {
                segments += Segment(
                    startLine = blockStart + 1,
                    endLine = endExclusive,
                    reason = blockReason,
                    lines = lines.subList(blockStart, endExclusive)
                )
            }
            blockStart = -1
            stackBudget = 0
            blockChars = 0
        }

        lines.forEachIndexed { index, line ->
            val isBlock = BLOCK_MARKERS.any { line.contains(it, ignoreCase = true) }
            if (isBlock) {
                flushBlock(index)
                blockStart = index
                blockReason = if (WATCHDOG_MARKERS.any { line.contains(it, ignoreCase = true) }) {
                    Reason.WATCHDOG
                } else {
                    Reason.EXCEPTION
                }
                stackBudget = STACK_BUDGET
                blockChars = line.length
                return@forEachIndexed
            }
            if (blockStart >= 0 && stackBudget > 0 && continuesBlock(line)) {
                stackBudget--
                blockChars += line.length
                // 单块超预算：立刻收口，避免一条超长栈把整个预算吃光
                if (blockChars > maxChars / 4) flushBlock(index + 1)
                return@forEachIndexed
            }
            if (blockStart >= 0) flushBlock(index)
            // 块刚收口时这一行本身仍可能是严重错误行，必须继续参与 SEVERE 判定
            if (SEVERE_MARKERS.any { line.contains(it, ignoreCase = true) }) {
                segments += Segment(index + 1, index + 1, Reason.SEVERE, listOf(line))
            }
        }
        flushBlock(lines.size)

        // 尾部上下文：始终保留最后若干行（崩溃结论通常就在尾部），
        // 已经落在异常块里的行不重复收集。
        val tailStart = (lines.size - TAIL_LINES).coerceAtLeast(0)
        if (tailStart < lines.size) {
            val covered = segments.filter { it.reason != Reason.CRASH_TAIL }
            val uncovered = (tailStart until lines.size).filter { idx ->
                covered.none { idx + 1 in it.startLine..it.endLine }
            }
            if (uncovered.isNotEmpty()) {
                val first = uncovered.first()
                val last = uncovered.last()
                segments += Segment(first + 1, last + 1, Reason.CRASH_TAIL, lines.subList(first, last + 1))
            }
        }

        return clamp(segments.sortedBy { it.startLine }, maxLines, maxChars)
    }

    /**
     * 预算裁剪：优先丢中间的段，保留首段（通常是首个异常）与尾部段（崩溃现场）。
     * 单段自身超限时保留其尾部（栈顶在前、根因在后）。
     */
    private fun clamp(segments: List<Segment>, maxLines: Int, maxChars: Int): List<Segment> {
        var result = segments
        while (result.size > 2 && (result.sumOf { it.lineCount } > maxLines ||
                result.sumOf { it.lines.sumOf { l -> l.length + 1 } } > maxChars)) {
            val dropIndex = (result.size - 2).coerceAtLeast(1)
            result = result.filterIndexed { index, _ -> index != dropIndex }
        }
        return result.map { seg ->
            val charBudget = seg.lines.sumOf { it.length + 1 }
            if (seg.lineCount > maxLines || charBudget > maxChars) {
                val kept = seg.lines.takeLast(maxLines.coerceAtMost(seg.lineCount))
                seg.copy(
                    startLine = seg.endLine - kept.size + 1,
                    lines = kept
                )
            } else {
                seg
            }
        }
    }

    /** 栈行：以空白字符开头的行（Java 栈帧、Caused by 的缩进续行）。 */
    private fun isStackLine(line: String): Boolean =
        line.isNotEmpty() && (line[0] == '\t' || line[0] == ' ')

    /**
     * 是否仍属于当前异常块。
     *
     * MC 日志有两种形态：带 `[HH:MM:SS]` 前缀的一行一条（log4j），以及纯 stdout。
     * 块内的合法续行包括：
     *  - 缩进的栈帧（`\tat a.B.c(...)`）；
     *  - 异常自身那一行（`java.lang.RuntimeException: boom`，没有缩进也没有时间前缀）；
     *  - `... 12 more` / `Suppressed:` 之类的收尾行。
     * 一旦遇到新的带时间前缀行，说明进入了另一条日志，块应当收口。
     */
    private fun continuesBlock(line: String): Boolean {
        if (isStackLine(line)) return true
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        if (isContinuation(line)) return true
        return !startsWithLogPrefix(trimmed)
    }

    /** 日志前缀：`[12:00:00]`、`2026-01-01 12:00:00` 这类新记录起点。 */
    private fun startsWithLogPrefix(trimmed: String): Boolean {
        if (trimmed.startsWith("[")) return true
        return trimmed.length > 10 && trimmed[4] == '-' && trimmed[7] == '-'
    }

    /** 续行：`... 12 more`、`Suppressed:` 等非缩进但属于异常块的行。 */
    private fun isContinuation(line: String): Boolean {
        val t = line.trim()
        return t.endsWith(" more") || t.startsWith("Suppressed:") || t.startsWith("... ")
    }
}
