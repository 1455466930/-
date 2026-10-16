package com.sfzsgybb.ai.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 日志表。
 * 索引说明：
 *  - time 倒序分页（原版按时间排序）
 *  - payloadCrc 去重折叠
 *  - (packageName, group) 供按应用/分组筛选
 */
@Entity(
    tableName = "log_record",
    indices = [
        Index("time"),
        Index("payloadCrc"),
        Index(value = ["packageName", "group"]),
        Index("logName"),
    ],
)
data class LogRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String? = null,
    val logName: String = "",
    val objName: String = "",
    val className: String = "",
    val group: Int = 0,
    val type: Int = -1,
    val callStack: String = "",
    val payload: ByteArray = ByteArray(0),
    val payloadBrief: String = "",
    @ColumnInfo(name = "payloadCrc") val payloadCrc: Long = 0,
    val results: List<ByteArray> = emptyList(),
    val resultBrief: String = "",
    val resultCrc: Long = 0,
    val size: Int = 0,
    val time: Long = 0,
    val isRead: Boolean = false,
    // ---------- 详情页分类字段 ----------
    val algoName: String = "",
    val keyType: String = "",
    val keyData: ByteArray = ByteArray(0),
    val paramDesc: String = "",
    val resultKind: String = "",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LogRecordEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

@Entity(
    tableName = "http_record",
    indices = [Index("sessionId"), Index("time"), Index("host"), Index("url"), Index("packageName")],
)
data class HttpRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String = "",
    /** 归属的应用包名（Hook 抓包时是目标应用，VPN 抓包时按进程归属） */
    val packageName: String = "",
    val protocol: String = "HTTP",
    val method: String = "",
    val url: String = "",
    val host: String = "",
    val path: String = "",
    val httpVersion: String = "1.1",
    val statusCode: Int = 0,
    val statusText: String = "",
    val requestHeaders: List<String> = emptyList(),
    val responseHeaders: List<String> = emptyList(),
    val requestBody: ByteArray = ByteArray(0),
    val responseBody: ByteArray = ByteArray(0),
    val requestBodyTruncated: Boolean = false,
    val responseBodyTruncated: Boolean = false,
    val requestSize: Long = 0,
    val responseSize: Long = 0,
    val durationMs: Long = 0,
    val time: Long = 0,
    val mimeType: String = "",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpRecordEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

/**
 * HTTP 记录的轻量投影 —— **不含 requestBody / responseBody 两个 BLOB**。
 *
 * 【为什么必须有这个类】
 * Android SQLite 的 CursorWindow 上限约 2MB，一条 3.57MB 的响应体
 * 就会把整个窗口打爆。只要列表/分页/搜索/统计里的任何一条记录
 * 用 `SELECT *` 拉进窗口，那个查询就整体报
 * `IllegalStateException: Couldn't read row`，而且**同一个会话里
 * 所有后续查询都跟着报错**（窗口被脏数据占用）。
 *
 * 所以：列表类查询一律用本投影（BLOB 不进来），
 * 只有按 id 取单条的详情查询才用 HttpRecordEntity。
 */
class HttpRecordSummaryEntity(
    val id: Long = 0,
    val sessionId: String = "",
    val packageName: String = "",
    val protocol: String = "HTTP",
    val method: String = "",
    val url: String = "",
    val host: String = "",
    val path: String = "",
    val httpVersion: String = "1.1",
    val statusCode: Int = 0,
    val statusText: String = "",
    val requestHeaders: List<String> = emptyList(),
    val responseHeaders: List<String> = emptyList(),
    val requestBodyTruncated: Boolean = false,
    val responseBodyTruncated: Boolean = false,
    val requestSize: Long = 0,
    val responseSize: Long = 0,
    val durationMs: Long = 0,
    val time: Long = 0,
    val mimeType: String = "",
)

@Entity(
    tableName = "packet_data",
    indices = [Index("sessionId"), Index("time"), Index("remoteAddress")],
)
data class PacketDataEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String = "",
    val protocol: String = "TCP",
    val remoteAddress: String = "",
    val remotePort: Int = 0,
    val localPort: Int = 0,
    val outbound: Boolean = true,
    val payload: ByteArray = ByteArray(0),
    val size: Int = 0,
    val time: Long = 0,
    val wsFrameType: String = "",
    val stackTrace: String = "",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PacketDataEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

@Entity(tableName = "capture_session", indices = [Index("startedAt")])
data class CaptureSessionEntity(
    @PrimaryKey val sessionId: String,
    val packageName: String = "",
    val appLabel: String = "",
    val state: String = "IDLE",
    val startedAt: Long = 0,
    val stoppedAt: Long? = null,
    /**
     * 是否过滤本机回环请求。
     *
     * 【D2 修复】默认改回 false：
     * 应用 → 本地代理（127.0.0.1:5266 这类）的回环流量是**真实业务流量**，
     * 默认过滤会把它们全部吞掉，造成「抓包失灵」的假象。
     * 需要时用户显式开启，而不是默认静默丢弃。
     */
    val filterLocalRequests: Boolean = false,
    val recordCount: Int = 0,
)

@Entity(tableName = "connect_record", indices = [Index("sessionId"), Index("remoteAddress")])
data class ConnectRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String = "",
    val remoteAddress: String = "",
    val remotePort: Int = 0,
    val connectTime: Long = 0,
    val closeTime: Long? = null,
    val packetCount: Int = 0,
    val sentBytes: Long = 0,
    val receivedBytes: Long = 0,
)

@Entity(tableName = "stack_record", indices = [Index("ownerId")])
data class StackRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ownerId: Long = 0,
    val frames: List<String> = emptyList(),
    val time: Long = 0,
)

/**
 * 报文的轻量投影 —— **不含 payload BLOB**。
 *
 * 同 HttpRecordSummaryEntity：列表/分页不能带 BLOB，
 * 否则 CursorWindow 被打爆后整个会话的查询全废。
 */
class PacketSummaryEntity(
    val id: Long = 0,
    val sessionId: String = "",
    val protocol: String = "TCP",
    val remoteAddress: String = "",
    val remotePort: Int = 0,
    val localPort: Int = 0,
    val outbound: Boolean = true,
    val size: Int = 0,
    val time: Long = 0,
    val wsFrameType: String = "",
)
