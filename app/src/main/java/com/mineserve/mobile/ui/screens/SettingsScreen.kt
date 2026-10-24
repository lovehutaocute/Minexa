package com.mineserve.mobile.ui.screens

import androidx.compose.ui.res.stringResource
import com.mineserve.mobile.BuildConfig
import com.mineserve.mobile.R

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.McApplication
import com.mineserve.mobile.data.AppLanguage
import com.mineserve.mobile.data.AppUpdateService
import com.mineserve.mobile.data.AptMirror
import com.mineserve.mobile.data.DownloadMirror
import com.mineserve.mobile.data.LocaleManager
import com.mineserve.mobile.ui.HeaderBlock
import com.mineserve.mobile.ui.McCard
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.QqGroupCard
import com.mineserve.mobile.ui.SegPill
import com.mineserve.mobile.ui.SubPage
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.McIcon
import com.mineserve.mobile.ui.theme.McShape
import com.mineserve.mobile.ui.theme.Muted

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: McViewModel, onNavigate: (SubPage) -> Unit = {}) {
    val config by vm.config.collectAsState()
    val context = LocalContext.current
    val lastCheckResult by vm.lastUpdateCheckResult.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        HeaderBlock(eyebrow = stringResource(R.string.eyebrow_settings), title = stringResource(R.string.s186))

        // 语言（全局偏好，放最前，一眼能找到）
        LanguageCard()

        // 关于：紧跟语言卡片，版本号 / 检查更新 / 仓库入口集中在一处
        McCard(title = stringResource(R.string.s1023)) {
            SettingEntry(
                title = stringResource(R.string.update_current_version),
                subtitle = "v${BuildConfig.VERSION_NAME}",
                onClick = {} // 版本号行不可点击
            )
            Spacer(Modifier.height(4.dp))
            SettingEntry(
                title = stringResource(R.string.update_check),
                subtitle = lastCheckResult ?: stringResource(R.string.s1025),
                onClick = { vm.checkForUpdate(manual = true) }
            )
            Spacer(Modifier.height(4.dp))
            SettingEntry(
                title = stringResource(R.string.settings_github_repo),
                subtitle = stringResource(R.string.settings_github_repo_desc),
                onClick = { openExternal(context, AppUpdateService.PROJECT_URL) }
            )
        }

        // 下载源设置（Termux bootstrap rootfs 下载源）
        // 右上角闪电图标 = 「依次尝试下载源」：按顺序逐个测试，找到第一个可用源即选中它。
        // 原先是一整条按钮，占掉一行高度；改成图标后信息密度更高，行为完全不变。
        val testing by vm.mirrorTestRunning.collectAsState()
        val testResult = vm.mirrorTestResult.collectAsState().value
        McCard(
            title = stringResource(R.string.s1007),
            trailing = {
                IconButton(
                    onClick = { vm.testDownloadSources() },
                    enabled = !testing,
                    modifier = Modifier.size(McIcon.Touch)
                ) {
                    Icon(
                        Icons.Outlined.Bolt,
                        contentDescription = stringResource(R.string.st_mirror_test),
                        tint = if (testing) Muted else Indigo,
                        modifier = Modifier.size(McIcon.Action)
                    )
                }
            }
        ) {
            Text(
                stringResource(R.string.st_mirror_hint),
                color = Muted,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.s1009), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DownloadMirror.values().forEach { mirror ->
                    SegPill(
                        text = downloadMirrorLabel(mirror),
                        selected = config.downloadMirror == mirror,
                        onClick = { vm.setDownloadMirror(mirror) }
                    )
                }
            }
            if (testing || testResult != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    if (testing) stringResource(R.string.st_mirror_test_running) else testResult.orEmpty(),
                    color = Muted,
                    fontSize = 11.sp
                )
            }
        }

        // APT 镜像设置（JDK/wget/frp 等依赖包下载源）
        McCard(title = stringResource(R.string.s1010)) {
            Text(
                stringResource(R.string.s1011),
                color = Muted,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.s1012), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AptMirror.values().filter { it != AptMirror.Official }.forEach { mirror ->
                    SegPill(
                        text = aptMirrorLabel(mirror),
                        selected = config.aptMirror == mirror,
                        onClick = { vm.setAptMirror(mirror) }
                    )
                }
            }
        }

        // 多线程下载设置（内置下载加速模块）
        McCard(title = stringResource(R.string.s1064)) {
            val enabledState = remember { mutableStateOf(vm.isMultiThreadDownloadEnabled()) }
            val threadState = remember { mutableStateOf(vm.downloadThreadCount()) }
            SettingToggle(
                title = stringResource(R.string.s1065),
                subtitle = stringResource(R.string.s1066),
                checked = enabledState.value,
                onChange = {
                    enabledState.value = it
                    vm.setMultiThreadDownloadEnabled(it)
                }
            )
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.s1067), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(2, 4, 8).forEach { count ->
                    SegPill(
                        text = stringResource(R.string.st_threads_count, count),
                        selected = threadState.value == count && enabledState.value,
                        onClick = {
                            threadState.value = count
                            vm.setDownloadThreadCount(count)
                        }
                    )
                }
            }
        }

        // 保活与恢复
        McCard(title = stringResource(R.string.s1013)) {
            SettingToggle(
                title = stringResource(R.string.s396),
                subtitle = stringResource(R.string.s397),
                checked = config.autoRestartOnCrash,
                onChange = { vm.setAutoRestart(it) }
            )
            Spacer(Modifier.height(8.dp))
            SettingToggle(
                title = stringResource(R.string.s1014),
                subtitle = stringResource(R.string.s1015),
                checked = config.keepWifiLock,
                onChange = { vm.setKeepWifiLock(it) }
            )
            Spacer(Modifier.height(8.dp))
            SettingToggle(
                title = stringResource(R.string.s1016),
                subtitle = stringResource(R.string.s1017),
                checked = config.keepCpuWakelock,
                onChange = { vm.setKeepCpuWakelock(it) }
            )
        }

        // 服务器事件通知
        McCard(title = stringResource(R.string.set_notify_title)) {
            Text(
                stringResource(R.string.set_notify_hint),
                color = Muted,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))
            val notifyLevel by vm.eventNotifyLevel.collectAsState()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SegPill(
                    text = stringResource(R.string.set_notify_off),
                    selected = notifyLevel == 0,
                    onClick = { vm.setEventNotifyLevel(0) }
                )
                SegPill(
                    text = stringResource(R.string.set_notify_important),
                    selected = notifyLevel == 1,
                    onClick = { vm.setEventNotifyLevel(1) }
                )
                SegPill(
                    text = stringResource(R.string.set_notify_all),
                    selected = notifyLevel == 2,
                    onClick = { vm.setEventNotifyLevel(2) }
                )
            }
        }

        // 快捷入口（子页面跳转）
        McCard(title = stringResource(R.string.s1020)) {
            SettingEntry(
                title = stringResource(R.string.s1021),
                subtitle = stringResource(R.string.s1022),
                onClick = { onNavigate(SubPage.DownloadHelp) }
            )
        }

        // 意见反馈
        McCard(title = stringResource(R.string.s1026)) {
            Text(
                stringResource(R.string.s1027),
                color = Muted,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))
            val feedbackSubject = stringResource(R.string.st_feedback_subject)
            Button(
                onClick = {
                    try {
                        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:167245484@qq.com"))
                        intent.putExtra(Intent.EXTRA_SUBJECT, feedbackSubject)
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        // 设备无邮件客户端时静默
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                shape = McShape.ControlCompact,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.s1029),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // QQ 交流群入口
        QqGroupCard()
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SettingToggle(
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
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingEntry(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Indigo)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
        Text("→", color = Indigo, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * 语言选择卡片。
 *
 * 列表直接遍历 [AppLanguage.entries]，新增语言只需要加枚举项 + 一个 values-xx 资源目录，
 * 这里不用改。切换后保存并 `recreate()` 当前 Activity —— 只有重建才会重新走
 * `attachBaseContext`，让整棵 Compose 树与通知/桌面组件一起换成新语言。
 */
@Composable
private fun downloadMirrorLabel(mirror: DownloadMirror): String = when (mirror) {
    DownloadMirror.Auto -> stringResource(R.string.mirror_auto)
    DownloadMirror.GitHub -> stringResource(R.string.mirror_github)
    // 其余都是镜像域名本身，各语言一致，直接用枚举里的名字
    else -> mirror.displayName
}

@Composable
private fun aptMirrorLabel(mirror: AptMirror): String = when (mirror) {
    AptMirror.Tuna -> stringResource(R.string.mirror_tuna)
    AptMirror.Aliyun -> stringResource(R.string.mirror_aliyun)
    AptMirror.Ustc -> stringResource(R.string.mirror_ustc)
    AptMirror.Nju -> stringResource(R.string.mirror_nju)
    AptMirror.Official -> stringResource(R.string.mirror_official)
}

@Composable
private fun LanguageCard() {
    val context = LocalContext.current
    val current = remember { LocaleManager.current(context) }
    var expanded by remember { mutableStateOf(false) }

    McCard(title = stringResource(R.string.set_language_title)) {
        Text(
            stringResource(R.string.set_language_hint),
            color = Muted,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    languageLabel(current),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Indigo
                )
            }
            Text(stringResource(R.string.set_language_change), color = Indigo, fontSize = 12.sp)
        }
    }

    if (expanded) {
        AlertDialog(
            onDismissRequest = { expanded = false },
            shape = McShape.Dialog,
            title = { Text(stringResource(R.string.set_language_title), fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    AppLanguage.selectable().forEach { language ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    expanded = false
                                    if (LocaleManager.select(context, language)) {
                                        // 先补刷不随 Activity 重建的界面，再重建整棵树
                                        McApplication.get().refreshLocalizedSurfaces()
                                        (context as? android.app.Activity)?.recreate()
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = language == current,
                                onClick = null
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                languageLabel(language),
                                fontSize = 14.sp,
                                color = if (language == current) Indigo else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { expanded = false }) {
                    Text(stringResource(R.string.s620), color = Muted)
                }
            }
        )
    }
}

/** 语言的展示名：跟随系统用资源文案，其余用该语言自身的写法 */
@Composable
private fun languageLabel(language: AppLanguage): String =
    if (language.isSystem) stringResource(R.string.set_language_system) else language.endonym

/**
 * 用系统浏览器打开外部链接。
 *
 * 无浏览器 / 无匹配 Activity 时静默返回 —— 与下面「意见反馈」的处理一致，
 * 不为「点一下没反应」这种场景弹崩溃对话框。
 */
private fun openExternal(context: android.content.Context, url: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        // 设备无可用的浏览器，静默
    }
}
