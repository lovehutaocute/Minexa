package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.server.McServerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal class ServerCommandDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    private val config: StateFlow<McConfig>,
    private val controller: McServerController,
    private val reportError: (String) -> Unit
) {
    fun sendCommand(line: String) {
        try {
            controller.sendCommand(line)
        } catch (e: Exception) {
            reportError(app.getString(R.string.s211, e.message))
        }
    }

    fun saveWorld() {
        val core = config.value.installedCores
            .firstOrNull { it.name == config.value.activeCoreName }?.core
        val commands = core?.consoleSaveCommands ?: listOf("save-all")
        sendCommand(commands.first())
        commands.drop(1).forEachIndexed { index, cmd ->
            scope.launch {
                delay(800L * (index + 1))
                sendCommand(cmd)
            }
        }
    }
}
