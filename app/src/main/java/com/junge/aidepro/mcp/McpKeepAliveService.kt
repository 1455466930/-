package com.junge.aidepro.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.junge.aidepro.MainActivity
import com.junge.aidepro.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * MCP 服务保活。
 *
 * 【为什么需要】
 * MCP 客户端（Claude Desktop / Cherry Studio 等）会保持长连接，
 * 如果 APP 被系统回收，连接就断了，客户端要反复重连。
 *
 * 【做法】
 * 起一个前台服务常驻，带低优先级通知。
 * 这与抓包服务分开：抓包服务只在抓包期间存在，MCP 保活是长期需要的。
 *
 * 【不做什么】
 * 不用双进程互拉、不用 JobScheduler 反复唤醒这类黑科技——
 * 在 Android 12+ 上会被限制，而且明显费电。
 * 前台服务已经能保证在用户可见范围内不被杀。
 *
 * 【断连自愈】
 * 服务内定时检查服务是否还活着（socket 可能因异常关闭），
 * 发现挂了就自动重启。
 */
@AndroidEntryPoint
class McpKeepAliveService : Service() {

    @Inject lateinit var mcpServer: McpHttpServer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        scope.launch {
            // 自愈循环：服务挂了就重启
            while (isActive) {
                if (!mcpServer.isRunning) {
                    Log.w(TAG, "检测到 MCP 已停止，尝试重启")
                    runCatching { mcpServer.start() }
                        .onFailure { Log.w(TAG, "重启失败", it) }
                }
                delay(HEALTH_CHECK_INTERVAL_MS)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "MCP 服务", NotificationManager.IMPORTANCE_MIN).apply {
                description = "保持 MCP 服务在线，供 AI 客户端连接"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
        )
    }

    private fun buildNotification(): Notification {
        val intent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val port = mcpServer.actualPort.takeIf { it > 0 } ?: McpNetwork.PREFERRED_PORT
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("MCP 服务运行中")
            .setContentText("127.0.0.1:$port")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(intent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "McpKeepAlive"
        private const val CHANNEL_ID = "mcp_keepalive"
        private const val NOTIFICATION_ID = 1002
        private const val HEALTH_CHECK_INTERVAL_MS = 10_000L

        const val ACTION_START = "com.junge.aidepro.action.MCP_KEEPALIVE_START"
        const val ACTION_STOP = "com.junge.aidepro.action.MCP_KEEPALIVE_STOP"

        fun start(context: Context) {
            val i = Intent(context, McpKeepAliveService::class.java).apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, McpKeepAliveService::class.java))
        }
    }
}
