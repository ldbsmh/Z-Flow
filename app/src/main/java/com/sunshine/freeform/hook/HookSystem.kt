package com.sunshine.freeform.hook

import android.content.pm.IPackageManager
import com.sunshine.freeform.hook.utils.XLog
import com.sunshine.freeform.xposed.services.FreeformManager
import com.sunshine.freeform.xposed.services.UserService
import de.robv.android.xposed.XC_MethodHook
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder
import io.github.kyuubiran.ezxhelper.core.util.ClassUtil.loadClass
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createHook
import kotlin.concurrent.thread

/**
 * HookSystem initializes FreeformManager and UserService in system_server.
 * This enables the pure Xposed implementation without Shizuku dependency.
 */
object HookSystem {
    private const val TAG = "HookSystem"

    fun init(classLoader: ClassLoader) {
        XLog.d("$TAG: Initializing system_server hooks")

        // Hook ServiceManager.addService to register UserService when PackageManager is ready
        hookServiceManager(classLoader)

        // Hook ActivityManagerService.systemReady to initialize FreeformManager
        hookActivityManagerService(classLoader)

        XLog.d("$TAG: System hooks initialized")
    }

    private fun hookServiceManager(classLoader: ClassLoader) {
        var unhook: XC_MethodHook.Unhook? = null
        unhook = MethodFinder.fromClass(loadClass("android.os.ServiceManager", classLoader))
            .filterByName("addService")
            .first()
            .createHook {
                before { param ->
                    val serviceName = param.args[0] as? String
                    if (serviceName == "package") {
                        unhook?.unhook()
                        val pms = param.args[1] as IPackageManager
                        XLog.d("$TAG: Got PackageManagerService: $pms")
                        thread {
                            runCatching {
                                UserService.register(pms)
                                XLog.d("$TAG: UserService registered successfully")
                            }.onFailure { e ->
                                XLog.e("$TAG: Failed to register UserService", e)
                            }
                        }
                    }
                }
            }
    }

    private fun hookActivityManagerService(classLoader: ClassLoader) {
        var unhook: XC_MethodHook.Unhook? = null
        unhook = MethodFinder.fromClass(loadClass("com.android.server.am.ActivityManagerService", classLoader))
            .filterByName("systemReady")
            .first()
            .createHook {
                after { param ->
                    unhook?.unhook()
                    FreeformManager.activityManagerService = param.thisObject
                    FreeformManager.systemReady()
                    XLog.d("$TAG: FreeformManager initialized on systemReady")
                }
            }
    }
}
