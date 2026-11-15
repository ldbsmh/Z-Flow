package io.relimus.zflow.xposed.hook

import de.robv.android.xposed.XC_MethodHook
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createHook
import io.relimus.zflow.utils.cast
import io.relimus.zflow.xposed.hook.utils.XLog
import io.relimus.zflow.xposed.services.UserService
import kotlin.concurrent.thread

/**
 * system_server 侧的启动挂点。
 *
 * 小窗本体由 libxposed 的 [io.relimus.zflow.xposed.nativewin.ZFlowWindowModule] 安装
 * （它需要 libxposed API 101 的 onSystemServerStarting 回调），这里只保留一件事：
 * 等 PackageManager / ActivityManager 就绪后注册 UID 观察，把窗口后端 binder 投给应用进程。
 */
object HookSystem {
    private const val TAG = "HookSystem"

    private var inited = false
    private var hookGeneration = -1L

    fun init() {
        if (inited && hookGeneration == HookRegistry.generation) return
        hookGeneration = HookRegistry.generation
        inited = false

        hookServiceManager()
        // libxposed 的 onSystemServerStarting 已登记窗口后端时，这里直接补投一次，
        // 覆盖「addService(package) 已经发生过」的时序。
        runCatching { UserService.setWindowBackend(UserService.windowBackend) }
        inited = true
    }

    private fun hookServiceManager() {
        val unhooks = mutableListOf<XC_MethodHook.Unhook>()
        MethodFinder.fromClass(android.os.ServiceManager::class.java)
            .filterByName("addService")
            .toList()
            .forEach { method ->
                method.createHook {
                    before { param ->
                        val serviceName = param.args[0].cast<String?>()
                        if (serviceName == "package") {
                            unhooks.forEach { it.unhook() }
                            val pms = param.args[1].cast<android.content.pm.IPackageManager>()
                            thread(name = "zflow-user-service") {
                                runCatching { UserService.register(pms) }
                                    .onFailure { XLog.e("$TAG: Failed to register UserService", it) }
                            }
                        }
                    }
                }?.also { unhooks.add(it) }
            }
    }
}
