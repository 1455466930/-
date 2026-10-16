package com.sfzsgybb.ai.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sfzsgybb.ai.data.model.LogRecord
import com.sfzsgybb.ai.di.DeviceAppListProvider
import com.sfzsgybb.ai.hook.config.ConfigRepository
import com.sfzsgybb.ai.hook.config.GlobalSwitch
import com.sfzsgybb.ai.hook.config.HookSwitch
import com.sfzsgybb.ai.log.LogRepository
import com.sfzsgybb.ai.mcp.tools.AppEntry
import com.sfzsgybb.ai.util.AppLauncher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 应用列表页状态。 */
data class AppListUiState(
    val loading: Boolean = true,
    val apps: List<AppEntry> = emptyList(),
    val query: String = "",
    val onlyEnabled: Boolean = false,
    val totalCount: Int = 0,
    val enabledCount: Int = 0,
)

@HiltViewModel
class AppListViewModel @Inject constructor(
    private val appListProvider: DeviceAppListProvider,
    private val configRepository: ConfigRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AppListUiState())
    val state: StateFlow<AppListUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val all = appListProvider.listApps(false, null, 1000)
            _state.value = AppListUiState(
                loading = false,
                apps = all,
                query = _state.value.query,
                onlyEnabled = _state.value.onlyEnabled,
                totalCount = all.size,
                enabledCount = all.count { it.hookEnabled },
            )
        }
    }

    fun setQuery(q: String) {
        _state.value = _state.value.copy(query = q)
        applyFilter()
    }

    fun setOnlyEnabled(v: Boolean) {
        _state.value = _state.value.copy(onlyEnabled = v)
        applyFilter()
    }

    private fun applyFilter() {
        viewModelScope.launch {
            val s = _state.value
            val filtered = appListProvider.listApps(s.onlyEnabled, s.query.ifBlank { null }, 1000)
            _state.value = s.copy(apps = filtered, loading = false)
        }
    }

    suspend fun isEnabled(pkg: String): Boolean = configRepository.getAppConfig(pkg).enabled
    fun toggleEnabled(pkg: String, enabled: Boolean) {
        viewModelScope.launch {
            configRepository.setAppEnabled(pkg, enabled)
            refresh()
        }
    }
}

/** 应用详情页状态。 */
data class AppDetailUiState(
    val packageName: String = "",
    val appLabel: String = "",
    val enabled: Boolean = false,
    val switches: Map<String, Boolean> = emptyMap(),
    val hooks: Int = 0,
    val loading: Boolean = true,
    /** 目标应用是否正在运行 —— 运行中改配置需要重启才生效 */
    val targetRunning: Boolean = false,
    val hasRoot: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class AppDetailViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val configRepository: ConfigRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AppDetailUiState())
    val state: StateFlow<AppDetailUiState> = _state.asStateFlow()

    fun load(pkg: String) {
        val cfg = configRepository.getAppConfig(pkg)
        val label = runCatching {
            appContext.packageManager.getApplicationLabel(
                appContext.packageManager.getApplicationInfo(pkg, 0)
            ).toString()
        }.getOrDefault(pkg)
        _state.value = AppDetailUiState(
            packageName = pkg,
            appLabel = label,
            enabled = cfg.enabled,
            switches = HookSwitch.entries.associate { it.key to (cfg.switches[it.key] ?: false) },
            hooks = cfg.hooks.size,
            loading = false,
            targetRunning = AppLauncher.isRunning(appContext, pkg),
            hasRoot = AppLauncher.hasRoot(),
        )
    }

    fun setAppEnabled(v: Boolean) {
        configRepository.setAppEnabled(_state.value.packageName, v)
        _state.value = _state.value.copy(enabled = v)
    }

    fun setSwitch(sw: HookSwitch, on: Boolean) {
        configRepository.setSwitch(_state.value.packageName, sw, on)
        _state.value = _state.value.copy(switches = _state.value.switches + (sw.key to on))
    }

    fun setGroup(group: Int, on: Boolean) {
        configRepository.setGroupEnabled(_state.value.packageName, group, on)
        val cfg = configRepository.getAppConfig(_state.value.packageName)
        _state.value = _state.value.copy(
            switches = HookSwitch.entries.associate { it.key to (cfg.switches[it.key] ?: false) },
        )
    }

    // ---------- 启动 / 重启 ----------

    /** 仅启动目标应用。 */
    fun launchApp() {
        when (val r = AppLauncher.launch(appContext, _state.value.packageName)) {
            is AppLauncher.Result.Success ->
                notify("已启动 ${_state.value.appLabel}，等几秒后到「日志」页查看")
            is AppLauncher.Result.Failed -> notify(r.reason)
        }
    }

    /**
     * 强停并重启。
     * 这是配置改动后让 Hook 生效的标准操作：必须让目标应用重新走一遍
     * Application.onCreate，才会重新读取配置并安装模块。
     */
    fun restartApp() {
        val pkg = _state.value.packageName
        viewModelScope.launch {
            val stop = AppLauncher.forceStop(pkg)
            if (stop is AppLauncher.Result.Failed) {
                // 没有 root：引导用户去系统设置手动强停
                notify("${stop.reason}（已为你打开设置页）")
                AppLauncher.openAppSettings(appContext, pkg)
                return@launch
            }
            kotlinx.coroutines.delay(800)
            when (val r = AppLauncher.launch(appContext, pkg)) {
                is AppLauncher.Result.Success -> notify("已重启 ${_state.value.appLabel}")
                is AppLauncher.Result.Failed -> notify(r.reason)
            }
        }
    }

    /** 打开系统设置中的应用详情页。 */
    fun openSystemSettings() {
        AppLauncher.openAppSettings(appContext, _state.value.packageName)
    }

    /** 刷新运行状态。 */
    fun refreshRunning() {
        _state.value = _state.value.copy(
            targetRunning = AppLauncher.isRunning(appContext, _state.value.packageName),
        )
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    private fun notify(msg: String) {
        _state.value = _state.value.copy(message = msg)
    }
}

