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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.data.AptMirror
import com.mineserve.mobile.data.DownloadMirror
import com.mineserve.mobile.ui.HeaderBlock
import com.mineserve.mobile.ui.McCard
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.QqGroupCard
import com.mineserve.mobile.ui.SegPill
import com.mineserve.mobile.ui.DebouncedTextField
import com.mineserve.mobile.ui.SubPage
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.Indigo
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

        // JVM 内存上限
        McCard(title = stringResource(R.string.s1003)) {
            Text(
                stringResource(R.string.s1004),
                color = Muted,
                fontSize = 11.sp
            )
            Text(
                stringResource(R.string.s1005),
                color = Coral,
                fontSize = 10.sp
            )
            Spacer(Modifier.height(8.dp))
            DebouncedTextField(
                value = config.maxHeapMb.toString(),
                onValueChange = { v ->
                    // 内存上限范围 256–65536 MB，超界自动收敛并提示（不静默改值）
                    v.toIntOrNull()?.let { raw ->
                        val mb = raw.coerceIn(256, 65536)
                        if (mb != raw) {
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.set_max_heap_range_hint),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                        vm.setMaxHeap(mb)
                    }
                },
                sanitize = { it.filter(Char::isDigit) },
                singleLine = true,
                label = { Text(stringResource(R.string.s1006)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 下载源设置（Termux bootstrap rootfs 下载源）
        McCard(title = stringResource(R.string.s1007)) {
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
                        text = mirror.displayName,
                        selected = config.downloadMirror == mirror,
                        onClick = { vm.setDownloadMirror(mirror) }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            // 「依次尝试下载源」：按顺序逐个测试，找到第一个可用源即选中它。
            val testing by vm.mirrorTestRunning.collectAsState()
            val testResult = vm.mirrorTestResult.collectAsState().value
            Button(
                onClick = { vm.testDownloadSources() },
                enabled = !testing,
                colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                shape = McShape.Control,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(if (testing) R.string.st_mirror_test_running else R.string.st_mirror_test),
                    fontSize = 13.sp
                )
            }
            if (testResult != null) {
                Spacer(Modifier.height(6.dp))
                Text(testResult, color = Muted, fontSize = 11.sp)
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
                        text = mirror.displayName,
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

        // 后台保活（大尺寸独立入口）
        McCard(title = stringResource(R.string.s542)) {
            Text(
                stringResource(R.string.s1018),
                color = Muted,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { onNavigate(SubPage.KeepAlive) },
                colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                shape = McShape.Control,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(stringResource(R.string.s1019), fontSize = 15.sp, fontWeight = FontWeight.Bold)
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

        // 关于
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