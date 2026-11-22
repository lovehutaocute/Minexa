package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.StorageLocation
import com.mineserve.mobile.server.ServerImporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ServerImportDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    private val serverImporter: ServerImporter,
    private val updateConfig: ((McConfig) -> McConfig) -> Unit,
    private val reportMessage: (String) -> Unit,
    private val reportError: (String) -> Unit
) {
    private val _isImportingServer = MutableStateFlow(false)
    val isImportingServer: StateFlow<Boolean> = _isImportingServer.asStateFlow()

    private val _importProgress = MutableStateFlow<Float?>(null)
    val importProgress: StateFlow<Float?> = _importProgress.asStateFlow()

    /** 从外部存储文件夹（SAF tree）导入为新的服务器；displayName 为空时自动解析文件夹名 */
    fun importServerFromFolder(uri: android.net.Uri, displayName: String? = null) {
        scope.launch {
            _isImportingServer.value = true
            _importProgress.value = null
            try {
                val imported = withContext(Dispatchers.IO) {
                    serverImporter.importFromFolder(uri, displayName) { done, total ->
                        _importProgress.value = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
                    }
                }
                registerImportedServer(imported)
                reportMessage(importServerMessage(imported))
            } catch (e: Exception) {
                reportError(str(R.string.import_server_failed, e.message))
            } finally {
                _isImportingServer.value = false
                _importProgress.value = null
            }
        }
    }

    /** 从外部存储压缩包（zip/tar/tar.gz/tar.xz/tar.bz2/7z）导入为新的服务器；displayName 为空时自动解析 */
    fun importServerFromArchive(uri: android.net.Uri, displayName: String? = null) {
        scope.launch {
            _isImportingServer.value = true
            _importProgress.value = null
            try {
                val imported = withContext(Dispatchers.IO) {
                    serverImporter.importFromArchive(uri, displayName) { done, total ->
                        _importProgress.value = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
                    }
                }
                registerImportedServer(imported)
                reportMessage(importServerMessage(imported))
            } catch (e: Exception) {
                reportError(str(R.string.import_server_failed, e.message))
            } finally {
                _isImportingServer.value = false
                _importProgress.value = null
            }
        }
    }

    /** 从单个服务端 JAR 创建标准服务器目录后导入。 */
    fun importServerFromJar(uri: android.net.Uri, displayName: String? = null) {
        scope.launch {
            _isImportingServer.value = true
            _importProgress.value = null
            try {
                val imported = withContext(Dispatchers.IO) {
                    serverImporter.importFromJar(uri, displayName) { done, total ->
                        _importProgress.value = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
                    }
                }
                registerImportedServer(imported)
                reportMessage(importServerMessage(imported))
            } catch (e: Exception) {
                reportError(str(R.string.import_server_failed, e.message))
            } finally {
                _isImportingServer.value = false
                _importProgress.value = null
            }
        }
    }

    /** 为导入确认对话框预填名称：kind 为 folder / archive / jar */
    suspend fun proposeImportName(kind: String, uri: android.net.Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            when (kind) {
            "folder" -> serverImporter.proposeFolderName(uri)
            "jar" -> serverImporter.proposeJarName(uri)
            "modpack" -> serverImporter.proposeArchiveName(uri)
            else -> serverImporter.proposeArchiveName(uri)
            }
        }.getOrNull()
    }

    /** 把导入的服务器注册进已安装核心列表并设为当前选中（核心/版本为自动识别结果） */
    private fun registerImportedServer(imported: ServerImporter.ImportedServer) {
        // 导入的服务器一律在统一存储目录（导入器保证），因此位置固定为 Internal。
        updateConfig {
            val core = imported.core ?: com.mineserve.mobile.data.ServerCore.Unknown
            it.copy(
                installedCores = it.installedCores.filter { c -> c.dirName != imported.dirName } +
                    com.mineserve.mobile.data.InstalledCore(
                        name = imported.displayName,
                        core = core,
                        version = imported.version ?: str(R.string.ver_imported),
                        dirName = imported.dirName,
                        serverFile = imported.serverFile,
                        location = StorageLocation.Internal
                    ),
                activeCoreName = imported.displayName
            )
        }
    }

    /** 导入完成提示（含自动识别的核心与版本） */
    private fun importServerMessage(imported: ServerImporter.ImportedServer): String {
        val coreLabel = if (imported.core != null && imported.core != com.mineserve.mobile.data.ServerCore.Unknown) {
            listOf(imported.core.displayName, imported.version).filter { !it.isNullOrBlank() }.joinToString(" ")
        } else null
        val suffix = if (coreLabel != null) {
            str(R.string.import_server_core, coreLabel)
        } else {
            str(R.string.import_server_core_unknown)
        }
        return str(R.string.import_server_done, imported.displayName) + "（" + suffix + "）"
    }

    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)
}
