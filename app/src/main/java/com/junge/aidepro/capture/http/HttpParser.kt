package com.junge.aidepro.capture.http

import java.io.ByteArrayOutputStream

/**
 * HTTP/1.x 报文解析器。
 *
 * 设计上与 Android 无关，纯 Kotlin，可单元测试——
 * 这是原版最欠缺的一点：原版解析逻辑全在 native C++ 里，无法单测。
 */

data class ParsedHead(
    val startLine: String,
    val headers: List<Pair<String, String>>,
    val headerBytes: Int,
)

data class ParsedRequest(
    val method: String,
    val uri: String,
    val version: String,
    val headers: List<Pair<String, String>>,
    val body: ByteArray,
    val chunked: Boolean,
    val truncated: Boolean,
) {
    fun header(name: String): String? =
        headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
}

data class ParsedResponse(
    val version: String,
    val statusCode: Int,
    val statusText: String,
    val headers: List<Pair<String, String>>,
    val body: ByteArray,
    val chunked: Boolean,
    val truncated: Boolean,
) {
    fun header(name: String): String? =
        headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    val isGzip: Boolean get() = header("Content-Encoding")?.contains("gzip", true) == true
    val isDeflate: Boolean get() = header("Content-Encoding")?.contains("deflate", true) == true
}

class HttpParseException(message: String) : Exception(message)

object HttpParser {

    private val CRLF = byteArrayOf(13, 10)
    private const val MAX_HEADER_BYTES = 128 * 1024
    private const val MAX_BODY_BYTES = 4 * 1024 * 1024

    /**
     * 解析请求。
     * @param raw 原始字节
     * @param bodyAlreadyKnown 若调用方已知 body 边界（例如 OkHttp 场景），可直接传入
     */
    fun parseRequest(raw: ByteArray): ParsedRequest {
        val headEnd = indexOf(raw, doubleCrlf())
        if (headEnd < 0) throw HttpParseException("未找到请求头结束标记")
        if (headEnd > MAX_HEADER_BYTES) throw HttpParseException("请求头过大")

        val headText = String(raw, 0, headEnd, Charsets.ISO_8859_1)
        val lines = headText.split("\r\n")
        if (lines.isEmpty()) throw HttpParseException("请求行为空")

        val startLine = lines[0]
        val parts = startLine.split(' ')
        if (parts.size < 3) throw HttpParseException("非法请求行: $startLine")

        val headers = parseHeaderLines(lines.drop(1))
        val bodyOffset = headEnd + 4
        val (body, truncated) = extractBody(raw, bodyOffset, headers)

        return ParsedRequest(
            method = parts[0],
            uri = parts[1],
            version = parts[2].removePrefix("HTTP/"),
            headers = headers,
            body = body,
            chunked = isChunked(headers),
            truncated = truncated,
        )
    }

    /** 解析响应。 */
    fun parseResponse(raw: ByteArray, requestMethod: String = "GET"): ParsedResponse {
        val headEnd = indexOf(raw, doubleCrlf())
        if (headEnd < 0) throw HttpParseException("未找到响应头结束标记")

        val headText = String(raw, 0, headEnd, Charsets.ISO_8859_1)
        val lines = headText.split("\r\n")
        if (lines.isEmpty()) throw HttpParseException("状态行为空")

        val statusLine = lines[0]
        val parts = statusLine.split(' ', limit = 3)
        if (parts.size < 2) throw HttpParseException("非法状态行: $statusLine")

        val version = parts[0].removePrefix("HTTP/")
        val code = parts[1].toIntOrNull() ?: throw HttpParseException("非法状态码: ${parts[1]}")
        val text = parts.getOrElse(2) { "" }

        val headers = parseHeaderLines(lines.drop(1))
        val bodyOffset = headEnd + 4
        val (body, truncated) = extractBody(raw, bodyOffset, headers)

        return ParsedResponse(
            version = version,
            statusCode = code,
            statusText = text,
            headers = headers,
            body = body,
            chunked = isChunked(headers),
            truncated = truncated,
        )
    }

