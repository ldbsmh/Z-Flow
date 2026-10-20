package io.relimus.zflow.broadcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 静态声明的小窗请求接收器（Manifest 中声明，带 android.permission.DUMP）。
 *
 * 存在的理由：动态注册的接收器只在 [io.relimus.zflow.service.KeepAliveService]
 * 或 [io.relimus.zflow.service.ForegroundService] 存活期间有效；而小窗请求来自
 * system_server，可能在 App 进程被杀之后到达。静态接收器由系统直接拉起进程，
 * 因此在“两种保活模式都没在跑”的情况下请求也不会丢。
 *
 * 它只做转发，不读库、不建窗口，主线程开销可以忽略。
 */
class FreeformRequestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        StartFreeformReceiver.dispatch(context, intent)
    }
}
