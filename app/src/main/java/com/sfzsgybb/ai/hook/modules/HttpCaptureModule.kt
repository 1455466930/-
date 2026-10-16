package com.sfzsgybb.ai.hook.modules

import com.sfzsgybb.ai.hook.config.HookSwitch
import com.sfzsgybb.ai.hook.engine.HookContext
import com.sfzsgybb.ai.hook.engine.HookModule
import com.sfzsgybb.ai.hook.engine.LogReporter
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.InputStream
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * HTTP 抓包模块（Java 层）。
 *
 * 【核心难点：混淆类名】
 * 很多应用（尤其用 R8/ProGuard 的）把 OkHttp 的类名混淆成
 * `okhttp3.a`、`okhttp3.b` 这种。写死 `okhttp3.internal.connection.RealCall`
 * 会直接 findClassOrNull 返回 null，导致什么都不 hook。
 *
 * 因此这里**按方法签名特征动态定位**：
 *  - 遍历 `okhttp3.` 包下的所有类
 *  - 找「有 execute() 方法、无参、返回非 void 类型」的类 → 那就是 Call 实现
 *  - 找「有 enqueue(1 个参数)」的类 → 异步路径
 *
 * 不依赖任何具体类名，混淆也能用。
 */
class HttpCaptureModule : HookModule {
    override val switch = HookSwitch.HTTP_CAPTURE

    override fun install(ctx: HookContext) {
        installOkHttp(ctx)
        installUrlConnection(ctx)
        installWebView(ctx)
    }

    // ---------------- OkHttp（动态定位） ----------------

