package com.mineserve.mobile.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.McApplication
import com.mineserve.mobile.R
import com.mineserve.mobile.mcp.McpSkillDoc
import com.mineserve.mobile.mcp.McpToolCatalog
import com.mineserve.mobile.mcp.McpServerManager
import com.mineserve.mobile.ui.BackBar
import com.mineserve.mobile.ui.McCard
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.Mint
import com.mineserve.mobile.ui.theme.Muted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MCP（Model Context Protocol）服务设置：
 * 开关、端口、访问令牌、端点地址与客户端配置示例。
 */
@Composable
fun McpScreen(vm: McViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val config by vm.config.collectAsState()
    val lanIp by vm.lanIp.collectAsState()
    val mcpManager = (context.applicationContext as McApplication).mcpServerManager
    val mcpRunning by mcpManager.isRunning.collectAsState()
    val mcpError by mcpManager.lastError.collectAsState()

    LaunchedEffect(Unit) { vm.refreshLanIp() }

    var portText by remember(config.mcpPort) { mutableStateOf(config.mcpPort.toString()) }
    val portValue = portText.toIntOrNull()
    val portValid = portValue != null && portValue in 1024..65535

    val statusColor: Color
    val statusText: String
    when {
        mcpError != null -> {
            statusColor = Coral
            statusText = stringResource(R.string.mcp_status_error, mcpError ?: "")
        }
        config.mcpEnabled && mcpRunning -> {
            statusColor = Mint
            statusText = stringResource(R.string.mcp_status_running)
        }
        else -> {
            statusColor = Muted
            statusText = stringResource(R.string.mcp_status_stopped)
        }
    }

    fun copyToClipboard(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Minexa MCP", text))
        Toast.makeText(context, R.string.mcp_copied, Toast.LENGTH_SHORT).show()
    }

    // 默认只监听 127.0.0.1：此时局域网 IP 地址根本不通，必须如实显示回环地址，否则用户照着连必然失败。
    val endpoint = if (config.mcpAllowLan) "http://$lanIp:${config.mcpPort}/mcp"
        else "http://127.0.0.1:${config.mcpPort}/mcp"

    // 技能文档：端点/令牌/工具清单都在导出时现算，用户拿到文件就能直接交给 AI
    val skillDoc = remember(endpoint, config.mcpToken) { McpSkillDoc.build(endpoint, config.mcpToken) }
    val scope = rememberCoroutineScope()
    val skillExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val failure = withContext(Dispatchers.IO) {
                    runCatching {
                        val output = context.contentResolver.openOutputStream(uri)
                            ?: throw IllegalStateException(context.getString(R.string.mcp_skill_open_failed))
                        output.use { it.write(skillDoc.toByteArray(Charsets.UTF_8)) }
                    }.exceptionOrNull()
                }
                Toast.makeText(
                    context,
                    if (failure == null) context.getString(R.string.mcp_skill_exported)
                    else context.getString(R.string.mcp_skill_export_failed, failure.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        BackBar(stringResource(R.string.mcp_title), onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(stringResource(R.string.mcp_intro), color = Muted, fontSize = 12.sp)

            McCard(title = stringResource(R.string.mcp_enable)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.mcp_enable), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.mcp_enable_desc), fontSize = 11.sp, color = Muted)
                    }
                    Switch(
                        checked = config.mcpEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled && config.mcpToken.isBlank()) {
                                vm.updateConfig { it.copy(mcpEnabled = true, mcpToken = McpServerManager.generateToken()) }
                            } else {
                                vm.updateConfig { it.copy(mcpEnabled = enabled) }
                            }
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(statusColor, CircleShape)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(statusText, fontSize = 11.sp, color = Muted)
                }
            }

            // 监听地址开关：默认只绑 127.0.0.1（更安全），需要手机以外的 AI 助手接入时才显式开放局域网。
            // 修改后由 McpServerManager 观察到 (mcpAllowLan) 变化自动重启服务生效。
            McCard(title = "局域网访问") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("允许局域网访问", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (config.mcpAllowLan) "局域网内任何设备都可访问，请保管好令牌"
                            else "仅本机（127.0.0.1）可访问",
                            fontSize = 11.sp,
                            color = Muted
                        )
                    }
                    Switch(
                        checked = config.mcpAllowLan,
                        onCheckedChange = { allow -> vm.updateConfig { it.copy(mcpAllowLan = allow) } }
                    )
                }
            }

            McCard(title = stringResource(R.string.mcp_port)) {
                OutlinedTextField(
                    value = portText,
                    onValueChange = { value ->
                        portText = value.filter { it.isDigit() }.take(5)
                        val parsed = portText.toIntOrNull()
                        if (parsed != null && parsed in 1024..65535 && parsed != config.mcpPort) {
                            vm.updateConfig { it.copy(mcpPort = parsed) }
                        }
                    },
                    label = { Text(stringResource(R.string.mcp_port)) },
                    isError = portText.isNotEmpty() && !portValid,
                    supportingText = if (portText.isNotEmpty() && !portValid) {
                        { Text(stringResource(R.string.mcp_port_invalid), color = Coral) }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.mcp_port_hint), fontSize = 11.sp, color = Muted)
            }

            McCard(title = stringResource(R.string.mcp_token)) {
                SelectionContainer {
                    Text(
                        config.mcpToken.ifEmpty { "—" },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row {
                    Button(
                        onClick = { copyToClipboard(config.mcpToken) },
                        enabled = config.mcpToken.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = Indigo)
                    ) {
                        Text(stringResource(R.string.mcp_copy))
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        vm.updateConfig { it.copy(mcpToken = McpServerManager.generateToken()) }
                    }) {
                        Text(stringResource(R.string.mcp_token_regenerate))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.mcp_token_desc), fontSize = 11.sp, color = Muted)
            }

            McCard(title = stringResource(R.string.mcp_endpoint)) {
                SelectionContainer {
                    Text(endpoint, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (config.mcpAllowLan) "局域网内任何设备都可访问，请保管好令牌"
                    else "仅本机可访问（默认只监听 127.0.0.1）",
                    fontSize = 11.sp,
                    color = Muted
                )
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = { copyToClipboard(endpoint) },
                    colors = ButtonDefaults.buttonColors(containerColor = Indigo)
                ) {
                    Text(stringResource(R.string.mcp_copy))
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.mcp_client_config), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (config.mcpAllowLan) stringResource(R.string.mcp_client_config_desc)
                    else "将以下片段加入 MCP 客户端配置（仅本机可访问，地址固定为 127.0.0.1）",
                    fontSize = 11.sp,
                    color = Muted
                )
                Spacer(Modifier.height(6.dp))
                val snippet = """{
  "mcpServers": {
    "mineserve": {
      "type": "http",
      "url": "$endpoint",
      "headers": {
        "Authorization": "Bearer ${config.mcpToken}"
      }
    }
  }
}"""
                SelectionContainer {
                    Text(snippet, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Muted)
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { copyToClipboard(snippet) }) {
                    Text(stringResource(R.string.mcp_copy))
                }
            }

            McCard(title = stringResource(R.string.mcp_skill)) {
                Text(stringResource(R.string.mcp_skill_desc), fontSize = 11.sp, color = Muted)
                Spacer(Modifier.height(8.dp))
                Row {
                    Button(
                        onClick = { skillExportLauncher.launch(McpSkillDoc.FILE_NAME) },
                        colors = ButtonDefaults.buttonColors(containerColor = Indigo)
                    ) {
                        Text(stringResource(R.string.mcp_skill_export))
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { copyToClipboard(skillDoc) }) {
                        Text(stringResource(R.string.mcp_skill_copy))
                    }
                }
            }

            McCard(title = stringResource(R.string.mcp_tools)) {
                Text(stringResource(R.string.mcp_tools_hint), fontSize = 11.sp, color = Muted)
                Spacer(Modifier.height(8.dp))
                McpToolCatalog.definitions().forEach { tool ->
                    Column(Modifier.padding(bottom = 8.dp)) {
                        Text(tool.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Indigo, fontFamily = FontFamily.Monospace)
                        Text(tool.description, fontSize = 11.sp, color = Muted)
                    }
                }
            }

            Text(stringResource(R.string.mcp_security_note), fontSize = 11.sp, color = Muted)
        }
    }
}
