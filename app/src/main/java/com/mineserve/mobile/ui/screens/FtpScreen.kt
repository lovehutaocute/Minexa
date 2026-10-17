package com.mineserve.mobile.ui.screens

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.R
import com.mineserve.mobile.ftp.FtpServerManager
import com.mineserve.mobile.ui.BackBar
import com.mineserve.mobile.ui.McCard
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.McShape
import com.mineserve.mobile.ui.theme.McSpacing
import com.mineserve.mobile.ui.theme.Mint
import com.mineserve.mobile.ui.theme.Muted
import kotlinx.coroutines.launch

private const val FTP_PREFS = "ftp_prefs"
private const val KEY_PORT = "port"
private const val KEY_ANONYMOUS = "anonymous"
private const val KEY_WRITABLE = "writable"
private const val KEY_USER = "username"
private const val KEY_PASSWORD = "password"
private const val DEFAULT_PORT = 2121

/**
 * FTP 文件管理：在局域网内通过 FTP 客户端（资源管理器 / MT 管理器等）
 * 直接访问应用的服务器目录，支持匿名访问与可写开关。
 */
@Composable
fun FtpScreen(vm: McViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(FTP_PREFS, Context.MODE_PRIVATE) }
    val running by FtpServerManager.running.collectAsState()
    val lanIp by vm.lanIp.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var portText by remember { mutableStateOf(prefs.getInt(KEY_PORT, DEFAULT_PORT).toString()) }
    // 匿名访问默认关闭：服务端侧匿名用户不校验密码且绑定 0.0.0.0，默认开启风险过高。
    // 仍从 prefs 读取既有值，老用户已保存的设置不受影响。
    var anonymous by remember { mutableStateOf(prefs.getBoolean(KEY_ANONYMOUS, false)) }
    var writable by remember { mutableStateOf(prefs.getBoolean(KEY_WRITABLE, true)) }
    var username by remember { mutableStateOf(prefs.getString(KEY_USER, "mc") ?: "mc") }
    var password by remember { mutableStateOf(prefs.getString(KEY_PASSWORD, "") ?: "") }

    LaunchedEffect(Unit) { vm.refreshLanIp() }

    val port = portText.toIntOrNull()?.takeIf { it in 1024..65535 }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val notify: (String) -> Unit = { msg ->
        scope.launch { snackbarHostState.showSnackbar(msg) }
    }

    fun persistSettings(p: Int) {
        prefs.edit()
            .putInt(KEY_PORT, p)
            .putBoolean(KEY_ANONYMOUS, anonymous)
            .putBoolean(KEY_WRITABLE, writable)
            .putString(KEY_USER, username)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    fun start() {
        val p = port ?: run {
            notify(context.getString(R.string.ftp_port_invalid))
            return
        }
        try {
            persistSettings(p)
            // 匿名模式传空用户名：FtpServerManager 只有收到空用户名才注册 anonymous 用户
            FtpServerManager.start(
                vm.ftpRootDir().absolutePath, p,
                if (anonymous) "" else username,
                if (anonymous) "" else password,
                writable, ipv6Enabled = true
            )
            notify(context.getString(R.string.ftp_started, p))
        } catch (e: Exception) {
            notify(context.getString(R.string.ftp_start_fail, e.message ?: ""))
        }
    }

    /** 运行中修改设置后自动重启服务，立即生效 */
    fun restartIfRunning() {
        if (running) {
            FtpServerManager.stop()
            start()
        }
    }

    // 用户名/密码是否被聚焦过（用于忽略 onFocusChanged 首次进入时的初始 false）
    var credentialsTouched by remember { mutableStateOf(false) }

    /**
     * 用户名 / 密码在失焦时才落盘，运行中才重启：
     * 逐键重启会每敲一个字符重启一次 FTP；只落盘不重启则改了密码后服务仍用旧凭据。
     */
    fun commitCredentialsAndRestart() {
        prefs.edit()
            .putString(KEY_USER, username)
            .putString(KEY_PASSWORD, password)
            .apply()
        restartIfRunning()
    }

    fun stop() {
        FtpServerManager.stop()
        notify(context.getString(R.string.ftp_stopped))
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }, contentWindowInsets = WindowInsets(0.dp)) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            BackBar(stringResource(R.string.ftp_title), onBack)
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 12.dp)
            ) {
                Text(
                    stringResource(R.string.ftp_intro),
                    color = Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = McSpacing.Page)
                )

                McCard(title = stringResource(R.string.ftp_title)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(if (running) Mint else Muted, CircleShape)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (running) stringResource(R.string.ftp_status_running, port ?: DEFAULT_PORT)
                            else stringResource(R.string.ftp_status_stopped),
                            fontSize = 12.sp,
                            color = if (running) Mint else Muted,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.ftp_root_hint), color = Muted, fontSize = 10.sp)
                    if (running) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "ftp://$lanIp:${port ?: DEFAULT_PORT}/",
                            color = Indigo, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("FTP", "ftp://$lanIp:${port ?: DEFAULT_PORT}/"))
                                    Toast.makeText(context, R.string.mcp_copied, Toast.LENGTH_SHORT).show()
                                },
                                shape = McShape.ControlCompact,
                                modifier = Modifier.weight(1f)
                            ) { Text(stringResource(R.string.ftp_copy_address), fontSize = 11.sp) }
                            OutlinedButton(
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("FTP host", lanIp))
                                    Toast.makeText(context, R.string.mcp_copied, Toast.LENGTH_SHORT).show()
                                },
                                shape = McShape.ControlCompact,
                                modifier = Modifier.weight(1f)
                            ) { Text(stringResource(R.string.ftp_copy_host), fontSize = 11.sp) }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.ftp_host_hint, lanIp, port ?: DEFAULT_PORT),
                            color = Coral, fontSize = 10.sp
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { if (running) stop() else start() },
                        colors = ButtonDefaults.buttonColors(containerColor = if (running) Coral else Indigo),
                        shape = McShape.Control,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (running) stringResource(R.string.ftp_stop) else stringResource(R.string.ftp_start),
                            color = Color.White, fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                McCard(title = stringResource(R.string.ftp_settings_title)) {
                    SettingSwitchRow(
                        title = stringResource(R.string.ftp_anonymous),
                        hint = stringResource(R.string.ftp_anonymous_hint),
                        checked = anonymous,
                        enabled = true
                    ) {
                        anonymous = it
                        prefs.edit().putBoolean(KEY_ANONYMOUS, it).apply()
                        restartIfRunning()
                    }
                    // 开启匿名后醒目提示风险：无密码即可访问并修改服务器目录
                    if (anonymous) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.ftp_anonymous_risk),
                            color = Coral, fontSize = 10.sp, lineHeight = 14.sp
                        )
                    }
                    if (!anonymous) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text(stringResource(R.string.ftp_username)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { state ->
                                    // 仅在失焦时落盘并重启，避免逐键重启；忽略首次组合的初始 false
                                    if (state.isFocused) credentialsTouched = true
                                    else if (credentialsTouched) commitCredentialsAndRestart()
                                }
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text(stringResource(R.string.ftp_password)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { state ->
                                    // 与用户名行为一致：失焦时才落盘并重启，改动密码立即生效
                                    if (state.isFocused) credentialsTouched = true
                                    else if (credentialsTouched) commitCredentialsAndRestart()
                                }
                        )
                        if (running) {
                            Text(stringResource(R.string.ftp_running_lock), color = Muted, fontSize = 10.sp)
                        }
                    }
                    SettingSwitchRow(
                        title = stringResource(R.string.ftp_writable),
                        hint = stringResource(R.string.ftp_writable_hint),
                        checked = writable,
                        enabled = true
                    ) {
                        writable = it
                        prefs.edit().putBoolean(KEY_WRITABLE, it).apply()
                        restartIfRunning()
                    }
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                        label = { Text(stringResource(R.string.ftp_port)) },
                        isError = portText.isNotEmpty() && port == null,
                        singleLine = true,
                        enabled = !running,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (running) {
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.ftp_port_lock), color = Muted, fontSize = 10.sp)
                    }
                }

                Text(
                    stringResource(R.string.ftp_security_note),
                    color = Muted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = McSpacing.Page, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    hint: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(hint, fontSize = 11.sp, color = Muted)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
