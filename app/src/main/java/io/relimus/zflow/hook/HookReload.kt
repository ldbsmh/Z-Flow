package io.relimus.zflow.hook

import android.content.pm.ActivityInfo
import de.robv.android.xposed.XposedHelpers
import io.github.kyuubiran.ezxhelper.core.finder.ConstructorFinder
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

                    // 只有被记录在案的 Task（即进入过小窗的任务），才返回 false
                    if (trackedTaskIds.contains(taskId)) {
                        it.result = false
                    }
                }
            }
    }

    private fun hookResizable() {
        ConstructorFinder.fromClass(activityRecordClass)
            .first()
            .createHook {
                after {
                    val activityRecord = it.thisObject
                    val info = XposedHelpers.getObjectField(activityRecord, "info") as ActivityInfo

                    XposedHelpers.setIntField(info, "resizeMode", 2)
                }
            }
    }
}