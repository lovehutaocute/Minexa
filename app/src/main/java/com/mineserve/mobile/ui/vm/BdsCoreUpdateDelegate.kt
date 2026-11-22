package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.data.BdsBuildCatalog
import com.mineserve.mobile.data.BdsChannel
import com.mineserve.mobile.data.BdsCoreStatus
import com.mineserve.mobile.data.BdsUpdateProgress
import com.mineserve.mobile.data.BdsVersionCatalogLoader
import com.mineserve.mobile.data.BdsVersionEntry
import com.mineserve.mobile.data.BdsVersionListState
import com.mineserve.mobile.data.InstalledCore
import com.mineserve.mobile.data.ServerState
import com.mineserve.mobile.server.McServerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class BdsCoreUpdateDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    private val controller: McServerController,
    private val serverState: StateFlow<ServerState>,
    private val activeBdsCore: () -> InstalledCore?,
    private val reportMessage: (String) -> Unit,
    private val reportError: (String) -> Unit
) {
    private val _bdsVersions = MutableStateFlow<List<BdsVersionEntry>>(emptyList())
    val bdsVersions: StateFlow<List<BdsVersionEntry>> = _bdsVersions.asStateFlow()

    private val _bdsVersionsState = MutableStateFlow(BdsVersionListState())
    val bdsVersionsState: StateFlow<BdsVersionListState> = _bdsVersionsState.asStateFlow()

    fun loadBdsVersions(force: Boolean = false) {
        if (_bdsVersionsState.value.loading) return
        _bdsVersionsState.update { it.copy(loading = true, error = null) }
        scope.launch {
            val result = BdsVersionCatalogLoader.load(app, force)
            result.fold(
                onSuccess = { catalog ->
                    _bdsVersions.value = catalog.entries
                    _bdsVersionsState.value = BdsVersionListState(
                        loading = false,
                        loaded = true,
                        fromCache = catalog.fromCache,
                        notes = catalog.notes,
                        error = if (catalog.entries.isEmpty()) str(R.string.bds_version_list_empty) else null
                    )
                },
                onFailure = { e ->
                    _bdsVersionsState.value = _bdsVersionsState.value.copy(
                        loading = false,
                        error = e.message ?: str(R.string.bds_version_list_failed)
                    )
                }
            )
        }
    }

    private val _bdsCoreStatus = MutableStateFlow(BdsCoreStatus())
    val bdsCoreStatus: StateFlow<BdsCoreStatus> = _bdsCoreStatus.asStateFlow()

    private val _bdsUpdateState = MutableStateFlow<BdsUpdateProgress>(BdsUpdateProgress.Idle)
    val bdsUpdateState: StateFlow<BdsUpdateProgress> = _bdsUpdateState.asStateFlow()

    private var bdsCheckJob: Job? = null

    fun checkBdsCoreUpdate(force: Boolean = false) {
        val core = activeBdsCore()
        if (core == null) {
            _bdsCoreStatus.value = BdsCoreStatus()
            return
        }
        if (_bdsCoreStatus.value.checking) return
        bdsCheckJob?.cancel()
        bdsCheckJob = scope.launch {
            // 版本以磁盘上的 bds-version.txt 为准：用户可能手动换过核心，
            // 而配置里的 version 是下载时写下的，两者会不一致
            val installed = withContext(Dispatchers.IO) {
                controller.bdsInstalledVersion(core.dirName) ?: core.version
            }
            _bdsCoreStatus.update {
                it.copy(dirName = core.dirName, installedVersion = installed, checking = true, error = null)
            }
            val result = BdsBuildCatalog.load(app, force)
            _bdsCoreStatus.update { previous ->
                result.fold(
                    onSuccess = { catalog ->
                        previous.copy(
                            stable = catalog.stable,
                            preview = catalog.preview,
                            fromCache = catalog.fromCache,
                            checked = true,
                            checking = false,
                            error = null
                        )
                    },
                    onFailure = { e ->
                        previous.copy(
                            checked = true,
                            checking = false,
                            error = e.message ?: str(R.string.bds_update_check_failed)
                        )
                    }
                )
            }
        }
    }

    fun updateBdsCore(channel: BdsChannel) {
        val target = _bdsCoreStatus.value.build(channel)
        if (target == null) {
            reportError(str(R.string.bds_update_no_build))
            return
        }
        installBdsVersion(target.version)
    }

    /**
     * BDS 换版「在途」闸门。
     *
     * 不能只靠 `_bdsUpdateState is Running`：那个状态要等 [runBdsSwitch] 内部才置位，
     * 而 [installBdsVersion] 之前还有一次远端元数据解析（可能数秒）。这期间第二次点击
     * 看到的仍是 Idle 而放行，两条流程会同时停服、各自备份、各自下载并替换
     * `bedrock_server` 与动态库，最终目录可能由两个版本的文件拼成。
     */
    @Volatile
    private var bdsSwitchInFlight = false

    fun installBdsVersion(version: String) {
        val core = activeBdsCore()
        if (core == null) {
            reportError(str(R.string.bds_update_need_core))
            return
        }
        if (bdsSwitchInFlight || _bdsUpdateState.value is BdsUpdateProgress.Running) return
        bdsSwitchInFlight = true
        scope.launch {
            try {
                val entry = withContext(Dispatchers.IO) {
                    BdsVersionCatalogLoader.resolve(app, version)
                }.getOrElse { e ->
                    reportError(str(R.string.bds_version_resolve_failed, e.message ?: version))
                    return@launch
                }
                runBdsSwitch(core, entry)
            } finally {
                bdsSwitchInFlight = false
            }
        }
    }

    /** 真正的换版流程：停服 → 备份 → 下载校验 → 兼容解压 → 落地复检 */
    private suspend fun runBdsSwitch(
        core: InstalledCore,
        entry: BdsVersionEntry
    ) {
        val status = _bdsCoreStatus.value
        lastBdsTarget = entry.version
        try {
            if (serverState.value.isRunning) {
                _bdsUpdateState.value = BdsUpdateProgress.Running(
                    channel = entry.channel,
                    version = entry.version,
                    phase = str(R.string.bds_update_stopping)
                )
                if (!controller.stopGracefully()) {
                    _bdsUpdateState.value = BdsUpdateProgress.Failed(
                        str(R.string.bds_update_stop_failed), false, entry.version
                    )
                    return
                }
            }
            val current = status.installedVersion ?: core.version
            val result = controller.updateBdsCore(core.dirName, current, entry) { event ->
                _bdsUpdateState.value = BdsUpdateProgress.Running(
                    channel = entry.channel,
                    version = entry.version,
                    phase = event.message.ifBlank { bdsPhaseLabel(event.phase) },
                    progress = event.progress
                )
            }
            _bdsUpdateState.value = BdsUpdateProgress.Done(
                version = result.version,
                backupName = java.io.File(result.backupPath).name,
                keptOldEntry = result.keptOldEntry,
                integrity = result.integrity
            )
            _bdsCoreStatus.update { it.copy(installedVersion = result.version, checked = true) }
            reportMessage(str(R.string.bds_update_done, result.version))
        } catch (e: com.mineserve.mobile.server.BdsUpdater.UpdateFailed) {
            _bdsUpdateState.value = BdsUpdateProgress.Failed(
                e.message ?: str(R.string.bds_update_check_failed), e.rolledBack, entry.version
            )
            reportError(e.message ?: str(R.string.bds_update_check_failed))
        } catch (e: Exception) {
            _bdsUpdateState.value = BdsUpdateProgress.Failed(
                e.message ?: str(R.string.bds_update_check_failed), false, entry.version
            )
            reportError(str(R.string.bds_update_failed, e.message ?: ""))
        }
    }

    fun retryBdsInstall() {
        val version = lastBdsTarget ?: return
        if (_bdsUpdateState.value is BdsUpdateProgress.Running) return
        installBdsVersion(version)
    }

    /** 上次尝试安装的目标版本（失败卡片上的「重新下载」用） */
    private var lastBdsTarget: String? = null

    fun dismissBdsUpdateState() {
        _bdsUpdateState.value = BdsUpdateProgress.Idle
    }

    private fun bdsPhaseLabel(phase: com.mineserve.mobile.server.BdsUpdater.Phase): String = when (phase) {
        com.mineserve.mobile.server.BdsUpdater.Phase.BackingUp -> str(R.string.bds_update_phase_backup)
        com.mineserve.mobile.server.BdsUpdater.Phase.Downloading -> str(R.string.bds_update_phase_download)
        com.mineserve.mobile.server.BdsUpdater.Phase.Verifying -> str(R.string.bds_update_phase_verify)
        com.mineserve.mobile.server.BdsUpdater.Phase.Installing -> str(R.string.bds_update_phase_install)
    }

    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)
}
