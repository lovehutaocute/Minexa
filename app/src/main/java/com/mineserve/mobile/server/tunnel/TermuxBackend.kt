package com.mineserve.mobile.server.tunnel

import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.TunnelState
import com.mineserve.mobile.data.TunnelStatus
import com.mineserve.mobile.data.TunnelType
import com.mineserve.mobile.runtime.TermuxRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Termux 进程隧道后端基类。
 * 封装进程管理、stdout 解析、公网地址提取、超时看门狗等通用逻辑。
 *
 * 子类只需实现 [buildArgs] 和 [parsePublicUrl]。
 */
abstract class TermuxBackend(
    protected val termux: TermuxRuntime,
    protected val binaryManager: BinaryManager,
    override val type: TunnelType
) : TunnelBackend {

    protected val _state = MutableStateFlow(TunnelState())
    override val state: StateFlow<TunnelState> = _state.asStateFlow()

    protected var log: (String) -> Unit = {}

    @Volatile
    private var process: Process? = null

    @Volatile
    private var isRunning = false

    /** 看门狗超时 ms */
    protected open val watchdogTimeoutMs: Long = 30_000

    /** 诊断时保留的进程输出最大行数（环形缓冲，防止长会话内存增长） */
    private companion object {
        const val DIAGNOSTIC_LINES = 200
    }

    /** 是否通过 proot 运行（让 /etc/resolv.conf → PREFIX/etc/resolv.conf） */
    protected open val useProot: Boolean = false

    override fun attachLog(logger: (String) -> Unit) {
        log = logger
    }

    override suspend fun start(config: McConfig): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            updateState(TunnelStatus.Starting, errorMessage = "正在准备 ${type.displayName}...")

            // 1. 确保二进制可用
            val binary = ensureBinary()
            if (binary == null) {
                val msg = "${type.displayName} 二进制不可用"
                log(msg)
                updateState(TunnelStatus.Failed, errorMessage = msg)
                return@withContext Result.failure(RuntimeException(msg))
            }

            // 2. 修复 Android DNS（写入 resolv.conf，否则 Go 程序无法解析域名）
            fixDns()

            // 3. 构建参数
            val args = buildArgs(config, binary)
            val env = buildEnv(config)

            // 4. 启动进程（useProot 时用 proot 包装，让 /etc/resolv.conf 映射到 PREFIX/etc/）
            val (cmd, displayedCmd) = if (useProot) {
                val prefix = termux.installer.rootDir.absolutePath
                val prootArgs = arrayOf("proot", "-r", prefix, "-b", "/dev", "-b", "/proc", "-b", "/sys", binary, *args.toTypedArray())
                Pair(prootArgs, "proot -r ... $binary ${args.joinToString(" ")}")
            } else {
                Pair(arrayOf(binary, *args.toTypedArray()), "$binary ${args.joinToString(" ")}")
            }
            log("启动命令: $displayedCmd")

            val proc = termux.execRaw("tunnel", *cmd, env = env)
            process = proc
            isRunning = true

            // 5. 子类进程启动回调（如 frp 直接设置公网地址）
            onProcessStarted(config)

            // 6. 启动 stdout 解析线程
            startMonitorThread(proc, config)

            Result.success(Unit)
        } catch (e: Exception) {
            log("启动失败: ${e.message}")
            updateState(TunnelStatus.Failed, errorMessage = e.message ?: "未知错误")
            Result.failure(e)
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        isRunning = false
        process?.let {
            if (it.isAlive) it.destroyForcibly()
        }
        process = null
        // 兜底 pkill
        killProcess()
        updateState(TunnelStatus.Stopped)
        log("${type.displayName} 已停止")
    }

    // ── 子类需实现 ────────────────────────────────────────────

    /** 确保二进制可用，返回绝对路径或 null */
    protected abstract suspend fun ensureBinary(): String?

    /** 构建启动参数 */
    protected abstract fun buildArgs(config: McConfig, binary: String): List<String>

    /** 构建环境变量 */
    protected open fun buildEnv(config: McConfig): Map<String, String> = emptyMap()

    /** 从 stdout 行中提取公网 URL，返回 null 表示未匹配 */
    protected abstract fun parsePublicUrl(line: String): String?

    /** 进程成功启动后的回调（在 stdout 监控线程启动前），子类可覆盖 */
    protected open fun onProcessStarted(config: McConfig) {}

    /** 兜底 pkill 进程名 */
    protected abstract fun killProcess()

    // ── Android 环境修复 ──────────────────────────────────────

    /**
     * 写入 DNS 配置 + 创建 /tmp 目录。
     *
     * 用 getprop 读取 Android 系统真实 DNS（WiFi/4G），
     * 写入所有可能路径，让 Go 程序无论什么上下文都能解析域名。
     */
    private fun fixDns() {
        val prefix = termux.installer.rootDir.absolutePath

        // 用 getprop 获取 Android 真实 DNS，失败则用 8.8.8.8
        val androidDns1 = runCatching {
            Runtime.getRuntime().exec(arrayOf("/system/bin/getprop", "net.dns1"))
                .inputStream.bufferedReader().readText().trim()
        }.getOrDefault("")
        val androidDns2 = runCatching {
            Runtime.getRuntime().exec(arrayOf("/system/bin/getprop", "net.dns2"))
                .inputStream.bufferedReader().readText().trim()
        }.getOrDefault("")

        val nameservers = mutableListOf<String>()
        if (androidDns1.isNotBlank()) nameservers.add("nameserver $androidDns1")
        if (androidDns2.isNotBlank()) nameservers.add("nameserver $androidDns2")
        if (nameservers.isEmpty()) {
            nameservers.add("nameserver 8.8.8.8")
            nameservers.add("nameserver 8.8.4.4")
        }
        val content = nameservers.joinToString("\n") + "\n"

        // 写入所有可能的 DNS 解析路径
        val dnsPaths = listOf(
            "$prefix/etc/resolv.conf",
            "/data/data/com.termux/files/usr/etc/resolv.conf"
        )
        dnsPaths.forEach { path ->
            try {
                val f = java.io.File(path)
                f.parentFile?.mkdirs()
                f.writeText(content)
            } catch (_: Exception) {}
        }

        // 创建 /tmp 目录
        listOf("$prefix/tmp", "/data/data/com.termux/files/usr/tmp").forEach { path ->
            try { java.io.File(path).mkdirs() } catch (_: Exception) {}
        }
    }

    // ── 公共方法 ──────────────────────────────────────────────

    protected fun updateState(status: TunnelStatus, publicUrl: String = _state.value.publicUrl, errorMessage: String = "") {
        _state.value = TunnelState(
            isRunning = status == TunnelStatus.Running,
            publicUrl = publicUrl,
            status = status,
            errorMessage = errorMessage,
            activeType = type
        )
    }

    // ── stdout 监控线程 ───────────────────────────────────────

    private fun startMonitorThread(proc: Process, config: McConfig) {
        Thread({
            try {
                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                // 环形缓冲：frpc 反复重连时输出会持续刷屏，只保留最后 DIAGNOSTIC_LINES 行
                // 供 diagnoseFailure 使用，避免长会话内存单调增长。
                val outputLines = ArrayDeque<String>()
                var gotUrl = false

                var line: String? = reader.readLine()
                while (line != null) {
                    log(line)
                    outputLines.addLast(line)
                    if (outputLines.size > DIAGNOSTIC_LINES) outputLines.removeFirst()

                    if (!gotUrl) {
                        val url = parsePublicUrl(line)
                        if (url != null && url.isNotBlank()) {
                            gotUrl = true
                            updateState(TunnelStatus.Running, publicUrl = url)
                            log("获取到公网地址: $url")
                        }
                    }
                    line = reader.readLine()
                }

                // 进程退出
                val exitCode = proc.waitFor()
                if (isRunning) {
                    val errMsg = if (!gotUrl) {
                        diagnoseFailure(exitCode, outputLines.joinToString("\n"))
                    } else {
                        "隧道进程已退出 (code=$exitCode)"
                    }
                    updateState(TunnelStatus.Failed, errorMessage = errMsg)
                    log(errMsg)
                }
            } catch (e: Exception) {
                if (isRunning) {
                    updateState(TunnelStatus.Failed, errorMessage = "监控异常: ${e.message}")
                }
            }
        }, "tunnel-${type.name}").start()

        // 超时看门狗
        Thread({
            try {
                Thread.sleep(watchdogTimeoutMs)
                if (proc.isAlive && _state.value.status == TunnelStatus.Starting) {
                    log("进程 ${watchdogTimeoutMs}ms 内未获取到公网地址，判定超时")
                    proc.destroyForcibly()
                    updateState(TunnelStatus.Failed, errorMessage = "启动超时，可能网络不通或配置有误")
                }
            } catch (_: Exception) {}
        }, "tunnel-watchdog-${type.name}").start()
    }

    /** 子类可覆盖以提供更详细的错误诊断 */
    protected open fun diagnoseFailure(exitCode: Int, output: String): String {
        return "隧道进程退出 (code=$exitCode)"
    }
}
