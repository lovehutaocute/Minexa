package com.mineserve.mobile.ui.vm

import com.mineserve.mobile.data.AptMirror
import com.mineserve.mobile.data.McConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class AptMirrorDelegate(
    private val scope: CoroutineScope,
    private val updateAndSaveConfig: ((McConfig) -> McConfig) -> McConfig,
    private val applyMirror: (AptMirror) -> Unit
) {
    fun setAptMirror(mirror: AptMirror) = scope.launch {
        val effective = if (mirror == AptMirror.Official) AptMirror.Tuna else mirror
        // 原子读改写 + 落盘，不用 config.value 陈旧快照整份写回（会覆盖并发的其他修改）
        updateAndSaveConfig { it.copy(aptMirror = effective) }
        withContext(Dispatchers.IO) { applyMirror(effective) }
    }
}
