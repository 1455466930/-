package com.sfzsgybb.ai.mcp.tools

import com.sfzsgybb.ai.capture.CaptureRepository
import com.sfzsgybb.ai.data.model.LogGroup
import com.sfzsgybb.ai.data.model.Protocol
import com.sfzsgybb.ai.data.model.ValueMode
import com.sfzsgybb.ai.log.LogRepository
import com.sfzsgybb.ai.mcp.Args
import com.sfzsgybb.ai.mcp.Cursor
import com.sfzsgybb.ai.mcp.McpToolHandler
import com.sfzsgybb.ai.mcp.Schema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** 日志类 MCP 工具。 */

class ListLogsTool(private val repo: LogRepository) : McpToolHandler {
    override val name = "list_logs"
    override val description = "分页列出 Hook 日志。返回的 nextAfterId 请原样回传用于下一页。"
    override val paginated = true

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "limit" to Schema.integer("每页条数", minimum = 1, maximum = 300, default = 100),
            "afterId" to Schema.string("增量游标。首次传 0，之后原样回传 nextAfterId。", pattern = Cursor.AFTER_ID_PATTERN, default = "0"),
            "packageName" to Schema.string("限定应用包名，留空表示全部"),
            "group" to Schema.integer("日志分组：0默认 1加密 2网络 3存储 4UI 5系统", minimum = 0, maximum = 5),
            "query" to Schema.string("在日志名/类名/对象名/内容摘要中模糊搜索（大小写不敏感）"),
        ),
        required = emptyList(),
    )

    override suspend fun execute(args: JsonObject): String {
        val a = Args(args)
        val limit = a.int("limit", 100)!!.coerceIn(1, 300)
        val packageName = a.string("packageName")
        val group = a.int("group", -1)!!
        val query = a.string("query")

        return if (!query.isNullOrBlank()) {
            val rows = repo.search(query, limit + 1, 0)
            val page = rows.take(limit)
            jsonLogs(page, rows.size > limit)
        } else {
            val cur = Cursor.decode(a.string("afterId"))
            val rows = repo.page(packageName, group, cur.time, cur.id, limit + 1)
            val page = rows.take(limit)
            jsonLogs(page, rows.size > limit)
        }
    }

    private fun jsonLogs(rows: List<com.sfzsgybb.ai.data.model.LogRecord>, hasMore: Boolean): String =
        buildJsonObject {
            putJsonArray("logs") {
                rows.forEach { r ->
                    add(buildJsonObject {
                        put("id", r.id)
                        put("time", r.time)
                        put("logName", r.logName)
                        put("className", r.className)
                        put("objName", r.objName)
                        put("group", r.group)
                        put("size", r.size)
                        put("payloadBrief", r.payloadBrief)
                        put("resultBrief", r.resultBrief)
                        put("callStack", r.callStack)
                        r.packageName?.let { put("packageName", it) }
                    })
                }
            }
            put("hasMore", hasMore)
            put("nextAfterId", rows.lastOrNull()?.let { Cursor.encode(it.time, 'l', it.id) } ?: "0")
            put("count", rows.size)
        }.toString()
}

class GetLogTool(private val repo: LogRepository) : McpToolHandler {
    override val name = "get_log"
    override val description = "按 id 获取单条日志的完整内容，含原始载荷与结果。"

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "id" to Schema.string("日志 id，来自 list_logs", pattern = "^[1-9][0-9]{0,18}$"),
            "mode" to Schema.string("值渲染模式", enum = listOf("text", "hex", "base64"), default = "text"),
            "includeStack" to Schema.boolean("是否返回调用栈", default = true),
        ),
        required = listOf("id"),
    )

    override suspend fun execute(args: JsonObject): String {
        val a = Args(args)
        val id = a.string("id", required = true)!!.toLong()
        val mode = ValueMode.valueOf(a.string("mode", false)?.uppercase() ?: "TEXT")
        val withStack = a.bool("includeStack", true)

        val r = repo.byId(id) ?: return errorJson("record_not_found", "未找到 id=$id 的日志")
        return buildJsonObject {
            put("id", r.id)
            put("time", r.time)
            put("logName", r.logName)
            put("className", r.className)
            put("objName", r.objName)
            put("group", r.group)
            put("payload", repo.render(r.payload, mode))
            put("payloadSize", r.payload.size)
            putJsonArray("results") { r.results.forEach { add(kotlinx.serialization.json.JsonPrimitive(repo.render(it, mode))) } }
            if (withStack) put("callStack", r.callStack)
        }.toString()
    }

    private fun errorJson(code: String, msg: String) =
        buildJsonObject { put("error", code); put("message", msg) }.toString()
}

