package com.sfzsgybb.ai.hook.config

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.io.File
import com.sfzsgybb.ai.hook.config.GlobalSwitch.StringKeys

/**
 * 配置仓库。
 *
 * 【存储选型说明】
 * 配置必须让「被 Hook 的进程」能读到，因此只能用 SharedPreferences
 * （通过 XSharedPreferences 跨进程读取），不能用 DataStore / Room —
 * 后两者是进程私有的，被 Hook 进程无法访问。
 *
 * 之前的实现把配置写在 DataStore，而读取端用的是 XSharedPreferences，
 * 读写不是同一个存储，导致开关永远读不到、Hook 永不生效。此处统一为
 * 模块自己的 hook_config.xml 作为唯一真相源。
 *
 * 【跨进程可见性】
 * M 版本及以后 getSharedPreferences 默认是 MODE_PRIVATE，其他进程读不到。
 * LSPosed 的 XSharedPreferences 会以 root 权限直接读文件来绕过这个限制，
 * 因此路径必须在模块自己的 /data/data/<模块包名>/shared_prefs/ 下 —— 这正是
 * 宿主进程用自己 Context 创建 sp 的位置。
 *
 * 【变更通知】
 * 每次写入递增 revision，被 Hook 进程用 revision 判断是否需要重载，
 * 避免在 Hook 热路径上反复跨进程读文件。
 */
class ConfigRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 配置变更通知，in-process 用。跨进程靠 revision 判断。 */
    private val _revision = MutableStateFlow(readRevision())
    val revisionFlow: Flow<Long> = _revision.asStateFlow()

    private fun prefs(): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---------- 全局 ----------

    /**
     * 全局开关集合的响应式 Flow。
     *
     * 【为什么之前是死的】
     * 旧实现 `MutableStateFlow(readAllGlobal()).asStateFlow()` 只在创建时
     * 读一次快照，之后永不发射新值 —— 设置页的开关点了不变化，
     * 要重启 APP 才显示新状态。
     *
     * 现在用 SharedPreferences 的 listener 驱动：本进程任何写入
     * （包括 Hook 引擎、MCP 自启逻辑）都会立刻推到订阅方。
     */
    private val _globalState = MutableStateFlow(readAllGlobal())
    private var globalListenerRegistered = false

    init {
        registerGlobalListener()
    }

    private fun registerGlobalListener() {
        if (globalListenerRegistered) return
        globalListenerRegistered = true
        runCatching {
            prefs().registerOnSharedPreferenceChangeListener { _, key ->
                if (key == null || GlobalSwitch.entries.any { it.key == key } || key == KEY_REVISION) {
                    _globalState.value = readAllGlobal()
                }
            }
        }
    }

    fun observeGlobal(sw: GlobalSwitch): Flow<Boolean> = _globalState.map { it[sw.key] ?: sw.default }

    fun observeAllGlobal(): Flow<Map<String, Boolean>> = _globalState.asStateFlow()

    fun readAllGlobal(): Map<String, Boolean> {
        val p = prefs()
        return GlobalSwitch.entries.associate { it.key to p.getBoolean(it.key, it.default) }
    }

    fun getGlobal(sw: GlobalSwitch): Boolean = prefs().getBoolean(sw.key, sw.default)

    fun setGlobal(sw: GlobalSwitch, value: Boolean) {
        prefs().edit().putBoolean(sw.key, value).apply()
        // listener 是异步回调，这里直接同步更新，避免 UI 慢半拍
        _globalState.value = readAllGlobal()
        bumpRevision()
    }

    // ---------- 字符串配置 ----------

    fun getString(key: String, def: String? = null): String? =
        prefs().getString(key, def)

    fun setString(key: String, value: String?) {
        prefs().edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
        bumpRevision()
    }

    /** 取 MCP 访问令牌；不存在则生成一个并持久化。 */
    fun ensureMcpToken(): String {
        val existing = getString(StringKeys.MCP_ACCESS_TOKEN)
        if (!existing.isNullOrBlank()) return existing
        val token = com.sfzsgybb.ai.mcp.McpNetwork.generateToken()
        setString(StringKeys.MCP_ACCESS_TOKEN, token)
        return token
    }

    /** 重置 MCP 访问令牌。 */
    fun resetMcpToken(): String = ensureMcpToken().let {
        val fresh = com.sfzsgybb.ai.mcp.McpNetwork.generateToken()
        setString(StringKeys.MCP_ACCESS_TOKEN, fresh)
        fresh
    }

    // ---------- 单应用 ----------

    fun getAppConfig(packageName: String): AppConfig {
        val raw = prefs().getString(KEY_CONFIG_PREFIX + packageName, null)
        if (raw.isNullOrBlank()) return AppConfig(packageName = packageName)
        return runCatching { json.decodeFromString(AppConfig.serializer(), raw) }
            .getOrElse { AppConfig(packageName = packageName) }
    }

    fun saveAppConfig(config: AppConfig) {
        val encoded = json.encodeToString(AppConfig.serializer(), config)
        prefs().edit().putString(KEY_CONFIG_PREFIX + config.packageName, encoded).apply()
        bumpRevision()
    }

    fun setSwitch(packageName: String, sw: HookSwitch, on: Boolean) {
        val cfg = getAppConfig(packageName)
        saveAppConfig(cfg.copy(switches = cfg.switches + (sw.key to on)))
    }

    fun setAppEnabled(packageName: String, enabled: Boolean) {
        val cfg = getAppConfig(packageName)
        saveAppConfig(cfg.copy(enabled = enabled))
    }

    fun setGroupEnabled(packageName: String, group: Int, on: Boolean) {
        val cfg = getAppConfig(packageName)
        val updates = HookSwitch.byGroup(group).associate { it.key to on }
        saveAppConfig(cfg.copy(switches = cfg.switches + updates))
    }

    fun addHookRule(packageName: String, rule: HookRule) {
        val cfg = getAppConfig(packageName)
        val dup = cfg.hooks.any {
            it.className == rule.className && it.methodName == rule.methodName &&
                it.parameterSign == rule.parameterSign
        }
        if (dup) return
        saveAppConfig(cfg.copy(hooks = cfg.hooks + rule))
    }

    fun removeHookRule(packageName: String, ruleId: String) {
        val cfg = getAppConfig(packageName)
        saveAppConfig(cfg.copy(hooks = cfg.hooks.filterNot { it.id == ruleId }))
    }

    fun clearAppConfig(packageName: String) {
        prefs().edit().remove(KEY_CONFIG_PREFIX + packageName).apply()
        bumpRevision()
    }

    /** 已配置的应用包名列表。 */
    fun listConfiguredPackages(): List<String> =
        prefs().all.keys
            .filter { it.startsWith(KEY_CONFIG_PREFIX) }
            .map { it.removePrefix(KEY_CONFIG_PREFIX) }

    // ---------- revision ----------

    fun revision(): Long = prefs().getLong(KEY_REVISION, 0L)

    private fun readRevision(): Long = prefs().getLong(KEY_REVISION, 0L)

    private fun bumpRevision() {
        val next = prefs().getLong(KEY_REVISION, 0L) + 1L
        prefs().edit().putLong(KEY_REVISION, next).apply()
        _revision.value = next
        exportMirror()
    }

    /**
     * 导出配置镜像。
     *
     * 被 Hook 进程读模块私有 sp 时会受权限限制（模块 uid 与目标应用 uid 不同），
     * 因此每次写入后额外导出一份到「目标进程可读」的位置。
     *
     * 选择 /data/local/tmp：该目录权限为 0771 且对 app 进程可读，
     * 无需 root 也无需存储权限。（写入需要宿主有权限，宿主自身可写。）
     */
    private fun exportMirror() {
        val p = prefs()
        val sb = StringBuilder()
        for ((k, v) in p.all) {
            if (v == null) continue
            val text = v.toString().replace("\n", "\\n")
            sb.append(k).append('=').append(text).append('\n')
        }
        val content = sb.toString()

        // 位置 1：应用私有 files 目录（被 Hook 进程可通过 ContentProvider 读到）
        runCatching {
            val f = File(context.filesDir, MIRROR_NAME)
            f.writeText(content)
            f.setReadable(true, false)
        }

        // 位置 2：外部私有目录（部分 ROM 上目标进程可直接读文件）
        runCatching {
            val dir = context.getExternalFilesDir(null)
            if (dir != null) {
                val f = File(dir, MIRROR_NAME)
                f.writeText(content)
                f.setReadable(true, false)
            }
        }

        // 位置 3：有 root 时额外投递到 /data/local/tmp（被 Hook 进程一定能读）
        runCatching {
            val proc = ProcessBuilder(
                "su", "-c",
                "mkdir -p /data/local/tmp && cat > /data/local/tmp/$MIRROR_NAME && chmod 644 /data/local/tmp/$MIRROR_NAME",
            ).redirectErrorStream(true).start()
            proc.outputStream.use { it.write(content.toByteArray()) }
            proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    /** 首次启动写入默认值。 */
    fun ensureDefaults() {
        val p = prefs()
        val e = p.edit()
        GlobalSwitch.entries.forEach { sw ->
            if (!p.contains(sw.key)) e.putBoolean(sw.key, sw.default)
        }
        if (!p.contains(KEY_REVISION)) e.putLong(KEY_REVISION, 1L)
        e.apply()
        _revision.value = readRevision()
        exportMirror()
    }

    /** 供诊断：当前镜像内容。 */
    fun mirrorPreview(): String {
        val candidates = buildList {
            add(File(context.filesDir, MIRROR_NAME))
            context.getExternalFilesDir(null)?.let { add(File(it, MIRROR_NAME)) }
            add(File("/data/local/tmp/$MIRROR_NAME"))
        }
        return candidates.firstNotNullOfOrNull { f ->
            runCatching { f.takeIf { it.exists() }?.readText() }.getOrNull()
        } ?: "(无镜像)"
    }

    companion object {
        /**
         * sp 文件名。必须与被 Hook 进程里读取时使用的名称一致。
         */
        const val PREFS_NAME = "hook_config"

        /** 镜像文件名。 */
        const val MIRROR_NAME = "aidepro_config.conf"

        private const val KEY_CONFIG_PREFIX = "app_config_"
        private const val KEY_REVISION = "config_revision"

        /** 模块自身包名。 */
        const val MODULE_PACKAGE = "com.sfzsgybb.ai"

        val TOGGLE_COUNT: Int get() = HookSwitch.count
        val GLOBAL_COUNT: Int get() = GlobalSwitch.entries.size
    }
}
