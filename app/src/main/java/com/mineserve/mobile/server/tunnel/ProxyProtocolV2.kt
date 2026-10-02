package com.mineserve.mobile.server.tunnel

import java.net.Inet4Address
import java.net.InetAddress

/**
 * PROXY protocol v2 头部构造（HAProxy 规范）。
 *
 * 隧道转发会让服务端只看到 `127.0.0.1`，开启后在转发的第一批字节前插入本头部，
 * 服务端（Paper / Velocity 等，需自行开启 `proxy_protocol`）便能拿到玩家真实 IP。
 * 服务端未开启时会把这些字节当作协议数据，导致玩家无法进入，故默认关闭。
 */
object ProxyProtocolV2 {

    /** 12 字节固定签名。 */
    private val SIGNATURE = byteArrayOf(
        0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A
    )

    /**
     * 构造一条 TCP 连接的 PROXY v2 头部。
     *
     * [src] / [srcPort] 为玩家真实来源，[dst] / [dstPort] 为玩家实际连上的本地监听
     * 端点。源与目的地址族不一致（极少见）时按 IPv6 处理，IPv4 地址会被映射为
     * `::ffff:a.b.c.d`。
     */
    fun buildHeader(
        src: InetAddress,
        srcPort: Int,
        dst: InetAddress,
        dstPort: Int
    ): ByteArray {
        val srcRaw = src.address
        val dstRaw = dst.address
        val isIPv4 = srcRaw.size == 4 && dstRaw.size == 4
        val addressLength = if (isIPv4) 12 else 36
        val header = ByteArray(16 + addressLength)

        System.arraycopy(SIGNATURE, 0, header, 0, 12)
        header[12] = 0x21 // 版本 2（高 4 位） + PROXY 命令（低 4 位）
        header[13] = if (isIPv4) 0x11 else 0x21 // TCP over IPv4 / TCP over IPv6
        header[14] = ((addressLength shr 8) and 0xFF).toByte()
        header[15] = (addressLength and 0xFF).toByte()

        val addressSize = if (isIPv4) 4 else 16
        var offset = 16
        System.arraycopy(rawAddress(src, isIPv4), 0, header, offset, addressSize)
        offset += addressSize
        System.arraycopy(rawAddress(dst, isIPv4), 0, header, offset, addressSize)
        offset += addressSize
        header[offset++] = ((srcPort shr 8) and 0xFF).toByte()
        header[offset++] = (srcPort and 0xFF).toByte()
        header[offset++] = ((dstPort shr 8) and 0xFF).toByte()
        header[offset] = (dstPort and 0xFF).toByte()
        return header
    }

    /** 取地址原始字节；IPv6 头部里的 IPv4 地址按 IPv4-mapped 形式补齐到 16 字节。 */
    private fun rawAddress(address: InetAddress, isIPv4: Boolean): ByteArray {
        val raw = address.address
        if (isIPv4 || raw.size == 16) return raw
        val mapped = ByteArray(16)
        mapped[10] = 0xFF.toByte()
        mapped[11] = 0xFF.toByte()
        System.arraycopy(raw, 0, mapped, 12, 4)
        return mapped
    }

    /** 便捷重载：本机监听端点固定为 IPv4 回环。 */
    fun buildHeader(src: InetAddress, srcPort: Int, dstPort: Int): ByteArray =
        buildHeader(src, srcPort, Inet4Address.getByName("127.0.0.1"), dstPort)
}
