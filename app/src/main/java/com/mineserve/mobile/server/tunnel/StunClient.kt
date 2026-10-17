package com.mineserve.mobile.server.tunnel

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.StandardSocketOptions
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * STUN（RFC 5389）over TCP 的最小实现，用于 NAT1（全锥形 NAT）打洞。
 *
 * 与常见的 STUN over UDP 不同，这里刻意走 **TCP**：隧道要接受的是 TCP 入站
 * 连接（Minecraft Java 版），只有用 TCP 向 STUN 服务器发起探测，NAT 才会为
 * 「本地 TCP 端口 P」建立映射，随后外部才能经由该映射连回本机的监听端口。
 */
object StunClient {

    /** STUN 默认端口。 */
    const val DEFAULT_PORT = 3478

    /**
     * 内置的 STUN 服务器列表。
     *
     * 列表内每一台都经实网验证支持 **TCP** 3478 —— 大量常见 STUN 服务器
     * （stun.l.google.com、stun.miwifi.com、stun.qq.com、stun.ekiga.net 等）
     * 只提供 UDP，TCP 连得上却永远不回包，放进列表只会白等一轮超时。
     * 顺序即尝试顺序。
     *
     * ⚠️ **刻意不放 TURN 服务器**（原先列表里的 `turn.cloudflare.com`、
     * `global.turn.twilio.com` 已移除）。它们同样会应答 Binding Request，看上去
     * 完全可用，但那是 **TURN** 不是 STUN：连接被当作一次「未认证的会话」管理，
     * 过一阵就会被服务端单方面关掉。对一次性的探测无所谓，对本功能却致命 ——
     * 隧道把这条连接当作**保活长连接**用（见 [StunTcpSession] 的说明），被掐一次
     * 就得整条重建、公网端口随之改变。2026-10-16 的隧道日志正是如此：
     * 两次探测都由 `global.turn.twilio.com` 胜出，随后「保活连接已断开」。
     */
    val DEFAULT_SERVERS = listOf(
        "fwa.lifesizecloud.com",
        "stun.freeswitch.org",
        "stun.nextcloud.com",
        "stun.telnyx.com",
        "stun.sonetel.com",
        "stun.voip.blackberry.com"
    )

    private const val MAGIC_COOKIE_1 = 0x21
    private const val MAGIC_COOKIE_2 = 0x12
    private const val MAGIC_COOKIE_3 = 0xA4
    private const val MAGIC_COOKIE_4 = 0x42

    /** 单条 STUN 消息不会很大；超出上限说明对端根本不是 STUN 服务器。 */
    private const val MAX_BUFFER = 8192

    internal const val HEADER_SIZE = 20

    internal val MAGIC_COOKIE: ByteArray = byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42)

    private val random = SecureRandom()

    /**
     * 解析一行服务器配置：接受 `host`、`host:port`，以及带 `stun://` 前缀的写法。
     *
     * 返回 null 表示这一行无法使用（空行、端口非法、端口越界），调用方应跳过而不是报错——
     * 用户手写的服务器列表里混进个别错行，不该让整条隧道起不来。
     */
    fun parseServer(line: String): StunServer? {
        val trimmed = line.trim()
            .removePrefix("stun://")
            .removePrefix("turn://")
            .trim()
            .trimEnd('/')
        if (trimmed.isEmpty()) return null
        val host: String
        val port: Int
        if (trimmed.contains(':')) {
            val idx = trimmed.lastIndexOf(':')
            host = trimmed.substring(0, idx).trim()
            val portText = trimmed.substring(idx + 1).trim()
            port = portText.toIntOrNull() ?: return null
            if (port !in 1..65535) return null
        } else {
            host = trimmed
            port = DEFAULT_PORT
        }
        if (host.isEmpty()) return null
        return StunServer(host, port)
    }

    /** 从整段文本解析服务器列表（每行一条），自动去重、保序。 */
    fun parseServers(text: String): List<StunServer> =
        text.lineSequence()
            .mapNotNull { parseServer(it) }
            .distinct()
            .toList()
}

