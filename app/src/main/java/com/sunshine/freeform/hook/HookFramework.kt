package com.sunshine.freeform.hook

import android.content.pm.ActivityInfo
import com.sunshine.freeform.hook.utils.XLog
import io.github.kyuubiran.ezxhelper.core.finder.ConstructorFinder
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

    fun init(classLoader: ClassLoader) {
        hookATS(classLoader)
    }

    private fun hookASS(classLoader: ClassLoader) {
        val assClazz = loadClass("com.android.server.wm.ActivityStackSupervisor", classLoader)

        MethodFinder.fromClass(assClazz)
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
                    XLog.d("hook isCallerAllowedToLaunchOnDisplay success")
                }
            }
    }

    private fun hookATS(classLoader: ClassLoader) {
        val atsClazz = loadClass("com.android.server.wm.ActivityTaskSupervisor", classLoader)

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

    private fun hookATSOnT(classLoader: ClassLoader) {
        val atsClazz = loadClass("com.android.server.wm.ActivityTaskSupervisor", classLoader)
        val taskClazz = loadClass("com.android.server.wm.Task", classLoader)
        val taskDisplayAreaClazz = loadClass("com.android.server.wm.TaskDisplayArea", classLoader)
        val displayContentClazz = loadClass("com.android.server.wm.DisplayContent", classLoader)

        MethodFinder.fromClass(atsClazz)
            .filterByName("handleNonResizableTaskIfNeeded")
            .toList()
            .createHooks {
                before {
                    val mPrevDisplayIdField = taskClazz.getDeclaredField("mPrevDisplayId")
                    mPrevDisplayIdField.isAccessible = true
                    val mPrevDisplayIdObj = mPrevDisplayIdField.get(it.args[0])

                    val mDisplayContentField =
                        taskDisplayAreaClazz.getDeclaredField("mDisplayContent")
                    mDisplayContentField.isAccessible = true
                    val mDisplayContentObj = mDisplayContentField.get(it.args[2])
                    val mDisplayIdField = displayContentClazz.getDeclaredField("mDisplayId")
                    mDisplayIdField.isAccessible = true
                    mDisplayIdField.set(mDisplayContentObj, mPrevDisplayIdObj)
                    XLog.d("hook handleNonResizableTaskIfNeeded success")
                }
            }
    }

    private fun hookDisplayRotation() {
        val classLoader = Thread.currentThread().contextClassLoader!!
        val displayRotationClazz = loadClass("com.android.server.wm.DisplayRotation", classLoader)

        ConstructorFinder.fromClass(displayRotationClazz)
            .toList()
            .createHooks {
                after {
                    val isDefaultDisplay = it.thisObject.javaClass
                        .getDeclaredField("isDefaultDisplay")
                        .apply { isAccessible = true }
                        .getBoolean(it.thisObject)
                    if (!isDefaultDisplay) {
                        it.thisObject.javaClass
                            .getDeclaredMethod("freezeRotation", Int::class.javaPrimitiveType)
                            .invoke(it.thisObject, 0)
                        XLog.d("freezeRotation success")
                    }
                }
            }
    }

    private fun hookIMS() {
        val classLoader = Thread.currentThread().contextClassLoader!!
        val imsClazz = loadClass("com.android.server.input.InputManagerService", classLoader)

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