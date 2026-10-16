package com.junge.aidepro.vpn.transport.protocol

import com.junge.aidepro.vpn.util.PacketUtil
import java.nio.ByteBuffer

/**
 * UDP 头。
 *
 * 移植自 ProxyPin（Apache 2.0）的
 * `com.network.proxy.vpn.transport.protocol.UDPHeader`。
 *
 * UDP 主要承载 DNS 查询。如果不处理 UDP，应用就无法解析域名，
 * 所以 VPN 方案必须同时支持 UDP 转发（哪怕是直连转发）。
 *
 * 注意：这里用普通 class 而非 data class —— data class 会为
 * `sourcePort` 属性自动生成 `getSourcePort()`，与 TransportHeader
 * 接口方法签名冲突（JVM 签名相同但返回类型检查不通过）。
 */
class UDPHeader(
    var sourcePort: Int = 0,       // 16 bit
    var destinationPort: Int = 0,  // 16 bit
    var length: Int = 0,           // 16 bit UDP 数据报长度
    var checksum: Int = 0,         // 16 bit
) : TransportHeader {

    override fun srcPort(): Int = sourcePort
    override fun dstPort(): Int = destinationPort

    fun copy(): UDPHeader = UDPHeader(sourcePort, destinationPort, length, checksum)

    override fun toString(): String =
        "UDPHeader(src=$sourcePort, dst=$destinationPort, len=$length)"
}

object UDPPacketFactory {

    @JvmStatic
    fun createUDPHeader(stream: ByteBuffer): UDPHeader {
        require(stream.remaining() >= 8) { "Minimum UDP header is 8 bytes." }
        val srcPort = stream.getShort().toInt() and 0xffff
        val destPort = stream.getShort().toInt() and 0xffff
        val length = stream.getShort().toInt() and 0xffff
        val checksum = stream.getShort().toInt()
        return UDPHeader(srcPort, destPort, length, checksum)
    }

    /** 构造回给客户端的 UDP 响应包。 */
    @JvmStatic
    fun createResponsePacket(ip: IP4Header, udp: UDPHeader, packetData: ByteArray?): ByteArray {
        var udpLen = 8
        if (packetData != null) udpLen += packetData.size

        val srcPort = udp.destinationPort
        val destPort = udp.sourcePort
        val ipHeader = ip.copy()
        val srcIp = ip.destinationIP
        val destIp = ip.sourceIP
        ipHeader.setMayFragment(false)
        ipHeader.sourceIP = srcIp
        ipHeader.destinationIP = destIp
        ipHeader.identification = PacketUtil.getPacketId()

        val totalLength = ipHeader.getIPHeaderLength() + udpLen
        ipHeader.totalLength = totalLength
        val buffer = ByteArray(totalLength)
        val ipData = ipHeader.toBytes()

        // 清空 IP 校验和字段后重算
        ipData[11] = 0
        ipData[10] = 0
        val ipChecksum = PacketUtil.calculateChecksum(ipData, 0, ipData.size)
        System.arraycopy(ipChecksum, 0, ipData, 10, 2)
        System.arraycopy(ipData, 0, buffer, 0, ipData.size)

        var start = ipData.size
        val intContainer = ByteArray(4)
        PacketUtil.writeIntToBytes(srcPort, intContainer, 0)
        System.arraycopy(intContainer, 2, buffer, start, 2)
        start += 2

        PacketUtil.writeIntToBytes(destPort, intContainer, 0)
        System.arraycopy(intContainer, 2, buffer, start, 2)
        start += 2

        PacketUtil.writeIntToBytes(udpLen, intContainer, 0)
        System.arraycopy(intContainer, 2, buffer, start, 2)
        start += 2

        // UDP 校验和置 0 表示不校验（IPv4 下允许）
        val checksum: Short = 0
        PacketUtil.writeIntToBytes(checksum.toInt(), intContainer, 0)
        System.arraycopy(intContainer, 2, buffer, start, 2)
        start += 2

        if (packetData != null) System.arraycopy(packetData, 0, buffer, start, packetData.size)
        return buffer
    }
}
