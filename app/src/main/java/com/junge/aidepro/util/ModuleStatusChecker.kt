package com.junge.aidepro.util

import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.util.Log
import java.io.File

/**
 * 模块激活状态检测。
 *
 * 【为什么不能只做 `Class.forName("XposedBridge")`】
 * 这是最常见的错误写法：XposedBridge 在编译期就打进 APK 了，
 * 任何进程都能加载到，跟模块有没有被激活**毫无关系**。
 *
 * 【可靠的做法：直接读 LSPosed 的配置数据库】
 * LSPosed 把「哪些模块启用了、作用域是谁」存在
 * `/data/adb/lspd/config/modules_config.db`：
 *
 *   modules        (module_pkg_name, apk_path)          —— 模块注册表
 *   modules_state  (module_pkg_name, user_id, enabled)  —— 启用状态
 *   scope          (module_pkg_name, app_pkg_name)      —— 作用域
 *
 * 该文件是 root-only，设备上通常也没有 sqlite3 命令，
 * 所以用 `su cp` 拷到临时目录，再用 Android 自带的 SQLite 打开查询。
 */
object ModuleStatusChecker {

    private const val TAG = "ModuleStatus"

    /** LSPosed 的模块配置库。 */
    private const val LSPD_DB = "/data/adb/lspd/config/modules_config.db"

    /** 拷贝落点：app 私有目录（SELinux 允许 app 读，不需要额外上下文）。 */
    private val APP_TMP_DIR: String by lazy {
        val ctx = com.junge.aidepro.AideProApp.peek()
        val base = ctx?.filesDir?.absolutePath ?: "/data/local/tmp"
        "$base/lspd"
    }

    private val LSPOSED_PACKAGES = listOf(
        "org.lsposed.manager",
        "org.lsposed.manager.debug",
        "io.github.lsposed.manager",
        "com.lsposed.manager",
    )

    private val OTHER_FRAMEWORKS = mapOf(
        "de.robv.android.xposed.installer" to "Xposed Installer",
        "org.meowcat.edxposed.manager" to "EdXposed",
        "com.solohsu.android.edxp.manager" to "EdXposed",
        "com.elderdrivers.riru.edxp.manager" to "EdXposed",
    )

    enum class State { ACTIVE, INSTALLED_INACTIVE, NO_FRAMEWORK, UNKNOWN }

    data class Status(
        val state: State,
        val framework: String?,
        val summary: String,
        /** 未激活时的操作指引；已激活时 null */
        val hint: String?,
        val rooted: Boolean,
        /** 是否打包了 Xposed 入口 */
        val modulePackaged: Boolean,
        /** 作用域里配置的应用（不含 system） */
        val scopes: List<String> = emptyList(),
    ) {
        val active: Boolean get() = state == State.ACTIVE
    }

    // ==================== 对外入口 ====================

