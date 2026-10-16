package com.junge.aidepro.ipc

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import com.junge.aidepro.capture.CaptureRepository
import com.junge.aidepro.hook.config.ConfigRepository
import com.junge.aidepro.hook.config.HookSwitch
import com.junge.aidepro.log.LogRepository
import com.junge.aidepro.util.PipeHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 算法服务宿主端（Binder 服务实现）。
 *
 * 对应原版的 AlgorithmServiceHost + BinderC0850。
 *
 * 【职责】
 *  - 实现 IAlgorithmService：被 Hook 进程读配置、拉脚本
 *  - 实现 ILogManager：     被 Hook 进程回传日志、报文、HTTP 记录
 *
 * 【为什么用 Binder 而不是文件】
 * 被 Hook 进程运行在 untrusted_app 域，SELinux 不允许它写任何宿主可读的文件位置
 * （实测 /data/local/tmp 会被 EACCES 拒绝）。Binder 走内核 IPC，不受此限制。
 *
 * 【线程模型】
 * Binder 方法在 binder 线程池执行，不能直接做耗时 IO。
 * 因此这里只做反序列化，入库统一丢到 IO 协程。
 */
@Singleton
class AlgorithmServiceHost @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val configRepository: ConfigRepository,
    private val logRepository: LogRepository,
    private val captureRepository: CaptureRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 允许连接的 uid 白名单。null 表示不限制（仅用于调试）。 */
    @Volatile private var allowedUids: Set<Int>? = null

    /**
     * 校验调用方 uid。
     *
     * 原版做法：把已配置 Hook 的应用的 uid 加进白名单。
     * 这里保持一致：凡是「在 APP 里开启了 Hook 的应用」都允许连接，
     * 其他应用一律拒绝，避免任意应用连上来偷日志。
     */
    fun isUidAllowed(uid: Int): Boolean {
        // 宿主自己
        if (uid == android.os.Process.myUid()) return true
        // 未启用白名单时放行（首次使用、还没配置任何应用时）
        val set = allowedUids ?: return true
        return uid in set
    }

    /** 刷新 uid 白名单。配置变更时调用。 */
    fun refreshAllowedUids() {
        runCatching {
            val pkgs = configRepository.listConfiguredPackages()
                .filter { configRepository.getAppConfig(it).enabled }
            val pm = context.packageManager
            val uids = pkgs.mapNotNull { pkg ->
                runCatching { pm.getApplicationInfo(pkg, 0).uid }.getOrNull()
            }.toSet()
            allowedUids = if (uids.isEmpty()) null else uids
            Log.i(TAG, "uid 白名单已刷新: $uids")
        }.onFailure { Log.w(TAG, "刷新 uid 白名单失败", it) }
    }

    // ---------------- IAlgorithmService ----------------

    inner class ServiceImpl : IAlgorithmService.Stub() {

        override fun isRunning(): Boolean = true

        override fun getVersion(): Int = PROTOCOL_VERSION

        override fun getVersionName(): String = PROTOCOL_VERSION_NAME

        override fun app_getConfig(packageName: String): String? {
            if (!checkCaller("app_getConfig")) return null
            return runCatching {
                val cfg = configRepository.getAppConfig(packageName)
                ConfigJson.encode(cfg)
            }.onFailure { Log.w(TAG, "app_getConfig 失败", it) }.getOrNull()
        }

        override fun app_getConfigItem(packageName: String, key: String): String? {
            if (!checkCaller("app_getConfigItem")) return null
            val cfg = configRepository.getAppConfig(packageName)
            return when {
                key == "enabled" -> cfg.enabled.toString()
                key.startsWith("switch:") -> (cfg.switches[key.removePrefix("switch:")] ?: false).toString()
                else -> cfg.globalSwitches[key]?.toString()
            }
        }

        override fun app_isSwitch(packageName: String): Boolean {
            if (!checkCaller("app_isSwitch")) return false
            return configRepository.getAppConfig(packageName).enabled
        }

        override fun app_addHook(packageName: String, hookJson: String) {
            if (!checkCaller("app_addHook")) return
            // 仅记录，不落库（热路径，避免 IO 压力）
            Log.d(TAG, "app_addHook: $packageName $hookJson")
        }

        override fun app_getScript(packageName: String, scriptName: String): String? {
            if (!checkCaller("app_getScript")) return null
            return runCatching {
                val f = java.io.File(context.filesDir, "frida/$packageName/$scriptName")
                if (f.exists()) f.readText() else null
            }.getOrNull()
        }

        override fun app_getHookRules(packageName: String): String? {
            if (!checkCaller("app_getHookRules")) return null
            return runCatching {
                ConfigJson.encodeRules(configRepository.getAppConfig(packageName).hooks)
            }.getOrNull()
        }
    }

    // ---------------- ILogManager ----------------

    inner class LogManagerImpl : ILogManager.Stub() {

        override fun isRunning(): Boolean = true

        override fun getVersion(): Int = PROTOCOL_VERSION

        override fun writeLog(data: Bundle) {
            if (!checkCaller("writeLog")) return
            // Binder 线程不做 IO，转交 IO 协程
            scope.launch { LogIngest.ingestLog(data, logRepository, configRepository) }
        }

        override fun writeHttp(data: Bundle) {
            if (!checkCaller("writeHttp")) return
            scope.launch { LogIngest.ingestHttp(data, captureRepository, logRepository) }
        }

        override fun writePacket(data: Bundle) {
            if (!checkCaller("writePacket")) return
            scope.launch { LogIngest.ingestPacket(data, captureRepository) }
        }

        override fun openWritePipe(packageName: String, channel: String): ParcelFileDescriptor? {
            if (!checkCaller("openWritePipe")) return null
            return runCatching { PipeHub.openPipe(packageName, channel) }
                .onFailure { Log.w(TAG, "openWritePipe 失败", it) }
                .getOrNull()
        }

        override fun exportLogs(query: String): ParcelFileDescriptor? {
            if (!checkCaller("exportLogs")) return null
            return runCatching { PipeHub.openExportPipe(query) }
                .onFailure { Log.w(TAG, "exportLogs 失败", it) }
                .getOrNull()
        }
    }

    // ---------------- 公共 ----------------

    private fun checkCaller(op: String): Boolean {
        val uid = runCatching { Binder.getCallingUid() }.getOrDefault(-1)
        if (!isUidAllowed(uid)) {
            Log.w(TAG, "拒绝 $op：调用方 uid=$uid 不在白名单")
            return false
        }
        return true
    }

    val serviceBinder: IAlgorithmService get() = ServiceImpl()
    val logBinder: ILogManager get() = LogManagerImpl()

    companion object {
        private const val TAG = "AlgorithmServiceHost"

        /** 协议版本。与原版一致用 1，便于将来对接。 */
        const val PROTOCOL_VERSION = 1
        const val PROTOCOL_VERSION_NAME = "3.0.0"

        /** 用于把 Binder 放进 Bundle 的 key。原版使用同样的 "service"。 */
        const val KEY_SERVICE = "service"
        const val KEY_LOG_MANAGER = "logManager"
    }
}
