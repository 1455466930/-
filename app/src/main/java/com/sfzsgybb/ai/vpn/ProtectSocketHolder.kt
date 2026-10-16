package com.sfzsgybb.ai.vpn

import android.util.Log
import java.net.Socket

/**
 * socket 保护器。
 *
 * 【为什么必须做】
 * VpnService 建立后，`addRoute("0.0.0.0", 0)` 会接管所有 IPv4 流量。
 * 我们从 VPN 内部发出去的 socket（转发到真实服务器或我们的代理）
 * **也会命中这条路由规则**，于是又回到 tun → 再进 VPN → 形成回环。
 *
 * 后果：连接卡死、应用一直转圈、CPU 飙高。
 *
 * `VpnService.protect(socket)` 把这条 socket 标记为「绕过 VPN」，
 * 让它直接走物理网卡。ProxyPin 也是这么做的
 * （见其 ProtectSocket / ProtectSocketHolder）。
 *
 * 【为什么用全局持有而不是直接调用】
 * ConnectionManager 在 Service 之外创建连接，拿不到 VpnService 实例。
 * 这里用全局持有，Service 启动时注册自己。
 */
object ProtectSocketHolder {

    private const val TAG = "ProtectSocketHolder"

    @Volatile
    private var protector: ((Socket) -> Boolean)? = null

    /** Service 启动时注册保护器。 */
    fun register(fn: (Socket) -> Boolean) {
        protector = fn
    }

    fun unregister() {
        protector = null
    }

    val isReady: Boolean get() = protector != null

    /** UDP 用的重载（DatagramSocket 不是 Socket 的子类）。 */
    fun protect(socket: java.net.DatagramSocket): Boolean {
        val fn = datagramProtector ?: run {
            Log.w(TAG, "UDP 保护器未注册")
            return false
        }
        return try {
            fn(socket)
        } catch (t: Throwable) {
            Log.w(TAG, "protect(UDP) 异常: ${t.message}")
            false
        }
    }

    @Volatile
    private var datagramProtector: ((java.net.DatagramSocket) -> Boolean)? = null

    fun registerDatagram(fn: (java.net.DatagramSocket) -> Boolean) {
        datagramProtector = fn
    }

    /**
     * 保护一条 socket。
     * @return 是否成功（失败会导致流量回环）
     */
    fun protect(socket: Socket): Boolean {
        val fn = protector
        if (fn == null) {
            Log.w(TAG, "保护器未注册，socket 流量可能回环")
            return false
        }
        return try {
            val ok = fn(socket)
            if (!ok) Log.w(TAG, "protect 返回 false")
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "protect 异常: ${t.message}")
            false
        }
    }
}
