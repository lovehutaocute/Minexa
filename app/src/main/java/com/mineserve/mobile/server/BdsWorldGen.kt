package com.mineserve.mobile.server

import java.io.File

/**
 * 新世界的「生成方式」：普通 / 超平坦 / 虚空，以及超平坦的自定义层。
 *
 * 这条链路是**离线**的，不需要先让服务端跑一遍：
 * `server.properties` 里没有 `level-type`（BDS 根本没有这个键），生成方式只存在世界目录的
 * `level.dat` 里。实测（1.19.83 / 1.20.81 / 1.21.51 / 1.21.100 / 1.26.63 五个版本一致）：
 * 世界目录里只要有一个**只含 5 个字段**的 `level.dat`（`LevelName` / `RandomSeed` /
 * `Generator` / `StorageVersion` / `FlatWorldLayers`），BDS 首次启动就直接按它生成地形，
 * 之后自己把缺的字段（`NetworkVersion`、`InventoryVersion` 等）补齐写回，种子保持不变。
 *
 * 所以「两段式（先建普通世界 → 改 Generator → 删 db → 再启动）」**不是必需的**，
 * 本类用一次写入代替。见 `docs/BDS-ADDONS-WORLDS-VERIFICATION.md` §12。
 */
object BdsWorldGen {

    /** BDS 的 `Generator` 取值（实测；0/3/4 未验证，不暴露）。 */
    enum class Generator(val code: Int) {
        NORMAL(1),
        FLAT(2),
        VOID(5);

        companion object {
            fun ofCode(code: Int?): Generator? = entries.firstOrNull { it.code == code }
        }
    }

    /** 超平坦的一层：`minecraft:dirt` × 2。 */
    data class Layer(val block: String, val count: Int)

    /** 官方默认超平坦：基岩 1 + 泥土 2 + 草方块 1（顶面在 y=-61）。 */
    val DEFAULT_FLAT: List<Layer> = listOf(
        Layer("minecraft:bedrock", 1),
        Layer("minecraft:dirt", 2),
        Layer("minecraft:grass_block", 1)
    )

    /** 超平坦世界的世界下限（1.18 起基岩层就在 y=-64；实测 `testforblock 0 -64 0 bedrock` 通过）。 */
    const val FLAT_BASE_Y = -64

    /**
     * 超平坦世界的安全落脚高度 = 最底层基岩 -64 + 总层高。
     *
     * 例：默认 `bedrock×1 + dirt×2 + grass×1` 共 4 层 → 顶面方块占 -61，玩家站 -60。
     */
    fun flatSpawnY(layers: List<Layer>): Int = FLAT_BASE_Y + layers.sumOf { it.count }

    /** 层数上限：BDS 自己也就允许几十层，给 64 足够，同时防止用户粘进超长文本。 */
    const val MAX_LAYERS = 64

    /** 单层方块数上限（原版允许 1~4096 之类的量级，这里只做防呆）。 */
    const val MAX_LAYER_COUNT = 512

    /**
     * 生成 `FlatWorldLayers` 的 JSON。
     *
     * `encoding_version` / `world_version` 是 BDS 1.18 起的新格式；实测旧版本（1.19）
     * 也接受这一份，不需要按版本分支。
     */
    fun layersJson(layers: List<Layer>): String {
        val body = layers.joinToString(",") { l ->
            """{"block_name":"${escape(l.block)}","count":${l.count}}"""
        }
        return """{"biome_id":1,"block_layers":[$body],"encoding_version":6,""" +
            """"preset_id":null,"structure_options":null,"world_version":"version.post_1_18"}"""
    }

