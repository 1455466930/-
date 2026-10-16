package com.junge.aidepro.mitm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MITM 代理控制器。
 *
 * 负责：初始化 CA、启动/停止代理、把抓到的事务写入数据库。
 */
@Singleton
class MitmController @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val repository: com.junge.aidepro.capture.CaptureRepository,
    private val logRepository: com.junge.aidepro.log.LogRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val server = MitmProxyServer(
        MitmProxyServer.Config(bindAddress = "127.0.0.1")
    )

    @Volatile var lastError: String? = null
        private set

    @Volatile var caReady: Boolean = false
        private set

    @Volatile var bcReady: Boolean = false
        private set

    /** 启动前必须先初始化 CA。 */
    fun ensureInitialized(): Boolean {
        bcReady = X509Signer.isAvailable
        if (!bcReady) {
            lastError = "系统 BouncyCastle 不可用: ${X509Signer.errorMessage}"
            Log.e(TAG, lastError!!)
            return false
        }

        if (!caReady) {
            caReady = CertificateManager.init { path ->
                runCatching { context.assets.open(path).readBytes() }.getOrNull()
            }
            if (!caReady) {
                lastError = "CA 初始化失败: ${CertificateManager.errorMessage}"
                Log.e(TAG, lastError!!)
                return false
            }
        }
        return true
    }

    /**
     * 启动代理。
     *
     * 注意：**不再设置系统全局代理**。
     * 流量入口统一由 VPN（CaptureVpnService）负责 —— 那是唯一能覆盖
     * Cronet / Flutter / 自带网络栈应用的方式。系统代理覆盖不全、忘关会断网，
     * 已废弃。
     */
    fun start(): Boolean {
        if (!ensureInitialized()) return false

        server.listener = object : MitmProxyServer.Listener {
            override fun onStarted(port: Int) {
                Log.i(TAG, "代理已启动 :$port")
                lastError = null
            }

            override fun onStopped() {
                Log.i(TAG, "代理已停止")
            }

            override fun onError(t: Throwable) {
                lastError = "${t.javaClass.simpleName}: ${t.message}"
                Log.e(TAG, "代理错误", t)
            }

            override fun onTransaction(tx: HttpTransaction) {
                scope.launch { persist(tx) }
            }

            override fun onLog(level: Char, message: String) {
                when (level) {
                    'E' -> Log.e(TAG, message)
                    'W' -> Log.w(TAG, message)
                    else -> Log.d(TAG, message)
                }
            }
        }

        return server.start()
    }

    /** 停止代理。 */
    fun stop() {
        server.stop()
    }

    /** 证书链诊断。 */
    fun certDiagnostics(): String = com.junge.aidepro.mitm.CertificateManager.diagnostics()

    val isRunning: Boolean get() = server.isRunning
    val port: Int get() = server.actualPort

    /** 系统代理当前是否由我们开启。 */
    /** 入库失败计数（诊断用） */
    val persistFailCount = java.util.concurrent.atomic.AtomicLong()
    val persistOkCount = java.util.concurrent.atomic.AtomicLong()

    private suspend fun persist(tx: HttpTransaction) {
        runCatching {
            // 用「没有就创建」的版本：VPN 独立运行时也能抓到数据，
            // 不会因为没有活跃会话而静默丢弃。
            val sessionId = repository.ensureOrCreateSession()
            if (sessionId == null) {
                Log.w(TAG, "入库失败：无法获取/创建会话，丢弃 ${tx.url}")
                persistFailCount.incrementAndGet()
                return@runCatching
            }
            if (!repository.shouldRecord(tx.url, tx.host)) return@runCatching

            repository.insertHttp(
                com.junge.aidepro.data.model.HttpRecord(
                    sessionId = sessionId,
                    // VPN 通过 META 头传来的应用包名；系统代理模式下为空
                    packageName = tx.ownerPackage ?: "",
                    protocol = com.junge.aidepro.data.model.Protocol.HTTP,
                    method = tx.method,
                    url = tx.url,
                    host = tx.host,
                    path = tx.path,
                    httpVersion = tx.requestHead.version,
                    statusCode = tx.statusCode,
                    statusText = tx.responseHead?.header("reason") ?: "",
                    requestHeaders = tx.requestHead.headerList.map {
                        com.junge.aidepro.data.model.HttpHeader(it.first, it.second)
                    },
                    responseHeaders = tx.responseHead?.headerList?.map {
                        com.junge.aidepro.data.model.HttpHeader(it.first, it.second)
                    } ?: emptyList(),
                    requestBody = tx.requestBody,
                    responseBody = tx.responseBody,
                    durationMs = tx.durationMs,
                    time = tx.startTime,
                )
            )

            // 同时写一条「网络」分组的日志，让日志页的网络栏也能看到
            val summary = buildString {
                append(tx.method).append(' ').append(tx.url)
                if (tx.statusCode > 0) append("  → ").append(tx.statusCode)
            }
            logRepository.insert(
                com.junge.aidepro.data.model.LogRecord(
                    packageName = null,
                    logName = "HTTP " + tx.method,
                    className = "",
                    objName = "",
                    group = com.junge.aidepro.data.model.LogGroup.NETWORK,
                    type = -1,
                    callStack = "",
                    payload = summary.toByteArray(),
                    payloadBrief = summary,
                    payloadCrc = com.junge.aidepro.log.LogRepository.crc(summary.toByteArray()),
                    results = listOf(tx.responseBody),
                    resultBrief = com.junge.aidepro.log.LogRepository.briefText(tx.responseBody),
                    resultCrc = com.junge.aidepro.log.LogRepository.crc(tx.responseBody),
                    size = tx.responseBody.size,
                    time = tx.startTime,
                )
            )
            persistOkCount.incrementAndGet()
        }.onFailure {
            persistFailCount.incrementAndGet()
            Log.w(TAG, "入库异常: ${it.javaClass.simpleName}: ${it.message}", it)
        }
    }

    fun status(): String = buildString {
        append("running=${server.isRunning} port=${server.actualPort} ")
        append("ca=$caReady bc=$bcReady ")
        append("并发=${server.activeConnections.get()} ")
        append("事务=${server.totalTransactions.get()} ")
        append("证书=${CertificateManager.stats()}")
        lastError?.let { append(" err=$it") }
    }

    private companion object {
        const val TAG = "MitmController"
    }
}