    fun check(context: Context): Status {
        val rooted = isRooted()
        val packaged = hasXposedEntry(context)
        val (fwPkg, fwName) = detectFramework(context)

        // ---------- 证据收集（按可靠性从高到低）----------
        //
        // 【为什么要多级回退】
        // 只靠读 LSPosed 数据库有个硬伤：需要 root，而且不同框架/版本
        // 路径不一样（LSPosed/KernelSU/APatch 各有各的存放位置）。
        // 设备上没 root、或用别的 root 方案时就会误报「未激活」，
        // 用户以为功能坏了 —— 但实际 Hook 是好的。
        //
        // 所以这里收集多份证据，任意一份成立就认为已激活。

        // 证据 1：LSPosed 配置库（最准，需要 root）
        val dbProbe = readLspdState(context)

        // 证据 2：XposedBridge 是否真实可用（不需要 root）
        //   api 是 compileOnly，APK 里没有这个类；
        //   能加载到 = 框架已经把它注入进我们的进程了。
        val bridgeAlive = isXposedBridgeAlive()

        // 证据 3：进程内存映射里的框架痕迹（不需要 root，读自己的 maps）
        val mapsHit = processMapsHit()

        val evidence = buildList {
            if (dbProbe != null && dbProbe.second) add("配置库已启用")
            if (bridgeAlive) add("XposedBridge 可用")
            if (mapsHit) add("进程已注入")
        }

        // 诊断：出问题时能一眼看出是「哪个证据没拿到」
        Log.i(
            TAG,
            "检测: fw=$fwName root=$rooted packaged=$packaged " +
                "db=${dbProbe?.let { "reg=${it.first},en=${it.second},scopes=${it.third.size}" } ?: "null"} " +
                "bridge=$bridgeAlive maps=$mapsHit evidence=$evidence",
        )

        // ---------- 判定 ----------
        // 没有框架包、也没有任何注入痕迹 → 真的没装
        if (fwName == null && !bridgeAlive && !mapsHit) {
            return Status(
                state = State.NO_FRAMEWORK,
                framework = null,
                summary = "未检测到 Xposed 框架",
                hint = buildNoFrameworkHint(rooted),
                rooted = rooted,
                modulePackaged = packaged,
            )
        }

        val name = fwName ?: "Xposed"

        // 有注入证据 → 已激活
        if (evidence.isNotEmpty()) {
            val scopes = dbProbe?.third ?: emptyList()
            val scopeText = if (scopes.isNotEmpty()) " · 已启用 ${scopes.size} 个应用" else ""
            return Status(
                state = State.ACTIVE,
                framework = name,
                summary = "$name · ${evidence.first()}$scopeText",
                hint = null,
                rooted = rooted,
                modulePackaged = packaged,
                scopes = scopes,
            )
        }

        // 读了库但明确未启用 → 未激活
        if (dbProbe != null) {
            val (registered, _, _) = dbProbe
            return Status(
                state = State.INSTALLED_INACTIVE,
                framework = name,
                summary = if (registered) "$name 已安装，模块未启用" else "$name 已安装，模块未注册",
                hint = buildInactiveHint(name),
                rooted = rooted,
                modulePackaged = packaged,
            )
        }

        // 完全查不到（没 root 且无注入证据）→ 未知，但不武断说没激活
        return Status(
            state = State.UNKNOWN,
            framework = name,
            summary = "$name 已安装，无法确认模块状态",
            hint = buildUnknownHint(name, rooted),
            rooted = rooted,
            modulePackaged = packaged,
        )
    }

    /**
     * XposedBridge 是否真实可用。
     *
     * 【原理】Xposed API 在 build.gradle 里是 compileOnly，**没有打进 APK**。
     * 所以如果 `Class.forName("de.robv.android.xposed.XposedBridge")` 能成功，
     * 只可能是框架把它注入到了本进程（或加进了 bootclasspath）——
     * 这就是「模块已生效」的可靠证据。
     *
     * 再进一步确认：调用它的静态方法，能正常返回才算数
     * （有些框架会塞一个空壳类做伪装检测对抗）。
     */
    private fun isXposedBridgeAlive(): Boolean {
        // 1) 先看类能不能加载
        val cls = runCatching {
            Class.forName("de.robv.android.xposed.XposedBridge", false, null)
        }.getOrNull() ?: runCatching {
            Class.forName("de.robv.android.xposed.XposedBridge")
        }.getOrNull() ?: return false

        // 2) 再看关键方法在不在
        val hasLog = runCatching {
            cls.getDeclaredMethod("log", String::class.java)
        }.isSuccess
        val hasHook = runCatching {
            cls.getDeclaredMethod("hookMethod", java.lang.reflect.Member::class.java, Any::class.java)
        }.isSuccess

        return hasLog && hasHook
    }

    /**
     * 进程内存映射里有没有框架的痕迹（不需要 root：读自己的 /proc/self/maps）。
     */
    private fun processMapsHit(): Boolean = runCatching {
        val maps = File("/proc/self/maps")
        if (!maps.exists() || !maps.canRead()) return false
        val keywords = listOf("lspd", "xposed", "edxp", "riru", "zygisk")
        maps.bufferedReader().useLines { lines ->
            lines.any { line ->
                val l = line.lowercase()
                keywords.any { l.contains(it) }
            }
        }
    }.getOrDefault(false)

