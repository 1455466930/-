package com.junge.aidepro.hook.config

import com.junge.aidepro.data.model.LogGroup

/**
 * Hook 功能开关枚举。
 *
 * 与原版 ConfigKeys$App 的 37 项 string 常量一一对应，
 * 但用枚举替代裸字符串：编译期检查、可遍历、可自动生成 UI，
 * 避免原版那种拼错 key 只能运行时才发现的坑。
 */
enum class HookSwitch(
    val key: String,
    val title: String,
    val desc: String,
    val group: Int,
    /** 目标应用高危开关：默认关闭并在 UI 上给出警告 */
    val risky: Boolean = false,
) {
    // ---- 加密算法 ----
    DIGEST("digestSwitch", "消息摘要", "Hook MessageDigest（MD5/SHA 系列）", LogGroup.CRYPTO),
    MAC("macSwitch", "消息认证码", "Hook Mac（HMAC 系列）", LogGroup.CRYPTO),
    CIPHER("cipherSwitch", "对称/非对称加密", "Hook Cipher（AES/DES/RSA）", LogGroup.CRYPTO),
    WEB_CRYPT("webCryptSwitch", "WebCrypto", "Hook WebView 内 WebCrypto 调用", LogGroup.CRYPTO),

    // ---- 环境对抗 ----
    CHECK_ROOT("checkRootSwitch", "绕过 Root 检测", "拦截常见 Root 检测（su 路径、build tags、SafetyNet 基础项）", LogGroup.SYSTEM, risky = true),
    HIDDEN_XPOSED("hiddenXposedSwitch", "隐藏 Xposed 痕迹", "隐藏 Xposed 类加载器与堆栈痕迹", LogGroup.SYSTEM, risky = true),
    HIDDEN_WIFI_PROXY("hiddenWifiProxySwitch", "隐藏 WiFi 代理", "伪造代理设置为 null", LogGroup.SYSTEM, risky = true),
    HIDDEN_VPN("hiddenVpnProxySwitch", "隐藏 VPN", "伪造 VPN 网络能力与 transport", LogGroup.SYSTEM, risky = true),
    SIGNATURE("signSwitch", "签名校验绕过", "伪造 getPackageInfo 签名返回", LogGroup.SYSTEM, risky = true),

    // ---- 证书 ----
    /**
     * HTTP(S) 抓包。
     *
     * 在 Java 层拦截 OkHttp3 / HttpURLConnection / WebView 的请求与响应，
     * 拿到的**是明文**（数据在 TLS 加密前 / 解密后截获），
     * 因此**不需要安装 CA 证书、也不需要开「SSL 证书信任」**。
     *
     * 这是抓「指定应用」最直接的方式：
     * 只有被勾选 Hook 的应用才会加载本模块。
     */
    HTTP_CAPTURE(
        "httpCaptureSwitch",
        "HTTP 抓包",
        "Hook OkHttp3/HttpURLConnection/WebView，拿请求与响应明文（无需证书）",
        LogGroup.NETWORK,
    ),

    JUST_TRUST_ME_PLUS("justTrustMePlushSwitch", "SSL 证书信任", "Hook X509TrustManager / HostnameVerifier 全信任（等价 JustTrustMe+）", LogGroup.NETWORK, risky = true),

    // ---- 文件 ----
    ASSETS("assetsSwitch", "Assets 读取", "Hook AssetManager.open", LogGroup.STORAGE),
    FILE_READ("fileSwitch", "文件读取", "Hook FileInputStream / RandomAccessFile 读取", LogGroup.STORAGE),
    FILE_WRITE("fileWriteSwitch", "文件写入", "Hook FileOutputStream 写入", LogGroup.STORAGE),
    FILE_DELETE("fileDeleteSwitch", "文件删除", "Hook File.delete", LogGroup.STORAGE),

    // ---- 执行 ----
    SHELL("shellSwitch", "命令执行", "Hook Runtime.exec / ProcessBuilder", LogGroup.SYSTEM),

    // ---- UI 交互 ----
    ON_CLICK("onClickSwitch", "点击事件", "Hook View.OnClickListener.onClick", LogGroup.UI),
    DIALOG("dialogSwitch", "弹窗展示", "Hook Dialog / AlertDialog.show", LogGroup.UI),
    CLOSE_DIALOG("closeDialogSwitch", "自动关闭弹窗", "自动调用 dialog.dismiss", LogGroup.UI, risky = true),
    TEXT_VIEW("textViewSwitch", "文本渲染", "Hook TextView.setText", LogGroup.UI),
    ACTIVITY("activitySwitch", "页面跳转", "Hook Activity 启动与生命周期", LogGroup.UI),

    // ---- WebView ----
    WEB_VIEW_DEBUG("webViewDebugSwitch", "WebView 调试", "强制 setWebContentsDebuggingEnabled(true)", LogGroup.NETWORK),
    WEB_VIEW_LOAD_URL("webViewLoadUrlSwitch", "WebView 加载", "Hook WebView.loadUrl / loadDataWithBaseURL", LogGroup.NETWORK),

    // ---- SQLite ----
    SQLITE_OPEN("sqliteOpenSwitch", "数据库打开", "Hook SQLiteDatabase.openOrCreateDatabase", LogGroup.STORAGE),
    SQLITE_INSERT("sqliteInsertSwitch", "数据库插入", "Hook insert / insertOrThrow / insertWithOnConflict", LogGroup.STORAGE),
    SQLITE_DELETE("sqliteDeleteSwitch", "数据库删除", "Hook delete", LogGroup.STORAGE),
    SQLITE_UPDATE("sqliteUpdateSwitch", "数据库更新", "Hook update", LogGroup.STORAGE),
    SQLITE_QUERY("sqliteQuerySwitch", "数据库查询", "Hook rawQuery / query", LogGroup.STORAGE),
    SQLITE_EXEC_SQL("sqliteExecSQLSwitch", "执行原始 SQL", "Hook execSQL", LogGroup.STORAGE),

    // ---- SharedPreferences ----
    SP_OPEN("getSharedPreferencesSwitch", "SP 打开", "Hook Context.getSharedPreferences", LogGroup.STORAGE),
    SP_GET("SharedPreferencesGetSwitch", "SP 读取", "Hook SharedPreferences.getXxx", LogGroup.STORAGE),
    SP_PUT("SharedPreferencesPutSwitch", "SP 写入", "Hook Editor.putXxx / commit / apply", LogGroup.STORAGE),

    // ---- 媒体 / 系统 ----
    CAMERA("cameraHookSwitch", "相机调用", "Hook Camera / Camera2 打开", LogGroup.SYSTEM),
    LOG("logSwitch", "日志输出", "Hook Log.d/i/w/e 与 System.out", LogGroup.SYSTEM),
    APPLICATION("ApplicationSwitch", "Application 生命周期", "Hook Application.attachBaseContext / onCreate", LogGroup.SYSTEM),
    EXIT("exitSwitch", "进程退出", "Hook System.exit / Process.killProcess", LogGroup.SYSTEM),
    EXCEPTION("ExceptionSwitch", "异常捕获", "Hook 未捕获异常并上报", LogGroup.SYSTEM),
    SCREEN("screenSwitch", "屏幕截图", "Hook 截屏 API（View.getDrawingCache / PixelCopy）", LogGroup.SYSTEM, risky = true),
    ;

    companion object {
        fun byKey(key: String): HookSwitch? = entries.firstOrNull { it.key == key }
        fun byGroup(group: Int): List<HookSwitch> = entries.filter { it.group == group }
        val count: Int get() = entries.size
    }
}

