package com.junge.aidepro.hook.modules

import com.junge.aidepro.hook.config.HookSwitch
import com.junge.aidepro.hook.engine.HookContext
import com.junge.aidepro.hook.engine.HookModule
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager

/**
 * SSL 证书信任（等价 JustTrustMe+）。
 *
 * 【用途】
 * Hook 掉证书校验，让目标应用信任任何证书。
 * 配合 Charles / mitmproxy 这类中间人工具才能解密 HTTPS 抓包。
 *
 * 注意：本模块只影响「证书校验」这一步，不改变网络栈本身。
 * 如果开启后目标应用完全没有网络，通常是下面的实现细节踩了坑。
 *
 * 【已修复的三个致命问题】
 *
 * 1. TrustManager 数组类型不匹配
 *    之前直接塞 `arrayOf<X509TrustManager>(...)`，而 SSLContext.init 的形参是
 *    `TrustManager[]`。部分 SSL 实现（尤其 SSLEngine / Netty / Cronet）在运行期
 *    会用 `Array<TrustManager>` 做类型检查，不匹配会抛 IllegalArgumentException，
 *    导致 SSLContext 初始化失败 → **所有 HTTPS 请求建立不起来**。
 *    修法：用 `arrayOfNulls<TrustManager>(1)` 再赋值，保证数组运行期类型正确。
 *
 * 2. getAcceptedIssuers() 返回空数组
 *    很多库会遍历这个列表判断「是否有可信 CA」，空数组会被判定为
 *    「没有可信证书链」，直接拒绝握手。
 *    修法：返回一个占位自签证书，让其看起来「有 CA 可用」。
 *
 * 3. 错误地 hook CertificatePinner.check 并设置 param.result
 *    check 是 void 方法，设 result 无效；且 hookAllMethods 会连 OkHttp 内部的
 *    `check$okhttp` 一起劫持，破坏 OkHttp 自身的证书固定流程，
 *    表现为随机性的连接失败（**这就是抓包「有时有数据有时没有」的原因之一**）。
 *    修法：只 hook 公开的 check(String, List<Certificate>) 重载，且只是放行，
 *    不篡改返回值结构。
 */
class SslTrustModule : HookModule {
    override val switch = HookSwitch.JUST_TRUST_ME_PLUS

    override fun install(ctx: HookContext) {
        installSSLContextInit(ctx)
        installHostnameVerifier(ctx)
        installOkHttpPinner(ctx)
        installWebViewSslError(ctx)
    }

