package com.junge.aidepro.mcp.tools

import com.junge.aidepro.capture.AnalysisRepository
import com.junge.aidepro.capture.CaptureRepository
import com.junge.aidepro.mitm.InterceptEngine
import com.junge.aidepro.mcp.Args
import com.junge.aidepro.mcp.McpToolHandler
import com.junge.aidepro.mcp.Schema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * 改包类工具（移植自 ProxyPin）。
 *
 *  - add_breakpoint / list_breakpoints / remove_breakpoint
 *  - get_pending_intercepts / release_intercept
 *  - list_rewrite_rules / add_rewrite_rule / remove_rewrite_rule
 *  - replay_request
 *  - list_scripts / get_script_content / create_or_update_script
 */

// ==================== 断点 ====================

class AddBreakpointTool : McpToolHandler {
    override val name = "add_breakpoint"
    override val description =
        "添加断点拦截规则。匹配的请求/响应会被暂停，可通过 get_pending_intercepts 查看、" +
            "release_intercept 修改后放行。类似 Fiddler 的断点功能。"

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "url" to Schema.string("URL 正则表达式，如 .*api/login.*"),
            "name" to Schema.string("规则名称（可选）"),
            "interceptRequest" to Schema.boolean("是否拦截请求阶段", default = true),
            "interceptResponse" to Schema.boolean("是否拦截响应阶段", default = false),
            "method" to Schema.string("只匹配特定 HTTP 方法，留空匹配所有"),
        ),
        required = listOf("url"),
    )

    override suspend fun execute(args: JsonObject): String {
        val a = Args(args)
        val url = a.string("url", required = true)!!
        val idx = InterceptEngine.addBreakpoint(
            urlPattern = url,
            name = a.string("name") ?: "",
            interceptRequest = a.bool("interceptRequest", true),
            interceptResponse = a.bool("interceptResponse", false),
            method = a.string("method") ?: "",
        )
        return buildJsonObject {
            put("index", idx)
            put("url", url)
            put("added", true)
        }.toString()
    }
}

class ListBreakpointsTool : McpToolHandler {
    override val name = "list_breakpoints"
    override val description = "列出当前所有断点规则，含索引、URL 模式、拦截阶段。"

    override val inputSchema = Schema.obj(properties = emptyMap())

    override suspend fun execute(args: JsonObject): String {
        val list = InterceptEngine.listBreakpoints()
        return buildJsonObject {
            put("count", list.size)
            putJsonArray("breakpoints") {
                list.forEach { b ->
                    add(buildJsonObject {
                        put("index", b.index)
                        put("url", b.urlPattern)
                        put("name", b.name)
                        put("interceptRequest", b.interceptRequest)
                        put("interceptResponse", b.interceptResponse)
                        put("method", b.method)
                    })
                }
            }
        }.toString()
    }
}

class RemoveBreakpointTool : McpToolHandler {
    override val name = "remove_breakpoint"
    override val description = "删除指定索引的断点规则。索引来自 list_breakpoints。"

    override val inputSchema = Schema.obj(
        properties = mapOf("index" to Schema.integer("规则索引（从 0 开始）", minimum = 0)),
        required = listOf("index"),
    )

    override suspend fun execute(args: JsonObject): String {
        val idx = Args(args).int("index", required = true)!!
        val ok = InterceptEngine.removeBreakpoint(idx)
        return buildJsonObject {
            put("removed", ok)
            put("index", idx)
        }.toString()
    }
}

class GetPendingInterceptsTool : McpToolHandler {
    override val name = "get_pending_intercepts"
    override val description =
        "获取当前被断点暂停、等待放行的请求/响应列表。返回完整数据供查看后决定如何修改。"

    override val inputSchema = Schema.obj(properties = emptyMap())

    override suspend fun execute(args: JsonObject): String {
        val list = InterceptEngine.pendingList()
        return buildJsonObject {
            put("count", list.size)
            putJsonArray("pending") {
                list.forEach { p ->
                    add(buildJsonObject {
                        put("requestId", p.requestId)
                        put("stage", p.stage)
                        put("method", p.method)
                        put("url", p.url)
                        putJsonArray("requestHeaders") {
                            p.requestHeaders.forEach { add(buildJsonObject { put("name", it.first); put("value", it.second) }) }
                        }
                        put("requestBody", p.requestBody.take(10000))
                        if (p.stage == "response") {
                            put("statusCode", p.responseStatusCode)
                            putJsonArray("responseHeaders") {
                                p.responseHeaders.forEach { add(buildJsonObject { put("name", it.first); put("value", it.second) }) }
                            }
                            put("responseBody", p.responseBody.take(10000))
                        }
                        put("createdAt", p.createdAt)
                    })
                }
            }
        }.toString()
    }
}

