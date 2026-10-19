package io.relimus.zflow.providers

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import io.relimus.zflow.room.DatabaseRepository

/**
 * 黑名单查询入口（供 launcher / 手势 hook 所在的进程调用）。
 *
 * 只提供只读的 [METHOD_IS_BLACKLISTED]，不暴露任何写入口：
 * 名单只能在 Z-Flow 自己的设置页里修改。
 */
class BlacklistProvider : ContentProvider() {

    companion object {
        private const val TAG = "BlacklistProvider"

        const val AUTHORITY = "io.relimus.zflow.blacklist.provider"
        const val METHOD_IS_BLACKLISTED = "is_blacklisted"
        /** 名单版本号，调用方可据此判断本地缓存是否过期 */
        const val METHOD_GET_VERSION = "get_version"

        const val EXTRA_PACKAGE_NAME = "packageName"
        const val EXTRA_USER_ID = "userId"
        const val EXTRA_RESULT = "result"
        const val EXTRA_VERSION = "version"

        const val KEY_BLACKLIST_VERSION = "blacklist_version"
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        return when (method) {
            METHOD_IS_BLACKLISTED -> {
                val ctx = context ?: return ProviderAccess.denied("context unavailable")
                val packageName = extras?.getString(EXTRA_PACKAGE_NAME).orEmpty()
                val userId = extras?.getInt(EXTRA_USER_ID, 0) ?: 0

                if (!isValidPackageName(packageName)) {
                    // 非法包名直接按“不在黑名单”处理，避免把脏数据带进数据库查询
                    return Bundle().apply { putBoolean(EXTRA_RESULT, false) }
                }

                val result = try {
                    DatabaseRepository(ctx).isBlacklisted(packageName, userId)
                } catch (e: Exception) {
                    Log.e(TAG, "is_blacklisted failed: pkg=$packageName userId=$userId", e)
                    return ProviderAccess.denied("database unavailable")
                }

                Bundle().apply { putBoolean(EXTRA_RESULT, result) }
            }

            METHOD_GET_VERSION -> {
                val ctx = context ?: return ProviderAccess.denied("context unavailable")
                val version = ctx.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
                    .getLong(KEY_BLACKLIST_VERSION, 0L)
                Bundle().apply {
                    putLong(EXTRA_VERSION, version)
                }
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
}