    /** 只解析头部，body 另行按需读取（大响应体场景）。 */
    fun parseHeadOnly(raw: ByteArray): ParsedHead {
        val headEnd = indexOf(raw, doubleCrlf())
        if (headEnd < 0) throw HttpParseException("未找到头部结束标记")
        val headText = String(raw, 0, headEnd, Charsets.ISO_8859_1)
        val lines = headText.split("\r\n")
        return ParsedHead(lines.firstOrNull() ?: "", parseHeaderLines(lines.drop(1)), headEnd + 4)
    }

    // ---------- 内部 ----------

    private fun parseHeaderLines(lines: List<String>): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>(lines.size)
        var lastName: String? = null
        for (line in lines) {
            if (line.isEmpty()) continue
            // 折行续接（obs-fold）
            if ((line[0] == ' ' || line[0] == '\t') && lastName != null) {
                val idx = out.indexOfLast { it.first == lastName }
                if (idx >= 0) {
                    out[idx] = out[idx].first to (out[idx].second + " " + line.trim())
                }
                continue
            }
            val c = line.indexOf(':')
            if (c <= 0) continue
            val name = line.substring(0, c).trim()
            val value = line.substring(c + 1).trim()
            out.add(name to value)
            lastName = name
        }
        return out
    }

    private fun extractBody(
        raw: ByteArray,
        offset: Int,
        headers: List<Pair<String, String>>,
    ): Pair<ByteArray, Boolean> {
        if (offset >= raw.size) return ByteArray(0) to false
        val chunked = isChunked(headers)

        if (chunked) {
            val decoded = decodeChunked(raw, offset)
            return if (decoded.size > MAX_BODY_BYTES) decoded.copyOf(MAX_BODY_BYTES) to true
            else decoded to false
        }

        val lenHeader = headers.firstOrNull { it.first.equals("Content-Length", true) }?.second
        val declared = lenHeader?.trim()?.toLongOrNull()

        if (declared != null) {
            val avail = raw.size - offset
            val take = minOf(declared, avail.toLong()).toInt().coerceAtLeast(0)
            val truncated = take > MAX_BODY_BYTES
            val n = if (truncated) MAX_BODY_BYTES else take
            return raw.copyOfRange(offset, offset + n) to (truncated || declared > take)
        }

        // 无 Content-Length：读到结尾（连接关闭界定长度）
        val n = minOf(raw.size - offset, MAX_BODY_BYTES)
        return raw.copyOfRange(offset, offset + n) to (raw.size - offset > MAX_BODY_BYTES)
    }

    /** chunked 传输解码。 */
    fun decodeChunked(raw: ByteArray, offset: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var pos = offset
        while (pos < raw.size) {
            // 读 chunk 长度行
            val lineEnd = indexOf(raw, CRLF, pos)
            if (lineEnd < 0) break
            val line = String(raw, pos, lineEnd - pos, Charsets.ISO_8859_1).trim()
            pos = lineEnd + 2
            // 支持 "1a;ext=1" 形式
            val sizeText = line.substringBefore(';').trim()
            val size = sizeText.toIntOrNull(16) ?: break
            if (size == 0) break // 结束块，trailer 忽略
            if (pos + size > raw.size) {
                out.write(raw, pos, raw.size - pos)
                break
            }
            out.write(raw, pos, size)
            pos += size + 2 // 跳过 chunk 后的 CRLF
            if (out.size() > MAX_BODY_BYTES) break
        }
        return out.toByteArray()
    }

    private fun isChunked(headers: List<Pair<String, String>>): Boolean =
        headers.any { it.first.equals("Transfer-Encoding", true) && it.second.contains("chunked", true) }

    private fun doubleCrlf() = byteArrayOf(13, 10, 13, 10)

    /** 朴素子串查找，够用且无依赖。 */
    fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int = 0): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in from..(haystack.size - needle.size)) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