/** 一台 STUN 服务器。 */
data class StunServer(val host: String, val port: Int = StunClient.DEFAULT_PORT) {
    override fun toString(): String = if (port == StunClient.DEFAULT_PORT) host else "$host:$port"
}

/** STUN 返回的公网映射地址（本机某个本地端口在 NAT 外侧的样子）。 */
data class StunMappedAddress(val host: String, val port: Int) {
    override fun toString(): String = "$host:$port"
}

/** STUN 协议层面的错误（超时、响应不合法、服务器返回 ERROR-CODE 等）。 */
class StunException(message: String) : IOException(message)

/**
 * 与单台 STUN 服务器之间的一条 **长连接** TCP 会话。
 *
 * ## 为什么必须保持长连接
 *
 * NAT 映射由出站流量创建，并在空闲一段时间后回收。要让映射一直有效，就得
 * 持续有流量经过「本地端口 P」。Windows 版 MSL 的做法是每 15 秒用 P 作为源
 * 端口新建一条到公网 80 端口的连接；但该做法在 **Linux/Android 上不可行**：
 *
 * - Linux 内核的 bind 冲突检查中，若已存在的同端口 socket 处于 `TCP_LISTEN`
 *   状态，则新 bind 必须双方都带 `SO_REUSEPORT` 才不冲突。而 Android 的
 *   `ServerSocket` 默认只设 `SO_REUSEADDR` 不设 `SO_REUSEPORT`；
 * - 反过来，若已存在的 socket 处于 **ESTABLISHED** 且双方都带 `SO_REUSEADDR`，
 *   则不冲突——`ServerSocket.bind` 在 POSIX 上默认设置 `SO_REUSEADDR`。
 *
 * 因此这里改为：**探测用的这条连接本身就是保活连接**，连上后立刻给它打上
 * `SO_REUSEADDR`，使随后把监听套接字绑定到同一本地端口 P 得以成功；之后周期性
 * 在这条连接上重发 Binding Request，既保活了 NAT 映射，又能顺带发现公网地址变化。
 *
 * 所有阻塞 IO 都在调用方指定的线程上执行，本类自身不创建线程。
 */
