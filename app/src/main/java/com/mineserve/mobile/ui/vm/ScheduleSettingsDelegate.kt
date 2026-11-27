package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.ScheduleManager

internal class ScheduleSettingsDelegate(
    private val app: Context,
    private val updateAndSaveConfig: ((McConfig) -> McConfig) -> McConfig,
    private val reportError: (String) -> Unit
) {
    fun setDailyStartEnabled(enabled: Boolean) =
        updateScheduleConfig { it.copy(dailyStartEnabled = enabled) }

    fun setDailyStartTime(hour: Int, minute: Int) = updateScheduleConfig {
        it.copy(dailyStartHour = hour.coerceIn(0, 23), dailyStartMinute = minute.coerceIn(0, 59))
    }

    fun setDailyStopEnabled(enabled: Boolean) =
        updateScheduleConfig { it.copy(dailyStopEnabled = enabled) }

    fun setDailyStopTime(hour: Int, minute: Int) = updateScheduleConfig {
        it.copy(dailyStopHour = hour.coerceIn(0, 23), dailyStopMinute = minute.coerceIn(0, 59))
    }

    /** 变更定时计划时同步保存并重注册下一次闹钟。 */
    private fun updateScheduleConfig(transform: (McConfig) -> McConfig) {
        // 基于仓库权威内存态读改写并原子入队，不用 UI 侧 config.value（可能是陈旧快照，
        // 写回会覆盖用户此间做的其他修改）。ScheduleManager 用的是落盘后的最终值。
        val updated = try {
            updateAndSaveConfig(transform)
        } catch (e: Exception) {
            reportError(app.getString(R.string.s191, e.message))
            return
        }
        ScheduleManager.register(app, updated)
    }
}