    // ==================== 读 LSPosed 数据库 ====================

    /**
     * 从 LSPosed 配置库读模块状态。
     *
     * @return (是否已注册, 是否启用, 作用域应用列表)；读不到返回 null
     */
    private fun readLspdState(context: Context): Triple<Boolean, Boolean, List<String>>? {
        val self = context.packageName

        // 1) 用 su 把库（连同 WAL）拷到 app 可读的位置
        val copied = copyDbWithSu()
        if (!copied) {
            Log.d(TAG, "拷贝 LSPosed 数据库失败（可能无 root）")
            return null
        }

        // 2) 用 Android SQLite 打开查询
        return runCatching {
            val f = File("$APP_TMP_DIR/modules_config.db")
            if (!f.exists()) return null
            val db = SQLiteDatabase.openDatabase(
                f.absolutePath, null, SQLiteDatabase.OPEN_READONLY,
            )
            db.use { d ->
                // 是否注册
                val registered = d.rawQuery(
                    "SELECT 1 FROM modules WHERE module_pkg_name=? LIMIT 1", arrayOf(self),
                ).use { it.moveToFirst() }

                // 是否启用（enabled=1 且 user_id=0）
                val enabled = d.rawQuery(
                    "SELECT 1 FROM modules_state WHERE module_pkg_name=? AND enabled=1 LIMIT 1",
                    arrayOf(self),
                ).use { it.moveToFirst() }

                // 作用域（排除 system，那是系统框架，不算「应用」）
                val scopes = mutableListOf<String>()
                d.rawQuery(
                    "SELECT app_pkg_name FROM scope WHERE module_pkg_name=? AND app_pkg_name<>'system'",
                    arrayOf(self),
                ).use { cur ->
                    while (cur.moveToNext()) {
                        cur.getString(0)?.let { scopes.add(it) }
                    }
                }
                Triple(registered, enabled, scopes)
            }
        }.getOrElse {
            Log.w(TAG, "解析 LSPosed 数据库失败: ${it.message}")
            null
        }?.also {
            Log.i(
                TAG,
                "LSPosed 状态: registered=${it.first} enabled=${it.second} scopes=${it.third}",
            )
        }
    }

    /** 用 su 把 LSPosed 数据库（含 WAL/SHM）拷到临时目录。 */
    private fun copyDbWithSu(): Boolean {
        val src = LSPD_DB
        // 先确认源文件存在
        if (runSu("test -f '$src' && echo y")?.trim() != "y") return false

        // 【关键】猫到 app 私有目录，不要放 /data/local/tmp：
        //  1. /data/local/tmp 是 shell_data_file，SELinux 下 app 读不了
        //     （即使 chmod 666，表现为 ls 显示权限为 ???）
        //  2. app 的私有目录（/data/data/<pkg>/files）天然可读，不需要改权限
        val dst = "$APP_TMP_DIR"
        val cmd = buildString {
            append("mkdir -p $dst && ")
            append("rm -f $dst/modules_config.db* && ")
            append("cp -f $src $dst/ 2>/dev/null; ")
            append("cp -f $src-wal $dst/ 2>/dev/null; ")
            append("cp -f $src-shm $dst/ 2>/dev/null; ")
            // 私有目录仍需放开权限：su 拷进去的文件属主是 root，
            // 而 app 以 u0_aXXX 身份读，必须给 666
            append("chmod 666 $dst/modules_config.db* 2>/dev/null; ")
            append("chcon u:object_r:app_data_file:s0 $dst/modules_config.db* 2>/dev/null; ")
            append("echo ok")
        }
        val out = runSu(cmd)
        Log.d(TAG, "拷贝 LSPosed 库: ${out?.take(60)}")

        val f = File("$dst/modules_config.db")
        val canRead = runCatching { f.exists() && f.length() > 0 && f.canRead() }.getOrDefault(false)
        if (!canRead) {
            Log.d(TAG, "拷贝后仍不可读: exists=${f.exists()} len=${f.length()} canRead=${f.canRead()}")
        }
        return canRead
    }

