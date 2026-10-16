package com.sfzsgybb.ai.hook.modules

import android.content.SharedPreferences
import com.sfzsgybb.ai.hook.config.HookSwitch
import com.sfzsgybb.ai.hook.engine.HookContext
import com.sfzsgybb.ai.hook.engine.HookModule
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
 *
 * 【日志怎么分类】
 * 列表里最要命的是「一堆 File 分不清读还是写」，所以这里把方向直接写进 logName：
 *
 *   logName   = 文件读取 / 文件写入 / 文件删除 / 文件复制
 *   objName   = 具体 API（FileInputStream、FileOutputStream(追加) …）
 *   paramDesc = 路径 + 关键细节（覆盖写会清空原内容、追加、偏移量…）
 *   payload   = 路径（保持旧格式兼容）
 *
 * 覆盖写和追加写是两码事：前者会清空原文件，逆向时常靠这个判断
 * 「这个操作是不是在重置配置/清缓存」，所以单独标出来。
 */
class FileModule : HookModule {
    override val switch = HookSwitch.FILE_READ

    override fun install(ctx: HookContext) {
        if (ctx.enabled(HookSwitch.FILE_READ)) installRead(ctx)
        if (ctx.enabled(HookSwitch.FILE_WRITE)) installWrite(ctx)
        if (ctx.enabled(HookSwitch.FILE_DELETE)) installDelete(ctx)
    }

    // ---------------- 读取 ----------------