    /**
     * 替换 SSLContext 的 TrustManager。
     *
     * 关键：数组必须声明为 TrustManager[]（而不是 X509TrustManager[]），
     * 否则某些实现在运行期做类型校验时会抛异常。
     */
    private fun installSSLContextInit(ctx: HookContext) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "javax.net.ssl.SSLContext", ctx.classLoader, "init",
                Array<javax.net.ssl.KeyManager>::class.java,
                Array<javax.net.ssl.TrustManager>::class.java,
                java.security.SecureRandom::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        runCatching {
                            @Suppress("UNCHECKED_CAST")
                            val tms = param.args[1] as? Array<javax.net.ssl.TrustManager>

                            // 已经是我们的 TrustAllManager 就不重复替换
                            if (tms != null && tms.size == 1 && tms[0] === TrustAllManager) return

                            // 用 arrayOfNulls 保证运行期数组类型是 TrustManager[]
                            val replacement = arrayOfNulls<javax.net.ssl.TrustManager>(1)
                            replacement[0] = TrustAllManager
                            param.args[1] = replacement
                        }.onFailure {
                            XposedBridge.log("[SslTrust] 替换 TrustManager 失败: ${it.message}")
                        }
                    }
                },
            )
        }.onFailure {
            XposedBridge.log("[SslTrust] hook SSLContext.init 失败: ${it.message}")
        }
    }

    /** HostnameVerifier 全放行。 */
    private fun installHostnameVerifier(ctx: HookContext) {
        for (cls in listOf("javax.net.ssl.HttpsURLConnection", "okhttp3.internal.tls.OkHostnameVerifier")) {
            for (m in listOf("setDefaultHostnameVerifier", "setHostnameVerifier")) {
                runCatching {
                    XposedHelpers.findAndHookMethod(
                        cls, ctx.classLoader, m, HostnameVerifier::class.java,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                param.args[0] = TrustAllHostnameVerifier
                            }
                        },
                    )
                }
            }
        }
    }

    /**
     * OkHttp CertificatePinner。
     *
     * 只 hook 公开的 `check(String, List<Certificate>)` 重载。
     * 不碰内部 `check$okhttp`，避免破坏 OkHttp 自身的请求流程
     * （之前用 hookAllMethods 全劫持，会导致随机的连接失败）。
     */
    private fun installOkHttpPinner(ctx: HookContext) {
        val cls = ctx.findClassOrNull("okhttp3.CertificatePinner") ?: return
        runCatching {
            XposedHelpers.findAndHookMethod(
                cls, "check",
                String::class.java, List::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // 直接返回，跳过 pin 校验。check 是 void，不需要设 result。
                        param.result = null
                    }
                },
            )
        }.onFailure {
            // 找不到该重载很正常（OkHttp 版本差异），忽略
        }
    }

    /** WebView 的 SSL 错误也一律忽略。 */
    private fun installWebViewSslError(ctx: HookContext) {
        val cls = ctx.findClassOrNull("android.webkit.WebViewClient") ?: return
        runCatching {
            XposedBridge.hookAllMethods(cls, "onReceivedSslError", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val handler = param.args.getOrNull(1) ?: return
                    runCatching {
                        handler.javaClass.getMethod("proceed").invoke(handler)
                    }
                    param.result = null
                }
            })
        }
    }

    companion object {
        /**
         * 信任一切证书的 TrustManager。
         *
         * 注意 getAcceptedIssuers 返回占位证书而不是空数组 ——
         * 有空数组会让部分库判定「无可信 CA」而拒绝握手。
         */
        val TrustAllManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = PLACEHOLDER_ISSUERS
        }

        val TrustAllHostnameVerifier = HostnameVerifier { _: String?, _: SSLSession? -> true }

        /**
         * 占位自签证书。
         *
         * getAcceptedIssuers 返回空数组时，部分网络库会判定「没有可信 CA」
         * 而直接拒绝握手（表现为开启 SSL 信任后反而没网络）。
         * 这里现场生成一张自签证书作为占位，保证列表非空。
         *
         * 用 by lazy 避免类加载时就做 RSA 运算（那会拖慢目标应用启动）。
         * 生成失败时回退为空数组——至少不会崩。
         */
        private val PLACEHOLDER_ISSUERS: Array<X509Certificate> by lazy {
            runCatching {
                val kpg = java.security.KeyPairGenerator.getInstance("RSA")
                kpg.initialize(1024)
                val kp = kpg.generateKeyPair()

                // 用 Android 自带的 BouncyCastle 生成自签证书
                val certBuilder = Class.forName("com.android.org.bouncycastle.x509.X509V3CertificateGenerator")
                val gen = certBuilder.getDeclaredConstructor().newInstance()
                val x500 = javax.security.auth.x500.X500Principal("CN=AideProPlaceholder")

                certBuilder.getMethod("setSerialNumber", java.math.BigInteger::class.java)
                    .invoke(gen, java.math.BigInteger.valueOf(System.currentTimeMillis()))
                certBuilder.getMethod("setIssuerX500Principal", javax.security.auth.x500.X500Principal::class.java)
                    .invoke(gen, x500)
                certBuilder.getMethod("setSubjectX500Principal", javax.security.auth.x500.X500Principal::class.java)
                    .invoke(gen, x500)
                certBuilder.getMethod("setNotBefore", java.util.Date::class.java)
                    .invoke(gen, java.util.Date(System.currentTimeMillis() - 86_400_000L))
                certBuilder.getMethod("setNotAfter", java.util.Date::class.java)
                    .invoke(gen, java.util.Date(System.currentTimeMillis() + 365L * 86_400_000L))
                certBuilder.getMethod("setPublicKey", java.security.PublicKey::class.java)
                    .invoke(gen, kp.public)

                val sigAlg = "SHA1withRSA"
                certBuilder.getMethod("setSignatureAlgorithm", String::class.java).invoke(gen, sigAlg)

                val signMethod = certBuilder.methods.firstOrNull { it.name == "generate" && it.parameterCount == 1 }
                    ?: return@runCatching emptyArray()
                val cert = signMethod.invoke(gen, kp.private) as? X509Certificate
                if (cert != null) arrayOf(cert) else emptyArray()
            }.getOrDefault(emptyArray())
        }
    }
}

