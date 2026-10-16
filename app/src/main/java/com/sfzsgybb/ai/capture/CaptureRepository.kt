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
     * 取会话 ID；**没有匹配的活跃会话时自动创建一个**。
     *
     * 【D4 修复】按 packageName 匹配：同一个应用的流量应该进同一个会话。
     * 之前的实现只要有 RUNNING 会话就复用，导致「Hook 抓包」这个会话
     * 把追剧鸭、QQ、应用商店的流量全吞进去，按 sessionId 查就漏数据。
     *
     * 匹配顺序：
     *  1. 内存中的活跃会话且包名一致 → 复用
     *  2. 数据库里的 RUNNING 会话且包名一致 → 恢复复用
     *  3. 都没有 → 新建一个（包名取传入值）
     */
    suspend fun ensureOrCreateSession(packageName: String = "自动抓包"): String? {
        // 1) 内存中的活跃会话，包名一致才复用
        activeSessionId?.let { id ->
            if (activePackage == packageName) return id
        }
        // 2) 数据库里的 RUNNING 会话，包名一致才复用
        val running = runCatching { dao.runningSession() }.getOrNull()
        if (running != null && running.packageName == packageName) {
            activeSessionId = running.sessionId
            activePackage = running.packageName
            activeFilterLocal = running.filterLocalRequests
            activeStartedAt = running.startedAt
            return running.sessionId
        }
        // 3) 都没有匹配的 → 新建
        return try {
            val s = start(packageName)
            android.util.Log.i("CaptureRepository", "自动创建会话: ${s.sessionId} pkg=$packageName")
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

    /**
     * 开始一个会话。
     *
     * 【D2 修复】filterLocalRequests 默认 false：
     * 应用 → 本地代理（127.0.0.1:5266 这类）的回环流量是**真实业务流量**，
     * 默认过滤会把它们吞掉，造成「抓包失灵」假象。
     */
    suspend fun start(packageName: String, filterLocalRequests: Boolean = false, appLabel: String = ""): CaptureSession {
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

    /**
     * 是否应记录：按过滤规则丢弃「自身代理」产生的流量。
     *
     * 【D2 修复】之前把 127.0.0.1 / localhost 一刀切全过滤，
     * 把「应用 → 本地代理（127.0.0.1:5266 这类）」的真实业务流量也吞了。
     * 现在只过滤我们自己产生的流量：
     *  - MITM 代理端口（8888）
     *  - VPN 虚拟网卡地址（10.0.0.2）
     * 应用连其他本地端口的流量照常记录。
     */
    fun shouldRecord(url: String?, remoteAddress: String?): Boolean {
        if (!activeFilterLocal) return true
        val host = url?.let { runCatching { URI(it).host }.getOrNull() } ?: remoteAddress
        if (host == null) return true
        // 只过滤「我们自己的代理/虚拟网卡」；应用连本地其他端口的保留
        val port = url?.let { runCatching { URI(it).port }.getOrNull() }
        val isOurProxy = (host == "127.0.0.1" || host == "localhost") &&
            (port == 8888 || port == 19191 || port == 19192)
        val isVpnSelf = host == "10.0.0.2"
        return !(isOurProxy || isVpnSelf)
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
     * 给一条 HTTP 记录挂调用栈（Hook 抓包时采集，供 network_stacks 用）。
     */
    suspend fun attachStack(ownerId: Long, frames: List<String>) {
        if (ownerId <= 0 || frames.isEmpty()) return
        runCatching {
            dao.insertStack(
                com.sfzsgybb.ai.data.db.StackRecordEntity(
                    ownerId = ownerId,
                    frames = frames,
                    time = System.currentTimeMillis(),
                )
            )
        }
    }

    /** 读元数据（不含 BLOB）——【D1】信息类工具用，避免 CursorWindow 打爆。 */
    suspend fun httpMetaById(id: Long): HttpRecord? = dao.httpMetaById(id)?.toModel()

    /**
     * 用 substr 切 HTTP body（BLOB 不进 CursorWindow）。
     * 【D1】http_body / network_packetBody 的回退路径用这个。
     */
    suspend fun httpBodySlice(id: Long, offset: Int, length: Int, fromResponse: Boolean): ByteArray? =
        if (fromResponse) dao.responseBodySlice(id, offset, length)
        else dao.requestBodySlice(id, offset, length)

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
                // D1：packetPage 是轻量投影（无 payload BLOB），直接用 size 字段
                items += NetworkItem(
                    id = e.id, kind = "tcp",
                    time = e.time, protocol = e.protocol,
                    method = null, url = null, statusCode = null,
                    remoteAddress = "${e.remoteAddress}:${e.remotePort}",
                    size = e.size,
                    brief = if (e.outbound) "→ ${e.size}B" else "← ${e.size}B",
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
        // 【D1】用 substr 切前 64KB（足够内容搜索），BLOB 不进 CursorWindow。
        // 注意：这里保留 hex/base64 匹配是**有意的** —— 用户显式搜 hex 时应该命中；
        // 与 search_requests 的 bodyKeyword 不同（那个是「关键词搜索」，
        // 二进制 fallback 成 hex 会造成假命中，这里不会，因为 q 是显式给出的）。
        val SEARCH_SLICE = 64 * 1024
        val bytes = if (item.kind == "http") {
            val req = dao.requestBodySlice(item.id, 0, SEARCH_SLICE) ?: ByteArray(0)
            val resp = dao.responseBodySlice(item.id, 0, SEARCH_SLICE) ?: ByteArray(0)
            if (req.size + resp.size >= SEARCH_SLICE) req + resp.take(SEARCH_SLICE - req.size)
            else req + resp
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

    data class Info(val protocol: String, val summary: String, val size: Long, val time: Long)

    suspend fun info(id: Long): Info? {
        // 【D1】meta 无 BLOB；size 用 responseSize 字段（与 BLOB 长度一致）
        dao.httpMetaById(id)?.let { r ->
            return Info(r.protocol, "${r.method} ${r.url} → ${r.statusCode}", r.responseSize, r.time)
        }
        dao.packetById(id)?.let { p ->
            return Info(p.protocol, "${p.remoteAddress}:${p.remotePort} ${if (p.outbound) "发送" else "接收"}", p.payload.size.toLong(), p.time)
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
            // 【D1】存在性判断用 meta（无 BLOB），避免大记录打爆 CursorWindow
            if (dao.httpMetaById(n) != null) httpIds += n else pktIds += n
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
