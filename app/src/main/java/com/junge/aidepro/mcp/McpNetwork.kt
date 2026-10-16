package com.junge.aidepro.mcp

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * MCP 服务的网络配置。
 *
 * 【端口冲突问题】
 * 原版 AlgorithmAidePro 也默认用 19191，同机安装时会互相抢占，
 * 谁先启动谁占住端口，后启动的那个静默失败。
 * 因此这里实现「从首选端口开始向后探测」，找到可用端口再绑定。
 *
 * 【局域网访问】
 * 默认只绑 127.0.0.1（安全）。开启局域网模式后额外绑定 0.0.0.0，
 * 让同一 WiFi 下的电脑/手机能直接访问，免去 adb forward。
 * 局域网模式必须配 token，否则同网段任何人都能读你的日志。
 */
object McpNetwork {

    private const val TAG = "McpNetwork"

    /** 首选端口，与原版保持一致。 */
    const val PREFERRED_PORT = 19191

    /** 端口探测范围：首选端口起连续尝试这么多个。 */
    const val PORT_RANGE = 20

    /**
     * 探测可用端口。
     * @return 可用端口，全部占用则返回 null
     */
    fun findAvailablePort(preferred: Int = PREFERRED_PORT, range: Int = PORT_RANGE): Int? {
        for (p in preferred until preferred + range) {
            if (isPortFree(p)) return p
        }
        return null
    }

    private fun isPortFree(port: Int): Boolean = runCatching {
        val socket = java.net.ServerSocket()
        socket.reuseAddress = true
        socket.bind(java.net.InetSocketAddress("127.0.0.1", port))
        socket.close()
        true
    }.getOrDefault(false)

    /** 取本机局域网 IP（IPv4）。 */
    fun localIpAddress(): String? {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        }.getOrNull()
    }

    /** 取 WiFi 名称，仅用于展示。 */
    @Suppress("DEPRECATION")
    fun wifiName(context: Context): String? = runCatching {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wm?.connectionInfo?.ssid?.trim('"')
    }.getOrNull()

    /**
     * 生成访问令牌。
     * 局域网模式下必填，防止同网段未授权访问。
     */
    fun generateToken(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** 拼出局域网访问地址（不再带 token，客户端直接用即可）。 */
    fun lanUrl(port: Int, token: String? = null): String? {
        val ip = localIpAddress() ?: return null
        return "http://$ip:$port/mcp"
    }
}
