package com.mineserve.mobile.ui.vm

import android.content.Context
import android.net.Uri
import com.mineserve.mobile.R
import com.mineserve.mobile.server.JavaResourcePack
import com.mineserve.mobile.server.JavaWorlds
import com.mineserve.mobile.server.WorldManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class JavaAddonsDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    worldManagerProvider: () -> WorldManager,
    private val activeDirName: () -> String?,
    private val refreshActiveCore: () -> Unit,
    private val isJavaCore: () -> Boolean,
    private val setJavaDimensions: (List<JavaWorlds.DimensionDir>) -> Unit,
    private val requireServerStopped: (String) -> Boolean,
    private val reportMessage: (String) -> Unit,
    private val reportError: (String) -> Unit
) {
    private val worldManager by lazy(worldManagerProvider)

    private val _javaDataPacks = MutableStateFlow<List<JavaWorlds.DataPack>>(emptyList())
    val javaDataPacks: StateFlow<List<JavaWorlds.DataPack>> = _javaDataPacks.asStateFlow()

    private val _javaAddonsLoading = MutableStateFlow(false)
    val javaAddonsLoading: StateFlow<Boolean> = _javaAddonsLoading.asStateFlow()

    /** 资源包配置（Java 侧单包模型）；基岩系或读不到时为 null。 */
    private val _resourcePack = MutableStateFlow<JavaResourcePack.Config?>(null)
    val resourcePack: StateFlow<JavaResourcePack.Config?> = _resourcePack.asStateFlow()

    /** 资源包配置的静态校验问题（空 = 没有明显问题）。 */
    private val _resourcePackIssues = MutableStateFlow<List<String>>(emptyList())
    val resourcePackIssues: StateFlow<List<String>> = _resourcePackIssues.asStateFlow()

    /** 读 Java 侧的世界附加内容（数据包 + 资源包 + 维度）。 */
    fun loadJavaAddons() {
        refreshActiveCore()
        val dirName = activeDirName() ?: return
        if (!isJavaCore()) return
        scope.launch {
            _javaAddonsLoading.value = true
            try {
                val world = withContext(Dispatchers.IO) { worldManager.readLevelName(dirName) }
                _javaDataPacks.value = withContext(Dispatchers.IO) {
                    runCatching { worldManager.scanJavaDataPacks(dirName, world) }.getOrDefault(emptyList())
                }
                setJavaDimensions(withContext(Dispatchers.IO) {
                    runCatching { worldManager.javaDimensionDirs(dirName, world) }.getOrDefault(emptyList())
                })
                val cfg = withContext(Dispatchers.IO) {
                    runCatching { worldManager.readResourcePack(dirName) }.getOrNull()
                }
                _resourcePack.value = cfg
                _resourcePackIssues.value = cfg?.let { JavaResourcePack.issues(it) }.orEmpty()
            } catch (e: Exception) {
                reportError("${str(R.string.jv_load_failed)}：${e.message}")
            } finally {
                _javaAddonsLoading.value = false
            }
        }
    }

    /** 启用 / 停用一个世界级数据包（改 `level.dat:DataPacks`）。 */
    fun setJavaDataPackEnabled(pack: JavaWorlds.DataPack, enabled: Boolean) {
        val dirName = activeDirName() ?: return
        if (!requireServerStopped(str(if (enabled) R.string.jv_enable else R.string.jv_disable))) return
        scope.launch {
            try {
                val world = withContext(Dispatchers.IO) { worldManager.readLevelName(dirName) }
                val ok = withContext(Dispatchers.IO) {
                    worldManager.setJavaDataPackEnabled(dirName, world, pack, enabled)
                }
                if (ok) {
                    reportMessage(
                        str(if (enabled) R.string.jv_enabled else R.string.jv_disabled, pack.name)
                    )
                    loadJavaAddons()
                } else {
                    reportError(str(R.string.jv_write_failed))
                }
            } catch (e: Exception) {
                reportError("${str(R.string.jv_write_failed)}：${e.message}")
            }
        }
    }

    /** 从系统选择器导入数据包 zip（拷进 `world/datapacks/`）。 */
    fun importJavaDataPackFromUri(uri: Uri, displayName: String) {
        val dirName = activeDirName() ?: return
        if (!requireServerStopped(str(R.string.jv_import))) return
        scope.launch {
            var temp: java.io.File? = null
            try {
                temp = withContext(Dispatchers.IO) {
                    val f = java.io.File(app.cacheDir, "datapack_${System.currentTimeMillis()}.zip")
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        f.outputStream().use { output -> input.copyTo(output) }
                    }
                    f.takeIf { it.isFile && it.length() > 0 }
                }
                if (temp == null) {
                    reportError(str(R.string.jv_import_failed))
                    return@launch
                }
                val world = withContext(Dispatchers.IO) { worldManager.readLevelName(dirName) }
                val name = withContext(Dispatchers.IO) {
                    worldManager.installJavaDataPack(dirName, world, temp)
                }
                if (name != null) {
                    reportMessage(str(R.string.jv_import_ok, name))
                    loadJavaAddons()
                } else {
                    reportError(str(R.string.jv_import_failed))
                }
            } catch (e: Exception) {
                reportError("${str(R.string.jv_import_failed)}：${e.message}")
            } finally {
                runCatching { temp?.delete() }
            }
        }
    }

    /** 保存资源包配置（URL / SHA-1 / ID / 提示语 / 是否强制）。 */
    fun saveResourcePack(config: JavaResourcePack.Config) {
        val dirName = activeDirName() ?: return
        val url = JavaResourcePack.normalizeUrl(config.url)
        if (url == null) { reportError(str(R.string.jv_rp_bad_url)); return }
        val sha1 = JavaResourcePack.normalizeSha1(config.sha1)
        if (sha1 == null) { reportError(str(R.string.jv_rp_bad_sha1)); return }
        if (!requireServerStopped(str(R.string.jv_rp_title))) return
        scope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    worldManager.writeResourcePack(dirName, config.copy(url = url, sha1 = sha1))
                }
                if (ok) {
                    _resourcePack.value = config.copy(url = url, sha1 = sha1)
                    _resourcePackIssues.value = JavaResourcePack.issues(config.copy(url = url, sha1 = sha1))
                    reportMessage(str(R.string.jv_rp_saved))
                } else {
                    reportError(str(R.string.jv_write_failed))
                }
            } catch (e: Exception) {
                reportError("${str(R.string.jv_write_failed)}：${e.message}")
            }
        }
    }

    /** 从用户选的文件算 SHA-1 并填进资源包配置。 */
    fun fillResourcePackSha1(uri: Uri) {
        scope.launch {
            try {
                val hash = withContext(Dispatchers.IO) {
                    val f = java.io.File(app.cacheDir, "rp_${System.currentTimeMillis()}.zip")
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        f.outputStream().use { output -> input.copyTo(output) }
                    }
                    try {
                        JavaResourcePack.sha1(f)
                    } finally {
                        f.delete()
                    }
                }
                val current = _resourcePack.value ?: JavaResourcePack.Config()
                if (hash == null) {
                    reportError(str(R.string.jv_rp_sha1_failed))
                } else {
                    _resourcePack.value = current.copy(sha1 = hash)
                    _resourcePackIssues.value = JavaResourcePack.issues(current.copy(sha1 = hash))
                    reportMessage(str(R.string.jv_rp_sha1_filled))
                }
            } catch (e: Exception) {
                reportError("${str(R.string.jv_rp_sha1_failed)}：${e.message}")
            }
        }
    }

    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)
}
