package com.sfzsgybb.ai.vpn.socket

import android.util.Log
import com.sfzsgybb.ai.vpn.TcpConnection
import com.sfzsgybb.ai.vpn.VpnLog
import com.sfzsgybb.ai.vpn.transport.protocol.TCPPacketFactory
import com.sfzsgybb.ai.vpn.transport.protocol.UDPPacketFactory
import java.nio.ByteBuffer
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.ClosedChannelException
import java.nio.channels.DatagramChannel
import java.nio.channels.NotYetConnectedException
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel

/**
 * 常量。
 */
object VpnSocketConstant {
    /**
     * 单次读取的最大字节数。
     *
     * 【为什么是 65535 而不是 16KB】
     * ProxyPin 原版是 65535（TCP 最大报文段的理论上限）。
     * 之前用 16KB 有两个后果：
     *  1. 大响应（HTML/CSS/图片）被切成 16KB 的小块反复搬运，慢；
     *  2. `hasReceivedLastSegment` 用 `dataSize < MAX` 判断「是否最后一段」，
     *     16KB 太小时这个判断恒真，导致每个包都打 PSH 标志 —— 语义错误。
     */
    const val MAX_RECEIVE_BUFFER_SIZE = 65535
}

/**
 * 出站 socket → tun 方向的数据搬运。
 *
 * 移植自 ProxyPin（Apache 2.0）的
 * `com.network.proxy.vpn.socket.SocketChannelReader`（Java）。
 *
 * 【职责】
 * NIO 线程发现某个出站 socket 可读时调用这里：
 *  1. 从 socket 读数据
 *  2. 把数据封装成 IP+TCP 包（`TCPPacketFactory.createResponsePacketData`）
 *  3. 写回 tun，客户端就收到了"服务器"的响应
 */
class SocketChannelReader(private val writer: TunnelPacketWriter) {

    /** 诊断：限制回包日志条数 */
    private val recvDiagCount = java.util.concurrent.atomic.AtomicInteger()

    fun read(conn: TcpConnection) {
        val channel = conn.channel
        when (channel) {
            is SocketChannel -> readTcp(conn)
            is DatagramChannel -> readUdp(conn)
            else -> return
        }

        // 重新订阅可读，保证后续数据还能触发
        conn.subscribeKey(SelectionKey.OP_READ)

        if (conn.isAbortingConnection) {
            Log.d(TAG, "回收已中止的连接 -> $conn")
            conn.cancelKey()
            runCatching {
                when (channel) {
                    is SocketChannel -> if (channel.isConnected) channel.close()
                    is DatagramChannel -> if (channel.isConnected) channel.close()
                }
            }
            conn.closeConnection()
        }
    }

