package com.mineserve.mobile.ui.screens

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.mineserve.mobile.BuildConfig
import com.mineserve.mobile.R

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.mineserve.mobile.McApplication
import com.mineserve.mobile.data.AppLanguage
import com.mineserve.mobile.data.AppUpdateService
import com.mineserve.mobile.data.AptMirror
import com.mineserve.mobile.data.DownloadMirror
import com.mineserve.mobile.data.DownloadPrefs
import com.mineserve.mobile.data.LocaleManager
import com.mineserve.mobile.ui.HeaderBlock
import com.mineserve.mobile.ui.McCard
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.QqGroupDialog
import com.mineserve.mobile.ui.SegPill
import com.mineserve.mobile.ui.SubPage
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.FieldGray
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.Line
import com.mineserve.mobile.ui.theme.McIcon
import com.mineserve.mobile.ui.theme.McShape
import com.mineserve.mobile.ui.theme.McSpacing
import com.mineserve.mobile.ui.theme.Muted

/** 意见反馈邮箱。底部图标与 mailto 共用一处，避免两处写死不同的地址。 */
private const val FEEDBACK_EMAIL = "167245484@qq.com"

/**
 * 设置页。
 *
 * 分层目标：常用项一屏可见，低频项不占版面。
 *
 * 1. **四组归拢**：通用 → 下载与更新 → 运行与保活 → 关于与支持。组标签规格与
 *    [HeaderBlock] 的 eyebrow 完全一致（10sp / Bold / 0.6sp 字距 / Muted），
 *    左边缘与卡片外边缘对齐；分组靠留白和字号区分，不额外加线框。
 * 2. **低频项降级**：APT 软件源改成折叠卡（默认收起，只剩一行标题）；关于卡改
 *    compact，并把「下载帮助」并进去 —— 原来「快捷入口」单独占一整张卡。
 * 3. **外链收拢到底部**：GitHub / 邮箱 / QQ 群原来各占一张整宽卡片（带大段说明和
 *    整宽按钮），现在统一成三个圆形图标 + 一行版本说明，放在页面最底部 ——
 *    它们都是「偶尔点一次」的出口，不该占滚动的黄金位置。
 */
@Composable
fun SettingsScreen(vm: McViewModel, onNavigate: (SubPage) -> Unit = {}) {
    val lastCheckResult by vm.lastUpdateCheckResult.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        HeaderBlock(eyebrow = stringResource(R.string.eyebrow_settings), title = stringResource(R.string.s186))

        // ── 通用 ──────────────────────────────────────────────────
        SettingsGroupLabel(stringResource(R.string.set_group_general))
        LanguageCard()
        EventNotifyCard(vm)

        // ── 下载与更新 ─────────────────────────────────────────────
        SettingsGroupLabel(stringResource(R.string.set_group_download))
        DownloadSourceCard(vm)
        AptMirrorCard(vm)
        DownloadTuningCard(vm)

        // ── 运行与保活 ─────────────────────────────────────────────
        SettingsGroupLabel(stringResource(R.string.set_group_runtime))
        McCard(title = stringResource(R.string.s1013)) {
            val config by vm.config.collectAsState()
            SettingToggle(
                title = stringResource(R.string.s396),
                subtitle = stringResource(R.string.s397),
                checked = config.autoRestartOnCrash,
                onChange = { vm.setAutoRestart(it) }
            )
            SettingDivider()
            SettingToggle(
                title = stringResource(R.string.s1014),
                subtitle = stringResource(R.string.s1015),
                checked = config.keepWifiLock,
                onChange = { vm.setKeepWifiLock(it) }
            )
            SettingDivider()
            SettingToggle(
                title = stringResource(R.string.s1016),
                subtitle = stringResource(R.string.s1017),
                checked = config.keepCpuWakelock,
                onChange = { vm.setKeepCpuWakelock(it) }
            )
        }

        // ── 关于与支持 ─────────────────────────────────────────────
        SettingsGroupLabel(stringResource(R.string.set_group_about))
        McCard(title = stringResource(R.string.s1023), compact = true) {
            // 版本号是只读信息。改造前它被写成可点击的 SettingEntry 并带箭头，
            // 但 onClick 是空实现 —— 看着能点、点了没反应。
            SettingInfoRow(
                title = stringResource(R.string.update_current_version),
                value = "v${BuildConfig.VERSION_NAME}"
            )
            SettingDivider()
            SettingEntry(
                title = stringResource(R.string.update_check),
                subtitle = lastCheckResult.orEmpty(),
                onClick = { vm.checkForUpdate(manual = true) }
            )
            SettingDivider()
            SettingEntry(
                title = stringResource(R.string.s1021),
                subtitle = stringResource(R.string.s1022),
                onClick = { onNavigate(SubPage.DownloadHelp) }
            )
        }

        SettingsFooter()
    }
}