class ReleaseInterceptTool : McpToolHandler {
    override val name = "release_intercept"
    override val description =
        "放行被断点拦截的请求/响应（可携带修改内容）。可改请求头、请求体、" +
            "响应状态码、响应头、响应体，也可选择直接中止。"

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "requestId" to Schema.string("要放行的 requestId，从 get_pending_intercepts 获取"),
            "abort" to Schema.boolean("是否中止（true = 不转发，直接拒绝）", default = false),
            "headers" to Schema.string("修改/新增的头，JSON 对象字符串，如 {\"X-Test\":\"1\"}"),
            "body" to Schema.string("修改后的体内容"),
            "statusCode" to Schema.integer("修改响应状态码（仅响应阶段有效）"),
        ),
        required = listOf("requestId"),
    )

    override suspend fun execute(args: JsonObject): String {
        val a = Args(args)
        val id = a.string("requestId", required = true)!!

        val headersJson = a.string("headers")
        val headers = if (headersJson.isNullOrBlank()) null else {
            runCatching {
                val obj = kotlinx.serialization.json.Json.parseToJsonElement(headersJson)
                    as kotlinx.serialization.json.JsonObject
                obj.mapValues { (it.value as? JsonPrimitive)?.content ?: "" }
            }.getOrNull()
        }

        val ok = InterceptEngine.release(
            id,
            InterceptEngine.Release(
                abort = a.bool("abort", false),
                headers = headers,
                body = a.string("body"),
                statusCode = a.int("statusCode"),
            )
        )
        return buildJsonObject {
            put("released", ok)
            put("requestId", id)
            if (!ok) put("hint", "该 requestId 不存在或已被放行")
        }.toString()
    }
}

// ==================== 重写规则 ====================

class ListRewriteRulesTool : McpToolHandler {
    override val name = "list_rewrite_rules"
    override val description = "列出当前所有 HTTP 重写规则（替换体、改 Header、重定向等）。"

    override val inputSchema = Schema.obj(properties = emptyMap())

    override suspend fun execute(args: JsonObject): String {
        val list = InterceptEngine.listRewriteRules()
        return buildJsonObject {
            put("count", list.size)
            putJsonArray("rules") {
                list.forEach { r ->
                    add(buildJsonObject {
                        put("index", r.index)
                        put("name", r.name)
                        put("url", r.urlPattern)
                        put("ruleType", r.ruleType.name.lowercase())
                        r.body?.let { put("body", it.take(2000)) }
                        r.statusCode?.let { put("statusCode", it) }
                        r.redirectUrl?.let { put("redirectUrl", it) }
                        putJsonObject("headers") { r.headers.forEach { (k, v) -> put(k, v) } }
                    })
                }
            }
        }.toString()
    }
}

