package com.mineserve.mobile

import android.util.Log
import android.view.WindowManager
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.mineserve.mobile.ui.McApp
import com.mineserve.mobile.ui.theme.MineServeMobileTheme
import com.mineserve.mobile.service.McForegroundService

class MainActivity : ComponentActivity() {

    private companion object {
        /** 见 onCreate 里的授权入口。 */
        const val EXTRA_REQUEST_VPN = "request_vpn"


        const val TAG = "MainActivity"
    }

    // Android 13+ 通知权限运行时申请
    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 用户拒绝仅影响通知可见性，前台服务仍可保活 */ }

    /**
     * 语言覆盖：Activity 的 Context 必须自己套一层，否则 `stringResource` 拿到的是
     * 系统语言而不是应用内选择的语言。切换语言时调用 `recreate()`，
     * 会重新走这里，从而让整棵树按新语言重建。
     */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.mineserve.mobile.data.LocaleManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 提高 UI 线程调度优先级：后台大游戏（如原神）会把 CPU 吃满，
        // 默认优先级下主线程时间片被挤占导致页面卡顿；与前台游戏体验保持一致的提权。
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
        } catch (_: Exception) {}
        // EasyTier 的 VPN 授权入口：带 --ez request_vpn true 启动时弹一次系统授权框。
        // 这条路径不只给调试用 —— 将来从桌面组件/通知跳回来补授权也走它，因为
        // establish() 拿不到授权时只会返回 null，不给任何可诊断的错误。
        if (intent?.getBooleanExtra(EXTRA_REQUEST_VPN, false) == true) {
            com.mineserve.mobile.server.tunnel.easytier.EasyTierEngine
                .prepareIntent(this)
                ?.let { startActivity(it) }
        }
        lifecycleScope.launch {
            val repo = McApplication.get(this@MainActivity).repository
            combine(repo.configFlow, repo.serverState) { config, server ->
                config.keepScreenOnWhileRunning && server.isRunning
            }.collect { keepOn ->
                if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = androidx.activity.SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 三键导航默认强制半透明对比度遮罩，关闭后才是全透明
            window.isNavigationBarContrastEnforced = false
        }
        ensureNotificationPermission()
        // 高刷新率：见 applyHighestRefreshRate() 的说明
        applyHighestRefreshRate()
        // 更新通知点击进入：通知带 open_update extra，通知 MainActivity 打开更新对话框
        if (intent?.getBooleanExtra("open_update", false) == true) {
            McApplication.get().requestOpenUpdate()
        }
        setContent {
            MineServeMobileTheme {
                // 显式给出书写方向：虽然 Compose 会从 Configuration 推断，但这里再钉一次，
                // 保证「切到波斯语 → 整棵树（含弹窗、抽屉这类子组合）立即 RTL」不依赖平台推断。
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalLayoutDirection provides
                        if (com.mineserve.mobile.data.LocaleManager.isRtl(this@MainActivity)) {
                            androidx.compose.ui.unit.LayoutDirection.Rtl
                        } else {
                            androidx.compose.ui.unit.LayoutDirection.Ltr
                        }
                ) {
                    McApp()
                }
            }
        }
    }

    /**
     * 让窗口使用设备的最高刷新率（90/120/144Hz）。
     *
     * 背景：应用没有任何地方主动锁 60 —— 全仓库没有 preferredRefreshRate /
     * preferredDisplayModeId / setFrameRate / Choreographer 固定延时。
     * 真正的限制来自 **targetSdk = 28**：Android 11（API 30）起，平台对
     * targetSdk < 30 的应用在可变刷新率屏幕上不启用高刷（兼容策略），
     * 系统会把这类应用的帧率钉在 60Hz，应用侧改不了这一层。
     *
     * 而 targetSdk=28 是本项目刻意选的（依赖 Android 10 起默认变 false 的
     * extractNativeLibs，Termux 必须能把 jniLibs 的 .so 当真实文件复制出去，
     * 见 AndroidManifest 注释）。因此这里不升 targetSdk，而是在**运行时**
     * 主动申请最高刷新率 —— 部分设备/系统版本会接受该申请，
     * 不接受时保持系统默认，不产生副作用。
     *
     * 做法：取当前 display 支持的最高刷新率，逐个尝试
     * `preferredDisplayModeId`（Android 6+）与 `preferredRefreshRate`（Android 7+），
     * 失败只记日志，不影响启动。
     */
    private fun applyHighestRefreshRate() {
        try {
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay
            }
            val modes = display?.supportedModes.orEmpty()
            if (modes.isEmpty()) return
            val best = modes.maxByOrNull { it.refreshRate } ?: return
            val attrs = window.attributes
            // 已经是最高刷新率就不动，避免多余的模式切换闪烁
            if (best.refreshRate <= display?.refreshRate ?: 0f) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                attrs.preferredDisplayModeId = best.modeId
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                attrs.preferredRefreshRate = best.refreshRate
            }
            window.attributes = attrs
            Log.i(TAG, "applyHighestRefreshRate: 申请 ${best.refreshRate}Hz (modeId=${best.modeId})")
        } catch (e: Exception) {
            Log.w(TAG, "applyHighestRefreshRate failed: ${e.message}")
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val config = McApplication.get(this@MainActivity).repository.configFlow.first()
            if (config.keepStatusOverlay) {
                // 授权页返回时服务可能还没完成启动，确保服务存在并让它重新挂载悬浮窗。
                if (McForegroundService.isRunning) {
                    startService(Intent(this@MainActivity, McForegroundService::class.java).apply {
                        action = McForegroundService.ACTION_REFRESH_KEEP_ALIVE
                    })
                } else {
                    McApplication.get(this@MainActivity).startForegroundService(
                        Intent(this@MainActivity, McForegroundService::class.java).apply {
                            action = McForegroundService.ACTION_START
                        }
                    )
                }
            } else if (McForegroundService.isRunning) {
                startService(Intent(this@MainActivity, McForegroundService::class.java).apply {
                    action = McForegroundService.ACTION_REFRESH_KEEP_ALIVE
                })
            }
        }
    }
}
