package com.sfzsgybb.ai.mitm

import android.util.Log
import java.lang.reflect.Modifier
import java.math.BigInteger
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * BouncyCastle 反射 API 探测器。
 *
 * 【为什么需要】
 * Android 系统的 bouncycastle.jar 是裁剪重打包版，
 * 类名/构造器签名与官方 Maven 包不一致，且文档缺失。
 * 拿不到准确签名就只能靠猜，而猜错会在运行期抛 NoSuchMethodException。
 *
 * 这个工具把关键类的真实签名全部打印出来，便于据此写调用代码。
 * 只在诊断时使用，正常流程不依赖它。
 */
object BcProbe {

    private const val TAG = "BcProbe"
    private const val P = "com.android.org.bouncycastle"

    /**
     * 探测 X509CertInfo.set() 接受的合法属性名。
     *
     * 系统裁剪掉了常量字段，文档也查不到，
     * 唯一可靠办法是逐个候选值试，看哪个不抛 "Attribute name not recognized"。
     */
    fun probeX509Attrs(): String {
        val sb = StringBuilder("=== X509CertInfo 属性名探测 ===\n")
        val infoCls = runCatching { Class.forName("sun.security.x509.X509CertInfo") }.getOrNull()
            ?: return "X509CertInfo 不可用"

        val setMethod = infoCls.getMethod("set", String::class.java, Any::class.java)

        val candidates = listOf(
            // JDK 源码风格
            "version", "serialNumber", "serial_number", "subject", "issuer",
            "validity", "key", "algorithmID", "algorithmId", "extensions",
            // 带前缀
            "x509.info.version", "x509.info.serialNumber", "x509.info.subject",
            "x509.info.issuer", "x509.info.validity", "x509.info.key",
            "x509.info.algorithmID", "x509.info.extensions",
            // 其他可能
            "info.version", "info.serialNumber", "info.subject", "info.issuer",
            "info.validity", "info.key", "info.algorithmID", "info.extensions",
        )

        // 用一个能塞进去的占位值：CertificateVersion(2) 对 version 有效，
        // 其他属性用 null 会 NPE，所以统一用 CertificateVersion 试探合法性即可
        // （只要能通过属性名检查，就会在类型检查/后续步骤报别的错，说明名字是对的）
        val verCls = runCatching { Class.forName("sun.security.x509.CertificateVersion") }.getOrNull()
        val probeValue = verCls?.getConstructor(Int::class.javaPrimitiveType)?.newInstance(2)

        for (c in candidates) {
            try {
                val info = infoCls.getDeclaredConstructor().newInstance()
                setMethod.invoke(info, c, probeValue)
                sb.append("  ✓ 有效: $c\n")
            } catch (t: Throwable) {
                val cause = t.cause ?: t
                val msg = cause.message ?: cause.javaClass.simpleName
                if (msg.contains("not recognized", true)) {
                    // 名字不对，跳过
                } else {
                    // 名字对了，但值类型不对 —— 也算有效属性名
                    sb.append("  ✓ 有效(类型不符): $c  → ${msg.take(80)}\n")
                }
            }
        }
        sb.append("\n（只列出有效的）")
        val text = sb.toString()
        Log.i(TAG, text)
        return text
    }

