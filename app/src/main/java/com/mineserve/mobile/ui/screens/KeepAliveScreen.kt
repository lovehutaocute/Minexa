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
import com.mineserve.mobile.data.skin.SkinTileSlots
import com.mineserve.mobile.ui.components.skinTileOverlay
import com.mineserve.mobile.ui.components.skinButtonColors

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
    // 系统限制诊断：跟服务状态一起每 5 秒刷新，用户在系统设置里改完返回立刻能看到变化。
    var batteryExempt by remember { mutableStateOf(isBatteryExempt(context)) }
    var exactAlarmOk by remember { mutableStateOf(isExactAlarmAllowed(context)) }
    var standbyBucket by remember { mutableStateOf(appStandbyBucket(context)) }
    // 服务按钮防重复状态
    var serviceBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 轮询服务运行状态（5s 一次，仅状态变化才写 state，避免无谓重组）
    LaunchedEffect(Unit) {
        while (true) {
            val running = McForegroundService.isRunning
            if (serviceRunning != running) serviceRunning = running
            val exempt = isBatteryExempt(context)
            if (batteryExempt != exempt) batteryExempt = exempt
            val exact = isExactAlarmAllowed(context)
            if (exactAlarmOk != exact) exactAlarmOk = exact
            val bucket = appStandbyBucket(context)
            if (standbyBucket != bucket) standbyBucket = bucket
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
                        colors = skinButtonColors(SkinTileSlots.PRIMARY_BUTTON, Indigo),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f).skinTileOverlay(SkinTileSlots.PRIMARY_BUTTON, RoundedCornerShape(16.dp))) { Text(stringResource(R.string.s546), color = Color.White, fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = {
                            serviceBusy = true
                            scope.launch { delay(1500); serviceBusy = false }
                            vm.stopKeepAliveService()
                        },
                        enabled = !serviceBusy,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.s547), color = Coral, fontSize = 12.sp) }
                }
            }

            // 系统限制诊断：这三项任一项不达标，前台服务都会被系统/厂商策略清理
            // （用户反馈的「开保活 10 分钟就没了」绝大多数就是这里没配好）。
            McCard(title = stringResource(R.string.kax_diag_title)) {
                DiagRow(
                    label = stringResource(R.string.kax_diag_battery),
                    ok = batteryExempt,
                    value = if (batteryExempt) stringResource(R.string.kax_diag_ok)
                    else stringResource(R.string.kax_diag_warn)
                )
                Spacer(Modifier.height(6.dp))
                DiagRow(
                    label = stringResource(R.string.kax_diag_bucket),
                    ok = standbyBucketOk(standbyBucket),
                    value = bucketLabel(standbyBucket)
                )
                Spacer(Modifier.height(6.dp))
                DiagRow(
                    label = stringResource(R.string.kax_diag_alarm),
                    ok = exactAlarmOk,
                    value = if (exactAlarmOk) stringResource(R.string.kax_diag_ok)
                    else stringResource(R.string.kax_diag_warn)
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.kax_diag_hint), color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
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
                // 以下两项原先在「设置 → 运行与保活」分组里，功能与文案原样搬过来，
                // 与其它保活开关同卡展示（同一组配置不该散落在两个页面）。
                Spacer(Modifier.height(8.dp))
                KeepAliveToggle(
                    title = stringResource(R.string.s1014),
                    subtitle = stringResource(R.string.s1015),
                    checked = config.keepWifiLock,
                    onChange = { vm.setKeepWifiLock(it) }
                )
                Spacer(Modifier.height(8.dp))
                KeepAliveToggle(
                    title = stringResource(R.string.s1016),
                    subtitle = stringResource(R.string.s1017),
                    checked = config.keepCpuWakelock,
                    onChange = { vm.setKeepCpuWakelock(it) }
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
                    colors = skinButtonColors(SkinTileSlots.PRIMARY_BUTTON, Indigo),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().skinTileOverlay(SkinTileSlots.PRIMARY_BUTTON, RoundedCornerShape(16.dp))) { Text(stringResource(R.string.ui_battery_btn), color = Color.White, fontSize = 12.sp) }
            }
            McCard(title = stringResource(R.string.ui_autostart_title)) {
                Text(stringResource(R.string.ui_autostart_hint), color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        Toast.makeText(context, context.getString(R.string.ui_autostart_btn), Toast.LENGTH_SHORT).show()
                        openManufacturerAutostart(context)
                    },
                    colors = skinButtonColors(SkinTileSlots.PRIMARY_BUTTON, Indigo),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().skinTileOverlay(SkinTileSlots.PRIMARY_BUTTON, RoundedCornerShape(16.dp))) { Text(stringResource(R.string.ui_autostart_btn), color = Color.White, fontSize = 12.sp) }
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

