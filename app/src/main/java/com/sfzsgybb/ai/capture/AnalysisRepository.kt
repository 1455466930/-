package com.sfzsgybb.ai.capture

import android.net.Uri
import android.util.Base64
import com.sfzsgybb.ai.data.db.AnalysisDao
import com.sfzsgybb.ai.data.db.DurationStatsRow
import com.sfzsgybb.ai.data.db.HttpRecordEntity
import com.sfzsgybb.ai.data.db.toModel
import com.sfzsgybb.ai.data.model.HttpRecord
import java.util.regex.Pattern

/**
 * 抓包数据分析仓库。
 *
 * 为移植自 ProxyPin 的分析类 MCP 工具提供数据支撑：
 * 统计、域名汇总、Cookie 提取、认证分析、敏感数据扫描、API 端点归组等。
 *
 * 【设计原则】
 * 纯计算与查询，不做网络副作用 —— 因此这些工具对数据来自
 * Hook 还是 MITM 代理都能用。
 */
class AnalysisRepository(private val dao: AnalysisDao) {

    // ==================== 列表与详情 ====================

    suspend fun list(
        sessionId: String?,
        domain: String?,
        method: String?,
        statusCode: Int?,
        urlKeyword: String?,
        offset: Int,
        limit: Int,
    ): Pair<List<HttpRecord>, Long> {
        val sid = sessionId ?: ""
        val d = domain ?: ""
        val m = method?.uppercase() ?: ""
        val sc = statusCode ?: -1
        val kw = urlKeyword ?: ""
        val rows = dao.filterHttp(sid, d, m, sc, kw, limit, offset)
        val total = dao.countFilterHttp(sid, d, m, sc, kw)
        return rows.map { it.toModel() } to total
    }

    suspend fun byId(id: Long): HttpRecord? = dao.httpById(id)?.toModel()
    suspend fun byIds(ids: List<Long>): List<HttpRecord> = dao.httpByIds(ids).map { it.toModel() }
    suspend fun total(sessionId: String?): Long = dao.totalHttp(sessionId ?: "")

    /**
     * 取单条记录元数据（不含 BLOB）—— 详情工具用。
     * 【D1】单行 3.57MB BLOB 会把 CursorWindow 打爆，详情必须走这个。
     */
    suspend fun metaById(id: Long): HttpRecord? = dao.httpMetaById(id)?.toModel()

    /**
     * 用 substr 切 body 的字节切片（BLOB 不进 CursorWindow）。
     */
    suspend fun bodySlice(id: Long, offset: Int, length: Int, fromResponse: Boolean): ByteArray? =
        if (fromResponse) dao.responseBodySlice(id, offset, length)
        else dao.requestBodySlice(id, offset, length)

    /** 跨会话取最近流量（流量列表用）。 */
    suspend fun recentTrafficAll(query: String, limit: Int, source: String = ""): List<HttpRecord> =
        dao.recentTrafficAll(query, limit, source).map { it.toModel() }

    suspend fun countTrafficAll(query: String, source: String = ""): Long =
        dao.countTrafficAll(query, source)

    /** 按包名分组统计。 */
    suspend fun groupByPackage(): List<Pair<String, Int>> =
        dao.groupByPackage().map { it.name to it.count }

    /** 取指定包名的最近记录。 */
    suspend fun httpByPackage(pkg: String, limit: Int): List<HttpRecord> =
        dao.httpByPackage(pkg, limit).map { it.toModel() }

    /** 最近所有记录（跨包名）。source 为空表示不过滤来源。 */
    suspend fun recentHttpAll(limit: Int, source: String = ""): List<HttpRecord> =
        dao.recentHttpAll(limit, source).map { it.toModel() }

    /** 按时间范围扫描（用于内容搜索，因为正文在 SQL 里搜不了）。 */
    suspend fun scan(sessionId: String, startTime: Long, endTime: Long, limit: Int): List<HttpRecord> =
        dao.scanForBodySearch(sessionId, startTime, endTime, limit).map { it.toModel() }

