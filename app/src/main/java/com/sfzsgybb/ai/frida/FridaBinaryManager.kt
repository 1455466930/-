package com.sfzsgybb.ai.frida

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import org.tukaani.xz.XZInputStream

/**
 * Frida 可执行文件管理。
 *
 * 【解决什么问题】
 * frida-inject 是 50MB 的外部二进制，不能要求每个用户自己装。
 * 这个类负责让它「开箱可用」：
 *
 *  arm64 设备 → 从 APK assets 释放（xz 压缩，15MB，**零联网**）
 *  其他 ABI   → 多源下载对应版本
 *  用户已装   → 优先使用，不覆盖
 *
 * 【为什么只按 SUPPORTED_ABIS[0] 判断】
 * SUPPORTED_ABIS 是「兼容列表」，arm64 设备会同时列出 armeabi-v7a。
 * 但那是降级容错用的 —— 我们的场景必须用首选 ABI，
 * 否则 arm64 设备可能去下 arm 版，注入 64 位进程会失败。
 */
object FridaBinaryManager {

    private const val TAG = "FridaBinary"

    /** 安装目标（shell 与 root 都能访问，且不在 app 私有目录才不会受 SELinux 限制）。 */
    const val TARGET_DIR = "/data/local/tmp"
    const val TARGET_PATH = "$TARGET_DIR/frida-inject"

    /** 内嵌的 arm64 版本（xz 压缩）。 */
    private const val ASSET_ARM64 = "frida/frida-inject-arm64.xz"

    /**
     * 版本对应的下载地址模板。
     * 只覆盖非 arm64 设备 —— arm64 走 assets。
     */
    private const val FRIDA_VERSION = "17.2.17"

    /** 多源前缀（国内可用性差异巨大，按顺序试）。 */
    private val MIRRORS = listOf(
        "https://github.com/frida/frida/releases/download/%s/%s",
        "https://gh-proxy.com/https://github.com/frida/frida/releases/download/%s/%s",
        "https://ghfast.top/https://github.com/frida/frida/releases/download/%s/%s",
    )

    /** 状态。 */
    enum class Source {
        /** 用户自己装的（优先） */
        PREINSTALLED,
        /** 从 APK assets 释放 */
        BUNDLED,
        /** 联网下载 */
        DOWNLOADED,
        /** 没有 */
        MISSING,
    }

    data class Status(
        val available: Boolean,
        val path: String,
        val source: Source,
        val abi: String,
        val version: String?,
        val message: String,
    )

    /**
     * 确保 frida-inject 可用；不可用时尝试自动安装。
     *
     * @param context 用于读 assets
     * @return 最终状态
     */
    suspend fun ensureAvailable(context: Context): Status {
        // 1) 已有可用的（用户自己装的，或之前释放过的）—— 直接返回
        existingUsable()?.let { return it }

        // 2) 按首选 ABI 决定来源
        val abi = primaryAbi()
        Log.i(TAG, "首选 ABI: $abi")

        return when {
            abi == "arm64-v8a" -> installFromAssets(context, abi)
            abi in setOf("armeabi-v7a", "armeabi") -> downloadForAbi("arm")
            abi == "x86_64" -> downloadForAbi("x86_64")
            abi == "x86" -> downloadForAbi("x86")
            else -> Status(
                available = false, path = TARGET_PATH, source = Source.MISSING,
                abi = abi, version = null,
                message = "不支持的 CPU 架构：$abi",
            )
        }
    }

    /** 探测路径 —— 覆盖常见安装位置。 */
    private val CANDIDATE_PATHS = listOf(
        "/data/local/tmp/frida-inject",
        "/data/adb/frida-inject",
        "/data/adb/modules/frida_server_boot/frida-inject",
        "/system/bin/frida-inject",
        "/system/xbin/frida-inject",
    )

    /** 查已存在的可用二进制（含版本自检）。 */
    fun existingUsable(): Status? {
        for (p in CANDIDATE_PATHS) {
            val f = File(p)
            if (!f.exists()) continue
            val ver = probeVersion(p)
            if (ver != null) {
                return Status(true, p, Source.PREINSTALLED, primaryAbi(), ver, "已存在：$p（$ver）")
            }
        }
        return null
    }

