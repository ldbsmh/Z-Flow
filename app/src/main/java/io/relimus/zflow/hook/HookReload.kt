package io.relimus.zflow.hook

import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder
import io.github.kyuubiran.ezxhelper.core.util.ClassUtil.loadClass
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createHook

object HookReload {
    
    fun init(classLoader: ClassLoader) {
        hookRelaunchMethod(classLoader)
    }
    
    private fun hookRelaunchMethod(classLoader: ClassLoader) {
        val activityRecord = loadClass("com.android.server.wm.ActivityRecord", classLoader)
        MethodFinder.fromClass(activityRecord)
            .filterByName("shouldRelaunchLocked")
            .filterByParamCount(2)
            .filterByParamTypes(
                Int::class.javaPrimitiveType,
                loadClass("android.content.res.Configuration")
            )
            .first()
            .createHook {
                after {
                    it.result = false
                }
            }
    }
}