/** 日志列表页状态。 */
data class LogListUiState(
    val loading: Boolean = true,
    val logs: List<LogRecord> = emptyList(),
    val total: Long = 0,
    val query: String = "",
    val groupFilter: Int = -1,
    val packageFilter: String? = null,
    val hasMore: Boolean = false,
    /** 有日志的应用列表（软件名 → 包名 + 条数），用于顶部筛选 */
    val appOptions: List<LogAppOption> = emptyList(),
    /** 应用列表页的搜索词（按软件名或包名过滤） */
    val appQuery: String = "",
) {
    /** 按搜索词过滤后的应用列表。 */
    val filteredApps: List<LogAppOption>
        get() = if (appQuery.isBlank()) appOptions
        else appOptions.filter {
            it.label.contains(appQuery, ignoreCase = true) ||
                it.packageName.contains(appQuery, ignoreCase = true)
        }
}

/** 日志页的应用筛选项。 */
data class LogAppOption(
    val packageName: String,
    /** 应用显示名（取不到时回退成包名） */
    val label: String,
    val count: Int,
    /** 最近一条日志的时间 */
    val lastTime: Long = 0L,
    /** 该应用日志的总字节数 */
    val totalBytes: Long = 0L,
    /** 应用图标 */
    val icon: android.graphics.drawable.Drawable? = null,
)

