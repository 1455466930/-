package com.junge.aidepro.mitm

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLSession

/**
 * 用 SSLEngine 手动驱动的 TLS 终结器。
 *
 * 【为什么不用 SSLSocket】
 * MITM 必须先把客户端发来的 ClientHello 读出来解析 SNI，
 * 这段字节已从 socket 流消费掉，之后必须"还回去"给 TLS 层。
 * SSLSocketFactory.createSocket(Socket, InputStream consumed, boolean)
 * 在 Android（Conscrypt）上语义不可靠，实测握手卡死。
 *
 * SSLEngine 是 JSSE 底层接口：不给它数据它就不动，
 * 我们把已读的 ClientHello 和后续 socket 数据一并喂进去，
 * 完全掌控每个字节。
 *
 * 【缓冲区管理要点】
 * 这是 SSLEngine 最容易写错的地方：
 *  - netIn 在读模式/写模式间显式切换，不用 compact 混用
 *  - BUFFER_UNDERFLOW 表示数据不够，需要继续从 socket 读
 *  - BUFFER_OVERFLOW 表示输出缓冲不够，扩容后重试
 *  - NEED_TASK 必须把所有 delegatedTask 跑完
 */
class TlsTerminator(
    private val socket: Socket,
    private val engine: SSLEngine,
    /** 已从 socket 读走、需先喂给 engine 的字节（ClientHello） */
    private val consumed: ByteArray,
) {
    private val rawIn: InputStream = socket.getInputStream()
    private val rawOut: OutputStream = socket.getOutputStream()

    /** 网络层输入缓冲（密文）。始终维护为「读模式」：position..limit 是可读数据。 */
    private var netIn: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize + 1024)

    /** 网络层输出缓冲（密文）。 */
    private var netOut: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize + 1024)

    /** 应用层输入缓冲（明文）。 */
    private var appIn: ByteBuffer = ByteBuffer.allocate(engine.session.applicationBufferSize + 1024)

    /** 已解密但尚未被 read() 取走的明文。 */
    private val appPlain = ByteArrayOutputStream(16 * 1024)

    /** 已读到流末尾。 */
    @Volatile private var eof = false

    @Volatile var lastError: String? = null
        private set

    val isEof: Boolean get() = eof

    val session: SSLSession get() = engine.session

    init {
        netIn.limit(0)
    }

    private fun hasNetData(): Boolean = netIn.hasRemaining()

    /** 把 netIn 切到写模式准备追加数据（保留未读完的部分）。 */
    private fun netInToWrite() {
        if (!netIn.hasRemaining()) {
            netIn.clear()
        } else {
            netIn.compact()
        }
    }

    /** 把 netIn 切回读模式。 */
    private fun netInToRead() {
        netIn.flip()
    }

    /** 从 socket 读更多密文。返回读到的字节数，-1 = 连接关闭。 */
    /**
     * 从 socket 读更多密文。
     * @return >0 读到字节；0 无数据（超时）；-1 流结束
     */
    private fun fillNetIn(): Int {
        if (eof) return -1
        netInToWrite()
        if (!netIn.hasRemaining()) {
            val bigger = ByteBuffer.allocate(netIn.capacity() * 2)
            netIn.flip()
            bigger.put(netIn)
            netIn = bigger
        }
        val n = try {
            rawIn.read(netIn.array(), netIn.position(), netIn.remaining())
        } catch (t: java.net.SocketTimeoutException) {
            0
        }
        if (n > 0) netIn.position(netIn.position() + n)
        if (n < 0) eof = true
        netInToRead()
        return n
    }

    /** 完成 TLS 握手。 */
    fun handshake(): Boolean {
        try {
            Log.d(TAG, "开始握手: 协议=${engine.enabledProtocols.joinToString()} " +
                "ALPN=${engine.sslParameters.applicationProtocols?.joinToString()}")
            engine.beginHandshake()

            if (consumed.isNotEmpty()) {
                if (consumed.size > netIn.capacity()) {
                    netIn = ByteBuffer.allocate(consumed.size + 1024)
                }
                netIn.clear()
                netIn.put(consumed)
                netIn.flip()
            }

            var loops = 0
            var stall = 0

            while (loops++ < MAX_HANDSHAKE_LOOPS) {
                when (engine.handshakeStatus) {
                    SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                        if (!hasNetData()) {
                            val n = fillNetIn()
                            if (n < 0) { lastError = "客户端在握手期间关闭连接"; return false }
                            if (n == 0) { if (++stall > MAX_STALL) { lastError = "握手停滞"; return false }; continue }
                        }
                        stall = 0
                        val before = netIn.remaining()
                        appIn.clear()
                        val res = engine.unwrap(netIn, appIn)
                        Log.d(TAG, "unwrap: 输入剩余=$before 状态=${res.status} " +
                            "握手=${res.handshakeStatus} 消耗=${res.bytesConsumed()} 产出=${res.bytesProduced()}")
                        when (res.status) {
                            SSLEngineResult.Status.OK -> Unit
                            SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                                val n = fillNetIn()
                                if (n < 0) { lastError = "握手期连接关闭"; return false }
                            }
                            SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                                appIn = ByteBuffer.allocate(appIn.capacity() * 2)
                            }
                            SSLEngineResult.Status.CLOSED -> { lastError = "engine 已关闭"; return false }
                        }
                    }

                    SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                        netOut.clear()
                        val res = engine.wrap(EMPTY, netOut)
                        when (res.status) {
                            SSLEngineResult.Status.OK -> {
                                netOut.flip()
                                if (netOut.hasRemaining()) {
                                    rawOut.write(netOut.array(), netOut.position(), netOut.remaining())
                                    rawOut.flush()
                                }
                            }
                            SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                                netOut = ByteBuffer.allocate(netOut.capacity() * 2)
                            }
                            else -> Unit
                        }
                    }

                    SSLEngineResult.HandshakeStatus.NEED_TASK -> runTasks()

                    SSLEngineResult.HandshakeStatus.FINISHED,
                    SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING -> {
                        Log.d(TAG, "TLS 握手完成 proto=" + engine.session.protocol)
                        return true
                    }
                }
            }
            lastError = "握手循环超限（" + MAX_HANDSHAKE_LOOPS + "）"
            return false
        } catch (t: Throwable) {
            lastError = t.javaClass.simpleName + ": " + t.message
            Log.w(TAG, "TLS 握手失败", t)
            return false
        }
    }

    /** 读取解密后的应用数据。返回字节数，-1 = 连接关闭。 */
    /**
     * 读取解密后的应用数据。
     *
     * 【关键】BUFFER_UNDERFLOW 必须去 socket 读更多数据。
     * 早期版本在这里什么都不做就继续循环，导致对着同一份数据反复 unwrap，
     * 转发线程彻底卡死 —— 表现为「目标应用一直转圈、抓不到事务」。
     *
     * @return 读到的字节数；-1 表示连接结束
     */
    fun read(out: ByteArray): Int {
        try {
            var guard = 0
            while (guard++ < MAX_READ_LOOPS) {
                // 1) 有已解密数据，直接返回
                if (appPlain.size() > 0) {
                    val data = appPlain.toByteArray()
                    appPlain.reset()
                    val take = minOf(data.size, out.size)
                    System.arraycopy(data, 0, out, 0, take)
                    if (take < data.size) appPlain.write(data, take, data.size - take)
                    return take
                }

                // 2) 没有待处理密文就去读
                if (!hasNetData()) {
                    val n = fillNetIn()
                    if (n < 0) return -1
                    if (n == 0) {
                        // 无数据可读（超时）：让出 CPU，避免空转烧 CPU
                        try { Thread.sleep(5) } catch (e: InterruptedException) { return -1 }
                        continue
                    }
                }

                // 3) 解密
                appIn.clear()
                val res = engine.unwrap(netIn, appIn)
                when (res.status) {
                    SSLEngineResult.Status.OK -> {
                        appIn.flip()
                        if (appIn.hasRemaining()) {
                            val b = ByteArray(appIn.remaining())
                            appIn.get(b)
                            val take = minOf(b.size, out.size)
                            System.arraycopy(b, 0, out, 0, take)
                            if (take < b.size) appPlain.write(b, take, b.size - take)
                            return take
                        }
                        // unwrap 成功但没产出应用数据，继续
                    }
                    SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                        // 必须读更多，否则死循环
                        val n = fillNetIn()
                        if (n < 0) return -1
                    }
                    SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                        appIn = ByteBuffer.allocate(appIn.capacity() * 2)
                    }
                    SSLEngineResult.Status.CLOSED -> return -1
                }

                runTasks()
                if (engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) flushWrap()
            }
            lastError = "read 循环超限"
            return -1
        } catch (t: Throwable) {
            lastError = t.javaClass.simpleName + ": " + t.message
            return -1
        }
    }

    /** 发送应用数据（加密后写出）。 */
    fun write(data: ByteArray, offset: Int, length: Int): Boolean {
        return try {
            var off = offset
            var remaining = length
            while (remaining > 0) {
                val chunk = minOf(remaining, MAX_WRAP_CHUNK)
                val src = ByteBuffer.wrap(data, off, chunk)
                var guard = 0
                while (src.hasRemaining() && guard++ < MAX_WRITE_LOOPS) {
                    netOut.clear()
                    val res = engine.wrap(src, netOut)
                    when (res.status) {
                        SSLEngineResult.Status.OK, SSLEngineResult.Status.CLOSED -> {
                            netOut.flip()
                            if (netOut.hasRemaining()) {
                                rawOut.write(netOut.array(), netOut.position(), netOut.remaining())
                                rawOut.flush()
                            }
                        }
                        SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                            netOut = ByteBuffer.allocate(netOut.capacity() * 2)
                        }
                        else -> Unit
                    }
                    runTasks()
                }
                off += chunk
                remaining -= chunk
            }
            true
        } catch (t: Throwable) {
            lastError = t.javaClass.simpleName + ": " + t.message
            false
        }
    }

    fun close() {
        runCatching {
            engine.closeOutbound()
            flushWrap()
        }
        runCatching { socket.close() }
    }

    private fun flushWrap() {
        runCatching {
            netOut.clear()
            val res = engine.wrap(EMPTY, netOut)
            if (res.bytesProduced() > 0) {
                netOut.flip()
                rawOut.write(netOut.array(), netOut.position(), netOut.remaining())
                rawOut.flush()
            }
        }
    }

    private fun runTasks() {
        var t = engine.delegatedTask
        while (t != null) {
            runCatching { t.run() }
            t = engine.delegatedTask
        }
    }

    companion object {
        private const val TAG = "TlsTerminator"
        private const val MAX_HANDSHAKE_LOOPS = 200
        private const val MAX_READ_LOOPS = 500
        private const val MAX_WRITE_LOOPS = 200
        private const val MAX_STALL = 100
        private const val MAX_WRAP_CHUNK = 16 * 1024
        private val EMPTY: ByteBuffer = ByteBuffer.allocate(0)
    }
}
