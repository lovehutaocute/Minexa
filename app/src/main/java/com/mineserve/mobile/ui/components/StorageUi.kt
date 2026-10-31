package com.mineserve.mobile.ui.components

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.R
import com.mineserve.mobile.data.ServerStorage
import com.mineserve.mobile.data.StorageAccess
import com.mineserve.mobile.ui.McCard
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.Muted

/**
 * 申请文件管理权限的统一入口（供按钮回调使用）。
 *
 * 两条分支互斥且都由系统决定结果：
 *  - Android 10 及以下：`RequestPermission` 运行时对话框；
 *  - Android 11+：只能跳「所有文件访问」设置页（没有运行时对话框），
 *    用户返回后由 Activity 的 onResume 重新判定，所以这里不需要等待回调。
 *
 * 重复弹窗的防护放在 [StorageAccess.consumeAutoPrompt]（每次进程启动一次），
 * 本函数是「用户点了按钮」的显式路径，允许随时再进一次。
 */
@Composable
fun rememberStoragePermissionRequest(onResult: (Boolean) -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val effective = StorageAccess.refresh(context) && granted
        onResult(effective)
    }
    return {
        StorageAccess.allowManualPrompt()
        if (!StorageAccess.requiresAllFilesAccess()) {
            launcher.launch(StorageAccess.LEGACY_PERMISSION)
        } else {
            val intent: Intent = StorageAccess.allFilesSettingsIntent(context)
                ?: StorageAccess.appDetailsIntent(context)
            runCatching { context.startActivity(intent) }
        }
    }
}

/**
 * 未授权时的提示卡：说明为什么需要、影响什么、去哪授权。
 *
 * 刻意**阻塞式**呈现（列表区不显示文件），避免用户在无权限状态下对着空列表
 * 反复刷新、或误以为服务器被删了。
 */
@Composable
fun StoragePermissionCard(
    granted: Boolean,
    onRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (granted) return
    val context = LocalContext.current
    McCard(title = stringResource(R.string.storage_perm_title), modifier = modifier) {
        Text(
            stringResource(R.string.storage_perm_reason, ServerStorage.internalRoot(context).absolutePath),
            color = Muted,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onRequest,
            colors = ButtonDefaults.buttonColors(containerColor = Coral),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.storage_perm_action), fontSize = 13.sp)
        }
    }
}

/**
 * 首次启动（或拒绝后再次启动）时的授权对话框。
 *
 * 为什么不用系统对话框直接了事：Android 11+ 根本没有可弹的运行时对话框，
 * 只能跳设置页；而设置页里用户很容易找不到「所有文件访问」在哪。
 * 这里先把「要做什么、点了会跳哪」说清楚，再引路。
 */
@Composable
fun StoragePermissionDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.storage_perm_title), fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column {
                Text(
                    stringResource(R.string.storage_perm_reason, ServerStorage.internalRoot(context).absolutePath),
                    color = Muted, fontSize = 12.sp, lineHeight = 17.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.storage_perm_denied_hint),
                    color = Muted, fontSize = 11.sp, lineHeight = 16.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.storage_perm_action), color = Indigo, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.storage_perm_later), color = Muted)
            }
        }
    )
}

/**
 * 旧版私有目录 → 统一存储目录的迁移确认框。
 *
 * 触发时机：每次冷启动扫描到旧目录里还有服务器文件就弹（不记「不再提示」）。
 * 文案必须把「搬运会做什么」讲清楚，因为它的最后一步是**删除源目录**。
 */
@Composable
fun LegacyMigrationDialog(
    candidates: List<com.mineserve.mobile.server.LegacyServerMigration.Candidate>,
    running: Boolean,
    progressDone: Int,
    progressTotal: Int,
    progressName: String,
    onMigrate: () -> Unit,
    onLater: () -> Unit
) {
    if (candidates.isEmpty() && !running) return
    AlertDialog(
        onDismissRequest = { if (!running) onLater() },
        title = {
            Text(
                stringResource(R.string.legacy_migrate_title, candidates.size),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        },
        text = {
            Column {
                Text(
                    stringResource(R.string.legacy_migrate_desc),
                    color = Muted, fontSize = 12.sp, lineHeight = 17.sp
                )
                Spacer(Modifier.height(8.dp))
                candidates.take(6).forEach { candidate ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Folder,
                            contentDescription = null,
                            tint = Indigo,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${candidate.displayName} · ${candidate.fileCount} 个文件 · ${formatBytes(candidate.totalBytes)}",
                            fontSize = 11.sp,
                            color = if (candidate.registered) Muted else Coral
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                }
                if (candidates.size > 6) {
                    Text(
                        stringResource(R.string.legacy_migrate_more, candidates.size - 6),
                        color = Muted, fontSize = 11.sp
                    )
                }
                if (running) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.legacy_migrate_progress, progressDone, progressTotal, progressName),
                        color = Indigo, fontSize = 11.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onMigrate, enabled = !running) {
                Text(
                    if (running) stringResource(R.string.legacy_migrate_running)
                    else stringResource(R.string.legacy_migrate_confirm),
                    color = if (running) Muted else Indigo,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onLater, enabled = !running) {
                Text(stringResource(R.string.legacy_migrate_later), color = Muted)
            }
        }
    )
}

/** 文件大小的人类可读形式（迁移列表里只需要粗略量级） */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1073741824.0)
    bytes >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
    bytes >= 1024 -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
