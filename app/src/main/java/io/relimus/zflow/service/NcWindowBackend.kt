package io.relimus.zflow.service

import android.content.Context
import android.content.Intent
import dev.local.sunwindowdemo.Engine
import io.relimus.zflow.app.ZFlow
import io.relimus.zflow.xposed.hook.utils.XLog

/**
 * 小窗后端选择：把 Z-Flow 的入口接到 NativeComplements 内核，或退回原有虚拟屏实现。
 *
 * 语义：
 * - `nc`（默认）：以真实 freeform 任务开窗，窗口控件、迷你窗、贴边停靠由
 *   ncengine 的 SystemUI 宿主负责；Z-Flow 只负责“开哪个应用”。
 * - `legacy`：原来的 VirtualDisplay + 自绘窗口方案，保留用于回退对比。
 *
 * 该偏好只影响“开窗”这一步，入口本身（侧边栏、磁贴、通知、桌面长按）不变。
 */
object NcWindowBackend {

    const val PREF_KEY = "window_engine"
    const val ENGINE_NC = "nc"
    const val ENGINE_LEGACY = "legacy"

    private const val TAG = "NcWindowBackend"

    fun selected(context: Context): String =
        context.getSharedPreferences(ZFlow.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_KEY, ENGINE_NC) ?: ENGINE_NC

    fun isNc(context: Context): Boolean = selected(context) == ENGINE_NC

    /**
     * 走新内核开窗。
     *
     * @return true 表示请求已交给引擎；false 表示引擎不可用，调用方应回退旧路径。
     */
    fun openApp(
        context: Context,
        packageName: String,
        miniMode: Boolean,
        taskId: Int = -1,
    ): Boolean {
        return try {
            syncEnabled(context)
            Engine.openApp(context, packageName, taskId, miniMode)
            XLog.i(
                "$TAG: OPEN via nc engine pkg=$packageName task=$taskId mini=$miniMode"
            )
            // logcat 也能看到这一行：`adb logcat -s ZFlowMini`。
            // LSPosed 日志只有装模块的人看得见，排查「卡片上滑没反应」时两端都留个锚点。
            android.util.Log.i("ZFlowMini", "open pkg=$packageName task=$taskId mini=$miniMode")
            true
        } catch (t: Throwable) {
            XLog.e("$TAG: nc engine open failed for $packageName", t)
            android.util.Log.e("ZFlowMini", "open failed pkg=$packageName task=$taskId", t)
            false
        }
    }

    /** 通知整条 PendingIntent 开窗（保留着陆页）。 */
    fun openNotification(
        context: Context,
        packageName: String,
        launch: android.app.PendingIntent,
        fill: Intent?
    ): Boolean {
        return try {
            syncEnabled(context)
            Engine.openNotification(context, packageName, launch, fill)
            XLog.i("$TAG: OPEN(notification) via nc engine pkg=$packageName")
            true
        } catch (t: Throwable) {
            XLog.e("$TAG: nc engine notification open failed for $packageName", t)
            false
        }
    }

    /** Z-Flow 的小窗总开关打开后，引擎侧开关随之打开（首次安装时引擎默认关闭）。 */
    private fun syncEnabled(context: Context) {
        try {
            if (!Engine.enabled(context)) Engine.setEnabled(context, true)
        } catch (t: Throwable) {
            XLog.w("$TAG: sync engine enabled failed: ${t.message}")
        }
    }
}
