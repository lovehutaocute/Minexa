package com.mineserve.mobile.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * BDS 附加包（行为包 / 资源包）的扫描、manifest 解析与世界注册表读写。
 *
 * 全部结论都来自真实 BDS 实测（`docs/BDS-ADDONS-WORLDS-VERIFICATION.md`），要点：
 * - 包目录：`behavior_packs/`、`resource_packs/`（全服）、`worlds/<世界>/behavior_packs`
 *   （单世界，**同名 uuid 世界内优先**）、`development_*`（1.19 起都生效）；
 * - 世界 `world_behavior_packs.json` / `world_resource_packs.json` 里 `pack_id` 必须是
 *   **`header.uuid`**，用 modules 的 uuid 会被静默忽略；
 * - 数组顺序 = 加载栈顺序（`Pack Stack - [00] [01]`）；
 * - 版本匹配：同 major 且「磁盘版本 ≤ 世界请求版本」才算命中，否则**静默**不加载
 *   （1.19/1.21 连警告都没有）。
 *
 * 本类刻意做成**纯路径 + 纯 JSON 逻辑**（只依赖 java.io 与 kotlinx.serialization），
 * 不引用 Termux/Android，方便单元测试——包管理写错一个 uuid，用户看到的就是
 * "包没生效但服务器一切正常"，这是最难靠肉眼发现的一类问题。
 */
object BdsPacks {

    /** 包类型。 */
    enum class Kind(val jsonName: String, val dirName: String) {
        BEHAVIOR("behavior", "behavior_packs"),
        RESOURCE("resource", "resource_packs");

        val devDirName: String get() = "development_$dirName"
        val registryName: String get() = "world_${jsonName}_packs.json"
    }

    /** 包的来源（决定能不能删、能不能改）。 */
    enum class Scope {
        /** 服务端根目录 `behavior_packs/` / `resource_packs/`。 */
        GLOBAL,

        /** `worlds/<世界>/behavior_packs/`，随世界一起导出/删除。 */
        WORLD,

        /** `development_*`，官方指定的开发目录（同样生效）。 */
        DEV,

        /** 官方内置包（vanilla / chemistry / editor…），只能看不能删。 */
        BUILTIN
    }

    /** manifest 里的一条依赖。 */
    data class Dependency(
        val uuid: String?,
        /** 内置脚本模块（`@minecraft/server`）时的模块名，磁盘上没有对应包。 */
        val moduleName: String?,
        val version: List<Int>
    ) {
        val isScriptModule: Boolean get() = uuid == null && moduleName != null
    }

    /** 一个已安装的包。 */
    data class Pack(
        val dir: File,
        val kind: Kind,
        val scope: Scope,
        val uuid: String,
        val version: List<Int>,
        val name: String,
        val description: String,
        val minEngineVersion: List<Int>,
        val dependencies: List<Dependency>,
        /** 解析阶段发现的问题；非空时 BDS 一定会拒绝加载这个包。 */
        val problems: List<String>,
        val sizeBytes: Long,
        val sizeTruncated: Boolean
    ) {
        /** 目录名（面板上给人看的标识；世界注册表用的是 uuid）。 */
        val dirName: String get() = dir.name

        /** 版本文本 `1.2.3`。 */
        val versionText: String get() = version.joinToString(".")

        val valid: Boolean get() = problems.isEmpty()
    }

    /** 世界注册表里的一条记录。 */
    data class Entry(val uuid: String, val version: List<Int>) {
        val versionText: String get() = version.joinToString(".")
    }

    /** 扫描结果。 */
    data class Scan(
        val packs: List<Pack>,
        /** 各包目录里躺着的压缩包（.mcpack/.mcaddon/.zip）：能识别但**不能**在这里启用。 */
        val archives: List<Archive>,
        /** 当前世界已启用的 uuid（顺序即加载栈顺序）。 */
        val enabledBehavior: List<Entry>,
        val enabledResource: List<Entry>,
        /** 世界注册表文件存在但解析失败（此时**不能**直接覆盖写）。 */
        val behaviorRegistryBroken: Boolean,
        val resourceRegistryBroken: Boolean
    ) {
        fun ofKind(kind: Kind): List<Pack> = packs.filter { it.kind == kind }

        fun enabled(kind: Kind): List<Entry> =
            if (kind == Kind.BEHAVIOR) enabledBehavior else enabledResource

        fun isEnabled(pack: Pack): Boolean = enabled(pack.kind).any { it.uuid.equals(pack.uuid, true) }

        /** 已被登记但磁盘上找不到的条目（BDS 只会静默忽略）。 */
        fun orphans(kind: Kind): List<Entry> = enabled(kind).filter { e ->
            packs.none { it.kind == kind && it.uuid.equals(e.uuid, true) }
        }
    }

