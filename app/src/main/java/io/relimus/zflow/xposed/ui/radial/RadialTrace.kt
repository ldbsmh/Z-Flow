package io.relimus.zflow.xposed.ui.radial

import android.util.Log
import de.robv.android.xposed.XposedBridge

/**
 * 轮盘诊断日志。
 *
 * **默认关闭**。轮盘的每条失败路径都是「静默退回原本手势」，排查时确实需要日志；
 * 但它跑在输入回调里，同步写 LSPosed 日志（`XposedBridge.log` 是文件 IO），
 * 一次手势十几条，日常使用纯属浪费，所以只在定位问题时临时打开。
 *
 * 打开方式：把 [ENABLED] 改成 `true`，重新编译安装模块并重启桌面进程。
 * - logcat：`adb logcat -s ZFlowRadial`
 * - LSPosed 日志：前缀 `ZFlowRadial`
 *
 * [log] 是 inline 函数、[ENABLED] 是 `const`，关闭时编译器会把整段调用
 * （含实参里的字符串拼接）当死代码消除，热路径上不留任何开销。
 */
internal object RadialTrace {

    const val TAG = "ZFlowRadial"

    /** 轮盘日志总开关，见类注释。 */
    @PublishedApi
    internal const val ENABLED = false

    /** 是否同时写 LSPosed 日志（没有 adb 时看模块日志用）。 */
    @PublishedApi
    internal const val WRITE_LSPOSED = false

    @Suppress("NOTHING_TO_INLINE")
    inline fun log(message: String) {
        if (!ENABLED) return
        val line = "[radial] $message"
        runCatching { Log.i(TAG, line) }
        if (WRITE_LSPOSED) {
            runCatching { XposedBridge.log("$TAG $line") }
        }
    }
}