    private fun installRead(ctx: HookContext) {
        // FileInputStream(String) / FileInputStream(File)
        hookCtors(ctx, "java.io.FileInputStream", HookSwitch.FILE_READ) { param, path ->
            report(ctx, "文件读取", "FileInputStream", path,
                "以字节流打开读取", HookSwitch.FILE_READ)
        }

        // FileReader —— 读文本常用这个，之前漏了
        hookCtors(ctx, "java.io.FileReader", HookSwitch.FILE_READ) { param, path ->
            report(ctx, "文件读取", "FileReader", path,
                "以字符流打开读取（文本）", HookSwitch.FILE_READ)
        }

        // RandomAccessFile —— 带模式的随机读写，要区分 "r" 和 "rw"
        runCatching {
            val c = ctx.findClassOrNull("java.io.RandomAccessFile") ?: return@runCatching
            XposedBridge.hookAllConstructors(c, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val path = param.args.getOrNull(0)?.toString() ?: return
                    val mode = param.args.getOrNull(1) as? String ?: "r"
                    val isWrite = mode.contains("w")
                    report(
                        ctx,
                        if (isWrite) "文件写入" else "文件读取",
                        "RandomAccessFile",
                        path,
                        "模式=$mode" + if (isWrite) "（可写）" else "（只读）",
                        if (isWrite) HookSwitch.FILE_WRITE else HookSwitch.FILE_READ,
                    )
                }
            })
        }
    }

    // ---------------- 写入 ----------------

    private fun installWrite(ctx: HookContext) {
        // FileOutputStream —— 关键是要看有没有 append 参数
        runCatching {
            val c = ctx.findClassOrNull("java.io.FileOutputStream") ?: return@runCatching
            XposedBridge.hookAllConstructors(c, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val path = param.args.getOrNull(0)?.toString() ?: return
                    // 第二个参数是 boolean append；没传就是 false（覆盖写）
                    val append = param.args.getOrNull(1) as? Boolean ?: false
                    val detail = if (append) {
                        "追加写入（保留原内容）"
                    } else {
                        "⚠ 覆盖写入（会清空原内容）"
                    }
                    report(ctx, "文件写入", "FileOutputStream", path, detail, HookSwitch.FILE_WRITE)
                }
            })
        }

        // FileWriter —— 写文本常用，同样区分 append
        runCatching {
            val c = ctx.findClassOrNull("java.io.FileWriter") ?: return@runCatching
            XposedBridge.hookAllConstructors(c, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val path = param.args.getOrNull(0)?.toString() ?: return
                    // FileWriter(String, boolean append) / FileWriter(File, boolean)
                    val append = param.args.drop(1).filterIsInstance<Boolean>().firstOrNull() ?: false
                    val detail = if (append) "追加写入（文本）" else "⚠ 覆盖写入（会清空原内容·文本）"
                    report(ctx, "文件写入", "FileWriter", path, detail, HookSwitch.FILE_WRITE)
                }
            })
        }

        // Files.write —— NIO 路径，现代代码常用
        runCatching {
            val c = ctx.findClassOrNull("java.nio.file.Files") ?: return@runCatching
            XposedBridge.hookAllMethods(c, "write", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val path = param.args.getOrNull(0)?.toString() ?: return
                    val opts = param.args.drop(1)
                        .flatMap { if (it is Array<*>) it.toList() else listOf(it) }
                        .joinToString(",") { it.toString() }
                    val append = opts.contains("APPEND")
                    val detail = if (append) "追加写入（NIO）" else "⚠ 覆盖写入（NIO，会清空原内容）"
                    report(ctx, "文件写入", "Files.write", path, detail, HookSwitch.FILE_WRITE)
                }
            })
        }
    }

    // ---------------- 删除 ----------------

    private fun installDelete(ctx: HookContext) {
        runCatching {
            val c = ctx.findClassOrNull("java.io.File") ?: return@runCatching

            // delete()
            XposedHelpers.findAndHookMethod(c, "delete", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val f = param.thisObject as? java.io.File ?: return
                    val ok = param.result as? Boolean ?: false
                    report(
                        ctx, "文件删除", "File.delete", f.absolutePath,
                        if (ok) "删除成功" else "删除失败（文件不存在或无权限）",
                        HookSwitch.FILE_DELETE,
                    )
                }
            })

            // deleteOnExit() —— 退出时才删，容易被忽略
            XposedHelpers.findAndHookMethod(c, "deleteOnExit", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val f = param.thisObject as? java.io.File ?: return
                    report(ctx, "文件删除", "File.deleteOnExit", f.absolutePath,
                        "进程退出时删除", HookSwitch.FILE_DELETE)
                }
            })
        }

        // Files.delete / deleteIfExists
        runCatching {
            val c = ctx.findClassOrNull("java.nio.file.Files") ?: return@runCatching
            listOf("delete", "deleteIfExists").forEach { m ->
                XposedBridge.hookAllMethods(c, m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val path = param.args.getOrNull(0)?.toString() ?: return
                        report(ctx, "文件删除", "Files.$m", path, "NIO 删除", HookSwitch.FILE_DELETE)
                    }
                })
            }
        }

        // File.renameTo —— 改名/移动，语义上算写入目标位置
        runCatching {
            val c = ctx.findClassOrNull("java.io.File") ?: return@runCatching
            XposedHelpers.findAndHookMethod(
                c, "renameTo", java.io.File::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val src = (param.thisObject as? java.io.File)?.absolutePath ?: return
                        val dst = (param.args.getOrNull(0) as? java.io.File)?.absolutePath ?: return
                        val ok = param.result as? Boolean ?: false
                        report(
                            ctx, "文件写入", "File.renameTo",
                            "$src  →  $dst",
                            if (ok) "重命名/移动成功" else "重命名/移动失败",
                            HookSwitch.FILE_WRITE,
                        )
                    }
                },
            )
        }
    }

    // ---------------- 公共 ----------------

    /** 批量 hook 构造函数，回调里给出路径。 */
    private inline fun hookCtors(
        ctx: HookContext,
        className: String,
        sw: HookSwitch,
        crossinline onHit: (de.robv.android.xposed.XC_MethodHook.MethodHookParam, String) -> Unit,
    ) {
        runCatching {
            val c = ctx.findClassOrNull(className) ?: return@runCatching
            XposedBridge.hookAllConstructors(c, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val path = param.args.getOrNull(0)?.toString() ?: return
                    onHit(param, path)
                }
            })
        }
    }

    /** 统一上报。 */
    private fun report(
        ctx: HookContext,
        logName: String,
        api: String,
        path: String,
        detail: String,
        sw: HookSwitch,
    ) {
        ctx.reporter.report(
            logName = logName,
            className = "java.io.File",
            objName = api,
            payload = path.utf8(),
            results = emptyList(),
            sw = sw,
            paramDesc = detail,
            // resultKind 复用为「操作类型」，详情页会显示成独立标签
            resultKind = logName.removePrefix("文件"),
        )
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
