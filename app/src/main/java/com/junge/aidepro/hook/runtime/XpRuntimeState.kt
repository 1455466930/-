package com.junge.aidepro.hook.runtime

/**
 * Xposed 框架运行时状态。
 * 被 Hook 进程用它记录/查询当前框架信息，便于诊断日志与兼容分支。
 */
object XpRuntimeState {

    /** 框架 API 版本：100 = 经典 Xposed API，101/102 = libxposed 现代 API。 */
    @Volatile
    var frameworkApiVersion: Int = -1

    /** 入口类型标识，如 "legacy" / "modern"。 */
    @Volatile
    var entryKind: String = "unknown"

    /** 当前进程名。 */
    @Volatile
    var processName: String = ""

    /** 模块路径。 */
    @Volatile
    var modulePath: String = ""

    fun describe(): String =
        "entry=$entryKind api=$frameworkApiVersion process=$processName"
}
