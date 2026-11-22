package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.data.FileOps
import com.mineserve.mobile.data.ServerRepository
import com.mineserve.mobile.data.StorageLocation
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

    @Volatile private var scanningServers = false

    /**
     * 扫描 servers 目录，自动登记未注册的服务器文件夹。
     *
     * 覆盖 MT 管理器等外部方式直接复制进目录的场景；识别不出核心时按
     * 「导入/未知核心」登记（降低识别门槛）。
     *
     * ## 为什么要跳过空目录
     * 早先只判断「目录名是否已登记」，不判断目录内容，结果**空目录也会被
     * 登记成一个核心**。空目录的常见来源是删除流程中断或历史残留，
     * 把它登记进列表只会让用户看到一个点进去什么都没有的幽灵条目，
     * 而且它会带着 `Unknown` 核心类型、参与「当前选中核心」等逻辑。
     *
     * 现在明确区分两种情况：
     *   - **空目录**（含只有隐藏文件/子目录壳）：不登记，仅记日志。用户可用
     *     文件管理页自行清理；App 不擅自删用户目录。
     *   - **有文件但识别不出核心**：仍然登记为「未知核心」。这可能是用户
     *     手动塞进去的整合包，贸然忽略反而会让人以为导入失败。
     */
    fun scanUnregisteredServers() {
        if (scanningServers) return
        scanningServers = true
        scope.launch {
            try {
                // 先与磁盘对齐：被外部删除的核心目录在这一步从列表移除（见 syncInstalledCoresWithDisk）
                pruneMissingCores()
                // 只扫统一存储目录：旧私有目录已不再作为服务器存放位置，
                // 它的内容走「迁移」流程（LegacyServerMigration），不在这里登记。
                val scanned = withContext(Dispatchers.IO) {
                    com.mineserve.mobile.data.ServerStorage.serversDir(app)
                        .listFiles { f -> f.isDirectory }
                        ?.sortedBy { it.name.lowercase() }.orEmpty()
                }
                if (scanned.isEmpty()) return@launch
                val cfg = repo.currentConfig()
                val known = cfg.installedCores.map { it.dirName }.toHashSet()
                // 待迁移的旧记录不算「已知」以外的异常：它们的 dirName 可能和这里的目录名撞上，
                // 但 location 仍为 Legacy，由迁移流程负责，不在扫描里改。
                val fresh = scanned.filter { it.name !in known }
                if (fresh.isEmpty()) return@launch

                // 空目录：跳过登记。判据是「整棵子树里一个文件都没有」——
                // 只要里面有 1 个文件（哪怕是不认识的 .dat），就按有效核心目录处理。
                val (emptyDirs, realDirs) = withContext(Dispatchers.IO) {
                    fresh.partition { dir -> !com.mineserve.mobile.data.ServerStorage.containsAnyFile(dir) }
                }
                if (emptyDirs.isNotEmpty()) {
                    termux3Log(
                        "扫描跳过 ${emptyDirs.size} 个空目录（未登记）: " +
                            emptyDirs.joinToString(", ") { it.name }
                    )
                }
                if (realDirs.isEmpty()) {
                    reportMessage(
                        str(R.string.msg_scan_skipped_empty, emptyDirs.size)
                    )
                    return@launch
                }

                val registered = realDirs.map { dir ->
                    val detected = withContext(Dispatchers.IO) {
                        com.mineserve.mobile.server.ServerCoreDetector.detect(dir)
                    }
                    com.mineserve.mobile.data.InstalledCore(
                        name = dir.name,
                        core = detected.core ?: com.mineserve.mobile.data.ServerCore.Unknown,
                        version = detected.version ?: str(R.string.ver_imported),
                        dirName = dir.name,
                        serverFile = detected.serverFile,
                        location = StorageLocation.Internal
                    )
                }
                val registeredNames = registered.map { it.dirName }.toHashSet()
                repo.updateAndSaveConfig { current ->
                    // 二次去重：登记期间可能有别处已登记同名目录，避免出现重复条目
                    val merged = current.installedCores +
                        registered.filter { it.dirName !in current.installedCores.map { c -> c.dirName } }
                    current.copy(installedCores = merged)
                }
                reportMessage(str(R.string.msg_scan_registered, registeredNames.size))
            } catch (e: Exception) {
                reportError(str(R.string.s191, e.message))
            } finally {
                scanningServers = false
            }
        }
    }

    /**
     * 已安装核心与磁盘对齐：**外部删除同步**。
     *
     * 触发点有三处，覆盖用户能遇到的全部时机：
     *  - 回到前台（McApp 的 LifecycleResumeEffect）—— 用户刚从文件管理器删完目录回来；
     *  - 进入下载页（DownloadScreen 的 LaunchedEffect）—— 列表就在这一页；
     *  - 扫描未登记目录（[scanUnregisteredServers]）—— 兜底，App 内部的刷新入口都过这里。
     *
     * 只做「移除登记」，绝不创建或删除任何目录：目录是用户的，App 不擅自补建。
     */
    fun syncInstalledCoresWithDisk() {
        scope.launch {
            runCatching { pruneMissingCores() }
        }
    }

    /**
     * 移除「目录已不存在 / 目录里一个文件都没有」的核心登记，返回移除条数。
     *
     * 判据与扫描保持一致：整棵子树里没有任何文件即视为空壳目录。
     * 运行中的核心跳过：目录被外部删掉但进程还活着时，登记是停止/崩溃处理
     * 定位 `dirName` 的唯一依据，等它停下来再清。
     */
    private suspend fun pruneMissingCores(): Int {
        val cfg = repo.currentConfig()
        val candidates = cfg.installedCores.filter { it.location == StorageLocation.Internal }
        if (candidates.isEmpty()) return 0
        val missing = withContext(Dispatchers.IO) {
            candidates.filter { core ->
                !repo.termuxRuntime.isMcRunningIn(core.dirName) &&
                    !com.mineserve.mobile.data.ServerStorage.dirExistsWithContent(app, core.dirName)
            }.map { it.name }.toSet()
        }
        if (missing.isEmpty()) return 0
        repo.updateAndSaveConfig { current ->
            val updated = current.installedCores.filter { it.name !in missing }
            val activeStillExists = current.activeCoreName?.let { it !in missing } == true
            current.copy(
                installedCores = updated,
                activeCoreName = if (activeStillExists) current.activeCoreName
                else updated.firstOrNull()?.name
            )
        }
        termux3Log(
            "外部删除同步：移除 ${missing.size} 个已不存在或为空的核心 -> " + missing.joinToString(", ")
        )
        reportMessage(str(R.string.msg_cores_synced_external, missing.size))
        return missing.size
    }

    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)
}
