package com.mineserve.mobile.data.skin

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** 列表里的一条主题。只带 UI 需要展示的字段，图片路径留给 UI 自己按需解码。 */
data class SkinEntry(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val previewFile: File?,
    val isBuiltin: Boolean,
    /** 目录存在但 manifest 解析失败 —— 列表里标红，允许删除。 */
    val broken: Boolean = false
)

data class SkinState(
    val loading: Boolean = true,
    val installed: List<SkinEntry> = emptyList(),
    val activeId: String = "",
    /** 已加载的运行时；light/dark 的解析交给 Compose 层（见 MineServeMobileTheme）。 */
    val active: SkinRuntime? = null,
    /**
     * 毛玻璃的手动设置：null = 没手动设过，跟随主题（启用主题包就开、用默认配色就关）。
     *
     * 单独存一个「没设过」的状态，是为了满足两条互相拉扯的要求：
     * 默认关闭、启用主题时默认开启，同时又不让用户的手动选择被切主题覆盖。
     */
    val glassOverride: Boolean? = null,
    /** 一次性提示，消费后置空。 */
    val message: String? = null
) {
    /** 当前是否启用毛玻璃 = 手动设置优先，否则跟随主题包是否启用。 */
    val glassEnabled: Boolean get() = glassOverride ?: activeId.isNotEmpty()
}

/** DataStore 委托必须是顶层属性：放在类里会对每个实例重复创建，触发多实例崩溃。 */
private val Context.skinDataStore: DataStore<Preferences> by preferencesDataStore(name = "skin")

/**
 * 主题仓库：装了什么、当前用哪个、导入/导出/删除。
 *
 * 只负责到「已加载的运行时」这一层；深色/浅色的令牌选择交给 Compose（`resolve(dark, width)`），
 * 这样将来加「跟随系统 / 强制浅色 / 强制深色」时不必回来改这里。
 */
