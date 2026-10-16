package com.junge.aidepro.hook.engine

import com.junge.aidepro.hook.config.HookSwitch
import com.junge.aidepro.hook.modules.AssetsModule
import com.junge.aidepro.hook.modules.CipherModule
import com.junge.aidepro.hook.modules.DigestModule
import com.junge.aidepro.hook.modules.FileModule
import com.junge.aidepro.hook.modules.HiddenProxyModule
import com.junge.aidepro.hook.modules.HiddenVpnModule
import com.junge.aidepro.hook.modules.HiddenXposedModule
import com.junge.aidepro.hook.modules.HttpCaptureModule
import com.junge.aidepro.hook.modules.LifecycleModule
import com.junge.aidepro.hook.modules.LogModule
import com.junge.aidepro.hook.modules.MacModule
import com.junge.aidepro.hook.modules.RootBypassModule
import com.junge.aidepro.hook.modules.ShellModule
import com.junge.aidepro.hook.modules.SharedPreferencesModule
import com.junge.aidepro.hook.modules.SqliteModule
import com.junge.aidepro.hook.modules.SslTrustModule
import com.junge.aidepro.hook.modules.SystemModule
import com.junge.aidepro.hook.modules.UiModule
import com.junge.aidepro.hook.modules.WebCryptoModule
import com.junge.aidepro.hook.modules.WebViewModule

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
