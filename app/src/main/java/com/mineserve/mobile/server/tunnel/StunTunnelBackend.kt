package com.mineserve.mobile.server.tunnel

import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.TunnelState
import com.mineserve.mobile.data.TunnelStatus
import com.mineserve.mobile.data.TunnelType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * STUN 直连隧道：不依赖任何中转服务器，靠 NAT 打洞把公网地址直接映射到本机端口。
 *
 * ## 成立条件
 *
 * 仅适用于 **全锥形 NAT（NAT1）**。其性质是：本机内网 `(IP, 端口)` 一旦向外部
 * 发过包，NAT 就为该端点建立固定映射，之后**任何**外部地址都可以经这个映射把
 * TCP 连接送到本机同一端口，不受来源限制（这正是打洞得名的原因）。
 *
 * ## 工作流程（对照 EdgeCube `_runLoop`）
 *
 * 1. **探测**：与 STUN 服务器建立 TCP 长连接，发送 Binding Request，取回本机的
 *    公网 `IP:端口`。这一步同时让 NAT 建立了映射。**刻意不指定本地端口**，由内核
 *    分配随机端口 P —— 详见 [StunTcpSession.connect]。
 * 2. **监听**：把 `ServerSocket` 绑定到 P（探测连接的本地端口），**而不是** MC
 *    服务端端口。[StunTcpSession] 已对该连接设置 `SO_REUSEADDR`，故绑定不会冲突。
 * 3. **保活**：周期在探测连接上重发 Binding Request，既维持映射不被回收，也能
 *    发现公网地址变化（重拨、切换网络）。[keepAlive] 返回即表示需要重建隧道。
 * 4. **转发**：接受公网入站连接，连到本地目标端口，双向透传。
 *
 * 主循环是**常驻**的：单轮失败不会终止隧道，而是按轮次重试；保活感知到映射失效
 * 会自动重建，因此 NAT 抖动或 STUN 服务器临时不可用都能自愈。
 *
 * ## 局限（必须如实告知用户）
 *
 * 打洞能成立，前提是「已知 NAT 映射」。若运营商或防火墙对入站连接做了额外拦截
 * （CGNAT、上行过滤），外部连接会被丢弃，表现为玩家连不上——本后端无法绕过，
 * 只能由用户改用 frp 或平台隧道。
 */
class StunTunnelBackend : TunnelBackend {

    override val type: TunnelType = TunnelType.Stun

    private val _state = MutableStateFlow(TunnelState())
    override val state: StateFlow<TunnelState> = _state.asStateFlow()

    /** 日志回调，由 [TunnelManager] 在 start 之前注入。 */
    @Volatile private var logSink: (String) -> Unit = {}

    override fun attachLog(logger: (String) -> Unit) {
        logSink = logger
    }

    // ── 运行期句柄 ────────────────────────────────────────────

    @Volatile private var session: StunTcpSession? = null
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var runThread: Thread? = null

    /**
     * 当前这次启动的世代号。每次 start/stop 递增，主循环据此判断自己是否已被取代，
     * 从而主动退出——避免旧一轮在后台继续跑并操作已被新一轮接管的 socket。
     */
    private val generation = AtomicInteger(0)

    /** 当前对外公布的地址，保活期间可能更新它。 */
    @Volatile private var published: StunMappedAddress? = null

    /** 活跃连接计数，供 UI 展示。 */
    private val activeConnections = AtomicInteger(0)
    private val totalUpload = AtomicLong(0)
    private val totalDownload = AtomicLong(0)

    // 速率统计：每秒用「当前累计 - 上一秒累计」得到瞬时速率。
    @Volatile private var speedThread: Thread? = null
    private val lastUpload = AtomicLong(0)
    private val lastDownload = AtomicLong(0)

    /**
     * 全部存活的转发套接字（入站与后端各一个）。
     * 用 Set 语义便于去重与 O(1) 移除；[teardown] 统一销毁。
     */
    private val liveSockets: MutableSet<Socket> =
        Collections.newSetFromMap(ConcurrentHashMap())

    // ── 日志环形缓冲（P5/P6） ────────────────────────────────

    /** 最多保留的日志行数，与 EdgeCube `_maxLogLines` 一致。 */
    private val maxLogLines = 500
    private val logBuffer = ArrayDeque<String>()

    private fun log(message: String) {
        synchronized(logBuffer) {
            logBuffer.addLast(message)
            while (logBuffer.size > maxLogLines) logBuffer.removeFirst()
        }
        logSink(message)
    }

