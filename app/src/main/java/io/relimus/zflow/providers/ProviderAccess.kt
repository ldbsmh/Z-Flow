package io.relimus.zflow.providers

import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log

/**
 * 跨进程调用的身份校验工具。
 *
 * 背景：本模块的 Provider 必须被 launcher / SystemUI / 被 hook 的应用进程读取，
 * 这些进程的 UID 各不相同，无法用单一的 `android:permission` 表达，
 * 也不能简单地 exported=false。这里的策略是：
 *  1. 读方法放行所有进程（被 hook 的应用需要判断自己是否在黑名单/横屏名单中）；
 *  2. 写方法只允许本应用、system_server、root 发起；
 *  3. 失败时返回带错误标记的 Bundle，调用方可区分“未授权”和“没有配置”。
 */
internal object ProviderAccess {

    private const val TAG = "ProviderAccess"

    fun callingUid(): Int = Binder.getCallingUid()

    /** 写方法（修改配置）的入口校验。被 hook 的第三方进程只能读，不能写。 */
    fun allowWrite(provider: String, method: String): Boolean {
        val uid = callingUid()
        val allowed = uid == Process.SYSTEM_UID ||
                uid == Process.ROOT_UID ||
                uid == Process.myUid()
        if (!allowed) {
            Log.w(TAG, "$provider: rejected write '$method' from uid=$uid")
        }
        return allowed
    }

    /** 统一的失败返回：调用方必须检查 [EXTRA_DENIED]，不能把失败当成“无配置”。 */
    fun denied(reason: String): Bundle = Bundle().apply {
        putBoolean(EXTRA_DENIED, true)
        putString(EXTRA_REASON, reason)
    }

    /** 调用方用来判断返回的 Bundle 是“失败”还是“真的没配置”。 */
    fun isDenied(bundle: Bundle?): Boolean =
        bundle != null && bundle.getBoolean(EXTRA_DENIED, false)

    const val EXTRA_DENIED = "denied"
    const val EXTRA_REASON = "reason"
}

/** 包名基本校验，避免把任意字符串当成配置 key 写进 SharedPreferences。 */
internal fun isValidPackageName(packageName: String?): Boolean {
    if (packageName.isNullOrEmpty() || packageName.length > 200) return false
    return packageName.split('.').all { part ->
        part.isNotEmpty() &&
                part.first().isLetter() &&
                part.all { it.isLetterOrDigit() || it == '_' }
    }
}
