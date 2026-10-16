package com.sfzsgybb.ai.mcp.tools

import com.sfzsgybb.ai.capture.AnalysisRepository
import com.sfzsgybb.ai.mcp.Args
import com.sfzsgybb.ai.mcp.McpToolHandler
import com.sfzsgybb.ai.mcp.Schema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 安全分析类工具（移植自 ProxyPin）。
 *
 *  - get_cookie_info           Cookie 提取与分析
 *  - compare_requests          两请求差异对比
 *  - analyze_encrypted_content 加密/编码内容分析
 *  - find_sensitive_data       敏感信息扫描
 *  - analyze_auth              认证机制分析
 *  - extract_api_endpoints     API 端点归组
 *  - generate_code             生成可执行代码
 */

class GetCookieInfoTool(private val analysis: AnalysisRepository) : McpToolHandler {
    override val name = "get_cookie_info"
    override val description =
        "提取并分析指定域名的 Cookie。从请求头与响应头提取 Cookie/Set-Cookie，" +
            "并解析 HttpOnly、Secure、Max-Age、Domain、Path 等属性。"

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "sessionId" to Schema.string("会话 id，留空则查全部"),
            "domain" to Schema.string("目标域名（模糊匹配）"),
        ),
        required = listOf("domain"),
    )

    override suspend fun execute(args: JsonObject): String {
        val a = Args(args)
        val domain = a.string("domain", required = true)!!
        val list = analysis.cookies(a.string("sessionId"), domain)
        return buildJsonObject {
            put("domain", domain)
            put("count", list.size)
            putJsonArray("cookies") {
                list.forEach { c ->
                    add(buildJsonObject {
                        put("name", c.name)
                        put("value", c.value)
                        put("domain", c.domain)
                        put("path", c.path)
                        put("secure", c.secure)
                        put("httpOnly", c.httpOnly)
                        c.maxAge?.let { put("maxAge", it) }
                        put("source", c.from)
                    })
                }
            }
        }.toString()
    }
}

class CompareRequestsTool(private val analysis: AnalysisRepository) : McpToolHandler {
    override val name = "compare_requests"
    override val description =
        "对比两个请求的差异：URL、Header、Query 参数、请求体的不同之处。"

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "requestId1" to Schema.string("第一个请求的 ID"),
            "requestId2" to Schema.string("第二个请求的 ID"),
        ),
        required = listOf("requestId1", "requestId2"),
    )

    override suspend fun execute(args: JsonObject): String {
        val a = Args(args)
        val id1 = a.string("requestId1", required = true)!!.toLongOrNull()
            ?: return err("requestId1 必须是数字")
        val id2 = a.string("requestId2", required = true)!!.toLongOrNull()
            ?: return err("requestId2 必须是数字")

        val diffs = analysis.compare(id1, id2)
            ?: return err("未找到指定请求（id1=$id1, id2=$id2）")

        return buildJsonObject {
            put("requestId1", id1)
            put("requestId2", id2)
            put("diffCount", diffs.size)
            putJsonArray("differences") {
                diffs.forEach { d ->
                    add(buildJsonObject {
                        put("field", d.field)
                        put("value1", d.value1)
                        put("value2", d.value2)
                    })
                }
            }
        }.toString()
    }

    private fun err(m: String) = buildJsonObject { put("error", m) }.toString()
}