    /** 历史日志快照，供界面初次渲染时回放。 */
    fun logSnapshot(): List<String> = synchronized(logBuffer) { logBuffer.toList() }

    /** 清空日志缓冲（只清本地缓冲，不影响已输出到控制台的内容）。 */
    fun clearLog() {
        synchronized(logBuffer) { logBuffer.clear() }
    }

    // ── 启动 ──────────────────────────────────────────────────

    /**
     * 启动隧道。**立即返回**，实际探测与就绪在后台主循环中进行（与 EdgeCube 一致：
     * 探测可能耗时数秒，不应阻塞调用方）。就绪或失败通过 [state] 反映。
     */
    override suspend fun start(config: McConfig): Result<Unit> {
        // 先停掉可能存在的旧一轮，避免两轮共用端口。
        withContext(Dispatchers.IO) { teardown() }

        val myGeneration = generation.incrementAndGet()
        log("[stun] 开始启动（第 $myGeneration 次请求）")

        val targetPort = if (config.stunLocalPort > 0) config.stunLocalPort else config.localPort
        if (targetPort !in 1..65535) {
            val msg = "目标端口无效（$targetPort）"
            log("[stun] $msg")
            updateState(TunnelStatus.Failed, "", msg)
            return Result.failure(IllegalArgumentException(msg))
        }

        val servers = StunClient.parseServers(config.stunServers)
            .ifEmpty { StunClient.DEFAULT_SERVERS.map { StunServer(it) } }
        if (servers.isEmpty()) {
            val msg = "没有可用的 STUN 服务器"
            log("[stun] $msg")
            updateState(TunnelStatus.Failed, "", msg)
            return Result.failure(IllegalStateException(msg))
        }

        resetCounters()
        updateState(TunnelStatus.Starting, "")
        log("[stun] 隧道启动中，转发目标端口 $targetPort")

        startSpeedTicker(myGeneration)

        val runner = thread(name = "stun-loop", isDaemon = true) {
            runLoop(config, targetPort, servers, myGeneration)
        }
        runThread = runner
        return Result.success(Unit)
    }

    /**
     * 主循环：探测 → 绑定监听 → 保活 → （失效则）重建。
     *
     * 单轮失败按 [maxConsecutiveFailures] 计数，未达上限就等待后重试——这样
     * 网络瞬断或某台 STUN 服务器抽风不会直接判死隧道。
     */
    private fun runLoop(
        config: McConfig,
        targetPort: Int,
        servers: List<StunServer>,
        myGeneration: Int
    ) {
        var failures = 0
        while (generation.get() == myGeneration) {
            val established = probe(servers, myGeneration)
            if (generation.get() != myGeneration) break

            if (established == null) {
                failures++
                if (failures >= maxConsecutiveFailures) {
                    failAndCleanup(
                        "全部 STUN 服务器均无法建立隧道，当前网络可能不支持（对称 NAT / CGNAT），请改用 frp 或平台隧道",
                        myGeneration
                    )
                    return
                }
                log("[stun] 本轮探测失败，15 秒后重试（$failures/$maxConsecutiveFailures）")
                updateState(TunnelStatus.Starting, "")
                if (!sleepInterruptible(retryProbeDelayMs, myGeneration)) return
                continue
            }

            val (activeSession, mapped) = established
            val bound = bindListener(activeSession, myGeneration)
            if (generation.get() != myGeneration) {
                activeSession.close()
                return
            }
            if (!bound) {
                failures++
                activeSession.close()
                // 探测虽已拿到映射，但没能监听，该地址不可用，不要留在界面上。
                published = null
                updateProbeStateKeep()
                if (failures >= maxConsecutiveFailures) {
                    failAndCleanup("无法在映射端口上建立监听，隧道启动失败", myGeneration)
                    return
                }
                if (!sleepInterruptible(retryBindDelayMs, myGeneration)) return
                continue
            }

            failures = 0
            published = mapped
            val url = "tcp://${mapped.host}:${mapped.port}"
            log("[stun] 隧道已就绪：tcp://127.0.0.1:$targetPort ← tcp://0.0.0.0:${activeSession.localPort}（公网 $url）")
            if (config.stunProxyProtocol) {
                log("[stun] 已启用 PROXY protocol v2：服务端必须同时开启该支持，否则玩家将无法进入")
            }
            updateState(TunnelStatus.Running, url, "")

            startAcceptLoop(config, targetPort, myGeneration)

            // 返回即表示需要重建（连接断开或公网地址变化）。
            keepAlive(activeSession, config, myGeneration)
            if (generation.get() != myGeneration) break

            teardown()
            published = null
            updateState(TunnelStatus.Starting, "")
            log("[stun] 正在重建隧道…")
            if (!sleepInterruptible(rebuildDelayMs, myGeneration)) return
        }
    }

