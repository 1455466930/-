package com.junge.aidepro.hook.modules

import android.content.SharedPreferences
import com.junge.aidepro.hook.config.HookSwitch
import com.junge.aidepro.hook.engine.HookContext
import com.junge.aidepro.hook.engine.HookModule
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

private fun String.utf8() = toByteArray(Charsets.UTF_8)

/**
 * SQLite Hook。
 * 覆盖 open / insert / update / delete / query / execSQL 六项开关。
 */
class SqliteModule : HookModule {
    override val switch = HookSwitch.SQLITE_OPEN

    override fun install(ctx: HookContext) {
        val dbCls = ctx.findClassOrNull("android.database.sqlite.SQLiteDatabase") ?: return

        if (ctx.enabled(HookSwitch.SQLITE_OPEN)) {
            runCatching {
                XposedBridge.hookAllMethods(dbCls, "openOrCreateDatabase", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val path = param.args.getOrNull(0)?.toString() ?: return
                        ctx.reporter.report("SQLite", dbCls.name, "openOrCreateDatabase",
                            path.utf8(), listOf(("opened: $path").utf8()), HookSwitch.SQLITE_OPEN)
                    }
                })
                XposedBridge.hookAllMethods(dbCls, "openDatabase", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val path = param.args.getOrNull(0)?.toString() ?: return
                        ctx.reporter.report("SQLite", dbCls.name, "openDatabase",
                            path.utf8(), listOf(("opened: $path").utf8()), HookSwitch.SQLITE_OPEN)
                    }
                })
            }
        }

        hookSql(ctx, dbCls, "insert", HookSwitch.SQLITE_INSERT) { p ->
            "table=${p.args.getOrNull(0)} values=${p.args.getOrNull(1)}"
        }
        hookSql(ctx, dbCls, "insertOrThrow", HookSwitch.SQLITE_INSERT) { p ->
            "table=${p.args.getOrNull(0)} values=${p.args.getOrNull(1)}"
        }
        hookSql(ctx, dbCls, "insertWithOnConflict", HookSwitch.SQLITE_INSERT) { p ->
            "table=${p.args.getOrNull(0)} values=${p.args.getOrNull(1)} conflict=${p.args.getOrNull(2)}"
        }
        hookSql(ctx, dbCls, "update", HookSwitch.SQLITE_UPDATE) { p ->
            "table=${p.args.getOrNull(0)} values=${p.args.getOrNull(1)} where=${p.args.getOrNull(2)} args=${p.args.getOrNull(3)}"
        }
        hookSql(ctx, dbCls, "delete", HookSwitch.SQLITE_DELETE) { p ->
            "table=${p.args.getOrNull(0)} where=${p.args.getOrNull(1)} args=${p.args.getOrNull(2)}"
        }
        hookSql(ctx, dbCls, "rawQuery", HookSwitch.SQLITE_QUERY) { p ->
            "sql=${p.args.getOrNull(0)} args=${p.args.getOrNull(1)}"
        }
        hookSql(ctx, dbCls, "rawQueryWithFactory", HookSwitch.SQLITE_QUERY) { p ->
            "sql=${p.args.getOrNull(1)} args=${p.args.getOrNull(4)}"
        }
        hookSql(ctx, dbCls, "execSQL", HookSwitch.SQLITE_EXEC_SQL) { p ->
            "sql=${p.args.getOrNull(0)} args=${p.args.getOrNull(1)}"
        }

        // query 系列重载很多，统一用 hookAllMethods 覆盖
        if (ctx.enabled(HookSwitch.SQLITE_QUERY)) {
            runCatching {
                XposedBridge.hookAllMethods(dbCls, "query", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val table = param.args.firstOrNull()?.toString() ?: ""
                        val columns = param.args.getOrNull(1)?.let { (it as? Array<*>)?.joinToString() } ?: "*"
                        val selection = param.args.filterIsInstance<String>().getOrNull(1) ?: ""
                        ctx.reporter.report("SQLite", dbCls.name, "query",
                            "table=$table columns=$columns selection=$selection".utf8(),
                            emptyList(), HookSwitch.SQLITE_QUERY)
                    }
                })
            }
        }
    }

    private fun hookSql(
        ctx: HookContext,
        cls: Class<*>,
        method: String,
        sw: HookSwitch,
        describe: (XC_MethodHook.MethodHookParam) -> String,
    ) {
        if (!ctx.enabled(sw)) return
        runCatching {
            XposedBridge.hookAllMethods(cls, method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val desc = runCatching { describe(param) }.getOrDefault(method)
                    ctx.reporter.report("SQLite", cls.name, method, desc.utf8(), emptyList(), sw)
                }
            })
        }
    }
}

/**
 * SharedPreferences Hook。
 * 注意：getSharedPreferences 会被高频调用，需做去重避免日志爆炸。
 */
class SharedPreferencesModule : HookModule {
    override val switch = HookSwitch.SP_OPEN

