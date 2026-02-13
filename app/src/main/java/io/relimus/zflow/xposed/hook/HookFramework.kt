package io.relimus.zflow.xposed.hook

import android.content.pm.ActivityInfo
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder
import io.github.kyuubiran.ezxhelper.core.util.ClassUtil.loadClass
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createHook
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createHooks

object HookFramework {

    fun initZygote() {
        val activityThread = loadClass("android.app.ActivityThread")
        MethodFinder.fromClass(activityThread)
            .filterByName("systemMain")
            .toList()
            .createHooks {
                after { hookIMS() }
            }
    }

    fun init() {
        hookATS()
    }

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
                after {
                    it.result = true
                }
            }
    }

    private fun hookIMS() {
        val imsClazz = loadClass("com.android.server.input.InputManagerService")

        MethodFinder.fromClass(imsClazz)
            .filterByName("injectInputEvent")
            .toList()
            .createHooks {
                replace { param ->
                    val injectInputEventInternal = imsClazz.getDeclaredMethod(
                        "injectInputEventInternal",
                        Class.forName("android.view.InputEvent"),
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    )
                    injectInputEventInternal.invoke(
                        param.thisObject,
                        param.args[0],
                        param.args[1],
                        0
                    )
                }
            }
    }
}