class StunTcpSession private constructor(
    private val socket: Socket,
    /** 服务器标识，仅用于日志。 */
    val server: String,
    /**
     * 想要复用旧本地端口却没占到时，内核给出的原因。
     *
     * `null` = 没尝试复用，或复用成功。日志需要它才能说清「公网地址为什么变了」——
     * 光写「实得 34808」看不出是端口被占、还是 bind 被内核拒绝。
     */
    val bindNote: String? = null
) {

    companion object {
        /** 单条 STUN 消息不会很大，超出上限说明对端不是 STUN 服务器。 */
        private const val MAX_READ_BUFFER = 8192
        /**
         * 建立会话：向 STUN 服务器发起 TCP 连接，并完成端口复用准备。
         *
         * ## 关于本地端口
         *
         * 这里**刻意不指定**本地端口，由内核分配一个临时端口 P。NAT 会为这个 P
         * 建立映射，随后隧道就把 `ServerSocket` 绑到 P 上——**入站连接到达的是 P，
         * 转发目标是 MC 服务端端口，两者不同**。（早先的版本试图把探测连接绑到
         * MC 端口上，那是错的：MC 服务端正监听该端口，bind 必然 EADDRINUSE。）
         *
         * ## 为什么连接后要设 SO_REUSEADDR
         *
         * 映射建立后，服务端 socket 还要把 `ServerSocket` 绑到**同一个本地端口 P**。
         * Linux 内核的 bind 冲突检查在既有 socket 处于 ESTABLISHED 时，要求双方都带
         * `SO_REUSEADDR`（`ServerSocket.bind` 默认会设，探测连接需要手动补）。
         * 因此在 connect 之后立刻补设，后续绑定才能成功。
         *
         * 失败时抛 [StunException]，由上层换一台服务器重试。
         *
         * ## [reuseLocalPort]：保住公网端口的关键
         *
         * 指定本地端口时，新连接会**先 `bind` 到该端口再 connect**。这一步是
         * 「隧道重建后公网地址不变」的唯一手段：本地端口相同 + STUN 服务器相同
         * 意味着五元组完全相同，NAT 只能复用既有映射，也就给出同一个外部端口。
         * 否则内核会随机挑一个新的临时端口，公网端口必然跟着变。
         *
         * `SO_REUSEADDR` **必须在 bind 之前设置**：Linux 只在该标志存在时允许
         * `bind` 处于 `TIME_WAIT` 的端口（我们自己发起关闭时旧端口会落到该状态）。
         * 另外，被玩家占用的已建立连接同样持有该本地端口，它们从监听套接字继承了
         * `SO_REUSEADDR`，因此这里带上该标志才能绑得上。
         *
         * 端口保不住时**不影响可用性**：bind 失败会退回随机端口；bind 成功但
         * connect 失败（个别系统不允许 bind 固定端口后再 connect，Windows 会报
         * `Address already in use`）也会整条重试一次。地址稳定是尽力而为，
         * 不能因此让隧道起不来。
         */
        fun connect(
            server: StunServer,
            connectTimeoutMs: Int = 5000,
            reuseLocalPort: Int? = null
        ): StunTcpSession {
            if (reuseLocalPort != null) {
                // 抢占旧端口有竞态：上一条连接（或它 accept 出来的玩家连接）刚关闭时，
                // 端口可能还残留几十毫秒。实测 2026-10-17 的日志里就出现过一次
                // 「原本地端口未占到（实得 34808）」，公网地址随之变化。
                // 端口一旦丢掉就再也拿不回来（下次只能拿到另一个随机端口），
                // 所以这里值得多试几次——只在**端口没抢到**时重试，总共约半秒。
                var lastReason: String? = null
                for (attempt in 0 until portReuseAttempts) {
                    // connect 失败（服务器不可达）不在这里重试：那不是端口问题，
                    // 多试几次只是把失败拖慢 4 倍。直接抛给上层换服务器。
                    val reused = try {
                        openSession(server, connectTimeoutMs, reuseLocalPort)
                    } catch (e: Exception) {
                        throw StunException(e.message ?: e.toString())
                    }
                    if (reused.localPort == reuseLocalPort) return reused
                    // 绑到了别的端口（bind 被拒/端口被占）：关掉重来，否则这条连接会
                    // 占着一个随机端口，反而让公网地址白白改变。
                    lastReason = reused.bindNote ?: "内核未分配该端口"
                    reused.close()
                    if (attempt < portReuseAttempts - 1) {
                        runCatching { Thread.sleep(portReuseBackoffMs[attempt].toLong()) }
                    }
                }
                // 实在保不住：退回随机端口，功能不受影响（只是公网端口会变）。
                return openSession(server, connectTimeoutMs, null, lastReason)
            }
            return try {
                openSession(server, connectTimeoutMs, null)
            } catch (e: Exception) {
                throw StunException(e.message ?: e.toString())
            }
        }

        /** 建立一条会话：可选先占住 [localPort]，随后 connect 并启动读线程。 */
        private fun openSession(
            server: StunServer,
            connectTimeoutMs: Int,
            localPort: Int?,
            bindFailure: String? = null
        ): StunTcpSession {
            val socket = Socket()
            var bindNote = bindFailure
            try {
                socket.tcpNoDelay = true
                // bind 失败不抛异常：此时 socket 仍是未绑定状态，随后的 connect 会让
                // 内核重新分配随机端口，功能不受影响，只是公网端口会变。
                if (localPort != null) bindNote = bindLocalPort(socket, localPort)
                socket.connect(InetSocketAddress(server.host, server.port), connectTimeoutMs)
                enableReuseAddress(socket)
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw e
            }
            val session = StunTcpSession(socket, server.toString(), bindNote)
            session.startReader()
            return session
        }

        /**
         * 给已连接的 socket 打上 `SO_REUSEADDR`。
         *
         * bind 冲突检查读取的是对端 socket **当前** 的 `sk_reuse` 标志，因此在
         * connect 之后设置同样有效——这正是本实现能在保活连接存活期间把监听
         * 套接字绑定到同一本地端口的原因。
         *
         * 注意这里用的是 `java.net.StandardSocketOptions.SO_REUSEADDR`（类型为
         * `SocketOption<Boolean>`），而不是 `java.net.SocketOptions.SO_REUSEADDR`
         * ——后者只是个 `int` 常量，`Socket.setOption` 并不接受它。在 Android 上
         * `StandardSocketOptions.SO_REUSEADDR` 底层映射到 `SOL_SOCKET` 的
         * `SO_REUSEADDR`，正是内核 bind 冲突检查所读的那个标志。
         */
        private fun enableReuseAddress(socket: Socket) {
            socket.setOption(StandardSocketOptions.SO_REUSEADDR, true)
        }

        /**
         * 尽力把 socket 绑到指定本地端口。
         *
         * 返回 `null` 表示成功，否则返回失败原因（供日志说明「公网地址为什么变了」）。
         * `reuseAddress` 必须**先于** `bind` 设置（见 [connect] 的说明）；这里的
         * `InetSocketAddress(port)` 是通配地址，不限定源 IP。
         */
        private fun bindLocalPort(socket: Socket, port: Int): String? = try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port))
            null
        } catch (e: Exception) {
            "${e.message ?: e.toString()}"
        }

        /** 占用旧端口的重试次数与间隔（毫秒）：端口残留通常只有几十毫秒。 */
        private const val portReuseAttempts = 4
        private val portReuseBackoffMs = intArrayOf(40, 120, 300)
    }

    /** 本地端口 —— NAT 映射的内侧端点，隧道监听套接字要绑定的就是它。 */
    val localPort: Int get() = socket.localPort

    private val pending = ConcurrentHashMap<String, PendingTxn>()
    private val alive = AtomicBoolean(true)

    /** 连接断开（正常关闭或出错）时被计数到 0。 */
    private val closedLatch = CountDownLatch(1)

    @Volatile
    private var closeReason: String? = null

    val isClosed: Boolean get() = !alive.get()

    /** 阻塞等待连接断开，返回断开原因；[timeoutMs] 为 0 表示无限等待。 */
    fun awaitClosed(timeoutMs: Long): String? {
        if (timeoutMs <= 0) closedLatch.await() else closedLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return closeReason
    }

    private class PendingTxn(val latch: CountDownLatch = CountDownLatch(1)) {
        @Volatile var result: StunMappedAddress? = null
        @Volatile var error: String? = null
    }

    // ── 读线程 ────────────────────────────────────────────────

    private fun startReader() {
        val thread = Thread({ readLoop() }, "stun-reader")
        thread.isDaemon = true
        // 最后一道闸：readLoop 内部已全量捕获 Throwable，但只要有任何一条漏出去，
        // Android 就会走默认未捕获异常处理器杀掉整个进程（用户看到的是「闪退」，
        // 而不是「隧道断了」）。这条线程只负责守护连接，出任何事都让它静默失效。
        thread.setUncaughtExceptionHandler { _, e ->
            shutdown("探测连接读线程异常：${e.message}")
        }
        thread.start()
    }

    private fun readLoop() {
        val chunk = ByteArray(4096)
        // 与 EdgeCube 的 `_buffer`（List<int>）等价：只保存**尚未凑成完整消息的残片**，
        // 每次收到数据后立刻把完整消息切走，因此正常情况下它始终只有几字节。
        // 若改用固定大小的数组并按其占用量判死，长连接下会把正常的残余误判成
        // 「非 STUN 服务器」而断开——这正是此前「跑一阵就重连」的原因。
        val pendingBytes = java.io.ByteArrayOutputStream()
        try {
            // ⚠️ getInputStream() 必须在 try 内。close() 与 startReader() 之间有竞态：
            // 并行探测（S0.3）下多台服务器几乎同时握手成功，没抢到先手的那台会被
            // 立刻 close()，而此时它的读线程可能还没被调度到这一行。对已关闭的
            // socket 取流会抛 SocketException("Socket is closed")，一旦逃出本线程，
            // Android 默认未捕获异常处理器会直接杀进程（2026-10-16 实测崩溃栈）。
            val input: InputStream = socket.getInputStream()
            while (true) {
                val read = input.read(chunk)
                if (read < 0) {
                    shutdown("探测连接已被对端关闭")
                    return
                }
                pendingBytes.write(chunk, 0, read)
                val data = pendingBytes.toByteArray()

                // 按 STUN 分帧规则逐条切出完整消息。
                var offset = 0
                while (data.size - offset >= StunClient.HEADER_SIZE) {
                    val payloadLength = ((data[offset + 2].toInt() and 0xFF) shl 8) or
                        (data[offset + 3].toInt() and 0xFF)
                    val total = StunClient.HEADER_SIZE + payloadLength
                    if (data.size - offset < total) break
                    handleMessage(data, offset, total)
                    offset += total
                }

                // 只保留未消费的残片；全部消费完则清空。
                pendingBytes.reset()
                if (offset < data.size) {
                    pendingBytes.write(data, offset, data.size - offset)
                }
                // 残片超过上限说明对端不是 STUN 服务器（正常 STUN 消息都很小）。
                if (pendingBytes.size() > MAX_READ_BUFFER) {
                    shutdown("响应数据异常（非 STUN 服务器？）")
                    return
                }
            }
        } catch (e: Throwable) {
            // 必须捕获 Throwable：这条线程上任何逃逸的异常（含 StackOverflowError、
            // 数组越界等 Error 子类）都会直接终结进程，表现为「点几下就闪退」。
            // 读线程只负责守护连接，出错时应让连接静默失效，由保活逻辑兜底。
            shutdown("探测连接出错：${e.message}")
        }
    }

    private fun handleMessage(buffer: ByteArray, start: Int, length: Int) {
        // 防御性边界检查：这是解析不可信网络数据，任何越界都必须在此挡住，
        // 否则会以 ArrayIndexOutOfBoundsException 逃逸到读线程。
        if (length < StunClient.HEADER_SIZE || start + StunClient.HEADER_SIZE > buffer.size) return
        // 校验 magic cookie，非 STUN 报文直接忽略。
        val cookie = StunClient.MAGIC_COOKIE
        if (buffer[start + 4] != cookie[0] ||
            buffer[start + 5] != cookie[1] ||
            buffer[start + 6] != cookie[2] ||
            buffer[start + 7] != cookie[3]
        ) return

        val type = ((buffer[start].toInt() and 0xFF) shl 8) or (buffer[start + 1].toInt() and 0xFF)
        val txId = ByteArray(12)
        System.arraycopy(buffer, start + 8, txId, 0, 12)
        val txn = pending.remove(hex(txId)) ?: return

        // 0x0101 = Binding Success Response，0x0111 = Binding Error Response。
        when (type) {
            0x0111 -> {
                txn.error = "STUN 服务器返回错误：${parseErrorCode(buffer, start, length) ?: "未知"}"
                txn.latch.countDown()
            }
            0x0101 -> {
                val mapped = parseMappedAddress(buffer, start, length)
                if (mapped == null) {
                    txn.error = "响应中没有可用的映射地址"
                } else {
                    txn.result = mapped
                }
                txn.latch.countDown()
            }
            else -> {
                txn.error = "非预期的 STUN 响应类型 0x${type.toString(16)}"
                txn.latch.countDown()
            }
        }
    }

    // ── 事务 ──────────────────────────────────────────────────

    /**
     * 发起一次 Binding 事务，返回本连接的公网映射地址。
     *
     * 同一会话可重复调用（用于保活与公网地址变化检测）。
     */
    @Throws(StunException::class)
    fun requestMapping(timeoutMs: Int = 5000): StunMappedAddress {
        if (isClosed) throw StunException("探测连接已关闭")
        val txId = ByteArray(12).also { StunClient.secureRandom().nextBytes(it) }
        val key = hex(txId)
        val txn = PendingTxn()
        pending[key] = txn
        try {
            val out: OutputStream = socket.getOutputStream()
            out.write(buildBindingRequest(txId))
            out.flush()
        } catch (e: Exception) {
            pending.remove(key)
            throw StunException("发送 Binding 请求失败：${e.message}")
        }
        val ok = try {
            txn.latch.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        pending.remove(key)
        if (!ok) throw StunException("等待 STUN 响应超时")
        txn.error?.let { throw StunException(it) }
        return txn.result ?: throw StunException("响应中没有可用的映射地址")
    }

    fun close() {
        shutdown(null)
        runCatching { socket.close() }
    }

    private fun shutdown(reason: String?) {
        if (!alive.compareAndSet(true, false)) return
        closeReason = reason ?: "探测连接已关闭"
        pending.values.forEach { txn ->
            if (txn.error == null && txn.result == null) txn.error = closeReason
            txn.latch.countDown()
        }
        pending.clear()
        closedLatch.countDown()
        // 关掉 socket 让阻塞中的 read 立刻返回，否则读线程会一直挂在它上面。
        runCatching { socket.close() }
    }

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}