class SearchLogsTool(private val repo: LogRepository) : McpToolHandler {
    override val name = "search_logs"
    override val description = "按关键词搜索日志内容（含 payload/result 的文本、Hex、Base64 三种表示）。"

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "query" to Schema.string("搜索关键词", ),
            "limit" to Schema.integer("最大返回条数", minimum = 1, maximum = 300, default = 50),
            "packageName" to Schema.string("限定应用包名"),
        ),
        required = listOf("query"),
    )

    override suspend fun execute(args: JsonObject): String {
        val a = Args(args)
        val q = a.string("query", required = true)!!
        val limit = a.int("limit", 50)!!.coerceIn(1, 300)
        val rows = repo.search(q, limit, 0)
        return buildJsonObject {
            put("query", q)
            put("count", rows.size)
            putJsonArray("logs") {
                rows.forEach { r ->
                    add(buildJsonObject {
                        put("id", r.id)
                        put("logName", r.logName)
                        put("time", r.time)
                        put("payloadBrief", r.payloadBrief)
                        put("resultBrief", r.resultBrief)
                    })
                }
            }
        }.toString()
    }
}

class ClearLogsTool(private val repo: LogRepository) : McpToolHandler {
    override val name = "clear_logs"
    override val description = "清空日志。可按应用包名限定范围。"

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "packageName" to Schema.string("仅清空该应用的日志，留空表示全部"),
            "confirm" to Schema.boolean("必须显式传 true 才会执行", default = false),
        ),
        required = listOf("confirm"),
    )

    override suspend fun execute(args: JsonObject): String {
        val a = Args(args)
        if (!a.bool("confirm", false)) {
            return buildJsonObject { put("error", "confirmation_required"); put("message", "请传 confirm=true") }.toString()
        }
        val pkg = a.string("packageName")
        val n = repo.clear(pkg)
        return buildJsonObject { put("cleared", n); put("packageName", pkg ?: "*") }.toString()
    }
}

class GetLogGraphTool(private val repo: LogRepository) : McpToolHandler {
    override val name = "get_log_graph"
    override val description = "获取日志统计：按分组计数与高频日志名 Top N，用于分析调用分布。"

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "topN" to Schema.integer("高频日志名数量", minimum = 1, maximum = 50, default = 10),
            "packageName" to Schema.string("限定应用包名"),
        ),
    )

    override suspend fun execute(args: JsonObject): String {
        val a = Args(args)
        val topN = a.int("topN", 10)!!.coerceIn(1, 50)
        val pkg = a.string("packageName")
        val groups = repo.groupCounts(pkg)
        val top = repo.topLogNames(pkg, topN)
        val total = repo.count(pkg)
        return buildJsonObject {
            put("total", total)
            putJsonObject("byGroup") {
                groups.forEach { (g, c) ->
                    put(LogGroup.label(g), c)
                }
            }
            putJsonArray("topLogNames") {
                top.forEach { (n, c) ->
                    add(buildJsonObject { put("name", n); put("count", c) })
                }
            }
        }.toString()
    }
}

class GetLogTotalTool(private val repo: LogRepository) : McpToolHandler {
    override val name = "get_log_total"
    override val description = "获取日志总数，可按应用包名限定。"

    override val inputSchema = Schema.obj(
        properties = mapOf("packageName" to Schema.string("限定应用包名")),
    )

