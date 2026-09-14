package io.relimus.zflow.xposed.hook

import android.content.pm.ActivityInfo
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder
import io.github.kyuubiran.ezxhelper.core.util.ClassUtil.loadClass
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createHook
import io.relimus.zflow.xposed.hook.utils.XLog
import io.relimus.zflow.xposed.services.FreeformManager

object HookFramework {

    private const val TAG = "HookFramework"

    fun init() {
        if (inited && hookGeneration == HookRegistry.generation) return
        hookGeneration = HookRegistry.generation
        inited = false

        hookATS()

        inited = true
    }

    private var inited = false
    private var hookGeneration = -1L

    private fun hookATS() {
        val atsClazz = loadClass("com.android.server.wm.ActivityTaskSupervisor")

        MethodFinder.fromClass(atsClazz)
            .filterByName("isCallerAllowedToLaunchOnDisplay")
            .filterByParamTypes(
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                ActivityInfo::class.java
            )
            .first()
            .createHook {
                after { param ->
                    val callingPid = param.args.getOrNull(0) as? Int ?: -1
                    val callingUid = param.args.getOrNull(1) as? Int ?: -1
                    val displayId = param.args.getOrNull(2) as? Int ?: -1
                    val activityInfo = param.args.getOrNull(3) as? ActivityInfo

                    val managed = FreeformManager.isManagedDisplay(displayId)

                    XLog.d(
                        "$TAG: launch display check pid=$callingPid uid=$callingUid displayId=$displayId activity=${activityInfo?.packageName} managed=$managed original=${param.result}"
                    )

                    if (managed) {
                        param.result = true
                    }
                }
            }
    }
}