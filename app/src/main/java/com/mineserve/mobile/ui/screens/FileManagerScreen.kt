package com.mineserve.mobile.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.R
import com.mineserve.mobile.ui.EmptyHint
import com.mineserve.mobile.ui.HeaderBlock
import com.mineserve.mobile.ui.McCard
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.components.StoragePermissionCard
import com.mineserve.mobile.ui.components.rememberStoragePermissionRequest
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.IndigoSoft
import com.mineserve.mobile.ui.theme.Line
import com.mineserve.mobile.ui.theme.McIcon
import com.mineserve.mobile.ui.theme.McShape
import com.mineserve.mobile.ui.theme.McSpacing
import com.mineserve.mobile.ui.theme.Mint
import com.mineserve.mobile.ui.theme.Muted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

/** 列表排序维度：目录永远排在最前，此项只决定同级项目的顺序。 */
private enum class FileSort { Name, Time, Size }

@Composable
fun FileManagerScreen(vm: McViewModel) {
    val context = LocalContext.current
    val files by vm.fileList.collectAsState()
    val currentPath by vm.currentPath.collectAsState()
    val listState by vm.fileListState.collectAsState()
    val storageGranted by vm.storageGranted.collectAsState()
    val editorFile by vm.textEditorFile.collectAsState()
    if (editorFile != null) {
        TextFileEditorScreen(vm, editorFile!!, onBack = vm::closeTextFile)
        return
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // ── 视图状态 ──────────────────────────────────────────────────
    var sortBy by rememberSaveable { mutableStateOf(FileSort.Name) }
    var sortAscending by rememberSaveable { mutableStateOf(true) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var selectMode by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    // 「复制 → 换目录 → 粘贴」的剪贴位：只存源路径，粘贴时才真正 IO
    var clipSource by remember { mutableStateOf<File?>(null) }

    // ── 弹窗状态 ──────────────────────────────────────────────────
    var pendingDelete by remember { mutableStateOf<List<File>>(emptyList()) }
    var showNewDirDialog by remember { mutableStateOf(false) }
    var newDirName by remember { mutableStateOf("") }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<File?>(null) }
    var renameName by remember { mutableStateOf("") }
    var detailTarget by remember { mutableStateOf<McViewModel.FileEntry?>(null) }

    val clipboard = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }
    fun copyToClipboard(text: String) {
        clipboard?.setPrimaryClip(ClipData.newPlainText("minexa-path", text))
    }

    // SAF 文件选择器
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) vm.uploadFile(uri, File(currentPath))
    }

    // 导出目标（用户点击的文件/文件夹），CreateDocument 回调用
    var exportTarget by remember { mutableStateOf<File?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        uri?.let { target -> exportTarget?.let { vm.exportPathToUri(it, target) } }
        exportTarget = null
    }

    LaunchedEffect(Unit) {
        vm.errorFlow.collectLatest { msg -> snackbarHostState.showSnackbar(msg) }
    }
    LaunchedEffect(Unit) {
        vm.messageFlow.collectLatest { msg -> snackbarHostState.showSnackbar(msg) }
    }

    // 进入本页 / 授权状态变化时刷新。
    // 关键：这里**不**用 Termux 环境（isBootstrapped）做门槛 —— 文件浏览与 Java 运行时无关，
    // 首启（引导包还没装完）时也必须能列出磁盘上已有的服务器目录。
    LaunchedEffect(storageGranted) { vm.onFileManagerVisible() }

    // 换目录后清空多选，否则「已选 N 项」会指向上一层的路径
    LaunchedEffect(currentPath) { selected = emptySet() }

    val rootPath = vm.fileManagerRoot.absolutePath
    val fullPath = currentPath.ifEmpty { rootPath }
    val currentDir = File(fullPath)
    val parentDir = currentDir.parentFile
    val canGoUp = parentDir != null &&
        currentDir.absolutePath != rootPath &&
        currentDir.absolutePath.startsWith(rootPath)

    // 排序 + 隐藏文件过滤在本地做：不动 ViewModel 的加载结果，
    // 切换排序/过滤是零 IO 的纯内存操作，不会因为重排重新扫盘。
    val visibleFiles = remember(files, sortBy, sortAscending, showHidden) {
        val filtered = if (showHidden) files else files.filter { !it.name.startsWith(".") }
        val byKey = when (sortBy) {
            FileSort.Name -> compareBy(String.CASE_INSENSITIVE_ORDER) { e: McViewModel.FileEntry -> e.name }
            FileSort.Time -> compareBy { e: McViewModel.FileEntry -> e.lastModified }
            FileSort.Size -> compareBy { e: McViewModel.FileEntry -> e.sizeBytes }
        }
        filtered.sortedWith(
            compareByDescending<McViewModel.FileEntry> { it.isDirectory }
                .then(if (sortAscending) byKey else byKey.reversed())
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 嵌套在外层 McApp Scaffold 内：insets 已由外层消费，这里不再重复应用
        contentWindowInsets = WindowInsets(0.dp)
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            HeaderBlock(
                eyebrow = stringResource(R.string.eyebrow_files),
                title = stringResource(R.string.s488)
            )

            // ── 路径 + 工具条 ────────────────────────────────────────
            McCard(title = stringResource(R.string.s493)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 完整绝对路径：等宽字体 + 最多两行，宁可折行也不截成「servers/xxx」，
                    // 用户要能一眼看出这些文件究竟落在共享存储的哪个位置。
                    Text(
                        text = fullPath,
                        color = Muted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 15.sp,
                        // 3 行兜底：深层路径（…/servers/xxx/plugins）两行放不下，
                        // 截断会把最关键的末级目录名切掉，正好丢掉用户要看的那一段。
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(4.dp))
                    IconAction(
                        icon = Icons.Outlined.ContentCopy,
                        description = stringResource(R.string.fm_copy_path),
                        tint = Muted,
                        onClick = {
                            copyToClipboard(fullPath)
                            scope.launch {
                                snackbarHostState.showSnackbar(context.getString(R.string.fm_path_copied))
                            }
                        }
                    )
                    IconAction(
                        icon = Icons.Outlined.Refresh,
                        description = stringResource(R.string.s333),
                        tint = Muted,
                        onClick = { vm.refreshFiles() }
                    )
                }

                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Line)
                )
                Spacer(Modifier.height(6.dp))

                // 图标工具条：单个图标 = 一个动作，命中区 40dp 但视觉重量只有 18dp 线宽，
                // 一排能放下 8 个动作，且不再有大色块抢走列表的注意力。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconAction(
                        icon = Icons.Outlined.Home,
                        description = stringResource(R.string.fm_action_root),
                        enabled = fullPath != rootPath,
                        onClick = { vm.loadFilesRoot() }
                    )
                    IconAction(
                        icon = Icons.Outlined.ArrowUpward,
                        description = stringResource(R.string.s494),
                        enabled = canGoUp,
                        onClick = { vm.navigateUp() }
                    )
                    IconAction(
                        icon = Icons.Outlined.Upload,
                        description = stringResource(R.string.s497),
                        tint = Mint,
                        onClick = { filePickerLauncher.launch(arrayOf("*/*")) }
                    )
                    IconAction(
                        icon = Icons.Outlined.CreateNewFolder,
                        description = stringResource(R.string.s498),
                        onClick = {
                            newDirName = ""
                            showNewDirDialog = true
                        }
                    )
                    IconAction(
                        icon = Icons.Outlined.NoteAdd,
                        description = stringResource(R.string.fm_action_new_file),
                        onClick = {
                            newFileName = ""
                            showNewFileDialog = true
                        }
                    )
                    SortAction(
                        sortBy = sortBy,
                        ascending = sortAscending,
                        onPick = { picked ->
                            if (picked == sortBy) {
                                sortAscending = !sortAscending
                            } else {
                                sortBy = picked
                                sortAscending = true
                            }
                        }
                    )
                    IconAction(
                        icon = if (showHidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                        description = stringResource(
                            if (showHidden) R.string.fm_action_hidden_off else R.string.fm_action_hidden_on
                        ),
                        active = showHidden,
                        onClick = { showHidden = !showHidden }
                    )
                    IconAction(
                        icon = Icons.Outlined.Checklist,
                        description = stringResource(R.string.fm_action_select),
                        active = selectMode,
                        onClick = {
                            selectMode = !selectMode
                            selected = emptySet()
                        }
                    )
                    if (clipSource != null) {
                        IconAction(
                            icon = Icons.Outlined.ContentPaste,
                            description = stringResource(R.string.fm_paste),
                            tint = Mint,
                            onClick = {
                                clipSource?.let { vm.copyEntryTo(it, File(fullPath)) }
                            }
                        )
                    }
                }
            }

            // ── 多选操作条（只在多选模式出现）─────────────────────────
            if (selectMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = McSpacing.Page, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.fm_selected_count, selected.size),
                        color = Indigo,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    IconAction(
                        icon = Icons.Outlined.SelectAll,
                        description = stringResource(R.string.fm_select_all),
                        onClick = { selected = visibleFiles.map { it.path }.toSet() }
                    )
                    IconAction(
                        icon = Icons.Outlined.Delete,
                        description = stringResource(R.string.fm_delete_selected),
                        tint = Coral,
                        enabled = selected.isNotEmpty(),
                        onClick = { pendingDelete = selected.map { File(it) } }
                    )
                    IconAction(
                        icon = Icons.Outlined.Close,
                        description = stringResource(R.string.fm_select_exit),
                        tint = Muted,
                        onClick = {
                            selectMode = false
                            selected = emptySet()
                        }
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // 未授权时先给「去授权」入口：无权限时 listFiles() 返回 null，列表只能为空
            val requestStorage = rememberStoragePermissionRequest()
            StoragePermissionCard(granted = storageGranted, onRequest = { requestStorage() })

            if (visibleFiles.isEmpty()) {
                McCard(title = stringResource(R.string.s500)) {
                    EmptyHint(
                        icon = Icons.Outlined.FolderOpen,
                        text = when {
                            storageGranted.not() -> stringResource(R.string.files_no_permission)
                            listState == McViewModel.FileListState.RootMissing ->
                                stringResource(R.string.files_root_missing, vm.fileManagerRoot.absolutePath)
                            listState == McViewModel.FileListState.Loading && currentPath.isEmpty() ->
                                stringResource(R.string.s501)
                            // 目录有内容但全被隐藏文件过滤掉了，提示要开「显示隐藏文件」
                            files.isNotEmpty() -> stringResource(R.string.fm_all_hidden)
                            else -> stringResource(R.string.s502)
                        }
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(visibleFiles, key = { it.path }, contentType = { "file-entry" }) { entry ->
                        FileItemRow(
                            entry = entry,
                            selectMode = selectMode,
                            checked = selected.contains(entry.path),
                            onToggleCheck = {
                                selected = if (selected.contains(entry.path)) {
                                    selected - entry.path
                                } else {
                                    selected + entry.path
                                }
                            },
                            onClick = {
                                if (entry.isDirectory) vm.loadFiles(File(entry.path))
                            },
                            onExport = {
                                exportTarget = File(entry.path)
                                exportLauncher.launch(if (entry.isDirectory) "${entry.name}.zip" else entry.name)
                            },
                            onEdit = { vm.openTextFile(File(entry.path)) },
                            onRename = {
                                renameTarget = File(entry.path)
                                renameName = entry.name
                            },
                            onCopy = {
                                clipSource = File(entry.path)
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        context.getString(R.string.fm_copied_src, entry.name)
                                    )
                                }
                            },
                            onPasteHere = {
                                val src = clipSource
                                if (src == null) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            context.getString(R.string.fm_clipboard_empty)
                                        )
                                    }
                                } else {
                                    vm.copyEntryTo(src, File(entry.path))
                                }
                            },
                            onCopyPath = {
                                copyToClipboard(entry.path)
                                scope.launch {
                                    snackbarHostState.showSnackbar(context.getString(R.string.fm_path_copied))
                                }
                            },
                            onDetail = { detailTarget = entry },
                            onDelete = { pendingDelete = listOf(File(entry.path)) }
                        )
                    }
                }
            }
        }
    }

    // ── 删除确认（单条 / 批量共用）──────────────────────────────────
    if (pendingDelete.isNotEmpty()) {
        val batch = pendingDelete.size > 1
        AlertDialog(
            onDismissRequest = { pendingDelete = emptyList() },
            title = {
                Text(
                    if (batch) stringResource(R.string.fm_delete_batch_title)
                    else stringResource(R.string.s401),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    if (batch) {
                        stringResource(R.string.fm_delete_batch_msg, pendingDelete.size)
                    } else {
                        val target = pendingDelete.first()
                        buildString {
                            append(
                                if (target.isDirectory) stringResource(R.string.s503)
                                else stringResource(R.string.s1030)
                            )
                            append(target.name)
                            if (target.isDirectory) append(stringResource(R.string.s504))
                            append(stringResource(R.string.s505))
                        }
                    },
                    color = Muted,
                    fontSize = 12.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (batch) vm.deleteEntries(pendingDelete) else vm.deleteFile(pendingDelete.first())
                    pendingDelete = emptyList()
                    selected = emptySet()
                }) {
                    Text(stringResource(R.string.s339), color = Coral, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = emptyList() }) {
                    Text(stringResource(R.string.s402), color = Muted)
                }
            }
        )
    }

    // ── 新建文件夹 ────────────────────────────────────────────────
    if (showNewDirDialog) {
        NameInputDialog(
            title = stringResource(R.string.s498),
            label = stringResource(R.string.s506),
            value = newDirName,
            onValueChange = { newDirName = it },
            confirmLabel = stringResource(R.string.s507),
            onDismiss = { showNewDirDialog = false },
            onConfirm = { name ->
                val invalid = name.isBlank() || name != newDirName ||
                    name.contains('/') || name.contains('\\') || name.contains("..")
                if (invalid) {
                    scope.launch {
                        snackbarHostState.showSnackbar(context.getString(R.string.fm_new_dir_invalid_name))
                    }
                } else {
                    vm.createDirectory(File(fullPath), name)
                    showNewDirDialog = false
                }
            }
        )
    }

    // ── 新建文件 ──────────────────────────────────────────────────
    if (showNewFileDialog) {
        NameInputDialog(
            title = stringResource(R.string.fm_action_new_file),
            label = stringResource(R.string.fm_new_file_name),
            value = newFileName,
            onValueChange = { newFileName = it },
            confirmLabel = stringResource(R.string.fm_create_file),
            onDismiss = { showNewFileDialog = false },
            onConfirm = { name ->
                vm.createTextFile(File(fullPath), name)
                showNewFileDialog = false
            }
        )
    }

    // ── 重命名 ────────────────────────────────────────────────────
    renameTarget?.let { target ->
        NameInputDialog(
            title = stringResource(R.string.fm_rename),
            label = stringResource(R.string.fm_new_file_name),
            value = renameName,
            onValueChange = { renameName = it },
            confirmLabel = stringResource(R.string.fm_rename),
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                vm.renameEntry(target, name)
                renameTarget = null
            }
        )
    }

    // ── 属性 ──────────────────────────────────────────────────────
    detailTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { detailTarget = null },
            title = { Text(entry.name, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
            text = {
                Column {
                    DetailRow(stringResource(R.string.fm_prop_type), stringResource(
                        if (entry.isDirectory) R.string.fm_type_folder else R.string.fm_type_file
                    ))
                    DetailRow(
                        stringResource(R.string.fm_prop_size),
                        if (entry.isDirectory) "—" else entry.sizeText
                    )
                    DetailRow(stringResource(R.string.fm_prop_modified), entry.modifiedText)
                    DetailRow(stringResource(R.string.fm_prop_path), entry.path, mono = true)
                }
            },
            confirmButton = {
                TextButton(onClick = { detailTarget = null }) {
                    Text(stringResource(R.string.s402), color = Indigo, fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }
}

/**
 * 统一的图标按钮：视觉上只有一个 18dp 线性图标，命中区固定 40dp（[McIcon.Touch]）。
 *
 * 「按钮太大」的根因不是图标本身，而是大色块容器 + 文字标签占满整行；
 * 这里剥掉容器底色与标签，只保留图标与波纹，一排能并排放 8 个动作。
 * [active] 用于有开关语义的动作（隐藏文件、多选），选中态用浅靛蓝底 + 主色图标表达。
 */
@Composable
private fun IconAction(
    icon: ImageVector,
    description: String,
    tint: Color = Indigo,
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(McIcon.TouchCompact)
            .clip(McShape.Small)
            .then(if (active) Modifier.background(IndigoSoft) else Modifier)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (enabled) tint else Muted.copy(alpha = 0.35f),
            modifier = Modifier.size(McIcon.Action)
        )
    }
}

/** 排序图标 + 下拉菜单；再次点击当前维度即切换升降序。 */
@Composable
private fun SortAction(sortBy: FileSort, ascending: Boolean, onPick: (FileSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconAction(
            icon = Icons.Outlined.Sort,
            description = stringResource(R.string.fm_action_sort),
            active = expanded || sortBy != FileSort.Name || !ascending,
            onClick = { expanded = true }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortMenuItem(stringResource(R.string.fm_sort_name), sortBy == FileSort.Name, ascending) {
                onPick(FileSort.Name); expanded = false
            }
            SortMenuItem(stringResource(R.string.fm_sort_time), sortBy == FileSort.Time, ascending) {
                onPick(FileSort.Time); expanded = false
            }
            SortMenuItem(stringResource(R.string.fm_sort_size), sortBy == FileSort.Size, ascending) {
                onPick(FileSort.Size); expanded = false
            }
        }
    }
}

@Composable
private fun SortMenuItem(label: String, checked: Boolean, ascending: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                if (checked) "$label  ${if (ascending) "↑" else "↓"}" else label,
                fontSize = 13.sp,
                color = if (checked) Indigo else Color.Unspecified,
                fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal
            )
        },
        onClick = onClick
    )
}

