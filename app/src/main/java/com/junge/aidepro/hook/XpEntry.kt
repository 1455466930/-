package com.junge.aidepro.hook

import com.junge.aidepro.hook.engine.HookEngine
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 经典 Xposed API 入口（API 82）。
 *
 * 与模块内的 assets/xposed_init 对应（该文件内容为：com.junge.aidepro.hook.XpEntry）。
 */
class XpEntry : IXposedHookLoadPackage, IXposedHookZygoteInit {

    private val engine = HookEngine()

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        XposedBridge.log("[AidePro2] zygote 初始化，模块路径=${startupParam.modulePath}")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        engine.handleLoadPackage(lpparam)
    }
}
