package com.sfzsgybb.ai.mitm

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * CA 证书的导出与安装。
 *
 * 【为什么需要】
 * HTTPS 解密的前提是目标应用信任我们签发的证书。
 * 但手工装证书对用户来说门槛很高（要算 hash、改文件名、找目录），
 * 所以这里提供两个一键操作。
 *
 * 【Android 的限制】
 * 从 7.0 起，`CA_CERTIFICATE` 安装 Intent 是系统设置专属的，
 * 普通应用调不起来（会抛 SecurityException）。所以只能：
 *  - 导出证书文件，引导用户手动去「设置 → 安全 → 加密与凭据」安装
 *  - 有 root 时，直接把证书写进系统信任区（一步到位）
 */
class CertificateInstaller(private val context: Context) {

    companion object {
        private const val TAG = "CertInstaller"

        /** 系统信任区目录。 */
        const val SYSTEM_CERT_DIR = "/system/etc/security/cacerts"

        /**
         * MoveCertificate 模块的用户投放目录。
         *
         * 这个 Magisk 模块会在开机时把这里的证书（DER 格式）转成系统格式，
         * 挂载到系统信任区并同时写入用户级信任区。
         * 所以把证书放这里 = 重启后持久生效。
         */
        const val MODULE_CERT_DIR = "/data/local/tmp/cert"

        /** MoveCertificate 模块自带的证书目录（已转换的成果）。 */
        const val MODULE_OUTPUT_DIR = "/data/adb/modules/MoveCertificate/certificates"

        /** 用户级信任区（不需要 root 的一种，但多数应用不认）。 */
        const val USER_CERT_DIR = "/data/misc/user/0/cacerts-added"
    }

    /** 安装结果。 */
    data class Result(
        val ok: Boolean,
        val message: String,
        /** 需要重启才能完全生效 */
        val needReboot: Boolean = false,
        /** 导出的文件路径（导出操作时） */
        val exportedPath: String? = null,
    )

    // ==================== 导出 ====================

