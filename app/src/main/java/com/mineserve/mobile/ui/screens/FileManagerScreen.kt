package com.mineserve.mobile.ui.screens

import androidx.compose.ui.res.stringResource
import com.mineserve.mobile.R

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.ui.HeaderBlock
import com.mineserve.mobile.ui.EmptyHint
import com.mineserve.mobile.ui.McCard
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.IndigoSoft
import com.mineserve.mobile.ui.theme.Mint
import com.mineserve.mobile.ui.theme.Muted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun FileManagerScreen(vm: McViewModel, onOpenMtGuide: () -> Unit = {}) {
    val context = LocalContext.current
    val files by vm.fileList.collectAsState()
    val currentPath by vm.currentPath.collectAsState()
    val isBootstrapped by vm.isBootstrapped.collectAsState()
    val editorFile by vm.textEditorFile.collectAsState()
    if (editorFile != null) {
        TextFileEditorScreen(vm, editorFile!!, onBack = vm::closeTextFile)
        return
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showDeleteConfirm by remember { mutableStateOf<File?>(null) }
    var showNewDirDialog by remember { mutableStateOf(false) }
    var newDirName by remember { mutableStateOf("") }

    // SAF 文件选择器
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val targetDir = File(currentPath)
            vm.uploadFile(uri, targetDir)
        }
    }

    // 导出目标（用户点击的文件/文件夹），CreateDocument 回调用
    var exportTarget by remember { mutableStateOf<File?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        uri?.let { target -> exportTarget?.let { vm.exportPathToUri(it, target) } }
        exportTarget = null
    }
    // 整服务器导出选择器
    val exportServerFileName = stringResource(R.string.s490)
    val exportServerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? -> uri?.let { vm.exportServerToUri(it) } }

    LaunchedEffect(Unit) {
        vm.errorFlow.collectLatest { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }
    LaunchedEffect(Unit) {
        vm.messageFlow.collectLatest { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    // 首次进入时加载根目录
    LaunchedEffect(isBootstrapped) {
        if (isBootstrapped && currentPath.isEmpty()) {
            vm.loadFilesRoot()
        }
    }

    androidx.compose.material3.Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 嵌套在外层 McApp Scaffold 内：insets 已由外层消费，这里不再重复应用，避免顶部空白/白色遮挡
        contentWindowInsets = WindowInsets(0.dp)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            HeaderBlock(eyebrow = stringResource(R.string.eyebrow_files), title = stringResource(R.string.s488))

            // 教程 + MT 管理器 + 导出服务器（三个按钮统一收起/展开）。折叠状态用 rememberSaveable 保存，
            // 避免在组合体内同步读 SharedPreferences 造成首帧主线程读盘。
            var topActionsExpanded by rememberSaveable { mutableStateOf(true) }
            // 折叠时只保留一行提示，三个入口一起收起，避免误点
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!topActionsExpanded) {
                    Text(
                        stringResource(R.string.files_actions_collapsed_hint),
                        color = Muted,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = topActionsExpanded,
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 教程（在线播放，不打包进安装包）
                        Button(
                            onClick = {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TUTORIAL_VIDEO_URL)))
                                } catch (e: Exception) {
                                    // 无浏览器/网络异常时静默
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.s492), fontSize = 12.sp, maxLines = 1)
                        }
                        Button(
                            onClick = onOpenMtGuide,
                            colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.s489), fontSize = 12.sp, maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = { exportServerLauncher.launch(exportServerFileName) },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text(stringResource(R.string.s491), fontSize = 12.sp, color = Indigo, maxLines = 1)
                        }
                    }
                }
                IconButton(
                    onClick = { topActionsExpanded = !topActionsExpanded },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        if (topActionsExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                        contentDescription = null,
                        tint = Muted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // 路径导航栏
            McCard(title = stringResource(R.string.s493)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val rootPath = vm.fileManagerRoot.absolutePath
                    val relPath = if (currentPath.startsWith(rootPath)) {
                        currentPath.removePrefix(rootPath).removePrefix(File.separator)
                    } else {
                        currentPath
                    }
                    val displayPath = if (relPath.isEmpty()) "servers/" else "servers/$relPath"

                    Text(
                        displayPath, color = Muted, fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    // 向上按钮
                    IconButton(onClick = { vm.navigateUp() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.ArrowUpward, stringResource(R.string.s494), tint = Indigo, modifier = Modifier.size(18.dp))
                    }
                    // 刷新按钮
                    IconButton(onClick = { vm.refreshFiles() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.Refresh, stringResource(R.string.s333), tint = Indigo, modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 返回上级
                    val currentDir = File(currentPath)
                    val parentDir = currentDir.parentFile
                    val canGoUp = parentDir != null &&
                        currentDir.absolutePath != vm.fileManagerRoot.absolutePath &&
                        currentDir.absolutePath.contains(vm.fileManagerRoot.absolutePath)

                    Button(
                        onClick = {
                            parentDir?.let { if (canGoUp) vm.loadFiles(it) }
                        },
                        enabled = canGoUp,
                        colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.s495),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(R.string.s496), color = Color.White, fontSize = 11.sp)
                    }

                    // 上传文件
                    Button(
                        onClick = {
                            filePickerLauncher.launch(arrayOf("*/*"))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Mint),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Outlined.Upload,
                            contentDescription = stringResource(R.string.s497),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(R.string.s497), color = Color.White, fontSize = 11.sp)
                    }

                    // 新建文件夹
                    Button(
                        onClick = {
                            newDirName = ""
                            showNewDirDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Outlined.CreateNewFolder,
                            contentDescription = stringResource(R.string.s498),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(R.string.s499), color = Color.White, fontSize = 11.sp)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // 文件列表
            if (files.isEmpty()) {
                McCard(title = stringResource(R.string.s500)) {
                    EmptyHint(
                        icon = Icons.Outlined.FolderOpen,
                        text = if (currentPath.isEmpty()) stringResource(R.string.s501) else stringResource(R.string.s502)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(files, key = { it.path }, contentType = { "file-entry" }) { entry ->
                        FileItemRow(
                            entry = entry,
                            onClick = {
                                if (entry.isDirectory) {
                                    vm.loadFiles(File(entry.path))
                                }
                            },
                            onExport = {
                                exportTarget = File(entry.path)
                                exportLauncher.launch(if (entry.isDirectory) "${entry.name}.zip" else entry.name)
                            },
                            onEdit = { vm.openTextFile(File(entry.path)) },
                            canEdit = entry.isEditable,
                            onDelete = {
                                showDeleteConfirm = File(entry.path)
                            }
                        )
                    }
                }
            }
        }
    }

    // 删除确认对话框
    showDeleteConfirm?.let { fileToDelete ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text(stringResource(R.string.s401), fontWeight = FontWeight.Bold) },
            text = {
                val deleteFolderPrefix = stringResource(R.string.s503)
                val deleteFilePrefix = stringResource(R.string.s1030)
                val folderContentMsg = stringResource(R.string.s504)
                val irreversibleMsg = stringResource(R.string.s505)
                Text(
                    buildString {
                        append(if (fileToDelete.isDirectory) deleteFolderPrefix else deleteFilePrefix)
                        append(fileToDelete.name)
                        if (fileToDelete.isDirectory) {
                            append(folderContentMsg)
                        }
                        append(irreversibleMsg)
                    },
                    color = Muted,
                    fontSize = 12.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteFile(fileToDelete)
                        showDeleteConfirm = null
                    }
                ) {
                    Text(stringResource(R.string.s339), color = Coral, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirm = null }
                ) {
                    Text(stringResource(R.string.s402), color = Muted)
                }
            }
        )
    }

    // 新建文件夹对话框
    if (showNewDirDialog) {
        AlertDialog(
            onDismissRequest = { showNewDirDialog = false },
            title = { Text(stringResource(R.string.s498), fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newDirName,
                    onValueChange = { newDirName = it },
                    label = { Text(stringResource(R.string.s506), fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newDirName.trim()
                        // 拒绝非法名称：'/' 会静默创建嵌套目录，'..' 可在服务器目录外建目录，首尾空白也一并拒绝
                        val invalid = name.isBlank() || name != newDirName ||
                            name.contains('/') || name.contains('\\') || name.contains("..")
                        if (invalid) {
                            scope.launch {
                                snackbarHostState.showSnackbar(context.getString(R.string.fm_new_dir_invalid_name))
                            }
                        } else {
                            vm.createDirectory(File(currentPath), name)
                            showNewDirDialog = false
                        }
                    }
                ) {
                    Text(stringResource(R.string.s507), color = Indigo, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showNewDirDialog = false }
                ) {
                    Text(stringResource(R.string.s402), color = Muted)
                }
            }
        )
    }
}

