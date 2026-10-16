package com.junge.aidepro.mitm

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * HTTP/1.x 消息读取。
 *
 * 与抓包解析器（capture.http.HttpParser）的区别：
 *  - HttpParser 解析**完整字节数组**（离线分析）
 *  - 这里从**流**里按需读取（在线代理，需要边收边转）
 *
 * 两者互补，不重复：代理收到完整 body 后会转交给上层入库。
 */
object HttpMessageReader {

    private const val MAX_HEAD = 128 * 1024
    private const val MAX_BODY = 8 * 1024 * 1024

    data class Head(
        val isConnect: Boolean,
        val method: String,
        val uri: String,
        val version: String,
        /** 保序的头列表 */
        val headerList: List<Pair<String, String>>,
        val rawBytes: ByteArray?,
        val bodyBytes: ByteArray,
        val receivedAt: Long,
    ) {
        val headers: Map<String, String>
            get() = headerList.associate { it.first to it.second }

        fun header(name: String): String? =
            headerList.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

        val contentLength: Long
            get() = header("Content-Length")?.trim()?.toLongOrNull() ?: -1L

        val isChunked: Boolean
            get() = header("Transfer-Encoding")?.contains("chunked", true) == true

        val statusCode: Int
            get() = if (isConnect) 0 else version.let { _ ->
                uri.toIntOrNull() ?: 0
            }
    }

    /** 读头结果：解析出的 Head + 本次读掉的原始字节。 */
    data class ReadResult(val head: Head?, val consumed: ByteArray)

    /**
     * 从**裸流**读一个请求头，并返回「本次读掉的全部字节」。
     *
     * 【为什么需要 consumed】
     * MITM 场景下，读完 CONNECT 后还要读 TLS ClientHello，
     * 而 TLS 握手时必须把已读字节交回 SSLSocket
     * （`createSocket(socket, consumed, autoClose)`）。
     *
     * 【为什么不用 BufferedInputStream】
     * BufferedInputStream 会预读 16KB，把 CONNECT 之后的 TLS 数据
     * 一起吞进内部缓冲。之后只能吐出「被 read() 消费过」的部分，
     * 未消费的缓冲数据无法还给 SSLSocket，导致
     * `SSLHandshakeException: connection closed`。
     * 因此这里直接读裸流，逐字节读到 \r\n\r\n，精确记录消费量。
     */
    fun readHeadWithConsumed(input: InputStream, preRead: ByteArray = ByteArray(0)): ReadResult {
        val buf = ByteArrayOutputStream(1024)
        var state = 0
        val start = System.currentTimeMillis()

        // 先把调用方预读的字节写回缓冲区（透明模式判断需要）
        for (pb in preRead) buf.write(pb.toInt())

        // 检查预读部分是否已包含完整头部
        var done = false
        if (preRead.isNotEmpty()) {
            val t = String(preRead, Charsets.ISO_8859_1)
            if (t.contains("\r\n\r\n")) done = true
        }

        while (!done) {
            val b = input.read()
            if (b < 0) return ReadResult(null, buf.toByteArray())
            buf.write(b)
            if (buf.size() > MAX_HEAD) return ReadResult(null, buf.toByteArray())

            state = when {
                state == 0 && b == 13 -> 1
                state == 1 && b == 10 -> 2
                state == 2 && b == 13 -> 3
                state == 3 && b == 10 -> 4
                b == 13 -> 1
                else -> 0
            }
            if (state == 4) break
        }

        val consumed = buf.toByteArray()
        return ReadResult(parseHeadBytes(consumed, start), consumed)
    }

    /** 保留旧签名：只取头，不关心消费量。 */
    fun readHead(input: InputStream): Head? = readHeadWithConsumed(input).head

    private fun parseHeadBytes(rawHead: ByteArray, startTime: Long): Head? {
        if (rawHead.size < 4) return null
        val text = String(rawHead, 0, rawHead.size - 4, Charsets.ISO_8859_1)
        val lines = text.split("\r\n")
        if (lines.isEmpty()) return null

        val startLine = lines[0]
        val parts = startLine.split(' ')
        if (parts.isEmpty()) return null

        val isConnect = parts[0].equals("CONNECT", true)
        val method: String
        val uri: String
        val version: String

        if (isConnect) {
            method = "CONNECT"
            uri = parts.getOrElse(1) { "" }
            version = parts.getOrElse(2) { "HTTP/1.1" }.removePrefix("HTTP/")
        } else if (parts[0].startsWith("HTTP/")) {
            // 这是响应
            method = "RESPONSE"
            uri = parts.getOrElse(1) { "0" }
            version = parts[0].removePrefix("HTTP/")
        } else {
            method = parts[0]
            uri = parts.getOrElse(1) { "/" }
            version = parts.getOrElse(2) { "HTTP/1.1" }.removePrefix("HTTP/")
        }

        val headerList = ArrayList<Pair<String, String>>(lines.size)
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) continue
            val c = line.indexOf(':')
            if (c <= 0) continue
            headerList.add(line.substring(0, c).trim() to line.substring(c + 1).trim())
        }

        return Head(
            isConnect = isConnect,
            method = method,
            uri = uri,
            version = version,
            headerList = headerList,
            rawBytes = rawHead,
            bodyBytes = ByteArray(0),
            receivedAt = startTime,
        )
    }

    /**
     * 读完 body。
     * 支持 Content-Length 与 chunked 两种界定方式。
     */
    fun readBody(input: InputStream, head: Head): ByteArray {
        return try {
            when {
                head.isChunked -> readChunked(input)
                head.contentLength > 0 -> {
                    val len = minOf(head.contentLength, MAX_BODY.toLong()).toInt()
                    val out = ByteArrayOutputStream(len)
                    val tmp = ByteArray(8192)
                    var remaining = len
                    while (remaining > 0) {
                        val n = input.read(tmp, 0, minOf(tmp.size, remaining))
                        if (n < 0) break
                        out.write(tmp, 0, n)
                        remaining -= n
                    }
                    out.toByteArray()
                }
                else -> ByteArray(0)
            }
        } catch (t: Throwable) {
            ByteArray(0)
        }
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val line = readLine(input) ?: break
            val size = line.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) break
            if (out.size() + size > MAX_BODY) break
            val chunk = ByteArray(size)
            var read = 0
            while (read < size) {
                val n = input.read(chunk, read, size - read)
                if (n < 0) break
                read += n
            }
            out.write(chunk, 0, read)
            readLine(input) // 块尾 CRLF
        }
        return out.toByteArray()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder(64)
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) return sb.toString()
            sb.append(b.toChar())
        }
    }
}

/**
 * 一次完整的 HTTP 事务。
 */
data class HttpTransaction(
    val scheme: String,
    val host: String,
    val port: Int,
    val method: String,
    val path: String,
    val url: String,
    val requestHead: HttpMessageReader.Head,
    val requestBody: ByteArray,
    val responseHead: HttpMessageReader.Head?,
    val responseBody: ByteArray,
    val startTime: Long,
    val endTime: Long,
    val fromTls: Boolean,
    /** 归属的应用包名（VPN 通过 META 头传来；系统代理模式为空） */
    val ownerPackage: String? = null,
) {
    val statusCode: Int get() = responseHead?.uri?.toIntOrNull() ?: 0
    val durationMs: Long get() = endTime - startTime
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}
