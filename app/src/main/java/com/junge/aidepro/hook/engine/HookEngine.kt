package com.junge.aidepro.hook.engine

import android.app.Application
import android.content.Context
import com.junge.aidepro.hook.config.AppConfig
import com.junge.aidepro.hook.config.HookConfigCache
import com.junge.aidepro.hook.ipc.HostConnector
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Hook 引擎。
 *
 * 【安装时机】
 * 必须在拿到 Application 实例之后安装。原因：
 *  - XSharedPreferences 需要模块的 sp 文件已存在且可读，attachBaseContext 阶段
 *    目标进程的 Context 还没完全初始化，部分 ROM 上读取会失败；
 *  - 部分模块（如 Activity 生命周期）需要 Application 已创建。
 * 因此采用：hook attachBaseContext 拿到 Context → hook Application.onCreate
 * 在其 afterHookedMethod 里安装。这样两阶段都能覆盖，且拿到的是最完整的 Context。
 *
 * 【异常隔离】
 * 任何模块安装失败都不能让目标应用崩溃，全部包裹在 try/catch 里。
 */
class HookEngine : IXposedHookLoadPackage {

    private val installedPackages = HashSet<String>()
    private val lock = Any()

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!lpparam.isFirstApplication) return

        // 不 Hook 自己
        if (lpparam.packageName == HOST_PACKAGE) return

        XposedBridge.log(
            "[$TAG] load package=${lpparam.packageName} process=${lpparam.processName} " +
                "api=${frameworkApiVersion()}"
        )

        hookApplicationLifecycle(lpparam)
    }

    private var context: Context? = null
    private var contextPackage: String? = null

    private fun hookApplicationLifecycle(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 阶段一：拿到 Context（此时不安装模块，只记录）
        val appCls = XposedHelpers.findClassIfExists("android.app.Application", lpparam.classLoader)
            ?: run {
                XposedBridge.log("[$TAG] 找不到 android.app.Application，放弃")
                return
            }

        XposedBridge.hookAllMethods(appCls, "attachBaseContext", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val ctx = param.thisObject as? Context ?: return
                if (context == null || contextPackage != lpparam.packageName) {
                    context = ctx
                    contextPackage = lpparam.packageName
                }
            }
        })

        // 阶段二：Application.onCreate 之后安装（此时 Context 与 sp 均可用）
        XposedBridge.hookAllMethods(appCls, "onCreate", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val app = param.thisObject as? Application ?: return
                installIfNeeded(app, lpparam)
            }
        })

        // 兜底：某些 ROM 上 onCreate 可能被跳过或时序异常，用 LoadedApk.makeApplication 补一次
        runCatching {
            val loadedApk = XposedHelpers.findClassIfExists("android.app.LoadedApk", lpparam.classLoader)
            if (loadedApk != null) {
                XposedBridge.hookAllMethods(loadedApk, "makeApplication", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.result as? Application ?: return
                        installIfNeeded(app, lpparam)
                    }
                })
            }
        }
    }

    private fun installIfNeeded(ctx: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        synchronized(lock) {
            if (pkg in installedPackages) return
        }

        try {
            // 先建立与宿主的 Binder 连接（通过 ContentProvider 握手）。
            val connected = runCatching { HostConnector.connect(ctx, force = true) }.getOrDefault(false)
            XposedBridge.log("[$TAG] 连接宿主: $connected")

            val config = HookConfigCache.get(ctx, pkg)
            XposedBridge.log(
                "[$TAG] 读取配置 pkg=$pkg enabled=${config.enabled} " +
                    "switches=${config.switches.size} hooks=${config.hooks.size} " +
                    "connected=$connected ${HookConfigCache.describe()}"
            )

            if (!config.enabled) {
                // 关键：宿主没启动时连不上，此时**不能**标记为已处理，
                // 否则目标应用整个生命周期都不会再尝试安装 Hook。
                // 这里安排一个延迟重试：等宿主起来后自动补上。
                if (!connected) {
                    scheduleRetry(ctx, lpparam, attempt = 1)
                } else {
                    synchronized(lock) { installedPackages.add(pkg) }
                    XposedBridge.log("[$TAG] $pkg 未启用 Hook，跳过安装")
                }
                return
            }

            synchronized(lock) { installedPackages.add(pkg) }
            doInstall(ctx, lpparam, config, connected)
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] 安装失败: ${t.javaClass.simpleName}: ${t.message}")
            XposedBridge.log(t)
        }
    }

    /**
     * 延迟重试安装。
     *
     * 场景：目标应用先于宿主 APP 启动（很常见），此时 Provider 未就绪，
     * 握手失败。等宿主被拉起后再补装 Hook，用户无需重启目标应用。
     *
     * 重试策略：3s / 8s / 20s / 45s 四次，之后放弃（避免无限占用线程）。
     */
    private fun scheduleRetry(ctx: Context, lpparam: XC_LoadPackage.LoadPackageParam, attempt: Int) {
        if (attempt > RETRY_DELAYS_MS.size) {
            XposedBridge.log("[$TAG] ${lpparam.packageName} 重试 $attempt 次仍无法连接宿主，放弃")
            return
        }
        val delay = RETRY_DELAYS_MS[attempt - 1]
        XposedBridge.log("[$TAG] ${lpparam.packageName} 将在 ${delay}ms 后第 $attempt 次重试连接")

        Thread({
            try {
                Thread.sleep(delay)
                val pkg = lpparam.packageName
                synchronized(lock) { if (pkg in installedPackages) return@Thread }

                val connected = runCatching { HostConnector.connect(ctx, force = true) }.getOrDefault(false)
                if (!connected) {
                    scheduleRetry(ctx, lpparam, attempt + 1)
                    return@Thread
                }

                val config = HookConfigCache.get(ctx, pkg)
                XposedBridge.log("[$TAG] 重试成功 pkg=$pkg enabled=${config.enabled} connected=$connected")
                if (config.enabled) {
                    synchronized(lock) { installedPackages.add(pkg) }
                    doInstall(ctx, lpparam, config, connected)
                } else {
                    synchronized(lock) { installedPackages.add(pkg) }
                }
            } catch (t: Throwable) {
                XposedBridge.log("[$TAG] 重试异常: ${t.message}")
            }
        }, "aidepro-retry-$attempt").apply { isDaemon = true }.start()
    }

    /** 实际安装各模块。 */
    private fun doInstall(
        ctx: Context,
        lpparam: XC_LoadPackage.LoadPackageParam,
        config: AppConfig,
        connected: Boolean,
    ) {
        val reporter = IpcLogReporter(ctx, lpparam.packageName)
        val hookCtx = HookContext(
            context = ctx,
            packageName = lpparam.packageName,
            processName = lpparam.processName,
            classLoader = lpparam.classLoader,
            loadPackageParam = lpparam,
            config = config,
            reporter = reporter,
        )

        var installed = 0
        for (module in ModuleRegistry.all()) {
            if (!hookCtx.enabled(module.switch)) continue
            safeInstall(module.switch.key) {
                module.installEarly(hookCtx)
                module.install(hookCtx)
            }
            installed++
        }

        if (config.hooks.isNotEmpty()) {
            safeInstall("custom-hooks") { CustomHookInstaller.install(hookCtx, config) }
        }

        safeInstall("selftest") {
            reporter.report(
                logName = "自检：Hook 已装载",
                className = "com.junge.aidepro.hook.engine.HookEngine",
                objName = "install",
                payload = "模块数=$installed 进程=${lpparam.processName}".toByteArray(),
                results = listOf("通道=ContentProvider+Binder".toByteArray()),
                sw = com.junge.aidepro.hook.config.HookSwitch.DIGEST,
            )
        }

        XposedBridge.log("[$TAG] ${lpparam.packageName} Hook 安装完成，模块数=$installed")
    }

    private inline fun safeInstall(tag: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] 模块 $tag 安装异常: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** 读取框架 API 版本，仅用于诊断日志。 */
    private fun frameworkApiVersion(): Int = runCatching {
        com.junge.aidepro.hook.runtime.XpRuntimeState.frameworkApiVersion
    }.getOrDefault(-1)

    companion object {
        const val TAG = "AlgorithmAidePro2"
        const val HOST_PACKAGE = "com.junge.aidepro"

        /**
         * 连接宿主失败时的重试延迟（毫秒）。
         *
         * 目标应用常常先于宿主启动，首次握手必然失败。
         * 宿主一般会在用户点开 APP 后几秒内就绪，因此前几次间隔较短。
         */
        private val RETRY_DELAYS_MS = longArrayOf(3_000, 8_000, 20_000, 45_000)
    }
}