    /**
     * 依次尝试各 STUN 服务器，返回可用的长连接会话与公网映射；全部失败返回 null。
     *
     * 不能只看「能连上」——大量服务器只支持 UDP，TCP 连得上却永不回包，所以以
     * 「能否取回映射地址」作为可用判据。
     */
    private fun probe(
        servers: List<StunServer>,
        myGeneration: Int
    ): Pair<StunTcpSession, StunMappedAddress>? {
        log("[stun] 开始探测公网映射（共 ${servers.size} 台服务器）")
        var lastError = "未知错误"
        for (server in servers) {
            if (generation.get() != myGeneration) return null
            var candidate: StunTcpSession? = null
            try {
                log("[stun] 尝试服务器 $server ...")
                candidate = StunTcpSession.connect(server)
                // connect 之后立刻查一次：期间可能已被 stop() 或新一轮取代。
                if (generation.get() != myGeneration) {
                    candidate.close()
                    return null
                }
                val mapped = candidate.requestMapping()
                // requestMapping 是阻塞等待，返回后同样要复查。
                if (generation.get() != myGeneration) {
                    candidate.close()
                    return null
                }
                log("[stun] 探测成功：本地端口 ${candidate.localPort} → 公网 ${mapped.host}:${mapped.port}")
                return candidate to mapped
            } catch (e: Exception) {
                lastError = e.message ?: e.toString()
                log("[stun] $server 不可用：$lastError")
                candidate?.close()
            }
        }
        log("[stun] 所有服务器探测失败（最后错误：$lastError）")
        return null
    }

    /** 把监听套接字绑定到 STUN 会话所用的本地端口。成功返回 true。 */
    private fun bindListener(activeSession: StunTcpSession, myGeneration: Int): Boolean {
        val listenPort = activeSession.localPort
        val listener = ServerSocket()
        return try {
            listener.reuseAddress = true
            listener.bind(InetSocketAddress(listenPort), 64)
            if (generation.get() != myGeneration) {
                runCatching { listener.close() }
                return false
            }
            serverSocket = listener
            session = activeSession
            log("[stun] 已监听映射端口 $listenPort")
            true
        } catch (e: Exception) {
            runCatching { listener.close() }
            log("[stun] 在映射端口 $listenPort 上建立监听失败：${e.message}")
            false
        }
    }

    /** 接受入站连接。用世代号判定存活，被取代即退出。 */
    private fun startAcceptLoop(config: McConfig, targetPort: Int, myGeneration: Int) {
        val listener = serverSocket ?: return
        val maxConnections = config.stunMaxConnections
        val proxyProtocol = config.stunProxyProtocol
        val showConnLog = config.stunShowConnectionLog
        thread(name = "stun-accept", isDaemon = true) {
            log("[stun] 开始接受入站连接")
            while (generation.get() == myGeneration) {
                val client: Socket = try {
                    listener.accept()
                } catch (e: Throwable) {
                    // 捕获 Throwable：这条线程上的任何逃逸异常都会终结进程。
                    // 被取代时静默退出，不刷无意义的错误日志。
                    if (generation.get() == myGeneration) log("[stun] 接受连接出错：${e.message}")
                    break
                }
                val remote = "${client.inetAddress?.hostAddress}:${client.port}"
                if (activeConnections.get() >= maxConnections) {
                    if (showConnLog) log("[stun] 拒绝 $remote：已达并发上限 $maxConnections")
                    runCatching { client.close() }
                    continue
                }
                val id = activeConnections.incrementAndGet()
                liveSockets.add(client)
                syncCounters()
                thread(name = "stun-conn-$id", isDaemon = true) {
                    try {
                        handleConnection(client, targetPort, proxyProtocol, showConnLog, id)
                    } finally {
                        activeConnections.decrementAndGet()
                        syncCounters()
                    }
                }
            }
        }
    }

