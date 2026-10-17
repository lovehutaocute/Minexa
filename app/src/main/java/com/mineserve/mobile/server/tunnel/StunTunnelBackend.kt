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
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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

    // ── STUN 专属诊断（供「STUN 直连」卡片展示） ──────────────

    private val _diagnostics = MutableStateFlow(StunDiagnostics())

    /**
     * 探测结论 / NAT 判定 / 连接回收计数。与 [state] 分开：
     * 通用隧道状态要给五种后端共用，而 NAT 判定只对 STUN 有意义。
     */
    val diagnostics: StateFlow<StunDiagnostics> = _diagnostics.asStateFlow()

    private fun updateDiagnostics(transform: (StunDiagnostics) -> StunDiagnostics) {
        _diagnostics.value = transform(_diagnostics.value)
    }

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

    /**
     * 上次成功建立隧道所用的**服务器 + 本地端口**。
     *
     * 重建时先拿它做「原地复用端口」的尝试：本地端口与服务器都不变 ⇒ 出站五元组
     * 完全一致 ⇒ NAT 只能沿用既有映射 ⇒ 公网地址不变。这是让玩家手里的地址在
     * 隧道自愈后依然有效的前提；失败才回落到 `probe()` 的并行探测（必然换端口）。
     */
    @Volatile private var lastGood: Endpoint? = null

    /**
     * 最近一次对外公布过的公网地址。
     *
     * 与 [published] 的唯一区别是**不随重建清空**——重建时 published 会被置空，
     * 拿它做新旧对比永远是 null，于是「地址到底有没有变」这个用户最关心的问题
     * 就没法回答了。这个字段专门服务于那行日志与界面提示。
     */
    @Volatile private var lastPublished: StunMappedAddress? = null

    /** 活跃连接计数，供 UI 展示。 */
    private val activeConnections = AtomicInteger(0)
    private val totalUpload = AtomicLong(0)
    private val totalDownload = AtomicLong(0)

    /** 因「连上后迟迟不发任何数据」被回收的连接数（S0.1 的防护战果）。 */
    private val idleTimeouts = AtomicInteger(0)

    /** 因达到并发上限被拒绝的连接数。 */
    private val limitRejects = AtomicInteger(0)

    /**
     * STUN 长连接被对端断开后「只换连接」的次数。
     *
     * 这个数持续上涨说明该服务器不会保持 TCP 长连接（每回应一次就关），属于对面
     * 的行为而不是本机故障 —— 但值得让日志能一眼看出来，否则容易被误判成隧道不稳。
     */
    @Volatile private var sessionRenewals = 0

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
        // 新一轮启动即新一轮诊断：清掉上一轮的公网地址与结论，避免界面
        // 在「正在探测」时还挂着上一轮的地址（那会让用户以为地址仍然有效）。
        _diagnostics.value = StunDiagnostics()
        updateState(TunnelStatus.Starting, "")
        log("[stun] 隧道启动中，转发目标端口 $targetPort")

        startSpeedTicker(myGeneration)

        val runner = thread(name = "stun-loop", isDaemon = true) {
            // 兜底：这条线程上的逃逸异常会直接杀进程（Android 不对线程做区分）。
            // 主循环自身已处理各类失败，能走到这里说明是意料外的 IO/运行时异常，
            // 按「隧道启动失败」收尾比整个 App 闪退合理。
            try {
                runLoop(config, targetPort, servers, myGeneration)
            } catch (e: Throwable) {
                log("[stun] 主循环异常终止：${e.javaClass.simpleName}: ${e.message}")
                failAndCleanup("隧道内部异常：${e.message}", myGeneration)
            }
        }
        runThread = runner
        return Result.success(Unit)
    }

    /** 一次建立成功的隧道：会话 + 公网映射 + 用的是哪台服务器。 */
    private data class Established(
        val session: StunTcpSession,
        val mapped: StunMappedAddress,
        val server: StunServer
    )

    /** 可复用的出站端点：同一台服务器 + 同一个本地端口。 */
    private data class Endpoint(val server: StunServer, val localPort: Int)

    /**
     * [keepAlive] 的结束原因。
     *
     * 区分这两种失败是地址稳定性的关键：`SessionClosed` 只说明那条 TCP 连接没了
     * （有的服务器每回应一次就关连接），此时**监听和玩家连接都还活着**，换个连接
     * 就行；`Failed` 才是 NAT 映射真失效，必须完整重建。
     */
    private enum class KeepAliveEnd {
        /** 对端关闭了这条 TCP 长连接 —— 只需同端口重连。 */
        SessionClosed,

        /** 保活连续失败，判定映射已失效 —— 需要完整重建。 */
        Failed,

        /** 世代号变了（stop / 新一轮启动） —— 直接退出。 */
        Stopped
    }

    /**
     * 主循环：**优先原地复用端口重连** → 探测 → 绑定监听 → 保活 → （失效则）重建。
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
        // 连续「刚建立就断」的次数，用于对反复掐连接的服务器退让（见重建前的延时选择）。
        var rapidRebuilds = 0
        while (generation.get() == myGeneration) {
            // 先试「同服务器 + 同本地端口」重连：只有这条路可能保住公网地址不变。
            // 并行探测是保底手段——它必然拿到一个新的随机本地端口，公网端口随之改变。
            val previous = lastGood
            var established: Established? = null
            if (previous != null) {
                established = reconnectSameEndpoint(previous, myGeneration)
                if (established == null) {
                    log("[stun] 端口复用失败（本地端口 ${previous.localPort} 或服务器不可用），改用并行探测")
                }
            }
            if (established == null) established = probe(servers, myGeneration)
            if (generation.get() != myGeneration) break

            if (established == null) {
                val diag = _diagnostics.value
                val cause = diag.failCategory
                // 确定性失败：拿到的是内网/CGNAT 地址，再重试多少轮结果都一样。
                // 直接判死，否则用户要白等 3 轮 × 15 秒才看到一句含糊的失败。
                if (cause == StunFailCategory.NonPublicAddress) {
                    failAndCleanup(failureMessageFor(cause, diag), myGeneration)
                    return
                }
                failures++
                if (failures >= maxConsecutiveFailures) {
                    failAndCleanup(failureMessageFor(cause, diag), myGeneration)
                    return
                }
                log("[stun] 本轮探测失败（${cause}），15 秒后重试（$failures/$maxConsecutiveFailures）")
                updateState(TunnelStatus.Starting, "")
                if (!sleepInterruptible(retryProbeDelayMs, myGeneration)) return
                continue
            }

            var activeSession = established.session
            val mapped = established.mapped
            // 记下这一次的端点供下次重建复用（放在 bindListener 之前：即便监听失败，
            // 这条连接的本地端口仍是我们想要保住的端口）。
            lastGood = Endpoint(established.server, activeSession.localPort)
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
                updateDiagnostics {
                    it.copy(
                        verdict = StunVerdict.BindFailed,
                        failCategory = StunFailCategory.BindFailed,
                        publicAddress = "",
                        localPort = 0
                    )
                }
                if (failures >= maxConsecutiveFailures) {
                    failAndCleanup("无法在映射端口上建立监听，隧道启动失败", myGeneration)
                    return
                }
                if (!sleepInterruptible(retryBindDelayMs, myGeneration)) return
                continue
            }

            failures = 0
            val previousAddress = lastPublished
            published = mapped
            lastPublished = mapped
            val url = "tcp://${mapped.host}:${mapped.port}"
            log("[stun] 隧道已就绪：tcp://127.0.0.1:$targetPort ← tcp://0.0.0.0:${activeSession.localPort}（公网 $url）")
            // 地址是否真的保住了，是「玩家手里的地址还能不能用」的直接答案，
            // 必须单独留一行日志：重建有两条路（复用端口 / 并行探测），结果不同。
            when {
                previousAddress == null -> Unit
                previousAddress == mapped -> log("[stun] 公网地址保持不变：$mapped（玩家无需换地址）")
                else -> log("[stun] 公网地址已变化：$previousAddress → $mapped（玩家需要用新地址）")
            }
            if (config.stunProxyProtocol) {
                log("[stun] 已启用 PROXY protocol v2：服务端必须同时开启该支持，否则玩家将无法进入")
            }
            updateState(TunnelStatus.Running, url, "")

            startAcceptLoop(config, targetPort, myGeneration)

            // ── 会话级自愈循环 ──────────────────────────────────────────
            // 有些 STUN 服务器（实测 stun.telnyx.com）**每回应一次就主动关掉 TCP
            // 连接**。这并不影响 NAT 映射，玩家的入站流量走的是运营商 NAT 里
            // 「外部端口 → 本地端口 P」那条映射，与这条连接的存活无关。所以这里
            // 只换连接、不动监听、不动玩家连接，绝不能掉进「断开→完整重建→踢人」
            // 的几秒一轮的循环。
            var establishedAt = System.currentTimeMillis()
            while (true) {
                val outcome = keepAlive(activeSession, config, myGeneration)
                if (generation.get() != myGeneration) break
                // 只有「连接被对端断开」才值得轻量自愈；映射失效/连续超时仍走完整重建。
                if (outcome != KeepAliveEnd.SessionClosed) break

                val endpoint = lastGood
                val renewed = endpoint?.let { renewSessionOnly(it, config, targetPort, myGeneration) }
                if (renewed == null) {
                    log("[stun] 轻量重连未成功，改为完整重建")
                    break
                }
                val lifetime = System.currentTimeMillis() - establishedAt
                activeSession = renewed.session
                lastGood = Endpoint(renewed.server, renewed.session.localPort)
                establishedAt = System.currentTimeMillis()
                sessionRenewals++
                if (renewed.mapped == published) {
                    // 不逐次播报：对面若每个保活周期都掐连接，这里会每几秒一行。
                    if (shouldAnnounceRenewal(sessionRenewals)) {
                        log(
                            "[stun] 连接已恢复（第 $sessionRenewals 次，公网地址保持不变 " +
                                "${renewed.mapped}，玩家无感）"
                        )
                    }
                } else {
                    // 新连接上报的地址就是当下唯一有效的映射，就地切换（不重建）。
                    applyMappedAddress(renewed.mapped, renewed.session.localPort)
                }
                // 对面若连上就掐（存活不足 1 秒），别把它当靶子猛打。
                if (lifetime < rapidSessionMs &&
                    !sleepInterruptible(sessionRenewPauseMs, myGeneration)
                ) return
            }
            if (generation.get() != myGeneration) break

            // 刚建立就断（对面反复掐连接）要退让，避免把 STUN 服务器当靶子猛打。
            if (System.currentTimeMillis() - establishedAt < minStableSessionMs) {
                rapidRebuilds++
            } else {
                rapidRebuilds = 0
            }
            teardown()
            published = null
            updateState(TunnelStatus.Starting, "")
            log("[stun] 正在重建隧道…")
            // 有可复用的端点就尽快接回去：运营商 NAT 里那条映射还在，越早重连越可能
            // 保住同一个外部端口。连续快断则退回常规间隔。
            val delayMs = when {
                rapidRebuilds >= maxRapidRebuilds -> rebuildDelayMs * 2
                lastGood != null -> rebuildFastMs
                else -> rebuildDelayMs
            }
            if (!sleepInterruptible(delayMs, myGeneration)) return
        }
    }

    /**
     * 用「同一台服务器 + 同一个本地端口」把隧道接回来。
     *
     * 这是整套实现里**唯一**能让公网地址不变的自愈路径：本地端口与目的地址都不变 ⇒
     * 出站五元组与之前完全一致 ⇒ NAT 只能沿用那条既有映射 ⇒ 外部端口照旧。
     * 走 [probe] 则会拿到内核新分配的随机本地端口，公网端口必然跟着变——这正是
     * 「保活开着、过一阵还是换端口」的成因。
     *
     * 只试一次、不在这里重试：失败立刻回落并行探测。重建时长直接等于玩家掉线时长，
     * 不值得为「可能省下一个新端口」再等一轮。
     */
    private fun reconnectSameEndpoint(
        previous: Endpoint,
        myGeneration: Int,
        announce: Boolean = true
    ): Established? {
        if (announce) log("[stun] 尝试原地复用端口 ${previous.localPort} 重连 ${previous.server} …")
        val startedAt = System.currentTimeMillis()
        // opened 只服务于 catch 里的收尾：session 必须保持 val，否则被
        // updateDiagnostics 的 lambda 捕获后就无法智能转换成非空。
        var opened: StunTcpSession? = null
        return try {
            val session = StunTcpSession.connect(
                previous.server,
                connectTimeoutMs = probeResponseTimeoutMs,
                reuseLocalPort = previous.localPort
            )
            opened = session
            if (generation.get() != myGeneration) {
                session.close()
                return null
            }
            val mapped = session.requestMapping(probeResponseTimeoutMs)
            if (generation.get() != myGeneration) {
                session.close()
                return null
            }
            val reason = nonPublicIpv4Reason(mapped.host)
            if (reason != null) {
                log("[stun] 复用端口得到的地址不是公网地址：${mapped.host}:${mapped.port}（$reason）")
                session.close()
                return null
            }
            if (session.localPort == previous.localPort) {
                // 轻量自愈（announce=false）每个保活周期都会走一次「复用成功」，
                // 逐次播报等于每几秒刷一行。它的结果由「连接已恢复」那行代表，
                // 这里只在人为发起的重连（完整重建 / 首次）时播报。
                if (announce) {
                    log("[stun] 端口复用成功：本地端口 ${session.localPort} → 公网 ${mapped.host}:${mapped.port}")
                }
            } else {
                // bind 未能占到原端口（被抢占或内核不放行），功能不受影响，但公网端口
                // 多半会变——这里如实记录原因，不做重试（重试已在 connect 内部做过）。
                val why = session.bindNote?.let { "：$it" } ?: ""
                log("[stun] 原本地端口 ${previous.localPort} 未占到（实得 ${session.localPort}）$why，公网地址可能变化")
            }
            updateDiagnostics {
                it.copy(
                    verdict = StunVerdict.PublicDirect,
                    failCategory = StunFailCategory.None,
                    publicAddress = "${mapped.host}:${mapped.port}",
                    localPort = session.localPort,
                    nonPublicAddress = "",
                    probeDurationMs = System.currentTimeMillis() - startedAt,
                    serversTried = 1,
                    serversConnected = 1
                )
            }
            Established(session, mapped, previous.server)
        } catch (e: Throwable) {
            runCatching { opened?.close() }
            log("[stun] 原地重连失败：${e.message}")
            null
        }
    }

    /**
     * 轻量自愈：STUN 长连接被服务器掐断后，**只换连接**，监听端口与已建立的玩家
     * 连接一律不动。
     *
     * ## 为什么不能走完整重建
     *
     * 完整重建会 `teardown()` —— 关监听、关所有玩家连接、清空已公布地址、把状态
     * 打回 `Starting`。而有些 STUN 服务器（实测 `stun.telnyx.com`）**每回应一次
     * Binding Request 就主动关掉 TCP 连接**，于是「保活 → 断开 → 重建」会形成
     * 几秒一轮的循环：玩家被反复踢下线、界面反复闪「启动中」、日志被刷满。
     *
     * 但这些断开**并不影响 NAT 映射的有效性**：入站数据靠的是运营商 NAT 里
     * 「外部端口 → 本地端口 P」那条映射，跟这条 TCP 连接是否活着无关。只要
     * (a) 监听套接字仍绑在 P 上、(b) 映射没被回收，玩家就一直是通的。
     *
     * ## 为什么必须先关监听再重连（顺序不可换）
     *
     * Linux 的 bind 冲突检查：若同端口上已存在 `TCP_LISTEN` 套接字，新 bind 即使
     * 双方都带 `SO_REUSEADDR` 也冲突（见 `StunTcpSession` 类注释）；而已存在的
     * socket 处于 ESTABLISHED 时不冲突。所以这里先关监听（**保留玩家连接**，
     * 它们也是 ESTABLISHED，不挡 bind），重连成功后再把监听绑回同一端口。
     * 代价仅是新建入站连接有约 0.1–0.5 秒的重连窗口，在位玩家完全无感。
     */
    private fun renewSessionOnly(
        previous: Endpoint,
        config: McConfig,
        targetPort: Int,
        myGeneration: Int
    ): Established? {
        // ① 只摘掉监听套接字；liveSockets 里的玩家连接原样保留。
        val listener = serverSocket
        serverSocket = null
        runCatching { listener?.close() }

        // ①' 显式关掉这条已死的会话。它虽然已被对端 reset，但本地 fd 还在，
        //    会继续占着端口 P —— 不关就可能出现「端口未占到」，白白丢掉公网地址
        //    （2026-10-17 真机日志出现过一次）。
        val dead = session
        session = null
        runCatching { dead?.close() }

        // ② 同服务器 + 同本地端口重连（五元组不变 ⇒ NAT 沿用旧映射 ⇒ 外部端口不变）。
        //    重试几次而不是一次定生死：刚才关掉的那批 fd 可能还在内核里残留几十毫秒，
        //    端口/4 元组会短暂 EADDRINUSE / EADDRNOTAVAIL——一次失败就走完整重建
        //    等于白白踢掉所有玩家。
        var renewed: Established? = null
        for (attempt in 0 until renewAttempts) {
            renewed = reconnectSameEndpoint(previous, myGeneration, announce = false)
            if (renewed != null) break
            if (generation.get() != myGeneration) return null
            if (attempt < renewAttempts - 1 &&
                !sleepInterruptible(renewRetryDelayMs, myGeneration)
            ) return null
        }
        if (renewed == null) return null
        if (renewed == null) return null
        if (generation.get() != myGeneration) {
            renewed.session.close()
            return null
        }

        // ③ 把监听绑回同一端口并重新开始接受连接。
        if (!bindListener(renewed.session, myGeneration, announce = false)) {
            renewed.session.close()
            return null
        }
        startAcceptLoop(config, targetPort, myGeneration, announce = false)
        return renewed
    }

    /**
     * 并发尝试各 STUN 服务器，返回**最先**建立成功的长连接会话与公网映射；全部失败返回 null。
     *
     * 不能只看「能连上」——大量服务器只支持 UDP，TCP 连得上却永不回包，所以以
     * 「能否取回映射地址」作为可用判据。
     *
     * ## 为什么改成并发（S0.3）
     *
     * 原实现逐台串行，每台最坏「5s 连接 + 5s 等响应」。8 台全不通时用户要干等
     * 80 秒才看到一句笼统的失败。现在并发取首个成功：最坏耗时降到单台水平，
     * 并且失败会被归类写进 [diagnostics]，让界面能说清「为什么不行、该换什么」。
     */
    private fun probe(
        servers: List<StunServer>,
        myGeneration: Int
    ): Established? {
        log("[stun] 开始并行探测公网映射（共 ${servers.size} 台服务器）")
        val startedAt = System.currentTimeMillis()
        val tried = AtomicInteger(0)
        val connected = AtomicInteger(0)
        val won = AtomicBoolean(false)
        val nonPublic = AtomicReference("")
        val failures = Collections.synchronizedList(mutableListOf<StunFailCategory>())
        val lastError = AtomicReference<String?>(null)

        val pool = Executors.newFixedThreadPool(
            minOf(servers.size, maxProbeConcurrency),
            probeThreadFactory
        )
        val done = ExecutorCompletionService<Established?>(pool)
        servers.forEach { server ->
            done.submit(Callable {
                if (won.get()) return@Callable null
                tried.incrementAndGet()
                var candidate: StunTcpSession? = null
                try {
                    candidate = StunTcpSession.connect(server)
                    connected.incrementAndGet()
                    if (won.get()) {
                        candidate.close()
                        return@Callable null
                    }
                    val mapped = candidate.requestMapping(probeResponseTimeoutMs)
                    // 复查：requestMapping 是阻塞等待，返回后本轮可能已被取代。
                    if (generation.get() != myGeneration || won.get()) {
                        candidate.close()
                        return@Callable null
                    }
                    val reason = nonPublicIpv4Reason(mapped.host)
                    if (reason != null) {
                        nonPublic.set("${mapped.host}:${mapped.port}（$reason）")
                        failures.add(StunFailCategory.NonPublicAddress)
                        log("[stun] $server 返回的地址不是公网地址：${mapped.host}:${mapped.port}（$reason）")
                        candidate.close()
                        return@Callable null
                    }
                    // 抢占先手：抢到的这台成为结果，其余分支自行关闭退出。
                    if (!won.compareAndSet(false, true)) {
                        candidate.close()
                        return@Callable null
                    }
                    log("[stun] 探测成功：$server → 本地端口 ${candidate.localPort} → 公网 ${mapped.host}:${mapped.port}")
                    Established(candidate, mapped, server)
                } catch (e: Throwable) {
                    runCatching { candidate?.close() }
                    failures.add(classifyProbeFailure(e))
                    lastError.compareAndSet(null, "$server 不可用：${e.message}")
                    null
                }
            })
        }

        // 逐台收取完成结果：谁先完成谁先被取到。
        // 第一台成功即结束；全部失败则收满 servers.size 次后自然退出。
        var winner: Established? = null
        var remaining = servers.size
        val deadline = startedAt + probeBudgetMs
        while (winner == null && remaining > 0) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            val future = try {
                done.poll(left, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } ?: break
            remaining--
            try {
                winner = future.get()
            } catch (e: ExecutionException) {
                // 任务体已把失败原因记进 failures，这里只需继续收下一台。
                log("[stun] 探测任务异常：${e.cause?.message}")
            }
        }
        // 已有结果（或已决定放弃）就立刻收工，不等其余服务器把超时耗满。
        won.set(true)
        pool.shutdownNow()

        val elapsed = System.currentTimeMillis() - startedAt
        if (winner != null) {
            val session = winner.session
            val mapped = winner.mapped
            updateDiagnostics {
                it.copy(
                    verdict = StunVerdict.PublicDirect,
                    failCategory = StunFailCategory.None,
                    publicAddress = "${mapped.host}:${mapped.port}",
                    localPort = session.localPort,
                    nonPublicAddress = "",
                    probeDurationMs = elapsed,
                    serversTried = tried.get(),
                    serversConnected = connected.get()
                    // portStable 在此**不重置**：它描述的是「本次会话内端口是否变过」，
                    // 每次都重置的话，重建后就永远看不到端口已漂移这个事实。
                )
            }
            return winner
        }

        val category = dominantFailure(failures)
        updateDiagnostics {
            it.copy(
                verdict = if (category == StunFailCategory.NonPublicAddress) {
                    StunVerdict.NonPublicAddress
                } else {
                    StunVerdict.Unreachable
                },
                failCategory = category,
                publicAddress = "",
                localPort = 0,
                nonPublicAddress = nonPublic.get(),
                probeDurationMs = elapsed,
                serversTried = tried.get(),
                serversConnected = connected.get()
            )
        }
        log(
            "[stun] 探测结束：${servers.size} 台全部失败（$category），耗时 ${elapsed}ms" +
                (lastError.get()?.let { "；最后错误：$it" } ?: "")
        )
        return null
    }

    /** 把探测异常归入失败分类，供界面给出具体原因。 */
    internal fun classifyProbeFailure(e: Throwable): StunFailCategory {
        val message = e.message.orEmpty()
        return when {
            message.contains("超时") -> StunFailCategory.NoResponse
            message.contains("返回错误") -> StunFailCategory.ServerRejected
            message.contains("没有可用") -> StunFailCategory.NoMapping
            else -> StunFailCategory.Unreachable
        }
    }

    /**
     * 从全部失败里挑出「最有信息量」的那一类。
     *
     * 优先级不是随便定的：只要有一台服务器明确反馈「你本机地址是非公网」，那就是
     * 本次探测最有价值的结论（问题在运营商，换服务器也没用）；其次是服务器侧明确
     * 拒绝、取不到映射，最后才是笼统的连不上。
     */
    internal fun dominantFailure(categories: List<StunFailCategory>): StunFailCategory {
        if (categories.isEmpty()) return StunFailCategory.Unreachable
        return failurePriority.firstOrNull { categories.contains(it) } ?: StunFailCategory.Unreachable
    }

    /** 把失败分类翻译成给用户看的结论（同时用于日志与 errorMessage）。 */
    internal fun failureMessageFor(cause: StunFailCategory, diag: StunDiagnostics): String =
        when (cause) {
            StunFailCategory.NonPublicAddress ->
                "本机公网地址是内网/CGNAT 地址（${diag.nonPublicAddress}），外网无法访问，请改用 frp 或平台隧道"
            StunFailCategory.NoResponse ->
                "STUN 服务器 TCP 可达但均无响应（多为只支持 UDP 的服务器），请更换自定义服务器"
            StunFailCategory.ServerRejected -> "STUN 服务器拒绝了请求，请更换自定义服务器"
            StunFailCategory.NoMapping -> "STUN 服务器未返回可用的映射地址，请更换自定义服务器"
            else ->
                "全部 STUN 服务器均无法连接，当前网络可能不支持（对称 NAT / CGNAT），请改用 frp 或平台隧道"
        }

    /** 把监听套接字绑定到 STUN 会话所用的本地端口。成功返回 true。 */
    private fun bindListener(
        activeSession: StunTcpSession,
        myGeneration: Int,
        announce: Boolean = true
    ): Boolean {
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
            // 轻量重连时每个保活周期都会绑一次，重复播报只会把日志刷满。
            if (announce) log("[stun] 已监听映射端口 $listenPort")
            true
        } catch (e: Exception) {
            runCatching { listener.close() }
            log("[stun] 在映射端口 $listenPort 上建立监听失败：${e.message}")
            false
        }
    }

    /** 接受入站连接。用世代号判定存活，被取代即退出。 */
    private fun startAcceptLoop(
        config: McConfig,
        targetPort: Int,
        myGeneration: Int,
        announce: Boolean = true
    ) {
        val listener = serverSocket ?: return
        val maxConnections = config.stunMaxConnections
        val proxyProtocol = config.stunProxyProtocol
        val showConnLog = config.stunShowConnectionLog
        thread(name = "stun-accept", isDaemon = true) {
            if (announce) log("[stun] 开始接受入站连接")
            while (generation.get() == myGeneration) {
                val client: Socket = try {
                    listener.accept()
                } catch (e: Throwable) {
                    // 捕获 Throwable：这条线程上的任何逃逸异常都会终结进程。
                    // 被取代（stop / 轻量重连换监听）时静默退出，不刷无意义的错误日志。
                    // 判据是 serverSocket 已不再指向自己：轻量重连会先摘引用再关它。
                    if (generation.get() == myGeneration && serverSocket === listener) {
                        log("[stun] 接受连接出错：${e.message}")
                    }
                    break
                }
                val remote = "${client.inetAddress?.hostAddress}:${client.port}"
                if (activeConnections.get() >= maxConnections) {
                    // 上限拒绝数要单独计数：这也是「有人拿空连接占坑」的可观测信号。
                    limitRejects.incrementAndGet()
                    if (showConnLog) log("[stun] 拒绝 $remote：已达并发上限 $maxConnections")
                    runCatching { client.close() }
                    syncCounters()
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
     * **返回 [KeepAliveEnd.SessionClosed] 只意味着「这条连接没了」**，不是隧道坏了：
     * 主循环会用 [renewSessionOnly] 在同端口重连，监听与玩家连接都不动。
     * 只有 [KeepAliveEnd.Failed]（连续失败判定映射失效）才走完整重建。
     * **地址变化不算**（见下），它在这里就地切换。
     *
     * ## 为什么单次失败不重建（地址稳定性的关键）
     *
     * 重建的代价不只是几秒掉线：走 [probe] 必然换一个本地端口，公网端口随之改变，
     * 玩家手里的地址就此作废。而保活失败在移动网络下**经常只是丢了一个包**——
     * 尤其是上行被占满时（玩家进服、拉区块），一次响应超时完全可能只是排队造成的。
     * 所以单次超时先原地重试，只有连续 [keepAliveRetries] 次失败才认定映射失效。
     * 连接真的断了（`isClosed`）则无需重试——那条连接已经没法保活了，只能重建。
     *
     * ## 地址变化就地切换，不重建
     *
     * 同一条连接上 STUN 刚报出的地址就是**当下唯一有效**的映射；重建只会把它丢掉、
     * 换一个随机端口，顺带把玩家全部踢下线。所以这里改地址、不断连接。
     */
    private fun keepAlive(
        activeSession: StunTcpSession,
        config: McConfig,
        myGeneration: Int
    ): KeepAliveEnd {
        val intervalMs = config.stunKeepAliveSeconds.coerceAtLeast(5) * 1000L
        // 当前生效的公网地址。就地切换后要跟着更新，否则下一次保活会反复报「变化」。
        var baseline = published
        // 连续失败计数：任一次成功即清零。用于区分「偶发丢包」与「映射真的没了」。
        var consecutiveFailures = 0
        while (generation.get() == myGeneration) {
            // 常态按用户设定的间隔；刚失败过则缩短间隔原地重试。
            val waitMs = if (consecutiveFailures == 0) intervalMs else keepAliveRetryDelayMs
            // 等连接断开或等满间隔——用 awaitClosed 而不是 sleep：连接一断就立刻醒来
            // 去重连。**重连越早，运营商 NAT 里那条映射还在的概率越高**，端口才保得住；
            // 若傻等 20 秒再发现，映射往往已被回收，重建只能拿到新端口。
            val closedReason = try {
                activeSession.awaitClosed(waitMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return KeepAliveEnd.Stopped
            }
            if (generation.get() != myGeneration) return KeepAliveEnd.Stopped
            if (closedReason != null || activeSession.isClosed) {
                // 这里**刻意不写日志**。
                //
                // 「连接断开」与「同端口重连成功」是同一件事的两半，中间没有任何用户
                // 可采取的动作：映射不受影响、监听不中断、玩家不掉线。上一版把两半都
                // 打出来（`保活连接已断开` + `连接已恢复`），结果是每个保活周期两行，
                // 96 次自愈就刷 32 对。现在只由主循环在成功接回后播一行
                // `连接已恢复（第 N 次…）`，失败路径另有
                // `原地重连失败` / `轻量重连未成功，改为完整重建` 兜底 ——
                // 真正出问题时不会因为这里静音而看不见。
                return KeepAliveEnd.SessionClosed
            }
            try {
                val refreshed = activeSession.requestMapping(timeoutMs = keepAliveResponseTimeoutMs)
                consecutiveFailures = 0
                if (refreshed != baseline) {
                    log("[stun] 检测到公网地址变化：$baseline → $refreshed")
                    // 地址变了**不等于隧道坏了**：同一条连接上 STUN 刚报出的地址就是
                    // 当下唯一有效的映射。此处就地切换——不重建、不断开玩家，只把新
                    // 地址公布出去。（旧实现一律 return → 重建，于是玩家被踢掉，
                    // 还要再经历一次随机端口。）
                    baseline = refreshed
                    applyMappedAddress(refreshed, activeSession.localPort)
                }
                syncCounters()
            } catch (e: Throwable) {
                // 捕获 Throwable：这条线程上的任何逃逸异常都会终结进程。
                //
                // ⚠️ 先分清这次失败是「连接已经没了」还是「包丢了」：
                // 公开 STUN 服务器（实测 stun.sonetel.com / telnyx）对每次 Binding
                // Response 之后**立刻 RST 这条 TCP**，于是下一次保活必然抛
                // `Connection reset` —— 而这条连接的读线程早已把它判死（isClosed）。
                // 这种情形既不是丢包，3 秒原地重试也必然失败，还会让日志里出现
                // 「视为偶发丢包」这种与事实相反的措辞。直接去同端口重连：
                // 越早接回，运营商 NAT 里那条映射还在的概率越高。
                if (activeSession.isClosed) {
                    // 与 awaitClosed 那条路径同样刻意静默：这一次失败只是「连接
                    // 被对端 RST 了」的可观测后果，不是新信息。成功接回后主循环
                    // 会播一行 `连接已恢复`。
                    return KeepAliveEnd.SessionClosed
                }
                consecutiveFailures++
                if (consecutiveFailures <= keepAliveRetries) {
                    log(
                        "[stun] 保活探测失败（第 $consecutiveFailures 次，视为偶发丢包）：${e.message}；" +
                            "${keepAliveRetryDelayMs / 1000} 秒后原地重试"
                    )
                    continue
                }
                log("[stun] 保活探测连续失败 $consecutiveFailures 次，判定映射失效：${e.message}")
                return KeepAliveEnd.Failed
            }
        }
        return KeepAliveEnd.Stopped
    }

    /** 就地切换公布的公网地址：不重建、不断开任何连接，只更新地址与诊断。 */
    private fun applyMappedAddress(refreshed: StunMappedAddress, localPort: Int) {
        val before = published
        if (before == refreshed) return
        // 端口变了意味着玩家手里的地址彻底作废；只有 IP 变而端口不变时才谈得上
        // 「无感续命」。这一项正是判断该问题严重程度的依据。
        val portChanged = before == null || refreshed.port != before.port
        updateDiagnostics {
            it.copy(
                addressChanges = it.addressChanges + 1,
                lastAddressChangeAt = System.currentTimeMillis(),
                portStable = it.portStable && !portChanged,
                // 卡片上的地址取自 diagnostics，不跟着改就会一直显示旧地址。
                publicAddress = "${refreshed.host}:${refreshed.port}",
                localPort = localPort
            )
        }
        published = refreshed
        lastPublished = refreshed
        val newUrl = "tcp://${refreshed.host}:${refreshed.port}"
        updateState(TunnelStatus.Running, newUrl, "")
        log("[stun] 已就地切换到新地址：$before → $refreshed（连接未中断，玩家需换用新地址）")
    }

    /**
     * 单条入站连接：等首字节 → 连本地目标 → 可选插入 PROXY 头 → 双向透传。
     *
     * ## 为什么要等首字节（S0.1）
     *
     * 入站端口是**公网可达**的，任何人都能连。此前连上就立刻建线程并连着本地目标，
     * 于是端口扫描器只要开满 `maxConnections` 条「只连不发」的连接，就能把并发配额
     * 占满，真实玩家全部被拒 —— 而且这种占用会无限期持续（每条连接都阻塞在 read 上）。
     *
     * 现在改为：连上后必须在 [firstByteTimeoutMs] 内发出第一个字节。MC 客户端连上
     * 即发 Handshake，正常玩家不会受影响；不发数据的连接会被直接回收。
     * 首字节到达后立刻复位 `soTimeout`，避免误伤挂机中的长连接。
     */
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
        // pump 两条方向各自写入断开原因，用于把「异常断开」与「正常断开」区分开。
        val closeReason = AtomicReference<String?>(null)
        try {
            client.tcpNoDelay = true

            // ── 首字节超时 ────────────────────────────────────────
            val clientInput: InputStream = try {
                client.soTimeout = firstByteTimeoutMs
                val buffered = PushbackInputStream(client.getInputStream(), 8)
                val first = buffered.read()
                if (first < 0) throw EOFException("对端在发送任何数据前关闭了连接")
                // 读到的这一个字节必须还回去：它多半是 MC Handshake 的第一个字节。
                buffered.unread(first)
                buffered
            } finally {
                // 无论成功与否都要复位：长连接不能带着读超时跑（挂机会被误杀）。
                runCatching { client.soTimeout = 0 }
            }

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
            // ⚠️ 两条流都必须在**建线程之前**取好。把 getInputStream() 写进线程体里会有
            // 与 stun-reader 完全相同的竞态：上行先结束时会 close 两端 socket，而此时
            // 下行线程可能还没被调度到，对已关闭的 socket 取流会抛 SocketException，
            // 逃出线程即杀进程（2026-10-16 崩溃的同类成因）。
            val targetInput = target.getInputStream()
            // 与 EdgeCube 的 Future.any 一致：**任一方向结束**即视为连接结束，
            // 由 finally 关闭两端让另一条 pump 退出。若两个都 join，客户端关闭后
            // 下行可能仍阻塞在 read 上，连接会迟迟不释放。
            val up = thread(name = "stun-up-$id", isDaemon = true) {
                pump(clientInput, target, upload = true, closeReason)
            }
            val down = thread(name = "stun-down-$id", isDaemon = true) {
                pump(targetInput, client, upload = false, closeReason)
            }
            // 等任一线程结束：pump 结束时已 shutdownOutput，另一方向多半也会随即结束。
            while (up.isAlive && down.isAlive) {
                Thread.sleep(50)
            }
            runCatching { client.close() }
            runCatching { target.close() }
            up.join()
            down.join()
        } catch (e: SocketTimeoutException) {
            idleTimeouts.incrementAndGet()
            closeReason.set("${firstByteTimeoutMs / 1000} 秒内未发送任何数据（疑似扫描器）")
            if (showConnLog) log("[stun] 丢弃 $remote：${closeReason.get()}")
            syncCounters()
        } catch (e: Throwable) {
            closeReason.compareAndSet(null, "转发失败：${e.message}")
            if (showConnLog) log("[stun] $remote 转发失败：${e.message}")
        } finally {
            liveSockets.remove(client)
            local?.let { liveSockets.remove(it) }
            runCatching { client.close() }
            runCatching { local?.close() }
            if (showConnLog) log("[stun] 断开连接 $remote（${closeReason.get() ?: "连接关闭"}）")
        }
    }

    /**
     * 单向搬运字节并累计流量，同时记录断开原因。
     *
     * 与 EdgeCube 的 `addStream` 一致，走「读一块写一块并 flush」的循环：写操作本身
     * 是阻塞的，对端消费慢时自然产生背压，数据不会在内存里无限堆积。
     *
     * 原来的实现 `catch (_: Throwable)` 把一切异常都吞成静默，导致日志里
     * 「对端正常断开」「连接被重置」「本地 teardown」长得一模一样。现在分类记录，
     * 排障时才有抓手。
     */
    private fun pump(
        from: InputStream,
        to: Socket,
        upload: Boolean,
        closeReason: AtomicReference<String?>
    ) {
        try {
            val output = to.getOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = from.read(buf)
                if (n < 0) {
                    closeReason.compareAndSet(null, "对端正常关闭")
                    break
                }
                output.write(buf, 0, n)
                output.flush()
                if (upload) totalUpload.addAndGet(n.toLong()) else totalDownload.addAndGet(n.toLong())
            }
        } catch (e: SocketTimeoutException) {
            closeReason.compareAndSet(null, "读超时")
        } catch (e: IOException) {
            val message = e.message.orEmpty()
            closeReason.compareAndSet(
                null,
                when {
                    message.contains("Socket closed", ignoreCase = true) -> "本地关闭"
                    message.contains("reset", ignoreCase = true) -> "对端重置连接"
                    else -> "传输中断：$message"
                }
            )
        } catch (_: Throwable) {
            closeReason.compareAndSet(null, "未知错误")
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
                // 单纯写 StateFlow 理论上不会抛，但这条线程同样不能放任何异常出去。
                runCatching { tickSpeed() }
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
        syncDiagnosticsCounters()
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
        idleTimeouts.set(0)
        limitRejects.set(0)
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
        syncDiagnosticsCounters()
    }

    /** 连接类计数同步进诊断流（STUN 卡片要展示「已回收空连接 / 已达上限拒绝」）。 */
    private fun syncDiagnosticsCounters() {
        updateDiagnostics {
            it.copy(
                activeConnections = activeConnections.get(),
                idleTimeouts = idleTimeouts.get(),
                limitRejects = limitRejects.get()
            )
        }
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

        /**
         * 有可复用端点时的重建间隔。
         *
         * 要的是「尽快接回去」：运营商 NAT 里那条映射还在，同一本地端口 + 同一服务器
         * 才可能拿到同一个外部端口。慢一秒就少一分把握。
         */
        const val rebuildFastMs = 800L

        /** 会话存活不足这么久就断，算「对面在反复掐」，连续 [maxRapidRebuilds] 次后退让。 */
        const val minStableSessionMs = 15_000L

        /** 轻量重连后会话活不过这么久，也算「对面在掐」，稍作停顿再连。 */
        const val rapidSessionMs = 1_000L

        /** 对面连上就掐时的让步间隔，避免把自己变成对方的压力源。 */
        const val sessionRenewPauseMs = 1_000L

        /** 轻量重连的尝试次数与间隔：刚关掉的 fd 在内核里可能还残留几十毫秒。 */
        const val renewAttempts = 3
        const val renewRetryDelayMs = 200L
        const val maxRapidRebuilds = 2

        /**
         * 保活单次失败后容忍的次数与重试间隔。
         *
         * 移动网络下保活超时多半只是丢包（上行被玩家流量占满时尤其明显），而一次
         * 误判的代价是「重建 → 换公网端口 → 玩家掉线且地址作废」。所以先原地重试
         * 两次再认定映射失效。
         */
        const val keepAliveRetries = 2
        const val keepAliveRetryDelayMs = 3_000L

        /** 保活等响应的时间。给得比探测宽：保活时隧道本身可能正在满速转发。 */
        const val keepAliveResponseTimeoutMs = 10_000

        /**
         * 并行探测的并发度上限。8 条 TCP 对一个 App 来说开销很小，
         * 但没必要无限开——服务器列表是用户可编辑的，可能很长。
         */
        const val maxProbeConcurrency = 8

        /** 单台服务器等待 STUN 响应的超时。 */
        const val probeResponseTimeoutMs = 5000

        /**
         * 一轮并行探测的总预算。单台最坏是「连接 5s + 等响应 5s」，并发后
         * 整体就是单台水平；这里再留一点余量给慢服务器。
         */
        const val probeBudgetMs = 14_000L

        /** 入站连接在发出首字节之前的等待上限（S0.1）。 */
        const val firstByteTimeoutMs = 10_000

        /**
         * 失败分类的信息量排序，越靠前越有结论价值（决定界面上展示哪一条原因）。
         */
        val failurePriority = listOf(
            StunFailCategory.NonPublicAddress,
            StunFailCategory.ServerRejected,
            StunFailCategory.NoMapping,
            StunFailCategory.NoResponse,
            StunFailCategory.Unreachable
        )

        /** 探测线程设为守护线程：探测尚未结束时进程退出不该被拖住。 */
        private val probeThreadFactory = ThreadFactory { runnable ->
            Thread(runnable, "stun-probe").apply { isDaemon = true }
        }
    }
}

// ── 会话自愈的日志节流 ────────────────────────────────────────

/**
 * 第 [index] 次（从 1 开始）轻量重连是否要写日志。
 *
 * ## 为什么需要节流
 *
 * 大量公开 STUN 服务器对每个 Binding Response 之后**立刻 RST 这条 TCP**
 * （2026-10-17 真机实测 `stun.sonetel.com`：每个保活周期都报 `Connection reset`）。
 * 于是「保活 → 连接已断 → 同端口重连」会**每个保活周期跑一遍**。按默认 20 秒
 * 间隔算，不节流就是每分钟 3 行以上，日志变成停不下来的滚动条；可一旦全静音，
 * 用户又看不出隧道还在自愈，反而会怀疑卡死了。
 *
 * 所以取「前几次逐条播报 + 之后每 N 次一条」：前 [RENEW_ANNOUNCE_FIRST] 次
 * 让用户确认自愈链路确实在工作，之后按 [RENEW_ANNOUNCE_EVERY] 次（约 2 分钟）
 * 一行当心跳。
 *
 * 抽成顶层可见函数而不是留在类里，是为了让 JVM 单测能直接断言这套边界——
 * 日志节奏没法在真机上回归，只能靠单测锁住。
 */
internal fun shouldAnnounceRenewal(index: Int): Boolean =
    index <= RENEW_ANNOUNCE_FIRST || index % RENEW_ANNOUNCE_EVERY == 0

/** 前几次轻量重连逐条播报。 */
private const val RENEW_ANNOUNCE_FIRST = 3

/** 之后每多少次播一条（配合 20 秒保活间隔 ≈ 2 分钟一行）。 */
private const val RENEW_ANNOUNCE_EVERY = 6