class SkinRepository(
    private val context: Context,
    private val installer: SkinInstaller
) {
    private val _state = MutableStateFlow(SkinState())
    val state: StateFlow<SkinState> = _state.asStateFlow()

    private object Key {
        val ACTIVE = stringPreferencesKey("active_id")

        /** 毛玻璃手动设置：`on` / `off` / 不存在（跟随主题）。 */
        val GLASS = stringPreferencesKey("glass_mode")
    }

    /** DataStore 里的字符串 ↔ 三态设置。未知值一律当「没设过」。 */
    private fun glassOf(raw: String?): Boolean? = when (raw) {
        "on" -> true
        "off" -> false
        else -> null
    }

    /**
     * 冷启动同步预解析：只读 DataStore + 解析 JSON，不碰 Bitmap（解码要 30–60ms，
     * 放进 Application.onCreate 会让冷启动肉眼可见地变慢）。
     *
     * 目的是消除「首帧 M3 淡紫」：图片晚一帧到位不会闪白，配色晚一帧会。
     */
    fun primeSync() {
        runCatching {
            val dir = installer.themesDir()
            val prefs = runBlocking { context.skinDataStore.data.first() }
            val stored = prefs[Key.ACTIVE]
            val id = stored?.takeIf { SkinPack.validId(it) && File(dir, it).isDirectory } ?: ""
            val runtime = id.takeIf { it.isNotEmpty() }
                ?.let { SkinRuntime.load(File(dir, it), it in SkinRuntime.BUILTIN_IDS) }
                ?: SkinRuntime.builtin()
            _state.value = _state.value.copy(
                activeId = id,
                active = runtime,
                glassOverride = glassOf(prefs[Key.GLASS]),
                loading = false
            )
        }
    }

    /** 首启解压内置包 + 清残留 + 组装列表。进程内只应调用一次（幂等，重复调用只是重扫）。 */
    suspend fun bootstrap() = withContext(Dispatchers.IO) {
        val dir = installer.themesDir()
        // 清理上次异常退出留下的半成品
        dir.listFiles { f -> f.name.startsWith(".staging-") || f.name.startsWith(".trash-") }
            ?.forEach { runCatching { it.deleteRecursively() } }

        val marker = File(dir, ".bootstrapped")
        val packNames = runCatching {
            context.assets.list("themes")?.filter { it.endsWith(".mctheme") }?.sorted().orEmpty()
        }.getOrDefault(emptyList())
        // 标记里带「包名 + APK 内体积」而不是一个 "1"：App 升级换了内置包时能自动重解压，
        // 否则老用户永远停在上一个版本的内置主题上。
        val signature = packNames.joinToString("|") { name ->
            val size = runCatching { context.assets.open("themes/$name").use { it.readBytes().size } }.getOrDefault(0)
            "$name:$size"
        }
        if (signature.isNotEmpty() && marker.takeIf { it.isFile }?.readText() != signature) {
            runCatching {
                packNames.forEach { name ->
                    context.assets.open("themes/$name").use { installer.installStream(it) }
                }
                marker.writeText(signature)
            }
        }
        refresh()
    }

    /** 扫目录 + 读 DataStore → 组装 state。不做 light/dark 解析。 */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val dir = installer.themesDir()
        val entries = dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            ?.map { entry ->
                val manifest = runCatching {
                    SkinPack.parseManifest(File(entry, "manifest.json").readText())
                }.getOrNull()
                if (manifest == null) {
                    SkinEntry(
                        id = entry.name, name = entry.name, version = "", author = "",
                        description = "", previewFile = null,
                        isBuiltin = entry.name in SkinRuntime.BUILTIN_IDS, broken = true
                    )
                } else {
                    SkinEntry(
                        id = manifest.id.ifBlank { entry.name },
                        name = manifest.name.ifBlank { manifest.id.ifBlank { entry.name } },
                        version = manifest.version,
                        author = manifest.author,
                        description = manifest.description,
                        previewFile = manifest.preview?.let { File(entry, it) }?.takeIf { it.isFile },
                        isBuiltin = manifest.id in SkinRuntime.BUILTIN_IDS
                    )
                }
            }
            ?.sortedWith(compareBy({ !it.isBuiltin }, { it.name }))
            ?: emptyList()

        val prefs = context.skinDataStore.data.first()
        val stored = prefs[Key.ACTIVE]
        val glassStored = prefs[Key.GLASS]
        val activeId = stored?.takeIf { id -> entries.any { it.id == id && !it.broken } } ?: ""

        val runtime = activeId.takeIf { it.isNotEmpty() }
            ?.let { id -> SkinRuntime.load(File(dir, id), id in SkinRuntime.BUILTIN_IDS) }
            ?: SkinRuntime.builtin()

        // 预解码图片：resolve() 是在组合里同步跑的，不预热就会在主线程解码背景图。
        runCatching { runtime.prewarm(context.resources.displayMetrics.widthPixels) }

        if (stored != null && stored != activeId) {
            // activeId 指向的主题已被删除/损坏：把 DataStore 复位成「不使用主题包」，
            // 而不是留在那里每次启动都失败一次。
            runCatching { context.skinDataStore.edit { it.remove(Key.ACTIVE) } }
        }

        _state.value = SkinState(
            loading = false,
            installed = entries,
            activeId = activeId,
            active = runtime,
            glassOverride = glassOf(glassStored)
        )
    }

    /**
     * 设置毛玻璃：`true` / `false` 是用户手动选择，`null` 表示恢复「跟随主题」。
     *
     * 手动值只在这里写，切主题（[select]）不碰它 —— 否则「换主题顺手把用户的开关改回去」
     * 就是一次无声的配置覆盖。
     */
    suspend fun setGlass(enabled: Boolean?) = withContext(Dispatchers.IO) {
        context.skinDataStore.edit { prefs ->
            when (enabled) {
                null -> prefs.remove(Key.GLASS)
                true -> prefs[Key.GLASS] = "on"
                false -> prefs[Key.GLASS] = "off"
            }
        }
        _state.value = _state.value.copy(glassOverride = enabled)
    }

    /** 导入用户包。成功后自动切换到新主题（用户刚导入，最可能需要看效果）。 */
    suspend fun install(uri: Uri): Result<SkinManifest> = withContext(Dispatchers.IO) {
        when (val result = installer.install(uri)) {
            is SkinInstaller.Result.Failed -> Result.failure(SkinInstallException(result.reason))
            is SkinInstaller.Result.Ok -> {
                runCatching { context.skinDataStore.edit { it[Key.ACTIVE] = result.manifest.id } }
                refresh()
                val warn = result.warnings.size
                _state.value = _state.value.copy(
                    message = if (warn == 0) null else "该主题可读性偏低（$warn 项对比度不足）"
                )
                Result.success(result.manifest)
            }
        }
    }

    /** 切换主题。传空串表示「不使用主题包」，回到 App 自带的默认配色。 */
    suspend fun select(id: String) = withContext(Dispatchers.IO) {
        if (id.isEmpty()) {
            context.skinDataStore.edit { it.remove(Key.ACTIVE) }
        } else {
            context.skinDataStore.edit { it[Key.ACTIVE] = id }
        }
        refresh()
    }

    /** 删除。当前生效主题不允许删 —— UI 先切走再删，避免「主题变空白」。 */
    suspend fun delete(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        val current = _state.value
        if (id == current.activeId) {
            return@withContext Result.failure(SkinInstallException("正在使用该主题，请先切换到其它主题"))
        }
        // 内置包不带「重新下载」入口，删掉就永久少了两个默认主题，因此直接拒绝。
        if (id in SkinRuntime.BUILTIN_IDS) {
            return@withContext Result.failure(SkinInstallException("内置主题不能删除"))
        }
        runCatching { installer.delete(id) }
            .onFailure { return@withContext Result.failure(it) }
        refresh()
        Result.success(Unit)
    }

    suspend fun export(id: String, out: OutputStream) = withContext(Dispatchers.IO) {
        installer.export(id, out)
    }

    fun consumeMessage() {
        if (_state.value.message != null) _state.value = _state.value.copy(message = null)
    }
}

class SkinInstallException(override val message: String) : Exception(message)
