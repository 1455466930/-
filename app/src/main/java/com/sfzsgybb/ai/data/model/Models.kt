package com.sfzsgybb.ai.data.model

import java.util.UUID

/**
 * 一条 Hook 日志。
 *
 * 对齐原版 LogDataV2：payload / result 以 byte[] 存储以保真，
 * CRC 用于去重折叠（foldDuplicateLogs），brief 字段供列表快速渲染。
 */
data class LogRecord(
    val id: Long = 0,
    /** 归属应用包名，null 表示本机自身日志 */
    val packageName: String? = null,
    /** 日志名称，如 "AES/CBC/PKCS5Padding" */
    val logName: String = "",
    /** 对象名 / 方法签名 */
    val objName: String = "",
    val className: String = "",
    /** 1..N 分组，见 LogGroup */
    val group: Int = LogGroup.DEFAULT,
    /** 对应 37 项开关中的哪一项，用于筛选 */
    val type: Int = -1,
    val callStack: String = "",
    val payload: ByteArray = ByteArray(0),
    val payloadBrief: String = "",
    val payloadCrc: Long = 0,
    /** 1..N 条结果，例如多次加密输出 */
    val results: List<ByteArray> = emptyList(),
    val resultBrief: String = "",
    val resultCrc: Long = 0,
    val size: Int = 0,
    val time: Long = System.currentTimeMillis(),
    val isRead: Boolean = false,

    // ---------- 详情页分类字段（对齐原版日志详情的字段排列）----------

    /** 算法名称，如 AES/CBC/PKCS5Padding / MD5 / HmacSHA256 */
    val algoName: String = "",
    /** 密钥类型，如 会话密钥 / 公钥 / 私钥 */
    val keyType: String = "",
    /** 密钥内容（十六进制展示） */
    val keyData: ByteArray = ByteArray(0),
    /** 参数描述，如 "参数类型: byte[] / 长度 32" */
    val paramDesc: String = "",
    /** 结果类别，如 加密结果 / 解密结果 / 摘要结果 / 签名结果 / MAC 结果 */
    val resultKind: String = "",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LogRecord) return false
        return id == other.id && time == other.time && payloadCrc == other.payloadCrc
    }

    override fun hashCode(): Int = (id.hashCode() * 31 + time.hashCode()) * 31 + payloadCrc.hashCode()
}

/**
 * 一次抓包会话，对应原版 CaptureSessionManager。
 * sessionId 用 UUID，与 MCP 侧 schema 的 format:uuid 对齐。
 */
data class CaptureSession(
    val sessionId: String = UUID.randomUUID().toString(),
    /** 抓包模式：hook / vpn */
    val source: String = "hook",
    val packageName: String = "",
    val appLabel: String = "",
    val state: CaptureState = CaptureState.IDLE,
    val startedAt: Long = System.currentTimeMillis(),
    val stoppedAt: Long? = null,
    /** 过滤本机回环请求 */
    val filterLocalRequests: Boolean = true,
    val recordCount: Int = 0,
)

/** 一条 HTTP 记录。 */
data class HttpRecord(
    val id: Long = 0,
    val sessionId: String = "",
    /** 数据来源：hook / vpn */
    val source: String = "hook",
    val packageName: String = "",
    val protocol: Protocol = Protocol.HTTP,
    val method: String = "",
    val url: String = "",
    val host: String = "",
    val path: String = "",
    val httpVersion: String = "1.1",
    val statusCode: Int = 0,
    val statusText: String = "",
    val requestHeaders: List<HttpHeader> = emptyList(),
    val responseHeaders: List<HttpHeader> = emptyList(),
    val requestBody: ByteArray = ByteArray(0),
    val responseBody: ByteArray = ByteArray(0),
    val requestBodyTruncated: Boolean = false,
    val responseBodyTruncated: Boolean = false,
    val requestSize: Long = 0,
    val responseSize: Long = 0,
    val durationMs: Long = 0,
    val time: Long = System.currentTimeMillis(),
    val mimeType: String = "",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpRecord) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

data class HttpHeader(val name: String, val value: String)

/** 一条 TCP / WebSocket 报文。 */
data class PacketData(
    val id: Long = 0,
    val sessionId: String = "",
    val protocol: Protocol = Protocol.TCP,
    val remoteAddress: String = "",
    val remotePort: Int = 0,
    val localPort: Int = 0,
    /** true = 发出，false = 接收 */
    val outbound: Boolean = true,
    val payload: ByteArray = ByteArray(0),
    val size: Int = 0,
    val time: Long = System.currentTimeMillis(),
    /** WebSocket 帧类型：text/binary/ping/pong/close，TCP 时为空 */
    val wsFrameType: String = "",
    val stackTrace: String = "",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PacketData) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

/** 调用栈记录。 */
data class StackRecord(
    val id: Long = 0,
    val ownerId: Long = 0,
    val frames: List<String> = emptyList(),
    val time: Long = System.currentTimeMillis(),
)

/** 连接记录（TCP 连接级元数据）。 */
data class ConnectRecord(
    val id: Long = 0,
    val sessionId: String = "",
    val remoteAddress: String = "",
    val remotePort: Int = 0,
    val connectTime: Long = 0,
    val closeTime: Long? = null,
    val packetCount: Int = 0,
    val sentBytes: Long = 0,
    val receivedBytes: Long = 0,
)

/** 数据库信息，供 list_databases 工具使用。 */
data class DatabaseInfo(
    val packageName: String = "",
    val name: String = "",
    val path: String = "",
    val size: Long = 0,
)