@HiltViewModel
class LogListViewModel @Inject constructor(
    private val logRepository: LogRepository,
    private val configRepository: ConfigRepository,
    private val appList: com.sfzsgybb.ai.mcp.tools.AppListProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(LogListUiState())
    val state: StateFlow<LogListUiState> = _state.asStateFlow()

    private var cursorTime = Long.MAX_VALUE
    private var cursorId = Long.MAX_VALUE
    private val pageSize = 100

    /** 自动刷新任务。 */
    private var autoRefreshJob: Job? = null

    /** 包名 → 软件名 缓存，避免每次都查 PackageManager。 */
    private var labelCache: Map<String, String> = emptyMap()

    init {
        refresh()
        loadAppOptions()
    }

    /**
     * 加载「有日志的应用」列表（日志页首页的卡片列表）。
     *
     * 只列出真正产生过日志的应用，显示图标、软件名、包名、最近时间和数据量 ——
     * 用户点应用卡片就进该应用的日志列表，不用在一大堆混在一起的日志里翻。
     */
    fun loadAppOptions() {
        viewModelScope.launch {
            runCatching {
                // 刷新软件名缓存（一次拿全量，后续查表）
                if (labelCache.isEmpty()) {
                    labelCache = appList.listApps(onlyEnabled = false, query = null, limit = 1000)
                        .associate { it.packageName to it.label }
                }
                val stats = logRepository.packageStats()
                val options = stats.map { st ->
                    LogAppOption(
                        packageName = st.name,
                        label = labelCache[st.name] ?: st.name,
                        count = st.c,
                        lastTime = st.lastTime,
                        totalBytes = st.bytes,
                        icon = runCatching { appList.loadIcon(st.name) }.getOrNull(),
                    )
                }
                _state.value = _state.value.copy(appOptions = options)
            }.onFailure {
                android.util.Log.w("LogListViewModel", "加载应用列表失败: ${it.message}")
            }
        }
    }

    /**
     * 启动自动刷新。
     *
     * 日志是持续产生的（Hook 实时上报），一次性加载会导致用户看不到新数据。
     * 这里用「只取比当前最新一条更新的记录」的方式增量拉取，
     * 避免全量重查（那会在日志量大时明显卡顿）。
     */
    fun startAutoRefresh(intervalMs: Long = 2000L) {
        if (autoRefreshJob?.isActive == true) return
        autoRefreshJob = viewModelScope.launch {
            while (isActive) {
                delay(intervalMs)
                // 搜索状态和分页加载中不打扰
                if (_state.value.query.isNotBlank() || _state.value.loading) continue
                runCatching { pullNewest() }
                // 新应用产生日志后，筛选栏要能出现它
                runCatching { loadAppOptions() }
            }
        }
    }

    fun stopAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    /** 拉取比当前列表更新的记录，插到最前面。 */
    private suspend fun pullNewest() {
        val s = _state.value
        val newestTime = s.logs.firstOrNull()?.time ?: 0L
        val newestId = s.logs.firstOrNull()?.id ?: 0L

        // 用游标从最新往回取，取到 <= newestTime 为止
        val fresh = logRepository.page(
            packageName = s.packageFilter,
            group = s.groupFilter,
            afterTime = Long.MAX_VALUE,
            afterId = Long.MAX_VALUE,
            limit = pageSize,
        ).takeWhile { it.time > newestTime || (it.time == newestTime && it.id > newestId) }

        if (fresh.isEmpty()) {
            // 没有新数据，只更新总数
            val total = logRepository.count(s.packageFilter)
            if (total != s.total) _state.value = s.copy(total = total)
            return
        }

        _state.value = s.copy(
            logs = fresh + s.logs,
            total = logRepository.count(s.packageFilter),
        )
    }

    override fun onCleared() {
        stopAutoRefresh()
        super.onCleared()
    }

    fun refresh() {
        viewModelScope.launch {
            cursorTime = Long.MAX_VALUE
            cursorId = Long.MAX_VALUE
            _state.value = _state.value.copy(loading = true)
            loadPage(append = false)
        }
    }

    /** 增量游标翻页：不会因新数据插入而重复。 */
    fun loadMore() {
        if (_state.value.loading || !_state.value.hasMore) return
        viewModelScope.launch { loadPage(append = true) }
    }

    private suspend fun loadPage(append: Boolean) {
        val s = _state.value
        val rows = logRepository.page(
            packageName = s.packageFilter,
            group = s.groupFilter,
            afterTime = cursorTime,
            afterId = cursorId,
            limit = pageSize + 1,
        )
        val page = rows.take(pageSize)
        if (page.isNotEmpty()) {
            cursorTime = page.last().time
            cursorId = page.last().id
        }
        _state.value = s.copy(
            loading = false,
            logs = if (append) s.logs + page else page,
            hasMore = rows.size > pageSize,
            total = logRepository.count(s.packageFilter),
        )
    }

    fun search(q: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(query = q, loading = true)
            if (q.isBlank()) {
                refresh()
            } else {
                val rows = logRepository.search(q, pageSize, 0)
                _state.value = _state.value.copy(
                    loading = false, logs = rows, hasMore = false,
                    total = logRepository.count(_state.value.packageFilter),
                )
            }
        }
    }

    fun setGroup(g: Int) {
        _state.value = _state.value.copy(groupFilter = g)
        refresh()
    }

    /** 按应用筛选：null = 全部。 */
    fun setPackage(pkg: String?) {
        _state.value = _state.value.copy(packageFilter = pkg)
        refresh()
    }

    /** 搜索应用（按软件名或包名过滤应用列表）。 */
    fun setAppQuery(q: String) {
        _state.value = _state.value.copy(appQuery = q)
    }

    /**
     * 删除某个应用的全部日志。
     *
     * 由长按应用卡片 + 确认对话框触发。
     */
    fun deleteAppLogs(pkg: String) {
        viewModelScope.launch {
            runCatching {
                logRepository.clear(pkg)
                loadAppOptions()
                refresh()
            }.onFailure {
                android.util.Log.w("LogListViewModel", "删除 $pkg 的日志失败: ${it.message}")
            }
        }
    }

    fun clear() {
        viewModelScope.launch {
            logRepository.clear(_state.value.packageFilter)
            loadAppOptions()
            refresh()
        }
    }

    suspend fun isFoldDuplicates(): Boolean =
        configRepository.observeGlobal(GlobalSwitch.FOLD_DUPLICATE_LOGS).first()
}

