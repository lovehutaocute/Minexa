package com.mineserve.mobile.ui.vm

import com.mineserve.mobile.R
import com.mineserve.mobile.server.PluginManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ModrinthDiscoveryDelegate(
    private val scope: CoroutineScope,
    private val pluginManager: PluginManager,
    private val currentServerVersion: () -> String,
    private val reportMessage: (Int) -> Unit
) {
    private val _modrinthResults = MutableStateFlow<List<PluginManager.ModrinthHit>>(emptyList())
    val modrinthResults: StateFlow<List<PluginManager.ModrinthHit>> = _modrinthResults.asStateFlow()

    /** Modrinth 全部可用加载器 */
    private val _modrinthLoaders = MutableStateFlow<List<String>>(emptyList())
    val modrinthLoaders: StateFlow<List<String>> = _modrinthLoaders.asStateFlow()

    /** Modrinth 支持的 MC 游戏版本（供版本筛选下拉） */
    private val _modrinthGameVersions = MutableStateFlow<List<String>>(emptyList())
    val modrinthGameVersions: StateFlow<List<String>> = _modrinthGameVersions.asStateFlow()

    /** 模组筛选选中的游戏版本（默认当前服务器版本） */
    private val _selectedModVersion = MutableStateFlow("")
    val selectedModVersion: StateFlow<String> = _selectedModVersion.asStateFlow()

    /** 加载 Modrinth 可用加载器列表（仅保留模组专用加载器，与插件池完全隔离） */
    fun loadModrinthLoaders() {
        scope.launch {
            val all = withContext(Dispatchers.IO) { pluginManager.fetchModrinthLoaders() }
            _modrinthLoaders.value = pluginManager.filterModLoaders(all)
        }
    }

    /** 加载 Modrinth 游戏版本列表；默认选中当前服务器版本 */
    fun loadModrinthGameVersions() {
        scope.launch {
            val versions = withContext(Dispatchers.IO) { pluginManager.fetchModrinthGameVersions() }
            _modrinthGameVersions.value = versions
            val serverVer = currentServerVersion()
            _selectedModVersion.value = serverVer.takeIf { it.isNotBlank() && it in versions } ?: versions.firstOrNull().orEmpty()
        }
    }

    /** 切换模组筛选的 MC 版本 */
    fun setSelectedModVersion(v: String) {
        _selectedModVersion.value = v
    }

    /** Modrinth 搜索参数（用于分页加载更多） */
    private data class ModrinthQueryArgs(
        val query: String,
        val loaders: List<String>,
        val sort: String,
        val mcVersion: String
    )

    private var lastModrinthModQuery: ModrinthQueryArgs? = null
    private val _isLoadingMoreMods = MutableStateFlow(false)
    val isLoadingMoreMods: StateFlow<Boolean> = _isLoadingMoreMods.asStateFlow()

    /**
     * 模组搜索的代际号。
     *
     * 搜索请求是异步的，用户快速改关键字/加载器时会发出多次请求，而**响应到达顺序
     * 不保证与发出顺序一致**（网络抖动、后一次请求更小更先返回）。没有代际校验时，
     * 晚到的旧响应会覆盖新结果——列表显示的内容与实际筛选条件不符。
     * 每次新搜索/加载更多都递增，回写前比对，只有最新一代才允许写入。
     */
    private var modrinthModSearchToken = 0L

    /** 搜索 Modrinth 模组（多加载器 + 版本筛选 + 排序） */
    fun searchModrinthMods(query: String, loaders: List<String>, sort: String, mcVersion: String) {
        lastModrinthModQuery = ModrinthQueryArgs(query, loaders, sort, mcVersion)
        val token = ++modrinthModSearchToken
        scope.launch {
            val hits = withContext(Dispatchers.IO) {
                pluginManager.searchModrinth(query, loaders, sort, projectType = "mod", mcVersion = mcVersion)
            }
            // 已被更新的一次搜索取代：本次结果整体丢弃，不覆盖新列表。
            if (token != modrinthModSearchToken) return@launch
            _modrinthResults.value = hits
            if (hits.isEmpty()) {
                reportMessage(R.string.s223)
            }
        }
    }

    /** 分页加载更多 Modrinth 模组（追加到现有结果） */
    fun loadMoreModrinthMods() {
        val args = lastModrinthModQuery ?: run {
            reportMessage(R.string.vm_search_first)
            return
        }
        if (_isLoadingMoreMods.value) return
        val token = ++modrinthModSearchToken
        scope.launch {
            _isLoadingMoreMods.value = true
            try {
                val offset = _modrinthResults.value.size
                val more = withContext(Dispatchers.IO) {
                    pluginManager.searchModrinth(args.query, args.loaders, args.sort, projectType = "mod", mcVersion = args.mcVersion, offset = offset)
                }
                // 期间若发生过新的搜索，这一页属于旧查询，追加进去会得到混合列表。
                if (token != modrinthModSearchToken) return@launch
                if (more.isEmpty()) {
                    reportMessage(R.string.vm_no_more_results)
                } else {
                    _modrinthResults.value = (_modrinthResults.value + more).distinctBy { it.slug }
                }
            } finally {
                _isLoadingMoreMods.value = false
            }
        }
    }
}
