package com.mineserve.mobile.server.tunnel.frpc

import android.content.Context
import com.mineserve.mobile.data.TunnelStatus
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * frpc 进程管理器 —— 把引擎作为**独立子进程**拉起并管理其生命周期。
 *
 * ## 启动链路
 *
 * ```
 * [Java] ProcessBuilder(nativeLibraryDir/libfrpcloader.so, <config 路径>)
 *           │  env: MINESERVE_FRPC_LIB = filesDir/frpc/libfrpc.so
 *           │  cwd: 配置所在目录
 *           ▼
 * [C]    libfrpcloader.so  → dlopen(MINESERVE_FRPC_LIB) → dlsym("RunFrpc") → 调用
 *           ▼
 * [Go]   libfrpc.so 内的 RunFrpc(configPath)，阻塞运行
 * ```
 *
 * 为什么不直接执行 `libfrpc.so`：API 29+ 起 SELinux 拒绝在 app 数据目录
 * （`app_data_file`）上 `execute_no_trans`，而 `nativeLibraryDir` 是允许执行 ELF 的
 * 特例。loader 随 APK 落在 `lib/`，引擎放在可写、可热更的数据目录，两个限制同时满足。
 *
 * ## 停止
 *
 * [stop] 用 `Process.destroy()` 发 SIGTERM；引擎内部用 `signal.Notify` 监听 SIGINT/
 * SIGTERM 并执行 GracefulClose，所以这是优雅停止。[forceStop] 发 SIGKILL 兜底。
 *
 * 应用级单例：同一时刻只跑一条隧道，且需要在配置变更/界面重建后仍能拿到当前进程。
 */
class FrpcProcessManager private constructor(private val appContext: Context) {

    companion object {
        private const val MAX_LOG_LINES = 2000

        /**
         * frpc 上报「真的通了」的标志。
         *
         * frpc 启动是多阶段的，进程活着不等于隧道可用：登录失败、节点拒绝、token
         * 错误时进程都可能还在跑甚至反复重试。所以用输出内容判定，而不是「启动后
         * 等 N 秒」这种猜法 —— 后者会把没通的隧道报成成功。
         *
         * 三个标志来自 frpc 二进制的实际文案（0.61+ 均如此，见 conf 与
         * client/service.go）：
         * - `login to server success, get run id [xxx]` —— 与 frps 建连并登录成功
         * - `proxy added: xxx`                        —— frps 已接受代理注册
         * - `[xxx] start proxy success`               —— 代理真正开始转发（最终成功）
         *
         * 命中**任一**即视为可用：老版本可能只打前两条，新版本打第三条；只认一条的话
         * 换个 frpc 版本就会永久卡在「启动中」。
         */
        val TUNNEL_READY_PATTERN = Regex(
            """login to server success|proxy added:|start proxy success""",
            RegexOption.IGNORE_CASE
        )

        /** 日志里的 ANSI 转义序列（CSI，如颜色码）；缓存与展示前剔除。 */
        private val ANSI_PATTERN = Regex("\\x1B\\[[0-?]*[ -/]*[@-~]")

        @Volatile
        private var instance: FrpcProcessManager? = null

        fun getInstance(context: Context): FrpcProcessManager =
            instance ?: synchronized(this) {
                instance ?: FrpcProcessManager(context.applicationContext).also { instance = it }
            }
    }

    private val logLock = Any()
    private val logBuffer = ArrayDeque<String>()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var logSink: ((String) -> Unit)? = null

    /**
     * 各后端登记的状态回调，键为后端自身。
     *
     * 用表而不是单字段：三个 frp 后端共用本管理器，后挂的不能覆盖先挂的
     * （否则发起启动的后端收不到状态，界面永远停在「启动中」）。见 [attach]。
     */
    private val stateSinks = mutableMapOf<Any, (TunnelStatus?, Int?) -> Unit>()
    private val stateSinksLock = Any()

    /** 当前进程的归属后端 —— 状态只投递给它。见 [attach]。 */
    @Volatile
    private var stateOwner: Any? = null

    /** 本次启动是否由 [owner] 持有 —— 供后端自查。 */
    fun isStateOwner(owner: Any): Boolean = stateOwner === owner

    @Volatile
    private var currentStatus: TunnelStatus? = null

    @Volatile
    private var stopping = false

