package com.mineserve.mobile.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import com.mineserve.mobile.runtime.TermuxRuntime

/**
 * 配置文件损坏（Preferences 层无法解析，例如断电导致写入半截）时的兜底。
 *
 * 这里只负责让 DataStore 能继续工作（返回空 Preferences），**不做文件删除**：
 * 真正的「留档 + 拒绝被默认值覆盖」由 [ServerRepository.ensureConfigLoaded] 完成
 * （那里拿得到 Context，能定位并改名损坏文件）。这个 handler 里没有 Context，
 * 早期版本在这里直接 deleteCorruptedFile 会静默抹掉用户全部设置。
 */
private val Context.configDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "mc_config",
    corruptionHandler = ReplaceFileCorruptionHandler { e ->
        android.util.Log.e("ServerRepository", "配置存储损坏，已回落为空配置（原文件将在读取阶段留档）", e)
        emptyPreferences()
    }
)

/**
 * 单一数据入口：
 *  - McConfig 通过 DataStore 持久化（JSON 序列化到单一 key）
 *  - ServerState 为运行时内存态，由 TermuxRuntime / ForegroundService 推送
 *  - 日志流由 TermuxRuntime.consoleFlow 直接暴露
 */
class ServerRepository(
    /** 公开给需要做存储目录判定的调用方（下载/导入前的可写性守卫）。 */
    val context: Context,
    val termuxRuntime: TermuxRuntime
) {
    /**
     * `coerceInputValues` 是必须的：`ignoreUnknownKeys` 只兜未知的 **key**，
     * 不兜未知的**枚举值**。历史上删过 `TunnelType.Stun`，当时的配置留在磁盘上
     * 就会让整份 `McConfig` 解析失败——而失败路径原来会静默回落成默认值并回写，
     * 用户的全部设置（核心列表、Java 版本、端口、各种 token）就此清零。
     * AppUpdateService 早就开了这个选项，这里漏了。
     */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    private val CONFIG_KEY = stringPreferencesKey("config_json")

    /**
     * 磁盘上的配置是否**解析失败过**。
     *
     * 一旦为 true，[saveChannel] 的落盘一律拒绝执行——否则会把"解析失败后的默认值"
     * 写回去，把用户原始内容彻底覆盖掉（那才是不可逆的那一步）。
     * 解析失败时用户的历史配置还躺在磁盘上，是可以通过修版本/手工修 JSON 找回的。
     */
    @Volatile
    private var configDecodeFailed = false

    /**
     * 尽量把磁盘上的 JSON 解成 [McConfig]；实在解不出来返回 null，**不抛**。
     *
     * 为什么不能抛：`configFlow` 被 McForegroundService、MainActivity、WidgetUpdater 多处 collect，
     * 抛出去会让 MainActivity 里的 `first()` 直接把 App 崩掉。
     */
    private fun decodeConfigRaw(raw: String): McConfig? {
        // 历史配置里的「Termux + Java 8」归一到 Proot + Java 8（focal）。
        // 放在这里而不是 configFlow：这是磁盘 → 内存的**唯一**入口，
        // configFlow 与 ensureConfigLoaded 都经过它，两处都归一才不会出现
        // 「界面显示的发行版」与「实际启动用的发行版」不一致。
        runCatching { json.decodeFromString<McConfig>(raw) }.getOrNull()
            ?.let { return it.normalizeProotRuntime() }
        // STUN 已被移除：只迁移这一个历史枚举值再试一次（其余枚举值交给 coerceInputValues）。
        runCatching {
            json.decodeFromString<McConfig>(
                raw.replace(Regex("\\\"tunnelType\\\"\\s*:\\s*\\\"Stun\\\""), "\"tunnelType\":\"Frp\"")
            )
        }.getOrNull()?.let { return it.normalizeProotRuntime() }
        Log.e(TAG, "配置解析失败：磁盘内容保持不动，本次不会写回（避免把用户设置清成默认值）")
        configDecodeFailed = true
        return null
    }

    /**
     * [decodeConfigRaw] + 旧配置迁移。
     *
     * 迁移放在这一层（而不是各自的调用点），是因为 [configFlow] 与
     * [ensureConfigLoaded] 是两条独立的读盘路径：只在其中一条迁移，
     * 会让「界面看到的配置」和「权威内存态」对不上。
     *
     * 存储位置也在这一层纠正（[ServerStorage.reconcile]）：它既是「磁盘 → 内存」的
     * 唯一入口，也是刷新 [ServerStorage] 目录索引的地方，界面首次渲染服务器列表时
     * 就已经能分辨哪个核心在统一存储、哪个还在旧私有目录。
     */
    private fun decodeConfigLenient(raw: String): McConfig? =
        decodeConfigRaw(raw)?.let { FrpProfiles.migrateLegacy(it) ?: it }
            ?.let { ServerStorage.reconcile(context, it) }

    /**
     * 配置的权威内存态：所有 updateConfig 的读改写都以它为基准。
     *
     * 为什么需要它：DataStore 是异步流，写入还带 300ms debounce。
     * 如果每次修改都基于 configFlow 的最新发射值做「读 → 改 → 写」，
     * 在 debounce 窗口内的第二次修改会读到尚未落盘的旧值，
     * 把第一次的改动整体覆盖掉（典型表现：切到 PHP 后 runtimeKind 又被写回 Java）。
     *
     * 有了这个内存态，读改写就是原子的（见 [updateConfig]），
     * 不再依赖"窗口有多长"这类时间假设。
     */
    private val _config = MutableStateFlow(McConfig())
    @Volatile
    private var configLoaded = false

    val configFlow: Flow<McConfig> = context.configDataStore.data.map { prefs ->
        val stored = prefs[CONFIG_KEY]?.let { raw -> decodeConfigLenient(raw) } ?: McConfig()
        val correctedCores = stored.installedCores.map { core ->
            core.copy(version = MinecraftVersionNormalizer.forCore(core.core, core.version))
        }
        stored.copy(
            mcVersion = MinecraftVersionNormalizer.forCore(stored.selectedCore, stored.mcVersion),
            installedCores = correctedCores
        )
    }

    /**
     * 镜像磁盘态到权威内存态 [_config]，**仅在内存态尚未建立时**执行一次。
     *
     * 这段逻辑从 [configFlow] 里挪出来是为了修一个状态撕裂问题：
     * 原先它挂在 `configFlow.map {}` 里，而 `configFlow` 是**冷流**，
     * 每个订阅者（ViewModel 的 `stateIn`、各处 `first()`）都会重新触发一次 map。
     * 一旦有第二个订阅者在 `configLoaded` 之后才第一次收集，它虽然不会改写
     * `_config`，却也让「磁盘 → 内存」的同步时机变得依赖于订阅顺序。
     *
     * 现在改为显式的 [ensureConfigLoaded]，语义清晰：谁来都只同步一次，
     * 之后的修改一律通过 [updateConfig] 的原子读改写进入内存态。
     */
    private fun ensureConfigLoaded() {
        if (configLoaded) return
        // 直接读 DataStore，而不是 runBlocking { configFlow.firstOrNull() }——
        // 走 configFlow 会顺带触发 map（含规范化逻辑），而且异常被 runCatching 吞成 null 后
        // 会回落成默认值**并且把 configLoaded 置为 true**，等于把默认值固化下来。
        //
        // 关键：必须区分三种结果——
        //   ① 键不存在（全新安装）      → 用默认值，可以置 configLoaded
        //   ② 读盘本身失败（IO/损坏）    → **不能**当成「没有配置」！否则默认值会被固化，
        //                                下一次任意修改就把用户磁盘上的真实设置整份覆盖掉。
        //   ③ 读到了但解码失败          → 同上，保留原始内容不动。
        val prefs = try {
            kotlinx.coroutines.runBlocking { context.configDataStore.data.firstOrNull() }
        } catch (e: Exception) {
            Log.e(TAG, "读取配置失败，已保留磁盘原始内容并禁止落盘以免覆盖用户设置", e)
            archiveCorruptedConfigFile()
            configDecodeFailed = true
            return
        }
        if (prefs == null) {
            Log.w(TAG, "DataStore 返回空，暂用默认配置（不落盘）")
            configDecodeFailed = true
            return
        }
        val raw = prefs[CONFIG_KEY]
        if (raw == null) {
            // ① 真正没有存过配置：默认值就是正确结果。
            // 但如果磁盘上**存在**配置文件却读到空 key，说明它是损坏后被 handler 回落成空的——
            // 这种情况必须按「读失败」处理，绝不能把默认值固化并落盘。
            if (hasPersistedConfigFile()) {
                Log.e(TAG, "磁盘存在配置文件但读不到配置键，按损坏处理：留档并禁止落盘")
                archiveCorruptedConfigFile()
                configDecodeFailed = true
                return
            }
            synchronized(this) {
                if (!configLoaded) {
                    _config.value = McConfig()
                    configLoaded = true
                }
            }
            return
        }
        val initial = decodeConfigRaw(raw)
        if (initial == null) {
            // ③ 解析失败：保留内存态的默认值让 UI 还能用，但**不置 configLoaded**、
            // 也不允许落盘（由 configDecodeFailed 拦住），用户磁盘上的原始内容保持不动。
            Log.e(TAG, "配置解析失败，已保留磁盘原始内容并禁止落盘")
            configDecodeFailed = true
            return
        }
        // 旧版「单份 FRP 配置」并入多份配置列表（仅在列表为空时命中，见 migrateLegacy）。
        // 不做这一步，老用户升级后原来能用的 FRP 会变成「还没有配置」，启动键点不动。
        val migrated = FrpProfiles.migrateLegacy(initial)
        val effective = migrated ?: initial
        // 升级用户的老服务器仍在旧私有目录：按磁盘实际情况纠正每个核心的位置字段，
        // 不移动、不复制文件，只让列表与后续的文件操作指向正确目录。
        val reconciled = ServerStorage.reconcile(context, effective)
        synchronized(this) {
            if (!configLoaded) {
                _config.value = reconciled
                configLoaded = true
            }
        }
        // 迁移会清空旧字段，必须立刻落盘：迁移结果只留在内存里的话，
        // 用户在落盘前把配置删光，重启后旧字段又会把配置变回来。
        if (migrated != null) {
            Log.i(TAG, "已把旧版单份 FRP 配置迁移为 ${migrated.frpProfiles.size} 份配置并落盘")
            saveChannel.trySend(reconciled)
        }
        // 位置纠正同理：不落盘的话每次冷启动都要重判一次磁盘，
        // 而且「配置说 Legacy、磁盘其实已在统一目录」的错位会一直留在导出/诊断里。
        if (reconciled != effective) {
            val pending = reconciled.installedCores.count { it.location == StorageLocation.Legacy }
            Log.i(TAG, "已把已存在于统一目录的核心标记为 Internal；仍待迁移 $pending 个")
            saveChannel.trySend(reconciled)
        }
    }

    /**
     * 磁盘上是否存在 DataStore 的 Preferences 文件。
     *
     * 用来区分两种「读到空配置」：真正的全新安装（文件不存在）vs 文件损坏后被
     * 回落成空（文件存在）。后者必须按读失败处理，否则默认值会覆盖用户设置。
     */
    private fun persistedConfigFile(): File =
        File(File(context.filesDir, "datastore"), "mc_config.preferences_pb")

    private fun hasPersistedConfigFile(): Boolean = try {
        persistedConfigFile().exists()
    } catch (_: Exception) {
        false
    }

    /**
     * 把损坏的配置文件改名留档（`.corrupt.<时间戳>`），**不删除**。
     *
     * 用户的历史设置即使这次读不出来也还在磁盘上，可以人工恢复；
     * 直接删掉才是真正不可逆的那一步。
     */
    private fun archiveCorruptedConfigFile() {
        try {
            val src = persistedConfigFile()
            if (!src.exists()) return
            val dst = File(src.parentFile, "${src.name}.corrupt.${System.currentTimeMillis()}")
            if (src.renameTo(dst)) {
                Log.w(TAG, "已把损坏的配置留档到 ${dst.absolutePath}")
            } else {
                Log.w(TAG, "损坏配置留档失败（无法改名），原文件保持不动")
            }
        } catch (e: Exception) {
            Log.w(TAG, "损坏配置留档异常：${e.message}")
        }
    }

    /**
     * 原子读改写配置。
     *
     * 全程在 [MutableStateFlow.update] 的比较并交换内完成，
     * 因此并发调用不会互相覆盖，也不需要在调用方额外维护"待写入快照"。
     * 返回本次应用后的配置，便于调用方继续链式使用。
     */
    fun updateConfig(transform: (McConfig) -> McConfig): McConfig {
        ensureConfigLoaded()
        return _config.updateAndGet(transform)
    }

    /**
     * 原子读改写配置，**并把结果排入落盘队列**。
     *
     * 这是推荐给业务代码使用的入口：调用方不需要（也不应该）自己先取一份
     * 配置快照再传进来。历史上有不少调用方写成：
     *
     * ```kotlin
     * repo.saveConfig(config.value.copy(installedCores = ...))   // config.value 来自 UI 侧
     * ```
     *
     * 一旦那份快照是陈旧的（例如 UI 侧 `WhileSubscribed(5s)` 已停止收集），
     * 就会把用户此前的修改整体覆盖掉。典型症状：删掉的核心又出现在列表里、
     * 刚下载的核心在列表里找不到。改为本方法后，读改写全程在 [_config] 上
     * 原子完成，不存在「拿旧快照覆盖新状态」的窗口。
     *
     * @return 应用后的配置（已含本次修改，尚未落盘）
     */
    fun updateAndSaveConfig(transform: (McConfig) -> McConfig): McConfig {
        val updated = updateConfig(transform)
        saveChannel.trySend(updated)
        return updated
    }

    /** 当前配置的内存快照（已含尚未落盘的修改）。 */
    fun currentConfig(): McConfig {
        ensureConfigLoaded()
        return _config.value
    }

    /**
     * 已加载完成时的当前配置；尚未从磁盘加载则返回 null。
     *
     * 与 [currentConfig] 的区别是**不阻塞**：广播接收器等可能在主线程调用的场景，
     * 不能用它去触发一次同步读盘（StrictMode 违规 / ANR）。拿到 null 表示
     * 「内存态还没就绪」，调用方应走异步路径（如交给前台服务的协程）而不是硬读。
     */
    fun currentConfigOrNull(): McConfig? = if (configLoaded) _config.value else null


    /**
     * Config 写入通道，用于合并高频写入。
     *
     * 这里**必须用 [Channel.CONFLATED] 而不是普通缓冲通道**，但要理解它的语义：
     * 它只保留「最后一份」，中间态会被丢弃。对配置写入来说这是安全的 ——
     * 因为每次写入的都是**完整**的 [McConfig]（由 [updateConfig] 从权威内存态
     * 原子算出），丢弃中间态等价于「跳过若干次落盘」，最终态永远正确。
     *
     * 反过来说：**绝不能把「部分字段的补丁」投进这个通道**。
     * 早期有调用方传的是从 UI 侧拿的陈旧快照，CONFLATED 一挤，
     * 就把并发的另一次修改整体丢了 —— 「刚下载的核心消失」正是这么来的。
     * 所有写入都应经由 [updateConfig] / [updateAndSaveConfig] 从 [_config] 生成。
     */
    private val saveChannel = Channel<McConfig>(Channel.CONFLATED)
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // 300ms debounce：连续快速修改只写入最后一次。
        //
        // 整个 collect 体必须自带 try/catch：原先 edit 抛异常（磁盘写满、IO 错误）时，
        // 异常会从 saveScope 这个根协程逃到线程默认处理器 → `McApplication` 的全局
        // 崩溃捕获记完日志就照旧交给系统 → **整个 App（连同正在运行的 MC）被杀**。
        // 而且 collect 一旦因异常终止，saveChannel 就再也没有消费者，
        // 之后所有配置修改都会静默丢失（CONFLATED 的 send 永不挂起，调用方察觉不到）。
        saveScope.launch {
            saveChannel.consumeAsFlow().debounce(300).collect { config ->
                // 解析失败过就绝不落盘：那份 config 是"解析失败后回落的默认值"，
                // 写回去等于把用户磁盘上的原始设置永久覆盖掉。宁可这次修改不保存。
                if (configDecodeFailed) {
                    Log.e(TAG, "磁盘配置此前解析失败，拒绝本次落盘以免覆盖用户设置")
                    return@collect
                }
                try {
                    context.configDataStore.edit { prefs ->
                        prefs[CONFIG_KEY] = json.encodeToString(McConfig.serializer(), config)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 落盘失败只影响持久化，绝不能升级成进程崩溃。
                    // 内存态仍然是最新的，UI 行为正常，下次修改会再次尝试落盘。
                    Log.e(TAG, "配置落盘失败（内存态保留，稍后重试）", e)
                }
            }
        }
        // 后台预热：把「首次读盘」从 UI/组件点击路径上挪走。
        // 原先第一次 updateConfig 会在调用线程上 runBlocking 读 DataStore，
        // 若那次调用发生在主线程就是一次 StrictMode 违规，冷启动/低端机上可能 ANR。
        saveScope.launch { ensureConfigLoaded() }
    }

    /*
     * 这里原本有一个 `saveConfig(config: McConfig)`：把调用方持有的一整份快照直接
     * 排进落盘队列。它是「设置保存后被回滚」这类 bug 的根源 —— [_config] 是权威
     * 内存态，而调用方手里那份往往已经过期（UI 侧 `WhileSubscribed(5s)` 已停止收集、
     * MCP 工具线程拿的是几秒前的 flow 值），一旦用它整份覆盖，并发期间的其它修改
     * 就全部丢失，内存态与磁盘还会永久分歧（MCP 令牌被重新生成、自动切换的 Java
     * 版本被回滚、新登记的核心从列表消失）。
     *
     * 已删除该方法，而不是保留一个「废弃但仍能调用」的入口：所有写入必须经
     * [updateConfig] / [updateAndSaveConfig] 从权威态做字段级更新，
     * 让编译器替我们挡住回退到旧写法的可能。若确实要整体替换（如恢复默认值），
     * 写成 `updateAndSaveConfig { defaultConfig }` 同样能表达，且意图更清楚。
     */

    private val _serverState = MutableStateFlow(ServerState())
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    /** 原子更新服务器状态（CAS），避免多线程并发读改写丢失更新 */
    fun updateServerState(transform: (ServerState) -> ServerState) {
        val before = _serverState.value
        _serverState.update { transform(it) }
        val after = _serverState.value
        // 桌面组件关心的字段变化时才刷新 widget，避免高频日志解析带来的无谓推送
        if (before.isRunning != after.isRunning ||
            before.onlinePlayers != after.onlinePlayers ||
            before.maxPlayers != after.maxPlayers ||
            before.tps != after.tps ||
            before.usedMemoryMb != after.usedMemoryMb ||
            before.cpuPercent != after.cpuPercent ||
            before.startupPhase != after.startupPhase
        ) {
            WidgetUpdater.refresh(context)
        }
    }

    /** 由 ForegroundService 调用：标记安装步骤进度 */
    fun markStep(step: InstallStep, status: StepStatus, progress: Int = _serverState.value.currentProgress) {
        updateServerState { state ->
            state.copy(
                installSteps = state.installSteps.map { if (it.step == step) it.copy(status = status) else it },
                currentProgress = progress
            )
        }
    }

    private companion object {
        private const val TAG = "ServerRepository"
    }
}
