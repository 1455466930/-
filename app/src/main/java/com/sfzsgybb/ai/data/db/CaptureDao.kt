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
     */
    @Query(
        """
        SELECT * FROM http_record
        WHERE sessionId = :sessionId
          AND (time < :afterTime OR (time = :afterTime AND id < :afterId))
        ORDER BY time DESC, id DESC LIMIT :limit
        """
    )
    suspend fun httpPage(sessionId: String, afterTime: Long, afterId: Long, limit: Int): List<HttpRecordEntity>

    @Query("SELECT * FROM http_record WHERE id = :id")
    suspend fun httpById(id: Long): HttpRecordEntity?

    @Query(
        """
        SELECT * FROM http_record
        WHERE sessionId = :sessionId
          AND (url LIKE '%' || :q || '%' OR host LIKE '%' || :q || '%')
        ORDER BY time DESC LIMIT :limit OFFSET :offset
        """
    )
    suspend fun searchHttp(sessionId: String, q: String, limit: Int, offset: Int): List<HttpRecordEntity>

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
        SELECT * FROM packet_data
        WHERE sessionId = :sessionId
          AND (time < :afterTime OR (time = :afterTime AND id < :afterId))
        ORDER BY time DESC, id DESC LIMIT :limit
        """
    )
    suspend fun packetPage(sessionId: String, afterTime: Long, afterId: Long, limit: Int): List<PacketDataEntity>

    @Query("SELECT * FROM packet_data WHERE id = :id")
    suspend fun packetById(id: Long): PacketDataEntity?

    @Query("SELECT * FROM packet_data WHERE sessionId = :sessionId ORDER BY time ASC LIMIT :limit")
    suspend fun packetsAsc(sessionId: String, limit: Int): List<PacketDataEntity>

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
