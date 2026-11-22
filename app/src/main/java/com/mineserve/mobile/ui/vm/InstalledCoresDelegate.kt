package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.data.FileOps
import com.mineserve.mobile.data.ServerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class InstalledCoresDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    private val repo: ServerRepository,
    private val requireServerStopped: (String) -> Boolean,
    private val termux3Log: (String) -> Unit,
    private val reportMessage: (String) -> Unit,
    private val reportError: (String) -> Unit
) {
    /**
     * 删除一个已安装核心（磁盘目录 + 配置登记）。
     *
     * ## 必须用仓库的权威内存态，不能用 UI 侧快照
     * 原先这里读的是 `McViewModel.config.value`（`WhileSubscribed(5s)` 的 StateFlow）。
     * 用户离开下载页超过 5 秒后该流停止收集，`config.value` 会停留在旧值；
     * 此时执行删除，写回的就是一份「把已删核心又带回来」的陈旧列表。
     *
     * 现在改为 [ServerRepository.updateAndSaveConfig]，读改写全程在仓库的
     * 权威内存态上原子完成，不存在陈旧快照覆盖新状态的窗口。
     *
     * ## 目录不存在时也要清理登记
     * 磁盘目录可能因删除中断、外部文件管理器操作、或历史 bug 残留而先一步消失。
     * 这时若直接报错返回，配置里的幽灵记录会永远清不掉（本函数的存在意义之一
     * 就是让用户能手动清掉这类残留）。因此：目录不存在视为「已经删干净」，
     * 继续清理登记信息。
     */
    fun deleteCore(name: String) {
        // 兜底：下面是对含世界存档的目录做整目录 deleteRecursively()，运行中执行会得到部分删除的存档且不可逆
        if (!requireServerStopped("删除服务器核心")) return
        scope.launch {
            try {
                val cfg = repo.currentConfig()
                val core = cfg.installedCores.find { it.name == name }
                    ?: throw RuntimeException(str(R.string.msg_core_not_found, name))
                val dir = repo.termuxRuntime.serverDirFor(core.dirName)
                // 不能用 exists() + deleteRecursively()：FUSE 上目录会在两步之间消失或被换掉，
                // 而 Kotlin 的 deleteRecursively 对「根不是目录」是 assert（AssertionError，
                // catch(Exception) 拦不住）—— 2026-11-13 的实机崩溃就是这么来的。
                val deleted = withContext(Dispatchers.IO) { FileOps.deleteRecursively(dir) }
                if (!deleted && FileOps.existsNoFollow(dir)) {
                    reportError(str(R.string.s299, dir.absolutePath))
                    return@launch
                }
                if (!FileOps.existsNoFollow(dir)) {
                    termux3Log("deleteCore: 目录已不存在，仅清理登记 ${dir.absolutePath}")
                }
                // 原子读改写 + 落盘；based on 权威态，不会把陈旧列表写回去
                repo.updateAndSaveConfig { current ->
                    val updated = current.installedCores.filter { it.name != name }
                    current.copy(
                        installedCores = updated,
                        activeCoreName = if (current.activeCoreName == name) {
                            updated.firstOrNull()?.name
                        } else {
                            current.activeCoreName
                        }
                    )
                }
                reportMessage(str(R.string.s300, name))
            } catch (e: Exception) {
                reportError(str(R.string.s301, e.message))
            }
        }
    }

    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)
}