/** 诊断行：左侧名称，右侧状态；`ok=null` 表示系统不支持、不做评判。 */
@Composable
private fun DiagRow(label: String, ok: Boolean?, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(
            value,
            color = when (ok) {
                true -> Mint
                false -> Coral
                null -> Muted
            },
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** `UsageStatsManager.STANDBY_BUCKET_EXEMPTED` 在部分 SDK 上不可见，直接取字面值 5。 */
private const val STANDBY_BUCKET_EXEMPTED = 5

@Composable
private fun bucketLabel(bucket: Int): String = when (bucket) {
    android.app.usage.UsageStatsManager.STANDBY_BUCKET_ACTIVE,
    STANDBY_BUCKET_EXEMPTED ->
        stringResource(R.string.kax_bucket_active)

    android.app.usage.UsageStatsManager.STANDBY_BUCKET_WORKING_SET,
    android.app.usage.UsageStatsManager.STANDBY_BUCKET_FREQUENT ->
        stringResource(R.string.kax_bucket_normal)

    android.app.usage.UsageStatsManager.STANDBY_BUCKET_RARE ->
        stringResource(R.string.kax_bucket_rare)

    android.app.usage.UsageStatsManager.STANDBY_BUCKET_RESTRICTED ->
        stringResource(R.string.kax_bucket_restricted)

    else -> stringResource(R.string.kax_diag_unknown)
}

/** 「极少使用 / 受限」两个分组下系统会主动冻结应用，是保活失效的典型状态。 */private fun standbyBucketOk(bucket: Int): Boolean? = when (bucket) {
    -1 -> null
    android.app.usage.UsageStatsManager.STANDBY_BUCKET_RARE,
    android.app.usage.UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> false

    else -> true
}

private fun isBatteryExempt(context: Context): Boolean = runCatching {
    (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .isIgnoringBatteryOptimizations(context.packageName)
}.getOrDefault(false)

private fun isExactAlarmAllowed(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    return runCatching {
        (context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager).canScheduleExactAlarms()
    }.getOrDefault(false)
}

/** 本应用的待机分组（API 28+ 免授权可读），-1 表示系统不支持。 */
private fun appStandbyBucket(context: Context): Int {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return -1
    return runCatching {
        (context.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager)
            .appStandbyBucket
    }.getOrDefault(-1)
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

/**
 * 按厂商跳转自启动设置页。
 *
 * 三个必须做对的点（都踩过）：
 * 1. **每个厂商给多个候选**。同一个品牌不同 ROM 版本 / 不同机型的管理页
 *    Activity 名会变（OPPO 的 coloros→oplus、荣耀 MagicOS 从华为包名独立出去），
 *    只写一个等于把一半用户丢进兜底。
 * 2. **先 resolveActivity 再 startActivity**。Android 11+ 有包可见性限制，
 *    没在 `<queries>` 里声明的包名 `resolveActivity` 恒为 null；直接
 *    startActivity 会抛 ActivityNotFoundException 并被 catch 吞掉，
 *    用户看到的是「点了按钮跳到应用详情页」，完全不知道发生了什么。
 * 3. **失败要落到应用详情页**，那是所有 ROM 都有的页面，用户至少能自己找到
 *    「自启动 / 后台运行 / 电池」开关。
 */
private fun openManufacturerAutostart(context: Context) {
    val m = (Build.MANUFACTURER ?: "").lowercase()
    val brand = (Build.BRAND ?: "").lowercase()
    fun component(pkg: String, cls: String) = Intent().setComponent(ComponentName(pkg, cls))

    val candidates: List<Intent> = when {
        m.contains("xiaomi") || m.contains("redmi") || brand.contains("poco") -> listOf(
            component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            Intent("miui.intent.action.OP_AUTO_START"),
            Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", context.packageName)
        )
        m.contains("huawei") || m.contains("honor") || brand.contains("hihonor") -> listOf(
            component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            component("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            component("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            component("com.hihonor.systemmanager", "com.hihonor.systemmanager.optimize.process.ProtectActivity")
        )
        m.contains("oppo") || m.contains("realme") || m.contains("oneplus") -> listOf(
            component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            component("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
            component("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity")
        )
        m.contains("vivo") || m.contains("iqoo") -> listOf(
            component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            component("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity")
        )
        m.contains("meizu") || m.contains("mblu") -> listOf(
            component("com.meizu.safe", "com.meizu.safe.permission.SecurePermissionActivity"),
            component("com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC")
        )
        m.contains("samsung") -> listOf(
            component("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            component("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity")
        )
        m.contains("asus") -> listOf(
            component("com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity")
        )
        m.contains("nokia") -> listOf(
            component("com.evenwell.powersaving.g3", "com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity")
        )
        else -> emptyList()
    }

    // resolveActivity 必须传 MATCH_DEFAULT_ONLY 以外的 flag 才能看到非默认组件，
    // 这些系统管理页通常没有 CATEGORY_DEFAULT。
    val flags = android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS
    val resolved = candidates.firstOrNull { intent ->
        runCatching { intent.resolveActivity(context.packageManager) != null }
            .getOrDefault(false) ||
            // 显式 component 的 intent 用 resolveActivity 有时拿不到（厂商把 Activity
            // 设为不导出），再退一步用 packageManager 直接查组件是否存在。
            runCatching {
                intent.component?.let {
                    context.packageManager.getActivityInfo(it, flags)
                    true
                } ?: false
            }.getOrDefault(false)
    }

    val target = resolved
        ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
    try {
        context.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
