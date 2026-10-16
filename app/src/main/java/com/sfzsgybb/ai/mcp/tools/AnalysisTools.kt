package com.sfzsgybb.ai.mcp.tools

import com.sfzsgybb.ai.capture.AnalysisRepository
import com.sfzsgybb.ai.capture.CaptureRepository
import com.sfzsgybb.ai.mcp.Args
import com.sfzsgybb.ai.mcp.McpToolHandler
import com.sfzsgybb.ai.mcp.Schema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 抓包查询与分析工具（移植自 ProxyPin 的 mcp_tools.dart）。
 *
 * 分四组：
 *  - 查询：get_request_list / get_request_detail / get_request_body / search_requests
 *  - 统计：get_request_stats / get_domain_summary
 *  - 分析：get_cookie_info / compare_requests / analyze_encrypted_content
 *          find_sensitive_data / analyze_auth / extract_api_endpoints
 *  - 生成：generate_code
 */

// ==================== 查询 ====================

class GetRequestListTool(
    private val analysis: AnalysisRepository,
    private val capture: CaptureRepository,
) : McpToolHandler {
    override val name = "get_request_list"
    override val description =
        "获取抓包请求列表。返回请求摘要（方法、URL、状态码、耗时等），" +
            "支持按域名、方法、状态码、URL 关键词过滤与分页。"

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "sessionId" to Schema.string("会话 id，留空则查询所有会话"),
            "domain" to Schema.string("按域名过滤（模糊匹配），如 api.example.com"),
            "method" to Schema.string("按 HTTP 方法过滤，如 GET / POST"),
            "statusCode" to Schema.integer("按响应状态码过滤，如 200 / 404 / 500"),
            "keyword" to Schema.string("按 URL 关键词搜索（模糊匹配）"),
            "offset" to Schema.integer("分页偏移，默认 0", minimum = 0, default = 0),
            "limit" to Schema.integer("每页数量，默认 50，最大 200", minimum = 1, maximum = 200, default = 50),
        ),
    )

    override suspend fun execute(args: JsonObject): String {
        val a = Args(args)
        val limit = (a.int("limit", 50) ?: 50).coerceIn(1, 200)
        val offset = (a.int("offset", 0) ?: 0).coerceAtLeast(0)
        val (rows, total) = analysis.list(
            sessionId = a.string("sessionId"),
            domain = a.string("domain"),
            method = a.string("method"),
            statusCode = a.int("statusCode"),
            urlKeyword = a.string("keyword"),
            offset = offset,
            limit = limit,
        )
        return buildJsonObject {
            put("total", total)
            put("offset", offset)
            put("limit", limit)
            putJsonArray("requests") {
                rows.forEach { r ->
                    add(buildJsonObject {
                        put("requestId", r.id)
                        put("method", r.method)
                        put("url", r.url)
                        put("host", r.host)
                        put("statusCode", r.statusCode)
                        put("durationMs", r.durationMs)
                        put("requestSize", r.requestSize)
                        put("responseSize", r.responseSize)
                        put("contentType", r.mimeType)
                        put("time", r.time)
                    })
                }
            }
        }.toString()
    }
}

