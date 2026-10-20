package io.relimus.zflow.broadcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 静态声明的小窗请求接收器（Manifest 中声明，exported=true）。
 *
 * 存在的理由：动态注册的接收器只在 [io.relimus.zflow.service.KeepAliveService]
 * 或 [io.relimus.zflow.service.ForegroundService] 存活期间有效；而小窗请求来自
 * 被 Hook 的 Launcher / SystemUI / 通知进程，可能在 App 进程被杀之后到达。
 * 静态接收器由系统直接拉起进程，因此在“两种保活模式都没在跑”的情况下请求也不会丢。
 *
 * 它只做转发：能力校验 + 入队（见 security/BroadcastAuth.kt 与
 * FreeformRequestHandler），不读库、不建窗口。
 */
class FreeformRequestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 小窗请求需要经能力校验（见 security/BroadcastAuth.kt）。
        // goAsync 让后台线程（请求队列 + Binder）在进程内安全完成后再 finish；
        // dispatch 返回 false（拒绝）时立即 finish，避免拖到系统广播超时。
        val pendingResult = goAsync()
        val accepted = StartFreeformReceiver.dispatch(context, intent) {
            runCatching { pendingResult.finish() }
        }
        if (!accepted) runCatching { pendingResult.finish() }
    }
}
