package com.mineserve.mobile.data

import android.content.ContentResolver
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File

/**
 * 旧版私有目录（`<filesDir>/home/servers/`）的访问入口。
 *
 * ## 为什么既能「经 MTDataFilesProvider」也能「直连」
 *
 * MTDataFilesProvider（https://github.com/L-JINBIN/MTDataFilesProvider）是一个
 * `DocumentsProvider`：它把 `/data/user/0/<pkg>` 这棵子树映射成 `content://` 文档，
 * 供**外部文件管理器**（MT 管理器、系统「文件」）浏览。工程里已随 Manifest 注册：
 *
 * ```xml
 * <provider android:name="bin.mt.file.content.MTDataFilesProvider"
 *           android:authorities="${applicationId}.MTDataFilesProvider"
 *           android:permission="android.permission.MANAGE_DOCUMENTS" ... />
 * ```
 *
 * 关键事实（照实说明，不做包装）：
 *  - 该 provider 声明了 `android:permission="android.permission.MANAGE_DOCUMENTS"`，
 *    这是**签名/特权**权限，普通应用（包括本应用自己）拿不到；
 *  - 但**本应用访问自己的私有目录并不需要它** —— 同一个 Linux uid，
 *    `File` 直连永远有权限，且不依赖任何外部授权，升级多少次都不会失效。
 *
 * 因此这里的策略是：**先尝试 provider（满足「经 MTDataFilesProvider 访问」的诉求），
 * 拿不到授权时自动回落到同 uid 直连**，并把实际使用的通道如实显示在界面上。
 * 这样「私有目录能不能看」这件事在功能上永远成立，provider 只影响
 * 「外部文件管理器能不能看到」，后者由 [ProviderHealth] 单独体检。
 *
 * ## 升级后的持续性
 *
 * provider 自身没有任何跨版本缓存：`attachInfo` 里每次进程启动都用
 * `context.getPackageName()` / `context.getFilesDir()` 现算路径，所以
 * **只要 applicationId 不变、provider 声明不被裁掉，升级不会让它失效**。
 * 真正会失效的是「外部应用持有的 URI 授权」（由 MT 管理器自己保存），
 * 这我们控制不了，只能检测 + 引导重新授权，见 [ProviderHealth]。
 */
object LegacyPrivateStore {

    private const val TAG = "LegacyPrivateStore"

    /** 旧私有目录里的服务器根（只用于迁移扫描；服务器已不再从这里运行） */
    fun root(context: Context): File = ServerStorage.legacyRoot(context)

    fun providerAuthority(context: Context): String = "${context.packageName}.MTDataFilesProvider"

    /** provider 的根文档 id 就是包名（见 MTDataFilesProvider.queryRoots） */
    fun providerRootUri(context: Context): Uri =
        DocumentsContract.buildRootUri(providerAuthority(context), context.packageName)

    /**
     * 旧私有目录在 provider 里的文档 URI。
     *
     * docId 结构是 `<pkg>/data/<dataDir 下的相对路径>`：`dataDir` 即
     * `/data/user/0/<pkg>`，而旧目录真实位置是 `files/home/home/servers`
     * （旧代码 `rootDir` 本身已是 `filesDir/home`，又拼了一段 `home/servers`）。
     * 这里按实际解析到的根目录算相对路径，不写死层级 —— 候选路径有两个。
     */
    fun providerServersUri(context: Context): Uri {
        val rel = runCatching {
            ServerStorage.legacyRoot(context).relativeTo(context.filesDir).invariantSeparatorsPath
        }.getOrNull().orEmpty()
        val docId = "${context.packageName}/data/files" + (if (rel.isBlank()) "" else "/$rel")
        return DocumentsContract.buildDocumentUri(providerAuthority(context), docId)
    }

    /**
     * 探测 provider 是否可作为客户端使用（真的发一次 queryDocument）。
     *
     * 只 `resolveContentProvider` 是不够的：声明存在但与客户端权限不匹配时，
     * 查询会抛 `SecurityException`，那才是实际可用性。
     */
    fun providerQueryable(context: Context): Boolean {
        val resolver: ContentResolver = context.contentResolver
        return try {
            val uri = providerServersUri(context)
            val cursor: Cursor? = resolver.query(uri, null, null, null, null)
            cursor?.use { it.count >= 0 }
            true
        } catch (e: SecurityException) {
            Log.i(TAG, "provider 不可作为客户端访问（缺少 MANAGE_DOCUMENTS），回落直连：${e.message}")
            false
        } catch (e: Exception) {
            Log.i(TAG, "provider 查询失败，回落直连：${e.message}")
            false
        }
    }

