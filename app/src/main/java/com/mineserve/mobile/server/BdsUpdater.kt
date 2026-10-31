package com.mineserve.mobile.server

import android.util.Log
import com.mineserve.mobile.data.BdsBuild
import com.mineserve.mobile.data.DownloadSource
import com.mineserve.mobile.data.MultiSourceDownload
import com.mineserve.mobile.data.ServerRepository
import com.mineserve.mobile.runtime.TermuxRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/**
 * 官方 BDS 核心的**在线更新**：备份 → 下载 → 校验 → 暂存 → 安全替换 → 回滚。
 *
 * 这不是「下载一个 jar 覆盖同名文件」那种更新。BDS 的安装包是一个 93MB 的 zip，
 * 解压后是 265MB 的 `bedrock_server` 加一整套只读资源目录，**并且安装包里同样带着
 * `server.properties`、`allowlist.json`、`permissions.json`**（内容都是空默认值）。
 * 直接解压到服务器目录 = 把用户的设置、白名单、权限全清空。因此这里必须按顺序做：
 *
 *  1. **备份**（[BackupManager.backupBdsBeforeUpdate]）：只备用户数据面
 *     （worlds / 三份配置 / 行为包 / 资源包 / config），不含核心本体 ——
 *     备份失败立刻中止，一个字节都不动服务器目录；
 *  2. **下载到临时目录**并做包级校验（zip 可打开、含 `bedrock_server`、体积合理）。
 *     多源下载层会把半截文件写在 `.part` 上，校验通过才改名，因此服务器目录里
 *     永远不会出现「下载到一半的安装包」；
 *  3. **解压到暂存目录**（不在服务器目录内）并校验：主程序存在、ELF 架构可运行、
 *     体积正常、`server.properties` 在。暂存校验失败同样不动服务器目录；
 *  4. **保留旧核心**：把 `bedrock_server` 改名为 `bedrock_server.old-<旧版本>`
 *     （改名不占额外空间，且随时可以改回来）；
 *  5. **合并安装**：官方文件覆盖，**用户数据文件不覆盖**
 *     （[PRESERVED_FILES] / [PRESERVED_DIRS]）；用户自己放进 `behavior_packs/`
 *     的包也不会被删 —— 安装只写不删；
 *  6. **落地后复检**：用 [BdsSupport.checkLaunch] 再确认一次新核心能跑，
 *     不通过就把 `.old` 改回原名、删掉本次写入的文件，回到更新前的状态；
 *  7. 成功后写 `bds-version.txt`、更新配置里的版本号，并清掉旧版本的
 *     265MB 运行副本（[BdsRuntime.payloadDir]），避免两份核心同时占空间。
 *
 * 服务器必须在**停止状态**下更新：运行中替换 `bedrock_server` 只会让正在跑的
 * 进程继续用旧 inode，界面显示「已更新」而实际还是旧核心，是最坏的结果。
 */