    override fun install(ctx: HookContext) {
        val ctxCls = ctx.findClassOrNull("android.app.ContextImpl")
            ?: ctx.findClassOrNull("android.content.ContextWrapper")

        if (ctx.enabled(HookSwitch.SP_OPEN) && ctxCls != null) {
            runCatching {
                XposedBridge.hookAllMethods(ctxCls, "getSharedPreferences", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val name = param.args.getOrNull(0) as? String ?: return
                        val prefs = param.result as? SharedPreferences ?: return
                        ctx.reporter.report("SharedPreferences", ctxCls.name, "getSharedPreferences",
                            name.utf8(), listOf(("opened: $name").utf8()), HookSwitch.SP_OPEN)
                        SharedPrefsWatcher.watch(ctx, prefs, name)
                    }
                })
            }
        }

        // Editor 写入
        if (ctx.enabled(HookSwitch.SP_PUT)) {
            val editorCls = ctx.findClassOrNull("android.app.SharedPreferencesImpl\$EditorImpl") ?: return
            for (m in listOf("putString", "putInt", "putLong", "putFloat", "putBoolean", "putStringSet")) {
                runCatching {
                    XposedHelpers.findAndHookMethod(editorCls, m, String::class.java, Any::class.java,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val k = param.args.getOrNull(0) as? String ?: return
                                val v = param.args.getOrNull(1)?.toString() ?: "null"
                                ctx.reporter.report("SharedPreferences", editorCls.name, m,
                                    "$k = $v".utf8(), emptyList(), HookSwitch.SP_PUT)
                            }
                        })
                }
            }
        }
    }
}

/**
 * 已打开 SharedPreferences 的读取监听。
 * 原版同样只监听了 Editor 写入，读取侧容易日志过载，这里做采样。
 */
private object SharedPrefsWatcher {
    private val watched = HashSet<String>()
    private const val MAX_WATCH = 32

    fun watch(ctx: HookContext, prefs: SharedPreferences, name: String) {
        synchronized(watched) {
            if (watched.size >= MAX_WATCH || !watched.add(name)) return
        }
        // 读取侧不逐个 hook：SharedPreferencesImpl 的 getXxx 是 final 且调用极密，
        // 这里改为在写入时打全量日志，读取仅记录打开动作，避免拖慢目标应用。
    }
}

/**
 * 文件读写 Hook。
 */
class FileModule : HookModule {
    override val switch = HookSwitch.FILE_READ

    override fun install(ctx: HookContext) {
        if (ctx.enabled(HookSwitch.FILE_READ)) {
            listOf("java.io.FileInputStream", "java.io.RandomAccessFile").forEach { n ->
                val c = ctx.findClassOrNull(n) ?: return@forEach
                runCatching {
                    XposedBridge.hookAllConstructors(c, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val path = param.args.getOrNull(0)?.toString() ?: return
                            ctx.reporter.report("File", n, "<init>", path.utf8(), emptyList(), HookSwitch.FILE_READ)
                        }
                    })
                }
            }
        }

        if (ctx.enabled(HookSwitch.FILE_WRITE)) {
            val c = ctx.findClassOrNull("java.io.FileOutputStream") ?: return
            runCatching {
                XposedBridge.hookAllConstructors(c, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val path = param.args.getOrNull(0)?.toString() ?: return
                        ctx.reporter.report("File", c.name, "<init>", path.utf8(), emptyList(), HookSwitch.FILE_WRITE)
                    }
                })
            }
        }

        if (ctx.enabled(HookSwitch.FILE_DELETE)) {
            val c = ctx.findClassOrNull("java.io.File") ?: return
            runCatching {
                XposedHelpers.findAndHookMethod(c, "delete", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val f = param.thisObject as? java.io.File ?: return
                        ctx.reporter.report("File", c.name, "delete", f.absolutePath.utf8(), emptyList(), HookSwitch.FILE_DELETE)
                    }
                })
            }
        }
    }
}

/**
 * Assets 读取 Hook。
 */
class AssetsModule : HookModule {
    override val switch = HookSwitch.ASSETS

    override fun install(ctx: HookContext) {
        val cls = ctx.findClassOrNull("android.content.res.AssetManager") ?: return
        runCatching {
            XposedBridge.hookAllMethods(cls, "open", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val name = param.args.getOrNull(0) as? String ?: return
                    ctx.reporter.report("Assets", cls.name, "open", name.utf8(), emptyList(), switch)
                }
            })
        }
    }
}

/** 命令执行 Hook。 */
class ShellModule : HookModule {
    override val switch = HookSwitch.SHELL

    override fun install(ctx: HookContext) {
        listOf("java.lang.Runtime", "java.lang.ProcessBuilder").forEach { n ->
            val c = ctx.findClassOrNull(n) ?: return@forEach
            runCatching {
                XposedBridge.hookAllMethods(c, "exec", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val cmd = param.args.getOrNull(0)
                        val text = when (cmd) {
                            is Array<*> -> cmd.joinToString(" ")
                            is List<*> -> cmd.joinToString(" ")
                            else -> cmd?.toString() ?: ""
                        }
                        ctx.reporter.report("Shell", n, "exec", text.utf8(), emptyList(), switch)
                    }
                })
                XposedBridge.hookAllMethods(c, "start", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pb = param.thisObject as? ProcessBuilder ?: return
                        ctx.reporter.report("Shell", n, "start", pb.command().joinToString(" ").utf8(), emptyList(), switch)
                    }
                })
            }
        }
    }
}
