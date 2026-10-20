package io.relimus.zflow.providers

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log

/**
 * 横屏应用名单。
 *
 * 调用方：
 * - 本应用设置页 / 选择页：读 + 写（同进程，UID 校验直接放行）
 * - 被 hook 的应用进程（HookSwipeGesture）：只读
 *
 * 写操作在 [ProviderAccess.allowWrite] 中限制为本应用 / system / root，
 * 被 hook 的第三方进程无法修改或清空用户的横屏配置。
 */
class LandscapeAppsProvider : ContentProvider() {

    companion object {
        private const val TAG = "LandscapeAppsProvider"

        const val AUTHORITY = "io.relimus.zflow.landscape.provider"
        const val METHOD_IS_LANDSCAPE = "is_landscape_app"
        const val METHOD_GET_ALL = "get_all"
        const val METHOD_ADD = "add"
        const val METHOD_REMOVE = "remove"
        const val METHOD_CLEAR_ALL = "clear_all"

        /**
         * 批量整体替换：一次 IPC、一次 commit、只推进一次版本号。
         * 全选 / 恢复备份这类操作必须走这里，逐条 ADD 会导致
         * N 次跨进程调用 + N 次缓存失效，中途失败还会留下半份名单。
         */
        const val METHOD_REPLACE_ALL = "replace_all"
        /** 名单版本号：每次写入自增，供 system_server 侧缓存失效判断。 */
        const val METHOD_GET_VERSION = "get_version"
        const val EXTRA_VERSION = "version"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_PACKAGE_LIST = "package_list"
        const val EXTRA_RESULT = "result"
        private const val PREFS_NAME = "landscape_apps"
        private const val KEY_VERSION = "__version__"
    }

    private val prefs by lazy {
        context!!.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun notifyListChanged() {
        runCatching {
            context?.contentResolver?.notifyChange(Uri.parse("content://$AUTHORITY"), null)
        }.onFailure { Log.w(TAG, "List invalidation notification failed", it) }
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        return when (method) {
            METHOD_IS_LANDSCAPE -> {
                val pkg = extras?.getString(EXTRA_PACKAGE_NAME).orEmpty()
                Bundle().apply {
                    putBoolean(EXTRA_RESULT, pkg.isNotEmpty() && prefs.getBoolean(pkg, false))
                }
            }
            METHOD_GET_ALL -> {
                val ctx = context ?: return ProviderAccess.denied("context unavailable")
                if (!ProviderAccess.allowBulkRead(ctx)) {
                    return ProviderAccess.denied("read not allowed")
                }
                Bundle().apply {
                    putStringArrayList(EXTRA_PACKAGE_LIST, ArrayList(prefs.all.filter {
                        isValidPackageName(it.key) && it.value == true
                    }.keys))
                }
            }
            METHOD_GET_VERSION -> Bundle().apply {
                putLong(EXTRA_VERSION, prefs.getLong(KEY_VERSION, 0L))
            }
            METHOD_ADD -> {
                if (!ProviderAccess.allowWrite(TAG, method)) {
                    return ProviderAccess.denied("write not allowed")
                }
                val pkg = extras?.getString(EXTRA_PACKAGE_NAME)
                if (!isValidPackageName(pkg)) {
                    return ProviderAccess.denied("invalid package name")
                }
                prefs.edit().putBoolean(pkg, true).putLong(KEY_VERSION, prefs.getLong(KEY_VERSION, 0L) + 1).apply()
                notifyListChanged()
                Bundle().apply { putBoolean(EXTRA_RESULT, true) }
            }
            METHOD_REMOVE -> {
                if (!ProviderAccess.allowWrite(TAG, method)) {
                    return ProviderAccess.denied("write not allowed")
                }
                val pkg = extras?.getString(EXTRA_PACKAGE_NAME)
                if (!isValidPackageName(pkg)) {
                    return ProviderAccess.denied("invalid package name")
                }
                prefs.edit().remove(pkg).putLong(KEY_VERSION, prefs.getLong(KEY_VERSION, 0L) + 1).apply()
                notifyListChanged()
                Bundle().apply { putBoolean(EXTRA_RESULT, true) }
            }
            METHOD_REPLACE_ALL -> {
                if (!ProviderAccess.allowWrite(TAG, method)) {
                    return ProviderAccess.denied("write not allowed")
                }
                val list = extras?.getStringArrayList(EXTRA_PACKAGE_LIST).orEmpty()
                val editor = prefs.edit()
                // 整体替换：先清空旧名单，再一次性写入新名单，只 commit 一次。
                editor.clear()
                var accepted = 0
                for (pkg in list) {
                    if (!isValidPackageName(pkg)) continue
                    editor.putBoolean(pkg, true)
                    accepted++
                }
                editor.putLong(KEY_VERSION, prefs.getLong(KEY_VERSION, 0L) + 1)
                if (!editor.commit()) {
                    return ProviderAccess.denied("commit failed")
                }
                Bundle().apply {
                    putBoolean(EXTRA_RESULT, true)
                    putInt("accepted", accepted)
                }
            }
            METHOD_CLEAR_ALL -> {
                if (!ProviderAccess.allowWrite(TAG, method)) {
                    return ProviderAccess.denied("write not allowed")
                }
                val version = prefs.getLong(KEY_VERSION, 0L) + 1
                prefs.edit().clear().putLong(KEY_VERSION, version).apply()
                notifyListChanged()
                Bundle().apply { putBoolean(EXTRA_RESULT, true) }
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
