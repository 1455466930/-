package com.sfzsgybb.ai.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CaptureDao {

    // ---------- 会话 ----------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: CaptureSessionEntity)

    @Query("SELECT * FROM capture_session ORDER BY startedAt DESC")
    fun observeSessions(): Flow<List<CaptureSessionEntity>>

    @Query("SELECT * FROM capture_session ORDER BY startedAt DESC LIMIT :limit")
    suspend fun sessions(limit: Int): List<CaptureSessionEntity>

    @Query("SELECT * FROM capture_session WHERE sessionId = :id")
    suspend fun session(id: String): CaptureSessionEntity?

    @Query("SELECT * FROM capture_session WHERE state = 'RUNNING' LIMIT 1")
    suspend fun runningSession(): CaptureSessionEntity?

    @Query("DELETE FROM capture_session WHERE sessionId IN (:ids)")
    suspend fun deleteSessions(ids: List<String>)

    @Query("UPDATE capture_session SET recordCount = :count WHERE sessionId = :id")
    suspend fun updateRecordCount(id: String, count: Int)

    // ---------- HTTP ----------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHttp(record: HttpRecordEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHttpAll(records: List<HttpRecordEntity>): List<Long>

    /**
     * 增量游标分页。cursor 形如 "v2:<time>:<h|t>:<id>"，
     * 与 MCP network_list 的 nextAfterId 格式保持一致。
     *
     * 【D1 修复】用轻量投影（不含 requestBody/responseBody），
     * 否则一条大响应体就把 CursorWindow 打爆，整个会话查询全废。
     */
    @Query(
        """
        SELECT id, sessionId, packageName, protocol, method, url, host, path,
               httpVersion, statusCode, statusText, requestHeaders, responseHeaders,
               requestBodyTruncated, responseBodyTruncated,
               requestSize, responseSize, durationMs, time, mimeType
        FROM http_record
        WHERE sessionId = :sessionId
          AND (time < :afterTime OR (time = :afterTime AND id < :afterId))
        ORDER BY time DESC, id DESC LIMIT :limit
        """
    )
    suspend fun httpPage(sessionId: String, afterTime: Long, afterId: Long, limit: Int): List<HttpRecordSummaryEntity>

    @Query("SELECT * FROM http_record WHERE id = :id")
    suspend fun httpById(id: Long): HttpRecordEntity?

    /** substr 切 body（BLOB 不进 CursorWindow）。 */
    @Query("SELECT substr(requestBody, :offset + 1, :length) FROM http_record WHERE id = :id")
    suspend fun requestBodySlice(id: Long, offset: Int, length: Int): ByteArray?

    @Query("SELECT substr(responseBody, :offset + 1, :length) FROM http_record WHERE id = :id")
    suspend fun responseBodySlice(id: Long, offset: Int, length: Int): ByteArray?

    /**
     * 读元数据（不含 BLOB）—— 详情/信息工具用。
     * 【D1】单行大 BLOB 会把 CursorWindow 打爆。
     */
    @Query(
        """
        SELECT id, sessionId, packageName, protocol, method, url, host, path,
               httpVersion, statusCode, statusText, requestHeaders, responseHeaders,
               requestBodyTruncated, responseBodyTruncated,
               requestSize, responseSize, durationMs, time, mimeType
        FROM http_record WHERE id = :id
        """
    )
    suspend fun httpMetaById(id: Long): HttpRecordSummaryEntity?

    @Query(
        """
        SELECT id, sessionId, packageName, protocol, method, url, host, path,
               httpVersion, statusCode, statusText, requestHeaders, responseHeaders,
               requestBodyTruncated, responseBodyTruncated,
               requestSize, responseSize, durationMs, time, mimeType
        FROM http_record
        WHERE sessionId = :sessionId
          AND (url LIKE '%' || :q || '%' OR host LIKE '%' || :q || '%')
        ORDER BY time DESC LIMIT :limit OFFSET :offset
        """
    )
    suspend fun searchHttp(sessionId: String, q: String, limit: Int, offset: Int): List<HttpRecordSummaryEntity>

    @Query("SELECT COUNT(*) FROM http_record WHERE sessionId = :sessionId")
    suspend fun httpCount(sessionId: String): Long

    @Query("DELETE FROM http_record WHERE id IN (:ids)")
    suspend fun deleteHttp(ids: List<Long>)

    // ---------- Packet ----------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPacket(packet: PacketDataEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPacketAll(packets: List<PacketDataEntity>): List<Long>

    @Query(
        """
        SELECT id, sessionId, protocol, remoteAddress, remotePort, localPort,
               outbound, size, time, wsFrameType
        FROM packet_data
        WHERE sessionId = :sessionId
          AND (time < :afterTime OR (time = :afterTime AND id < :afterId))
        ORDER BY time DESC, id DESC LIMIT :limit
        """
    )
    suspend fun packetPage(sessionId: String, afterTime: Long, afterId: Long, limit: Int): List<PacketSummaryEntity>

    @Query("SELECT * FROM packet_data WHERE id = :id")
    suspend fun packetById(id: Long): PacketDataEntity?

    @Query(
        """
        SELECT id, sessionId, protocol, remoteAddress, remotePort, localPort,
               outbound, size, time, wsFrameType
        FROM packet_data WHERE sessionId = :sessionId ORDER BY time ASC LIMIT :limit
        """
    )
    suspend fun packetsAsc(sessionId: String, limit: Int): List<PacketSummaryEntity>

    @Query("SELECT COUNT(*) FROM packet_data WHERE sessionId = :sessionId")
    suspend fun packetCount(sessionId: String): Long

    @Query("DELETE FROM packet_data WHERE id IN (:ids)")
    suspend fun deletePackets(ids: List<Long>)

    // ---------- 连接 / 调用栈 ----------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConnect(record: ConnectRecordEntity): Long

    @Query("SELECT * FROM connect_record WHERE sessionId = :sessionId ORDER BY connectTime DESC")
    suspend fun connects(sessionId: String): List<ConnectRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStack(record: StackRecordEntity): Long

    @Query("SELECT * FROM stack_record WHERE ownerId = :ownerId LIMIT 1")
    suspend fun stackOf(ownerId: Long): StackRecordEntity?

    // ---------- 清理 ----------

    @Query("DELETE FROM http_record WHERE sessionId IN (:ids)")
    suspend fun deleteHttpBySessions(ids: List<String>)

    @Query("DELETE FROM packet_data WHERE sessionId IN (:ids)")
    suspend fun deletePacketsBySessions(ids: List<String>)

    @Query("DELETE FROM http_record")
    suspend fun clearHttp()

    @Query("DELETE FROM packet_data")
    suspend fun clearPackets()

    @Query("DELETE FROM capture_session")
    suspend fun clearSessions()
}
