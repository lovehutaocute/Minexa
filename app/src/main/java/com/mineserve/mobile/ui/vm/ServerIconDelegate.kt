package com.mineserve.mobile.ui.vm

import android.content.Context
import android.net.Uri
import com.mineserve.mobile.R
import com.mineserve.mobile.server.PowerNukkitXLayout
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ServerIconDelegate(
    private val context: Context,
    private val scope: CoroutineScope,
    private val activeDirName: () -> String?,
    private val serverDirFor: (String) -> File,
    private val reportError: (String) -> Unit,
    private val reportMessage: (String) -> Unit
) {
    private val _serverIconVersion = MutableStateFlow(0)
    val serverIconVersion: StateFlow<Int> = _serverIconVersion.asStateFlow()

    fun serverIconFile(): File? {
        val dirName = activeDirName() ?: return null
        val dir = serverDirFor(dirName)
        return if (PowerNukkitXLayout.isPowerNukkitX(dir)) null
        else File(dir, "server-icon.png")
            .takeIf { it.exists() }
    }

    fun setServerIcon(uri: Uri) {
        val dirName = activeDirName() ?: run {
            reportError(str(R.string.s212))
            return
        }
        if (PowerNukkitXLayout.isPowerNukkitX(serverDirFor(dirName))) {
            reportError(str(R.string.err_pnx_icon_full))
            return
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw RuntimeException(str(R.string.err_read_image))
                    val src = input.use { android.graphics.BitmapFactory.decodeStream(it) }
                        ?: throw RuntimeException(str(R.string.err_parse_image))
                    try {
                        // 居中裁剪为正方形后缩放到 64×64
                        val size = minOf(src.width, src.height)
                        val x = (src.width - size) / 2
                        val y = (src.height - size) / 2
                        val square = android.graphics.Bitmap.createBitmap(src, x, y, size, size)
                        try {
                            val icon = android.graphics.Bitmap.createScaledBitmap(square, 64, 64, true)
                            try {
                                val target = File(serverDirFor(dirName), "server-icon.png")
                                target.parentFile?.mkdirs()
                                java.io.FileOutputStream(target).use { fos ->
                                    icon.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos)
                                }
                            } finally {
                                if (!icon.isRecycled) icon.recycle()
                            }
                        } finally {
                            if (square !== src && !square.isRecycled) square.recycle()
                        }
                    } finally {
                        if (!src.isRecycled) src.recycle()
                    }
                }
                reportMessage(str(R.string.ui_server_icon_updated))
                _serverIconVersion.value++
            } catch (e: Exception) {
                reportError(str(R.string.ui_server_icon_fail, e.message))
            }
        }
    }

    fun removeServerIcon() {
        val dirName = activeDirName() ?: run {
            reportError(str(R.string.s212))
            return
        }
        if (PowerNukkitXLayout.isPowerNukkitX(serverDirFor(dirName))) {
            reportError(str(R.string.err_pnx_icon))
            return
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    File(serverDirFor(dirName), "server-icon.png").delete()
                }
                reportMessage(str(R.string.ui_server_icon_reset_done))
                _serverIconVersion.value++
            } catch (e: Exception) {
                reportError(str(R.string.ui_server_icon_fail, e.message))
            }
        }
    }

    private fun str(id: Int, vararg args: Any?): String =
        context.getString(id, *args)
}