/** 全局开关（对应原版 ConfigKeys$Global）。 */
enum class GlobalSwitch(
    val key: String,
    val title: String,
    val desc: String,
    val default: Boolean,
) {
    BASE64("base64Switch", "Base64 视图", "日志详情中提供 Base64 呈现", true),
    HEX("hexSwitch", "Hex 视图", "日志详情中提供十六进制呈现", true),
    STACK("stackSwitch", "调用栈", "记录每条日志的调用栈", true),
    DISCARD_SYS_STACK("discardSysStackSwitch", "过滤系统栈帧", "调用栈中剔除 android./java. 帧", true),
    TOAST("toastSwitch", "Toast 输出", "Hook Toast 并在日志中展示", false),
    FOLD_DUPLICATE_LOGS("foldDuplicateLogs", "折叠重复日志", "按 payload CRC 折叠相同内容", true),
    LOG_DETAIL_VIEW("logDetailView", "详情精细化视图", "默认进详情页而非弹窗", false),
    CAPTURE_FILTER_LOCAL("captureFilterLocalRequests", "过滤本机请求", "抓包时忽略 127.0.0.1 / 本机回环", true),
    CAPTURE_FLOATING_WINDOW("captureFloatingWindow", "抓包悬浮窗", "抓包时显示悬浮小球", true),
    MCP_HTTP_ENABLED("mcpHttpEnabled", "MCP 服务", "启用本地 MCP HTTP 服务供 AI 调用", false),
    MCP_LAN_ENABLED("mcpLanEnabled", "MCP 局域网访问", "允许同一 WiFi 下的设备直接连接（需设置访问令牌）", false),
    MCP_KEEP_ALIVE("mcpKeepAlive", "MCP 常驻保活", "前台服务保持 MCP 在线，避免 AI 客户端频繁断连", true),
    LOG_AUTO_REFRESH("logAutoRefresh", "日志自动刷新", "日志页实时刷新新捕获的日志", true),
    ;

    /** 字符串型配置的 key（非布尔开关）。 */
    object StringKeys {
        const val MCP_ACCESS_TOKEN = "mcpAccessToken"
    }
}

