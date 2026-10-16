package com.junge.aidepro.hook.config

import android.content.Context
import android.util.Log
import com.junge.aidepro.hook.ipc.HostConnector
import kotlinx.serialization.json.Json

/**
 * 被 Hook 应用进程内的配置读取。
 *
 * 【最终方案：Binder 直连】
 * 尝试过的方案及其失败原因：
 *  1. XSharedPreferences —— LSPosed 2.1+ 返回空 map（keys=[]），API 行为已变
 *  2. 镜像文件（/data/local/tmp）—— untrusted_app 域 SELinux 拒绝写入/读取
 *  3. 应用外部目录 —— Android 11+ 沙箱隔离，其他应用不可访问
 *
 * 最终采用与原版一致的方案：通过 ContentProvider 握手拿到宿主 Binder，
 * 直接调用 IAlgorithmService.app_getConfig()。Binder 走内核 IPC，
 * 不受文件路径 SELinux 限制。
 */
object HookConfigCache {

    private const val TAG = "HookConfigCache"

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var cached: AppConfig? = null
    @Volatile private var cachedPkg: String? = null
    @Volatile private var cachedAt: Long = 0

    /** 诊断信息，打到 Xposed 日志里便于排查。 */
    @Volatile var DIAG: String = ""
        internal set

    @Volatile var lastError: String? = null
        internal set

    @Volatile var everLoaded: Boolean = false
        private set

    private const val CACHE_TTL_MS = 3000L

    fun get(context: Context, packageName: String): AppConfig {
        val now = System.currentTimeMillis()
        cached?.let {
            if (cachedPkg == packageName && now - cachedAt < CACHE_TTL_MS) return it
        }

        val raw = try {
            HostConnector.getConfig(context, packageName)
        } catch (t: Throwable) {
            lastError = "Binder 调用异常: ${t.javaClass.simpleName}: ${t.message}"
            null
        }

        if (raw.isNullOrBlank()) {
            if (lastError == null) lastError = "宿主返回空配置（应用可能未启用 Hook，或宿主未运行）"
            DIAG = "socket=null"
            return AppConfig(packageName = packageName)
        }

        val cfg = runCatching { json.decodeFromString(AppConfig.serializer(), raw) }
            .getOrElse {
                lastError = "配置解析失败: ${it.message}"
                AppConfig(packageName = packageName)
            }
            .also { everLoaded = true }

        cached = cfg
        cachedPkg = packageName
        cachedAt = now
        DIAG = "binder len=${raw.length} enabled=${cfg.enabled} switches=${cfg.switches.size}"
        lastError = null
        return cfg
    }

    fun global(context: Context, sw: GlobalSwitch): Boolean {
        // 全局开关也由宿主下发，这里退化为从已缓存配置取；未命中时用默认值，
        // 避免在热路径上额外增加一次 Binder 往返。
        return cached?.globalSwitches?.get(sw.key) ?: sw.default
    }

    fun invalidate() {
        cached = null
        cachedPkg = null
        cachedAt = 0
        HostConnector.invalidateConfig()
    }

    /** 供 HookEngine 打日志用。 */
    fun describe(): String =
        "cached=${cached != null} pkg=$cachedPkg diag=$DIAG err=${lastError ?: "none"}"
}