    // ==================== 统计 ====================

    data class Stats(
        val total: Long,
        val domainDist: List<Pair<String, Int>>,
        val statusDist: List<Pair<Int, Int>>,
        val methodDist: List<Pair<String, Int>>,
        val avgMs: Double,
        val maxMs: Long,
        val minMs: Long,
        val sampleCount: Int,
    )

    suspend fun stats(sessionId: String?, domain: String?, topN: Int = 20): Stats {
        val sid = sessionId ?: ""
        val d = domain ?: ""
        val recent = dao.recentHttp(sid, d, 5000)
        val total = recent.size.toLong()

        val domainMap = recent.groupingBy { it.host }.eachCount().entries
            .sortedByDescending { it.value }.take(topN)
        val statusMap = recent.filter { it.statusCode > 0 }
            .groupingBy { it.statusCode }.eachCount().entries
            .sortedByDescending { it.value }
        val methodMap = recent.groupingBy { it.method }.eachCount().entries
            .sortedByDescending { it.value }
        val durations = recent.map { it.durationMs }.filter { it > 0 }

        return Stats(
            total = total,
            domainDist = domainMap.map { it.key to it.value },
            statusDist = statusMap.map { it.key to it.value },
            methodDist = methodMap.map { it.key to it.value },
            avgMs = if (durations.isEmpty()) 0.0 else durations.average(),
            maxMs = durations.maxOrNull() ?: 0L,
            minMs = durations.minOrNull() ?: 0L,
            sampleCount = durations.size,
        )
    }

    // ==================== 域名汇总 ====================

    data class DomainSummary(
        val host: String,
        val count: Int,
        val paths: List<String>,
        val methods: List<Pair<String, Int>>,
        val mimeTypes: List<Pair<String, Int>>,
        val avgMs: Double,
    )

    suspend fun domainSummary(sessionId: String?, domain: String?, topN: Int): List<DomainSummary> {
        val sid = sessionId ?: ""
        val hosts = if (domain.isNullOrBlank()) {
            dao.topDomains(sid, topN)
        } else {
            dao.topDomains(sid, 1000).filter { it.name.contains(domain, true) }.take(topN)
        }

        return hosts.map { row ->
            val recent = dao.recentHttp(sid, row.name, 500)
            val paths = recent.map { it.path }.filter { it.isNotBlank() }.distinct().take(30)
            val methods = recent.groupingBy { it.method }.eachCount().entries
                .sortedByDescending { it.value }.map { it.key to it.value }
            val mimes = dao.mimeOfHost(sid, row.name).map { it.name to it.count }
            val durations = recent.map { it.durationMs }.filter { it > 0 }
            DomainSummary(
                host = row.name,
                count = row.count,
                paths = paths,
                methods = methods,
                mimeTypes = mimes,
                avgMs = if (durations.isEmpty()) 0.0 else durations.average(),
            )
        }
    }

    // ==================== Cookie 分析 ====================

    data class CookieInfo(
        val name: String,
        val value: String,
        val domain: String,
        val path: String,
        val secure: Boolean,
        val httpOnly: Boolean,
        val maxAge: Long?,
        /** 来源：request（Cookie 头）还是 response（Set-Cookie） */
        val from: String,
    )

    suspend fun cookies(sessionId: String?, domain: String): List<CookieInfo> {
        val records = dao.recentHttp(sessionId ?: "", domain, 1000)
        val out = ArrayList<CookieInfo>(32)

        for (r in records) {
            // 响应里的 Set-Cookie
            r.responseHeaders.forEach { h ->
                val idx = h.indexOf('\u0000')
                if (idx <= 0) return@forEach
                val name = h.substring(0, idx)
                if (!name.equals("Set-Cookie", true)) return@forEach
                parseSetCookie(h.substring(idx + 1), r.host).forEach { out.add(it) }
            }
            // 请求里的 Cookie
            r.requestHeaders.forEach { h ->
                val idx = h.indexOf('\u0000')
                if (idx <= 0) return@forEach
                val name = h.substring(0, idx)
                if (!name.equals("Cookie", true)) return@forEach
                parseCookieHeader(h.substring(idx + 1), r.host).forEach { out.add(it) }
            }
        }

        // 按 name+domain 去重，保留最后一个值
        return out.groupBy { "${it.name}@${it.domain}@${it.from}" }
            .map { it.value.last() }
            .sortedBy { it.name }
    }

