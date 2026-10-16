package com.junge.aidepro.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.net.InetSocketAddress
import java.nio.ByteBuffer

/**
 * 抓包用的 VPN 服务。
 *
 * 移植自 ProxyPin（Apache 2.0）的 `com.network.proxy.ProxyVpnService`。
 *
 * 【为什么需要 VPN】
 * 系统全局代理（Settings.Global.HTTP_PROXY）只有「主动读取代理设置」的应用才遵守。
 * 像 Cronet、Flutter、自带网络栈的应用会绕过它。
 * VpnService 是在**内核路由层**接管流量：所有被路由到 VPN 的 IP 包都会
 * 送到我们手里，应用无法绕过。
 *
 * 【架构】
 * ```
 * VpnService.Builder
 *   .addAddress("10.0.0.2", 32)      虚拟网卡地址
 *   .addRoute("0.0.0.0", 0)          接管全部 IPv4 流量
 *   .setBlocking(true)               阻塞读，配合独立读线程
 *   .addDisallowedApplication(自己)  排除自身，避免流量回环
 *   .establish()  →  ParcelFileDescriptor（tun 设备）
 *
 * 读 tun → 原始 IP 包 → ConnectionHandler（用户态 TCP 栈）
 *                    → 还原成 TCP 流 → 交给上游 HTTP 代理（我们的 MITM）
 * ```
 */
class CaptureVpnService : VpnService() {

