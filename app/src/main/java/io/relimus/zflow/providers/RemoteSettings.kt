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

    /** 开关缓存稍长一些：热路径（如 Resources 取尺寸）会频繁命中 */
    private const val FLAG_CACHE_MS = 3000L

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private class CacheEntry(val value: Any?, val at: Long)

    fun clearCache() {
        cache.clear()
    }

    /**
     * 读取一个兼容性修复开关。
     *
     * 供运行在被 hook 应用进程里的补丁使用——那边读不到 Z-Flow 的
     * SharedPreferences，只能跨进程问 Provider。
     *
     * @param key 必须是 [NotificationSettingsProvider] 白名单内的 key
     * @param default 查询失败时的兜底值（保证不因为读不到设置而误改行为）
     */
    fun isFeatureEnabled(context: Context, key: String, default: Boolean): Boolean {
        val cacheKey = "flag:$key"
        val now = android.os.SystemClock.uptimeMillis()
        val cached = cache[cacheKey]
        if (cached != null && now - cached.at < FLAG_CACHE_MS) {
            return cached.value as? Boolean ?: default
        }

        val value = runCatching {
            val uri = Uri.parse("content://${NotificationSettingsProvider.AUTHORITY}")
            val extras = Bundle().apply {
                putString(NotificationSettingsProvider.EXTRA_FLAG_KEY, key)
            }
            context.contentResolver.call(
                uri,
                NotificationSettingsProvider.METHOD_GET_FEATURE_FLAG,
                null,
                extras
            )?.getBoolean(NotificationSettingsProvider.EXTRA_FLAG_VALUE, default)
        }.getOrNull() ?: default

        cache[cacheKey] = CacheEntry(value, now)
        return value
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
