package com.mineserve.mobile.server

import com.mineserve.mobile.runtime.TermuxRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 世界（存档）管理：列出 / 切换 / 导入 / 导出 / 重置。
 *
 * 目录与配置的定位：
 * - Java 系：世界就在服务端目录下，`level-name` 存在 `server.properties`
 * - 基岩系（BDS / PowerNukkitX / PocketMine / Allay）：世界在 `worlds/` 下。
 *   只有 PowerNukkitX 把 `level-name` 放在 `pnx.yml`，BDS 仍然读 `server.properties`。
 *
 * **两种判定必须分开**（曾经混用过一次，代价是 BDS 的世界列表恒为空）：
 * - 世界根目录看「是不是基岩系核心」→ [usesWorldsDir]
 * - 配置落在哪个文件看「是不是 PNX」→ [usesPnxConfig]
 *
 * 纯识别与打包逻辑放在 [Worlds]（不依赖 Termux，可单元测试）；本类只做路径解析、
 * 配置读写与安全备份编排。
 */
class WorldManager(private val termux: TermuxRuntime) {

    private val propertiesManager = ServerPropertiesManager(termux)
    private val pnxConfigManager = PowerNukkitXConfigManager(termux)

    /** 未配置 level-name 时的缺省世界名。 */
    private val defaultLevelName = "world"

    fun serverDir(dirName: String): File = termux.serverDirFor(dirName)

    /**
     * 世界是否放在服务端目录下的 `worlds/` 子目录里。
     *
     * 判据是**核心类型**（`ServerCore.isBedrock`：BDS / PNX / PocketMine / Allay），
     * 不是「服务端目录里有没有 `worlds/`」也不是「有没有 pnx.yml」——
     * BDS 目录里既没有 jar 也没有 pnx.yml，早期用 PNX 探测的结果是「非基岩」，
     * 于是世界根被取成服务端根目录，BDS 的世界列表永远是空的。
     *
     * 识别结果按「目录 mtime」缓存：`ServerCoreDetector` 要开 jar 读 class，
     * 世界页每次刷新都重扫没必要；新增/删除核心文件会改动目录 mtime，缓存随之失效。
     */
    fun usesWorldsDir(dirName: String): Boolean {
        val dir = serverDir(dirName)
        val key = dir.absolutePath + "|" + dir.lastModified() + "|" + dir.isDirectory
        layoutCache?.let { if (it.first == key) return it.second }
        val detected = ServerCoreDetector.detect(dir).core?.isBedrock
        // 探测失败（目录还没铺好 / 核心文件被改名）时按目录形态兜底：
        // 有 worlds/ 又没有根级 level.dat，基本只能是基岩系布局。
        val result = detected ?: Worlds.looksLikeWorldsContainer(dir)
        layoutCache = key to result
        return result
    }

    /** 缓存 `(key, 是否基岩布局)`；只留一条，服务器目录切换时自然换掉。 */
    @Volatile
    private var layoutCache: Pair<String, Boolean>? = null

    /** `level-name` 是否保存在 `pnx.yml` 里（只有 PowerNukkitX 如此，BDS 仍用 server.properties）。 */
    fun usesPnxConfig(dirName: String): Boolean = PowerNukkitXLayout.isPowerNukkitX(serverDir(dirName))

    /** 世界目录的父目录：Java 系是服务端目录，基岩系是 `worlds/`。 */
    fun worldsRoot(dirName: String): File = Worlds.worldsRoot(serverDir(dirName), usesWorldsDir(dirName))

    /** 某个世界的目录（已做名字净化，避免拼出越界路径）。 */
    fun worldDir(dirName: String, worldName: String): File =
        File(worldsRoot(dirName), Worlds.sanitize(worldName))

    /** 世界导出目录（外部存储，卸载应用不丢）。 */
    fun exportDir(): File = File(ExternalBackupStore.rootDir, "世界导出")

    // ── level-name / level-seed ─────────────────────────────────

    /** 读取当前生效的世界名。 */
    fun readLevelName(dirName: String): String {
        val map = if (usesPnxConfig(dirName)) pnxConfigManager.read(dirName)
        else propertiesManager.readProperties(dirName)
        return map["level-name"]?.trim().orEmpty().ifBlank { defaultLevelName }
    }

