package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.data.AutomationScheduler
import com.mineserve.mobile.data.AutomationTask
import com.mineserve.mobile.data.McConfig

internal class AutomationTasksDelegate(
    private val app: Context,
    private val updateAndSaveConfig: ((McConfig) -> McConfig) -> McConfig,
    private val reportError: (String) -> Unit
) {
    /**
     * 变更任务列表并落盘、重排下一次闹钟。
     *
     * 未排期的启用任务在此统一补上 [AutomationTask.nextRunMs]，保证调度器拿到的
     * 就是落盘后的最终值（与 `updateScheduleConfig` 同样的读改写策略，避免陈旧快照覆盖）。
     */
    private fun updateAutomationTasks(transform: (List<AutomationTask>) -> List<AutomationTask>) {
        val updated = try {
            updateAndSaveConfig { config ->
                config.copy(
                    automationTasks = AutomationScheduler.withNextRunsAssigned(transform(config.automationTasks))
                )
            }
        } catch (e: Exception) {
            reportError(app.getString(R.string.s191, e.message))
            return
        }
        AutomationScheduler.register(app, updated.automationTasks)
    }

    fun addAutomationTask(task: AutomationTask) =
        updateAutomationTasks { it + task.copy(nextRunMs = 0L) }

    fun updateAutomationTask(task: AutomationTask) = updateAutomationTasks { list ->
        list.map { if (it.id == task.id) task.copy(nextRunMs = 0L) else it }
    }

    fun deleteAutomationTask(id: String) = updateAutomationTasks { list -> list.filterNot { it.id == id } }

    fun setAutomationTaskEnabled(id: String, enabled: Boolean) = updateAutomationTasks { list ->
        list.map { if (it.id == id) it.copy(enabled = enabled, nextRunMs = if (enabled) 0L else it.nextRunMs) else it }
    }

    fun runAutomationTaskNow(id: String) {
        com.mineserve.mobile.service.McForegroundService.enqueueAutomationTask(app, id)
    }
}
