package com.junge.aidepro.capture

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * 轻量保活服务。
 *
 * 抓包期间被 Hook 的目标应用可能被系统回收，需要宿主进程常驻。
 * 与 CaptureService 分离：CaptureService 带前台通知（用户可见），
 * 本服务只做进程兜底，避免重复通知打扰用户。
 *
 * 注意：不做任何绕过系统省电策略的黑科技（原版用悬浮窗 + 多进程互拉，
 * 在 Android 12+ 会被限制且拖累续航），这里仅依赖前台服务本身的优先级。
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "保活服务启动")
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "保活服务停止")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KeepAliveService"
    }
}
