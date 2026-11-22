package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.server.BdsWorldGen
import com.mineserve.mobile.server.JavaWorldGen
import com.mineserve.mobile.server.WorldManager
import com.mineserve.mobile.server.Worlds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class WorldCreationDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    worldManagerProvider: () -> WorldManager,
    private val activeDirName: () -> String?,
    private val requireServerStopped: (String) -> Boolean,
    private val loadWorlds: () -> Unit,
    private val reportMessage: (String) -> Unit,
    private val reportError: (String) -> Unit
) {
    private val worldManager by lazy(worldManagerProvider)

    /** 清空 level-seed，让服务端下次生成地图时使用随机种子。 */
    fun clearWorldSeed() {
        val dirName = activeDirName() ?: return
        scope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { worldManager.clearLevelSeed(dirName) }
                if (ok) {
                    reportMessage(str(R.string.wl_seed_cleared))
                    // 必须重载：卡片上的种子来自 level.dat / properties 的快照，
                    // 不清空这个快照的话界面上还挂着清空前的旧种子（真机实测）。
                    loadWorlds()
                } else {
                    reportError(str(R.string.wl_seed_clear_failed))
                }
            } catch (e: Exception) {
                reportError("${str(R.string.wl_seed_clear_failed)}：${e.message}")
            }
        }
    }

    /**
     * 新建一个世界（基岩版）。
     *
     * 生成方式（普通/超平坦/虚空）写在**新世界目录的 `level.dat`** 里，不经过
     * `server.properties`（BDS 没有 `level-type`）。[BdsWorldGen] 只写 5 个字段，
     * BDS 首次启动就会按它生成地形——实测把原来的"两段式"省掉了。
     *
     * 种子允许数字或文本；文本会被 BDS 按 Java `String.hashCode` 转成 int64（实测 1.19~1.26
     * 一致），这里直接按同一规则算好写进 `level.dat`，UI 也能提前展示"将被保存为 xxx"。
     */
    fun createWorld(
        name: String,
        seed: String,
        generator: BdsWorldGen.Generator = BdsWorldGen.Generator.NORMAL,
        layers: List<BdsWorldGen.Layer> = BdsWorldGen.DEFAULT_FLAT
    ) {
        val dirName = activeDirName() ?: return
        if (!requireServerStopped(str(R.string.wl_create))) return
        // 超平坦层里的方块名先对一遍原版清单：BDS 遇到不认识的方块不会报错，
        // 只会把那一层当成空气，世界生成出来缺层，而生成参数写过就改不了了。
        if (generator == BdsWorldGen.Generator.FLAT) {
            val unknown = com.mineserve.mobile.data.BlockIds
                .unknownVanilla(app, layers.map { it.block })
            if (unknown.isNotEmpty()) {
                reportError(
                    str(R.string.wl_create_fail_unknown_block, unknown.take(3).joinToString(", "))
                )
                return
            }
        }
        scope.launch {
            try {
                val failure = withContext(Dispatchers.IO) {
                    worldManager.createBedrockWorld(dirName, name, seed, generator, layers)
                }
                when (failure) {
                    null -> {
                        reportMessage(str(R.string.wl_created, Worlds.sanitize(name)))
                        loadWorlds()
                    }
                    WorldManager.CreateFailure.BadName ->
                        reportError(str(R.string.wl_create_fail_name))
                    WorldManager.CreateFailure.NameTaken ->
                        reportError(str(R.string.wl_create_fail_taken))
                    WorldManager.CreateFailure.ServerRunning ->
                        reportError(str(R.string.wl_create_fail_running))
                    WorldManager.CreateFailure.WriteFailed ->
                        reportError(str(R.string.wl_create_fail_write))
                    WorldManager.CreateFailure.NotBedrock ->
                        reportError(str(R.string.wl_create_fail_not_bedrock))
                    WorldManager.CreateFailure.NotJava ->
                        reportError(str(R.string.wl_create_fail_not_java))
                    WorldManager.CreateFailure.LevelDatFailed ->
                        reportError(str(R.string.wl_create_fail_leveldat))
                    WorldManager.CreateFailure.BadBiome ->
                        reportError(str(R.string.wl_create_fail_biome))
                    WorldManager.CreateFailure.BadLayer ->
                        reportError(str(R.string.wl_create_fail_layer))
                }
            } catch (e: Exception) {
                reportError("${str(R.string.wl_create_fail_write)}：${e.message}")
            }
        }
    }

    fun createJavaWorld(
        name: String,
        seed: String,
        type: JavaWorldGen.LevelType,
        layers: List<BdsWorldGen.Layer>,
        biome: String
    ) {
        val dirName = activeDirName() ?: return
        if (!requireServerStopped(str(R.string.wl_create))) return
        scope.launch {
            try {
                val failure = withContext(Dispatchers.IO) {
                    worldManager.createJavaWorld(dirName, name, seed, type, layers, biome)
                }
                when (failure) {
                    null -> {
                        reportMessage(str(R.string.wl_jv_created, Worlds.sanitize(name)))
                        loadWorlds()
                    }
                    WorldManager.CreateFailure.BadName ->
                        reportError(str(R.string.wl_create_fail_name))
                    WorldManager.CreateFailure.NameTaken ->
                        reportError(str(R.string.wl_create_fail_taken))
                    WorldManager.CreateFailure.ServerRunning ->
                        reportError(str(R.string.wl_create_fail_running))
                    WorldManager.CreateFailure.WriteFailed ->
                        reportError(str(R.string.wl_create_fail_write))
                    WorldManager.CreateFailure.NotJava ->
                        reportError(str(R.string.wl_create_fail_not_java))
                    WorldManager.CreateFailure.NotBedrock ->
                        reportError(str(R.string.wl_create_fail_not_bedrock))
                    WorldManager.CreateFailure.LevelDatFailed ->
                        reportError(str(R.string.wl_create_fail_leveldat))
                    WorldManager.CreateFailure.BadBiome ->
                        reportError(str(R.string.wl_create_fail_biome))
                    WorldManager.CreateFailure.BadLayer ->
                        reportError(str(R.string.wl_create_fail_layer))
                }
            } catch (e: Exception) {
                reportError("${str(R.string.wl_create_fail_write)}：${e.message}")
            }
        }
    }

    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)
}