class GetRequestDetailTool(private val analysis: AnalysisRepository) : McpToolHandler {
    override val name = "get_request_detail"
    override val description =
        "获取某个请求的完整详情：请求头、请求体、响应头、响应体、耗时等。需要 requestId。"

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "requestId" to Schema.string("请求 ID，从 get_request_list 获取"),
            "includeBody" to Schema.boolean("是否包含请求体与响应体", default = true),
            "maxBodySize" to Schema.integer("body 最大返回字符数", minimum = 100, default = 10000),
        ),
        required = listOf("requestId"),
    )

    override suspend fun execute(args: JsonObject): String {
        val a = Args(args)
        val id = a.string("requestId", required = true)!!.toLongOrNull()
            ?: return err("requestId 必须是数字")
        val includeBody = a.bool("includeBody", true)
        val maxBody = (a.int("maxBodySize", 10000) ?: 10000).coerceAtLeast(100)

        // 【D1 修复】用 metaById（无 BLOB）取元数据，正文用 substr 切片 ——
        // 单行 3.57MB 的 BLOB 直接进 CursorWindow 会抛 SQLiteBlobTooBigException。
        val r = analysis.metaById(id) ?: return err("未找到 requestId=$id 的请求")
        val reqBody = if (includeBody) analysis.bodySlice(id, 0, maxBody, fromResponse = false) ?: ByteArray(0)
        else ByteArray(0)
        val respBody = if (includeBody) analysis.bodySlice(id, 0, maxBody, fromResponse = true) ?: ByteArray(0)
        else ByteArray(0)

        return buildJsonObject {
            put("requestId", r.id)
            put("method", r.method)
            put("url", r.url)
            put("host", r.host)
            put("path", r.path)
            put("httpVersion", r.httpVersion)
            put("statusCode", r.statusCode)
            put("durationMs", r.durationMs)
            put("requestTime", r.time)
            putJsonArray("requestHeaders") {
                r.requestHeaders.forEach { add(buildJsonObject { put("name", it.name); put("value", it.value) }) }
            }
            putJsonArray("responseHeaders") {
                r.responseHeaders.forEach { add(buildJsonObject { put("name", it.name); put("value", it.value) }) }
            }
            put("requestSize", r.requestSize)
            put("responseSize", r.responseSize)
            if (includeBody) {
                put("requestBody", analysis.decodeText(reqBody).take(maxBody))
                put("responseBody", analysis.decodeText(respBody).take(maxBody))
                put("requestBodyTruncated", r.requestSize > maxBody)
                put("responseBodyTruncated", r.responseSize > maxBody)
            }
        }.toString()
    }

    private fun err(msg: String) = buildJsonObject { put("error", msg) }.toString()
}

class GetRequestBodyTool(private val analysis: AnalysisRepository) : McpToolHandler {
    override val name = "get_request_body"
    override val description = "单独获取某个请求的请求体或响应体完整内容，适合查看大体积内容。"

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "requestId" to Schema.string("请求 ID"),
            "type" to Schema.string("获取哪一侧", enum = listOf("request", "response"), default = "response"),
            "maxSize" to Schema.integer("最大返回字符数", minimum = 100, default = 50000),
        ),
        required = listOf("requestId"),
    )

    override suspend fun execute(args: JsonObject): String {
        val a = Args(args)
        val id = a.string("requestId", required = true)!!.toLongOrNull()
            ?: return buildJsonObject { put("error", "requestId 必须是数字") }.toString()
        val type = a.string("type", false)?.lowercase() ?: "response"
        val maxSize = (a.int("maxSize", 50000) ?: 50000).coerceAtLeast(100)

        // 【D1 修复】meta + substr 切片，BLOB 不进 CursorWindow
        val meta = analysis.metaById(id)
            ?: return buildJsonObject { put("error", "未找到该请求") }.toString()
        val fromResponse = type == "response"
        val totalSize = if (fromResponse) meta.responseSize else meta.requestSize
        val raw = analysis.bodySlice(id, 0, maxSize, fromResponse)
            ?: ByteArray(0)
        val text = analysis.decodeText(raw)

        return buildJsonObject {
            put("requestId", id)
            put("type", type)
            put("size", totalSize)
            put("returned", minOf(text.length, maxSize))
            put("truncated", totalSize > maxSize)
            put("content", text.take(maxSize))
        }.toString()
    }
}

