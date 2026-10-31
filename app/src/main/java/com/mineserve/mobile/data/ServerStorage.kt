package com.mineserve.mobile.data

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import com.mineserve.mobile.R
import kotlinx.serialization.Serializable
import java.io.File

/**
 * 服务器文件所在的存储位置。
 *
 * 现版本服务器**只**存在于 [Internal]（统一存储目录
 * `/storage/emulated/0/Minexa/Minecraftserver/`）。
 *
 * [Legacy] 只作为「旧配置里留下的历史标记」存在：
 *  - 升级用户的核心记录里可能还是这个值；
 *  - 这种记录在列表里不显示、不可启动，只能通过「迁移」对话框搬到新目录；
 *  - 迁移完成后由迁移器改写为 [Internal]。
 *
 * 保留枚举而不是删掉它，是因为 `McConfig` 是 JSON 持久化的：删掉枚举值会让老配置
 * 反序列化失败，进而触发「配置解析失败 → 拒绝落盘」的保护，用户设置会整体卡住。
 */
@Serializable
enum class StorageLocation {
    Internal,
    Legacy;

    companion object {
        /** 反序列化兜底：老配置没有这个字段时按旧私有目录解释会误判，故默认 Internal。 */
        fun of(name: String?): StorageLocation =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Internal
    }
}

/**
 * 服务器目录的唯一真相来源。
 *
 * 为什么需要单独一层：历史上所有路径都是 `File(installer.rootDir, "home/servers/$dirName")`
 * 直接拼出来的，散落在 40 多处。改成统一存储目录之后，任何一处自己拼路径都会
 * 指向不存在的位置（症状：列表里有、点进去空、启动报「找不到 jar」）。
 *
 * 因此约定：新代码一律走 [serversDir] / [dirFor]，不要再自己拼根目录。
 *
 * 旧版目录（[legacyRootCandidates]）只被迁移流程读取：扫描、搬运、删源。
 * 它**不是**服务器的运行位置，也不参与任何文件定位。
 */
object ServerStorage {

    private const val TAG = "ServerStorage"

    /** 公共存储下的固定层级：/storage/emulated/0/Minexa/Minecraftserver */
    const val PUBLIC_DIR_NAME = "Minexa"
    const val PUBLIC_SUB_DIR = "Minecraftserver"

    /** 服务器目录的唯一根：/storage/emulated/0/Minexa/Minecraftserver */
    fun internalRoot(context: Context): File =
        File(Environment.getExternalStorageDirectory(), "$PUBLIC_DIR_NAME/$PUBLIC_SUB_DIR")

    /** 旧版存放服务端的根目录（**只用于迁移扫描**，不再参与运行定位）。
     *
     * 历史路径是 `<filesDir>/home/home/servers`，不是 `<filesDir>/home/servers`：
     * 旧代码里 `BootstrapInstaller.rootDir` 本身已经是 `filesDir/home`，
     * 而 `serversDir` 又拼了一段 `home/servers`。这一点曾经搞错，导致
     * 「私有目录读不到、老服务器被当成不存在、还在新目录里凭空建出空文件夹」。
     *
     * 第二个候选是为极端情况兜底（例如有人手工把目录搬过一层），成本只有一次 stat。
     */
    fun legacyRootCandidates(context: Context): List<File> = listOf(
        File(context.filesDir, "home/home/servers"),
        File(context.filesDir, "home/servers")
    )

    /** 实际存在的旧根目录（用于显示与 provider 文档路径）；都不存在时返回历史主路径 */
    fun legacyRoot(context: Context): File =
        legacyRootCandidates(context).firstOrNull { it.isDirectory }
            ?: legacyRootCandidates(context).first()

    /**
     * 旧目录里所有「有内容」的服务器目录，供迁移使用。
     *
     * 空目录不算：那多半是旧版某次失败留下的壳子，提示迁移只会打扰用户。
     */
    fun legacyServerDirs(context: Context): List<File> =
        legacyRootCandidates(context)
            .filter { it.isDirectory }
            .flatMap { root ->
                root.listFiles { f -> f.isDirectory }.orEmpty().toList()
            }
            .filter { !isEmptyDirectory(it) }
            .distinctBy { it.name }
            .sortedBy { it.name.lowercase() }

    /** 目录名 → 目录（服务器**只**存在于统一存储目录） */
    fun dirFor(context: Context, dirName: String): File =
        File(internalRoot(context), dirName)

    /** 所有服务器目录的根：新下载 / 导入 / 还原一律落在这里 */
    fun serversDir(context: Context): File = internalRoot(context)

    /**
     * 已被占用的目录名集合：统一存储目录下的实际目录 + 已登记的核心。
     *
     * 跨根唯一曾经是为了区分新旧同名服务器；现在服务器只有统一目录一个根，
     * 但**旧目录里的同名项仍可能被迁移进来**，所以迁移器会单独处理重名，
     * 这里不再把旧目录算作占用 —— 新服务器不该因为旧目录有个同名壳子而被改名。
     */
    fun takenDirNames(context: Context, configured: Collection<String> = emptyList()): Set<String> {
        val names = HashSet<String>(configured)
        runCatching {
            internalRoot(context).listFiles { f -> f.isDirectory }?.forEach { names += it.name }
        }
        return names
    }

