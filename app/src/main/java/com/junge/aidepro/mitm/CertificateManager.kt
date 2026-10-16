package com.junge.aidepro.mitm

import android.util.Log
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.util.concurrent.ConcurrentHashMap

/**
 * MITM 证书管理。
 *
 * 【原理】
 * 中间人抓包的核心是：代理冒充目标服务器与客户端握手。
 * 为此需要为每个访问的域名**动态签发**一张证书，
 * 用我们的 CA 私钥签名，这样客户端才会（在信任了我们的 CA 后）接受。
 *
 * 【与 ProxyPin 的对应关系】
 * 参考 lib/network/util/crts.dart 的 CertificateManager；
 * CA 材料直接沿用 ProxyPin 的（Apache 2.0 许可），
 * 这样用户只需安装一次 ProxyPin CA 即可，不用重新生成。
 *
 * 【关键实现点】
 *  1. 叶子证书的 issuer 必须与 CA 证书的 Subject DER **逐字节一致**，
 *     否则部分客户端（尤其 iOS、以及做了严格校验的 App）会拒绝
 *  2. 服务端密钥对全局复用一个 —— 每次握手重新生成 RSA 密钥代价太大
 *  3. 证书按域名缓存，避免重复签发
 *  4. TLS 校验失败时，可用真实服务器证书重新签发（绕过证书固定）
 *
 * 【为什么用 BouncyCastle】
 * 需要构造并签发自定义 X.509 证书。JDK 自带的 API 只能生成自签证书，
 * 无法用指定 CA 私钥签名。Android 内置了 BouncyCastle（bcprov），
 * 这里通过反射或直接依赖使用，不额外增加 APK 体积。
 */
object CertificateManager {

    private const val TAG = "MitmCertManager"

    private const val CA_CERT_ASSET = "mitm/ca.crt"
    private const val CA_KEY_ASSET = "mitm/ca_key.der"

    /** 叶子证书有效期（天）。CA 有效期到 2033，叶子取短一些。 */
    private const val LEAF_VALIDITY_DAYS = 825

    @Volatile private var caCert: X509Certificate? = null
    @Volatile private var caPrivateKey: PrivateKey? = null
    @Volatile private var caSubjectDer: ByteArray? = null
    @Volatile private var initialized = false
    @Volatile private var initError: String? = null

    /** 全局复用的服务端密钥对。 */
    private val serverKeyPair: KeyPair by lazy { generateRsaKeyPair() }

    /** 域名 → SSLContext 缓存。 */
    private val contextCache = ConcurrentHashMap<String, SSLContext>()

    /** 域名 → 真实服务器证书（用于绕过证书固定）。 */
    private val remoteCertCache = ConcurrentHashMap<String, X509Certificate>()

    /**
     * 初始化 CA。
     * @param assets 用于读取内置 CA 材料
     */
    @Synchronized
    fun init(assetLoader: (String) -> ByteArray?): Boolean {
        if (initialized) return true
        return try {
            val certBytes = assetLoader(CA_CERT_ASSET)
                ?: throw IllegalStateException("找不到 $CA_CERT_ASSET")
            val keyBytes = assetLoader(CA_KEY_ASSET)
                ?: throw IllegalStateException("找不到 $CA_KEY_ASSET")

            val cf = CertificateFactory.getInstance("X.509")
            caCert = cf.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate

            val kf = KeyFactory.getInstance("RSA")
            caPrivateKey = kf.generatePrivate(PKCS8EncodedKeySpec(keyBytes))

            // 取 CA 的 Subject DER，作为叶子证书的 issuer（必须逐字节一致）
            caSubjectDer = caCert!!.subjectX500Principal.encoded

            initialized = true
            Log.i(TAG, "CA 初始化成功: ${caCert!!.subjectX500Principal.name}")
            true
        } catch (t: Throwable) {
            initError = "${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "CA 初始化失败", t)
            false
        }
    }

    val isReady: Boolean get() = initialized
    val isInitialized: Boolean get() = initialized
    val errorMessage: String? get() = initError
    val caCertificate: X509Certificate? get() = caCert

    /** CA 证书的 PEM 编码（用于导出给用户手动安装）。 */
    fun caCertificatePem(): ByteArray? = runCatching {
        caCert?.encoded?.let { der ->
            val b64 = android.util.Base64.encodeToString(der, android.util.Base64.NO_WRAP)
            // 按 64 字符折行，符合 PEM 规范
            val sb = StringBuilder()
            sb.append("-----BEGIN CERTIFICATE-----\n")
            b64.chunked(64).forEach { sb.append(it).append('\n') }
            sb.append("-----END CERTIFICATE-----\n")
            sb.toString().toByteArray(Charsets.US_ASCII)
        }
    }.getOrNull()

