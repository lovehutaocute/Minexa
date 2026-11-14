package com.mineserve.mobile.runtime

import java.io.File

/**
 * 一次「启动 → 退出」的日志会话。
 *
 * 崩溃弹窗里的「关键日志」「全部日志」以及崩溃报告，全部只允许读这个会话的切片：
 *  - [startOffset] 在 spawn 进程前记录（文件当时的长度）；
 *  - [frozenEndOffset] 在进程退出时冻结，之后即使自动重启，弹窗内容也不会漂移。
 *
 * 为什么不用服务端自己的 `logs/latest.log`：那个文件由 log4j 维护，轮转/覆盖行为
 * 因核心而异（Forge / PowerNukkitX / PocketMine 各不相同），无法保证「只含本次启动」。
 * 应用侧捕获文件 `logs/mineserve-console.log` 只由本应用 append，配合偏移即可硬隔离。
 */
class LaunchSession(
    val id: String,
    val dirName: String,
    val logFile: File,
    val startOffset: Long,
    val startedAt: Long,
    /** 捕获文件不可用时回退 latest.log —— UI 必须提示「可能含少量历史行」 */
    val fellBackToLatestLog: Boolean = false,
    val javaVersion: String = ""
) {
    @Volatile
    var frozenEndOffset: Long? = null
        private set

    val isFrozen: Boolean get() = frozenEndOffset != null

    fun freeze(endOffset: Long) {
        if (frozenEndOffset == null) {
            frozenEndOffset = endOffset.coerceAtLeast(startOffset)
        }
    }

    fun effectiveEnd(fileLength: Long): Long = frozenEndOffset ?: fileLength
}