    /**
     * 切换当前世界：写入 level-name。
     *
     * 世界名只做净化，不校验存在性——用户可以先把名字改成即将导入的目录名；
     * 服务端在目标不存在时会自行生成一个新世界，这与原版行为一致。
     */
    fun setLevelName(dirName: String, worldName: String): Boolean {
        val safe = Worlds.sanitize(worldName)
        if (safe.isBlank()) return false
        return if (usesPnxConfig(dirName)) {
            pnxConfigManager.write(dirName, mapOf("level-name" to safe))
        } else {
            propertiesManager.setProperty(dirName, "level-name", safe)
        }
    }

    /**
     * 清空世界种子，使服务端下次生成地图时使用随机种子。
     *
     * 只对 PNX 无效（它的种子字段没有映射到本项目的配置项，也无 UI）。
     * **BDS 走 server.properties 的 `level-seed`，是支持的**；注意 BDS 只在该世界
     * 尚未生成时读这个值，已有世界改它不会有任何效果（实测，见
     * `docs/BDS-ADDONS-WORLDS-VERIFICATION.md`）。
     *
     * **光清 properties 不够**：这个世界是我们自己预写的 `level.dat`（含 `RandomSeed`），
     * BDS 生成时以 `level.dat` 为准。只清 `level-seed` 的话界面上还挂着旧种子，
     * 而且世界照样按旧种子生成 —— 等于「点了没反应」。所以未生成的世界要
     * 把 `level.dat:RandomSeed` 一起删掉（真机实测 2026-11-14）。
     */
    fun clearLevelSeed(dirName: String): Boolean {
        if (usesPnxConfig(dirName)) return false
        val propOk = propertiesManager.setProperty(dirName, "level-seed", "")

        // 未生成的世界：连 level.dat 里的 RandomSeed 一起去掉。
        // 已生成的世界不动它（服务端已经按它生成完了，改文件只会让人误解）。
        val worldName = readLevelName(dirName)
        val worldDir = File(worldsRoot(dirName), Worlds.sanitize(worldName))
        val levelDat = File(worldDir, Worlds.LEVEL_DAT)
        if (levelDat.isFile && !generatedByServer(levelDat)) {
            runCatching {
                LevelDat.update(levelDat) { world -> world.seed = null }
            }.onFailure { android.util.Log.w("WorldManager", "清除 level.dat 种子失败: ${it.message}") }
        }

        // 未生成的世界，种子显示取的是 WorldGenLedger 里「当时填的要求」。
        // 只改文件和 properties，记录里还留着旧种子，卡片就不会变（真机实测）。
        WorldGenLedger.get(serverDir(dirName), worldName)?.let { record ->
            if (record.seedText.isNotBlank()) {
                WorldGenLedger.put(serverDir(dirName), worldName, record.copy(seedText = ""))
            }
        }
        return propOk
    }

    /** `level.dat` 是否已被服务端真正生成过（判据同 [LevelDat.World.generatedByServer]）。 */
    private fun generatedByServer(levelDat: File): Boolean {
        val world = LevelDat.read(levelDat) ?: return false
        return world.generatedByServer
    }

    // ── 强制资源包（texturepack-required） ──────────────────────

    /**
     * 服务端是否强制客户端下载资源包。
     *
     * `texturepack-required` 是 `server.properties` 里**唯一**与资源包相关的键，语义是
     * 「进服前必须下载/接受本服登记的全部资源包」（实测每次启动都会被写进
     * `level.dat:texturePacksRequired`，properties 才是真相源）。
     *
     * @return null 表示当前核心不读 properties（PNX 走 pnx.yml），UI 不显示开关
     */
    fun readTexturePackRequired(dirName: String): Boolean? {
        if (usesPnxConfig(dirName)) return null
        val map = propertiesManager.readProperties(dirName)
        val raw = map["texturepack-required"]?.trim()?.lowercase()
        return if (raw == null || raw.isEmpty()) false else raw == "true" || raw == "1"
    }

    /** 写 `texturepack-required`；服务端运行中不写（BDS 只在启动时读）。 */
    fun setTexturePackRequired(dirName: String, required: Boolean): Boolean {
        if (usesPnxConfig(dirName)) return false
        if (termux.isMcRunningIn(dirName)) return false
        return propertiesManager.setProperty(dirName, "texturepack-required", required.toString())
    }

