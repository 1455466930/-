package com.sfzsgybb.ai.vpn.socket

import android.util.Log
import com.sfzsgybb.ai.vpn.TcpConnection
import java.io.IOException
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.nio.channels.spi.AbstractSelectableChannel
import java.util.concurrent.locks.ReentrantLock

/**
 * NIO 事件驱动服务：单线程处理所有会话的出站 socket 事件。
 *
 * 移植自 ProxyPin（Apache 2.0）的
 * `com.network.proxy.vpn.socket.SocketNIODataService`（Java）。
 *
 * 【为什么用 NIO 而不是一连接一线程】
 * VPN 上可能同时有几十上百条连接。每条连接开两个线程（读+写）
 * 会造成线程爆炸。NIO 用单个 Selector 处理所有 socket 的
 * 可读/可写/可连接事件，资源占用恒定。
 *
 * 【与 tun 读线程的分工】
 * - tun 读线程：阻塞读 tun，解析 IP 包，往里塞数据（写入 connection 的发送缓冲）
 * - NIO 线程：事件驱动，把发送缓冲里的数据写到出站 socket，
 *   把 socket 收到的数据封装成 IP 包写回 tun
 *
 * 两者通过 `Connection` 上的缓冲区和标志位交互，用 synchronized 保护。
 */
class SocketNioService(
    private val clientWriter: TunnelPacketWriter,
) : Runnable {

    private val selector: Selector = Selector.open()
    private val selectionLock = ReentrantLock()
    private val handlingLock = ReentrantLock()

    private val reader = SocketChannelReader(clientWriter)
    private val writer = SocketChannelWriter()

    @Volatile private var shutdown = false
    private var thread: Thread? = null

    fun start() {
        if (thread != null) return
        thread = Thread(this, "vpn-nio").apply {
            isDaemon = true
            start()
        }
    }

    override fun run() {
        Log.i(TAG, "NIO 事件循环启动")
        while (!shutdown) {
            try {
                selectionLock.lockInterruptibly()
                selector.select()
            } catch (e: IOException) {
                Log.e(TAG, "select() 异常: ${e.message}")
                runCatching { Thread.sleep(100) }
                continue
            } catch (e: InterruptedException) {
                Log.i(TAG, "select() 被中断")
            } finally {
                if (selectionLock.isHeldByCurrentThread) selectionLock.unlock()
            }

            if (shutdown) break

            handlingLock.lock()
            try {
                val iterator = selector.selectedKeys().iterator()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    val conn = key.attachment() as? TcpConnection
                    if (conn != null) {
                        synchronized(conn) {
                            try {
                                processKey(key, conn)
                            } catch (e: IOException) {
                                synchronized(key) { key.cancel() }
                            }
                        }
                    }
                    iterator.remove()
                    if (shutdown) break
                }
            } finally {
                handlingLock.unlock()
            }
        }
        Log.i(TAG, "NIO 事件循环结束")
    }

    /**
     * 注册会话到 Selector。
     * 已连接 → 监听可读；未连接 → 监听可连接。
     */
    @Throws(IOException::class)
    fun register(conn: TcpConnection) {
        val channel: AbstractSelectableChannel = conn.channel ?: return
        val isConnected = when (channel) {
            is DatagramChannel -> channel.isConnected
            is SocketChannel -> channel.isConnected
            else -> false
        }

        val lock = lockSelector()
        try {
            val key = channel.register(
                selector,
                if (isConnected) SelectionKey.OP_READ else SelectionKey.OP_CONNECT,
            )
            conn.selectionKey = key
            key.attach(conn)
        } finally {
            lock.unlock()
        }
    }

    /**
     * 立即尝试写出待发送数据（不依赖 selector 事件）。
     *
     * 【为什么需要】
     * 出站连接刚建立时，selector 只订阅了 OP_READ。
     * 如果只等事件，而对端也在等我们先发数据，就会互相死锁。
     * 建立连接后主动调用一次，把首批数据推出去。
     */
    fun writeNow(conn: TcpConnection) {
        try {
            if (conn.hasDataToSend() && conn.isDataForSendingReady) {
                writer.write(conn)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "立即写出失败: ${t.message}")
        }
    }

    /** 唤醒 selector（改完 interestOps 后调用）。 */
    fun refreshSelect(conn: TcpConnection) {
        if (!selectionLock.tryLock()) {
            runCatching { conn.selectionKey?.selector()?.wakeup() }
        } else {
            selectionLock.unlock()
        }
    }

    fun shutdown() {
        shutdown = true
        runCatching { selector.wakeup() }
        thread?.interrupt()
    }

    private fun lockSelector(): ReentrantLock {
        if (selectionLock.tryLock()) return selectionLock
        handlingLock.lock()
        runCatching { selector.wakeup() }
        selectionLock.lock()
        handlingLock.unlock()
        return selectionLock
    }

    private fun processKey(key: SelectionKey, conn: TcpConnection) {
        if (!key.isValid) return
        val channel = key.channel()
        if (channel == null) return

        // 连接完成事件
        if (channel is SocketChannel && !conn.isConnected && key.isConnectable) {
            if (channel.isConnectionPending) {
                conn.isConnected = channel.finishConnect()
                Log.d(TAG, "出站连接建立: ${conn}")
            } else {
                throw IllegalStateException("TCP 通道既未连接也未在连接中")
            }
        }

        val connected = when (channel) {
            is DatagramChannel -> channel.isConnected
            is SocketChannel -> channel.isConnected
            else -> false
        }
        if (!connected) return

        // 已连接：常驻监听可读
        conn.unsubscribeKey(SelectionKey.OP_CONNECT)
        conn.subscribeKey(SelectionKey.OP_READ)

        // 刚完成连接：立即把积压的数据写出去（避免与对端互等）
        if (conn.isDataForSendingReady && conn.hasDataToSend()) {
            conn.subscribeKey(SelectionKey.OP_WRITE)
            writer.write(conn)
        }

        // 读：socket → 缓冲区 → 封装成 IP 包回 tun
        val canRead = synchronized(key) { key.isValid && key.isReadable }
        if (canRead) {
            reader.read(conn)
        }

        // 写：把 tun 收到的数据写到出站 socket
        if (!conn.hasDataToSend() || !conn.isDataForSendingReady) {
            conn.unsubscribeKey(SelectionKey.OP_WRITE)
            return
        }
        val canWrite = synchronized(key) { key.isValid && key.isWritable }
        if (canWrite) {
            conn.unsubscribeKey(SelectionKey.OP_WRITE)
            writer.write(conn)
        }
    }

    companion object {
        private const val TAG = "SocketNioService"
    }
}

/** 向 tun 写包的接口，避免直接依赖具体实现。 */
interface TunnelPacketWriter {
    fun write(data: ByteArray): Boolean
}
