package com.sunshine.freeform.hook

import android.content.pm.ActivityInfo
import android.os.Binder
import com.sunshine.freeform.hook.utils.Instances
import com.sunshine.freeform.hook.utils.XLog
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookFramework : IXposedHookLoadPackage, IXposedHookZygoteInit {

    /**
     * Android12 以上
     */
    private fun hookATS(classLoader: ClassLoader) {
        //val classLoader = Thread.currentThread().contextClassLoader
        val ats = XposedHelpers.findClass("com.android.server.wm.ActivityTaskSupervisor", classLoader)
        XposedHelpers.findAndHookMethod(
            ats,
            "isCallerAllowedToLaunchOnDisplay",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            ActivityInfo::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = true
                    XLog.d("hook isCallerAllowedToLaunchOnDisplay success")
                }
            }
        )
    }

    /**
     * 冻结虚拟屏幕方向
     */
    private fun hookDisplayRotation() {
        val classLoader = Thread.currentThread().contextClassLoader
        val displayRotationClazz = XposedHelpers.findClass("com.android.server.wm.DisplayRotation", classLoader)
        XposedBridge.hookAllConstructors(
            displayRotationClazz,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val isDefaultDisplay = XposedHelpers.getBooleanField(param.thisObject, "isDefaultDisplay")
                    if (!isDefaultDisplay) {
                        XposedHelpers.callMethod(param.thisObject, "freezeRotation", 0)
                        XLog.d("freezeRotation success")
                    }
                }
            }
        )
    }

    /**
     * 暂时无用
     */
    private fun hookATSHandleNonResizableTaskIfNeeded() {
        val classLoader = Thread.currentThread().contextClassLoader
        val ats = XposedHelpers.findClass("com.android.server.wm.ActivityTaskSupervisor", classLoader)
        val taskClazz = XposedHelpers.findClass("com.android.server.wm.Task", classLoader)
        val taskDisplayAreaClazz = XposedHelpers.findClass("com.android.server.wm.TaskDisplayArea", classLoader)
        //val activityStackClazz = XposedHelpers.findClass("com.android.server.wm.ActivityStack", classLoader)
        XposedHelpers.findAndHookMethod(
            ats,
            "handleNonResizableTaskIfNeeded",
            taskClazz,
            Int::class.javaPrimitiveType,
            taskDisplayAreaClazz,
            taskClazz,
            Boolean::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val taskObj = param.args[0]
                    val preferredTaskDisplayAreaObj = param.args[2]
                    val preferredDisplayField = XposedHelpers.findField(taskDisplayAreaClazz, "mDisplayContent")
                    val displayContentObj = XposedHelpers.callMethod(taskObj, "getDisplayContent")
                    preferredDisplayField.set(preferredTaskDisplayAreaObj, displayContentObj)

                    XLog.d("hook ATSHandleNonResizableTaskIfNeeded success")
                }
            }
        )
    }

    override fun handleLoadPackage(p0: XC_LoadPackage.LoadPackageParam) {
        if (p0.packageName == "android") {
            val classLoader = p0.classLoader

            // Hook ActivityManagerService.systemReady to initialize Instances and register service
            hookSystemReady(classLoader)

            // Hook InputManagerService to bypass INJECT_EVENTS permission
            hookIMS(classLoader)

            // Hook ATMS to bypass MANAGE_ACTIVITY_TASKS permission for TaskStackListener
            hookATMS(classLoader)

            // Hook display permission checks
            hookATS(classLoader)
        }
    }

    /**
     * Hook ActivityManagerService.systemReady to initialize Instances
     */
    private fun hookSystemReady(classLoader: ClassLoader) {
        val ams = XposedHelpers.findClass("com.android.server.am.ActivityManagerService", classLoader)
        XposedBridge.hookAllMethods(
            ams,
            "systemReady",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    XLog.d("ActivityManagerService.systemReady called")
                    Instances.init(param.thisObject)
                    XLog.d("Instances initialized successfully")
                }
            }
        )

        // Hook startActivityAsUserWithFeature to allow shell package from our app
        hookStartActivity(ams)
    }

    /**
     * Hook startActivityAsUserWithFeature to bypass UID check for our app
     * When callingPackage is "com.android.shell" but caller UID is not shell (2000),
     * clear calling identity to allow the call
     */
    private fun hookStartActivity(ams: Class<*>) {
        XposedBridge.hookAllMethods(
            ams,
            "startActivityAsUserWithFeature",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // args[1] is callingPackage
                    val callingPackage = param.args[1] as? String ?: return
                    if (callingPackage != "com.android.shell") return

                    val callingUid = Binder.getCallingUid()
                    // Shell UID is 2000, if someone uses shell package but isn't shell,
                    // they need our help to bypass the check
                    if (callingUid != 2000) {
                        val token = Binder.clearCallingIdentity()
                        param.setObjectExtra("binder_token", token)
                        XLog.d("Cleared calling identity for startActivity, uid=$callingUid")
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val token = param.getObjectExtra("binder_token") as? Long
                    if (token != null) {
                        Binder.restoreCallingIdentity(token)
                        XLog.d("Restored calling identity")
                    }
                }
            }
        )

        // Also hook sendIntentSender for PendingIntent support
        XposedBridge.hookAllMethods(
            ams,
            "sendIntentSender",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val callingUid = Binder.getCallingUid()
                    // Allow non-system apps to send intent senders
                    if (callingUid >= 10000) {
                        val token = Binder.clearCallingIdentity()
                        param.setObjectExtra("binder_token", token)
                        XLog.d("Cleared calling identity for sendIntentSender, uid=$callingUid")
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val token = param.getObjectExtra("binder_token") as? Long
                    if (token != null) {
                        Binder.restoreCallingIdentity(token)
                    }
                }
            }
        )

        XLog.d("hookStartActivity success")
    }

    /**
     * Hook ATMS to bypass MANAGE_ACTIVITY_TASKS permission for register/unregisterTaskStackListener
     */
    private fun hookATMS(classLoader: ClassLoader) {
        val atms = XposedHelpers.findClass("com.android.server.wm.ActivityTaskManagerService", classLoader)

        for (methodName in arrayOf("registerTaskStackListener", "unregisterTaskStackListener", "moveRootTaskToDisplay")) {
            XposedBridge.hookAllMethods(
                atms,
                methodName,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val callingUid = Binder.getCallingUid()
                        if (callingUid >= 10000) {
                            val token = Binder.clearCallingIdentity()
                            param.setObjectExtra("atms_token", token)
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val token = param.getObjectExtra("atms_token") as? Long
                        if (token != null) {
                            Binder.restoreCallingIdentity(token)
                        }
                    }
                }
            )
        }
        XLog.d("hookATMS success - TaskStackListener permission bypassed")
    }

    /**
     * Hook InputManagerService to bypass INJECT_EVENTS permission check
     */
    private fun hookIMS(classLoader: ClassLoader) {
        val ims = XposedHelpers.findClass("com.android.server.input.InputManagerService", classLoader)

        XposedBridge.hookAllMethods(
            ims,
            "injectInputEvent",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val token = Binder.clearCallingIdentity()
                    param.setObjectExtra("ims_token", token)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val token = param.getObjectExtra("ims_token") as? Long
                    if (token != null) {
                        Binder.restoreCallingIdentity(token)
                    }
                }
            }
        )
        XLog.d("hookIMS success - INJECT_EVENTS permission bypassed")
    }

    override fun initZygote(param: IXposedHookZygoteInit.StartupParam) {
        // IMS hook is now handled in handleLoadPackage() for all Android versions
    }
}