// ── 报文编解码（模块内可见，便于 JVM 单测做字节级断言） ────────
//
// 这几个函数原本是文件级 private，导致「解析不可信网络数据」这条最容易出错的
// 路径完全无法被单测覆盖（只能靠真机联调撞）。改成 internal：外部 API 没有变化，
// 但 StunClientTest 可以逐字节验证 XOR 解掩码、属性对齐与越界防御。

/** Binding Request：类型 0x0001、长度 0、magic cookie、12 字节事务 ID。 */
internal fun buildBindingRequest(txId: ByteArray): ByteArray {
    val message = ByteArray(20)
    message[0] = 0x00
    message[1] = 0x01
    message[2] = 0x00
    message[3] = 0x00
    message[4] = 0x21
    message[5] = 0x12
    message[6] = 0xA4.toByte()
    message[7] = 0x42
    System.arraycopy(txId, 0, message, 8, 12)
    return message
}

/**
 * 遍历属性，优先取 XOR-MAPPED-ADDRESS（0x0020），回退 MAPPED-ADDRESS（0x0001）。
 *
 * 只接受 IPv4（family = 0x01）：隧道要绑定的是 IPv4 监听端口，
 * 拿到 IPv6 映射对本功能没有意义。
 */
internal fun parseMappedAddress(buffer: ByteArray, start: Int, length: Int): StunMappedAddress? {
    val payloadLength = ((buffer[start + 2].toInt() and 0xFF) shl 8) or (buffer[start + 3].toInt() and 0xFF)
    val end = minOf(start + 20 + payloadLength, start + length)
    var offset = start + 20
    var fallback: StunMappedAddress? = null
    while (offset + 4 <= end) {
        val type = ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
        val attrLength = ((buffer[offset + 2].toInt() and 0xFF) shl 8) or (buffer[offset + 3].toInt() and 0xFF)
        val valueStart = offset + 4
        if (valueStart + attrLength > end) break

        if ((type == 0x0020 || type == 0x0001) && attrLength >= 8) {
            val family = buffer[valueStart + 1].toInt() and 0xFF
            if (family == 0x01) {
                var port = ((buffer[valueStart + 2].toInt() and 0xFF) shl 8) or
                    (buffer[valueStart + 3].toInt() and 0xFF)
                val ip = ByteArray(4)
                System.arraycopy(buffer, valueStart + 4, ip, 0, 4)
                if (type == 0x0020) {
                    // XOR-MAPPED-ADDRESS：端口与地址分别与 magic cookie 异或。
                    port = port xor 0x2112
                    ip[0] = (ip[0].toInt() xor 0x21).toByte()
                    ip[1] = (ip[1].toInt() xor 0x12).toByte()
                    ip[2] = (ip[2].toInt() xor 0xA4).toByte()
                    ip[3] = (ip[3].toInt() xor 0x42).toByte()
                }
                val address = StunMappedAddress(ipv4ToString(ip), port)
                if (type == 0x0020) return address
                if (fallback == null) fallback = address
            }
        }
        // 属性值按 4 字节对齐填充。
        offset = valueStart + attrLength + ((4 - (attrLength % 4)) % 4)
    }
    return fallback
}

