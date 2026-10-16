package com.junge.aidepro.mitm

import android.util.Log
import java.lang.reflect.Constructor
import java.math.BigInteger
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * X.509 证书签发器（反射调用 sun.security.x509）。
 *
 * 【为什么用反射】
 * `sun.security.x509.*` 是 **JDK 内部 API**：
 *  - **运行期存在**：Android 完整保留了这套类（已实测全部可加载）
 *  - **编译期不可见**：不在 Android SDK 的 android.jar 里
 * 因此只能用 `Class.forName` + 反射调用。
 *
 * 【为什么不用 BouncyCastle】
 * 实测 Android 系统的 bouncycastle.jar 是严重裁剪版：
 *  - `X509V3CertificateGenerator` 有 setXxx，但**没有 addExtension**
 *  - ASN.1 类（BasicConstraints/KeyUsage/GeneralName）**构造器全是非 public**
 * BC 这条路在 Android 上走不通。
 *
 * 【签发流程】
 * ```
 * X509CertInfo info = new X509CertInfo();
 * info.set(VERSION,          new CertificateVersion(2));   // 2 = v3
 * info.set(SERIAL_NUMBER,    new CertificateSerialNumber(serial));
 * info.set(SUBJECT,          new CertificateSubjectName(new X500Name(dn)));
 * info.set(ISSUER,           new CertificateSubjectName(new X500Name(dn)));
 * info.set(VALIDITY,         new CertificateValidity(from, to));
 * info.set(KEY,              new CertificateX509Key(pubKey));
 * info.set(ALGORITHM_ID,     new CertificateAlgorithmId(AlgorithmId));
 * info.set(EXTENSIONS,       extensions);                  // 含 SAN
 * X509CertImpl cert = new X509CertImpl(info);
 * cert.sign(privKey, "SHA256withRSA");
 * ```
 */
object X509Signer {

    private const val TAG = "X509Signer"
    private const val PKG = "sun.security.x509"

    @Volatile private var available: Boolean? = null
    @Volatile private var lastError: String? = null

    val isAvailable: Boolean
        get() = available ?: detect().also { available = it }

    val errorMessage: String? get() = lastError

    private fun detect(): Boolean = try {
        Class.forName("$PKG.X509CertInfo")
        Class.forName("$PKG.CertificateExtensions")
        Class.forName("$PKG.X509CertImpl")
        Log.i(TAG, "sun.security.x509 可用")
        true
    } catch (t: Throwable) {
        lastError = "sun.security.x509 不可用: ${t.message}"
        Log.w(TAG, lastError!!)
        false
    }

    fun sign(
        issuer: X500Principal,
        issuerKey: PrivateKey,
        subject: X500Principal,
        subjectPublicKey: PublicKey,
        serial: BigInteger,
        notBefore: Date,
        notAfter: Date,
        dnsNames: List<String>,
        isCa: Boolean = false,
    ): X509Certificate = sign(
        issuerDer = issuer.encoded,
        issuerKey = issuerKey,
        subjectDer = subject.encoded,
        subjectPublicKey = subjectPublicKey,
        serial = serial,
        notBefore = notBefore,
        notAfter = notAfter,
        dnsNames = dnsNames,
        isCa = isCa,
    )