    /** 探测 CertificateExtensions.set() 接受的扩展名。 */
    fun probeExtNames(): String {
        val sb = StringBuilder("=== CertificateExtensions 扩展名探测 ===\n")
        val extsCls = runCatching { Class.forName("sun.security.x509.CertificateExtensions") }.getOrNull()
            ?: return "CertificateExtensions 不可用"
        val setMethod = extsCls.getMethod("set", String::class.java, Any::class.java)

        val kuCls = runCatching { Class.forName("sun.security.x509.KeyUsageExtension") }.getOrNull()
        val kuValue = kuCls?.getConstructor(BooleanArray::class.java)
            ?.newInstance(booleanArrayOf(true, false, true, false, false, false, false, false, false))

        val candidates = listOf(
            "basicConstraints", "BasicConstraints",
            "keyUsage", "KeyUsage",
            "subjectAlternativeName", "SubjectAlternativeName", "san",
            "extendedKeyUsage", "ExtendedKeyUsage",
            "subjectKeyIdentifier", "SubjectKeyIdentifier",
            "x509.info.extensions.BasicConstraints",
            "x509.info.extensions.KeyUsage",
            "x509.info.extensions.SubjectAlternativeName",
        )

        for (c in candidates) {
            try {
                val exts = extsCls.getDeclaredConstructor().newInstance()
                setMethod.invoke(exts, c, kuValue)
                sb.append("  ✓ 有效: $c\n")
            } catch (t: Throwable) {
                val cause = t.cause ?: t
                val msg = cause.message ?: cause.javaClass.simpleName
                if (!msg.contains("not recognized", true) && !msg.contains("invalid", true)) {
                    sb.append("  ✓ 有效(其他错): $c → ${msg.take(70)}\n")
                }
            }
        }
        sb.append("\n（只列出有效的）")
        val text = sb.toString()
        Log.i(TAG, text)
        return text
    }

    /**
     * 探测每个属性接受的**具体类型**。
     *
     * 属性名已确认（裸名），但类型不对会报 "Xxx class type invalid"。
     * Android 裁剪版接受的类型可能与 JDK 不同，这里逐个试。
     */
    fun probeTypes(): String {
        val sb = StringBuilder("=== 属性类型探测 ===\n")
        val infoCls = runCatching { Class.forName("sun.security.x509.X509CertInfo") }.getOrNull()
            ?: return "X509CertInfo 不可用"
        val setMethod = infoCls.getMethod("set", String::class.java, Any::class.java)

        val x500NameCls = Class.forName("sun.security.x509.X500Name")
        val subjectNameCls = Class.forName("sun.security.x509.CertificateSubjectName")
        val serialCls = Class.forName("sun.security.x509.CertificateSerialNumber")
        val validityCls = Class.forName("sun.security.x509.CertificateValidity")
        val keyCls = Class.forName("sun.security.x509.CertificateX509Key")
        val algIdCls = Class.forName("sun.security.x509.AlgorithmId")
        val certAlgCls = Class.forName("sun.security.x509.CertificateAlgorithmId")
        val verCls = Class.forName("sun.security.x509.CertificateVersion")
        val extsCls = Class.forName("sun.security.x509.CertificateExtensions")

        val x500 = x500NameCls.getConstructor(String::class.java).newInstance("CN=test.example.com")
        val javaxX500 = X500Principal("CN=test.example.com")

        // 每个属性候选值列表
        val probes = mapOf(
            "subject" to listOf(
                "X500Name" to x500,
                "CertificateSubjectName" to subjectNameCls.getConstructor(x500NameCls).newInstance(x500),
                "javax.X500Principal" to javaxX500,
                "String" to "CN=test.example.com",
            ),
            "issuer" to listOf(
                "X500Name" to x500,
                "CertificateSubjectName" to subjectNameCls.getConstructor(x500NameCls).newInstance(x500),
                "javax.X500Principal" to javaxX500,
            ),
            "serialNumber" to listOf(
                "CertificateSerialNumber" to serialCls.getConstructor(BigInteger::class.java)
                    .newInstance(BigInteger.valueOf(12345)),
                "BigInteger" to BigInteger.valueOf(12345),
                "Integer" to 12345,
            ),
            "validity" to listOf(
                "CertificateValidity" to validityCls.getConstructor(Date::class.java, Date::class.java)
                    .newInstance(Date(), Date(System.currentTimeMillis() + 86400000)),
            ),
            "algorithmID" to listOf(
                "CertificateAlgorithmId" to certAlgCls.getConstructor(algIdCls)
                    .newInstance(algIdCls.getMethod("getAlgorithmId", String::class.java)
                        .invoke(null, "SHA256withRSA")),
                "AlgorithmId" to algIdCls.getMethod("getAlgorithmId", String::class.java)
                    .invoke(null, "SHA256withRSA"),
            ),
        )

        for ((attr, values) in probes) {
            sb.append("\n[$attr]\n")
            for ((typeName, value) in values) {
                try {
                    val info = infoCls.getDeclaredConstructor().newInstance()
                    // 先设 version，否则部分属性会报 Invalid version
                    setMethod.invoke(info, "version", verCls.getConstructor(Int::class.javaPrimitiveType).newInstance(2))
                    setMethod.invoke(info, attr, value)
                    sb.append("  ✓ $typeName\n")
                } catch (t: Throwable) {
                    val cause = t.cause ?: t
                    val msg = cause.message ?: cause.javaClass.simpleName
                    if (msg.contains("type invalid", true)) {
                        sb.append("  ✗ $typeName  (类型不符)\n")
                    } else if (msg.contains("not recognized", true)) {
                        sb.append("  ? $typeName  (属性名问题)\n")
                    } else {
                        sb.append("  ~ $typeName  → ${msg.take(60)}\n")
                    }
                }
            }
        }

        val text = sb.toString()
        Log.i(TAG, text)
        return text
    }