class AddRewriteRuleTool : McpToolHandler {
    override val name = "add_rewrite_rule"
    override val description =
        "添加 HTTP 重写规则。类型：requestReplace、responseReplace、" +
            "requestUpdate、responseUpdate、redirect。"

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "url" to Schema.string("URL 匹配模式（支持 * 通配符），如 *.api.example.com/v2/user*"),
            "ruleType" to Schema.string(
                "规则类型",
                enum = listOf("requestReplace", "responseReplace", "requestUpdate", "responseUpdate", "redirect"),
            ),
            "name" to Schema.string("规则名称（可选）"),
            "body" to Schema.string("替换的请求体或响应体内容"),
            "statusCode" to Schema.integer("替换的响应状态码（仅 responseReplace）"),
            "headers" to Schema.string("要替换/新增的头，JSON 对象字符串"),
            "redirectUrl" to Schema.string("重定向目标（仅 redirect 类型）"),
        ),
        required = listOf("url", "ruleType"),
    )

    override suspend fun execute(args: JsonObject): String {
        val a = Args(args)
        val url = a.string("url", required = true)!!
        val typeStr = a.string("ruleType", required = true)!!

        val type = runCatching {
            InterceptEngine.RewriteType.valueOf(
                when (typeStr.lowercase()) {
                    "requestreplace" -> "REQUEST_REPLACE"
                    "responsereplace" -> "RESPONSE_REPLACE"
                    "requestupdate" -> "REQUEST_UPDATE"
                    "responseupdate" -> "RESPONSE_UPDATE"
                    "redirect" -> "REDIRECT"
                    else -> typeStr.uppercase()
                }
            )
        }.getOrNull() ?: return buildJsonObject {
            put("error", "不支持的 ruleType: $typeStr")
        }.toString()

        val headersJson = a.string("headers")
        val headers = if (headersJson.isNullOrBlank()) emptyMap() else {
            runCatching {
                val obj = kotlinx.serialization.json.Json.parseToJsonElement(headersJson)
                    as kotlinx.serialization.json.JsonObject
                obj.mapValues { (it.value as? JsonPrimitive)?.content ?: "" }
            }.getOrDefault(emptyMap())
        }

        val idx = InterceptEngine.addRewriteRule(
            name = a.string("name") ?: "",
            urlPattern = url,
            ruleType = type,
            body = a.string("body"),
            statusCode = a.int("statusCode"),
            headers = headers,
            redirectUrl = a.string("redirectUrl"),
        )
        return buildJsonObject {
            put("index", idx)
            put("url", url)
            put("ruleType", typeStr)
            put("added", true)
        }.toString()
    }
}

class RemoveRewriteRuleTool : McpToolHandler {
    override val name = "remove_rewrite_rule"
    override val description = "删除指定索引的重写规则。索引来自 list_rewrite_rules。"

    override val inputSchema = Schema.obj(
        properties = mapOf("index" to Schema.integer("规则索引（从 0 开始）", minimum = 0)),
        required = listOf("index"),
    )

    override suspend fun execute(args: JsonObject): String {
        val idx = Args(args).int("index", required = true)!!
        val ok = InterceptEngine.removeRewriteRule(idx)
        return buildJsonObject { put("removed", ok); put("index", idx) }.toString()
    }
}

// ==================== 重放 ====================

class ReplayRequestTool(private val analysis: AnalysisRepository) : McpToolHandler {
    override val name = "replay_request"
    override val description =
        "重放已捕获的 HTTP 请求。可在重放时覆盖请求头或请求体做改包测试，" +
            "返回实际的响应结果（状态码、响应头、响应体）。"

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "requestId" to Schema.string("要重放的请求 ID"),
            "headers" to Schema.string("覆盖请求头，JSON 对象字符串"),
            "body" to Schema.string("覆盖请求体内容"),
            "timeoutSeconds" to Schema.integer("超时秒数", minimum = 1, maximum = 120, default = 30),
        ),
        required = listOf("requestId"),
    )

    override suspend fun execute(args: JsonObject): String {
        val a = Args(args)
        val id = a.string("requestId", required = true)!!.toLongOrNull()
            ?: return buildJsonObject { put("error", "requestId 必须是数字") }.toString()
        val timeout = (a.int("timeoutSeconds", 30) ?: 30).coerceIn(1, 120) * 1000

        val r = analysis.byId(id)
            ?: return buildJsonObject { put("error", "未找到 requestId=$id") }.toString()

        val overrideHeaders = a.string("headers")?.let { h ->
            runCatching {
                val obj = kotlinx.serialization.json.Json.parseToJsonElement(h)
                    as kotlinx.serialization.json.JsonObject
                obj.mapValues { (it.value as? JsonPrimitive)?.content ?: "" }
            }.getOrNull()
        }
        val overrideBody = a.string("body")

        return runCatching {
            val conn = (URL(r.url).openConnection() as HttpURLConnection).apply {
                requestMethod = r.method
                connectTimeout = timeout
                readTimeout = timeout
                instanceFollowRedirects = false
                // 信任所有证书：重放是本地调试行为
                if (this is HttpsURLConnection) {
                    sslSocketFactory = trustAllContext().socketFactory
                    setSSLSocketFactory(sslSocketFactory)
                    hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                }
            }

            // 设置头（跳过会引发问题的）
            val skip = setOf("content-length", "host", "connection", "transfer-encoding")
            r.requestHeaders.forEach { h ->
                if (h.name.lowercase() !in skip) {
                    runCatching { conn.setRequestProperty(h.name, h.value) }
                }
            }
            overrideHeaders?.forEach { (k, v) ->
                runCatching { conn.setRequestProperty(k, v) }
            }

            // body
            val bodyBytes = (overrideBody ?: analysis.decodeText(r.requestBody))
                .takeIf { it.isNotBlank() }?.toByteArray()
            if (bodyBytes != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(bodyBytes) }
            }

            val code = conn.responseCode
            val respHeaders = conn.headerFields
                .filterKeys { it != null }
                .map { (k, v) -> k to v.joinToString(", ") }

            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val respBody = stream?.let { readAll(it) } ?: ByteArray(0)

            buildJsonObject {
                put("requestId", id)
                put("replayed", true)
                put("statusCode", code)
                put("statusMessage", conn.responseMessage ?: "")
                putJsonArray("responseHeaders") {
                    respHeaders.forEach { add(buildJsonObject { put("name", it.first); put("value", it.second) }) }
                }
                put("responseBody", analysis.decodeText(respBody).take(20000))
                put("responseSize", respBody.size)
            }.toString()
        }.getOrElse { t ->
            buildJsonObject {
                put("requestId", id)
                put("replayed", false)
                put("error", "${t.javaClass.simpleName}: ${t.message}")
            }.toString()
        }
    }

    private fun readAll(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun trustAllContext(): SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) = Unit
            override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }), java.security.SecureRandom())
    }
}

