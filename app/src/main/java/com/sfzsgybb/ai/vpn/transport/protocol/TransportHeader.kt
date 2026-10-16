package com.sfzsgybb.ai.vpn.transport.protocol

/**
 * 传输层头（TCP/UDP 的公共接口）。
 *
 * 移植自 ProxyPin（Apache 2.0）。
 *
 * 【为什么方法名不是 getSourcePort】
 * Kotlin 里 `var sourcePort` 会自动生成 JVM 方法 `getSourcePort()`，
 * 如果接口也声明 `getSourcePort()`，两者 JVM 签名相同但 Kotlin
 * 认为它们不构成 override，会报 "Platform declaration clash"。
 * 因此接口改用 `srcPort()` / `dstPort()` 命名以避开冲突。
 */
interface TransportHeader {
    fun srcPort(): Int
    fun dstPort(): Int
}
