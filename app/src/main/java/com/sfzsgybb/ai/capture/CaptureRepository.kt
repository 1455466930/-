package com.sfzsgybb.ai.capture

import com.sfzsgybb.ai.capture.http.BodyDecoder
import com.sfzsgybb.ai.capture.http.ContentClassifier
import com.sfzsgybb.ai.capture.http.ContentKind
import com.sfzsgybb.ai.data.db.CaptureDao
import com.sfzsgybb.ai.data.db.toEntity
import com.sfzsgybb.ai.data.db.toModel
import com.sfzsgybb.ai.data.model.CaptureSession
import com.sfzsgybb.ai.data.model.CaptureState
import com.sfzsgybb.ai.data.model.HttpRecord
import com.sfzsgybb.ai.data.model.PacketData
import com.sfzsgybb.ai.data.model.Protocol
import com.sfzsgybb.ai.data.model.ValueMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.net.URI
import java.util.UUID

/**
 * 抓包仓库。
 *
 * 与原版差异：
 *  - 原版把 network_list 的三种协议（HTTP/TCP/WS）分成独立查询，
 *    MCP 侧再手工合并排序；这里直接提供统一的混合列表并内建游标。
 *  - 原版 response body 解压失败即丢弃；这里保留压缩前原始字节并标记。
 */
class CaptureRepository(private val dao: CaptureDao) {

    // ---------- 状态 ----------

    data class Status(
        val running: Boolean,
        val sessionId: String?,
        val packageName: String?,
        val recordCount: Long,
        val startedAt: Long,
        val filterLocalRequests: Boolean,
    )

    @Volatile private var activeSessionId: String? = null
    @Volatile private var activePackage: String? = null
    @Volatile private var activeFilterLocal: Boolean = true
    @Volatile private var activeStartedAt: Long = 0L

    /**
     * 取当前活跃会话 ID。
     *
     * 【为什么不能只用内存变量】
     * activeSessionId 是进程内变量，APP 被系统回收后重启就丢了，
     * 而数据库里的会话状态仍然是 RUNNING。此时如果用内存变量判断，
     * 抓包数据会被静默丢弃 —— 表现就是「会话显示运行中，但没有数据」，
     * 也就是用户反馈的「抓包有时候有有时候没有」。
     *
     * 因此这里改为：内存没有时从数据库恢复一次（一次性，之后走内存）。
     */
    private suspend fun resolvedSessionId(): String? {
        activeSessionId?.let { return it }
        // 从数据库恢复：找最近一个 RUNNING 会话
        val s = runCatching { dao.runningSession() }.getOrNull() ?: return null
        activeSessionId = s.sessionId
        activePackage = s.packageName
        activeFilterLocal = s.filterLocalRequests
        activeStartedAt = s.startedAt
        return s.sessionId
    }

    /** 同步版本：只读内存，用于不能挂起的热路径。 */
    fun currentSessionId(): String? = activeSessionId

    /** 挂起版本：内存为空时从数据库恢复。供数据写入路径使用。 */
    suspend fun ensureSessionId(): String? = resolvedSessionId()

    /**
     * 取会话 ID；**没有活跃会话时自动创建一个**。
     *
     * 【为什么需要自动创建】
     * VPN / MITM 代理独立于「抓包会话」运行：用户可能先启动 VPN，
     * 或者 APP 被系统回收后重启导致内存里的会话丢了。
     * 此时没有 RUNNING 会话，数据会被静默丢弃 ——
     * 表现为「应用请求正常，但抓包页一条都没有」。
     *
     * 所以写入路径改用这个方法：没有会话就现建一个，保证流量不丢。
     */
    suspend fun ensureOrCreateSession(packageName: String = "自动抓包"): String? {
        resolvedSessionId()?.let { return it }
        return try {
            val s = start(packageName)
            android.util.Log.i("CaptureRepository", "自动创建会话: ${s.sessionId}")
            activeSessionId = s.sessionId
            s.sessionId
        } catch (t: Throwable) {
            android.util.Log.e("CaptureRepository", "自动创建会话失败", t)
            null
        }
    }

