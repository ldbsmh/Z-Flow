package io.relimus.zflow.providers

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

class LandscapeAppsProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "io.relimus.zflow.landscape.provider"
        const val METHOD_IS_LANDSCAPE = "is_landscape_app"
        const val METHOD_GET_ALL = "get_all"
        const val METHOD_ADD = "add"
        const val METHOD_REMOVE = "remove"
        const val METHOD_CLEAR_ALL = "clear_all"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_PACKAGE_LIST = "package_list"
        const val EXTRA_RESULT = "result"
        private const val PREFS_NAME = "landscape_apps"
    }

    private val prefs by lazy {
        context!!.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        return when (method) {
            METHOD_IS_LANDSCAPE -> {
                val pkg = extras?.getString(EXTRA_PACKAGE_NAME) ?: ""
                Bundle().apply {
                    putBoolean(EXTRA_RESULT, pkg.isNotEmpty() && prefs.getBoolean(pkg, false))
                }
            }
            METHOD_GET_ALL -> {
                Bundle().apply {
                    putStringArrayList(EXTRA_PACKAGE_LIST, ArrayList(prefs.all.keys))
                }
            }
            METHOD_ADD -> {
                extras?.getString(EXTRA_PACKAGE_NAME)?.let {
                    prefs.edit().putBoolean(it, true).apply()
                }
                null
            }
            METHOD_REMOVE -> {
                extras?.getString(EXTRA_PACKAGE_NAME)?.let {
                    prefs.edit().remove(it).apply()
                }
                null
            }
            METHOD_CLEAR_ALL -> {
                prefs.edit().clear().apply()
                null
            }
            else -> null
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