package io.relimus.zflow.providers

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import io.relimus.zflow.room.DatabaseRepository

/**
 * 向其它进程（SystemUI）暴露“接管通知”的应用名单。
 *
 * 方案 A：Z-Flow 不再取消原通知，而是由 SystemUI 进程为勾选应用的通知行
 * 注入“打开小窗”按钮。SystemUI 进程通过本 Provider 查询哪些应用被勾选。
 */
class NotificationSettingsProvider : ContentProvider() {

    companion object {
        private const val TAG = "NotificationSettingsProvider"

        const val AUTHORITY = "io.relimus.zflow.notification.provider"
        const val METHOD_GET_ENABLED_APPS = "get_enabled_apps"
        const val METHOD_IS_FREEFORM_ENABLED = "is_freeform_enabled"
        const val METHOD_GET_MAX_FREEFORM = "get_max_freeform_windows"
        const val EXTRA_RESULT = "enabledApps"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_MAX = "maxFreeform"
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        return when (method) {
            METHOD_GET_ENABLED_APPS -> {
                val ctx = context
                val apps = if (ctx != null) {
                    try {
                        DatabaseRepository(ctx).getAllNotificationPackageNames()
                            ?.toTypedArray()
                    } catch (e: Exception) {
                        Log.e(TAG, "get_enabled_apps failed", e)
                        null
                    }
                } else {
                    null
                }

                Bundle().apply {
                    putStringArray(EXTRA_RESULT, apps)
                }
            }

            METHOD_IS_FREEFORM_ENABLED -> {
                val ctx = context
                val enabled = ctx?.let {
                    it.getSharedPreferences(
                        "app_settings",
                        android.content.Context.MODE_PRIVATE
                    ).getBoolean("notify_freeform", true)
                } ?: false

                Bundle().apply {
                    putBoolean(EXTRA_ENABLED, enabled)
                }
            }

            METHOD_GET_MAX_FREEFORM -> {
                val ctx = context
                val max = ctx?.let {
                    it.getSharedPreferences(
                        "app_settings",
                        android.content.Context.MODE_PRIVATE
                    ).getInt("max_freeform_windows", 2).coerceIn(1, 5)
                } ?: 2

                Bundle().apply {
                    putInt(EXTRA_MAX, max)
                }
            }

            else -> {
                Log.w(TAG, "Unknown method: $method")
                Bundle.EMPTY
            }
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        // 与 call() 等价：返回包名列表（用于不支持 call 的客户端）
        val ctx = context ?: return null
        val apps = runCatching {
            DatabaseRepository(ctx).getAllNotificationPackageNames()
        }.getOrNull().orEmpty()

        val cursor = MatrixCursor(arrayOf("packageName"))
        apps.forEach { cursor.addRow(arrayOf(it)) }
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