/**
 * 页面底部的外链区：GitHub / 邮箱 / QQ 群三个圆形图标 + 一行版本说明。
 *
 * 三个入口原先各是一张整宽卡片：GitHub 一行、邮箱一段说明 + 整宽按钮、QQ 群三段
 * 文字 + 整宽按钮，加起来占掉大半屏。它们的共同点是「点一次就走」，不需要解释性
 * 文案，所以收敛成图标；具体信息（群号、反馈说明）留在点击后的弹窗里。
 */
@Composable
private fun SettingsFooter() {
    val context = LocalContext.current
    val mailSubject = stringResource(R.string.st_feedback_subject)
    var showQq by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 26.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FooterLink(
                label = stringResource(R.string.set_link_github),
                onClick = { openExternal(context, AppUpdateService.PROJECT_URL) }
            ) {
                Icon(Icons.Outlined.Code, null, tint = Indigo, modifier = Modifier.size(22.dp))
            }
            FooterLink(
                label = stringResource(R.string.set_link_email),
                onClick = { openMail(context, mailSubject) }
            ) {
                Icon(Icons.Outlined.Email, null, tint = Coral, modifier = Modifier.size(22.dp))
            }
            FooterLink(
                label = stringResource(R.string.set_link_qq),
                onClick = { showQq = true }
            ) {
                Icon(
                    painterResource(R.drawable.ic_qq_penguin),
                    null,
                    tint = Indigo,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "v${BuildConfig.VERSION_NAME} · " + stringResource(R.string.s1025),
            color = Muted,
            fontSize = 10.sp,
            lineHeight = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = McSpacing.Page + 8.dp)
        )
    }
    if (showQq) QqGroupDialog(onDismiss = { showQq = false })
}

/** 底部外链图标：46dp 圆片 + 1px 描边 + 下方 10sp 标签，整体命中区满足最小触摸尺寸。 */
@Composable
private fun FooterLink(
    label: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(McShape.Row)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, Line, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * 分组标签。
 *
 * 规格与 HeaderBlock 的 eyebrow 完全一致（10sp / Bold / 0.6sp 字距 / Muted），
 * 这样页面标题和分组标签在同一套字阶上，不会出现「两个都是小字但排版不一样」。
 * 左内边距取 [McSpacing.Page]，与卡片外边缘对齐。
 */
@Composable
private fun SettingsGroupLabel(text: String) {
    Text(
        text,
        color = Muted,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.6.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = McSpacing.Page,
                end = McSpacing.Page,
                top = McSpacing.CardGap + 6.dp,
                bottom = 0.dp
            )
    )
}

/** 卡片内相邻两行之间的分隔线。 */
@Composable
private fun SettingDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 2.dp),
        thickness = 1.dp,
        color = Line
    )
}

/**
 * 开关行。
 *
 * 最小高度按 48dp 给：原先两行文字 + Switch 的自然高度只有约 36dp，
 * 在手机上一行行挤在一起，误触到隔壁开关的概率不低。
 */
@Composable
private fun SettingToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * 可点击的设置行。
 *
 * [subtitle] 允许为空：像「检查更新」在还没检查过的时候没有副标题，
 * 空串会被直接跳过，不留一条空行把行高撑开。
 */
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
            .heightIn(min = 44.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Indigo)
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = Indigo,
            modifier = Modifier.size(McIcon.Item)
        )
    }
}

