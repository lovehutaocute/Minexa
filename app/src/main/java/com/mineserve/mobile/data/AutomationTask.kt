package com.mineserve.mobile.data

import kotlinx.serialization.Serializable
import java.util.Calendar
import java.util.UUID

/**
 * 自动化定时任务的动作类型。
 *
 * 前五种对齐 MineServe 的定时任务能力（世界备份 / 完整备份 / 重启 / 广播 / 自定义命令），
 * [StartServer] 与 [StopServer] 用于承接原「每日定时开/停服」，保证旧能力不丢失。
 */
@Serializable
enum class AutomationTaskType {
    StartServer,
    StopServer,
    WorldBackup,
    FullBackup,
    RestartServer,
    BroadcastMessage,
    CustomCommand
}

/**
 * 定时任务的触发频率。
 *
 * [EveryXDays] 的 `dayInterval == 1` 即「每天定时」；不再单列 Daily，避免两种模式行为重合。
 */
@Serializable
enum class AutomationFrequency {
    /** 固定间隔（分钟） */
    Interval,
    /** 每 N 天的某一时刻（N=1 即每天） */
    EveryXDays,
    /** 每周的固定几天（可选每 N 周） */
    WeeklyDays,
    /** 5 段式 Cron 表达式 */
    CustomCron
}

/**
 * 一条自动化定时任务。
 *
 * 持久化在 [McConfig.automationTasks]（JSON），字段带默认值，旧配置反序列化不受影响。
 * [lastRunMs] / [nextRunMs] 记录执行与下次触发时间，由 [AutomationScheduler] 维护：
 * `nextRunMs <= 0` 表示尚未排期，需要时再计算。
 *
 * 注意：`daysOfWeek` 使用 [Calendar] 的常量（SUNDAY=1 … SATURDAY=7）。
 */
@Serializable
data class AutomationTask(
    val id: String = UUID.randomUUID().toString().take(8),
    val name: String = "",
    val enabled: Boolean = true,
    val type: AutomationTaskType = AutomationTaskType.WorldBackup,
    val frequency: AutomationFrequency = AutomationFrequency.Interval,
    /** 间隔模式的周期（分钟），默认 6 小时 */
    val intervalMinutes: Int = 360,
    /** 每日/每周模式的执行小时（0-23） */
    val hour: Int = 4,
    /** 每日/每周模式的执行分钟（0-59） */
    val minute: Int = 0,
    /** 每 N 天执行一次（N>=1），仅 [AutomationFrequency.EveryXDays] 使用 */
    val dayInterval: Int = 1,
    /** 每周执行的日子（Calendar.SUNDAY..SATURDAY），仅 [AutomationFrequency.WeeklyDays] 使用 */
    val daysOfWeek: List<Int> = emptyList(),
    /** 每 N 周执行一次（N>=1），仅 [AutomationFrequency.WeeklyDays] 使用 */
    val weekInterval: Int = 1,
    /** 5 段式 Cron 表达式，仅 [AutomationFrequency.CustomCron] 使用 */
    val cron: String = "",
    /** 广播内容或自定义控制台命令 */
    val command: String = "",
    /** 备份保留数量（0 = 全部保留），仅备份类任务使用 */
    val retentionCount: Int = 5,
    /** 上次执行时间（毫秒） */
    val lastRunMs: Long = 0L,
    /** 下次触发时间（毫秒），<=0 表示未排期 */
    val nextRunMs: Long = 0L
)
