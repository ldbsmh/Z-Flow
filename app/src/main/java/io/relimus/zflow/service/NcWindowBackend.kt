package io.relimus.zflow.service

import android.content.Context
import android.content.Intent
import dev.local.sunwindowdemo.Engine
import io.relimus.zflow.app.ZFlow
import io.relimus.zflow.providers.WindowEngineSettings
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
 *
 * 外观不随请求下发：引擎窗口支持长按调整大小，尺寸/迷你比例/贴边样式由引擎
 * 自己的默认值决定；Z-Flow 设置页里那几项只对经典虚拟屏内核生效。
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
            if (!WindowEngineSettings.enabled(context)) {
                // 引擎总开关关掉：不接管，交给调用方回退经典虚拟屏内核。
                XLog.i("$TAG: engine disabled, fallback to legacy pkg=$packageName")
                return false
            }
            Engine.openApp(context, packageName, taskId, miniMode)
            XLog.i("$TAG: OPEN via nc engine pkg=$packageName task=$taskId mini=$miniMode")
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
            if (!WindowEngineSettings.enabled(context)) {
                XLog.i("$TAG: engine disabled, fallback to legacy (notification) pkg=$packageName")
                return false
            }
            Engine.openNotification(context, packageName, launch, fill)
            XLog.i("$TAG: OPEN(notification) via nc engine pkg=$packageName")
            true
        } catch (t: Throwable) {
            XLog.e("$TAG: nc engine notification open failed for $packageName", t)
            false
        }
    }

    /**
     * 首次开窗时把引擎总开关投影到 PM 组件状态（system_server 启动时靠它决定是否安装后端）。
     *
     * 只在「设置页开关为开」时做，并且每个进程只做一次：`Engine.setEnabled` 写的是
     * `corner.enabled`，用户手动关掉后不能被这里悄悄打开。
     */
    private fun syncEnabled(context: Context) {
        if (engineProjected) return
        if (!WindowEngineSettings.enabled(context)) return
        try {
            Engine.setEnabled(context, true)
            engineProjected = true
        } catch (t: Throwable) {
            XLog.w("$TAG: sync engine enabled failed: ${t.message}")
        }
    }

    /** 本进程是否已经完成过一次开关投影。 */
    @Volatile
    private var engineProjected = false
}
