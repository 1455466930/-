package com.sfzsgybb.ai.di

import android.content.Context
import android.content.pm.ApplicationInfo
import com.sfzsgybb.ai.data.model.DatabaseInfo
import com.sfzsgybb.ai.hook.config.ConfigRepository
import com.sfzsgybb.ai.mcp.McpConstants
import com.sfzsgybb.ai.mcp.tools.AppEntry
import com.sfzsgybb.ai.mcp.tools.AppListProvider
import com.sfzsgybb.ai.mcp.tools.DatabaseProvider
import com.sfzsgybb.ai.mcp.tools.FridaLogEntry
import com.sfzsgybb.ai.mcp.tools.FridaLogProvider
import com.sfzsgybb.ai.mcp.tools.InstructionLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 基于 PackageManager 的应用列表。 */
@Singleton
class DeviceAppListProvider @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val configRepository: ConfigRepository,
) : AppListProvider {

    override suspend fun listApps(onlyEnabled: Boolean, query: String?, limit: Int): List<AppEntry> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val configured = configRepository.listConfiguredPackages().toHashSet()
            val configs = configured.associateWith { configRepository.getAppConfig(it) }
            pm.getInstalledApplications(0).asSequence()
                .filter { it.packageName != context.packageName }
                .map { info ->
                    AppEntry(
                        packageName = info.packageName,
                        label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(info.packageName),
                        versionName = runCatching {
                            pm.getPackageInfo(info.packageName, 0).versionName ?: ""
                        }.getOrDefault(""),
                        versionCode = runCatching {
                            val pi = pm.getPackageInfo(info.packageName, 0)
                            if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode
                            else @Suppress("DEPRECATION") pi.versionCode.toLong()
                        }.getOrDefault(0L),
                        hookEnabled = configs[info.packageName]?.enabled ?: false,
                        isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    )
                }
                .filter { !onlyEnabled || it.hookEnabled }
                .filter { query.isNullOrBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }
                // 已启用的排最前，组内按名称排序。
                // 这样用户打开列表先看到自己开过的应用，不用靠开关过滤。
                .sortedWith(compareByDescending<AppEntry> { it.hookEnabled }.thenBy { it.label })
                .take(limit)
                .toList()
        }

    /** 取应用图标（日志页卡片用）。 */
    override suspend fun loadIcon(packageName: String): android.graphics.drawable.Drawable? =
        withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
        }
}

/** 枚举目标应用的 SQLite 数据库文件。 */
@Singleton
class DeviceDatabaseProvider @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) : DatabaseProvider {

    override suspend fun listDatabases(packageName: String): List<DatabaseInfo> =
        withContext(Dispatchers.IO) {
            // 【Bug 修复】目标应用的 /data/data/<pkg>/databases 是私有目录，
            // 宿主 APP 用自己的 uid 直接 listFiles() 会 Permission denied
            // （连目录都列不出来）—— 所以之前永远返回空。
            // 现在优先用 root 枚举；无 root 时退回可直接读的路径。
            val viaRoot = listViaRoot(packageName)
            if (viaRoot.isNotEmpty()) return@withContext viaRoot

            // 无 root 回退：外部存储（targetSdk 29 及以下的应用才可能用）
            val roots = listOf(
                File("/sdcard/Android/data/$packageName/databases"),
                File("/storage/emulated/0/Android/data/$packageName/databases"),
            )
            val out = ArrayList<DatabaseInfo>()
            for (root in roots) {
                if (!root.exists() || !root.isDirectory) continue
                root.listFiles()?.forEach { f ->
                    if (!f.isFile) return@forEach
                    if (f.name.endsWith("-journal") || f.name.endsWith("-wal") ||
                        f.name.endsWith("-shm") || f.name.startsWith(".")) return@forEach
                    out += DatabaseInfo(packageName, f.name, f.absolutePath, f.length())
                }
            }
            out.sortedByDescending { it.size }
        }

