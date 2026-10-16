package com.sfzsgybb.ai.mcp.tools

import com.sfzsgybb.ai.frida.FridaEngine
import com.sfzsgybb.ai.mcp.Args
import com.sfzsgybb.ai.mcp.McpToolHandler
import com.sfzsgybb.ai.mcp.Schema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

/**
 * Frida 脚本注入工具。
 *
 * 把 JS 脚本通过 frida-inject 注入目标应用进程，
 * console.log 输出写入日志文件，frida_get_log 读取。
 */
class FridaInjectTool(private val engine: FridaEngine) : McpToolHandler {
    override val name = "frida_inject_script"
    override val description =
        "向目标应用注入 Frida JS 脚本并执行（需 root + 设备有 frida-inject）。" +
            "脚本的 console.log 输出会写入日志，用 frida_get_log 读取。"

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "packageName" to Schema.string("目标应用包名"),
            "scriptName" to Schema.string("脚本名（用于日志标识）"),
            "script" to Schema.string("JavaScript 源码"),
        ),
        required = listOf("packageName", "script", "scriptName"),
    )

    override suspend fun execute(args: JsonObject): String {
        val a = Args(args)
        val pkg = a.string("packageName", required = true)!!
        val name = a.string("scriptName", required = true)!!
        val script = a.string("script", required = true)!!
        val r = engine.inject(pkg, name, script)
        return buildJsonObject {
            put("injected", r.ok)
            put("packageName", pkg)
            put("scriptName", name)
            put("message", r.message)
        }.toString()
    }
}

/** Frida 注入状态工具。 */
class FridaStatusTool(private val engine: FridaEngine) : McpToolHandler {
    override val name = "frida_status"
    override val description = "查看当前 Frida 脚本注入状态。"

    override val inputSchema = Schema.obj(properties = emptyMap())

    override suspend fun execute(args: JsonObject): String {
        val status = engine.status()
        return buildJsonObject {
            put("count", status.size)
            putJsonArray("runs") {
                status.forEach { (pkg, s) ->
                    add(buildJsonObject { put("packageName", pkg); put("status", s) })
                }
            }
        }.toString()
    }
}

/** Frida 停止注入工具。 */
class FridaStopTool(private val engine: FridaEngine) : McpToolHandler {
    override val name = "frida_stop_script"
    override val description = "停止某个应用（或全部）的 Frida 脚本注入。"

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "packageName" to Schema.string("要停止的应用包名，留空停止全部"),
        ),
    )

    override suspend fun execute(args: JsonObject): String {
        val pkg = Args(args).string("packageName")
        return if (pkg.isNullOrBlank()) {
            engine.stopAll()
            buildJsonObject { put("stoppedAll", true) }.toString()
        } else {
            engine.stop(pkg)
            buildJsonObject { put("stopped", true); put("packageName", pkg) }.toString()
        }
    }
}
