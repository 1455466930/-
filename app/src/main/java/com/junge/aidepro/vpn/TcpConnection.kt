package com.junge.aidepro.vpn

import android.util.Log
import com.junge.aidepro.vpn.transport.protocol.IP4Header
import com.junge.aidepro.vpn.transport.protocol.TCPHeader
import com.junge.aidepro.vpn.transport.protocol.UDPHeader
import com.junge.aidepro.vpn.util.PacketUtil
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.channels.SelectionKey
import java.nio.channels.spi.AbstractSelectableChannel

/**
 * 一条 VPN 会话（TCP 或 UDP）的状态。
 *
 * 移植自 ProxyPin（Apache 2.0）的 `com.network.proxy.vpn.Connection`。
 *
 * 【关键字段说明（用户态 TCP 必须维护的东西）】
 * - `recSequence`：我们已确认收到的客户端序号（回 ACK 时用）
 * - `sendUnAck`  ：客户端已确认的、我们发出的序号
 * - `sendNext`   ：我们下一个要发出的序号
 * - `receivingStream`：从出站 socket 收到、待封装回 tun 的数据
 * - `sendingStream`  ：从 tun 收到、待写到出站 socket 的数据
 *
 * 这两个流是**双向转发的中转站**：
 * ```
 * tun  --(sendingStream)-->  出站 socket
 * 出站 socket --(receivingStream)--> tun
 * ```
 */
class TcpConnection(
    val protocol: Protocol,
    val sourceIp: Int, val sourcePort: Int,
    val destinationIp: Int, val destinationPort: Int,
    private val closer: ConnectionCloser,
) {
    /** 出站 socket 通道（连到目标服务器或我们的代理） */
    var channel: AbstractSelectableChannel? = null
    var selectionKey: SelectionKey? = null

    /** 出站 socket 收到、待封装成 IP 包回给客户端的数据 */
    private val receivingStream = ByteArrayOutputStream()

    /** 从客户端收到、待写到出站 socket 的数据 */
    private val sendingStream = ByteArrayOutputStream()

    /** 是否已建立到目标的连接 */
    @Volatile var isConnected = false

    /** 是否已发起过连接（针对代理的延迟初始化） */
    @Volatile var isInitConnect = false

    /**
     * 这条连接归属的应用包名。
     * VPN 是路由层接管，只能通过 ConnectivityManager 反查 uid → 包名。
     * 拿到后会通过元数据头告诉 MITM 代理，用于数据入库时的应用归属。
     */
    @Volatile var ownerPackage: String? = null

    /** 是否正在关闭 */
    @Volatile var isClosingConnection = false

    /** 是否已中止（等待后台回收） */
    @Volatile var isAbortingConnection = false

    /** 客户端发过 FIN 且已被确认 */
    @Volatile var isAckedToFin = false

    /** 客户端数据已就绪，可以写到出站 socket */
    @Volatile var isDataForSendingReady = false

    @Volatile var hasReceivedLastSegment = false

    /** 最近一次来自客户端的头，回包时要翻转使用 */
    var lastIpHeader: IP4Header? = null
    var lastTcpHeader: TCPHeader? = null
    var lastUdpHeader: UDPHeader? = null

    var timestampSender = 0
    var timestampReplyTo = 0

    /** 我们已确认的客户端序号 */
    var recSequence: Long = 0

    /** 客户端 SYN 里通告的 MSS */
    var maxSegmentSize = 0

    /** 客户端已确认的、我们发出的序号 */
    var sendUnAck: Long = 0

    /** 我们下一个要发出的序号 */
    var sendNext: Long = 0

    /** 统计 */
    @Volatile var bytesFromClient = 0L
    @Volatile var bytesToClient = 0L

    // ==================== 数据搬运 ====================

    /** 把客户端数据放进发送队列。 */
    @Synchronized
    fun setSendingData(data: ByteArray, offset: Int, length: Int): Int {
        sendingStream.write(data, offset, length)
        return length
    }

    /** 出站 socket 收到数据，放入接收队列。 */
    @Synchronized
    fun addReceivedData(data: ByteArray?, length: Int = data?.size ?: 0) {
        if (data == null || length <= 0) return
        try {
            receivingStream.write(data, 0, length)
        } catch (e: IOException) {
            Log.e(TAG, "写入接收缓冲失败: ${e.message}")
        }
    }

    /**
     * 取出接收队列的数据（最多 maxSize 字节）。
     * 剩余部分留在队列里，下次继续。
     */
    @Synchronized
    fun getReceivedData(maxSize: Int): ByteArray? {
        if (receivingStream.size() == 0) return null
        var data = receivingStream.toByteArray()
        receivingStream.reset()
        if (data.size > maxSize) {
            val small = ByteArray(maxSize)
            System.arraycopy(data, 0, small, 0, maxSize)
            receivingStream.write(data, maxSize, data.size - maxSize)
            data = small
        }
        return data
    }

    fun hasReceivedData(): Boolean = receivingStream.size() > 0
    fun hasDataToSend(): Boolean = sendingStream.size() > 0

    /** 取出待发送数据（清空队列）。 */
    @Synchronized
    fun getSendingData(): ByteArray? {
        if (sendingStream.size() == 0) return null
        val data = sendingStream.toByteArray()
        sendingStream.reset()
        return data
    }

    // ==================== SelectionKey 操作 ====================

    fun cancelKey() {
        selectionKey?.let {
            synchronized(it) {
                if (it.isValid) it.cancel()
            }
        }
    }

    fun subscribeKey(op: Int) {
        selectionKey?.let {
            synchronized(it) {
                if (it.isValid) it.interestOps(it.interestOps() or op)
            }
        }
    }

    fun unsubscribeKey(op: Int) {
        selectionKey?.let {
            synchronized(it) {
                if (it.isValid) it.interestOps(it.interestOps() and op.inv())
            }
        }
    }

    fun closeConnection() {
        closer.closeConnection(this)
    }

    fun closeChannel() {
        runCatching { channel?.close() }
    }

    override fun toString(): String =
        "${protocol.name} ${PacketUtil.intToIPAddress(sourceIp)}:$sourcePort -> " +
            "${PacketUtil.intToIPAddress(destinationIp)}:$destinationPort"

    companion object {
        private const val TAG = "TcpConnection"

        /** 五元组 key。 */
        fun key(protocol: Protocol, srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int): String =
            "${protocol.name}|${PacketUtil.intToIPAddress(srcIp)}:$srcPort" +
                "->${PacketUtil.intToIPAddress(dstIp)}:$dstPort"
    }
}

/** 关闭连接的回调（由 ConnectionManager 实现）。 */
interface ConnectionCloser {
    fun closeConnection(conn: TcpConnection)
}