    private var tunInterface: ParcelFileDescriptor? = null
    private var reader: VpnTunnelReader? = null
    private var writer: VpnTunnelWriter? = null
    private var handler: VpnPacketHandler? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            else -> {
                val proxyHost = intent?.getStringExtra(EXTRA_PROXY_HOST) ?: DEFAULT_PROXY_HOST
                val proxyPort = intent?.getIntExtra(EXTRA_PROXY_PORT, DEFAULT_PROXY_PORT)
                    ?: DEFAULT_PROXY_PORT
                val allowedApps = intent?.getStringArrayListExtra(EXTRA_ALLOW_APPS)
                val disallowedApps = intent?.getStringArrayListExtra(EXTRA_DISALLOW_APPS)
                startVpn(proxyHost, proxyPort, allowedApps, disallowedApps)
            }
        }
        return START_STICKY
    }

    private fun startVpn(
        proxyHost: String,
        proxyPort: Int,
        allowedApps: List<String>?,
        disallowedApps: List<String>?,
    ) {
        if (tunInterface != null) {
            Log.i(TAG, "VPN 已在运行")
            return
        }

        startForegroundNotification()

        val proxyAddress = InetSocketAddress(proxyHost, proxyPort)
        Log.i(TAG, "启动 VPN，上游代理=$proxyAddress allow=$allowedApps disallow=$disallowedApps")
        VpnLog.add("正在启动 VPN…")

        val fd = createTunInterface(allowedApps, disallowedApps)
        if (fd == null) {
            Log.e(TAG, "创建 tun 接口失败")
            VpnLog.add("✗ 创建 tun 接口失败（VPN 权限被拒？）")
            stopSelf()
            return
        }
        tunInterface = fd
        isRunning = true

        // 初始化 uid→包名 解析器：VPN 是路由层接管，只能靠它反查流量归属
        UidResolver.init(applicationContext)

        // 注册 socket 保护器：转发出去的 socket 必须绕过 VPN，
        // 否则流量会回到 tun 形成回环，表现为应用一直转圈。
        ProtectSocketHolder.register { socket -> protect(socket) }
        ProtectSocketHolder.registerDatagram { socket -> protect(socket) }
        VpnLog.add("socket 保护器已注册")

        val input = java.io.FileInputStream(fd.fileDescriptor)
        val output = java.io.FileOutputStream(fd.fileDescriptor)
        val w = VpnTunnelWriter(output)
        writer = w

        // 包处理器：解析 IP/TCP，维护用户态 TCP 状态机，转发到上游代理
        val h = VpnPacketHandler(
            writer = w,
            proxyAddress = proxyAddress,
            onLog = { level, msg ->
                when (level) {
                    'E' -> Log.e(TAG, msg)
                    'W' -> Log.w(TAG, msg)
                    else -> Log.d(TAG, msg)
                }
            },
        )
        handler = h
        // 暴露给 MCP 工具查询统计
        com.junge.aidepro.mcp.tools.VpnStatsHolder.handler = h

        val r = VpnTunnelReader(input) { buf ->
            h.handlePacket(buf)
        }
        reader = r
        r.start()

        Log.i(TAG, "VPN 已启动")
        VpnLog.add("VPN 已启动，上游代理=$proxyAddress")
        if (allowedApps.isNullOrEmpty()) {
            VpnLog.add("抓取范围：全部应用")
        } else {
            VpnLog.add("抓取范围：${allowedApps.size} 个应用")
        }
    }

    /**
     * 建立 tun 接口。
     *
     * 关键参数含义：
     * - `addAddress("10.0.0.2", 32)`：给虚拟网卡一个地址，/32 表示只有它自己
     * - `addRoute("0.0.0.0", 0)`：把全部 IPv4 流量路由进来
     * - `setBlocking(true)`：读操作阻塞，这样读线程不会空转
     * - `addDisallowedApplication(packageName)`：**必须排除自己**，
     *   否则我们转发出去的流量又会被 VPN 抓回来，形成无限回环
     */
    private fun createTunInterface(
        allowedApps: List<String>?,
        disallowedApps: List<String>?,
    ): ParcelFileDescriptor? {
        return try {
            val builder = Builder()
                .setMtu(MTU)
                .addAddress(VIRTUAL_ADDRESS, 32)
                .addRoute("0.0.0.0", 0)
                .setSession(SESSION_NAME)
                .setBlocking(true)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            // 排除自己，避免流量回环
            runCatching { builder.addDisallowedApplication(packageName) }
                .onFailure { Log.w(TAG, "排除自身失败: ${it.message}") }

            if (!allowedApps.isNullOrEmpty()) {
                // 只代理白名单应用
                allowedApps.forEach { pkg ->
                    runCatching { builder.addAllowedApplication(pkg) }
                        .onFailure { Log.w(TAG, "加入白名单失败 $pkg: ${it.message}") }
                }
                // 白名单模式下必须再排除自己一次
                runCatching { builder.addDisallowedApplication(packageName) }
            } else if (!disallowedApps.isNullOrEmpty()) {
                disallowedApps.forEach { pkg ->
                    runCatching { builder.addDisallowedApplication(pkg) }
                }
            }

            builder.establish()
        } catch (t: Throwable) {
            Log.e(TAG, "建立 tun 失败", t)
            null
        }
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "抓包 VPN", NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "保持抓包 VPN 运行" }
            nm.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, CaptureVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("抓包运行中")
            .setContentText("正在通过 VPN 接管网络流量")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPending)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun stopVpn() {
        Log.i(TAG, "停止 VPN")
        VpnLog.add("VPN 已停止")
        isRunning = false
        runCatching { reader?.stop() }
        runCatching { handler?.shutdown() }
        runCatching { tunInterface?.close() }
        reader = null
        writer = null
        handler = null
        com.junge.aidepro.mcp.tools.VpnStatsHolder.handler = null
        tunInterface = null
        ProtectSocketHolder.unregister()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN 被系统撤销")
        stopVpn()
        super.onRevoke()
    }

    companion object {
        private const val TAG = "CaptureVpnService"

        const val ACTION_STOP = "com.junge.aidepro.vpn.STOP"
        const val EXTRA_PROXY_HOST = "proxyHost"
        const val EXTRA_PROXY_PORT = "proxyPort"
        const val EXTRA_ALLOW_APPS = "allowApps"
        const val EXTRA_DISALLOW_APPS = "disallowApps"

        /** 虚拟网卡地址（不能和真实网段冲突） */
        const val VIRTUAL_ADDRESS = "10.0.0.2"

        /** MTU。1400 比较保守，避免分片 */
        const val MTU = 1500

        const val DEFAULT_PROXY_HOST = "127.0.0.1"
        const val DEFAULT_PROXY_PORT = 8888

        private const val SESSION_NAME = "AidePro Capture"
        private const val CHANNEL_ID = "aidepro-vpn"
        private const val NOTIFICATION_ID = 1003

        /** 服务是否在运行。 */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** 构造启动 Intent。 */
        fun startIntent(
            context: Context,
            proxyHost: String = DEFAULT_PROXY_HOST,
            proxyPort: Int = DEFAULT_PROXY_PORT,
            allowApps: List<String>? = null,
            disallowApps: List<String>? = null,
        ): Intent = Intent(context, CaptureVpnService::class.java).apply {
            putExtra(EXTRA_PROXY_HOST, proxyHost)
            putExtra(EXTRA_PROXY_PORT, proxyPort)
            allowApps?.let { putStringArrayListExtra(EXTRA_ALLOW_APPS, ArrayList(it)) }
            disallowApps?.let { putStringArrayListExtra(EXTRA_DISALLOW_APPS, ArrayList(it)) }
        }

        fun stopIntent(context: Context): Intent =
            Intent(context, CaptureVpnService::class.java).apply { action = ACTION_STOP }
    }
}