/** 解析 ERROR-CODE（0x0009）属性，形如 `401 Unauthorized`。 */
internal fun parseErrorCode(buffer: ByteArray, start: Int, length: Int): String? {
    val payloadLength = ((buffer[start + 2].toInt() and 0xFF) shl 8) or (buffer[start + 3].toInt() and 0xFF)
    val end = minOf(start + 20 + payloadLength, start + length)
    var offset = start + 20
    while (offset + 4 <= end) {
        val type = ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
        val attrLength = ((buffer[offset + 2].toInt() and 0xFF) shl 8) or (buffer[offset + 3].toInt() and 0xFF)
        val valueStart = offset + 4
        if (valueStart + attrLength > end) break
        if (type == 0x0009 && attrLength >= 4) {
            val code = (buffer[valueStart + 2].toInt() and 0xFF) * 100 +
                (buffer[valueStart + 3].toInt() and 0xFF)
            val reason = String(
                buffer, valueStart + 4, attrLength - 4,
                Charsets.ISO_8859_1
            )
            return "$code $reason"
        }
        offset = valueStart + attrLength + ((4 - (attrLength % 4)) % 4)
    }
    return null
}

internal fun ipv4ToString(ip: ByteArray): String =
    "${ip[0].toInt() and 0xFF}.${ip[1].toInt() and 0xFF}.${ip[2].toInt() and 0xFF}.${ip[3].toInt() and 0xFF}"

