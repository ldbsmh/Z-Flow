package io.relimus.zflow.hook

import android.content.res.Configuration
import de.robv.android.xposed.XposedHelpers
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder
import io.github.kyuubiran.ezxhelper.core.util.ClassUtil.loadClass
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createHook


object HookReload {

    val activityRecordClass = loadClass("com.android.server.wm.ActivityRecord")
    val configurationClass = loadClass("android.content.res.Configuration")
    val trackedTaskIds = mutableSetOf<Int>()

    fun init() {
        hookRelaunchMethod()
        hookResizable()
    }

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
                    it.result = false
                }
            }
    }

    private fun hookResizable() {
        MethodFinder.fromClass(activityRecordClass)
            .filterByName("resolveOverrideConfiguration")
            .filterByParamCount(1)
            .filterByParamTypes(configurationClass)
            .first()
            .createHook {
                after {
                    val activityRecord = it.thisObject

                    // 获取当前所在的 DisplayId
                    val displayContent =
                        XposedHelpers.getObjectField(activityRecord, "mDisplayContent")
                    val currentDisplayId = if (displayContent != null) {
                        XposedHelpers.getIntField(displayContent, "mDisplayId")
                    } else {
                        0
                    }

                    // 获取所属的任务 Task
                    val task = XposedHelpers.callMethod(activityRecord, "getTask") ?: return@after
                    val taskId = XposedHelpers.getIntField(task, "mTaskId")

                    // 关键逻辑：如果是虚拟显示器（ID != 0），则标记该 Task
                    // 小窗创建的虚拟显示器 ID 通常 > 0
                    if (currentDisplayId != 0) {
                        trackedTaskIds.add(taskId)
                    }

                    // 只有被记录在案的 Task（即进入过小窗的任务），才执行抹除 Bounds 的逻辑
                    if (trackedTaskIds.contains(taskId)) {

                        // 如果当前已经在主屏（从虚拟屏恢复回来），继续保持抹除，实现全屏放大
                        // 如果在虚拟屏内，抹除 Bounds 确保它铺满小窗
                        val resolvedConfig = XposedHelpers.callMethod(
                            activityRecord,
                            "getResolvedOverrideConfiguration"
                        ) as Configuration
                        val windowConfig =
                            XposedHelpers.getObjectField(resolvedConfig, "windowConfiguration")

                        XposedHelpers.callMethod(windowConfig, "setBounds", *arrayOf<Any?>(null))
                        XposedHelpers.callMethod(windowConfig, "setAppBounds", *arrayOf<Any?>(null))
                        XposedHelpers.callMethod(windowConfig, "setMaxBounds", *arrayOf<Any?>(null))

                        // Android 14
                        XposedHelpers.setObjectField(activityRecord, "mSizeCompatBounds", null)
                        XposedHelpers.setFloatField(activityRecord, "mSizeCompatScale", 1.0f)
                        XposedHelpers.setBooleanField(
                            activityRecord,
                            "mInSizeCompatModeForBounds",
                            false
                        )
                    }
                }
            }
    }
}