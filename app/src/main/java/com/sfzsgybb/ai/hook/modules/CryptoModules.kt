package com.sfzsgybb.ai.hook.modules

import com.sfzsgybb.ai.hook.config.HookSwitch
import com.sfzsgybb.ai.hook.engine.HookContext
import com.sfzsgybb.ai.hook.engine.CallStackCapture
import com.sfzsgybb.ai.hook.engine.HookModule
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.WeakHashMap
import javax.crypto.Mac

/**
 * 摘要信息收集器。
 *
 * 【为什么单独抽出来并改用 WeakHashMap + ByteArrayOutputStream】
 * 之前的实现有三个问题：
 *  1. ALGO_MAP / INPUTS 用 identityHashCode 做 key 的普通 HashMap，**从不清理**，
 *     MessageDigest 对象被 GC 后 key 仍然留着 → 内存只增不减
 *  2. INPUTS[key] = prev + buf 每次都重新分配并拷贝整个累积数组 → O(n²)，
 *     大文件哈希会退化成几万次拷贝，直接卡死调用线程
 *  3. identityHashCode 会复用，旧对象的残留数据可能被错误关联到新对象
 *
 * 现在改为：
 *  - WeakHashMap 以对象本身为 key，对象被回收时条目自动清除
 *  - ByteArrayOutputStream 追加写入，避免反复拷贝
 *  - 限制单次采集上限，超过后不再累积（只保留前 N 字节）
 */
internal object DigestCollector {

    /** 单次输入采集上限。超过后停止累积，避免大文件撑爆内存。 */
    private const val MAX_INPUT = 64 * 1024

    private val algos = Collections.synchronizedMap(WeakHashMap<MessageDigest, String>())
    private val inputs = Collections.synchronizedMap(WeakHashMap<MessageDigest, ByteArrayOutputStream>())
    private val sizes = Collections.synchronizedMap(WeakHashMap<MessageDigest, Int>())

    fun onGetInstance(md: MessageDigest) {
        runCatching { algos[md] = md.algorithm }
    }

    fun onUpdate(md: MessageDigest, buf: ByteArray, off: Int = 0, len: Int = buf.size) {
        runCatching {
            val total = sizes[md] ?: 0
            if (total >= MAX_INPUT) return
            val take = minOf(len, MAX_INPUT - total)
            if (take <= 0) return
            val out = inputs.getOrPut(md) { ByteArrayOutputStream(minOf(1024, MAX_INPUT)) }
            out.write(buf, off, take)
            sizes[md] = total + take
        }
    }

    /**
     * 取出输入并清理状态（digest 已调用，该实例的输入不再需要）。
     * 返回 null 表示没有采集到输入。
     */
    fun takeInput(md: MessageDigest): ByteArray? {
        val out = inputs.remove(md) ?: return null
        sizes.remove(md)
        algos.remove(md)
        return runCatching { out.toByteArray() }.getOrNull()
    }

    fun algoOf(md: MessageDigest): String? = algos[md]

    /** 供诊断。 */
    fun trackSize(): Int = inputs.size
}

/**
 * 消息摘要 Hook：MessageDigest。
 *
 * 关键点：
 *  - digest() 调用后 MessageDigest 会被 reset，因此必须在 afterHookedMethod 取 result
 *  - 只为已开启该开关的应用安装
 */
class DigestModule : HookModule {
    override val switch = HookSwitch.DIGEST