    private fun parseSetCookie(raw: String, defaultDomain: String): List<CookieInfo> {
        val parts = raw.split(';').map { it.trim() }
        if (parts.isEmpty() || !parts[0].contains('=')) return emptyList()
        val nv = parts[0]
        val name = nv.substringBefore('=')
        val value = nv.substringAfter('=', "")

        var domain = defaultDomain
        var path = "/"
        var secure = false
        var httpOnly = false
        var maxAge: Long? = null

        for (i in 1 until parts.size) {
            val p = parts[i]
            val lower = p.lowercase()
            when {
                lower.startsWith("domain=") -> domain = p.substringAfter('=').trimStart('.')
                lower.startsWith("path=") -> path = p.substringAfter('=')
                lower == "secure" -> secure = true
                lower == "httponly" -> httpOnly = true
                lower.startsWith("max-age=") -> maxAge = p.substringAfter('=').toLongOrNull()
            }
        }
        return listOf(CookieInfo(name, value, domain, path, secure, httpOnly, maxAge, "response"))
    }

    private fun parseCookieHeader(raw: String, defaultDomain: String): List<CookieInfo> =
        raw.split(';').mapNotNull { part ->
            val p = part.trim()
            if (!p.contains('=')) return@mapNotNull null
            CookieInfo(
                name = p.substringBefore('='),
                value = p.substringAfter('=', ""),
                domain = defaultDomain,
                path = "/",
                secure = false,
                httpOnly = false,
                maxAge = null,
                from = "request",
            )
        }

    // ==================== 请求对比 ====================

    data class DiffEntry(val field: String, val value1: String, val value2: String)

    suspend fun compare(id1: Long, id2: Long): List<DiffEntry>? {
        val a = dao.httpById(id1)?.toModel() ?: return null
        val b = dao.httpById(id2)?.toModel() ?: return null
        val diffs = ArrayList<DiffEntry>()

        if (a.url != b.url) diffs.add(DiffEntry("url", a.url, b.url))
        if (a.method != b.method) diffs.add(DiffEntry("method", a.method, b.method))
        if (a.statusCode != b.statusCode) {
            diffs.add(DiffEntry("statusCode", a.statusCode.toString(), b.statusCode.toString()))
        }

        // 头对比
        val ha = a.requestHeaders.associate { it.name.lowercase() to it.value }
        val hb = b.requestHeaders.associate { it.name.lowercase() to it.value }
        (ha.keys + hb.keys).forEach { k ->
            val v1 = ha[k] ?: "(无)"
            val v2 = hb[k] ?: "(无)"
            if (v1 != v2) diffs.add(DiffEntry("reqHeader.$k", v1, v2))
        }

        // Query 参数对比
        val qa = queryParams(a.url)
        val qb = queryParams(b.url)
        (qa.keys + qb.keys).forEach { k ->
            val v1 = qa[k] ?: "(无)"
            val v2 = qb[k] ?: "(无)"
            if (v1 != v2) diffs.add(DiffEntry("query.$k", v1, v2))
        }

        // body 对比
        val ba = decodeText(a.requestBody)
        val bb = decodeText(b.requestBody)
        if (ba != bb) {
            diffs.add(DiffEntry("requestBody", ba.take(500), bb.take(500)))
        }

        return diffs
    }