    /** CA 证书的 DER 编码（系统信任区要求 DER 格式）。 */
    fun caCertificateDer(): ByteArray? = runCatching { caCert?.encoded }.getOrNull()

    /**
     * 诊断：签发一张测试证书并对比与 CA 的链关系。
     * 用于排查 CERTIFICATE_UNKNOWN 类问题。
     */
    fun diagnostics(host: String = "test.example.com"): String {
        if (!initialized) return "CA 未初始化: $initError"
        return try {
            val ca = caCert!!
            val leaf = signLeafCertificate(host, null)
            buildString {
                append("=== CA 证书 ===\n")
                append(CertDiagnostics.describe(ca))
                append("\n=== 签发的叶子证书 ===\n")
                append(CertDiagnostics.describe(leaf))
                append("\n")
                append(CertDiagnostics.verifyChain(leaf, ca))
            }
        } catch (t: Throwable) {
            "诊断失败: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    /**
     * 取指定域名的 SSLContext（用于与客户端握手）。
     *
     * @param host 域名（从 ClientHello 的 SNI 提取）
     */
    fun getContext(host: String): SSLContext? {
        if (!initialized) return null
        contextCache[host]?.let { return it }
        return synchronized(this) {
            contextCache[host] ?: createContext(host, remoteCertCache[host]).also {
                contextCache[host] = it
            }
        }
    }

    /**
     * 用真实服务器证书重新签发（绕过证书固定）。
     *
     * 场景：某些 App 校验服务器证书的指纹，我们自签的证书过不了。
     * 此时把真实服务器证书的 Subject / SAN / 有效期复制过来，
     * 用我们的 CA 重新签一张，客户端看到的证书信息与真实的一致。
     *
     * @return 是否成功刷新
     */
    fun regenerateByRemoteCert(host: String, remote: X509Certificate?): Boolean {
        if (remote == null) return false
        if (remoteCertCache.containsKey(host)) return false
        return try {
            remoteCertCache[host] = remote
            synchronized(this) {
                contextCache[host] = createContext(host, remote)
            }
            Log.i(TAG, "已用真实证书重签 $host")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "重签失败 $host: ${t.message}")
            false
        }
    }

    private fun createContext(host: String, remote: X509Certificate?): SSLContext {
        val cert = try {
            signLeafCertificate(host, remote)
        } catch (t: Throwable) {
            Log.e(TAG, "签发证书失败 $host", t)
            throw t
        }
        Log.i(TAG, "已签发证书 $host subject=${cert.subjectX500Principal.name}")

        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("leaf", serverKeyPair.private, CHAR_PWD, arrayOf(cert))
        // 把 CA 也放进去，形成完整链
        caCert?.let { ks.setCertificateEntry("ca", it) }

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, CHAR_PWD)

        return SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, arrayOf<TrustManager>(TrustAllManager), SecureRandom())
        }
    }

    /**
     * 签发域名证书。
     *
     * @param remote 真实服务器证书（可选）。提供时复制其 Subject/SAN/有效期，
     *               以通过客户端的严格校验。
     */
    private fun signLeafCertificate(host: String, remote: X509Certificate?): X509Certificate {
        val ca = caCert ?: throw IllegalStateException("CA 未初始化")
        val caKey = caPrivateKey ?: throw IllegalStateException("CA 私钥未加载")

        // 有真实证书时，复制它的主体信息，让客户端看到的就是"真"证书
        val subject = remote?.subjectX500Principal ?: javax.security.auth.x500.X500Principal(
            "CN=$host, OU=ProxyPin, O=ProxyPin, L=BeiJing, ST=BJ, C=CN"
        )
        val notBefore = remote?.notBefore ?: Date(System.currentTimeMillis() - 24L * 3600 * 1000)
        val notAfter = remote?.notAfter ?: Date(System.currentTimeMillis() + LEAF_VALIDITY_DAYS * 24L * 3600 * 1000)

        return X509Signer.sign(
            // 用原始 DER 字节，保证与 CA 证书的 subject 逐字节一致
            issuerDer = ca.subjectX500Principal.encoded,
            issuerKey = caKey,
            subjectDer = subject.encoded,
            subjectPublicKey = serverKeyPair.public,
            serial = BigInteger(64, SecureRandom()).abs(),
            notBefore = notBefore,
            notAfter = notAfter,
            dnsNames = listOf(host),
            isCa = false,
        )
    }

    private fun generateRsaKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        return kpg.generateKeyPair()
    }

    fun clearCache() {
        contextCache.clear()
        remoteCertCache.clear()
    }

    fun stats(): String =
        "cached=${contextCache.size} remote=${remoteCertCache.size} ready=$initialized"

    private val CHAR_PWD = "aidepro".toCharArray()

    /** 信任一切（我们扮演的是客户端，连真实服务器时不校验）。 */
    private val TrustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
