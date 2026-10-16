package com.sfzsgybb.ai.frida

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Frida 脚本执行引擎。
 *
 * 【方案】
 * 设备上已有 frida-inject 二进制（17.17.0，与 frida-server 17.23.1 匹配）。
 * 这里不搞原生集成（JNI 太重），直接：
 *  1. 把 JS 脚本写到 APP 私有目录
 *  2. 用 root 跑 `frida-inject -n <pkg> -s <script.js>`（attach 到目标进程）
 *  3. 脚本的 console.log 输出重定向到日志文件
 *  4. 后台轮询读日志文件，投喂给 InMemoryFridaLogProvider
 *
 * 【为什么用 frida-inject 而不是 frida-server API】
 * frida-inject 是单次注入，进程退出即结束，脚本输出直接 stdout，
 * 不用维护长连接，也不依赖 frida-server 常驻 —— 更简单可靠。
 * 代价是每次注入有 spawn 开销（~1秒），对交互式脚本足够。
 */
class FridaEngine(private val context: Context) {

    companion object {
        private const val TAG = "FridaEngine"
    }

    data class ScriptRun(
        val packageName: String,
        val scriptName: String,
        val pid: Int,
        val process: Process?,
        val logFile: File,
        val startedAt: Long,
    )

    /** 正在运行的注入（按包名）。 */
    private val runs = ConcurrentHashMap<String, ScriptRun>()

    /** 脚本目录。 */
    private fun scriptDir(): File = File(context.filesDir, "frida_scripts").apply { mkdirs() }

    /** 日志目录。 */
    private fun logDir(): File = File(context.filesDir, "frida_logs").apply { mkdirs() }

    /**
     * 注入脚本到目标进程。
     *
     * 【二进制来源】先调 FridaBinaryManager.ensureAvailable：
     * arm64 从 assets 释放（零联网），其他 ABI 下载。
     * 用户自己装过的优先，不覆盖。
     *
     * @param packageName 目标应用
     * @param scriptName  脚本名（用于日志标识）
     * @param jsCode      JavaScript 源码
     * @return 结果描述
     */
    suspend fun inject(packageName: String, scriptName: String, jsCode: String): Result {
        // 已有运行中的注入则先停
        stop(packageName)

        // 0) 确保 frida-inject 可用
        val bin = FridaBinaryManager.ensureAvailable(context)
        if (!bin.available) {
            return Result(false, "frida-inject 不可用：${bin.message}")
        }
        Log.i(TAG, "使用 frida-inject: ${bin.path}（${bin.source}）")

        return runCatching {
            // 1) 写脚本文件
            val scriptFile = File(scriptDir(), "${sanitize(scriptName)}.js")
            scriptFile.writeText(jsCode)
            // 脚本文件需要能被 root 读到
            runSu("chmod 644 ${scriptFile.absolutePath}")

            // 2) 查目标进程 pid
            val pid = resolvePid(packageName)
            if (pid <= 0) return Result(false, "目标应用未运行，请先启动 $packageName")

            // 3) 日志文件
            val logFile = File(logDir(), "${sanitize(packageName)}_${System.currentTimeMillis()}.log")
            logFile.writeText("")

            // 4) 启动 frida-inject（后台进程，输出重定向到日志文件）
            // -R v8：默认 qjs runtime 没有 Java bridge，hook Java 必须 v8
            // LANG=C.UTF-8：保证 console.log 中文以 UTF-8 输出（su 默认环境无 LANG）
            val cmd = "LANG=C.UTF-8 ${bin.path} -R v8 -p $pid -s ${scriptFile.absolutePath} > ${logFile.absolutePath} 2>&1"
            val process = runSuAsync(cmd)

            val run = ScriptRun(packageName, scriptName, pid, process, logFile, System.currentTimeMillis())
            runs[packageName] = run

            Log.i(TAG, "已注入 $scriptName → $packageName (pid=$pid)")
            Result(true, "脚本已注入 $packageName，输出写入 ${logFile.name}")
        }.getOrElse {
            Result(false, "注入失败: ${it.message}")
        }
    }

    /** 停止某个应用的注入。 */
    fun stop(packageName: String) {
        runs.remove(packageName)?.let { run ->
            runCatching { run.process?.destroyForcibly() }
            // 杀掉 frida-inject 进程
            runSu("pkill -f 'frida-inject.*-p ${run.pid}' 2>/dev/null")
            Log.i(TAG, "已停止 $packageName 的注入")
        }
    }

    fun stopAll() {
        runs.keys.toList().forEach { stop(it) }
    }

    /** 当前注入状态。 */
    fun status(): Map<String, String> = runs.mapValues {
        val running = it.value.process?.isAlive == true
        if (running) "运行中 (pid=${it.value.pid})" else "已退出"
    }

    /** 读某个应用的最新日志（从日志文件读）。 */
    fun readLog(packageName: String, limit: Int): List<String> {
        val run = runs[packageName] ?: return emptyList()
        return runCatching {
            run.logFile.readLines().takeLast(limit)
        }.getOrDefault(emptyList())
    }

    // ---------------- 底层 ----------------

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9_\\-]"), "_").take(60)

    private fun resolvePid(packageName: String): Int = runCatching {
        val out = runSu("pidof $packageName")?.trim()
        out?.substringBefore(' ')?.toIntOrNull() ?: -1
    }.getOrDefault(-1)

    /** 同步执行 su 命令，返回输出。 */
    private fun runSu(cmd: String): String? = runCatching {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
        text
    }.getOrNull()

    /** 异步执行 su 命令（frida-inject 是常驻进程）。 */
    private fun runSuAsync(cmd: String): Process? = runCatching {
        ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
    }.getOrNull()

    data class Result(val ok: Boolean, val message: String)
}
