package com.sfzsgybb.ai.ui.screens

import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sfzsgybb.ai.BuildConfig
import com.sfzsgybb.ai.hook.config.AppConfig
import com.sfzsgybb.ai.hook.config.ConfigRepository
import com.sfzsgybb.ai.hook.config.GlobalSwitch
import com.sfzsgybb.ai.hook.config.HookRule
import com.sfzsgybb.ai.mcp.McpConstants
import com.sfzsgybb.ai.mcp.McpHttpServer
import com.sfzsgybb.ai.mcp.McpNetwork
import com.sfzsgybb.ai.mcp.McpToolMeta
import com.sfzsgybb.ai.mcp.tools.FridaLogEntry
import com.sfzsgybb.ai.mcp.tools.FridaLogProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class SettingsUiState(
    val global: Map<String, Boolean> = emptyMap(),
    val mcpRunning: Boolean = false,
    val mcpPort: Int = McpConstants.DEFAULT_PORT,
    val versionName: String = BuildConfig.VERSION_NAME,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val configRepository: ConfigRepository,
    private val mcpServer: McpHttpServer,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            configRepository.observeAllGlobal().collect { map ->
                _state.value = _state.value.copy(global = map, mcpRunning = mcpServer.isRunning)
            }
        }
    }

    fun setGlobal(sw: GlobalSwitch, v: Boolean) {
        viewModelScope.launch { configRepository.setGlobal(sw, v) }
    }

    fun toggleMcp(enable: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (enable) {
                // 同步绑定策略，避免单例里的旧 config 绑错地址
                val lan = configRepository.getGlobal(GlobalSwitch.MCP_LAN_ENABLED)
                mcpServer.config = mcpServer.config.copy(bindLocalhostOnly = !lan)
                runCatching { mcpServer.start() }
            } else {
                runCatching { mcpServer.stop() }
            }
            _state.value = _state.value.copy(mcpRunning = mcpServer.isRunning)
        }
    }
}

data class McpConsoleUiState(
    val running: Boolean = false,
    val port: Int = McpConstants.DEFAULT_PORT,
    val tools: List<McpToolMeta> = emptyList(),
    val lanEnabled: Boolean = false,
    val keepAlive: Boolean = true,
    val lanUrl: String? = null,
    val localUrl: String = "",
    val connections: Int = 0,
    val requests: Long = 0,
) {
    /** 当前实际要用的连接地址（局域网优先）。 */
    val activeUrl: String get() = if (lanEnabled && !lanUrl.isNullOrBlank()) lanUrl else localUrl
}

@HiltViewModel
class McpConsoleViewModel @Inject constructor(
    private val mcpServer: McpHttpServer,
    private val dispatcher: com.sfzsgybb.ai.mcp.McpDispatcher,
    private val configRepository: ConfigRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(McpConsoleUiState(tools = dispatcher.toolMetaList()))
    val state: StateFlow<McpConsoleUiState> = _state.asStateFlow()

    init {
        syncState()
    }

    fun syncState() {
        val lan = configRepository.getGlobal(GlobalSwitch.MCP_LAN_ENABLED)
        val keep = configRepository.getGlobal(GlobalSwitch.MCP_KEEP_ALIVE)
        val port = mcpServer.actualPort.takeIf { it > 0 } ?: McpConstants.DEFAULT_PORT
        _state.value = _state.value.copy(
            running = mcpServer.isRunning,
            port = port,
            lanEnabled = lan,
            keepAlive = keep,
            localUrl = "http://127.0.0.1:$port/mcp",
            lanUrl = if (lan) McpNetwork.lanUrl(port) else null,
            connections = mcpServer.activeConnections.get(),
            requests = mcpServer.totalRequests.get(),
        )
    }

    fun startServer() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { configRepository.setGlobal(GlobalSwitch.MCP_HTTP_ENABLED, true) }
            // 同步绑定策略：配置里的局域网开关可能与 server 单例里的旧 config 不一致
            val lan = configRepository.getGlobal(GlobalSwitch.MCP_LAN_ENABLED)
            mcpServer.config = mcpServer.config.copy(bindLocalhostOnly = !lan)
            runCatching { mcpServer.stop() }
            runCatching { mcpServer.start() }
            if (configRepository.getGlobal(GlobalSwitch.MCP_KEEP_ALIVE)) {
                runCatching { com.sfzsgybb.ai.mcp.McpKeepAliveService.start(appContext) }
            }
            syncState()
        }
    }

    fun stopServer() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { mcpServer.stop() }
            runCatching { com.sfzsgybb.ai.mcp.McpKeepAliveService.stop(appContext) }
            runCatching { configRepository.setGlobal(GlobalSwitch.MCP_HTTP_ENABLED, false) }
            syncState()
        }
    }

    /** 切换局域网模式。切换后需要重启服务生效。 */
    fun setLanEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            configRepository.setGlobal(GlobalSwitch.MCP_LAN_ENABLED, enabled)
            // 【Bug 修复】McpHttpServer 是单例，构造时只读一次配置。
            // 必须先更新它的 config 再重启，否则重启后仍绑 127.0.0.1，
            // 局域网永远连不上。
            mcpServer.config = mcpServer.config.copy(bindLocalhostOnly = !enabled)
            runCatching { mcpServer.stop() }
            runCatching { mcpServer.start() }
            syncState()
        }
    }

    fun setKeepAlive(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            configRepository.setGlobal(GlobalSwitch.MCP_KEEP_ALIVE, enabled)
            if (enabled && mcpServer.isRunning) {
                runCatching { com.sfzsgybb.ai.mcp.McpKeepAliveService.start(appContext) }
            } else {
                runCatching { com.sfzsgybb.ai.mcp.McpKeepAliveService.stop(appContext) }
            }
            syncState()
        }
    }

    /** 定时刷新统计。 */
    fun refreshStats() {
        _state.value = _state.value.copy(
            running = mcpServer.isRunning,
            connections = mcpServer.activeConnections.get(),
            requests = mcpServer.totalRequests.get(),
        )
    }
}

