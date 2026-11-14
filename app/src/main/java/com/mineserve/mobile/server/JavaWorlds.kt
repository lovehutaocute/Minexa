package com.mineserve.mobile.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.zip.ZipFile

/**
 * Java 版世界的"读"侧：布局探测、种子 / 生成类型 / 难度 / 数据包状态。
 *
 * **两代布局**（实测 1.20.4 与 26.3，见 `docs/JAVA-ADVANCED-WORLD-MANAGEMENT-PLAN.md` §2.4）：
 * ```
 * ≤1.21.x                       26.x（26.1 起重构）
 * world/region/                 world/dimensions/minecraft/overworld/region/
 * world/DIM-1, world/DIM1       world/dimensions/minecraft/{the_nether,the_end}
 * world/playerdata/             world/players/data/
 * level.dat:Data.WorldGenSettings   world/data/minecraft/world_gen_settings.dat
 * level.dat:Data.GameRules          world/data/minecraft/game_rules.dat
 * level.dat:Data.Difficulty(int)    level.dat:Data.difficulty_settings
 * ```
 * 探测用**特征文件存在性**，不按版本号硬编码——26.x 已经重构过一次，以后还会变。
 */
object JavaWorlds {

    enum class Layout { LEGACY, MODERN }

    /** 世界主目录下的生成设置文件（26.x）。 */
    private const val MODERN_GEN_SETTINGS = "data/minecraft/world_gen_settings.dat"

    /**
     * 26.3 起把生成设置挪到了**主世界维度目录**里，世界根目录那份不再存在。
     *
     * 实测（Paper 26.3）：`t1/data/minecraft/` 下只剩 custom_boss_events /
     * random_sequences / scoreboard / stopwatches / world_clocks，没有
     * `world_gen_settings`；真正的文件在
     * `dimensions/minecraft/overworld/data/minecraft/world_gen_settings.dat`。
     *
     * 两代路径都试，按存在性选，不按版本号分支。
     */
    private val MODERN_GEN_SETTINGS_CANDIDATES = listOf(
        "dimensions/minecraft/overworld/data/minecraft/world_gen_settings.dat",
        MODERN_GEN_SETTINGS
    )

    /** 世界级数据包目录。 */
    const val DATAPACK_DIR = "datapacks"

    fun layout(worldDir: File): Layout =
        if (File(worldDir, "dimensions").isDirectory ||
            MODERN_GEN_SETTINGS_CANDIDATES.any { File(worldDir, it).isFile }
        ) {
            Layout.MODERN
        } else {
            Layout.LEGACY
        }

    /** 世界元信息（读不到的字段一律 null，由 UI 决定不显示，而不是显示 0）。 */
    data class Meta(
        val layout: Layout,
        val seed: Long?,
        /** 主世界生成器类型，如 `minecraft:flat` / `minecraft:noise`。 */
        val generatorType: String?,
        val levelName: String?,
        /** `peaceful` / `easy` / `normal` / `hard`。 */
        val difficulty: String?,
        val hardcore: Boolean?,
        val versionName: String?,
        val dataPacksEnabled: List<String>,
        val dataPacksDisabled: List<String>
    ) {
        val generatorText: String?
            get() = generatorType?.removePrefix("minecraft:")?.takeIf { it.isNotBlank() }
    }

    private val DIFFICULTY_NAMES = listOf("peaceful", "easy", "normal", "hard")