    /** 本次访问实际使用的通道，用于界面如实展示 */
    enum class AccessChannel { Provider, DirectFile }

    fun describeChannel(context: Context): AccessChannel =
        if (providerQueryable(context)) AccessChannel.Provider else AccessChannel.DirectFile

    /** 列出旧目录里的服务器目录（扫描所有候选根，按名字去重；不创建、不修改） */
    fun listServerDirs(context: Context): List<File> =
        ServerStorage.legacyServerDirs(context)

    fun isAvailable(context: Context): Boolean = root(context).isDirectory
}

/**
 * MTDataFilesProvider 的运行体检。
 *
 * 需求里点名的风险是「多次应用更新后访问功能失效」。为了让这个问题**可观测**，
 * 每次冷启动做三项检查并把结论落盘：
 *
 *  1. `resolveContentProvider(authority)` 能否解析到（代码/清单是否还在）；
 *  2. provider 是否 `enabled` 且 `exported`（被裁掉 export 后外部就看不到）；
 *  3. provider 的 `applicationInfo.uid` 是否等于本应用 uid（包名/签名被换掉会不一致）。
 *
 * 结果与「最近一次正常时的 versionCode」一起存进独立 SharedPreferences，
 * 升级后若突然不正常，界面能直接显示「上次正常版本 X，当前 Y」，用户知道是升级引起的。
 */
object ProviderHealth {

    private const val TAG = "ProviderHealth"
    private const val PREFS = "mt_provider_health"
    private const val KEY_LAST_OK_AT = "last_ok_at"
    private const val KEY_LAST_OK_VERSION = "last_ok_version"
    private const val KEY_LAST_FAIL_REASON = "last_fail_reason"

    data class Report(
        val authority: String,
        val declared: Boolean,
        val enabled: Boolean,
        val exported: Boolean,
        val sameUid: Boolean,
        val lastOkVersionCode: Int,
        val currentVersionCode: Int,
        val failReason: String?
    ) {
        val healthy: Boolean get() = declared && enabled && exported && sameUid

        /** 升级后才失效的典型形态：以前正常、现在不正常 */
        val brokenByUpgrade: Boolean
            get() = !healthy && lastOkVersionCode > 0 && currentVersionCode > lastOkVersionCode

        fun summary(): String = when {
            healthy -> "provider 已注册、已导出，当前版本正常"
            !declared -> "未解析到 provider（清单声明被裁掉或 authority 变更）"
            !enabled -> "provider 被禁用"
            !exported -> "provider 未导出，外部文件管理器无法访问"
            !sameUid -> "provider 归属的 uid 与本应用不一致（包名/签名已变更）"
            else -> "未知异常"
        }
    }

    fun check(context: Context): Report {
        val authority = LegacyPrivateStore.providerAuthority(context)
        val currentVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        }.getOrDefault(0)

        val info: ProviderInfo? = runCatching {
            context.packageManager.resolveContentProvider(authority, 0)
        }.getOrNull()

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastOkVersion = prefs.getInt(KEY_LAST_OK_VERSION, 0)

        val declared = info != null
        val enabled = info?.enabled ?: false
        val exported = info?.exported ?: false
        val sameUid = info?.applicationInfo?.uid == android.os.Process.myUid()

        val report = Report(
            authority = authority,
            declared = declared,
            enabled = enabled,
            exported = exported,
            sameUid = sameUid,
            lastOkVersionCode = lastOkVersion,
            currentVersionCode = currentVersion,
            failReason = if (declared && enabled && exported && sameUid) null else "见 summary()"
        )
        if (report.healthy) {
            prefs.edit()
                .putLong(KEY_LAST_OK_AT, System.currentTimeMillis())
                .putInt(KEY_LAST_OK_VERSION, currentVersion)
                .remove(KEY_LAST_FAIL_REASON)
                .apply()
        } else {
            Log.w(TAG, "provider 体检异常：${report.summary()}")
            prefs.edit()
                .putString(KEY_LAST_FAIL_REASON, report.summary())
                .putInt("last_fail_version", currentVersion)
                .apply()
        }
        return report
    }

    /** 恢复动作：把 provider 根 URI 交给系统文件管理器打开（外部浏览的重新授权入口） */
    fun recoveryIntent(context: Context): android.content.Intent? {
        val uri = LegacyPrivateStore.providerRootUri(context)
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return intent.takeIf { it.resolveActivity(context.packageManager) != null }
    }

    /** 最近一次失败原因（用于界面展示历史，不必每次重新体检） */
    fun lastFailReason(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_FAIL_REASON, null)
}
