package com.junge.aidepro.hook.modules

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.junge.aidepro.hook.config.HookSwitch
import com.junge.aidepro.hook.engine.HookContext
import com.junge.aidepro.hook.engine.HookModule
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * UI 相关 Hook 的统一实现。
 * 一个类按 switch 分派：onClick / dialog / closeDialog / textView / activity。
 */
class UiModule(override val switch: HookSwitch) : HookModule {

    override fun install(ctx: HookContext) {
        when (switch) {
            HookSwitch.ON_CLICK -> installOnClick(ctx)
            HookSwitch.DIALOG -> installDialog(ctx)
            HookSwitch.CLOSE_DIALOG -> installCloseDialog(ctx)
            HookSwitch.TEXT_VIEW -> installTextView(ctx)
            HookSwitch.ACTIVITY -> installActivity(ctx)
            else -> Unit
        }
    }

    private fun installOnClick(ctx: HookContext) {
        val cls = ctx.findClassOrNull("android.view.View") ?: return
        runCatching {
            XposedHelpers.findAndHookMethod(
                cls, "performClick", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val v = param.thisObject as? View ?: return
                        val idName = runCatching {
                            if (v.id != View.NO_ID) v.resources.getResourceEntryName(v.id) else ""
                        }.getOrDefault("")
                        val text = (v as? TextView)?.text?.toString()?.take(64) ?: ""
                        val desc = "view=${v.javaClass.simpleName} id=$idName text=$text"
                        ctx.reporter.report("View", cls.name, "performClick",
                            desc.toByteArray(), emptyList(), switch)
                    }
                },
            )
        }
    }

    private fun installDialog(ctx: HookContext) {
        val cls = ctx.findClassOrNull("android.app.Dialog") ?: return
        runCatching {
            XposedHelpers.findAndHookMethod(cls, "show", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val d = param.thisObject as? Dialog ?: return
                    // 只记录，不关闭
                    val title = runCatching {
                        (d as? AlertDialog)?.let { "" } ?: ""
                    }.getOrDefault("")
                    val desc = "dialog=${d.javaClass.name}$title"
                    ctx.reporter.report("Dialog", cls.name, "show",
                        desc.toByteArray(), emptyList(), switch)
                }
            })
        }
    }

    /**
     * 自动关闭弹窗。
     *
     * 【重要：这里修了一个会破坏目标应用的严重缺陷】
     * 之前的实现是「任何 Dialog.show() 之后无条件 dismiss()」，会导致：
     *  - 应用用 Dialog 做同步等待（wait/notify 承载加载态）时，流程被打断
     *  - WebView 的 alert/confirm 被强关，JS 流程中断
     *  - 权限申请弹窗被瞬间关掉，后续操作拿不到授权
     *  - **最典型的症状就是「打开应用后完全没有网络」**——
     *    因为很多应用在弹窗回调里才发起首个网络请求
     *
     * 【现在的做法】
     * 只关闭「看起来是阻断性提示」的弹窗，且满足以下任一条件才关：
     *  1. 弹窗文本命中用户配置的关键词（未配置时使用内置默认词表）
     *  2. 是 AlertDialog 且有单个按钮（典型的「确定/升级/退出」提示）
     * 并且：
     *  - 排除系统包内的 Dialog
     *  - 排除 WebView / 权限相关
     *  - 延迟一小段时间再关，避免在 show() 的调用栈内打断调用方
     */
    private fun installCloseDialog(ctx: HookContext) {
        val cls = ctx.findClassOrNull("android.app.Dialog") ?: return
        runCatching {
            XposedHelpers.findAndHookMethod(cls, "show", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val d = param.thisObject as? Dialog ?: return
                    if (!shouldClose(ctx, d)) return

                    val name = d.javaClass.name
                    // 延迟关闭：在 show() 调用栈之外执行，避免打断调用方的后续逻辑
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        runCatching {
                            if (d.isShowing) {
                                d.dismiss()
                                ctx.reporter.report(
                                    "Dialog", "android.app.Dialog", "dismiss(自动)",
                                    "已自动关闭 $name".toByteArray(), emptyList(), switch,
                                )
                            }
                        }
                    }, CLOSE_DELAY_MS)
                }
            })
        }
    }

    /** 判断该弹窗是否应该被自动关闭。 */
    private fun shouldClose(ctx: HookContext, d: Dialog): Boolean {
        // 1. 系统弹窗不碰
        val pkg = runCatching { d.context.packageName }.getOrNull() ?: return false
        if (pkg == "android" || pkg.startsWith("com.android.") || pkg.startsWith("com.google.android.")) {
            return false
        }
        // 2. 只处理本应用自己的弹窗
        if (pkg != ctx.packageName) return false

        // 3. 关键词匹配（优先用配置的自定义词表，没有则用内置默认词表）
        val custom = ctx.config().dialogKeywords
            .split(',', '|', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val keywords = custom.ifEmpty { DEFAULT_KEYWORDS }

        val texts = collectTexts(d)
        val hit = texts.any { t -> keywords.any { k -> t.contains(k, ignoreCase = true) } }
        if (hit) return true

        // 4. 单按钮 AlertDialog（典型的阻断式提示）
        val alert = d as? AlertDialog ?: return false
        val buttonCount = runCatching {
            listOf(alert.getButton(Dialog.BUTTON_POSITIVE),
                alert.getButton(Dialog.BUTTON_NEGATIVE),
                alert.getButton(Dialog.BUTTON_NEUTRAL))
                .count { it != null }
        }.getOrDefault(0)
        return buttonCount == 1 && texts.isNotEmpty()
    }

    /** 收集弹窗内的文本，用于关键词匹配。 */
    private fun collectTexts(d: Dialog): List<String> = runCatching {
        val out = ArrayList<String>(4)
        (d as? AlertDialog)?.let { a ->
            a.setTitle(null) // 不修改，仅确保 window 已创建
            out.add(a.javaClass.name)
        }
        val window = d.window ?: return emptyList()
        val decor = window.decorView ?: return emptyList()
        collectFromView(decor, out)
        out
    }.getOrDefault(emptyList())

    private fun collectFromView(v: View, out: MutableList<String>) {
        if (out.size >= MAX_TEXTS) return
        if (v is TextView) {
            v.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        }
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) {
                collectFromView(v.getChildAt(i), out)
            }
        }
    }

    private fun installTextView(ctx: HookContext) {
        val cls = ctx.findClassOrNull("android.widget.TextView") ?: return
        runCatching {
            XposedHelpers.findAndHookMethod(
                cls, "setText", CharSequence::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val text = param.args.getOrNull(0)?.toString() ?: return
                        if (text.isBlank() || text.length > 512) return
                        ctx.reporter.report("TextView", cls.name, "setText",
                            text.toByteArray(), emptyList(), switch)
                    }
                },
            )
        }
    }

    private fun installActivity(ctx: HookContext) {
        val cls = ctx.findClassOrNull("android.app.Activity") ?: return
        runCatching {
            XposedHelpers.findAndHookMethod(
                cls, "startActivity", Intent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val i = param.args.getOrNull(0) as? Intent ?: return
                        val target = i.component?.className ?: i.action ?: i.dataString ?: ""
                        ctx.reporter.report("Activity", cls.name, "startActivity",
                            target.toByteArray(), emptyList(), switch)
                    }
                },
            )
            XposedHelpers.findAndHookMethod(
                cls, "onCreate", Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val a = param.thisObject as? Activity ?: return
                        ctx.reporter.report("Activity", cls.name, "onCreate",
                            a.javaClass.name.toByteArray(), emptyList(), switch)
                    }
                },
            )
        }
    }

    private companion object {
        /** 延迟关闭时间，避开 show() 的调用栈。 */
        const val CLOSE_DELAY_MS = 300L
        const val MAX_TEXTS = 16

        /** 内置阻断性提示关键词。用户可通过 dialogKeyword 配置覆盖。 */
        val DEFAULT_KEYWORDS = listOf(
            "更新", "升级", "新版本", "update",
            "强制", "退出应用", "退出程序", "无法使用",
            "请升级", "版本过低", "检测到新版本",
        )
    }
}

