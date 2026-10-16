package com.sfzsgybb.ai.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sfzsgybb.ai.capture.CaptureRepository
import com.sfzsgybb.ai.data.model.CaptureSession
import com.sfzsgybb.ai.data.model.HttpRecord
import com.sfzsgybb.ai.data.model.PacketData
import com.sfzsgybb.ai.data.model.ValueMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.coroutines.withContext

/** 流量列表条目。 */
/** 流量列表自动刷新间隔 */
private const val IF_AUTO_REFRESH_MS = 2000L

data class TrafficItem(
    val id: Long,
    val method: String,
    val url: String,
    val host: String,
    val statusCode: Int,
    val durationMs: Long,
    val requestSize: Long,
    val responseSize: Long,
    val mimeType: String,
    val time: Long,
    val sessionId: String,
    val packageName: String,
)

/** 按包名分组的抓包数据。 */
data class PackageGroup(
    val packageName: String,
    val label: String,
    val count: Int,
)

data class CaptureUiState(
    val running: Boolean = false,
    val sessionId: String? = null,
    val packageName: String? = null,
    val filterLocal: Boolean = true,
    val recordCount: Long = 0,
    val sessions: List<CaptureSession> = emptyList(),
    /** 多选删除模式 */
    val selectionMode: Boolean = false,
    val selected: Set<String> = emptySet(),
    val message: String? = null,
    // ---------- MITM 代理 ----------
    /** MITM 代理是否运行 */
    val mitmRunning: Boolean = false,
    val mitmPort: Int = 0,
    val caReady: Boolean = false,
    val mitmError: String? = null,
    // ---------- VPN 抓包 ----------
    /** VPN 是否运行 */
    val vpnRunning: Boolean = false,
    /** 是否已获得 VPN 授权 */
    val vpnAuthorized: Boolean = false,
    /** 需要用户去系统对话框确认 */
    val vpnNeedPermission: Boolean = false,
    /** VPN 抓到的包统计摘要 */
    val vpnStats: String = "",
    val vpnPackets: Long = 0,
    /** VPN 接管的顶部目标地址 */
    val vpnDestinations: List<Pair<String, Long>> = emptyList(),
    val vpnError: String? = null,
    /** 是否全局抓包（false = 只抓已勾选 Hook 的应用） */
    val vpnGlobal: Boolean = false,
    /**
     * VPN 正在启动/停止中。
     *
     * 【为什么需要】
     * `startForegroundService` 是异步的：调用后服务还没真正起来，
     * `isRunning` 仍是 false。如果立刻按 isRunning 渲染按钮，
     * 就会显示成「未启动」，用户以为没生效、又点一次 —— 这就是
     * 「要点两次才出停止按钮」的原因。
     * 用这个标志位让按钮在过渡期间显示「启动中…」并禁用。
     */
    val vpnBusy: Boolean = false,
    /** CA 证书状态 */
    val caState: com.sfzsgybb.ai.mitm.CaStatusChecker.CaState =
        com.sfzsgybb.ai.mitm.CaStatusChecker.CaState.NONE,
    /** 实时流量列表（最近抓到的请求） */
    val recentTraffic: List<TrafficItem> = emptyList(),
    /** 抓包运行日志（显示在界面上，让用户知道有没有在抓） */
    val captureLog: List<String> = emptyList(),
    /** 要打开详情的 HTTP 记录 ID */
    val openHttpId: Long? = null,
    /** 流量过滤关键词 */
    val trafficFilter: String = "",
    /** VPN 当前抓取的应用包名（空表示全局） */
    val vpnTargets: List<String> = emptyList(),
    // ---------- 按包名分组展示 ----------
    /** 按包名分组的抓包数据 */
    val packageGroups: List<PackageGroup> = emptyList(),
    /** 当前选中的包名（null = 全部） */
    val selectedPackage: String? = null,
    /** 已启用 Hook 的应用（软件名 + 包名，用于目标选择） */
    val enabledApps: List<Pair<String, String>> = emptyList(),
)

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val repo: CaptureRepository,
    private val mitm: com.sfzsgybb.ai.mitm.MitmController,
    private val vpn: com.sfzsgybb.ai.vpn.VpnController,
    private val appList: com.sfzsgybb.ai.mcp.tools.AppListProvider,
    private val analysis: com.sfzsgybb.ai.capture.AnalysisRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : ViewModel() {

    private val _state = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    private var vpnTicker: Job? = null

    init {
        refresh()
        refreshMitm()
        refreshVpn()
        loadPackageGroups()
        loadTraffic()
        // VPN 统计是实时数据，需要定时刷新
        vpnTicker = viewModelScope.launch {
            while (true) {
                delay(2000)
                if (_state.value.vpnRunning) refreshVpn()
            }
        }
        // 流量列表自动刷新
        startTrafficAutoRefresh()
    }

    override fun onCleared() {
        vpnTicker?.cancel()
        super.onCleared()
    }

    /** 刷新 VPN 状态与统计。 */
    fun refreshVpn() {
        val running = vpn.isRunning
        val authorized = vpn.hasPermission()
        val handler = com.sfzsgybb.ai.mcp.tools.VpnStatsHolder.handler

        val dests = if (running && handler != null) {
            handler.destinations().entries
                .sortedByDescending { it.value }
                .take(8)
                .map { it.key to it.value }
        } else emptyList()

        _state.value = _state.value.copy(
            vpnRunning = running,
            vpnAuthorized = authorized,
            vpnStats = if (running && handler != null) handler.stats() else "",
            vpnPackets = handler?.packetsTotal?.get() ?: 0L,
            vpnDestinations = dests,
            vpnError = vpn.lastError,
            caState = com.sfzsgybb.ai.mitm.CaStatusChecker.check(),
        )
    }

    /**
     * 请求 VPN 授权。
     * 返回需要启动的授权 Intent（由 Activity 发起），null 表示已授权。
     */
    fun vpnPermissionIntent(): android.content.Intent? = vpn.prepareIntent()

    /**
     * 启动 VPN（要求已授权）。
     *
     * 【默认只抓已启用 Hook 的应用，而不是全局】
     * 全局接管会带来两个问题：
     *  1. 隐私：抓了不该抓的（聊天、支付、银行）
     *  2. 性能与稳定性：所有流量都过一遍用户态 TCP 栈，
     *     非 HTTP 协议（推送、游戏、视频）也走这条路，容易出问题
     *
     * 所以默认把「在应用列表里勾选了 Hook 的应用」作为白名单传下去。
     * 只有用户显式选择「全局抓包」时才不做限制。
     */
    fun startVpn(globalCapture: Boolean = false) {
        viewModelScope.launch {
            val allow = if (globalCapture) null else enabledHookPackages()
            if (!globalCapture && allow.isNullOrEmpty()) {
                notify("请先在「应用」页勾选要抓包的应用（或选择全局抓包）")
                return@launch
            }

            // 先启动 HTTPS 解密代理：VPN 只是把流量引过来，
            // 没有解密代理就抓不到 HTTPS 内容（只有 CONNECT 隧道元信息）。
            // 所以这里自动启动，不给用户留一个「忘了开」的坑。
            if (!mitm.isRunning) {
                runCatching { mitm.start() }
                    .onFailure { android.util.Log.w("CaptureViewModel", "自动启动解密代理失败", it) }
            }
            refreshMitm()

            // 过渡态：按钮立刻变成「启动中…」，避免用户重复点击
            _state.value = _state.value.copy(vpnBusy = true)

            val result = vpn.start(
                proxyPort = mitm.port.takeIf { it > 0 } ?: 8888,
                allowApps = allow,
            )
            when (result) {
                com.sfzsgybb.ai.vpn.VpnController.StartResult.STARTED -> {
                    _state.value = _state.value.copy(vpnTargets = allow ?: emptyList())

                    // 【关键】startForegroundService 是异步的：
                    // 这里要等服务真正起来（isRunning 变 true），
                    // 否则界面会停留在「未启动」，用户以为没生效。
                    val started = awaitVpnState(expectRunning = true)
                    _state.value = _state.value.copy(vpnBusy = false)

                    if (!started) {
                        notify("VPN 启动超时，请检查是否被系统拦截")
                        refreshVpn()
                        return@launch
                    }

                    val base = if (globalCapture) "VPN 已启动（全局）"
                    else "VPN 已启动，仅抓取已选中的应用（${allow?.size ?: 0} 个）"
                    // 检测 CA 状态，给出可操作的建议
                    val caHint = com.sfzsgybb.ai.mitm.CaStatusChecker
                        .hint(com.sfzsgybb.ai.mitm.CaStatusChecker.check())
                    notify(if (caHint != null) "$base\n$caHint" else base)
                }
                com.sfzsgybb.ai.vpn.VpnController.StartResult.NEED_PERMISSION -> {
                    _state.value = _state.value.copy(vpnBusy = false, vpnNeedPermission = true)
                    notify("需要先授权 VPN 连接")
                }
                com.sfzsgybb.ai.vpn.VpnController.StartResult.FAILED -> {
                    _state.value = _state.value.copy(vpnBusy = false)
                    notify("VPN 启动失败：${vpn.lastError ?: "未知错误"}")
                }
            }
            refreshVpn()
        }
    }

    /**
     * 等待 VPN 状态变成期望值（服务启停都是异步的）。
     *
     * 每 100ms 查一次，最多等 [timeoutMs]。
     * @return true 表示已到达期望状态
     */
    private suspend fun awaitVpnState(expectRunning: Boolean, timeoutMs: Long = 5000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (vpn.isRunning == expectRunning) return true
            delay(100)
        }
        return vpn.isRunning == expectRunning
    }

    /**
     * 读出「已启用 Hook」的应用包名，作为 VPN 白名单。
     * 复用现有的应用配置（用户已经在应用列表里勾过的）。
     */
    private suspend fun enabledHookPackages(): List<String>? {
        return runCatching {
            // onlyEnabled=true：只取用户在应用列表里勾选了 Hook 的
            appList.listApps(onlyEnabled = true, query = null, limit = 500)
                .map { it.packageName }
                .distinct()
        }.getOrNull()
    }

    /** 切换抓取范围。 */
    fun setVpnGlobal(global: Boolean) {
        _state.value = _state.value.copy(vpnGlobal = global)
    }

    /** 用户拒绝了 VPN 授权。 */
    fun onVpnPermissionDenied() {
        notify("已取消 VPN 授权，无法抓包")
        refreshVpn()
    }

    // ==================== 证书导出 / 安装 ====================

    /** 导出 CA 证书到下载目录（无需 root）。 */
    fun exportCertificate() {
        viewModelScope.launch {
            val installer = com.sfzsgybb.ai.mitm.CertificateInstaller(context)
            val r = withContext(kotlinx.coroutines.Dispatchers.IO) { installer.exportToDownloads() }
            notify(r.message)
        }
    }

    /** 一键装到系统信任区（需要 root）。 */
    fun installCertificateToSystem() {
        viewModelScope.launch {
            val installer = com.sfzsgybb.ai.mitm.CertificateInstaller(context)
            notify("正在安装证书…")
            val r = withContext(kotlinx.coroutines.Dispatchers.IO) { installer.installToSystem() }
            notify(r.message)
            // 重新检测 CA 状态
            refreshVpn()
        }
    }

    /** 检查 root 是否可用（决定「装到系统」按钮是否可点）。 */
    fun checkRoot(): Boolean = runCatching {
        com.sfzsgybb.ai.mitm.CertificateInstaller(context).hasRoot()
    }.getOrDefault(false)

    fun stopVpn() {
        viewModelScope.launch {
            _state.value = _state.value.copy(vpnBusy = true)
            vpn.stop()
            // 解密代理与 VPN 是一体的，一起停掉，避免残留
            runCatching { mitm.stop() }
            // 同样要等服务真正停下来，否则按钮会短暂显示成「未启动」
            awaitVpnState(expectRunning = false)
            _state.value = _state.value.copy(vpnBusy = false)
            refreshVpn()
            refreshMitm()
            notify("代理抓包已停止")
        }
    }

    // ==================== 实时流量列表 ====================

    private var trafficTicker: Job? = null

    /** 加载最近流量（按选中包名过滤）。 */
    fun loadTraffic() {
        viewModelScope.launch {
            runCatching {
                val selPkg = _state.value.selectedPackage
                // 按选中包名过滤：选中了具体应用就只看它，null 看全部
                val raw = if (selPkg != null) {
                    analysis.httpByPackage(selPkg, 300)
                } else {
                    analysis.recentHttpAll(300)
                }
                val items = raw.map { r ->
                    TrafficItem(
                        id = r.id,
                        method = r.method,
                        url = r.url,
                        host = r.host,
                        statusCode = r.statusCode,
                        durationMs = r.durationMs,
                        requestSize = r.requestBody.size.toLong(),
                        responseSize = r.responseBody.size.toLong(),
                        mimeType = r.mimeType,
                        time = r.time,
                        sessionId = r.sessionId,
                        packageName = r.packageName,
                    )
                }
                _state.value = _state.value.copy(recentTraffic = items)
            }.onFailure {
                android.util.Log.w("CaptureViewModel", "加载流量失败: ${it.message}")
            }
        }
    }

    /** 加载按包名分组的数据 + 已启用 Hook 的应用列表。 */
    fun loadPackageGroups() {
        viewModelScope.launch {
            runCatching {
                val groups = analysis.groupByPackage().map { (pkg, count) ->
                    val label = appLabel(pkg)
                    PackageGroup(pkg, label, count)
                }
                val apps = appList.listApps(onlyEnabled = true, query = null, limit = 200)
                    .map { it.packageName to it.label }
                _state.value = _state.value.copy(
                    packageGroups = groups,
                    enabledApps = apps,
                )
            }.onFailure {
                android.util.Log.w("CaptureViewModel", "加载分组失败: ${it.message}")
            }
        }
    }

    /** 选择要查看的包名（null = 全部）。 */
    fun selectPackage(pkg: String?) {
        _state.value = _state.value.copy(selectedPackage = pkg)
        loadTraffic()
    }

    /** 根据包名查软件名（从已启用应用列表，或从 Hook 配置）。 */
    private suspend fun appLabel(pkg: String): String {
        if (pkg.isBlank()) return "未知应用"
        // 优先从已启用应用列表查
        val apps = appList.listApps(onlyEnabled = true, query = null, limit = 500)
        apps.firstOrNull { it.packageName == pkg }?.label?.let { return it }
        return pkg
    }

    /** 清空抓包日志。 */
    fun clearCaptureLog() {
        com.sfzsgybb.ai.vpn.VpnLog.clear()
        _state.value = _state.value.copy(captureLog = emptyList())
    }

    /** 打开某条流量的详情。 */
    fun openTraffic(id: Long) {
        _state.value = _state.value.copy(openHttpId = id)
    }

    /** 设置流量过滤关键词。 */
    fun setTrafficFilter(q: String) {
        _state.value = _state.value.copy(trafficFilter = q)
        loadTraffic()
    }

    /** 启动流量自动刷新（抓包运行时每 2 秒刷一次）。 */
    fun startTrafficAutoRefresh() {
        if (trafficTicker != null) return
        trafficTicker = viewModelScope.launch {
            while (true) {
                loadTraffic()
                loadPackageGroups()
                delay(IF_AUTO_REFRESH_MS)
            }
        }
    }

    fun stopTrafficAutoRefresh() {
        trafficTicker?.cancel()
        trafficTicker = null
    }

    /** 刷新 MITM 代理状态。 */
    fun refreshMitm() {
        _state.value = _state.value.copy(
            mitmRunning = mitm.isRunning,
            mitmPort = mitm.port,
            caReady = mitm.caReady,
            mitmError = mitm.lastError,
        )
    }

    /** 启动 MITM 代理（流量入口由 VPN 负责，本代理只做解密与入库）。 */
    fun startMitm() {
        viewModelScope.launch {
            val ok = mitm.start()
            val msg = when {
                !ok -> "代理启动失败：${mitm.lastError ?: "未知错误"}"
                else -> "HTTPS 解密代理已启动"
            }
            refreshMitm()
            notify(msg)
        }
    }

    /** 停止代理。 */
    fun stopMitm() {
        viewModelScope.launch {
            mitm.stop()
            refreshMitm()
            notify("HTTPS 解密代理已停止")
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val s = repo.status()
            val sessions = repo.sessions(100)
            _state.value = _state.value.copy(
                running = s.running,
                sessionId = s.sessionId,
                packageName = s.packageName,
                filterLocal = s.filterLocalRequests,
                recordCount = s.recordCount,
                sessions = sessions,
            )
        }
    }

    fun start() {
        viewModelScope.launch {
            val pkg = _state.value.packageName ?: DEFAULT_TARGET
            repo.start(pkg, _state.value.filterLocal)
            notify("已开始抓包：$pkg")
            refresh()
        }
    }

    fun stop() {
        viewModelScope.launch {
            repo.stop()
            notify("已停止抓包")
            refresh()
        }
    }

    fun setFilterLocal(v: Boolean) {
        _state.value = _state.value.copy(filterLocal = v)
    }

    // ---------- 删除 ----------

    /** 删除单个会话（连带其流量记录）。 */
    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            val ok = repo.deleteSession(sessionId)
            notify(if (ok) "已删除该会话及其记录" else "删除失败")
            refresh()
        }
    }

    /** 清空所有已结束的会话。 */
    fun clearStopped() {
        viewModelScope.launch {
            val n = repo.clearStoppedSessions()
            notify(if (n > 0) "已清理 $n 个已结束会话" else "没有可清理的会话")
            refresh()
        }
    }

    /** 清空全部会话。 */
    fun clearAll() {
        viewModelScope.launch {
            val n = repo.clearAllSessions()
            notify("已清空 $n 个会话")
            refresh()
        }
    }

    // ---------- 多选 ----------

    fun toggleSelectionMode() {
        val s = _state.value
        _state.value = s.copy(
            selectionMode = !s.selectionMode,
            selected = if (s.selectionMode) emptySet() else s.selected,
        )
    }

    fun toggleSelect(sessionId: String) {
        val cur = _state.value.selected
        _state.value = _state.value.copy(
            selected = if (sessionId in cur) cur - sessionId else cur + sessionId,
        )
    }

    fun selectAll() {
        _state.value = _state.value.copy(selected = _state.value.sessions.map { it.sessionId }.toSet())
    }

    fun deleteSelected() {
        val ids = _state.value.selected.toList()
        if (ids.isEmpty()) {
            notify("未选择任何会话")
            return
        }
        viewModelScope.launch {
            val n = repo.deleteSessions(ids)
            _state.value = _state.value.copy(selected = emptySet(), selectionMode = false)
            notify("已删除 $n 个会话")
            refresh()
        }
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    private fun notify(msg: String) {
        _state.value = _state.value.copy(message = msg)
    }

    companion object {
        const val DEFAULT_TARGET = "com.android.settings"
    }
}