    private fun queryParams(url: String): Map<String, String> = runCatching {
        Uri.parse(url).queryParameterNames.associateWith { Uri.parse(url).getQueryParameter(it) ?: "" }
    }.getOrDefault(emptyMap())

    // ==================== 敏感数据扫描 ====================

    data class SensitiveHit(
        val type: String,
        val value: String,
        val recordId: Long,
        val url: String,
        val location: String,
    )

    private val PATTERNS: List<Triple<String, Pattern, Int>> = listOf(
        Triple("手机号", Pattern.compile("\\b1[3-9]\\d{9}\\b"), 0),
        Triple("身份证", Pattern.compile("\\b[1-9]\\d{5}(19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]\\b"), 0),
        Triple("邮箱", Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"), 0),
        Triple("JWT", Pattern.compile("eyJ[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"), 0),
        Triple("Bearer Token", Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._\\-]{16,}"), 0),
        Triple("API Key", Pattern.compile("(?i)(api[_-]?key|apikey|access[_-]?key)[\"'\\s:=]+([A-Za-z0-9]{16,})"), 2),
        Triple("密码字段", Pattern.compile("(?i)(password|passwd|pwd|secret)[\"'\\s:=]+([^\"'&\\s,}]{4,})"), 2),
        Triple("IP 地址", Pattern.compile("\\b(?!127\\.|0\\.|255\\.)((25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)\\b"), 0),
        Triple("银行卡", Pattern.compile("\\b62\\d{14,17}\\b"), 0),
        Triple("私钥", Pattern.compile("-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----"), 0),
    )

    suspend fun scanSensitive(
        sessionId: String?,
        domain: String?,
        recordId: Long?,
        maxRecords: Int = 500,
    ): List<SensitiveHit> {
        val records = if (recordId != null) {
            listOfNotNull(dao.httpById(recordId)?.toModel())
        } else {
            dao.recentHttp(sessionId ?: "", domain ?: "", maxRecords).map { it.toModel() }
        }

        val hits = ArrayList<SensitiveHit>(32)
        for (r in records) {
            // 扫描 URL
            scanText(r.url, r, "url", hits)
            // 扫描请求体
            scanText(decodeText(r.requestBody), r, "requestBody", hits)
            // 扫描响应体
            scanText(decodeText(r.responseBody), r, "responseBody", hits)
            // 扫描头
            r.requestHeaders.forEach { scanText(it.value, r, "reqHeader:${it.name}", hits) }
            r.responseHeaders.forEach { scanText(it.value, r, "respHeader:${it.name}", hits) }
        }

        // 去重（同类型同值只留一条）
        return hits.distinctBy { "${it.type}|${it.value}" }
    }

    private fun scanText(text: String, r: HttpRecord, where: String, out: MutableList<SensitiveHit>) {
        if (text.isBlank()) return
        val sample = if (text.length > 200_000) text.substring(0, 200_000) else text
        for ((type, pattern, group) in PATTERNS) {
            val m = pattern.matcher(sample)
            var count = 0
            while (m.find() && count < 20) {
                val value = if (group > 0 && group <= m.groupCount()) m.group(group) else m.group()
                if (!value.isNullOrBlank()) {
                    out.add(SensitiveHit(type, value.take(120), r.id, r.url, where))
                    count++
                }
            }
        }
    }

    // ==================== 认证分析 ====================

    data class AuthInfo(
        val url: String,
        val host: String,
        val scheme: String,
        val credentials: String,
        val recordId: Long,
    )

    suspend fun analyzeAuth(sessionId: String?, domain: String?, maxRecords: Int = 500): List<AuthInfo> {
        val records = dao.recentHttp(sessionId ?: "", domain ?: "", maxRecords).map { it.toModel() }
        val out = ArrayList<AuthInfo>(32)

        for (r in records) {
            r.requestHeaders.forEach { h ->
                val n = h.name.lowercase()
                when {
                    n == "authorization" -> out.add(
                        AuthInfo(r.url, r.host, h.value.substringBefore(' '), h.value, r.id)
                    )
                    n == "x-api-key" || n == "apikey" || n == "x-auth-token" || n == "token" ->
                        out.add(AuthInfo(r.url, r.host, n, h.value, r.id))
                    n == "cookie" -> {
                        // 只提取疑似 session 的项
                        h.value.split(';').forEach { c ->
                            val p = c.trim()
                            val lc = p.lowercase()
                            if (lc.contains("session") || lc.contains("token") || lc.contains("auth") ||
                                lc.contains("sid") || lc.contains("ticket")
                            ) {
                                out.add(AuthInfo(r.url, r.host, "cookie", p, r.id))
                            }
                        }
                    }
                }
            }
        }

        // 去重
        return out.distinctBy { "${it.host}|${it.scheme}|${it.credentials.take(64)}" }
    }

    // ==================== API 端点归组 ====================

    data class ApiEndpoint(
        val method: String,
        val pattern: String,
        val host: String,
        val count: Int,
        val statusCodes: Map<Int, Int>,
        val contentTypes: List<String>,
    )

    suspend fun extractEndpoints(sessionId: String?, domain: String?, maxRecords: Int = 2000): List<ApiEndpoint> {
        val records = dao.recentHttp(sessionId ?: "", domain ?: "", maxRecords).map { it.toModel() }

        // 把路径里的 ID 类片段替换成占位符
        val grouped = records.groupBy { r ->
            "${r.method} ${r.host}${normalizePath(r.path)}"
        }

        return grouped.map { (key, list) ->
            val first = list.first()
            val statusMap = list.filter { it.statusCode > 0 }
                .groupingBy { it.statusCode }.eachCount()
            val mimes = list.map { it.mimeType }.filter { it.isNotBlank() }.distinct().take(5)
            ApiEndpoint(
                method = first.method,
                pattern = normalizePath(first.path),
                host = first.host,
                count = list.size,
                statusCodes = statusMap,
                contentTypes = mimes,
            )
        }.sortedByDescending { it.count }
    }

    /**
     * 把路径里的动态片段替换成占位符，便于归组同一接口。
     * 例：/api/user/12345/profile → /api/user/{id}/profile
     */
    private fun normalizePath(path: String): String {
        if (path.isBlank()) return "/"
        return path.split('/').joinToString("/") { seg ->
            when {
                seg.isEmpty() -> seg
                // 纯数字
                seg.all { it.isDigit() } -> "{id}"
                // UUID
                UUID_RE.matches(seg) -> "{uuid}"
                // 长十六进制串
                seg.length >= 16 && seg.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' } -> "{hash}"
                // 时间戳（10 或 13 位数字已在上面处理）
                else -> seg
            }
        }
    }

    // ==================== 加密内容分析 ====================

    data class CryptoAnalysis(
        val encoded: String,
        val decodedLength: Int,
        val decodeType: String,
        val entropy: Double,
        val printableRatio: Double,
        val guessedTypes: List<String>,
        val preview: String,
    )

    fun analyzeCrypto(raw: String): CryptoAnalysis {
        val trimmed = raw.trim()
        val guesses = ArrayList<String>(4)
        var decoded = ByteArray(0)
        var decodeType = "unknown"

        // Base64
        if (trimmed.length >= 8 && trimmed.matches(Regex("^[A-Za-z0-9+/]+={0,2}$"))) {
            runCatching {
                decoded = Base64.decode(trimmed, Base64.DEFAULT)
                decodeType = "base64"
            }
        }
        // Hex
        if (decodeType == "unknown" && trimmed.length >= 8 && trimmed.length % 2 == 0 &&
            trimmed.matches(Regex("^[0-9a-fA-F]+$"))
        ) {
            runCatching {
                decoded = ByteArray(trimmed.length / 2) { i ->
                    trimmed.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
                decodeType = "hex"
            }
        }
        // URL 编码
        if (decodeType == "unknown" && trimmed.contains('%')) {
            runCatching {
                decoded = java.net.URLDecoder.decode(trimmed, "UTF-8").toByteArray()
                decodeType = "url"
            }
        }

        val bytes = if (decoded.isNotEmpty()) decoded else trimmed.toByteArray()
        val entropy = shannonEntropy(bytes)
        val printable = printableRatio(bytes)

        // 推测类型
        when {
            entropy > 7.5 -> guesses.add("高熵：可能是加密数据或压缩数据")
            entropy > 6.0 -> guesses.add("中高熵：可能是 Base64 编码的二进制、或哈希值")
            entropy > 4.0 -> guesses.add("中等熵：可能是文本混少量二进制")
            else -> guesses.add("低熵：可能是纯文本")
        }
        if (bytes.size == 16 || bytes.size == 32 || bytes.size == 64) {
            guesses.add("长度 ${bytes.size}B：可能是 MD5/SHA-1/SHA-256 摘要")
        }
        if (bytes.size % 16 == 0 && bytes.size >= 16) {
            guesses.add("长度是 16 的倍数：可能是 AES 分组加密结果")
        }
        if (decodeType == "base64" && printable > 0.9) {
            guesses.add("解码后全是可打印字符：可能是嵌套的 Base64 或 JSON")
        }

        return CryptoAnalysis(
            encoded = trimmed.take(80),
            decodedLength = decoded.size,
            decodeType = decodeType,
            entropy = entropy,
            printableRatio = printable,
            guessedTypes = guesses,
            preview = decodePreview(bytes),
        )
    }

    /** 香农熵，单位 bit/byte。 */
    private fun shannonEntropy(data: ByteArray): Double {
        if (data.isEmpty()) return 0.0
        val freq = IntArray(256)
        for (b in data) freq[b.toInt() and 0xFF]++
        var h = 0.0
        val n = data.size.toDouble()
        for (c in freq) {
            if (c == 0) continue
            val p = c / n
            h -= p * (Math.log(p) / Math.log(2.0))
        }
        return h
    }

    private fun printableRatio(data: ByteArray): Double {
        if (data.isEmpty()) return 0.0
        val sample = if (data.size > 4096) data.copyOf(4096) else data
        var ok = 0
        for (b in sample) {
            val v = b.toInt() and 0xFF
            if (v in 32..126 || v == 9 || v == 10 || v == 13 || v >= 0x80) ok++
        }
        return ok.toDouble() / sample.size
    }

    private fun decodePreview(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val take = minOf(bytes.size, 120)
        val text = String(bytes, 0, take, Charsets.UTF_8)
        val printable = text.count { it.code in 32..126 || it.code > 0x7F }
        return if (printable * 100 / maxOf(text.length, 1) >= 85) {
            text.replace("\n", "\\n").take(120)
        } else {
            bytes.take(60).joinToString("") { "%02x".format(it) }
        }
    }

    // ==================== 代码生成 ====================

    suspend fun generateCode(requestId: Long, language: String): String? {
        val r = dao.httpById(requestId)?.toModel() ?: return null
        val reqBody = decodeText(r.requestBody)
        val ct = r.requestHeaders.firstOrNull { it.name.equals("Content-Type", true) }?.value ?: ""

        return when (language.lowercase()) {
            "curl" -> buildString {
                append("curl -X ").append(r.method).append(" '").append(r.url).append("'")
                r.requestHeaders.forEach { h ->
                    if (h.name.equals("Content-Length", true)) return@forEach
                    append(" \\\n  -H '").append(h.name).append(": ").append(h.value).append("'")
                }
                if (reqBody.isNotBlank()) append(" \\\n  --data-raw '").append(reqBody.replace("'", "'\\''")).append("'")
            }

            "python" -> buildString {
                append("import requests\n\n")
                append("url = \"").append(r.url).append("\"\n")
                append("headers = {\n")
                r.requestHeaders.forEach { h ->
                    if (h.name.equals("Content-Length", true)) return@forEach
                    append("    \"").append(h.name).append("\": \"").append(h.value.replace("\"", "\\\"")).append("\",\n")
                }
                append("}\n")
                if (reqBody.isNotBlank()) {
                    append("data = ").append(pyLiteral(reqBody, ct)).append("\n")
                    append("resp = requests.").append(r.method.lowercase())
                        .append("(url, headers=headers, data=data, verify=False)\n")
                } else {
                    append("resp = requests.").append(r.method.lowercase())
                        .append("(url, headers=headers, verify=False)\n")
                }
                append("print(resp.status_code)\nprint(resp.text)")
            }

            "javascript" -> buildString {
                append("const resp = await fetch(\"").append(r.url).append("\", {\n")
                append("  method: \"").append(r.method).append("\",\n")
                append("  headers: {\n")
                r.requestHeaders.forEach { h ->
                    if (h.name.equals("Content-Length", true)) return@forEach
                    append("    \"").append(h.name).append("\": \"").append(h.value.replace("\"", "\\\"")).append("\",\n")
                }
                append("  },\n")
                if (reqBody.isNotBlank()) {
                    append("  body: ").append(jsLiteral(reqBody, ct)).append(",\n")
                }
                append("});\nconst data = await resp.text();\nconsole.log(data);")
            }

            "go" -> buildString {
                append("package main\n\nimport (\n\t\"fmt\"\n\t\"io\"\n\t\"net/http\"\n\t\"strings\"\n)\n\n")
                append("func main() {\n")
                if (reqBody.isNotBlank()) {
                    append("\tbody := strings.NewReader(`").append(reqBody).append("`)\n")
                } else {
                    append("\tbody := strings.NewReader(\"\")\n")
                }
                append("\treq, _ := http.NewRequest(\"").append(r.method).append("\", \"")
                    .append(r.url).append("\", body)\n")
                r.requestHeaders.forEach { h ->
                    if (h.name.equals("Content-Length", true)) return@forEach
                    append("\treq.Header.Set(\"").append(h.name).append("\", \"")
                        .append(h.value.replace("\"", "\\\"")).append("\")\n")
                }
                append("\tresp, _ := http.DefaultClient.Do(req)\n")
                append("\tdefer resp.Body.Close()\n")
                append("\tb, _ := io.ReadAll(resp.Body)\n")
                append("\tfmt.Println(resp.Status)\n\tfmt.Println(string(b))\n}")
            }

            else -> null
        }
    }

    private fun pyLiteral(body: String, contentType: String): String =
        if (contentType.contains("json", true)) {
            "json.loads(r'''$body''')"
        } else {
            "r'''$body'''"
        }

    private fun jsLiteral(body: String, contentType: String): String =
        if (contentType.contains("json", true)) body else "`$body`"

    // ==================== 工具方法 ====================

    fun decodeText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
        val sample = text.take(512)
        val printable = sample.count { it.code in 32..126 || it.code > 0x7F || it == '\n' }
        return if (printable * 100 / maxOf(sample.length, 1) >= 85) text
        else bytes.take(256).joinToString("") { "%02x".format(it) }
    }

    /**
     * 供关键词搜索用的文本解码。
     *
     * 【D5 修复】和 decodeText 的关键区别：
     * decodeText 对二进制会 fallback 成 hex 字符串，
     * 拿 hex 去 contains 搜索会把「搜 00 / 0a」这类关键词
     * 误匹配到一堆二进制响应上。
     * 这个版本二进制直接返回空串 —— 搜不到就是搜不到，
     * 不会假命中。
     */
    fun decodeTextForSearch(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
        val sample = text.take(512)
        val printable = sample.count { it.code in 32..126 || it.code > 0x7F || it == '\n' }
        return if (printable * 100 / maxOf(sample.length, 1) >= 85) text else ""
    }

    private companion object {
        val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}