    /**
     * 读世界元信息；`level.dat` 读不到时返回 null。
     *
     * 注意：26.x 的世界生成设置不在 `level.dat` 里，必须额外读 `world_gen_settings.dat`。
     */
    fun readMeta(worldDir: File): Meta? {
        val data = JavaLevelDat.readData(worldDir) ?: return null
        val modern = layout(worldDir) == Layout.MODERN

        val gen = if (modern) readModernGenSettings(worldDir) else JavaLevelDat.compound(data, "WorldGenSettings")
        val overworld = overworldGenerator(gen)

        val difficulty = if (modern) {
            JavaLevelDat.string(JavaLevelDat.compound(data, "difficulty_settings"), "difficulty")
        } else {
            JavaLevelDat.intAny(data, "Difficulty")?.let { DIFFICULTY_NAMES.getOrNull(it) }
        }
        val hardcore = if (modern) {
            JavaLevelDat.bool(JavaLevelDat.compound(data, "difficulty_settings"), "hardcore")
        } else {
            // 1.20+ 的极限模式写在 level.dat 顶层的 hardcore 字节
            JavaLevelDat.bool(data, "hardcore")
        }

        val packs = JavaLevelDat.compound(data, "DataPacks")
        return Meta(
            layout = if (modern) Layout.MODERN else Layout.LEGACY,
            seed = JavaLevelDat.long(gen, "seed"),
            generatorType = JavaLevelDat.string(overworld, "type"),
            levelName = JavaLevelDat.string(data, "LevelName"),
            difficulty = difficulty,
            hardcore = hardcore,
            versionName = JavaLevelDat.string(JavaLevelDat.compound(data, "Version"), "Name"),
            dataPacksEnabled = packs?.let { JavaLevelDat.stringList(it, "Enabled") }.orEmpty(),
            dataPacksDisabled = packs?.let { JavaLevelDat.stringList(it, "Disabled") }.orEmpty()
        )
    }

    /**
     * 26.x 的 `world_gen_settings.dat`：根是 `{data:{seed,dimensions},DataVersion}`。
     *
     * 26.3 把它放在**主世界维度目录**里（见 [MODERN_GEN_SETTINGS_CANDIDATES]），
     * 只认世界根目录那一份会读不到 → 种子显示「随机」、生成类型显示「未知」。
     */
    private fun readModernGenSettings(worldDir: File): LevelDat.Tag.CompoundTag? {
        for (relative in MODERN_GEN_SETTINGS_CANDIDATES) {
            val file = File(worldDir, relative)
            if (!file.isFile) continue
            val root = JavaLevelDat.read(file)?.root ?: continue
            return JavaLevelDat.compound(root, "data") ?: root
        }
        return null
    }

    /** 主世界的 `generator` 复合（含 `type` 与 `settings`）。 */
    private fun overworldGenerator(gen: LevelDat.Tag.CompoundTag?): LevelDat.Tag.CompoundTag? {
        val dimensions = JavaLevelDat.compound(gen, "dimensions") ?: return null
        val overworld = JavaLevelDat.compound(dimensions, "minecraft:overworld") ?: return null
        return JavaLevelDat.compound(overworld, "generator")
    }

    /**
     * 读当前世界的超平坦层配置；不是超平坦或读不到时返回 null。
     *
     * 超平坦的 `settings` 在 1.16+ 是复合标签（`layers[{block,height}]` + `biome`），
     * 与 `generator-settings` 里的 JSON 结构一致。
     */
    fun readFlatLayers(worldDir: File): List<BdsWorldGen.Layer>? {
        val data = JavaLevelDat.readData(worldDir) ?: return null
        val modern = layout(worldDir) == Layout.MODERN
        val gen = if (modern) readModernGenSettings(worldDir) else JavaLevelDat.compound(data, "WorldGenSettings")
        val overworld = overworldGenerator(gen) ?: return null
        if (JavaLevelDat.string(overworld, "type")?.endsWith("flat") != true) return null
        val settings = JavaLevelDat.compound(overworld, "settings") ?: return null
        val layers = (settings["layers"] as? LevelDat.Tag.ListTag)?.items?.mapNotNull { item ->
            val c = item as? LevelDat.Tag.CompoundTag ?: return@mapNotNull null
            val block = JavaLevelDat.string(c, "block") ?: return@mapNotNull null
            val height = JavaLevelDat.int(c, "height") ?: 1
            BdsWorldGen.Layer(block, height)
        }.orEmpty()
        return layers.ifEmpty { null }
    }