    override suspend fun execute(args: JsonObject): String {
        val pkg = Args(args).string("packageName")
        return buildJsonObject {
            put("total", repo.count(pkg))
            put("packageName", pkg ?: "*")
        }.toString()
    }
}

/** 抓包类 MCP 工具。 */

class CaptureStatusTool(private val repo: CaptureRepository) : McpToolHandler {
    override val name = "capture_status"
    override val description = "查询抓包服务状态。调用任何抓包工具前应先调本工具确认可用。"

    override val inputSchema = Schema.obj(properties = emptyMap())

    override suspend fun execute(args: JsonObject): String {
        val s = repo.status()
        return buildJsonObject {
            put("running", s.running)
            put("sessionId", s.sessionId ?: "")
            put("packageName", s.packageName ?: "")
            put("recordCount", s.recordCount)
            put("startedAt", s.startedAt)
            put("filterLocalRequests", s.filterLocalRequests)
        }.toString()
    }
}

class CaptureSessionsTool(private val repo: CaptureRepository) : McpToolHandler {
    override val name = "capture_sessions"
    override val description = "列出抓包会话，返回真实 sessionId 供其他抓包工具使用。"

    override val inputSchema = Schema.obj(
        properties = mapOf("limit" to Schema.integer("最大会话数", minimum = 1, maximum = 100, default = 20)),
    )

    override suspend fun execute(args: JsonObject): String {
        val limit = Args(args).int("limit", 20)!!.coerceIn(1, 100)
        val sessions = repo.sessions(limit)
        return buildJsonObject {
            putJsonArray("sessions") {
                sessions.forEach { s ->
                    add(buildJsonObject {
                        put("sessionId", s.sessionId)
                        put("packageName", s.packageName)
                        put("appLabel", s.appLabel)
                        put("state", s.state.name)
                        put("startedAt", s.startedAt)
                        put("recordCount", s.recordCount)
                    })
                }
            }
        }.toString()
    }
}

class CaptureStartStopTool(
    private val repo: CaptureRepository,
    private val start: Boolean,
) : McpToolHandler {
    override val name = if (start) "capture_start" else "capture_stop"
    override val description =
        if (start) "对指定应用启动抓包。" else "停止当前抓包会话。"

    override val inputSchema: JsonObject = if (start) {
        Schema.obj(
            properties = mapOf(
                "packageName" to Schema.string("目标应用包名"),
                "filterLocalRequests" to Schema.boolean("是否过滤本机回环请求", default = true),
            ),
            required = listOf("packageName"),
        )
    } else {
        Schema.obj(properties = mapOf("sessionId" to Schema.string("要停止的会话 id，留空停止当前会话")))
    }

    override suspend fun execute(args: JsonObject): String {
        val a = Args(args)
        return if (start) {
            val pkg = a.string("packageName", required = true)!!
            val filter = a.bool("filterLocalRequests", true)
            val s = repo.start(pkg, filter)
            buildJsonObject {
                put("sessionId", s.sessionId)
                put("packageName", s.packageName)
                put("started", true)
            }.toString()
        } else {
            val id = a.string("sessionId")
            val ok = repo.stop(id)
            buildJsonObject { put("stopped", ok); put("sessionId", id ?: "") }.toString()
        }
    }
}

class CaptureDeleteSessionsTool(private val repo: CaptureRepository) : McpToolHandler {
    override val name = "capture_delete_sessions"
    override val description = "删除指定会话及其全部流量记录。"

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "sessionIds" to Schema.array("要删除的会话 id 列表", Schema.string("sessionId")),
            "confirm" to Schema.boolean("必须显式传 true", default = false),
        ),
        required = listOf("sessionIds", "confirm"),
    )

    override suspend fun execute(args: JsonObject): String {
        val a = Args(args)
        if (!a.bool("confirm", false)) {
            return buildJsonObject { put("error", "confirmation_required") }.toString()
        }
        val ids = a.stringList("sessionIds")
        val n = repo.deleteSessions(ids)
        return buildJsonObject { put("deleted", n) }.toString()
    }
}

