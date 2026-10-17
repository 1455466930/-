package com.sfzsgybb.ai.data.db

import androidx.room.Dao
import androidx.room.Query

/**
 * MCP 分析类工具需要的查询。
 *
 * 这些查询是为了支持移植自 ProxyPin 的分析工具
 * （统计、域名汇总、Cookie 提取、API 端点归组等）。
 * 与 CaptureDao 分开，避免主 DAO 过于臃肿。
 */
@Dao
interface AnalysisDao {

    // ---------- 通用列表（带过滤） ----------

    /**
     * 按条件筛选 HTTP 记录。
     * 用 COALESCE/NULL 判断实现「参数为空则不过滤」。
     */
    @Query(
        """
        SELECT id, sessionId, source, packageName, protocol, method, url, host, path,
               httpVersion, statusCode, statusText, requestHeaders, responseHeaders,
               requestBodyTruncated, responseBodyTruncated,
               requestSize, responseSize, durationMs, time, mimeType
        FROM http_record
        WHERE (:sessionId = '' OR sessionId = :sessionId)
          AND (:domain = '' OR host LIKE '%' || :domain || '%')
          AND (:method = '' OR method = :method)
          AND (:statusCode = -1 OR statusCode = :statusCode)
          AND (:urlKeyword = '' OR url LIKE '%' || :urlKeyword || '%')
          AND (:source = '' OR source = :source)
        ORDER BY time DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun filterHttp(
        sessionId: String,
        domain: String,
        method: String,
        statusCode: Int,
        urlKeyword: String,
        limit: Int,
        offset: Int,
        source: String = "",
    ): List<HttpRecordSummaryEntity>

    @Query(
        """
        SELECT COUNT(*) FROM http_record
        WHERE (:sessionId = '' OR sessionId = :sessionId)
          AND (:domain = '' OR host LIKE '%' || :domain || '%')
          AND (:method = '' OR method = :method)
          AND (:statusCode = -1 OR statusCode = :statusCode)
          AND (:urlKeyword = '' OR url LIKE '%' || :urlKeyword || '%')
          AND (:source = '' OR source = :source)
        """
    )
    suspend fun countFilterHttp(
        sessionId: String,
        domain: String,
        method: String,
        statusCode: Int,
        urlKeyword: String,
        source: String = "",
    ): Long

    /** 取最近 N 条（分析工具用）。 */
    @Query(
        """
        SELECT id, sessionId, source, packageName, protocol, method, url, host, path,
               httpVersion, statusCode, statusText, requestHeaders, responseHeaders,
               requestBodyTruncated, responseBodyTruncated,
               requestSize, responseSize, durationMs, time, mimeType
        FROM http_record
        WHERE (:sessionId = '' OR sessionId = :sessionId)
          AND (:domain = '' OR host LIKE '%' || :domain || '%')
        ORDER BY time DESC LIMIT :limit
        """
    )
    suspend fun recentHttp(sessionId: String, domain: String, limit: Int): List<HttpRecordSummaryEntity>

    /**
     * 跨会话取最近的 HTTP 记录（流量列表用）。
     *
     * 为什么不用 networkList：那个按 sessionId 过滤，
     * 而 VPN 抓包时可能有多个会话，用户想看的是「全部」。
     */
    @Query(
        """
        SELECT id, sessionId, source, packageName, protocol, method, url, host, path,
               httpVersion, statusCode, statusText, requestHeaders, responseHeaders,
               requestBodyTruncated, responseBodyTruncated,
               requestSize, responseSize, durationMs, time, mimeType
        FROM http_record
        WHERE (:query = '' OR url LIKE '%' || :query || '%' OR host LIKE '%' || :query || '%')
          AND (:source = '' OR source = :source)
        ORDER BY id DESC LIMIT :limit
        """
    )
    suspend fun recentTrafficAll(query: String, limit: Int, source: String = ""): List<HttpRecordSummaryEntity>

    @Query(
        """
        SELECT COUNT(*) FROM http_record
        WHERE (:query = '' OR url LIKE '%' || :query || '%' OR host LIKE '%' || :query || '%')
          AND (:source = '' OR source = :source)
        """
    )
    suspend fun countTrafficAll(query: String, source: String = ""): Long

    @Query("SELECT * FROM http_record WHERE id = :id")
    suspend fun httpById(id: Long): HttpRecordEntity?

    // ---------- 大 BLOB 安全读取（D1 修复） ----------

    /**
     * 读元数据（不含两个 BLOB）—— 详情工具用它，避免单行大 BLOB
     * 直接把 CursorWindow 打爆（3.57MB 单行就会炸）。
     */
    @Query(
        """
        SELECT id, sessionId, source, packageName, protocol, method, url, host, path,
               httpVersion, statusCode, statusText, requestHeaders, responseHeaders,
               requestBodyTruncated, responseBodyTruncated,
               requestSize, responseSize, durationMs, time, mimeType
        FROM http_record WHERE id = :id
        """
    )
    suspend fun httpMetaById(id: Long): HttpRecordSummaryEntity?

    /**
     * 用 substr 切 body —— SQLite 在引擎内截断，**BLOB 不会整体进 CursorWindow**。
     * @param offset 从 0 起的字节偏移
     */
    @Query("SELECT substr(requestBody, :offset + 1, :length) FROM http_record WHERE id = :id")
    suspend fun requestBodySlice(id: Long, offset: Int, length: Int): ByteArray?

    @Query("SELECT substr(responseBody, :offset + 1, :length) FROM http_record WHERE id = :id")
    suspend fun responseBodySlice(id: Long, offset: Int, length: Int): ByteArray?

    /** 按应用包名分组统计（抓包页展示用）。 */
    @Query(
        """
        SELECT packageName AS name, COUNT(*) AS count FROM http_record
        GROUP BY packageName ORDER BY count DESC
        """
    )
    suspend fun groupByPackage(): List<NameCountRow>

    /** 取指定包名的最近 HTTP 记录。 */
    @Query(
        """
        SELECT id, sessionId, source, packageName, protocol, method, url, host, path,
               httpVersion, statusCode, statusText, requestHeaders, responseHeaders,
               requestBodyTruncated, responseBodyTruncated,
               requestSize, responseSize, durationMs, time, mimeType
        FROM http_record
        WHERE packageName = :pkg
        ORDER BY id DESC LIMIT :limit
        """
    )
    suspend fun httpByPackage(pkg: String, limit: Int): List<HttpRecordSummaryEntity>

    /** 最近抓到的所有 HTTP 记录（跨包名，抓包页「全部」视图）。 */
    @Query(
        """
        SELECT id, sessionId, source, packageName, protocol, method, url, host, path,
               httpVersion, statusCode, statusText, requestHeaders, responseHeaders,
               requestBodyTruncated, responseBodyTruncated,
               requestSize, responseSize, durationMs, time, mimeType
        FROM http_record
        WHERE (:source = '' OR source = :source)
        ORDER BY id DESC LIMIT :limit
        """
    )
    suspend fun recentHttpAll(limit: Int, source: String = ""): List<HttpRecordSummaryEntity>

    @Query("SELECT * FROM http_record WHERE id IN (:ids)")
    suspend fun httpByIds(ids: List<Long>): List<HttpRecordEntity>

    // ---------- 统计 ----------

    @Query(
        """
        SELECT host AS name, COUNT(*) AS count FROM http_record
        WHERE (:sessionId = '' OR sessionId = :sessionId)
          AND host != ''
        GROUP BY host ORDER BY count DESC LIMIT :limit
        """
    )
    suspend fun topDomains(sessionId: String, limit: Int): List<NameCountRow>

    @Query(
        """
        SELECT statusCode AS code, COUNT(*) AS count FROM http_record
        WHERE (:sessionId = '' OR sessionId = :sessionId)
        GROUP BY statusCode ORDER BY count DESC
        """
    )
    suspend fun statusDistribution(sessionId: String): List<StatusCountRow>

    @Query(
        """
        SELECT method AS name, COUNT(*) AS count FROM http_record
        WHERE (:sessionId = '' OR sessionId = :sessionId)
        GROUP BY method ORDER BY count DESC
        """
    )
    suspend fun methodDistribution(sessionId: String): List<NameCountRow>

    @Query(
        """
        SELECT AVG(durationMs) AS avgMs, MAX(durationMs) AS maxMs,
               MIN(durationMs) AS minMs, COUNT(*) AS count
        FROM http_record
        WHERE (:sessionId = '' OR sessionId = :sessionId) AND durationMs > 0
        """
    )
    suspend fun durationStats(sessionId: String): DurationStatsRow?

    @Query(
        """
        SELECT COUNT(*) FROM http_record
        WHERE (:sessionId = '' OR sessionId = :sessionId)
        """
    )
    suspend fun totalHttp(sessionId: String): Long

    // ---------- 域名汇总 ----------

    @Query(
        """
        SELECT path FROM http_record
        WHERE (:sessionId = '' OR sessionId = :sessionId) AND host = :host
        ORDER BY time DESC LIMIT :limit
        """
    )
    suspend fun pathsOfHost(sessionId: String, host: String, limit: Int): List<String>

    @Query(
        """
        SELECT mimeType AS name, COUNT(*) AS count FROM http_record
        WHERE (:sessionId = '' OR sessionId = :sessionId) AND host = :host AND mimeType != ''
        GROUP BY mimeType ORDER BY count DESC
        """
    )
    suspend fun mimeOfHost(sessionId: String, host: String): List<NameCountRow>

    // ---------- 全文搜索（正文里找关键词） ----------

    @Query(
        """
        SELECT id, sessionId, source, packageName, protocol, method, url, host, path,
               httpVersion, statusCode, statusText, requestHeaders, responseHeaders,
               requestBodyTruncated, responseBodyTruncated,
               requestSize, responseSize, durationMs, time, mimeType
        FROM http_record
        WHERE (:sessionId = '' OR sessionId = :sessionId)
          AND (:startTime = 0 OR time >= :startTime)
          AND (:endTime = 0 OR time <= :endTime)
        ORDER BY time DESC LIMIT :scanLimit
        """
    )
    suspend fun scanForBodySearch(
        sessionId: String, startTime: Long, endTime: Long, scanLimit: Int,
    ): List<HttpRecordSummaryEntity>

    // ---------- 删除 ----------

    @Query("SELECT COUNT(*) FROM http_record WHERE sessionId = :sessionId")
    suspend fun countBySession(sessionId: String): Long

    @Query("SELECT COUNT(*) FROM packet_data WHERE sessionId = :sessionId")
    suspend fun countPacketsBySession(sessionId: String): Long
}

data class NameCountRow(val name: String, val count: Int)
data class StatusCountRow(val code: Int, val count: Int)
data class DurationStatsRow(val avgMs: Double, val maxMs: Long, val minMs: Long, val count: Int)