/** 复用 [StunClient] 的随机源，保持单一 CSPRNG 实例。 */
private fun StunClient.secureRandom(): SecureRandom = sharedRandom

private val sharedRandom = SecureRandom()

/** 便捷判断：字符串是否是合法 IPv4 字面量（用于日志与地址拼装）。 */
internal fun isIpv4Literal(text: String): Boolean =
    text.split('.').let { parts ->
        parts.size == 4 && parts.all { p ->
            p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() in 0..255
        }
    }

/**
 * 判断一个 IPv4 字面量是否为**可被外网访问**的公网地址。
 *
 * ## 为什么必须有这个判断
 *
 * STUN 探测「成功」只说明拿到了一份映射，不代表这份映射有用。运营商 CGNAT
 * （100.64.0.0/10）下拿到的就是一个 100.64.x.x 地址 —— 它不是公网地址，外部
 * 连接永远到不了。此前本实现在这种情况下**照原样把地址展示给用户并宣布就绪**，
 * 用户把地址发出去发现没人连得上，且日志里看不出任何异常。
 *
 * 现在改为：非公网地址一律判定为「直连不可用」，并说明是哪一段。
 *
 * 排除清单依据 RFC 6890（含 RFC 1918 私网、RFC 6598 CGNAT、RFC 3927 链路本地）。
 *
 * @return null 表示是公网地址；否则返回人类可读的排除原因。
 */
