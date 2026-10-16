package com.junge.aidepro

import android.app.Application
import android.util.Log
import com.junge.aidepro.hook.config.ConfigRepository
import com.junge.aidepro.hook.config.GlobalSwitch
import com.junge.aidepro.ipc.AlgorithmServiceHost
import com.junge.aidepro.mcp.McpHttpServer
import com.junge.aidepro.util.PipeConsumer
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class AideProApp : Application() {

    @Inject lateinit var configRepository: ConfigRepository
    @Inject lateinit var mcpServer: McpHttpServer
    @Inject lateinit var serviceHost: AlgorithmServiceHost

    /** 全局协程作用域。 */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this

        runCatching { configRepository.ensureDefaults() }
            .onFailure { Log.w(TAG, "初始化默认配置失败", it) }

        // 刷新 Binder 服务的 uid 白名单：只有被启用了 Hook 的应用才允许连接
        runCatching { serviceHost.refreshAllowedUids() }
            .onFailure { Log.w(TAG, "刷新 uid 白名单失败", it) }

        // 管道消费者（供大数据量传输用）
        PipeConsumer.impl = object : PipeConsumer {
            override fun onLine(packageName: String, channel: String, line: String) {
                Log.d(TAG, "pipe[$channel] $packageName: ${line.take(200)}")
            }

            override fun exportContent(query: String): String {
                return runCatching { configRepository.mirrorPreview() }.getOrDefault("")
            }
        }

        appScope.launch {
            val enabled = runCatching {
                configRepository.getGlobal(GlobalSwitch.MCP_HTTP_ENABLED)
            }.getOrDefault(false)
            val keepAlive = runCatching {
                configRepository.getGlobal(GlobalSwitch.MCP_KEEP_ALIVE)
            }.getOrDefault(true)

            if (enabled && !mcpServer.isRunning) {
                runCatching { mcpServer.start() }
                    .onFailure { Log.w(TAG, "MCP 服务启动失败", it) }
            }
            // 前台服务保活：避免长时间空闲被系统回收导致 AI 客户端断连
            if (enabled && keepAlive) {
                runCatching { com.junge.aidepro.mcp.McpKeepAliveService.start(this@AideProApp) }
                    .onFailure { Log.w(TAG, "MCP 保活服务启动失败", it) }
            }
        }
    }

    /** 供 Provider 获取 Binder 服务实现。 */
    fun serviceHostOrNull(): AlgorithmServiceHost = serviceHost

    companion object {
        private const val TAG = "AideProApp"

        @Volatile
        private var instance: AideProApp? = null

        fun get(): AideProApp = instance ?: throw IllegalStateException("Application 尚未初始化")
        fun peek(): AideProApp? = instance
    }
}

/** 供 Provider 以非 Hilt 方式访问（Provider 早于 Hilt 注入时机）。 */
val AideProApp.serviceHost: AlgorithmServiceHost
    get() = serviceHostOrNull()
