package io.relimus.zflow.providers

import android.content.Context
import android.net.Uri
import android.os.Bundle
import java.util.concurrent.ConcurrentHashMap

/**
 * 供其它进程（SystemUI / Launcher）读取 Z-Flow 设置的工具。
 *
 * 通过 ContentProvider 跨进程读取，并带短缓存避免每次回调都走 Binder。
 * 借鉴 MoreBubbleButton 的 ModuleSettings 远程缓存设计。
 */
object RemoteSettings {

    private const val CACHE_MS = 250L

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private class CacheEntry(val value: Any?, val at: Long)

    fun clearCache() {
        cache.clear()
    }

    /**
     * 读取“通知小窗”总开关（notify_freeform）。
     * 关掉时通知上不显示小窗按钮。
     */
    fun isNotificationFreeformEnabled(context: Context): Boolean {
        return runCatching {
            val uri = Uri.parse(
                "content://${NotificationSettingsProvider.AUTHORITY}"
            )
            val bundle = context.contentResolver.call(
                uri,
                NotificationSettingsProvider.METHOD_IS_FREEFORM_ENABLED,
                null,
                Bundle()
            )
            bundle?.getBoolean(NotificationSettingsProvider.EXTRA_ENABLED, false) ?: false
        }.getOrDefault(false)
    }

    /**
     * 读取被勾选“接管通知”的应用包名集合。
     * @return 勾选应用包名 Set；查询失败时返回 null（调用方需决定默认值）。
     */
    fun getNotificationEnabledApps(context: Context): Set<String>? {
        val key = "notification_enabled_apps"
        val cached = cache[key]
        val now = android.os.SystemClock.uptimeMillis()
        if (cached != null && now - cached.at < CACHE_MS) {
            @Suppress("UNCHECKED_CAST")
            return cached.value as? Set<String>
        }

        val result = runCatching {
            val uri = Uri.parse(
                "content://${NotificationSettingsProvider.AUTHORITY}"
            )
            val bundle = context.contentResolver.call(
                uri,
                NotificationSettingsProvider.METHOD_GET_ENABLED_APPS,
                null,
                Bundle()
            )
            val apps = bundle?.getStringArray(
                NotificationSettingsProvider.EXTRA_RESULT
            )?.toSet()
            apps
        }.getOrNull()

        cache[key] = CacheEntry(result, now)
        return result
    }

    /**
     * 判断某个包名是否被勾选“接管通知”。
     * 查询失败时返回 false（不接管），保证兜底安全。
     */
    fun isNotificationEnabled(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val apps = getNotificationEnabledApps(context) ?: return false
        return apps.contains(packageName)
    }
}
