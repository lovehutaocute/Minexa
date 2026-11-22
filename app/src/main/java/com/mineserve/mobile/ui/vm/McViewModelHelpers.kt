package com.mineserve.mobile.ui.vm

import com.mineserve.mobile.server.BdsWorldGen

internal fun clearMarkKey(dirName: String) = "mc_console_clear_$dirName"

internal fun formatDiagnosticBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1_024.0)
    else -> "$bytes B"
}

/** 当前核心对应的 Modrinth 加载器名 */
internal fun modrinthLoader(core: com.mineserve.mobile.data.ServerCore): String =
    if (core == com.mineserve.mobile.data.ServerCore.Fabric || core == com.mineserve.mobile.data.ServerCore.Quilt) "fabric"
    else "forge"

internal fun previewLayers(text: String): List<BdsWorldGen.Layer>? = BdsWorldGen.parseLayers(text)

internal fun formatCoreSize(bytes: Long): String =
    if (bytes >= 1024 * 1024) "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else if (bytes >= 1024) "%.1f KB".format(bytes / 1024.0)
    else "$bytes B"

internal fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
