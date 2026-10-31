package com.mineserve.mobile.server

import android.content.Context
import android.net.Uri
import android.util.Log
import com.mineserve.mobile.R
import com.mineserve.mobile.data.MultiThreadDownloader
import com.mineserve.mobile.runtime.TermuxRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile

/**
 * 插件管理器（重构版）
 *
 * 设计原则：
 *  1. 真实文件系统为唯一真相源 —— 扫描 plugins/ 目录下实际存在的 .jar 文件
 *  2. 三种安装方式：精选推荐库 / 本地 SAF 上传 / 自定义 URL 直装
 *  3. 启用/禁用采用 Bukkit 标准 —— 文件名以 `-` 前缀为禁用状态
 *  4. 多核心隔离 —— 每个核心独立 plugins/ 目录
 *  5. plugin.yml 元信息解析带 lastModified 缓存，文件未变直接复用
 */
class PluginManager(
    private val termux: TermuxRuntime,
    private val context: Context
) {

    companion object {
        private const val TAG = "PluginManager"
        private const val JAR_EXT = ".jar"
        private const val DISABLED_PREFIX = "-"
        /** 模组禁用后缀（Fabric/Forge 官方约定：xxx.jar → xxx.jar.disabled） */
        private const val MOD_DISABLED_SUFFIX = ".jar.disabled"
        /** 历史版本遗留的禁用后缀（缺 .jar 的变体） */
        private const val LEGACY_DISABLED_SUFFIX = ".disabled"

        /** 忽略大小写地剥离后缀（removeSuffix 区分大小写，会漏掉 .JAR 等变体）。 */
        private fun stripSuffixIgnoreCase(name: String, suffix: String): String {
            if (name.length < suffix.length) return name
            val tail = name.substring(name.length - suffix.length)
            return if (tail.equals(suffix, ignoreCase = true)) {
                name.substring(0, name.length - suffix.length)
            } else name
        }

        /**
         * 解析 mods 目录文件名 → (展示基名, 是否启用)。
         * 兼容：标准 .jar / .jar.disabled、遗留 .disabled、遗留 `-` 前缀、
         * 以及历史 bug 留下的无后缀文件（一律按禁用模组处理，可通过开关自愈为标准名）。
         */
        internal fun parseModFileName(name: String): Pair<String, Boolean> = when {
            name.endsWith(MOD_DISABLED_SUFFIX, ignoreCase = true) ->
                stripSuffixIgnoreCase(name, MOD_DISABLED_SUFFIX) to false
            name.endsWith(LEGACY_DISABLED_SUFFIX, ignoreCase = true) ->
                stripSuffixIgnoreCase(stripSuffixIgnoreCase(name, LEGACY_DISABLED_SUFFIX), JAR_EXT) to false
            name.startsWith(DISABLED_PREFIX) ->
                stripSuffixIgnoreCase(name.substring(1), JAR_EXT) to false
            name.endsWith(JAR_EXT, ignoreCase = true) ->
                stripSuffixIgnoreCase(name, JAR_EXT) to true
            else -> name to false
        }

        /** 计算切换后的目标文件名：启用恢复标准 baseName.jar，禁用为标准 baseName.jar.disabled。 */
        internal fun modToggleTarget(fileName: String, enable: Boolean): String {
            val (baseName, _) = parseModFileName(fileName)
            return if (enable) "$baseName$JAR_EXT" else "$baseName$MOD_DISABLED_SUFFIX"
        }

        /** 判断 mods 目录里的文件是否按模组条目展示。 */
        private fun isModCandidate(f: File): Boolean {
            val name = f.name
            if (name.endsWith(JAR_EXT, ignoreCase = true) ||
                name.endsWith(MOD_DISABLED_SUFFIX, ignoreCase = true) ||
                name.endsWith(LEGACY_DISABLED_SUFFIX, ignoreCase = true) ||
                name.startsWith(DISABLED_PREFIX)
            ) return true
            // 历史遗留的无后缀文件：内容为 zip（PK 魔数）才按模组展示
            if (!name.contains('.')) {
                return runCatching {
                    f.inputStream().use { ins ->
                        val head = ByteArray(2)
                        ins.read(head) == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
                    }
                }.getOrDefault(false)
            }
            return false
        }
        private const val UPDATE_CHECK_COOLDOWN_MS = 5 * 60 * 1000L  // 更新检测冷却 5 分钟
    }

    /**
     * 精选插件库
     *
     * URL 选择策略：优先使用 GitHub Releases latest 重定向（自动跟随最新版本）
     */
    data class CuratedPlugin(
        val id: String,
        val name: String,
        val author: String,
        val description: String,
        val avatarText: String,
        val homepage: String,
        val downloadUrl: String,
        val targetFileName: String,
        /** GitHub 仓库全名（owner/repo），用于更新检测 API */
        val repo: String,
        /** asset 文件名包含模式；非空时下载前用 GitHub API 动态解析最新 asset 直链（如 ViaVersion） */
        val githubAssetPattern: String? = null
    )

    /** 精选插件已下线（统一从 Modrinth 等平台获取，避免失效链接） */
    val curatedPlugins: List<CuratedPlugin> = emptyList()

    /**
     * 从 plugin.yml 解析出的插件元信息
     * 兼容 Bukkit/Spigot/Paper 的 plugin.yml 和 paper-plugin.yml
     */
    data class PluginMeta(
        val name: String,
        val version: String,
        val mainClass: String,
        val author: String,
        val description: String,
        val apiVersion: String,
        val depends: List<String>,
        val softDepends: List<String>
    )

    /**
     * 真实已安装插件（来自文件系统扫描 + plugin.yml 解析）
     */
    data class InstalledPlugin(
        val fileName: String,
        val baseName: String,
        val sizeBytes: Long,
        val sizeText: String,
        val lastModified: Long,
        val lastModifiedText: String,
        val isEnabled: Boolean,
        val sourceTag: String,          // "精选" / "本地"
        val meta: PluginMeta?           // plugin.yml 解析结果，可能为空（非插件 jar）
    )

    /**
     * 检测插件冲突：plugin.yml 同名或同主类的插件（可能导致加载失败或互相覆盖）。
     * @return 人类可读的冲突描述列表，无冲突时为空
     */
    fun detectPluginConflicts(plugins: List<InstalledPlugin>): List<String> {
        val withMeta = plugins.filter { it.meta != null }
        val conflicts = mutableListOf<String>()
        withMeta.groupBy { it.meta!!.name.lowercase() }
            .filter { it.value.size > 1 }
            .forEach { (_, group) ->
                conflicts += "插件名称重复「${group.first().meta!!.name}」: ${group.joinToString("、") { it.fileName }}"
            }
        withMeta.filter { it.meta!!.mainClass.isNotBlank() }
            .groupBy { it.meta!!.mainClass }
            .filter { it.value.size > 1 }
            .forEach { (mainClass, group) ->
                conflicts += "主类重复「$mainClass」: ${group.joinToString("、") { it.fileName }}"
            }
        return conflicts
    }

    /**
     * 精选插件的更新检测结果
     */
    data class CuratedUpdateInfo(
        val curated: CuratedPlugin,
        val latestVersion: String,      // GitHub Releases 最新 tag
        val latestReleaseUrl: String,   // GitHub Releases 页面
        val installedVersion: String?,  // 已安装版本（从 plugin.yml 解析），null 表示未安装
        val hasUpdate: Boolean          // 是否有更新
    )

    // ── plugin.yml 解析缓存：key = jar.absolutePath，value = (lastModified, meta) ──
    private val metaCache = ConcurrentHashMap<String, Pair<Long, PluginMeta?>>()

    // ── 更新检测缓存：key = curated.id，value = (检测时间戳, info) ──
    private val updateCache = ConcurrentHashMap<String, Pair<Long, CuratedUpdateInfo>>()

    /**
     * 扫描指定核心目录下 plugins/ 文件夹的所有 .jar 文件
     * 并行解析 plugin.yml 以加速
     */
    suspend fun scan(dirName: String): List<InstalledPlugin> = withContext(Dispatchers.IO) {
        val pluginsDir = pluginsDirOf(dirName)
        if (!pluginsDir.exists() || !pluginsDir.isDirectory) {
            pluginsDir.mkdirs()
            return@withContext emptyList()
        }
        val jarFiles = pluginsDir.listFiles { f ->
            f.isFile && f.name.endsWith(JAR_EXT, ignoreCase = true)
        }?.toList() ?: emptyList()

        // 并行解析每个 jar 的 plugin.yml
        val installed = jarFiles.map { f ->
            async { toInstalledPlugin(f) }
        }.map { it.await() }

        installed.sortedWith(
            compareByDescending<InstalledPlugin> { it.isEnabled }
                .thenByDescending { it.lastModified }
        )
    }

    /**
     * 从 URL 下载插件到指定核心的 plugins/ 目录
     * @param onProgress (已下载字节, 总字节, 速度 bytes/s)
     */
    suspend fun installFromUrl(
        url: String,
        targetFileName: String,
        dirName: String,
        onProgress: (Long, Long, Long) -> Unit = { _, _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val pluginsDir = pluginsDirOf(dirName).apply { mkdirs() }
        val target = File(pluginsDir, ensureJarExtension(sanitizeFileName(targetFileName, "plugin.jar")))

        // 备份旧文件（若存在），下载失败时回滚
        val backup: File? = if (target.exists()) File(pluginsDir, "${target.name}.bak") else null
        if (backup != null) target.renameTo(backup)
        // 清缓存（旧文件解析结果失效）
        metaCache.remove(target.absolutePath)

        try {
            downloadTo(url, target, onProgress)
            if (!target.exists() || target.length() < 1024) {
                throw RuntimeException(context.getString(R.string.s128, target.length()))
            }
            backup?.takeIf { it.exists() }?.delete()
            target
        } catch (e: Exception) {
            if (backup?.exists() == true) backup.renameTo(target)
            throw e
        }
    }

    /**
     * 从本地 Uri 上传插件到指定核心的 plugins/ 目录
     * @return 实际保存的文件名
     */
    suspend fun installFromUri(uri: Uri, dirName: String, fallbackName: String = "plugin.jar"): String =
        withContext(Dispatchers.IO) {
            val pluginsDir = pluginsDirOf(dirName).apply { mkdirs() }
            val safeName = ensureJarExtension(sanitizeFileName(queryFileName(uri), fallbackName))
            val target = File(pluginsDir, safeName)

            val input = context.contentResolver.openInputStream(uri)
                ?: throw RuntimeException(context.getString(R.string.s129, uri.toString()))
            input.use { ins ->
                FileOutputStream(target).use { fos ->
                    ins.copyTo(fos, bufferSize = 64 * 1024)
                }
            }
            if (target.length() < 1024) {
                target.delete()
                throw RuntimeException(context.getString(R.string.s130))
            }
            target.name
        }

    /**
     * 删除指定插件文件
     * @param alsoRemoveDataDir 是否同时删除插件数据目录（plugins/插件名/）
     */
    suspend fun delete(fileName: String, dirName: String, alsoRemoveDataDir: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            val pluginsDir = pluginsDirOf(dirName)
            // 文件名可能来自外部（MCP / 导入）：`../server.properties` 经 File(dir, name)
            // 会落到服务端目录里。这里要求它必须是不含目录成分的纯文件名。
            val safe = sanitizeFileName(fileName, "")
            if (safe.isEmpty() || safe != fileName) return@withContext false
            val file = File(pluginsDir, safe)
            if (!file.exists()) return@withContext false
            if (file.canonicalFile.parentFile?.canonicalPath != pluginsDir.canonicalPath) return@withContext false

            // 先尝试解析元信息获取插件名（用于清理数据目录）
            val pluginName = if (alsoRemoveDataDir) {
                val installed = toInstalledPlugin(file)
                installed.meta?.name ?: installed.baseName
            } else null

            // 清缓存
            metaCache.remove(file.absolutePath)

            val ok = file.delete()
            if (!ok) {
                termux.execOnce("rm", "-f", file.absolutePath)
            }

            // 清理插件数据目录。
            //
            // pluginName 来自插件自己的 plugin.yml（`name: ../world` 这种可以任意构造），
            // 它和新插件文件名一样是**外部输入**：只做 File(pluginsDir, name) 拼路径的话，
            // `../world` 会落到服务端目录里，deleteRecursively() 直接把世界删掉。
            // 所以这里的净化规则必须与上面的 fileName 完全一致（纯文件名 + canonical 父目录校验）。
            if (alsoRemoveDataDir && pluginName != null) {
                val safeDataName = sanitizeFileName(pluginName, "")
                if (safeDataName.isNotEmpty() && safeDataName == pluginName) {
                    val dataDir = File(pluginsDir, safeDataName)
                    val insidePlugins = dataDir.canonicalFile.parentFile?.canonicalPath == pluginsDir.canonicalPath
                    if (insidePlugins && dataDir.exists() && dataDir.isDirectory) {
                        dataDir.deleteRecursively()
                    } else {
                        Log.w(TAG, "delete: 跳过可疑插件数据目录 ${dataDir.absolutePath}")
                    }
                } else {
                    Log.w(TAG, "delete: 插件名不是纯文件名，已跳过数据目录清理: $pluginName")
                }
            }
            !file.exists()
        }

    /**
     * 切换插件启用状态
     * Bukkit 标准：文件名以 `-` 前缀表示禁用
     * @return 切换后的新文件名
     */
    suspend fun toggleEnabled(fileName: String, dirName: String): String? = withContext(Dispatchers.IO) {
        val pluginsDir = pluginsDirOf(dirName)
        // 与 delete 同规则：拒绝含目录成分的文件名，避免 `../xxx` 改写服务端目录里的其它文件
        val safe = sanitizeFileName(fileName, "")
        if (safe.isEmpty() || safe != fileName) return@withContext null
        val file = File(pluginsDir, safe)
        if (!file.exists()) return@withContext null
        if (file.canonicalFile.parentFile?.canonicalPath != pluginsDir.canonicalPath) return@withContext null

        val newName = if (safe.startsWith(DISABLED_PREFIX)) safe.substring(1) else "$DISABLED_PREFIX$safe"
        val target = File(pluginsDir, newName)

        // 同步迁移缓存 key
        val oldCache = metaCache.remove(file.absolutePath)
        if (file.renameTo(target)) {
            if (oldCache != null) metaCache[target.absolutePath] = oldCache
            newName
        } else null
    }

    /**
     * 检测某个精选插件是否已安装（同 baseName 视为已安装，不区分大小写和禁用前缀）
     */
    fun isCuratedInstalled(curated: CuratedPlugin, installedList: List<InstalledPlugin>): Boolean {
        val targetBase = curated.targetFileName.removeSuffix(JAR_EXT)
        return installedList.any { it.baseName.equals(targetBase, ignoreCase = true) }
    }

    /**
     * 检测精选插件的更新状态
     * 5 分钟内复用缓存，避免频繁请求 GitHub API
     */
    suspend fun checkCuratedUpdates(installedList: List<InstalledPlugin>): List<CuratedUpdateInfo> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            // 找出需要重新检测的项（未缓存或已过期）
            val needFetch = curatedPlugins.filter { c ->
                val cached = updateCache[c.id]
                cached == null || now - cached.first > UPDATE_CHECK_COOLDOWN_MS
            }

            // 并行请求 GitHub API
            val freshInfos = needFetch.map { c ->
                async {
                    val installed = installedList.find {
                        it.baseName.equals(c.targetFileName.removeSuffix(JAR_EXT), ignoreCase = true)
                    }
                    val installedVersion = installed?.meta?.version
                    try {
                        val (latestVer, releaseUrl) = fetchLatestRelease(c.repo)
                        val hasUpdate = installedVersion != null && !installedVersion.equals(latestVer, ignoreCase = true)
                        CuratedUpdateInfo(c, latestVer, releaseUrl, installedVersion, hasUpdate)
                    } catch (e: Exception) {
                        Log.w(TAG, "checkUpdates failed for ${c.id}: ${e.message}")
                        CuratedUpdateInfo(c, context.getString(R.string.s131), c.homepage, installedVersion, false)
                    }
                }
            }.map { it.await() }

            // 写入缓存
            freshInfos.forEach { info ->
                updateCache[info.curated.id] = now to info
            }

            // 合并缓存与新检测
            curatedPlugins.map { c ->
                updateCache[c.id]?.second ?: run {
                    val installed = installedList.find {
                        it.baseName.equals(c.targetFileName.removeSuffix(JAR_EXT), ignoreCase = true)
                    }
                    CuratedUpdateInfo(c, context.getString(R.string.s131), c.homepage, installed?.meta?.version, false)
                }
            }
        }

    /**
     * 清空更新检测缓存（强制下次重新检测）
     */
    fun invalidateUpdateCache() {
        updateCache.clear()
    }

    // ── 私有辅助 ────────────────────────────────────────────

    private fun pluginsDirOf(dirName: String): File {
        return File(termux.serverDirFor(dirName), "plugins").apply { mkdirs() }
    }

    private fun toInstalledPlugin(f: File): InstalledPlugin {
        val rawName = f.name
        val isEnabled = !rawName.startsWith(DISABLED_PREFIX)
        val baseName = if (isEnabled) rawName else rawName.substring(1)
        val baseNameNoExt = baseName.removeSuffix(JAR_EXT).removeSuffix(JAR_EXT.uppercase(Locale.ROOT))

        val sourceTag = when {
            curatedPlugins.any { it.targetFileName.equals(baseName, ignoreCase = true) } -> "精选"
            else -> "本地"
        }

        // 解析 plugin.yml（带缓存）
        val meta = readPluginMetaCached(f)

        return InstalledPlugin(
            fileName = rawName,
            baseName = baseNameNoExt,
            sizeBytes = f.length(),
            sizeText = formatFileSize(f.length()),
            lastModified = f.lastModified(),
            lastModifiedText = formatTime(f.lastModified()),
            isEnabled = isEnabled,
            sourceTag = sourceTag,
            meta = meta
        )
    }

    /**
     * 读取 jar 内的 plugin.yml / paper-plugin.yml，带 lastModified 缓存
     */
    private fun readPluginMetaCached(f: File): PluginMeta? {
        val lastMod = f.lastModified()
        val cached = metaCache[f.absolutePath]
        if (cached != null && cached.first == lastMod) {
            return cached.second
        }
        val meta = try {
            readPluginMeta(f)
        } catch (e: Exception) {
            Log.w(TAG, "readPluginMeta failed for ${f.name}: ${e.message}")
            null
        }
        metaCache[f.absolutePath] = lastMod to meta
        return meta
    }

    /**
     * 从 jar 内读取 plugin.yml 或 paper-plugin.yml
     * 支持 Bukkit/Spigot/Paper 三种格式
     */
    private fun readPluginMeta(f: File): PluginMeta? {
        JarFile(f).use { jar ->
            // 优先 paper-plugin.yml，其次 plugin.yml
            val entry = jar.getEntry("paper-plugin.yml") ?: jar.getEntry("plugin.yml") ?: return null
            val yamlText = jar.getInputStream(entry).bufferedReader().use { it.readText() }
            return parsePluginYaml(yamlText)
        }
    }

    /**
     * 解析 plugin.yml 文本
     * 简单行解析，兼容 Bukkit/Spigot/Paper 格式，避免引入完整 YAML 库
     * 仅提取顶层字段，不支持嵌套结构
     */
    private fun parsePluginYaml(text: String): PluginMeta? {
        // 收集顶层 key: value 对（缩进为 0 的行）
        val topFields = mutableMapOf<String, String>()
        var currentKey: String? = null
        var currentValue = StringBuilder()
        var inList = false

        fun flush() {
            if (currentKey != null) {
                val value = currentValue.toString().trim()
                if (value.isNotEmpty()) {
                    topFields[currentKey!!] = value
                }
                currentKey = null
                currentValue = StringBuilder()
            }
        }

        for (line in text.lines()) {
            if (line.isBlank() || line.trim().startsWith("#")) continue
            val indent = line.takeWhile { it == ' ' }.length
            val trimmed = line.trim()

            if (indent == 0 && trimmed.contains(":")) {
                // 顶层字段
                flush()
                val idx = trimmed.indexOf(":")
                currentKey = trimmed.substring(0, idx).trim()
                val rest = trimmed.substring(idx + 1).trim()
                if (rest.isNotEmpty()) {
                    currentValue.append(rest)
                    inList = false
                } else {
                    // 可能是多行列表（下一行检查）
                    inList = false
                }
            } else if (currentKey != null) {
                // 多行列表项
                if (trimmed.startsWith("- ") || trimmed.startsWith("-")) {
                    val item = trimmed.removePrefix("-").trim().trim('\'').trim('"')
                    if (currentValue.isNotEmpty()) currentValue.append(", ")
                    currentValue.append(item)
                    inList = true
                } else if (!inList) {
                    // 多行字符串值（换行续行）
                    currentValue.append(" ").append(trimmed)
                }
            }
        }
        flush()

        val name = topFields["name"] ?: return null
        val version = topFields["version"] ?: "未知"
        val mainClass = topFields["main"] ?: topFields["bootstrap"] ?: ""
        val author = topFields["author"] ?: topFields["authors"] ?: "未知"
        val description = topFields["description"] ?: ""
        val apiVersion = topFields["api-version"] ?: ""
        val depends = topFields["depend"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val softDepends = topFields["softdepend"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        return PluginMeta(
            name = name,
            version = version,
            mainClass = mainClass,
            author = author,
            description = description,
            apiVersion = apiVersion,
            depends = depends,
            softDepends = softDepends
        )
    }

    /**
     * 拉取 GitHub API 响应体（跟随重定向）。
     *
     * 直连优先（api.github.com 延迟最低），失败时退到 `cdn.gh-proxy.com`——它是 2026-10 实测
     * 唯一能代理 `api.github.com` 的镜像（ghfast.top / gh-proxy.com / ghproxy.net 对 api 请求均 403）。
     */
    private fun fetchGithubApiBody(path: String): String {
        val candidates = listOf(
            "https://api.github.com/$path",
            "https://cdn.gh-proxy.com/https://api.github.com/$path"
        )
        var last: Exception? = null
        for (api in candidates) {
            val conn = (URL(api).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "McServerManager/1.0 (Android)")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw RuntimeException("GitHub API HTTP $code")
                return conn.inputStream.bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                last = e
                Log.w(TAG, "GitHub API 源失败，尝试下一个: $api (${e.message})")
            } finally {
                conn.disconnect()
            }
        }
        throw last ?: RuntimeException("GitHub API unavailable")
    }

    /**
     * 调用 GitHub API 获取最新 Release 信息
     * @return (tag_name, html_url)
     */
    private fun fetchLatestRelease(repo: String): Pair<String, String> {
        val body = fetchGithubApiBody("repos/$repo/releases/latest")
        // 简单 JSON 解析（避免引入完整 JSON 库）
        val tagRegex = """"tag_name"\s*:\s*"([^"]+)"""".toRegex()
        val urlRegex = """"html_url"\s*:\s*"([^"]+)"""".toRegex()
        val tag = tagRegex.find(body)?.groupValues?.get(1)
            ?: throw RuntimeException("未找到 tag_name 字段")
        val releaseUrl = urlRegex.find(body)?.groupValues?.get(1) ?: "https://github.com/$repo/releases"
        return tag to releaseUrl
    }

    /**
     * 解析 GitHub 最新 release 中匹配指定名称模式的 .jar asset 下载直链。
     * 用于 asset 文件名带版本号的仓库（如 ViaVersion-4.x.x.jar，无固定 latest/download 文件名）。
     * 逐 asset 对象解析（name 在 browser_download_url 之前，按段配对），排除 -sources/-dev/-javadoc 包。
     * @param repo GitHub 仓库全名（owner/repo）
     * @param pattern asset 文件名包含的模式（如 "ViaVersion"）
     * @return 匹配的 browser_download_url；解析失败返回 null
     */
    fun resolveLatestAsset(repo: String, pattern: String): String? {
        return try {
            val body = fetchGithubApiBody("repos/$repo/releases/latest")
            // 提取 assets 数组内容（简单正则解析，避免引入完整 JSON 库）
            val assetsBody = Regex("\"assets\"\\s*:\\s*\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL)
                .find(body)?.groupValues?.get(1) ?: return null
            val nameRegex = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")
            val urlRegex = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"")
            val names = nameRegex.findAll(assetsBody)
                .map { it.range.first to it.groupValues[1] }.toList()
            // GitHub API 每个 asset 对象内 name 位于 browser_download_url 之前：
            // 以 name 位置为界切段，段内取 browser_download_url 保证配对正确
            names.forEachIndexed { i, (pos, name) ->
                val segEnd = if (i + 1 < names.size) names[i + 1].first else assetsBody.length
                val segment = assetsBody.substring(pos, segEnd)
                val assetUrl = urlRegex.find(segment)?.groupValues?.get(1) ?: return@forEachIndexed
                val lower = name.lowercase()
                if (lower.contains(pattern.lowercase()) &&
                    lower.endsWith(".jar") &&
                    !lower.contains("-sources") &&
                    !lower.contains("-dev") &&
                    !lower.contains("-javadoc")
                ) {
                    return assetUrl
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun ensureJarExtension(name: String): String {
        return if (name.endsWith(JAR_EXT, ignoreCase = true)) name else "$name$JAR_EXT"
    }

    /**
     * 净化来自 SAF（`_display_name` / `lastPathSegment`）或远端的文件名。
     *
     * 只取最后一段路径，拒绝空串、`.`、`..`，否则回退 [fallback]。不净化的话，
     * provider 返回 `../../server.jar` 这类名字会经 `File(dir, name)` 写出目录之外。
     * 思路与 `ServerImportLayout.importedJarFileName` 一致。
     */
    private fun sanitizeFileName(name: String?, fallback: String): String {
        val base = name?.substringAfterLast('/')?.substringAfterLast('\\')?.trim().orEmpty()
        return if (base.isEmpty() || base == "." || base == "..") fallback else base
    }

    private fun queryFileName(uri: Uri): String? {
        return try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex("_display_name")
                    if (idx >= 0) it.getString(idx) else uri.lastPathSegment
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "queryFileName failed: ${e.message}")
            uri.lastPathSegment
        }
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format(Locale.ROOT, "%.2f MB", mb)
    }

    private fun formatTime(ts: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(ts))
    }

    private fun downloadTo(
        url: String,
        target: File,
        onProgress: (Long, Long, Long) -> Unit
    ) = kotlinx.coroutines.runBlocking {
        MultiThreadDownloader.download(
            url = url,
            target = target,
            onProgress = onProgress,
            onLog = { logMsg -> Log.i(TAG, "download: $logMsg") }
        )
    }

    // ── 模组管理（Fabric/Forge 的 mods/ 目录） ─────────────────────

    /** 模组目录：{serverDir}/mods */
    fun modsDirOf(dirName: String): File =
        File(termux.serverDirFor(dirName), "mods")

    /** 已安装模组条目（简化，不解析 jar 内元信息） */
    data class ModEntry(
        val fileName: String,
        val baseName: String,
        val sizeText: String,
        val isEnabled: Boolean
    )

    /** 读取 mods/ 目录下的模组列表。除标准 .jar/.jar.disabled 外，还识别历史版本
     *  遗留的禁用形态（.disabled、`-` 前缀、无后缀的 jar 文件），避免条目凭空消失。 */
    fun readMods(dirName: String): List<ModEntry> {
        val dir = modsDirOf(dirName)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && isModCandidate(f) }
            ?.sortedBy { it.name.lowercase() }
            ?.map { f ->
                val (baseName, isEnabled) = parseModFileName(f.name)
                ModEntry(f.name, baseName, formatSize(f.length()), isEnabled)
            } ?: emptyList()
    }

    /** 切换模组启用状态（.jar ↔ .jar.disabled），并自愈历史遗留的坏文件名。 */
    suspend fun toggleModEnabled(fileName: String, dirName: String): String? = withContext(Dispatchers.IO) {
        val dir = modsDirOf(dirName)
        val file = File(dir, fileName)
        if (!file.exists()) return@withContext null
        val (_, isEnabled) = parseModFileName(fileName)
        val target = modToggleTarget(fileName, !isEnabled)
        if (target == fileName) return@withContext null
        return@withContext if (file.renameTo(File(dir, target))) target else null
    }

    /** 删除模组文件 */
    suspend fun deleteMod(fileName: String, dirName: String): Boolean = withContext(Dispatchers.IO) {
        val dir = modsDirOf(dirName)
        val file = File(dir, fileName)
        if (!file.exists()) return@withContext false
        file.delete() || run { termux.execOnce("rm", "-f", file.absolutePath); !file.exists() }
    }

    /** 从 URL 下载安装模组到 mods/ 目录 */
    suspend fun installModFromUrl(
        url: String,
        targetFileName: String,
        dirName: String,
        onProgress: (Long, Long, Long) -> Unit = { _, _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val dir = modsDirOf(dirName).apply { mkdirs() }
        val target = File(dir, ensureJarExtension(sanitizeFileName(targetFileName, "mod.jar")))
        val backup: File? = if (target.exists()) File(dir, "${target.name}.bak") else null
        if (backup != null) target.renameTo(backup)
        try {
            downloadTo(url, target, onProgress)
            if (!target.exists() || target.length() < 1024) {
                throw RuntimeException(context.getString(R.string.s128, target.length()))
            }
            backup?.takeIf { it.exists() }?.delete()
            target
        } catch (e: Exception) {
            if (backup?.exists() == true) backup.renameTo(target)
            throw e
        }
    }

    /** 从本地 Uri 上传模组到 mods/ 目录 */
    suspend fun installModFromUri(uri: Uri, dirName: String, fallbackName: String = "mod.jar"): String =
        withContext(Dispatchers.IO) {
            val dir = modsDirOf(dirName).apply { mkdirs() }
            val safeName = ensureJarExtension(sanitizeFileName(queryFileName(uri), fallbackName))
            val target = File(dir, safeName)
            val input = context.contentResolver.openInputStream(uri)
                ?: throw RuntimeException(context.getString(R.string.s129, uri.toString()))
            input.use { ins ->
                FileOutputStream(target).use { fos ->
                    ins.copyTo(fos, bufferSize = 64 * 1024)
                }
            }
            if (target.length() < 1024) {
                target.delete()
                throw RuntimeException(context.getString(R.string.s130))
            }
            target.name
        }

    // ── 精选模组（GitHub 动态解析最新版） ─────────────────────────

    data class CuratedMod(
        val id: String,
        val name: String,
        val author: String,
        val description: String,
        val avatarText: String,
        val homepage: String,
        val targetFileName: String,
        val repo: String,
        val githubAssetPattern: String
    )

    /** 精选模组已下线（统一从 Modrinth 获取，避免失效链接） */
    val curatedMods: List<CuratedMod> = emptyList()

    /** 当前核心的 mods 目录路径 */
    fun currentModsPath(dirName: String): String = modsDirOf(dirName).absolutePath

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    // ── Modrinth 模组获取（开放 API，需 User-Agent） ───────────────

    @Serializable
    data class ModrinthHit(
        val title: String = "",
        val slug: String = "",
        val description: String = "",
        val author: String = "",
        val downloads: Long = 0,
        val icon_url: String = "",
        val categories: List<String> = emptyList()
    )

    @Serializable
    private data class ModrinthSearchResponse(val hits: List<ModrinthHit> = emptyList())

    @Serializable
    private data class ModrinthVersion(val version_type: String = "", val files: List<ModrinthFile> = emptyList())

    @Serializable
    private data class ModrinthFile(val url: String = "", val primary: Boolean = false)

    private val modrinthJson = Json { ignoreUnknownKeys = true }

    /** 搜索 Modrinth 模组/插件（多加载器 OR 过滤 + 版本筛选 + 排序，最多 20 条）
     * @param loaders 加载器列表（fabric/forge/quilt/neoforge/bukkit/paper 等），空表示不限
     * @param mcVersion MC 游戏版本筛选（不匹配即返回空），空表示不限
     * @param projectType 项目类型：mod / plugin
     * @param sort 排序：relevance/downloads/newest
     */
    fun searchModrinth(
        query: String,
        loaders: List<String>,
        sort: String,
        projectType: String = "mod",
        mcVersion: String = "",
        offset: Int = 0
    ): List<ModrinthHit> {
        if (query.isBlank()) return emptyList()
        return try {
            val facets = buildString {
                append("[")
                if (loaders.isNotEmpty()) {
                    append("[")
                    loaders.forEachIndexed { i, l ->
                        if (i > 0) append(",")
                        append("\"categories:$l\"")
                    }
                    append("],")
                }
                if (mcVersion.isNotBlank()) {
                    append("[\"versions:$mcVersion\"],")
                }
                append("[\"project_type:$projectType\"]")
                append("]")
            }
            val urlStr = "https://api.modrinth.com/v2/search?query=${java.net.URLEncoder.encode(query, "UTF-8")}" +
                "&facets=${java.net.URLEncoder.encode(facets, "UTF-8")}" +
                "&index=${java.net.URLEncoder.encode(sort, "UTF-8")}&limit=20&offset=$offset"
            val body = fetchModrinthText(urlStr)
            modrinthJson.decodeFromString<ModrinthSearchResponse>(body).hits
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 拉取 Modrinth 支持的 MC 游戏版本列表（过滤 rc/pre/snapshot，取最新 N 个） */
    private val gameVersionCache = ConcurrentHashMap<String, Pair<Long, List<String>>>()

    fun fetchModrinthGameVersions(limit: Int = 40): List<String> {
        val now = System.currentTimeMillis()
        val cached = gameVersionCache["all"]
        if (cached != null && now - cached.first < 10 * 60 * 1000L) {
            return cached.second
        }
        val versions = try {
            val body = fetchModrinthText("https://api.modrinth.com/v2/tag/game_version")
            val raw = modrinthJson.decodeFromString<List<ModrinthGameVersion>>(body).map { it.version }
            raw.filter { v ->
                v.matches(Regex("\\d+\\.\\d+(\\.\\d+)?")) &&
                    !v.contains("rc", ignoreCase = true) &&
                    !v.contains("pre", ignoreCase = true) &&
                    !v.contains("snapshot", ignoreCase = true)
            }
                .distinct()
                .sortedWith(compareByDescending { versionKey(it) })
                .take(limit)
        } catch (e: Exception) {
            DEFAULT_GAME_VERSIONS
        }
        if (versions.isNotEmpty()) gameVersionCache["all"] = now to versions
        return versions
    }

    /** MC 版本 → 可比较排序键（1.20.4 → 1040200） */
    private fun versionKey(v: String): Long {
        val parts = v.split(".").map { it.toIntOrNull() ?: 0 }
        return when (parts.size) {
            1 -> parts[0] * 1_000_000L
            2 -> parts[0] * 1_000_000L + parts[1] * 1_000L
            else -> parts[0] * 1_000_000L + parts[1] * 1_000L + parts[2]
        }
    }

    @Serializable
    private data class ModrinthGameVersion(val version: String = "")

    private val DEFAULT_GAME_VERSIONS = listOf(
        "1.21.9", "1.21.8", "1.21.7", "1.21.6", "1.21.5", "1.21.4", "1.21.3", "1.21.2", "1.21.1", "1.21",
        "1.20.6", "1.20.5", "1.20.4", "1.20.3", "1.20.2", "1.20.1", "1.20",
        "1.19.4", "1.19.3", "1.19.2", "1.19.1", "1.19",
        "1.18.2", "1.18.1", "1.18",
        "1.17.1", "1.17",
        "1.16.5", "1.16.4", "1.16.3", "1.16.2", "1.16.1", "1.16"
    )

    /** 服务端核心对应的 Modrinth 加载器白名单（与服务端核心选择界面 9 类核心一一对应）：
     *  模组加载器池（5）：Fabric/Forge/NeoForge/Quilt/Vanilla
     *  插件加载器池（4）：Paper/Purpur/Velocity/BungeeCord
     *  两类筛选池完全隔离，其余 Modrinth 加载器不进入筛选列表。 */
    val MOD_LOADERS = listOf("fabric", "forge", "neoforge", "quilt", "vanilla")
    val PLUGIN_LOADERS = listOf("paper", "purpur", "velocity", "bungeecord")

    /** 拉取 Modrinth 全部可用加载器列表 */
    fun fetchModrinthLoaders(): List<String> {
        return try {
            val body = fetchModrinthText("https://api.modrinth.com/v2/tag/loader")
            modrinthJson.decodeFromString<List<ModrinthLoader>>(body).map { it.name }
        } catch (e: Exception) {
            listOf("fabric", "forge", "quilt", "neoforge")
        }
    }

    /** 模组筛选池：仅保留模组专用加载器（顺序与核心选择界面一致） */
    fun filterModLoaders(all: List<String>): List<String> =
        MOD_LOADERS.filter { it in all }

    /** 插件筛选池：仅保留插件专用加载器（顺序与核心选择界面一致） */
    fun filterPluginLoaders(all: List<String>): List<String> =
        PLUGIN_LOADERS.filter { it in all }

    @Serializable
    private data class ModrinthLoader(val name: String = "")

    /** 解析 Modrinth 模组在指定 MC 版本+加载器下的最新 release 下载直链 */
    fun resolveModrinthDownload(slug: String, mcVersion: String, loader: String): String? {
        return try {
            val gameVersions = java.net.URLEncoder.encode("[\"$mcVersion\"]", "UTF-8")
            val loaders = java.net.URLEncoder.encode("[\"$loader\"]", "UTF-8")
            // 降级策略：精确(mcVersion+loader) → 仅 mcVersion → 仅 loader → 项目最新 release
            // 解决部分插件仅标注 paper 未标注 bukkit 等导致精确查询为空的问题
            val queries = listOf(
                "?game_versions=$gameVersions&loaders=$loaders",
                "?game_versions=$gameVersions",
                "?loaders=$loaders",
                ""
            )
            for (query in queries) {
                val body = fetchModrinthText("https://api.modrinth.com/v2/project/$slug/version$query")
                val versions = runCatching {
                    modrinthJson.decodeFromString<List<ModrinthVersion>>(body)
                }.getOrNull()
                val v = versions?.firstOrNull { it.version_type == "release" }
                    ?: versions?.firstOrNull()
                    ?: continue
                val url = v.files.firstOrNull { it.primary }?.url ?: v.files.firstOrNull()?.url
                if (url != null) return url
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /** 读取 URL 文本（Modrinth 要求 User-Agent） */
    private fun fetchModrinthText(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "McServerManager/1.0 (mcserver-manager)")
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}