    /**
     * 从 assets 释放 arm64 版本。
     *
     * 流程：解 xz → 写私有目录 → su 拷到 /data/local/tmp → chmod → 版本自检
     */
    private fun installFromAssets(context: Context, abi: String): Status {
        val tmp = File(context.filesDir, "frida-inject.tmp")
        return try {
            // 解压
            context.assets.open(ASSET_ARM64).use { input ->
                XZInputStream(input).use { xz ->
                    FileOutputStream(tmp).use { out -> xz.copyTo(out, 1 shl 16) }
                }
            }
            if (!tmp.exists() || tmp.length() < 1024) {
                return fail("assets 解压失败（文件为空）")
            }
            Log.i(TAG, "已解压到 ${tmp.absolutePath}（${tmp.length() / 1024 / 1024} MB）")

            // 拷到 /data/local/tmp 并赋可执行权限（需要 root）
            val cmd = "cp -f ${tmp.absolutePath} $TARGET_PATH && " +
                "chmod 755 $TARGET_PATH && echo ok"
            val out = runSu(cmd)
            if (out?.contains("ok") != true) {
                return fail("释放失败（需要 root 权限）：${out?.take(120) ?: "无输出"}")
            }

            // 版本自检 —— 防止 ABI 不匹配
            val ver = probeVersion(TARGET_PATH)
                ?: return fail("释放后无法执行（可能 ABI 不匹配，设备是 $abi）")

            Status(true, TARGET_PATH, Source.BUNDLED, abi, ver, "已从内置资源安装（$ver）")
        } catch (t: Throwable) {
            Log.e(TAG, "assets 释放失败", t)
            fail("释放失败：${t.javaClass.simpleName}: ${t.message}")
        } finally {
            runCatching { tmp.delete() }
        }
    }

    /** 下载对应 ABI 的 frida-inject。 */
    private fun downloadForAbi(fridaArch: String): Status {
        val fileName = "frida-inject-$FRIDA_VERSION-android-$fridaArch.xz"
        for (tmpl in MIRRORS) {
            val url = String.format(tmpl, FRIDA_VERSION, fileName)
            Log.i(TAG, "尝试下载: $url")
            val ok = runSu(
                "curl -fsSL --connect-timeout 10 --max-time 180 '$url' -o $TARGET_PATH.xz && " +
                    "echo downloaded"
            )?.contains("downloaded") == true
            if (!ok) continue

            // 解压（设备有 toybox xz 吗？没有就用 busybox/我们自己的解压）
            val un = runSu(
                "cd $TARGET_DIR && (xz -d -f frida-inject.xz 2>/dev/null || " +
                    "toybox xz -d -f frida-inject.xz 2>/dev/null) && " +
                    "chmod 755 frida-inject && echo ok"
            )
            if (un?.contains("ok") != true) {
                runSu("rm -f $TARGET_PATH.xz")
                continue
            }

            val ver = probeVersion(TARGET_PATH) ?: continue
            return Status(true, TARGET_PATH, Source.DOWNLOADED, primaryAbi(), ver, "已下载安装（$ver）")
        }
        return fail(
            "下载失败。请手动安装：\n" +
                "1. 下载 frida-inject-$FRIDA_VERSION-android-$fridaArch.xz\n" +
                "2. 解压后 push 到 $TARGET_PATH\n" +
                "3. su -c 'chmod 755 $TARGET_PATH'"
        )
    }

    // ---------------- 工具方法 ----------------

    /** 首选 ABI（不是兼容列表的第一个以外的任何值）。 */
    fun primaryAbi(): String = runCatching {
        val abis = Build.SUPPORTED_ABIS
        if (abis.isNotEmpty()) abis[0] else "unknown"
    }.getOrDefault("unknown")

    /** 执行 --version 自检。返回版本号，不可执行返回 null。 */
    private fun probeVersion(path: String): String? = runCatching {
        val out = runSu("$path --version 2>&1")
            ?: return null
        // frida-inject 输出版本号形如 "17.17.0"
        val m = Regex("(\\d+\\.\\d+\\.\\d+)").find(out)
        m?.groupValues?.get(1)
    }.getOrNull()

    private fun runSu(cmd: String): String? = runCatching {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        val done = p.waitFor(60, TimeUnit.SECONDS)
        if (!done) {
            p.destroyForcibly()
            Log.w(TAG, "su 超时: ${cmd.take(60)}")
            return@runCatching null
        }
        text
    }.getOrNull()

    private fun fail(msg: String) =
        Status(false, TARGET_PATH, Source.MISSING, primaryAbi(), null, msg)
}
