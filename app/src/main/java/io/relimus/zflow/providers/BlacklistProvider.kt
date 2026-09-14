package io.relimus.zflow.providers

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import io.relimus.zflow.room.DatabaseRepository

class BlacklistProvider : ContentProvider() {

    companion object {
        private const val TAG = "BlacklistProvider"

        const val AUTHORITY = "io.relimus.zflow.blacklist.provider"
        const val METHOD_IS_BLACKLISTED = "is_blacklisted"

        const val EXTRA_PACKAGE_NAME = "packageName"
        const val EXTRA_USER_ID = "userId"
        const val EXTRA_RESULT = "result"
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        return when (method) {
            METHOD_IS_BLACKLISTED -> {
                val ctx = context
                val packageName = extras?.getString(EXTRA_PACKAGE_NAME).orEmpty()
                val userId = extras?.getInt(EXTRA_USER_ID, 0) ?: 0

                val result = if (ctx != null && packageName.isNotBlank()) {
                    try {
                        DatabaseRepository(ctx).isBlacklisted(packageName, userId)
                    } catch (e: Exception) {
                        Log.e(TAG, "is_blacklisted failed: pkg=$packageName userId=$userId", e)
                        false
                    }
                } else {
                    false
                }

                Bundle().apply {
                    putBoolean(EXTRA_RESULT, result)
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