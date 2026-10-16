package com.sfzsgybb.ai.vpn.transport.protocol

import com.sfzsgybb.ai.vpn.util.TCPHeaderLike
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TCP 头。
 *
 * 移植自 ProxyPin（Apache 2.0）的 `com.network.proxy.vpn.transport.protocol.TCPHeader`。
 * 这是用户态 TCP 状态机最核心的数据结构：序号、确认号、标志位、选项都在这里。
 */
class TCPHeader
constructor(
    private var sourcePort: Int = 0,        // 16 bit 源端口
    private var destinationPort: Int = 0,   // 16 bit 目的端口
    var sequenceNumber: Long = 0,           // 32 bit 序号
    var ackNumber: Long = 0,                // 32 bit 确认号
    var dataOffset: Int = 0,                // 4 bit 数据偏移（头部长度/4）
    var isNS: Boolean = false,              // ECN-nonce（RFC 3540）
    var flags: Int = 0,                     // 9 bit 标志位
    var windowSize: Int = 0,                // 16 bit 窗口
    var checksum: Int = 0,                  // 16 bit 校验和
    private var urgentPointer: Int = 0,     // 16 bit 紧急指针
    var options: ByteArray? = null,         // 选项
) : TCPHeaderLike, TransportHeader {

    // ---- 选项解析结果 ----
    var maxSegmentSize: Short = 0
    private var windowScale: Byte = 0
    private var isSelectiveAckPermitted = false
    var timeStampSender = 0
    var timeStampReplyTo = 0

    companion object {
        private const val END_OF_OPTIONS_LIST: Byte = 0
        private const val NO_OPERATION: Byte = 1
        private const val MAX_SEGMENT_SIZE: Byte = 2
        private const val WINDOW_SCALE: Byte = 3
        private const val SELECTIVE_ACK_PERMITTED: Byte = 4
        private const val TIME_STAMP: Byte = 8
    }

    // ---- 标志位判断 ----
    fun isSYN(): Boolean = flags and 0x02 != 0
    fun isFIN(): Boolean = flags and 0x01 != 0
    fun isRST(): Boolean = flags and 0x04 != 0
    fun isPSH(): Boolean = flags and 0x08 != 0
    fun isACK(): Boolean = flags and 0x10 != 0
    fun isURG(): Boolean = flags and 0x20 != 0
    fun isECE(): Boolean = flags and 0x40 != 0
    fun isCWR(): Boolean = flags and 0x80 != 0

    // ---- 标志位设置 ----
    fun setIsRST(v: Boolean) { flags = if (v) flags or 0x04 else flags and 0xFB }
    fun setIsSYN(v: Boolean) { flags = if (v) flags or 0x02 else flags and 0xFD }
    fun setIsFIN(v: Boolean) { flags = if (v) flags or 0x01 else flags and 0xFE }
    fun setIsPSH(v: Boolean) { flags = if (v) flags or 0x08 else flags and 0xF7 }
    fun setIsACK(v: Boolean) { flags = if (v) flags or 0x10 else flags and 0xEF }

    fun getTCPHeaderLength(): Int = dataOffset * 4

    fun toBytes(): ByteArray {
        val tcpHeader = ByteArray(getTCPHeaderLength())
        val bb = ByteBuffer.wrap(tcpHeader)
        bb.order(ByteOrder.BIG_ENDIAN)

        bb.putShort(sourcePort.toShort())
        bb.putShort(destinationPort.toShort())
        bb.putInt(sequenceNumber.toInt())
        bb.putInt(ackNumber.toInt())
        // NS 位 + 数据偏移
        bb.put(((dataOffset shl 4) and 0xF0 or (if (isNS) 0x1 else 0x0)).toByte())
        bb.put(flags.toByte())
        bb.putShort(windowSize.toShort())
        bb.putShort(checksum.toShort())
        bb.putShort(urgentPointer.toShort())
        return tcpHeader
    }

    fun copy(): TCPHeader = TCPHeader(
        sourcePort, destinationPort, sequenceNumber, ackNumber,
        dataOffset, isNS, flags, windowSize, checksum, urgentPointer, options,
    )

    /** 解析 TCP 选项（MSS、窗口缩放、时间戳、SACK）。 */
    fun handleTcpOptions() {
        val opts = options ?: return
        var index = 0
        val packet = ByteBuffer.wrap(opts)
        val optionsSize = opts.size

        while (index < optionsSize) {
            val optionKind = packet.get()
            index++
            if (optionKind == END_OF_OPTIONS_LIST || optionKind == NO_OPERATION) continue

            if (index >= optionsSize) break
            val size = packet.get()
            index++

            when (optionKind) {
                MAX_SEGMENT_SIZE -> {
                    if (packet.remaining() >= 2) {
                        maxSegmentSize = packet.getShort()
                        index += 2
                    }
                }
                WINDOW_SCALE -> {
                    if (packet.hasRemaining()) {
                        windowScale = packet.get()
                        index++
                    }
                }
                SELECTIVE_ACK_PERMITTED -> isSelectiveAckPermitted = true
                TIME_STAMP -> {
                    if (packet.remaining() >= 8) {
                        timeStampSender = packet.getInt()
                        timeStampReplyTo = packet.getInt()
                        index += 8
                    }
                }
                else -> {
                    skipRemainingOptions(packet, size.toInt())
                    index = index + size - 2
                }
            }
        }
    }

    private fun skipRemainingOptions(packet: ByteBuffer, size: Int) {
        for (i in 2 until size) {
            if (!packet.hasRemaining()) break
            packet.get()
        }
    }

    override fun srcPort(): Int = sourcePort
    override fun dstPort(): Int = destinationPort
    fun setSourcePort(port: Int) { sourcePort = port }
    fun setDestinationPort(port: Int) { destinationPort = port }

    // ---- TCPHeaderLike 实现 ----
    override val srcPort: Int get() = sourcePort
    override val dstPort: Int get() = destinationPort
    override val seq: Long get() = sequenceNumber
    override val ack: Long get() = ackNumber
    override val window: Int get() = windowSize
    override val tcpHeaderLength: Int get() = getTCPHeaderLength()
}
