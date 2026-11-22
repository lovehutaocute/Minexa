package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.server.WorldManager
import com.mineserve.mobile.data.FileOps
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class WorldMaintenanceDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    worldManagerProvider: () -> WorldManager,
    private val serverDirFor: (String) -> File,
    private val activeDirName: () -> String?,
    private val requireServerStopped: (String) -> Boolean,
    private val loadWorlds: () -> Unit,
    private val loadJavaAddons: () -> Unit,
    private val reportMessage: (String) -> Unit,
    private val reportError: (String) -> Unit
) {
    private val worldManager by lazy(worldManagerProvider)

    /** 删除当前激活核心的世界文件夹（world / world_nether / world_the_end），不可恢复 */
    fun deleteWorldDirs() {
        // 必须拦住运行中的删除：服务端正持有 region 句柄持续写盘，目录被 unlink 后
        // 进程会继续写已删除的 inode，停止/崩溃时世界直接消失（不可恢复）。
        // createWorld / resetWorld / deleteCore 都有这道闸门，这里漏了。
        if (!requireServerStopped("删除世界目录")) return
        val dirName = activeDirName() ?: run {
            reportError(str(R.string.s249))
            return
        }
        scope.launch {
            try {
                val deleted = withContext(Dispatchers.IO) {
                    val base = serverDirFor(dirName)
                    var count = 0
                    for (name in listOf("world", "world_nether", "world_the_end")) {
                        val dir = File(base, name)
                        if (FileOps.existsNoFollow(dir) && FileOps.deleteRecursively(dir)) count++
                    }
                    count
                }
                reportMessage(str(R.string.ui_world_deleted, deleted))
            } catch (e: Exception) {
                reportError(str(R.string.ui_world_delete_failed))
            }
        }
    }

    /** 重命名世界（目录 + level.dat 的 LevelName 一起改）。 */
    fun renameWorld(worldName: String, newName: String) {
        val dirName = activeDirName() ?: return
        if (!requireServerStopped(str(R.string.wl_rename))) return
        scope.launch {
            try {
                val renamed = withContext(Dispatchers.IO) {
                    worldManager.renameJavaWorld(dirName, worldName, newName)
                }
                if (renamed != null) {
                    reportMessage(str(R.string.wl_renamed, renamed))
                    loadWorlds()
                } else {
                    reportError(str(R.string.wl_rename_failed))
                }
            } catch (e: Exception) {
                reportError("${str(R.string.wl_rename_failed)}：${e.message}")
            }
        }
    }

    /** 只重置一个维度（下界 / 末地），先备份。 */
    fun resetDimension(worldName: String, key: String) {
        val dirName = activeDirName() ?: return
        if (!requireServerStopped(str(R.string.wl_reset_dim))) return
        scope.launch {
            try {
                val result = worldManager.resetDimension(dirName, worldName, key)
                when {
                    result.backupFailed -> reportError(str(R.string.wl_backup_failed))
                    result.success -> {
                        reportMessage(str(R.string.wl_dim_reset_ok, key))
                        loadWorlds()
                        loadJavaAddons()
                    }
                    else -> reportError(str(R.string.wl_dim_reset_failed))
                }
            } catch (e: Exception) {
                reportError("${str(R.string.wl_dim_reset_failed)}：${e.message}")
            }
        }
    }

    /**
     * 重置世界：先自动导出一份安全备份，再删除目录，让服务端下次启动重新生成。
     *
     * 备份失败时不会删除任何文件（由 [WorldManager.resetWorld] 保证），这里按
     * backupFailed -> success 的顺序判断，避免把「没删成」提示成「已重置」，
     * 也避免把「已删除但没备份」提示成「已中止」。
     */
    fun resetWorld(worldName: String) {
        val dirName = activeDirName() ?: return
        if (!requireServerStopped("重置世界")) return
        scope.launch {
            try {
                val result = worldManager.resetWorld(dirName, worldName, backupFirst = true)
                val backupPath = result.backupPath
                when {
                    result.backupFailed -> reportError("未能在删除前创建安全备份，已中止重置（存档未改动）")
                    !result.success || backupPath == null -> reportError("重置失败：世界目录不存在或无法删除")
                    else -> reportMessage(
                        "世界「$worldName」已重置，备份保存在 ${java.io.File(backupPath).parentFile?.absolutePath ?: "导出目录"}"
                    )
                }
                loadWorlds()
            } catch (e: Exception) {
                reportError("重置失败：${e.message}")
            }
        }
    }

    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)
}
