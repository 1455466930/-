package com.sfzsgybb.ai.data.db

import com.sfzsgybb.ai.data.model.CaptureSession
import com.sfzsgybb.ai.data.model.CaptureState
import com.sfzsgybb.ai.data.model.ConnectRecord
import com.sfzsgybb.ai.data.model.HttpHeader
import com.sfzsgybb.ai.data.model.HttpRecord
import com.sfzsgybb.ai.data.model.LogRecord
import com.sfzsgybb.ai.data.model.PacketData
import com.sfzsgybb.ai.data.model.Protocol
import com.sfzsgybb.ai.data.model.StackRecord

/** 实体 <-> 领域模型映射。 */

fun LogRecordEntity.toModel() = LogRecord(
    id = id,
    packageName = packageName,
    logName = logName,
    objName = objName,
    className = className,
    group = group,
    type = type,
    callStack = callStack,
    payload = payload,
    payloadBrief = payloadBrief,
    payloadCrc = payloadCrc,
    results = results,
    resultBrief = resultBrief,
    resultCrc = resultCrc,
    size = size,
    time = time,
    isRead = isRead,
    algoName = algoName,
    keyType = keyType,
    keyData = keyData,
    paramDesc = paramDesc,
    resultKind = resultKind,
)

fun LogRecord.toEntity() = LogRecordEntity(
    id = id,
    packageName = packageName,
    logName = logName,
    objName = objName,
    className = className,
    group = group,
    type = type,
    callStack = callStack,
    payload = payload,
    payloadBrief = payloadBrief,
    payloadCrc = payloadCrc,
    results = results,
    resultBrief = resultBrief,
    resultCrc = resultCrc,
    size = size,
    time = time,
    isRead = isRead,
    algoName = algoName,
    keyType = keyType,
    keyData = keyData,
    paramDesc = paramDesc,
    resultKind = resultKind,
)

private const val HEADER_SEP = "\u0000"

private fun List<HttpHeader>.encode(): List<String> = map { "${it.name}$HEADER_SEP${it.value}" }

private fun List<String>.decodeHeaders(): List<HttpHeader> = mapNotNull {
    val i = it.indexOf(HEADER_SEP)
    if (i < 0) null else HttpHeader(it.substring(0, i), it.substring(i + 1))
}

fun HttpRecordEntity.toModel() = HttpRecord(
    id = id,
    sessionId = sessionId,
    packageName = packageName,
    protocol = runCatching { Protocol.valueOf(protocol) }.getOrDefault(Protocol.HTTP),
    method = method,
    url = url,
    host = host,
    path = path,
    httpVersion = httpVersion,
    statusCode = statusCode,
    statusText = statusText,
    requestHeaders = requestHeaders.decodeHeaders(),
    responseHeaders = responseHeaders.decodeHeaders(),
    requestBody = requestBody,
    responseBody = responseBody,
    requestBodyTruncated = requestBodyTruncated,
    responseBodyTruncated = responseBodyTruncated,
    requestSize = requestSize,
    responseSize = responseSize,
    durationMs = durationMs,
    time = time,
    mimeType = mimeType,
)


/**
 * 轻量投影 → 领域模型。
 *
 * 【D1 修复的配套】投影实体不含 requestBody/responseBody 两个 BLOB
 * （防 CursorWindow 被打爆），转换时正文置空 —— 需要正文的调用方
 * 必须走 byId(id) 按单条取。
 */
fun HttpRecordSummaryEntity.toModel() = HttpRecord(
    id = id,
    sessionId = sessionId,
    packageName = packageName,
    protocol = runCatching { Protocol.valueOf(protocol) }.getOrDefault(Protocol.HTTP),
    method = method,
    url = url,
    host = host,
    path = path,
    httpVersion = httpVersion,
    statusCode = statusCode,
    statusText = statusText,
    requestHeaders = requestHeaders.decodeHeaders(),
    responseHeaders = responseHeaders.decodeHeaders(),
    requestBody = ByteArray(0),
    responseBody = ByteArray(0),
    requestBodyTruncated = requestBodyTruncated,
    responseBodyTruncated = responseBodyTruncated,
    requestSize = requestSize,
    responseSize = responseSize,
    durationMs = durationMs,
    time = time,
    mimeType = mimeType,
)

