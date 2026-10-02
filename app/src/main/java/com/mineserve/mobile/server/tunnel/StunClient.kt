package com.mineserve.mobile.server.tunnel

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
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
     */
    val DEFAULT_SERVERS = listOf(
        "fwa.lifesizecloud.com",
        "stun.freeswitch.org",
        "turn.cloudflare.com",
        "global.turn.twilio.com",
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
    val server: String
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
         */
        fun connect(
            server: StunServer,
            connectTimeoutMs: Int = 5000
        ): StunTcpSession {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(server.host, server.port), connectTimeoutMs)
                enableReuseAddress(socket)
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw StunException(e.message ?: e.toString())
            }
            val session = StunTcpSession(socket, server.toString())
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
        thread.start()
    }

    private fun readLoop() {
        val input: InputStream = socket.getInputStream()
        val chunk = ByteArray(4096)
        // 与 EdgeCube 的 `_buffer`（List<int>）等价：只保存**尚未凑成完整消息的残片**，
        // 每次收到数据后立刻把完整消息切走，因此正常情况下它始终只有几字节。
        // 若改用固定大小的数组并按其占用量判死，长连接下会把正常的残余误判成
        // 「非 STUN 服务器」而断开——这正是此前「跑一阵就重连」的原因。
        val pendingBytes = java.io.ByteArrayOutputStream()
        try {
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

// ── 报文编解码（文件级私有工具） ───────────────────────────────

/** Binding Request：类型 0x0001、长度 0、magic cookie、12 字节事务 ID。 */
private fun buildBindingRequest(txId: ByteArray): ByteArray {
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
private fun parseMappedAddress(buffer: ByteArray, start: Int, length: Int): StunMappedAddress? {
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
private fun parseErrorCode(buffer: ByteArray, start: Int, length: Int): String? {
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

private fun ipv4ToString(ip: ByteArray): String =
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

/** 本地 IPv4 回环地址常量，避免各处重复构造。 */
internal val LOOPBACK_V4: Inet4Address = Inet4Address.getByName("127.0.0.1") as Inet4Address
