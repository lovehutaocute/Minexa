package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.ServerCore
import com.mineserve.mobile.data.ServerState
import com.mineserve.mobile.server.JavaWorlds
import com.mineserve.mobile.server.WorldGenLedger
import com.mineserve.mobile.server.WorldManager
import com.mineserve.mobile.server.Worlds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class WorldListDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    private val config: StateFlow<McConfig>,
    private val serverState: StateFlow<ServerState>,
    worldManagerProvider: () -> WorldManager,
    private val activeDirName: () -> String?,
    private val isMcRunningIn: (String) -> Boolean,
    private val reportMessage: (String) -> Unit,
    private val reportError: (String) -> Unit
) {
    private val worldManager by lazy(worldManagerProvider)

    private val _worlds = MutableStateFlow<List<Worlds.WorldInfo>>(emptyList())
    val worlds: StateFlow<List<Worlds.WorldInfo>> = _worlds.asStateFlow()

    private val _worldsLoading = MutableStateFlow(false)
    val worldsLoading: StateFlow<Boolean> = _worldsLoading.asStateFlow()

    /** 当前 level-name 指向的世界名（可能尚未生成对应目录）。 */
    private val _activeWorldName = MutableStateFlow("")
    val activeWorldName: StateFlow<String> = _activeWorldName.asStateFlow()

    /** 当前世界的基岩版元信息（种子 / 生成类型）；Java 系或还没生成时为 null。 */
    private val _activeWorldMeta = MutableStateFlow<WorldManager.BedrockMeta?>(null)
    val activeWorldMeta: StateFlow<WorldManager.BedrockMeta?> = _activeWorldMeta.asStateFlow()

    /** 当前世界的 Java 版元信息（种子 / 生成类型 / 难度 / 数据包数）；基岩系为 null。 */
    private val _activeJavaWorldMeta = MutableStateFlow<JavaWorlds.Meta?>(null)
    val activeJavaWorldMeta: StateFlow<JavaWorlds.Meta?> = _activeJavaWorldMeta.asStateFlow()

    /**
     * 当前世界的「建世界时的要求」（种子原文 + 生成方式）。
     *
     * 与 [activeWorldMeta] / [activeJavaWorldMeta] 的分工：那两个是**世界真实状态**，
     * 这个是**用户当时的选择**。世界还没生成时只有后者有值，UI 必须分开展示，
     * 否则就会出现「种子：尚未生成 / 生成类型：未知」这种什么都没说的组合。
     */
    private val _activeWorldPlan = MutableStateFlow<WorldGenLedger.Record?>(null)
    val activeWorldPlan: StateFlow<WorldGenLedger.Record?> = _activeWorldPlan.asStateFlow()

    /** 当前核心类型（UI 靠它决定显示基岩版还是 Java 版的世界/附加包界面）。 */
    private val _activeCore = MutableStateFlow<ServerCore?>(null)
    val activeCore: StateFlow<ServerCore?> = _activeCore.asStateFlow()

    /** 当前世界可单独重置的维度。 */
    private val _javaDimensions = MutableStateFlow<List<JavaWorlds.DimensionDir>>(emptyList())
    val javaDimensions: StateFlow<List<JavaWorlds.DimensionDir>> = _javaDimensions.asStateFlow()

    fun refreshActiveCore() {
        _activeCore.value = config.value.installedCores
            .firstOrNull { it.name == config.value.activeCoreName }?.core
    }

    /** 当前核心是否 Java 版（非 BDS / PNX / PocketMine / Allay）。 */
    fun isJavaCore(): Boolean {
        val core = _activeCore.value ?: return false
        return !core.isBedrock
    }

    /** 加载世界列表（含各世界占用大小）。大小统计较慢，放在 IO 线程。 */
    fun loadWorlds() {
        val dirName = activeDirName() ?: return
        refreshActiveCore()
        scope.launch {
            _worldsLoading.value = true
            try {
                val list = withContext(Dispatchers.IO) { worldManager.listWorlds(dirName) }
                val active = withContext(Dispatchers.IO) { worldManager.readLevelName(dirName) }
                // 已建但还没生成的世界（目录不存在）也要列出来：Java 新建世界只写
                // properties，不建目录，只列真实目录会让用户「建完什么也看不到」。
                val planned = withContext(Dispatchers.IO) {
                    runCatching {
                        val root = worldManager.worldsRoot(dirName)
                        val existing = list.map { it.name }.toHashSet()
                        WorldGenLedger.read(worldManager.serverDir(dirName))
                            .filterKeys { it !in existing && it.isNotBlank() }
                            .map { (name, record) ->
                                Worlds.WorldInfo(
                                    name = name,
                                    dir = java.io.File(root, name),
                                    kind = if (record.kind == WorldGenLedger.Kind.JAVA) Worlds.Kind.Java
                                    else Worlds.Kind.Bedrock,
                                    sizeBytes = 0L,
                                    sizeTruncated = false,
                                    isActive = name == active,
                                    planned = true
                                )
                            }
                    }.getOrDefault(emptyList())
                }
                _worlds.value = (list + planned).sortedWith(
                    compareByDescending<Worlds.WorldInfo> { it.isActive }.thenBy { it.name }
                )
                _activeWorldName.value = active
                // 两套 level.dat 格式完全不同（基岩小端 / Java gzip 大端），按核心类型只读一套
                val javaCore = isJavaCore()
                _activeWorldMeta.value = withContext(Dispatchers.IO) {
                    if (javaCore) null
                    else runCatching { worldManager.readBedrockMeta(dirName, active) }.getOrNull()
                }
                _activeJavaWorldMeta.value = withContext(Dispatchers.IO) {
                    if (!javaCore) null
                    else runCatching { worldManager.readJavaMeta(dirName, active) }.getOrNull()
                }
                // 「用户当时要的」单独一份：世界没生成时只有它有值
                _activeWorldPlan.value = withContext(Dispatchers.IO) {
                    runCatching { WorldGenLedger.get(worldManager.serverDir(dirName), active) }.getOrNull()
                }
                // 维度也要跟着当前世界刷新，否则切到未生成的世界后仍显示上一个世界的维度
                if (javaCore) {
                    _javaDimensions.value = withContext(Dispatchers.IO) {
                        runCatching { worldManager.javaDimensionDirs(dirName, active) }
                            .getOrDefault(emptyList())
                    }
                }
            } catch (e: Exception) {
                reportError("读取世界列表失败：${e.message}")
            } finally {
                _worldsLoading.value = false
            }
        }
    }

    /**
     * 切换当前使用的世界（写入 level-name）。
     *
     * 服务端运行中时改动不会立刻生效，需要重启，这里明确提示而不是静默写入。
     */
    fun setActiveWorld(name: String) {
        val dirName = activeDirName() ?: return
        scope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { worldManager.setLevelName(dirName, name) }
                if (ok) {
                    _activeWorldName.value = name
                    reportMessage(
                        if (serverState.value.isRunning) "已切换到世界「$name」，重启服务端后生效"
                        else "已切换到世界「$name」"
                    )
                    loadWorlds()
                } else {
                    // setLevelName 现在能区分「写不进去」与「目标实例正在运行」，别再一律
                    // 报成配置不可写——用户会去翻存储权限，而真正原因可能只是要停服。
                    reportError(
                        if (isMcRunningIn(dirName)) {
                            str(R.string.world_switch_blocked_running)
                        } else {
                            str(R.string.world_switch_failed)
                        }
                    )
                }
            } catch (e: Exception) {
                reportError("切换世界失败：${e.message}")
            }
        }
    }

    fun setJavaDimensions(dimensions: List<JavaWorlds.DimensionDir>) {
        _javaDimensions.value = dimensions
    }

    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)
}