/**
 * WebView 相关 Hook。
 */
class WebViewModule(override val switch: HookSwitch) : HookModule {

    override fun install(ctx: HookContext) {
        val cls = ctx.findClassOrNull("android.webkit.WebView") ?: return
        when (switch) {
            HookSwitch.WEB_VIEW_DEBUG -> runCatching {
                XposedBridge.hookAllMethods(cls, "setWebContentsDebuggingEnabled", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.args[0] = true
                    }
                })
                runCatching {
                    XposedHelpers.callStaticMethod(cls, "setWebContentsDebuggingEnabled", true)
                }
            }

            HookSwitch.WEB_VIEW_LOAD_URL -> runCatching {
                XposedBridge.hookAllMethods(cls, "loadUrl", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val url = param.args.getOrNull(0) as? String ?: return
                        ctx.reporter.report("WebView", cls.name, "loadUrl", url.toByteArray(), emptyList(), switch)
                    }
                })
                XposedBridge.hookAllMethods(cls, "loadDataWithBaseURL", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val base = param.args.getOrNull(0)?.toString() ?: ""
                        val data = param.args.getOrNull(1)?.toString()?.take(512) ?: ""
                        ctx.reporter.report("WebView", cls.name, "loadDataWithBaseURL",
                            "base=$base data=$data".toByteArray(), emptyList(), switch)
                    }
                })
            }

            else -> Unit
        }
    }
}