    suspend fun status(): Status {
        val id = resolvedSessionId()
        val count = id?.let { dao.httpCount(it) + dao.packetCount(it) } ?: 0L
        return Status(
            running = id != null,
            sessionId = id,
            packageName = activePackage,
            recordCount = count,
            startedAt = activeStartedAt,
            filterLocalRequests = activeFilterLocal,
        )
    }

    // ---------- 会话生命周期 ----------

    suspend fun start(packageName: String, filterLocalRequests: Boolean = true, appLabel: String = ""): CaptureSession {
        activeSessionId?.let { dao.updateRecordCount(it, 0) }
        val session = CaptureSession(
            sessionId = UUID.randomUUID().toString(),
            packageName = packageName,
            appLabel = appLabel,
            state = CaptureState.RUNNING,
            startedAt = System.currentTimeMillis(),
            filterLocalRequests = filterLocalRequests,
        )
        dao.upsertSession(session.toEntity())
        activeSessionId = session.sessionId
        activePackage = packageName
        activeFilterLocal = filterLocalRequests
        activeStartedAt = session.startedAt
        return session
    }

    suspend fun stop(sessionId: String? = null): Boolean {
        val id = sessionId ?: activeSessionId ?: return false
        val s = dao.session(id) ?: return false
        dao.upsertSession(s.copy(state = CaptureState.STOPPED.name, stoppedAt = System.currentTimeMillis()))
        if (id == activeSessionId) {
            activeSessionId = null
            activePackage = null
        }
        return true
    }

    fun shouldFilterLocal(): Boolean = activeFilterLocal

    suspend fun sessions(limit: Int): List<CaptureSession> =
        dao.sessions(limit).map { it.toModel() }

    fun observeSessions(): Flow<List<CaptureSession>> =
        dao.observeSessions().map { list -> list.map { it.toModel() } }

    /**
     * 删除会话及其全部流量记录。
     * 若删的是当前活跃会话，同时清空内存状态，避免后续数据挂到已删除的会话上。
     */
    suspend fun deleteSessions(ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        dao.deleteHttpBySessions(ids)
        dao.deletePacketsBySessions(ids)
        dao.deleteSessions(ids)
        if (ids.contains(activeSessionId)) {
            activeSessionId = null
            activePackage = null
            activeStartedAt = 0L
        }
        return ids.size
    }

    /** 删除单个会话。 */
    suspend fun deleteSession(id: String): Boolean = deleteSessions(listOf(id)) > 0

    /**
     * 清空所有已结束的会话（保留正在运行的那个）。
     * @return 删除的会话数
     */
    suspend fun clearStoppedSessions(): Int {
        val all = dao.sessions(1000)
        val stopped = all.filter { it.state != CaptureState.RUNNING.name }.map { it.sessionId }
        if (stopped.isEmpty()) return 0
        return deleteSessions(stopped)
    }

    /** 清空全部会话与流量（含正在运行的）。 */
    suspend fun clearAllSessions(): Int {
        val all = dao.sessions(1000).map { it.sessionId }
        activeSessionId = null
        activePackage = null
        activeStartedAt = 0L
        dao.clearHttp()
        dao.clearPackets()
        dao.clearSessions()
        return all.size
    }

    /** 删除某一会话下的单条记录。 */
    suspend fun deleteHttpRecord(id: Long): Boolean {
        val rec = dao.httpById(id) ?: return false
        dao.deleteHttp(listOf(id))
        bumpCount(rec.sessionId)
        return true
    }

    /** 删除单条报文记录。 */
    suspend fun deletePacketRecord(id: Long): Boolean {
        val rec = dao.packetById(id) ?: return false
        dao.deletePackets(listOf(id))
        bumpCount(rec.sessionId)
        return true
    }

