package com.mineserve.mobile.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileUpload
import com.mineserve.mobile.McApplication
import com.mineserve.mobile.R
import com.mineserve.mobile.data.skin.SkinEntry
import com.mineserve.mobile.ui.BackBar
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.Line
import com.mineserve.mobile.ui.theme.McIcon
import com.mineserve.mobile.ui.theme.McShape
import com.mineserve.mobile.ui.theme.McSpacing
import com.mineserve.mobile.ui.theme.Muted
import kotlinx.coroutines.launch
import com.mineserve.mobile.ui.components.AppDialog

/**
 * 主题列表页：切换 / 导入 / 导出 / 删除。
 *
 * 交互约定（与全局一致）：
 * - 点卡片 = 应用该主题；长按卡片 = 删除确认（**不做滑动删除**，与其它列表页保持一致）；
 * - 删除当前生效主题会被仓库拒绝，界面照抄仓库给的提示，不做「先自动切走」的隐式行为；
 * - 导入走 SAF（不限 MIME），不申请任何存储权限 —— 主题包来自任意位置，也可能被改名成 `.zip`。
 */
@Composable
fun ThemeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { McApplication.get(context).skinRepository }
    val state by repo.state.collectAsState()
    val scope = rememberCoroutineScope()

    var importing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<SkinEntry?>(null) }
    var pendingExportId by remember { mutableStateOf<String?>(null) }

    // 仓库里的一次性提示（例如「可读性偏低」）不自己消失，这里统一收口到页内横幅。
    LaunchedEffect(state.message) {
        state.message?.let {
            notice = it
            repo.consumeMessage()
        }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            kotlinx.coroutines.delay(4_000)
            notice = null
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            repo.install(uri)
                .onSuccess { notice = context.getString(R.string.theme_import_done, it.name) }
                .onFailure { notice = context.getString(R.string.theme_import_failed, it.message ?: "") }
            importing = false
        }
    }

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val id = pendingExportId
        pendingExportId = null
        if (uri == null || id == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                context.contentResolver.openOutputStream(uri, "wt")?.use { repo.export(id, it) }
            }.onSuccess { notice = context.getString(R.string.theme_export_done) }
                .onFailure { notice = context.getString(R.string.theme_export_failed, it.message ?: "") }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        BackBar(title = stringResource(R.string.theme_title), onBack = onBack)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.theme_intro),
            color = Muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(horizontal = McSpacing.Page)
        )
        Spacer(Modifier.height(8.dp))

        // 毛玻璃开关：默认跟随主题（启用主题包就开、默认配色就关），手动拨过就记住。
        GlassCard(
            checked = state.glassEnabled,
            following = state.glassOverride == null,
            onToggle = { on -> scope.launch { repo.setGlass(on) } },
            onFollow = { scope.launch { repo.setGlass(null) } }
        )

        if (notice != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = McSpacing.Page, vertical = 4.dp),
                shape = McShape.Control,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Text(
                    notice!!,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        // 「默认」不是主题包，而是「不使用主题包」：回到 App 自带配色。
        // 单独列一条，用户才有办法从任何主题退回原样（不然只能删包）。
        ThemeRow(
            entry = SkinEntry(
                id = "",
                name = stringResource(R.string.theme_default),
                version = "",
                author = "",
                description = stringResource(R.string.theme_default_desc),
                previewFile = null,
                isBuiltin = true
            ),
            selected = state.activeId.isEmpty(),
            onApply = { if (state.activeId.isNotEmpty()) scope.launch { repo.select("") } },
            onDelete = null,
            onExport = null
        )

        state.installed.forEach { entry ->
            ThemeRow(
                entry = entry,
                selected = entry.id == state.activeId,
                onApply = { if (entry.id != state.activeId) scope.launch { repo.select(entry.id) } },
                onDelete = { pendingDelete = entry },
                onExport = {
                    pendingExportId = entry.id
                    exporter.launch("${entry.id}-${entry.version.ifBlank { "1" }}.mctheme")
                }
            )
        }

        ImportRow(
            busy = importing,
            onClick = { if (!importing) picker.launch(arrayOf("*/*")) }
        )

        if (state.installed.isEmpty()) {
            Text(
                stringResource(R.string.theme_empty_hint),
                color = Muted,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = McSpacing.Page, vertical = 12.dp)
            )
        }
        Spacer(Modifier.height(24.dp))
    }

    val deleting = pendingDelete
    if (deleting != null) {
        AppDialog(
            onDismissRequest = { pendingDelete = null },
            shape = McShape.Dialog,
            title = { Text(stringResource(R.string.theme_delete_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.theme_delete_message, deleting.name), fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        repo.delete(deleting.id).onFailure {
                            notice = it.message ?: context.getString(R.string.theme_delete_failed, "")
                        }
                    }
                }) {
                    Text(stringResource(R.string.theme_confirm_delete), color = MaterialTheme.colorScheme.tertiary)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.s620), color = Muted)
                }
            }
        )
    }
}