    /** 包目录里的压缩包。 */
    data class Archive(val file: File, val kind: Kind, val scope: Scope)

    const val MANIFEST_NAME = "manifest.json"

    /** UUID 形态（BDS 比较时不区分大小写，读写统一小写）。 */
    private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /** 递归找 manifest 的最大层数（BDS 实测可以多套一层，Minexa 只认 3 层足够）。 */
    private const val MAX_DEPTH = 3

    /** 统计包体积时最多遍历的文件数（超大资源包不能把主线程 IO 拖死）。 */
    private const val SIZE_SCAN_FILE_LIMIT = 20_000

    // ── 扫描 ────────────────────────────────────────────────────

    /**
     * 扫描一个服务器的所有包。
     *
     * @param worldName 当前世界名（`level-name`）；为空则只扫全服/开发目录，世界注册表按空处理
     * @param builtinUuids 官方内置包的 uuid 集合；命中则 [Pack.scope] 标为 [Scope.BUILTIN]
     */
    fun scan(
        serverDir: File,
        worldName: String?,
        builtinUuids: Set<String> = emptySet()
    ): Scan {
        val worldDir = if (worldName.isNullOrBlank()) null else File(File(serverDir, "worlds"), worldName)
        val rootDirs = listOf(
            File(serverDir, Kind.BEHAVIOR.dirName) to Scope.GLOBAL,
            File(serverDir, Kind.RESOURCE.dirName) to Scope.GLOBAL
        )
        val devDirs = listOf(
            File(serverDir, Kind.BEHAVIOR.devDirName) to Scope.DEV,
            File(serverDir, Kind.RESOURCE.devDirName) to Scope.DEV
        )
        val worldDirs = if (worldDir == null) emptyList() else listOf(
            File(worldDir, Kind.BEHAVIOR.dirName) to Scope.WORLD,
            File(worldDir, Kind.RESOURCE.dirName) to Scope.WORLD
        )

        val packs = mutableListOf<Pack>()
        val archives = mutableListOf<Archive>()

        for ((root, scope) in rootDirs + devDirs + worldDirs) {
            if (!root.isDirectory) continue
            root.listFiles()?.sortedBy { it.name }?.forEach { child ->
                when {
                    child.isDirectory -> {
                        findManifests(child, MAX_DEPTH).forEach { mf ->
                            val dir = mf.parentFile ?: return@forEach
                            val declared = manifestKind(mf, root.name.startsWith("resource"))
                            val scope2 = if (scope != Scope.WORLD && isBuiltin(child, builtinUuids)) {
                                Scope.BUILTIN
                            } else {
                                scope
                            }
                            declared.forEach { kind ->
                                parsePack(dir, mf, kind, scope2)?.let { packs += it }
                            }
                        }
                    }
                    isArchive(child) -> archives += Archive(child, kindOfDir(root.name), scope)
                }
            }
        }

        val behavior: Pair<List<Entry>, Boolean> =
            if (worldDir == null) emptyList<Entry>() to false else readWorldEntries(worldDir, Kind.BEHAVIOR)
        val resource: Pair<List<Entry>, Boolean> =
            if (worldDir == null) emptyList<Entry>() to false else readWorldEntries(worldDir, Kind.RESOURCE)

        return Scan(
            packs = packs.sortedWith(compareBy({ it.kind }, { it.scope }, { it.name.lowercase() })),
            archives = archives,
            enabledBehavior = behavior.first,
            enabledResource = resource.first,
            behaviorRegistryBroken = behavior.second,
            resourceRegistryBroken = resource.second
        )
    }

    /**
     * 目录名推断包类型（用于压缩包；真正的包以 manifest 为准）。
     *
     * 注意 `development_resource_packs` 也含 "resource"，不能只看前缀——
     * 早期用 `startsWith("resource")` 会把开发目录里的资源包判成行为包。
     */
    fun kindOfDir(dirName: String): Kind =
        if (dirName.contains("resource")) Kind.RESOURCE else Kind.BEHAVIOR