    private fun readTcp(conn: TcpConnection) {
        if (conn.isAbortingConnection) return
        val channel = conn.channel as? SocketChannel ?: return
        val buffer = ByteBuffer.allocate(VpnSocketConstant.MAX_RECEIVE_BUFFER_SIZE)

        try {
            var len: Int
            do {
                len = channel.read(buffer)
                if (len > 0) {
                    pushToClient(buffer, len, conn)
                    buffer.clear()
                } else if (len == -1) {
                    // 服务器关闭了连接 → 给客户端发 FIN
                    Log.d(TAG, "对端关闭，发 FIN 给: $conn")
                    sendFin(conn)
                    conn.isAbortingConnection = true
                }
            } while (len > 0)
        } catch (e: NotYetConnectedException) {
            Log.e(TAG, "socket 尚未连接")
        } catch (e: ClosedByInterruptException) {
            Log.e(TAG, "读取被中断: ${e.message}")
        } catch (e: ClosedChannelException) {
            Log.e(TAG, "通道已关闭: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "读取出错: ${e.message}")
            conn.isAbortingConnection = true
        }
    }

    /**
     * 把从 socket 读到的数据封装成 IP 包写回 tun。
     *
     * 分片逻辑：按客户端通告的 MSS 切片，每次发一个 TCP 段。
     * 最后一片设置 PSH 标志（告诉客户端"数据到此为止，可以交给应用了"）。
     */
    private fun pushToClient(buffer: ByteBuffer, dataSize: Int, conn: TcpConnection) {
        // 诊断：记录从出站 socket 收到的数据
        if (recvDiagCount.get() < 30) {
            recvDiagCount.incrementAndGet()
            val preview = String(buffer.array(), 0, minOf(dataSize, 60), Charsets.ISO_8859_1)
                .replace("\r", "").replace("\n", " ")
            VpnLog.add("← 收到 ${dataSize}B: $preview")
        }

        // 数据量小于缓冲区上限，说明这是最后一段 → 打 PSH
        conn.hasReceivedLastSegment = dataSize < VpnSocketConstant.MAX_RECEIVE_BUFFER_SIZE

        val data = ByteArray(dataSize)
        System.arraycopy(buffer.array(), 0, data, 0, dataSize)
        conn.addReceivedData(data, dataSize)
        conn.bytesToClient += dataSize

        while (conn.hasReceivedData()) {
            pushOneSegment(conn)
        }
    }

    private fun pushOneSegment(conn: TcpConnection) {
        val ipHeader = conn.lastIpHeader ?: run {
            Log.w(TAG, "缺少 IP 头，无法回包: $conn")
            VpnLog.add("✗ 缺少 IP 头，丢弃回包数据")
            return
        }
        val tcpHeader = conn.lastTcpHeader ?: run {
            Log.w(TAG, "缺少 TCP 头，无法回包: $conn")
            VpnLog.add("✗ 缺少 TCP 头，丢弃回包数据")
            return
        }

        // 每段大小受客户端 MSS 限制，减 60 是保守余量（IP+TCP 头）
        var max = conn.maxSegmentSize - 60
        if (max < 1) max = 1024

        val body = conn.getReceivedData(max) ?: return
        if (body.isEmpty()) return

        val unAck = conn.sendNext
        conn.sendNext = unAck + body.size

        val packet = TCPPacketFactory.createResponsePacketData(
            ipHeader = ipHeader,
            tcpHeader = tcpHeader,
            packetData = body,
            isPsh = conn.hasReceivedLastSegment,
            ackNumber = conn.recSequence,
            seqNumber = unAck,
            timeSender = conn.timestampSender,
            timeReplyTo = conn.timestampReplyTo,
        )
        val ok = writer.write(packet)
        if (sendDiagCount.get() < 30) {
            sendDiagCount.incrementAndGet()
            VpnLog.add("→ 回包 ${body.size}B seq=$unAck ack=${conn.recSequence} ${if (ok) "成功" else "✗写tun失败"}")
        }
    }

    private val sendDiagCount = java.util.concurrent.atomic.AtomicInteger()

    private fun sendFin(conn: TcpConnection) {
        val ipHeader = conn.lastIpHeader ?: return
        val tcpHeader = conn.lastTcpHeader ?: return
        val data = TCPPacketFactory.createFinData(
            ipHeader, tcpHeader,
            conn.recSequence, conn.sendNext,
            conn.timestampSender, conn.timestampReplyTo,
        )
        writer.write(data)
    }

    private fun readUdp(conn: TcpConnection) {
        val channel = conn.channel as? DatagramChannel ?: return
        val buffer = ByteBuffer.allocate(VpnSocketConstant.MAX_RECEIVE_BUFFER_SIZE)

        try {
            var len: Int
            do {
                if (conn.isAbortingConnection) break
                len = channel.read(buffer)
                if (len > 0) {
                    val data = ByteArray(len)
                    System.arraycopy(buffer.array(), 0, data, 0, len)

                    val ipHeader = conn.lastIpHeader
                    val udpHeader = conn.lastUdpHeader
                    if (ipHeader != null && udpHeader != null) {
                        val packet = UDPPacketFactory.createResponsePacket(ipHeader, udpHeader, data)
                        writer.write(packet)
                    }
                    conn.bytesToClient += len
                    buffer.clear()
                }
            } while (len > 0)
        } catch (e: NotYetConnectedException) {
            Log.e(TAG, "UDP socket 未连接")
        } catch (e: Exception) {
            Log.e(TAG, "UDP 读取失败: ${e.message}")
            conn.isAbortingConnection = true
        }
    }

    companion object {
        private const val TAG = "SocketChannelReader"
    }
}

/**
 * tun → 出站 socket 方向的数据搬运。
 *
 * 移植自 ProxyPin（Apache 2.0）的
 * `com.network.proxy.vpn.socket.SocketChannelWriter`（Java）。
 *
 * 【职责】
 * 把客户端发来的数据（存在 `sendingStream`）写到出站 socket。
 * 如果一次写不完（socket 缓冲满），重新订阅 OP_WRITE，等下次可写继续。
 */
class SocketChannelWriter {

    fun write(conn: TcpConnection) {
        val channel = conn.channel

        if (channel is SocketChannel) {
            writeTcp(conn, channel)
        } else if (channel is DatagramChannel) {
            writeUdp(conn, channel)
        }

        if (conn.isAbortingConnection) {
            conn.cancelKey()
            runCatching { channel?.close() }
            conn.closeConnection()
        }
    }

    private fun writeTcp(conn: TcpConnection, channel: SocketChannel) {
        if (conn.isAbortingConnection) return

        try {
            while (conn.hasDataToSend()) {
                val data = conn.getSendingData() ?: break
                if (data.isEmpty()) break

                val buffer = ByteBuffer.wrap(data)
                val written = channel.write(buffer)

                if (written < data.size) {
                    // 没写完：剩下的塞回队列，并订阅可写等待下次
                    conn.setSendingData(data, written, data.size - written)
                    conn.subscribeKey(SelectionKey.OP_WRITE)
                    Log.d(TAG, "socket 缓冲满，剩 ${data.size - written}B 待写")
                    return
                }
            }
            // 写完了，不再需要可写通知
            conn.unsubscribeKey(SelectionKey.OP_WRITE)
        } catch (e: Exception) {
            Log.e(TAG, "写出错: ${e.message}")
            conn.isAbortingConnection = true
        }
    }

    private fun writeUdp(conn: TcpConnection, channel: DatagramChannel) {
        try {
            while (conn.hasDataToSend()) {
                val data = conn.getSendingData() ?: break
                if (data.isEmpty()) break
                channel.write(ByteBuffer.wrap(data))
            }
            conn.unsubscribeKey(SelectionKey.OP_WRITE)
        } catch (e: Exception) {
            Log.e(TAG, "UDP 写出错: ${e.message}")
            conn.isAbortingConnection = true
        }
    }

    companion object {
        private const val TAG = "SocketChannelWriter"
    }
}
