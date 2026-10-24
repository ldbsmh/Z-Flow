package io.relimus.zflow.security

import android.content.Context
import io.relimus.zflow.app.ZFlow

/**
 * 受信任外部调用者白名单：允许普通第三方应用（如手势工具、自动化工具）通过
 * [RequestAuthProvider] 领取小窗启动能力 token。
 *
 * - 名单只存包名，存在应用进程私有的 SharedPreferences 里；除 Z-Flow 自身进程外
 *   无人可读，因此不需要跨进程 Provider 同步。
 * - 校验时把 Binder UID 反查回当前包名再比对（[isWhitelisted]），不信任广播或
 *   extras 里的任何可伪造字段。包名被卸载后 UID 即使被系统回收给新应用，新包名
 *   不在名单内，也不会继承信任。
 * - 与 [CapabilityPolicy] 的区别：那份是"内核 UID 即身份"的静态信任判定，
 *   这份是用户在设置页显式授予的动态授权。
 */
object TrustedCallers {

    private const val PREF_KEY = "trusted_external_callers"

    fun read(context: Context): Set<String> = parse(
        prefs(context).getString(PREF_KEY, null)
    )

    fun isWhitelisted(context: Context, uid: Int): Boolean {
        if (uid < 0) return false
        val whitelist = read(context)
        if (whitelist.isEmpty()) return false
        val packages = context.packageManager.getPackagesForUid(uid) ?: return false
        return packages.any { it in whitelist }
    }

    fun add(context: Context, packageName: String) = write(context) { it + packageName }

    fun remove(context: Context, packageName: String) = write(context) { it - packageName }

    fun replace(context: Context, packages: Collection<String>) = write(context) {
        parse(serialize(packages))
    }

    fun clear(context: Context) = write(context) { emptySet() }

    internal fun parse(serialized: String?): Set<String> = serialized
        ?.split('\n')
        ?.map(String::trim)
        ?.filter(String::isNotBlank)
        ?.toSet()
        ?: emptySet()

    internal fun serialize(values: Collection<String>): String =
        values.map(String::trim).filter(String::isNotBlank).distinct().joinToString("\n")

    private fun write(context: Context, mutate: (Set<String>) -> Set<String>) {
        prefs(context).edit().putString(PREF_KEY, serialize(mutate(read(context)))).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(ZFlow.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
}