    // ==================== 基础检测 ====================

    private fun detectFramework(context: Context): Pair<String?, String?> {
        val pm = context.packageManager
        for (pkg in LSPOSED_PACKAGES) {
            if (isInstalled(pm, pkg)) return pkg to "LSPosed"
        }
        for ((pkg, name) in OTHER_FRAMEWORKS) {
            if (isInstalled(pm, pkg)) return pkg to name
        }
        return null to null
    }

    private fun isInstalled(pm: PackageManager, pkg: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0)
        }
        true
    }.getOrDefault(false)

    private fun hasXposedEntry(context: Context): Boolean = runCatching {
        context.assets.open("xposed_init").use { it.read() }
        true
    }.getOrDefault(false)

    private fun isRooted(): Boolean {
        val paths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/data/local/xbin/su", "/data/local/bin/su", "/system/sd/xbin/su",
        )
        if (paths.any { runCatching { File(it).exists() }.getOrDefault(false) }) return true
        return runSu("id")?.contains("uid=0") == true
    }

    private fun runSu(cmd: String): String? = runCatching {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        val code = p.waitFor()
        if (code == 0) out else null
    }.getOrNull()

    // ==================== 操作指引 ====================

    private fun buildNoFrameworkHint(rooted: Boolean): String = buildString {
        append("Hook 功能需要 Xposed 框架，当前未检测到。\n\n")
        append("安装步骤：\n")
        append("1. 确认设备已 root")
        if (!rooted) append("（当前未检测到 root，这是前提）")
        append("\n")
        append("2. 安装 Magisk，启用 Zygisk\n")
        append("3. 安装 LSPosed（Zygisk 版）\n")
        append("4. 打开 LSPosed → 模块 → 启用「算法助手公益版」\n")
        append("5. 在「作用域」里勾选要分析的应用\n")
        append("6. 强行停止目标应用，重新打开\n\n")
        append("完成后回到这里点「重新检测」。")
    }

    private fun buildInactiveHint(framework: String): String = buildString {
        append("$framework 已安装，但模块还没生效。\n\n")
        append("检查步骤：\n")
        append("1. 打开 $framework 管理器\n")
        append("2. 进入「模块」页，勾选启用「算法助手公益版」\n")
        append("3. 进入「作用域」，勾选要分析的目标应用\n")
        append("   （只勾应用；system 一般不用勾，除非你要 Hook 系统框架）\n")
        append("4. 强行停止目标应用，重新打开\n\n")
        append("完成后回到这里点「重新检测」。")
    }

    private fun buildScopeHint(framework: String): String = buildString {
        append("模块已启用，但作用域是空的 —— 没有应用会被 Hook。\n\n")
        append("操作步骤：\n")
        append("1. 打开 $framework 管理器 → 模块 → 算法助手公益版\n")
        append("2. 点「作用域」，勾选你要分析的应用\n")
        append("   （追剧鸭、Via浏览器之类）\n")
        append("3. 强行停止目标应用，重新打开\n\n")
        append("完成后回到这里点「重新检测」。")
    }

    private fun buildUnknownHint(framework: String, rooted: Boolean): String = buildString {
        append("$framework 已安装，但无法自动确认模块状态")
        if (!rooted) append("（当前没有 root 权限，读不到框架配置）")
        append("。\n\n")
        append("请手动确认：\n")
        append("1. 打开 $framework 管理器 → 模块\n")
        append("2. 确认「算法助手公益版」是勾选状态\n")
        append("3. 点「作用域」，勾选要分析的应用\n")
        append("4. 强行停止目标应用，重新打开\n\n")
        append("如果模块确实已启用，Hook 功能就是正常的 ——\n")
        append("这里显示「无法确认」只是检测受限，不代表功能不可用。")
    }
}
