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
        if (inited && hookGeneration == HookRegistry.generation) return
        hookGeneration = HookRegistry.generation
        inited = false

        hookServiceManager()
        hookActivityManagerService()

        inited = true
    }

    private var inited = false
    private var hookGeneration = -1L

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
                        thread {
                            runCatching {
                                UserService.register(pms)
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
                    runCatching {
                        FreeformManager.activityManagerService = param.thisObject
                        FreeformManager.systemReady()
                        XLog.d("$TAG: FreeformManager initialized")
                    }.onFailure { e ->
                        XLog.e("$TAG: Failed to initialize FreeformManager", e)
                    }
                }
            }
    }
}