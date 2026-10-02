package com.mineserve.mobile.server

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import com.mineserve.mobile.data.ServerCore
import com.mineserve.mobile.runtime.TermuxRuntime
import io.airlift.compress.zstd.ZstdInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.contentOrNull
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.security.MessageDigest
import java.util.jar.JarFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipFile

/**
 * 服务器导入器：
 *  - 从外部存储「文件夹」导入（SAF DocumentsContract 递归复制，自动下钻单层服务器根目录，
 *    避免把父目录整层搬进 home/servers 造成多级嵌套）
 *  - 从外部存储「压缩包」导入（支持 zip / tar / tar.gz / tar.xz / tar.bz2 / 7z；
 *    压缩包内若只有一个顶层服务器文件夹，会自动剥离该顶层，避免多层嵌套）
 *
 * 导入完成后调用 [ServerCoreDetector] 自动识别核心类型与版本。
 */
class ServerImporter(private val context: Context, private val termux: TermuxRuntime) {

    private val TAG = "ServerImporter"

    data class ImportedServer(
        val dirName: String,
        val displayName: String,
        val core: ServerCore?,
        val version: String?,
        /** 导入目录中检测到的真实入口；没有入口时保持为空，绝不伪造 server.jar。 */
        val serverFile: String? = null
    )

    // ── 文件夹导入（SAF tree） ─────────────────────────────────────

    /**
     * 从 SAF 文件夹（OpenDocumentTree 返回的 tree URI）导入服务器。
     * 若所选文件夹本身就像服务器根目录则直接用；否则仅当其中恰有一个子目录
     * 像服务器根目录时自动下钻一层（避免多级嵌套）。
     */
    suspend fun importFromFolder(
        treeUri: Uri,
        requestedName: String? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): ImportedServer = withContext(Dispatchers.IO) {
        // OpenDocumentTree returns a /tree/... URI; getDocumentId only accepts
        // /document/... URIs and throws "Invalid URI" on some providers.
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val children = listTreeChildren(treeUri, rootId)
        if (children.isEmpty()) throw IOException("所选文件夹为空")

        val (rootDocId, rootName) = resolveServerRoot(treeUri, rootId, children)
        val displayName = requestedName?.takeIf { it.isNotBlank() } ?: rootName ?: "imported_server"
        val dirName = uniqueDirName(McServerController.sanitizeDirName(displayName))
        val target = File(termux.serversDir, dirName)
        val totalFiles = countTreeFiles(treeUri, rootDocId)
        importInto(target) {
            walkTreeCopy(treeUri, rootDocId, target, onProgress, totalFiles)
            val detection = ServerCoreDetector.detect(target)
            ensureEula(target)
            ImportedServer(dirName, displayName, detection.core, detection.version, detection.serverFile)
        }
    }

    /** 解析服务器根目录：所选文件夹本身 / 唯一像服务器的子目录 / 原样 */
    private fun resolveServerRoot(
        treeUri: Uri,
        rootId: String,
        children: List<TreeChild>
    ): Pair<String, String?> {
        if (looksLikeServerRoot(children)) return rootId to queryName(treeUri, rootId)

        val serverLikeSubdirs = children.filter { child ->
            child.isDir && looksLikeServerRoot(listTreeChildren(treeUri, child.docId))
        }
        return when {
            serverLikeSubdirs.size == 1 ->
                serverLikeSubdirs.first().docId to serverLikeSubdirs.first().name
            serverLikeSubdirs.size > 1 ->
                throw IOException("所选文件夹包含多个服务器目录，请直接选择要导入的服务器文件夹")
            else -> rootId to queryName(treeUri, rootId)
        }
    }

    /** 目录内容是否像服务器根目录（有核心 jar / eula / world / plugins 等特征） */
    private fun looksLikeServerRoot(children: List<TreeChild>): Boolean =
        children.any { child ->
            val n = child.name.lowercase(Locale.US)
            if (child.isDir) {
                n == "world" || n == "world_nether" || n == "world_the_end" ||
                    n == "plugins" || n == "mods" || n == "libraries" ||
                    n == "cache" || n == "versions" || n == "config" || n == "logs" ||
                    n == ".fabric" || n == ".paper"
            } else {
                n == "server.jar" || n == "eula.txt" || n == "server.properties" ||
                    n == "version.json" || n == "powernukkitx.jar" ||
                    n == "fabric-server-launch.jar" || n == "quilt-server-launch.jar" ||
                    n == "bungee.jar" || n == "spigot.jar" || n == "paper.jar" ||
                    n == "purpur.jar" || n.endsWith(".jar")
            }
        }