class AnalyzeEncryptedContentTool(private val analysis: AnalysisRepository) : McpToolHandler {
    override val name = "analyze_encrypted_content"
    override val description =
        "分析疑似加密/编码的内容。自动检测 Base64/Hex/URL 编码并尝试解码，" +
            "计算熵值与可打印比例，推测可能的加密算法或数据格式。"

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "requestId" to Schema.string("请求 ID，分析该请求的请求体与响应体"),
            "type" to Schema.string("分析目标", enum = listOf("request", "response", "both"), default = "both"),
            "rawContent" to Schema.string("直接传入待分析内容（与 requestId 二选一）"),
        ),
    )

    override suspend fun execute(args: JsonObject): String {
        val a = Args(args)
        val raw = a.string("rawContent")
        if (!raw.isNullOrBlank()) {
            return one(raw, "rawContent").toString()
        }

        val idStr = a.string("requestId") ?: return buildJsonObject {
            put("error", "需要提供 requestId 或 rawContent")
        }.toString()
        val id = idStr.toLongOrNull() ?: return buildJsonObject {
            put("error", "requestId 必须是数字")
        }.toString()

        // 【D1 修复】meta + substr 切片（最多 256KB 足够分析加密），
        // 大 BLOB 直接 byId 会把 CursorWindow 打爆
        val meta = analysis.metaById(id) ?: return buildJsonObject {
            put("error", "未找到 requestId=$id")
        }.toString()
        val MAX = 256 * 1024
        val reqBody = analysis.bodySlice(id, 0, MAX, fromResponse = false) ?: ByteArray(0)
        val respBody = analysis.bodySlice(id, 0, MAX, fromResponse = true) ?: ByteArray(0)

        val type = a.string("type", false)?.lowercase() ?: "both"
        return buildJsonObject {
            put("requestId", id)
            put("url", meta.url)
            if (type == "request" || type == "both") {
                put("requestBody", one(analysis.decodeText(reqBody), "requestBody"))
            }
            if (type == "response" || type == "both") {
                put("responseBody", one(analysis.decodeText(respBody), "responseBody"))
            }
        }.toString()
    }

    private fun one(text: String, label: String): JsonObject {
        if (text.isBlank()) {
            return buildJsonObject { put("label", label); put("empty", true) }
        }
        val ca = analysis.analyzeCrypto(text)
        return buildJsonObject {
            put("label", label)
            put("inputLength", text.length)
            put("inputPreview", ca.encoded)
            put("detectedEncoding", ca.decodeType)
            put("decodedLength", ca.decodedLength)
            put("entropy", (Math.round(ca.entropy * 100) / 100.0))
            put("printableRatio", (Math.round(ca.printableRatio * 100) / 100.0))
            putJsonArray("guesses") { ca.guessedTypes.forEach { add(JsonPrimitive(it)) } }
            put("contentPreview", ca.preview)
        }
    }
}

class FindSensitiveDataTool(private val analysis: AnalysisRepository) : McpToolHandler {
    override val name = "find_sensitive_data"
    override val description =
        "扫描抓包数据中的敏感信息：手机号、身份证、邮箱、JWT、Bearer Token、" +
            "API Key、密码字段、IP、银行卡、私钥等。适用于安全测试与数据泄漏检查。"

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "sessionId" to Schema.string("会话 id，留空则扫描全部"),
            "domain" to Schema.string("只扫描指定域名（模糊匹配）"),
            "requestId" to Schema.string("只扫描指定请求（与 domain 可二选一）"),
            "maxRecords" to Schema.integer("最多扫描多少条记录", minimum = 1, maximum = 5000, default = 500),
        ),
    )

    override suspend fun execute(args: JsonObject): String {
        val a = Args(args)
        val hits = analysis.scanSensitive(
            sessionId = a.string("sessionId"),
            domain = a.string("domain"),
            recordId = a.string("requestId")?.toLongOrNull(),
            maxRecords = (a.int("maxRecords", 500) ?: 500).coerceIn(1, 5000),
        )
        return buildJsonObject {
            put("hitCount", hits.size)
            putJsonObject("byType") {
                hits.groupingBy { it.type }.eachCount().forEach { (k, v) -> put(k, v) }
            }
            putJsonArray("findings") {
                hits.forEach { h ->
                    add(buildJsonObject {
                        put("type", h.type)
                        put("value", h.value)
                        put("requestId", h.recordId)
                        put("url", h.url)
                        put("location", h.location)
                    })
                }
            }
        }.toString()
    }
}

