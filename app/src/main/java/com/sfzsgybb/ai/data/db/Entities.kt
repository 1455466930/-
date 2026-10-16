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
    val filterLocalRequests: Boolean = true,
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