@Composable
private fun GlassCard(
    checked: Boolean,
    following: Boolean,
    onToggle: (Boolean) -> Unit,
    onFollow: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = McSpacing.Page, vertical = 4.dp),
        shape = McShape.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.theme_glass_title),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.theme_glass_desc),
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = Muted
                    )
                }
                Spacer(Modifier.width(10.dp))
                Switch(checked = checked, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (following) {
                    Text(
                        stringResource(
                            if (checked) R.string.theme_glass_following_on
                            else R.string.theme_glass_following_off
                        ),
                        fontSize = 10.sp,
                        color = Muted,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                    // 只有手动设过才给「跟随主题」的回头路，否则这是一行空按钮。
                    TextButton(onClick = onFollow, modifier = Modifier.heightIn(min = 32.dp)) {
                        Text(stringResource(R.string.theme_glass_follow), fontSize = 11.sp, color = Indigo)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThemeRow(
    entry: SkinEntry,
    selected: Boolean,
    onApply: () -> Unit,
    onDelete: (() -> Unit)?,
    onExport: (() -> Unit)?
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = McSpacing.Page, vertical = 4.dp)
            .combinedClickable(
                onClick = onApply,
                onLongClick = if (onDelete != null) onDelete else null
            ),
        shape = McShape.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = if (selected) {
            androidx.compose.foundation.BorderStroke(1.5.dp, Indigo)
        } else {
            androidx.compose.foundation.BorderStroke(1.dp, Line)
        }
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ThemeThumbnail(entry)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (entry.isBuiltin) {
                        Spacer(Modifier.width(6.dp))
                        Tag(stringResource(R.string.theme_builtin))
                    }
                    if (entry.broken) {
                        Spacer(Modifier.width(6.dp))
                        Tag(stringResource(R.string.theme_broken), danger = true)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        if (entry.author.isNotBlank()) append(entry.author)
                        if (entry.version.isNotBlank()) {
                            if (isNotEmpty()) append(" · ")
                            append("v${entry.version}")
                        }
                    },
                    fontSize = 11.sp,
                    color = Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (entry.description.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        entry.description,
                        fontSize = 11.sp,
                        color = Muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (selected) {
                    Text(
                        stringResource(R.string.theme_in_use),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Indigo
                    )
                } else {
                    TextButton(
                        onClick = onApply,
                        enabled = !entry.broken,
                        modifier = Modifier.heightIn(min = 32.dp)
                    ) {
                        Text(stringResource(R.string.theme_apply), fontSize = 12.sp, color = Indigo)
                    }
                }
                if (onExport != null || onDelete != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (onExport != null) {
                            TextButton(onClick = onExport, modifier = Modifier.heightIn(min = 28.dp)) {
                                Icon(
                                    Icons.Outlined.FileUpload,
                                    contentDescription = stringResource(R.string.theme_export),
                                    tint = Muted,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(2.dp))
                                Text(stringResource(R.string.theme_export), fontSize = 10.sp, color = Muted)
                            }
                        }
                        if (onDelete != null) {
                            TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 28.dp)) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = stringResource(R.string.theme_delete),
                                    tint = Muted,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String, danger: Boolean = false) {
    Text(
        text,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        color = if (danger) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (danger) MaterialTheme.colorScheme.tertiaryContainer
                else MaterialTheme.colorScheme.primaryContainer
            )
            .padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

/** 缩略图：56×120，缺预览图时用当前卡片底色 + 主题名首字占位。 */
@Composable
private fun ThemeThumbnail(entry: SkinEntry) {
    val bitmap = remember(entry.previewFile?.path) {
        val file = entry.previewFile ?: return@remember null
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0) return@runCatching null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 160) sample *= 2
            BitmapFactory.decodeFile(
                file.path,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )?.asImageBitmap()
        }.getOrNull()
    }
    Box(
        modifier = Modifier
            .size(width = 56.dp, height = 120.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                entry.name.take(1),
                color = Muted,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ImportRow(busy: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = McSpacing.Page, vertical = 6.dp)
            .clip(McShape.Card)
            .background(Color.Transparent)
            .border(1.dp, Line, McShape.Card)
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Indigo)
                Spacer(Modifier.width(8.dp))
            } else {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = null,
                    tint = Indigo,
                    modifier = Modifier.size(McIcon.Action)
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                if (busy) stringResource(R.string.theme_importing) else stringResource(R.string.theme_import),
                color = Indigo,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
