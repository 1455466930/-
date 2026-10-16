package com.junge.aidepro.hook.ipc

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.junge.aidepro.ipc.AlgorithmServiceHost
import com.junge.aidepro.ipc.IAlgorithmService
import com.junge.aidepro.ipc.ILogManager

/**
 * 被 Hook 进程侧的宿主连接器。
 *
 * 【连接流程】（完全对齐原版机制）
 *   ① ContentResolver.call(authority, "getAll", null, null)
 *      —— 通过 Provider 拿到宿主 Binder 句柄
 *   ② IAlgorithmService.Stub.asInterface(binder) 转成接口
 *   ③ 之后全程 Binder 直连读写
 *
 * 【为什么不用 bindService】
 * 被 Hook 进程是被注入进去的，没有合适的时机和权限去 bindService；
 * 而 ContentProvider 的访问是同步的、随时可用的。
 *
 * 【容错】
 * 宿主未启动、Provider 未就绪、Binder 死亡，都不能让目标应用崩溃或卡住。
 * 所有调用都有超时与空值兜底。
 */
object HostConnector {

    private const val TAG = "HostConnector"

    const val AUTHORITY = "com.junge.aidepro.ipc"
    const val HOST_PACKAGE = "com.junge.aidepro"

    private val URI_ALL = Uri.parse("content://$AUTHORITY")

    @Volatile private var service: IAlgorithmService? = null
    @Volatile private var logManager: ILogManager? = null
    @Volatile private var lastConnectAt: Long = 0
    @Volatile private var connectFailed: Boolean = false

    /** 缓存一份配置，避免每次读都走 Binder。 */
    @Volatile private var cachedConfig: String? = null
    @Volatile private var cachedConfigAt: Long = 0
    private var cachedConfigPkg: String? = null

    /** 连接失败次数，用于退避。 */
    @Volatile private var failureCount: Int = 0

    /**
     * 建立连接。
     * @param force 为 true 时忽略退避，立即重试
     * @return 是否成功
     */
    fun connect(context: Context, force: Boolean = false): Boolean {
        // 已连接且存活
        service?.let {
            if (isAlive(it.asBinder()) && logManager?.asBinder()?.let { b -> isAlive(b) } == true) {
                return true
            }
            // 句柄存在但已死，清掉重连
            service = null
            logManager = null
        }

        val now = System.currentTimeMillis()
        // 退避：失败后短时间内不重试，避免拖慢目标应用启动
        if (!force && connectFailed && now - lastConnectAt < RETRY_INTERVAL_MS) return false
        lastConnectAt = now

        return try {
            val bundle = context.contentResolver.call(URI_ALL, "getAll", null, null)
            if (bundle == null) {
                Log.w(TAG, "Provider 返回 null（宿主未启动或 uid 被拒）")
                connectFailed = true
                failureCount++
                return false
            }
            @Suppress("DEPRECATION")
            val svcBinder: IBinder? = bundle.getBinder(AlgorithmServiceHost.KEY_SERVICE)
            @Suppress("DEPRECATION")
            val logBinder: IBinder? = bundle.getBinder(AlgorithmServiceHost.KEY_LOG_MANAGER)

            if (svcBinder == null || logBinder == null) {
                Log.w(TAG, "Bundle 中缺少 Binder 句柄")
                connectFailed = true
                return false
            }

            service = IAlgorithmService.Stub.asInterface(svcBinder)
            logManager = ILogManager.Stub.asInterface(logBinder)

            // Binder 死亡监听：宿主被回收时清空缓存，下次调用会重连
            svcBinder.linkToDeath({
                Log.w(TAG, "宿主 Binder 已死亡")
                service = null
                logManager = null
                connectFailed = false
            }, 0)

            connectFailed = false
            failureCount = 0
            val ver = runCatching {
                bundle.getInt(com.junge.aidepro.provider.AlgorithmServiceProvider.KEY_VERSION, -1)
            }.getOrDefault(-1)
            Log.i(TAG, "已连接宿主，协议版本=$ver")
            true
        } catch (t: Throwable) {
            // Provider 不存在 / 权限拒绝 / 宿主未安装，都会走到这里
            Log.w(TAG, "连接宿主失败: ${t.javaClass.simpleName}: ${t.message}")
            connectFailed = true
            failureCount++
            false
        }
    }

    private fun isAlive(binder: IBinder?): Boolean =
        runCatching { binder?.isBinderAlive == true }.getOrDefault(false)

    /** 取配置 JSON。带短时缓存，避免 Hook 热路径频繁走 Binder。 */
    fun getConfig(context: Context, packageName: String): String? {
        val now = System.currentTimeMillis()
        cachedConfig?.let {
            if (cachedConfigPkg == packageName && now - cachedConfigAt < CONFIG_TTL_MS) return it
        }
        // 连接失败时不返回旧缓存（可能属于别的应用或已过期），
        // 让调用方明确知道当前拿不到配置，从而触发重试逻辑。
        if (!connect(context)) return null

        return runCatching {
            val raw = service?.app_getConfig(packageName)
            if (raw != null) {
                cachedConfig = raw
                cachedConfigPkg = packageName
                cachedConfigAt = now
            }
            raw
        }.onFailure {
            Log.w(TAG, "app_getConfig 失败: ${it.message}")
            service = null
        }.getOrNull()
    }

    fun isAppEnabled(context: Context, packageName: String): Boolean {
        if (!connect(context)) return false
        return runCatching { service?.app_isSwitch(packageName) ?: false }
            .onFailure { service = null }
            .getOrDefault(false)
    }

    fun getLogManager(context: Context): ILogManager? {
        if (!connect(context)) return null
        return logManager
    }

    fun serviceIfConnected(): IAlgorithmService? = service
    fun logManagerIfConnected(): ILogManager? = logManager

    /** 强制刷新配置缓存（revision 变化时调用）。 */
    fun invalidateConfig() {
        cachedConfig = null
        cachedConfigAt = 0
    }

    fun reset() {
        service = null
        logManager = null
        cachedConfig = null
        connectFailed = false
        failureCount = 0
    }

    private const val RETRY_INTERVAL_MS = 3000L
    private const val CONFIG_TTL_MS = 5000L
}
