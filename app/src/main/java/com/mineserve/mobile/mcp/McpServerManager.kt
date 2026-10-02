package com.mineserve.mobile.mcp

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.mineserve.mobile.BuildConfig
import com.mineserve.mobile.McApplication
import com.mineserve.mobile.R
import com.mineserve.mobile.data.InstalledCore
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.ServerCore
import com.mineserve.mobile.data.ServerRepository
import com.mineserve.mobile.data.StartupPhase
import com.mineserve.mobile.server.McServerController
import com.mineserve.mobile.server.PluginManager
import com.mineserve.mobile.service.McForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 内嵌 MCP（Model Context Protocol）服务器管理器：
 *  - 收集 McConfig，按 (mcpEnabled, mcpPort, mcpToken, mcpAllowLan) 变化自动启停/重启 HTTP 服务
 *  - Streamable HTTP 传输：单端点 POST /mcp（无状态），通知返回 202，GET/DELETE 返回 405
 *  - 所有请求（OPTIONS 预检除外）需 Bearer 令牌鉴权
 *  - 带 Origin 头的请求一律拒绝：MCP 客户端是原生程序不带该头，而浏览器/DNS rebinding 必带
 *  - 工具执行桥接到 McServerController / ServerRepository / TermuxRuntime
 */
class McpServerManager(
    private val app: McApplication,
    private val repo: ServerRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val controller = McServerController(app.termuxRuntime, repo)
    private val pluginManager = PluginManager(app.termuxRuntime, app)

    /** 长任务注册表：安装运行时、下载核心、还原快照等异步任务都在这里排队执行 */
    private val jobs = McpJobRegistry()

    /** 新增的「开发/测试」工具集（环境依赖、核心安装、配置、快照、插件、诊断、测试辅助） */
    private val tools = McpToolRunner(app, repo, controller, pluginManager, jobs)

    /** 串行化 Termux 一次性执行（与 UI 终端共享 interactiveInput 全局句柄） */
    private val termuxMutex = Mutex()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    /** 绑定失败等原因，供 UI 展示；null 表示正常 */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val lock = Any()
    private var httpServer: McpHttpServer? = null
    private var servingSpec: ServingSpec? = null

    // allowLan 纳入 spec：一旦用户在设置里切换「允许局域网访问」，
    // 与端口变更同等对待 —— 下一轮 applyConfig 发现 spec 不同即自动重启服务生效。
    private data class ServingSpec(val enabled: Boolean, val port: Int, val token: String, val allowLan: Boolean)

    /** 在应用启动时调用：开始观察配置并按需启停 */
    fun start() {
        scope.launch {
            repo.configFlow.collect { config -> applyConfig(config) }
        }
    }

    /** 进程退出前调用（一般无需手动调用） */
    fun shutdown() {
        scope.cancel()
        synchronized(lock) {
            httpServer?.stop()
            httpServer = null
            servingSpec = null
            _isRunning.value = false
        }
    }

    private suspend fun applyConfig(config: McConfig) {
        var token = config.mcpToken
        if (config.mcpEnabled && token.isBlank()) {
            // 首次启用：生成令牌并原子回写。这里必须基于权威内存态做字段级更新：
            // 传 `config.copy(...)` 这种「整份快照」会把并发期间用户改的其它字段一起覆盖掉
            // （典型症状：MCP 令牌每次改设置都被重新生成、客户端全部 401）。
            val generated = generateToken()
            token = repo.updateAndSaveConfig { current ->
                // 双检：并发下可能已有人写入过令牌，避免无谓地重新生成
                if (current.mcpToken.isBlank()) current.copy(mcpToken = generated) else current
            }.mcpToken
        }
        val spec = ServingSpec(config.mcpEnabled, config.mcpPort, token, config.mcpAllowLan)
        synchronized(lock) {
            if (spec == servingSpec) return
            stopLocked()
            if (!spec.enabled) return
            val server = McpHttpServer(spec.port) { request -> onRequest(request, spec.token) }
            if (server.start(spec.allowLan)) {
                httpServer = server
                servingSpec = spec
                _isRunning.value = true
                _lastError.value = null
                val bindAddr = if (spec.allowLan) "0.0.0.0" else "127.0.0.1"
                Log.i(TAG, "MCP server listening on $bindAddr:${spec.port} (lan=${spec.allowLan})")
            } else {
                _lastError.value = server.lastBindError ?: "bind failed"
                Log.e(TAG, "MCP server bind failed: ${_lastError.value}")
            }
        }
    }

    private fun stopLocked() {
        httpServer?.stop()
        httpServer = null
        servingSpec = null
        _isRunning.value = false
    }

    // ── HTTP 处理 ─────────────────────────────────────────────

    private fun onRequest(request: McpHttpServer.Request, token: String): McpHttpServer.Response {
        // 拒绝一切带 Origin 头的请求：MCP 客户端（Claude Desktop、Cursor 等）是原生程序，
        // 不会发 Origin；而浏览器页面（含 DNS rebinding 构造的同源页面）发 fetch/XHR 时
        // 必带 Origin。这比"收紧 CORS 白名单"更彻底 —— 直接从来源上排除网页调用，
        // 无需依赖浏览器是否正确执行 CORS。OPTIONS 预检同样适用（浏览器预检也带 Origin）。
        if (request.headers.containsKey("origin")) {
            return McpHttpServer.Response.json(
                403, "{\"error\":\"requests with an Origin header are not accepted\"}"
            )
        }
        val path = request.path.substringBefore('?')
        if (request.method == "OPTIONS") {
            return McpHttpServer.Response(
                204, McpHttpServer.Response.reasonFor(204), null, ByteArray(0), corsHeaders
            )
        }
        if (path != MCP_PATH) {
            return McpHttpServer.Response.json(404, "{\"error\":\"not found\"}", corsHeaders)
        }
        if (!isAuthorized(request, token)) {
            return McpHttpServer.Response.json(
                401, "{\"error\":\"unauthorized\"}",
                corsHeaders + listOf("WWW-Authenticate" to "Bearer")
            )
        }
        return when (request.method) {
            "POST" -> handlePost(request)
            "GET", "DELETE" -> methodNotAllowed()
            else -> methodNotAllowed()
        }
    }

    /** 常量时间比较，避免令牌时序泄露 */
    private fun isAuthorized(request: McpHttpServer.Request, token: String): Boolean {
        if (token.isEmpty()) return false
        val header = request.headers["authorization"]?.trim() ?: return false
        if (!header.startsWith("Bearer ", ignoreCase = true)) return false
        val presented = header.substring("Bearer ".length).trim()
        if (presented.isEmpty()) return false
        return MessageDigest.isEqual(presented.encodeToByteArray(), token.encodeToByteArray())
    }

    private fun handlePost(request: McpHttpServer.Request): McpHttpServer.Response {
        val text = request.body.toString(Charsets.UTF_8)
        return when (val outcome = McpProtocol.handle(text, BuildConfig.VERSION_NAME, ::executeTool)) {
            is McpOutcome.Notification ->
                McpHttpServer.Response(202, McpHttpServer.Response.reasonFor(202), null, ByteArray(0), corsHeaders)
            is McpOutcome.Response ->
                McpHttpServer.Response(
                    200, McpHttpServer.Response.reasonFor(200), "application/json; charset=utf-8",
                    outcome.body.toString().encodeToByteArray(), corsHeaders
                )
        }
    }

    private fun methodNotAllowed(): McpHttpServer.Response =
        McpHttpServer.Response.json(
            405, "{\"error\":\"method not allowed\"}",
            corsHeaders + listOf(
                "Allow" to "POST, OPTIONS",
                "Access-Control-Allow-Methods" to "POST, OPTIONS"
            )
        )

    // 刻意不再返回 Access-Control-Allow-Origin（尤其 "*"）：MCP 客户端不依赖 CORS，
    // 而 `*` 会让任意网页的来源通过浏览器校验。去掉后浏览器默认拒绝读取响应；
    // 来源层面的拦截另见 onRequest 对 Origin 头的直接拒绝。
    private val corsHeaders = listOf(
        "Access-Control-Allow-Methods" to "POST, OPTIONS",
        "Access-Control-Allow-Headers" to "Content-Type, Authorization, Mcp-Session-Id, Mcp-Protocol-Version, Last-Event-ID",
        "Access-Control-Expose-Headers" to "Mcp-Session-Id",
        "Access-Control-Max-Age" to "86400"
    )

    // ── 工具实现 ───────────────────────────────────────────────

    /**
     * 工具执行入口：HTTP 线程内阻塞执行。
     * 各工具自行控制耗时上限（Termux/Modrinth 有独立超时），不再套全局超时。
     */
    private fun executeTool(name: String, args: JsonObject?): McpToolResult = try {
        runBlocking { callTool(name, args) }
    } catch (e: Exception) {
        McpToolResult("Tool execution failed: ${e.message ?: e.javaClass.simpleName}", isError = true)
    } catch (e: OutOfMemoryError) {
        // OutOfMemoryError 是 Error 而非 Exception，上面的 catch 抓不到。
        // 若不单独兜底，某次工具（读大文件/解压）撑爆堆时线程会直接终止、进程可能被系统杀掉，
        // 客户端只会看到连接断开。这里转成一条可读的工具错误，让调用方知道是本次操作 OOM。
        Log.e(TAG, "tool '$name' ran out of memory", e)
        McpToolResult(
            "Tool execution ran out of memory; the operation was aborted. " +
                "Try a smaller file or a more limited range.",
            isError = true
        )
    }

    private suspend fun callTool(name: String, args: JsonObject?): McpToolResult = when (name) {
        "get_server_status" -> toolServerStatus()
        "list_servers" -> toolListServers()
        "select_server" -> toolSelectServer(args)
        "start_server" -> toolStartServer(args)
        "stop_server" -> toolStopServer()
        "send_command" -> toolSendCommand(args)
        "get_console_logs" -> toolConsoleLogs(args)
        "run_termux_command" -> toolRunTermux(args)
        "list_files" -> toolListFiles(args)
        "read_file" -> toolReadFile(args)
        "write_file" -> toolWriteFile(args)
        "delete_file" -> toolDeleteFile(args)
        "rename_file" -> toolRenameFile(args)
        "make_dir" -> toolMakeDir(args)
        "upload_file" -> toolUploadFile(args)
        "extract_archive" -> toolExtractArchive(args)
        "import_server" -> toolImportServer(args)
        "list_mods" -> toolListMods(args)
        "search_mods" -> toolSearchMods(args)
        "install_mod" -> toolInstallMod(args)
        // 运行环境/依赖、核心安装、配置、快照、插件、日志诊断、自动化测试辅助等工具
        // 都在 McpToolRunner 里；它返回 null 表示这个名字不归它管。
        else -> tools.call(name, args) ?: McpToolResult("Unknown tool: $name", isError = true)
    }

    private suspend fun toolServerStatus(): McpToolResult {
        val config = repo.configFlow.first()
        val state = repo.serverState.value
        val active = config.installedCores.firstOrNull { it.name == config.activeCoreName }
        val json = buildJsonObject {
            put("isRunning", state.isRunning)
            put("startupPhase", state.startupPhase.name)
            put("playersOnline", state.onlinePlayers)
            put("maxPlayers", state.maxPlayers)
            put("tps", state.tps)
            put("usedMemoryMb", state.usedMemoryMb)
            put("maxMemoryMb", state.maxMemoryMb)
            state.cpuPercent?.let { put("cpuPercent", it) }
            if (state.isRunning && state.runningSinceMs > 0) {
                put("uptimeSeconds", (SystemClock.elapsedRealtime() - state.runningSinceMs) / 1000L)
            }
            put("localPort", config.localPort)
            active?.let {
                put("activeServer", buildJsonObject {
                    put("name", it.name)
                    put("core", it.core.name)
                    put("version", it.version)
                })
            }
        }
        return McpToolResult(json.toString())
    }

    private suspend fun toolListServers(): McpToolResult {
        val config = repo.configFlow.first()
        val json = kotlinx.serialization.json.buildJsonArray {
            config.installedCores.forEach { core ->
                add(buildJsonObject {
                    put("name", core.name)
                    put("dirName", core.dirName)
                    put("core", core.core.name)
                    put("version", core.version)
                    put("isActive", core.name == config.activeCoreName)
                })
            }
        }
        return McpToolResult(json.toString())
    }

    /**
     * 解析 server 参数（名称或目录名，不区分大小写）；缺省用活动服务器。
     * 语义集中在 [McpServers] 里，新增工具共用同一份实现。未找到抛 IllegalArgumentException。
     */
    private suspend fun requireServer(args: JsonObject?): Pair<McConfig, InstalledCore> =
        McpServers.resolve(repo, args)

    private suspend fun toolSelectServer(args: JsonObject?): McpToolResult {
        val wanted = stringArg(args, "server")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return McpToolResult("Missing required argument: server (see list_servers)", isError = true)
        val config = repo.configFlow.first()
        val core = config.installedCores.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
            ?: config.installedCores.firstOrNull { it.dirName.equals(wanted, ignoreCase = true) }
            ?: return McpToolResult(
                "Server '$wanted' not found. Installed servers: " +
                    config.installedCores.joinToString(", ") { it.name }.ifEmpty { "(none)" },
                isError = true
            )
        if (core.name == config.activeCoreName) {
            return McpToolResult("Server '${core.name}' is already active.")
        }
        // 字段级原子写入，避免用陈旧快照覆盖并发修改。
        repo.updateAndSaveConfig { it.copy(activeCoreName = core.name) }
        // 配置写入走 300ms debounce：等待 configFlow 反映新活动服务器，避免紧随其后的调用读到旧值
        var updated = config
        repeat(30) {
            updated = repo.configFlow.first()
            if (updated.activeCoreName == core.name) {
                return McpToolResult("Active server switched to '${core.name}' (${core.core.displayName} ${core.version}).")
            }
            kotlinx.coroutines.delay(100)
        }
        return McpToolResult(
            "Switch to '${core.name}' requested but not confirmed within 3s; verify with list_servers.",
            isError = true
        )
    }

    private suspend fun toolStartServer(args: JsonObject?): McpToolResult {
        if (!app.termuxRuntime.isReady()) {
            return McpToolResult("Runtime is not ready yet (bootstrap incomplete). Wait for setup to finish and retry.", isError = true)
        }
        val state = repo.serverState.value
        if (state.isRunning && state.startupPhase != StartupPhase.Failed) {
            return McpToolResult("Server is already running (phase: ${state.startupPhase.name}). Use get_server_status to inspect.")
        }
        val (config, core) = requireServer(args)
        // 传入的 server 与活动核心不同：先切换（含持久化），再以新配置启动
        val launchConfig = if (core.name != config.activeCoreName) {
            // 字段级原子写入；用返回值作为启动配置，保证后续启动用的是合并后的权威态。
            repo.updateAndSaveConfig { it.copy(activeCoreName = core.name) }
        } else {
            config
        }
        repo.updateServerState {
            it.copy(
                isRunning = true,
                runningSinceMs = 0L,
                startupPhase = StartupPhase.PreparingEnvironment
            )
        }
        // 与 McViewModel.startServer 一致：异步启动，立即返回，状态由日志解析与服务推送
        scope.launch {
            try {
                controller.start(launchConfig)
                // 准备阶段被用户取消时 start() 会直接返回（状态已复位为未运行），
                // 此时不该再拉起保活服务，否则会出现「没在跑却常驻前台服务」。
                if (repo.serverState.value.isRunning) {
                    startForegroundKeepAlive()
                }
            } catch (e: Exception) {
                repo.updateServerState {
                    it.copy(isRunning = false, runningSinceMs = 0L, startupPhase = StartupPhase.Failed)
                }
                repo.termuxRuntime.emitLog("[mcp] 启动失败: ${e.message}")
            }
        }
        return McpToolResult("Start initiated for '${core.name}'. Use get_server_status to poll startup progress.")
    }

    private suspend fun toolStopServer(): McpToolResult {
        if (!repo.serverState.value.isRunning) {
            return McpToolResult("Server is not running.")
        }
        controller.stop()
        return McpToolResult("Server stop requested.")
    }

    private suspend fun toolSendCommand(args: JsonObject?): McpToolResult {
        val command = stringArg(args, "command")?.trim()
        if (command.isNullOrEmpty()) {
            return McpToolResult("Missing required argument: command", isError = true)
        }
        if (!repo.serverState.value.isRunning) {
            return McpToolResult("Server is not running; cannot send command.", isError = true)
        }
        controller.sendCommand(command)
        return McpToolResult("Command sent to console: $command")
    }

    private suspend fun toolConsoleLogs(args: JsonObject?): McpToolResult {
        val requested = McpProtocol.intArg(args, "lines") ?: 100
        val lines = requested.coerceIn(1, 2000)
        val (_, core) = requireServer(args)
        // 不能硬编码 logs/latest.log：PocketMine 不生成它（1.2.7 到本轮之间控制台捕获改了文件名，
        // 于是这个工具在 PocketMine 上恒返回空）。统一走 TermuxRuntime 的首选/回退逻辑。
        val logFile = app.termuxRuntime.mcLogFileFor(core.dirName)
        if (logFile == null) {
            return McpToolResult("Console log is empty (the server may never have started).")
        }
        val recent = McpFileUtils.tailLines(logFile, lines)
        if (recent.isEmpty()) {
            return McpToolResult("Console log is empty (the server may never have started).")
        }
        val prefix = if (recent.size < lines) "" else "[last ${recent.size} lines of ${core.name}]\n"
        return McpToolResult(prefix + recent.joinToString("\n"))
    }

    // ── Termux 运行环境 ────────────────────────────────────────

    private suspend fun toolRunTermux(args: JsonObject?): McpToolResult {
        val command = stringArg(args, "command")?.takeIf { it.isNotBlank() }
            ?: return McpToolResult("Missing required argument: command", isError = true)
        val timeoutSec = (McpProtocol.intArg(args, "timeout_sec") ?: 60).coerceIn(5, 300)
        val maxOutputLines = 800
        termuxMutex.withLock {
            // 与 UI 终端一致：执行前修复环境（幂等，毫秒级）
            withContext(Dispatchers.IO) { app.termuxRuntime.refreshTermux() }
            val output = Collections.synchronizedList(ArrayList<String>())
            val latch = CountDownLatch(1)
            var exitCode = Int.MIN_VALUE
            val worker = thread(start = true, isDaemon = true, name = "mcp-termux") {
                try {
                    exitCode = app.termuxRuntime.execTermux(command) { line ->
                        if (output.size < maxOutputLines) output.add(line)
                    }
                } catch (e: Exception) {
                    output.add("[mcp] exec error: ${e.message}")
                } finally {
                    latch.countDown()
                }
            }
            val finished = latch.await(timeoutSec.toLong(), TimeUnit.SECONDS)
            val text = synchronized(output) { output.joinToString("\n") }
            return if (finished) {
                if (text.isBlank()) McpToolResult("(no output; exit code $exitCode)")
                else McpToolResult("$text\n[exit code: $exitCode]")
            } else {
                worker.interrupt()
                McpToolResult(
                    "Command timed out after ${timeoutSec}s and may still be running in background. Partial output:\n$text",
                    isError = true
                )
            }
        }
    }

    // ── 服务器文件管理（沙盒限定在服务器目录内） ────────────────

    private suspend fun sandboxedTarget(args: JsonObject?, key: String): Pair<InstalledCore, File> {
        val (_, core) = requireServer(args)
        val rel = stringArg(args, key) ?: ""
        return core to McpFileUtils.sandboxedFile(app.termuxRuntime.serverDirFor(core.dirName), rel)
    }

    /**
     * 取必填的路径参数。
     *
     * 不能写成 `stringArg(args, key) ?: ""`：空路径会被 [McpFileUtils.sandboxedFile] 归一成
     * 「服务器根目录」，于是「没传参数」被当成「传了根目录」，报出
     * `Target already exists: <服务器名>` 这种指错对象的错误，调用方还会以为参数没问题。
     */
    private fun requiredPath(args: JsonObject?, key: String = "path"): String? =
        stringArg(args, key)?.trim()?.takeIf { it.isNotEmpty() }

    /** 统一的缺参错误文案：点名缺哪个参数，便于调用方自纠。 */
    private fun missingArg(key: String) = McpToolResult("Missing required argument: $key", isError = true)

    private suspend fun toolListFiles(args: JsonObject?): McpToolResult {
        val rel = stringArg(args, "path")?.trim().orEmpty()
        val (_, core) = requireServer(args)
        val dir = McpFileUtils.sandboxedFile(app.termuxRuntime.serverDirFor(core.dirName), rel)
        if (!dir.exists() || !dir.isDirectory) {
            return McpToolResult("Directory not found: ${rel.ifEmpty { "server root" }}", isError = true)
        }
        val entries = dir.listFiles()?.sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
        ) ?: emptyList()
        if (entries.isEmpty()) return McpToolResult("(empty directory)")
        val cap = 500
        val json = kotlinx.serialization.json.buildJsonArray {
            entries.take(cap).forEach { f ->
                add(buildJsonObject {
                    put("name", f.name)
                    put("type", if (f.isDirectory) "dir" else "file")
                    if (f.isFile) put("size", f.length())
                    put("modified", f.lastModified() / 1000L)
                })
            }
        }
        val note = if (entries.size > cap) "\n[showing $cap of ${entries.size} entries]" else ""
        return McpToolResult(json.toString() + note)
    }

    private suspend fun toolReadFile(args: JsonObject?): McpToolResult {
        val path = requiredPath(args) ?: return missingArg("path")
        val (_, file) = sandboxedTarget(args, "path")
        if (!file.isFile) return McpToolResult("File not found: $path", isError = true)
        val maxBytes = (McpProtocol.intArg(args, "max_bytes") ?: 131_072).coerceIn(1, 524_288)
        if (McpFileUtils.looksBinary(file)) {
            return McpToolResult("Binary file (${file.length()} bytes); text reading is not available.", isError = true)
        }
        // 流式限量读：最多读 maxBytes+1 字节，多读的 1 字节只用来判断是否被截断。
        // 原实现 file.readBytes() 会把整个文件读进内存再截断，读取超大文件（如几百 MB 日志）
        // 会直接 OOM，而 OOM 是 Error、常规 catch 抓不到。
        val bytes = readAtMost(file, maxBytes + 1)
        val truncated = bytes.size > maxBytes
        val text = String(bytes, 0, if (truncated) maxBytes else bytes.size, Charsets.UTF_8)
        val note = if (truncated) "\n[truncated at $maxBytes of ${file.length()} bytes]" else ""
        return McpToolResult(text + note)
    }

    /**
     * 从 [file] 读取至多 [limit] 字节（读满即停，绝不把整个大文件载入内存）。
     *
     * 说明：不能直接用 `InputStream.readNBytes` —— 它依赖 Android API 33，
     * 而本应用 minSdk=26 且未启用 core library desugaring，低版本设备上会 NoSuchMethodError。
     */
    private fun readAtMost(file: File, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buf = ByteArray(64 * 1024)
        java.io.FileInputStream(file).use { input ->
            var remaining = limit
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(buf.size, remaining))
                if (n == -1) break
                out.write(buf, 0, n)
                remaining -= n
            }
        }
        return out.toByteArray()
    }

    private suspend fun toolWriteFile(args: JsonObject?): McpToolResult {
        val relPath = stringArg(args, "path")?.trim()
            ?: return McpToolResult("Missing required argument: path", isError = true)
        val content = stringArg(args, "content")
            ?: return McpToolResult("Missing required argument: content", isError = true)
        val (_, file) = sandboxedTarget(args, "path")
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > 1_048_576) {
            return McpToolResult("Content too large (${bytes.size} bytes); limit is 1 MB.", isError = true)
        }
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return McpToolResult("Wrote ${bytes.size} bytes to $relPath.")
    }

    private suspend fun toolDeleteFile(args: JsonObject?): McpToolResult {
        val path = requiredPath(args) ?: return missingArg("path")
        val (_, core) = requireServer(args)
        val root = app.termuxRuntime.serverDirFor(core.dirName).canonicalFile
        val target = McpFileUtils.sandboxedFile(root, path)
        if (target == root) {
            return McpToolResult("Refusing to delete the server root directory.", isError = true)
        }
        if (!target.exists()) {
            return McpToolResult("Path not found: $path", isError = true)
        }
        // 删除不可撤销：必须先显式确认。目录场景尤其重要——会连同里面全部内容删掉。
        if (McpArgs.bool(args, "confirm") != true) {
            val kind = if (target.isDirectory) "directory and everything under it" else "file"
            return McpToolResult(
                "Refusing to delete $path ($kind) in '${core.name}' without confirmation. " +
                    "Re-issue with \"confirm\": true to proceed.",
                isError = true
            )
        }
        val removed = McpFileUtils.deleteRecursivelyCounted(target)
        return McpToolResult("Deleted $removed entr${if (removed == 1) "y" else "ies"} ($path).")
    }

    private suspend fun toolRenameFile(args: JsonObject?): McpToolResult {
        val path = requiredPath(args) ?: return missingArg("path")
        val newPath = requiredPath(args, "new_path") ?: return missingArg("new_path")
        val (_, core) = requireServer(args)
        val root = app.termuxRuntime.serverDirFor(core.dirName).canonicalFile
        val source = McpFileUtils.sandboxedFile(root, path)
        val target = McpFileUtils.sandboxedFile(root, newPath)
        if (!source.exists()) return McpToolResult("Source not found: $path", isError = true)
        if (target.exists()) return McpToolResult("Target already exists: $newPath", isError = true)
        target.parentFile?.mkdirs()
        if (!source.renameTo(target)) {
            return McpToolResult("Rename failed (cross-device or permission issue).", isError = true)
        }
        return McpToolResult("Renamed $path -> $newPath.")
    }

    private suspend fun toolMakeDir(args: JsonObject?): McpToolResult {
        val path = requiredPath(args) ?: return missingArg("path")
        val (_, file) = sandboxedTarget(args, "path")
        if (file.exists()) {
            return McpToolResult(
                if (file.isDirectory) "Directory already exists." else "A file with that name exists.",
                isError = true
            )
        }
        if (!file.mkdirs()) return McpToolResult("mkdir failed: $path", isError = true)
        return McpToolResult("Directory created: $path")
    }

    private suspend fun toolUploadFile(args: JsonObject?): McpToolResult {
        val relPath = stringArg(args, "path")?.trim()
            ?: return McpToolResult("Missing required argument: path", isError = true)
        var payload = stringArg(args, "content_base64")?.trim()
            ?: return McpToolResult("Missing required argument: content_base64", isError = true)
        // 兼容 data URI 前缀
        if (payload.startsWith("data:")) payload = payload.substringAfter("base64,").trim()
        if (payload.length > MAX_UPLOAD_B64_CHARS) {
            return McpToolResult(
                "Chunk too large (${payload.length} base64 chars; limit $MAX_UPLOAD_B64_CHARS). " +
                    "Split the file and send chunks with append=true.",
                isError = true
            )
        }
        val bytes = try {
            java.util.Base64.getDecoder().decode(payload.replace("\n", "").replace("\r", ""))
        } catch (e: IllegalArgumentException) {
            return McpToolResult("Invalid base64 content: ${e.message}", isError = true)
        }
        val (_, file) = sandboxedTarget(args, "path")
        file.parentFile?.mkdirs()
        // 与其它开关统一走宽松布尔（true/false/yes/no/1/0/on/off）：
        // 以前只认字面 "true"，传 "1" 会被静默当成覆盖写，分块大文件只剩最后一块。
        // 无法识别的取值按「没传」处理（= 覆盖写），因为那是既有行为。
        val append = McpArgs.bool(args, "append") ?: false
        // 单文件累计上限：append 分块上传若不设总量，可持续追加直到写满设备存储。
        // 覆盖模式从 0 起算，追加模式以现有大小起算；超限删除此前分块累积的半截文件。
        val existing = if (append && file.exists()) file.length() else 0L
        if (existing + bytes.size > MAX_UPLOAD_TOTAL_BYTES) {
            runCatching { file.delete() }
            return McpToolResult(
                "Upload would exceed the ${MAX_UPLOAD_TOTAL_BYTES / (1024L * 1024 * 1024)} GiB per-file limit " +
                    "(existing $existing + this chunk ${bytes.size} bytes). The partial file was removed.",
                isError = true
            )
        }
        java.io.FileOutputStream(file, append).use { it.write(bytes) }
        val size = file.length()
        return McpToolResult(
            "${if (append) "Appended" else "Wrote"} ${bytes.size} bytes → $relPath (total $size bytes). " +
                "If this is a zip/tar archive you can unpack it with extract_archive."
        )
    }

    private suspend fun toolExtractArchive(args: JsonObject?): McpToolResult {
        val path = requiredPath(args) ?: return missingArg("path")
        val destRel = stringArg(args, "dest")?.trim().orEmpty()
        val (_, core) = requireServer(args)
        val root = app.termuxRuntime.serverDirFor(core.dirName).canonicalFile
        val archive = McpFileUtils.sandboxedFile(root, path)
        if (!archive.isFile) return McpToolResult("Archive not found: $path", isError = true)
        val dest = McpFileUtils.sandboxedFile(root, destRel).apply { mkdirs() }
        val stats = withContext(Dispatchers.IO) { McpArchive.extract(archive, dest) }
        return McpToolResult("${stats.describe()} → ${dest.relativeTo(root).path.ifEmpty { "server root" }}")
    }

    /**
     * 从上传的压缩包导入新服务器：解压到 servers 根目录并注册（识别不出核心也按「导入/未知」登记）。
     * 压缩包路径相对于活动服务器目录（upload_file 的落点），导入完成后删除。
     */
    private suspend fun toolImportServer(args: JsonObject?): McpToolResult {
        val name = stringArg(args, "name")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return McpToolResult("Missing required argument: name", isError = true)
        val archiveRel = stringArg(args, "archive")?.trim()
            ?: return McpToolResult("Missing required argument: archive (uploaded via upload_file)", isError = true)
        val config = repo.configFlow.first()
        if (config.installedCores.any { it.name.equals(name, ignoreCase = true) }) {
            return McpToolResult("A server named '$name' already exists.", isError = true)
        }
        val (_, stagingCore) = requireServer(null)
        val archive = McpFileUtils.sandboxedFile(
            app.termuxRuntime.serverDirFor(stagingCore.dirName), archiveRel
        )
        if (!archive.isFile) return McpToolResult("Archive not found: ${archive.name}", isError = true)
        val dirName = McServerController.sanitizeDirName(name)
        val target = File(app.termuxRuntime.serversDir, dirName)
        if (target.exists()) {
            return McpToolResult("Server folder '$dirName' already exists; delete it first or pick another name.", isError = true)
        }
        target.mkdirs()
        val stats = try {
            withContext(Dispatchers.IO) { McpArchive.extract(archive, target) }
        } catch (e: Exception) {
            target.deleteRecursively()
            throw e
        }
        val detected = withContext(Dispatchers.IO) {
            com.mineserve.mobile.server.ServerCoreDetector.detect(target)
        }
        val registered = com.mineserve.mobile.data.InstalledCore(
            name = name,
            core = detected.core ?: com.mineserve.mobile.data.ServerCore.Unknown,
            version = detected.version ?: app.getString(R.string.ver_imported),
            dirName = dirName,
            serverFile = detected.serverFile
        )
        // list + 1 必须基于权威内存态原子完成：先 repo.configFlow.first() 再整份写回，
        // 会丢掉读取与写入之间用户/其它工具对配置的任何修改。
        repo.updateAndSaveConfig { it.copy(installedCores = it.installedCores + registered) }
        archive.delete()
        // 等待 DataStore 流反映新列表（写入走 300ms debounce），避免紧随其后的查询读到旧值
        repeat(30) {
            val updated = repo.configFlow.first()
            if (updated.installedCores.any { it.dirName == dirName }) {
                val jarNote = if (registered.serverFile == null) {
                    " No runnable jar detected — add the core jar before starting."
                } else ""
                return McpToolResult(
                    "Imported '${registered.name}' (${registered.core.displayName} ${registered.version}) — " +
                        stats.describe() + ".$jarNote Use select_server to switch to it."
                )
            }
            kotlinx.coroutines.delay(100)
        }
        return McpToolResult(
            "Import of '${registered.name}' completed but confirmation timed out; verify with list_servers.",
            isError = true
        )
    }

    // ── 模组（Modrinth） ───────────────────────────────────────

    private suspend fun toolListMods(args: JsonObject?): McpToolResult {
        val (_, core) = requireServer(args)
        val mods = pluginManager.readMods(core.dirName)
        if (mods.isEmpty()) return McpToolResult("(no mods installed)")
        val json = kotlinx.serialization.json.buildJsonArray {
            mods.forEach { m ->
                add(buildJsonObject {
                    put("fileName", m.fileName)
                    put("name", m.baseName)
                    put("size", m.sizeText)
                    put("enabled", m.isEnabled)
                })
            }
        }
        return McpToolResult(json.toString())
    }

    private suspend fun toolSearchMods(args: JsonObject?): McpToolResult {
        val query = stringArg(args, "query")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return McpToolResult("Missing required argument: query", isError = true)
        val (_, core) = requireServer(args)
        val mcVersion = stringArg(args, "mc_version")?.trim().takeIf { !it.isNullOrEmpty() } ?: core.version
        val loader = stringArg(args, "loader")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: coreLoader(core.core) ?: "fabric"
        val hits = withTimeout(MODRINTH_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                pluginManager.searchModrinth(query, listOf(loader), "relevance", projectType = "mod", mcVersion = mcVersion)
            }
        }
        if (hits.isEmpty()) {
            return McpToolResult("No results for '$query' (MC $mcVersion, loader $loader). Check the version/loader or loosen filters.")
        }
        val json = kotlinx.serialization.json.buildJsonArray {
            hits.forEach { h ->
                add(buildJsonObject {
                    put("title", h.title)
                    put("slug", h.slug)
                    put("author", h.author)
                    put("downloads", h.downloads)
                    put("description", h.description)
                })
            }
        }
        return McpToolResult("${json}\nInstall with install_mod {\"slug\": \"...\"}.")
    }

    private suspend fun toolInstallMod(args: JsonObject?): McpToolResult {
        val slug = stringArg(args, "slug")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return McpToolResult("Missing required argument: slug (from search_mods)", isError = true)
        val (_, core) = requireServer(args)
        if (core.core.isBedrock) {
            return McpToolResult("Bedrock core (${core.core.displayName}) has no mod system.", isError = true)
        }
        val mcVersion = stringArg(args, "mc_version")?.trim().takeIf { !it.isNullOrEmpty() } ?: core.version
        val loader = stringArg(args, "loader")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: coreLoader(core.core)
            ?: return McpToolResult(
                "Core ${core.core.displayName} has no mod loader; pass the loader argument explicitly (fabric/forge/neoforge/quilt).",
                isError = true
            )
        val url = withTimeout(MODRINTH_TIMEOUT_MS) {
            withContext(Dispatchers.IO) { pluginManager.resolveModrinthDownload(slug, mcVersion, loader) }
        } ?: return McpToolResult(
            "No downloadable version of '$slug' for MC $mcVersion + loader $loader. Try search_mods to confirm compatibility.",
            isError = true
        )
        val fileName = safeRemoteFileName(url, slug)
        val installed = withTimeout(DOWNLOAD_TIMEOUT_MS) {
            pluginManager.installModFromUrl(url, fileName, core.dirName)
        }
        return McpToolResult(
            "Installed ${installed.name} (${installed.length() / 1024} KB) into ${core.name}/mods. " +
                "Restart the server to load it."
        )
    }

    /**
     * 从远端下载 URL 推导安全的落盘文件名。
     *
     * 净化逻辑见 [McpFileUtils.safeRemoteFileName]（与原实现一致，抽出去是为了让
     * install_plugin 等新增工具复用同一份实现，避免两处漂移）。
     */
    private fun safeRemoteFileName(url: String, slug: String): String =
        McpFileUtils.safeRemoteFileName(url, slug)

    /** 服务端核心 → Modrinth 加载器 */
    private fun coreLoader(core: ServerCore): String? = when (core) {
        ServerCore.Fabric -> "fabric"
        ServerCore.Forge -> "forge"
        ServerCore.NeoForge -> "neoforge"
        ServerCore.Quilt -> "quilt"
        else -> null
    }

    private fun stringArg(args: JsonObject?, key: String): String? = McpArgs.string(args, key)

    private fun startForegroundKeepAlive() {
        try {
            val intent = Intent(app, McForegroundService::class.java)
                .setAction(McForegroundService.ACTION_START)
            app.startForegroundService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "startForegroundService failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "McpServerManager"
        private const val MCP_PATH = "/mcp"
        private const val MODRINTH_TIMEOUT_MS = 60_000L
        private const val DOWNLOAD_TIMEOUT_MS = 600_000L

        /** upload_file 单块 base64 上限（约 12MB 二进制） */
        private const val MAX_UPLOAD_B64_CHARS = 16 * 1024 * 1024

        /** upload_file 单文件累计上限（覆盖模式算总量、追加模式含已有大小），防止分块上传写满存储 */
        private const val MAX_UPLOAD_TOTAL_BYTES = 4L * 1024 * 1024 * 1024

        /** 生成 32 位十六进制访问令牌 */
        fun generateToken(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