    /**
     * 用 root 枚举目标应用的数据库文件。
     *
     * ls -l 输出解析：`-rw-rw---- 1 u0_a356 u0_a356 45056 2026-10-14 02:12 name`
     * 文件名在前，直接取最后一列；大小取第 5 列。
     */
    private fun listViaRoot(packageName: String): List<DatabaseInfo> = runCatching {
        val dirs = listOf(
            "/data/data/$packageName/databases",
            "/data/user/0/$packageName/databases",
        )
        val out = ArrayList<DatabaseInfo>()
        val seen = HashSet<String>()  // /data/data 与 /data/user/0 是同一目录的两个挂载点，按文件名去重
        for (dir in dirs) {
            // 不用 --time-style（toybox ls 不支持），直接用默认 ls -l
            // 不吞 stderr：失败时能看到原因（日志里）
            val listing = runSu("ls -l $dir 2>&1") ?: continue
            for (line in listing.lines()) {
                if (!line.startsWith("-")) continue  // 只要普通文件
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 5) continue
                // 【Bug 修复】日期时间可能占两列（`2026-10-14 02:12`），
                // 文件名永远在最后一列，不要按固定下标取。
                val name = parts.last()
                if (name.endsWith("-journal") || name.endsWith("-wal") ||
                    name.endsWith("-shm") || name.startsWith(".")) continue
                if (!seen.add(name)) continue  // 两个挂载点重复
                val size = parts[4].toLongOrNull() ?: 0L
                out += DatabaseInfo(packageName, name, "$dir/$name", size)
            }
        }
        out.sortedByDescending { it.size }
    }.getOrDefault(emptyList())

    private fun runSu(cmd: String): String? = runCatching {
        // 【Bug 修复】用 --mount-master 进入全局挂载命名空间：
        // 普通 su 跑在隔离的 mount namespace 里，/data/data 下
        // 看不到别的应用目录（No such file or directory）。
        // 先试 --mount-master，失败再回退普通 su。
        val candidates = listOf(
            listOf("su", "--mount-master", "-c", cmd),
            listOf("su", "-mm", "-c", cmd),
            listOf("su", "-c", cmd),
        )
        for (argv in candidates) {
            val p = ProcessBuilder(argv).redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            val done = p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)
            if (!done) {
                p.destroyForcibly()
                android.util.Log.w("DeviceDatabaseProvider", "su 超时: ${argv.joinToString(" ")}")
                continue
            }
            if (p.exitValue() != 0) {
                android.util.Log.w("DeviceDatabaseProvider", "su(${argv[1]}) 退出码 ${p.exitValue()}: ${text.take(120)}")
                continue
            }
            return@runCatching text
        }
        null
    }.getOrNull()
}

/**
 * Frida 日志实现。
 *
 * 两个来源：
 *  - 内存缓冲：Hook 进程通过管道写回（预留），或 UI 手动 append
 *  - FridaEngine 日志文件：frida_inject_script 注入的脚本 console.log 输出
 * get 时先拉引擎日志并入内存，保证 frida_get_log 能读到真数据。
 */
@Singleton
class InMemoryFridaLogProvider @Inject constructor(
    private val fridaEngine: com.sfzsgybb.ai.frida.FridaEngine,
) : FridaLogProvider {

    private val store = HashMap<String, ArrayDeque<FridaLogEntry>>()
    private val lock = Any()

    fun append(packageName: String, entry: FridaLogEntry) = synchronized(lock) {
        val q = store.getOrPut(packageName) { ArrayDeque() }
        if (q.size >= MAX_PER_APP) q.removeFirst()
        q.addLast(entry)
    }

    override suspend fun get(packageName: String, scriptName: String?, limit: Int): List<FridaLogEntry> =
        synchronized(lock) {
            // 先拉 FridaEngine 的日志文件，追加到内存（去重靠时间戳）
            pullEngineLogs(packageName)
            val q = store[packageName] ?: return emptyList()
            q.asSequence()
                .filter { scriptName.isNullOrBlank() || it.scriptName == scriptName }
                .toList()
                .takeLast(limit)
        }

    override suspend fun clear(packageName: String) {
        synchronized(lock) { store.remove(packageName) }
    }

    /** 把 FridaEngine 的日志文件内容拉到内存缓冲。 */
    private fun pullEngineLogs(packageName: String) {
        val lines = fridaEngine.readLog(packageName, 500)
        if (lines.isEmpty()) return
        val q = store.getOrPut(packageName) { ArrayDeque() }
        for (line in lines) {
            val entry = FridaLogEntry(
                time = System.currentTimeMillis(),
                scriptName = "frida",
                level = "info",
                message = line,
            )
            if (q.size >= MAX_PER_APP) q.removeFirst()
            q.addLast(entry)
        }
    }

    companion object {
        private const val MAX_PER_APP = 5000
    }
}

/** MCP 说明。优先读 assets，缺失时用内置文本。 */
@Singleton
class DefaultInstructionLoader @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) : InstructionLoader {

    override suspend fun instructions(): String = withContext(Dispatchers.IO) {
        val fromAsset = runCatching {
            context.assets.open("mcp/server_instructions.md").bufferedReader().use { it.readText() }
        }.getOrNull()
        if (!fromAsset.isNullOrBlank()) return@withContext fromAsset
        McpConstants.INSTRUCTIONS
    }
}
