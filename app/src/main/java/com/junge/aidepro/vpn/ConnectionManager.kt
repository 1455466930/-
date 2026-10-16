package com.junge.aidepro.vpn

import android.util.Log
import com.junge.aidepro.vpn.socket.SocketNioService
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话管理器：创建/查找/关闭 VPN 会话，并决定流量走代理还是直连。
 *
 * 移植自 ProxyPin（Apache 2.0）的 `com.network.proxy.vpn.ConnectionManager`。
 *
 * 【核心决策：走代理还是直连】
 * `getProxyAddress()` 里判断：
 *  - 如果是 TLS ClientHello 或 HTTP 请求（GET/POST...）→ 转发到我们的 MITM 代理
 *    （这样能看到明文）
 *  - 其他（比如私有协议、推送长连接）→ 直连真实目标
 *    （不解析，但保证应用能用）
 *
 * 这个判断很重要：全部强行走代理会让非 HTTP 流量断掉，
 * 全部直连则抓不到内容。
 */
class ConnectionManager(
    private val nio: SocketNioService,
) : ConnectionCloser {

    private val connections = ConcurrentHashMap<String, TcpConnection>()

    /** 上游代理地址（我们的 MITM 端口）。 */
    @Volatile var proxyAddress: InetSocketAddress? = null

    /** 不走代理的地址（CIDR / IP / 域名）。 */
    @Volatile var bypassList: List<String> = emptyList()

    val activeCount: Int get() = connections.size

    // ==================== 查找与创建 ====================

    fun get(key: String): TcpConnection? = connections[key]

    fun getByEndpoints(
        protocol: Protocol,
        srcIp: Int, srcPort: Int,
        dstIp: Int, dstPort: Int,
    ): TcpConnection? = connections[TcpConnection.key(protocol, srcIp, srcPort, dstIp, dstPort)]

    fun forEach(action: (TcpConnection) -> Unit) = connections.values.forEach(action)

    @Throws(Exception::class)
    fun createTcp(
        srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int,
    ): TcpConnection {
        val key = TcpConnection.key(Protocol.TCP, srcIp, srcPort, dstIp, dstPort)
        val conn = TcpConnection(Protocol.TCP, srcIp, srcPort, dstIp, dstPort, this)
        val ch = SocketChannel.open()
        // 对齐 ProxyPin：这些参数直接影响连接质量
        ch.socket().keepAlive = true          // 长连接保活，防止 NAT 超时断
        ch.socket().tcpNoDelay = true         // 关闭 Nagle，抓包转发必须低延迟
        ch.socket().soTimeout = 0             // 非阻塞，超时交给 selector
        ch.socket().receiveBufferSize = com.junge.aidepro.vpn.socket.VpnSocketConstant.MAX_RECEIVE_BUFFER_SIZE
        ch.configureBlocking(false)
        // 关键：让这条 socket 绕过 VPN，否则它的流量会被我们自己的 tun 再抓一次
        val sock = ch.socket()
        val protected = ProtectSocketHolder.protect(sock)
        if (!protected) {
            VpnLog.add("✗ socket 保护失败，可能出现回环")
        }
        conn.channel = ch
        connections[key] = conn
        return conn
    }

    @Throws(Exception::class)
    fun createUdp(
        srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int,
    ): TcpConnection {
        val key = TcpConnection.key(Protocol.UDP, srcIp, srcPort, dstIp, dstPort)
        val conn = TcpConnection(Protocol.UDP, srcIp, srcPort, dstIp, dstPort, this)
        val ch = DatagramChannel.open()
        ch.configureBlocking(false)
        ProtectSocketHolder.protect(ch.socket())
        conn.channel = ch
        connections[key] = conn
        return conn
    }

    fun close(key: String) {
        connections.remove(key)?.let {
            it.cancelKey()
            it.closeChannel()
        }
    }

    override fun closeConnection(conn: TcpConnection) {
        val key = TcpConnection.key(
            conn.protocol, conn.sourceIp, conn.sourcePort, conn.destinationIp, conn.destinationPort,
        )
        connections.remove(key)
        conn.cancelKey()
        conn.closeChannel()
    }

    fun closeAll() {
        connections.values.forEach {
            it.cancelKey()
            it.closeChannel()
        }
        connections.clear()
    }

    // ==================== 代理决策 ====================

    /**
     * 决定这条连接要连到哪里。
     *
     * @param firstPayload 客户端发来的第一批数据（用于判断是否 HTTP/TLS）
     * @return 目标地址（代理地址或真实目标）
     */
    fun resolveTarget(
        dstIp: Int,
        dstPort: Int,
        firstPayload: ByteBuffer,
    ): InetSocketAddress {
        val dstIpStr = com.junge.aidepro.vpn.util.PacketUtil.intToIPAddress(dstIp)
        val real = InetSocketAddress(dstIpStr, dstPort)

        // 在绕过列表里 → 直连
        if (shouldBypass(dstIpStr)) {
            Log.d(TAG, "绕过代理（在排除列表中）: $dstIpStr:$dstPort")
            return real
        }

        val proxy = proxyAddress ?: return real

        // 只把 HTTP/TLS 流量送进代理，其他直连（否则非 HTTP 协议会断）
        return if (isHttpOrTls(firstPayload)) {
            Log.d(TAG, "转发到代理: $dstIpStr:$dstPort → $proxy")
            proxy
        } else {
            Log.d(TAG, "非 HTTP/TLS，直连: $dstIpStr:$dstPort")
            real
        }
    }

    /**
     * 判断是否是 HTTP 请求或 TLS 握手。
     * 只有这类流量送进 MITM 代理才有意义。
     */
    private fun isHttpOrTls(buf: ByteBuffer): Boolean {
        val pos = buf.position()
        try {
            if (isTlsClientHello(buf)) return true
            buf.position(pos)

            val remaining = buf.remaining()
            if (remaining < 3) return false

            for (method in HTTP_METHODS) {
                if (remaining < method.length) continue
                var match = true
                for (i in method.indices) {
                    val b = buf.get(pos + i).toInt().toChar()
                    if (b.uppercaseChar() != method[i]) { match = false; break }
                }
                if (match) return true
            }
            return false
        } catch (t: Throwable) {
            return false
        } finally {
            buf.position(pos)
        }
    }

    /**
     * 检测 TLS ClientHello。
     *
     * TLS 记录层格式：
     * ```
     * byte  0     : ContentType (0x16 = Handshake)
     * bytes 1-2   : Version (0x0301 ~ 0x0303)
     * bytes 3-4   : Length
     * byte  5     : HandshakeType (0x01 = ClientHello)
     * ```
     */
    private fun isTlsClientHello(buf: ByteBuffer): Boolean {
        val pos = buf.position()
        if (buf.remaining() < 6) return false
        return try {
            val contentType = buf.get(pos).toInt() and 0xFF
            val major = buf.get(pos + 1).toInt() and 0xFF
            val minor = buf.get(pos + 2).toInt() and 0xFF
            val handshakeType = buf.get(pos + 5).toInt() and 0xFF

            contentType == 0x16 &&
                major == 0x03 && minor in 0x00..0x04 &&
                handshakeType == 0x01
        } catch (t: Throwable) {
            false
        }
    }

    /** 是否应绕过代理。 */
    private fun shouldBypass(ip: String): Boolean {
        for (entry in bypassList) {
            val e = entry.trim()
            if (e.isEmpty()) continue
            when {
                e.contains("/") -> if (matchesCidr(ip, e)) return true
                e.startsWith("*.") -> if (ip.endsWith(e.substring(1))) return true
                else -> if (ip == e) return true
            }
        }
        return false
    }

    private fun matchesCidr(ip: String, cidr: String): Boolean {
        return try {
            val parts = cidr.split("/")
            if (parts.size != 2) return false
            val baseIp = parts[0]
            val prefix = parts[1].toIntOrNull() ?: return false

            val ipInt = ipToInt(ip) ?: return false
            val baseInt = ipToInt(baseIp) ?: return false
            if (prefix <= 0) return true
            if (prefix > 32) return false

            val mask = (-1 shl (32 - prefix))
            (ipInt and mask) == (baseInt and mask)
        } catch (t: Throwable) {
            false
        }
    }

    private fun ipToInt(ip: String): Int? {
        val segs = ip.split(".")
        if (segs.size != 4) return null
        var v = 0
        for (s in segs) {
            val n = s.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            v = (v shl 8) or n
        }
        return v
    }

    /** 诊断摘要。 */
    fun stats(): String = "活跃会话=${connections.size}" +
        (proxyAddress?.let { " 代理=$it" } ?: " 无代理")

    companion object {
        private const val TAG = "ConnectionManager"

        private val HTTP_METHODS = arrayOf(
            "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS",
            "TRACE", "CONNECT", "PROPFIND", "REPORT",
        )
    }
}
