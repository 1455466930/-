package com.junge.aidepro.mcp.tools

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 拦截脚本存储。
 *
 * 与抓包引擎的 JS 拦截脚本对应（ProxyPin 的 script.json 概念）。
 * 脚本内容与元数据存在 SharedPreferences 里，代码本体也落一份到 files 目录，
 * 便于用户用文本编辑器查看。
 *
 * 注意：本类只负责存取，脚本的**执行**由 JS 引擎负责
 * （当前未接入执行引擎，脚本编辑与存储先就位，
 * 后续接入 Rhino/QuickJS 时只需在此扩展执行入口）。
 */
@Singleton
class ScriptStoreImpl @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) : ScriptStore {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Entry(
        val name: String,
        val urlPattern: String,
        val code: String,
        val enabled: Boolean = true,
    )

    @Serializable
    private data class Store(val items: List<Entry> = emptyList())

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(): List<Entry> {
        val raw = prefs().getString(KEY, null) ?: return emptyList()
        return runCatching { json.decodeFromString(Store.serializer(), raw).items }
            .getOrDefault(emptyList())
    }

    private fun persist(items: List<Entry>) {
        prefs().edit { putString(KEY, json.encodeToString(Store.serializer(), Store(items))) }
        // 同时导出到文件，方便查看
        runCatching {
            val dir = java.io.File(context.filesDir, "scripts").apply { mkdirs() }
            items.forEachIndexed { i, e ->
                java.io.File(dir, "${i}_${sanitize(e.name)}.js").writeText(e.code)
            }
        }.onFailure { Log.w(TAG, "导出脚本失败: ${it.message}") }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9_\\-\\u4e00-\\u9fa5]"), "_").take(40)

    override fun list(): List<ScriptStore.Item> =
        load().mapIndexed { i, e ->
            ScriptStore.Item(i, e.name, e.urlPattern, e.code, e.enabled)
        }

    override fun get(index: Int): ScriptStore.Item? =
        load().getOrNull(index)?.let { ScriptStore.Item(index, it.name, it.urlPattern, it.code, it.enabled) }

    override fun save(index: Int?, name: String, urlPattern: String, code: String): Int {
        val items = load().toMutableList()
        val entry = Entry(name, urlPattern, code)
        return if (index != null && index in items.indices) {
            items[index] = entry
            persist(items)
            index
        } else {
            items.add(entry)
            persist(items)
            items.size - 1
        }
    }

    companion object {
        private const val TAG = "ScriptStore"
        private const val PREFS = "intercept_scripts"
        private const val KEY = "scripts"
    }
}
