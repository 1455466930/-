package com.junge.aidepro.mitm

import java.io.ByteArrayOutputStream

/**
 * 最小化的 DER 编码器。
 *
 * 【为什么需要它】
 * Android 裁剪版的 `sun.security.x509` 里，
 * `SubjectAlternativeNameExtension` 和 `BasicConstraintsExtension`
 * **连非 public 构造器都被删掉了**（实测：declaredConstructors 为空）。
 *
 * 而 `CertificateExtensions` 保留了 `public (DerInputStream)` 构造器 ——
 * 可以从 DER 字节流构建整个扩展集合。
 *
 * 所以要自己把扩展编码成 DER，再交给它解析。
 *
 * 【DER 结构】
 * ```
 * Extensions ::= SEQUENCE SIZE (1..MAX) OF Extension
 * Extension ::= SEQUENCE {
 *     extnID    OBJECT IDENTIFIER,
 *     critical  BOOLEAN DEFAULT FALSE,
 *     extnValue OCTET STRING   -- 内含该扩展自己的 DER 编码
 * }
 * ```
 */
object DerEncoder {

    private const val TAG = "DerEncoder"

    // ---- 常用 OID ----
    /** 2.5.29.17 SubjectAlternativeName */
    val OID_SAN = intArrayOf(2, 5, 29, 17)
    /** 2.5.29.19 BasicConstraints */
    val OID_BASIC_CONSTRAINTS = intArrayOf(2, 5, 29, 19)
    /** 2.5.29.15 KeyUsage */
    val OID_KEY_USAGE = intArrayOf(2, 5, 29, 15)
    /** 2.5.29.37 ExtendedKeyUsage */
    val OID_EXT_KEY_USAGE = intArrayOf(2, 5, 29, 37)

    /** GeneralizedTime / 通用名称类型 */
    private const val GENERAL_NAME_DNS = 2
    private const val GENERAL_NAME_IP = 7

    // ==================== 基础编码 ====================

    /** 编码长度（DER 短/长形式）。 */
    private fun encodeLength(len: Int): ByteArray = when {
        len < 0x80 -> byteArrayOf(len.toByte())
        len < 0x100 -> byteArrayOf(0x81.toByte(), len.toByte())
        len < 0x10000 -> byteArrayOf(
            0x82.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte(),
        )
        else -> byteArrayOf(
            0x83.toByte(), (len shr 16).toByte(),
            (len shr 8 and 0xFF).toByte(), (len and 0xFF).toByte(),
        )
    }

