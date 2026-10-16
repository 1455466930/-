package com.sfzsgybb.ai.mcp.tools

import com.sfzsgybb.ai.mcp.Args
import com.sfzsgybb.ai.mcp.McpToolHandler
import com.sfzsgybb.ai.mcp.Schema
import com.sfzsgybb.ai.vpn.CaptureVpnService
import com.sfzsgybb.ai.vpn.VpnController
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VPN 抓包控制工具。
 *
 * 【为什么需要它】
 * 系统全局代理只有「主动读取代理设置」的应用才遵守。
 * Cronet、Flutter、自带网络栈的应用会绕过它 —— 表现为「抓不到」。
 * VPN 在**内核路由层**接管全部 IP 包，应用无法绕过。
 */
@Singleton
class VpnControlTool @Inject constructor(
    private val controller: VpnController,
) : McpToolHandler {

    override val name = "vpn_capture"
    override val description =
        "代理抓包控制。在内核路由层接管全部应用流量，可抓到不读系统代理的应用" +
            "（Cronet / Flutter / 自带网络栈）。" +
            "action=start 启动、stop 停止、status 查看状态、stats 查看包统计、destinations 看目标地址。"

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "action" to Schema.string(
                "操作类型",
                enum = listOf("start", "stop", "status", "stats", "destinations", "prepare"),
                default = "status",
            ),
            "proxyHost" to Schema.string("上游代理地址，默认 127.0.0.1"),
            "proxyPort" to Schema.integer("上游代理端口，默认 8888"),
        ),
        required = listOf("action"),
    )

    override suspend fun execute(args: JsonObject): String {
        val a = Args(args)
        return when (val action = a.string("action", required = true)!!) {
            "start" -> {
                val host = a.string("proxyHost") ?: CaptureVpnService.DEFAULT_PROXY_HOST
                val port = a.int("proxyPort") ?: CaptureVpnService.DEFAULT_PROXY_PORT
                when (controller.start(host, port)) {
                    VpnController.StartResult.STARTED -> buildJsonObject {
                        put("started", true)
                        put("proxyHost", host)
                        put("proxyPort", port)
                        put("permission", true)
                    }.toString()

                    VpnController.StartResult.NEED_PERMISSION -> buildJsonObject {
                        put("started", false)
                        put("permission", false)
                        put(
                            "message",
                            "需要用户在应用内授权 VPN 连接（系统会弹确认框）。" +
                                "请在 APP 抓包页点击「启动代理抓包」并确认。"
                        )
                    }.toString()

                    VpnController.StartResult.FAILED -> buildJsonObject {
                        put("started", false)
                        put("error", controller.lastError ?: "未知错误")
                    }.toString()
                }
            }

            "stop" -> {
                controller.stop()
                buildJsonObject { put("stopped", true) }.toString()
            }

            "prepare" -> buildJsonObject {
                put("hasPermission", controller.hasPermission())
                put("needUserConfirm", !controller.hasPermission())
            }.toString()

            "stats" -> {
                val h = VpnStatsHolder.handler
                if (h == null) {
                    buildJsonObject {
                        put("running", controller.isRunning)
                        put("message", "VPN 未运行，无统计数据")
                    }.toString()
                } else {
                    buildJsonObject {
                        put("packetsTotal", h.packetsTotal.get())
                        put("packetsTcp", h.packetsTcp.get())
                        put("packetsUdp", h.packetsUdp.get())
                        put("packetsOther", h.packetsOther.get())
                        put("tcpSynCount", h.synCount.get())
                        put("summary", h.stats())
                    }.toString()
                }
            }

            "destinations" -> {
                val h = VpnStatsHolder.handler
                if (h == null) {
                    buildJsonObject { put("count", 0) }.toString()
                } else {
                    val dests = h.destinations().entries.sortedByDescending { it.value }.take(50)
                    buildJsonObject {
                        put("count", dests.size)
                        putJsonObject("destinations") {
                            dests.forEach { put(it.key, it.value) }
                        }
                    }.toString()
                }
            }

            else -> buildJsonObject {
                put("running", controller.isRunning)
                put("hasPermission", controller.hasPermission())
                put("status", controller.status())
                controller.lastError?.let { put("error", it) }
            }.toString()
        }
    }
}

/**
 * 让 MCP 工具能访问当前运行中的包处理器（拿统计、目标地址）。
 *
 * 用全局持有而不是注入：包处理器由 Service 创建，生命周期跟随 Service，
 * 不适合放进 DI 容器。
 */
object VpnStatsHolder {
    @Volatile var handler: com.sfzsgybb.ai.vpn.VpnPacketHandler? = null
}