@Composable
private fun FileItemRow(
    entry: McViewModel.FileEntry,
    onClick: () -> Unit,
    onExport: () -> Unit,
    onEdit: () -> Unit,
    canEdit: Boolean,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 文件类型图标
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (entry.isDirectory) Mint.copy(alpha = 0.15f) else IndigoSoft),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (entry.isDirectory) Icons.Outlined.Folder else Icons.Outlined.Description,
                contentDescription = null,
                tint = if (entry.isDirectory) Mint else Indigo,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(Modifier.size(10.dp))

        // 文件名和信息
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                // 自动功能注释（如 world【主世界存档文件夹】）
                fileAnnotation(entry)?.let { resId ->
                    val ann = stringResource(resId)
                    Spacer(Modifier.size(6.dp))
                    Text(
                        "【$ann】",
                        color = Muted,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                if (entry.isDirectory) entry.modifiedText else "${entry.sizeText} · ${entry.modifiedText}",
                color = Muted,
                fontSize = 11.sp,
                maxLines = 1
            )
        }

        // 导出按钮
        IconButton(onClick = onExport, modifier = Modifier.padding(start = 2.dp)) {
            Icon(
                Icons.Outlined.FileDownload,
                contentDescription = stringResource(R.string.s335),
                tint = Indigo,
                modifier = Modifier.size(18.dp)
            )
        }

        if (canEdit) IconButton(onClick = onEdit) {
            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.fm_edit_text), tint = Indigo, modifier = Modifier.size(18.dp))
        }

        // 删除按钮
        TextButton(
            onClick = onDelete,
            modifier = Modifier.padding(start = 4.dp)
        ) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.s339),
                tint = Coral,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ── 服务器目录文件/文件夹中文功能注释 ────────────────────────────────

