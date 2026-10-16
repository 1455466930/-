package com.sfzsgybb.ai.vpn

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VPN 抓包控制器。
 *
 * 统一管理 [CaptureVpnService] 的启停，并负责：
 *  - 申请 VPN 权限（Android 会弹系统确认框）
 *  - 启动/停止服务
 *  - 提供运行状态与诊断信息
 */
@Singleton
class VpnController @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) {
    @Volatile var lastError: String? = null
        private set

    val isRunning: Boolean get() = CaptureVpnService.isRunning

    /**
     * 检查是否已有 VPN 权限（不会弹框）。
     * 返回 null 表示已授权，否则返回需要启动的授权 Intent。
     */
    fun prepareIntent(): Intent? = try {
        VpnService.prepare(context)
    } catch (t: Throwable) {
        Log.w(TAG, "prepare 异常: ${t.message}")
        null
    }

    /**
     * 是否可以直接启动（已授权）。
     * 注意：`VpnService.prepare()` 返回 null 表示已授权。
     */
    fun hasPermission(): Boolean = prepareIntent() == null

    /**
     * 启动 VPN。
     *
     * @return 结果：
     *   - [StartResult.STARTED] 已启动
     *   - [StartResult.NEED_PERMISSION] 需要用户授权，调用方需启动 [prepareIntent]
     *   - [StartResult.FAILED] 失败（见 [lastError]）
     */
    fun start(
        proxyHost: String = CaptureVpnService.DEFAULT_PROXY_HOST,
        proxyPort: Int = CaptureVpnService.DEFAULT_PROXY_PORT,
        allowApps: List<String>? = null,
        disallowApps: List<String>? = null,
    ): StartResult {
        if (isRunning) return StartResult.STARTED

        val prepare = prepareIntent()
        if (prepare != null) {
            lastError = "需要用户授权 VPN 连接"
            return StartResult.NEED_PERMISSION
        }

        return try {
            val intent = CaptureVpnService.startIntent(
                context, proxyHost, proxyPort, allowApps, disallowApps,
            )
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            lastError = null
            StartResult.STARTED
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "启动 VPN 失败", t)
            StartResult.FAILED
        }
    }

    fun stop() {
        try {
            context.startService(CaptureVpnService.stopIntent(context))
        } catch (t: Throwable) {
            Log.w(TAG, "停止 VPN 失败: ${t.message}")
        }
    }

    /** 状态摘要。 */
    fun status(): String = buildString {
        append("running=").append(isRunning)
        append(" hasPermission=").append(hasPermission())
        lastError?.let { append(" error=").append(it) }
    }

    enum class StartResult { STARTED, NEED_PERMISSION, FAILED }

    companion object {
        private const val TAG = "VpnController"

        /** 供 Activity 用：请求 VPN 权限的 requestCode。 */
        const val REQUEST_CODE = 0x7F71
    }
}
