package com.mineserve.mobile.ui.vm

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.mineserve.mobile.BuildConfig
import com.mineserve.mobile.MainActivity
import com.mineserve.mobile.R
import com.mineserve.mobile.data.AppRelease
import com.mineserve.mobile.data.AppUpdateService
import com.mineserve.mobile.ui.McViewModel.UpdateUiState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class AppUpdateDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    private val reportMessage: (String) -> Unit
) {
    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    private val _updateDialogVisible = MutableStateFlow(false)
    val updateDialogVisible: StateFlow<Boolean> = _updateDialogVisible.asStateFlow()

    private val _lastUpdateCheckResult = MutableStateFlow<String?>(null)
    val lastUpdateCheckResult: StateFlow<String?> = _lastUpdateCheckResult.asStateFlow()
    private var skippedReleaseTag: String? = null

    fun dismissUpdateDialog() { _updateDialogVisible.value = false }

    fun checkForUpdate(manual: Boolean = false) {
        if (_updateState.value is UpdateUiState.Checking) return
        _updateState.value = UpdateUiState.Checking
        if (manual) _updateDialogVisible.value = true // 手动检查：立即显示检查中对话框
        scope.launch {
            try {
                val release = AppUpdateService.latest(BuildConfig.VERSION_NAME)
                if (release == null) {
                    _updateState.value = UpdateUiState.Idle
                    _lastUpdateCheckResult.value =
                        "${app.getString(R.string.update_already_latest)} · ${nowTime()}"
                    if (manual) {
                        _updateDialogVisible.value = false
                        reportMessage(app.getString(R.string.update_already_latest))
                    }
                } else {
                    _updateState.value = UpdateUiState.Available(release)
                    _lastUpdateCheckResult.value =
                        "${app.getString(R.string.update_available, release.tag)} · ${nowTime()}"
                    if (manual || skippedReleaseTag != release.tag) {
                        _updateDialogVisible.value = true
                        if (!manual) showUpdateNotification(app, release)
                    }
                }
            } catch (e: Exception) {
                    _updateState.value = UpdateUiState.Failed(e.message ?: str(R.string.msg_update_check_failed))
                    _lastUpdateCheckResult.value =
                        "${app.getString(R.string.update_check_failed)} · ${nowTime()}"
                    if (manual) {
                        _updateDialogVisible.value = true
                    }
            }
        }
    }

    private fun nowTime(): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())

    fun showUpdateDialog() {
        if (_updateState.value is UpdateUiState.Available) _updateDialogVisible.value = true
    }

    fun skipCurrentUpdate() {
        val state = _updateState.value as? UpdateUiState.Available ?: return
        skippedReleaseTag = state.release.tag
        dismissUpdateDialog()
    }

    fun openGithubUpdate() {
        val url = (_updateState.value as? UpdateUiState.Available)?.release?.releaseUrl
            ?: com.mineserve.mobile.data.AppUpdateService.PROJECT_URL
        try {
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(url)
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
        } catch (e: Exception) {
            reportMessage(str(R.string.msg_open_browser_fail, e.message))
        }
    }

    fun downloadUpdate() {
        val state = _updateState.value
        val release = when (state) {
            is UpdateUiState.Available -> state.release
            is UpdateUiState.Failed -> state.release
            else -> null
        } ?: return
        _updateState.value = UpdateUiState.Downloading(0f, release)
        scope.launch {
            try {
                val target = File(app.cacheDir, "update/MineServeMobile-latest.apk")
                AppUpdateService.download(release.apkUrls, target) { p ->
                    _updateState.value = UpdateUiState.Downloading(p, release)
                }
                _updateState.value = UpdateUiState.Downloaded(release, target.absolutePath)
            } catch (e: Exception) {
                _updateState.value = UpdateUiState.Failed(e.message ?: app.getString(R.string.update_download_failed), release)
            }
        }
    }

    fun installApk(context: Context, file: File) {
        try {
            if (!AppUpdateService.isApk(file)) {
                throw IllegalStateException(str(R.string.err_apk_corrupt))
            }
            val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                ?: throw IllegalStateException(str(R.string.err_apk_read_pkg))
            if (archive.packageName != context.packageName) {
                throw IllegalStateException(str(R.string.err_apk_pkg_mismatch))
            }
            // 签名要比对：下载源全是第三方 GitHub 反代，包名对了还不够。
            // 同签名才是"我们自己发的那个更新"，不同签名一律拒绝。
            if (!AppUpdateService.verifySignature(context, file)) {
                throw IllegalStateException(str(R.string.err_apk_sig_mismatch))
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                !context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                reportMessage(str(R.string.msg_allow_unknown_install))
                return
            }
            val uri = FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                clipData = ClipData.newRawUri("update-apk", uri)
            }
            val installers = context.packageManager.queryIntentActivities(
                intent,
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
            )
            if (installers.isEmpty()) {
                throw IllegalStateException(str(R.string.err_no_installer))
            }
            installers.forEach { handler ->
                context.grantUriPermission(
                    handler.activityInfo.packageName,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            _updateState.value = UpdateUiState.Failed(e.message ?: "install error")
        }
    }

    fun installDownloadedUpdate(path: String) = installApk(app, File(path))

    /** 自动检查发现新版时发系统通知，点击进入更新对话框 */
    private fun showUpdateNotification(app: Context, info: AppRelease) {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val contentIntent = PendingIntent.getActivity(
            app, 1001,
            Intent(app, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("open_update", true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(app, app.getString(R.string.notif_channel_id))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(app.getString(R.string.update_notif_title, info.tag))
            .setContentText(app.getString(R.string.update_notif_text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        try { nm.notify(1001, notification) } catch (_: Exception) {}
    }

    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)
}
