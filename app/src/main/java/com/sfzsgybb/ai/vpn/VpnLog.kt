package com.sfzsgybb.ai.vpn

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * VPN 抓包的运行日志。
 *
 * 【为什么需要】
 * 用户看不到日志就不知道：
 *  - VPN 是不是真的起来了
 *  - 有没有流量进来
 *  - 是抓到了但没入库，还是压根没抓到
 *
 * 所以把关键事件记到内存里，界面直接显示最近若干条。
 */
object VpnLog {

    private const val MAX_ENTRIES = 200

    private val entries = ConcurrentLinkedDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /** 添加一条日志。 */
    fun add(message: String) {
        val line = "${fmt.format(Date())}  $message"
        entries.addLast(line)
        while (entries.size > MAX_ENTRIES) entries.pollFirst()
        android.util.Log.d("VpnLog", message)
    }

    /** 取最近的日志（新的在后）。 */
    fun recent(count: Int = 50): List<String> =
        entries.toList().takeLast(count)

    fun clear() = entries.clear()

    fun size(): Int = entries.size
}