    /**
     * 周期重发 Binding Request：保活 NAT 映射，感知公网地址变化。
     *
     * **返回即表示需要重建隧道**——连接断开、保活失败或地址变化都属此类，
     * 由主循环统一处理重建（因此不再直接判定 Failed）。
     */
    private fun keepAlive(activeSession: StunTcpSession, config: McConfig, myGeneration: Int) {
        val intervalMs = config.stunKeepAliveSeconds.coerceAtLeast(5) * 1000L
        val baseline = published
        while (generation.get() == myGeneration) {
            if (!sleepInterruptible(intervalMs, myGeneration)) return
            if (generation.get() != myGeneration) return
            if (activeSession.isClosed) {
                log("[stun] 保活连接已断开，NAT 映射可能失效")
                return
            }
            try {
                val refreshed = activeSession.requestMapping(timeoutMs = 8000)
                if (refreshed != baseline) {
                    log("[stun] 检测到公网地址变化：$baseline → $refreshed")
                    return
                }
                syncCounters()
            } catch (e: Throwable) {
                // 捕获 Throwable：这条线程上的任何逃逸异常都会终结进程。
                log("[stun] 保活探测失败：${e.message}")
                return
            }
        }
    }

    /** 单条入站连接：连本地目标 → 可选插入 PROXY 头 → 双向透传。 */
    private fun handleConnection(
        client: Socket,
        targetPort: Int,
        proxyProtocol: Boolean,
        showConnLog: Boolean,
        id: Int
    ) {
        val peer = client.inetAddress
        val peerPort = client.port
        val remote = "${peer?.hostAddress}:$peerPort"
        if (showConnLog) log("[stun] 入站连接 $remote")
        var local: Socket? = null
        try {
            client.tcpNoDelay = true
            local = Socket()
            local.tcpNoDelay = true
            local.connect(InetSocketAddress("127.0.0.1", targetPort), 5000)
            liveSockets.add(local)

            if (proxyProtocol) {
                val header = ProxyProtocolV2.buildHeader(peer, peerPort, targetPort)
                local.getOutputStream().write(header)
                local.getOutputStream().flush()
            }

            val target = local
            // 与 EdgeCube 的 Future.any 一致：**任一方向结束**即视为连接结束，
            // 由 finally 关闭两端让另一条 pump 退出。若两个都 join，客户端关闭后
            // 下行可能仍阻塞在 read 上，连接会迟迟不释放。
            val up = thread(name = "stun-up-$id", isDaemon = true) {
                pump(client, target, upload = true)
            }
            val down = thread(name = "stun-down-$id", isDaemon = true) {
                pump(target, client, upload = false)
            }
            // 等任一线程结束：pump 结束时已 shutdownOutput，另一方向多半也会随即结束。
            while (up.isAlive && down.isAlive) {
                Thread.sleep(50)
            }
            runCatching { client.close() }
            runCatching { target.close() }
            up.join()
            down.join()
        } catch (e: Throwable) {
            if (showConnLog) log("[stun] $remote 转发失败：${e.message}")
        } finally {
            liveSockets.remove(client)
            local?.let { liveSockets.remove(it) }
            runCatching { client.close() }
            runCatching { local?.close() }
            if (showConnLog) log("[stun] 断开连接 $remote")
        }
    }

