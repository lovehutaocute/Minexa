package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.ui.McViewModel.LegacyMigrationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class LegacyMigrationDelegate(
    private val context: Context,
    private val scope: CoroutineScope,
    private val currentConfigOrNull: () -> McConfig?,
    private val updateAndSaveConfig: ((McConfig) -> McConfig) -> Unit,
    private val termux3Log: (String) -> Unit,
    private val reportError: (String) -> Unit,
    private val reportMessage: (String) -> Unit,
    private val refreshStoragePermission: () -> Unit
) {
    private val _legacyMigration = MutableStateFlow(LegacyMigrationState())
    val legacyMigration: StateFlow<LegacyMigrationState> = _legacyMigration.asStateFlow()

    fun checkLegacyMigration() {
        scope.launch {
            // 不用 currentConfig()：它可能 runBlocking 读盘。这里等一次内存态就绪再走，
            // 拿不到（极端情况）就放弃本轮，下次启动仍会重新扫描。
            val cfg = currentConfigOrNull()
                ?: run {
                    kotlinx.coroutines.delay(600)
                    currentConfigOrNull()
                }
                ?: return@launch
            val list = withContext(Dispatchers.IO) {
                com.mineserve.mobile.server.LegacyServerMigration.scan(context, cfg)
            }
            _legacyMigration.value = LegacyMigrationState(
                candidates = list,
                scanned = true
            )
            if (list.isNotEmpty()) {
                termux3Log("检测到 ${list.size} 个旧版服务器待迁移：${list.joinToString(", ") { it.dirName }}")
            }
        }
    }

    fun dismissLegacyMigration() {
        _legacyMigration.value = _legacyMigration.value.copy(candidates = emptyList())
    }

    fun runLegacyMigration(skipUnreadable: Boolean = false) {
        val state = _legacyMigration.value
        if (state.running || state.candidates.isEmpty()) return
        val candidates = state.candidates
        _legacyMigration.value = state.copy(
            running = true,
            progressDone = 0,
            progressTotal = candidates.size,
            progressName = candidates.first().dirName,
            canSkipFailed = false,
            lastError = null
        )
        scope.launch {
            val outcomes = com.mineserve.mobile.server.LegacyServerMigration.migrateAll(
                context, candidates, allowSkipUnreadable = skipUnreadable
            ) { done, total, name ->
                _legacyMigration.value = _legacyMigration.value.copy(
                    progressDone = done, progressTotal = total, progressName = name
                )
            }
            val root = com.mineserve.mobile.data.ServerStorage.internalRoot(context)
            updateAndSaveConfig { current ->
                com.mineserve.mobile.server.LegacyServerMigration.applyOutcomes(current, outcomes, root)
            }
            val ok = outcomes.count { it.success }
            val failed = outcomes.filter { !it.success }
            val skipped = outcomes.flatMap { it.skipped }
            // 失败但「只因个别文件读不出来」时保留候选，让用户能当场选「忽略并继续」；
            // 否则关掉对话框，等下次冷启动再问。
            val canSkip = failed.isNotEmpty() && failed.all { it.canSkip }
            val errorText = if (failed.isEmpty()) null else
                failed.joinToString("; ") { "${it.dirName}: ${it.error ?: "未知错误"}" }
            _legacyMigration.value = LegacyMigrationState(
                candidates = if (canSkip) candidates else emptyList(),
                scanned = true,
                canSkipFailed = canSkip,
                lastError = errorText
            )
            if (failed.isEmpty()) {
                reportMessage(str(R.string.legacy_migrate_done, ok))
                if (skipped.isNotEmpty()) {
                    reportError(str(R.string.legacy_migrate_skipped, skipped.size, skipped.take(3).joinToString("、")))
                }
            } else {
                reportError(
                    str(
                        R.string.legacy_migrate_partial,
                        ok,
                        errorText ?: ""
                    )
                )
            }
            // 迁移进来的目录直接登记完成，不需要再走「扫描未登记目录」
            refreshStoragePermission()
        }
    }

    private fun str(id: Int, vararg args: Any?): String =
        context.getString(id, *args)
}
