package com.sfzsgybb.ai.vpn.transport.protocol

import android.util.Log
import com.sfzsgybb.ai.vpn.util.IP4HeaderLike
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * IPv4 头。
 *
 * 移植自 ProxyPin（Apache 2.0）的 `com.network.proxy.vpn.transport.protocol.IP4Header`。
 */
data class IP4Header(
    var ipVersion: Byte = 0,              // 4 bit，IPv4 时为 4
    private var internetHeaderLength: Byte = 0, // 4 bit，头部长度（单位 4 字节）
    private var diffTypeOfService: Byte = 0,    // 6 bit，差分服务代码点
    private var ecn: Byte = 0,            // 2 bit，显式拥塞通知
    var totalLength: Int = 0,             // 16 bit，IP 包总长
    var identification: Int = 0,          // 16 bit，标识
    private var mayFragment: Boolean = true,   // 1 bit，是否可分片
    private var lastFragment: Boolean = false, // 1 bit，是否最后一片
    var fragmentOffset: Short = 0,        // 13 bit，片偏移
    private var timeToLive: Byte = 0,     // 8 bit，TTL
    var protocol: Byte = 0,               // 8 bit，上层协议（6=TCP, 17=UDP, 1=ICMP）
    var headerChecksum: Int = 0,          // 16 bit，头校验和
    var sourceIP: Int = 0,                // 32 bit，源 IP
    var destinationIP: Int = 0,           // 32 bit，目的 IP
) : IP4HeaderLike {

    /**
     * 3 bit 标志位。
     * bit0 保留、bit1 Don't Fragment、bit2 More Fragments
     */
    private var flag: Byte = initFlag()

    private fun initFlag(): Byte {
        var f = 0
        if (mayFragment) f = 0x40
        if (lastFragment) f = f or 0x20
        return f.toByte()
    }

    fun setMayFragment(value: Boolean) {
        this.mayFragment = value
        flag = if (value) (flag.toInt() or 0x40).toByte() else (flag.toInt() and 0xBF).toByte()
    }

    /** IP 头长度（字节）。 */
    fun getIPHeaderLength(): Int = internetHeaderLength * 4

    fun copy(): IP4Header = IP4Header(
        ipVersion, internetHeaderLength, diffTypeOfService, ecn, totalLength, identification,
        mayFragment, lastFragment, fragmentOffset, timeToLive, protocol, headerChecksum,
        sourceIP, destinationIP,
    )

    fun toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(getIPHeaderLength())
        buffer.order(ByteOrder.BIG_ENDIAN)
        val versionAndHeaderLength = (ipVersion.toInt() shl 4) + internetHeaderLength
        buffer.put(versionAndHeaderLength.toByte())

        val typeOfService = ((diffTypeOfService.toInt() shl 2) and (ecn.toInt() and 0xFF)).toByte()
        buffer.put(typeOfService)

        buffer.putShort(totalLength.toShort())
        buffer.putShort(identification.toShort())

        buffer.put((fragmentOffset.toInt() shr 8 and 0x1F or flag.toInt()).toByte())
        buffer.put(fragmentOffset.toByte())

        buffer.put(timeToLive)
        buffer.put(protocol)
        buffer.putShort(headerChecksum.toShort())
        buffer.putInt(sourceIP)
        buffer.putInt(destinationIP)
        return buffer.array()
    }

    // ---- IP4HeaderLike 实现 ----
    override val srcIp: Int get() = sourceIP
    override val dstIp: Int get() = destinationIP
    override val proto: Byte get() = protocol
    override val ipHeaderLength: Int get() = getIPHeaderLength()
}

/**
 * IPv4 头解析工厂。
 */
object IPPacketFactory {
    private const val IP4_HEADER_SIZE = 20
    private const val IP4_VERSION = 0x04
    private const val TAG = "IPPacketFactory"

    /**
     * 从缓冲区解析 IPv4 头。
     *
     * 注意：解析会移动 buffer 的 position；调用方如需复用原始数据要自行标记。
     * @return 解析失败（非 IPv4）返回 null
     */
    fun createIP4Header(buffer: ByteBuffer): IP4Header? {
        if (buffer.remaining() < IP4_HEADER_SIZE) {
            throw IllegalArgumentException("IP header byte array must have at least $IP4_HEADER_SIZE bytes")
        }

        val versionAndHeaderLength: Byte = buffer.get()
        val ipVersion = (versionAndHeaderLength.toInt() shr 4).toByte()
        if (ipVersion.toInt() != IP4_VERSION) {
            Log.e(TAG, "Invalid IP version $ipVersion")
            return null
        }

        val internetHeaderLength = (versionAndHeaderLength.toInt() and 0x0F).toByte()
        val typeOfService = buffer.get().toInt()
        val diffTypeOfService: Byte = (typeOfService shr 2).toByte()
        val ecn: Byte = (typeOfService and 0x03).toByte()

        val totalLength: Int = buffer.getShort().toInt()
        val identification: Int = buffer.getShort().toInt()

        val flagsAndFragmentOffset: Short = buffer.getShort()
        val mayFragment = flagsAndFragmentOffset.toInt() and 0x4000 != 0
        val lastFragment = flagsAndFragmentOffset.toInt() and 0x2000 != 0
        val fragmentOffset = (flagsAndFragmentOffset.toInt() and 0x1FFF).toShort()

        val timeToLive: Byte = buffer.get()
        val protocol: Byte = buffer.get()
        val checksum: Int = buffer.getShort().toInt()
        val sourceIp: Int = buffer.getInt()
        val desIp: Int = buffer.getInt()

        // 跳过 IP 选项
        if (internetHeaderLength > 5) {
            for (i in 0 until (internetHeaderLength - 5)) {
                buffer.getInt()
            }
        }

        return IP4Header(
            ipVersion, internetHeaderLength, diffTypeOfService, ecn, totalLength, identification,
            mayFragment, lastFragment, fragmentOffset, timeToLive, protocol, checksum,
            sourceIp, desIp,
        )
    }
}
