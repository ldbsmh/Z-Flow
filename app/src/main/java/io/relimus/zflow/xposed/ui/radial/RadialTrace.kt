package io.relimus.zflow.xposed.ui.radial

import android.util.Log
import de.robv.android.xposed.XposedBridge

/**
 * 轮盘诊断日志。
 *
 * 故意**不**受 HookSwipeGesture 的 ENABLE_DIAGNOSTIC_LOGS 控制：
 * 轮盘的每条失败路径都是「静默退回原本手势」，如果日志也能被关掉，
 * 用户侧就只能观察到「没反应」，无法区分是热区没命中、设置没读到、
 * 名单为空、父视图找不到，还是提交失败。
 *
 * 因此这里固定输出，但只挂在状态迁移点上（一次手势最多几行），不会刷屏：
 * - logcat：`adb logcat -s ZFlowRadial`
 * - LSPosed 日志：前缀 `ZFlowRadial`
 */
internal object RadialTrace {

    const val TAG = "ZFlowRadial"

    /** 常开。真机上排查完可以改成 false，但保留 logcat 一路通常没必要关。 */
    private const val ENABLED = true

    /** 同步写 LSPosed 日志，方便没有 adb 的用户直接看模块日志。 */
    private const val WRITE_LSPOSED = true

    fun log(message: String) {
        if (!ENABLED) return
        val line = "[radial] $message"
        runCatching { Log.i(TAG, line) }
        if (WRITE_LSPOSED) {
            runCatching { XposedBridge.log("$TAG $line") }
        }
    }
}