    /**
     * 单向搬运字节并累计流量。
     *
     * 与 EdgeCube 的 `addStream` 一致，走「读一块写一块并 flush」的循环：写操作本身
     * 是阻塞的，对端消费慢时自然产生背压，数据不会在内存里无限堆积。
     */
    private fun pump(from: Socket, to: Socket, upload: Boolean) {
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                output.flush()
                if (upload) totalUpload.addAndGet(n.toLong()) else totalDownload.addAndGet(n.toLong())
            }
        } catch (_: Throwable) {
            // 对端关闭或 teardown() 强制断开都会走到这里，属正常收尾。
        } finally {
            // 主动关掉对端写方向，让另一条 pump 尽快结束，避免连接悬挂。
            runCatching { to.shutdownOutput() }
        }
    }

    // ── 速率统计（P2） ────────────────────────────────────────

    /** 每秒计算一次上/下行瞬时速率，写入 StateFlow。 */
    private fun startSpeedTicker(myGeneration: Int) {
        speedThread = thread(name = "stun-speed", isDaemon = true) {
            while (generation.get() == myGeneration) {
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    return@thread
                }
                if (generation.get() != myGeneration) return@thread
                tickSpeed()
            }
        }
    }

    private fun tickSpeed() {
        val up = totalUpload.get()
        val down = totalDownload.get()
        val upSpeed = up - lastUpload.getAndSet(up)
        val downSpeed = down - lastDownload.getAndSet(down)
        val current = _state.value
        if (current.status != TunnelStatus.Running) return
        _state.value = current.copy(
            activeConnections = activeConnections.get(),
            totalUploadBytes = up,
            totalDownloadBytes = down,
            uploadBytesPerSecond = if (upSpeed > 0) upSpeed else 0,
            downloadBytesPerSecond = if (downSpeed > 0) downSpeed else 0
        )
    }

    // ── 停止 ──────────────────────────────────────────────────

    override suspend fun stop() {
        log("[stun] 正在停止隧道")
        // 递增世代号，让主循环与所有子线程立刻发现自己已过期。
        generation.incrementAndGet()
        withContext(Dispatchers.IO) { teardown() }
        updateState(TunnelStatus.Stopped, "")
        log("[stun] 隧道已停止")
    }

    /** 判定启动失败：只有仍是最新一轮时才置错，避免覆盖新一轮的状态。 */
    private fun failAndCleanup(reason: String, myGeneration: Int) {
        if (generation.get() != myGeneration) return
        teardown()
        updateState(TunnelStatus.Failed, "", reason)
        log("[stun] $reason")
    }

    /** 释放全部套接字与线程引用。不改变状态，由调用方决定置成什么。 */
    private fun teardown() {
        // 先摘掉引用再关闭：即便关闭过程中抛异常，也不会留下半关状态的引用被后续操作复用。
        val listener = serverSocket
        serverSocket = null
        runCatching { listener?.close() }

        val currentSession = session
        session = null
        runCatching { currentSession?.close() }

        runThread = null
        runCatching { speedThread?.interrupt() }
        speedThread = null

        // 拷贝一份再清空，避免并发新增/移除导致迭代器失效。
        val sockets = liveSockets.toList()
        liveSockets.clear()
        sockets.forEach { runCatching { it.close() } }

        published = null
        activeConnections.set(0)
        // 不在此处同步计数到 StateFlow：teardown 可能在非 UI 路径被调用，
        // 且紧随其后的 updateState 会写入完整状态。
    }

    private fun resetCounters() {
        totalUpload.set(0)
        totalDownload.set(0)
        lastUpload.set(0)
        lastDownload.set(0)
        activeConnections.set(0)
    }

    /**
     * 可被取代操作提前打断的等待。返回 false 表示已过期，调用方应立刻退出。
     * 切片等待使 stop() 不必等满整个间隔。
     */
    private fun sleepInterruptible(totalMs: Long, myGeneration: Int): Boolean {
        val step = 250L
        var remaining = totalMs
        while (remaining > 0) {
            if (generation.get() != myGeneration) return false
            val slice = if (remaining < step) remaining else step
            try {
                Thread.sleep(slice)
            } catch (_: InterruptedException) {
                return false
            }
            remaining -= slice
        }
        return generation.get() == myGeneration
    }

    // ── 状态 ──────────────────────────────────────────────────

    /** 探测/重试期间保持 Starting，但不要把已失效的地址留在界面上。 */
    private fun updateProbeStateKeep() {
        val current = _state.value
        _state.value = current.copy(
            isRunning = false,
            publicUrl = "",
            status = TunnelStatus.Starting,
            errorMessage = ""
        )
    }

    private fun updateState(
        status: TunnelStatus,
        publicUrl: String,
        errorMessage: String = ""
    ) {
        _state.value = TunnelState(
            isRunning = status == TunnelStatus.Running,
            publicUrl = publicUrl,
            status = status,
            errorMessage = errorMessage,
            activeType = TunnelType.Stun,
            activeConnections = if (status == TunnelStatus.Running) activeConnections.get() else 0,
            totalUploadBytes = totalUpload.get(),
            totalDownloadBytes = totalDownload.get()
        )
    }

    /** 把当前计数同步进 StateFlow，供 UI 展示实时连接数。 */
    private fun syncCounters() {
        val current = _state.value
        if (current.status != TunnelStatus.Running) return
        _state.value = current.copy(
            activeConnections = activeConnections.get(),
            totalUploadBytes = totalUpload.get(),
            totalDownloadBytes = totalDownload.get()
        )
    }

    private companion object {
        /** 连续失败多少轮后放弃（避免在不支持的网络下无休止重试）。 */
        const val maxConsecutiveFailures = 3

        /** 探测失败后的重试间隔。 */
        const val retryProbeDelayMs = 15_000L

        /** 监听失败后的重试间隔。 */
        const val retryBindDelayMs = 5_000L

        /** 隧道失效后重建前的等待间隔。 */
        const val rebuildDelayMs = 3_000L
    }
}
