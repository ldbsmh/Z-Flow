package io.relimus.zflow.providers

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.util.Log
import io.relimus.zflow.room.DatabaseRepository

/**
 * 把“小窗收藏应用”名单暴露给 Launcher 进程。
 *
 * 背景：轮盘需要知道要画哪几个应用，但名单存在 App 进程的数据库里，
 * 而轮盘跑在 Launcher 进程（那里才能拿到手势输入与图标），
 * 直接读对方的 SharedPreferences/数据库不可行，只能走 Provider。
 *
 * 安全边界与其它 Provider 一致：
 * - 只读，没有写入口；
 * - 调用方必须是本应用 / system / root，或 UID 属于受支持的 Launcher；
 * - 失败返回带 [ProviderAccess.EXTRA_DENIED] 的 Bundle，调用方据此区分
 *   「查询失败」与「用户确实没配置收藏」，不会把失败当成空名单去画一个空轮盘。
 */
class FreeformAppsProvider : ContentProvider() {

    companion object {
        private const val TAG = "FreeformAppsProvider"

        const val AUTHORITY = "io.relimus.zflow.freeformapps.provider"
        const val METHOD_GET_APPS = "get_freeform_apps"

        /**
         * 轮盘专用的应用名单（用户在「轮盘应用」页里挑的）。
         *
         * 与 [METHOD_GET_APPS] 分开：小窗收藏应用面向的是「哪些应用允许开小窗」，
         * 轮盘名单面向的是「手势弹出的那几个格子放谁」，两者的数量上限与
         * 使用场景都不一样，混在一起会让用户改一处、另一处跟着变。
         */
        const val METHOD_GET_RADIAL_APPS = "get_radial_apps"

        /** 输出 `packageName|userId` 字符串数组，顺序即收藏顺序。 */
        const val EXTRA_KEYS = "keys"

        /** 轮盘最多显示 6 格，读多了也没用；顺便给跨进程数据一个上限。 */
        const val MAX_ITEMS = 8

        private val LAUNCHER_PACKAGES = setOf(
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
        )
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        return when (method) {
            METHOD_GET_APPS -> {
                val ctx = context ?: return ProviderAccess.denied("context unavailable")
                if (!allowLauncherRead(ctx)) {
                    val uid = ProviderAccess.callingUid()
                    Log.w(TAG, "rejected read from uid=$uid")
                    return ProviderAccess.denied("uid $uid is not a supported launcher")
                }
                val keys = DatabaseRepository(ctx).getAllFreeFormKeysOrNull()
                    ?: return ProviderAccess.denied("database unavailable").also {
                        Log.w(TAG, "database read failed")
                    }
                Log.i(TAG, "serving ${keys.size} favorites")
                Bundle().apply {
                    putStringArray(
                        EXTRA_KEYS,
                        keys.take(MAX_ITEMS).toTypedArray(),
                    )
                }
            }

            METHOD_GET_RADIAL_APPS -> {
                val ctx = context ?: return ProviderAccess.denied("context unavailable")
                if (!allowLauncherRead(ctx)) {
                    val uid = ProviderAccess.callingUid()
                    Log.w(TAG, "rejected read from uid=$uid")
                    return ProviderAccess.denied("uid $uid is not a supported launcher")
                }
                val keys = runCatching { RadialAppsStore.read(ctx) }
                    .getOrElse {
                        Log.w(TAG, "radial apps read failed", it)
                        return ProviderAccess.denied("radial list unavailable")
                    }
                Bundle().apply {
                    putStringArray(EXTRA_KEYS, keys.take(RadialAppsStore.MAX_ITEMS).toTypedArray())
                }
            }

            else -> ProviderAccess.denied("unknown method")
        }
    }

    /**
     * Launcher 的 UID 与第三方应用一样是普通 app uid，无法用 uid 常量判断，
     * 只能反查该 UID 名下是否装了受支持的 Launcher 包名。
     */
    private fun allowLauncherRead(context: android.content.Context): Boolean {
        val uid = ProviderAccess.callingUid()
        if (uid == Process.myUid() || uid == Process.SYSTEM_UID || uid == Process.ROOT_UID) {
            return true
        }
        val packages = runCatching {
            context.packageManager.getPackagesForUid(uid)?.toSet().orEmpty()
        }.getOrDefault(emptySet())
        if (packages.isEmpty()) return false
        if (packages.any { it in LAUNCHER_PACKAGES }) return true

        /*
         * 兜底：调用方是不是当前默认桌面。
         *
         * OEM 桌面包名无法穷举（ColorOS/HyperOS/OriginOS 各有各的），
         * 但「谁是 HOME」永远只有一个答案，比维护包名白名单稳。
         * 仍然只放行桌面：轮盘只该被桌面进程读取。
         */
        val home = runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager
                .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo
                ?.packageName
        }.getOrNull()
        return home != null && home in packages
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
