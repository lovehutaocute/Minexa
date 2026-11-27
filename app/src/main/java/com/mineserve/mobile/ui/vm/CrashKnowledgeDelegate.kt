package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.server.CrashKnowledgeBase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal class CrashKnowledgeDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    private val reportUpdated: (String) -> Unit
) {
    private val crashKnowledgeBase by lazy { CrashKnowledgeBase(app) }

    suspend fun refreshIfStale() {
        crashKnowledgeBase.refreshIfStale()
    }

    fun refreshCrashKnowledge() {
        scope.launch(Dispatchers.IO) {
            val updated = runCatching { crashKnowledgeBase.refresh() }.getOrDefault(false)
            if (updated) reportUpdated(app.getString(R.string.crash_knowledge_updated))
        }
    }
}
