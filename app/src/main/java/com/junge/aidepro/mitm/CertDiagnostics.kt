package com.junge.aidepro.mitm

import android.util.Log
import java.security.cert.X509Certificate
import java.security.MessageDigest

/**
 * 证书诊断：导出发送的证书与 CA 的详细信息，用于排查证书链验证失败。
 */
object CertDiagnostics {

    private const val TAG = "CertDiagnostics"

    fun describe(cert: X509Certificate): String = buildString {
        append("Subject: ").append(cert.subjectX500Principal.name).append('\n')
        append("Issuer:  ").append(cert.issuerX500Principal.name).append('\n')
        append("SubjectDer(hex): ").append(hex(cert.subjectX500Principal.encoded)).append('\n')
        append("IssuerDer(hex):  ").append(hex(cert.issuerX500Principal.encoded)).append('\n')
        append("Serial:  ").append(cert.serialNumber.toString(16)).append('\n')
        append("NotBefore: ").append(cert.notBefore).append('\n')
        append("NotAfter:  ").append(cert.notAfter).append('\n')
        append("SigAlg:  ").append(cert.sigAlgName).append('\n')
        append("IsCA:    ").append(cert.basicConstraints).append('\n')
        append("KeyUsage:").append(cert.keyUsage?.joinToString(",") ?: "null").append('\n')
        append("SANs:    ").append(cert.subjectAlternativeNames?.joinToString(",") ?: "null").append('\n')
        append("SHA256:  ").append(sha256(cert.encoded)).append('\n')
    }

    /** 对比叶子证书的 issuer 与 CA 的 subject 是否逐字节一致。 */
    fun verifyChain(leaf: X509Certificate, ca: X509Certificate): String = buildString {
        val leafIssuer = leaf.issuerX500Principal.encoded
        val caSubject = ca.subjectX500Principal.encoded
        val same = leafIssuer.contentEquals(caSubject)

        append("=== 证书链校验 ===\n")
        append("叶子 issuer 长度: ").append(leafIssuer.size).append('\n')
        append("CA  subject 长度: ").append(caSubject.size).append('\n')
        append("逐字节一致: ").append(same).append('\n')

        if (!same) {
            append("\n叶子 issuer DER: ").append(hex(leafIssuer)).append('\n')
            append("CA  subject DER: ").append(hex(caSubject)).append('\n')

            // 找第一个不同的字节
            val minLen = minOf(leafIssuer.size, caSubject.size)
            for (i in 0 until minLen) {
                if (leafIssuer[i] != caSubject[i]) {
                    append("\n第一个差异在第 ").append(i).append(" 字节: ")
                    append("叶子=0x").append("%02x".format(leafIssuer[i]))
                    append(" CA=0x").append("%02x".format(caSubject[i]))
                    append('\n')
                    break
                }
            }
        }

        // 用 Java 内置的验证器试一次
        append("\n尝试用 CA 验证叶子证书: ")
        try {
            leaf.verify(ca.publicKey)
            append("签名验证通过 ✓\n")
        } catch (t: Throwable) {
            append("签名验证失败 ✗ ").append(t.message).append('\n')
        }
    }

    private fun hex(b: ByteArray): String =
        b.joinToString("") { "%02x".format(it) }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b)
            .joinToString(":") { "%02X".format(it) }
}