    override fun install(ctx: HookContext) {
        val cls = ctx.findClassOrNull("java.security.MessageDigest") ?: return

        runCatching {
            XposedHelpers.findAndHookMethod(
                cls, "getInstance", String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val md = param.result as? MessageDigest ?: return
                        DigestCollector.onGetInstance(md)
                    }
                },
            )
        }

        // update(byte[]) / update(byte[],int,int)
        runCatching {
            XposedHelpers.findAndHookMethod(
                cls, "update", ByteArray::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val md = param.thisObject as? MessageDigest ?: return
                        val buf = param.args.getOrNull(0) as? ByteArray ?: return
                        DigestCollector.onUpdate(md, buf)
                    }
                },
            )
        }
        runCatching {
            XposedHelpers.findAndHookMethod(
                cls, "update",
                ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val md = param.thisObject as? MessageDigest ?: return
                        val buf = param.args.getOrNull(0) as? ByteArray ?: return
                        val off = param.args.getOrNull(1) as? Int ?: 0
                        val len = param.args.getOrNull(2) as? Int ?: buf.size
                        DigestCollector.onUpdate(md, buf, off, len)
                    }
                },
            )
        }

        // digest() —— 无参版本，用之前累积的输入
        runCatching {
            XposedHelpers.findAndHookMethod(
                cls, "digest",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val md = param.thisObject as? MessageDigest ?: return
                        val out = param.result as? ByteArray ?: return
                        if (out.isEmpty()) return
                        val input = DigestCollector.takeInput(md) ?: ByteArray(0)
                        val algo = DigestCollector.algoOf(md) ?: md.algorithm ?: "MessageDigest"
                        ctx.reporter.report(
                            logName = algo, className = "java.security.MessageDigest",
                            objName = "digest", payload = input,
                            results = listOf(out), sw = switch,
                            algoName = algo,
                            paramDesc = "输入长度 ${input.size} 字节",
                            resultKind = "摘要结果",
                            callStack = CallStackCapture.captureCaller("java.security.MessageDigest"),
                        )
                    }
                },
            )
        }

        // digest(byte[]) —— 一次性版本，输入来自参数
        runCatching {
            XposedHelpers.findAndHookMethod(
                cls, "digest", ByteArray::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val md = param.thisObject as? MessageDigest ?: return
                        val input = param.args.getOrNull(0) as? ByteArray ?: return
                        val out = param.result as? ByteArray ?: return
                        ctx.reporter.report(
                            logName = md.algorithm ?: "MessageDigest",
                            className = "java.security.MessageDigest", objName = "digest",
                            payload = input, results = listOf(out), sw = switch,
                            algoName = md.algorithm ?: "MessageDigest",
                            paramDesc = "输入长度 ${input.size} 字节（byte[]）",
                            resultKind = "摘要结果",
                            callStack = CallStackCapture.captureCaller("java.security.MessageDigest"),
                        )
                    }
                },
            )
        }
    }
}

/**
 * MAC Hook：HMAC 系列。
 *
 * 同样改用 WeakHashMap，避免对象泄漏。
 */
class MacModule : HookModule {
    override val switch = HookSwitch.MAC

    private val keys = Collections.synchronizedMap(WeakHashMap<Mac, ByteArray>())
    private val inputs = Collections.synchronizedMap(WeakHashMap<Mac, ByteArrayOutputStream>())

    override fun install(ctx: HookContext) {
        val cls = ctx.findClassOrNull("javax.crypto.Mac") ?: return

        runCatching {
            XposedHelpers.findAndHookMethod(
                cls, "init", java.security.Key::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val mac = param.thisObject as? Mac ?: return
                        val key = param.args.getOrNull(0) as? java.security.Key ?: return
                        keys[mac] = key.encoded ?: ByteArray(0)
                    }
                },
            )
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                cls, "update", ByteArray::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val mac = param.thisObject as? Mac ?: return
                        val buf = param.args.getOrNull(0) as? ByteArray ?: return
                        if (buf.size > MAX_FIELD) return
                        val out = inputs.getOrPut(mac) { ByteArrayOutputStream() }
                        if (out.size() < MAX_FIELD) out.write(buf, 0, minOf(buf.size, MAX_FIELD - out.size()))
                    }
                },
            )
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                cls, "doFinal",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val mac = param.thisObject as? Mac ?: return
                        val out = param.result as? ByteArray ?: return
                        val key = keys.remove(mac) ?: ByteArray(0)
                        val data = inputs.remove(mac)?.toByteArray() ?: ByteArray(0)
                        ctx.reporter.report(
                            logName = mac.algorithm ?: "Hmac",
                            className = "javax.crypto.Mac",
                            objName = "doFinal",
                            // payload = 待计算的数据；key 单独作为「密钥」分类展示
                            payload = data,
                            results = listOf(out), sw = switch,
                            algoName = mac.algorithm ?: "Hmac",
                            keyType = "会话密钥",
                            keyData = key,
                            paramDesc = "数据长度 ${data.size} 字节",
                            resultKind = "MAC 结果",
                            callStack = CallStackCapture.captureCaller("javax.crypto.Mac"),
                        )
                    }
                },
            )
        }
    }

    private companion object {
        const val MAX_FIELD = 64 * 1024
    }
}