    /**
     * 解析用户输入的层配置：`bedrock, dirt*2, grass_block`。
     *
     * 返回 null 表示格式不合法（由调用方提示，不要静默退回默认值——用户改了半天层，
     * 静默丢掉比报错更糟）。空输入返回 [DEFAULT_FLAT]。
     */
    fun parseLayers(text: String): List<Layer>? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return DEFAULT_FLAT
        val out = mutableListOf<Layer>()
        for (raw in trimmed.split(',', '，', '\n')) {
            val part = raw.trim()
            if (part.isEmpty()) continue
            val star = part.lastIndexOf('*')
            val name = (if (star > 0) part.substring(0, star) else part).trim()
            val count = if (star > 0) part.substring(star + 1).trim().toIntOrNull() else 1
            if (name.isEmpty() || count == null || count !in 1..MAX_LAYER_COUNT) return null
            val id = if (name.contains(':')) name.lowercase() else "minecraft:" + name.lowercase()
            // 方块 id 只允许小写字母、数字、下划线、冒号、点
            if (!id.matches(Regex("^[a-z0-9_.:-]+$"))) return null
            out += Layer(id, count)
        }
        if (out.isEmpty()) return DEFAULT_FLAT
        if (out.size > MAX_LAYERS) return null
        return out
    }

    /** 把层配置还原成可编辑文本（`bedrock, dirt*2, grass_block`）。 */
    fun layersText(layers: List<Layer>): String =
        layers.joinToString(", ") { (block, count) ->
            block.removePrefix("minecraft:") + if (count > 1) "*$count" else ""
        }

    /**
     * 为一个**尚未生成**的世界写 `level.dat`。
     *
     * 只写 BDS 真正读取的 5 个字段。故意不写 `NetworkVersion` / `InventoryVersion`：
     * 它们是客户端协议版本，由 BDS 自己按当前版本补齐（实测 1.19 给 1.26 的值也同样被覆盖），
     * 写死反而会让旧核心收到不属于它的协议号。
     *
     * **超平坦必须显式写 `SpawnX/Y/Z`**（2026-11-14 真机实测）：普通世界 BDS 会把
     * `SpawnY` 填成哨兵值 `32767` 再在运行时自己算安全点；但 `Generator=2` 时它直接
     * 采用文件里的值，缺字段就是默认 `0`。超平坦地面在 y=-61，玩家一进服就从 y=0
     * 摔到地面（61 格），生存模式当场摔死。所以这里按层高算出落脚点写死。
     *
     * @return 写完并**回读校验通过**才返回 true；校验失败会删掉写坏的文件
     */
    fun createLevelDat(
        file: File,
        levelName: String,
        seed: Long?,
        generator: Generator,
        flatLayers: List<Layer> = DEFAULT_FLAT
    ): Boolean {
        val root = LevelDat.Tag.CompoundTag()
        root["LevelName"] = LevelDat.Tag.StringTag(levelName)
        // 种子留空时不给 RandomSeed，让 BDS 自己随机（与 level-seed 留空等价）
        if (seed != null) root["RandomSeed"] = LevelDat.Tag.LongTag(seed)
        root["Generator"] = LevelDat.Tag.IntTag(generator.code)
        root["StorageVersion"] = LevelDat.Tag.IntTag(10)
        if (generator == Generator.FLAT) {
            root["FlatWorldLayers"] = LevelDat.Tag.StringTag(layersJson(flatLayers))
            // 不写就会用默认 0,0,0 → 从 y=0 摔到 -61 的地面，生存模式直接摔死
            root["SpawnX"] = LevelDat.Tag.IntTag(0)
            root["SpawnY"] = LevelDat.Tag.IntTag(flatSpawnY(flatLayers))
            root["SpawnZ"] = LevelDat.Tag.IntTag(0)
        }
        val world = LevelDat.World(headerVersion = 10, rootName = "", root = root)
        file.parentFile?.mkdirs()
        if (!LevelDat.write(file, world, backup = false)) return false

        // 回读校验：写错了的话 BDS 会把它当"没有世界"直接重新生成，用户看到的
        // 就是"我明明选了超平坦，出来还是普通地形"——必须在这里拦住。
        val back = LevelDat.read(file)
        val ok = back != null &&
            back.string("LevelName") == levelName &&
            back.int("Generator") == generator.code &&
            (seed == null || back.long("RandomSeed") == seed) &&
            (generator != Generator.FLAT || back.string("FlatWorldLayers") != null) &&
            (generator != Generator.FLAT || back.int("SpawnY") == flatSpawnY(flatLayers))
        if (!ok) {
            file.delete()
            return false
        }
        return true
    }

    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
