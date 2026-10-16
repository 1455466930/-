package com.junge.aidepro.mcp

import kotlinx.serialization.json.JsonObject

/**
 * MCP 工具接口。
 * 每个工具一个实现，[McpTools] 统一注册。
 */
interface McpToolHandler {
    val name: String
    val description: String
    val inputSchema: JsonObject

    /** 执行并返回文本结果。抛出的异常会被包装成 isError 响应。 */
    suspend fun execute(args: JsonObject): String

    /** 结果是否需要分页游标。 */
    val paginated: Boolean get() = false
}

/** 参数读取辅助，带默认值与校验。 */
class Args(private val obj: JsonObject) {

    fun string(name: String, required: Boolean = false): String? {
        val v = obj[name]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNullSafe() }
        if (v == null && required) throw McpArgException("缺少必填参数: $name")
        return v
    }

    fun int(name: String, default: Int? = null, required: Boolean = false): Int? {
        val v = string(name, required)
        if (v == null) return default
        return v.toIntOrNull() ?: throw McpArgException("参数 $name 必须是整数")
    }

    fun long(name: String, default: Long? = null): Long? {
        val v = string(name) ?: return default
        return v.toLongOrNull() ?: throw McpArgException("参数 $name 必须是长整数")
    }

    fun bool(name: String, default: Boolean = false): Boolean {
        val v = string(name) ?: return default
        return v.equals("true", true) || v == "1"
    }

    fun stringList(name: String): List<String> {
        val e = obj[name] ?: return emptyList()
        return (e as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNullSafe() }
            ?: emptyList()
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content

class McpArgException(message: String) : Exception(message)

/**
 * 分页游标。
 *
 * 格式与 MCP schema 中声明的正则严格一致：
 *   afterId: ^(0|v2:[0-9]{1,19}:[ht]:[1-9][0-9]{0,18})$
 *   id:      ^[1-9][0-9]{0,18}(-(h2-)?[0-9]{1,20})?$
 *
 * v2 游标编码 time + kind(h/t) + rowId，保证在持续写入的场景下翻页不重不漏
 * （原版用 OFFSET 分页，新数据插入会导致重复行）。
 */
object Cursor {

    private const val PREFIX = "v2"

    const val AFTER_ID_PATTERN = "^(0|v2:[0-9]{1,19}:[ht]:[1-9][0-9]{0,18})$"
    const val ID_PATTERN = "^[1-9][0-9]{0,18}(-(h2-)?[0-9]{1,20})?$"

    data class Decoded(val time: Long, val kind: Char, val id: Long)

    fun encode(time: Long, kind: Char, id: Long): String = "$PREFIX:$time:$kind:$id"

    fun decode(cursor: String?): Decoded {
        if (cursor.isNullOrBlank() || cursor == "0") return Decoded(Long.MAX_VALUE, 'h', Long.MAX_VALUE)
        val parts = cursor.split(':')
        if (parts.size != 4 || parts[0] != PREFIX) {
            throw McpArgException("非法游标: $cursor。首次请传 0，之后请原样回传 nextAfterId。")
        }
        val time = parts[1].toLongOrNull() ?: throw McpArgException("非法游标时间: ${parts[1]}")
        val kind = parts[2].firstOrNull() ?: throw McpArgException("非法游标类型")
        val id = parts[3].toLongOrNull() ?: throw McpArgException("非法游标 id: ${parts[3]}")
        return Decoded(time, kind, id)
    }

    fun validateId(id: String) {
        if (!Regex(ID_PATTERN).matches(id)) throw McpArgException("非法 id: $id")
    }
}

/** 分页返回包装。 */
data class PagedResult<T>(
    val items: List<T>,
    val nextAfterId: String,
    val hasMore: Boolean,
)

/**
 * 工具集合包装。
 *
 * Dagger 无法直接注入 `List<McpToolHandler>`（泛型擦除后无法区分同类型集合），
 * 必须用一个具体类型包一层——这是 Dagger 的标准限制，不是设计缺陷。
 */
@javax.inject.Singleton
class McpToolSet @javax.inject.Inject constructor(
    val tools: List<McpToolHandler>,
)
