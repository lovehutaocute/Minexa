package com.mineserve.mobile.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 文件管理权限的统一入口。
 *
 * 为什么必须集中：新版把服务器放到公共存储 `/storage/emulated/0/Minexa/Minecraftserver/`，
 * 没有权限时 `File.mkdirs()` 只是**静默返回 false**，于是下载/导入会在几十秒后以
 * 「无法写入」这类看不懂的错误收场，甚至留下半个目录。所以所有文件落盘路径都先问这里。
 *
 * 三档权限模型（按系统版本）：
 *  - Android 10 及以下：`WRITE_EXTERNAL_STORAGE` 运行时权限；
 *  - Android 11+：`MANAGE_EXTERNAL_STORAGE`（"所有文件访问"），走**设置页**授权，
 *    没有可用的运行时对话框，因此必须能检测「用户从设置页返回后是否真的给了」；
 *  - targetSdk=28 意味着在 Android 11+ 上系统仍按旧存储模型放行部分路径，
 *    但固定外部路径的写入依然要「所有文件访问」，不能靠 targetSdk 蒙混。
 */
object StorageAccess {

    private const val TAG = "StorageAccess"

    /**
     * 应用启动后是否已经主动弹过一次。
     *
     * 用途是**同一进程内不重复弹**：用户拒绝后每次冷启动都要再提示（产品要求），
     * 但同一次运行里反复进入前台不该反复弹系统页 —— 那会变成「从设置页返回 →
     * 又被踢回设置页」的死循环，用户只会以为 App 坏了。
     */
    @Volatile
    private var promptedThisProcess = false

    private val _granted = MutableStateFlow(false)

    /** 供 UI 观察的授权状态；每次 [refresh] 更新 */
    val granted: StateFlow<Boolean> = _granted.asStateFlow()

    /** 旧版权限（Android 10 及以下）对应的运行时权限名 */
    const val LEGACY_PERMISSION: String = Manifest.permission.WRITE_EXTERNAL_STORAGE

    /** Android 11+ 需要「所有文件访问」 */
    fun requiresAllFilesAccess(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** 当前是否持有写公共存储的权限（只查状态，不做 IO） */
    fun hasAllFilesAccess(context: Context): Boolean {
        return try {
            if (requiresAllFilesAccess()) {
                Environment.isExternalStorageManager()
            } else {
                context.checkSelfPermission(LEGACY_PERMISSION) == PackageManager.PERMISSION_GRANTED
            }
        } catch (e: Exception) {
            Log.w(TAG, "权限状态查询失败：${e.message}")
            false
        }
    }

    /**
     * 是否**真的能用**公共存储：权限位为真，或实际落盘探测通过。
     *
     * 为什么还要探测：targetSdk=28 在 Android 11+ 上仍走旧存储模型，很多设备
     * 不给「所有文件访问」也能直接写 `/storage/emulated/0`。只看权限位会把这类
     * 设备误判成未授权，进而挡住下载/导入，还把用户引到不存在的开关前。
     */
    fun isUsable(context: Context): Boolean =
        hasAllFilesAccess(context) || ServerStorage.isInternalWritable(context)

    fun refresh(context: Context): Boolean {
        val usable = isUsable(context)
        _granted.value = usable
        return usable
    }

    /**
     * 本次进程启动是否应该自动弹一次授权。
     *
     * 已经可用 → false；本次已弹过 → false；否则 true 并立刻记为已弹。
     * 这样「每次启动重新提示」与「同一次运行不连环弹」两个要求同时成立。
     */
    fun consumeAutoPrompt(context: Context): Boolean {
        if (refresh(context)) return false
        if (promptedThisProcess) return false
        promptedThisProcess = true
        return true
    }

    /** 用户手动点了「授权」时也走这里，允许再弹一次（不受进程内一次限制） */
    fun allowManualPrompt() {
        promptedThisProcess = false
    }

    /**
     * 构造授权页 Intent。
     *
     * Android 11+ 优先直达本应用的「所有文件访问」页面；部分 ROM（vivo/OPPO 等）
     * 会把这个 action 拦掉或改道，因此返回的 Intent 用 [resolveActivity] 校验过，
     * 找不到时调用方应退回到 [appDetailsIntent]。
     */
    fun allFilesSettingsIntent(context: Context): Intent? {
        if (!requiresAllFilesAccess()) return null
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return intent.takeIf { it.resolveActivity(context.packageManager) != null }
    }

    /** 兜底：应用详情页（几乎所有 ROM 都可用，用户可在「权限」里手动给存储） */
    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** 未授权时给用户看的完整说明（含两个目录，便于判断影响范围） */
    fun permissionRationale(context: Context): String =
        "Minexa 需要「所有文件访问」权限才能把服务器下载/导入到 " +
            "${ServerStorage.internalRoot(context).absolutePath}，" +
            "并读取旧版本留在私有目录中的服务器。未授权时不会执行任何文件操作。"

    /** 统一日志：权限变化时留痕，便于从崩溃/诊断日志回溯 */
    fun logState(context: Context, where: String) {
        val granted = hasAllFilesAccess(context)
        Log.i(TAG, "[$where] 文件权限=${if (granted) "已授权" else "未授权"} ${ServerStorage.describe(context)}")
    }
}
