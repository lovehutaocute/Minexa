package com.mineserve.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.R
import com.mineserve.mobile.data.AptMirror
import com.mineserve.mobile.data.DownloadMirror
import com.mineserve.mobile.data.MirrorSpeedTest
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.FieldGray
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.IndigoSoft
import com.mineserve.mobile.ui.theme.McIcon
import com.mineserve.mobile.ui.theme.McShape
import com.mineserve.mobile.ui.theme.McSpacing
import com.mineserve.mobile.ui.theme.Mint
import com.mineserve.mobile.ui.theme.Muted

/**
 * 镜像测速弹窗：主界面安装卡片与设置页「下载源设置」共用的同一个入口。
 *
 * 打开即自动测速（用户点闪电就是想看哪个源能用），测完把延迟标在每一行右侧；
 * 点任意一行立刻切换为当前下载源 —— 不需要再回卡片里找胶囊。
 *
 * 状态刻意留在弹窗内部（`remember`）：关掉弹窗 = 丢弃结果。镜像可用性几分钟就变，
 * 把旧延迟留在内存里只会让下次打开时显示一串过期数字。
 */
@Composable
fun MirrorSpeedTestDialog(vm: McViewModel, onDismiss: () -> Unit) {
    val config by vm.config.collectAsState()
    // key = 分组 + 枚举名：GitHub 与 APT 的枚举名不重叠，但加分组能防将来重名。
    val results = remember { mutableStateMapOf<String, MirrorSpeedTest.Result>() }
    var running by remember { mutableStateOf(true) }
    var round by remember { mutableIntStateOf(0) }

    LaunchedEffect(round) {
        results.clear()
        running = true
        try {
            MirrorSpeedTest.run { r -> results[r.group.name + "/" + r.key] = r }
        } finally {
            running = false
        }
    }

    // 全部测完后把最快的两个标出来：用户不需要自己在那排数字里找最小值。
    val fastestGithub = remember(results.size, running) { fastestKey(results, MirrorSpeedTest.Group.GitHub) }
    val fastestApt = remember(results.size, running) { fastestKey(results, MirrorSpeedTest.Group.Apt) }

    AppDialog(
        onDismissRequest = onDismiss,
        shape = McShape.Dialog,
        containerColor = MaterialTheme.colorScheme.surface,
        icon = {
            Icon(Icons.Outlined.Bolt, contentDescription = null, tint = Indigo, modifier = Modifier.size(McIcon.Display))
        },
        title = {
            Text(
                stringResource(R.string.mirror_speed_title),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    stringResource(R.string.mirror_speed_desc),
                    color = Muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
                Spacer(Modifier.height(McSpacing.Row))
                MirrorSection(
                    title = stringResource(R.string.mirror_speed_github),
                    results = results,
                    group = MirrorSpeedTest.Group.GitHub,
                    selectedKey = config.downloadMirror.name,
                    fastestKey = fastestGithub,
                    running = running,
                    onPick = { key ->
                        runCatching { DownloadMirror.valueOf(key) }.getOrNull()
                            ?.let { vm.setDownloadMirror(it) }
                    }
                )
                Spacer(Modifier.height(McSpacing.Section))
                MirrorSection(
                    title = stringResource(R.string.mirror_speed_apt),
                    results = results,
                    group = MirrorSpeedTest.Group.Apt,
                    selectedKey = config.aptMirror.name,
                    fastestKey = fastestApt,
                    running = running,
                    onPick = { key ->
                        runCatching { AptMirror.valueOf(key) }.getOrNull()
                            ?.let { vm.setAptMirror(it) }
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { round++ }, enabled = !running) {
                Text(
                    stringResource(if (running) R.string.mirror_speed_testing else R.string.mirror_speed_retest),
                    fontSize = 12.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.mirror_speed_close), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    )
}

private fun fastestKey(
    results: Map<String, MirrorSpeedTest.Result>,
    group: MirrorSpeedTest.Group
): String? {
    val best = results.values
        .filter { it.group == group && it.ok }
        .minByOrNull { it.latencyMs ?: Long.MAX_VALUE }
    return best?.key
}

@Composable
private fun MirrorSection(
    title: String,
    results: Map<String, MirrorSpeedTest.Result>,
    group: MirrorSpeedTest.Group,
    selectedKey: String,
    fastestKey: String?,
    running: Boolean,
    onPick: (String) -> Unit
) {
    // 行顺序取自枚举，而不是 results —— 结果边测边到，按到达顺序排会让列表不停跳动。
    val targets = remember(group) {
        if (group == MirrorSpeedTest.Group.GitHub) MirrorSpeedTest.githubTargets()
        else MirrorSpeedTest.aptTargets()
    }
    Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        targets.forEach { target ->
            val result = results[group.name + "/" + target.key]
            MirrorProbeRow(
                label = target.label,
                latencyMs = result?.latencyMs,
                pending = result == null && running,
                selected = target.key == selectedKey,
                fastest = target.key == fastestKey,
                onClick = { onPick(target.key) }
            )
        }
    }
}

@Composable
private fun MirrorProbeRow(
    label: String,
    latencyMs: Long?,
    pending: Boolean,
    selected: Boolean,
    fastest: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(McShape.Row)
            .background(if (selected) IndigoSoft else FieldGray)
            .clickable(enabled = latencyMs != null) { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            if (fastest) {
                Text(
                    stringResource(R.string.mirror_speed_fastest),
                    color = Mint,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        when {
            pending -> CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = Indigo,
                strokeWidth = 2.dp
            )
            latencyMs != null -> Text(
                stringResource(R.string.mirror_speed_ms, latencyMs),
                color = Indigo,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            else -> Text(
                stringResource(R.string.mirror_speed_fail),
                color = Coral,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (selected) {
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = stringResource(R.string.mirror_speed_selected),
                tint = Indigo,
                modifier = Modifier.size(McIcon.Inline)
            )
        }
    }
}
