package com.junge.aidepro.hook.engine

import com.junge.aidepro.hook.config.AppConfig
import com.junge.aidepro.hook.config.HookRule
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.json.JSONObject

/**
 * 自定义 Hook 规则安装器。
 *
 * 对齐原版「添加动态类 / Hook 列表」功能，但修掉了原版的两个问题：
 *  1. 原版把运行时抓到的参数值(argsValues)存在 HookData 里一起持久化，
 *     配置和运行时数据混在一起，导致配置膨胀且无法复用同一条规则。
 *  2. 原版 intercept 后直接把 results 字符串塞回去，类型不匹配就抛异常。
 *     这里按声明类型做转换，失败则放弃替换而不影响业务。
 */
object CustomHookInstaller {

    fun install(ctx: HookContext, config: AppConfig) {
        for (rule in config.hooks) {
            if (!rule.enable) continue
            runCatching { installOne(ctx, rule) }
                .onFailure {
                    XposedBridge.log("[CustomHook] 规则安装失败 ${rule.className}#${rule.methodName}: ${it.message}")
                }
        }
    }

    private fun installOne(ctx: HookContext, rule: HookRule) {
        val cls = XposedHelpers.findClassIfExists(rule.className, ctx.classLoader)
            ?: runCatching { Class.forName(rule.className, false, ctx.classLoader) }.getOrNull()
            ?: return

        val callback = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                reportInvocation(ctx, rule, param, isAfter = false)
                if (rule.intercept && rule.replaceResult.isNotBlank()) {
                    coerceAndSet(param, rule.replaceResult)
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if (!rule.intercept) reportInvocation(ctx, rule, param, isAfter = true)
            }
        }

        if (rule.isConstructor) {
            XposedBridge.hookAllConstructors(cls, callback)
        } else if (rule.parameterSign.isBlank()) {
            XposedBridge.hookAllMethods(cls, rule.methodName, callback)
        } else {
            val paramTypes = parseParamSign(rule.parameterSign, ctx.classLoader)
            if (paramTypes == null) {
                XposedBridge.hookAllMethods(cls, rule.methodName, callback)
            } else {
                XposedHelpers.findAndHookMethod(cls, rule.methodName, *paramTypes, callback)
            }
        }
        XposedBridge.log("[CustomHook] 已安装 ${rule.className}#${rule.methodName}")
    }

    private fun reportInvocation(
        ctx: HookContext,
        rule: HookRule,
        param: XC_MethodHook.MethodHookParam,
        isAfter: Boolean,
    ) {
        val args = param.args?.joinToString(", ") { describe(it) } ?: ""
        val result = if (isAfter) describe(param.result) else ""
        val text = if (isAfter) "返回: $result" else "参数: ($args)"
        ctx.reporter.report(
            logName = rule.description.ifBlank { "${rule.className.substringAfterLast('.')}.${rule.methodName}" },
            className = rule.className,
            objName = rule.methodName,
            payload = args.toByteArray(),
            results = if (isAfter && param.result != null) listOf(result.toByteArray()) else emptyList(),
            sw = com.junge.aidepro.hook.config.HookSwitch.DIALOG, // 归入通用自定义分组
            callStack = text,
        )
    }

    private fun describe(v: Any?): String = when (v) {
        null -> "null"
        is ByteArray -> "[${v.size}B] ${v.toHexPreview()}"
        is String -> "\"$v\""
        is Array<*> -> v.joinToString(prefix = "[", postfix = "]") { describe(it) }
        is IntArray -> v.joinToString(prefix = "[", postfix = "]")
        else -> v.toString()
    }

    private fun ByteArray.toHexPreview(): String {
        val n = minOf(size, 32)
        val sb = StringBuilder(n * 2)
        for (i in 0 until n) {
            val x = this[i].toInt() and 0xFF
            sb.append("0123456789abcdef"[x ushr 4]).append("0123456789abcdef"[x and 0x0F])
        }
        if (size > n) sb.append("...")
        return sb.toString()
    }

    /**
     * 把替换值按目标参数类型转换。
     * 不匹配时直接放弃，绝不因配置错误影响目标应用。
     */
    private fun coerceAndSet(param: XC_MethodHook.MethodHookParam, literal: String) {
        runCatching {
            val method = param.method as? java.lang.reflect.Method ?: return
            val rt = method.returnType
            val value: Any? = when (rt) {
                java.lang.Void.TYPE -> null
                java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> literal.toBoolean()
                java.lang.Integer.TYPE, java.lang.Integer::class.java -> literal.toInt()
                java.lang.Long.TYPE, java.lang.Long::class.java -> literal.toLong()
                java.lang.Double.TYPE, java.lang.Double::class.java -> literal.toDouble()
                java.lang.Float.TYPE, java.lang.Float::class.java -> literal.toFloat()
                String::class.java -> literal
                else -> parseJsonLiteral(literal, rt)
            }
            param.result = value
        }.onFailure {
            XposedBridge.log("[CustomHook] 返回值替换失败，已跳过: ${it.message}")
        }
    }

    private fun parseJsonLiteral(literal: String, rt: Class<*>): Any? {
        val trimmed = literal.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null
        return runCatching { JSONObject(trimmed) }.getOrNull()
    }

    /** 解析 "Ljava.lang.String;I" 这类参数签名。 */
    private fun parseParamSign(sign: String, cl: ClassLoader): Array<Class<*>?>? = runCatching {
        val out = ArrayList<Class<*>?>()
        var i = 0
        while (i < sign.length) {
            when (val c = sign[i]) {
                'L' -> {
                    val end = sign.indexOf(';', i)
                    if (end < 0) return null
                    val name = sign.substring(i + 1, end).replace('/', '.')
                    out.add(Class.forName(name, false, cl))
                    i = end + 1
                }
                'I' -> { out.add(Int::class.javaPrimitiveType); i++ }
                'J' -> { out.add(Long::class.javaPrimitiveType); i++ }
                'Z' -> { out.add(Boolean::class.javaPrimitiveType); i++ }
                'D' -> { out.add(Double::class.javaPrimitiveType); i++ }
                'F' -> { out.add(Float::class.javaPrimitiveType); i++ }
                'B' -> { out.add(Byte::class.javaPrimitiveType); i++ }
                'S' -> { out.add(Short::class.javaPrimitiveType); i++ }
                'C' -> { out.add(Char::class.javaPrimitiveType); i++ }
                '[' -> {
                    var j = i + 1
                    while (j < sign.length && sign[j] == '[') j++
                    val comp = when (sign[j]) {
                        'L' -> {
                            val end = sign.indexOf(';', j)
                            Class.forName(sign.substring(j + 1, end).replace('/', '.'), false, cl)
                        }
                        'I' -> Int::class.javaPrimitiveType
                        'B' -> Byte::class.javaPrimitiveType
                        'Z' -> Boolean::class.javaPrimitiveType
                        else -> return null
                    }
                    out.add(java.lang.reflect.Array.newInstance(comp, 0).javaClass)
                    i = if (sign[j] == 'L') sign.indexOf(';', j) + 1 else j + 1
                }
                else -> return null
            }
        }
        out.toTypedArray()
    }.getOrNull()
}
