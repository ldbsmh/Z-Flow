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
        hookStartBackNavigation()
    }

    private fun hookStartBackNavigation() {
        MethodFinder.fromClass(backNavigationControllerClass)
            .filterByName("startBackNavigation")
            .filterByParamCount(2)
            .first()
            .createHook {
                after {
                    val focusedDisplayId = resolveTopFocusedDisplayId(it.thisObject)
                    if (!isVirtualDisplay(focusedDisplayId)) return@after

                    disablePredictiveBackAnimation(it.result)
                }
            }

        XLog.d("$TAG: hook BackNavigationController.startBackNavigation success")
    }

    private fun disablePredictiveBackAnimation(info: Any?) {
        if (info == null) return
        try {
            XposedHelpers.setBooleanField(info, "mPrepareRemoteAnimation", false)
        } catch (e: Throwable) {
            XLog.e("$TAG: failed to patch BackNavigationInfo", e)
        }
    }

    private fun isVirtualDisplay(displayId: Int): Boolean {
        return displayId >= 0 && displayId != Display.DEFAULT_DISPLAY
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
