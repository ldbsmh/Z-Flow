package io.relimus.zflow.xposed.hook

import android.content.pm.IPackageManager
import io.relimus.zflow.xposed.hook.utils.XLog
import io.relimus.zflow.xposed.services.FreeformManager
import io.relimus.zflow.xposed.services.UserService
import de.robv.android.xposed.XC_MethodHook
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder
import io.github.kyuubiran.ezxhelper.core.util.ClassUtil.loadClass
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createHook
import io.relimus.zflow.utils.cast
import kotlin.concurrent.thread

/**
 * HookSystem initializes FreeformManager and UserService in system_server.
 * This enables the pure Xposed implementation without Shizuku dependency.
 */
object HookSystem {
    private const val TAG = "HookSystem"

    fun init() {
        XLog.d("$TAG: Initializing system_server hooks")

        // Hook ServiceManager.addService to register UserService when PackageManager is ready
        hookServiceManager()

        // Hook ActivityManagerService.systemReady to initialize FreeformManager
        hookActivityManagerService()

        XLog.d("$TAG: System hooks initialized")
    }

    private fun hookServiceManager() {
        var unhook: XC_MethodHook.Unhook? = null
        unhook = MethodFinder.fromClass(loadClass("android.os.ServiceManager"))
            .filterByName("addService")
            .first()
            .createHook {
                before { param ->
                    val serviceName = param.args[0].cast<String?>()
                    if (serviceName == "package") {
                        unhook?.unhook()
                        val pms = param.args[1].cast<IPackageManager>()
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

    private fun hookActivityManagerService() {
        var unhook: XC_MethodHook.Unhook? = null
        unhook = MethodFinder.fromClass(loadClass("com.android.server.am.ActivityManagerService"))
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