    // ── 数据包（datapack） ─────────────────────────────────────

    /** 世界级数据包目录（不存在时由调用方决定是否创建）。 */
    fun datapackDir(worldDir: File): File = File(worldDir, DATAPACK_DIR)

    /** 数据包在 `level.dat:DataPacks` 里的标识：zip 与目录都用 `file/<名字>`。 */
    fun datapackId(file: File): String = "file/" + file.name

    data class DataPack(
        val file: File,
        val name: String,
        val isZip: Boolean,
        val sizeBytes: Long,
        /** `pack.mcmeta` 里的 `pack_format`（1.21.9+ 是 `min_format[0]`）。 */
        val packFormat: Int?,
        val description: String,
        val enabled: Boolean,
        /** 非空表示服务端不会识别这个包（缺 `pack.mcmeta` 或 JSON 非法）。 */
        val problem: String?
    )

    /**
     * 扫描 `world/datapacks/`。
     *
     * @param meta 已经读好的世界元信息（复用，避免重复解压 `level.dat`）
     */
    fun scanDataPacks(worldDir: File, meta: Meta?): List<DataPack> {
        val dir = datapackDir(worldDir)
        if (!dir.isDirectory) return emptyList()
        val enabledIds = meta?.dataPacksEnabled.orEmpty()
        return dir.listFiles()?.sortedBy { it.name }?.mapNotNull { f ->
            val isZip = f.isFile && f.name.endsWith(".zip", ignoreCase = true)
            if (!f.isDirectory && !isZip) return@mapNotNull null
            val mcmeta = readPackMeta(f, isZip)
            val id = datapackId(f)
            DataPack(
                file = f,
                name = f.name.removeSuffix(".zip"),
                isZip = isZip,
                sizeBytes = if (isZip) f.length() else sizeOf(f),
                packFormat = mcmeta?.format,
                description = mcmeta?.description.orEmpty(),
                enabled = enabledIds.any { it == id },
                problem = if (mcmeta == null) "缺少 pack.mcmeta，服务端不会识别这个包" else null
            )
        }.orEmpty()
    }

    private data class PackMeta(val format: Int?, val description: String)

    private fun readPackMeta(file: File, isZip: Boolean): PackMeta? {
        val text = runCatching {
            if (isZip) {
                ZipFile(file).use { z ->
                    val entry = z.getEntry("pack.mcmeta") ?: return null
                    z.getInputStream(entry).use { input ->
                        val bytes = input.readBytes()
                        if (bytes.size > 64 * 1024) null else String(bytes, Charsets.UTF_8)
                    }
                }
            } else {
                val f = File(file, "pack.mcmeta")
                if (!f.isFile) null else f.readText(Charsets.UTF_8)
            }
        }.getOrNull() ?: return null
        if (text.isBlank()) return null
        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val pack = root["pack"] as? JsonObject ?: return null
        val format = (pack["pack_format"] as? JsonPrimitive)?.intOrNull
            ?: (pack["min_format"] as? kotlinx.serialization.json.JsonArray)
                ?.firstOrNull()?.jsonPrimitive?.intOrNull
        val desc = (pack["description"] as? JsonPrimitive)?.content
            ?: (pack["description"] as? JsonObject)?.get("text")?.jsonPrimitive?.content
            ?: ""
        return PackMeta(format, desc)
    }

    private fun sizeOf(dir: File): Long {
        var total = 0L
        var count = 0
        dir.walkTopDown().forEach { f ->
            if (f.isFile) { total += f.length(); count++ }
            if (count >= 20_000) return total
        }
        return total
    }