class NetworkListTool(private val repo: CaptureRepository) : McpToolHandler {
    override val name = "network_list"
    override val description = "列出 HTTP / TCP / WebSocket 混合流量。返回 nextAfterId 供翻页，请勿自行构造。"
    override val paginated = true

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "sessionId" to Schema.string("会话 id，来自 capture_sessions"),
            "limit" to Schema.integer("每页条数", minimum = 1, maximum = 300, default = 100),
            "afterId" to Schema.string("增量游标，首次传 0", pattern = Cursor.AFTER_ID_PATTERN, default = "0"),
            "query" to Schema.string("搜索关键词：匹配 URL、请求/响应头、TCP 收发载荷"),
            "id" to Schema.string("按连接 id 过滤（来自上一次返回）", pattern = Cursor.ID_PATTERN),
            "protocol" to Schema.string("按协议过滤", enum = listOf("HTTP", "WebSocket", "TCP", "UNKNOWN")),
        ),
        required = listOf("sessionId"),
    )

    override suspend fun execute(args: JsonObject): String {
        val a = Args(args)
        val sessionId = a.string("sessionId", required = true)!!
        val limit = a.int("limit", 100)!!.coerceIn(1, 300)
        val cur = Cursor.decode(a.string("afterId"))
        val query = a.string("query")
        val protoFilter = a.string("protocol")?.let { runCatching { Protocol.valueOf(it.uppercase()) }.getOrNull() }

        val rows = repo.networkList(sessionId, cur.time, cur.id, limit + 1, query, protoFilter)
        val page = rows.take(limit)
        return buildJsonObject {
            putJsonArray("items") {
                page.forEach { it ->
                    add(buildJsonObject {
                        put("id", it.id)
                        put("kind", it.kind)
                        put("time", it.time)
                        put("protocol", it.protocol)
                        it.method?.let { m -> put("method", m) }
                        it.url?.let { u -> put("url", u) }
                        it.statusCode?.let { c -> put("statusCode", c) }
                        it.remoteAddress?.let { r -> put("remoteAddress", r) }
                        put("size", it.size)
                        put("brief", it.brief)
                    })
                }
            }
            put("hasMore", rows.size > limit)
            put("nextAfterId", page.lastOrNull()?.let { Cursor.encode(it.time, it.kind.first().lowercaseChar(), it.id) } ?: "0")
            put("count", page.size)
        }.toString()
    }
}

class HttpInfoTool(private val repo: CaptureRepository) : McpToolHandler {
    override val name = "http_info"
    override val description = "获取 HTTP 请求/响应的元数据：方法、URL、状态码、全部请求与响应头。"

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "id" to Schema.string("来自 network_list 的 id，原样传入", pattern = Cursor.ID_PATTERN),
        ),
        required = listOf("id"),
    )

    override suspend fun execute(args: JsonObject): String {
        val id = Args(args).string("id", required = true)!!
        Cursor.validateId(id)
        val rowId = id.substringBefore('-').toLong()
        val r = repo.httpById(rowId)
            ?: return buildJsonObject { put("error", "record_not_found"); put("id", id) }.toString()
        return buildJsonObject {
            put("id", id)
            put("method", r.method)
            put("url", r.url)
            put("host", r.host)
            put("httpVersion", r.httpVersion)
            put("statusCode", r.statusCode)
            put("statusText", r.statusText)
            put("durationMs", r.durationMs)
            put("requestSize", r.requestSize)
            put("responseSize", r.responseSize)
            putJsonArray("requestHeaders") {
                r.requestHeaders.forEach { h -> add(buildJsonObject { put("name", h.name); put("value", h.value) }) }
            }
            putJsonArray("responseHeaders") {
                r.responseHeaders.forEach { h -> add(buildJsonObject { put("name", h.name); put("value", h.value) }) }
            }
        }.toString()
    }
}

