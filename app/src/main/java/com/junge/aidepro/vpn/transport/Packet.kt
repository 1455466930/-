package com.junge.aidepro.vpn.transport

import com.junge.aidepro.vpn.transport.protocol.IP4Header
import com.junge.aidepro.vpn.transport.protocol.TransportHeader

/**
 * 一个完整的网络包：IP 头 + 传输层头 + 原始字节。
 *
 * 移植自 ProxyPin（Apache 2.0）的 `com.network.proxy.vpn.transport.Packet`。
 */
data class Packet(
    val ipHeader: IP4Header,
    val transportHeader: TransportHeader,
    val buffer: ByteArray,
)
