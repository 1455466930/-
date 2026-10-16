package com.junge.aidepro.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 导航目的地。
 *
 * 对照原版 19 个 Activity，这里收敛为「底部 4 个主区 + 栈内二级页」：
 * 原版把 Hook 配置、抓包、日志、设置平铺成 19 个 Activity，
 * 层级靠 parentActivityName 维护，既难跳转也难回退。
 */
sealed class Dest(val route: String, val title: String) {

    // 主区（底部导航）
    data object Home : Dest("home", "首页")
    data object Apps : Dest("apps", "应用")
    data object Logs : Dest("logs", "日志")
    data object Capture : Dest("capture", "抓包")
    data object Settings : Dest("settings", "设置")

    // 二级页
    data object AppDetail : Dest("app/{packageName}", "应用详情") {
        fun of(pkg: String) = "app/$pkg"
    }
    data object LogDetail : Dest("log/{id}", "日志详情") {
        fun of(id: Long) = "log/$id"
    }
    data object CaptureHistory : Dest("capture/history", "抓包历史")
    data object SessionDetail : Dest("capture/session/{sessionId}", "会话详情") {
        fun of(id: String) = "capture/session/$id"
    }
    data object HttpDetail : Dest("capture/http/{id}", "请求详情") {
        fun of(id: Long) = "capture/http/$id"
    }
    data object TcpDetail : Dest("capture/tcp/{id}", "报文详情") {
        fun of(id: Long) = "capture/tcp/$id"
    }
    data object HookList : Dest("apps/{packageName}/hooks", "Hook 规则") {
        fun of(pkg: String) = "apps/$pkg/hooks"
    }
    data object FridaEditor : Dest("frida/{packageName}", "Frida 脚本") {
        fun of(pkg: String) = "frida/$pkg"
    }
    data object FridaLog : Dest("frida/{packageName}/log", "Frida 日志") {
        fun of(pkg: String) = "frida/$pkg/log"
    }
    data object McpConsole : Dest("settings/mcp", "MCP 服务")
    data object About : Dest("settings/about", "关于")
    data object Feedback : Dest("settings/feedback", "反馈")

    companion object {
        const val PACKAGE_ARG = "packageName"
        const val ID_ARG = "id"
        const val SESSION_ARG = "sessionId"
    }
}

/** 底部导航项。 */
enum class BottomTab(val dest: Dest, val icon: ImageVector) {
    HOME(Dest.Home, Icons.Filled.Home),
    APPS(Dest.Apps, Icons.Filled.BugReport),
    LOGS(Dest.Logs, Icons.Filled.Terminal),
    CAPTURE(Dest.Capture, Icons.Filled.NetworkCheck),
    SETTINGS(Dest.Settings, Icons.Filled.Settings),
}
