package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class LanAddressDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    private val realServerPort: () -> Int,
    private val reportMessage: (String) -> Unit,
    private val reportError: (String) -> Unit
) {
    private val _lanIp = MutableStateFlow("--")
    val lanIp: StateFlow<String> = _lanIp.asStateFlow()

    fun refreshLanIp() {
        scope.launch(Dispatchers.IO) {
            _lanIp.value = queryLanIp()
        }
    }

    private fun queryLanIp(): String {
        return try {
            NetworkInterface.getNetworkInterfaces()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress }
                .map { it.hostAddress ?: "" }
                .firstOrNull { it.isNotEmpty() }
                ?: "--"
        } catch (e: Exception) {
            "--"
        }
    }

    fun copyServerAddress(context: Context) {
        val ip = _lanIp.value
        // 端口取真源（服务端配置文件里的 server-port），不读 config.localPort ——
        // 后者在配置文件被外部改动时可能停在旧值。
        // 用 [realServerPort] 而不是 [serverPort] 这个 StateFlow：复制出去的是要发给别人的
        // 地址，不能受订阅状态影响而拿到缓存里的旧端口。
        val port = realServerPort()
        val address = if (ip == "--") {
            reportError(str(R.string.s187))
            return
        } else {
            "$ip:$port"
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MC Server", address))
        reportMessage(str(R.string.s188, address))
    }

    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)
}