    /** 探测扩展类的真实构造器签名（含非 public）。 */
    fun probeExtCtors(): String {
        val sb = StringBuilder("=== 扩展类构造器 ===\n")
        val classes = listOf(
            "sun.security.x509.SubjectAlternativeNameExtension",
            "sun.security.x509.KeyUsageExtension",
            "sun.security.x509.BasicConstraintsExtension",
            "sun.security.x509.GeneralNames",
            "sun.security.x509.GeneralName",
        )
        for (cn in classes) {
            sb.append("\n--- $cn ---\n")
            try {
                val c = Class.forName(cn)
                val ctors = c.declaredConstructors
                if (ctors.isEmpty()) sb.append("  [无构造器]\n")
                for (ct in ctors) {
                    val mods = java.lang.reflect.Modifier.toString(ct.modifiers)
                    val params = ct.parameterTypes.joinToString(", ") { it.simpleName }
                    sb.append("  $mods ($params)\n")
                }
            } catch (t: Throwable) {
                sb.append("  ✗ ${t.javaClass.simpleName}\n")
            }
        }
        val text = sb.toString()
        Log.i(TAG, text)
        return text
    }

    /**
     * 探测扩展的替代构造路径。
     *
     * 背景：Android 裁剪掉了 SubjectAlternativeNameExtension 和
     * BasicConstraintsExtension 的构造器，但 CertificateExtensions
     * 有 `public (DerInputStream)` 构造器 —— 可以从 DER 解析。
     * 这里验证这条路是否可走。
     */
    fun probeExtApi(): String {
        val sb = StringBuilder("=== 扩展替代构造路径 ===\n")
        try {
            val extsCls = Class.forName("sun.security.x509.CertificateExtensions")
            sb.append("\nCertificateExtensions 构造器:\n")
            extsCls.declaredConstructors.forEach { ct ->
                sb.append("  ${java.lang.reflect.Modifier.toString(ct.modifiers)} ")
                    .append("(").append(ct.parameterTypes.joinToString(", ") { it.simpleName }).append(")\n")
            }

            // 试：用 DerInputStream + DerValue 构造一个 BasicConstraints 扩展
            sb.append("\n--- 尝试 DER 方式创建扩展 ---\n")
            val derValueCls = Class.forName("sun.security.util.DerValue")

            // BasicConstraints: SEQUENCE { BOOLEAN TRUE }
            // DER: 30 03 01 01 FF
            val bcDer = byteArrayOf(0x30, 0x03, 0x01, 0x01, 0xFF.toByte())
            val bcValue = derValueCls.getConstructor(ByteArray::class.java).newInstance(bcDer)
            sb.append("  创建 DerValue: ✓\n")

            // CertificateExtensions.set(String, Object) 需要扩展对象而非 DerValue
            // 试试用 BasicConstraintsExtension 的非 public 构造器（setAccessible）
            val bcExtCls = Class.forName("sun.security.x509.BasicConstraintsExtension")
            val ctors = bcExtCls.declaredConstructors
            sb.append("\nBasicConstraintsExtension 全部构造器（含非 public）:\n")
            ctors.forEach { ct ->
                ct.isAccessible = true
                sb.append("  ${java.lang.reflect.Modifier.toString(ct.modifiers)} ")
                    .append("(").append(ct.parameterTypes.joinToString(", ") { it.simpleName }).append(")\n")
            }

            // 尝试用反射调用非 public 构造器
            ctors.firstOrNull { it.parameterCount == 2 }?.let { ct ->
                ct.isAccessible = true
                val obj = ct.newInstance(true, 5)
                sb.append("  ✓ 用非 public 构造器创建成功: $obj\n")
            }

            val sanExtCls = Class.forName("sun.security.x509.SubjectAlternativeNameExtension")
            sb.append("\nSubjectAlternativeNameExtension 全部构造器（含非 public）:\n")
            sanExtCls.declaredConstructors.forEach { ct ->
                ct.isAccessible = true
                sb.append("  ${java.lang.reflect.Modifier.toString(ct.modifiers)} ")
                    .append("(").append(ct.parameterTypes.joinToString(", ") { it.simpleName }).append(")\n")
            }
        } catch (t: Throwable) {
            sb.append("  ✗ ${t.javaClass.simpleName}: ${t.message}\n")
        }
        val text = sb.toString()
        Log.i(TAG, text)
        return text
    }

