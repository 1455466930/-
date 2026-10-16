package com.junge.aidepro.ui.screens

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.junge.aidepro.BuildConfig
import com.junge.aidepro.hook.config.ConfigRepository
import com.junge.aidepro.hook.config.GlobalSwitch
import com.junge.aidepro.mcp.McpHttpServer
import com.junge.aidepro.util.ModuleStatusChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 首页状态。 */
data class HomeUiState(
    val loading: Boolean = true,
    /** 模块激活状态 */
    val module: ModuleStatusChecker.Status? = null,
    /** 版本名 */
    val versionName: String = BuildConfig.VERSION_NAME,
    /** 版本号 */
    val versionCode: Long = BuildConfig.VERSION_CODE.toLong(),
    /** 已启用 Hook 的应用数 */
    val enabledApps: Int = 0,
    /** MCP 是否运行 */
    val mcpRunning: Boolean = false,
    /** MCP 端口 */
    val mcpPort: Int = 0,
    /** MCP 工具数 */
    val mcpTools: Int = 0,
    /** 日志条数 */
    val logCount: Long = 0,
    /** 抓包记录数 */
    val requestCount: Long = 0,
    /** 展开的说明弹窗（模块未激活时用） */
    val showActivationHelp: Boolean = false,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configRepository: ConfigRepository,
    private val mcpServer: McpHttpServer,
    private val mcpDispatcher: com.junge.aidepro.mcp.McpDispatcher,
    private val logRepository: com.junge.aidepro.log.LogRepository,
    private val analysis: com.junge.aidepro.capture.AnalysisRepository,
    private val appList: com.junge.aidepro.mcp.tools.AppListProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** 重新检测所有状态。 */
    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)

            // 模块激活状态（要跑 su，放 IO 线程）
            val module = withContext(Dispatchers.IO) {
                runCatching { ModuleStatusChecker.check(context) }.getOrNull()
            }

            val enabledApps = runCatching {
                appList.listApps(onlyEnabled = true, query = null, limit = 1000).size
            }.getOrDefault(0)

            val logCount = runCatching { logRepository.count(null) }.getOrDefault(0L)
            val requestCount = runCatching { analysis.countTrafficAll("") }.getOrDefault(0L)

            _state.value = _state.value.copy(
                loading = false,
                module = module,
                enabledApps = enabledApps,
                mcpRunning = mcpServer.isRunning,
                mcpPort = mcpServer.actualPort.takeIf { it > 0 } ?: 0,
                mcpTools = mcpDispatcher.toolNames().size,
                logCount = logCount,
                requestCount = requestCount,
            )
        }
    }

    /** 显示激活帮助。 */
    fun showHelp() {
        _state.value = _state.value.copy(showActivationHelp = true)
    }

    /** 关闭激活帮助。 */
    fun dismissHelp() {
        _state.value = _state.value.copy(showActivationHelp = false)
    }
}