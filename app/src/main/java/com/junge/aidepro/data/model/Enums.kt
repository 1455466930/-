package com.junge.aidepro.data.model

/**
 * 日志分组：与原版 LogDataV2.GROUP 对齐。
 */
object LogGroup {
    const val DEFAULT = 0
    const val CRYPTO = 1
    const val NETWORK = 2
    const val STORAGE = 3
    const val UI = 4
    const val SYSTEM = 5

    fun label(g: Int): String = when (g) {
        DEFAULT -> "默认"
        CRYPTO -> "加密"
        NETWORK -> "网络"
        STORAGE -> "存储"
        UI -> "界面"
        SYSTEM -> "系统"
        else -> "其他"
    }
}

/**
 * 报文视图模式：与原版 PACKET_TYPE_* 对齐。
 */
enum class PacketView { HEAD, BODY, RAW, WEBSOCKET }

/** 日志值渲染模式：对应原版 base64Switch / hexSwitch。 */
enum class ValueMode { TEXT, BASE64, HEX }

/** 抓包会话状态。 */
enum class CaptureState { IDLE, RUNNING, PAUSED, STOPPED, ERROR }

/** 流量类型。 */
enum class Protocol { HTTP, WEBSOCKET, TCP, UNKNOWN }
