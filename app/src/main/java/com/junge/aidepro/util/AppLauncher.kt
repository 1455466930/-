package com.junge.aidepro.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * 应用启动与强停工具。
 *
 * 【为什么要单独抽出来】
 * 被 Hook 的应用在修改配置后必须重启才能重新走 Xposed 注入流程，
 * 否则新配置不会生效（配置只在 Application.onCreate 时读取一次）。
 * 手动去设置里强停太麻烦，这里提供一键操作。
 *
 * 【强停的实现】
 * 普通应用无法直接强停其他应用，需要 root：
 *   am force-stop <pkg>
 * 没有 root 时降级为「跳转到该应用的详情页」，让用户手动强停。
 */
object AppLauncher {

    private const val TAG = "AppLauncher"

    sealed class Result {
        data object Success : Result()
        data class Failed(val reason: String) : Result()
    }

    /** 启动目标应用的主界面。 */
    fun launch(context: Context, packageName: String): Result {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return Result.Failed("找不到启动入口（可能是系统组件或未安装启动页）")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(intent)
            Result.Success
        } catch (t: Throwable) {
            Log.w(TAG, "启动失败 $packageName", t)
            Result.Failed(t.message ?: "启动失败")
        }
    }

    /**
     * 强制停止目标应用。
     * 优先 root 执行 am force-stop；无 root 时返回失败，由调用方引导用户走设置页。
     */
    fun forceStop(packageName: String): Result {
        // 尝试通过 root 强停
        val viaRoot = runCatching {
            val proc = ProcessBuilder("su", "-c", "am force-stop $packageName")
                .redirectErrorStream(true)
                .start()
            val finished = proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            val exit = if (finished) proc.exitValue() else -1
            if (!finished) proc.destroy()
            exit
        }.getOrElse { -1 }

        if (viaRoot == 0) return Result.Success

        // 降级：用 killBackgroundProcesses（需要 KILL_BACKGROUND_PROCESSES 权限，
        // 且只能杀后台进程，对前台应用无效，但聊胜于无）
        return Result.Failed("需要 root 权限才能强制停止，请到系统设置里手动停止")
    }

    /** 跳转到系统设置里的应用详情页（用户可手动强停/清除数据）。 */
    fun openAppSettings(context: Context, packageName: String): Result {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            Result.Success
        } catch (t: Throwable) {
            Result.Failed(t.message ?: "无法打开设置页")
        }
    }

    /** 设备是否具备 root（su 可执行）。 */
    fun hasRoot(): Boolean = runCatching {
        val proc = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val ok = proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) && proc.exitValue() == 0
        if (!ok) proc.destroy()
        ok
    }.getOrDefault(false)

    /** 目标应用是否已安装。 */
    fun isInstalled(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    /** 目标应用当前是否正在运行。 */
    fun isRunning(context: Context, packageName: String): Boolean = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            am.runningAppProcesses?.any { it.processName == packageName } == true
        } else {
            @Suppress("DEPRECATION")
            am.runningAppProcesses?.any { it.processName == packageName } == true
        }
    }.getOrDefault(false)

    /** 查询应用版本信息。 */
    fun versionOf(context: Context, packageName: String): String = runCatching {
        val pi = context.packageManager.getPackageInfo(packageName, 0)
        pi.versionName ?: ""
    }.getOrDefault("")
}
