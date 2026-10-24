package io.relimus.zflow.xposed.services

import android.content.ComponentName
import android.content.Context
import io.relimus.zflow.xposed.hook.utils.XLog
import io.relimus.zflowwheel.wheel.IWheelLauncher

/**
 * IWheelLauncher 的 system_server 实现。
 *
 * 只做一件事：把「包名 + Activity + 用户」翻译成 FreeformManager.createWindow。
 * 尺寸/DPI/停靠样式全部读用户设置（与 FreeformRequestHandler 完全相同的键和
 * 默认值），保证轮盘开出来的窗口和侧边栏开出来的分毫不差 —— 两个入口开出两种
 * 窗是最容易被当成 bug 的行为。
 */
class WheelLauncherImpl(
    private val getContext: () -> Context
) : IWheelLauncher.Stub() {

    override fun getApiVersion(): Int = 1

    override fun isReady(): Boolean = FreeformManager.isReady

    override fun launchFreeform(
        packageName: String?,
        activityName: String?,
        userId: Int
    ): Boolean {
        if (packageName.isNullOrEmpty()) {
            XLog.ls("diag:wheelLaunch reject empty package", tag = "ZFlowDiag")
            return false
        }
        if (!FreeformManager.isReady) {
            XLog.ls("diag:wheelLaunch reject not-ready", tag = "ZFlowDiag")
            return false
        }

        return runCatching {
            val context = getContext()
            val sp = context.getSharedPreferences(APP_SETTINGS, Context.MODE_PRIVATE)
            // 键名与默认值必须与 FreeformRequestHandler 保持一致。
            val freeformDpi = sp.getInt("freeform_scale", context.resources.displayMetrics.densityDpi)
            val freeformSize = sp.getInt("freeform_size", 75)
            val freeformSizeLand = sp.getInt("freeform_size_land", 90)
            val floatViewSize = sp.getInt("freeform_float_view_size", 25)
            val dimAmount = sp.getInt("freeform_dimming_amount", 20)
            val manualAdjust = sp.getBoolean("manual_adjust_freeform_rotation", false)
            val dockStyle = sp.getInt("freeform_dock_style", 100)

            XLog.ls(
                "diag:wheelLaunch pkg=$packageName act=${activityName.orEmpty()} user=$userId " +
                    "size=$freeformSize dock=$dockStyle",
                tag = "ZFlowDiag"
            )

            FreeformManager.createWindow(
                componentName = ComponentName(packageName, resolveLauncherActivity(packageName, activityName)),
                pendingIntent = null,
                userId = userId,
                taskId = -1,
                freeformDpi = freeformDpi,
                freeformSize = freeformSize,
                freeformSizeLand = freeformSizeLand,
                floatViewSize = floatViewSize,
                dimAmount = dimAmount,
                manualAdjustFreeformRotation = manualAdjust,
                sourceRotation = 0,
                sourceScreenWidth = 0,
                sourceScreenHeight = 0,
                dockStyle = dockStyle
            )
            true
        }.onFailure {
            XLog.e("WheelLauncher: launch $packageName failed", it)
            XLog.ls(
                "diag:wheelLaunch FAILED ${it.javaClass.simpleName}: ${it.message}",
                tag = "ZFlowDiag"
            )
        }.getOrDefault(false)
    }

    /**
     * 槽位里存了 activityName 就直接用；启动器图标给的是 launcher activity，
     * 与「应用主入口」一致，无需再做解析。
     */
    private fun resolveLauncherActivity(packageName: String, activityName: String?): String {
        if (!activityName.isNullOrEmpty()) return activityName
        return runCatching {
            val ctx = getContext()
            val intent = ctx.packageManager.getLaunchIntentForPackage(packageName)
            intent?.component?.className ?: ""
        }.getOrDefault("")
    }

    private companion object {
        /** 与 ZFlow.APP_SETTINGS_NAME 同一个文件。 */
        const val APP_SETTINGS = "app_settings"
    }
}
