package com.junge.aidepro.util

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 管道中心。
 *
 * 【用途】
 * Binder 单次事务有 ~1MB 上限，大体积数据（Frida 日志流、大响应体）不能塞进
 * Bundle 传。原版的做法是 openWritePipe 返回 ParcelFileDescriptor，
 * 被 Hook 进程拿到写端持续写，宿主拿读端消费。
 *
 * 这里用 ParcelFileDescriptor.createPipe() 实现同样的机制。
 *
 * 【为什么要留着这个口子】
 * 虽然当前日志走 Bundle 已经够用（字段已截断到 256KB），但：
 *  - Frida 脚本的 console.log 是持续流，没法用一次性 Bundle 传
 *  - 某些应用单次加密的输入超过 1MB
 * 保留管道通道可以在不改变协议的前提下支撑这些场景。
 */
object PipeHub {

    private const val TAG = "PipeHub"

    private val pipes = ConcurrentHashMap<String, ParcelFileDescriptor>()

    /**
     * 创建管道，返回**写端**给调用方（被 Hook 进程）。
     * 读端留在宿主，由后台线程消费。
     */
    fun openPipe(packageName: String, channel: String): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]

        val key = "$packageName/$channel"
        pipes[key]?.let { runCatching { it.close() } }

        // 启动读端消费线程
        Thread({ consumePipe(packageName, channel, readEnd) }, "pipe-$channel").apply {
            isDaemon = true
            start()
        }

        Log.i(TAG, "管道已创建: $key")
        return writeEnd
    }

    private fun consumePipe(packageName: String, channel: String, readEnd: ParcelFileDescriptor) {
        val key = "$packageName/$channel"
        try {
            ParcelFileDescriptor.AutoCloseInputStream(readEnd).use { input ->
                val buf = ByteArray(8192)
                val sb = StringBuilder()
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    sb.append(String(buf, 0, n, Charsets.UTF_8))
                    // 按行处理
                    var idx = sb.indexOf("\n")
                    while (idx >= 0) {
                        val line = sb.substring(0, idx)
                        sb.delete(0, idx + 1)
                        if (line.isNotBlank()) {
                            PipeConsumer.onLine(packageName, channel, line)
                        }
                        idx = sb.indexOf("\n")
                    }
                }
                if (sb.isNotBlank()) PipeConsumer.onLine(packageName, channel, sb.toString())
            }
        } catch (t: Throwable) {
            Log.w(TAG, "管道 $key 读取结束: ${t.message}")
        } finally {
            pipes.remove(key)
            Log.i(TAG, "管道已关闭: $key")
        }
    }

    /** 为导出日志创建管道，把数据库内容写出去。 */
    fun openExportPipe(query: String): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]

        Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { out ->
                    val content = PipeConsumer.exportContent(query)
                    out.write(content.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "导出写入失败: ${t.message}")
            }
        }, "pipe-export").apply {
            isDaemon = true
            start()
        }
        return readEnd
    }

    /**
     * 降级方案：把管道内容落盘到应用私有目录。
     * 当宿主进程与 Provider 不同进程时，管道读写端可能不在同一进程，
     * 此时用文件做中转（仅限宿主自己可读，不涉及跨应用权限）。
     */
    fun fallbackFile(packageName: String, channel: String): File =
        File("/data/local/tmp/aidepro_pipe_${packageName}_$channel.tmp")
}

/** 管道数据消费者。由宿主侧实现具体入库逻辑。 */
interface PipeConsumer {
    fun onLine(packageName: String, channel: String, line: String)

    fun exportContent(query: String): String

    companion object {
        /** 由 Application 在启动时注入实现。 */
        @Volatile
        var impl: PipeConsumer? = null

        fun onLine(packageName: String, channel: String, line: String) {
            impl?.onLine(packageName, channel, line)
        }

        fun exportContent(query: String): String =
            impl?.exportContent(query) ?: ""
    }
}