class SearchRequestsTool(private val analysis: AnalysisRepository) : McpToolHandler {
    override val name = "search_requests"
    override val description =
        "高级搜索抓包请求。支持 URL 关键词、请求/响应体内容、Header 关键词、时间范围组合搜索。"

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "sessionId" to Schema.string("会话 id，留空则搜索所有"),
            "urlKeyword" to Schema.string("URL 中包含的关键词"),
            "bodyKeyword" to Schema.string("请求体或响应体中包含的关键词"),
            "headerKeyword" to Schema.string("请求头或响应头中包含的关键词"),
            "startTime" to Schema.string("起始时间戳（毫秒）"),
            "endTime" to Schema.string("结束时间戳（毫秒）"),
            "limit" to Schema.integer("最大返回数量", minimum = 1, maximum = 200, default = 50),
        ),
    )

    override suspend fun execute(args: JsonObject): String {
        val a = Args(args)
        val limit = (a.int("limit", 50) ?: 50).coerceIn(1, 200)
        val urlKw = a.string("urlKeyword")?.lowercase()
        val bodyKw = a.string("bodyKeyword")?.lowercase()
        val headerKw = a.string("headerKeyword")?.lowercase()
        val startTime = a.string("startTime")?.toLongOrNull() ?: 0L
        val endTime = a.string("endTime")?.toLongOrNull() ?: 0L
        val sessionId = a.string("sessionId") ?: ""

        val records = analysis.scan(sessionId, startTime, endTime, 2000)
        val hits = records.filter { r ->
            val urlOk = urlKw == null || r.url.lowercase().contains(urlKw)
            val headerOk = headerKw == null || r.requestHeaders.any {
                it.name.lowercase().contains(headerKw) || it.value.lowercase().contains(headerKw)
            } || r.responseHeaders.any {
                it.name.lowercase().contains(headerKw) || it.value.lowercase().contains(headerKw)
            }
            val bodyOk = bodyKw == null ||
                analysis.decodeTextForSearch(r.requestBody).lowercase().contains(bodyKw) ||
                analysis.decodeTextForSearch(r.responseBody).lowercase().contains(bodyKw)
            urlOk && headerOk && bodyOk
        }.take(limit)

        return buildJsonObject {
            put("count", hits.size)
            putJsonArray("requests") {
                hits.forEach { r ->
                    add(buildJsonObject {
                        put("requestId", r.id)
                        put("method", r.method)
                        put("url", r.url)
                        put("statusCode", r.statusCode)
                        put("time", r.time)
                    })
                }
            }
        }.toString()
    }
}

// ==================== 统计 ====================

class GetRequestStatsTool(private val analysis: AnalysisRepository) : McpToolHandler {
    override val name = "get_request_stats"
    override val description =
        "获取抓包统计摘要：总请求数、域名分布、状态码分布、方法分布、平均耗时等分析数据。"

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "sessionId" to Schema.string("会话 id，留空则统计全部"),
            "domain" to Schema.string("只统计指定域名（模糊匹配）"),
        ),
    )

    override suspend fun execute(args: JsonObject): String {
        val a = Args(args)
        val s = analysis.stats(a.string("sessionId"), a.string("domain"))
        return buildJsonObject {
            put("totalRequests", s.total)
            putJsonObject("domainDistribution") {
                s.domainDist.forEach { put(it.first.ifBlank { "unknown" }, it.second) }
            }
            putJsonObject("statusCodeDistribution") {
                s.statusDist.forEach { put(it.first.toString(), it.second) }
            }
            putJsonObject("methodDistribution") {
                s.methodDist.forEach { put(it.first.ifBlank { "UNKNOWN" }, it.second) }
            }
            putJsonObject("costTimeStats") {
                put("avgMs", s.avgMs.toLong())
                put("maxMs", s.maxMs)
                put("minMs", s.minMs)
                put("sampleCount", s.sampleCount)
            }
        }.toString()
    }
}

class GetDomainSummaryTool(private val analysis: AnalysisRepository) : McpToolHandler {
    override val name = "get_domain_summary"
    override val description =
        "按域名分组汇总：请求数、接口路径列表（去重）、方法分布、Content-Type 分布、平均耗时。"

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "sessionId" to Schema.string("会话 id"),
            "domain" to Schema.string("只查看指定域名（模糊匹配）"),
            "topN" to Schema.integer("返回请求数最多的前 N 个域名", minimum = 1, maximum = 100, default = 20),
        ),
    )

    override suspend fun execute(args: JsonObject): String {
        val a = Args(args)
        val topN = (a.int("topN", 20) ?: 20).coerceIn(1, 100)
        val list = analysis.domainSummary(a.string("sessionId"), a.string("domain"), topN)
        return buildJsonObject {
            put("domainCount", list.size)
            putJsonArray("domains") {
                list.forEach { d ->
                    add(buildJsonObject {
                        put("domain", d.host)
                        put("requestCount", d.count)
                        put("avgDurationMs", d.avgMs.toLong())
                        putJsonArray("paths") { d.paths.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                        putJsonObject("methods") { d.methods.forEach { put(it.first, it.second) } }
                        putJsonObject("contentTypes") { d.mimeTypes.forEach { put(it.first, it.second) } }
                    })
                }
            }
        }.toString()
    }
}
