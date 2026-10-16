package com.sfzsgybb.ai.vpn

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.system.OsConstants
import android.util.Log
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * 把 VPN 连接归属到具体应用。
 *
 * 【为什么需要】
 * VPN 是路由层接管，拿到的只有 IP 包 —— 不知道是哪个应用的流量。
 * 而 Hook 抓包天然知道包名（就在目标进程里）。
 * 结果就是 VPN 抓的数据包名为空，用户没法按应用筛选，只能在一大堆里翻。
 *
 * 【怎么归属】
 * Android 提供了 `ConnectivityManager.getConnectionOwnerUid(protocol, local, remote)`：
 * 传入本地/远端地址，返回发起这条连接的 uid。
 * 拿到 uid 后再用 PackageManager 查包名。
 *
 * 这是 ProxyPin 的做法（见其 ProcessInfoManager）。
 */
object UidResolver {

    private const val TAG = "UidResolver"

    /** uid → 包名缓存（包名基本不变，可以长缓存） */
    private val uidToPackage = ConcurrentHashMap<Int, String>()

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * 查一条连接属于哪个应用。
     *
     * @param localAddress  本机地址（VPN 虚拟网卡那侧）
     * @param remoteAddress 对端地址
     * @return 包名；查不到返回 null
     */
    fun resolvePackage(
        localAddress: InetSocketAddress,
        remoteAddress: InetSocketAddress,
    ): String? {
        val ctx = appContext ?: return null
        val uid = queryUid(ctx, localAddress, remoteAddress) ?: return null
        if (uid == Process.INVALID_UID || uid < 0) return null
        return uidToPackage.getOrPut(uid) {
            packageOfUid(ctx, uid) ?: ""
        }.ifBlank { null }
    }

    /** 调系统 API 查连接归属的 uid。 */
    private fun queryUid(
        ctx: Context,
        local: InetSocketAddress,
        remote: InetSocketAddress,
    ): Int? = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val uid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cm.getConnectionOwnerUid(OsConstants.IPPROTO_TCP, local, remote)
        } else {
            val m = ConnectivityManager::class.java.getMethod(
                "getConnectionOwnerUid",
                Int::class.javaPrimitiveType,
                InetSocketAddress::class.java,
                InetSocketAddress::class.java,
            )
            m.invoke(cm, OsConstants.IPPROTO_TCP, local, remote) as Int
        }
        if (uid == Process.INVALID_UID) null else uid
    } catch (t: Throwable) {
        Log.w(TAG, "getConnectionOwnerUid 失败: ${t.message}")
        null
    }

    /** uid → 包名。 */
    private fun packageOfUid(ctx: Context, uid: Int): String? = runCatching {
        val pm = ctx.packageManager
        val packages = pm.getPackagesForUid(uid) ?: return null
        packages.firstOrNull()
    }.getOrNull()

    /** 清缓存（卸载/安装应用后调用）。 */
    fun clearCache() {
        uidToPackage.clear()
    }
}
