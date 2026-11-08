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

    /** [version] 是否就是当前装着的版本（段数不同也算同一版） */
    fun isInstalled(version: String): Boolean = BdsVersions.sameRelease(version, installedVersion)

    /**
     * [version] 是否比当前版本旧。
     *
     * 独立的判定而不是 `!sameRelease && compare<0` 写在界面里：回退到旧版本
     * 需要额外的警告（世界存档已被新版本升级，降级可能读不出来），
     * 这条规则必须只有一处，不能散落在几个 Composable 里。
     */
    fun isDowngrade(version: String): Boolean {
        val installed = installedVersion ?: return false
        if (isInstalled(version)) return false
        return BdsVersions.compare(version, installed) < 0
    }
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

    /**
     * @param backupName 换版前自动备份的文件名（在「世界与服务器的备份」目录里）
     * @param integrity 本次安装包用的校验强度（清单 SHA-256 / 本地记录 / 结构 + CRC）
     */
    data class Done(
        val version: String,
        val backupName: String,
        val keptOldEntry: String?,
        val integrity: String = ""
    ) : BdsUpdateProgress

    /**
     * 失败。
     *
     * [version] 是本次想装的目标版本：带上它界面才能给「重新下载」按钮 ——
     * 完整性校验失败（下载到坏包 / 内容被换）时，唯一的正解是重下一次，
     * 而不是让用户重新翻一遍列表。
     */
    data class Failed(
        val message: String,
        val rolledBack: Boolean,
        val version: String? = null
    ) : BdsUpdateProgress
}

/**
 * 历史版本清单的加载状态。
 *
 * [fromCache] 必须单独存在：远端清单拉不到时会退回内置 + 缓存，
 * 界面要如实说「离线清单」，否则用户会把「列表里没有刚发布的版本」
 * 当成官方没发。
 */
data class BdsVersionListState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val fromCache: Boolean = false,
    val error: String? = null,
    val notes: List<String> = emptyList()
)
