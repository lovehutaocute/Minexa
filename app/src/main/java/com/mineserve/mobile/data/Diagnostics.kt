package com.mineserve.mobile.data

/** A non-destructive runtime and active-server health check. */
enum class DiagnosticStatus { Pass, Warning, Failed, NotApplicable, Running }

data class DiagnosticCheck(
    val id: String,
    val title: String,
    val detail: String,
    val status: DiagnosticStatus,
    val repairable: Boolean = false
)

data class DiagnosticReport(
    val checks: List<DiagnosticCheck> = emptyList(),
    val generatedAtMs: Long = 0L,
    val isRunning: Boolean = false
) {
    val issueCount: Int
        get() = checks.count { it.status == DiagnosticStatus.Warning || it.status == DiagnosticStatus.Failed }
}

data class ServerResourceStats(
    val processMemoryMb: Long? = null,
    /** 服务端进程 CPU 占用率（%），按可用核心数归一化到 0-100；未运行时为 null。 */
    val cpuPercent: Int? = null,
    /**
     * 本机整体 CPU 总使用率（%），0-100，口径为「所有核心的非空闲时间占比」。
     *
     * 与 [cpuPercent] 刻意分开：后者只统计服务端进程，用于悬浮窗/桌面组件/MCP 等
     * 「服务端在吃多少 CPU」的场景；本字段是整机读数，概览页 Hero 卡片用它，
     * 且**服务端未运行时也照常有值**。不做核数归一化。
     */
    val deviceCpuPercent: Int? = null,
    val availableBytes: Long? = null,
    val directoryBytes: Long? = null,
    val javaAvailable: Boolean = false,
    val sampledAtMs: Long = 0L
)
