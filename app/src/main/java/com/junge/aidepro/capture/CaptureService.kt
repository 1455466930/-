package com.junge.aidepro.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.junge.aidepro.MainActivity
import com.junge.aidepro.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 抓包前台服务。
 *
 * 与原版的差异：
 *  - 原版把抓包逻辑、Binder 接口、通知、保活全塞在 PacketCaptureService 一个类里。
 *  - 这里服务只负责「会话生命周期 + 前台通知」，跨进程数据由 [HookLogReader]
 *    从被 Hook 进程写入的 JSONL 文件读取并入库（在 Application 里已常驻启动）。
 */
@AndroidEntryPoint
class CaptureService : Service() {

    @Inject lateinit var captureRepository: CaptureRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val pkg = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
                val filterLocal = intent.getBooleanExtra(EXTRA_FILTER_LOCAL, true)
                startForeground(NOTIFICATION_ID, buildNotification(pkg))
                scope.launch {
                    runCatching { captureRepository.start(pkg, filterLocal) }
                }
            }
            ACTION_STOP -> {
                scope.launch { runCatching { captureRepository.stop() } }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
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
        val channel = NotificationChannel(
            CHANNEL_ID, "抓包服务", NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "抓包进行中的常驻通知"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(pkg: String): Notification {
        val intent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("正在抓包")
            .setContentText(pkg.ifBlank { "等待目标请求" })
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(intent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "capture_service"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.junge.aidepro.action.CAPTURE_START"
        const val ACTION_STOP = "com.junge.aidepro.action.CAPTURE_STOP"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_FILTER_LOCAL = "filterLocal"

        fun start(context: Context, packageName: String, filterLocal: Boolean = true) {
            val i = Intent(context, CaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PACKAGE, packageName)
                putExtra(EXTRA_FILTER_LOCAL, filterLocal)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CaptureService::class.java).apply { action = ACTION_STOP })
        }
    }
}
