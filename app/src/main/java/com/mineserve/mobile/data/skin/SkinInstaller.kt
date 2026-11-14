package com.mineserve.mobile.data.skin

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.mineserve.mobile.BuildConfig
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 主题包安装器：解压 → 校验 → 原子落盘，另外负责导出与删除。
 *
 * 设计上的两条硬约束：
 * 1. **先落 staging 再 rename**：断流/杀进程只会留下 `.staging-*`，不会出现「列表里有、点开就崩」的半个主题。
 * 2. **校验只有一份实现**：`install(uri)` 与 `installStream()` 只差第一步（落盘 vs 直接用流），
 *    落到临时文件之后统一走 [installFrom]。安全逻辑写两遍迟早漏改一处。
 */
class SkinInstaller(private val context: Context) {

    /** validate() 失败时写入的原因，供上层拼错误提示（避免为每个失败分支定义异常类型）。 */
    private var lastError: String = "主题包校验失败"

    sealed interface Result {
        data class Ok(val manifest: SkinManifest, val warnings: List<String>) : Result
        data class Failed(val reason: String) : Result
    }

    fun themesDir(): File = File(context.filesDir, "themes").apply { mkdirs() }

    /** 从 SAF uri 安装。整个方法必须在 Dispatchers.IO 上调用。 */
    fun install(uri: Uri): Result {
        val tmp = File(context.cacheDir, "import-${System.nanoTime()}.mctheme")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out -> copyLimited(input, out, SkinPack.MAX_TOTAL_BYTES) }
            } ?: return Result.Failed("无法读取文件")
            installFrom(tmp)
        } catch (t: Throwable) {
            Log.w(TAG, "install(uri) failed", t)
            Result.Failed(friendly(t))
        } finally {
            tmp.delete()
        }
    }

    /**
     * 从输入流安装（首启解压内置包走这里）。
     *
     * 内置包与用户包共用同一条路径：只有一套加载代码，才不会出现「内置主题能显示、导入主题不显示」的分叉。
     */
    fun installStream(input: InputStream): Result {
        val tmp = File(context.cacheDir, "import-${System.nanoTime()}.mctheme")
        return try {
            tmp.outputStream().use { out -> copyLimited(input, out, SkinPack.MAX_TOTAL_BYTES) }
            installFrom(tmp, allowBuiltin = true)
        } catch (t: Throwable) {
            Log.w(TAG, "installStream failed", t)
            Result.Failed(friendly(t))
        } finally {
            tmp.delete()
        }
    }

    /** 落盘之后的公共流程。[allowBuiltin] 为 true 时允许写内置主题 id（仅首启 bootstrap 用）。 */
    private fun installFrom(tmp: File, allowBuiltin: Boolean = false): Result {
        if (tmp.length() <= 0) return Result.Failed("主题包为空")

        // 1. 扫一遍条目做限额与路径检查（不解压）
        val entries = scan(tmp) ?: return Result.Failed(
            lastError.takeIf { it.isNotBlank() && it != "主题包校验失败" }
                ?: "不是合法的主题包（ZIP 解析失败）"
        )
        if (entries.size > SkinPack.MAX_ENTRIES) {
            return Result.Failed("主题包文件数过多（${entries.size} > ${SkinPack.MAX_ENTRIES}）")
        }

        // 2. 解压到 staging
        val staging = File(themesDir(), ".staging-${System.nanoTime()}").apply { mkdirs() }
        return try {
            unzip(tmp, staging, entries)
            val checked = validate(staging) ?: return Result.Failed(lastError)

            // 内置 id 不允许被用户包覆盖（防止恶意包冒充内置主题）。
            // allowBuiltin 只在首启解压内置包时为 true。
            if (!allowBuiltin && checked.manifest.id in SkinRuntime.BUILTIN_IDS) {
                return Result.Failed("「${checked.manifest.id}」是内置主题，不能通过导入覆盖")
            }

            // 3. 原子落位 + 留一份原始 zip 供导出（保证导出包与导入包字节一致，含 checksums.json）
            val target = File(themesDir(), checked.manifest.id)
            replaceDir(staging, target)
            runCatching { tmp.copyTo(File(themesDir(), "${checked.manifest.id}.source"), overwrite = true) }
            return Result.Ok(checked.manifest, checked.warnings)
        } catch (t: Throwable) {
            Log.w(TAG, "installFrom failed", t)
            Result.Failed(friendly(t))
        } finally {
            runCatching { staging.deleteRecursively() }
        }
    }

    /** 内置包安装期间允许写同名目录（`bootstrap` 用）。 */
    /** 导出：优先原样复制安装时留下的 source 包。 */
    fun export(id: String, out: OutputStream) {
        val source = File(themesDir(), "$id.source")
        if (source.isFile) {
            source.inputStream().use { it.copyTo(out) }
            return
        }
        // 没有 source（例如主题目录是手动放进去的）：现场重新压缩
        val dir = File(themesDir(), id)
        if (!dir.isDirectory) throw IllegalStateException("主题不存在：$id")
        ZipOutputStream(out).use { zip ->
            dir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { file ->
                val rel = file.relativeTo(dir).invariantSeparatorsPath
                if (rel == "checksums.json") return@forEach
                zip.putNextEntry(ZipEntry(rel))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** 删除已安装主题目录（含其 source 包）。不允许删当前生效主题 —— 由 UI 层先切走。 */
    fun delete(id: String) {
        if (!SkinPack.validId(id)) return
        File(themesDir(), id).deleteRecursively()
        File(themesDir(), "$id.source").delete()
    }

    // ── 内部实现 ────────────────────────────────────────────────

    /** 读到 limit + 1 就抛：`openInputStream` 也可能是永不 EOF 的管道。 */
    private fun copyLimited(input: InputStream, out: OutputStream, limit: Long) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n <= 0) break
            total += n
            if (total > limit) throw IllegalStateException("主题包超过 ${limit / 1024 / 1024}MiB")
            out.write(buffer, 0, n)
        }
        out.flush()
    }

    /** null = 不是合法 zip。条目名与体积在这里就拦掉，避免解压阶段才发现。 */
    private fun scan(zip: File): List<ZipEntry>? {
        val entries = ArrayList<ZipEntry>()
        var total = 0L
        try {
            ZipFile(zip).use { zf ->
                val e = zf.entries()
                while (e.hasMoreElements()) {
                    val entry = e.nextElement()
                    SkinPack.unsafeName(entry.name)?.let {
                        lastError = "非法条目 ${entry.name}：$it"
                        return null
                    }
                    SkinPack.unsupportedExt(entry.name)?.let {
                        lastError = "非法条目 ${entry.name}：$it"
                        return null
                    }
                    // entry.size == -1（data descriptor）时不可信：单文件按上限判，
                    // 但总量不累加 —— 真正的总量上限在 unzip 里按「已读字节」硬校验，
                    // 在这里累加会把几个用 data descriptor 的正常包误判成炸弹。
                    val size = if (entry.size < 0) SkinPack.MAX_FILE_BYTES else entry.size
                    if (size > SkinPack.MAX_FILE_BYTES) {
                        lastError = "${entry.name} 体积超过 8MiB"
                        return null
                    }
                    if (entry.size >= 0) {
                        total += size
                        if (total > SkinPack.MAX_TOTAL_BYTES) {
                            lastError = "主题包解压后超过 24MiB"
                            return null
                        }
                    }
                    entries.add(entry)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "scan failed", t)
            return null
        }
        if (entries.isEmpty()) return null
        return entries
    }

    /** 只解 allowed 里的条目，且逐条累计已读字节二次校验（ZipEntry.size 可能撒谎）。 */
    private fun unzip(zip: File, dest: File, allowed: List<ZipEntry>) {
        val allowedNames = allowed.map { it.name }.toHashSet()
        var total = 0L
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.name !in allowedNames) {
                    zis.closeEntry()
                    continue
                }
                val target = File(dest, entry.name)
                if (entry.isDirectory) {
                    target.mkdirs()
                    zis.closeEntry()
                    continue
                }
                target.parentFile?.mkdirs()
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var fileBytes = 0L
                    while (true) {
                        val n = zis.read(buffer)
                        if (n <= 0) break
                        fileBytes += n
                        total += n
                        if (fileBytes > SkinPack.MAX_FILE_BYTES) {
                            throw IllegalStateException("${entry.name} 解压后超过 8MiB")
                        }
                        if (total > SkinPack.MAX_TOTAL_BYTES) {
                            throw IllegalStateException("主题包解压后超过 24MiB")
                        }
                        out.write(buffer, 0, n)
                    }
                }
                zis.closeEntry()
            }
        }
    }

    private data class Checked(val manifest: SkinManifest, val warnings: List<String>)

    /** 校验解压后的目录。失败返回 null 并写 [lastError]。 */
    private fun validate(dir: File): Checked? {
        fun fail(reason: String): Checked? {
            lastError = reason
            return null
        }

        val manifestFile = File(dir, "manifest.json")
        if (!manifestFile.isFile) return fail("缺少 manifest.json")
        val tokensFileName = runCatching {
            SkinPack.parseManifest(manifestFile.readText()).tokens.ifBlank { "tokens.json" }
        }.getOrDefault("tokens.json")
        if (!File(dir, tokensFileName).isFile) return fail("缺少 $tokensFileName")

        val manifest = runCatching { SkinPack.parseManifest(manifestFile.readText()) }
            .getOrElse { return fail("manifest.json 解析失败：${it.message}") }

        if (manifest.schemaVersion != SkinPack.SCHEMA_VERSION) {
            return fail("主题包格式版本 ${manifest.schemaVersion} 不受支持，请升级 App")
        }
        if (!SkinPack.validId(manifest.id)) {
            return fail("id 非法：${manifest.id}（只允许小写字母、数字、. _ -，2–64 字符）")
        }
        for ((key, maxLen) in SkinPack.textLimits) {
            val value = when (key) {
                "name" -> manifest.name
                "author" -> manifest.author
                "description" -> manifest.description
                else -> manifest.version
            }
            if (value.isBlank()) return fail("manifest.$key 不能为空")
            if (value.length > maxLen) return fail("manifest.$key 超过 $maxLen 字符")
        }
        if (manifest.minAppVersionCode > BuildConfig.VERSION_CODE) {
            return fail("该主题需要 App 版本 ${manifest.minAppVersionCode} 或更高，当前为 ${BuildConfig.VERSION_CODE}")
        }

        val tokens = runCatching { SkinPack.parseTokens(File(dir, tokensFileName).readText()) }
            .getOrElse { return fail("$tokensFileName 解析失败：${it.message}") }

        // 颜色：32 + 24 × 2 套，逐键检查，保留第一个出错键名方便主题作者定位
        for (side in listOf("light", "dark")) {
            val colors = if (side == "light") tokens.color.light else tokens.color.dark
            if (colors == null) return fail("tokens.color.$side 缺失")
            colors.entries.forEach { (key, value) ->
                if (parseHexColor(value) == null) {
                    return fail("tokens.color.$side.$key 不是合法颜色：${value ?: "缺失"}")
                }
            }
            val ext = if (side == "light") tokens.ext.light else tokens.ext.dark
            if (ext == null) return fail("tokens.ext.$side 缺失")
            ext.entries.forEach { (key, value) ->
                if (parseHexColor(value) == null) {
                    return fail("tokens.ext.$side.$key 不是合法颜色：${value ?: "缺失"}")
                }
            }
        }

        // 资产：路径必须在包内（canonicalPath 二次校验，防 assets/../../x 逃逸），图片必须可解码
        val backgroundPath = manifest.assets["background"]
            ?: return fail("manifest.assets.background 缺失（整屏背景是唯一必需资产）")
        val root = dir.canonicalPath
        // assetsDark 是可选的一套深色贴图，校验规则与 assets 完全一致
        listOf("assets" to manifest.assets, "assetsDark" to manifest.assetsDark).forEach { (label, map) ->
            map.forEach { (slot, path) ->
                SkinPack.unsafeName(path)?.let { return fail("$label.$slot 路径非法：$it") }
                val file = File(dir, path)
                val canonical = runCatching { file.canonicalPath }.getOrNull()
                    ?: return fail("$label.$slot 路径非法：$path")
                if (canonical != root && !canonical.startsWith(root + File.separator)) {
                    return fail("$label.$slot 指向包外路径：$path")
                }
                if (!file.isFile) return fail("$label.$slot 指向的 $path 不存在于包内")
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    return fail("$path 不是可解码的图片")
                }
                if (maxOf(bounds.outWidth, bounds.outHeight) > SkinPack.MAX_IMAGE_EDGE) {
                    return fail("$path 单边 ${maxOf(bounds.outWidth, bounds.outHeight)}px 超过上限 ${SkinPack.MAX_IMAGE_EDGE}px")
                }
            }
        }
        if (backgroundPath.isBlank()) return fail("manifest.assets.background 为空")

        if (manifest.preview != null) {
            val preview = File(dir, manifest.preview)
            if (!preview.isFile) {
                Log.w(TAG, "preview 缺失：${manifest.preview}")
            }
        }

        // checksums（可选）：存在就必须全部匹配
        val checksumsFile = File(dir, "checksums.json")
        if (checksumsFile.isFile) {
            val problem = verifyChecksums(dir, checksumsFile)
            if (problem != null) return fail(problem)
        }

        return Checked(manifest, contrastWarnings(tokens))
    }

    private fun verifyChecksums(dir: File, checksumsFile: File): String? = runCatching {
        val node = SkinPack.json.parseToJsonElement(checksumsFile.readText()).jsonObject
        val algorithm = node["algorithm"]?.jsonPrimitive?.content
        if (algorithm != "sha256") return "checksums.json.algorithm 必须为 sha256"
        val files = node["files"]?.jsonObject ?: return "checksums.json.files 缺失"
        files.forEach { (path, expectEl) ->
            SkinPack.unsafeName(path)?.let { return "checksums 条目名非法：$path" }
            val file = File(dir, path)
            if (!file.isFile) return "checksums 引用了不存在的 $path"
            val actual = "sha256:" + sha256(file)
            val expected = expectEl.jsonPrimitive.content
            if (actual != expected) return "$path 校验和不匹配（包可能被改动过）"
        }
        null
    }.getOrElse { "checksums.json 解析失败：${it.message}" }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 对比度不拦截，只产出警告文案。 */
    private fun contrastWarnings(tokens: SkinTokens): List<String> {
        val out = ArrayList<String>()
        for (side in listOf("light", "dark")) {
            val colors = (if (side == "light") tokens.color.light else tokens.color.dark) ?: continue
            val ext = (if (side == "light") tokens.ext.light else tokens.ext.dark) ?: continue
            val label = if (side == "light") "浅色" else "深色"
            CONTRAST_RULES.forEach { rule ->
                val table = if (rule.group == "color") colors.byKey(rule.fgKey) else ext.byKey(rule.fgKey)
                val bgTable = if (rule.group == "color") colors.byKey(rule.bgKey) else ext.byKey(rule.bgKey)
                val fg = parseRgba(table) ?: return@forEach
                val bg = parseRgba(bgTable) ?: return@forEach
                val ratio = contrastRatio(fg, bg)
                if (ratio < rule.min) {
                    out.add("$label · ${rule.label} 对比度 ${"%.2f".format(ratio)}:1 < ${rule.min}:1")
                }
            }
        }
        return out
    }

    /** 同分区 rename 是原子的；跨分区（或 rename 失败）退回「复制 + 删除」。 */
    private fun replaceDir(src: File, target: File) {
        val parent = target.parentFile ?: return
        val trash = File(parent, ".trash-${System.nanoTime()}")
        if (target.exists()) {
            if (!target.renameTo(trash)) {
                target.deleteRecursively()
            }
        }
        if (!src.renameTo(target)) {
            src.copyRecursively(target, overwrite = true)
            src.deleteRecursively()
        }
        trash.deleteRecursively()
    }

    private fun friendly(t: Throwable): String = when (t) {
        is IllegalStateException -> t.message ?: "导入失败"
        else -> "导入失败：${t.message ?: t.javaClass.simpleName}"
    }

    private companion object {
        const val TAG = "SkinInstaller"
    }
}
