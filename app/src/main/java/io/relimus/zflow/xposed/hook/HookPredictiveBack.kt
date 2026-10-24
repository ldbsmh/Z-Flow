package io.relimus.zflow.xposed.hook

import android.view.Display
import de.robv.android.xposed.XposedHelpers
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder
import io.github.kyuubiran.ezxhelper.core.util.ClassUtil.loadClass
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createHook
import io.relimus.zflow.xposed.hook.utils.XLog

object HookPredictiveBack {

    private const val TAG = "HookPredictiveBack"

    private val backNavigationControllerClass = loadClass("com.android.server.wm.BackNavigationController")

    fun init() {
        if (inited && hookGeneration == HookRegistry.generation) return
        hookGeneration = HookRegistry.generation
        inited = false

        hookStartBackNavigation()

        XLog.d("$TAG: hook BackNavigationController.startBackNavigation success")
        inited = true
    }

    private var inited = false
    private var hookGeneration = -1L

    private fun hookStartBackNavigation() {
        MethodFinder.fromClass(backNavigationControllerClass)
            .filterByName("startBackNavigation")
            .filterByParamCount(2)
            .first()
            .createHook {
                after {
                    val focusedDisplayId = resolveTopFocusedDisplayId(it.thisObject)
                    // 只在 Z-Flow 受管小窗上禁用；否则折叠屏副屏/HDMI 屏的返回动画也会被杀
                    if (!isManagedFreeformDisplay(focusedDisplayId)) return@after

                    disablePredictiveBackAnimation(it.result)
                }
            }
    }

    private fun disablePredictiveBackAnimation(info: Any?) {
        if (info == null) return
        try {
            XposedHelpers.setBooleanField(info, "mPrepareRemoteAnimation", false)
        } catch (e: Throwable) {
            XLog.e("$TAG: failed to patch BackNavigationInfo", e)
        }
    }

    private fun isManagedFreeformDisplay(displayId: Int): Boolean {
        return io.relimus.zflow.xposed.services.FreeformManager.isManagedDisplay(displayId)
    }

    private fun resolveTopFocusedDisplayId(controller: Any): Int {
        return try {
            val wms = XposedHelpers.getObjectField(controller, "mWindowManagerService")
            val root = XposedHelpers.getObjectField(wms, "mRoot")
            XposedHelpers.getIntField(root, "mTopFocusedDisplayId")
        } catch (_: Throwable) {
            Display.DEFAULT_DISPLAY
        }
    }
}
