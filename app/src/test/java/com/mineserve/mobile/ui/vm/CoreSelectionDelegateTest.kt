package com.mineserve.mobile.ui.vm

import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.ServerCore
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreSelectionDelegateTest {
    @Test
    fun selectingSameCoreResetsVersionButKeepsCustomPort() {
        val config = MutableStateFlow(
            McConfig(selectedCore = ServerCore.Paper, mcVersion = "1.21.4", localPort = 25562)
        )
        val reselectMessages = mutableListOf<ServerCore>()
        val delegate = delegate(config, reselectMessages)

        delegate.selectCore(ServerCore.Paper)

        assertEquals("", config.value.mcVersion)
        assertEquals(25562, config.value.localPort)
        assertEquals(listOf(ServerCore.Paper), reselectMessages)
    }

    @Test
    fun selectingBdsMigratesDefaultPortWithoutVersionPrompt() {
        val config = MutableStateFlow(McConfig(mcVersion = "1.21.4", localPort = 25565))
        val reselectMessages = mutableListOf<ServerCore>()
        val delegate = delegate(config, reselectMessages)

        delegate.selectCore(ServerCore.Bds)

        assertEquals(ServerCore.Bds, config.value.selectedCore)
        assertEquals("", config.value.mcVersion)
        assertEquals(19132, config.value.localPort)
        assertTrue(reselectMessages.isEmpty())
    }

    @Test
    fun setMcVersionTrimsInputBeforeSaving() {
        val config = MutableStateFlow(McConfig())
        val reselectMessages = mutableListOf<ServerCore>()
        val correctedMessages = mutableListOf<Triple<ServerCore, String, String>>()
        val delegate = CoreSelectionDelegate(
            config = config,
            updateConfig = { transform -> config.value = transform(config.value) },
            showVersionReselectMessage = { reselectMessages += it },
            showVersionCorrectedMessage = { core, input, normalized ->
                correctedMessages += Triple(core, input, normalized)
            }
        )

        delegate.setMcVersion(" 1.21.11 ")

        assertEquals("1.21.11", config.value.mcVersion)
        assertTrue(correctedMessages.isEmpty())
    }

    private fun delegate(
        config: MutableStateFlow<McConfig>,
        reselectMessages: MutableList<ServerCore>
    ) = CoreSelectionDelegate(
        config = config,
        updateConfig = { transform -> config.value = transform(config.value) },
        showVersionReselectMessage = { reselectMessages += it },
        showVersionCorrectedMessage = { _, _, _ -> }
    )
}