/** 单应用配置。 */
@kotlinx.serialization.Serializable
data class AppConfig(
    val packageName: String,
    val appLabel: String = "",
    /** 应用级总开关 */
    val enabled: Boolean = false,
    /** 开关状态：key 为 HookSwitch.key */
    val switches: Map<String, Boolean> = emptyMap(),
    /** 全局项覆盖，key 为 GlobalSwitch.key */
    val globalSwitches: Map<String, Boolean> = emptyMap(),
    /** 自定义 Hook 规则 */
    val hooks: List<HookRule> = emptyList(),
    /** 是否在被 Hook 应用内显示日志 Toast */
    val toastInApp: Boolean = false,
    /**
     * 自动关闭弹窗的关键词表，逗号分隔。
     * 为空时使用内置默认词表（更新/升级/强制退出等）。
     */
    val dialogKeywords: String = "",
) {
    fun isOn(sw: HookSwitch): Boolean = enabled && (switches[sw.key] ?: false)
    fun isOn(sw: GlobalSwitch): Boolean = globalSwitches[sw.key] ?: sw.default

    /** 便捷方法，用于 Xposed 侧高频查询。 */
    fun isOn(key: String): Boolean = enabled && (switches[key] ?: false)
}

/**
 * 自定义 Hook 规则。
 * 对齐原版 HookData，但去掉了原版把 argsValues/results 混在规则里的设计
 * （那是运行时数据，不应持久化在配置中）。
 */
@kotlinx.serialization.Serializable
data class HookRule(
    val id: String = java.util.UUID.randomUUID().toString(),
    val className: String = "",
    val methodName: String = "",
    /** 参数签名，空表示所有重载 */
    val parameterSign: String = "",
    val isConstructor: Boolean = false,
    val enable: Boolean = true,
    /** 拦截并替换返回值 */
    val intercept: Boolean = false,
    /** 替换为的返回值，JSON 字面量 */
    val replaceResult: String = "",
    val description: String = "",
)
