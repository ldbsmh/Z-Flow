package io.relimus.zflow.xposed.hook

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

                    // 获取所属的任务 Task
                    val task = XposedHelpers.callMethod(activityRecord, "getTask") ?: return@after
                    val taskId = XposedHelpers.getIntField(task, "mTaskId")

                    // 对于主屏幕上的任务，检查是否曾经在小窗中（从小窗放大回来的）
                    if (trackedTaskIds.contains(taskId)) {
                        it.result = false
                        trackedTaskIds.remove(taskId)
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