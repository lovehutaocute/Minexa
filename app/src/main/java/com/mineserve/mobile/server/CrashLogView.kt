package com.mineserve.mobile.server

/**
 * 崩溃弹窗的日志视图模型（与 UI 解耦，便于单测与复用）。
 *
 * [lines] 与 [segments] 的行号严格对应：segments 里的 `startLine/endLine` 是
 * `lines` 的 1 基下标，UI 据此从「关键日志」跳到「全部日志」并滚动高亮。
 */
data class CrashLogView(
    val scopeId: String,
    val startedAt: Long,
    val lines: List<String>,
    val segments: List<KeyLogExtractor.Segment>,
    val truncated: Boolean,
    val fallbackToLatestLog: Boolean
) {
    val keyLineCount: Int get() = segments.sumOf { it.lineCount }

    companion object {
        /** 从会话日志文本构建；行号天然从 1 开始，不含任何历史启动内容。 */
        fun from(
            scopeId: String,
            startedAt: Long,
            logText: String,
            truncated: Boolean = false,
            fallbackToLatestLog: Boolean = false
        ): CrashLogView {
            val lines = if (logText.isEmpty()) emptyList() else logText.lines()
            return CrashLogView(
                scopeId = scopeId,
                startedAt = startedAt,
                lines = lines,
                segments = KeyLogExtractor.extract(lines),
                truncated = truncated,
                fallbackToLatestLog = fallbackToLatestLog
            )
        }
    }
}
