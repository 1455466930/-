package com.sfzsgybb.ai.mitm

import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * MITM 会话：把双向字节流还原成 HTTP 事务。
 *
 * 【难点】
 * 代理看到的是**裸字节流**，一次 read 可能包含：
 *  - 半个请求头
 *  - 一个完整请求
 *  - 多个请求（HTTP pipelining）
 *
 * 因此必须做**流式状态机**：把上行的字节累积成请求，
 * 下行的字节累积成响应，配对后产出一个事务。
 *
 * 【状态机】
 * ```
 * 上行: WAIT_HEAD → 解析头 → WAIT_BODY(按 Content-Length/chunked) → 产出请求 → WAIT_HEAD
 * 下行: 等待请求完成 → WAIT_HEAD → 解析头 → WAIT_BODY → 配对产出事务 → 复位
 * ```
 */
class MitmSession(
    private val host: String,
    private val listener: MitmProxyServer.Listener?,
    /** 归属的应用包名（VPN 通过 META 头传来） */
    private val ownerPackage: String? = null,
) {
    // 上行（客户端 → 服务器）
    private var reqBuffer = ByteArrayOutputStream(4096)
    private var curReqHead: ParsedHead? = null
    private var curReqBody = ByteArrayOutputStream(4096)
    private var reqStartTime = 0L

    // 下行（服务器 → 客户端）
    private var respBuffer = ByteArrayOutputStream(4096)
    private var curRespHead: ParsedHead? = null
    private var curRespBody = ByteArrayOutputStream(4096)

    private var pendingRequest: PendingReq? = null

    private val lock = Any()

    data class ParsedHead(
        val isConnect: Boolean,
        val method: String,
        val uri: String,
        val version: String,
        val headers: List<Pair<String, String>>,
        val raw: ByteArray,
    ) {
        fun header(name: String) = headers.firstOrNull { it.first.equals(name, true) }?.second
        val contentLength: Long get() = header("Content-Length")?.trim()?.toLongOrNull() ?: -1L
        val isChunked: Boolean get() = header("Transfer-Encoding")?.contains("chunked", true) == true
    }

    data class PendingReq(
        val head: ParsedHead,
        val body: ByteArray,
        val startTime: Long,
    )

    fun feed(data: ByteArray, length: Int, outbound: Boolean) {
        synchronized(lock) {
            try {
                if (outbound) feedRequest(data, length) else feedResponse(data, length)
            } catch (t: Throwable) {
                Log.w(TAG, "会话解析异常: ${t.message}")
                reset()
            }
        }
    }

    // ---------- 上行：组装请求 ----------

    private fun feedRequest(data: ByteArray, length: Int) {
        reqBuffer.write(data, 0, length)
        if (reqBuffer.size() > MAX_HEAD + MAX_BODY) {
            reset()
            return
        }
        while (true) {
            if (curReqHead == null) {
                val idx = indexOfDoubleCrlf(reqBuffer.toByteArray()) ?: return
                val all = reqBuffer.toByteArray()
                val head = parseHead(all, 0, idx + 4) ?: run { reset(); return }
                curReqHead = head
                curReqBody = ByteArrayOutputStream(1024)
                reqStartTime = System.currentTimeMillis()

                // CONNECT 没有 body
                if (head.isConnect) {
                    pendingRequest = PendingReq(head, ByteArray(0), reqStartTime)
                    reqBuffer = ByteArrayOutputStream(4096)
                    curReqHead = null
                    return
                }

                // 头之后剩余的部分当作 body 开头
                val restStart = idx + 4
                if (restStart < all.size) {
                    curReqBody.write(all, restStart, all.size - restStart)
                }
                reqBuffer = ByteArrayOutputStream(4096)
            }

            val head = curReqHead ?: return

            if (head.isChunked) {
                // 请求体用 chunked：靠结束块判断完整性。
                // 早期实现直接 return，导致带 chunked 请求体的 POST 永远出不来事务。
                val raw = curReqBody.toByteArray()
                val end = findChunkedEnd(raw)
                if (end < 0) return
                val body = decodeChunked(raw, end)
                pendingRequest = PendingReq(head, body, reqStartTime)
                curReqHead = null
                curReqBody = ByteArrayOutputStream(1024)
                continue
            }

            val need = bodyLength(head, curReqBody.size())
            if (need < 0 || curReqBody.size() < need) return // 等更多数据

            val body = curReqBody.toByteArray().copyOf(need.toInt())
            pendingRequest = PendingReq(head, body, reqStartTime)
            curReqHead = null
            curReqBody = ByteArrayOutputStream(1024)
        }
    }

    // ---------- 下行：组装响应 ----------

    private fun feedResponse(data: ByteArray, length: Int) {
        respBuffer.write(data, 0, length)
        if (respBuffer.size() > MAX_HEAD + MAX_BODY) {
            reset()
            return
        }
        while (true) {
            if (curRespHead == null) {
                val idx = indexOfDoubleCrlf(respBuffer.toByteArray()) ?: return
                val all = respBuffer.toByteArray()
                val head = parseHead(all, 0, idx + 4) ?: run { reset(); return }
                curRespHead = head
                curRespBody = ByteArrayOutputStream(1024)

                val restStart = idx + 4
                if (restStart < all.size) {
                    curRespBody.write(all, restStart, all.size - restStart)
                }
                respBuffer = ByteArrayOutputStream(4096)
            }

            val head = curRespHead ?: return
            // 204/304 和 HEAD 请求没有 body
            val noBody = head.uri.toIntOrNull()?.let { it == 204 || it == 304 } == true ||
                pendingRequest?.head?.method.equals("HEAD", true)

            if (noBody) {
                emitTransaction(head, ByteArray(0))
                curRespHead = null
                curRespBody = ByteArrayOutputStream(1024)
                continue
            }

            if (head.isChunked) {
                // chunked 没有 Content-Length，必须靠「结束块」判断完整性。
                // 早期实现这里返回 -1 直接放弃 → 事务永远不产出，
                // 数据全部堆在缓冲区里 → 表现为「应用转圈、抓不到数据」。
                val raw = curRespBody.toByteArray()
                val end = findChunkedEnd(raw)
                if (end < 0) return // 还没收完，继续等
                val body = decodeChunked(raw, end)
                emitTransaction(head, body)
                curRespHead = null
                curRespBody = ByteArrayOutputStream(1024)
                continue
            }

            val need = bodyLength(head, curRespBody.size())
            if (need < 0 || curRespBody.size() < need) return

            val body = curRespBody.toByteArray().copyOf(need.toInt())
            emitTransaction(head, body)
            curRespHead = null
            curRespBody = ByteArrayOutputStream(1024)
        }
    }

    /**
     * 判断 chunked 数据是否收完。
     * 找结束块 "0\r\n" 之后的 "\r\n"（或 trailer 后的空行）。
     * @return 结束位置（含），-1 表示未收完
     */
    private fun findChunkedEnd(data: ByteArray): Int {
        // 从末尾往前找 "0\r\n\r\n" —— 常见的结束标记
        val endMark = byteArrayOf(48, 13, 10, 13, 10) // "0\r\n\r\n"
        for (i in data.size - endMark.size downTo 0) {
            var ok = true
            for (j in endMark.indices) {
                if (data[i + j] != endMark[j]) { ok = false; break }
            }
            if (ok) return i + endMark.size
        }
        // 有些服务器带 trailer：最后是 "0\r\n<trailer>\r\n\r\n"
        // 用宽松判断：出现过 "0\r\n" 且结尾是 "\r\n\r\n"
        if (data.size >= 5 && data[data.size - 4] == 13.toByte() && data[data.size - 3] == 10.toByte() &&
            data[data.size - 2] == 13.toByte() && data[data.size - 1] == 10.toByte()
        ) {
            val s = String(data, Charsets.ISO_8859_1)
            if (s.contains("\r\n0\r\n") || s.startsWith("0\r\n")) return data.size
        }
        return -1
    }

    /** 解码 chunked 正文。 */
    private fun decodeChunked(data: ByteArray, end: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(end, 64 * 1024))
        var pos = 0
        val text = String(data, 0, end, Charsets.ISO_8859_1)
        while (pos < text.length) {
            val lineEnd = text.indexOf("\r\n", pos)
            if (lineEnd < 0) break
            val sizeStr = text.substring(pos, lineEnd).substringBefore(';').trim()
            val size = sizeStr.toIntOrNull(16) ?: break
            pos = lineEnd + 2
            if (size == 0) break
            if (pos + size > text.length) break
            out.write(text.substring(pos, pos + size).toByteArray(Charsets.ISO_8859_1))
            pos += size + 2
        }
        return out.toByteArray()
    }

    private fun emitTransaction(respHead: ParsedHead, respBody: ByteArray) {
        val req = pendingRequest
        pendingRequest = null
        if (req == null) return

        val path = req.head.uri
        val scheme = "https"
        val url = if (path.startsWith("http")) path else "$scheme://$host$path"

        val tx = HttpTransaction(
            scheme = scheme,
            host = host,
            port = 443,
            method = req.head.method,
            path = path,
            url = url,
            requestHead = toReaderHead(req.head, req.body),
            requestBody = req.body,
            responseHead = toReaderHead(respHead, respBody),
            responseBody = respBody,
            startTime = req.startTime,
            endTime = System.currentTimeMillis(),
            fromTls = true,
            ownerPackage = ownerPackage,
        )
        listener?.onTransaction(tx)
    }

    private fun toReaderHead(h: ParsedHead, body: ByteArray) = HttpMessageReader.Head(
        isConnect = h.isConnect,
        method = h.method,
        uri = h.uri,
        version = h.version,
        headerList = h.headers,
        rawBytes = h.raw,
        bodyBytes = body,
        receivedAt = System.currentTimeMillis(),
    )

    // ---------- 工具 ----------

    /** 计算 body 长度。返回 -1 表示流式（无法预知，需等连接结束）。 */
    private fun bodyLength(head: ParsedHead, current: Int): Long {
        if (head.isChunked) return -1L // chunked 暂按流式处理，整段收完后提交
        val cl = head.contentLength
        return if (cl >= 0) cl else 0L
    }

    private fun parseHead(data: ByteArray, from: Int, to: Int): ParsedHead? {
        if (to <= from) return null
        val text = String(data, from, to - from - 4, Charsets.ISO_8859_1)
        val lines = text.split("\r\n")
        if (lines.isEmpty()) return null

        val parts = lines[0].split(' ')
        val isConnect = parts.getOrNull(0)?.equals("CONNECT", true) == true
        val isResp = parts.getOrNull(0)?.startsWith("HTTP/") == true

        val method = when {
            isConnect -> "CONNECT"
            isResp -> "RESPONSE"
            else -> parts.getOrNull(0) ?: "GET"
        }
        val uri = when {
            isConnect -> parts.getOrElse(1) { "" }
            isResp -> parts.getOrElse(1) { "0" }
            else -> parts.getOrElse(1) { "/" }
        }
        val version = when {
            isResp -> parts.getOrNull(0)?.removePrefix("HTTP/") ?: "1.1"
            else -> parts.getOrElse(2) { "HTTP/1.1" }.removePrefix("HTTP/")
        }

        val headers = ArrayList<Pair<String, String>>(lines.size)
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) continue
            val c = line.indexOf(':')
            if (c <= 0) continue
            headers.add(line.substring(0, c).trim() to line.substring(c + 1).trim())
        }

        return ParsedHead(isConnect, method, uri, version, headers, data.copyOfRange(from, to))
    }

    private fun indexOfDoubleCrlf(data: ByteArray): Int? {
        if (data.size < 4) return null
        for (i in 0..data.size - 4) {
            if (data[i] == 13.toByte() && data[i + 1] == 10.toByte() &&
                data[i + 2] == 13.toByte() && data[i + 3] == 10.toByte()
            ) {
                return i
            }
        }
        return null
    }

    private fun reset() {
        reqBuffer = ByteArrayOutputStream(4096)
        respBuffer = ByteArrayOutputStream(4096)
        curReqBody = ByteArrayOutputStream(1024)
        curRespBody = ByteArrayOutputStream(1024)
        curReqHead = null
        curRespHead = null
        pendingRequest = null
    }

    private companion object {
        const val TAG = "MitmSession"
        const val MAX_HEAD = 128 * 1024
        const val MAX_BODY = 8 * 1024 * 1024
    }
}
