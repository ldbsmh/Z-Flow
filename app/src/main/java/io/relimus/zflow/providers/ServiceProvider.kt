package io.relimus.zflow.providers

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import io.relimus.zflow.service.FreeformManagerProxy

/**
 * 接收 system_server 投递过来的「小窗后端」binder。
 *
 * 新后端的 binder 是 [io.relimus.zflow.xposed.nativewin.OpenPort]：
 * 只负责打开 / 关闭 / 状态查询，窗口由 system_server 直接把真实任务接管成 freeform 小窗。
 */
class ServiceProvider : ContentProvider() {
    companion object {
        private const val TAG = "ZFlowServiceProvider"
    }

    override fun onCreate(): Boolean = false

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        // 只接受 system_server 的投递
        if (callingPackage != "android" || extras == null) {
            Log.w(TAG, "Rejected call from package: $callingPackage")
            return null
        }

        val binder = extras.getBinder("binder")
        if (binder == null) {
            Log.e(TAG, "Binder not found in extras")
            return null
        }

        FreeformManagerProxy.linkService(binder)
        Log.d(TAG, "window backend binder linked")
        return Bundle()
    }
}