    // ── 列表 ────────────────────────────────────────────────────

    /** 列出可切换的世界，当前生效的排在最前。 */
    fun listWorlds(dirName: String): List<Worlds.WorldInfo> {
        val root = worldsRoot(dirName)
        if (!root.isDirectory) return emptyList()
        // 基岩布局下子目录一律按 Bedrock 记：未生成的 BDS 世界只有 level.dat，
        // 仅凭标识文件会被误判成 Java 版（真机实测）。
        return Worlds.list(root, readLevelName(dirName), containerBedrock = usesWorldsDir(dirName))
    }

    // ── 基岩版世界元信息（种子 / 生成类型） ─────────────────────

    /**
     * 基岩版世界的元信息。
     *
     * @param seed 世界种子；文本种子在 BDS 侧已被哈希成 int64，还原不回原字符串
     * @param generator 1=普通、2=超平坦、5=虚空；null 表示读不到
     * @param hasLevelDat 目录里有没有 `level.dat`（没有通常是"还没生成过"）
     * @param unreadable 有 `level.dat` 但解析失败（文件损坏 / 不是 NBT）
     * @param worldDirExists 世界目录本身在不在（区分"没建过"和"建了但没生成"）
     * @param serverGenerated `level.dat` 是否已被服务端**真正生成过**
     *        （判据：有 `InventoryVersion`/`NetworkVersion` 这类只有服务端才会写的字段）。
     *        我们预写的"5 字段"文件同样叫 `level.dat`，但它只是**期望**，不是世界状态。
     * @param planned 建世界时记录的要求（见 [WorldGenLedger]）；真实值以 `level.dat` 为准
     */
    data class BedrockMeta(
        val seed: Long?,
        val generator: Int?,
        val hasLevelDat: Boolean,
        val unreadable: Boolean = false,
        val worldDirExists: Boolean = false,
        val serverGenerated: Boolean = false,
        val planned: WorldGenLedger.Record? = null
    ) {
        /** 世界已经由服务端真实生成过（不是我们预写的那份"期望文件"）。 */
        val generated: Boolean get() = hasLevelDat && !unreadable && serverGenerated
    }

    /**
     * 读当前世界的种子与生成类型。
     *
     * 只对基岩系有效：Java 的 `level.dat` 是 gzip 大端 NBT，格式完全不同，
     * 这里直接返回 null，由 UI 决定不显示这一块（而不是显示一个错数字）。
     */
    fun readBedrockMeta(dirName: String, worldName: String): BedrockMeta? {
        if (!usesWorldsDir(dirName)) return null
        val dir = File(worldsRoot(dirName), Worlds.sanitize(worldName))
        val planned = WorldGenLedger.get(serverDir(dirName), worldName)
        val lvl = File(dir, Worlds.LEVEL_DAT)
        if (!lvl.isFile) {
            return BedrockMeta(null, null, false, worldDirExists = dir.isDirectory, planned = planned)
        }
        val world = LevelDat.read(lvl)
            ?: return BedrockMeta(null, null, true, unreadable = true, worldDirExists = true, planned = planned)
        // 我们预写的文件只有 5 个字段；BDS 接手生成后才会补上 InventoryVersion 等 100 多个字段。
        // 用这个判据区分"用户说要超平坦"和"世界真的是超平坦"（见 LevelDat.World.generatedByServer）。
        val serverGenerated = world.generatedByServer
        return BedrockMeta(
            seed = world.seed,
            generator = world.generator,
            hasLevelDat = true,
            worldDirExists = true,
            serverGenerated = serverGenerated,
            planned = planned
        )
    }

