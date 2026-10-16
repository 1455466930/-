package com.sfzsgybb.ai.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 日志 DAO。
 *
 * 分页采用「增量游标」而非 OFFSET：
 * 抓包/日志是持续写入的流，OFFSET 分页在插入新数据时会错位重复。
 * 这里用 time+id 复合游标，与原版 native 侧 nextAfterId 语义一致。
 */
@Dao
interface LogRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: LogRecordEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<LogRecordEntity>): List<Long>

    @Update
    suspend fun update(record: LogRecordEntity)

    @Query("SELECT * FROM log_record ORDER BY time DESC, id DESC LIMIT :limit")
    fun observeLatest(limit: Int = 300): Flow<List<LogRecordEntity>>

    /** 增量游标分页：取比 (afterTime, afterId) 更旧的一页。 */
    @Query(
        """
        SELECT * FROM log_record
        WHERE (time < :afterTime) OR (time = :afterTime AND id < :afterId)
        ORDER BY time DESC, id DESC
        LIMIT :limit
        """
    )
    suspend fun pageBefore(afterTime: Long, afterId: Long, limit: Int): List<LogRecordEntity>

    @Query(
        """
        SELECT * FROM log_record
        WHERE (:packageName IS NULL OR packageName = :packageName)
          AND (:group < 0 OR `group` = :group)
        ORDER BY time DESC, id DESC
        LIMIT :limit OFFSET :offset
        """
    )
    fun observeFiltered(packageName: String?, group: Int, limit: Int, offset: Int): Flow<List<LogRecordEntity>>

    @Query(
        """
        SELECT * FROM log_record
        WHERE logName LIKE '%' || :q || '%'
           OR className LIKE '%' || :q || '%'
           OR objName LIKE '%' || :q || '%'
           OR payloadBrief LIKE '%' || :q || '%'
           OR resultBrief LIKE '%' || :q || '%'
        ORDER BY time DESC, id DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun search(q: String, limit: Int, offset: Int): List<LogRecordEntity>

    @Query("SELECT * FROM log_record WHERE id = :id")
    suspend fun byId(id: Long): LogRecordEntity?

    @Query("SELECT COUNT(*) FROM log_record")
    suspend fun count(): Long

    @Query("SELECT COUNT(*) FROM log_record WHERE (:packageName IS NULL OR packageName = :packageName)")
    suspend fun countByPackage(packageName: String?): Long

    @Query("UPDATE log_record SET isRead = 1 WHERE id IN (:ids)")
    suspend fun markRead(ids: List<Long>)

    @Query("DELETE FROM log_record WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM log_record")
    suspend fun clear()

    @Query("DELETE FROM log_record WHERE packageName = :packageName")
    suspend fun clearByPackage(packageName: String)

    @Query("DELETE FROM log_record WHERE id NOT IN (SELECT id FROM log_record ORDER BY time DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)

    /** 分组统计，用于 MCP get_log_graph。 */
    @Query("SELECT `group` AS g, COUNT(*) AS c FROM log_record GROUP BY `group` ORDER BY c DESC")
    suspend fun groupCounts(): List<GroupCount>

    /** 按 logName 聚合，用于图表。 */
    @Query(
        """
        SELECT logName AS name, COUNT(*) AS c FROM log_record
        GROUP BY logName ORDER BY c DESC LIMIT :limit
        """
    )
    suspend fun topLogNames(limit: Int): List<NameCount>

    /**
     * 按应用包名分组统计。
     *
     * 用于日志页的应用卡片列表：显示条数、最近活动时间、日志总大小。
     */
    @Query(
        """
        SELECT packageName AS name,
               COUNT(*) AS c,
               MAX(time) AS lastTime,
               SUM(LENGTH(payload)) AS bytes
        FROM log_record
        WHERE packageName IS NOT NULL AND packageName != ''
        GROUP BY packageName ORDER BY MAX(time) DESC
        """
    )
    suspend fun packageCounts(): List<PackageStat>
}

data class GroupCount(val g: Int, val c: Int)
data class NameCount(val name: String, val c: Int)

/** 应用维度的日志统计。 */
data class PackageStat(
    val name: String,
    val c: Int,
    val lastTime: Long,
    val bytes: Long,
)
