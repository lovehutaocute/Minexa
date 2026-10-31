package com.mineserve.mobile.data

/**
 * 当前 BDS 服务器的核心版本情况（供界面展示，不含任何 IO）。
 *
 * [installedVersion] 来自磁盘上的 `bds-version.txt`（用户自行解压的包可能没有，
 * 此时为 null，界面显示「未知」而不是猜一个版本号）；[stable] / [preview] 来自
 * 官方 links 接口，[fromCache] 表示这次是离线读缓存的结果。
 */
data class BdsCoreStatus(
    val dirName: String? = null,
    val installedVersion: String? = null,
    val stable: BdsBuild? = null,
    val preview: BdsBuild? = null,
    val fromCache: Boolean = false,
    val checked: Boolean = false,
    val checking: Boolean = false,
    val error: String? = null
) {
    fun build(channel: BdsChannel): BdsBuild? =
        if (channel == BdsChannel.Stable) stable else preview

    /** 该通道是否有比当前更新的版本；当前版本未知时按「有更新」处理 */
    fun hasUpdate(channel: BdsChannel): Boolean =
        BdsBuildCatalog.isNewer(build(channel)?.version, installedVersion)

    /** 已安装版本是否本身就是 Preview 通道（版本号 ≥ 稳定版时提示降级到稳定版） */
    val isAheadOfStable: Boolean
        get() = installedVersion != null && stable != null &&
            BdsBuildCatalog.compareVersions(installedVersion, stable!!.version) > 0
}

/** BDS 核心更新的运行状态，界面据此显示进度条与结果 */
sealed interface BdsUpdateProgress {
    data object Idle : BdsUpdateProgress

    /**
     * @param progress 0..1；null 表示当前阶段无法量化（解压/校验），显示不确定进度条
     */
    data class Running(
        val channel: BdsChannel,
        val version: String,
        val phase: String,
        val progress: Float? = null
    ) : BdsUpdateProgress

    /** [backupName] 是更新前自动备份的文件名（在「世界与服务器的备份」目录里） */
    data class Done(
        val version: String,
        val backupName: String,
        val keptOldEntry: String?
    ) : BdsUpdateProgress

    data class Failed(val message: String, val rolledBack: Boolean) : BdsUpdateProgress
}
