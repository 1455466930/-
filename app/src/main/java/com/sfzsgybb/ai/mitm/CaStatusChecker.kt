package com.sfzsgybb.ai.mitm

import android.util.Log
import java.io.File

/**
 * CA 证书安装状态检测。
 *
 * 【为什么需要】
 * HTTPS 解密的前提是「目标应用信任我们签发的证书」。
 * 但 Android 7.0 起，**用户安装的 CA 默认不被应用信任**，
 * 只有系统级 CA（/system/etc/security/cacerts/）才生效。
 *
 * 普通用户装不了系统 CA（需要 root），这时只能靠 Hook 的
 * 「SSL 证书信任」开关让目标应用无条件信任。
 *
 * 所以启动 VPN 时要检测：
 *  - 系统级 CA 装了 → 直接能解密，无需额外操作
 *  - 只装了用户级 CA 或没装 → 提示用户开启目标应用的证书信任开关
 */
object CaStatusChecker {

    private const val TAG = "CaStatusChecker"

    /** 我们 CA 的 subject_hash_old（用于定位系统信任区里的文件名）。 */
    const val CA_HASH = "243f0bfb"

    /** 系统级 CA 目录。 */
    private const val SYSTEM_CA_DIR = "/system/etc/security/cacerts"

    /** 用户级 CA 目录（Android 14+ 在 /apex 下也有）。 */
    private val USER_CA_DIRS = listOf(
        "/data/misc/user/0/cacerts-added",
        "/data/misc/keychain/cacerts-added",
    )

    enum class CaState {
        /** 系统级 CA 已安装 → HTTPS 解密可直接工作 */
        SYSTEM,

        /** 只有用户级 CA → 大多数应用不信任，需要 Hook 证书信任 */
        USER_ONLY,

        /** 完全没装 → 需要先安装 CA */
        NONE,
    }

    /** 检测当前 CA 状态。 */
    fun check(): CaState {
        if (hasSystemCa()) return CaState.SYSTEM
        if (hasUserCa()) return CaState.USER_ONLY
        return CaState.NONE
    }

    private fun hasSystemCa(): Boolean {
        // 常见命名：<hash>.0
        val candidates = listOf(
            "$SYSTEM_CA_DIR/$CA_HASH.0",
            "$SYSTEM_CA_DIR/$CA_HASH.1",
        )
        return candidates.any { runCatching { File(it).exists() }.getOrDefault(false) }
    }

    private fun hasUserCa(): Boolean = USER_CA_DIRS.any { dir ->
        runCatching {
            File(dir).listFiles()?.any { it.name.startsWith(CA_HASH) } == true
        }.getOrDefault(false)
    }

    /** 给用户的建议文本。 */
    fun hint(state: CaState): String? = when (state) {
        CaState.SYSTEM -> null
        CaState.USER_ONLY ->
            "当前只装了用户级 CA。Android 7+ 的应用默认不信任用户 CA，" +
                "HTTPS 内容抓不到。建议在「应用」页给目标应用开启「SSL 证书信任」开关。" +
                "（追剧鸭这类用 okhttp 的应用，也可以用 Hook 抓包直接拿明文，无需证书）"
        CaState.NONE ->
            "未检测到 CA 证书。HTTPS 内容无法解密（只能看到域名和流量大小）。" +
                "有 root 可安装系统级 CA；没 root 请在「应用」页开启目标应用的「SSL 证书信任」开关。"
    }

    /** 诊断信息。 */
    fun describe(): String {
        val s = check()
        return "state=$s systemCa=${hasSystemCa()} userCa=${hasUserCa()}"
    }
}