fun HttpRecord.toEntity() = HttpRecordEntity(
    id = id,
    sessionId = sessionId,
    packageName = packageName,
    protocol = protocol.name,
    method = method,
    url = url,
    host = host,
    path = path,
    httpVersion = httpVersion,
    statusCode = statusCode,
    statusText = statusText,
    requestHeaders = requestHeaders.encode(),
    responseHeaders = responseHeaders.encode(),
    requestBody = requestBody,
    responseBody = responseBody,    requestBodyTruncated = requestBodyTruncated,
    responseBodyTruncated = responseBodyTruncated,
    requestSize = requestSize,
    responseSize = responseSize,
    durationMs = durationMs,
    time = time,
    mimeType = mimeType,
)

fun PacketDataEntity.toModel() = PacketData(
    id = id,
    sessionId = sessionId,
    protocol = runCatching { Protocol.valueOf(protocol) }.getOrDefault(Protocol.TCP),
    remoteAddress = remoteAddress,
    remotePort = remotePort,
    localPort = localPort,
    outbound = outbound,
    payload = payload,
    size = size,
    time = time,
    wsFrameType = wsFrameType,
    stackTrace = stackTrace,
)

fun PacketData.toEntity() = PacketDataEntity(
    id = id,
    sessionId = sessionId,
    protocol = protocol.name,
    remoteAddress = remoteAddress,
    remotePort = remotePort,
    localPort = localPort,
    outbound = outbound,
    payload = payload,
    size = size,
    time = time,
    wsFrameType = wsFrameType,
    stackTrace = stackTrace,
)

fun CaptureSessionEntity.toModel() = CaptureSession(
    sessionId = sessionId,
    packageName = packageName,
    appLabel = appLabel,
    state = runCatching { CaptureState.valueOf(state) }.getOrDefault(CaptureState.IDLE),
    startedAt = startedAt,
    stoppedAt = stoppedAt,
    filterLocalRequests = filterLocalRequests,
    recordCount = recordCount,
)

fun CaptureSession.toEntity() = CaptureSessionEntity(
    sessionId = sessionId,
    packageName = packageName,
    appLabel = appLabel,
    state = state.name,
    startedAt = startedAt,
    stoppedAt = stoppedAt,
    filterLocalRequests = filterLocalRequests,
    recordCount = recordCount,
)

fun ConnectRecordEntity.toModel() = ConnectRecord(
    id = id,
    sessionId = sessionId,
    remoteAddress = remoteAddress,
    remotePort = remotePort,
    connectTime = connectTime,
    closeTime = closeTime,
    packetCount = packetCount,
    sentBytes = sentBytes,
    receivedBytes = receivedBytes,
)

fun ConnectRecord.toEntity() = ConnectRecordEntity(
    id = id,
    sessionId = sessionId,
    remoteAddress = remoteAddress,
    remotePort = remotePort,
    connectTime = connectTime,
    closeTime = closeTime,
    packetCount = packetCount,
    sentBytes = sentBytes,
    receivedBytes = receivedBytes,
)

fun StackRecordEntity.toModel() = StackRecord(
    id = id,
    ownerId = ownerId,
    frames = frames,
    time = time,
)

fun StackRecord.toEntity() = StackRecordEntity(
    id = id,
    ownerId = ownerId,
    frames = frames,
    time = time,
)

/**
 * 轻量投影 → 领域模型。
 *
 * 【D1 修复的配套】投影实体不含 payload BLOB，
 * 转换时 payload 置空 —— 需要正文的调用方必须走 byId(id) 按单条取。
 */
fun PacketSummaryEntity.toModel() = PacketData(
    id = id,
    sessionId = sessionId,
    protocol = runCatching { Protocol.valueOf(protocol) }.getOrDefault(Protocol.TCP),
    remoteAddress = remoteAddress,
    remotePort = remotePort,
    localPort = localPort,
    outbound = outbound,
    payload = ByteArray(0),
    size = size,
    time = time,
    wsFrameType = wsFrameType,
    stackTrace = "",
)

/**
 * 轻量投影 → 领域模型。
 *
 * 【日志列表闪退修复的配套】投影实体不含 payload/results/keyData BLOB，
 * 转换时这些字段置空 —— 需要正文的调用方必须走 byId(id) 单条取。
 */
fun LogRecordSummaryEntity.toModel() = LogRecord(
    id = id,
    packageName = packageName,
    logName = logName,
    objName = objName,
    className = className,
    group = group,
    type = type,
    callStack = "",
    payload = ByteArray(0),
    payloadBrief = payloadBrief,
    payloadCrc = payloadCrc,
    results = emptyList(),
    resultBrief = resultBrief,
    resultCrc = resultCrc,
    size = size,
    time = time,
    isRead = isRead,
    algoName = algoName,
    keyType = keyType,
    keyData = ByteArray(0),
    paramDesc = paramDesc,
    resultKind = resultKind,
)