    /**
     * 规范化已登记核心的位置字段。
     *
     * 新规则（「完全不兼容旧目录」之后）：
     *  - 服务器**只**存在于统一存储目录，[StorageLocation.Legacy] 只是配置里的历史值，
     *    不代表「可以在旧目录里运行」；
     *  - 唯一的自动纠正是：某条 Legacy 记录的目录**已经**出现在统一目录里且非空
     *    （用户手工搬过去、或迁移刚完成）→ 就地改为 Internal；
     *  - 其余 Legacy 记录原样保留，等着迁移流程处理（列表里不显示、不可启动）。
     *
     * 这里不做任何文件搬运，也不删除任何东西 —— 迁移是用户显式确认后才发生的事。
     */
    fun reconcile(context: Context, config: McConfig): McConfig {
        if (config.installedCores.isEmpty()) return config
        val (cores, changed) = normalizeCores(config.installedCores, internalRoot(context))
        return if (changed) {
            val pending = cores.count { it.location == StorageLocation.Legacy }
            Log.i(TAG, "已按磁盘纠正核心位置；仍有 $pending 个待迁移的旧版服务器")
            config.copy(installedCores = cores)
        } else {
            config
        }
    }

    /**
     * [reconcile] 的纯逻辑版本（无 Context，便于 JVM 单测覆盖边界）。
     *
     * @return 规范化后的列表与「是否发生过变化」
     */
    fun normalizeCores(
        cores: List<InstalledCore>,
        internalRoot: File
    ): Pair<List<InstalledCore>, Boolean> {
        var changed = false
        val result = cores.map { core ->
            if (core.location != StorageLocation.Legacy) {
                core
            } else {
                val dir = File(internalRoot, core.dirName)
                if (dir.isDirectory && !isEmptyDirectory(dir)) {
                    changed = true
                    core.copy(location = StorageLocation.Internal)
                } else {
                    core
                }
            }
        }
        return result to changed
    }

    /**
     * 公共存储根**实际可写**吗。
     *
     * 只做真实落盘探测，不看权限状态位 —— 这一点很重要：本应用 targetSdk=28，
     * 在 Android 11+ 上系统仍按旧存储模型放行大量路径，此时
     * `Environment.isExternalStorageManager()` 会返回 false，但目录其实写得进去。
     * 若以权限位为唯一判据，就会在「本来能用」的设备上拒绝下载，
     * 还把用户引到一个根本不会列出的「所有文件访问」开关前面。
     *
     * 因此判据统一是「能不能写」，权限位只用来决定要不要提示去授权。
     */
    fun isInternalWritable(context: Context): Boolean {
        return try {
            val root = internalRoot(context)
            if (!root.isDirectory && !root.mkdirs()) return false
            val probe = File(root, ".mineserve_write_probe")
            val ok = probe.exists() || probe.createNewFile()
            if (ok) probe.delete()
            ok
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "公共存储根不可写：${e.message}") }
            false
        }
    }

    /** 新建服务器前的守卫：不可写时抛出可直接照做的错误，而不是让下载中途失败。 */
    fun requireInternalWritable(context: Context) {
        if (isInternalWritable(context)) return
        val reason = if (!StorageAccess.hasAllFilesAccess(context)) {
            "尚未授予文件管理权限"
        } else {
            "目录 ${internalRoot(context).absolutePath} 无法写入"
        }
        throw IllegalStateException(
            "$reason，无法在统一存储目录创建服务器。请在「设置 → 存储权限」中完成授权后重试"
        )
    }

    /** 供诊断/关于页展示的一行摘要 */
    fun describe(context: Context): String =
        "internal=${internalRoot(context).absolutePath}, " +
            "legacy=${legacyRoot(context).absolutePath}, " +
            "sdk=${Build.VERSION.SDK_INT}"

    /** 统一存储目录下 [dirName] 这个服务器目录「存在且里面有文件」。 */
    fun dirExistsWithContent(context: Context, dirName: String): Boolean =
        containsAnyFile(File(internalRoot(context), dirName))

    /**
     * 目录（含任意深度的子目录）里是否**至少存在 1 个文件**。
     *
     * 这是「是不是一个有效的核心目录」的唯一判据：
     *  - 只有一个空文件夹 / 只有空的 `plugins/`、`logs/` 子目录 ⇒ false（空壳，不算核心）；
     *  - 里面有 1 个文件，不管认不认识（`.dat`、`README`、甚至 `.gitignore`）⇒ true。
     *
     * 与旧实现（忽略 `.` 开头的条目、只往下看 4 层）的差别：
     *  - 隐藏文件同样算「有文件」——用户要求「无论什么文件」，而实际见过
     *    「目录里只有一个 `.mineserve-exec-repaired` 之类标记文件」的半成品；
     *  - 深度上限放宽到 12 层，仍然防止循环软链接把扫描拖死。
     */
    fun containsAnyFile(dir: File, depth: Int = 0): Boolean {
        if (!dir.isDirectory) return false
        val children = dir.listFiles() ?: return false
        for (child in children) {
            if (child.isFile) return true
            if (child.isDirectory && depth < 12 && containsAnyFile(child, depth + 1)) return true
        }
        return false
    }

    /** 目录是否为空（判定「空壳目录」用）：整棵子树里没有任何文件即视为空。 */
    fun isEmptyDirectory(dir: File): Boolean {
        if (!dir.isDirectory) return false
        return !containsAnyFile(dir)
    }
}