data class SessionUiState(
    val loading: Boolean = true,
    val items: List<CaptureRepository.NetworkItem> = emptyList(),
    val query: String = "",
    val protocolFilter: String? = null,
    val sessionId: String = "",
    val selectionMode: Boolean = false,
    val selected: Set<String> = emptySet(),
    val message: String? = null,
)

@HiltViewModel
class SessionViewModel @Inject constructor(
    private val repo: CaptureRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    /** 自动刷新任务：抓包过程中实时显示新记录。 */
    private var autoRefreshJob: kotlinx.coroutines.Job? = null

    fun load(sessionId: String) {
        _state.value = _state.value.copy(sessionId = sessionId)
        refresh()
        startAutoRefresh()
    }

    private fun startAutoRefresh() {
        if (autoRefreshJob?.isActive == true) return
        autoRefreshJob = viewModelScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(2500)
                if (!_state.value.selectionMode) refresh()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val s = _state.value
            _state.value = s.copy(loading = s.items.isEmpty())
            val proto = s.protocolFilter?.let {
                runCatching { com.sfzsgybb.ai.data.model.Protocol.valueOf(it.uppercase()) }.getOrNull()
            }
            val items = repo.networkList(
                sessionId = s.sessionId,
                afterTime = Long.MAX_VALUE,
                afterId = Long.MAX_VALUE,
                limit = 500,
                query = s.query.ifBlank { null },
                protocolFilter = proto,
            )
            _state.value = s.copy(loading = false, items = items)
        }
    }

    fun setQuery(q: String) {
        _state.value = _state.value.copy(query = q)
        refresh()
    }

    fun setProtocolFilter(p: String?) {
        _state.value = _state.value.copy(protocolFilter = p)
        refresh()
    }

    // ---------- 多选与删除 ----------

    fun toggleSelectionMode() {
        val s = _state.value
        _state.value = s.copy(
            selectionMode = !s.selectionMode,
            selected = if (s.selectionMode) emptySet() else s.selected,
        )
    }

    fun toggleSelect(key: String) {
        val cur = _state.value.selected
        _state.value = _state.value.copy(
            selected = if (key in cur) cur - key else cur + key,
        )
    }

    fun selectAll() {
        _state.value = _state.value.copy(
            selected = _state.value.items.map { "${it.kind}-${it.id}" }.toSet(),
        )
    }

    /** 删除单条记录。 */
    fun deleteRecord(item: CaptureRepository.NetworkItem) {
        viewModelScope.launch {
            val ok = if (item.kind == "http") repo.deleteHttpRecord(item.id)
            else repo.deletePacketRecord(item.id)
            notify(if (ok) "已删除" else "删除失败")
            refresh()
        }
    }

    /** 删除所选记录。 */
    fun deleteSelected() {
        val keys = _state.value.selected
        if (keys.isEmpty()) {
            notify("未选择任何记录")
            return
        }
        viewModelScope.launch {
            var n = 0
            keys.forEach { key ->
                val kind = key.substringBefore('-')
                val id = key.substringAfter('-').toLongOrNull() ?: return@forEach
                val ok = if (kind == "http") repo.deleteHttpRecord(id) else repo.deletePacketRecord(id)
                if (ok) n++
            }
            _state.value = _state.value.copy(selected = emptySet(), selectionMode = false)
            notify("已删除 $n 条记录")
            refresh()
        }
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    private fun notify(msg: String) {
        _state.value = _state.value.copy(message = msg)
    }

    override fun onCleared() {
        autoRefreshJob?.cancel()
        super.onCleared()
    }
}