class AnalyzeAuthTool(private val analysis: AnalysisRepository) : McpToolHandler {
    override val name = "analyze_auth"
    override val description =
        "提取并分析认证信息：Authorization 头、Bearer Token、API Key、" +
            "Cookie 中的 Session/Token 等。用于逆向分析认证机制。"

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "sessionId" to Schema.string("会话 id，留空则分析全部"),
            "domain" to Schema.string("目标域名（模糊匹配）"),
            "maxRecords" to Schema.integer("最多分析多少条", minimum = 1, maximum = 5000, default = 500),
        ),
    )

    override suspend fun execute(args: JsonObject): String {
        val a = Args(args)
        val list = analysis.analyzeAuth(
            sessionId = a.string("sessionId"),
            domain = a.string("domain"),
            maxRecords = (a.int("maxRecords", 500) ?: 500).coerceIn(1, 5000),
        )
        return buildJsonObject {
            put("count", list.size)
            putJsonObject("byScheme") {
                list.groupingBy { it.scheme }.eachCount().forEach { (k, v) -> put(k, v) }
            }
            putJsonArray("credentials") {
                list.forEach { i ->
                    add(buildJsonObject {
                        put("host", i.host)
                        put("url", i.url)
                        put("scheme", i.scheme)
                        put("credentials", i.credentials)
                        put("requestId", i.recordId)
                    })
                }
            }
        }.toString()
    }
}

class ExtractApiEndpointsTool(private val analysis: AnalysisRepository) : McpToolHandler {
    override val name = "extract_api_endpoints"
    override val description =
        "提取并归组 API 端点。把路径中的 ID、UUID、哈希等动态片段替换成占位符，" +
            "统计每个接口的调用次数、状态码分布、Content-Type。适合逆向分析 API 设计。"

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "sessionId" to Schema.string("会话 id，留空则分析全部"),
            "domain" to Schema.string("目标域名（模糊匹配）"),
            "maxRecords" to Schema.integer("最多分析多少条", minimum = 1, maximum = 10000, default = 2000),
        ),
    )

    override suspend fun execute(args: JsonObject): String {
        val a = Args(args)
        val list = analysis.extractEndpoints(
            sessionId = a.string("sessionId"),
            domain = a.string("domain"),
            maxRecords = (a.int("maxRecords", 2000) ?: 2000).coerceIn(1, 10000),
        )
        return buildJsonObject {
            put("endpointCount", list.size)
            putJsonArray("endpoints") {
                list.forEach { e ->
                    add(buildJsonObject {
                        put("method", e.method)
                        put("host", e.host)
                        put("pattern", e.pattern)
                        put("callCount", e.count)
                        putJsonObject("statusCodes") { e.statusCodes.forEach { (k, v) -> put(k.toString(), v) } }
                        putJsonArray("contentTypes") { e.contentTypes.forEach { add(JsonPrimitive(it)) } }
                    })
                }
            }
        }.toString()
    }
}

class GenerateCodeTool(private val analysis: AnalysisRepository) : McpToolHandler {
    override val name = "generate_code"
    override val description =
        "根据抓包请求生成可执行代码片段。支持 python（requests）、javascript（fetch）、curl、go。"

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "requestId" to Schema.string("请求 ID"),
            "language" to Schema.string(
                "目标语言", enum = listOf("python", "javascript", "curl", "go"), default = "python",
            ),
        ),
        required = listOf("requestId"),
    )

    override suspend fun execute(args: JsonObject): String {
        val a = Args(args)
        val id = a.string("requestId", required = true)!!.toLongOrNull()
            ?: return buildJsonObject { put("error", "requestId 必须是数字") }.toString()
        val lang = a.string("language", false) ?: "python"

        val code = analysis.generateCode(id, lang)
            ?: return buildJsonObject {
                put("error", "无法生成代码（请求不存在或语言不支持: $lang）")
            }.toString()

        return buildJsonObject {
            put("requestId", id)
            put("language", lang)
            put("code", code)
        }.toString()
    }
}