class HttpBodyTool(private val repo: CaptureRepository) : McpToolHandler {
    override val name = "http_body"
    override val description = "分块读取 HTTP 请求或响应体。用 part 指定 request/response，用 offset+limit 分块。"

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "id" to Schema.string("来自 network_list 的 id", pattern = Cursor.ID_PATTERN),
            "part" to Schema.string("读取哪一侧", enum = listOf("request", "response"), default = "response"),
            "mode" to Schema.string("渲染模式", enum = listOf("text", "hex", "base64"), default = "text"),
            "offset" to Schema.integer("字节偏移", minimum = 0, default = 0),
            "limit" to Schema.integer("本次读取字节数", minimum = 1, maximum = 262144, default = 65536),
        ),
        required = listOf("id"),
    )

    override suspend fun execute(args: JsonObject): String {
        val a = Args(args)
        val id = a.string("id", required = true)!!
        Cursor.validateId(id)
        val part = a.string("part", false)?.lowercase() ?: "response"
        val mode = ValueMode.valueOf(a.string("mode", false)?.uppercase() ?: "TEXT")
        val offset = a.int("offset", 0)!!.coerceAtLeast(0)
        val limit = a.int("limit", 65536)!!.coerceIn(1, 262144)

        val rowId = id.substringBefore('-').toLong()
        val r = repo.httpById(rowId)
            ?: return buildJsonObject { put("error", "record_not_found"); put("id", id) }.toString()

        val full = if (part == "request") r.requestBody else r.responseBody
        val slice = if (offset >= full.size) ByteArray(0) else full.copyOfRange(offset, minOf(offset + limit, full.size))
        return buildJsonObject {
            put("id", id)
            put("part", part)
            put("mode", mode.name.lowercase())
            put("offset", offset)
            put("totalSize", full.size)
            put("returned", slice.size)
            put("hasMore", offset + slice.size < full.size)
            put("nextOffset", offset + slice.size)
            put("content", repo.render(slice, mode))
            put("contentKind", repo.classify(full).label)
        }.toString()
    }
}

class NetworkPacketBodyTool(private val repo: CaptureRepository) : McpToolHandler {
    override val name = "network_packetBody"
    override val description = "读取 TCP / WebSocket 报文的载荷，用 recordId 定位。"

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "recordId" to Schema.string("报文 id，来自 network_list 的 TCP/WebSocket 项", pattern = "^[1-9][0-9]{0,18}$"),
            "mode" to Schema.string("渲染模式", enum = listOf("text", "hex", "base64"), default = "text"),
            "offset" to Schema.integer("字节偏移", minimum = 0, default = 0),
            "limit" to Schema.integer("读取字节数", minimum = 1, maximum = 262144, default = 65536),
        ),
        required = listOf("recordId"),
    )

    override suspend fun execute(args: JsonObject): String {
        val a = Args(args)
        val rid = a.string("recordId", required = true)!!.toLong()
        val mode = ValueMode.valueOf(a.string("mode", false)?.uppercase() ?: "TEXT")
        val offset = a.int("offset", 0)!!.coerceAtLeast(0)
        val limit = a.int("limit", 65536)!!.coerceIn(1, 262144)

        val p = repo.packetById(rid)
            ?: return buildJsonObject { put("error", "record_not_found"); put("recordId", rid) }.toString()
        val slice = if (offset >= p.payload.size) ByteArray(0)
        else p.payload.copyOfRange(offset, minOf(offset + limit, p.payload.size))
        return buildJsonObject {
            put("recordId", rid)
            put("protocol", p.protocol.name)
            put("remoteAddress", p.remoteAddress)
            put("outbound", p.outbound)
            put("totalSize", p.payload.size)
            put("returned", slice.size)
            put("hasMore", offset + slice.size < p.payload.size)
            put("nextOffset", offset + slice.size)
            put("content", repo.render(slice, mode))
        }.toString()
    }
}

class NetworkStacksTool(private val repo: CaptureRepository) : McpToolHandler {
    override val name = "network_stacks"
    override val description = "获取指定流量记录的发起方调用栈，用于定位代码位置。"

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "recordId" to Schema.string("记录 id"),
            "discardSystemFrames" to Schema.boolean("剔除 android./java. 系统帧", default = true),
        ),
        required = listOf("recordId"),
    )

    override suspend fun execute(args: JsonObject): String {
        val a = Args(args)
        val rid = a.string("recordId", required = true)!!.toLong()
        val discard = a.bool("discardSystemFrames", true)
        val frames = repo.stackOf(rid, discard)
        return buildJsonObject {
            put("recordId", rid)
            putJsonArray("frames") { frames.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        }.toString()
    }
}

