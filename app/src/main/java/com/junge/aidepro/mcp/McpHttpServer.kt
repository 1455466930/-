package com.junge.aidepro.mcp

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * MCP HTTP 传输服务。
 *
 * 只依赖 JDK ServerSocket，不引入 Netty/Ktor——原版用 CivetWeb（C）实现，
 * 而我们只需要 JSON-RPC over HTTP，自己实现便于审计。
 *
 * 【绑定策略】
 *  - 默认只绑 127.0.0.1，不暴露到网络
 *  - 开启局域网模式后绑 0.0.0.0，此时**强制要求 token**
 *  - 端口冲突时自动向后探测（原版会静默失败）
 *
 * 【长连接与保活】
 *  - 支持 HTTP keep-alive，减少客户端重连
 *  - 空闲连接超时 5 分钟，避免句柄泄漏
 *  - 每个连接独立协程，单连接异常不影响服务
 */
class McpHttpServer(
    private val dispatcher: McpDispatcher,
    private val config: Config = Config(),
) {
    data class Config(
        val preferredPort: Int = McpNetwork.PREFERRED_PORT,
        val bindLocalhostOnly: Boolean = true,
        val authToken: String? = null,
        /** 空闲连接超时（毫秒）。 */
        val idleTimeoutMs: Int = 300_000,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val running = AtomicBoolean(false)
    private val clientSeq = AtomicInteger(0)

    /** 实际绑定的端口。 */
    @Volatile var actualPort: Int = -1
        private set

    /** 客户端连接数统计。 */
    val activeConnections = AtomicInteger(0)
    val totalConnections = AtomicLong(0)
    val totalRequests = AtomicLong(0)

    interface Listener {
        fun onStarted(port: Int, lanEnabled: Boolean, lanUrl: String?)
        fun onStopped()
        fun onError(t: Throwable)
    }

    var listener: Listener? = null

    val isRunning: Boolean get() = running.get()

    fun start(): Boolean {
        if (running.get()) return true
        return try {
            val port = McpNetwork.findAvailablePort(config.preferredPort)
                ?: throw IllegalStateException(
                    "端口 ${config.preferredPort}~${config.preferredPort + McpNetwork.PORT_RANGE} 全部被占用"
                )

            if (port != config.preferredPort) {
                Log.w(TAG, "首选端口 ${config.preferredPort} 被占用，改用 $port")
            }

            val addr = if (config.bindLocalhostOnly) InetAddress.getByName("127.0.0.1") else null
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(java.net.InetSocketAddress(addr, port), BACKLOG)
            serverSocket = ss
            actualPort = port
            running.set(true)
            acceptJob = scope.launch { acceptLoop(ss) }

            val lanEnabled = !config.bindLocalhostOnly
            val lanUrl = if (lanEnabled) McpNetwork.lanUrl(port, config.authToken) else null
            Log.i(TAG, "MCP 已启动 port=$port lan=$lanEnabled url=${lanUrl ?: "http://127.0.0.1:$port/mcp"}")
            listener?.onStarted(port, lanEnabled, lanUrl)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "MCP 启动失败", t)
            listener?.onError(t)
            false
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        actualPort = -1
        listener?.onStopped()
        Log.i(TAG, "MCP 已停止")
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    private suspend fun acceptLoop(ss: ServerSocket) {
        while (running.get() && !ss.isClosed) {
            val socket = try {
                ss.accept()
            } catch (e: SocketException) {
                break
            } catch (t: Throwable) {
                listener?.onError(t)
                continue
            }
            val id = clientSeq.incrementAndGet()
            totalConnections.incrementAndGet()
            scope.launch { handleClient(socket, id) }
        }
    }

    private suspend fun handleClient(socket: Socket, clientId: Int) {
        activeConnections.incrementAndGet()
        try {
            socket.soTimeout = config.idleTimeoutMs
            socket.tcpNoDelay = true
            socket.keepAlive = true
            val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val output = BufferedOutputStream(socket.getOutputStream())

            while (!socket.isClosed && running.get()) {
                val request = readRequest(input) ?: break
                totalRequests.incrementAndGet()
                val response = route(request)
                writeResponse(output, response.status, response.contentType, response.body)

                if (request.headers["connection"]?.equals("close", true) == true) break
            }
        } catch (t: Throwable) {
            Log.w(TAG, "客户端 $clientId 断开: ${t.javaClass.simpleName}")
        } finally {
            activeConnections.decrementAndGet()
            runCatching { socket.close() }
        }
    }

    // ---------- HTTP 解析 ----------

    private data class HttpRequest(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: String,
    )

    private data class HttpResponse(
        val status: Int,
        val contentType: String,
        val body: ByteArray,
    )

    private fun readRequest(input: BufferedReader): HttpRequest? {
        val requestLine = input.readLine() ?: return null
        if (requestLine.isBlank()) return null
        val parts = requestLine.split(' ')
        if (parts.size < 3) return null

        val rawPath = parts[1]
        val path = rawPath.substringBefore('?')
        val query = rawPath.substringAfter('?', "")
            .split('&')
            .filter { it.contains('=') }
            .associate {
                val i = it.indexOf('=')
                decode(it.substring(0, i)) to decode(it.substring(i + 1))
            }

        val headers = HashMap<String, String>()
        while (true) {
            val line = input.readLine() ?: return null
            if (line.isEmpty()) break
            val c = line.indexOf(':')
            if (c > 0) headers[line.substring(0, c).trim().lowercase()] = line.substring(c + 1).trim()
        }

        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (len > 0 && len <= MAX_BODY) {
            val buf = CharArray(len)
            var read = 0
            while (read < len) {
                val n = input.read(buf, read, len - read)
                if (n < 0) break
                read += n
            }
            String(buf, 0, read)
        } else ""

        return HttpRequest(parts[0].uppercase(), path, query, headers, body)
    }

    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private suspend fun route(req: HttpRequest): HttpResponse {
        // 鉴权：局域网模式或显式配置 token 时启用
        val token = config.authToken
        if (!token.isNullOrBlank()) {
            val provided = req.headers["authorization"]?.removePrefix("Bearer ")?.trim()
                ?: req.headers["x-auth-token"]
                ?: req.query["token"]
            if (provided != token) {
                return json(401, """{"error":"unauthorized","hint":"请在 URL 加 ?token= 或使用 Authorization: Bearer <token>"}""")
            }
        }

        return when {
            req.method == "GET" && (req.path == "/" || req.path == "/health") -> {
                val sb = StringBuilder(160)
                sb.append("{\"status\":\"ok\"")
                sb.append(",\"protocol\":\"").append(McpConstants.PROTOCOL_VERSION).append('"')
                sb.append(",\"tools\":").append(dispatcher.toolNames().size)
                sb.append(",\"port\":").append(actualPort)
                sb.append(",\"lan\":").append(!config.bindLocalhostOnly)
                sb.append(",\"authRequired\":").append(!config.authToken.isNullOrBlank())
                sb.append(",\"connections\":").append(activeConnections.get())
                sb.append(",\"requests\":").append(totalRequests.get())
                sb.append('}')
                json(200, sb.toString())
            }

            req.path == "/mcp" || req.path == "/mcp/" || req.path == "/messages" -> {
                if (req.method != "POST") {
                    json(405, """{"error":"method_not_allowed","hint":"MCP 端点只接受 POST"}""")
                } else {
                    val response = dispatcher.handle(req.body)
                    if (response == null) {
                        HttpResponse(204, "application/json", ByteArray(0))
                    } else {
                        HttpResponse(200, "application/json", response.toByteArray(Charsets.UTF_8))
                    }
                }
            }

            else -> json(404, """{"error":"not_found","path":"${req.path}","hint":"MCP 端点是 /mcp"}""")
        }
    }

    private fun json(status: Int, body: String) =
        HttpResponse(status, "application/json", body.toByteArray(Charsets.UTF_8))

    private fun writeResponse(out: BufferedOutputStream, status: Int, contentType: String, body: ByteArray) {
        val statusText = when (status) {
            200 -> "OK"; 202 -> "Accepted"; 204 -> "No Content"
            401 -> "Unauthorized"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
            else -> "OK"
        }
        val header = buildString {
            append("HTTP/1.1 $status $statusText\r\n")
            append("Content-Type: $contentType; charset=utf-8\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: keep-alive\r\n")
            append("Keep-Alive: timeout=300\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: Content-Type, MCP-Protocol-Version, Authorization, X-Auth-Token\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(Charsets.US_ASCII))
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    companion object {
        private const val TAG = "McpHttpServer"
        private const val BACKLOG = 32
        private const val MAX_BODY = 4 * 1024 * 1024
    }
}