    /** 包一层 TLV。 */
    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        out.write(encodeLength(content.size))
        out.write(content)
        return out.toByteArray()
    }

    fun sequence(vararg parts: ByteArray): ByteArray = sequence(parts.toList())

    fun sequence(parts: List<ByteArray>): ByteArray {
        val body = ByteArrayOutputStream()
        parts.forEach { body.write(it) }
        return tlv(0x30, body.toByteArray())
    }

    fun set(parts: List<ByteArray>): ByteArray {
        val body = ByteArrayOutputStream()
        parts.forEach { body.write(it) }
        return tlv(0x31, body.toByteArray())
    }

    fun boolean(value: Boolean): ByteArray =
        tlv(0x01, byteArrayOf(if (value) 0xFF.toByte() else 0x00))

    fun octetString(content: ByteArray): ByteArray = tlv(0x04, content)

    /** 编码 OID。 */
    fun oid(arcs: IntArray): ByteArray {
        val body = ByteArrayOutputStream()
        // 首字节 = 第一个弧 * 40 + 第二个弧
        body.write(arcs[0] * 40 + arcs[1])
        for (i in 2 until arcs.size) {
            var v = arcs[i]
            if (v < 0x80) {
                body.write(v)
            } else {
                // 变长编码：每 7 位一组，最高位表示后续还有
                val stack = ArrayDeque<Int>()
                stack.addFirst(v and 0x7F)
                v = v shr 7
                while (v > 0) {
                    stack.addFirst((v and 0x7F) or 0x80)
                    v = v shr 7
                }
                stack.forEach { body.write(it) }
            }
        }
        return tlv(0x06, body.toByteArray())
    }

    /** BMP/ASCII 字符串（用 IA5String，tag 0x16）。 */
    fun ia5String(s: String): ByteArray = tlv(0x16, s.toByteArray(Charsets.US_ASCII))

    // ==================== 扩展构造 ====================

    /**
     * 构造一个 Extension 条目。
     * @param critical 是否标记为 critical
     */
    fun extension(oidArcs: IntArray, critical: Boolean, value: ByteArray): ByteArray {
        val parts = ArrayList<ByteArray>(3)
        parts.add(oid(oidArcs))
        if (critical) parts.add(boolean(true))
        parts.add(octetString(value))
        return sequence(parts)
    }

    /**
     * SubjectAlternativeName。
     * ```
     * SubjectAltName ::= GeneralNames
     * GeneralNames ::= SEQUENCE SIZE (1..MAX) OF GeneralName
     * GeneralName ::= dNSName [2] IA5String
     * ```
     */
    fun subjectAltName(dnsNames: List<String>): ByteArray {
        val names = dnsNames.map { dns ->
            // [2] 隐式标签，内容直接是 IA5String 的字节
            tlv(0x82, dns.toByteArray(Charsets.US_ASCII))
        }
        val generalNames = sequence(names)
        return extension(OID_SAN, false, generalNames)
    }

    /**
     * BasicConstraints。
     * ```
     * BasicConstraints ::= SEQUENCE {
     *     cA BOOLEAN DEFAULT FALSE,
     *     pathLenConstraint INTEGER (0..MAX) OPTIONAL
     * }
     * ```
     */
    fun basicConstraints(isCa: Boolean, pathLen: Int = -1): ByteArray {
        val parts = ArrayList<ByteArray>(2)
        if (isCa) parts.add(boolean(true))
        if (pathLen >= 0) {
            parts.add(tlv(0x02, encodeInt(pathLen)))
        }
        val value = sequence(parts)
        // CA 证书的 BasicConstraints 必须是 critical
        return extension(OID_BASIC_CONSTRAINTS, isCa, value)
    }

    /**
     * KeyUsage（BIT STRING）。
     * 位序：digitalSignature, nonRepudiation, keyEncipherment, dataEncipherment,
     *       keyAgreement, keyCertSign, cRLSign, encipherOnly, decipherOnly
     */
    fun keyUsage(vararg bits: Boolean): ByteArray {
        // BIT STRING 第一个字节是「未使用位数」
        var lastUsed = 0
        for (i in bits.indices) if (bits[i]) lastUsed = i
        val byteCount = (lastUsed / 8) + 1
        val data = ByteArray(byteCount)
        for (i in bits.indices) {
            if (bits[i]) {
                data[i / 8] = (data[i / 8].toInt() or (0x80 shr (i % 8))).toByte()
            }
        }
        val unused = (byteCount * 8 - lastUsed - 1).toByte()
        val content = ByteArray(1 + byteCount)
        content[0] = unused
        System.arraycopy(data, 0, content, 1, byteCount)
        val value = tlv(0x03, content)
        return extension(OID_KEY_USAGE, true, value)
    }

    /** 把多个扩展组装成 Extensions 序列。 */
    fun extensions(vararg exts: ByteArray): ByteArray = sequence(exts.toList())

    /** 正整数编码（最小字节数）。 */
    private fun encodeInt(v: Int): ByteArray {
        if (v == 0) return byteArrayOf(0)
        var n = v
        val bytes = ArrayList<Byte>(4)
        while (n > 0) {
            bytes.add(0, (n and 0xFF).toByte())
            n = n shr 8
        }
        // 最高位为 1 时要补 0x00，避免被当成负数
        if (bytes[0].toInt() and 0x80 != 0) bytes.add(0, 0)
        return bytes.toByteArray()
    }
}