class BdsUpdater(
    private val termux: TermuxRuntime,
    private val repo: ServerRepository
) {

    private val backups = BackupManager(termux)

    private val TAG = "BdsUpdater"

    /** 更新过程的阶段，界面按它切换文案与进度条形态 */
    enum class Phase { BackingUp, Downloading, Verifying, Installing }

    /** 一次进度事件：[progress] 为 null 表示「无法量化」，界面显示不确定进度条 */
    data class Event(val phase: Phase, val progress: Float? = null, val message: String = "")

    data class Result(
        val version: String,
        /** 更新前自动备份 zip 的绝对路径（一定存在，否则不会走到成功分支） */
        val backupPath: String,
        /** 保留下来的旧核心文件名（`bedrock_server.old-1.26.61.1`），可在文件管理里手动删 */
        val keptOldEntry: String?
    )

    /** 更新失败。[rolledBack] 表示服务器目录是否已回到更新前状态。 */
    class UpdateFailed(
        message: String,
        val rolledBack: Boolean,
        cause: Throwable? = null
    ) : RuntimeException(message, cause)

    /** 目录里记录的已安装版本（`bds-version.txt` 优先，其次 `version.txt`） */
    fun installedVersion(serverDir: File): String? {
        listOf("bds-version.txt", "version.txt").forEach { name ->
            val file = File(serverDir, name)
            if (!file.isFile) return@forEach
            val text = runCatching { file.readText().trim() }.getOrNull()
            if (!text.isNullOrBlank()) return text.lineSequence().first().trim().take(32)
        }
        return null
    }

    suspend fun update(
        dirName: String,
        currentVersion: String?,
        target: BdsBuild,
        onEvent: (Event) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        val serverDir = termux.serverDirFor(dirName)
        if (!serverDir.isDirectory) {
            throw UpdateFailed("服务器目录不存在（$dirName），无法更新", false)
        }
        if (!BdsSupport.isBdsDir(serverDir)) {
            throw UpdateFailed("该服务器目录里没有 ${BdsSupport.ENTRY_NAME}，不是官方 BDS 服务器", false)
        }
        // 运行中替换核心 = 换了个假的核心：进程继续用旧 inode，界面却显示新版本
        if (termux.isMcRunningIn(dirName)) {
            throw UpdateFailed("服务器正在运行，请先停止服务器再更新核心", false)
        }

        val previousVersion = currentVersion?.takeIf { it.isNotBlank() } ?: installedVersion(serverDir)
        val versionFile = File(serverDir, "bds-version.txt")
        val previousVersionText = runCatching { versionFile.takeIf { it.isFile }?.readText() }.getOrNull()

        // ── 1. 备份（失败即中止，服务器目录未被改动） ──────────────
        onEvent(Event(Phase.BackingUp, null, "备份世界存档与服务器配置…"))
        val backupPath = backups.backupBdsBeforeUpdate(dirName, previousVersion)
            ?: throw UpdateFailed("更新前备份失败，已中止更新（服务器文件未改动）", false)
        termux.emitLog("[bds] 更新前已备份用户数据：$backupPath")

        val zip = File(termux.installer.tmpDir, "bds-update-${target.version}.zip")
        val staging = File(termux.installer.tmpDir, "bds-staging-${target.version}")
        zip.delete()
        staging.deleteRecursively()

        try {
            // ── 2. 下载（多源 + 包级校验） ──────────────────────────
            onEvent(Event(Phase.Downloading, 0f, "正在下载 BDS ${target.version}…"))
            MultiSourceDownload.download(
                sources = listOf(DownloadSource("Mojang 官方（${target.channel.downloadType}）", target.url)),
                target = zip,
                onProgress = { downloaded, total, _ ->
                    val ratio = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else null
                    onEvent(Event(Phase.Downloading, ratio, "正在下载 BDS ${target.version}…"))
                },
                perSourceTimeoutMs = UPDATE_DOWNLOAD_TIMEOUT_MS,
                overallTimeoutMs = UPDATE_DOWNLOAD_OVERALL_TIMEOUT_MS,
                validate = ::validatePackage
            )

            // ── 3. 解压到暂存目录并校验 ────────────────────────────
            onEvent(Event(Phase.Verifying, null, "校验并解压安装包…"))
            staging.mkdirs()
            val entries = BdsSupport.extractZip(zip, staging)
            termux.emitLog("[bds] 安装包解压完成：$entries 个文件")
            verifyStaging(staging)

            // ── 4. 保留旧核心 ──────────────────────────────────────
            onEvent(Event(Phase.Installing, null, "替换核心文件…"))
            val oldName = "bedrock_server.old-" + (previousVersion ?: "unknown")
            val oldEntry = File(serverDir, oldName)
            oldEntry.delete()
            pruneOldEntries(serverDir, keepName = oldName)
            val newEntry = BdsSupport.entryFile(serverDir)
            // BDS 目录里没有 bedrock_server 就不是 BDS 服务器了（上面已校验），这里必然存在
            if (!newEntry.renameTo(oldEntry)) {
                throw UpdateFailed(
                    "无法把旧核心改名保留（${oldName}），可能是存储空间不足。已中止更新，服务器文件未改动",
                    false
                )
            }

            // ── 5. 合并安装（不覆盖用户数据、不删除用户文件） ──────
            val written = mutableListOf<File>()
            try {
                BdsInstallRules.installTree(staging, serverDir, written)
                // 新核心必须可执行：zip 里带 unix mode 时 extractZip 已处理，
                // 但复制到 serverDir 后是一个新文件，权限位要再补一次
                BdsSupport.makeExecutable(newEntry)

                // ── 6. 落地后复检 ──────────────────────────────────
                verifyInstalled(serverDir)

                versionFile.writeText(target.version + "\n")
            } catch (e: Throwable) {
                // 回滚：删掉本次新写入的文件 → 旧核心改回原名 → 版本文件恢复原内容
                written.forEach { file -> runCatching { if (file.isFile) file.delete() } }
                runCatching { if (newEntry.isFile) newEntry.delete() }
                val restored = oldEntry.renameTo(newEntry)
                runCatching {
                    if (previousVersionText.isNullOrBlank()) versionFile.delete()
                    else versionFile.writeText(previousVersionText)
                }
                if (e is UpdateFailed) throw e.copyAfterRollback(restored)
                throw UpdateFailed(
                    "替换核心失败，已" + (if (restored) "回滚到更新前状态" else "中止（旧核心保留为 ${oldName}，请手动改回）") +
                        "：${e.message ?: e.javaClass.simpleName}",
                    restored,
                    e
                )
            }

            // ── 7. 更新配置里的版本号 + 清理旧运行副本 ─────────────
            runCatching {
                repo.updateAndSaveConfig { cfg ->
                    cfg.copy(
                        installedCores = cfg.installedCores.map { core ->
                            if (core.dirName == dirName) core.copy(version = target.version) else core
                        }
                    )
                }
            }.onFailure { Log.w(TAG, "更新配置里的 BDS 版本失败: ${it.message}") }
            cleanupRuntimeCopies(dirName, previousVersion, keepVersion = target.version)

            termux.emitLog("[bds] 核心已更新到 ${target.version}（旧核心保留为 $oldName）")
            termux.emitLog("[bds] 更新前备份：$backupPath")
            Result(target.version, backupPath, oldName)
        } finally {
            zip.delete()
            staging.deleteRecursively()
        }
    }

    // ── 校验 ─────────────────────────────────────────────────────

    /**
     * 安装包校验（在 `.part` 上执行，不通过就换源/失败）。
     *
     * 官方不给 SHA-256，但 CDN 给了 `Content-MD5`；这里不额外拉一遍响应头，
     * 改用更直接的事实校验：zip 目录能否完整读出、主程序条目是否在、解压后
     * 应有 265MB 级的主程序。截断的包在这里就会被判出来（ZipFile 读 central
     * directory 失败 / 条目大小对不上）。
     */
    private fun validatePackage(file: File): String? {
        if (file.length() < MIN_PACKAGE_BYTES) {
            return "安装包过小（${file.length()} 字节），下载可能被中断"
        }
        return try {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry(BdsSupport.ENTRY_NAME)
                    ?: return "安装包里没有 ${BdsSupport.ENTRY_NAME}，不是官方 BDS 包"
                if (entry.size in 1 until MIN_ENTRY_BYTES) {
                    return "主程序体积异常（${entry.size} 字节）"
                }
            }
            null
        } catch (e: Exception) {
            "安装包损坏：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 暂存目录校验：结构、主程序架构、体积、配置文件都要对得上才允许动服务器目录 */
    private fun verifyStaging(staging: File) {
        val entry = BdsSupport.entryFile(staging)
        if (!entry.isFile) {
            throw UpdateFailed("安装包解压后缺少 ${BdsSupport.ENTRY_NAME}，已中止更新", false)
        }
        if (entry.length() < MIN_ENTRY_BYTES) {
            throw UpdateFailed("解压出的主程序只有 ${entry.length()} 字节，安装包不完整", false)
        }
        val arch = BdsSupport.elfArch(entry)
            ?: throw UpdateFailed("解压出的 ${BdsSupport.ENTRY_NAME} 不是可识别的 Linux 可执行文件", false)
        val supported = BdsSupport.deviceArch(android.os.Build.SUPPORTED_ABIS.toList())
        val runnable = arch in supported || (arch == BdsSupport.ElfArch.X86_64 && BdsSupport.ElfArch.AARCH64 in supported)
        if (!runnable) {
            throw UpdateFailed("新核心是 ${arch.displayName} 架构，本机无法运行，已中止更新", false)
        }
        if (!File(staging, BdsSupport.PROPERTIES_NAME).isFile) {
            throw UpdateFailed("安装包里缺少 ${BdsSupport.PROPERTIES_NAME}，不是完整安装包", false)
        }
    }

    /** 落地后复检：直接复用启动前检查，保证「能解压」和「能跑」是同一个判据 */
    private fun verifyInstalled(serverDir: File) {
        val check = BdsSupport.checkLaunch(serverDir, android.os.Build.SUPPORTED_ABIS.toList())
        if (!check.ok) {
            throw UpdateFailed("新核心无法运行：${check.reason}", false)
        }
    }

    // ── 文件操作 ─────────────────────────────────────────────────

    /**
     * 只保留最新一份旧核心（规则与保留清单见 [BdsInstallRules]）。
     *
     * 包装一层只是为了在日志里留下「清掉了几份历史核心」—— 那是用户空间被
     * 莫名吃掉 265MB×N 时唯一能追的线索。
     */
    private fun pruneOldEntries(serverDir: File, keepName: String) {
        val removed = BdsInstallRules.pruneOldEntries(serverDir, keepName)
        if (removed > 0) termux.emitLog("[bds] 已清理 $removed 份历史核心文件")
    }

    /**
     * 清掉旧版本的运行副本（`payload/<旧版本>/bedrock_server`，265MB）。
     *
     * 必须在**更新成功之后**做，且同时删掉运行目录里的硬链接：硬链接与副本
     * 指向同一个 inode，只删其中一边不会释放空间。删掉后下次启动
     * `BdsRuntime.stageEntry` 会按新版本复制一份新副本，`prepareRunDir` 重建链接。
     */
    private fun cleanupRuntimeCopies(dirName: String, previousVersion: String?, keepVersion: String) {
        val prefix = termux.installer.rootDir
        val keepDir = BdsRuntime.payloadDir(prefix, keepVersion)
        val payloadRoot = keepDir.parentFile
        if (payloadRoot != null && payloadRoot.isDirectory) {
            val keepName = keepDir.name
            payloadRoot.listFiles()?.forEach { dir ->
                // 旧版本目录、以及无版本标记的历史目录一律清掉
                if (dir.name != keepName && dir.isDirectory) {
                    runCatching { dir.deleteRecursively() }
                }
            }
            if (previousVersion != null && BdsRuntime.payloadDir(prefix, previousVersion).name != keepName) {
                runCatching { BdsRuntime.payloadDir(prefix, previousVersion).deleteRecursively() }
            }
        }
        // 运行目录里指向旧副本的硬链接：删掉，下次启动按新版本重建
        runCatching {
            File(BdsRuntime.runDir(prefix, dirName), BdsSupport.ENTRY_NAME).delete()
        }
    }

    /** 回滚后修正异常里的 [UpdateFailed.rolledBack] 标记（保持原 message） */
    private fun UpdateFailed.copyAfterRollback(rolledBack: Boolean): UpdateFailed =
        UpdateFailed(message ?: "更新失败", rolledBack, this)

    private companion object {
        /** 官方 Linux 包现在约 93MB；低于 20MB 一定是错误页或被截断 */
        const val MIN_PACKAGE_BYTES = 20L * 1024 * 1024

        /** 解压后的主程序约 265MB；低于 50MB 说明包不完整 */
        const val MIN_ENTRY_BYTES = 50L * 1024 * 1024

        /** 93MB 的包在 3G 网络下也要能下完：单源 15 分钟、总预算 20 分钟 */
        const val UPDATE_DOWNLOAD_TIMEOUT_MS = 15L * 60 * 1000
        const val UPDATE_DOWNLOAD_OVERALL_TIMEOUT_MS = 20L * 60 * 1000
    }
}
