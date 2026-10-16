package com.sfzsgybb.ai.hook.engine

import com.sfzsgybb.ai.hook.config.HookSwitch
import com.sfzsgybb.ai.hook.modules.AssetsModule
import com.sfzsgybb.ai.hook.modules.CipherModule
import com.sfzsgybb.ai.hook.modules.DigestModule
import com.sfzsgybb.ai.hook.modules.FileModule
import com.sfzsgybb.ai.hook.modules.HiddenProxyModule
import com.sfzsgybb.ai.hook.modules.HiddenVpnModule
import com.sfzsgybb.ai.hook.modules.HiddenXposedModule
import com.sfzsgybb.ai.hook.modules.HttpCaptureModule
import com.sfzsgybb.ai.hook.modules.LifecycleModule
import com.sfzsgybb.ai.hook.modules.LogModule
import com.sfzsgybb.ai.hook.modules.MacModule
import com.sfzsgybb.ai.hook.modules.RootBypassModule
import com.sfzsgybb.ai.hook.modules.ShellModule
import com.sfzsgybb.ai.hook.modules.SharedPreferencesModule
import com.sfzsgybb.ai.hook.modules.SqliteModule
import com.sfzsgybb.ai.hook.modules.SslTrustModule
import com.sfzsgybb.ai.hook.modules.SystemModule
import com.sfzsgybb.ai.hook.modules.UiModule
import com.sfzsgybb.ai.hook.modules.WebCryptoModule
import com.sfzsgybb.ai.hook.modules.WebViewModule

/**
 * 模块注册表。
 *
 * 新增一项 Hook 功能只需：
 *  1. 实现 [HookModule]
 *  2. 在 [all] 里注册
 * 无需改动引擎代码。
 */
object ModuleRegistry {

    private val modules: List<HookModule> by lazy {
        listOf(
            // 加密
            DigestModule(),
            MacModule(),
            CipherModule(),
            WebCryptoModule(),
            // 环境
            RootBypassModule(),
            HiddenXposedModule(),
            HiddenProxyModule(),
            HiddenVpnModule(),
            // 证书
            SslTrustModule(),
            // 网络抓包
            HttpCaptureModule(),
            // 文件
            AssetsModule(),
            FileModule(),
            // 执行
            ShellModule(),
            // UI
            UiModule(HookSwitch.ON_CLICK),
            UiModule(HookSwitch.DIALOG),
            UiModule(HookSwitch.CLOSE_DIALOG),
            UiModule(HookSwitch.TEXT_VIEW),
            UiModule(HookSwitch.ACTIVITY),
            // WebView
            WebViewModule(HookSwitch.WEB_VIEW_DEBUG),
            WebViewModule(HookSwitch.WEB_VIEW_LOAD_URL),
            // 数据库
            SqliteModule(),
            SharedPreferencesModule(),
            // 系统
            LogModule(),
            LifecycleModule(),
            SystemModule(HookSwitch.EXIT),
            SystemModule(HookSwitch.EXCEPTION),
            SystemModule(HookSwitch.CAMERA),
            SystemModule(HookSwitch.SCREEN),
        )
    }

    fun all(): List<HookModule> = modules

    fun bySwitch(sw: HookSwitch): HookModule? = modules.firstOrNull { it.switch == sw }
}