    /**
     * 把 CA 证书导出到「下载」目录（DER + PEM 两种格式）。
     *
     * 用 MediaStore 写入，不需要存储权限（Android 10+）。
     * 导出的文件可以直接在系统设置里安装。
     */
    fun exportToDownloads(): Result {
        val m = CertificateManager
        if (!m.isInitialized) return Result(false, "CA 未初始化")

        val pem = m.caCertificatePem() ?: return Result(false, "读不到 CA 证书")
        val der = m.caCertificateDer() ?: return Result(false, "读不到 CA 证书")

        return runCatching {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "AidePro",
            )
            if (!dir.exists()) dir.mkdirs()

            val pemFile = File(dir, "AidePro-CA.crt")
            pemFile.writeBytes(pem)
            val derFile = File(dir, "AidePro-CA.der")
            derFile.writeBytes(der)

            Result(
                ok = true,
                message = "已导出到「下载/AidePro」目录：\n" +
                    "· AidePro-CA.crt（PEM，用于手动安装）\n" +
                    "· AidePro-CA.der（DER）",
                exportedPath = pemFile.absolutePath,
            )
        }.getOrElse {
            Result(false, "导出失败：${it.message}")
        }
    }

    // ==================== 安装到系统 ====================

    /**
     * 一键装到系统信任区。
     *
     * 【为什么要双写】
     * 1. 系统信任区在多数机器上是 tmpfs（内存盘），直接写能立即生效，
     *    但**重启后会丢失**。
     * 2. MoveCertificate 这类模块会在开机时把 MODULE_CERT_DIR 里的证书
     *    重新挂载进去 —— 所以往那里也放一份，就能持久化。
     *
     * 两处都写 = 立即能用 + 重启不丢。
     */
    fun installToSystem(): Result {
        val m = CertificateManager
        if (!m.isInitialized) return Result(false, "CA 未初始化")

        val der = m.caCertificateDer() ?: return Result(false, "读不到 CA 证书")
        val hash = subjectHash(m) ?: return Result(false, "算不出证书 hash")

        // 步骤 1：写到模块投放目录（重启后持久）
        val toModule = writeTemp(der, hash)

        // 步骤 2：检查 root
        if (!hasRoot()) {
            return if (toModule) {
                Result(
                    ok = true,
                    message = "已放到系统证书投放目录（${MODULE_CERT_DIR}）。\n\n" +
                        "未检测到 root 权限，无法立即生效。\n" +
                        "如果你装了 MoveCertificate 之类的证书模块，重启后即可生效；\n" +
                        "否则请先用「导出证书」手动安装。",
                    needReboot = true,
                )
            } else {
                Result(false, "写入失败，且未检测到 root 权限")
            }
        }

        // 步骤 3：root 可用，直接写系统信任区（立即生效）
        val immediate = runSu(
            "mkdir -p $SYSTEM_CERT_DIR && " +
                "cat > $SYSTEM_CERT_DIR/$hash.0 << 'EOF'\n" +
                String(der, Charsets.ISO_8859_1) + "\nEOF\n" +
                "chmod 644 $SYSTEM_CERT_DIR/$hash.0 && " +
                "chown root:root $SYSTEM_CERT_DIR/$hash.0",
        )

        // 步骤 4：顺便装到用户级（有 networkSecurityConfig 的应用也能认）
        runSu(
            "mkdir -p $USER_CERT_DIR && " +
                "cat > $USER_CERT_DIR/$hash.0 << 'EOF'\n" +
                String(der, Charsets.ISO_8859_1) + "\nEOF\n" +
                "chmod 644 $USER_CERT_DIR/$hash.0",
        )

        return if (immediate) {
            val persistHint = if (toModule) {
                "已同时写入模块目录，重启后依然有效。"
            } else {
                "注意：系统信任区在部分机型上是内存盘，重启可能丢失。\n" +
                    "建议安装 MoveCertificate 模块以获得持久效果。"
            }
            Result(
                ok = true,
                message = "证书已安装到系统信任区，立即生效。\n$persistHint",
                needReboot = false,
            )
        } else {
            Result(
                ok = false,
                message = "写入系统信任区失败。\n" +
                    "可能原因：SELinux 阻止、分区只读、su 未授权。\n" +
                    "请改用「导出证书」手动安装。",
            )
        }
    }

    /** 生成证书 hash（subject_hash_old）。 */
    private fun subjectHash(m: CertificateManager): String? = runCatching {
        val cert = m.caCertificate ?: return null
        // 用 MessageDigest 按 OpenSSL 的 subject_hash_old 算法算
        val enc = cert.subjectX500Principal.encoded
        val md5 = java.security.MessageDigest.getInstance("MD5").digest(enc)
        val v = ((md5[3].toInt() and 0xFF) shl 24) or
            ((md5[2].toInt() and 0xFF) shl 16) or
            ((md5[1].toInt() and 0xFF) shl 8) or
            (md5[0].toInt() and 0xFF)
        String.format("%08x", v)
    }.getOrNull()

    /** 写一份到模块投放目录。 */
    private fun writeTemp(der: ByteArray, hash: String): Boolean = runCatching {
        val dir = File(MODULE_CERT_DIR)
        if (!dir.exists()) dir.mkdirs()
        File(dir, "$hash.0").writeBytes(der)
        true
    }.getOrElse {
        Log.w(TAG, "写入模块目录失败: ${it.message}")
        false
    }

    /** 检查 root 是否可用。 */
    fun hasRoot(): Boolean = runCatching {
        val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out.contains("uid=0")
    }.getOrDefault(false)

    /** 以 root 执行命令。 */
    private fun runSu(cmd: String): Boolean {
        return try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            if (code != 0) Log.w(TAG, "su 执行失败($code): $out")
            code == 0
        } catch (t: Throwable) {
            Log.w(TAG, "su 调用异常: ${t.message}")
            false
        }
    }

    /** 检测证书是否已装进系统信任区。 */
    fun isInstalledInSystem(): Boolean {
        val m = CertificateManager
        val hash = subjectHash(m) ?: return false
        return File("$SYSTEM_CERT_DIR/$hash.0").exists()
    }
}
