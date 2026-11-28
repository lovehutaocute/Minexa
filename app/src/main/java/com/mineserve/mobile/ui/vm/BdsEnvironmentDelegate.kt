package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.data.BdsEnvStatus
import com.mineserve.mobile.server.BdsEnvironmentManager
import kotlinx.coroutines.flow.StateFlow

internal class BdsEnvironmentDelegate(
    private val app: Context,
    private val environment: BdsEnvironmentManager,
    private val isBootstrapped: () -> Boolean,
    private val reportError: (String) -> Unit
) {
    val status: StateFlow<BdsEnvStatus> = environment.status

    fun refresh() = environment.refresh()

    fun initialize() {
        if (!isBootstrapped()) {
            reportError(app.getString(R.string.bds_env_need_bootstrap))
            return
        }
        environment.startInitialize()
    }

    fun cancel() = environment.cancel()

    fun removeBox64() = environment.removeBox64()
}
