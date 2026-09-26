package io.relimus.zflow.xposed.hook

import android.app.Activity
import android.os.Build
import android.view.Display
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import io.relimus.zflow.xposed.hook.utils.XLog

/**
 * 落在小窗（虚拟屏）上时退出系统 PiP。
 *
 * ## 问题
 *
 * Telegram 系应用播放视频时，上滑离开会进入系统 PiP：
 *
 * ```
 * LaunchActivity.onUserLeaveHint()
 *   → PipActivityHandler.onUserLeaveHint()
 *       → manualEnterPictureInPictureModeInternal()
 *           → Activity.enterPictureInPictureMode(params)
 * ```
 *
 * 此时若再用任务卡上滑到右上角开小窗，Z-Flow 把这个**已经处于 PiP 的任务**
 * 整个搬到虚拟屏，PiP 状态被一起带过去。
 *
 * 真机上 PiP 由 SystemUI 托管（小窗浮在桌面、默认右下角）；虚拟屏上没有任何
 * 系统 UI，PiP 窗口退化成「铺满整屏的容器 + 角落里一个很小的画面 + 其余空白」。
 *
 * ## 做法
 *
 * 在 Activity 恢复前台时检查：**不在主屏且处于 PiP → 退出 PiP**。
 *
 * 只用框架 API（`isInPictureInPictureMode` / `exitPictureInPictureMode`），
 * 与具体应用无关，因此对所有应用生效，也不受应用混淆影响。
 *
 * ## 为什么不在 enterPictureInPictureMode 上拦截
 *
 * Telegram 在调用**之前**就 `dispatchStartEnterPip()` 更新了自己的内部状态。
 * 拦住系统调用会让它的状态机以为「已经进了 PiP」而系统并没有，可能卡在错误 UI。
 * 让它真进、再真退，状态机才自洽。
 *
 * ## 为什么用 onResume
 *
 * 任务被移到另一个显示设备会触发 relaunch，`onResume` 必然走到，是可靠落点。
 *
 * ## 边界
 *
 * 这是最小验证版本，只处理「带着 PiP 状态落地到虚拟屏」这一种情况。
 * 尚未处理「已经在虚拟屏上、之后才新进 PiP」，也还没加开关。
 */
object HookPipExitBridge {

    private const val TAG = "HookPipExit"

    fun init(classLoader: ClassLoader) {
        if (inited && hookGeneration == HookRegistry.generation) return
        hookGeneration = HookRegistry.generation
        inited = false

        runCatching { hookActivityResume(classLoader) }
            .onFailure { XLog.e("$TAG: init failed", it) }

        inited = true
        XLog.d("$TAG: init done")
    }

    private var inited = false
    private var hookGeneration = -1L

    private fun hookActivityResume(classLoader: ClassLoader) {
        val activityClass = runCatching {
            XposedHelpers.findClass("android.app.Activity", classLoader)
        }.getOrNull() ?: return

        HookRegistry.findAndHookMethod(
            activityClass,
            "onResume",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    exitPipIfNeeded(activity)
                }
            }
        )
    }

    private fun exitPipIfNeeded(activity: Activity) {
        // isInPictureInPictureMode / exitPictureInPictureMode 均为 API 24+
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        // 主屏不管；非主屏（Z-Flow 小窗）才处理
        val displayId = runCatching { activity.display?.displayId }.getOrNull() ?: return
        if (displayId == Display.DEFAULT_DISPLAY) return

        val inPip = runCatching { activity.isInPictureInPictureMode }.getOrDefault(false)
        if (!inPip) return

        val exited = runCatching { activity.exitPictureInPictureMode() }.getOrDefault(false)
        XLog.d(
            "$TAG: exit PiP display=$displayId activity=${activity.javaClass.name} -> $exited"
        )
    }
}