class NetworkDeletesTool(private val repo: CaptureRepository) : McpToolHandler {
    override val name = "network_deletes"
    override val description = "删除指定的 HTTP 请求或 TCP 连接记录。"

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "ids" to Schema.array("要删除的 id 列表", Schema.string("id")),
            "confirm" to Schema.boolean("必须显式传 true", default = false),
        ),
        required = listOf("ids", "confirm"),
    )

    override suspend fun execute(args: JsonObject): String {
        val a = Args(args)
        if (!a.bool("confirm", false)) {
            return buildJsonObject { put("error", "confirmation_required") }.toString()
        }
        val n = repo.delete(a.stringList("ids"))
        return buildJsonObject { put("deleted", n) }.toString()
    }
}

class NetworkInfoTool(private val repo: CaptureRepository) : McpToolHandler {
    override val name = "network_info"
    override val description = "获取指定记录的概要信息（协议、地址、大小、耗时）。"

    override val inputSchema = Schema.obj(
        properties = mapOf("id" to Schema.string("记录 id", pattern = Cursor.ID_PATTERN)),
        required = listOf("id"),
    )

    override suspend fun execute(args: JsonObject): String {
        val id = Args(args).string("id", required = true)!!
        Cursor.validateId(id)
        val rowId = id.substringBefore('-').toLong()
        val info = repo.info(rowId) ?: return buildJsonObject { put("error", "record_not_found") }.toString()
        return buildJsonObject {
            put("id", id)
            put("protocol", info.protocol)
            put("summary", info.summary)
            put("size", info.size)
            put("time", info.time)
        }.toString()
    }
}

class NetworkPacketsTool(private val repo: CaptureRepository) : McpToolHandler {
    override val name = "network_packets"
    override val description = "列出某会话下的 TCP/WebSocket 报文序列（按时间升序），用于还原连接会话。"

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "sessionId" to Schema.string("会话 id"),
            "limit" to Schema.integer("最大条数", minimum = 1, maximum = 300, default = 100),
        ),
        required = listOf("sessionId"),
    )

    override suspend fun execute(args: JsonObject): String {
        val a = Args(args)
        val sessionId = a.string("sessionId", required = true)!!
        val limit = a.int("limit", 100)!!.coerceIn(1, 300)
        val packets = repo.packetsAsc(sessionId, limit)
        return buildJsonObject {
            putJsonArray("packets") {
                packets.forEach { p ->
                    add(buildJsonObject {
                        put("id", p.id)
                        put("remoteAddress", p.remoteAddress)
                        put("outbound", p.outbound)
                        put("size", p.size)
                        put("time", p.time)
                        if (p.wsFrameType.isNotBlank()) put("wsFrameType", p.wsFrameType)
                    })
                }
            }
        }.toString()
    }
}

/** 应用/环境类工具。 */

class ListAppsTool(private val provider: AppListProvider) : McpToolHandler {
    override val name = "list_apps"
    override val description = "列出设备上已安装的应用，含是否已启用 Hook、是否已配置。"

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "onlyEnabled" to Schema.boolean("只返回已启用 Hook 的应用", default = false),
            "query" to Schema.string("按应用名或包名过滤"),
            "limit" to Schema.integer("最大条数", minimum = 1, maximum = 500, default = 200),
        ),
    )

    override suspend fun execute(args: JsonObject): String {
        val a = Args(args)
        val onlyEnabled = a.bool("onlyEnabled", false)
        val query = a.string("query")
        val limit = a.int("limit", 200)!!.coerceIn(1, 500)
        val apps = provider.listApps(onlyEnabled, query, limit)
        return buildJsonObject {
            putJsonArray("apps") {
                apps.forEach { app ->
                    add(buildJsonObject {
                        put("packageName", app.packageName)
                        put("label", app.label)
                        put("versionName", app.versionName)
                        put("versionCode", app.versionCode)
                        put("enabled", app.hookEnabled)
                        put("isSystem", app.isSystem)
                    })
                }
            }
            put("count", apps.size)
        }.toString()
    }
}