    // ---------- 写入 ----------

    /** 是否应记录：按过滤规则丢弃本机回环请求。 */
    fun shouldRecord(url: String?, remoteAddress: String?): Boolean {
        if (!activeFilterLocal) return true
        val host = url?.let { runCatching { URI(it).host }.getOrNull() } ?: remoteAddress
        if (host == null) return true
        return !(host == "127.0.0.1" || host == "localhost" || host == "::1" ||
            host.startsWith("10.0.2.2") || host.endsWith(".local"))
    }

    suspend fun insertHttp(record: HttpRecord): Long {
        val sid = record.sessionId.ifBlank { ensureSessionId() ?: "" }
        if (sid.isBlank()) return -1L
        val host = runCatching { URI(record.url).host }.getOrNull() ?: record.host
        val path = runCatching { URI(record.url).path }.getOrNull() ?: record.path
        val mime = record.responseHeaders
            .firstOrNull { it.name.equals("Content-Type", true) }?.value?.substringBefore(';')?.trim()
            ?: record.mimeType

        val id = dao.insertHttp(
            record.copy(
                sessionId = sid,
                host = host ?: "",
                path = path ?: "",
                mimeType = mime,
                requestSize = record.requestBody.size.toLong(),
                responseSize = record.responseBody.size.toLong(),
            ).toEntity()
        )
        bumpCount(sid)
        return id
    }

    suspend fun insertPacket(packet: PacketData): Long {
        val sid = packet.sessionId.ifBlank { ensureSessionId() ?: "" }
        if (sid.isBlank()) return -1L
        val id = dao.insertPacket(packet.copy(sessionId = sid).toEntity())
        bumpCount(sid)
        return id
    }

    private suspend fun bumpCount(sessionId: String) {
        if (sessionId.isBlank()) return
        val n = dao.httpCount(sessionId) + dao.packetCount(sessionId)
        dao.updateRecordCount(sessionId, n.toInt())
    }

    // ---------- 查询 ----------

    suspend fun httpById(id: Long): HttpRecord? = dao.httpById(id)?.toModel()
    suspend fun packetById(id: Long): PacketData? = dao.packetById(id)?.toModel()

    /**
     * 混合列表项。HTTP 与 packet 合并后按 time desc, id desc 排序。
     * kind 首字母用于游标编码（h/t）。
     */
    data class NetworkItem(
        val id: Long,
        val kind: String,      // "http" / "tcp" / "websocket"
        val time: Long,
        val protocol: String,
        val method: String?,
        val url: String?,
        val statusCode: Int?,
        val remoteAddress: String?,
        val size: Int,
        val brief: String,
    )

    suspend fun networkList(
        sessionId: String,
        afterTime: Long,
        afterId: Long,
        limit: Int,
        query: String?,
        protocolFilter: Protocol?,
    ): List<NetworkItem> {
        val items = ArrayList<NetworkItem>(limit * 2)

        if (protocolFilter == null || protocolFilter == Protocol.HTTP || protocolFilter == Protocol.WEBSOCKET) {
            dao.httpPage(sessionId, afterTime, afterId, limit).forEach { e ->
                val m = e.toModel()
                items += NetworkItem(
                    id = m.id, kind = "http", time = m.time, protocol = m.protocol.name,
                    method = m.method, url = m.url, statusCode = m.statusCode,
                    remoteAddress = m.host, size = m.responseBody.size,
                    brief = "${m.method} ${m.path}",
                )
            }
        }
        if (protocolFilter == null || protocolFilter == Protocol.TCP || protocolFilter == Protocol.UNKNOWN) {
            dao.packetPage(sessionId, afterTime, afterId, limit).forEach { e ->
                val m = e.toModel()
                items += NetworkItem(
                    id = m.id, kind = if (m.protocol == Protocol.WEBSOCKET) "websocket" else "tcp",
                    time = m.time, protocol = m.protocol.name,
                    method = null, url = null, statusCode = null,
                    remoteAddress = "${m.remoteAddress}:${m.remotePort}",
                    size = m.payload.size,
                    brief = if (m.outbound) "→ ${m.size}B" else "← ${m.size}B",
                )
            }
        }

        var sorted = items.sortedWith(compareByDescending<NetworkItem> { it.time }.thenByDescending { it.id })

        if (!query.isNullOrBlank()) {
            val q = query.lowercase()
            sorted = sorted.filter { item ->
                (item.url?.lowercase()?.contains(q) == true) ||
                    (item.remoteAddress?.lowercase()?.contains(q) == true) ||
                    (item.method?.lowercase()?.contains(q) == true) ||
                    (item.brief.lowercase().contains(q)) ||
                    bodyContains(item, q)
            }
        }
        return sorted.take(limit)
    }

