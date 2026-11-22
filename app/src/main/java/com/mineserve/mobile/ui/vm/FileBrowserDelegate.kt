package com.mineserve.mobile.ui.vm

import android.content.Context
import android.net.Uri
import com.mineserve.mobile.R
import com.mineserve.mobile.data.FileOps
import com.mineserve.mobile.server.SafeTextFile
import com.mineserve.mobile.ui.McViewModel.FileEntry
import com.mineserve.mobile.ui.McViewModel.FileListState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class FileBrowserDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    private val openTextFile: (java.io.File) -> Unit,
    private val reportMessage: (String) -> Unit,
    private val reportError: (String) -> Unit
) {
    private val _fileList = MutableStateFlow<List<FileEntry>>(emptyList())
    val fileList: StateFlow<List<FileEntry>> = _fileList.asStateFlow()

    private val _currentPath = MutableStateFlow("")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    private val _fileListState = MutableStateFlow(FileListState.Loading)
    val fileListState: StateFlow<FileListState> = _fileListState.asStateFlow()

    /**
     * 文件管理根目录：只有统一存储目录一个根。
     *
     * 「内部 / 私有」切换控件已移除 —— 旧私有目录不再作为可浏览、可运行的位置，
     * 它的内容只能通过迁移流程搬到统一目录（见 `LegacyServerMigration`）。
     */
    val fileManagerRoot: java.io.File
        get() = com.mineserve.mobile.data.ServerStorage.serversDir(app)

    /**
     * 文件管理权限状态：未授权时界面必须给出「去授权」入口，并且**不执行任何文件操作**。
     *
     * 直接暴露 [StorageAccess.granted]（全局单例流）而不是自己再存一份：
     * 授权是在 Activity 的 onResume 里刷新的，界面若各自缓存会造成
     * 「从设置页返回后按钮还在、列表仍为空」的状态撕裂。
     */
    val storageGranted: StateFlow<Boolean>
        get() = com.mineserve.mobile.data.StorageAccess.granted

    fun refreshStoragePermission() {
        com.mineserve.mobile.data.StorageAccess.refresh(app)
    }

    /**
     * 进入文件管理页（或授权状态变化）时刷新。
     *
     * **不再以 Termux 环境就绪为前提**：浏览/上传/导出服务器目录跟 Java 运行时无关，
     * 初次安装（还没装 Termux 引导包）时文件页也必须能看到磁盘上已有的服务器目录。
     * 之前的门槛导致首启进入文件页永远是空列表 + 一句「请先安装环境」。
     */
    fun onFileManagerVisible() {
        val path = _currentPath.value
        if (path.isEmpty()) loadFilesRoot() else loadFiles(java.io.File(path))
    }

    /** 授权成功后（之前因无权限列表是空的），补一次加载 */
    fun onStoragePermissionGranted() {
        onFileManagerVisible()
    }

    /** 加载指定目录的文件列表 */
    fun loadFiles(path: java.io.File) {
        scope.launch {
            try {
                _currentPath.value = path.absolutePath
                val granted = com.mineserve.mobile.data.StorageAccess.hasAllFilesAccess(app)
                val result = withContext(Dispatchers.IO) {
                    val exists = path.isDirectory
                    val entries = if (!exists) {
                        emptyList()
                    } else {
                        // SimpleDateFormat 创建较贵，且非线程安全；在单线程的 map 内复用一个实例，
                        // 避免大目录（成百上千文件）下每个文件都 new 一次带来的 GC 与构造开销。
                        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                        path.listFiles()?.sortedWith(
                            compareByDescending<java.io.File> { it.isDirectory }
                                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                        )?.map { f ->
                            FileEntry(
                                name = f.name,
                                path = f.absolutePath,
                                isDirectory = f.isDirectory,
                                isEditable = f.isFile && SafeTextFile.isSupported(f),
                                sizeBytes = if (f.isFile) f.length() else 0L,
                                sizeText = if (f.isFile) formatBytes(f.length()) else "",
                                lastModified = f.lastModified(),
                                modifiedText = formatter.format(java.util.Date(f.lastModified()))
                            )
                        } ?: emptyList()
                    }
                    val state = when {
                        entries.isNotEmpty() -> FileListState.Ready
                        !exists -> if (granted) FileListState.RootMissing else FileListState.NoPermission
                        else -> FileListState.Empty
                    }
                    entries to state
                }
                _fileList.value = result.first
                _fileListState.value = result.second
            } catch (e: Exception) {
                reportError(str(R.string.s302, e.message))
            }
        }
    }

    /** 加载文件管理根目录（不依赖 Termux 环境） */
    fun loadFilesRoot() {
        loadFiles(fileManagerRoot)
    }

    /** 向上返回上级目录 */
    fun navigateUp() {
        val current = java.io.File(_currentPath.value)
        val parent = current.parentFile
        if (parent != null && parent.absolutePath.startsWith(fileManagerRoot.absolutePath)) {
            loadFiles(parent)
        }
    }

    /** 刷新当前目录文件列表；还没定位到目录时重新回到根目录 */
    fun refreshFiles() {
        val path = _currentPath.value
        if (path.isEmpty()) loadFilesRoot() else loadFiles(java.io.File(path))
    }

    /** 上传文件：从 Uri 复制到目标目录 */
    fun uploadFile(uri: android.net.Uri, targetDir: java.io.File) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val input = app.contentResolver.openInputStream(uri)
                        ?: throw RuntimeException(str(R.string.err_open_file))
                    val fileName = queryFileName(uri) ?: "uploaded_${System.currentTimeMillis()}"
                    val targetFile = java.io.File(targetDir, fileName)
                    input.use { ins ->
                        java.io.FileOutputStream(targetFile).use { fos ->
                            ins.copyTo(fos)
                        }
                    }
                }
                reportMessage(str(R.string.s307))
                loadFiles(java.io.File(currentPath.value))
            } catch (e: Exception) {
                reportError(str(R.string.s308, e.message))
            }
        }
    }

    /** 导出文件/文件夹到用户选择的 Uri（文件夹打包为 zip） */
    fun exportPathToUri(source: java.io.File, uri: android.net.Uri) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = app.contentResolver.openOutputStream(uri)
                        ?: throw RuntimeException(str(R.string.err_open_export))
                    output.use { os ->
                        if (source.isDirectory) {
                            // 文件夹打包为 zip
                            java.util.zip.ZipOutputStream(os).use { zos ->
                                source.walkTopDown().filter { it.isFile }.forEach { f ->
                                    val entryName = source.toURI().relativize(f.toURI()).path
                                    zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                                    f.inputStream().use { it.copyTo(zos) }
                                    zos.closeEntry()
                                }
                            }
                        } else {
                            source.inputStream().use { it.copyTo(os) }
                        }
                    }
                }
                // 写完主动扫一次 + 把落盘路径告诉用户。
                // SAF 写进 Downloads 的文件不会自动进 MediaStore，部分文件管理器
                // 要等扫描后才显示 —— 这正是「导出成功但找不到文件」的最常见成因。
                val path = scanExportedUri(uri)
                reportMessage(
                    if (path != null) str(R.string.export_saved_to, path) else str(R.string.s309)
                )
            } catch (e: Exception) {
                reportError(str(R.string.s257, e.message))
            }
        }
    }

    /**
     * 把 SAF 的 `content://` 目标反解成真实路径并触发媒体扫描。
     *
     * 只处理最常见的 `primary:`（主存储）docId；反解不出来时返回 null，
     * 调用方退回普通的「导出完成」提示——不猜路径，避免给出一个错的。
     */
    private fun scanExportedUri(uri: Uri): String? = runCatching {
        val docId = android.provider.DocumentsContract.getDocumentId(uri)
        val rel = docId.substringAfter(':', missingDelimiterValue = "")
        if (rel.isBlank()) return@runCatching null
        val file = java.io.File(android.os.Environment.getExternalStorageDirectory(), rel)
        if (!file.isFile) return@runCatching null
        android.media.MediaScannerConnection.scanFile(app, arrayOf(file.absolutePath), null, null)
        file.absolutePath
    }.getOrNull()

    /** 从 Uri 查询文件名 */
    private fun queryFileName(uri: android.net.Uri): String? {
        return try {
            val cursor = app.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && it.moveToFirst()) it.getString(nameIndex) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 删除文件或目录 */
    fun deleteFile(file: java.io.File) {
        scope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { FileOps.deleteRecursively(file) }
                if (ok) {
                    reportMessage(str(R.string.s304, file.name))
                    loadFiles(java.io.File(currentPath.value))
                } else {
                    reportError(str(R.string.s305, file.name))
                }
            } catch (e: Exception) {
                reportError(str(R.string.s236, e.message))
            }
        }
    }

    /** 创建新目录 */
    fun createDirectory(parent: java.io.File, name: String) {
        if (name.isBlank()) return
        scope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    java.io.File(parent, name).mkdirs()
                }
                if (ok) {
                    reportMessage(str(R.string.s311, name))
                    loadFiles(java.io.File(currentPath.value))
                } else {
                    reportError(str(R.string.s312))
                }
            } catch (e: Exception) {
                reportError(str(R.string.s313, e.message))
            }
        }
    }

    /**
     * 新建空文本文件。
     *
     * 名单校验与 [createDirectory] 同源：'/' 会静默建到别处、'..' 能跳出根目录，
     * 这里必须提前拒绝，否则用户会在「文件管理」里写出根目录之外的东西。
     * 建成功后直接进入编辑器 —— 新建一个空文件却停在列表里，用户还得再点一次。
     */
    fun createTextFile(parent: java.io.File, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        if (trimmed != name || trimmed.contains('/') || trimmed.contains('\\') || trimmed.contains("..")) {
            reportError(str(R.string.fm_new_dir_invalid_name))
            return
        }
        scope.launch {
            try {
                val target = withContext(Dispatchers.IO) {
                    val f = java.io.File(parent, trimmed)
                    if (f.exists()) null else f.createNewFile().let { if (it) f else null }
                }
                if (target == null) {
                    reportError(str(R.string.fm_name_exists, trimmed))
                    return@launch
                }
                reportMessage(str(R.string.fm_created, trimmed))
                loadFiles(java.io.File(currentPath.value))
                openTextFile(target)
            } catch (e: Exception) {
                reportError(str(R.string.fm_create_failed, e.message))
            }
        }
    }

    /**
     * 重命名文件/目录。
     *
     * 目标已存在时**拒绝**而不是覆盖：文件管理里一次误点就把 server.properties
     * 换成另一个同名文件，是不可恢复的事故。
     */
    fun renameEntry(file: java.io.File, newName: String) {
        val name = newName.trim()
        if (name.isEmpty() || name == file.name) return
        if (name.contains('/') || name.contains('\\') || name.contains("..")) {
            reportError(str(R.string.fm_new_dir_invalid_name))
            return
        }
        scope.launch {
            try {
                val message = withContext(Dispatchers.IO) {
                    val target = java.io.File(file.parentFile, name)
                    when {
                        target.exists() -> str(R.string.fm_name_exists, name) to true
                        file.renameTo(target) -> str(R.string.fm_renamed, name) to true
                        else -> str(R.string.fm_rename_failed, file.name) to false
                    }
                }
                if (message.second) reportMessage(message.first) else reportError(message.first)
                loadFiles(java.io.File(currentPath.value))
            } catch (e: Exception) {
                reportError(str(R.string.fm_rename_failed, e.message))
            }
        }
    }

    /** 复制文件/目录到目标目录；重名自动加序号，不覆盖已有内容 */
    fun copyEntryTo(src: java.io.File, destDir: java.io.File) {
        scope.launch {
            try {
                // 把目录复制到它自己内部会无限递归 —— 先挡掉，别等 IO 把磁盘写满
                if (destDir.absolutePath == src.absolutePath ||
                    destDir.absolutePath.startsWith(src.absolutePath + java.io.File.separator)
                ) {
                    reportError(str(R.string.fm_copy_into_self))
                    return@launch
                }
                val target = withContext(Dispatchers.IO) { copyRecursivelyTo(src, destDir) }
                reportMessage(str(R.string.fm_copied_to, target.name))
                loadFiles(java.io.File(currentPath.value))
            } catch (e: Exception) {
                reportError(str(R.string.fm_copy_failed, e.message))
            }
        }
    }

    private fun copyRecursivelyTo(src: java.io.File, destDir: java.io.File): java.io.File {
        val target = uniqueTarget(destDir, src.name)
        if (src.isDirectory) {
            target.mkdirs()
            src.listFiles()?.forEach { copyRecursivelyTo(it, target) }
        } else {
            src.inputStream().use { ins -> java.io.FileOutputStream(target).use { ins.copyTo(it) } }
        }
        return target
    }

    /** `name.ext` 已存在时生成 `name-1.ext`、`name-2.ext`… */
    private fun uniqueTarget(dir: java.io.File, name: String): java.io.File {
        val first = java.io.File(dir, name)
        if (!first.exists()) return first
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 1
        var candidate = java.io.File(dir, "$base-$index$ext")
        while (candidate.exists()) {
            index++
            candidate = java.io.File(dir, "$base-$index$ext")
        }
        return candidate
    }

    /** 批量删除（多选模式）；部分失败时报出失败数量，不谎报全部成功 */
    fun deleteEntries(entries: List<java.io.File>) {
        if (entries.isEmpty()) return
        scope.launch {
            try {
                val failed = withContext(Dispatchers.IO) {
                    entries.count { f -> !FileOps.deleteRecursively(f) }
                }
                when {
                    failed == 0 -> reportMessage(str(R.string.fm_deleted_count, entries.size))
                    failed == entries.size -> reportError(str(R.string.fm_delete_failed_count, failed))
                    else -> reportError(
                        str(R.string.fm_deleted_partial, entries.size - failed, failed)
                    )
                }
                loadFiles(java.io.File(currentPath.value))
            } catch (e: Exception) {
                reportError(str(R.string.s236, e.message))
            }
        }
    }

    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)
}