    private fun isArchive(f: File): Boolean {
        if (!f.isFile) return false
        val n = f.name.lowercase()
        return n.endsWith(".mcpack") || n.endsWith(".mcaddon") || n.endsWith(".zip")
    }

    /** 官方内置包判定：目录名命中官方命名，或 uuid 在清单里。 */
    private fun isBuiltin(dir: File, builtinUuids: Set<String>): Boolean {
        if (isBuiltinDirName(dir.name)) return true
        if (builtinUuids.isEmpty()) return false
        val mf = File(dir, MANIFEST_NAME)
        if (!mf.isFile) return false
        val uuid = runCatching { parseManifest(mf).uuid }.getOrNull() ?: return false
        return builtinUuids.any { it.equals(uuid, true) }
    }

    /**
     * 官方内置包的目录名特征。
     *
     * BDS 1.26 的 `behavior_packs/` 里就有 30 多个官方包：`vanilla`、`chemistry_1.21.30`、
     * `editor`、`experimental_*`、`server_ui_library`…。它们与用户包混在同一目录，
     * 只能靠命名 + uuid 清单区分。命中后 UI 里只读、也不建议启用（资源包会让每个
     * 进服的客户端下载上百 MB）。
     */
    fun isBuiltinDirName(name: String): Boolean {
        val n = name.lowercase()
        return n == "vanilla" || n.startsWith("vanilla_") ||
            n == "chemistry" || n.startsWith("chemistry_") ||
            n == "editor" || n == "experimental" || n.startsWith("experimental_") ||
            n == "server_library" || n == "server_editor_library" || n == "server_ui_library"
    }

    /** 就地找 manifest（含当前目录本身），最多 [depth] 层。 */
    private fun findManifests(dir: File, depth: Int): List<File> {
        if (depth < 0) return emptyList()
        val out = mutableListOf<File>()
        val self = File(dir, MANIFEST_NAME)
        if (self.isFile) {
            out += self
        } else {
            dir.listFiles()?.forEach { child ->
                if (child.isDirectory) out += findManifests(child, depth - 1)
            }
        }
        return out
    }

    /** manifest 声明的包类型集合（一个包可以同时含 data + resources）。 */
    private fun manifestKind(mf: File, defaultResource: Boolean): List<Kind> {
        val parsed = runCatching { parseManifest(mf) }.getOrNull() ?: return emptyList()
        val kinds = mutableListOf<Kind>()
        if (parsed.moduleTypes.any { it == "data" || it == "script" || it == "client_data" }) {
            kinds += Kind.BEHAVIOR
        }
        if (parsed.moduleTypes.any { it == "resources" }) kinds += Kind.RESOURCE
        if (kinds.isEmpty()) kinds += if (defaultResource) Kind.RESOURCE else Kind.BEHAVIOR
        return kinds
    }

    private fun parsePack(dir: File, mf: File, kind: Kind, scope: Scope): Pack? {
        val m = parseManifest(mf)
        if (m.uuid == null) {
            // 连 uuid 都没有的包无法登记，但仍要让用户看见并知道原因
            return Pack(
                dir = dir, kind = kind, scope = scope, uuid = "", version = emptyList(),
                name = dir.name, description = "", minEngineVersion = emptyList(),
                dependencies = emptyList(),
                problems = listOf("manifest 缺少 header.uuid，BDS 会拒绝加载"),
                sizeBytes = 0, sizeTruncated = false
            )
        }
        val (size, truncated) = sizeOf(dir)
        return Pack(
            dir = dir,
            kind = kind,
            scope = scope,
            uuid = m.uuid.lowercase(),
            version = m.version,
            name = m.name.ifBlank { dir.name },
            description = m.description,
            minEngineVersion = m.minEngineVersion,
            dependencies = m.dependencies,
            problems = m.problems,
            sizeBytes = size,
            sizeTruncated = truncated
        )
    }

    private fun sizeOf(dir: File): Pair<Long, Boolean> {
        var total = 0L
        var count = 0
        dir.walkTopDown().forEach { f ->
            if (f.isFile) {
                total += f.length()
                count++
            }
            if (count >= SIZE_SCAN_FILE_LIMIT) return total to true
        }
        return total to false
    }

    // ── manifest 解析 ───────────────────────────────────────────

    /** manifest 解析结果。字段缺失/非法时在 [problems] 里给出与 BDS 控制台同义的说明。 */
    data class Manifest(
        val uuid: String?,
        val version: List<Int>,
        val name: String,
        val description: String,
        val minEngineVersion: List<Int>,
        val moduleTypes: List<String>,
        val dependencies: List<Dependency>,
        val problems: List<String>
    )