    private suspend fun bodyContains(item: NetworkItem, q: String): Boolean {
        val bytes = if (item.kind == "http") {
            val r = dao.httpById(item.id) ?: return false
            r.requestBody + r.responseBody
        } else {
            dao.packetById(item.id)?.payload ?: return false
        }
        if (bytes.isEmpty()) return false
        // 三模式匹配：原文（ASCII 大小写不敏感）、Hex、Base64
        val text = String(bytes, Charsets.ISO_8859_1).lowercase()
        if (text.contains(q)) return true
        val hex = toHex(bytes).lowercase()
        if (hex.contains(q)) return true
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP).lowercase()
        return b64.contains(q)
    }

    data class Info(val protocol: String, val summary: String, val size: Int, val time: Long)

    suspend fun info(id: Long): Info? {
        dao.httpById(id)?.let { r ->
            return Info(r.protocol, "${r.method} ${r.url} → ${r.statusCode}", r.responseBody.size, r.time)
        }
        dao.packetById(id)?.let { p ->
            return Info(p.protocol, "${p.remoteAddress}:${p.remotePort} ${if (p.outbound) "发送" else "接收"}", p.payload.size, p.time)
        }
        return null
    }

    suspend fun packetsAsc(sessionId: String, limit: Int): List<PacketData> =
        dao.packetsAsc(sessionId, limit).map { it.toModel() }

    suspend fun stackOf(recordId: Long, discardSystem: Boolean): List<String> {
        val frames = dao.stackOf(recordId)?.frames ?: emptyList()
        return if (discardSystem) frames.filterNot { it.startsWith("android.") || it.startsWith("java.") || it.startsWith("kotlin.") }
        else frames
    }

    suspend fun delete(ids: List<String>): Int {
        val httpIds = ArrayList<Long>()
        val pktIds = ArrayList<Long>()
        for (raw in ids) {
            val main = raw.substringBefore('-')
            val n = main.toLongOrNull() ?: continue
            if (dao.httpById(n) != null) httpIds += n else pktIds += n
        }
        if (httpIds.isNotEmpty()) dao.deleteHttp(httpIds)
        if (pktIds.isNotEmpty()) dao.deletePackets(pktIds)
        return httpIds.size + pktIds.size
    }

    suspend fun clearAll() {
        dao.clearHttp()
        dao.clearPackets()
        activeSessionId = null
    }

    // ---------- 渲染 ----------

    fun render(bytes: ByteArray, mode: ValueMode): String = when (mode) {
        ValueMode.TEXT -> String(bytes, Charsets.UTF_8)
        ValueMode.HEX -> toHex(bytes)
        ValueMode.BASE64 -> android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    fun classify(bytes: ByteArray): ContentKind = ContentClassifier.classify(null, bytes)

    /** 解码响应体（gzip/deflate）。 */
    fun decodeBody(bytes: ByteArray, contentEncoding: String?): BodyDecoder.Result =
        BodyDecoder.decode(bytes, contentEncoding)

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }
}