internal fun nonPublicIpv4Reason(text: String): String? {
    if (!isIpv4Literal(text)) return "不是合法的 IPv4 字面量"
    val b = text.split('.').map { it.toInt() }
    val a = b[0]
    val c = b[1]
    return when {
        a == 0 -> "保留段 0.0.0.0/8"
        a == 10 -> "私网地址 10.0.0.0/8"
        a == 100 && c in 64..127 -> "运营商 CGNAT 地址 100.64.0.0/10"
        a == 127 -> "本机回环地址 127.0.0.0/8"
        a == 169 && c == 254 -> "链路本地地址 169.254.0.0/16"
        a == 172 && c in 16..31 -> "私网地址 172.16.0.0/12"
        a == 192 && c == 0 && b[2] == 0 -> "保留段 192.0.0.0/24"
        a == 192 && c == 0 && b[2] == 2 -> "文档用地址 192.0.2.0/24"
        a == 192 && c == 168 -> "私网地址 192.168.0.0/16"
        a == 198 && (c == 18 || c == 19) -> "基准测试段 198.18.0.0/15"
        a == 198 && c == 51 && b[2] == 100 -> "文档用地址 198.51.100.0/24"
        a == 203 && c == 0 && b[2] == 113 -> "文档用地址 203.0.113.0/24"
        a >= 224 -> "组播/保留段（224.0.0.0/4 及以上）"
        else -> null
    }
}

/** [nonPublicIpv4Reason] 的布尔形式。 */
internal fun isPublicIpv4Address(text: String): Boolean = nonPublicIpv4Reason(text) == null
