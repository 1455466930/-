package com.sfzsgybb.ai.hook.engine

import android.os.Bundle
import android.util.Log
import com.sfzsgybb.ai.hook.config.HookSwitch
import com.sfzsgybb.ai.hook.ipc.HostConnector
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 日志上报实现（Binder 直连）。
 *
 * 【与之前方案的差异】
 * 之前尝试过「内存队列」和「写文件」，都失败：
 *  - 内存队列：被 Hook 进程与宿主是两个进程，各自的 object 实例互不可见
 *  - 写文件：untrusted_app 域受 SELinux 限制，写 /data/local/tmp 被 EACCES 拒绝
 *
 * 现在改为与原版一致的 Binder 通道：
 *   ContentProvider 握手拿到 IBinder → ILogManager.writeLog(Bundle)
 *
 * 【为什么用队列 + 单线程投递】
 * Hook 回调运行在目标应用的业务线程上（可能在加密、可能在 UI），
 * 直接在回调里做 Binder 调用会：
 *   - 阻塞业务线程（Binder 往返通常 0.1~2ms，高频率下累积明显）
 *   - 一旦宿主无响应，直接拖死目标应用
 * 因此这里入队即返回，由独立线程批量投递，队列满则丢弃。
 */
class IpcLogReporter(
    private val context: android.content.Context,
    private val packageName: String,
) : LogReporter {

    private val queue = ArrayBlockingQueue<Bundle>(QUEUE_CAPACITY)

    @Volatile private var running = true

    /**
     * 单线程投递器。
     * 用 daemon 线程，不影响目标应用退出。
     * 声明在 init 之前，保证初始化顺序正确。
     */
    private val dispatcher = Thread({
        while (running) {
            try {
                val first = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                deliver(first)
                // 批量取走剩余，减少 Binder 往返次数
                var n = 0
                while (n < BATCH_SIZE) {
                    val next = queue.poll() ?: break
                    deliver(next)
                    n++
                }
            } catch (t: InterruptedException) {
                break
            } catch (t: Throwable) {
                Log.w(TAG, "投递失败: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }, "aidepro-log-dispatch").apply { isDaemon = true }

    init {
        dispatcher.start()
    }

    // ---------------- 对外接口 ----------------

    override fun report(
        logName: String,
        className: String,
        objName: String,
        payload: ByteArray,
        results: List<ByteArray>,
        sw: HookSwitch,
        callStack: String,
        algoName: String,
        keyType: String,
        keyData: ByteArray,
        paramDesc: String,
        resultKind: String,
    ) {
        val b = Bundle().apply {
            putString("pkg", packageName)
            putString("logName", logName)
            putString("className", className)
            putString("objName", objName)
            putString("switchKey", sw.key)
            putString("group", sw.group.toString())
            putString("callStack", callStack)
            putString("ts", System.currentTimeMillis().toString())
            putByteArray("payload", cap(payload))
            putSerializable("results", results.map { cap(it) }.toTypedArray())
            // 详情页分类字段
            putString("algoName", algoName)
            putString("keyType", keyType)
            putByteArray("keyData", cap(keyData))
            putString("paramDesc", paramDesc)
            putString("resultKind", resultKind)
        }
        enqueue(b)
    }

    override fun packet(protocol: String, remoteAddress: String, outbound: Boolean, payload: ByteArray) {
        val b = Bundle().apply {
            putString("pkg", packageName)
            putString("protocol", protocol)
            putString("remoteAddress", remoteAddress)
            putString("outbound", outbound.toString())
            putString("ts", System.currentTimeMillis().toString())
            putByteArray("payload", cap(payload))
        }
        enqueue(b)
    }

    override fun http(
        method: String,
        url: String,
        statusCode: Int,
        requestHeaders: List<Pair<String, String>>,
        responseHeaders: List<Pair<String, String>>,
        requestBody: ByteArray,
        responseBody: ByteArray,
        durationMs: Long,
    ) {
        val b = Bundle().apply {
            putString("pkg", packageName)
            putString("method", method)
            putString("url", url)
            putString("statusCode", statusCode.toString())
            putString("durationMs", durationMs.toString())
            putString("ts", System.currentTimeMillis().toString())
            putString("reqHeaders", encodeHeaders(requestHeaders))
            putString("respHeaders", encodeHeaders(responseHeaders))
            putByteArray("requestBody", cap(requestBody))
            putByteArray("responseBody", cap(responseBody))
        }
        enqueue(b)
    }

    // ---------------- 投递 ----------------

    private fun enqueue(b: Bundle) {
        if (!queue.offer(b)) {
            dropped.incrementAndGet()
        }
    }

    private fun cap(b: ByteArray): ByteArray =
        if (b.size <= MAX_FIELD) b else b.copyOf(MAX_FIELD)

    private fun encodeHeaders(headers: List<Pair<String, String>>): String =
        headers.joinToString("|") { "${it.first}=${it.second}" }

    private fun deliver(b: Bundle) {
        val lm = HostConnector.getLogManager(context)
        if (lm == null) {
            dropped.incrementAndGet()
            return
        }
        try {
            when {
                b.containsKey("logName") -> lm.writeLog(b)
                b.containsKey("url") -> lm.writeHttp(b)
                b.containsKey("protocol") -> lm.writePacket(b)
            }
            delivered.incrementAndGet()
        } catch (t: Throwable) {
            dropped.incrementAndGet()
            // Binder 死亡时清理并等待重连
            HostConnector.reset()
        }
    }

    fun shutdown() {
        running = false
        dispatcher.interrupt()
    }

    companion object {
        private const val TAG = "IpcLogReporter"

        /** 单字段上限。Binder 事务总上限约 1MB，这里保守取 256KB。 */
        const val MAX_FIELD = 256 * 1024

        private const val QUEUE_CAPACITY = 4096
        private const val BATCH_SIZE = 32

        /** 统计。 */
        val delivered = AtomicLong(0)
        val dropped = AtomicLong(0)
        private val started = AtomicBoolean(false)
    }
}
