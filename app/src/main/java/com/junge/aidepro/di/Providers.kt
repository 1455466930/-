package com.junge.aidepro.di

import android.content.Context
import android.content.pm.ApplicationInfo
import com.junge.aidepro.data.model.DatabaseInfo
import com.junge.aidepro.hook.config.ConfigRepository
import com.junge.aidepro.mcp.McpConstants
import com.junge.aidepro.mcp.tools.AppEntry
import com.junge.aidepro.mcp.tools.AppListProvider
import com.junge.aidepro.mcp.tools.DatabaseProvider
import com.junge.aidepro.mcp.tools.FridaLogEntry
import com.junge.aidepro.mcp.tools.FridaLogProvider
import com.junge.aidepro.mcp.tools.InstructionLoader
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

    override suspend fun listDatabases(packageName: String): List<DatabaseInfo> = withContext(Dispatchers.IO) {
        val roots = buildList {
            // 常规私有目录（需 root 或同 uid 才可读）
            add(File("/data/data/$packageName/databases"))
            add(File(context.filesDir.parentFile?.parentFile, "$packageName/databases"))
            // 外部存储（targetSdk 29 及以下常见）
            add(File("/sdcard/Android/data/$packageName/databases"))
        }
        val out = ArrayList<DatabaseInfo>()
        for (root in roots) {
            if (!root.exists() || !root.isDirectory) continue
            root.listFiles()?.forEach { f ->
                if (!f.isFile) return@forEach
                // 忽略 SQLite 的 -journal / -wal / -shm 辅助文件
                if (f.name.endsWith("-journal") || f.name.endsWith("-wal") ||
                    f.name.endsWith("-shm") || f.name.startsWith(".")) return@forEach
                out += DatabaseInfo(packageName, f.name, f.absolutePath, f.length())
            }
        }
        out.sortedByDescending { it.size }
    }
}

/**
 * Frida 日志内存实现。
 *
 * 真实场景应由 Frida 脚本通过管道写回（原版走 openFridaWritePipe 的 ParcelFileDescriptor），
 * 这里先落内存环形缓冲，接口保持不变，后续接管道实现时 UI 与 MCP 层无需改动。
 */
@Singleton
class InMemoryFridaLogProvider @Inject constructor() : FridaLogProvider {

    private val store = HashMap<String, ArrayDeque<FridaLogEntry>>()
    private val lock = Any()

    fun append(packageName: String, entry: FridaLogEntry) = synchronized(lock) {
        val q = store.getOrPut(packageName) { ArrayDeque() }
        if (q.size >= MAX_PER_APP) q.removeFirst()
        q.addLast(entry)
    }

    override suspend fun get(packageName: String, scriptName: String?, limit: Int): List<FridaLogEntry> =
        synchronized(lock) {
            val q = store[packageName] ?: return emptyList()
            q.asSequence()
                .filter { scriptName.isNullOrBlank() || it.scriptName == scriptName }
                .toList()
                .takeLast(limit)
        }

    override suspend fun clear(packageName: String) {
        synchronized(lock) { store.remove(packageName) }
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
