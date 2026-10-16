package com.junge.aidepro.vpn.util

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 网络包处理工具：校验和计算、地址转换、报文转储。
 *
 * 移植自 ProxyPin（Apache 2.0）的 `com.network.proxy.vpn.util.PacketUtil`。
 * 保留了原始算法（互联网校验和 / 反码求和），只调整了包名与注释。
 */
object PacketUtil {

    @get:Synchronized
    private var packetId = 0

    fun getPacketId() = packetId++

    val currentTime: Int
        get() = (System.currentTimeMillis() / 1000).toInt()

    /** 把 32 位整数按大端写入字节数组。 */
    fun writeIntToBytes(value: Int, buffer: ByteArray, offset: Int) {
        if (buffer.size - offset < 4) return
        buffer[offset] = (value ushr 24 and 0xFF).toByte()
        buffer[offset + 1] = (value shr 16 and 0xFF).toByte()
        buffer[offset + 2] = (value shr 8 and 0xFF).toByte()
        buffer[offset + 3] = (value and 0xFF).toByte()
    }

    /** 从字节数组读取最多 4 字节的大端整数。 */
    fun getNetworkInt(buffer: ByteArray, start: Int, length: Int): Int {
        var value = 0
        var end = start + minOf(length, 4)
        if (end > buffer.size) end = buffer.size
        for (i in start until end) {
            value = value or (buffer[i].toInt() and 0xFF)
            if (i < end - 1) value = value shl 8
        }
        return value
    }

    /**
     * 校验 TCP 校验和。
     * TCP 校验需要带 12 字节伪头（源IP、目的IP、保留、协议、TCP长度）。
     */
    fun isValidTCPChecksum(
        source: Int, destination: Int,
        data: ByteArray, tcpLength: Short, tcpOffset: Int,
    ): Boolean {
        var buffersize = tcpLength + 12
        var isodd = false
        if (buffersize % 2 != 0) {
            buffersize++
            isodd = true
        }
        val buffer = ByteBuffer.allocate(buffersize)
        buffer.putInt(source)
        buffer.putInt(destination)
        buffer.put(0.toByte())   // 保留
        buffer.put(6.toByte())   // TCP 协议号
        buffer.putShort(tcpLength)
        buffer.put(data, tcpOffset, tcpLength.toInt())
        if (isodd) buffer.put(0.toByte())
        return isValidIPChecksum(buffer.array(), buffersize)
    }

    /** 互联网校验和验证（反码求和后取反，结果为 0 即有效）。 */
    private fun isValidIPChecksum(data: ByteArray, length: Int): Boolean {
        var start = 0
        var sum = 0
        while (start < length) {
            sum += getNetworkInt(data, start, 2)
            start += 2
        }
        // 进位回卷
        while (sum shr 16 > 0) sum = (sum and 0xffff) + (sum shr 16)
        sum = sum.inv()
        val buffer = ByteBuffer.allocate(4)
        buffer.putInt(sum)
        return buffer.getShort(2).toInt() == 0
    }

    /** 计算互联网校验和。 */
    fun calculateChecksum(data: ByteArray, offset: Int, length: Int): ByteArray {
        var start = offset
        var sum = 0
        while (start < length) {
            sum += getNetworkInt(data, start, 2)
            start += 2
        }
        while (sum shr 16 > 0) {
            sum = (sum and 0xffff) + (sum shr 16)
        }
        sum = sum.inv()
        val checksum = ByteArray(2)
        checksum[0] = (sum shr 8).toByte()
        checksum[1] = sum.toByte()
        return checksum
    }

    /** 计算 TCP 头校验和（含伪头）。 */
    fun calculateTCPHeaderChecksum(
        data: ByteArray, offset: Int, tcplength: Int,
        destip: Int, sourceip: Int,
    ): ByteArray {
        var buffersize = tcplength + 12
        var odd = false
        if (buffersize % 2 != 0) {
            buffersize++
            odd = true
        }
        val buffer = ByteBuffer.allocate(buffersize)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(sourceip)
        buffer.putInt(destip)
        buffer.put(0.toByte())
        buffer.put(6.toByte())
        buffer.putShort(tcplength.toShort())
        buffer.put(data, offset, tcplength)
        if (odd) buffer.put(0.toByte())
        return calculateChecksum(buffer.array(), 0, buffersize)
    }

    /** 32 位整数 → 点分十进制 IP。 */
    fun intToIPAddress(addressInt: Int): String =
        "${addressInt ushr 24 and 0xFF}.${addressInt ushr 16 and 0xFF}." +
            "${addressInt ushr 8 and 0xFF}.${addressInt and 0xFF}"

    /** 点分十进制 IP → 32 位整数。 */
    fun ipAddressToInt(ip: String): Int? = runCatching {
        val parts = ip.split('.')
        if (parts.size != 4) return@runCatching null
        var v = 0
        for (p in parts) {
            val n = p.toInt()
            if (n !in 0..255) return@runCatching null
            v = (v shl 8) or n
        }
        v
    }.getOrNull()

    /** 报文转储，用于调试。 */
    fun getOutput(ipHeader: IP4HeaderLike, tcpheader: TCPHeaderLike, packetData: ByteArray): String {
        val tcpLength = (packetData.size - ipHeader.ipHeaderLength).toShort()
        val isValidChecksum = isValidTCPChecksum(
            ipHeader.srcIp, ipHeader.dstIp, packetData, tcpLength, ipHeader.ipHeaderLength,
        )
        val packetBodyLength = packetData.size - ipHeader.ipHeaderLength - tcpheader.tcpHeaderLength
        return buildString {
            append("\r\nProtocol: ").append(ipHeader.proto)
            append("\r\nData Length: ").append(packetBodyLength)
            append("\r\nDest: ").append(intToIPAddress(ipHeader.dstIp)).append(":").append(tcpheader.dstPort)
            append("\r\nSrc: ").append(intToIPAddress(ipHeader.srcIp)).append(":").append(tcpheader.srcPort)
            append("\r\nSeq: ").append(tcpheader.seq)
            append("\r\nACK: ").append(tcpheader.ack)
            append("\r\nIs Valid TCP checksum: ").append(isValidChecksum)
            append("\r\nWindow: ").append(tcpheader.window)
        }
    }

    /**
     * 检测客户端 ACK 的 TCP 选项里的「包损坏」标记（TCP-AO kind=23）。
     * 移植自 ProxyPin 的 isPacketCorrupted。
     */
    fun isPacketCorrupted(options: ByteArray?): Boolean {
        if (options == null) return false
        var i = 0
        while (i < options.size) {
            val kind = options[i].toInt()
            when {
                kind == 0 || kind == 1 -> Unit
                kind == 2 -> i += 3
                kind == 3 || kind == 14 -> i += 2
                kind == 4 -> i++
                kind == 5 || kind == 15 -> {
                    if (i + 1 >= options.size) return false
                    i = i + options[++i] - 2
                }
                kind == 8 -> i += 9
                kind == 23 -> return true
                else -> Log.e(TAG, "unknown option: $kind")
            }
            i++
        }
        return false
    }

    private const val TAG = "PacketUtil"
}

// 下面两个接口用于让 getOutput 解耦具体的数据类（避免循环依赖）
interface IP4HeaderLike {
    val srcIp: Int
    val dstIp: Int
    val proto: Byte
    val ipHeaderLength: Int
}

interface TCPHeaderLike {
    val srcPort: Int
    val dstPort: Int
    val seq: Long
    val ack: Long
    val window: Int
    val tcpHeaderLength: Int
}
