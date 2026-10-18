package com.mineserve.mobile.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 1.2.x 旧版「每日定时开/停服」→ 1.3.0 自动化任务的迁移。
 *
 * 这条迁移不做的话，升级后旧闹钟还会照旧每天开停服，而界面上已经没有入口能关掉它。
 */
class AutomationMigrationTest {

    @Test
    fun `没有旧设置时原样返回`() {
        val config = McConfig()
        assertEquals(config, AutomationMigration.migrate(config))
        assertFalse(AutomationMigration.pending(config))
    }

    @Test
    fun `每日开停服迁移为两条每天触发的任务`() {
        val legacy = McConfig(
            dailyStartEnabled = true,
            dailyStartHour = 8,
            dailyStartMinute = 5,
            dailyStopEnabled = true,
            dailyStopHour = 23,
            dailyStopMinute = 30
        )

        val migrated = AutomationMigration.migrate(legacy)

        assertFalse(migrated.dailyStartEnabled)
        assertFalse(migrated.dailyStopEnabled)
        assertEquals(2, migrated.automationTasks.size)

        val start = migrated.automationTasks.single { it.type == AutomationTaskType.StartServer }
        assertEquals(AutomationFrequency.EveryXDays, start.frequency)
        assertEquals(1, start.dayInterval)
        assertEquals(8, start.hour)
        assertEquals(5, start.minute)
        assertTrue(start.enabled)

        val stop = migrated.automationTasks.single { it.type == AutomationTaskType.StopServer }
        assertEquals(23, stop.hour)
        assertEquals(30, stop.minute)
    }

    @Test
    fun `重复迁移不会重复生成任务`() {
        val once = AutomationMigration.migrate(McConfig(dailyStartEnabled = true))
        val twice = AutomationMigration.migrate(once)
        assertEquals(once, twice)
        assertEquals(1, twice.automationTasks.size)
    }

    @Test
    fun `已有自动化任务时不再从旧字段补任务`() {
        val existing = AutomationTask(name = "自定义备份", type = AutomationTaskType.WorldBackup)
        val config = McConfig(dailyStartEnabled = true, automationTasks = listOf(existing))

        val migrated = AutomationMigration.migrate(config)

        assertEquals(listOf(existing), migrated.automationTasks)
        // 已有新任务 = 用户已经在用新版，旧字段保持原样，交给用户自己决定
        assertTrue(migrated.dailyStartEnabled)
    }
}