data class AppEntry(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val hookEnabled: Boolean,
    val isSystem: Boolean,
)

interface AppListProvider {
    suspend fun listApps(onlyEnabled: Boolean, query: String?, limit: Int): List<AppEntry>

    /**
     * 取应用图标（日志页应用卡片用）。
     * @return Drawable；取不到返回 null
     */
    suspend fun loadIcon(packageName: String): android.graphics.drawable.Drawable? = null
}

class ListDatabasesTool(private val provider: DatabaseProvider) : McpToolHandler {
    override val name = "list_databases"
    override val description = "列出指定应用的 SQLite 数据库文件及其大小。"

    override val inputSchema = Schema.obj(
        properties = mapOf("packageName" to Schema.string("目标应用包名")),
        required = listOf("packageName"),
    )

    override suspend fun execute(args: JsonObject): String {
        val pkg = Args(args).string("packageName", required = true)!!
        val dbs = provider.listDatabases(pkg)
        return buildJsonObject {
            put("packageName", pkg)
            putJsonArray("databases") {
                dbs.forEach { d ->
                    add(buildJsonObject {
                        put("name", d.name)
                        put("path", d.path)
                        put("size", d.size)
                    })
                }
            }
        }.toString()
    }
}

interface DatabaseProvider {
    suspend fun listDatabases(packageName: String): List<com.sfzsgybb.ai.data.model.DatabaseInfo>
}

class FridaLogTool(
    private val provider: FridaLogProvider,
    private val clear: Boolean,
) : McpToolHandler {
    override val name = if (clear) "frida_clear_log" else "frida_get_log"
    override val description =
        if (clear) "清空 Frida 脚本运行日志。" else "获取 Frida 脚本运行日志，可按脚本名过滤。"

    override val inputSchema: JsonObject = if (clear) {
        Schema.obj(properties = mapOf("packageName" to Schema.string("目标应用包名")), required = listOf("packageName"))
    } else {
        Schema.obj(
            properties = mapOf(
                "packageName" to Schema.string("目标应用包名"),
                "scriptName" to Schema.string("脚本名，留空返回全部"),
                "limit" to Schema.integer("最大条数", minimum = 1, maximum = 1000, default = 200),
            ),
            required = listOf("packageName"),
        )
    }

    override suspend fun execute(args: JsonObject): String {
        val a = Args(args)
        val pkg = a.string("packageName", required = true)!!
        return if (clear) {
            provider.clear(pkg)
            buildJsonObject { put("cleared", true); put("packageName", pkg) }.toString()
        } else {
            val logs = provider.get(pkg, a.string("scriptName"), a.int("limit", 200)!!.coerceIn(1, 1000))
            buildJsonObject {
                putJsonArray("logs") {
                    logs.forEach { l ->
                        add(buildJsonObject {
                            put("time", l.time)
                            put("scriptName", l.scriptName)
                            put("level", l.level)
                            put("message", l.message)
                        })
                    }
                }
                put("count", logs.size)
            }.toString()
        }
    }
}

data class FridaLogEntry(val time: Long, val scriptName: String, val level: String, val message: String)

interface FridaLogProvider {
    suspend fun get(packageName: String, scriptName: String?, limit: Int): List<FridaLogEntry>
    suspend fun clear(packageName: String)
}

class HostInstructionsTool(private val loader: InstructionLoader) : McpToolHandler {
    override val name = "host_mcp_instructions"
    override val description = "返回宿主应用提供的 MCP 使用说明与当前能力清单。"

    override val inputSchema = Schema.obj(properties = emptyMap())

    override suspend fun execute(args: JsonObject): String = loader.instructions()
}

interface InstructionLoader {
    suspend fun instructions(): String
}