    /**
     * 解析 `manifest.json`。
     *
     * 实测容错点（1.26.63）：
     * - 带 UTF-8 BOM 能读（这里主动剥掉）；
     * - 缺 `header.uuid` / `header.min_engine_version` → BDS 报
     *   `Missing '/header/xxx' element in pack manifest.` 并**不加载**；
     * - 缺 `format_version` → 只警告，仍能加载。
     */
    fun parseManifest(file: File): Manifest {
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
        return parseManifestText(text, file.parentFile?.name.orEmpty())
    }

    /**
     * 解析 manifest 正文。压缩包里的 manifest 没有独立的 File，走这个入口。
     *
     * @param fallbackName 读不到 `header.name` 时的兜底名（通常是目录名/包名）
     */
    fun parseManifestText(raw: String?, fallbackName: String): Manifest {
        val problems = mutableListOf<String>()
        val text = raw?.let { stripBom(it) }
            ?: return Manifest(null, emptyList(), fallbackName, "", emptyList(),
                emptyList(), emptyList(), listOf("manifest.json 无法读取"))

        val root = runCatching { Json.parseToJsonElement(stripComments(text)) }.getOrNull() as? JsonObject
            ?: return Manifest(null, emptyList(), fallbackName, "", emptyList(),
                emptyList(), emptyList(), listOf("manifest.json 不是合法 JSON"))

        val header = root["header"] as? JsonObject
        val uuid = header?.get("uuid").stringOrNull()?.trim()
        if (uuid == null || uuid.isEmpty()) {
            problems += "Missing '/header/uuid' element in pack manifest."
        } else if (!UUID_RE.matches(uuid)) {
            problems += "header.uuid 不是合法 UUID：$uuid"
        }

        val version = header?.get("version").versionOrNull() ?: emptyList()
        if (version.isEmpty()) problems += "header.version 缺失或非法"

        val minEngine = header?.get("min_engine_version").versionOrNull()
        if (minEngine == null) problems += "Missing '/header/min_engine_version' element in pack manifest."

        if (root["format_version"] == null) {
            problems += "缺少 format_version（BDS 会警告 'version 0 is deprecated'）"
        }

        val moduleTypes = (root["modules"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.get("type").stringOrNull() }
            .orEmpty()

        val deps = (root["dependencies"] as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val depUuid = o["uuid"].stringOrNull()
            val moduleName = o["module_name"].stringOrNull()
            if (depUuid == null && moduleName == null) return@mapNotNull null
            Dependency(depUuid, moduleName, o["version"].versionOrNull() ?: emptyList())
        }.orEmpty()

        return Manifest(
            uuid = uuid,
            version = version,
            name = header?.get("name").stringOrNull().orEmpty(),
            description = header?.get("description").stringOrNull().orEmpty(),
            minEngineVersion = minEngine ?: emptyList(),
            moduleTypes = moduleTypes,
            dependencies = deps,
            problems = problems
        )
    }

    /** 去 BOM（Windows 记事本/部分打包工具会带）。 */
    private fun stripBom(s: String): String = if (s.startsWith('\uFEFF')) s.substring(1) else s

    /**
     * 去掉行注释（双斜杠）与块注释（斜杠加星号成对出现）。JSON 规范不允许注释，
     * 但社区包很常见，BDS 自己倒是会直接判 JSON 非法。
     *
     * 必须跳过字符串内部，否则 `"http://x"` 会被截断。
     */
    private fun stripComments(text: String): String {
        if (!text.contains("//") && !text.contains("/*")) return text
        val sb = StringBuilder(text.length)
        var i = 0
        var inStr = false
        while (i < text.length) {
            val c = text[i]
            if (inStr) {
                sb.append(c)
                if (c == '\\' && i + 1 < text.length) {
                    sb.append(text[i + 1]); i += 2; continue
                }
                if (c == '"') inStr = false
                i++
                continue
            }
            when {
                c == '"' -> { inStr = true; sb.append(c); i++ }
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i += 2
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    private fun JsonElement?.stringOrNull(): String? = runCatching { (this as? JsonPrimitive)?.content }.getOrNull()

    /** 版本既可能是 `[1,0,0]`，也可能是 `"1.0.0"`（format_version 3 预览）。 */
    private fun JsonElement?.versionOrNull(): List<Int>? {
        if (this == null) return null
        (this as? JsonArray)?.let { arr ->
            val v = arr.mapNotNull { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
            return if (v.size == arr.size && v.isNotEmpty()) v else null
        }
        val s = stringOrNull() ?: return null
        val parts = s.split('.').mapNotNull { it.toIntOrNull() }
        return parts.ifEmpty { null }
    }

    // ── 世界注册表 ──────────────────────────────────────────────

    /**
     * 读世界注册表。
     *
     * @return (条目列表, 文件是否损坏)。文件不存在 = (空, false)；存在但解析失败 = (空, true)——
     *         后者必须让调用方知道，否则一次"启用"就会把用户手写的文件覆盖成空表。
     */
    fun readWorldEntries(worldDir: File, kind: Kind): Pair<List<Entry>, Boolean> {
        val f = File(worldDir, kind.registryName)
        if (!f.isFile) return emptyList<Entry>() to false
        val text = runCatching { stripBom(f.readText(Charsets.UTF_8)) }.getOrNull()
            ?: return emptyList<Entry>() to true
        val arr = runCatching { Json.parseToJsonElement(stripComments(text)) }.getOrNull() as? JsonArray
            ?: return emptyList<Entry>() to true
        val entries = arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["pack_id"].stringOrNull() ?: return@mapNotNull null
            val ver = o["version"].versionOrNull() ?: return@mapNotNull null
            Entry(id.lowercase(), ver)
        }
        return entries to false
    }

    /**
     * 写世界注册表（严格 JSON 数组、UTF-8 无 BOM、原子替换）。
     *
     * @param backupBroken 原文件解析失败时是否先备份成 `.minexa-bad`
     */
    fun writeWorldEntries(
        worldDir: File,
        kind: Kind,
        entries: List<Entry>,
        backupBroken: Boolean = true
    ): Boolean = runCatching {
        worldDir.mkdirs()
        val f = File(worldDir, kind.registryName)
        if (backupBroken && f.isFile) {
            val (_, broken) = readWorldEntries(worldDir, kind)
            if (broken) f.copyTo(File(worldDir, kind.registryName + ".minexa-bad"), overwrite = true)
        }
        val body = if (entries.isEmpty()) {
            "[]"
        } else {
            entries.joinToString(",\n", prefix = "[\n", postfix = "\n]") { e ->
                "  {\n    \"pack_id\": \"${e.uuid}\",\n    \"version\": ${e.version.joinToString(",", "[", "]")}\n  }"
            }
        }
        val tmp = File(worldDir, kind.registryName + ".tmp")
        tmp.writeText(body + "\n", Charsets.UTF_8)
        if (!tmp.renameTo(f)) {
            tmp.copyTo(f, overwrite = true)
            tmp.delete()
        }
        true
    }.getOrDefault(false)

    /**
     * 启用/停用一个包：只动这一条，其它条目原样保留（顺序不变）。
     *
     * 停用时连带把「因它而被自动登记、且没有别处引用」的条目留着不动——BDS 允许
     * 悬空条目（只是静默忽略），擅自清理会把用户手工加的条目一起删掉。
     */
    fun setEnabled(worldDir: File, kind: Kind, entry: Entry, enabled: Boolean): Boolean {
        val (current, broken) = readWorldEntries(worldDir, kind)
        if (broken) return false
        val normalized = entry.copy(uuid = entry.uuid.lowercase())
        val next = current.toMutableList()
        val idx = next.indexOfFirst { it.uuid.equals(normalized.uuid, true) }
        if (enabled) {
            if (idx >= 0) next[idx] = normalized else next += normalized
        } else {
            if (idx < 0) return true
            next.removeAt(idx)
        }
        return writeWorldEntries(worldDir, kind, next)
    }

    // ── 版本与依赖 ──────────────────────────────────────────────

    /**
     * BDS 的版本命中规则（实测）：同 major，且「磁盘版本 ≤ 世界请求版本」。
     *
     * 例：请求 `[1,0,1]` 命中磁盘 `[1,0,0]`；请求 `[9,9,9]` 对 `[1,0,0]` 不命中。
     */
    fun versionAccepted(installed: List<Int>, requested: List<Int>): Boolean {
        if (installed.isEmpty() || requested.isEmpty()) return false
        if (installed[0] != requested[0]) return false
        val n = maxOf(installed.size, requested.size)
        for (i in 1 until n) {
            val a = installed.getOrElse(i) { 0 }
            val b = requested.getOrElse(i) { 0 }
            if (a != b) return a < b
        }
        return true
    }

    /** 依赖解析结果。 */
    data class DependencyResolution(
        /** 需要一并启用的包（拓扑序：依赖在前）。 */
        val required: List<Pack>,
        /** 磁盘上没有、或版本不满足的依赖（BDS 会静默忽略，必须提前告诉用户）。 */
        val missing: List<Dependency>
    )

    /**
     * 展开一个包的依赖闭包。
     *
     * 实测：BDS 会自己把被依赖的包拉进加载栈，但**不登记**它——客户端拿到的资源包
     * 清单可能因此缺一份，所以 Minexa 仍然显式写入注册表。
     */
    fun resolveDependencies(target: Pack, all: List<Pack>): DependencyResolution {
        val required = LinkedHashMap<String, Pack>()
        val missing = mutableListOf<Dependency>()
        val queue = ArrayDeque(target.dependencies)

        while (queue.isNotEmpty()) {
            val dep = queue.removeFirst()
            if (dep.isScriptModule) continue // @minecraft/server 这类内置模块，磁盘上没有包
            val uuid = dep.uuid?.lowercase() ?: continue
            val candidates = all.filter { it.uuid.equals(uuid, true) }
            val hit = candidates.firstOrNull { versionAccepted(it.version, dep.version) }
                ?: candidates.maxByOrNull { it.version.firstOrNull() ?: 0 }
            if (hit == null || !versionAccepted(hit.version, dep.version)) {
                missing += dep
                continue
            }
            if (required.putIfAbsent(uuid, hit) == null) {
                queue.addAll(hit.dependencies)
            }
        }
        return DependencyResolution(required.values.toList(), missing)
    }

    /** 该包是否被别的已启用包依赖（删包前提示用）。 */
    fun dependentsOf(pack: Pack, all: List<Pack>, enabled: (Pack) -> Boolean): List<Pack> =
        all.filter { other ->
            other.uuid != pack.uuid &&
                enabled(other) &&
                other.dependencies.any { it.uuid?.equals(pack.uuid, true) == true }
        }

    /** 在 [root] 下取一个不冲突的目录名：`base`、`base-2`、`base-3`…… */
    fun uniqueDirName(root: File, base: String): String {
        val safe = sanitizeDirName(base)
        if (!File(root, safe).exists()) return safe
        var i = 2
        while (File(root, "$safe-$i").exists()) i++
        return "$safe-$i"
    }

    // ── 压缩包安装（.mcpack / .mcaddon / .zip） ─────────────────

    /** 一个从压缩包里解出来的包。 */
    data class InstalledPack(val dir: File, val kind: Kind, val name: String, val uuid: String)

    /** 压缩包安装结果。 */
    data class ArchiveInstall(
        val installed: List<InstalledPack> = emptyList(),
        /** 已存在同 uuid 同版本而被跳过的包名。 */
        val skipped: List<String> = emptyList(),
        /** 失败原因；null 表示至少装成功一个。 */
        val error: String? = null
    ) {
        val success: Boolean get() = installed.isNotEmpty()
    }

    /** 解压上限：防 zip 炸弹（.mcpack 正常几 MB，超大资源包也就几百 MB）。 */
    private const val MAX_EXTRACT_BYTES = 1_500L * 1024 * 1024
    private const val MAX_EXTRACT_ENTRIES = 40_000

    /** 按扩展名猜包类型（.mcaddon/.zip 里可能两种都有，真正的类型以 manifest 为准）。 */
    fun kindOfArchiveName(name: String): Kind =
        if (name.lowercase().contains("resource")) Kind.RESOURCE else Kind.BEHAVIOR

    /**
     * 把一个 `.mcpack` / `.mcaddon` / `.zip` 解压安装到包目录。
     *
     * 为什么必须解压：实测 `.mcaddon` **不能**直接放进 `behavior_packs/`（BDS 报
     * `Multiple manifests found at the same directory level`），`.mcpack` 虽然 1.26 能直接读，
     * 但旧版本行为不确定、也无法在世界注册表里精确控制。统一解成目录最稳。
     *
     * 处理规则：
     * - 一个 mcaddon 里可能有好几个包（行为包 + 资源包各一份），逐个解到各自的目录；
     * - 只取**最外层**的 manifest：捆绑包里 `subpacks/` 内层的 manifest 属于同一个包，
     *   单独解出去会被 BDS 当成两个冲突的包；
     * - 目标目录重名自动加 `-2`；同 uuid + 同版本已装则跳过（避免重复登记）；
     * - 拒绝越界路径（`../`、绝对路径），解压总字节与条目数都有上限。
     *
     * @param destFor 目标目录（按包类型给：`behavior_packs` 或 `resource_packs`）
     * @param archiveOut 非空且安装成功时，把原压缩包**移动**到这里（`null` = 原地保留）
     */
    fun installArchive(
        archive: File,
        destFor: (Kind) -> File,
        archiveOut: File? = null
    ): ArchiveInstall {
        if (!archive.isFile) return ArchiveInstall(error = "压缩包不存在")
        val zf = runCatching { java.util.zip.ZipFile(archive) }.getOrNull()
            ?: return ArchiveInstall(error = "不是有效的压缩包（.mcpack / .mcaddon 本质是 zip）")
        val result = zf.use { zip ->
            val entries = zip.entries().toList().filter { !it.isDirectory }
                .map { it to normalizeEntryName(it.name) }
                .filter { (_, n) -> n.isNotEmpty() && isSafeEntry(n) }
            if (entries.isEmpty()) return@use ArchiveInstall(error = "压缩包是空的")

            // 最外层 manifest：按层级排序后，剔除被更外层 manifest 覆盖的
            val manifests = entries
                .filter { (_, n) -> n == MANIFEST_NAME || n.endsWith("/$MANIFEST_NAME") }
                .map { (e, n) -> e to n.removeSuffix(MANIFEST_NAME).ifEmpty { "" } }
                .sortedBy { (_, prefix) -> prefix.count { it == '/' } }
            val roots = mutableListOf<String>()
            val picked = manifests.filter { (_, prefix) ->
                if (roots.any { prefix.startsWith(it) }) false else { roots += prefix; true }
            }
            if (picked.isEmpty()) return@use ArchiveInstall(error = "压缩包里没有 manifest.json")

            val installed = mutableListOf<InstalledPack>()
            val skipped = mutableListOf<String>()
            var budget = MAX_EXTRACT_BYTES
            var count = 0

            for ((entry, prefix) in picked) {
                val manifestText = runCatching {
                    zip.getInputStream(entry).use { input ->
                        val bytes = input.readBytes()
                        if (bytes.size > 2 * 1024 * 1024) null else String(bytes, Charsets.UTF_8)
                    }
                }.getOrNull()
                val manifest = parseManifestText(manifestText, archive.nameWithoutExtension)
                val kinds = kindsOf(manifest.moduleTypes)
                val baseName = manifest.name.ifBlank { archive.nameWithoutExtension }

                for (kind in kinds) {
                    val root = destFor(kind)
                    root.mkdirs()
                    if (manifest.uuid != null && hasUuid(root, manifest.uuid, manifest.version)) {
                        skipped += baseName
                        continue
                    }
                    val dirName = uniqueDirName(root, baseName)
                    val target = File(root, dirName)
                    if (!target.mkdirs()) {
                        return@use ArchiveInstall(installed, skipped, "无法创建目录 ${target.name}")
                    }
                    var written = 0
                    for ((e, name) in entries) {
                        if (!name.startsWith(prefix) || e.isDirectory) continue
                        val rel = name.removePrefix(prefix)
                        if (rel.isEmpty()) continue
                        if (++count > MAX_EXTRACT_ENTRIES) break
                        val out = File(target, rel)
                        if (!isInside(out, target)) continue
                        out.parentFile?.mkdirs()
                        val copied = runCatching {
                            zip.getInputStream(e).use { input ->
                                out.outputStream().use { o -> input.copyTo(o) }
                            }
                            out.length()
                        }.getOrNull() ?: continue
                        written += copied.toInt()
                        budget -= copied
                        if (budget <= 0) break
                    }
                    if (written == 0) {
                        target.deleteRecursively()
                        return@use ArchiveInstall(installed, skipped, "解压失败：包里没有可写出的文件")
                    }
                    installed += InstalledPack(target, kind, baseName, manifest.uuid.orEmpty())
                }
            }
            if (installed.isEmpty() && skipped.isEmpty()) {
                return@use ArchiveInstall(error = "包里没有可安装的附加包")
            }
            ArchiveInstall(installed, skipped, null)
        }

        // 装成功后把原压缩包挪走：留在包目录里既不会被 BDS 正确读取（.mcaddon），
        // 也会让下一次扫描重复列出同一个包。**必须等 ZipFile 关掉再动**——Windows 上
        // 还开着句柄时 rename/delete 都会失败（Android 不受影响，但别写出两套行为）。
        if (result.installed.isNotEmpty() && archiveOut != null) {
            runCatching {
                archiveOut.parentFile?.mkdirs()
                if (!archive.renameTo(archiveOut)) {
                    archive.copyTo(archiveOut, overwrite = true)
                    archive.delete()
                }
            }
        }
        return result
    }

    /** 解压/扫描用：`a\\b` → `a/b`，去掉开头的 `./`。 */
    private fun normalizeEntryName(name: String): String {
        var n = name.replace('\\', '/')
        while (n.startsWith("./")) n = n.substring(2)
        return n.trimStart('/')
    }

    /** 拒绝越界与奇怪的条目名。 */
    private fun isSafeEntry(name: String): Boolean {
        if (name.length > 512) return false
        if (name.contains("..")) return false
        if (name.matches(Regex("^[A-Za-z]:"))) return false
        if (name.contains('\u0000')) return false
        return true
    }

    private fun isInside(child: File, parent: File): Boolean =
        runCatching { child.canonicalPath.startsWith(parent.canonicalPath + File.separator) }
            .getOrDefault(false)

    /** manifest 的 modules 能声明的包类型；都没有时按行为包处理（BDS 也这么兜底）。 */
    private fun kindsOf(moduleTypes: List<String>): List<Kind> {
        val out = mutableListOf<Kind>()
        if (moduleTypes.any { it == "data" || it == "script" || it == "client_data" }) out += Kind.BEHAVIOR
        if (moduleTypes.any { it == "resources" }) out += Kind.RESOURCE
        return out.ifEmpty { listOf(Kind.BEHAVIOR) }
    }

    /** 目标目录里是否已有同 uuid 且版本可覆盖的包（避免重复安装同一个包）。 */
    private fun hasUuid(root: File, uuid: String, version: List<Int>): Boolean {
        root.listFiles()?.forEach { child ->
            if (!child.isDirectory) return@forEach
            findManifests(child, MAX_DEPTH).forEach { mf ->
                val m = runCatching { parseManifest(mf) }.getOrNull() ?: return@forEach
                if (m.uuid.equals(uuid, true) && m.version == version) return true
            }
        }
        return false
    }

    // ── 官方内置包清单 ──────────────────────────────────────────

    /** 记录官方内置包 uuid 的文件（相对服务端目录）。 */
    private const val BUILTIN_FILE = ".minexa/builtin-packs.json"

    private fun builtinFile(serverDir: File): File = File(serverDir, BUILTIN_FILE)

    /**
     * 读官方内置包 uuid 清单。
     *
     * BDS 的 `behavior_packs/` 与 `resource_packs/` 里同时躺着官方包（vanilla、chemistry…）
     * 和用户包，只能靠 uuid 区分。这份清单由安装/更新服务端时顺手记录；文件不存在时返回空集，
     * 此时 UI 不做区分（宁可多显示，也不要误把用户的包标成"系统自带"）。
     */
    fun readBuiltinUuids(serverDir: File): Set<String> {
        val f = builtinFile(serverDir)
        if (!f.isFile) return emptySet()
        val arr = runCatching {
            Json.parseToJsonElement(stripBom(f.readText(Charsets.UTF_8))) as? JsonArray
        }.getOrNull() ?: return emptySet()
        return arr.mapNotNull { it.stringOrNull()?.lowercase() }.toSet()
    }

    /** 写官方内置包清单（[uuids] 为空时删除文件，表示"未采集"）。 */
    fun writeBuiltinUuids(serverDir: File, uuids: Collection<String>): Boolean = runCatching {
        val f = builtinFile(serverDir)
        if (uuids.isEmpty()) {
            f.delete()
            return true
        }
        f.parentFile?.mkdirs()
        val body = uuids.map { it.lowercase() }.distinct().sorted()
            .joinToString(",\n", prefix = "[\n", postfix = "\n]\n") { "  \"$it\"" }
        f.writeText(body, Charsets.UTF_8)
        true
    }.getOrDefault(false)

    /** 包目录名净化：去掉路径分隔符与各平台非法字符。 */
    fun sanitizeDirName(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|\r\n]"""), "_").trim().trim('.').ifBlank { "pack" }
}