/** MT 管理器教程视频（在线播放） */
private const val TUTORIAL_VIDEO_URL =
    "https://img.remit.ee/api/file/BAACAgUAAyEGAASHRsPbAAEYWwdqbtydlO6_o-rdvwpJ3O92AfEtHQACbB0AAh7GeVdh2nS2uFg_6T0E.mp4"

private val fileAnnotations = mapOf(
    "server.jar" to R.string.s508,
    "server.properties" to R.string.s509,
    "ops.json" to R.string.s510,
    "whitelist.json" to R.string.s511,
    "banned-players.json" to R.string.s512,
    "banned-ips.json" to R.string.s513,
    "eula.txt" to R.string.s514,
    "usercache.json" to R.string.s515,
    "usernamecache.json" to R.string.s516,
    "permissions.json" to R.string.s517,
    "help.yml" to R.string.s518,
    "bukkit.yml" to R.string.s519,
    "spigot.yml" to R.string.s520,
    "paper.yml" to R.string.s521,
    "commands.yml" to R.string.s522,
    "seedcache" to R.string.s523,
    "version_history.json" to R.string.s524,
    "latest.log" to R.string.s525,
    "debug.log" to R.string.s526
)

private val dirAnnotations = mapOf(
    "world" to R.string.s527,
    "world_nether" to R.string.s528,
    "world_the_end" to R.string.s529,
    "logs" to R.string.s530,
    "log" to R.string.s531,
    "plugins" to R.string.s532,
    "config" to R.string.s533,
    "versions" to R.string.s534,
    "libraries" to R.string.s535,
    "crash-reports" to R.string.s536,
    "cache" to R.string.s537,
    "generated" to R.string.s538,
    "datapacks" to R.string.s539,
    "world_backup" to R.string.s540
)

/** 返回文件/文件夹的中文功能注释，未知名称返回 null */
private fun fileAnnotation(entry: McViewModel.FileEntry): Int? {
    val key = entry.name.lowercase()
    return if (entry.isDirectory) dirAnnotations[key] else fileAnnotations[key]
}