    private fun installOkHttp(ctx: HookContext) {
        try {
            // 找到 okhttp3 包下所有类
            val loader = ctx.classLoader
            val okhttpClasses = findOkHttpClasses(loader)

            // 诊断：打印探测到的类数量，帮助定位混淆问题
            XposedBridge.log("[HttpCapture] 探测到 ${okhttpClasses.size} 个 okhttp 类")

            // 1) 找「execute 方法」的类 —— 同步调用入口
            var executeHooked = false
            for (cls in okhttpClasses) {
                if (hookExecuteMethod(ctx, cls)) {
                    executeHooked = true
                    XposedBridge.log("[HttpCapture] 定位到 Call.execute: ${cls.name}")
                    break
                }
            }
            if (!executeHooked) {
                // 诊断：打印前几个类及其方法，看方法名是否被混淆
                val sample = okhttpClasses.take(6)
                for (c in sample) {
                    val ms = c.declaredMethods.take(8).joinToString(",") { it.name }
                    XposedBridge.log("[HttpCapture] 类 ${c.name} 方法: $ms")
                }
                XposedBridge.log("[HttpCapture] 未找到 execute 方法（可能方法名被混淆或不用 OkHttp）")
            }

            // 2) 找「enqueue 方法」的类 —— 异步调用入口
            var enqueueHooked = false
            for (cls in okhttpClasses) {
                if (hookEnqueueMethod(ctx, cls)) {
                    enqueueHooked = true
                    XposedBridge.log("[HttpCapture] 定位到 Call.enqueue: ${cls.name}")
                    break
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log("[HttpCapture] installOkHttp 失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** 枚举 okhttp3 包下的所有类（含内部类）。 */
    private fun findOkHttpClasses(loader: ClassLoader): List<Class<*>> {
        val result = ArrayList<Class<*>>()

        // 已知未混淆类名（正常 OkHttp）
        val known = listOf(
            "okhttp3.internal.connection.RealCall",
            "okhttp3.RealCall",
            "okhttp3.Call",
            "okhttp3.OkHttpClient",
            "okhttp3.Response",
            "okhttp3.Request",
            "okhttp3.Callback",
            "okhttp3.ResponseBody",
            "okhttp3.RequestBody",
        )
        for (name in known) {
            runCatching { Class.forName(name, false, loader) }.getOrNull()?.let { result.add(it) }
        }

        // R8 混淆形态：okhttp3/a ~ okhttp3/z，以及内部类 a$a ~ z$z
        // （mark.via 实测类名是 okhttp3/a、okhttp3/b$a、okhttp3/c$a 这种）
        val base = "okhttp3."
        for (c in 'a'..'z') {
            runCatching { Class.forName(base + c, false, loader) }.getOrNull()?.let { result.add(it) }
            // 内部类
            for (inner in 'a'..'z') {
                runCatching { Class.forName("$base$c\$$inner", false, loader) }
                    .getOrNull()?.let { result.add(it) }
            }
        }
        // 两位字母（a0/z0）
        for (c in 'a'..'z') {
            runCatching { Class.forName(base + c + "0", false, loader) }.getOrNull()?.let { result.add(it) }
        }
        return result
    }

    /** 尝试 hook 某类的 execute() 方法（无参、返回非 void、非静态）。 */
    private fun hookExecuteMethod(ctx: HookContext, cls: Class<*>): Boolean {
        val methods = runCatching { cls.declaredMethods }.getOrNull() ?: return false
        val execute = methods.firstOrNull { m ->
            m.name == "execute" &&
                m.parameterTypes.isEmpty() &&
                m.returnType != Void.TYPE &&
                !Modifier.isStatic(m.modifiers)
        } ?: return false

        // 找到 execute 了，hook 它
        val startTimes = HashMap<Int, Long>()
        XposedBridge.hookMethod(execute, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                startTimes[System.identityHashCode(param.thisObject)] = System.currentTimeMillis()
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                val resp = param.result ?: return
                val start = startTimes.remove(System.identityHashCode(param.thisObject)) ?: 0L
                // 读响应体后返回「替换后的 response」（body 已换成可重复读副本）
                val newResp = emitOkHttp(ctx, resp, System.currentTimeMillis() - start)
                // 写回替换后的 response，让业务代码拿到完整 body
                if (newResp != null) param.result = newResp
            }
        })
        return true
    }

    /** 尝试 hook 某类的 enqueue(Callback) 方法（1 个参数）。 */
    private fun hookEnqueueMethod(ctx: HookContext, cls: Class<*>): Boolean {
        val methods = runCatching { cls.declaredMethods }.getOrNull() ?: return false
        val enqueue = methods.firstOrNull { m ->
            m.name == "enqueue" && m.parameterTypes.size == 1
        } ?: return false

        XposedBridge.hookMethod(enqueue, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                // enqueue 的回调参数，包装一下，在 onResponse 时抓取
                val callback = param.args.getOrNull(0) ?: return
                wrapCallback(ctx, callback)
            }
        })
        return true
    }

    /** 包装 Callback，在 onResponse 时抓数据。 */
    private fun wrapCallback(ctx: HookContext, callback: Any) {
        val cbCls = callback.javaClass
        val methods = cbCls.declaredMethods
        // 找 onResponse(Call, Response) 或 onResponse(某类, 某类)
        val onResponse = methods.firstOrNull { it.name == "onResponse" && it.parameterTypes.size == 2 }
            ?: return

        XposedBridge.hookMethod(onResponse, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val resp = param.args.getOrNull(1) ?: return
                val newResp = emitOkHttp(ctx, resp, 0L)
                if (newResp != null) param.args[1] = newResp
            }
        })
    }

    /**
     * 提取响应信息（通过反射，不依赖具体类名）。
     *
     * 只通过方法名反射取值：request() / url() / method() / code() / headers() / body()。
     */
    /**
     * 提取响应信息，并返回「替换了 body 的新 response」。
     *
     * 【为什么必须替换 body】
     * OkHttp 的 ResponseBody 是一次性流，调用 bytes()/string() 会消费掉内容。
     * 如果只读不换，业务代码再读 body 时拿到的是空的 ——
     * 表现为「目标应用页面加载不出内容」。
     *
     * 所以这里读完内容后，用 ResponseBody.create() 造一个可重复读的副本，
     * 通过 newBuilder().body(newBody).build() 替换，返回新 response。
     *
     * @return 替换后的 response（body 已是可重复读副本）；失败返回 null
     */
    private fun emitOkHttp(ctx: HookContext, response: Any, durationMs: Long): Any? {
        return runCatching {
            // 反射取 request()
            val request = callMethod(response, "request") ?: return null
            val url = callMethod(request, "url")?.toString() ?: return null
            val method = callMethod(request, "method") as? String ?: "GET"
            val code = callMethod(response, "code") as? Int ?: 0

            // headers
            val reqHeaders = readHeaders(callMethod(request, "headers"))
            val respHeaders = readHeaders(callMethod(response, "headers"))

            // body：读 + 替换
            val reqBody = readRequestBody(request)
            val (respBody, newResponse) = readAndReplaceResponseBody(response)

            ctx.reporter.http(method, url, code, reqHeaders, respHeaders, reqBody, respBody, durationMs)

            // 返回替换后的 response（可能为 null，表示替换失败，保持原样）
            newResponse ?: response
        }.onFailure {
            XposedBridge.log("[HttpCapture] emitOkHttp 失败: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()
    }

    /** 反射调用一个无参方法（递归在父类/接口找）。 */
    private fun callMethod(obj: Any, name: String): Any? {
        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            runCatching {
                val m = cls.getDeclaredMethod(name)
                m.isAccessible = true
                return m.invoke(obj)
            }
            cls = cls.superclass
        }
        return null
    }

    /** 读 Headers（通过 name(int)/value(int)/size() 反射）。 */
    private fun readHeaders(headers: Any?): List<Pair<String, String>> {
        if (headers == null) return emptyList()
        return runCatching {
            val cls = headers.javaClass
            val size = cls.getMethod("size").invoke(headers) as Int
            val nameM = cls.getMethod("name", Int::class.javaPrimitiveType)
            val valueM = cls.getMethod("value", Int::class.javaPrimitiveType)
            (0 until size).map { i ->
                (nameM.invoke(headers, i) as String) to (valueM.invoke(headers, i) as String)
            }
        }.getOrDefault(emptyList())
    }

    /** 读请求体（通用：writeTo(Buffer)）。 */
    private fun readRequestBody(request: Any): ByteArray = runCatching {
        val body = callMethod(request, "body") ?: return ByteArray(0)
        val bodyCls = body.javaClass
        // 找 writeTo 方法（1 个参数，参数类型是 okio Buffer）
        val writeTo = bodyCls.methods.firstOrNull { it.name == "writeTo" && it.parameterTypes.size == 1 }
            ?: return ByteArray(0)
        val bufferType = writeTo.parameterTypes[0]
        val buffer = bufferType.getDeclaredConstructor().newInstance()
        writeTo.invoke(body, buffer)
        // 从 Buffer 读字节
        val readByteArray = bufferType.getMethod("readByteArray")
        readByteArray.invoke(buffer) as? ByteArray ?: ByteArray(0)
    }.getOrDefault(ByteArray(0))

    /**
     * 读响应体，并把 response 的 body 替换成「可重复读的副本」。
     *
     * 返回 (响应体内容, 替换后的 response)。
     * 替换失败时第二个返回 null（调用方保持原 response）。
     */
    private fun readAndReplaceResponseBody(response: Any): Pair<ByteArray, Any?> {
        return runCatching {
            val body = callMethod(response, "body") ?: return Pair(ByteArray(0), null)
            val bodyCls = body.javaClass

            // 1) 读内容（bytes() 优先，会消费原流）
            val bytes = runCatching {
                bodyCls.getMethod("bytes").invoke(body) as? ByteArray
            }.getOrNull()
            val content = bytes ?: runCatching {
                (bodyCls.getMethod("string").invoke(body) as? String)?.toByteArray()
            }.getOrNull() ?: ByteArray(0)

            // 2) 造一个可重复读的新 body
            val newBody = runCatching {
                val contentType = callMethod(body, "contentType")
                // 用 body 类自己的静态 create 方法（混淆后方法名可能变，但 create 通常在 ResponseBody 上）
                // 先试 ResponseBody.create(MediaType, byte[])，再试静态 create(MediaType, byte[])
                createResponseBody(bodyCls, contentType, content)
            }.getOrNull()

            if (newBody == null) {
                // 替换失败，只返回内容
                XposedBridge.log("[HttpCapture] ✗ create body 失败 bodyCls=${bodyCls.name} content=${content.size}B")
                return Pair(content, null)
            }

            // 3) 用 newBuilder().body(newBody).build() 替换
            val newResponse = runCatching {
                val builder = callMethod(response, "newBuilder")
                    ?: return@runCatching null
                // builder.body(newBody)
                val bodySetter = builder.javaClass.methods.firstOrNull { m ->
                    m.name == "body" && m.parameterTypes.size == 1
                }
                bodySetter?.invoke(builder, newBody)
                callMethod(builder, "build")
            }.getOrNull()

            if (newResponse == null) {
                XposedBridge.log("[HttpCapture] ✗ newBuilder/body/build 替换失败 content=${content.size}B")
            }
            Pair(content, newResponse)
        }.getOrElse { Pair(ByteArray(0), null) }
    }

    /**
     * 反射创建 ResponseBody.create(MediaType, byte[])。
     *
     * 【关键】create 是抽象类 okhttp3.ResponseBody 的静态方法，
     * 不在具体实现类（RealResponseBody 等）上。
     * 所以要在「ResponseBody 抽象类」上找，而不是 body 的具体类型。
     *
     * 未混淆时直接用 okhttp3.ResponseBody；混淆时从 body 类往上找父类。
     */
    private fun createResponseBody(bodyCls: Class<*>, contentType: Any?, bytes: ByteArray): Any? {
        // 候选类：okhttp3.ResponseBody（未混淆）+ body 的父类链（混淆时）
        val candidates = ArrayList<Class<*>>()
        runCatching {
            Class.forName("okhttp3.ResponseBody", false, bodyCls.classLoader)?.let { candidates.add(it) }
        }
        var cls: Class<*>? = bodyCls
        while (cls != null && cls != Any::class.java) {
            if (cls != bodyCls) candidates.add(cls)
            cls = cls.superclass
        }

        for (c in candidates) {
            // 遍历所有 create 静态方法，打印签名帮助诊断
            val creates = runCatching {
                c.methods.filter { m ->
                    java.lang.reflect.Modifier.isStatic(m.modifiers) && m.name == "create"
                }
            }.getOrNull() ?: continue

            for (create in creates) {
                XposedBridge.log("[HttpCapture] 找到 create: ${c.name}.${create.name}(${create.parameterTypes.joinToString(",") { it.simpleName }})")
                val r = runCatching {
                    // 按参数个数和类型尝试调用
                    when (create.parameterTypes.size) {
                        1 -> {
                            // create(String) 或 create(byte[])？试 byte[]
                            if (create.parameterTypes[0] == ByteArray::class.java) {
                                create.invoke(null, bytes)
                            } else {
                                create.invoke(null, String(bytes))
                            }
                        }
                        2 -> create.invoke(null, contentType, bytes)
                        else -> null
                    }
                }.getOrNull()
                if (r != null) return r
            }
        }
        XposedBridge.log("[HttpCapture] ✗ 所有 create 调用都失败 bodyCls=${bodyCls.name}")
        return null
    }

    // ---------------- HttpURLConnection ----------------

    private fun installUrlConnection(ctx: HookContext) {
        val cls = ctx.findClassOrNull("java.net.HttpURLConnection") ?: return
        val startTimes = HashMap<Int, Long>()

        runCatching {
            XposedBridge.hookAllMethods(cls, "getInputStream", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val conn = param.thisObject ?: return
                    startTimes[System.identityHashCode(conn)] = System.currentTimeMillis()
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val conn = param.thisObject ?: return
                    val original = param.result as? InputStream ?: return
                    val k = System.identityHashCode(conn)
                    val start = startTimes.remove(k) ?: 0L

                    val url = runCatching {
                        conn.javaClass.getMethod("getURL").invoke(conn).toString()
                    }.getOrNull() ?: ""
                    val method = runCatching {
                        conn.javaClass.getMethod("getRequestMethod").invoke(conn) as? String
                    }.getOrNull() ?: "GET"
                    val code = runCatching {
                        conn.javaClass.getMethod("getResponseCode").invoke(conn) as? Int
                    }.getOrNull() ?: 0

                    val tee = TeeInputStream(original) { captured ->
                        if (captured.isNotEmpty()) {
                            ctx.reporter.http(
                                method = method, url = url, statusCode = code,
                                requestHeaders = emptyList(), responseHeaders = emptyList(),
                                requestBody = ByteArray(0), responseBody = captured,
                                durationMs = if (start > 0) System.currentTimeMillis() - start else 0L,
                            )
                        }
                    }
                    param.result = tee
                }
            })
        }
    }

    // ---------------- WebView ----------------

    private fun installWebView(ctx: HookContext) {
        val cls = ctx.findClassOrNull("android.webkit.WebViewClient") ?: return
        runCatching {
            XposedBridge.hookAllMethods(cls, "shouldInterceptRequest", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val req = param.args.getOrNull(1) ?: return
                    val url = runCatching {
                        req.javaClass.getMethod("getUrl").invoke(req).toString()
                    }.getOrNull() ?: return
                    ctx.reporter.http("GET", url, 0, emptyList(), emptyList(), ByteArray(0), ByteArray(0), 0L)
                }
            })
        }
    }
}

/**
 * 旁路复制流。
 */
internal class TeeInputStream(
    private val delegate: InputStream,
    private val onComplete: (ByteArray) -> Unit,
) : InputStream() {

    private val captured = java.io.ByteArrayOutputStream()
    private var reported = false

    override fun read(): Int {
        val b = delegate.read()
        if (b >= 0) {
            if (captured.size() < MAX_CAPTURE) captured.write(b)
        } else {
            report()
        }
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = delegate.read(b, off, len)
        if (n > 0) {
            if (captured.size() < MAX_CAPTURE) {
                captured.write(b, off, minOf(n, MAX_CAPTURE - captured.size()))
            }
        } else if (n < 0) {
            report()
        }
        return n
    }

    override fun close() {
        report()
        delegate.close()
    }

    override fun available(): Int = delegate.available()

    private fun report() {
        if (reported) return
        reported = true
        runCatching { onComplete(captured.toByteArray()) }
    }

    companion object {
        private const val MAX_CAPTURE = 512 * 1024
    }
}
