package com.junge.aidepro.mitm

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/**
 * MITM 代理服务器。
 *
 * 【工作流程】
 * ```
 * 客户端 → ServerSocket:port
 *   ↓ 读第一个数据包
 *   ├─ 明文 HTTP（GET/POST...）     → 直接解析并转发
 *   └─ CONNECT host:port            → 建隧道
 *        ↓ 隧道内首个包是 TLS ClientHello
 *        ↓ 从 ClientHello 提取 SNI 域名
 *        ↓ CertificateManager 签发该域名证书
 *        ↓ SSLSocket 与客户端握手（冒充服务器）
 *        ↓ 同时连真实服务器并握手
 *        ↓ 双向解密 → 解析 HTTP → 转发
 * ```
 *
 * 【与原版算法助手的区别】
 * 原版用 Xposed Hook Java API，只能看到 Java 网络栈。
 * 本实现工作在**网络层**，能看到所有走代理的流量，
 * 包括 Flutter / ReactNative / native 库 / 加固应用。
 *
 * 【参考】
 * ProxyPin 的 lib/network/channel/network.dart（Server.onEvent / ssl）
 */
class MitmProxyServer(
    private val config: Config = Config(),
) {
    data class Config(
        val port: Int = DEFAULT_PORT,
        val bindAddress: String = "127.0.0.1",
        /** 是否启用 HTTPS 解密（关闭则只做隧道转发，不解密） */
        val enableHttpsDecrypt: Boolean = true,
        /** 连接超时 */
        val connectTimeoutMs: Int = 8000,
        val readTimeoutMs: Int = 30_000,
    )

    interface Listener {
        fun onStarted(port: Int)
        fun onStopped()
        fun onError(t: Throwable)
        /** 收到一个完整的 HTTP 事务 */
        fun onTransaction(tx: HttpTransaction)
        /** 握手失败等诊断信息 */
        fun onLog(level: Char, message: String)
    }

    var listener: Listener? = null

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "mitm-worker").apply { isDaemon = true }
    }

    /** 统计。 */
    val activeConnections = AtomicInteger(0)
    val totalConnections = AtomicLong(0)
    val totalTransactions = AtomicLong(0)
    val totalBytesIn = AtomicLong(0)
    val totalBytesOut = AtomicLong(0)

    /** 诊断开关：dump 解密后的原始数据。 */
    /** 透明模式（VPN 转发）处理的连接数 */
    private val snifCount = java.util.concurrent.atomic.AtomicLong()

    @Volatile var diagnosticDump: Boolean = true
    private val diagCount = AtomicInteger(0)

    @Volatile var actualPort: Int = -1
        private set

    val isRunning: Boolean get() = running.get()

    fun start(): Boolean {
        if (running.get()) return true
        return try {
            val port = PortFinder.find(config.port)
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(config.bindAddress, port), BACKLOG)
            serverSocket = ss
            actualPort = port
            running.set(true)

            pool.execute {
                while (running.get() && !ss.isClosed) {
                    try {
                        val socket = ss.accept()
                        totalConnections.incrementAndGet()
                        pool.execute { handleConnection(socket) }
                    } catch (e: IOException) {
                        if (running.get()) listener?.onError(e)
                        break
                    }
                }
            }

            Log.i(TAG, "MITM 代理已启动: ${config.bindAddress}:$port")
            listener?.onStarted(port)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "代理启动失败", t)
            listener?.onError(t)
            false
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        actualPort = -1
        listener?.onStopped()
        Log.i(TAG, "MITM 代理已停止")
    }

    // ==================== 连接处理 ====================

    private fun handleConnection(socket: Socket) {
        activeConnections.incrementAndGet()
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = config.readTimeoutMs

            // 注意：这里刻意不用 BufferedInputStream。
            //
            // 原因：BufferedInputStream 会预读 16KB，把 CONNECT 之后的数据
            // （即 TLS ClientHello 以及后续握手数据）一起吞进它的内部缓冲。
            // 之后做 MITM 握手时需要把这些数据"还给" SSLSocket，
            // 但 BufferedInputStream 只能吐出被 read() 消费过的部分，
            // 未消费的缓冲数据会丢失 —— 表现为 SSLHandshakeException: connection closed。
            //
            // 改用裸流 + 自己管理读取，保证「读了多少就还多少」。
            // 用 PushbackInputStream 包一层：
            // 需要「先看看是不是 META 头，不是就放回去」，普通流做不到回退。
            val rawIn = java.io.PushbackInputStream(socket.getInputStream(), META_PEEK)
            val output = BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE)

            // ---------- 读 VPN 传来的元数据头 ----------
            //
            // VPN 是路由层接管，只知道 IP 包，不知道是哪个应用的流量。
            // 所以它在转发到本代理时会先发一行：
            //     META <packageName>\r\n
            // 我们读出来，用于把抓到的数据归属到具体应用
            // （否则用户没法按应用筛选，只能在一大堆请求里翻）。
            val owner = readMetaHeader(rawIn)

            // 先窥探前几个字节，判断是不是 TLS。
            //
            // 【为什么需要这一步】
            // 流量来源有两种：
            //  1. 系统代理模式：客户端知道有代理，HTTPS 会先发 `CONNECT host:443`
            //  2. VPN 透明模式：客户端不知道有代理，直接发裸 TLS ClientHello
            //     （因为 VPN 在 IP 层转发，443 端口的目标被换成了我们）
            //
            // 第 2 种情况下没有 CONNECT，如果按 HTTP 头去读，
            // 会一直找不到 \r\n\r\n 直到超时 —— 表现为「应用一直转圈」。
            val peek = peekBytes(rawIn, 6)
            if (peek == null) return
            // peekBytes 已经把字节消费掉了，推回去供后续解析
            rawIn.unread(peek)

            if (isTlsClientHello(peek)) {
                // 透明模式：从 ClientHello 里取 SNI 作为目标主机
                handleTransparentTls(socket, rawIn, output, peek, owner)
                return
            }

            // 读第一个请求头（CONNECT 或普通 HTTP）
            val headRead = HttpMessageReader.readHeadWithConsumed(rawIn)
            val first = headRead.head ?: return

            if (first.isConnect) {
                handleConnect(socket, rawIn, output, first, headRead.consumed, owner)
            } else {
                handlePlainHttp(socket, rawIn, output, first, owner)
            }
        } catch (t: Throwable) {
            listener?.onLog('E', "连接处理异常: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            activeConnections.decrementAndGet()
            runCatching { socket.close() }
        }
    }

    /**
     * 读 VPN 传来的元数据头 `META <packageName>\r\n`。
     *
     * 不是元数据头时，把读到的字节 unread 回 PushbackInputStream，
     * 不影响后续的正常解析。
     *
     * @return 包名；没有元数据头返回 null
     */
    private fun readMetaHeader(input: java.io.PushbackInputStream): String? {
        val head = ByteArray(META_PREFIX.length)
        var read = 0
        while (read < head.size) {
            val n = input.read(head, read, head.size - read)
            if (n < 0) {
                if (read > 0) input.unread(head, 0, read)
                return null
            }
            read += n
        }
        if (String(head, Charsets.ISO_8859_1) != META_PREFIX) {
            // 不是元数据头 → 放回去
            input.unread(head)
            return null
        }
        // 读到换行
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 256) break
        }
        return sb.toString().trim().ifBlank { null }
    }

    /**
     * 从流里预读若干字节但不消费（用 PushbackInputStream 之类会复杂化，
     * 这里直接把读到的字节返回，后续处理时一并交给解析器）。
     */
    private fun peekBytes(input: InputStream, count: Int): ByteArray? {
        val buf = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(buf, read, count - read)
            if (n < 0) {
                return if (read > 0) buf.copyOf(read) else null
            }
            read += n
        }
        return buf
    }

    /**
     * 判断是否是 TLS ClientHello。
     *
     * 记录层：ContentType(1) | Version(2) | Length(2) | HandshakeType(1)
     * TLS: 0x16 0x03 0x0X ...；ClientHello 的 HandshakeType = 0x01
     */
    private fun isTlsClientHello(b: ByteArray): Boolean {
        if (b.size < 6) return false
        val contentType = b[0].toInt() and 0xFF
        val major = b[1].toInt() and 0xFF
        val minor = b[2].toInt() and 0xFF
        val handshake = b[5].toInt() and 0xFF
        return contentType == 0x16 && major == 0x03 && minor in 0x00..0x04 && handshake == 0x01
    }

    /**
     * 透明 TLS 模式：客户端没发 CONNECT，上来就是 ClientHello。
     *
     * 目标主机取自 ClientHello 的 SNI 扩展。
     * 之后的流程和 CONNECT 模式一样（动态签发证书 + 双向解密）。
     */
    private fun handleTransparentTls(
        socket: Socket,
        rawIn: InputStream,
        output: BufferedOutputStream,
        preRead: ByteArray,
        owner: String?,
    ) {
        // 透明模式：客户端没发 CONNECT，上来就是裸 TLS。
        //
        // 【注意】preRead 的数据**已经在 rawIn 里了**
        // —— handleConnection 里 peekBytes 读完后调了 rawIn.unread(peek)。
        // 所以这里直接用 rawIn，**不能**再拼 preRead，
        // 否则数据重复、ClientHello 解析错位（表现为「无 SNI」）。
        listener?.onLog('D', "透明 TLS 模式（${preRead.size}B 预读）")
        snifCount.incrementAndGet()

        proxyTls(socket, rawIn, output, sniHint = "", port = 443, owner = owner)
    }

    /**
     * 处理 CONNECT 隧道（HTTPS）。
     *
     * @param rawIn 裸流（不能用 BufferedInputStream，会预读导致 TLS 数据丢失）
     * @param connectConsumed 读 CONNECT 头时消费掉的字节
     */
    private fun handleConnect(
        client: Socket,
        rawIn: InputStream,
        clientOut: BufferedOutputStream,
        connect: HttpMessageReader.Head,
        connectConsumed: ByteArray,
        owner: String?,
    ) {
        val hostPort = connect.uri.removePrefix("https://").removePrefix("http://")
        val host = hostPort.substringBefore(':')
        val port = hostPort.substringAfter(':', "443").toIntOrNull() ?: 443

        val decrypt = config.enableHttpsDecrypt && CertificateManager.isReady

        if (!decrypt) {
            // 不解密：直接建隧道转发
            clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            clientOut.flush()
            val remote = Socket()
            remote.connect(InetSocketAddress(host, port), config.connectTimeoutMs)
            tunnel(client, remote)
            return
        }

        // 通知客户端隧道已建立（仅 CONNECT 模式需要，透明模式没有这一步）
        clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
        clientOut.flush()

        proxyTls(client, rawIn, clientOut, sniHint = host, port = port, owner = owner)
    }

    /**
     * TLS 中间人处理 —— CONNECT 模式与透明模式共用。
     *
     * @param sniHint 已知的目标主机（透明模式下从 ClientHello 的 SNI 得到）
     */
    /**
     * TLS 中间人处理（CONNECT 模式与透明模式共用）。
     *
     * 【为什么用 SSLSocket 而不是 SSLEngine】
     * 早期用 SSLEngine 手工驱动握手，需要自己维护 netIn/netOut/appIn
     * 三个缓冲区和完整状态机。实测边界情况极多（BUFFER_UNDERFLOW 反复出现），
     * 握手成功率不足 20%，且失败表现为「应用一直转圈、网络不通」。
     *
     * 改用系统 SSLSocket + PushbackInputStream 后，TLS 状态机交给
     * Android 成熟实现，我们只负责把预读的 ClientHello 推回流里。
     *
     * @param rawIn CONNECT 模式传原始流；透明模式传已包含 ClientHello 的 PushbackInputStream
     */
    private fun proxyTls(
        client: Socket,
        rawIn: java.io.InputStream,
        clientOut: java.io.BufferedOutputStream,
        sniHint: String,
        port: Int,
        owner: String? = null,
    ) {
        // 先读 ClientHello，拿到真实 SNI（透明模式 sniHint 为空，必须从 SNI 取）
        val clientHello = TlsSniffer.readClientHello(rawIn)
        val consumed = clientHello?.raw ?: ByteArray(0)
        val sni = clientHello?.serverName ?: sniHint

        if (sni.isBlank()) {
            listener?.onLog('E', "无法确定目标主机（无 SNI 且无 CONNECT 头）")
            return
        }
        listener?.onLog('D', "MITM: $sni ClientHello=${consumed.size}B")

        val ctx = CertificateManager.getContext(sni) ?: run {
            listener?.onLog('E', "无法为 $sni 创建 SSLContext")
            return
        }

        // 服务端 SSLEngine
        val serverEngine = ctx.createSSLEngine().apply {
            useClientMode = false
            enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")
            runCatching {
                val p = sslParameters
                p.applicationProtocols = arrayOf("http/1.1")
                sslParameters = p
            }
        }

        val clientTls = TlsTerminator(client, serverEngine, consumed)
        if (!clientTls.handshake()) {
            listener?.onLog('W', "与客户端握手失败 $sni: ${clientTls.lastError}")
            runCatching {
                val realCert = fetchRemoteCert(sni, port)
                if (realCert != null) CertificateManager.regenerateByRemoteCert(sni, realCert)
            }
            return
        }
        listener?.onLog('D', "服务端握手完成 $sni proto=${serverEngine.session.protocol}")

        // 连真实服务器（客户端侧 SSLEngine）
        val remotePlain = Socket()
        remotePlain.tcpNoDelay = true
        remotePlain.connect(java.net.InetSocketAddress(sni, port), config.connectTimeoutMs)
        remotePlain.soTimeout = config.readTimeoutMs

        val remoteCtx = javax.net.ssl.SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) = Unit
                override fun checkServerTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) = Unit
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
            }), java.security.SecureRandom())
        }
        val remoteEngine = remoteCtx.createSSLEngine(sni, port).apply {
            useClientMode = true
            runCatching {
                val p = sslParameters
                p.applicationProtocols = arrayOf("http/1.1")
                sslParameters = p
            }
        }
        val remoteTls = TlsTerminator(remotePlain, remoteEngine, ByteArray(0))
        if (!remoteTls.handshake()) {
            listener?.onLog('E', "连接真实服务器失败 $sni:$port: ${remoteTls.lastError}")
            runCatching { clientTls.close() }
            return
        }
        listener?.onLog('D', "客户端握手完成 $sni proto=${remoteEngine.session.protocol}")

        handleMitmTls(clientTls, remoteTls, sni, owner)
    }

    /** 拼出完整异常链，便于定位。 */
    private fun describeThrowable(t: Throwable): String = buildString {
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 5) {
            if (depth > 0) append(" <- ")
            append(cur.javaClass.simpleName)
            cur.message?.let { append(": ").append(it.take(180)) }
            cur = cur.cause
            depth++
        }
    }

    /**
     * 透明模式专用：ClientHello 已经被读过（用于判断和取 SNI），
     * 直接把原始字节交给握手流程，不再重复读。
     */

    /**
     * 与客户端握手：创建 SSLEngine 并完成握手。
     * @param consumed 已从流里读走的 ClientHello
     */

    /**
     * 连接真实服务器并完成 TLS 握手（客户端侧）。
     */

    /**
     * MITM 双向转发：两个方向都边转发边解析 HTTP。
     */
    /**
     * MITM 双向转发：两个方向都边转发边解析 HTTP。
     *
     * 用 SSLSocket 的加密流直接对拷 —— 不需要关心 TLS 帧边界，
     * 系统实现会把 read() 返回的明文按记录正确解出来。
     */
    private fun handleMitmTls(
        clientTls: TlsTerminator,
        remoteTls: TlsTerminator,
        sni: String,
        owner: String?,
    ) {
        val session = MitmSession(sni, listener, owner)
        listener?.onLog('D', "开始双向转发 $sni")

        val up = Thread({
            pump(clientTls, remoteTls, session, outbound = true)
            listener?.onLog('D', "上行结束 $sni")
        }, "mitm-up").apply { isDaemon = true; start() }

        val down = Thread({
            pump(remoteTls, clientTls, session, outbound = false)
            listener?.onLog('D', "下行结束 $sni")
        }, "mitm-down").apply { isDaemon = true; start() }

        runCatching { up.join() }
        runCatching { down.join(3000) }
        runCatching { clientTls.close() }
        runCatching { remoteTls.close() }
    }

    /**
     * 一个方向的转发：from.read() 解密 → to.write() 加密，同时喂给 HTTP 解析器。
     */
    private fun pump(
        from: TlsTerminator,
        to: TlsTerminator,
        session: MitmSession,
        outbound: Boolean,
    ) {
        val buf = ByteArray(BUFFER_SIZE)
        try {
            while (running.get()) {
                val n = from.read(buf)
                if (n < 0) break
                if (n == 0) continue

                if (outbound) totalBytesOut.addAndGet(n.toLong())
                else totalBytesIn.addAndGet(n.toLong())

                if (!to.write(buf, 0, n)) break

                if (diagnosticDump && diagCount.get() < 12) {
                    diagCount.incrementAndGet()
                    val preview = buildString {
                        val take = minOf(n, 110)
                        for (i in 0 until take) {
                            val c = buf[i].toInt() and 0xFF
                            append(if (c in 32..126) c.toChar() else '.')
                        }
                    }
                    listener?.onLog('D', "原始数据[${if (outbound) "上行" else "下行"}] ${n}B: $preview")
                }

                session.feed(buf, n, outbound)
            }
        } catch (t: Throwable) {
            listener?.onLog('D', "${if (outbound) "上行" else "下行"} 流结束: ${t.javaClass.simpleName}")
        }
    }


    /** 拉取真实服务器证书（用于绕过证书固定）。 */
    private fun fetchRemoteCert(host: String, port: Int): java.security.cert.X509Certificate? =
        runCatching {
            val ctx = SSLContext.getInstance("TLS").apply { init(null, TRUST_ALL, java.security.SecureRandom()) }
            val plain = Socket()
            plain.connect(InetSocketAddress(host, port), config.connectTimeoutMs)
            val ssl = ctx.socketFactory.createSocket(plain, host, port, true) as SSLSocket
            ssl.startHandshake()
            val certs = ssl.session.peerCertificates
            runCatching { ssl.close() }
            certs.firstOrNull() as? java.security.cert.X509Certificate
        }.getOrNull()

    /** 明文 HTTP 直连转发。 */
    private fun handlePlainHttp(
        client: Socket,
        clientIn: InputStream,
        clientOut: BufferedOutputStream,
        head: HttpMessageReader.Head,
        owner: String?,
    ) {
        val url = if (head.uri.startsWith("http")) head.uri
        else "http://${head.header("Host") ?: "unknown"}${head.uri}"
        val host = (head.header("Host") ?: "").substringBefore(':')
        val port = (head.header("Host") ?: "").substringAfter(':', "80").toIntOrNull() ?: 80
        if (host.isBlank()) return

        val remote = Socket()
        remote.connect(InetSocketAddress(host, port), config.connectTimeoutMs)
        val remoteOut = BufferedOutputStream(remote.getOutputStream())

        // 重建请求头转发
        remoteOut.write(head.rawBytes ?: buildRawHead(head))
        // 把已读的 body 也转过去
        if (head.bodyBytes.isNotEmpty()) remoteOut.write(head.bodyBytes)
        remoteOut.flush()

        // 读响应
        val remoteIn = BufferedInputStream(remote.getInputStream(), BUFFER_SIZE)
        val responseHead = HttpMessageReader.readHead(remoteIn)
        if (responseHead != null) {
            val body = HttpMessageReader.readBody(remoteIn, responseHead)
            clientOut.write(responseHead.rawBytes ?: buildRawHead(responseHead))
            clientOut.write(body)
            clientOut.flush()

            totalTransactions.incrementAndGet()
            listener?.onTransaction(
                HttpTransaction(
                    scheme = "http", host = host, port = port,
                    method = head.method, path = head.uri, url = url,
                    requestHead = head, requestBody = head.bodyBytes,
                    responseHead = responseHead, responseBody = body,
                    startTime = head.receivedAt, endTime = System.currentTimeMillis(),
                    fromTls = false,
                )
            )
        }
        runCatching { remote.close() }
    }

    private fun buildRawHead(h: HttpMessageReader.Head): ByteArray {
        val sb = StringBuilder()
        sb.append(h.method).append(' ').append(h.uri).append(' ').append(h.version).append("\r\n")
        h.headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("\r\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    /** 简单隧道转发（不解密时用）。 */
    private fun tunnel(client: Socket, remote: Socket) {
        val t1 = Thread({
            runCatching {
                client.getInputStream().copyTo(remote.getOutputStream())
                remote.shutdownOutput()
            }
        }, "tunnel-up")
        val t2 = Thread({
            runCatching {
                remote.getInputStream().copyTo(client.getOutputStream())
                client.shutdownOutput()
            }
        }, "tunnel-down")
        t1.isDaemon = true; t2.isDaemon = true
        t1.start(); t2.start()
        runCatching { t1.join() }
        runCatching { t2.join(1500) }
        runCatching { remote.close() }
    }

    companion object {
        /** VPN 转发时附带的应用归属元数据头前缀 */
        const val META_PREFIX = "META "

        /** META 头预读字节数 */
        private const val META_PEEK = 512
        private const val TAG = "MitmProxy"
        private const val BUFFER_SIZE = 16 * 1024
        private const val BACKLOG = 50
        const val DEFAULT_PORT = 8888

        private val TRUST_ALL = arrayOf<javax.net.ssl.TrustManager>(
            object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) = Unit
                override fun checkServerTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) = Unit
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
            }
        )
    }
}

/** 端口探测。 */
object PortFinder {
    fun find(preferred: Int, range: Int = 20): Int {
        for (p in preferred until preferred + range) {
            if (isFree(p)) return p
        }
        throw IllegalStateException("端口 $preferred~${preferred + range} 全部被占用")
    }

    private fun isFree(port: Int): Boolean = runCatching {
        ServerSocket().use { it.bind(InetSocketAddress("127.0.0.1", port)) }
        true
    }.getOrDefault(false)
}