    /**
     * 用**原始 DER 字节**签发证书。
     *
     * 【为什么必须用原始字节而不是 X500Principal】
     * 证书链验证时，客户端逐字节比较
     * 「叶子证书的 issuer」与「CA 证书的 subject」。
     *
     * 如果用 `X500Principal("CN=xxx,OU=yyy")` 重建，重新编码后：
     *  - RDN 顺序可能变化
     *  - 字符串类型可能从 PrintableString 变成 UTF8String
     *  - 空格/转义处理差异
     * 任一差异都会让客户端认为「签发者不是我信任的 CA」，
     * 返回 TLS 告警 `CERTIFICATE_UNKNOWN`。
     *
     * ProxyPin 也是这么做的（见其 crts.dart 的 _caSubjectDnBytes，
     * 注释："保证与 CA 二进制精确匹配"）。
     */
    fun sign(
        issuerDer: ByteArray,
        issuerKey: PrivateKey,
        subjectDer: ByteArray,
        subjectPublicKey: PublicKey,
        serial: BigInteger,
        notBefore: Date,
        notAfter: Date,
        dnsNames: List<String>,
        isCa: Boolean = false,
    ): X509Certificate {
        require(isAvailable) { lastError ?: "sun.security.x509 不可用" }

        val infoCls = Class.forName("$PKG.X509CertInfo")
        val info = infoCls.getDeclaredConstructor().newInstance()
        val setMethod = infoCls.getMethod("set", String::class.java, Any::class.java)

        // --- 版本 v3 ---
        // 注意：Android 的 X509CertInfo 没有 VERSION 等 public 常量字段（被裁剪），
        // 因此直接用 JDK 源码里的字面量（这些值在 JDK 中是固定的）。
        val verCls = Class.forName("$PKG.CertificateVersion")
        val verObj = verCls.getConstructor(Int::class.javaPrimitiveType).newInstance(2) // 2 = v3
        setMethod.invoke(info, ATTR_VERSION, verObj)

        // --- 序列号 ---
        val serialCls = Class.forName("$PKG.CertificateSerialNumber")
        setMethod.invoke(
            info, ATTR_SERIAL,
            serialCls.getConstructor(BigInteger::class.java).newInstance(serial),
        )

        // --- 主体 / 签发者 ---
        // 用**原始 DER 字节**构造 X500Name，保证与 CA 证书逐字节一致。
        // 用字符串重建会因编码差异导致客户端报 CERTIFICATE_UNKNOWN。
        val x500NameCls = Class.forName("$PKG.X500Name")
        val subjName = x500NameCls.getConstructor(ByteArray::class.java).newInstance(subjectDer)
        val issName = x500NameCls.getConstructor(ByteArray::class.java).newInstance(issuerDer)
        setMethod.invoke(info, ATTR_SUBJECT, subjName)
        setMethod.invoke(info, ATTR_ISSUER, issName)

        // --- 有效期 ---
        val validityCls = Class.forName("$PKG.CertificateValidity")
        setMethod.invoke(
            info, ATTR_VALIDITY,
            validityCls.getConstructor(Date::class.java, Date::class.java).newInstance(notBefore, notAfter),
        )

        // --- 公钥 ---
        val keyCls = Class.forName("$PKG.CertificateX509Key")
        setMethod.invoke(
            info, ATTR_KEY,
            keyCls.getConstructor(PublicKey::class.java).newInstance(subjectPublicKey),
        )

        // --- 签名算法 ---
        // 注意：AlgorithmId 没有 SHA256withRSA_oid 常量（Android 裁剪掉了），
        // 用静态方法 getAlgorithmId("SHA256withRSA") 拿。
        val algIdCls = Class.forName("$PKG.AlgorithmId")
        val algObj = algIdCls.getMethod("getAlgorithmId", String::class.java)
            .invoke(null, "SHA256withRSA")
        val certAlgCls = Class.forName("$PKG.CertificateAlgorithmId")
        setMethod.invoke(
            info, ATTR_ALGID,
            certAlgCls.getConstructor(algIdCls).newInstance(algObj),
        )

        // --- 扩展 ---
        //
        // 【为什么自己编码 DER】
        // Android 裁剪版的 sun.security.x509 里，
        // SubjectAlternativeNameExtension 和 BasicConstraintsExtension
        // **连非 public 构造器都被删了**（实测 declaredConstructors 为空）。
        //
        // 而现代 TLS 客户端强制要求服务端证书**必须有 SAN**，
        // 缺失会导致认证失败（表现为 TLS 告警 CERTIFICATE_UNKNOWN）。
        //
        // CertificateExtensions 保留了 `public (DerInputStream)` 构造器，
        // 所以这里自己把扩展编码成 DER，再交给它解析。
        val extBytes = ArrayList<ByteArray>(3)

        // BasicConstraints（CA 证书标 critical）
        extBytes.add(
            DerEncoder.basicConstraints(isCa, if (isCa) 5 else -1)
        )

        // KeyUsage
        if (isCa) {
            extBytes.add(
                DerEncoder.keyUsage(
                    false, false, false, false, false, true, true, false, false,
                )
            )
        } else {
            extBytes.add(
                DerEncoder.keyUsage(
                    true, false, true, false, false, false, false, false, false,
                )
            )
        }

        // SubjectAlternativeName —— 现代客户端必需
        if (dnsNames.isNotEmpty()) {
            extBytes.add(DerEncoder.subjectAltName(dnsNames))
        }

        val extensionsDer = DerEncoder.extensions(*extBytes.toTypedArray())
        Log.d(TAG, "扩展 DER 长度=${extensionsDer.size} 含 SAN=${dnsNames.isNotEmpty()}")

        val extsCls = Class.forName("$PKG.CertificateExtensions")
        val derInputCls = Class.forName("sun.security.util.DerInputStream")
        val derInput = derInputCls.getConstructor(ByteArray::class.java).newInstance(extensionsDer)
        val exts = extsCls.getConstructor(derInputCls).newInstance(derInput)

        setMethod.invoke(info, ATTR_EXTENSIONS, exts)

        // --- 组装并签名 ---
        val implCls = Class.forName("$PKG.X509CertImpl")
        val cert = implCls.getConstructor(infoCls).newInstance(info)
        val signMethod = implCls.getMethod("sign", PrivateKey::class.java, String::class.java)
        signMethod.invoke(cert, issuerKey, "SHA256withRSA")

        return cert as X509Certificate
    }

    // X509CertInfo 的属性名。
    //
    // 这些值是通过在设备上逐个试探 set() 得出的（Android 裁剪掉了常量字段，
    // 官方文档也没有）。探测结果：裸名有效，带 x509.info. 前缀无效。
    // 探测同时确认了「必须先设 version，否则 extensions 会报 Invalid version」。
    private const val ATTR_VERSION = "version"
    private const val ATTR_SERIAL = "serialNumber"
    private const val ATTR_SUBJECT = "subject"
    private const val ATTR_ISSUER = "issuer"
    private const val ATTR_VALIDITY = "validity"
    private const val ATTR_KEY = "key"
    private const val ATTR_ALGID = "algorithmID"
    private const val ATTR_EXTENSIONS = "extensions"

    // CertificateExtensions 的扩展名。
    // 同样用裸名，与 X509CertInfo 属性名风格一致。
    private const val EXT_BASIC_CONSTRAINTS = "basicConstraints"
    private const val EXT_KEY_USAGE = "keyUsage"
    private const val EXT_SAN = "subjectAlternativeName"

    fun subjectDerBytes(cert: X509Certificate): ByteArray = cert.subjectX500Principal.encoded

    fun fromPem(pem: String): X509Certificate? = runCatching {
        val b64 = pem.lineSequence()
            .filterNot { it.startsWith("-----") }
            .joinToString("")
            .replace("\r", "")
            .replace("\n", "")
        val der = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        val cf = java.security.cert.CertificateFactory.getInstance("X.509")
        cf.generateCertificate(der.inputStream()) as X509Certificate
    }.onFailure { Log.w(TAG, "PEM 解析失败: ${it.message}") }.getOrNull()

    fun isIssuedBy(cert: X509Certificate, ca: X509Certificate): Boolean =
        cert.issuerX500Principal == ca.subjectX500Principal
}
