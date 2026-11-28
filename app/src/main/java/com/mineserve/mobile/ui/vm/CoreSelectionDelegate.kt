package com.mineserve.mobile.ui.vm

import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.ServerCore
import kotlinx.coroutines.flow.StateFlow

internal class CoreSelectionDelegate(
    private val config: StateFlow<McConfig>,
    private val updateConfig: ((McConfig) -> McConfig) -> Unit,
    private val showVersionReselectMessage: (ServerCore) -> Unit,
    private val showVersionCorrectedMessage: (ServerCore, String, String) -> Unit
) {
    /**
     * Even selecting the current core resets its version: the user action means they want to
     * configure it again. A custom port remains untouched; default ports migrate only on a
     * Java/Bedrock core switch. BDS has no selectable-version row, so it gets no reselect prompt.
     */
    fun selectCore(core: ServerCore) {
        val previous = config.value.selectedCore
        updateConfig { CoreSelection.select(it, core, previous != core) }
        if (core != ServerCore.Bds) showVersionReselectMessage(core)
    }

    fun setMcVersion(version: String) {
        CoreSelection.setVersion(version, config.value.selectedCore) { core, trimmed, normalized ->
            updateConfig { it.copy(mcVersion = normalized) }
            // Keep effects outside the replayable config transform: concurrent updates can replay it.
            if (normalized != trimmed) showVersionCorrectedMessage(core, trimmed, normalized)
        }
    }
}
