package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.data.BdsVersionCatalogLoader
import com.mineserve.mobile.data.BdsVersionEntry
import com.mineserve.mobile.data.BdsVersionListState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class BdsCoreUpdateDelegate(
    private val app: Context,
    private val scope: CoroutineScope
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

    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)
}
