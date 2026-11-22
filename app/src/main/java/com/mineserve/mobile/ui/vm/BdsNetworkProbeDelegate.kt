package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class BdsNetworkProbeDelegate(
    private val context: Context,
    private val scope: CoroutineScope,
    private val activeDirName: () -> String?,
    private val runProbe: suspend (String?) -> List<String>,
    private val emitLog: (String) -> Unit,
    private val reportError: (String) -> Unit,
    private val reportMessage: (String) -> Unit
) {
    private val _bdsProbeRunning = MutableStateFlow(false)
    val bdsProbeRunning: StateFlow<Boolean> = _bdsProbeRunning.asStateFlow()

    fun probeBdsNetwork() {
        if (_bdsProbeRunning.value) return
        _bdsProbeRunning.value = true
        scope.launch {
            try {
                emitLog("[probe] 开始容器内网络自检（在线模式 / 正版验证用）")
                val lines = runProbe(activeDirName())
                lines.forEach { emitLog("[probe] $it") }
                if (lines.any { it.startsWith("CA 修复: 已从系统根证书补入") }) {
                    emitLog("[probe] 根证书已补好，请直接重新启动服务器验证")
                }
                reportMessage(bdsProbeConclusion(lines))
            } catch (e: Exception) {
                emitLog("[probe] 自检失败：${e.message}")
                reportError(str(R.string.bds_env_probe_failed, e.message ?: "?"))
            } finally {
                _bdsProbeRunning.value = false
            }
        }
    }

    /** 把自检输出压成一句人话（判据只有「哪一段先断」） */
    private fun bdsProbeConclusion(lines: List<String>): String {
        val dnsFail = lines.filter { it.startsWith("DNS 失败") }
        val tcpFail = lines.filter { it.startsWith("TCP 失败") }
        val tlsFail = lines.filter { it.startsWith("TLS 失败") }
        val ok = lines.count { it.startsWith("TCP ok") }
        return when {
            lines.isEmpty() || lines.any { it.startsWith("容器未就绪") || it.startsWith("proot 不可用") } ->
                str(R.string.bds_env_probe_need_env)
            dnsFail.isNotEmpty() ->
                str(R.string.bds_env_probe_dns_fail, dnsFail.size)
            tcpFail.isNotEmpty() && ok > 0 ->
                str(R.string.bds_env_probe_partial, ok, tcpFail.size)
            tcpFail.isNotEmpty() ->
                str(R.string.bds_env_probe_tcp_fail, tcpFail.size)
            // TCP 通但 TLS 断：典型 SNI 拦截，必须让代理接管 TCP
            tlsFail.isNotEmpty() ->
                str(R.string.bds_env_probe_tls_fail, tlsFail.size)
            lines.any { it.contains("CA 证书: 缺失") } ->
                str(R.string.bds_env_probe_no_ca)
            else -> str(R.string.bds_env_probe_ok)
        }
    }

    private fun str(id: Int, vararg args: Any?): String =
        context.getString(id, *args)
}
