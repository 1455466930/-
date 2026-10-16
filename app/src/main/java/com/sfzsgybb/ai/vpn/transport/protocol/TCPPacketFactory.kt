package com.sfzsgybb.ai.vpn.transport.protocol

import com.sfzsgybb.ai.vpn.transport.Packet
import com.sfzsgybb.ai.vpn.util.PacketUtil
import java.nio.ByteBuffer
import java.util.concurrent.ThreadLocalRandom

/**
 * TCP 包构造工厂。
 *
 * 移植自 ProxyPin（Apache 2.0）的
 * `com.network.proxy.vpn.transport.protocol.TCPPacketFactory`。
 *
 * 【职责】
 * 用户态 TCP 协议栈的「出站」侧：把要发给客户端（手机里的应用）
 * 的 TCP 包构造出来，含 IP 头、TCP 头、校验和。
 *
 * 关键点：所有回给客户端的包都要先 `flipIp` —— 把源和目的对调，
 * 因为我们要冒充目标服务器。
 */
object TCPPacketFactory {

    private const val TCP_HEADER_LENGTH = 20

    /** 从缓冲区解析 TCP 头。 */
    @JvmStatic
    fun createTCPHeader(byteBuffer: ByteBuffer): TCPHeader {
        if (byteBuffer.remaining() < TCP_HEADER_LENGTH) {
            throw IllegalArgumentException("Invalid TCP Header Length")
        }

        val sourcePort: Int = byteBuffer.getShort().toInt() and 0xFFFF
        val destinationPort: Int = byteBuffer.getShort().toInt() and 0xFFFF
        // 【关键】TCP 序号/确认号是 32 位**无符号**数。
        // getInt() 返回有符号 int，序号 > 2^31 时会变成负数，
        // 直接 .toLong() 后仍是负数 → 序号比较和加法全部错乱
        // （表现为 ack 出现负数、数据转发后客户端无法正确重组）。
        // 必须 & 0xFFFFFFFFL 转回无符号。
        val sequenceNumber: Long = byteBuffer.getInt().toLong() and 0xFFFFFFFFL
        val ackNumber: Long = byteBuffer.getInt().toLong() and 0xFFFFFFFFL

        val dataOffsetAndReserved = byteBuffer.get()
        val dataOffset = (dataOffsetAndReserved.toInt() and 0xF0) shr 4
        val isNs: Boolean = dataOffsetAndReserved.toInt() and 0x1 > 0x0

        val flags = byteBuffer.get().toInt()
        val window = byteBuffer.short.toInt()
        val checksum = byteBuffer.short.toInt()
        val urgentPointer = byteBuffer.short.toInt()

        var optionsAndPadding: ByteArray? = null
        val optionsSize = dataOffset - 5
        if (optionsSize > 0 && byteBuffer.remaining() >= optionsSize * 4) {
            optionsAndPadding = ByteArray(optionsSize * 4)
            byteBuffer.get(optionsAndPadding, 0, optionsSize * 4)
        }

        return TCPHeader(
            sourcePort, destinationPort, sequenceNumber, ackNumber,
            dataOffset, isNs, flags, window, checksum, urgentPointer, optionsAndPadding,
        )
    }

    /** 构造 RST 包（重置连接）。 */
    fun createRstData(ipHeader: IP4Header, tcpHeader: TCPHeader, dataLength: Int): ByteArray {
        val ip = ipHeader.copy()
        val tcp = tcpHeader.copy()

        var ackNumber: Long = 0
        var seqNumber: Long = 0

        if (tcp.ackNumber > 0) {
            seqNumber = tcp.ackNumber
        } else {
            ackNumber = tcp.sequenceNumber + dataLength
        }

        tcp.ackNumber = ackNumber
        tcp.sequenceNumber = seqNumber

        flipIp(ip, tcp)
        ip.identification = 0

        tcp.flags = 0
        tcp.isNS = false
        tcp.setIsRST(true)
        tcp.dataOffset = 5
        tcp.options = null
        tcp.windowSize = 0

        ip.totalLength = ip.getIPHeaderLength() + tcp.getTCPHeaderLength()
        return createPacketData(ip, tcp, null)
    }

    /** 构造携带数据的响应包。 */
    @JvmStatic
    fun createResponsePacketData(
        ipHeader: IP4Header, tcpHeader: TCPHeader, packetData: ByteArray?, isPsh: Boolean,
        ackNumber: Long, seqNumber: Long, timeSender: Int, timeReplyTo: Int,
    ): ByteArray {
        val ip = ipHeader.copy()
        val tcp = tcpHeader.copy()

        flipIp(ip, tcp)
        tcp.ackNumber = ackNumber
        tcp.sequenceNumber = seqNumber
        ip.identification = PacketUtil.getPacketId()

        tcp.setIsACK(true)
        tcp.setIsSYN(false)
        tcp.setIsPSH(isPsh)
        tcp.setIsFIN(false)
        tcp.timeStampSender = timeSender
        tcp.timeStampReplyTo = timeReplyTo
        tcp.dataOffset = 5
        tcp.options = null

        var totalLength = ip.getIPHeaderLength() + tcp.getTCPHeaderLength()
        if (packetData != null) totalLength += packetData.size
        ip.totalLength = totalLength

        return createPacketData(ip, tcp, packetData)
    }

