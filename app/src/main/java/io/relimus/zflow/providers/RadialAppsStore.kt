package io.relimus.zflow.providers

import android.content.Context
import io.relimus.zflow.app.ZFlow

/**
 * 轮盘里显示的应用名单（用户在「轮盘应用」页里自己挑的那份）。
 *
 * 为什么不用 Room：轮盘跑在 Launcher 进程，只能通过只读 Provider 拿数据；
 * 这份名单最多 [MAX_ITEMS] 条、没有关联查询、顺序即显示顺序，
 * 用一条有序的字符串塞进 SharedPreferences 最省事，也不会因为迁移
 * 数据库版本而牵连主业务表。
 *
 * 键格式与其它名单保持一致：`packageName|userId`，这样跨用户的应用也能区分。
 */
object RadialAppsStore {

    /**
     * 与 [io.relimus.zflow.xposed.ui.radial.RadialLayoutSpec.MAX_ITEMS] 对齐。
     *
     * 13 = 三环 3+4+6，正好是轮盘能干净排下的极限：再多一格就必须缩圆盘，
     * 白色按钮会小到看不清图标。
     */
    const val MAX_ITEMS = 13

    private const val KEY = "radial_apps"
    private const val SEPARATOR = "\n"

    fun read(context: Context): List<String> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return raw.split(SEPARATOR)
            .map { it.trim() }
            .filter { isValidPackageKey(it) }
            .distinct()
    }

    /** 覆盖写入；超出上限的部分直接丢掉，避免存进去却画不出来。 */
    fun write(context: Context, keys: List<String>) {
        val normalized = keys.filter { isValidPackageKey(it) }
            .distinct()
            .take(MAX_ITEMS)
        prefs(context).edit().putString(KEY, normalized.joinToString(SEPARATOR)).apply()
    }

    /**
     * 追加一条。
     * @return true 表示真的写进去了；已存在或超过上限返回 false，调用方据此提示。
     */
    fun add(context: Context, key: String): Boolean {
        if (!isValidPackageKey(key)) return false
        val current = read(context)
        if (key in current) return true
        if (current.size >= MAX_ITEMS) return false
        write(context, current + key)
        return true
    }

    fun remove(context: Context, key: String) {
        val current = read(context)
        if (key !in current) return
        write(context, current - key)
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }

    /** 与其它名单一致：`packageName|userId`，userId 允许为 -1（不分用户）。 */
    fun keyOf(packageName: String, userId: Int): String = "$packageName|$userId"

    private fun isValidPackageKey(key: String): Boolean {
        val parts = key.split('|')
        if (parts.size != 2) return false
        if (!isValidPackageName(parts[0])) return false
        return parts[1].toIntOrNull() != null
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(ZFlow.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
}
