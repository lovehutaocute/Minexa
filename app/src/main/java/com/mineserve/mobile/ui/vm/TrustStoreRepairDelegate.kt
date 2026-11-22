package com.mineserve.mobile.ui.vm

import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.runtime.TermuxRuntime
import com.mineserve.mobile.runtime.TrustStoreRepair
import com.mineserve.mobile.ui.TrustStoreRepairUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class TrustStoreRepairDelegate(
    private val scope: CoroutineScope,
    private val config: StateFlow<McConfig>,
    private val termuxRuntime: TermuxRuntime,
    private val refreshJava: () -> Unit
) {
    private val _trustStoreRepair = MutableStateFlow(TrustStoreRepairUi())
    val trustStoreRepair: StateFlow<TrustStoreRepairUi> = _trustStoreRepair.asStateFlow()

    fun repairJavaTrustStore() {
        if (_trustStoreRepair.value.running) return
        val cfg = config.value
        _trustStoreRepair.value = TrustStoreRepairUi(running = true)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    termuxRuntime.repairTrustStore(cfg.selectedJavaVersion, cfg.selectedJavaVendor)
                }.getOrElse { TrustStoreRepair(false, "修复过程出错：${it.message ?: it::class.java.simpleName}") }
            }
            _trustStoreRepair.value = TrustStoreRepairUi(running = false, ok = result.ok, message = result.summary)
            if (result.ok) {
                // 环境页读的是缓存列表，不刷新会继续显示旧状态。
                refreshJava()
            }
        }
    }

    fun clearResult() {
        _trustStoreRepair.value = TrustStoreRepairUi()
    }
}
