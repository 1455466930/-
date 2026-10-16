package com.junge.aidepro.mitm

import android.util.Log
import java.io.InputStream
import java.net.Socket
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * 用 SSLSocket + PushbackInputStream 做 TLS 中间人终结。
 *
 * 【设计思路】
 * MITM 必须先把客户端发来的 ClientHello 读出来解析 SNI，
 * 这段字节已经从 socket 流消费掉了，之后必须「还回去」给 TLS 层。
 *
 * 试过两条路都不行：
 *  1. `SSLSocketFactory.createSocket(Socket, InputStream consumed, boolean)`
 *     —— consumed 是「一次性」的，之后直接读 socket 原始流，
 *     已读数据无法与后续数据正确拼接，握手卡死。
 *  2. `SSLEngine` 手工驱动
 *     —— 需要自己维护 netIn/netOut/appIn 三个缓冲区与状态机，
 *     边界情况极多（实测 BUFFER_UNDERFLOW 反复、握手成功率不足 20%）。
 *
 * 【本方案】
 * 把已读字节**推回流里**（PushbackInputStream），
 * 然后用系统的 `SSLSocket` 做握手。
 *
 * 关键区别：Pushback 的数据会**参与后续所有读操作**，
 * 所以 SSLSocket 看到的就是一条从 ClientHello 开始的完整 TCP 流 ——
 * 和它平时处理的 socket 没有区别，因此不需要任何特殊处理。
 *
 * 这样 TLS 状态机、缓冲区管理、重协商全部交给系统成熟实现，
 * 我们只负责把 SNI 那几字节还回去。
 */
object TlsMitm {

    private const val TAG = "TlsMitm"

    /** 预读上限：够放下 ClientHello 即可 */
    const val PEEK_LIMIT = 8 * 1024

    /**
     * 包一层 PushbackInputStream，读 ClientHello 拿 SNI。
     *
     * @return Pair(带pushback的输入流, ClientHello原始字节)；识别失败返回 null
     */
    fun peekClientHello(rawIn: InputStream): Pair<java.io.PushbackInputStream, ByteArray>? {
        val pb = java.io.PushbackInputStream(rawIn, PEEK_LIMIT)
        val hello = TlsSniffer.readClientHello(pb) ?: return null
        // 把读走的字节推回去，之后的 SSLSocket 会重新读到它们
        if (hello.raw.isNotEmpty()) {
            pb.unread(hello.raw)
        }
        return pb to hello.raw
    }

    /**
     * 在已连接的 socket 上做服务端 TLS 握手（冒充目标服务器）。
     *
     * @param socket  已连接的原生 socket
     * @param pbIn    携带了 ClientHello 的 pushback 流（由 [peekClientHello] 得到）
     * @param ctx     为该域名准备好的 SSLContext（含动态签发的证书）
     */
    fun handshakeAsServer(socket: Socket, pbIn: java.io.PushbackInputStream, ctx: SSLContext): SSLSocket {
        // Java 的 SSLSocketFactory 有个重载可以接受「已连接的 socket」，
        // 但它内部会重新包装 socket 流，我们推回去的数据会丢失。
        //
        // 所以换一种做法：把 socket 的输入流替换掉。
        // SSLSocketFactory.createSocket(Socket, String host, int port, boolean autoClose)
        // 会用 socket 的 getInputStream() 读数据 —— 我们在握手前
        // 把 pushback 流「贴」到 socket 上。
        //
        // 实现方式：用 Socket 的装饰器，让 getInputStream() 返回我们的 pushback 流。
        val wrapped = SocketWrapper(socket, pbIn)

        val factory = ctx.socketFactory
        val ssl = factory.createSocket(wrapped, socket.inetAddress.hostAddress, socket.port, true) as SSLSocket

        ssl.useClientMode = false
        // ALPN 只声明 http/1.1：不支持 HTTP/2 解密
        runCatching {
            val p = ssl.sslParameters
            p.applicationProtocols = arrayOf("http/1.1")
            ssl.sslParameters = p
        }
        ssl.soTimeout = socket.soTimeout

        ssl.startHandshake()
        Log.d(TAG, "服务端握手完成 proto=${ssl.session.protocol}")
        return ssl
    }

    /**
     * 让 socket 的输入流变成我们提供的 pushback 流。
     *
     * `SSLSocketFactory.createSocket(Socket, ...)` 内部会调用
     * `socket.getInputStream()`，所以只要让它返回我们的流即可。
     */
    private class SocketWrapper(
        private val delegate: Socket,
        private val wrappedIn: InputStream,
    ) : Socket() {
        init {
            // 复制基本属性，部分 SSLSocket 实现会读这些
            runCatching {
                soTimeout = delegate.soTimeout
                tcpNoDelay = delegate.tcpNoDelay
                keepAlive = delegate.keepAlive
            }
        }

        override fun getInputStream(): InputStream = wrappedIn
        override fun getOutputStream() = delegate.getOutputStream()
        override fun getInetAddress() = delegate.inetAddress
        override fun getPort() = delegate.port
        override fun getLocalAddress() = delegate.localAddress
        override fun getLocalPort() = delegate.localPort
        override fun isConnected() = delegate.isConnected
        override fun isClosed() = delegate.isClosed
        override fun close() = delegate.close()
        override fun shutdownInput() = delegate.shutdownInput()
        override fun shutdownOutput() = delegate.shutdownOutput()
        override fun setTcpNoDelay(on: Boolean) = delegate.setTcpNoDelay(on)
        override fun setSoTimeout(timeout: Int) = delegate.setSoTimeout(timeout)
        override fun getSoTimeout() = delegate.soTimeout
        override fun setKeepAlive(on: Boolean) = delegate.setKeepAlive(on)
        override fun toString(): String = "SocketWrapper($delegate)"
    }

    /**
     * 客户端侧握手（连真实服务器）。
     * 这条路径不需要 pushback（没有预读），直接用系统实现。
     */
    fun handshakeAsClient(host: String, port: Int, connectTimeoutMs: Int, readTimeoutMs: Int): SSLSocket {
        val plain = Socket()
        plain.tcpNoDelay = true
        plain.connect(java.net.InetSocketAddress(host, port), connectTimeoutMs)
        plain.soTimeout = readTimeoutMs

        val ctx = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<javax.net.ssl.TrustManager>(TrustAll), java.security.SecureRandom())
        }
        val ssl = ctx.socketFactory.createSocket(plain, host, port, true) as SSLSocket
        ssl.useClientMode = true
        runCatching {
            val p = ssl.sslParameters
            p.applicationProtocols = arrayOf("http/1.1")
            ssl.sslParameters = p
        }
        ssl.startHandshake()
        Log.d(TAG, "客户端握手完成 $host proto=${ssl.session.protocol}")
        return ssl
    }

    /** 信任所有证书（仅用于连真实服务器时绕过校验）。 */
    private object TrustAll : javax.net.ssl.X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
