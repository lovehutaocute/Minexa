package com.mineserve.mobile.ui.vm

import com.mineserve.mobile.data.AutoBackupType
import com.mineserve.mobile.data.McConfig

internal class BackupSettingsDelegate(
    private val updateConfig: ((McConfig) -> McConfig) -> Unit
) {
    fun setAutoBackupInterval(min: Int) {
        updateConfig {
            it.copy(autoBackupIntervalMin = if (min <= 0) 0 else min.coerceIn(5, 10080))
        }
    }

    fun setAutoBackupType(type: AutoBackupType) {
        updateConfig { it.copy(autoBackupType = type) }
    }

    fun setMaxSnapshots(max: Int) {
        updateConfig { it.copy(maxSnapshots = max.coerceIn(1, 100)) }
    }

    fun setStopAutoBackup(enabled: Boolean) {
        updateConfig { it.copy(stopAutoBackup = enabled) }
    }
}
