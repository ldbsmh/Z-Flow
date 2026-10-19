package io.relimus.zflow.providers

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap

/**
 * 供其它进程（SystemUI / Launcher / 被 hook 应用）读取 Z-Flow 设置的工具。
 *
 * 通过 ContentProvider 跨进程读取，并带短缓存避免每次回调都走 Binder。
 * 所有读取都返回“安全默认值”而不是异常，保证读不到设置时不会误改被 hook 应用的行为。
 */
object RemoteSettings {

    private const val CACHE_MS = 250L

    /** 开关缓存稍长一些：热路径（如 Resources 取尺寸）会频繁命中 */
    private const val FLAG_CACHE_MS = 3000L

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val appKeyCache = ConcurrentHashMap<String, AppKeyCacheEntry>()
    private var observerRegistered = false

    private class CacheEntry(val value: Any?, val at: Long)

    /**
     * 读取黑名单版本号。调用方（含被 hook 的应用进程）可自行与上次记录的版本比对，
     * 版本变化就丢弃本地缓存的“是否黑名单”结果。
     */
    fun fetchBlacklistVersion(context: Context): Long? =
        fetchVersion(context, BlacklistProvider.METHOD_GET_VERSION, BlacklistProvider.AUTHORITY)

    private object VERSION_KEY {
        const val notification = "version:notification"
    }
    private class AppKeyCacheEntry(val key: Boolean, val at: Long)

    fun clearCache() {
        cache.clear()
        appKeyCache.clear()
    }

    /**
     * 注册通知名单变化监听：设置页修改名单后，SystemUI 进程能立刻清缓存，
     * 不需要等 250ms 的 TTL 自然过期。
     */
    fun registerInvalidationObserver(context: Context) {
        if (observerRegistered) return
        observerRegistered = true
        runCatching {
            context.contentResolver.registerContentObserver(
                Uri.parse("content://${NotificationSettingsProvider.AUTHORITY}"),
                false,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        clearCache()
                    }
                }
            )
        }
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
        cache[cacheKey]?.let { cached ->
            if (now - cached.at < FLAG_CACHE_MS) return cached.value as? Boolean ?: default
        }

        val value = runCatching {
            val result = call(
                context,
                NotificationSettingsProvider.METHOD_GET_FEATURE_FLAG,
                Bundle().apply { putString(NotificationSettingsProvider.EXTRA_FLAG_KEY, key) }
            )
            if (result.isDenied()) null
            else result?.getBoolean(NotificationSettingsProvider.EXTRA_FLAG_VALUE, default)
        }.getOrNull() ?: default

        cache[cacheKey] = CacheEntry(value, now)
        return value
    }

    /**
     * 读取“通知小窗”总开关（notify_freeform）。
     * 关掉时通知上不显示小窗按钮。
     */
    fun isNotificationFreeformEnabled(context: Context): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        val cacheKey = "notify_freeform"
        cache[cacheKey]?.let { cached ->
            if (now - cached.at < CACHE_MS) return cached.value as? Boolean ?: false
        }

        val value = runCatching {
            val result = call(context, NotificationSettingsProvider.METHOD_IS_FREEFORM_ENABLED, null)
            if (result.isDenied()) null
            else result?.getBoolean(NotificationSettingsProvider.EXTRA_ENABLED, false)
        }.getOrNull() ?: false

        cache[cacheKey] = CacheEntry(value, now)
        return value
    }

    /**
     * 读取被勾选“接管通知”的应用包名集合。
     * @return 勾选应用包名 Set；查询失败（Provider 不可用 / 被拒绝）时返回 null。
     */
    fun getNotificationEnabledApps(context: Context): Set<String>? {
        val key = "notification_enabled_apps"
        val cached = cache[key]
        val now = android.os.SystemClock.uptimeMillis()
        if (cached != null && now - cached.at < CACHE_MS) {
            @Suppress("UNCHECKED_CAST")
            return cached.value as? Set<String>
        }

        // 名单在设置页被修改时版本号会变化，这里先比对版本号，
        // 变化则立即丢弃缓存，而不是继续等 TTL 过期。
        val version = fetchVersion(context, NotificationSettingsProvider.METHOD_GET_APP_LIST_VERSION)
        val cachedVersion = cache[VERSION_KEY.notification]?.value as? Long
        if (version != null && cachedVersion != null && version != cachedVersion) {
            cache.remove(key)
            appKeyCache.clear()
        }
        if (version != null) {
            cache[VERSION_KEY.notification] = CacheEntry(version, now)
        }

        val result = runCatching {
            val bundle = call(context, NotificationSettingsProvider.METHOD_GET_ENABLED_APPS, null)
            if (bundle == null || bundle.isDenied()) {
                null
            } else {
                bundle.getStringArray(NotificationSettingsProvider.EXTRA_RESULT)?.toSet()
            }
        }.getOrNull()

        // 失败结果不缓存，避免一次瞬时异常把“不接管”状态锁定 250ms
        if (result != null) {
            cache[key] = CacheEntry(result, now)
        }
        return result
    }

    /**
     * 高频路径使用的单包名判断：
     * 同一包名在缓存窗口内只做一次 Set 查询，避免每次手势都复制整个集合。
     * 查询失败时返回 false（不接管），保证兜底安全。
     */
    fun isNotificationEnabled(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val now = android.os.SystemClock.uptimeMillis()
        appKeyCache[packageName]?.let { entry ->
            if (now - entry.at < CACHE_MS) return entry.key
        }

        val enabled = getNotificationEnabledApps(context)?.contains(packageName) ?: false
        appKeyCache[packageName] = AppKeyCacheEntry(enabled, now)
        return enabled
    }

    /** 读取名单版本号；查询失败返回 null（退化为 TTL 缓存）。 */
    private fun fetchVersion(
        context: Context,
        method: String,
        authority: String? = null
    ): Long? = runCatching {
        val bundle = call(context, method, null, authority)
        if (bundle == null || bundle.isDenied()) null
        else bundle.getLong(NotificationSettingsProvider.EXTRA_VERSION, 0L)
    }.getOrNull()

    /**
     * @param authority 目标 Provider；版本号查询会命中不同 authority，
     *                  写死 notification 的 authority 会让横屏/黑名单缓存永远拿不到版本。
     */
    private fun call(
        context: Context,
        method: String,
        extras: Bundle?,
        authority: String? = null
    ): Bundle? = context.contentResolver.call(
        Uri.parse("content://${authority ?: NotificationSettingsProvider.AUTHORITY}"),
        method,
        null,
        extras
    )

    private fun Bundle?.isDenied(): Boolean = this?.getBoolean(ProviderAccess.EXTRA_DENIED, false) == true
}
