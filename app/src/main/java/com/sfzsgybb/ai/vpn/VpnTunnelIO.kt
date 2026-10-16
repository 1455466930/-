package com.sfzsgybb.ai.vpn

import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 从 VpnService 的 tun 接口读取原始 IP 包。
 *
 * 移植自 ProxyPin（Apache 2.0）的 `com.network.proxy.vpn.ProxyVpnThread`。
 *
 * 【工作原理】
 * `VpnService.Builder.establish()` 返回一个 ParcelFileDescriptor，
 * 它代表一个虚拟网卡（tun）。系统把「被路由到 VPN 的」IP 包写进这个 fd，
 * 我们读出来就是原始的 IPv4 报文（含 IP 头）。
 *
 * 注意：读出来的包长度可变，必须按 IP 头里的 totalLength 处理，
 * 不能假设固定长度。
 */
class VpnTunnelReader(
    private val input: FileInputStream,
    /** 收到一个完整 IP 包时回调 */
    private val onPacket: (ByteBuffer) -> Unit,
) {

    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    @Volatile var packetsRead = 0L
        private set
    @Volatile var bytesRead = 0L
        private set
    @Volatile var lastError: String? = null
        private set

    fun start() {
        if (running.getAndSet(true)) return
        worker = Thread({
            val buf = ByteArray(MAX_PACKET_SIZE)
            Log.i(TAG, "VPN 读取线程启动，MTU=$MAX_PACKET_SIZE")
            while (running.get()) {
                try {
                    val n = input.read(buf)
                    if (n <= 0) {
                        if (n < 0) {
                            Log.w(TAG, "tun 读到 EOF，退出")
                            break
                        }
                        continue
                    }
                    if (packetsRead == 0L) {
                        VpnLog.add("收到第一个数据包（tun 已通）")
                    }
                    packetsRead++
                    bytesRead += n
                    // 包装成 ByteBuffer；限制长度到实际读到的字节数
                    val bb = ByteBuffer.wrap(buf, 0, n)
                    try {
                        onPacket(bb)
                    } catch (t: Throwable) {
                        // 单个包处理失败不能影响整体
                        Log.w(TAG, "处理包失败: ${t.javaClass.simpleName}: ${t.message}")
                    }
                } catch (t: Throwable) {
                    if (!running.get()) break
                    lastError = "${t.javaClass.simpleName}: ${t.message}"
                    Log.w(TAG, "读取包异常", t)
                    // 避免异常时疯狂空转
                    try { Thread.sleep(10) } catch (e: InterruptedException) { break }
                }
            }
            Log.i(TAG, "VPN 读取线程结束，共读 $packetsRead 个包 / $bytesRead 字节")
        }, "vpn-reader").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running.set(false)
        worker?.interrupt()
    }

    companion object {
        private const val TAG = "VpnTunnelReader"

        /** 常见 MTU，够容纳标准以太网帧的 IP 部分 */
        const val MAX_PACKET_SIZE = 32767
    }
}

/**
 * 向 tun 接口写入回给客户端的 IP 包。
 *
 * 移植自 ProxyPin（Apache 2.0）的 `com.network.proxy.vpn.socket.ClientPacketWriter`。
 * 加了同步保护：多个连接线程可能同时回包，不同步会把包写乱。
 */
class VpnTunnelWriter(private val output: FileOutputStream) :
    com.sfzsgybb.ai.vpn.socket.TunnelPacketWriter {

    private val lock = Any()

    @Volatile var packetsWritten = 0L
        private set
    @Volatile var bytesWritten = 0L
        private set

    override fun write(data: ByteArray): Boolean = write(data, 0, data.size)

    fun write(data: ByteArray, offset: Int, length: Int): Boolean {
        return try {
            synchronized(lock) {
                output.write(data, offset, length)
                output.flush()
            }
            packetsWritten++
            bytesWritten += length
            true
        } catch (t: Throwable) {
            Log.w(TAG, "写包失败: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    companion object {
        private const val TAG = "VpnTunnelWriter"
    }
}