    /**
     * 启用 / 停用一个数据包：改 `level.dat:DataPacks`。
     *
     * 语义与游戏内 `/datapack enable|disable` 等价（顺序 = 加载顺序 = 覆盖顺序）。
     * 服务端运行中调用会写不进去，调用方必须先停服。
     */
    fun setDataPackEnabled(worldDir: File, file: File, enabled: Boolean): Boolean {
        val id = datapackId(file)
        return JavaLevelDat.update(worldDir) { root ->
            val data = root["Data"] as? LevelDat.Tag.CompoundTag ?: return@update
            val packs = (data["DataPacks"] as? LevelDat.Tag.CompoundTag)
                ?: LevelDat.Tag.CompoundTag().also { data["DataPacks"] = it }
            val enabledList = stringListOf(packs["Enabled"])
            val disabledList = stringListOf(packs["Disabled"])
            val newEnabled = if (enabled) {
                if (enabledList.contains(id)) enabledList else enabledList + id
            } else {
                enabledList - id
            }
            val newDisabled = if (enabled) {
                disabledList - id
            } else {
                if (disabledList.contains(id)) disabledList else disabledList + id
            }
            packs["Enabled"] = stringList(newEnabled)
            packs["Disabled"] = stringList(newDisabled)
        }
    }

    private fun stringListOf(tag: LevelDat.Tag?): List<String> =
        (tag as? LevelDat.Tag.ListTag)?.items?.mapNotNull { (it as? LevelDat.Tag.StringTag)?.value }.orEmpty()

    private fun stringList(values: List<String>): LevelDat.Tag.ListTag =
        LevelDat.Tag.ListTag(
            LevelDat.Type.STRING,
            values.map { LevelDat.Tag.StringTag(it) }.toMutableList()
        )

    // ── 重命名 / 维度 ───────────────────────────────────────────

    /**
     * 重命名世界：改目录名 + 同步 `level.dat:LevelName`。
     *
     * 只改目录名不会同步 `LevelName`（与基岩版同一个坑，实测 1.20.4/26.3 都不自动改），
     * 会导致世界列表和游戏内显示不一致。
     *
     * @return 新目录；失败返回 null
     */
    fun renameWorld(worldsRoot: File, oldName: String, newName: String): File? {
        val safeNew = Worlds.sanitize(newName)
        if (safeNew.isBlank()) return null
        val from = File(worldsRoot, Worlds.sanitize(oldName))
        val to = File(worldsRoot, safeNew)
        if (!from.isDirectory || to.exists()) return null
        if (!from.renameTo(to)) return null
        JavaLevelDat.update(to, backup = false) { root ->
            val data = root["Data"] as? LevelDat.Tag.CompoundTag ?: return@update
            data["LevelName"] = LevelDat.Tag.StringTag(safeNew)
        }
        return to
    }

    /** 一个维度的可删除目录。 */
    data class DimensionDir(val key: String, val dir: File)

    /**
     * 列出可用"单独重置"的维度目录。
     *
     * 三种布局都要认：
     * - ≤1.21 原版：`world/DIM-1`、`world/DIM1`
     * - 26.x：`world/dimensions/minecraft/the_nether`、`the_end`
     * - Bukkit 系：与服务端同级的 `world_nether`、`world_the_end`
     */
    fun dimensionDirs(worldDir: File, worldsRoot: File, worldName: String): List<DimensionDir> {
        val out = mutableListOf<DimensionDir>()
        val legacy = listOf("nether" to "DIM-1", "end" to "DIM1")
        val modern = listOf("nether" to "the_nether", "end" to "the_end")
        for ((key, name) in legacy) {
            val d = File(worldDir, name)
            if (d.isDirectory) out += DimensionDir(key, d)
        }
        for ((key, name) in modern) {
            val d = File(worldDir, "dimensions/minecraft/$name")
            if (d.isDirectory) out += DimensionDir(key, d)
        }
        for ((key, suffix) in listOf("nether" to "_nether", "end" to "_the_end")) {
            val d = File(worldsRoot, Worlds.sanitize(worldName) + suffix)
            if (d.isDirectory) out += DimensionDir(key, d)
        }
        return out
    }
}