data class HookListUiState(
    val packageName: String = "",
    val rules: List<HookRule> = emptyList(),
)

@HiltViewModel
class HookListViewModel @Inject constructor(
    private val configRepository: ConfigRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(HookListUiState())
    val state: StateFlow<HookListUiState> = _state.asStateFlow()

    fun load(pkg: String) {
        viewModelScope.launch {
            val cfg = configRepository.getAppConfig(pkg)
            _state.value = HookListUiState(pkg, cfg.hooks)
        }
    }

    fun add(rule: HookRule) {
        viewModelScope.launch {
            configRepository.addHookRule(_state.value.packageName, rule)
            load(_state.value.packageName)
        }
    }

    fun remove(id: String) {
        viewModelScope.launch {
            configRepository.removeHookRule(_state.value.packageName, id)
            load(_state.value.packageName)
        }
    }
}

data class FridaUiState(
    val packageName: String = "",
    val script: String = FridaViewModel.DEFAULT_SCRIPT,
    val savedMessage: String? = null,
)

@HiltViewModel
class FridaViewModel @Inject constructor(
    private val configRepository: ConfigRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(FridaUiState())
    val state: StateFlow<FridaUiState> = _state.asStateFlow()

    fun load(pkg: String) {
        viewModelScope.launch {
            val dir = scriptDir(pkg)
            val f = File(dir, "main.js")
            val content = if (f.exists()) runCatching { f.readText() }.getOrDefault(DEFAULT_SCRIPT) else DEFAULT_SCRIPT
            _state.value = FridaUiState(packageName = pkg, script = content)
        }
    }

    fun setScript(s: String) {
        _state.value = _state.value.copy(script = s)
    }

    fun save() {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                val dir = scriptDir(_state.value.packageName)
                if (!dir.exists()) dir.mkdirs()
                File(dir, "main.js").writeText(_state.value.script)
                true
            }.getOrDefault(false)
            _state.value = _state.value.copy(savedMessage = if (ok) "已保存" else "保存失败")
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(savedMessage = null)
    }

    private fun scriptDir(pkg: String): File =
        File(com.sfzsgybb.ai.AideProApp.get().filesDir, "frida/$pkg")

    companion object {
        val DEFAULT_SCRIPT = """
            Java.perform(function () {
                // 在这里编写 Frida 脚本
                // 例：打印所有 Cipher 调用
                // var Cipher = Java.use('javax.crypto.Cipher');
                // Cipher.doFinal.overload('[B').implementation = function (input) {
                //     var out = this.doFinal(input);
                //     console.log('doFinal ->', bytesToHex(out));
                //     return out;
                // };
            });
        """.trimIndent()
    }
}

data class FridaLogUiState(
    val packageName: String = "",
    val logs: List<FridaLogEntry> = emptyList(),
)

@HiltViewModel
class FridaLogViewModel @Inject constructor(
    private val provider: FridaLogProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(FridaLogUiState())
    val state: StateFlow<FridaLogUiState> = _state.asStateFlow()

    fun load(pkg: String) {
        viewModelScope.launch {
            val logs = provider.get(pkg, null, 500)
            _state.value = FridaLogUiState(pkg, logs)
        }
    }

    fun clear() {
        viewModelScope.launch {
            provider.clear(_state.value.packageName)
            load(_state.value.packageName)
        }
    }
}

data class AboutUiState(
    val versionName: String = BuildConfig.VERSION_NAME,
    val versionCode: Long = BuildConfig.VERSION_CODE.toLong(),
    /** 模块检测结果（复用首页那套三级检测） */
    val module: com.sfzsgybb.ai.util.ModuleStatusChecker.Status? = null,
    val rooted: Boolean = false,
    val scopeHint: String = "未配置",
    val toolCount: Int = 0,
) {
    /** 是否已激活（兼容旧 UI 字段）。 */
    val xposedActive: Boolean get() = module?.active == true

    /** 框架名。 */
    val frameworkName: String get() = module?.framework ?: "未检测到"
}

@HiltViewModel
class AboutViewModel @Inject constructor(
    private val dispatcher: com.sfzsgybb.ai.mcp.McpDispatcher,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {

    private val _state = MutableStateFlow(AboutUiState(toolCount = dispatcher.toolNames().size))
    val state: StateFlow<AboutUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            // 【重要】复用 ModuleStatusChecker，别再自己写 Class.forName。
            // Xposed API 是 compileOnly，APK 里没有这个类，
            // 在宿主进程里永远加载不到 —— 那是个必然失败的检测。
            val module = withContext(Dispatchers.IO) {
                runCatching { com.sfzsgybb.ai.util.ModuleStatusChecker.check(appContext) }.getOrNull()
            }
            _state.value = _state.value.copy(
                module = module,
                rooted = module?.rooted ?: false,
            )
        }
    }
}