/**
 * Cipher Hook：AES/DES/RSA 等。
 *
 * 关键点：
 *  - init 的 key/iv 与 doFinal 的输入输出按实例关联，用 WeakHashMap
 *  - doFinal 可能被多次调用（流式），每次处理后清理对应实例的输入
 */
class CipherModule : HookModule {
    override val switch = HookSwitch.CIPHER

    private val meta = Collections.synchronizedMap(WeakHashMap<javax.crypto.Cipher, String>())
    private val inputs = Collections.synchronizedMap(WeakHashMap<javax.crypto.Cipher, ByteArrayOutputStream>())

    /** 密钥内容（init 时存下，doFinal 时作为「加密密钥」分类上报） */
    private val keys = Collections.synchronizedMap(WeakHashMap<javax.crypto.Cipher, ByteArray>())

    /** 密钥类型（RSA 是公钥/私钥，对称算法是会话密钥） */
    private val keyTypes = Collections.synchronizedMap(WeakHashMap<javax.crypto.Cipher, String>())

    /** 是加密还是解密 */
    private val modes = Collections.synchronizedMap(WeakHashMap<javax.crypto.Cipher, Boolean>())

    override fun install(ctx: HookContext) {
        val cls = ctx.findClassOrNull("javax.crypto.Cipher") ?: return

        runCatching {
            XposedHelpers.findAndHookMethod(
                cls, "getInstance", String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val transformation = param.args.getOrNull(0) as? String ?: return
                        val c = param.result as? javax.crypto.Cipher ?: return
                        meta[c] = "transformation=$transformation"
                    }
                },
            )
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                cls, "init",
                Int::class.javaPrimitiveType, java.security.Key::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val c = param.thisObject as? javax.crypto.Cipher ?: return
                        val mode = param.args.getOrNull(0) as? Int ?: return
                        val key = param.args.getOrNull(1) as? java.security.Key
                        val sb = StringBuilder()
                        sb.append(if (mode == 1) "ENCRYPT" else "DECRYPT")
                        key?.let {
                            sb.append(" key=").append(it.algorithm)
                                .append('/').append(it.encoded?.size ?: 0).append('B')
                        }
                        meta[c] = sb.toString()
                    }
                },
            )
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                cls, "init",
                Int::class.javaPrimitiveType, java.security.Key::class.java,
                java.security.spec.AlgorithmParameterSpec::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val c = param.thisObject as? javax.crypto.Cipher ?: return
                        val mode = param.args.getOrNull(0) as? Int ?: return
                        val key = param.args.getOrNull(1) as? java.security.Key
                        val iv = param.args.getOrNull(2)
                        val sb = StringBuilder()
                        sb.append(if (mode == 1) "ENCRYPT" else "DECRYPT")
                        key?.let {
                            sb.append(" key=").append(it.algorithm)
                                .append('/').append(it.encoded?.size ?: 0).append('B')
                        }
                        when (iv) {
                            is javax.crypto.spec.IvParameterSpec ->
                                sb.append(" iv=").append(iv.iv.toHexPreview())
                            is javax.crypto.spec.GCMParameterSpec ->
                                sb.append(" gcmIv=").append(iv.iv.toHexPreview())
                                    .append(" tag=").append(iv.tLen * 8)
                        }
                        meta[c] = sb.toString()
                        // 存下密钥与用途，供 doFinal 分类上报
                        key?.encoded?.let { keys[c] = it }
                        keyTypes[c] = rsaKeyType(key)
                        modes[c] = (mode == 1)
                    }
                },
            )
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                cls, "update", ByteArray::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val c = param.thisObject as? javax.crypto.Cipher ?: return
                        val buf = param.args.getOrNull(0) as? ByteArray ?: return
                        if (buf.size > MAX_FIELD) return
                        val out = inputs.getOrPut(c) { ByteArrayOutputStream() }
                        if (out.size() < MAX_FIELD) out.write(buf, 0, minOf(buf.size, MAX_FIELD - out.size()))
                    }
                },
            )
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                cls, "doFinal", ByteArray::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val c = param.thisObject as? javax.crypto.Cipher ?: return
                        val direct = param.args.getOrNull(0) as? ByteArray
                        val out = param.result as? ByteArray ?: return
                        val accumulated = inputs.remove(c)?.toByteArray() ?: ByteArray(0)
                        val payload = if (direct != null && direct.isNotEmpty()) direct else accumulated
                        val isEnc = modes[c] ?: true
                        ctx.reporter.report(
                            logName = runCatching { c.algorithm }.getOrNull() ?: "Cipher",
                            className = "javax.crypto.Cipher",
                            objName = "doFinal",
                            payload = payload,
                            results = listOf(out), sw = switch,
                            algoName = runCatching { c.algorithm }.getOrNull() ?: "Cipher",
                            keyType = keyTypes[c] ?: "会话密钥",
                            keyData = keys[c] ?: ByteArray(0),
                            paramDesc = meta[c] ?: "",
                            resultKind = if (isEnc) "加密结果" else "解密结果",
                            callStack = CallStackCapture.captureCaller("javax.crypto.Cipher"),
                        )
                    }
                },
            )
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                cls, "doFinal",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val c = param.thisObject as? javax.crypto.Cipher ?: return
                        val out = param.result as? ByteArray ?: return
                        val accumulated = inputs.remove(c)?.toByteArray() ?: ByteArray(0)
                        val isEnc2 = modes[c] ?: true
                        ctx.reporter.report(
                            logName = runCatching { c.algorithm }.getOrNull() ?: "Cipher",
                            className = "javax.crypto.Cipher",
                            objName = "doFinal",
                            payload = accumulated,
                            results = listOf(out), sw = switch,
                            algoName = runCatching { c.algorithm }.getOrNull() ?: "Cipher",
                            keyType = keyTypes[c] ?: "会话密钥",
                            keyData = keys[c] ?: ByteArray(0),
                            paramDesc = meta[c] ?: "",
                            resultKind = if (isEnc2) "加密结果" else "解密结果",
                            callStack = CallStackCapture.captureCaller("javax.crypto.Cipher"),
                        )
                    }
                },
            )
        }
    }

    /** 判断密钥类型：RSA 区分公钥/私钥，其余算会话密钥。 */
    private fun rsaKeyType(key: java.security.Key?): String = when (key) {
        null -> "会话密钥"
        is java.security.PublicKey -> "公钥"
        is java.security.PrivateKey -> "私钥"
        else -> "会话密钥"
    }

    private companion object {
        const val MAX_FIELD = 256 * 1024
    }
}

/**
 * 数字签名/证书相关。
 */
class SignatureModule : HookModule {
    override val switch = HookSwitch.SIGNATURE

    override fun install(ctx: HookContext) {
        val pmCls = ctx.findClassOrNull("android.app.ApplicationPackageManager") ?: return
        runCatching {
            XposedHelpers.findAndHookMethod(
                pmCls, "getPackageInfo",
                String::class.java, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // 仅记录，不篡改返回。
                        // 真正伪造签名需要目标应用的具体签名，此处只提供 hook 点。
                    }
                },
            )
        }
    }
}

internal fun ByteArray.toHexPreview(max: Int = 32): String {
    val n = minOf(size, max)
    val sb = StringBuilder(n * 2 + 3)
    for (i in 0 until n) {
        val v = this[i].toInt() and 0xFF
        sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 0x0F])
    }
    if (size > n) sb.append("...")
    return sb.toString()
}
