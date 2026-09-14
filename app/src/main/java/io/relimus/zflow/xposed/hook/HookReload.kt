package io.relimus.zflow.xposed.hook

import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import android.view.Display
import de.robv.android.xposed.XposedHelpers
import io.github.kyuubiran.ezxhelper.core.finder.ConstructorFinder
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder
import io.github.kyuubiran.ezxhelper.core.util.ClassUtil.loadClass
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createHook
import io.relimus.zflow.utils.cast
import io.relimus.zflow.xposed.services.FreeformManager
import java.util.Collections


object HookReload {

    private const val RELAUNCH_SUPPRESSION_TIMEOUT_MS = 5000L
    val activityRecordClass = loadClass("com.android.server.wm.ActivityRecord")
    val configurationClass = loadClass("android.content.res.Configuration")
    private val trackedTaskIds = Collections.synchronizedSet(mutableSetOf<Int>())
    private val mainHandler = Handler(Looper.getMainLooper())

    fun suppressNextRelaunch(taskId: Int) {
        if (taskId <= 0) return
        trackedTaskIds.add(taskId)
        mainHandler.postDelayed(
            { trackedTaskIds.remove(taskId) },
            RELAUNCH_SUPPRESSION_TIMEOUT_MS
        )
    }

    fun cancelRelaunchSuppression(taskId: Int) {
        trackedTaskIds.remove(taskId)
    }

    fun init() {
        if (inited && hookGeneration == HookRegistry.generation) return
        hookGeneration = HookRegistry.generation
        inited = false

        // 热重载时清空运行期状态
        trackedTaskIds.clear()

        hookRelaunchMethod()
        hookResizable()

        inited = true
    }

    private var inited = false
    private var hookGeneration = -1L

    private fun hookRelaunchMethod() {
        MethodFinder.fromClass(activityRecordClass)
            .filterByName("shouldRelaunchLocked")
            .filterByParamCount(2)
            .filterByParamTypes(
                Int::class.javaPrimitiveType,
                configurationClass
            )
            .first()
            .createHook {
                after {
                    val activityRecord = it.thisObject

                    // 小窗（受管虚拟屏）上一律不 relaunch。
                    //
                    // 根因：微信 ChattingUI 只声明了
                    // configChanges="keyboardHidden|orientation|screenSize"。
                    // 打开半透明的 ImageGalleryUI（图片大图页）时，系统会在同一个
                    // task 内创建 TaskFragment 并下发配置变化，ChattingUI 处理不了
                    // 就被整体 relaunch（销毁+重建，实测 finishDrawing 254ms）。
                    // 重建空档里虚拟屏没有稳定内容，于是出现“闪一下像刷新页面”。
                    //
                    // 抑制后 Activity 走 onConfigurationChanged 承接变化，不再重建。
                    if (isOnManagedDisplay(activityRecord)) {
                        it.result = false
                        return@after
                    }

                    val task = XposedHelpers.callMethod(activityRecord, "getTask") ?: return@after
                    val taskId = XposedHelpers.getIntField(task, "mTaskId")

                    // Display migration can query this once per ActivityRecord in the task.
                    if (trackedTaskIds.contains(taskId)) {
                        it.result = false
                    }
                }
            }
    }

    /** 该 ActivityRecord 是否位于 Z-Flow 受管的小窗虚拟屏上。 */
    private fun isOnManagedDisplay(activityRecord: Any?): Boolean {
        activityRecord ?: return false
        val displayId = runCatching {
            XposedHelpers.callMethod(activityRecord, "getDisplayId") as Int
        }.getOrElse {
            runCatching {
                val task = XposedHelpers.callMethod(activityRecord, "getTask")
                val dc = XposedHelpers.callMethod(task, "getDisplayContent")
                XposedHelpers.getIntField(dc, "mDisplayId")
            }.getOrDefault(-1)
        }
        return displayId > Display.DEFAULT_DISPLAY &&
            FreeformManager.isManagedDisplay(displayId)
    }

    private fun hookResizable() {
        ConstructorFinder.fromClass(activityRecordClass)
            .first()
            .createHook {
                after {
                    val activityRecord = it.thisObject
                    val info = XposedHelpers.getObjectField(activityRecord, "info").cast<ActivityInfo>()

                    XposedHelpers.setIntField(info, "resizeMode", 2)
                }
            }
    }
}
