package com.junge.aidepro.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MCP (Model Context Protocol) 协议数据结构。
 *
 * 对齐原版 native 层实现的 MCP server：
 *  - serverInfo: {"name":"algorithm-aide-pro-native","version":"1"}
 *  - 支持 tools/list 与 tools/call
 *  - 头部 MCP-Protocol-Version 协商
 *
 * 本实现补齐了原版缺少的两点：
 *  1. 完整的 JSON-RPC 2.0 错误码（原版部分分支返回非法结构）
 *  2. 工具入参用 JSON Schema 校验（原版 schema 存在但未强制校验）
 */

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val method: String = "",
    val params: JsonObject? = null,
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
) {
    companion object {
        // JSON-RPC 标准错误码
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603

        fun parseError(msg: String = "Parse error") = JsonRpcError(PARSE_ERROR, msg)
        fun invalidRequest(msg: String = "Invalid Request") = JsonRpcError(INVALID_REQUEST, msg)
        fun methodNotFound(name: String) = JsonRpcError(METHOD_NOT_FOUND, "Method not found: $name")
        fun invalidParams(msg: String) = JsonRpcError(INVALID_PARAMS, msg)
        fun internal(msg: String) = JsonRpcError(INTERNAL_ERROR, msg)
    }
}

@Serializable
data class McpServerInfo(
    val name: String = "algorithm-aide-pro",
    val version: String = "3.0.0",
)

@Serializable
data class McpCapabilities(
    val tools: ToolsCapability = ToolsCapability(),
)

@Serializable
data class ToolsCapability(
    @SerialName("listChanged") val listChanged: Boolean = false,
)

@Serializable
data class McpInitializeResult(
    @SerialName("protocolVersion") val protocolVersion: String,
    @SerialName("serverInfo") val serverInfo: McpServerInfo,
    val capabilities: McpCapabilities,
    val instructions: String = "",
)

@Serializable
data class McpTool(
    val name: String,
    val description: String,
    @SerialName("inputSchema") val inputSchema: JsonObject,
)

@Serializable
data class McpToolListResult(val tools: List<McpTool>)

/** UI 展示用的工具元信息。 */
data class McpToolMeta(val name: String, val description: String, val paginated: Boolean)

/** 工具调用结果。 */
@Serializable
data class McpContent(
    val type: String = "text",
    val text: String = "",
)

@Serializable
data class McpToolCallResult(
    val content: List<McpContent>,
    @SerialName("isError") val isError: Boolean = false,
)

object McpJson {
    val instance = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
        explicitNulls = false
    }
}

/** JSON Schema 构造辅助。 */
object Schema {

    fun obj(
        properties: Map<String, JsonObject>,
        required: List<String> = emptyList(),
        description: String? = null,
    ): JsonObject = buildJsonObject {
        put("type", "object")
        description?.let { put("description", it) }
        put("properties", JsonObject(properties))
        if (required.isNotEmpty()) {
            put("required", kotlinx.serialization.json.JsonArray(required.map { JsonPrimitive(it) }))
        }
        put("additionalProperties", false)
    }

    fun string(description: String, pattern: String? = null, enum: List<String>? = null, default: String? = null): JsonObject =
        buildJsonObject {
            put("type", "string")
            put("description", description)
            pattern?.let { put("pattern", it) }
            default?.let { put("default", it) }
            enum?.let { list ->
                put("enum", kotlinx.serialization.json.JsonArray(list.map { JsonPrimitive(it) }))
            }
        }

    fun integer(description: String, minimum: Int? = null, maximum: Int? = null, default: Int? = null): JsonObject =
        buildJsonObject {
            put("type", "integer")
            put("description", description)
            minimum?.let { put("minimum", it) }
            maximum?.let { put("maximum", it) }
            default?.let { put("default", it) }
        }

    fun boolean(description: String, default: Boolean? = null): JsonObject = buildJsonObject {
        put("type", "boolean")
        put("description", description)
        default?.let { put("default", it) }
    }

    fun array(description: String, items: JsonObject): JsonObject = buildJsonObject {
        put("type", "array")
        put("description", description)
        put("items", items)
    }
}

object McpConstants {
    const val PROTOCOL_VERSION = "2024-11-05"
    const val HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version"
    const val HEADER_SESSION_ID = "Mcp-Session-Id"
    const val DEFAULT_PORT = 19191

    const val INSTRUCTIONS = """AlgorithmAidePro MCP 服务。

调用约定：
1. 抓包类工具需先调 capture_status 确认状态，再用 capture_sessions 获取真实 sessionId。
2. 分页时把上一次返回的 nextAfterId / nextOffset 原样传回，不要自行构造。
3. HTTP 详情用 http_info / http_body（按 id + part），TCP/WebSocket 载荷用 recordId。
4. 所有 id 必须来自前一次工具调用的返回，不要臆造。"""
}