    /** 构造纯 ACK 包。 */
    @JvmStatic
    fun createResponseAckData(
        ipHeader: IP4Header, tcpHeader: TCPHeader, ackToClient: Long,
    ): ByteArray {
        val ip = ipHeader.copy()
        val tcp = tcpHeader.copy()

        flipIp(ip, tcp)
        val seqNumber = tcp.ackNumber
        tcp.ackNumber = ackToClient
        tcp.sequenceNumber = seqNumber

        ip.identification = PacketUtil.getPacketId()

        tcp.setIsACK(true)
        tcp.setIsSYN(false)
        tcp.setIsPSH(false)
        tcp.setIsFIN(false)
        tcp.dataOffset = 5
        tcp.options = null

        ip.totalLength = ip.getIPHeaderLength() + tcp.getTCPHeaderLength()
        return createPacketData(ip, tcp, null)
    }

    /** 把 IP 的源与目的对调（含端口）。 */
    private fun flipIp(ip: IP4Header, tcp: TCPHeader) {
        val sourceIp = ip.destinationIP
        val destIp = ip.sourceIP
        val sourcePort = tcp.dstPort()
        val destPort = tcp.srcPort()

        ip.destinationIP = destIp
        ip.sourceIP = sourceIp
        tcp.setDestinationPort(destPort)
        tcp.setSourcePort(sourcePort)
    }

    /**
     * 构造 SYN-ACK（三次握手的第二步）。
     *
     * 服务器初始序号随机生成；ack = 客户端序号 + 1。
     */
    fun createSynAckPacketData(ipHeader: IP4Header, tcpHeader: TCPHeader): Packet {
        val ip = ipHeader.copy()
        val tcp = tcpHeader.copy()

        flipIp(ip, tcp)

        val ackNumber = tcpHeader.sequenceNumber + 1
        tcp.ackNumber = ackNumber

        // 服务器生成的初始序列号
        val seqNumber = ThreadLocalRandom.current().nextLong(0, 100000)
        tcp.sequenceNumber = seqNumber

        tcp.setIsACK(true)
        tcp.setIsSYN(true)

        tcp.timeStampReplyTo = tcp.timeStampSender
        tcp.timeStampSender = PacketUtil.currentTime

        tcp.dataOffset = 5
        tcp.options = null
        ip.totalLength = ip.getIPHeaderLength() + tcp.getTCPHeaderLength()

        return Packet(ip, tcp, createPacketData(ip, tcp, null))
    }

    /** 构造 FIN-ACK。 */
    fun createFinAckData(
        ipHeader: IP4Header, tcpHeader: TCPHeader, ackToClient: Long,
        seqToClient: Long, isFin: Boolean, isAck: Boolean,
    ): ByteArray {
        val ip = ipHeader.copy()
        val tcp = tcpHeader.copy()

        flipIp(ip, tcp)

        tcp.ackNumber = ackToClient
        tcp.sequenceNumber = seqToClient
        ip.identification = PacketUtil.getPacketId()

        tcp.setIsACK(isAck)
        tcp.setIsSYN(false)
        tcp.setIsPSH(false)
        tcp.setIsFIN(isFin)

        tcp.dataOffset = 5
        tcp.options = null

        ip.totalLength = ip.getIPHeaderLength() + tcp.getTCPHeaderLength()
        return createPacketData(ip, tcp, null)
    }

    /** 构造 FIN 包。 */
    fun createFinData(
        ip: IP4Header, tcp: TCPHeader, ackNumber: Long, seqNumber: Long,
        timeSender: Int, timeReplyTo: Int,
    ): ByteArray {
        flipIp(ip, tcp)

        tcp.ackNumber = ackNumber
        tcp.sequenceNumber = seqNumber
        ip.identification = PacketUtil.getPacketId()

        tcp.timeStampReplyTo = timeReplyTo
        tcp.timeStampSender = timeSender

        tcp.flags = 0
        tcp.isNS = false
        tcp.setIsACK(true)
        tcp.setIsFIN(true)

        tcp.dataOffset = 5
        tcp.options = null
        tcp.windowSize = 0

        ip.totalLength = ip.getIPHeaderLength() + tcp.getTCPHeaderLength()
        return createPacketData(ip, tcp, null)
    }

    /**
     * 把 IP 头 + TCP 头 + 数据组装成完整报文，并填好两个校验和。
     *
     * 校验和计算前必须先把对应字段清零，否则算出来的值是错的。
     */
    private fun createPacketData(ipHeader: IP4Header, tcpHeader: TCPHeader, data: ByteArray?): ByteArray {
        val dataLength = data?.size ?: 0

        val buffer = ByteBuffer.allocate(
            ipHeader.getIPHeaderLength() + tcpHeader.getTCPHeaderLength() + dataLength
        )
        val ipBuffer = ipHeader.toBytes()
        val tcpBuffer = tcpHeader.toBytes()

        buffer.put(ipBuffer)
        buffer.put(tcpBuffer)
        data?.let { buffer.put(it) }

        val zero = byteArrayOf(0, 0)

        // IP 校验和
        buffer.position(10)
        buffer.put(zero)
        val ipChecksum = PacketUtil.calculateChecksum(buffer.array(), 0, ipBuffer.size)
        buffer.position(10)
        buffer.put(ipChecksum)

        // TCP 校验和
        val tcpStart = ipBuffer.size
        buffer.position(tcpStart + 16)
        buffer.put(zero)

        val tcpChecksum = PacketUtil.calculateTCPHeaderChecksum(
            buffer.array(), tcpStart, tcpBuffer.size + dataLength,
            ipHeader.destinationIP, ipHeader.sourceIP,
        )

        buffer.position(tcpStart + 16)
        buffer.put(tcpChecksum)
        return buffer.array()
    }
}
