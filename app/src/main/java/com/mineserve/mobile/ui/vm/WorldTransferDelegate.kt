package com.mineserve.mobile.ui.vm

import android.content.Context
import android.net.Uri
import com.mineserve.mobile.server.WorldManager
import com.mineserve.mobile.server.Worlds
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class WorldTransferDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    private val worldManager: WorldManager,
    private val activeDirName: () -> String?,
    private val requireServerStopped: (String) -> Boolean,
    private val exportPathToUri: (File, Uri) -> Unit,
    private val loadWorlds: () -> Unit,
    private val reportMessage: (String) -> Unit,
    private val reportError: (String) -> Unit
) {
    /** 世界导入包体积上限。导入会先整体落到 cacheDir，不设上限会把设备存储填满。 */
    private val maxWorldImportBytes = 2L * 1024 * 1024 * 1024

    /** 导出世界到用户选择的位置（先打包到导出目录，再复制到 SAF 目标）。 */
    fun exportWorldToUri(worldName: String, uri: Uri) {
        val dirName = activeDirName() ?: return
        if (!requireServerStopped("导出世界")) {
            // 兜底：万一还有别的入口绕过了前置检查，这里把空文档清掉，别留 0 字节垃圾
            runCatching {
                app.contentResolver.openOutputStream(uri)?.close()
                app.contentResolver.delete(uri, null, null)
            }
            return
        }
        scope.launch {
            try {
                val path = worldManager.exportWorld(dirName, worldName)
                if (path == null) {
                    reportError("导出失败：世界目录不存在或存储不可写")
                    return@launch
                }
                exportPathToUri(java.io.File(path), uri)
            } catch (e: Exception) {
                reportError("导出失败：${e.message}")
            }
        }
    }

    /**
     * 从用户选择的压缩包导入世界。
     *
     * 先复制到 cache 再解压：SAF 的 InputStream 只能顺序读一次，而解压需要随机读取
     * 条目头部（[Worlds.inspectZip] 会独立打开一次）。
     */
    fun importWorldFromUri(uri: Uri, preferredName: String) {
        val dirName = activeDirName() ?: return
        if (!requireServerStopped("导入世界")) return
        scope.launch {
            var temp: java.io.File? = null
            try {
                temp = withContext(Dispatchers.IO) {
                    val f = java.io.File(app.cacheDir, "world_import_${System.currentTimeMillis()}.zip")
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        f.outputStream().use { output ->
                            // 边拷边计数：先全量落盘再判断大小，设备已经被填满了
                            val buf = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val read = input.read(buf)
                                if (read < 0) break
                                total += read
                                if (total > maxWorldImportBytes) {
                                    throw RuntimeException("压缩包超过 2 GB，已中止导入")
                                }
                                output.write(buf, 0, read)
                            }
                        }
                    } ?: throw RuntimeException("无法读取所选文件")
                    f
                }
                val imported = withContext(Dispatchers.IO) {
                    worldManager.importWorld(dirName, temp, preferredName)
                }
                if (imported != null) {
                    reportMessage("世界已导入为「$imported」")
                    loadWorlds()
                } else {
                    reportError("导入失败：压缩包内没有找到世界（缺少 level.dat / region / db）")
                }
            } catch (e: Exception) {
                reportError("导入失败：${e.message}")
            } finally {
                // NonCancellable：协程已取消时 finally 里的 withContext 会立刻抛出取消异常，
                // 直接调用会让临时 zip 残留在 cacheDir 里。
                withContext(NonCancellable + Dispatchers.IO) { temp?.delete() }
            }
        }
    }
}
