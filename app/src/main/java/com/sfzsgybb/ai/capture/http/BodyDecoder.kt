package com.sfzsgybb.ai.capture.http

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * 响应体解码：
 *  - Content-Encoding: gzip / deflate(br 暂不支持)
 *  - 大体积截断保护
 *
 * 原版对超限的处理是静默丢弃，这里改为标记 truncated 并保留前 N 字节，
 * 让用户至少能看到内容开头。
 */
object BodyDecoder {

    private const val MAX_DECODED = 8 * 1024 * 1024

    data class Result(val bytes: ByteArray, val decoded: Boolean, val truncated: Boolean)

    fun decode(body: ByteArray, contentEncoding: String?, transferChunked: Boolean = false): Result {
        if (body.isEmpty()) return Result(body, false, false)

        val enc = contentEncoding?.lowercase()?.trim().orEmpty()
        return when {
            enc.contains("gzip") -> gunzip(body)
            enc.contains("deflate") -> inflate(body)
            else -> Result(body, false, body.size >= MAX_DECODED)
        }
    }

    private fun gunzip(body: ByteArray): Result = try {
        GZIPInputStream(body.inputStream()).use { gz ->
            readLimited(gz)
        }.let { (bytes, truncated) -> Result(bytes, true, truncated) }
    } catch (t: Throwable) {
        // 有些服务端标了 gzip 但实际是原始 deflate
        try {
            InflaterInputStream(body.inputStream()).use { readLimited(it) }
                .let { (bytes, truncated) -> Result(bytes, true, truncated) }
        } catch (t2: Throwable) {
            Result(body, false, false)
        }
    }

    private fun inflate(body: ByteArray): Result = try {
        // zlib 头（0x78 开头）用 InflaterInputStream，裸 deflate 需要 nowrap
        val nowrap = !(body.size >= 2 && (body[0].toInt() and 0xFF) == 0x78)
        val inf = Inflater(nowrap)
        InflaterInputStream(body.inputStream(), inf).use { readLimited(it) }
            .let { (bytes, truncated) -> Result(bytes, true, truncated) }
    } catch (t: Throwable) {
        Result(body, false, false)
    }

    private fun readLimited(input: java.io.InputStream): Pair<ByteArray, Boolean> {
        val out = ByteArrayOutputStream(minOf(64 * 1024, MAX_DECODED))
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n
            if (total > MAX_DECODED) {
                out.write(buf, 0, n - (total - MAX_DECODED))
                return out.toByteArray() to true
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray() to false
    }
}

/**
 * 内容类型推断与可读性判定。
 * 对应原版 res/drawable 那套 capture_ic_type_* 图标分类。
 */
enum class ContentKind(val label: String) {
    JSON("JSON"),
    XML("XML"),
    HTML("HTML"),
    TEXT("TEXT"),
    FORM("FORM"),
    SCRIPT("SCRIPT"),
    FONT("FONT"),
    IMAGE("IMAGE"),
    AUDIO("AUDIO"),
    VIDEO("VIDEO"),
    ARCHIVE("ARCHIVE"),
    PROTOBUF("PROTOBUF"),
    BINARY("BINARY"),
    EMPTY("EMPTY"),
}

object ContentClassifier {

    fun classify(mimeType: String?, body: ByteArray): ContentKind {
        if (body.isEmpty()) return ContentKind.EMPTY
        val mime = mimeType?.lowercase()?.substringBefore(';')?.trim().orEmpty()

        when {
            mime.contains("json") -> return ContentKind.JSON
            mime.contains("xml") -> return ContentKind.XML
            mime.contains("html") -> return ContentKind.HTML
            mime.contains("javascript") || mime.contains("ecmascript") -> return ContentKind.SCRIPT
            mime.contains("x-www-form-urlencoded") -> return ContentKind.FORM
            mime.startsWith("font/") || mime.contains("font") -> return ContentKind.FONT
            mime.startsWith("image/") -> return ContentKind.IMAGE
            mime.startsWith("audio/") -> return ContentKind.AUDIO
            mime.startsWith("video/") -> return ContentKind.VIDEO
            mime.contains("protobuf") || mime.contains("x-protobuf") -> return ContentKind.PROTOBUF
            mime.contains("zip") || mime.contains("gzip") || mime.contains("tar") ||
                mime.contains("compressed") -> return ContentKind.ARCHIVE
        }

        // MIME 不可靠时按魔数嗅探
        return sniff(body)
    }

    private fun sniff(b: ByteArray): ContentKind {
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) return ContentKind.TEXT
        if (b.size >= 2 && b[0] == 0x1F.toByte() && b[1] == 0x8B.toByte()) return ContentKind.ARCHIVE
        if (b.size >= 4 && b[0] == 0x50.toByte() && b[1] == 0x4B.toByte()) return ContentKind.ARCHIVE
        if (b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte()) return ContentKind.IMAGE
        if (b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) return ContentKind.IMAGE
        if (b.size >= 4 && b[0] == 0x25.toByte() && b[1] == 0x50.toByte()) return ContentKind.IMAGE

        // 可打印比例判定
        val sample = minOf(b.size, 512)
        var printable = 0
        for (i in 0 until sample) {
            val v = b[i].toInt() and 0xFF
            if (v == 9 || v == 10 || v == 13 || (v in 32..126)) printable++
            else if (v >= 0xE0) printable++ // 视为 UTF-8 多字节
        }
        if (printable * 100 / sample < 85) return ContentKind.BINARY

        val head = String(b, 0, minOf(b.size, 256), Charsets.UTF_8).trimStart()
        return when {
            head.startsWith("{") || head.startsWith("[") -> ContentKind.JSON
            head.startsWith("<?xml") -> ContentKind.XML
            head.startsWith("<!DOCTYPE") || head.startsWith("<html", true) -> ContentKind.HTML
            else -> ContentKind.TEXT
        }
    }

    /** 判断该内容是否适合文本视图展示。 */
    fun isTextual(kind: ContentKind): Boolean = when (kind) {
        ContentKind.JSON, ContentKind.XML, ContentKind.HTML, ContentKind.TEXT,
        ContentKind.FORM, ContentKind.SCRIPT -> true
        else -> false
    }
}
