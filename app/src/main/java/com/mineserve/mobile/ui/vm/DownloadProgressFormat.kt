package com.mineserve.mobile.ui.vm

internal fun formatSpeed(bytesPerSec: Long): String {
    val mb = bytesPerSec / 1024.0 / 1024.0
    val kb = bytesPerSec / 1024.0
    return when {
        mb >= 1.0 -> String.format("%.2f MB/s", mb)
        kb >= 1.0 -> String.format("%.0f KB/s", kb)
        else -> "$bytesPerSec B/s"
    }
}

internal fun formatSize(bytes: Long): String {
    val mb = bytes / 1024.0 / 1024.0
    val kb = bytes / 1024.0
    return when {
        mb >= 1.0 -> String.format("%.1f MB", mb)
        kb >= 1.0 -> String.format("%.0f KB", kb)
        else -> "$bytes B"
    }
}
