package io.relimus.zflow.providers

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import io.relimus.zflow.app.ZFlow
import io.relimus.zflow.room.DatabaseRepository

/**
 * 向其它进程（SystemUI 等）暴露“接管通知”的应用名单与少量开关。
 *
 * 安全策略：
 * - 全部方法只读，没有写入口；
 * - 开关通过 [ALLOWED_FEATURE_FLAGS] 白名单收敛，不把任意设置项当查询接口；
 * - 失败时返回带 [ProviderAccess.EXTRA_DENIED] 的 Bundle，调用方可以据此
 *   区分“查询失败”和“确实为空”，而不是误以为用户关掉了全部接管。
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

        /** 通知名单版本号：每次事务写入后自增（见 DatabaseRepository.bumpVersion） */
        const val METHOD_GET_APP_LIST_VERSION = "get_app_list_version"
        const val EXTRA_VERSION = "version"

        /** 读取兼容性修复开关：extras 传 key，返回 flag_value */
        const val METHOD_GET_FEATURE_FLAG = "get_feature_flag"
        const val EXTRA_FLAG_KEY = "flag_key"
        const val EXTRA_FLAG_VALUE = "flag_value"

        private const val PREFS_NAME = "app_settings"
        private const val KEY_NOTIFY_FREEFORM = "notify_freeform"
        private const val KEY_MAX_FREEFORM = "max_freeform_windows"
        private const val DEFAULT_MAX_FREEFORM = 2
        private const val MAX_FREEFORM_UPPER_BOUND = 5
        const val KEY_APP_LIST_VERSION = "notification_apps_version"

        /**
         * 允许跨进程读取的开关白名单。
         * 本 Provider 是 exported 的，必须显式限制，
         * 否则等于把任意设置项暴露给其它进程。
         */
        private val ALLOWED_FEATURE_FLAGS = setOf(
            ZFlow.KEY_POPUP_FREEFORM_ENABLE,
            ZFlow.KEY_POPUP_BUBBLE_BLOCK,
        )
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        return when (method) {
            METHOD_GET_ENABLED_APPS -> {
                val ctx = context ?: return ProviderAccess.denied("context unavailable")
                if (!ProviderAccess.allowBulkRead(ctx, allowSystemUi = true)) {
                    return ProviderAccess.denied("read not allowed")
                }
                // null = 查询失败（数据库不可用），必须与“用户没配置任何应用”（空数组）区分：
                // 前者返回 denied，让 SystemUI 侧保持原状/重试，而不是把通知接管全部关掉。
                val apps = DatabaseRepository(ctx).getAllNotificationPackageNames()
                    ?: return ProviderAccess.denied("database unavailable")

                Bundle().apply {
                    putStringArray(EXTRA_RESULT, apps.toTypedArray())
                }
            }

            METHOD_IS_FREEFORM_ENABLED -> {
                val ctx = context ?: return ProviderAccess.denied("context unavailable")
                val enabled = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY_NOTIFY_FREEFORM, true)

                Bundle().apply { putBoolean(EXTRA_ENABLED, enabled) }
            }

            METHOD_GET_MAX_FREEFORM -> {
                val ctx = context ?: return ProviderAccess.denied("context unavailable")
                val max = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getInt(KEY_MAX_FREEFORM, DEFAULT_MAX_FREEFORM)
                    .coerceIn(1, MAX_FREEFORM_UPPER_BOUND)

                Bundle().apply { putInt(EXTRA_MAX, max) }
            }

            METHOD_GET_FEATURE_FLAG -> {
                val key = extras?.getString(EXTRA_FLAG_KEY).orEmpty()
                if (key !in ALLOWED_FEATURE_FLAGS) {
                    Log.w(TAG, "feature flag not allowed: $key")
                    return ProviderAccess.denied("flag not allowed")
                }
                val ctx = context ?: return ProviderAccess.denied("context unavailable")
                val enabled = ctx.getSharedPreferences(ZFlow.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(key, true)

                Bundle().apply { putBoolean(EXTRA_FLAG_VALUE, enabled) }
            }

            METHOD_GET_APP_LIST_VERSION -> {
                val ctx = context ?: return ProviderAccess.denied("context unavailable")
                val version = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getLong(KEY_APP_LIST_VERSION, 0L)
                Bundle().apply { putLong(EXTRA_VERSION, version) }
            }

            else -> {
                Log.w(TAG, "Unknown method: $method")
                ProviderAccess.denied("unknown method")
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
        if (!ProviderAccess.allowBulkRead(ctx, allowSystemUi = true)) {
            throw SecurityException("Notification list read not allowed")
        }
        val entries = DatabaseRepository(ctx).getAllNotificationEntries() ?: return null

        // userId 一并返回：名单按 (packageName, userId) 存，缺失 userId 会让
        // 工作资料里的同名应用与主用户混淆。
        val cursor = MatrixCursor(arrayOf("packageName", "userId"))
        entries.forEach { entry ->
            cursor.addRow(arrayOf<Any>(entry.packageName, entry.userId))
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