    /** SAF 树递归复制到目标目录（保留目录结构，带文件级进度回调） */
    private fun walkTreeCopy(
        treeUri: Uri,
        docId: String,
        destDir: File,
        onProgress: (Long, Long) -> Unit,
        total: Long
    ) {
        var done = 0L
        fun copyRecursive(uri: Uri, id: String, dest: File) {
            dest.mkdirs()
            listTreeChildren(uri, id).forEach { child ->
                val d = safeChild(dest, child.name)
                if (child.isDir) {
                    copyRecursive(uri, child.docId, d)
                } else {
                    d.parentFile?.mkdirs()
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, child.docId)
                    try {
                        context.contentResolver.openInputStream(docUri)?.use { input ->
                            d.outputStream().use { out -> input.copyTo(out) }
                        }
                    } catch (e: Exception) {
                        throw IOException("复制文件失败: " + child.name, e)
                    }
                    done++
                    onProgress(done, total)
                }
            }
        }
        copyRecursive(treeUri, docId, destDir)
    }

    /** 统计 SAF 树内文件总数（用于导入进度） */
    private fun countTreeFiles(treeUri: Uri, docId: String): Long {
        var count = 0L
        listTreeChildren(treeUri, docId).forEach { child ->
            if (child.isDir) count += countTreeFiles(treeUri, child.docId) else count++
        }
        return count
    }

    /** 为导入确认对话框预填文件夹名称（与导入时相同的服务器根目录下钻逻辑） */
    fun proposeFolderName(treeUri: Uri): String? {
        return runCatching {
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            val children = listTreeChildren(treeUri, rootId)
            if (children.isEmpty()) return@runCatching null
            val (_, name) = runCatching { resolveServerRoot(treeUri, rootId, children) }.getOrNull()
                ?: return@runCatching queryName(treeUri, rootId)
            name
        }.getOrNull()
    }

    /** 为导入确认对话框预填压缩包名称（去掉扩展名） */
    fun proposeArchiveName(uri: Uri): String? {
        val name = queryDisplayName(uri) ?: return null
        return archiveBaseName(name)
    }

    fun proposeJarName(uri: Uri): String? = queryDisplayName(uri)
        ?.removeSuffix(".jar")
        ?.ifBlank { null }

    private data class TreeChild(val docId: String, val name: String, val isDir: Boolean)

    /** 列出 SAF 树中某个文档的所有子项（无 DocumentFile 依赖，直接用 DocumentsContract） */
    private fun listTreeChildren(treeUri: Uri, docId: String): List<TreeChild> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val result = mutableListOf<TreeChild>()
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
            val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (c.moveToNext()) {
                val id = if (idIdx >= 0) c.getString(idIdx) else null
                val name = if (nameIdx >= 0) c.getString(nameIdx) else null
                if (id != null && name != null) {
                    val mime = if (mimeIdx >= 0) c.getString(mimeIdx) else ""
                    result += TreeChild(id, name, mime == DocumentsContract.Document.MIME_TYPE_DIR)
                }
            }
        }
        return result
    }

    /** 查询 SAF 树根文档的显示名称 */
    private fun queryName(treeUri: Uri, docId: String): String? {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        return context.contentResolver.query(
            docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }

    // ── 压缩包导入 ─────────────────────────────────────────────────

    /**
     * 从 SAF 压缩包（OpenDocument 返回的 URI）导入服务器。
     * 自动识别格式，剥离单一顶层文件夹，防 zip-slip 路径穿越。
     */
    suspend fun importFromArchive(
        uri: Uri,
        requestedName: String? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): ImportedServer = withContext(Dispatchers.IO) {
        val fileName = queryDisplayName(uri) ?: ("server_import_" + System.currentTimeMillis() + ".zip")
        val temp = File(
            termux.installer.tmpDir,
            "import_" + System.currentTimeMillis() + "_" + McServerController.sanitizeDirName(fileName)
        )
        try {
            copyUriToFile(uri, temp)
            val format = detectFormat(temp, fileName)
            val entryNames = listEntryNames(temp, format)
            val stripPrefix = ServerImportLayout.computeStripPrefix(entryNames)
            val autoName = stripPrefix ?: archiveBaseName(fileName)
            val displayName = requestedName?.takeIf { it.isNotBlank() } ?: autoName
            val dirName = uniqueDirName(McServerController.sanitizeDirName(displayName))
            val target = File(termux.serversDir, dirName)
            importInto(target) {
                extractArchive(temp, format, target, stripPrefix, onProgress, entryNames.size.toLong())
                val modpackHint = if (format == ArchiveFormat.ZIP && hasModrinthManifest(temp)) {
                    installModrinthPack(temp, target, onProgress)
                } else null

                val detection = ServerCoreDetector.detect(target)
                ensureEula(target)
                ImportedServer(
                    dirName,
                    displayName,
                    detection.core ?: modpackHint?.first,
                    detection.version ?: modpackHint?.second,
                    detection.serverFile
                )
            }
        } finally {
            temp.delete()
        }
    }

    /** 从单个服务端 JAR 创建独立服务器目录。 */
    suspend fun importFromJar(
        uri: Uri,
        requestedName: String? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): ImportedServer = withContext(Dispatchers.IO) {
        val fileName = queryDisplayName(uri) ?: "server.jar"
        if (!fileName.endsWith(".jar", ignoreCase = true)) throw IOException("请选择 JAR 核心文件")
        val displayName = requestedName?.takeIf { it.isNotBlank() }
            ?: fileName.removeSuffix(".jar").ifBlank { "imported_server" }
        val dirName = uniqueDirName(McServerController.sanitizeDirName(displayName))
        val target = File(termux.serversDir, dirName)
        val targetJar = File(target, ServerImportLayout.importedJarFileName(fileName))
        importInto(target) {
            target.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input ->
                targetJar.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IOException("无法读取所选 JAR 文件")
            JarFile(targetJar).use { }
            val detection = ServerCoreDetector.detect(target)
            prepareJarLayout(target, detection.core)
            ensureEula(target)
            onProgress(1, 1)
            ImportedServer(dirName, displayName, detection.core, detection.version, detection.serverFile)
        }
    }

    /** Match EdgeCube's empty-instance lifecycle: failed imports leave no partial server behind. */
    private inline fun <T> importInto(target: File, block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        target.deleteRecursively()
        throw e
    }

    private fun safeChild(parent: File, name: String): File {
        require(name.isNotBlank() && name != "." && name != ".." &&
            !name.contains('/') && !name.contains('\\')) { "导入文件名非法: $name" }
        return File(parent, name)
    }

    /** JAR 导入只补齐安全的空目录和最小配置，不覆盖核心自带文件。 */
    private fun prepareJarLayout(serverDir: File, core: ServerCore?) {
        listOf("plugins", "logs").forEach { File(serverDir, it).mkdirs() }
        if (core == ServerCore.PowerNukkitX) {
            listOf("worlds", "players", "resource_packs").forEach { File(serverDir, it).mkdirs() }
            val properties = File(serverDir, "server.properties")
            if (!properties.exists()) properties.writeText("server-port=19132\n")
            val nukkit = File(serverDir, "nukkit.yml")
            if (!nukkit.exists()) nukkit.writeText("# PowerNukkitX / Nukkit configuration\n")
        } else {
            File(serverDir, "world").mkdirs()
            val properties = File(serverDir, "server.properties")
            if (!properties.exists()) properties.writeText("server-port=25565\n")
        }
    }

    /** 与下载核心流程一致：缺少 eula.txt 时自动写入，保证导入后可直接启动 */
    private fun ensureEula(serverDir: File) {
        try {
            val eula = File(serverDir, "eula.txt")
            if (!eula.exists()) eula.writeText("eula=true\n")
        } catch (_: Exception) {
            // eula 写入失败不影响导入结果
        }
    }

    /** Minimal Modrinth .mrpack support: download server files and apply overrides. */
    private fun installModrinthPack(
        archiveFile: File,
        target: File,
        onProgress: (Long, Long) -> Unit
    ): Pair<ServerCore?, String?>? {
        val json = Json { ignoreUnknownKeys = true }
        ZipFile(archiveFile).use { zip ->
            val manifestEntry = zip.getEntry("modrinth.index.json") ?: return null
            val root = json.parseToJsonElement(zip.getInputStream(manifestEntry).bufferedReader().use { it.readText() }).jsonObject
            val deps = root["dependencies"]?.jsonObject ?: emptyMap()
            val mcVersion = deps["minecraft"]?.jsonPrimitive?.contentOrNull
            val loader = deps.keys.firstOrNull { it in setOf("fabric-loader", "forge", "neoforge", "quilt-loader") }
            val core = when (loader) {
                "fabric-loader" -> ServerCore.Fabric
                "forge" -> ServerCore.Forge
                "neoforge" -> ServerCore.NeoForge
                "quilt-loader" -> ServerCore.Quilt
                else -> null
            }
            val fileArray = root["files"]?.jsonArray ?: emptyList()
            var done = 0L
            val total = fileArray.size.toLong()
            for (item in fileArray) {
                val obj = item.jsonObject
                val env = obj["env"]?.jsonObject
                if (env?.get("server")?.jsonPrimitive?.contentOrNull == "unsupported") continue
                val rel = safeImportedPath(obj["path"]?.jsonPrimitive?.contentOrNull ?: continue)
                val urls = obj["downloads"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                val sha1 = obj["hashes"]?.jsonObject?.get("sha1")?.jsonPrimitive?.contentOrNull
                if (urls.isNotEmpty()) downloadPackFile(urls, File(target, rel), sha1)
                done++
                onProgress(done, total)
            }
            val overridePrefixes = listOf("overrides/", "server-overrides/")
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                val prefix = overridePrefixes.firstOrNull { entry.name.startsWith(it) } ?: continue
                val rel = safeImportedPath(entry.name.removePrefix(prefix))
                val out = File(target, rel)
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> out.outputStream().use { output -> input.copyTo(output) } }
            }
            File(target, "overrides").deleteRecursively()
            File(target, "server-overrides").deleteRecursively()
            File(target, "client-overrides").deleteRecursively()
            File(target, "modrinth.index.json").delete()
            return core to mcVersion
        }
    }

    private fun hasModrinthManifest(file: File): Boolean = runCatching {
        ZipFile(file).use { it.getEntry("modrinth.index.json") != null }
    }.getOrDefault(false)

    private fun safeImportedPath(path: String): String {
        val normalized = path.replace('\\', '/').trimStart('/')
        require(normalized.isNotEmpty() && normalized != "." && !normalized.split('/').contains("..") && !normalized.contains(':')) {
            "整合包包含非法路径: $path"
        }
        return normalized
    }

    private fun downloadPackFile(urls: List<String>, target: File, expectedSha1: String?) {
        target.parentFile?.mkdirs()
        var last: Exception? = null
        for (url in urls) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    setRequestProperty("User-Agent", "MineServeMobile/1.1")
                }
                conn.connect()
                if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
                conn.inputStream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                if (expectedSha1 != null && sha1(target) != expectedSha1.lowercase(Locale.US)) {
                    target.delete()
                    throw IOException("整合包文件校验失败: ${target.name}")
                }
                return
            } catch (e: Exception) {
                last = e
            } finally {
                // 原先只在成功路径 disconnect，HTTP 非 2xx / 网络异常 / SHA-1 不匹配都会走
                // catch 而不释放连接，弱网下 socket 泄漏导致后续下载持续超时。
                conn?.disconnect()
            }
        }
        throw IOException("整合包文件下载失败: ${target.name}", last)
    }

    /** 流式计算 SHA-1：整合包常见 100~500MB 文件，readBytes() 全量载入会在低内存机 OOM。 */
    private fun sha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 查询所选文件的原始文件名 */
    private fun queryDisplayName(uri: Uri): String? =
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun copyUriToFile(uri: Uri, target: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { out -> input.copyTo(out) }
        } ?: throw IOException("无法读取所选文件")
    }

    /** 压缩包格式 */
    private enum class ArchiveFormat { ZIP, TAR, TAR_GZ, TAR_XZ, TAR_BZ2, TAR_ZST, TAR_LZ4, SEVEN_Z }

    /** 按扩展名判断格式，未知扩展名时嗅探文件头 */
    private fun detectFormat(file: File, fileName: String): ArchiveFormat {
        val lower = fileName.lowercase(Locale.US)
        return when {
            lower.endsWith(".zip") -> ArchiveFormat.ZIP
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> ArchiveFormat.TAR_GZ
            lower.endsWith(".tar.xz") || lower.endsWith(".txz") -> ArchiveFormat.TAR_XZ
            lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") -> ArchiveFormat.TAR_BZ2
            lower.endsWith(".tar.zst") || lower.endsWith(".tzst") -> ArchiveFormat.TAR_ZST
            lower.endsWith(".tar.lz4") -> ArchiveFormat.TAR_LZ4
            lower.endsWith(".tar") -> ArchiveFormat.TAR
            lower.endsWith(".7z") -> ArchiveFormat.SEVEN_Z
            else -> sniffFormat(file) ?: ArchiveFormat.ZIP
        }
    }

    private fun sniffFormat(file: File): ArchiveFormat? {
        val magic = ByteArray(6)
        FileInputStream(file).use { input ->
            val read = input.read(magic)
            if (read < 4) return null
        }
        return when {
            magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte() -> ArchiveFormat.ZIP
            magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte() -> ArchiveFormat.TAR_GZ
            magic[0] == 'B'.code.toByte() && magic[1] == 'Z'.code.toByte() &&
                magic[2] == 'h'.code.toByte() -> ArchiveFormat.TAR_BZ2
            magic[0] == 0xfd.toByte() && magic[1] == 0x37.toByte() &&
                magic[2] == 0x7a.toByte() -> ArchiveFormat.TAR_XZ
            magic[0] == 0x28.toByte() && magic[1] == 0xb5.toByte() &&
                magic[2] == 0x2f.toByte() && magic[3] == 0xfd.toByte() -> ArchiveFormat.TAR_ZST
            magic[0] == 0x04.toByte() && magic[1] == 0x22.toByte() &&
                magic[2] == 0x4d.toByte() && magic[3] == 0x18.toByte() -> ArchiveFormat.TAR_LZ4
            magic[0] == 0x37.toByte() && magic[1] == 0x7a.toByte() &&
                magic[2] == 0xbc.toByte() -> ArchiveFormat.SEVEN_Z
            else -> {
                // tar：偏移 257 处为 ustar
                val ustar = ByteArray(5)
                try {
                    RandomAccessFile(file, "r").use { raf ->
                        raf.seek(257)
                        raf.read(ustar)
                    }
                    if (String(ustar) == "ustar") ArchiveFormat.TAR else null
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    /** 第一遍：列出压缩包内所有条目名（统一为正斜杠），用于顶层目录剥离判断 */
    private fun listEntryNames(file: File, format: ArchiveFormat): List<String> {
        val names = mutableListOf<String>()
        when (format) {
            ArchiveFormat.ZIP -> {
                ZipInputStream(BufferedInputStream(FileInputStream(file), 64 * 1024)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        names += entry.name.replace('\\', '/')
                        entry = zis.nextEntry
                    }
                }
            }
            ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_XZ, ArchiveFormat.TAR_BZ2,
            ArchiveFormat.TAR_ZST, ArchiveFormat.TAR_LZ4 -> {
                openTar(file, format).use { tar ->
                    var entry = tar.nextTarEntry
                    while (entry != null) {
                        names += entry.name.replace('\\', '/')
                        entry = tar.nextTarEntry
                    }
                }
            }
            ArchiveFormat.SEVEN_Z -> {
                SevenZFile(file).use { sz ->
                    var entry = sz.nextEntry
                    while (entry != null) {
                        names += entry.name.replace('\\', '/')
                        entry = sz.nextEntry
                    }
                }
            }
        }
        return names
    }

    private fun archiveBaseName(fileName: String): String =
        fileName.substringBeforeLast('.')
            .let { if (it.endsWith(".tar", ignoreCase = true)) it.removeSuffix(".tar") else it }
            .ifBlank { "imported_server" }

    /** 目标目录名去重：同名时追加 _2 / _3 … */
    private fun uniqueDirName(base: String): String {
        var candidate = base
        var i = 2
        while (File(termux.serversDir, candidate).exists()) {
            candidate = base + "_" + i
            i++
        }
        return candidate
    }

    // ── 解压（含剥离与防路径穿越） ─────────────────────────────────

    /**
     * 解压预算常量。
     *
     * 上限取「比任何真实服务端都宽裕、但压不垮 /data」的量级：
     * 现代模组整合包解压后可达 10+ GiB，所以给 32 GiB；
     * 条目数上限对应大量小文件（模组包常见几十万文件）。
     */
    internal companion object {
        internal const val MAX_TOTAL_BYTES = 32L * 1024 * 1024 * 1024
        internal const val MAX_ENTRIES = 400_000L
    }

    private fun extractArchive(
        file: File,
        format: ArchiveFormat,
        dest: File,
        stripPrefix: String?,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        total: Long = 0L
    ) {
        dest.mkdirs()
        val counter = ProgressCounter(onProgress, total)
        try {
            when (format) {
                ArchiveFormat.ZIP -> extractZip(file, dest, stripPrefix, counter)
                ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_XZ, ArchiveFormat.TAR_BZ2,
                ArchiveFormat.TAR_ZST, ArchiveFormat.TAR_LZ4 ->
                    extractTar(file, format, dest, stripPrefix, counter)
                ArchiveFormat.SEVEN_Z -> extractSevenZ(file, dest, stripPrefix, counter)
            }
        } catch (e: Exception) {
            // 预算超限/解压出错时，已写出的部分必须清掉：
            // 否则会在 servers/ 下留下半个「服务器实例」，用户下次导入还要先手动删。
            // 只清理 dest 自身（调用方每次都用全新的空目录），不做递归向上删除。
            Log.e(TAG, "解压失败，清理已写出的部分: ${dest.absolutePath}", e)
            dest.deleteRecursively()
            throw e
        }
    }

    /** 解压进度计数器（每个条目 +1） */
    private class ProgressCounter(
        private val onProgress: (Long, Long) -> Unit,
        private val total: Long
    ) {
        private var done = 0L
        fun tick() {
            done++
            if (total > 0) onProgress(done, total)
        }
    }

    private fun openTar(file: File, format: ArchiveFormat): TarArchiveInputStream {
        val buffered = BufferedInputStream(FileInputStream(file), 64 * 1024)
        return when (format) {
            ArchiveFormat.TAR -> TarArchiveInputStream(buffered)
            ArchiveFormat.TAR_GZ -> TarArchiveInputStream(GzipCompressorInputStream(buffered))
            ArchiveFormat.TAR_XZ -> TarArchiveInputStream(XZCompressorInputStream(buffered))
            ArchiveFormat.TAR_BZ2 -> TarArchiveInputStream(BZip2CompressorInputStream(buffered))
            // zstd：用 aircompressor 的**纯 Java** 解码器，而不是 commons-compress 自带的
            // ZstdCompressorInputStream —— 后者需要 com.github.luben:zstd-jni 的原生库，
            // 而 zstd-jni 的 aarch64 .so 是 glibc 构建（NEEDED 含 libc.so.6），jar 里也没有
            // android/ 目录，在 Android 的 bionic 上必然 dlopen 失败。
            // 也就是说：本 App 的「导入 .tar.zst」在真机上从来没成功过。
            ArchiveFormat.TAR_ZST -> TarArchiveInputStream(ZstdInputStream(buffered))
            ArchiveFormat.TAR_LZ4 -> TarArchiveInputStream(FramedLZ4CompressorInputStream(buffered))
            else -> throw IOException("不是 tar 格式")
        }
    }

    /**
     * 解压时的资源预算。
     *
     * 这三条只有 canonical 前缀校验（防目录穿越），但**没有体积/条目上限**：
     * 一个几百 KB 的压缩炸弹就能把 `copyTo` 一路写到 `/data` 撑爆，
     * 而 App 与被托管的服务端共用同一分区——磁盘写满会让配置落盘、世界保存全部失败。
     * 这里给出一条明确的预算，超限即中止（已解出的内容由调用方负责清理）。
     */
    internal class ExtractBudget {
        var entries = 0L
        var bytes = 0L

        fun addEntry() {
            entries++
            if (entries > MAX_ENTRIES) {
                throw IOException("压缩包条目数超过上限 $MAX_ENTRIES，已中止导入（疑似压缩炸弹）")
            }
        }

        fun addBytes(n: Long) {
            bytes += n
            if (bytes > MAX_TOTAL_BYTES) {
                throw IOException("解压体积超过上限 ${MAX_TOTAL_BYTES / (1024L * 1024 * 1024)} GiB，已中止导入")
            }
        }
    }

    private fun extractZip(file: File, dest: File, stripPrefix: String?, counter: ProgressCounter? = null) {
        val canonicalDest = dest.canonicalPath + File.separator
        val budget = ExtractBudget()
        ZipInputStream(BufferedInputStream(FileInputStream(file), 64 * 1024)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                budget.addEntry()
                val raw = entry.name.replace('\\', '/')
                val rel = ServerImportLayout.strippedPath(raw, stripPrefix)
                if (rel != null && !ServerImportLayout.isJunkEntry(raw)) {
                    val outFile = File(dest, rel)
                    if (outFile.canonicalPath.startsWith(canonicalDest)) {
                        if (entry.isDirectory) {
                            if (outFile.exists() && !outFile.isDirectory) outFile.delete()
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = zis.read(buf)
                                    if (n < 0) break
                                    budget.addBytes(n.toLong())
                                    out.write(buf, 0, n)
                                }
                            }
                        }
                    }
                }
                counter?.tick()
                entry = zis.nextEntry
            }
        }
    }

    private fun extractTar(file: File, format: ArchiveFormat, dest: File, stripPrefix: String?, counter: ProgressCounter? = null) {
        val canonicalDest = dest.canonicalPath + File.separator
        val budget = ExtractBudget()
        openTar(file, format).use { tar ->
            var entry = tar.nextTarEntry
            while (entry != null) {
                budget.addEntry()
                val raw = entry.name.replace('\\', '/')
                // 符号链接 / 硬链接一律跳过（防止链接逃逸出目标目录）
                if (!entry.isSymbolicLink && !entry.isLink) {
                    val rel = ServerImportLayout.strippedPath(raw, stripPrefix)
                    if (rel != null && !ServerImportLayout.isJunkEntry(raw)) {
                        val outFile = File(dest, rel)
                        if (outFile.canonicalPath.startsWith(canonicalDest)) {
                            if (entry.isDirectory) {
                                if (outFile.exists() && !outFile.isDirectory) outFile.delete()
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { out ->
                                    val buf = ByteArray(64 * 1024)
                                    while (true) {
                                        val n = tar.read(buf)
                                        if (n < 0) break
                                        budget.addBytes(n.toLong())
                                        out.write(buf, 0, n)
                                    }
                                }
                            }
                        }
                    }
                }
                counter?.tick()
                entry = tar.nextTarEntry
            }
        }
    }


    private fun extractSevenZ(file: File, dest: File, stripPrefix: String?, counter: ProgressCounter? = null) {
        val canonicalDest = dest.canonicalPath + File.separator
        val budget = ExtractBudget()
        SevenZFile(file).use { sz ->
            var entry = sz.nextEntry
            while (entry != null) {
                budget.addEntry()
                val raw = entry.name.replace('\\', '/')
                if (!entry.isAntiItem) {
                val rel = ServerImportLayout.strippedPath(raw, stripPrefix)
                if (rel != null && !ServerImportLayout.isJunkEntry(raw)) {
                    val outFile = File(dest, rel)
                    if (outFile.canonicalPath.startsWith(canonicalDest)) {
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val n = sz.read(buffer)
                                    if (n < 0) break
                                    budget.addBytes(n.toLong())
                                    out.write(buffer, 0, n)
                                }
                            }
                        }
                    }
                }
                }
                counter?.tick()
                entry = sz.nextEntry
            }
        }
    }


}