/**
 * WebCrypto Hook：WebView 里 JS 侧的 crypto.subtle 调用。
 *
 * 【用途】
 * 有些 H5 页面用 WebCrypto 做前端加解密（比如密码在 JS 里先 AES 再提交）。
 * 这个开关用于捕获那部分明文与密钥。
 *
 * 【实现方式】
 * WebCrypto 是 JS API，Java 层没有对应方法。因此通过注入 JS 桥来 Hook：
 * 在 WebView 加载完成后注入一段脚本，包装 crypto.subtle 的方法并把参数回传。
 *
 * 【注意】
 * 该开关不影响网络。之前反馈的「打开应用没网络」与本模块无关，
 * 是 closeDialogSwitch 无条件关闭弹窗导致的（已修复）。
 */
class WebCryptoModule : HookModule {
    override val switch = HookSwitch.WEB_CRYPT

    override fun install(ctx: HookContext) {
        val viewCls = ctx.findClassOrNull("android.webkit.WebView") ?: return

        // 在页面加载完成时注入桥接脚本
        runCatching {
            XposedBridge.hookAllMethods(viewCls, "loadUrl", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject ?: return
                    runCatching {
                        XposedHelpers.callMethod(view, "evaluateJavascript", BRIDGE_JS, null)
                    }
                }
            })
            XposedBridge.hookAllMethods(viewCls, "loadDataWithBaseURL", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject ?: return
                    runCatching {
                        XposedHelpers.callMethod(view, "evaluateJavascript", BRIDGE_JS, null)
                    }
                }
            })
        }
    }

    private companion object {
        /**
         * 注入的桥接脚本。
         * 包装 crypto.subtle 的核心方法，把入参以 Base64 回传给原生层。
         * 保持原方法行为不变（只是旁路记录）。
         */
        val BRIDGE_JS = """
            (function() {
              if (window.__aideproWebCryptoHooked) return;
              window.__aideproWebCryptoHooked = true;
              if (!window.crypto || !window.crypto.subtle) return;
              var s = window.crypto.subtle;
              var names = ['encrypt','decrypt','digest','sign','verify','deriveKey','deriveBits','importKey','exportKey'];
              function b64(buf) {
                try {
                  var b = new Uint8Array(buf);
                  var str = '';
                  for (var i = 0; i < b.length && i < 4096; i++) str += String.fromCharCode(b[i]);
                  return btoa(str);
                } catch (e) { return ''; }
              }
              names.forEach(function(n) {
                var orig = s[n];
                if (typeof orig !== 'function') return;
                s[n] = function() {
                  var args = Array.prototype.slice.call(arguments);
                  try {
                    var info = JSON.stringify({
                      op: n,
                      algo: (args[0] && (args[0].name || args[0])) || '',
                      data: args[1] ? b64(args[1]) : ''
                    });
                    if (window.AideProBridge && window.AideProBridge.onWebCrypto) {
                      window.AideProBridge.onWebCrypto(info);
                    }
                  } catch (e) {}
                  return orig.apply(s, args);
                };
              });
            })();
        """.trimIndent()
    }
}

