package com.sfzsgybb.ai.hook.engine

import android.content.Context
import com.sfzsgybb.ai.hook.config.AppConfig
import com.sfzsgybb.ai.hook.config.HookSwitch
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Hook 模块接口。
 *
 * 原版把 37 项功能的实现散落在几个巨大类里（XpInit 单个类就上万行），
 * 新增一项功能必须改动核心文件，风险高。
 * 这里改为 SPI：每项功能一个独立实现，[HookEngine] 扫描注册，
 * 新增功能不需要动引擎代码。
 */
interface HookModule {

    /** 本模块负责的开关。 */
    val switch: HookSwitch

    /** 在目标进程中被调用的 install 时机（Application.attachBaseContext 之后）。 */
    fun install(ctx: HookContext)

    /** 有些模块必须更早安装（如 Application 自身相关）。 */
    fun installEarly(ctx: HookContext) {}
}

/**
 * Hook 上下文，封装目标进程信息与配置查询。
 */
class HookContext(
    val context: Context,
    val packageName: String,
    val processName: String,
    val classLoader: ClassLoader,
    val loadPackageParam: XC_LoadPackage.LoadPackageParam,
    private val config: AppConfig,
    /** 上报一条日志到宿主 APP */
    val reporter: LogReporter,
) {
    val isMainProcess: Boolean get() = processName == packageName

    fun enabled(sw: HookSwitch): Boolean = config.isOn(sw)
    fun config(): AppConfig = config

    /** 安全的 class 查找，找不到返回 null 而不抛异常。 */
    fun findClassOrNull(name: String): Class<*>? =
        runCatching { Class.forName(name, false, classLoader) }.getOrNull()
}

/**
 * 日志上报接口。被 Hook 进程通过它把日志写回宿主 APP 的数据库。
 */
interface LogReporter {
    fun report(
        logName: String,
        className: String,
        objName: String,
        payload: ByteArray,
        results: List<ByteArray>,
        sw: HookSwitch,
        callStack: String = "",
        /** 算法名称（详情页分类展示），如 AES/CBC/PKCS5Padding */
        algoName: String = "",
        /** 密钥类型：会话密钥 / 公钥 / 私钥 */
        keyType: String = "",
        /** 密钥内容 */
        keyData: ByteArray = ByteArray(0),
        /** 参数描述（含参数类型） */
        paramDesc: String = "",
        /** 结果类别：加密结果 / 解密结果 / 摘要结果 / 签名结果 / MAC 结果 */
        resultKind: String = "",
    )

    fun packet(
        protocol: String,
        remoteAddress: String,
        outbound: Boolean,
        payload: ByteArray,
    )

    fun http(
        method: String,
        url: String,
        statusCode: Int,
        requestHeaders: List<Pair<String, String>>,
        responseHeaders: List<Pair<String, String>>,
        requestBody: ByteArray,
        responseBody: ByteArray,
        durationMs: Long,
    )
}