    fun dump(): String {
        val sb = StringBuilder()
        sb.append("=== 证书签发 API 探测 ===\n")

        for (cls in listOf(
            "$P.x509.X509V3CertificateGenerator",
            // 真正要用的：sun.security.x509
            "sun.security.x509.X509CertInfo",
            "sun.security.x509.CertificateVersion",
            "sun.security.x509.CertificateSerialNumber",
            "sun.security.x509.CertificateSubjectName",
            "sun.security.x509.CertificateX509Key",
            "sun.security.x509.CertificateValidity",
            "sun.security.x509.CertificateAlgorithmId",
            "sun.security.x509.CertificateExtensions",
            "sun.security.x509.BasicConstraintsExtension",
            "sun.security.x509.KeyUsageExtension",
            "sun.security.x509.SubjectAlternativeNameExtension",
            "sun.security.x509.ExtendedKeyUsageExtension",
            "sun.security.x509.AlgorithmId",
            "sun.security.x509.X500Name",
            "sun.security.x509.X509CertImpl",
            "sun.security.util.ObjectIdentifier",
        )) {
            sb.append("\n--- $cls ---\n")
            try {
                val c = Class.forName(cls)
                val ctors = c.declaredConstructors
                if (ctors.isEmpty()) {
                    sb.append("  [无构造器]\n")
                } else {
                    sb.append("  [构造器]\n")
                    ctors.take(12).forEach { ct ->
                        val params = ct.parameterTypes.joinToString(", ") { it.simpleName }
                        sb.append("    ${Modifier.toString(ct.modifiers)} ($params)\n")
                    }
                }

                // 静态字段（版本常量、扩展名常量）
                val fields = c.fields.filter {
                    Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers)
                }
                if (fields.isNotEmpty()) {
                    sb.append("  [静态常量]\n")
                    fields.take(20).forEach { f ->
                        val v = runCatching { f.get(null).toString().take(40) }.getOrDefault("?")
                        sb.append("    ${f.type.simpleName} ${f.name} = $v\n")
                    }
                }

                val ms = c.methods.filter { it.declaringClass == c }
                if (ms.isNotEmpty()) {
                    sb.append("  [公开方法]\n")
                    ms.take(20).forEach { m ->
                        val params = m.parameterTypes.joinToString(", ") { it.simpleName }
                        sb.append("    ${m.returnType.simpleName} ${m.name}($params)\n")
                    }
                }
            } catch (t: Throwable) {
                sb.append("  ✗ 不可用: ${t.javaClass.simpleName}\n")
            }
        }

        val text = sb.toString()
        Log.i(TAG, text)
        return text
    }
}