    /** 子进程 PID；未运行时返回 -1。 */
    val pid: Int
        get() {
            val p = process ?: return -1
            return try {
                val field = p.javaClass.getDeclaredField("pid")
                field.isAccessible = true
                field.getInt(p)
            } catch (_: Exception) {
                -1
            }
        }

    val isRunning: Boolean
        get() = process?.isAlive == true

    /** 历史日志快照（供界面重建时回放）。 */
    fun logSnapshot(): List<String> = synchronized(logLock) { logBuffer.toList() }

    /**
     * 注入日志与状态回调。
     *
     * 设置时立即回放历史日志与当前状态，使界面重建（旋转、切页）后能恢复现场，
     * 而不是空白一条等到下次输出。
     */
    /**
     * 挂接日志回调，并为本后端登记状态回调。
     *
     * ## 为什么状态回调按「所有者」记账
     *
     * 三个 frp 系后端（frp / SakuraFrp / ME Frp）共用这一个管理器实例，但每次只有
     * 一个真正持有进程。曾经的写法是单一 `stateSink` 字段，`attach` 直接覆盖 ——
     * `TunnelManager` 初始化时依次 attach 三个后端，**最后注册的那个（MeFrp）永久
     * 占据回调位**。
     *
     * 后果极隐蔽：跑自建 frp 时，`Running` 与退出事件全被送进
     * `MeFrpBackend.onProcessState`，状态写进 MeFrpBackend 的 StateFlow；而 UI 订阅
     * 的是 FrpBackend 的状态 —— 它永远停在 Starting。表现就是日志里明明已经
     * `start proxy success`，界面却一直卡在「启动中」。
     *
     * 所以回调登记进 [stateSinks] 表，投递时只发给 `stateOwner`。
     *
     * @param owner 后端标识，传后端自身。
     */
    fun attach(
        owner: Any,
        logSink: ((String) -> Unit)?,
        stateSink: ((TunnelStatus?, Int?) -> Unit)? = null
    ) {
        this.logSink = logSink
        if (stateSink != null) {
            synchronized(stateSinksLock) {
                stateSinks[owner] = stateSink
                // 首次登记的后端自动成为归属者；后续启动会显式改认领（见 start）。
                if (stateOwner == null) stateOwner = owner
            }
        }
        if (logSink == null) return
        logSnapshot().forEach { logSink(it) }
    }

    /**
     * 把状态投递给**归属者**的回调。
     *
     * 只发一家：进程只属于一个后端，把状态广播给所有后端会让没在跑的那几个
     * 也跟着变 Running/Failed，界面上出现多条隧道"一起启动"的假象。
     */
    private fun dispatchState(status: TunnelStatus?, exitCode: Int?) {
        val (owner, sink) = synchronized(stateSinksLock) { stateOwner to stateSinks[stateOwner] }
        if (sink == null) {
            emitLog("[frpc] 进程状态变更（${status ?: "exit"}），但没有后端认领本进程，界面不会更新")
            return
        }
        sink.invoke(status, exitCode)
    }

    fun clearLog() {
        synchronized(logLock) { logBuffer.clear() }
    }