/** 日志详情状态。 */
data class LogDetailUiState(
    val loading: Boolean = true,
    val log: LogRecord? = null,
    val mode: com.sfzsgybb.ai.data.model.ValueMode = com.sfzsgybb.ai.data.model.ValueMode.TEXT,
)

@HiltViewModel
class LogDetailViewModel @Inject constructor(
    private val logRepository: LogRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LogDetailUiState())
    val state: StateFlow<LogDetailUiState> = _state.asStateFlow()

    fun load(id: Long) {
        viewModelScope.launch {
            val log = logRepository.byId(id)
            _state.value = LogDetailUiState(loading = false, log = log)
            if (log != null) logRepository.markRead(listOf(id))
        }
    }

    fun setMode(m: com.sfzsgybb.ai.data.model.ValueMode) {
        _state.value = _state.value.copy(mode = m)
    }

    /**
     * 组装整条日志的纯文本，供「复制」使用。
     *
     * 字段排列对齐详情页展示顺序，复制出来的内容可直接贴到别处分析。
     */
    fun buildFullText(): String {
        val log = _state.value.log ?: return ""
        return buildString {
            append("时间: ").append(formatTime(log.time)).append('\n')
            append("日志名称: ").append(log.logName).append('\n')
            append("日志分组: ").append(com.sfzsgybb.ai.data.model.LogGroup.label(log.group)).append('\n')
            log.packageName?.takeIf { it.isNotBlank() }?.let {
                append("应用包名: ").append(it).append('\n')
            }
            if (log.className.isNotBlank()) append("类名: ").append(log.className).append('\n')
            if (log.objName.isNotBlank()) append("对象: ").append(log.objName).append('\n')
            if (log.algoName.isNotBlank()) append("算法名称: ").append(log.algoName).append('\n')
            if (log.paramDesc.isNotBlank()) append("参数: ").append(log.paramDesc).append('\n')
            if (log.keyData.isNotEmpty()) {
                append("密钥类型: ").append(log.keyType).append('\n')
                append("加密密钥: ").append(render(log.keyData)).append('\n')
            }
            append("\n--- 加密内容 (").append(log.payload.size).append(" B) ---\n")
            append(render(log.payload)).append('\n')
            log.results.forEachIndexed { i, r ->
                val kind = log.resultKind.ifBlank { "结果 #${i + 1}" }
                append("\n--- ").append(kind).append(" (").append(r.size).append(" B) ---\n")
                append(render(r)).append('\n')
            }
            if (log.callStack.isNotBlank()) {
                append("\n--- 调用堆栈 ---\n").append(log.callStack)
            }
        }
    }

    /** 轻提示（UI 层用 Snackbar 时可不调）。 */
    fun toast(msg: String) {
        android.util.Log.d("LogDetail", msg)
    }

    fun render(bytes: ByteArray): String = logRepository.render(bytes, _state.value.mode)
}
