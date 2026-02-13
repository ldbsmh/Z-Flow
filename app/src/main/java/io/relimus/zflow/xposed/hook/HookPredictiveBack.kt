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
    private val backAnimationAdapterClass = loadClass("android.window.BackAnimationAdapter")

    fun init() {
        hookStartBackNavigation()
    }

    private fun hookStartBackNavigation() {
        MethodFinder.fromClass(backNavigationControllerClass)
            .filterByName("startBackNavigation")
            .filterByParamCount(2)
            .first()
            .createHook {
                before {
                    val focusedDisplayId = resolveTopFocusedDisplayId(it.thisObject)
                    if (!isVirtualDisplay(focusedDisplayId)) return@before

                    clearBackAnimationAdapter(it.args, focusedDisplayId)
                }

                after {
                    val focusedDisplayId = resolveTopFocusedDisplayId(it.thisObject)
                    if (!isVirtualDisplay(focusedDisplayId)) return@after

                    disablePredictiveBackAnimation(it.result, focusedDisplayId)
                }
            }

        XLog.d("$TAG: hook BackNavigationController.startBackNavigation success")
    }

    private fun isVirtualDisplay(displayId: Int): Boolean {
        return displayId >= 0 && displayId != Display.DEFAULT_DISPLAY
    }

    private fun clearBackAnimationAdapter(args: Array<Any?>, displayId: Int) {
        if (args.size < 2) return

        val adapterArg = args[1]
        if (!backAnimationAdapterClass.isInstance(adapterArg)) return

        args[1] = null
        XLog.w("$TAG: clear BackAnimationAdapter on virtual displayId=$displayId")
    }

    private fun disablePredictiveBackAnimation(backNavigationInfo: Any?, displayId: Int) {
        if (backNavigationInfo == null) return

        try {
            XposedHelpers.setBooleanField(backNavigationInfo, "mPrepareRemoteAnimation", false)
            XposedHelpers.setBooleanField(backNavigationInfo, "mAnimationCallback", false)
            XposedHelpers.setBooleanField(backNavigationInfo, "mAppProgressGenerationAllowed", false)
        } catch (e: Throwable) {
            XLog.e("$TAG: failed to patch BackNavigationInfo on displayId=$displayId", e)
            return
        }

        XLog.w("$TAG: patched BackNavigationInfo to disable predictive animation on virtual display")
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
