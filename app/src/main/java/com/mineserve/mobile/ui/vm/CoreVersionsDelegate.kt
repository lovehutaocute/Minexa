package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.ServerCore
import com.mineserve.mobile.data.VersionSelection
import com.mineserve.mobile.server.McServerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class CoreVersionsDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    private val config: StateFlow<McConfig>,
    private val fetchVersionOptions: suspend (ServerCore) -> List<McServerController.CoreVersionOption>,
    private val updateConfig: ((McConfig) -> McConfig) -> Unit,
    private val reportMessage: (String) -> Unit,
    private val reportError: (String) -> Unit
) {
    /** 可用版本列表（从 API 获取，供 DownloadScreen 选择） */
    private val _availableVersions = MutableStateFlow<List<String>>(emptyList())
    val availableVersions: StateFlow<List<String>> = _availableVersions.asStateFlow()
    private var versionsLoadToken = 0L

    /** 缓存判断：最近一次成功加载版本列表的核心 */
    private var lastLoadedVersionsCore: ServerCore? = null

    /** 最近一次校正时的版本编号体系，用来判断下次加载是不是「跨体系切换」 */
    private var lastVersionFamily: String? = null

    private val _versionHints = MutableStateFlow<Map<String, String?>>(emptyMap())
    val versionHints: StateFlow<Map<String, String?>> = _versionHints.asStateFlow()

    fun supportedGameVersion(coreVersion: String): String? = _versionHints.value[coreVersion]

    /** 版本列表加载中 */
    private val _isLoadingVersions = MutableStateFlow(false)
    val isLoadingVersions: StateFlow<Boolean> = _isLoadingVersions.asStateFlow()

    /**
     * 最近一次列表是否来自内置兜底。
     *
     * 只在「列表非空且每项都标了兜底」时为 true —— 空列表另有「获取失败」提示，
     * 两者同时出现会互相矛盾。
     */
    private val _versionsAreFallback = MutableStateFlow(false)
    val versionsAreFallback: StateFlow<Boolean> = _versionsAreFallback.asStateFlow()

    /**
     * 加载指定核心的可用版本列表。
     *
     * 除了拉列表，还负责把「当前选中版本」校正到该核心真实存在的版本上 ——
     * 切换核心后旧版本号往往不在新列表里（Paper 1.20.4 → BDS 官方 build 号），
     * 界面上就会出现「版本号不在列表、抽屉里没有一项高亮、点下载 404」三连。
     *
     * @param force true 时忽略缓存强制重新拉取
     */
    fun loadVersions(core: ServerCore, force: Boolean = false) {
        // 同一核心且列表已经拿到：直接复用。切 Tab 回来会重建可组合，
        // 没有这层判断的话每次回到下载页都要重新发一遍官方 API 请求。
        if (!force && core == lastLoadedVersionsCore && _availableVersions.value.isNotEmpty()) {
            reconcileSelectedVersion(core, _availableVersions.value)
            return
        }
        val token = ++versionsLoadToken
        // 同一核心刷新（重试 / force）时不要先清空：清空会让下拉框瞬间变成「版本列表为空」，
        // 选中项也跟着消失，用户只会看到一次闪烁。只有换核心（旧列表已不属于当前核心）才清。
        if (core != lastLoadedVersionsCore) {
            _availableVersions.value = emptyList()
            _versionsAreFallback.value = false
            _versionHints.value = emptyMap()
        }
        _isLoadingVersions.value = true
        scope.launch {
            try {
                val options = fetchVersionOptions(core)
                if (token != versionsLoadToken) return@launch
                val versions = options.map { it.version }
                _availableVersions.value = versions
                _versionsAreFallback.value = versions.isNotEmpty() && options.all { it.isFallback }
                _versionHints.value = options.associate { it.version to it.supportedGameVersion }
                lastLoadedVersionsCore = core
                reconcileSelectedVersion(core, versions)
            } catch (e: Exception) {
                if (token != versionsLoadToken) return@launch
                reportError(str(R.string.s294, e.message))
                _availableVersions.value = emptyList()
                _versionsAreFallback.value = false
                _versionHints.value = emptyMap()
                // 记下失败的核心：否则重试会被缓存判断拦掉（列表为空时不会命中缓存，
                // 这里显式复位是为了让 lastVersionFamily 的「首次加载」判定保持准确）
                lastLoadedVersionsCore = null
            } finally {
                if (token == versionsLoadToken) _isLoadingVersions.value = false
            }
        }
    }

    /**
     * 把选中版本校正到 [versions] 里真实存在的一项。
     *
     * 判定规则本身在 [VersionSelection.resolve]（纯函数，有单测）；这里只负责
     * 维护「是否跨体系切换 / 是否本次进程首次加载」两个上下文，并落配置。
     */
    private fun reconcileSelectedVersion(core: ServerCore, versions: List<String>) {
        // 列表为空时什么都不记：包括 lastVersionFamily。否则「这次拿到空列表、下次才拿到
        // 真实列表」的情形会被判成「同一体系内重新加载」，旧版本号就再也纠正不掉了。
        if (versions.isEmpty()) return
        if (config.value.selectedCore != core) return
        val family = VersionSelection.familyOf(core)
        val firstLoadInProcess = lastVersionFamily == null
        val familyChanged = lastVersionFamily != null && lastVersionFamily != family
        lastVersionFamily = family

        // BDS 例外：官方只提供「当前最新 Linux 构建」一项，界面上没有版本抽屉，
        // 被清空后用户没有任何地方可以选回来 —— 这里直接落到列表首项。
        if (config.value.mcVersion.isBlank() && core == ServerCore.Bds) {
            updateConfig { it.copy(mcVersion = versions.first()) }
            return
        }

        // BDS 的版本号段数不固定：`bds-version.txt` 里记的是文件名版本（1.21.84.1），
        // 而清单键常是三段（1.21.84）。同一个版本不能因为段数不同就判成「不在列表里」，
        // 否则每次进下载页都会把用户选的版本悄悄改回最新版。
        if (core == ServerCore.Bds) {
            val current = config.value.mcVersion
            val match = versions.firstOrNull { com.mineserve.mobile.data.BdsVersions.sameRelease(it, current) }
            if (match != null && match != current) {
                updateConfig { it.copy(mcVersion = match) }
                return
            }
        }

        val resolved = VersionSelection.resolve(
            current = config.value.mcVersion,
            versions = versions,
            familyChanged = familyChanged,
            firstLoadInProcess = firstLoadInProcess
        ) ?: return
        updateConfig { it.copy(mcVersion = resolved) }
        reportMessage(str(R.string.msg_version_auto_selected, core.displayName, resolved))
    }

    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)
}
