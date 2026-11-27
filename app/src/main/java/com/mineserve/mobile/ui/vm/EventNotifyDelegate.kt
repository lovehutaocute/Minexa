package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.data.ServerEventNotifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class EventNotifyDelegate(
    private val app: Context
) {
    private val _eventNotifyLevel = MutableStateFlow(ServerEventNotifier.level(app))
    val eventNotifyLevel: StateFlow<Int> = _eventNotifyLevel.asStateFlow()

    fun setEventNotifyLevel(value: Int) {
        val level = value.coerceIn(0, 2)
        ServerEventNotifier.setLevel(app, level)
        _eventNotifyLevel.value = level
    }
}
