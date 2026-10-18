package com.mineserve.mobile.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.HealthAndSafety
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.mineserve.mobile.R
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.ui.HeaderBlock
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.Line
import com.mineserve.mobile.ui.theme.McIcon
import com.mineserve.mobile.ui.theme.McShape
import com.mineserve.mobile.ui.theme.McSpacing
import com.mineserve.mobile.ui.theme.Muted

private data class MoreEntry(val title: String, val description: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
fun MoreScreen(onNetwork: () -> Unit, onBackup: () -> Unit, onDiagnostics: () -> Unit, onKeepAlive: () -> Unit, onHelp: () -> Unit, onCrashReports: () -> Unit, onWidgetSettings: () -> Unit, onMcp: () -> Unit, onFtp: () -> Unit, onAutomation: () -> Unit) {
    val entries = listOf(
        MoreEntry(stringResource(R.string.more_network), stringResource(R.string.more_network_desc), Icons.Outlined.Cloud, onNetwork),
        MoreEntry(stringResource(R.string.more_automation), stringResource(R.string.more_automation_desc), Icons.Outlined.Schedule, onAutomation),
        MoreEntry(stringResource(R.string.more_backup), stringResource(R.string.more_backup_desc), Icons.Outlined.Backup, onBackup),
        MoreEntry(stringResource(R.string.more_ftp), stringResource(R.string.more_ftp_desc), Icons.Outlined.Storage, onFtp),
        MoreEntry(stringResource(R.string.more_diagnostics), stringResource(R.string.more_diagnostics_desc), Icons.Outlined.HealthAndSafety, onDiagnostics),
        MoreEntry(stringResource(R.string.more_crash), stringResource(R.string.more_crash_desc), Icons.Outlined.Warning, onCrashReports),
        MoreEntry(stringResource(R.string.more_keepalive), stringResource(R.string.more_keepalive_desc), Icons.Outlined.FavoriteBorder, onKeepAlive),
        MoreEntry(stringResource(R.string.more_mcp), stringResource(R.string.more_mcp_desc), Icons.Outlined.SmartToy, onMcp),
        MoreEntry(stringResource(R.string.more_help), stringResource(R.string.more_help_desc), Icons.AutoMirrored.Outlined.HelpOutline, onHelp),
        MoreEntry(stringResource(R.string.more_widgets), stringResource(R.string.more_widgets_desc), Icons.Outlined.Widgets, onWidgetSettings)
    )
    Column(Modifier.fillMaxSize()) {
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
                    modifier = Modifier.fillMaxWidth(),
                    shape = McShape.Card,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    border = BorderStroke(1.dp, Line)
                ) {
                    Column(Modifier.padding(McSpacing.CardInner), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(entry.icon, null, tint = Indigo, modifier = Modifier.size(McIcon.Display))
                        Text(entry.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(entry.description, fontSize = 11.sp, color = Muted, lineHeight = 16.sp)
                    }
                }
            }
        }
    }
}