    /**
     * 启动隧道。
     *
     * 含文件与进程操作，**必须在后台线程调用**。
     *
     * @param configFile 已写好的 frpc 配置文件（toml/yaml/json）绝对路径。
     * @param runtime    已就绪的引擎（调用方负责确保 [FrpcRuntime.isInstalled]）。
     * @param extraEnv   叠加到子进程环境上的额外变量（平台专用，可为空）。
     * @throws IllegalStateException 已在运行、配置或引擎缺失、启动器缺失。
     */
    @Synchronized
    fun start(
        configFile: File,
        runtime: FrpcRuntime,
        extraEnv: Map<String, String> = emptyMap(),
        owner: Any? = null
    ) {
        if (isRunning) throw IllegalStateException("隧道已在运行，请先停止")

        // 本次启动的所有者：进程状态只投递给它的回调。
        // 不在这里认领的话，共用本管理器的三个 frp 后端里会有一个（初始化时最后
        // attach 的那个）永久劫持状态回调，发起启动的后端反而收不到任何通知。
        if (owner != null) stateOwner = owner

        if (!configFile.isFile) {
            throw IllegalStateException("配置文件不存在：${configFile.absolutePath}")
        }
        if (!runtime.isInstalled()) {
            throw IllegalStateException(
                "frpc 引擎未安装。请先在「依赖管理」导入 libfrpc.so 引擎文件。"
            )
        }
        val loader = runtime.loaderFile
        if (loader == null || !loader.exists()) {
            throw IllegalStateException(
                "未找到 ${FrpcRuntime.LOADER_NAME}，它应随 APK 打包在 lib 目录中。" +
                    "若使用非官方构建，请确认已启用 CMake 构建（app/src/main/cpp/CMakeLists.txt）。"
            )
        }

        stopExistingProcess()

        // 工作目录取配置所在目录，使配置里的相对路径（store、日志文件等）有确定基准。
        val workDir = configFile.parentFile ?: appContext.filesDir
        workDir.mkdirs()

        val pb = ProcessBuilder(loader.absolutePath, configFile.absolutePath)
        pb.directory(workDir)
        // stderr 合并进 stdout：引擎启动早期的失败（dlopen/dlsym 报错）走的是 stderr，
        // 分开读会漏掉——而那些恰好是最需要看到的错误。
        pb.redirectErrorStream(true)

        val env = pb.environment()
        env["MINESERVE_FRPC_LIB"] = runtime.engineFile.absolutePath
        env["LD_LIBRARY_PATH"] = "${runtime.engineDir.absolutePath}:${loader.parentFile?.absolutePath}"
        env["HOME"] = workDir.absolutePath
        env["TMPDIR"] = appContext.cacheDir.absolutePath
        env["LANG"] = "en_US.UTF-8"
        env.putAll(extraEnv)

        stopping = false
        val p = pb.start()
        process = p
        currentStatus = TunnelStatus.Starting
        emitLog("[frpc] 引擎 ${runtime.installedVersion ?: "(版本未知)"}，配置 ${configFile.name}")
        dispatchState(TunnelStatus.Starting, null)

        thread(name = "frpc-stdout") {
            try {
                BufferedReader(InputStreamReader(p.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        val clean = ANSI_PATTERN.replace(line, "")
                        emitLog(clean)
                        // 任一成功标志命中即切 Running；已经是 Running 就不再重复判定。
                        if (currentStatus == TunnelStatus.Starting &&
                            TUNNEL_READY_PATTERN.containsMatchIn(clean)
                        ) {
                            currentStatus = TunnelStatus.Running
                            dispatchState(TunnelStatus.Running, null)
                        }
                        line = reader.readLine()
                    }
                }
            } catch (e: Exception) {
                emitLog("[frpc] 读取输出失败：${e.message}")
            }

            val code = try {
                p.waitFor()
            } catch (_: InterruptedException) {
                -1
            }
            synchronized(this) {
                // 只在仍是当前进程时才清状态：stop() 后紧接着 start() 的新进程
                // 不能被旧进程的退出回调误伤。
                if (process === p) {
                    process = null
                    currentStatus = null
                    dispatchState(null, code)
                }
            }
        }
    }

    /** 优雅停止：发 SIGTERM，触发引擎内部的 GracefulClose。 */
    fun stop() {
        stopping = true
        process?.destroy()
    }

    /** 强制结束：发 SIGKILL。用于优雅停止超时后的兜底。 */
    fun forceStop() {
        stopping = true
        process?.destroyForcibly()
    }

    /**
     * 优雅停止并等待退出，超时后强杀。
     *
     * 单独给一个「等」的入口，是为了让调用方能确保端口与连接真的释放了再继续 ——
     * `destroy()` 只是发信号，进程可能还在收尾。切换隧道时若不等待，新旧进程会
     * 短暂同时持有同一个本地端口。
     */
    fun stopAndWait(timeoutMs: Long = 3000) {
        val p = process ?: return
        stop()
        try {
            if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                emitLog("[frpc] 优雅停止超时，强制结束")
                p.destroyForcibly()
                p.waitFor(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun stopExistingProcess() {
        val existing = process ?: return
        if (existing.isAlive) {
            emitLog("[frpc] 停止上一个进程")
            existing.destroy()
            try {
                existing.waitFor(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        process = null
    }

    private fun emitLog(line: String) {
        synchronized(logLock) {
            logBuffer.addLast(line)
            while (logBuffer.size > MAX_LOG_LINES) logBuffer.removeFirst()
        }
        logSink?.invoke(line)
    }
}
