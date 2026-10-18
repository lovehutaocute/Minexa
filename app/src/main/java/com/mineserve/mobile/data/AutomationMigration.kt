package com.mineserve.mobile.data

/**
 * 1.2.x → 1.3.0 的定时任务迁移。
 *
 * 旧版本的「每日定时开/停服」是 [McConfig] 上的四个固定字段（`dailyStartEnabled` 等），
 * 1.3.0 起界面入口改为「更多 → 自动化」，同一个能力由 [AutomationTask] 表达。
 *
 * 迁移必须做，否则升级后旧字段仍在：`BootReceiver` / `McForegroundService` 照旧注册
 * [ScheduleManager] 的闹钟、服务端照旧每天开停，而设置页已经没有那个开关了 ——
 * 用户既看不见也关不掉。
 *
 * [migrate] 是纯函数且幂等：只需要迁移时才会改配置，重复调用不会重复生成任务。
 */
object AutomationMigration {

    /** 配置里是否还存在「需要迁移」的旧版定时设置。 */
    fun pending(config: McConfig): Boolean =
        (config.dailyStartEnabled || config.dailyStopEnabled) && config.automationTasks.isEmpty()

    /**
     * 把旧版每日开/停服转成等价的两条定时任务，并清空旧字段。
     *
     * 只处理「一条自动化任务都没有」的配置：已经有了新任务说明用户已经在用新版，
     * 此时再补两条出来属于无中生有。
     *
     * [ScheduleManager] 的闹钟由调用方在落盘后用新配置重注册（`dailyXxxEnabled` 为 false
     * 即会取消旧闹钟）。
     */
    fun migrate(config: McConfig): McConfig {
        if (!pending(config)) return config
        val tasks = buildList {
            if (config.dailyStartEnabled) {
                add(
                    AutomationTask(
                        name = "每日开服",
                        type = AutomationTaskType.StartServer,
                        frequency = AutomationFrequency.EveryXDays,
                        dayInterval = 1,
                        hour = config.dailyStartHour,
                        minute = config.dailyStartMinute
                    )
                )
            }
            if (config.dailyStopEnabled) {
                add(
                    AutomationTask(
                        name = "每日停服",
                        type = AutomationTaskType.StopServer,
                        frequency = AutomationFrequency.EveryXDays,
                        dayInterval = 1,
                        hour = config.dailyStopHour,
                        minute = config.dailyStopMinute
                    )
                )
            }
        }
        return config.copy(
            automationTasks = tasks,
            dailyStartEnabled = false,
            dailyStopEnabled = false
        )
    }
}
