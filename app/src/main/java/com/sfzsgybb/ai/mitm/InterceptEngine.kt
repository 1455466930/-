package com.sfzsgybb.ai.mitm

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/**
 * 断点拦截与重写规则引擎。
 *
 * 对应 ProxyPin 的 request_breakpoint_manager / request_rewrite_manager，
 * 以及 get_pending_intercepts / release_intercept 那套交互模型。
 *
 * 【工作方式】
 *  - 断点：匹配的请求/响应被挂起，等 MCP 客户端查看后放行或修改
 *  - 重写：匹配后直接修改（不挂起），用于替换响应体、改 Header、重定向
 *
 * 【线程模型】
 * 代理线程在匹配到断点时会阻塞等待（最长 WAIT_TIMEOUT），
 * MCP 客户端通过 release 接口放行。超时自动放行，避免把目标应用卡死。
 */
object InterceptEngine {

    private const val TAG = "InterceptEngine"

    /** 挂起等待的最长时间，超时自动放行（防止目标应用被卡死）。 */
    private const val WAIT_TIMEOUT_MS = 60_000L

    // ==================== 断点规则 ====================

    data class Breakpoint(
        val index: Int,
        val urlPattern: String,
        val name: String,
        val interceptRequest: Boolean,
        val interceptResponse: Boolean,
        val method: String,
    ) {
        val compiled: Pattern by lazy { Pattern.compile(urlPattern) }
    }

    private val breakpoints = ArrayList<Breakpoint>()
    private val bpLock = Any()

    fun addBreakpoint(
        urlPattern: String,
        name: String,
        interceptRequest: Boolean,
        interceptResponse: Boolean,
        method: String,
    ): Int = synchronized(bpLock) {
        val idx = breakpoints.size
        breakpoints.add(
            Breakpoint(idx, urlPattern, name, interceptRequest, interceptResponse, method)
        )
        Log.i(TAG, "新增断点 #$idx: $urlPattern")
        idx
    }

    fun listBreakpoints(): List<Breakpoint> = synchronized(bpLock) { breakpoints.toList() }

    fun removeBreakpoint(index: Int): Boolean = synchronized(bpLock) {
        if (index in breakpoints.indices) {
            breakpoints.removeAt(index)
            // 重建索引
            for (i in breakpoints.indices) {
                breakpoints[i] = breakpoints[i].copy(index = i)
            }
            Log.i(TAG, "已删除断点 #$index")
            true
        } else false
    }

    private fun matchBreakpoint(url: String, method: String, forRequest: Boolean): Breakpoint? =
        synchronized(bpLock) {
            breakpoints.firstOrNull { bp ->
                val stageOk = if (forRequest) bp.interceptRequest else bp.interceptResponse
                val methodOk = bp.method.isBlank() || bp.method.equals(method, true)
                stageOk && methodOk && runCatching { bp.compiled.matcher(url).find() }.getOrDefault(false)
            }
        }

    // ==================== 挂起的拦截 ====================

    data class Pending(
        val requestId: String,
        val stage: String,           // "request" 或 "response"
        val method: String,
        val url: String,
        val requestHeaders: List<Pair<String, String>>,
        val requestBody: String,
        val responseStatusCode: Int,
        val responseHeaders: List<Pair<String, String>>,
        val responseBody: String,
        val createdAt: Long,
    )

    /** 放行指令。 */
    data class Release(
        val abort: Boolean,
        val headers: Map<String, String>?,
        val body: String?,
        val statusCode: Int?,
    )

    private val pendingMap = ConcurrentHashMap<String, Pending>()
    private val latches = ConcurrentHashMap<String, CountDownLatch>()
    private val releases = ConcurrentHashMap<String, Release>()
    private val seq = AtomicInteger(0)

    /** 当前挂起的列表。 */
    fun pendingList(): List<Pending> = pendingMap.values.sortedBy { it.createdAt }

    fun nextId(): String = "bp-" + seq.incrementAndGet()

    /**
     * 挂起等待放行。
     *
     * @return 放行指令；超时返回 null（调用方按原样放行）
     */
    fun awaitRelease(id: String): Release? {
        val latch = latches[id] ?: return null
        val ok = latch.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        pendingMap.remove(id)
        latches.remove(id)
        if (!ok) {
            Log.w(TAG, "断点 $id 等待超时，自动放行")
            return null
        }
        return releases.remove(id)
    }

