package com.mineserve.mobile.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import com.mineserve.mobile.R
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.ui.HeaderBlock
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.Line
import com.mineserve.mobile.ui.theme.LocalSkin
import com.mineserve.mobile.ui.theme.McIcon
import com.mineserve.mobile.ui.theme.McShape
import com.mineserve.mobile.ui.theme.McSpacing
import com.mineserve.mobile.ui.theme.Muted

private data class MoreEntry(val title: String, val description: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * 「更多功能」入口网格。
 *
 * 卡片是 2 列固定网格，而入口描述的长短差得很远（中文 4–14 字，英文/波斯语能到三四行），
 * 之前每张卡按内容自适应高度，同一行两张卡会一高一矮，整页看起来参差不齐。
 * 现在统一规则：
 *  - 宽度：都 `fillMaxWidth()` 占满各自网格列（列宽由 [GridCells.Fixed] 决定，窄屏/宽屏一致）；
 *  - 高度：固定 [CARD_HEIGHT_BASE] dp，并按系统字体缩放同比放大，大字号下不会裁掉文字；
 *  - 内边距、图标尺寸、文字行数上限全部取同一组常量，标题最多 2 行、描述最多 3 行，
 *    超出省略号，描述用 `SpaceBetween` 贴底，于是每张卡的内容基线也对齐。
 */
private const val CARD_HEIGHT_BASE = 118f
private const val TITLE_MAX_LINES = 2
private const val DESC_MAX_LINES = 3

/**
 * 「外观」入口的开关（2026-11-14 按用户要求临时关掉）。
 *
 * 主题页本身、`SubPage.Theme` 路由、导入导出功能全部保留，只是「更多」网格里不再露出
 * 这张卡片 —— 想恢复把这里改回 true 即可，不需要动导航。
 */
private const val APPEARANCE_ENTRY_VISIBLE = false

@Composable
fun MoreScreen(onNetwork: () -> Unit, onBackup: () -> Unit, onDiagnostics: () -> Unit, onKeepAlive: () -> Unit, onTheme: () -> Unit, onCrashReports: () -> Unit, onWidgetSettings: () -> Unit, onMcp: () -> Unit, onFtp: () -> Unit, onAutomation: () -> Unit) {
    // 外观入口的副标题直接显示当前生效主题名（跟随 LocalSkin，换主题后无需刷新即变）；
    // 没用主题包时显示本地化的「默认」，而不是内置运行时里写死的中文名。
    val skin = LocalSkin.current
    val appearanceDesc = if (skin.id == "builtin") stringResource(R.string.theme_default) else skin.name
    val entries = listOfNotNull(
        MoreEntry(stringResource(R.string.more_network), stringResource(R.string.more_network_desc), Icons.Outlined.Cloud, onNetwork),
        MoreEntry(stringResource(R.string.more_automation), stringResource(R.string.more_automation_desc), Icons.Outlined.Schedule, onAutomation),
        MoreEntry(stringResource(R.string.more_backup), stringResource(R.string.more_backup_desc), Icons.Outlined.Backup, onBackup),
        MoreEntry(stringResource(R.string.more_ftp), stringResource(R.string.more_ftp_desc), Icons.Outlined.Storage, onFtp),
        MoreEntry(stringResource(R.string.more_diagnostics), stringResource(R.string.more_diagnostics_desc), Icons.Outlined.HealthAndSafety, onDiagnostics),
        MoreEntry(stringResource(R.string.more_crash), stringResource(R.string.more_crash_desc), Icons.Outlined.Warning, onCrashReports),
        MoreEntry(stringResource(R.string.more_keepalive), stringResource(R.string.more_keepalive_desc), Icons.Outlined.FavoriteBorder, onKeepAlive),
        MoreEntry(stringResource(R.string.more_mcp), stringResource(R.string.more_mcp_desc), Icons.Outlined.SmartToy, onMcp),
        // 「下载帮助」入口已按要求隐藏（2026-11-13）：页面与设置页里的入口都还在，
        // 只是「更多」这张网格里不再重复出现。
        // 「外观」入口 2026-11-14 起临时隐藏（见 APPEARANCE_ENTRY_VISIBLE）。
        if (APPEARANCE_ENTRY_VISIBLE) {
            MoreEntry(stringResource(R.string.theme_appearance), appearanceDesc, Icons.Outlined.Palette, onTheme)
        } else {
            null
        },
        MoreEntry(stringResource(R.string.more_widgets), stringResource(R.string.more_widgets_desc), Icons.Outlined.Widgets, onWidgetSettings)
    )
    // 卡片高度跟随系统字号：dp 不随字体缩放变，纯固定值在大字号下会把文字裁掉。
    val fontScale = LocalDensity.current.fontScale.coerceIn(0.85f, 1.6f)
    val cardHeight = (CARD_HEIGHT_BASE * fontScale).dp
    Column(Modifier.fillMaxSize()) {
        // 「更多」和 Tab 页同级：底栏可见、没有悬浮返回键，所以这里不需要预留返回键的间距
        HeaderBlock(stringResource(R.string.more_more), stringResource(R.string.more_more))
        // 卡片统一走 McCard 同款外观（12dp 圆角 + 1dp 描边 + 极浅投影）与页面边距，
        // 避免这一页的入口卡与其它页面的卡片看起来不是一套东西。
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = McSpacing.Page,
                vertical = McSpacing.CardGap
            ),
            horizontalArrangement = Arrangement.spacedBy(McSpacing.Row),
            verticalArrangement = Arrangement.spacedBy(McSpacing.Row)
        ) {
            items(entries, key = { it.title }, contentType = { "more-entry" }) { entry ->
                Card(
                    onClick = entry.onClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(cardHeight),
                    shape = McShape.Card,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    border = BorderStroke(1.dp, Line)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(McSpacing.CardInner),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // 图标 + 标题同一行：比上下堆叠省一行的纵向空间，
                        // 省下来的空间留给描述 —— 这样固定高度下三种语言都放得下。
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                entry.icon,
                                contentDescription = null,
                                tint = Indigo,
                                modifier = Modifier.size(McIcon.Item)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                entry.title,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = TITLE_MAX_LINES,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Text(
                            entry.description,
                            fontSize = 11.sp,
                            color = Muted,
                            lineHeight = 15.sp,
                            maxLines = DESC_MAX_LINES,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
