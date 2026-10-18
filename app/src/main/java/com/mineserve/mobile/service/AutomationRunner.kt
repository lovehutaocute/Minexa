package com.mineserve.mobile.service

import android.content.Context
import com.mineserve.mobile.McApplication
import com.mineserve.mobile.R
import com.mineserve.mobile.data.AutomationTask
import com.mineserve.mobile.data.AutomationTaskType
import com.mineserve.mobile.data.InstalledCore
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.ServerEventNotifier
import com.mineserve.mobile.server.BackupManager
import com.mineserve.mobile.server.McServerController
import kotlinx.coroutines.delay

/**
 * 自动化定时任务的执行器：把一条 [AutomationTask] 翻译成对应的服务器动作。
 *
 * 由 [McForegroundService] 在服务作用域里调用（见 `runAutomationTask`），
 * 因此可以安全地做分钟级阻塞操作（启动服务端、打包备份、重启等待）——
 * 不会踩到 BroadcastReceiver 的时限。
 */
object AutomationRunner {

    suspend fun run(context: Context, task: AutomationTask, config: McConfig) {
        val app = McApplication.get(context)
        val repo = app.repository
        val termux = app.termuxRuntime
        val controller = McServerController(termux, repo)
        val core = config.installedCores.find { it.name == config.activeCoreName }

        fun requireCore(): InstalledCore =
            core ?: throw IllegalStateException(context.getString(R.string.auto_no_core))

        termux.emitLog("[auto] 执行定时任务「${task.name}」")

        when (task.type) {
            AutomationTaskType.StartServer -> {
                controller.start(config, userInitiated = false)
            }

            AutomationTaskType.StopServer -> {
                // 未运行则跳过，不视为失败
                runCatching { controller.stop() }
            }

            AutomationTaskType.RestartServer -> {
                requireCore()
                if (termux.isMcRunning()) {
                    termux.emitLog("[auto] 计划重启：10 秒后关闭服务器")
                    runCatching { termux.sendCommand("say ${context.getString(R.string.auto_restart_warning)}") }
                    delay(10_000L)
                    controller.stop()
                    var waited = 0
                    while (termux.isMcRunning() && waited < 30_000) {
                        delay(1_000L)
                        waited += 1_000
                    }
                }
                controller.start(config, userInitiated = false)
            }

            AutomationTaskType.WorldBackup -> {
                val c = requireCore()
                saveWorldIfRunning(termux, c)
                val path = BackupManager(termux).backupWorldToExternal(
                    c.dirName,
                    BackupManager.BackupOrigin.Automatic,
                    task.retentionCount
                )
                reportBackup(context, task, path)
            }

            AutomationTaskType.FullBackup -> {
                val c = requireCore()
                saveWorldIfRunning(termux, c)
                val path = BackupManager(termux).backupServerToExternal(
                    c.dirName,
                    c.core.displayName,
                    BackupManager.BackupOrigin.Automatic,
                    task.retentionCount
                )
                reportBackup(context, task, path)
            }

            AutomationTaskType.BroadcastMessage -> {
                if (task.command.isNotBlank() && termux.isMcRunning()) {
                    termux.sendCommand(normalizeBroadcast(task.command))
                }
            }

            AutomationTaskType.CustomCommand -> {
                if (task.command.isNotBlank() && termux.isMcRunning()) {
                    termux.sendCommand(task.command.removePrefix("/"))
                }
            }
        }
    }

    /** 服务器运行中时先让核心把世界写盘，再打包，避免备份到未落盘的数据。 */
    private suspend fun saveWorldIfRunning(termux: com.mineserve.mobile.runtime.TermuxRuntime, core: InstalledCore) {
        if (termux.isMcRunning()) {
            termux.sendCommand(core.core.consoleSaveCommand)
            delay(1_000L)
        }
    }

    /**
     * 广播内容归一化：`/say hi` 与 `say hi` 都视为 say 命令；裸文本补上 `say ` 前缀。
     */
    private fun normalizeBroadcast(raw: String): String {
        val text = raw.trim()
        return when {
            text.startsWith("/say ", ignoreCase = true) -> text.removePrefix("/")
            text.startsWith("say ", ignoreCase = true) -> text
            text.startsWith("/") -> text.removePrefix("/")
            else -> "say $text"
        }
    }

    private fun reportBackup(context: Context, task: AutomationTask, path: String?) {
        val app = McApplication.get(context)
        if (path == null) {
            ServerEventNotifier.notify(
                app,
                context.getString(R.string.auto_backup_fail_title),
                context.getString(R.string.auto_backup_fail_text, task.name),
                ServerEventNotifier.ID_SCHEDULE_FAIL,
                1
            )
            app.termuxRuntime.emitLog("[auto] 「${task.name}」备份失败：外部目录不可写或没有可备份的数据")
        } else {
            app.termuxRuntime.emitLog("[auto] 「${task.name}」备份完成: ${java.io.File(path).name}")
        }
    }
}
