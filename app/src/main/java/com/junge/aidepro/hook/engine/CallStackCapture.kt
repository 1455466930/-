package com.junge.aidepro.hook.engine

/**
 * 调用堆栈采集。
 *
 * 【为什么需要】
 * 逆向时看到「某处调用了 AES/CBC」还不够，要知道**是谁调的** ——
 * 是网络层算签名、还是本地加密存储、还是校验逻辑。
 * 只有堆栈才能定位到业务代码的具体位置。
 *
 * 【为什么要过滤】
 * 直接 `Thread.currentThread().stackTrace` 会带上一大堆系统帧：
 *   java.lang.Thread.getStackTrace
 *   com.junge.aidepro.hook...（我们自己的 Hook 帧）
 *   de.robv.android.xposed...（Xposed 框架帧）
 *   java.lang.reflect.Method.invoke（反射调用帧）
 * 这些对用户毫无意义，还会把真正的业务帧挤出显示范围。
 */
object CallStackCapture {

    /** 最多保留多少帧（超过的截断，避免日志过大）。 */
    private const val MAX_FRAMES = 24

    /** 需要过滤掉的包名前缀。 */
    private val FILTER_PREFIXES = arrayOf(
        "java.lang.Thread",
        "java.lang.reflect.",
        "de.robv.android.xposed.",
        "com.junge.aidepro.hook.",
        "com.junge.aidepro.ipc.",
        "android.os.",
        "com.android.internal.os.",
        "dalvik.system.",
        "com.android.org.conscrypt.",
    )

    /**
     * 采集当前调用堆栈（已过滤系统帧）。
     *
     * @param skipExtra 额外跳过的栈帧数（默认 0；调用方一般不用改）
     * @return 多行文本，每行一帧；采集失败返回空串
     */
    fun capture(skipExtra: Int = 0): String = runCatching {
        val stack = Thread.currentThread().stackTrace ?: return ""
        val sb = StringBuilder()
        var written = 0
        var skippedSys = 0

        for (frame in stack) {
            val cls = frame.className ?: continue

            // 过滤系统/框架/自身帧
            if (FILTER_PREFIXES.any { cls.startsWith(it) }) {
                skippedSys++
                continue
            }

            // 跳过头几帧（capture 自己的调用栈）
            if (skippedSys == 0 && written == 0 && skipExtra > 0) continue

            if (written >= MAX_FRAMES) {
                sb.append("\n  ... (更多帧已省略)")
                break
            }

            if (written > 0) sb.append('\n')
            sb.append("  at ").append(cls)
                .append('.').append(frame.methodName)
                .append('(').append(frame.fileName ?: "Unknown")
                .append(':').append(frame.lineNumber).append(')')
            written++
        }
        sb.toString()
    }.getOrDefault("")

    /**
     * 采集「业务侧」堆栈 —— 只要调用方，不含加密库自身的帧。
     *
     * 场景：MessageDigest.digest() 的堆栈里前几帧一定是
     * `java.security.MessageDigest.digest` 自己，用户想看的是「谁调的 digest」。
     *
     * @param excludeClass 要排除的类名前缀（如 "java.security.MessageDigest"）
     */
    fun captureCaller(excludeClass: String): String = runCatching {
        val stack = Thread.currentThread().stackTrace ?: return ""
        val sb = StringBuilder()
        var written = 0
        var passedSelf = false

        for (frame in stack) {
            val cls = frame.className ?: continue

            // 先跳过加密库自身及其调用链
            if (!passedSelf) {
                if (cls.startsWith(excludeClass)) continue
                // 跳过 javax.crypto / java.security 的包装层
                if (cls.startsWith("javax.crypto.") || cls.startsWith("java.security.")) continue
                passedSelf = true
            }

            if (FILTER_PREFIXES.any { cls.startsWith(it) }) continue
            if (written >= MAX_FRAMES) {
                sb.append("\n  ... (更多帧已省略)")
                break
            }

            if (written > 0) sb.append('\n')
            sb.append("  at ").append(cls)
                .append('.').append(frame.methodName)
                .append('(').append(frame.fileName ?: "Unknown")
                .append(':').append(frame.lineNumber).append(')')
            written++
        }
        sb.toString()
    }.getOrDefault("")
}