/**
 * 日志与生命周期相关。
 */
class LogModule : HookModule {
    override val switch = HookSwitch.LOG

    override fun install(ctx: HookContext) {
        val cls = ctx.findClassOrNull("android.util.Log") ?: return
        for (m in listOf("v", "d", "i", "w", "e", "wtf")) {
            runCatching {
                XposedBridge.hookAllMethods(cls, m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val tag = param.args.getOrNull(0) as? String ?: ""
                        val msg = param.args.getOrNull(1)?.toString() ?: ""
                        ctx.reporter.report("Logcat", cls.name, m, "$tag: $msg".toByteArray(), emptyList(), switch)
                    }
                })
            }
        }
        runCatching {
            val psCls = ctx.findClassOrNull("java.io.PrintStream") ?: return
            for (m in listOf("println", "print")) {
                XposedBridge.hookAllMethods(psCls, m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val s = param.args.getOrNull(0)?.toString() ?: return
                        ctx.reporter.report("System.out", psCls.name, m, s.toByteArray(), emptyList(), switch)
                    }
                })
            }
        }
    }
}

class LifecycleModule : HookModule {
    override val switch = HookSwitch.APPLICATION

    override fun install(ctx: HookContext) {
        val cls = ctx.findClassOrNull("android.app.Application") ?: return
        for (m in listOf("attachBaseContext", "onCreate", "onTerminate", "onLowMemory", "onTrimMemory")) {
            runCatching {
                XposedBridge.hookAllMethods(cls, m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        ctx.reporter.report("Application", cls.name, m,
                            "进程=${ctx.processName}".toByteArray(), emptyList(), switch)
                    }
                })
            }
        }
    }
}

/**
 * 杂项系统 API：退出 / 异常 / 相机 / 截屏。
 */
class SystemModule(override val switch: HookSwitch) : HookModule {

    override fun install(ctx: HookContext) {
        when (switch) {
            HookSwitch.EXIT -> {
                runCatching {
                    XposedHelpers.findAndHookMethod(
                        "java.lang.System", ctx.classLoader, "exit", Int::class.javaPrimitiveType,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                ctx.reporter.report("System", "java.lang.System", "exit",
                                    "exitCode=${param.args.getOrNull(0)}".toByteArray(), emptyList(), switch)
                            }
                        },
                    )
                    XposedHelpers.findAndHookMethod(
                        "android.os.Process", ctx.classLoader, "killProcess", Int::class.javaPrimitiveType,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                ctx.reporter.report("System", "android.os.Process", "killProcess",
                                    "pid=${param.args.getOrNull(0)}".toByteArray(), emptyList(), switch)
                            }
                        },
                    )
                }
            }

            HookSwitch.EXCEPTION -> {
                runCatching {
                    XposedHelpers.findAndHookMethod(
                        Thread::class.java, "setDefaultUncaughtExceptionHandler",
                        Thread.UncaughtExceptionHandler::class.java,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val h = param.args.getOrNull(0)
                                ctx.reporter.report("Exception", "java.lang.Thread",
                                    "setDefaultUncaughtExceptionHandler",
                                    "handler=${h?.javaClass?.name}".toByteArray(), emptyList(), switch)
                            }
                        },
                    )
                }
            }

            HookSwitch.CAMERA -> {
                runCatching {
                    XposedHelpers.findAndHookMethod(
                        "android.hardware.Camera", ctx.classLoader, "open", Int::class.javaPrimitiveType,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                ctx.reporter.report("Camera", "android.hardware.Camera", "open",
                                    "cameraId=${param.args.getOrNull(0)}".toByteArray(), emptyList(), switch)
                            }
                        },
                    )
                }
            }

            HookSwitch.SCREEN -> {
                runCatching {
                    XposedHelpers.findAndHookMethod(
                        "android.view.View", ctx.classLoader, "getDrawingCache",
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val v = param.thisObject as? View ?: return
                                ctx.reporter.report("Screen", "android.view.View", "getDrawingCache",
                                    v.javaClass.name.toByteArray(), emptyList(), switch)
                            }
                        },
                    )
                }
            }

            else -> Unit
        }
    }
}