    /**
     * 校验用户输入的种子，返回 BDS 实际会存进 `level.dat` 的值。
     *
     * 实测规则（1.19.83 ~ 1.26.63 一致）：能按 int64 解析（允许首尾空白与 `+`）就用字面值，
     * 否则取 **Java `String.hashCode`** —— 小数（`1.5`）也算文本，不是浮点。
     * 返回 null 表示空输入 = 让 BDS 随机。
     */
    fun resolveSeed(raw: String): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        text.toLongOrNull()?.let { return it }
        var hash = 0
        for (ch in text) hash = 31 * hash + ch.code
        return hash.toLong()
    }

    /**
     * 新建一个基岩版世界。
     *
     * 生成方式（普通 / 超平坦 / 虚空）**不能**靠 `server.properties`：BDS 没有 `level-type`
     * 这个键。正确做法是世界目录里先放一份 `level.dat` 再把 `level-name` 指过去——实测
     * 只写 5 个字段就够，BDS 首次启动会直接按它生成地形，随后自己补齐其余字段
     * （见 [BdsWorldGen] 与 `docs/BDS-ADDONS-WORLDS-VERIFICATION.md` §12）。
     *
     * 容错：`level.dat` 写完会回读校验；任一环节失败都**清掉半成品目录**并退回「只写
     * `level-name` / `level-seed`」的老路径，绝不留下一个"看着有世界但地形不对"的目录。
     *
     * @param seed 用户输入；空 = 随机
     * @param generator 生成方式；[BdsWorldGen.Generator.NORMAL] 时不写 `level.dat`（交给 BDS）
     * @param flatLayers 超平坦层配置，仅 [BdsWorldGen.Generator.FLAT] 生效
     * @return 失败原因；null 表示成功
     */
    fun createBedrockWorld(
        dirName: String,
        worldName: String,
        seed: String,
        generator: BdsWorldGen.Generator = BdsWorldGen.Generator.NORMAL,
        flatLayers: List<BdsWorldGen.Layer> = BdsWorldGen.DEFAULT_FLAT
    ): CreateFailure? {
        val safe = Worlds.sanitize(worldName)
        if (safe.isBlank()) return CreateFailure.BadName
        val root = worldsRoot(dirName)
        val worldDir = File(root, safe)
        if (worldDir.exists()) return CreateFailure.NameTaken
        if (termux.isMcRunningIn(dirName)) return CreateFailure.ServerRunning

        // Java 系的 level.dat 是 gzip 大端 NBT，格式完全不同：这里的写入逻辑只对基岩系成立。
        // 非基岩系退回"只写 level-name / level-seed"，生成方式一概不支持（不要伪造 level.dat）。
        if (!usesWorldsDir(dirName)) {
            if (generator != BdsWorldGen.Generator.NORMAL) return CreateFailure.NotBedrock
            val ok = propertiesManager.setProperty(dirName, "level-name", safe) &&
                propertiesManager.setProperty(dirName, "level-seed", seed.trim())
            return if (ok) null else CreateFailure.WriteFailed
        }

        val resolved = resolveSeed(seed)
        // 普通地形也写 level.dat：这样种子按我们算好的 int64 落盘，不依赖 BDS 对
        // 文本种子做哈希（规则虽然一致，但少一处"两边算法对不上"的可能）。
        val dirCreated = worldDir.mkdirs()
        val levelOk = dirCreated && BdsWorldGen.createLevelDat(
            file = File(worldDir, Worlds.LEVEL_DAT),
            levelName = safe,
            seed = resolved,
            generator = generator,
            flatLayers = flatLayers
        )
        if (!levelOk) {
            // 半成品目录必须清掉：留着它，BDS 会认为世界已存在而不重新生成。
            if (dirCreated) worldDir.deleteRecursively()
            // **绝不静默降级成"只写 level-name/level-seed"**：那样用户选了超平坦/虚空，
            // 服务端会按普通地形生成，界面上却显示"创建成功"——用户只会在世界生成后
            // 才发现地形不对，而那时种子和地形都已经改不回来了。
            return CreateFailure.LevelDatFailed
        }

        // level-seed 仍然写成用户原文：它只在世界不存在时被 BDS 读，这里留着纯属可读性
        // （用户翻 server.properties 时能看到自己填的那串）。
        val ok = propertiesManager.setProperty(dirName, "level-name", safe) &&
            propertiesManager.setProperty(dirName, "level-seed", seed.trim())
        if (!ok) {
            worldDir.deleteRecursively()
            return CreateFailure.WriteFailed
        }
        // 记下"用户当时要的是什么"：世界生成后 level.dat 会被服务端重写，
        // 留空种子时连 BDS 自己选的种子都只存在于重写后的文件里，用户的要求就查不到了。
        WorldGenLedger.put(
            serverDir(dirName), safe,
            WorldGenLedger.Record(
                seedText = seed.trim(),
                generator = generator.code.toString(),
                kind = WorldGenLedger.Kind.BDS,
                createdAt = System.currentTimeMillis()
            )
        )
        return null
    }

    /** [createBedrockWorld] / [createJavaWorld] 的失败原因。 */
    enum class CreateFailure {
        BadName, NameTaken, ServerRunning, WriteFailed, NotBedrock, NotJava, LevelDatFailed,
        /** 生物群系 id 不合法（`namespace:path` 格式）。服务端遇到非法值只会静默回退默认群系。 */
        BadBiome,
        /** 超平坦层里有无法解析的方块 id。 */
        BadLayer
    }

    // ── Java 版世界 ────────────────────────────────────────────

    /** 当前世界是否 Java 布局（用于 UI 决定显示哪套生成参数）。 */
    fun isJavaLayout(dirName: String): Boolean = !usesWorldsDir(dirName) && !usesPnxConfig(dirName)

    /** 读 Java 版世界的种子 / 生成类型 / 难度 / 数据包状态。 */
    fun readJavaMeta(dirName: String, worldName: String): JavaWorlds.Meta? {
        val dir = File(worldsRoot(dirName), Worlds.sanitize(worldName))
        return JavaWorlds.readMeta(dir)
    }

    /** 读 Java 世界的超平坦层（不是超平坦返回 null）。 */
    fun readJavaFlatLayers(dirName: String, worldName: String): List<BdsWorldGen.Layer>? =
        JavaWorlds.readFlatLayers(File(worldsRoot(dirName), Worlds.sanitize(worldName)))

    /**
     * 新建 Java 版世界。
     *
     * 与基岩版不同：**不预写 `level.dat`**，只写 `server.properties` 的四个键，
     * 服务端首次启动时自己生成世界（实测 1.20.4 / 26.3 一致）。
     * 生成参数只对新世界生效，世界一旦生成，改它们无效。
     */
    fun createJavaWorld(
        dirName: String,
        worldName: String,
        seed: String,
        type: JavaWorldGen.LevelType = JavaWorldGen.LevelType.NORMAL,
        layers: List<BdsWorldGen.Layer> = BdsWorldGen.DEFAULT_FLAT,
        biome: String = JavaWorldGen.DEFAULT_BIOME
    ): CreateFailure? {
        val safe = Worlds.sanitize(worldName)
        if (safe.isBlank()) return CreateFailure.BadName
        if (!isJavaLayout(dirName)) return CreateFailure.NotJava
        val worldDir = File(worldsRoot(dirName), safe)
        if (worldDir.exists()) return CreateFailure.NameTaken
        if (termux.isMcRunningIn(dirName)) return CreateFailure.ServerRunning

        // 超平坦 / 单一群系才读 biome；先规范化再写，非法 id 直接报错。
        // 不校验的话服务端只会静默回退默认群系，用户以为「我选的群系生效了」。
        val needsBiome = type == JavaWorldGen.LevelType.FLAT ||
            type == JavaWorldGen.LevelType.SINGLE_BIOME
        val normalizedBiome = if (needsBiome) {
            JavaWorldGen.normalizeId(biome) ?: return CreateFailure.BadBiome
        } else {
            JavaWorldGen.DEFAULT_BIOME
        }
        // 层里的方块 id 同样先规范化（补 minecraft: 并校验字符集），坏 id 直接拒绝
        val safeLayers = layers.map { layer ->
            val id = JavaWorldGen.normalizeId(layer.block) ?: return CreateFailure.BadLayer
            if (layer.count !in 1..BdsWorldGen.MAX_LAYER_COUNT) return CreateFailure.BadLayer
            BdsWorldGen.Layer(id, layer.count)
        }

        val settings = JavaWorldGen.buildSettings(
            type, JavaWorldGen.FlatConfig(safeLayers, normalizedBiome)
        )
        val ok = propertiesManager.setProperty(dirName, "level-name", safe) &&
            propertiesManager.setProperty(dirName, "level-seed", seed.trim()) &&
            propertiesManager.setProperty(dirName, "level-type", type.id) &&
            propertiesManager.setProperty(dirName, "generator-settings", settings)
        if (ok) {
            WorldGenLedger.put(
                serverDir(dirName), safe,
                WorldGenLedger.Record(
                    seedText = seed.trim(),
                    generator = type.id,
                    kind = WorldGenLedger.Kind.JAVA,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
        return if (ok) null else CreateFailure.WriteFailed
    }

    /** 重命名 Java 世界：目录 + `level.dat:LevelName`（BDS 同样不会自动同步）。 */
    fun renameJavaWorld(dirName: String, oldName: String, newName: String): String? {
        if (termux.isMcRunningIn(dirName)) return null
        val root = worldsRoot(dirName)
        val target = JavaWorlds.renameWorld(root, oldName, newName) ?: return null
        // 建世界记录跟着改名，否则新名字查不到"当时要的种子/生成方式"
        WorldGenLedger.rename(serverDir(dirName), oldName, target.name)
        // 当前生效的世界被改名时，level-name 也要跟上，否则服务端会另建一个空世界
        if (readLevelName(dirName) == Worlds.sanitize(oldName)) {
            setLevelName(dirName, target.name)
        }
        return target.name
    }

    /** 该 Java 世界可以单独重置的维度目录。 */
    fun javaDimensionDirs(dirName: String, worldName: String): List<JavaWorlds.DimensionDir> =
        JavaWorlds.dimensionDirs(
            worldDir(dirName, worldName),
            worldsRoot(dirName),
            Worlds.sanitize(worldName)
        )

    /**
     * 只重置一个维度（删掉下界 / 末地，让服务端重新生成）。
     *
     * 与整世界重置同一条红线：**备份没做成一个文件都不删**。
     */
    suspend fun resetDimension(
        dirName: String,
        worldName: String,
        key: String,
        backupFirst: Boolean = true
    ): ResetResult = withContext(Dispatchers.IO) {
        try {
            val dim = javaDimensionDirs(dirName, worldName).firstOrNull { it.key == key }
                ?: return@withContext ResetResult(null, 0)
            if (!ExternalBackupStore.ensure()) {
                return@withContext if (backupFirst) ResetResult(null, 0, backupFailed = true)
                else ResetResult(null, 0)
            }
            val backup = if (backupFirst) {
                val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                val out = File(
                    exportDir().apply { mkdirs() },
                    "世界_${Worlds.sanitize(dirName)}_${Worlds.sanitize(worldName)}_维度${key}_$ts.zip"
                )
                if (Worlds.exportToZip(dim.dir, emptySet(), out)) out.absolutePath else null
            } else null
            if (backupFirst && backup == null) {
                return@withContext ResetResult(null, 0, backupFailed = true)
            }
            val ok = dim.dir.deleteRecursively()
            ResetResult(backup, if (ok) 1 else 0)
        } catch (e: Exception) {
            ResetResult(null, 0)
        }
    }

    // ── Java 数据包 ────────────────────────────────────────────

    fun scanJavaDataPacks(dirName: String, worldName: String): List<JavaWorlds.DataPack> {
        val dir = worldDir(dirName, worldName)
        return JavaWorlds.scanDataPacks(dir, JavaWorlds.readMeta(dir))
    }

    /**
     * 把用户选的 zip 装成世界级数据包。
     *
     * Java 允许 zip 与目录两种形态，这里统一存 zip：便于删除与回滚，也不会因为目录里
     * 多出无关文件而让服务端误判包结构。
     */
    fun installJavaDataPack(dirName: String, worldName: String, zip: File): String? {
        if (termux.isMcRunningIn(dirName)) return null
        if (!zip.isFile) return null
        val targetDir = JavaWorlds.datapackDir(worldDir(dirName, worldName)).apply { mkdirs() }
        val base = BdsPacks.sanitizeDirName(zip.name.removeSuffix(".zip"))
        val name = BdsPacks.uniqueDirName(targetDir, base) + ".zip"
        val target = File(targetDir, name)
        return runCatching {
            zip.copyTo(target, overwrite = false)
            name
        }.getOrNull()
    }

    fun setJavaDataPackEnabled(dirName: String, worldName: String, pack: JavaWorlds.DataPack, enabled: Boolean): Boolean {
        if (termux.isMcRunningIn(dirName)) return false
        return JavaWorlds.setDataPackEnabled(worldDir(dirName, worldName), pack.file, enabled)
    }

    // ── Java 资源包 ────────────────────────────────────────────

    fun readResourcePack(dirName: String): JavaResourcePack.Config? {
        if (usesPnxConfig(dirName)) return null
        return JavaResourcePack.fromProperties(propertiesManager.readProperties(dirName))
    }

    /** 写资源包配置；运行中拒绝（服务端只在启动时读）。 */
    fun writeResourcePack(dirName: String, config: JavaResourcePack.Config): Boolean {
        if (usesPnxConfig(dirName)) return false
        if (termux.isMcRunningIn(dirName)) return false
        return propertiesManager.writeProperties(JavaResourcePack.toProperties(config), dirName)
    }

    // ── 导出 / 导入 / 重置 ──────────────────────────────────────

    /**
     * 把某个世界连同它的维度目录导出为 zip。
     *
     * @return 导出文件路径；失败返回 null。
     */
    suspend fun exportWorld(dirName: String, worldName: String): String? = withContext(Dispatchers.IO) {
        try {
            val root = worldsRoot(dirName)
            val worldDir = File(root, Worlds.sanitize(worldName))
            if (!worldDir.isDirectory) return@withContext null
            if (!ExternalBackupStore.ensure()) return@withContext null
            val allNames = root.listFiles()?.map { it.name }?.toSet().orEmpty()
            val out = File(exportDir().apply { mkdirs() }, exportFileName(dirName, worldDir.name))
            if (Worlds.exportToZip(worldDir, allNames, out)) out.absolutePath else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 从 zip 导入一个世界。
     *
     * @param preferredName 期望的世界名（通常是用户所选文件的名字）；
     *        若它带着本应用的导出前缀（`世界_…`）会改取压缩包内的真实世界名，
     *        避免在服务端目录里落出不符合命名规范的文件夹。重名时自动追加 `-2`、`-3`。
     * @return 实际使用的世界名；失败返回 null。
     */
    suspend fun importWorld(dirName: String, zip: File, preferredName: String): String? = withContext(Dispatchers.IO) {
        try {
            if (!zip.isFile) return@withContext null
            val root = worldsRoot(dirName)
            root.mkdirs()
            val name = Worlds.uniqueName(root, Worlds.importedWorldName(zip, preferredName))
            if (Worlds.importWorldZip(zip, root, name)) name else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 重置世界：先把现有世界导出为安全备份，再删除，让服务端下次启动重新生成。
     *
     * **备份是删除的前置条件**：`backupFirst = true` 时只要备份没做成，就一个文件都不删。
     * 调用方必须按 [ResetResult.backupFailed] -> [ResetResult.success] 的顺序判断，
     * 否则会出现「备份失败但世界已被删掉」的提示错位。
     *
     * @return 备份文件路径、被删除的目录数，以及备份失败标记；
     *         调用方据此决定提示文案。
     */
    data class ResetResult(
        val backupPath: String?,
        val removedDirs: Int,
        /** 要求备份但备份失败（此时未执行任何删除）。 */
        val backupFailed: Boolean = false
    ) {
        val success: Boolean get() = removedDirs > 0
    }

    suspend fun resetWorld(dirName: String, worldName: String, backupFirst: Boolean = true): ResetResult =
        withContext(Dispatchers.IO) {
            try {
                val root = worldsRoot(dirName)
                val worldDir = File(root, Worlds.sanitize(worldName))
                // 防越界：只允许删除 worlds 根目录之内的目录
                if (!worldDir.isDirectory || !Worlds.isInside(worldDir, root)) {
                    return@withContext ResetResult(null, 0)
                }
                val backup = if (backupFirst) exportWorld(dirName, worldName) else null
                // 删存档不可逆：没做成备份就中止，不能先删后报
                if (backupFirst && backup == null) {
                    return@withContext ResetResult(null, 0, backupFailed = true)
                }
                val allNames = root.listFiles()?.map { it.name }?.toSet().orEmpty()
                val removed = Worlds.deleteWorld(worldDir, allNames)
                // 世界没了，建世界记录也一起丢掉，否则下次同名世界会读到上一轮的要求
                if (removed > 0) WorldGenLedger.remove(serverDir(dirName), worldName)
                ResetResult(backup, removed)
            } catch (e: Exception) {
                ResetResult(null, 0)
            }
        }

    private fun exportFileName(dirName: String, worldName: String): String {
        val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        return "世界_${Worlds.sanitize(dirName)}_${Worlds.sanitize(worldName)}_$ts.zip"
    }
}
