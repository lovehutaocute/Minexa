package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.server.McServerController
import com.mineserve.mobile.ui.McViewModel.DownloadProgress
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class CoreDownloadDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    private val config: StateFlow<McConfig>,
    private val controller: McServerController,
    private val serverDirFor: (String) -> File,
    private val reportMessage: (String) -> Unit,
    private val reportError: (String) -> Unit
) {
    /** 服务端核心下载中 */
    private val _isDownloadingCore = MutableStateFlow(false)
    val isDownloadingCore: StateFlow<Boolean> = _isDownloadingCore.asStateFlow()

    private val _downloadProgress = MutableStateFlow(DownloadProgress())
    val downloadProgress: StateFlow<DownloadProgress> = _downloadProgress.asStateFlow()

    /** 下载服务端核心（使用自定义名称，保存到独立目录），成功返回 true */
    fun downloadCore(customName: String) {
        if (_isDownloadingCore.value) return
        if (customName.isBlank()) {
            reportError(str(R.string.s295))
            return
        }
        // 重名拦截：重名会让列表里出现两个无法区分的条目，且历史上会共用同一目录。
        // 这里在下载前直接拒绝，并提示改用别的名字（目录名即便被去重，显示名仍会撞车）。
        controller.duplicateNameOf(customName, config.value)?.let { existing ->
            reportError(str(R.string.dl_duplicate_name, existing))
            return
        }
        // 版本为空 = 刚切过核心还没重新选。放行只会拼出一个 404 的下载地址，
        // 用户看到的是「下载失败」，真正的原因却在上一屏。
        if (config.value.mcVersion.isBlank()) {
            reportError(str(R.string.msg_version_required))
            return
        }
        _isDownloadingCore.value = true
        _downloadProgress.value = DownloadProgress()
        scope.launch {
            try {
                controller.downloadCore(config.value, customName.trim()) { downloaded, total, speed ->
                    _downloadProgress.value = DownloadProgress(downloaded, total, speed)
                }
                reportMessage(str(R.string.s296, customName.trim()))
            } catch (e: Exception) {
                reportError(str(R.string.s297, e.message))
            } finally {
                _isDownloadingCore.value = false
                _downloadProgress.value = DownloadProgress()
            }
        }
    }

    /** 校验已安装核心；未知核心只检查原文件，绝不自动下载或覆盖。 */
    fun verifyOrRepairCore(dirName: String) {
        val cfg = config.value
        val core = cfg.installedCores.firstOrNull { it.dirName == dirName } ?: return
        // BDS 的「核心」不是一个 JAR：它是官方 zip 解开的主程序 + 一批 x86_64 动态库
        // + 默认配置，用「jar manifest 是否可读」当判据必然误判成损坏，随后的「修复」
        // 会重下整包、把用户改过的 server.properties / 白名单覆盖掉。
        // 因此直接拒绝，并说明该走哪条恢复路径。
        if (core.core == com.mineserve.mobile.data.ServerCore.Bds) {
            reportError(str(R.string.err_core_bds_no_verify))
            return
        }
        scope.launch {
            try {
                val serverDir = serverDirFor(dirName)
                val entryName = core.serverFile?.trim()?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() }
                if (entryName == null) {
                    reportError(str(R.string.err_core_unknown_no_repair, "未指定启动文件"))
                    return@launch
                }
                val jar = java.io.File(serverDir, entryName)
                val valid = withContext(Dispatchers.IO) {
                    if (!jar.isFile) false
                    else if (core.core == com.mineserve.mobile.data.ServerCore.Unknown) true
                    else runCatching {
                        java.util.jar.JarFile(jar).use { jf -> jf.manifest != null }
                    }.getOrDefault(false)
                }
                if (valid) {
                    reportMessage(str(R.string.msg_core_verify_ok, core.name, formatCoreSize(jar.length())))
                    return@launch
                }
                if (core.core == com.mineserve.mobile.data.ServerCore.Unknown) {
                    reportError(str(R.string.err_core_unknown_no_repair, entryName))
                    return@launch
                }
                reportMessage(str(R.string.msg_core_repairing, core.name))
                _isDownloadingCore.value = true
                try {
                    val downloadConfig = cfg.copy(selectedCore = core.core, mcVersion = core.version)
                    withContext(Dispatchers.IO) {
                        controller.downloadCoreTo(jar.absolutePath, downloadConfig, dirName) { _, _, _ -> }
                    }
                    reportMessage(str(R.string.msg_core_repaired, core.name))
                } finally {
                    _isDownloadingCore.value = false
                }
            } catch (e: Exception) {
                _isDownloadingCore.value = false
                reportError(str(R.string.err_core_repair_fail, e.message))
            }
        }
    }

    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)
}
