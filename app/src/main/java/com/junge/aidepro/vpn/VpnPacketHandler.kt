package com.junge.aidepro.vpn

import android.util.Log
import com.junge.aidepro.vpn.socket.SocketNioService
import com.junge.aidepro.vpn.socket.TunnelPacketWriter
import com.junge.aidepro.vpn.transport.protocol.IP4Header
import com.junge.aidepro.vpn.transport.protocol.IPPacketFactory
import com.junge.aidepro.vpn.transport.protocol.TCPHeader
import com.junge.aidepro.vpn.transport.protocol.TCPPacketFactory
import com.junge.aidepro.vpn.transport.protocol.UDPHeader
import com.junge.aidepro.vpn.transport.protocol.UDPPacketFactory
import com.junge.aidepro.vpn.util.PacketUtil
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * VPN 包处理器 —— 用户态 TCP/IP 协议栈。
 *
 * 移植自 ProxyPin（Apache 2.0）的 `com.network.proxy.vpn.ConnectionHandler`。
 *
 * 【完整数据流】
 * ```
 * 客户端 SYN
 *   → 回 SYN-ACK（冒充目标服务器）
 *
 * 客户端数据
 *   → 建立出站 socket（连到我们的 MITM 代理，或直连目标）
 *   → 数据写进 connection.sendingStream
 *   → 通知 NIO 线程写出去
 *
 * 出站 socket 收到响应
 *   → NIO 线程读出 → 封装成 IP+TCP 包
 *   → 写回 tun → 客户端收到"服务器响应"
 * ```
 */
