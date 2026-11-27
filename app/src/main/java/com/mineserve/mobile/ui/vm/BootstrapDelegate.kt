package com.mineserve.mobile.ui.vm

import com.mineserve.mobile.McApplication
import kotlinx.coroutines.flow.StateFlow

internal class BootstrapDelegate(
    private val app: McApplication
) {
    val isBootstrapped: StateFlow<Boolean> get() = app.isBootstrapped
    val bootstrapError: StateFlow<String?> get() = app.bootstrapError
    val bootstrapSpeed: StateFlow<Long> get() = app.bootstrapSpeed
    val currentMirrorIndex: StateFlow<Int> get() = app.currentMirrorIndex
    val mirrorSources: List<String> get() = app.mirrorSources

    fun switchBootstrapMirror() {
        android.util.Log.i("McViewModel", "[切换] switchBootstrapMirror 调用, 线程=${Thread.currentThread().name}")
        app.switchBootstrapMirror()
    }

    fun retryBootstrap() {
        app.startBootstrap()
    }

    fun deleteBootstrap() {
        app.deleteBootstrap()
    }

    fun forceDeleteBootstrap() {
        app.forceDeleteBootstrap()
    }
}
