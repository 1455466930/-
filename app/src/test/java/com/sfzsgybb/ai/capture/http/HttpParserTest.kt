package com.sfzsgybb.ai.capture.http

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

/**
 * HttpParser / BodyDecoder / ContentClassifier 的纯 JVM 单元测试。
 *
 * 不依赖 Android：被测代码本身是纯 Kotlin + java.util.zip。
 * 所有报文样本都用真实字节数组构造（ISO_8859_1 保证 CRLF 与二进制不被改写）。
 */
class HttpParserTest {

    private companion object {
        /** 源码里是 private const，这里照抄数值用于构造边界样本。 */
        const val MAX_BODY_BYTES = 4 * 1024 * 1024

        fun bytes(s: String): ByteArray = s.toByteArray(Charsets.ISO_8859_1)

        /** 把若干片段拼成一个字节数组（方便混排字符串与二进制）。 */
        fun cat(vararg parts: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            parts.forEach { out.write(it) }
            return out.toByteArray()
        }

        fun deflateZlib(text: String): ByteArray {
            val deflater = Deflater() // 默认 nowrap=false，即带 zlib 头（0x78 ..）
            val input = bytes(text)
            deflater.setInput(input)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                out.write(buf, 0, n)
            }
            deflater.end()
            return out.toByteArray()
        }

        fun gzip(text: String): ByteArray = gzipBytes(bytes(text))

