package com.sfzsgybb.ai.mitm

import java.io.InputStream

/**
 * TLS ClientHello 解析器。
 *
 * 【作用】
 * MITM 代理在收到 `CONNECT host:443` 后，客户端紧接着会发 TLS ClientHello。
 * 从中提取 **SNI（Server Name Indication）** 才能知道客户端真正想访问哪个域名 ——
 * 这一步决定了要给它签发哪张证书。
 *
 * 【为什么不能直接用 CONNECT 里的 host】
 * 很多时候 CONNECT 的 host 是 CDN 的 IP 或中转域名，
 * 真实目标在 SNI 里。用错域名签证书，客户端会拒绝握手。
 *
 * 【协议结构】
 * ```
 * TLS Record:  [type(1)=0x16][version(2)][length(2)] ← 之后是 Handshake
 * Handshake:   [type(1)=0x01][length(3)]             ← 0x01 = ClientHello
 * ClientHello: [version(2)][random(32)][sessionIdLen(1)+sessionId][cipherSuitesLen(2)+...]
 *              [compressionLen(1)+...][extensionsLen(2)][extensions...]
 * Extension:   [type(2)=0x0000 server_name][len(2)][serverNameListLen(2)]
 *              [nameType(1)=0x00][nameLen(2)][name...]
 * ```
 */
object TlsSniffer {

    data class ClientHello(
        val serverName: String?,
        val version: String,
        val alpnProtocols: List<String>,
        var raw: ByteArray,
    ) {
        val isTls: Boolean get() = version.isNotEmpty()
    }

    /**
     * 从流里读一个 ClientHello。
     * 会完整读出 record，不破坏后续数据。
     *
     * @param preRead 调用方已经读走、需要先当作数据源的字节
     *        （透明模式下调用方会预读几个字节判断是不是 TLS）
     */
    fun readClientHello(input: InputStream, preRead: ByteArray = ByteArray(0)): ClientHello? {
        return try {
            // TLS Record 头（5 字节），先消费预读的部分
            val header = ByteArray(5)
            var fromPre = 0
            if (preRead.isNotEmpty()) {
                fromPre = minOf(preRead.size, 5)
                System.arraycopy(preRead, 0, header, 0, fromPre)
            }
            if (fromPre < 5) {
                if (!readFully(input, header, fromPre, 5 - fromPre)) return null
            }

            val contentType = header[0].toInt() and 0xFF
            if (contentType != 0x16) return null // 不是 Handshake

            val recordLen = ((header[3].toInt() and 0xFF) shl 8) or (header[4].toInt() and 0xFF)
            if (recordLen <= 0 || recordLen > MAX_RECORD) return null

            val record = ByteArray(recordLen)
            var written = 0
            // 预读里可能还有剩余字节
            if (preRead.size > fromPre) {
                val remain = preRead.size - fromPre
                val take = minOf(remain, recordLen)
                System.arraycopy(preRead, fromPre, record, 0, take)
                written = take
            }
            if (written < recordLen) {
                if (!readFully(input, record, written, recordLen - written)) return null
            }

            val all = header + record
            return parse(all)?.also { it.raw = all }
        } catch (t: Throwable) {
            null
        }
    }

    /** 解析已读出的完整 record（含 5 字节头）。 */
    fun parse(data: ByteArray): ClientHello? {
        try {
            var p = 5 // 跳过 record header
            if (p + 4 > data.size) return null

            val hsType = data[p].toInt() and 0xFF
            if (hsType != 0x01) return null // 不是 ClientHello
            p++

            val hsLen = ((data[p].toInt() and 0xFF) shl 16) or
                ((data[p + 1].toInt() and 0xFF) shl 8) or
                (data[p + 2].toInt() and 0xFF)
            p += 3
            if (p + hsLen > data.size) return null

            // version (2)
            val verMajor = data[p].toInt() and 0xFF
            val verMinor = data[p + 1].toInt() and 0xFF
            val version = tlsVersionName(verMajor, verMinor)
            p += 2

            // random (32)
            p += 32

            // session id
            if (p >= data.size) return null
            val sidLen = data[p].toInt() and 0xFF
            p += 1 + sidLen

            // cipher suites
            if (p + 2 > data.size) return null
            val csLen = ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)
            p += 2 + csLen

            // compression methods
            if (p >= data.size) return null
            val compLen = data[p].toInt() and 0xFF
            p += 1 + compLen

            var sni: String? = null
            val alpns = ArrayList<String>()

            // extensions
            if (p + 2 <= data.size) {
                val extTotal = ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)
                p += 2
                val extEnd = minOf(p + extTotal, data.size)

                while (p + 4 <= extEnd) {
                    val extType = ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)
                    val extLen = ((data[p + 2].toInt() and 0xFF) shl 8) or (data[p + 3].toInt() and 0xFF)
                    p += 4
                    if (p + extLen > extEnd) break

                    when (extType) {
                        0x0000 -> sni = parseSni(data, p, extLen)
                        0x0010 -> alpns.addAll(parseAlpn(data, p, extLen))
                    }
                    p += extLen
                }
            }

            return ClientHello(sni, version, alpns, data)
        } catch (t: Throwable) {
            return null
        }
    }

    /** server_name 扩展。 */
    private fun parseSni(d: ByteArray, off: Int, len: Int): String? {
        if (len < 5) return null
        var p = off
        val listLen = ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF)
        p += 2
        if (p + listLen > off + len) return null
        val end = p + listLen
        while (p + 3 <= end) {
            val nameType = d[p].toInt() and 0xFF
            val nameLen = ((d[p + 1].toInt() and 0xFF) shl 8) or (d[p + 2].toInt() and 0xFF)
            p += 3
            if (p + nameLen > end) break
            if (nameType == 0x00) {
                return String(d, p, nameLen, Charsets.US_ASCII)
            }
            p += nameLen
        }
        return null
    }

    /** ALPN 扩展。 */
    private fun parseAlpn(d: ByteArray, off: Int, len: Int): List<String> {
        if (len < 2) return emptyList()
        var p = off
        val listLen = ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF)
        p += 2
        val end = minOf(p + listLen, off + len)
        val out = ArrayList<String>(2)
        while (p < end) {
            val n = d[p].toInt() and 0xFF
            p++
            if (p + n > end) break
            out.add(String(d, p, n, Charsets.US_ASCII))
            p += n
        }
        return out
    }

    /** 判断数据是否是 TLS ClientHello。 */
    fun isClientHello(data: ByteArray): Boolean {
        if (data.size < 6) return false
        return (data[0].toInt() and 0xFF) == 0x16 && (data[5].toInt() and 0xFF) == 0x01
    }

    private fun tlsVersionName(major: Int, minor: Int): String = when {
        major == 3 && minor == 1 -> "TLSv1.0"
        major == 3 && minor == 2 -> "TLSv1.1"
        major == 3 && minor == 3 -> "TLSv1.2"
        major == 3 && minor == 4 -> "TLSv1.3"
        else -> "$major.$minor"
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    /** 读满指定长度，从 offset 开始写。 */
    private fun readFully(input: InputStream, buf: ByteArray, offset: Int, len: Int): Boolean {
        var read = 0
        while (read < len) {
            val n = input.read(buf, offset + read, len - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private const val MAX_RECORD = 64 * 1024
}
