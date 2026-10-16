package com.sfzsgybb.ai.mcp.tools

import com.sfzsgybb.ai.mitm.MitmController
import com.sfzsgybb.ai.mcp.Args
import com.sfzsgybb.ai.mcp.McpToolHandler
import com.sfzsgybb.ai.mcp.Schema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MITM 代理控制工具。
 *
 * 【为什么需要】
 * Hook 抓包只能看到 Java 网络栈，看不到 Flutter / native / 加固应用的流量。
 * MITM 代理工作在网络层，能覆盖全部流量，两者互补。
 *
 * 【使用前提】
 * 1. 设备需安装 CA 证书到系统信任区（Android 7+ 用户证书对 App 无效）
 * 2. 流量入口由 VPN（vpn_capture 工具）负责，本代理只做解密与入库
 */
class MitmControlTool(private val controller: MitmController) : McpToolHandler {
    override val name = "mitm_proxy"
    override val description =
        "控制 MITM 代理抓包引擎。action=start 启动代理，stop 停止，status 查看状态。" +
            "代理工作在网络层，可捕获 Flutter/native 等非 Java 网络栈的流量。" +
            "流量入口由 VPN 负责（见 vpn_capture 工具），本代理只做 HTTPS 解密与入库。"

    override val inputSchema = Schema.obj(
        properties = mapOf(
            "action" to Schema.string(
                "操作类型",
                enum = listOf("start", "stop", "status", "restart",
                    "probe_bc", "probe_attrs", "probe_ext", "probe_ext_api", "cert_diag"),
                default = "status",
            ),
        ),
        required = listOf("action"),
    )

    override suspend fun execute(args: JsonObject): String {
        val action = Args(args).string("action", required = true)!!
        return when (action) {
            "probe_bc" -> buildJsonObject {
                put("available", com.sfzsgybb.ai.mitm.X509Signer.isAvailable)
                com.sfzsgybb.ai.mitm.X509Signer.errorMessage?.let { put("error", it) }
                put("api", com.sfzsgybb.ai.mitm.BcProbe.dump())
            }.toString()

            "probe_attrs" -> buildJsonObject {
                put("attrs", com.sfzsgybb.ai.mitm.BcProbe.probeX509Attrs())
                put("extNames", com.sfzsgybb.ai.mitm.BcProbe.probeExtNames())
                put("types", com.sfzsgybb.ai.mitm.BcProbe.probeTypes())
            }.toString()

            "probe_ext" -> buildJsonObject {
                put("ctors", com.sfzsgybb.ai.mitm.BcProbe.probeExtCtors())
            }.toString()

            "probe_ext_api" -> buildJsonObject {
                put("api", com.sfzsgybb.ai.mitm.BcProbe.probeExtApi())
            }.toString()

            "cert_diag" -> buildJsonObject {
                put("diagnostics", com.sfzsgybb.ai.mitm.CertificateManager.diagnostics())
            }.toString()

            "start" -> buildJsonObject {
                val ok = controller.start()
                put("started", ok)
                put("port", controller.port)
                put("caReady", controller.caReady)
                put("bcReady", controller.bcReady)
                put("persistOk", controller.persistOkCount.get())
                put("persistFail", controller.persistFailCount.get())
                controller.lastError?.let { put("error", it) }
            }.toString()

            "stop" -> {
                controller.stop()
                buildJsonObject { put("stopped", true) }.toString()
            }

            "restart" -> {
                controller.stop()
                kotlinx.coroutines.delay(300)
                val ok = controller.start()
                buildJsonObject {
                    put("restarted", ok)
                    put("port", controller.port)
                }.toString()
            }

            else -> buildJsonObject {
                put("running", controller.isRunning)
                put("port", controller.port)
                put("persistOk", controller.persistOkCount.get())
                put("persistFail", controller.persistFailCount.get())
                put("caReady", controller.caReady)
                put("bcReady", controller.bcReady)
                put("status", controller.status())
            }.toString()
        }
    }
}
