package com.sfzsgybb.ai.ipc

import android.os.Bundle
import android.util.Log
import com.sfzsgybb.ai.capture.CaptureRepository
import com.sfzsgybb.ai.data.model.HttpHeader
import com.sfzsgybb.ai.data.model.HttpRecord
import com.sfzsgybb.ai.data.model.LogRecord
import com.sfzsgybb.ai.data.model.PacketData
import com.sfzsgybb.ai.data.model.Protocol
import com.sfzsgybb.ai.hook.config.ConfigRepository
import com.sfzsgybb.ai.hook.config.HookSwitch
import com.sfzsgybb.ai.log.LogRepository

/**
 * 从 Binder Bundle 反序列化并入库。
 *
 * 运行在宿主 IO 协程里（不在 binder 线程），因此可以安全做数据库操作。
 * 所有字段读取都做了容错，Bundle 字段缺失不会导致崩溃。
 */
internal object LogIngest {

    private const val TAG = "LogIngest"

    suspend fun ingestLog(
        data: Bundle,
        logRepository: LogRepository,
        configRepository: ConfigRepository,
    ) {
        runCatching {
            val switchKey = data.str("switchKey") ?: ""
            val sw = HookSwitch.byKey(switchKey)
            val payload = data.getByteArray("payload") ?: ByteArray(0)
            val results = data.getSerializable("results") as? Array<ByteArray> ?: emptyArray()

            logRepository.insert(
                LogRecord(
                    packageName = data.str("pkg"),
                    logName = data.str("logName") ?: "",
                    className = data.str("className") ?: "",
                    objName = data.str("objName") ?: "",
                    group = sw?.group ?: (data.str("group")?.toIntOrNull() ?: 0),
                    type = sw?.ordinal ?: -1,
                    callStack = data.str("callStack") ?: "",
                    payload = payload,
                    payloadBrief = LogRepository.briefText(payload),
                    payloadCrc = LogRepository.crc(payload),
                    results = results.toList(),
                    resultBrief = results.firstOrNull()?.let { LogRepository.briefText(it) } ?: "",
                    resultCrc = LogRepository.crc(results.firstOrNull() ?: ByteArray(0)),
                    size = payload.size,
                    time = data.str("ts")?.toLongOrNull() ?: System.currentTimeMillis(),
                    // 详情页分类字段（对齐原版日志详情页的字段排列）
                    algoName = data.str("algoName") ?: "",
                    keyType = data.str("keyType") ?: "",
                    keyData = data.getByteArray("keyData") ?: ByteArray(0),
                    paramDesc = data.str("paramDesc") ?: "",
                    resultKind = data.str("resultKind") ?: "",
                )
            )
        }.onFailure { Log.w(TAG, "ingestLog 失败: ${it.message}") }
    }

    suspend fun ingestHttp(
        data: Bundle,
        captureRepository: CaptureRepository,
        logRepository: LogRepository,
    ) {
        runCatching {
            // 用「没有就创建」的版本：Hook 抓到的数据在 APP 刚启动、
            // 用户还没点「开始抓包」时也不该丢。
            // （早期用 currentSessionId() 只读内存，没有会话就静默丢弃，
            //   表现为「Hook 明明装上了却一条数据都没有」）
            val sessionId = captureRepository.ensureOrCreateSession("Hook 抓包")
                ?: return@runCatching
            val url = data.str("url") ?: return@runCatching
            if (!captureRepository.shouldRecord(url, null)) return@runCatching

            val uri = runCatching { java.net.URI(url) }.getOrNull()
            val method = data.str("method") ?: "GET"
            val statusCode = data.str("statusCode")?.toIntOrNull() ?: 0
            val requestBody = data.getByteArray("requestBody") ?: ByteArray(0)
            val responseBody = data.getByteArray("responseBody") ?: ByteArray(0)
            val ts = data.str("ts")?.toLongOrNull() ?: System.currentTimeMillis()
            // Hook 上报的包名：目标应用的包名
            val pkg = data.str("pkg") ?: ""

            captureRepository.insertHttp(
                HttpRecord(
                    sessionId = sessionId,
                    packageName = pkg,
                    protocol = Protocol.HTTP,
                    method = method,
                    url = url,
                    host = uri?.host ?: "",
                    path = uri?.path ?: "",
                    statusCode = statusCode,
                    requestHeaders = parseHeaders(data.str("reqHeaders")),
                    responseHeaders = parseHeaders(data.str("respHeaders")),
                    requestBody = requestBody,
                    responseBody = responseBody,
                    durationMs = data.str("durationMs")?.toLongOrNull() ?: 0L,
                    time = ts,
                )
            )

            // 同时写一条「网络」分组的日志，让日志页的网络栏也能看到
            // （http_record 是抓包页用的，log_record 是日志页用的，两张表）。
            val summary = buildString {
                append(method).append(' ').append(url)
                if (statusCode > 0) append("  → ").append(statusCode)
            }
            logRepository.insert(
                LogRecord(
                    packageName = data.str("pkg"),
                    logName = "HTTP " + method,
                    className = "",
                    objName = "",
                    group = com.sfzsgybb.ai.data.model.LogGroup.NETWORK,
                    type = -1,
                    callStack = "",
                    payload = summary.toByteArray(),
                    payloadBrief = summary,
                    payloadCrc = LogRepository.crc(summary.toByteArray()),
                    results = listOf(responseBody),
                    resultBrief = LogRepository.briefText(responseBody),
                    resultCrc = LogRepository.crc(responseBody),
                    size = responseBody.size,
                    time = ts,
                )
            )
        }.onFailure { Log.w(TAG, "ingestHttp 失败: ${it.message}") }
    }

    suspend fun ingestPacket(data: Bundle, captureRepository: CaptureRepository) {
        runCatching {
            val sessionId = captureRepository.currentSessionId() ?: return@runCatching
            val addr = data.str("remoteAddress") ?: ""
            if (!captureRepository.shouldRecord(null, addr)) return@runCatching

            val payload = data.getByteArray("payload") ?: ByteArray(0)
            captureRepository.insertPacket(
                PacketData(
                    sessionId = sessionId,
                    protocol = runCatching {
                        Protocol.valueOf((data.str("protocol") ?: "TCP").uppercase())
                    }.getOrDefault(Protocol.TCP),
                    remoteAddress = addr.substringBefore(':'),
                    remotePort = addr.substringAfter(':', "0").toIntOrNull() ?: 0,
                    outbound = data.str("outbound")?.toBooleanStrictOrNull() ?: true,
                    payload = payload,
                    size = payload.size,
                    time = data.str("ts")?.toLongOrNull() ?: System.currentTimeMillis(),
                )
            )
        }.onFailure { Log.w(TAG, "ingestPacket 失败: ${it.message}") }
    }

    private fun parseHeaders(s: String?): List<HttpHeader> =
        s?.takeIf { it.isNotBlank() }
            ?.split('|')
            ?.mapNotNull { p ->
                val i = p.indexOf('=')
                if (i <= 0) null else HttpHeader(p.substring(0, i), p.substring(i + 1))
            }
            ?: emptyList()

    private fun Bundle.str(key: String): String? = runCatching { getString(key) }.getOrNull()
}