/** 只读设置行：无点击、无箭头，右侧直接给值。 */
@Composable
private fun SettingInfoRow(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(value, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** 事件通知档位。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EventNotifyCard(vm: McViewModel) {
    val notifyLevel by vm.eventNotifyLevel.collectAsState()
    McCard(title = stringResource(R.string.set_notify_title)) {
        Text(
            stringResource(R.string.set_notify_hint),
            color = Muted,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
        Spacer(Modifier.height(McSpacing.Section))
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
}

/**
 * 下载源（Termux bootstrap rootfs）。
 *
 * 右上角闪电图标 = 「依次尝试下载源」：按顺序逐个测试，找到第一个可用源即选中它。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadSourceCard(vm: McViewModel) {
    val config by vm.config.collectAsState()
    val testing by vm.mirrorTestRunning.collectAsState()
    val testResult by vm.mirrorTestResult.collectAsState()
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
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
        Spacer(Modifier.height(McSpacing.Row))
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
            Spacer(Modifier.height(McSpacing.Row))
            Text(
                if (testing) stringResource(R.string.st_mirror_test_running) else testResult.orEmpty(),
                color = Muted,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
    }
}

/**
 * APT 镜像（JDK / wget / frp 等依赖包下载源）。
 *
 * 默认折叠：这是「装依赖时才想起来」的低频项，展开后 4 个镜像胶囊会把
 * 「下载设置」「保活」整段推下屏。折叠后只剩一行标题，需要时点开。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AptMirrorCard(vm: McViewModel) {
    val config by vm.config.collectAsState()
    McCard(
        title = stringResource(R.string.s1010),
        compact = true,
        collapsible = true,
        initiallyExpanded = false
    ) {
        Text(
            stringResource(R.string.s1011),
            color = Muted,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
        Spacer(Modifier.height(McSpacing.Row))
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
}

/** 多线程下载加速。 */
@Composable
private fun DownloadTuningCard(vm: McViewModel) {
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
        Spacer(Modifier.height(McSpacing.Section))
        // 线程数用滑块而不是 2/4/8 三个胶囊：可取值本来就是连续区间（1..16），
        // 三档会把 6/12 这类中间值藏起来；滑块同时把手势面积做大，单手好调。
        //
        // 关闭多线程时整块降到 50% 不透明度：原先滑块只是变成灰色轨道、
        // 数字仍然用 Indigo 高亮，看着像是「还能调」，实际拖不动。
        Column(Modifier.alpha(if (enabledState.value) 1f else 0.5f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.s1067),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    stringResource(R.string.st_threads_count, threadState.value),
                    color = if (enabledState.value) Indigo else Muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Slider(
                value = threadState.value.toFloat(),
                onValueChange = { threadState.value = it.roundToInt() },
                // 只在抬手（拖动结束）时落盘：拖动过程每移动 1 像素就写一次
                // SharedPreferences 没有意义，而且会让 IO 线程跟着抖。
                onValueChangeFinished = { vm.setDownloadThreadCount(threadState.value) },
                valueRange = DownloadPrefs.MIN_THREADS.toFloat()..DownloadPrefs.MAX_THREADS.toFloat(),
                steps = DownloadPrefs.MAX_THREADS - DownloadPrefs.MIN_THREADS - 1,
                enabled = enabledState.value,
                colors = SliderDefaults.colors(
                    thumbColor = Indigo,
                    activeTrackColor = Indigo,
                    inactiveTrackColor = FieldGray,
                    disabledThumbColor = Muted,
                    disabledActiveTrackColor = Line,
                    disabledInactiveTrackColor = FieldGray
                )
            )
            Text(
                stringResource(R.string.st_threads_range, DownloadPrefs.MIN_THREADS, DownloadPrefs.MAX_THREADS),
                color = Muted,
                fontSize = 10.sp
            )
        }
    }
}

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

/**
 * 语言选择卡片。
 *
 * 列表直接遍历 [AppLanguage.entries]，新增语言只需要加枚举项 + 一个 values-xx 资源目录，
 * 这里不用改。切换后保存并 `recreate()` 当前 Activity —— 只有重建才会重新走
 * `attachBaseContext`，让整棵 Compose 树与通知/桌面组件一起换成新语言。
 */
@Composable
private fun LanguageCard() {
    val context = LocalContext.current
    val current = remember { LocaleManager.current(context) }
    var expanded by remember { mutableStateOf(false) }

    McCard(title = stringResource(R.string.set_language_title)) {
        Text(
            stringResource(R.string.set_language_hint),
            color = Muted,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
        Spacer(Modifier.height(McSpacing.Row))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                languageLabel(current),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Indigo,
                modifier = Modifier.weight(1f)
            )
            Text(stringResource(R.string.set_language_change), color = Muted, fontSize = 12.sp)
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = Indigo,
                modifier = Modifier.size(McIcon.Item)
            )
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
                                .heightIn(min = 48.dp)
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
 * 无浏览器 / 无匹配 Activity 时静默返回 —— 与 [openMail] 的处理一致，
 * 不为「点一下没反应」这种场景弹崩溃对话框。
 */
private fun openExternal(context: android.content.Context, url: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
        // 设备无可用的浏览器，静默
    }
}

/** 打开邮件客户端并预填主题；无邮件应用时静默。 */
private fun openMail(context: android.content.Context, subject: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$FEEDBACK_EMAIL"))
                .putExtra(Intent.EXTRA_SUBJECT, subject)
        )
    } catch (_: Exception) {
        // 设备无邮件客户端，静默
    }
}
