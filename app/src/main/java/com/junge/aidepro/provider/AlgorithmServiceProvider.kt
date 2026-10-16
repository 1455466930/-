package com.junge.aidepro.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log
import com.junge.aidepro.AideProApp
import com.junge.aidepro.ipc.AlgorithmServiceHost

/**
 * 服务发现 Provider。
 *
 * 【作用】
 * 被 Hook 进程无法直接拿到宿主的 Binder（没有绑定 Service 的权限/时机），
 * 因此先用 ContentResolver.call() 访问本 Provider，
 * Provider 把宿主侧的 Binder 对象塞进 Bundle 返回，
 * 之后双方走 AIDL 直连。
 *
 * 这是原版 com.junge.algorithmAidePro.provider.AlgorithmServiceProvider 的做法，
 * 逐条对齐其关键设计：
 *   1. Binder.getCallingUid() 取调用方 uid
 *   2. uid 白名单校验，不通过则 Log.w 并返回 null
 *   3. 通过后 Bundle.putBinder(KEY_SERVICE, binder) 返回
 *
 * 【安全】
 * 只暴露 Binder 句柄，不暴露任何数据。
 * 真正的数据访问在 AIDL 层还有一道 uid 校验。
 */
class AlgorithmServiceProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        Log.i(TAG, "AlgorithmServiceProvider 已创建")
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val callingUid = Binder.getCallingUid()
        Log.i(TAG, "call(method=$method, arg=$arg, callingUid=$callingUid)")

        val host = hostOrNull()
        if (host == null) {
            Log.w(TAG, "宿主未就绪，拒绝 $method")
            return null
        }

        if (!host.isUidAllowed(callingUid)) {
            Log.w(TAG, "Rejected $method from uid=$callingUid")
            return null
        }

        return when (method) {
            METHOD_GET_SERVICE -> Bundle().apply {
                putBinder(AlgorithmServiceHost.KEY_SERVICE, host.serviceBinder.asBinder())
            }

            METHOD_GET_LOG_MANAGER -> Bundle().apply {
                putBinder(AlgorithmServiceHost.KEY_LOG_MANAGER, host.logBinder.asBinder())
            }

            /** 一次性返回两个句柄，减少往返。 */
            METHOD_GET_ALL -> Bundle().apply {
                putBinder(AlgorithmServiceHost.KEY_SERVICE, host.serviceBinder.asBinder())
                putBinder(AlgorithmServiceHost.KEY_LOG_MANAGER, host.logBinder.asBinder())
                putInt(KEY_VERSION, AlgorithmServiceHost.PROTOCOL_VERSION)
            }

            METHOD_PING -> Bundle().apply {
                putBoolean(KEY_OK, true)
                putInt(KEY_VERSION, AlgorithmServiceHost.PROTOCOL_VERSION)
            }

            else -> {
                Log.w(TAG, "未知方法: $method")
                null
            }
        }
    }

    private fun hostOrNull(): AlgorithmServiceHost? =
        (context?.applicationContext as? AideProApp)?.serviceHost

    // ---------- 以下接口不使用，但必须实现 ----------

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        private const val TAG = "AlgorithmServiceProvider"

        const val AUTHORITY = "com.junge.aidepro.ipc"

        /** 与原版同名的方法常量，便于对照。 */
        const val METHOD_GET_SERVICE = "getAlgorithmService"
        const val METHOD_GET_LOG_MANAGER = "getLogManager"
        const val METHOD_GET_ALL = "getAll"
        const val METHOD_PING = "ping"

        const val KEY_OK = "ok"
        const val KEY_VERSION = "version"
    }
}
