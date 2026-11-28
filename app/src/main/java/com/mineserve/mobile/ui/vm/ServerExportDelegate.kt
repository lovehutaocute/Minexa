package com.mineserve.mobile.ui.vm

import android.content.Context
import android.net.Uri
import com.mineserve.mobile.R
import java.io.File

internal class ServerExportDelegate(
    private val app: Context,
    private val activeDirName: () -> String?,
    private val serverDirFor: (String) -> File,
    private val exportPathToUri: (File, Uri) -> Unit,
    private val reportError: (String) -> Unit
) {
    fun exportServerToUri(uri: Uri) {
        val dirName = activeDirName() ?: return
        val dir = serverDirFor(dirName)
        if (!dir.exists()) {
            reportError(app.getString(R.string.s310))
            return
        }
        exportPathToUri(dir, uri)
    }
}