// ==================== 脚本管理 ====================

class ListScriptsTool(private val scripts: ScriptStore) : McpToolHandler {
    override val name = "list_scripts"
    override val description = "列出当前所有 JS 拦截脚本及其匹配的 URL 模式。"

    override val inputSchema = Schema.obj(properties = emptyMap())

    override suspend fun execute(args: JsonObject): String {
        val list = scripts.list()
        return buildJsonObject {
            put("count", list.size)
            putJsonArray("scripts") {
                list.forEach { s ->
                    add(buildJsonObject {
                        put("index", s.index)
                        put("name", s.name)
                        put("url", s.urlPattern)
                        put("enabled", s.enabled)
                        put("codeLength", s.code.length)
                    })
                }
            }
        }.toString()
    }
}

class GetScriptContentTool(private val scripts: ScriptStore) : McpToolHandler {
    override val name = "get_script_content"
    override val description = "获取指定脚本的完整 JavaScript 代码。"

    override val inputSchema = Schema.obj(
        properties = mapOf("index" to Schema.integer("脚本索引（从 0 开始）", minimum = 0)),
        required = listOf("index"),
    )

    override suspend fun execute(args: JsonObject): String {
        val idx = Args(args).int("index", required = true)!!
        val s = scripts.get(idx)
            ?: return buildJsonObject { put("error", "索引 $idx 不存在") }.toString()
        return buildJsonObject {
            put("index", s.index)
            put("name", s.name)
            put("url", s.urlPattern)
            put("code", s.code)
        }.toString()
    }
}

class CreateOrUpdateScriptTool(private val scripts: ScriptStore) : McpToolHandler {
    override val name = "create_or_update_script"
    override val description =
        "创建或更新 JS 拦截脚本。脚本需包含 onRequest(context, request) " +
            "和 onResponse(context, request, response) 函数。"

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "name" to Schema.string("脚本名称"),
            "url" to Schema.string("URL 匹配模式，支持 * 通配符，多个用逗号分隔"),
            "script" to Schema.string("JavaScript 代码"),
            "index" to Schema.integer("要更新的脚本索引（不填则新建）", minimum = 0),
        ),
        required = listOf("name", "url", "script"),
    )

    override suspend fun execute(args: JsonObject): String {
        val a = Args(args)
        val idx = scripts.save(
            index = a.int("index"),
            name = a.string("name", required = true)!!,
            urlPattern = a.string("url", required = true)!!,
            code = a.string("script", required = true)!!,
        )
        return buildJsonObject {
            put("index", idx)
            put("saved", true)
        }.toString()
    }
}

/** 脚本存储接口，由 UI 层实现（复用现有 Frida 脚本目录）。 */
interface ScriptStore {
    data class Item(val index: Int, val name: String, val urlPattern: String, val code: String, val enabled: Boolean)
    fun list(): List<Item>
    fun get(index: Int): Item?
    /** @return 脚本索引 */
    fun save(index: Int?, name: String, urlPattern: String, code: String): Int
}
