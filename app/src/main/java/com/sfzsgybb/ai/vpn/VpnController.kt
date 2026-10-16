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

        // 【D3 修复】未授权时先尝试 root 直接授予（免去人工弹框）。
        // appops 的 ACTIVATE_VPN 就是 VpnService.prepare 背后检查的 op，
        // 置 allow 后 prepare() 返回 null，服务可直接启动 —— AI 全自动。
        var prepare = prepareIntent()
        if (prepare != null) {
            val granted = grantVpnViaRoot()
            if (granted) {
                Log.i(TAG, "已通过 root 授予 VPN 权限，无需人工确认")
                prepare = prepareIntent() // 重新检查
            }
        }
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

    /**
     * 尝试用 root 直接授予 VPN 权限（免人工弹框）。
     *
     * 【原理】VpnService.prepare() 背后检查的是 appops 的 ACTIVATE_VPN op，
     * 用 root 把它置成 allow 后 prepare() 就返回 null。
     * 依次试三种 appops 写法（不同 Android 版本的命令名略有差异）：
     *   cmd appops set <pkg> ACTIVATE_VPN allow
     *   appops set <pkg> ACTIVATE_VPN allow
     */
    private fun grantVpnViaRoot(): Boolean {
        val pkg = context.packageName
        val cmds = listOf(
            "cmd appops set $pkg ACTIVATE_VPN allow",
            "appops set $pkg ACTIVATE_VPN allow",
        )
        for (cmd in cmds) {
            val ok = runCatching {
                val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                val code = p.waitFor()
                code == 0 && !out.contains("Error") && !out.contains("Exception")
            }.getOrDefault(false)
            if (ok) return true
        }
        Log.w(TAG, "root 授权 VPN 失败（可能无 root 或 appops 受限）")
        return false
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