        fun gzipBytes(payload: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(payload) }
            return out.toByteArray()
        }

        /** 造一个大数组但只填少量真实数据，避免测试进程真的吃掉几 MB。 */
        fun padded(size: Int, filler: Byte = 'A'.code.toByte()): ByteArray =
            ByteArray(size) { filler }

        /** 从 0x00 到 0xFF 的连续字节，用作确定的"随机二进制"。 */
        fun allByteValues(): ByteArray = ByteArray(256) { it.toByte() }
    }

    // ---------------------------------------------------------------- 1. GET

    @Test
    fun parseRequest_standardGet_noBody() {
        val raw = bytes(
            "GET /api/v1/items?page=2 HTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "User-Agent: AlgorithmAidePro/3.0.0\r\n" +
                "Accept: application/json\r\n" +
                "\r\n"
        )

        val req = HttpParser.parseRequest(raw)

        assertEquals("GET", req.method)
        assertEquals("/api/v1/items?page=2", req.uri)
        assertEquals("1.1", req.version) // 前缀 "HTTP/" 已被剥掉
        assertEquals(3, req.headers.size)
        assertEquals("example.com", req.header("Host"))
        assertEquals("AlgorithmAidePro/3.0.0", req.header("User-Agent"))
        assertEquals("application/json", req.header("Accept"))
        assertTrue(req.header("host") == "example.com") // header() 大小写不敏感
        assertEquals(0, req.body.size)
        assertFalse(req.chunked)
        assertFalse(req.truncated)
    }

    // --------------------------------------------------------------- 2. POST

    @Test
    fun parseRequest_postWithContentLength_bodyPreserved() {
        val body = """{"user":"junge","n":42}"""
        val raw = cat(
            bytes(
                "POST /login HTTP/1.1\r\n" +
                    "Host: example.com\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: ${body.toByteArray(Charsets.ISO_8859_1).size}\r\n" +
                    "\r\n"
            ),
            bytes(body)
        )

        val req = HttpParser.parseRequest(raw)

        assertEquals("POST", req.method)
        assertEquals("/login", req.uri)
        assertEquals(body, String(req.body, Charsets.ISO_8859_1))
        assertEquals(3, req.headers.size)
        assertEquals("application/json; charset=utf-8", req.header("Content-Type"))
        assertFalse(req.chunked)
        assertFalse(req.truncated)
    }

    @Test
    fun parseRequest_postBody_withBinaryPayloadNotMangled() {
        // 0x00 / 0xFF 往返必须原样保留，不能被当字符串改写
        val binary = byteArrayOf(0x00, 0x01, 0x7F, 0x80.toByte(), 0xFF.toByte(), 0x0D, 0x0A)
        val raw = cat(
            bytes("POST /bin HTTP/1.1\r\nContent-Length: ${binary.size}\r\n\r\n"),
            binary
        )

        val req = HttpParser.parseRequest(raw)

        assertArrayEquals(binary, req.body)
    }

    // ------------------------------------------------------------- 3. CHUNKED

    @Test
    fun parseRequest_chunkedTransferEncoding_decodesChunks() {
        val raw = bytes(
            "POST /upload HTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "4\r\nWiki\r\n" +
                "5\r\npedia\r\n" +
                "e\r\n in\r\n\r\nchunks.\r\n" +
                "0\r\n" +
                "\r\n"
        )

        val req = HttpParser.parseRequest(raw)

        assertTrue(req.chunked)
        assertEquals("Wikipedia in\r\n\r\nchunks.", String(req.body, Charsets.ISO_8859_1))
        assertFalse(req.truncated)
    }

    @Test
    fun decodeChunked_hexSizeWithExtension_isParsed() {
        // "1a;ext=1" 形式：分号后面是 chunk extension，必须忽略
        val raw = bytes("A;ext=1\r\n0123456789\r\n0\r\n\r\n")

        val decoded = HttpParser.decodeChunked(raw, 0)

        assertEquals("0123456789", String(decoded, Charsets.ISO_8859_1))
    }

    // ------------------------------------------------------------ 4. RESPONSE

    @Test
    fun parseResponse_standardResponse_statusLineParsed() {
        val raw = bytes(
            "HTTP/1.1 404 Not Found\r\n" +
                "Server: nginx/1.24.0\r\n" +
                "Content-Length: 9\r\n" +
                "\r\n" +
                "not found"
        )

        val resp = HttpParser.parseResponse(raw)

        assertEquals("1.1", resp.version)
        assertEquals(404, resp.statusCode)
        assertEquals("Not Found", resp.statusText) // split(limit=3)，短语里的空格保留
        assertEquals("nginx/1.24.0", resp.header("Server"))
        assertEquals("not found", String(resp.body, Charsets.ISO_8859_1))
        assertFalse(resp.chunked)
    }

    @Test
    fun parseResponse_statusWithoutReasonPhrase_textIsEmpty() {
        // parts.size == 2 的合法情形（例如 204 无原因短语）
        val raw = bytes("HTTP/1.1 204\r\n\r\n")

        val resp = HttpParser.parseResponse(raw)

        assertEquals(204, resp.statusCode)
        assertEquals("", resp.statusText)
        assertEquals(0, resp.body.size)
    }

    @Test
    fun parseResponse_chunkedBody_decoded() {
        val raw = bytes(
            "HTTP/1.1 200 OK\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "5\r\nhello\r\n" +
                "6\r\n world\r\n" +
                "0\r\n" +
                "\r\n"
        )

        val resp = HttpParser.parseResponse(raw)

        assertTrue(resp.chunked)
        assertEquals("hello world", String(resp.body, Charsets.ISO_8859_1))
    }

    @Test
    fun parseResponse_contentEncodingFlags_detected() {
        val gz = bytes("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\n\r\n")
        val df = bytes("HTTP/1.1 200 OK\r\nContent-Encoding: deflate\r\n\r\n")
        val none = bytes("HTTP/1.1 200 OK\r\nContent-Encoding: identity\r\n\r\n")

        assertTrue(HttpParser.parseResponse(gz).isGzip)
        assertTrue(HttpParser.parseResponse(df).isDeflate)
        assertFalse(HttpParser.parseResponse(none).isGzip)
        assertFalse(HttpParser.parseResponse(none).isDeflate)
    }

    // ------------------------------------------------------ 5. 重复同名头保留

    @Test
    fun parseResponse_duplicateHeaders_bothRetained() {
        val raw = bytes(
            "HTTP/1.1 200 OK\r\n" +
                "Set-Cookie: a=1\r\n" +
                "Set-Cookie: b=2\r\n" +
                "X-Trace: t1\r\n" +
                "set-cookie: c=3\r\n" +
                "\r\n"
        )

        val resp = HttpParser.parseResponse(raw)

        // 不合并、不覆盖：三条 cookie 头全部保留
        val cookies = resp.headers.filter { it.first.equals("Set-Cookie", true) }
        assertEquals(3, cookies.size)
        assertEquals(listOf("a=1", "b=2", "c=3"), cookies.map { it.second })

        // header() 只返回第一个（firstOrNull 语义）
        assertEquals("a=1", resp.header("Set-Cookie"))
        assertEquals(4, resp.headers.size)
    }

    // ------------------------------------------------------------- 6. obs-fold

    @Test
    fun parseResponse_obsFoldContinuationLine_mergedIntoOneValue() {
        // 续行分别用 space 和 tab 开头，两种都要合并
        val raw = bytes(
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/plain;\r\n" +
                " charset=utf-8;\r\n" +
                "\tboundary=xyz\r\n" +
                "X-Normal: ok\r\n" +
                "\r\n"
        )

        val resp = HttpParser.parseResponse(raw)

        // 折行被合并成单个头，值用空格连接并 trim
        assertEquals(2, resp.headers.size)
        assertEquals("Content-Type", resp.headers[0].first)
        assertEquals("text/plain; charset=utf-8; boundary=xyz", resp.headers[0].second)
        assertEquals("X-Normal", resp.headers[1].first)
        assertEquals("ok", resp.headers[1].second)
    }

    @Test
    fun parseHeadOnly_returnsHeaderBytesOffset() {
        val raw = bytes("GET /x HTTP/1.1\r\nHost: h\r\n\r\nBODY")

        val head = HttpParser.parseHeadOnly(raw)

        assertEquals("GET /x HTTP/1.1", head.startLine)
        assertEquals(1, head.headers.size)
        // headerBytes 应指向 body 起点
        assertEquals("BODY", String(raw, head.headerBytes, raw.size - head.headerBytes, Charsets.ISO_8859_1))
    }

    // ------------------------------------------------------------- 7. truncate

    @Test
    fun parseRequest_declaredLongerThanActuallyDelivered_truncatedFlagTrue() {
        // 声明 4MB+，实际只给 10 字节：走 declared > take 分支，且不能真的分配 5MB
        val declared = MAX_BODY_BYTES + 1024L
        val raw = cat(
            bytes("POST /big HTTP/1.1\r\nContent-Length: $declared\r\n\r\n"),
            bytes("0123456789")
        )

        val req = HttpParser.parseRequest(raw)

        assertEquals(10, req.body.size)
        assertEquals("0123456789", String(req.body, Charsets.ISO_8859_1))
        assertTrue("声明长度大于实际到达字节时应标记 truncated", req.truncated)
    }

    @Test
    fun parseRequest_actualBodyExceedsMaxBodyBytes_cappedAndFlagged() {
        // 真实数据超过 4MB：body 被截到 MAX_BODY_BYTES，标记 truncated
        val actual = MAX_BODY_BYTES + 4096
        val raw = cat(
            bytes("POST /big HTTP/1.1\r\nContent-Length: $actual\r\n\r\n"),
            padded(actual)
        )

        val req = HttpParser.parseRequest(raw)

        assertEquals(MAX_BODY_BYTES, req.body.size)
        assertTrue(req.truncated)
        // 截断后保留的是开头，内容仍是填充字节
        assertEquals('A'.code.toByte(), req.body[0])
        assertEquals('A'.code.toByte(), req.body[MAX_BODY_BYTES - 1])
    }

    @Test
    fun parseRequest_exactlyAtLimit_notTruncated() {
        val raw = cat(
            bytes("POST /exact HTTP/1.1\r\nContent-Length: $MAX_BODY_BYTES\r\n\r\n"),
            padded(MAX_BODY_BYTES)
        )

        val req = HttpParser.parseRequest(raw)

        assertEquals(MAX_BODY_BYTES, req.body.size)
        assertFalse(req.truncated) // take == MAX 时不算截断
    }

    @Test
    fun parseRequest_noContentLength_readsToEnd() {
        // 无 Content-Length：按连接关闭界定，读到结尾
        val raw = bytes("POST /x HTTP/1.1\r\nHost: h\r\n\r\ntail-data")

        val req = HttpParser.parseRequest(raw)

        assertEquals("tail-data", String(req.body, Charsets.ISO_8859_1))
        assertFalse(req.truncated)
    }

    // ------------------------------------------------------------ 8/9/10. 异常

    @Test(expected = HttpParseException::class)
    fun parseRequest_missingCrlfCrlf_throws() {
        // 头没结束
        HttpParser.parseRequest(bytes("GET / HTTP/1.1\r\nHost: h\r\n"))
    }

    @Test(expected = HttpParseException::class)
    fun parseResponse_missingCrlfCrlf_throws() {
        HttpParser.parseResponse(bytes("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n"))
    }

    @Test
    fun parseRequest_illegalStartLine_throws() {
        // 只有 2 段，< 3（注意 "GET /a HTTP/1.1" 是 3 段，属合法请求行，不会抛）
        val raw = bytes("GET /only-two\r\nHost: h\r\n\r\n")

        val e = try {
            HttpParser.parseRequest(raw)
            null
        } catch (ex: HttpParseException) {
            ex
        }

        assertNotNull("非法请求行必须抛 HttpParseException", e)
        assertTrue("异常信息应包含原文以便排查", e!!.message!!.contains("GET /only-two"))
    }

    @Test
    fun parseRequest_singleTokenStartLine_throws() {
        // 1 段也比 3 段少
        val raw = bytes("BADLINE\r\nHost: h\r\n\r\n")

        val e = try {
            HttpParser.parseRequest(raw)
            null
        } catch (ex: HttpParseException) {
            ex
        }

        assertNotNull("单段请求行必须抛 HttpParseException", e)
        assertTrue(e!!.message!!.contains("BADLINE"))
    }

    @Test
    fun parseResponse_illegalStatusCode_throws() {
        val raw = bytes("HTTP/1.1 ABC NotANumber\r\n\r\n")

        val e = try {
            HttpParser.parseResponse(raw)
            null
        } catch (ex: HttpParseException) {
            ex
        }

        assertNotNull("非数字状态码必须抛 HttpParseException", e)
        assertTrue(e!!.message!!.contains("ABC"))
    }

    // ---------------------------------------------------------- 11. indexOf

    @Test
    fun indexOf_findsSubstring() {
        val hay = bytes("abcdefgh")
        assertEquals(2, HttpParser.indexOf(hay, bytes("cd")))
        assertEquals(0, HttpParser.indexOf(hay, bytes("abc")))
        // from 参数生效
        assertEquals(6, HttpParser.indexOf(hay, bytes("gh"), 0))
        assertEquals(-1, HttpParser.indexOf(bytes("aaa"), bytes("a"), 3))
    }

    @Test
    fun indexOf_notFound_returnsMinusOne() {
        assertEquals(-1, HttpParser.indexOf(bytes("abcdefgh"), bytes("zz")))
    }

    @Test
    fun indexOf_needleLongerThanHaystack_returnsMinusOne() {
        assertEquals(-1, HttpParser.indexOf(bytes("ab"), bytes("abcdef")))
        assertEquals(-1, HttpParser.indexOf(ByteArray(0), bytes("a")))
    }

    @Test
    fun indexOf_emptyNeedle_returnsMinusOne() {
        // 源码显式短路：空 needle 返回 -1（不是 0）
        assertEquals(-1, HttpParser.indexOf(bytes("abc"), ByteArray(0)))
    }

    @Test
    fun indexOf_findsCrlfCrlfAcrossRealHeader() {
        val raw = bytes("GET / HTTP/1.1\r\nHost: h\r\n\r\nBODY")
        val headEnd = HttpParser.indexOf(raw, byteArrayOf(13, 10, 13, 10))
        assertEquals(raw.size - 4 - 4, headEnd) // "BODY" 之前
    }

    // ------------------------------------------------ 12. decodeChunked 容错

    @Test
    fun decodeChunked_invalidHexSize_exitsSafely() {
        // "zz" 不是合法十六进制 → toIntOrNull(16) 为 null → break，不抛异常
        val raw = bytes("zz\r\nnot-a-real-chunk\r\n0\r\n\r\n")

        val decoded = HttpParser.decodeChunked(raw, 0)

        assertEquals(0, decoded.size)
    }

    @Test
    fun decodeChunked_missingTerminatorCrlf_noException() {
        // 数据里有合法 size 但后面缺 CRLF，且没有 0 结束块
        val raw = bytes("5\r\nhello")

        val decoded = HttpParser.decodeChunked(raw, 0)

        assertEquals("hello", String(decoded, Charsets.ISO_8859_1))
    }

    @Test
    fun decodeChunked_truncatedChunk_writesWhatIsAvailable() {
        // 声明 10 字节实际只有 4 字节 → 走 pos+size > raw.size 分支，写出剩余部分后退出
        val raw = bytes("a\r\nabcd")

        val decoded = HttpParser.decodeChunked(raw, 0)

        assertEquals("abcd", String(decoded, Charsets.ISO_8859_1))
    }

    @Test
    fun decodeChunked_emptyInput_returnsEmpty() {
        assertEquals(0, HttpParser.decodeChunked(ByteArray(0), 0).size)
    }

    @Test
    fun decodeChunked_offsetRespected() {
        val raw = cat(bytes("XXXX"), bytes("4\r\nWiki\r\n0\r\n\r\n"))

        val decoded = HttpParser.decodeChunked(raw, 4)

        assertEquals("Wiki", String(decoded, Charsets.ISO_8859_1))
    }

    @Test
    fun decodeChunked_uppercaseHexSize_accepted() {
        val raw = bytes("A\r\n0123456789\r\n0\r\n\r\n")

        val decoded = HttpParser.decodeChunked(raw, 0)

        assertEquals(10, decoded.size)
    }

    // --------------------------------------------------- 13/14/15. BodyDecoder

    @Test
    fun bodyDecoder_uncompressed_returnsDecodedFalse() {
        val plain = bytes("hello plain text")

        val r = BodyDecoder.decode(plain, null)

        assertFalse("未压缩数据 decoded 必须为 false", r.decoded)
        assertArrayEquals(plain, r.bytes)
        assertFalse(r.truncated)
    }

    @Test
    fun bodyDecoder_emptyBody_returnsAsIs() {
        val r = BodyDecoder.decode(ByteArray(0), "gzip")

        assertFalse(r.decoded)
        assertEquals(0, r.bytes.size)
        assertFalse(r.truncated)
    }

    @Test
    fun bodyDecoder_unknownEncoding_passthrough() {
        val plain = bytes("br-not-supported")

        val r = BodyDecoder.decode(plain, "br")

        assertFalse(r.decoded)
        assertArrayEquals(plain, r.bytes)
    }

    @Test
    fun bodyDecoder_gzip_decompresses() {
        // 正文用 ASCII，避免测试源码文件编码影响字面量；另加一段显式 UTF-8 字节验证多字节还原
        val original = "Hello, gzip payload " + "x".repeat(500)

        val r = BodyDecoder.decode(gzip(original), "gzip")

        assertTrue("gzip 数据 decoded 应为 true", r.decoded)
        assertEquals(original, String(r.bytes, Charsets.UTF_8))
        assertFalse(r.truncated)
    }

    @Test
    fun bodyDecoder_gzip_restoresMultibyteUtf8BytesExactly() {
        // 用转义写死 UTF-8 字节，断言逐字节相等——不受编译器读源码的字符集影响
        val utf8 = byteArrayOf(
            0xE4.toByte(), 0xB8.toByte(), 0x96.toByte(), // 世
            0xE7.toByte(), 0x95.toByte(), 0x8C.toByte()  // 界
        )
        val payload = cat(bytes("CJK:"), utf8, bytes(":end"))

        val r = BodyDecoder.decode(gzipBytes(payload), "gzip")

        assertTrue(r.decoded)
        assertArrayEquals("gzip 解压必须逐字节还原原始字节", payload, r.bytes)
    }

    @Test
    fun bodyDecoder_gzipContentEncodingIsCaseInsensitive() {
        val original = "case-test"
        val r = BodyDecoder.decode(gzip(original), "GZIP")

        assertTrue(r.decoded)
        assertEquals(original, String(r.bytes, Charsets.UTF_8))
    }

    @Test
    fun bodyDecoder_gzipHeaderWithWrongPayload_fallsBackToInflate() {
        // 标 gzip 实际是 zlib deflate：gunzip 抛错后回退 InflaterInputStream
        val original = "mislabeled deflate payload " + "y".repeat(200)
        val zlib = deflateZlib(original)

        val r = BodyDecoder.decode(zlib, "gzip")

        assertTrue(r.decoded)
        assertEquals(original, String(r.bytes, Charsets.UTF_8))
    }

    private fun deflateBytes(payload: ByteArray): ByteArray {
        val deflater = Deflater() // 默认 nowrap=false → 带 zlib 头
        deflater.setInput(payload)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return out.toByteArray()
    }

    @Test
    fun bodyDecoder_deflateWithZlibHeader_decompresses() {
        val original = "deflate payload with zlib header " + "z".repeat(300)
        val zlib = deflateZlib(original)
        // 前置确认确实是 0x78 开头，走 InflaterInputStream(zlib) 分支
        assertEquals(0x78, zlib[0].toInt() and 0xFF)

        val r = BodyDecoder.decode(zlib, "deflate")

        assertTrue(r.decoded)
        assertEquals(original, String(r.bytes, Charsets.UTF_8))
    }

    @Test
    fun bodyDecoder_deflate_restoresMultibyteUtf8BytesExactly() {
        val payload = cat(
            bytes("deflate:"),
            byteArrayOf(0xE4.toByte(), 0xB8.toByte(), 0x96.toByte()),
            bytes(":done")
        )

        val r = BodyDecoder.decode(deflateBytes(payload), "deflate")

        assertTrue(r.decoded)
        assertArrayEquals("deflate 解压必须逐字节还原原始字节", payload, r.bytes)
    }

    @Test
    fun bodyDecoder_garbageWithGzipHeader_returnsOriginalUndecoded() {
        val garbage = bytes("this is definitely not gzip")

        val r = BodyDecoder.decode(garbage, "gzip")

        assertFalse("解压失败应回退为原始数据且 decoded=false", r.decoded)
        assertArrayEquals(garbage, r.bytes)
    }

    // ---------------------------------------------------- 16. ContentClassifier

    @Test
    fun classify_empty_returnsEmpty() {
        assertEquals(ContentKind.EMPTY, ContentClassifier.classify(null, ByteArray(0)))
        assertEquals(ContentKind.EMPTY, ContentClassifier.classify("application/json", ByteArray(0)))
    }

    @Test
    fun classify_jsonBySniff_returnsJson() {
        assertEquals(ContentKind.JSON, ContentClassifier.classify(null, bytes("""{"a":1}""")))
        assertEquals(ContentKind.JSON, ContentClassifier.classify(null, bytes("""[1,2,3]""")))
        // 前导空白也应被 trimStart 吃掉
        assertEquals(ContentKind.JSON, ContentClassifier.classify(null, bytes("  \n {\"a\":1}")))
    }

    @Test
    fun classify_xmlBySniff_returnsXml() {
        assertEquals(
            ContentKind.XML,
            ContentClassifier.classify(null, bytes("<?xml version=\"1.0\"?><a/>"))
        )
    }

    @Test
    fun classify_htmlBySniff_returnsHtml() {
        assertEquals(
            ContentKind.HTML,
            ContentClassifier.classify(null, bytes("<html><body>hi</body></html>"))
        )
        assertEquals(
            ContentKind.HTML,
            ContentClassifier.classify(null, bytes("<!DOCTYPE html><p>x</p>"))
        )
    }

    @Test
    fun classify_shortAsciiText_returnsText() {
        assertEquals(ContentKind.TEXT, ContentClassifier.classify(null, bytes("just a plain sentence")))
        assertEquals(ContentKind.TEXT, ContentClassifier.classify("text/plain", bytes("hello")))
    }

    @Test
    fun classify_randomBinary_returnsBinary() {
        val rnd = byteArrayOf(0x00, 0x01, 0xFF.toByte(), 0xFE.toByte())

        assertEquals(ContentKind.BINARY, ContentClassifier.classify(null, rnd))
        // 全部 256 个字节值：可打印比例远低于 85%
        assertEquals(ContentKind.BINARY, ContentClassifier.classify(null, allByteValues()))
    }

    @Test
    fun classify_pngMagic_returnsImage() {
        val png = cat(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            bytes("\u0000\u0000\u0000\rIHDR")
        )

        assertEquals(ContentKind.IMAGE, ContentClassifier.classify(null, png))
    }

    @Test
    fun classify_gzipMagic_returnsArchive() {
        // 1f 8b —— 真实 gzip 数据也应被判为 ARCHIVE
        val realGzip = gzip("archive me")

        assertEquals(ContentKind.ARCHIVE, ContentClassifier.classify(null, byteArrayOf(0x1F, 0x8B.toByte())))
        assertEquals(ContentKind.ARCHIVE, ContentClassifier.classify(null, realGzip))
        // PK.. (zip)
        assertEquals(
            ContentKind.ARCHIVE,
            ContentClassifier.classify(null, byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00))
        )
    }

    @Test
    fun classify_mimeTypeWinsOverSniff() {
        // body 看着像 JSON，但 MIME 说是图片 → 以 MIME 为准
        assertEquals(
            ContentKind.IMAGE,
            ContentClassifier.classify("image/png", bytes("""{"a":1}"""))
        )
        assertEquals(
            ContentKind.JSON,
            ContentClassifier.classify("application/json; charset=utf-8", bytes("not-json-at-all"))
        )
        assertEquals(
            ContentKind.FORM,
            ContentClassifier.classify("application/x-www-form-urlencoded", bytes("a=1&b=2"))
        )
        assertEquals(
            ContentKind.SCRIPT,
            ContentClassifier.classify("text/javascript", bytes("var a=1;"))
        )
    }

    @Test
    fun isTextual_matchesKind() {
        assertTrue(ContentClassifier.isTextual(ContentKind.JSON))
        assertTrue(ContentClassifier.isTextual(ContentKind.XML))
        assertTrue(ContentClassifier.isTextual(ContentKind.HTML))
        assertTrue(ContentClassifier.isTextual(ContentKind.TEXT))
        assertTrue(ContentClassifier.isTextual(ContentKind.FORM))
        assertTrue(ContentClassifier.isTextual(ContentKind.SCRIPT))
        assertFalse(ContentClassifier.isTextual(ContentKind.IMAGE))
        assertFalse(ContentClassifier.isTextual(ContentKind.ARCHIVE))
        assertFalse(ContentKind.BINARY.let { ContentClassifier.isTextual(it) })
    }
}