/** 通用单行输入弹窗（新建文件夹 / 新建文件 / 重命名共用，避免三份重复布局）。 */
@Composable
private fun NameInputDialog(
    title: String,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(label, fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()) }) {
                Text(confirmLabel, color = Indigo, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.s402), color = Muted)
            }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String, mono: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = Muted, fontSize = 11.sp, modifier = Modifier.width(64.dp))
        Text(
            value,
            fontSize = 11.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun FileItemRow(
    entry: McViewModel.FileEntry,
    selectMode: Boolean,
    checked: Boolean,
    onToggleCheck: () -> Unit,
    onClick: () -> Unit,
    onExport: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onPasteHere: () -> Unit,
    onCopyPath: () -> Unit,
    onDetail: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (selectMode) onToggleCheck() else onClick() }
            .padding(start = if (selectMode) 6.dp else 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectMode) {
            Checkbox(checked = checked, onCheckedChange = { onToggleCheck() })
            Spacer(Modifier.width(2.dp))
        }

        // 文件类型图标
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (entry.isDirectory) Mint.copy(alpha = 0.15f) else IndigoSoft),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (entry.isDirectory) Icons.Outlined.Folder else Icons.Outlined.Description,
                contentDescription = null,
                tint = if (entry.isDirectory) Mint else Indigo,
                modifier = Modifier.size(McIcon.Action)
            )
        }

        Spacer(Modifier.width(10.dp))

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
                fileAnnotation(entry)?.let { resId ->
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "【${stringResource(resId)}】",
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

        if (!selectMode) {
            RowAction(Icons.Outlined.FileDownload, stringResource(R.string.s335), Indigo, onExport)
            if (entry.isEditable) {
                RowAction(Icons.Outlined.Edit, stringResource(R.string.fm_edit_text), Indigo, onEdit)
            }
            // 低频动作收进「更多」，行内只留 3 个图标，文件名能多显示 40dp
            Box {
                var menuOpen by remember { mutableStateOf(false) }
                RowAction(Icons.Outlined.MoreVert, stringResource(R.string.fm_more), Muted) { menuOpen = true }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    MenuRow(Icons.Outlined.DriveFileRenameOutline, stringResource(R.string.fm_rename), Indigo) {
                        menuOpen = false; onRename()
                    }
                    MenuRow(Icons.Outlined.ContentCopy, stringResource(R.string.fm_copy), Indigo) {
                        menuOpen = false; onCopy()
                    }
                    if (entry.isDirectory) {
                        MenuRow(Icons.Outlined.ContentPaste, stringResource(R.string.fm_paste), Mint) {
                            menuOpen = false; onPasteHere()
                        }
                    }
                    MenuRow(Icons.Outlined.Info, stringResource(R.string.fm_detail), Indigo) {
                        menuOpen = false; onDetail()
                    }
                    MenuRow(Icons.Outlined.Delete, stringResource(R.string.s339), Coral) {
                        menuOpen = false; onDelete()
                    }
                }
            }
        }
    }
}

@Composable
private fun RowAction(icon: ImageVector, description: String, tint: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(McIcon.TouchCompact)
            .clip(McShape.Small)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(McIcon.Action))
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, fontSize = 13.sp, color = tint) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(McIcon.Action)) },
        onClick = onClick
    )
}

// ── 服务器目录文件/文件夹中文功能注释 ────────────────────────────────

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