class VpnPacketHandler(
    private val writer: TunnelPacketWriter,
    private val proxyAddress: InetSocketAddress,
    private val onLog: (Char, String) -> Unit = { _, _ -> },
) {

    private val nio = SocketNioService(writer)
    private val manager = ConnectionManager(nio)

    /** 按五元组索引的连接。 */
    private val connections = ConcurrentHashMap<String, TcpConnection>()

    val packetsTotal = AtomicLong(0)
    val packetsTcp = AtomicLong(0)
    val packetsUdp = AtomicLong(0)
    val packetsOther = AtomicLong(0)
    val synCount = AtomicLong(0)
    val proxiedCount = AtomicLong(0)
    val directCount = AtomicLong(0)

    private val seenDestinations = ConcurrentHashMap<String, AtomicLong>()

    @Volatile private var running = true

    init {
        manager.proxyAddress = proxyAddress
        // 默认绕过：本地回环 + 私有网段（避免把内网流量也搅进来）
        manager.bypassList = listOf(
            "127.0.0.0/8",
            "10.0.0.0/8",
            "172.16.0.0/12",
            "192.168.0.0/16",
        )
        nio.start()
    }

    fun handlePacket(stream: ByteBuffer) {
        if (!running) return
        packetsTotal.incrementAndGet()

        stream.rewind()
        val ipHeader = try {
            IPPacketFactory.createIP4Header(stream)
        } catch (t: Throwable) {
            return
        } ?: run {
            packetsOther.incrementAndGet()
            return
        }

        try {
            when (ipHeader.protocol.toInt()) {
                6 -> { packetsTcp.incrementAndGet(); handleTcp(stream, ipHeader) }
                17 -> { packetsUdp.incrementAndGet(); handleUdp(stream, ipHeader) }
                else -> packetsOther.incrementAndGet()
            }
        } catch (t: Throwable) {
            onLog('W', "处理包异常: ${t.javaClass.simpleName}: ${t.message}")
            VpnLog.add("✗ 处理包异常: ${t.message}")
        }
    }

    // ==================== TCP ====================

    private fun handleTcp(stream: ByteBuffer, ipHeader: IP4Header) {
        val tcp = try {
            TCPPacketFactory.createTCPHeader(stream)
        } catch (t: Throwable) {
            return
        }
        tcp.handleTcpOptions()

        val srcIp = ipHeader.sourceIP
        val dstIp = ipHeader.destinationIP
        val srcPort = tcp.srcPort()
        val dstPort = tcp.dstPort()
        val key = TcpConnection.key(Protocol.TCP, srcIp, srcPort, dstIp, dstPort)

        val dstLabel = "${PacketUtil.intToIPAddress(dstIp)}:$dstPort"
        seenDestinations.computeIfAbsent(dstLabel) { AtomicLong(0) }.incrementAndGet()

        if (tcp.isSYN() && !tcp.isACK()) {
            // 三次握手第一步：回 SYN-ACK，建立会话
            synCount.incrementAndGet()
            if (synCount.get() % 10 == 1L) {
                VpnLog.add("新连接 ${PacketUtil.intToIPAddress(ipHeader.destinationIP)}:${tcp.dstPort()}（累计 ${synCount.get()}）")
            }
            replySynAck(ipHeader, tcp, key, srcIp, srcPort, dstIp, dstPort)
            return
        }

        val conn = connections[key]
        if (conn == null) {
            // 未知连接
            if (tcp.isFIN()) {
                sendLastAck(ipHeader, tcp)
            } else if (!tcp.isRST()) {
                sendRst(ipHeader, tcp)
            }
            return
        }

        synchronized(conn) {
            conn.lastIpHeader = ipHeader
            conn.lastTcpHeader = tcp
            conn.timestampReplyTo = tcp.timeStampSender
            conn.timestampSender = (System.currentTimeMillis() / 1000).toInt()

            val dataLen = stream.limit() - stream.position()

            if (dataLen > 0) {
                // 先把数据完整取出来（避免后续操作移动 position 导致数据丢失）
                val payload = ByteArray(dataLen)
                stream.get(payload)

                // 建立出站连接（用刚取出的数据判断是 HTTP/TLS 还是其他）
                ensureOutbound(conn, payload, ipHeader, tcp)

                // 序号检查：只接受新数据
                if (conn.recSequence == 0L || tcp.sequenceNumber >= conn.recSequence) {
                    conn.setSendingData(payload, 0, payload.size)
                    conn.bytesFromClient += payload.size
                    sendAck(ipHeader, tcp, payload.size, conn)

                    // 数据已就绪：通知 NIO 线程写出
                    conn.isDataForSendingReady = true
                    conn.subscribeKey(SelectionKey.OP_WRITE)
                    nio.refreshSelect(conn)
                } else {
                    // 乱序包：重发上次的 ACK
                    resendAck(conn)
                }
            } else {
                acceptAck(tcp, conn)
                if (conn.isClosingConnection) {
                    sendFinAck(ipHeader, tcp, conn)
                } else if (conn.isAckedToFin && !tcp.isFIN()) {
                    // FIN 后的最后一个 ACK，可以关闭了
                    closeConnection(conn, key)
                    return
                }
            }

            if (tcp.isFIN()) {
                ackFinAck(ipHeader, tcp, conn, key)
            } else if (tcp.isRST()) {
                conn.isAbortingConnection = true
            }
        }
    }

    /**
     * 确保出站连接已建立。
     *
     * 这里做代理/直连的判断：看客户端第一批数据是不是 HTTP/TLS，
     * 是就走我们的 MITM 代理（能解密），不是就直连目标。
     */
    private fun ensureOutbound(
        conn: TcpConnection,
        payload: ByteArray,
        ipHeader: IP4Header,
        tcp: TCPHeader,
    ) {
        if (conn.isInitConnect) return
        conn.isInitConnect = true

        try {
            val channel = conn.channel as? SocketChannel ?: return
            val target = manager.resolveTarget(
                ipHeader.destinationIP, tcp.dstPort(), ByteBuffer.wrap(payload),
            )

            if (target == manager.proxyAddress) {
                proxiedCount.incrementAndGet()
                // 归属到应用：拿包名，通过元数据头告诉 MITM 代理
                conn.ownerPackage = resolveOwnerPackage(ipHeader, tcp)
                VpnLog.add("→ 走代理 ${com.junge.aidepro.vpn.util.PacketUtil.intToIPAddress(ipHeader.destinationIP)}:${tcp.dstPort()}" +
                    conn.ownerPackage?.let { " [${it.substringAfterLast('.')}]" }.orEmpty())

                // 走代理时，先把元数据头（包名）放进发送队列。
                //
                // 【为什么放在 connect 之前】
                // 之前放在 `if (connected)` 分支里，导致异步连接
                // （connect 返回 false，连上后才由 NIO 的 OP_CONNECT 接手）
                // 不会发送 META 头 —— 表现为「同一 URL 一半带包名一半不带」。
                // 放进队列后，无论同步还是异步连接，NIO 写数据时都会先发它。
                if (!conn.ownerPackage.isNullOrBlank()) {
                    val meta = "META ${conn.ownerPackage}\r\n".toByteArray(Charsets.ISO_8859_1)
                    conn.setSendingData(meta, 0, meta.size)
                }
            } else {
                directCount.incrementAndGet()
                VpnLog.add("→ 直连 ${com.junge.aidepro.vpn.util.PacketUtil.intToIPAddress(ipHeader.destinationIP)}:${tcp.dstPort()}")
            }

            val connected = channel.connect(target)
            conn.isConnected = connected

            // 先注册再改interestOps：register 之前 selectionKey 还是 null，
            // subscribeKey 会静默失效 —— 这是之前数据发不出去的原因之一。
            nio.register(conn)

            // 数据已就绪（含 META 头），标记后由 NIO 写出
            conn.isDataForSendingReady = true

            if (connected) {
                // 已连上（连本地代理通常立即成功）。
                //
                // 【关键】必须**主动写一次**，不能只订阅 OP_WRITE 等事件。
                // 原因：连上后注册的监听位是 OP_READ，只有对端发数据才会触发；
                // 而对端（我们的 MITM 代理）正等着我们先发数据。
                // 双方互等 → 死锁 → 表现为「应用一直转圈、代理端零日志」。
                conn.subscribeKey(SelectionKey.OP_WRITE)
                nio.writeNow(conn)
            }
            // 异步连接由 NIO 的 OP_CONNECT 事件接手，连上后会主动写一次
        } catch (e: Exception) {
            onLog('W', "建立出站连接失败 $conn: ${e.javaClass.simpleName}: ${e.message}")
            VpnLog.add("✗ 连接失败 ${e.javaClass.simpleName}: ${e.message}")
            conn.isAbortingConnection = true
        }
    }


    /**
     * 查这条连接属于哪个应用。
     *
     * 用 VPN 虚拟网卡的本地地址 + 对端地址询问系统
     * （`ConnectivityManager.getConnectionOwnerUid`）。
     */
    private fun resolveOwnerPackage(ipHeader: IP4Header, tcp: TCPHeader): String? = runCatching {
        // 客户端侧地址：源 IP/端口（就是发起方）
        val local = InetSocketAddress(
            PacketUtil.intToIPAddress(ipHeader.sourceIP), tcp.srcPort(),
        )
        val remote = InetSocketAddress(
            PacketUtil.intToIPAddress(ipHeader.destinationIP), tcp.dstPort(),
        )
        UidResolver.resolvePackage(local, remote)
    }.getOrNull()

    private fun replySynAck(
        ipHeader: IP4Header, tcp: TCPHeader, key: String,
        srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int,
    ) {
        try {
            ipHeader.identification = 0
            val packet = TCPPacketFactory.createSynAckPacketData(ipHeader, tcp)
            val serverTcp = packet.transportHeader as TCPHeader

            // 复用已有连接或新建
            val existing = connections[key]
            val conn = if (existing != null && existing.lastIpHeader == null) {
                existing
            } else {
                val c = manager.createTcp(srcIp, srcPort, dstIp, dstPort)
                connections[key] = c
                c
            }

            synchronized(conn) {
                conn.maxSegmentSize = tcp.maxSegmentSize.toInt()
                conn.sendUnAck = serverTcp.sequenceNumber
                conn.sendNext = serverTcp.sequenceNumber + 1
                conn.recSequence = serverTcp.ackNumber
                conn.lastIpHeader = ipHeader
                conn.lastTcpHeader = tcp
                writer.write(packet.buffer)
            }
        } catch (t: Throwable) {
            onLog('W', "回 SYN-ACK 失败: ${t.message}")
        }
    }

    /** 回 ACK 确认收到客户端数据。 */
    private fun sendAck(
        ipHeader: IP4Header, tcp: TCPHeader, acceptedLength: Int, conn: TcpConnection,
    ) {
        val ackNumber = conn.recSequence + acceptedLength
        conn.recSequence = ackNumber
        val data = TCPPacketFactory.createResponseAckData(ipHeader, tcp, ackNumber)
        writer.write(data)
    }

    private fun resendAck(conn: TcpConnection) {
        val ip = conn.lastIpHeader ?: return
        val tcp = conn.lastTcpHeader ?: return
        val data = TCPPacketFactory.createResponseAckData(ip, tcp, conn.recSequence)
        writer.write(data)
    }

    private fun acceptAck(tcp: TCPHeader, conn: TcpConnection) {
        if (tcp.ackNumber > conn.sendUnAck || tcp.ackNumber == conn.sendNext) {
            conn.sendUnAck = tcp.ackNumber
            conn.recSequence = tcp.sequenceNumber
            conn.timestampReplyTo = tcp.timeStampSender
        }
    }

    private fun sendLastAck(ipHeader: IP4Header, tcp: TCPHeader) {
        val data = TCPPacketFactory.createResponseAckData(ipHeader, tcp, tcp.sequenceNumber + 1)
        writer.write(data)
    }

    private fun sendRst(ipHeader: IP4Header, tcp: TCPHeader) {
        runCatching { writer.write(TCPPacketFactory.createRstData(ipHeader, tcp, 0)) }
    }

    private fun ackFinAck(
        ipHeader: IP4Header, tcp: TCPHeader, conn: TcpConnection, key: String,
    ) {
        val ack = tcp.sequenceNumber + 1
        val data = TCPPacketFactory.createFinAckData(
            ipHeader, tcp, ack, tcp.ackNumber, isFin = true, isAck = true,
        )
        writer.write(data)
        conn.sendNext = tcp.ackNumber + 1
        conn.isClosingConnection = false
        conn.isAckedToFin = true
        // 给客户端最后确认后关闭出站
        runCatching { conn.channel?.close() }
    }

    private fun sendFinAck(ipHeader: IP4Header, tcp: TCPHeader, conn: TcpConnection) {
        val data = TCPPacketFactory.createFinAckData(
            ipHeader, tcp, tcp.sequenceNumber, tcp.ackNumber, isFin = true, isAck = false,
        )
        writer.write(data)
        conn.sendNext = tcp.ackNumber + 1
        conn.isClosingConnection = false
    }

    private fun closeConnection(conn: TcpConnection, key: String) {
        connections.remove(key)
        manager.closeConnection(conn)
    }

    // ==================== UDP ====================

    /**
     * UDP 处理。
     *
     * UDP 主要用于 DNS。这里做**直连转发**：
     * 建立到目标的 DatagramChannel，把数据发过去，响应原样回给客户端。
     *
     * 不对 UDP 做 MITM：QUIC 等协议解密成本太高，而且 DNS 只需能通即可。
     */
    private fun handleUdp(stream: ByteBuffer, ipHeader: IP4Header) {
        val udp = try {
            UDPPacketFactory.createUDPHeader(stream)
        } catch (t: Throwable) {
            return
        }

        val srcIp = ipHeader.sourceIP
        val dstIp = ipHeader.destinationIP
        val srcPort = udp.sourcePort
        val dstPort = udp.destinationPort
        val key = TcpConnection.key(Protocol.UDP, srcIp, srcPort, dstIp, dstPort)

        val dstLabel = "UDP ${PacketUtil.intToIPAddress(dstIp)}:$dstPort"
        seenDestinations.computeIfAbsent(dstLabel) { AtomicLong(0) }.incrementAndGet()

        var conn = connections[key]
        val isNew = conn == null
        if (conn == null) {
            conn = try {
                manager.createUdp(srcIp, srcPort, dstIp, dstPort)
            } catch (e: Exception) {
                return
            }
            connections[key] = conn
        }

        synchronized(conn) {
            conn.lastIpHeader = ipHeader
            conn.lastUdpHeader = udp

            val available = stream.remaining()
            if (available > 0) {
                val data = ByteArray(available)
                stream.get(data)
                conn.setSendingData(data, 0, data.size)
                conn.isDataForSendingReady = true
            }

            if (isNew) {
                // UDP 是「无连接」的，但 DatagramChannel 需要 connect 才知道往哪发
                try {
                    val ch = conn.channel as? java.nio.channels.DatagramChannel ?: return
                    ch.connect(InetSocketAddress(PacketUtil.intToIPAddress(dstIp), dstPort))
                    conn.isConnected = true
                    nio.register(conn)
                } catch (e: Exception) {
                    onLog('W', "UDP 连接失败: ${e.message}")
                    connections.remove(key)
                    return
                }
            }

            conn.subscribeKey(SelectionKey.OP_WRITE)
            nio.refreshSelect(conn)
        }
    }

    // ==================== 诊断与控制 ====================

    fun stats(): String {
        val top = seenDestinations.entries
            .sortedByDescending { it.value.get() }
            .take(10)
            .joinToString("; ") { "${it.key}×${it.value.get()}" }
        return "包=${packetsTotal.get()} TCP=${packetsTcp.get()} UDP=${packetsUdp.get()} " +
            "SYN=${synCount.get()} 走代理=${proxiedCount.get()} 直连=${directCount.get()} " +
            "活跃会话=${connections.size}\nTop: $top"
    }

    fun destinations(): Map<String, Long> =
        seenDestinations.entries.associate { it.key to it.value.get() }

    fun shutdown() {
        running = false
        runCatching { nio.shutdown() }
        runCatching { manager.closeAll() }
        connections.clear()
    }

    companion object {
        private const val TAG = "VpnPacketHandler"
    }
}