data class HttpDetailUiState(
    val loading: Boolean = true,
    val record: HttpRecord? = null,
    val part: String = "response",
    val mode: ValueMode = ValueMode.TEXT,
)

@HiltViewModel
class HttpDetailViewModel @Inject constructor(
    private val repo: CaptureRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(HttpDetailUiState())
    val state: StateFlow<HttpDetailUiState> = _state.asStateFlow()

    fun load(id: Long) {
        viewModelScope.launch {
            val r = repo.httpById(id)
            _state.value = HttpDetailUiState(loading = false, record = r)
        }
    }

    fun setPart(p: String) { _state.value = _state.value.copy(part = p) }
    fun setMode(m: ValueMode) { _state.value = _state.value.copy(mode = m) }

    /** 按当前 part 与 mode 渲染正文；自动处理 gzip/deflate 解压。 */
    fun renderBody(): String {
        val r = _state.value.record ?: return ""
        val raw = if (_state.value.part == "request") r.requestBody else r.responseBody
        if (raw.isEmpty()) return ""
        val enc = if (_state.value.part == "request") {
            r.requestHeaders.firstOrNull { it.name.equals("Content-Encoding", true) }?.value
        } else {
            r.responseHeaders.firstOrNull { it.name.equals("Content-Encoding", true) }?.value
        }
        val decoded = repo.decodeBody(raw, enc)
        val bytes = if (decoded.decoded) decoded.bytes else raw
        val text = repo.render(bytes, _state.value.mode)
        return if (decoded.truncated) "$text\n\n[内容已截断]" else text
    }
}

data class TcpDetailUiState(
    val loading: Boolean = true,
    val packet: PacketData? = null,
    val mode: ValueMode = ValueMode.TEXT,
)

@HiltViewModel
class TcpDetailViewModel @Inject constructor(
    private val repo: CaptureRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(TcpDetailUiState())
    val state: StateFlow<TcpDetailUiState> = _state.asStateFlow()

    fun load(id: Long) {
        viewModelScope.launch {
            val p = repo.packetById(id)
            _state.value = TcpDetailUiState(loading = false, packet = p)
        }
    }

    fun setMode(m: ValueMode) { _state.value = _state.value.copy(mode = m) }

    fun render(): String {
        val p = _state.value.packet ?: return ""
        return repo.render(p.payload, _state.value.mode)
    }
}
