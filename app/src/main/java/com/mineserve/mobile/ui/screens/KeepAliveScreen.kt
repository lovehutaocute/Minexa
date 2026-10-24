package com.mineserve.mobile.ui.screens

import androidx.compose.ui.res.stringResource
import com.mineserve.mobile.R
import androidx.compose.ui.platform.LocalContext
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.service.McForegroundService
import com.mineserve.mobile.ui.BackBar
import com.mineserve.mobile.ui.HeaderBlock
import com.mineserve.mobile.ui.McCard
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.Mint
import com.mineserve.mobile.ui.theme.Muted
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 后台保活设置页：开机自启 / 周期保活 / 崩溃自动重启 等开关，每项带功能说明。
 */
@Composable
fun KeepAliveScreen(vm: McViewModel, onBack: () -> Unit) {
    val config by vm.config.collectAsState()
    val context = LocalContext.current
    var bootAuto by remember { mutableStateOf(vm.isBootAutoStart()) }
    var keepAlive by remember { mutableStateOf(vm.isKeepAliveEnabled()) }
    var serviceRunning by remember { mutableStateOf(McForegroundService.isRunning) }
    // 服务按钮防重复状态
    var serviceBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 轮询服务运行状态（5s 一次，仅状态变化才写 state，避免无谓重组）
    LaunchedEffect(Unit) {
        while (true) {
            val running = McForegroundService.isRunning
            if (serviceRunning != running) serviceRunning = running
            delay(5000)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 统一返回栏
        BackBar(title = stringResource(R.string.s541), onBack = onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            HeaderBlock(eyebrow = stringResource(R.string.eyebrow_keepalive), title = stringResource(R.string.s542), statusBarPadding = false)

            // 服务状态
            McCard(title = stringResource(R.string.s543)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(if (serviceRunning) Mint else Coral)
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        if (serviceRunning) stringResource(R.string.s544) else stringResource(R.string.s545),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (serviceRunning) Mint else Coral
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            // 防重复点击：禁用 1.5s，避免连点触发多次前台服务启动
                            serviceBusy = true
                            scope.launch { delay(1500); serviceBusy = false }
                            vm.startKeepAliveService()
                        },
                        enabled = !serviceBusy,
                        colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.s546), color = Color.White, fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = {
                            serviceBusy = true
                            scope.launch { delay(1500); serviceBusy = false }
                            vm.stopKeepAliveService()
                        },
                        enabled = !serviceBusy,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.s547), color = Coral, fontSize = 12.sp) }
                }
            }

            // 保活开关
            McCard(title = stringResource(R.string.s548)) {
                KeepAliveToggle(
                    title = stringResource(R.string.s549),
                    subtitle = stringResource(R.string.s550),
                    checked = bootAuto,
                    onChange = { vm.setBootAutoStart(it); bootAuto = it }
                )
                Spacer(Modifier.height(8.dp))
                KeepAliveToggle(
                    title = stringResource(R.string.s551),
                    subtitle = stringResource(R.string.s552),
                    checked = keepAlive,
                    onChange = { vm.setKeepAliveEnabled(it); keepAlive = it }
                )
                Spacer(Modifier.height(8.dp))
                KeepAliveToggle(
                    title = stringResource(R.string.s396),
                    subtitle = stringResource(R.string.s553),
                    checked = config.autoRestartOnCrash,
                    onChange = { vm.setAutoRestart(it) }
                )
                Spacer(Modifier.height(8.dp))
                KeepAliveToggle(
                    title = stringResource(R.string.kax_screen_on_title),
                    subtitle = stringResource(R.string.kax_screen_on_subtitle),
                    checked = config.keepScreenOnWhileRunning,
                    onChange = vm::setKeepScreenOnWhileRunning
                )
                Spacer(Modifier.height(8.dp))
                KeepAliveToggle(
                    title = stringResource(R.string.kax_overlay_title),
                    subtitle = stringResource(R.string.kax_overlay_subtitle),
                    checked = config.keepStatusOverlay,
                    onChange = { enabled ->
                        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }
                        }
                        vm.setKeepStatusOverlay(enabled)
                        if (enabled) vm.startKeepAliveService()
                    }
                )
            }

            // 厂商系统优化：电池优化豁免 + 自启动引导
            McCard(title = stringResource(R.string.ui_battery_title)) {
                Text(stringResource(R.string.ui_battery_hint), color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        Toast.makeText(context, context.getString(R.string.ui_battery_btn), Toast.LENGTH_SHORT).show()
                        requestIgnoreBatteryOptimizations(context)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.ui_battery_btn), color = Color.White, fontSize = 12.sp) }
            }
            McCard(title = stringResource(R.string.ui_autostart_title)) {
                Text(stringResource(R.string.ui_autostart_hint), color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        Toast.makeText(context, context.getString(R.string.ui_autostart_btn), Toast.LENGTH_SHORT).show()
                        openManufacturerAutostart(context)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.ui_autostart_btn), color = Color.White, fontSize = 12.sp) }
            }

            // 说明
            McCard(title = stringResource(R.string.s554)) {
                Text(
                    stringResource(R.string.ka_bg_hint) +
                        stringResource(R.string.s556),
                    color = Muted,
                    fontSize = 11.sp,
                    lineHeight = 17.sp
                )
            }
        }
    }
}

@Composable
private fun KeepAliveToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 引导关闭电池优化（Android 12+ 优先跳专项页，失败兜底应用详情） */
private fun requestIgnoreBatteryOptimizations(context: Context) {
    try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(context.packageName)) {
            Toast.makeText(context, context.getString(R.string.ui_battery_ok), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:" + context.packageName)
        )
        context.startActivity(intent)
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                )
            } catch (_: Exception) {}
        }
    }
}

/** 按厂商跳转自启动设置页（小米/华为/荣耀/OPPO/vivo，其余走应用详情） */
private fun openManufacturerAutostart(context: Context) {
    val m = (Build.MANUFACTURER ?: "").lowercase()
    val intent = when {
        m.contains("xiaomi") -> Intent().setComponent(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        )
        m.contains("huawei") || m.contains("honor") -> Intent().setComponent(
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
        )
        m.contains("oppo") || m.contains("realme") -> Intent().setComponent(
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
        )
        m.contains("vivo") -> Intent().setComponent(
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
        )
        else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
    }
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
            )
        } catch (_: Exception) {}
    }
}