    /**
     * 登记一个挂起的拦截。
     * @return 用于等待的 latch
     */
    fun register(p: Pending): CountDownLatch {
        val latch = CountDownLatch(1)
        pendingMap[p.requestId] = p
        latches[p.requestId] = latch
        Log.i(TAG, "已挂起 ${p.requestId} [${p.stage}] ${p.method} ${p.url}")
        return latch
    }

    /** 放行。 */
    fun release(id: String, r: Release): Boolean {
        val latch = latches[id] ?: return false
        releases[id] = r
        latch.countDown()
        Log.i(TAG, "已放行 $id abort=${r.abort}")
        return true
    }

    /** 是否应该拦截（并返回匹配的断点）。 */
    fun shouldInterceptRequest(url: String, method: String): Breakpoint? =
        matchBreakpoint(url, method, forRequest = true)

    fun shouldInterceptResponse(url: String, method: String): Breakpoint? =
        matchBreakpoint(url, method, forRequest = false)

    // ==================== 重写规则 ====================

    enum class RewriteType { REQUEST_REPLACE, RESPONSE_REPLACE, REQUEST_UPDATE, RESPONSE_UPDATE, REDIRECT }

    data class RewriteRule(
        val index: Int,
        val name: String,
        val urlPattern: String,
        val ruleType: RewriteType,
        val body: String?,
        val statusCode: Int?,
        val headers: Map<String, String>,
        val redirectUrl: String?,
    ) {
        val compiled: Pattern by lazy { compileWildcard(urlPattern) }
    }

    private val rewriteRules = ArrayList<RewriteRule>()
    private val rwLock = Any()

    fun addRewriteRule(
        name: String,
        urlPattern: String,
        ruleType: RewriteType,
        body: String?,
        statusCode: Int?,
        headers: Map<String, String>,
        redirectUrl: String?,
    ): Int = synchronized(rwLock) {
        val idx = rewriteRules.size
        rewriteRules.add(RewriteRule(idx, name, urlPattern, ruleType, body, statusCode, headers, redirectUrl))
        Log.i(TAG, "新增重写规则 #$idx: $urlPattern ($ruleType)")
        idx
    }

    fun listRewriteRules(): List<RewriteRule> = synchronized(rwLock) { rewriteRules.toList() }

    fun removeRewriteRule(index: Int): Boolean = synchronized(rwLock) {
        if (index in rewriteRules.indices) {
            rewriteRules.removeAt(index)
            for (i in rewriteRules.indices) rewriteRules[i] = rewriteRules[i].copy(index = i)
            true
        } else false
    }

    /** 找出适用的重写规则。 */
    fun matchRewrite(url: String, forRequest: Boolean): RewriteRule? = synchronized(rwLock) {
        rewriteRules.firstOrNull { r ->
            val typeOk = if (forRequest) {
                r.ruleType == RewriteType.REQUEST_REPLACE ||
                    r.ruleType == RewriteType.REQUEST_UPDATE ||
                    r.ruleType == RewriteType.REDIRECT
            } else {
                r.ruleType == RewriteType.RESPONSE_REPLACE ||
                    r.ruleType == RewriteType.RESPONSE_UPDATE
            }
            typeOk && runCatching { r.compiled.matcher(url).find() }.getOrDefault(false)
        }
    }

    /**
     * 把通配符模式编译成正则。
     * `*` → `.*`，`?` → `\?`
     * 与 ProxyPin 的 wildcard URL 规则语义一致。
     */
    private fun compileWildcard(pattern: String): Pattern {
        val sb = StringBuilder()
        for (c in pattern) {
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append("\\?")
                '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
        }
        return Pattern.compile(sb.toString())
    }

    fun clear() {
        synchronized(bpLock) { breakpoints.clear() }
        synchronized(rwLock) { rewriteRules.clear() }
        pendingMap.clear()
        latches.clear()
        releases.clear()
    }

    fun stats(): String =
        "breakpoints=${breakpoints.size} rewrites=${rewriteRules.size} pending=${pendingMap.size}"
}