/** 环境检测绕过。 */
class RootBypassModule : HookModule {
    override val switch = HookSwitch.CHECK_ROOT

    private val suPaths = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/sd/xbin/su",
        "/system/bin/failsafe/su", "/data/local/su", "/data/local/bin/su", "/data/local/xbin/su",
        "su", "/system/app/Superuser.apk", "/system/app/SuperSU.apk",
    )

    override fun install(ctx: HookContext) {
        val fileCls = ctx.findClassOrNull("java.io.File") ?: return
        runCatching {
            XposedHelpers.findAndHookMethod(fileCls, "exists", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val f = param.thisObject as? java.io.File ?: return
                    if (suPaths.any { f.absolutePath.endsWith(it) || f.absolutePath == it }) {
                        param.result = false
                    }
                }
            })
        }

        listOf("java.lang.Runtime", "java.lang.ProcessBuilder").forEach { n ->
            val c = ctx.findClassOrNull(n) ?: return@forEach
            runCatching {
                XposedBridge.hookAllMethods(c, "exec", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val a = param.args.getOrNull(0)
                        val s = when (a) {
                            is Array<*> -> a.joinToString(" ")
                            is List<*> -> a.joinToString(" ")
                            else -> a?.toString() ?: ""
                        }
                        if (s.trim().startsWith("su")) param.result = null
                    }
                })
            }
        }

        runCatching {
            XposedHelpers.setStaticObjectField(android.os.Build::class.java, "TAGS", "release-keys")
        }
    }
}

/** 隐藏 Xposed 痕迹。 */
class HiddenXposedModule : HookModule {
    override val switch = HookSwitch.HIDDEN_XPOSED

    override fun install(ctx: HookContext) {
        val clsLoader = ctx.findClassOrNull("java.lang.ClassLoader") ?: return
        runCatching {
            XposedHelpers.findAndHookMethod(
                clsLoader, "loadClass", String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val name = param.args.getOrNull(0) as? String ?: return
                        if (name.startsWith("de.robv.android.xposed") ||
                            name.startsWith("org.lsposed") ||
                            name.contains("XposedBridge")
                        ) {
                            param.result = null
                        }
                    }
                },
            )
        }
    }
}

/** 隐藏 WiFi 代理设置。 */
class HiddenProxyModule : HookModule {
    override val switch = HookSwitch.HIDDEN_WIFI_PROXY

    override fun install(ctx: HookContext) {
        val cls = ctx.findClassOrNull("android.net.wifi.WifiInfo") ?: return
        runCatching {
            XposedHelpers.findAndHookMethod(cls, "getHttpProxy", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) { param.result = null }
            })
        }
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.net.Proxy", ctx.classLoader, "getDefaultProxy",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) { param.result = null }
                },
            )
        }
    }
}

/** 隐藏 VPN。 */
class HiddenVpnModule : HookModule {
    override val switch = HookSwitch.HIDDEN_VPN

    override fun install(ctx: HookContext) {
        val cls = ctx.findClassOrNull("android.net.NetworkCapabilities") ?: return
        runCatching {
            XposedHelpers.findAndHookMethod(
                cls, "hasTransport", Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // TRANSPORT_VPN = 4
                        if (param.args.getOrNull(0) == 4) param.result = false
                    }
                },
            )
        }
    }
}
