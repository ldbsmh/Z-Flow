package io.relimus.zflow.xposed.services

import android.content.ComponentName
import android.os.IBinder
import io.relimus.zflow.xposed.hook.utils.XLog

/**
 * 轮盘模块的 system_server 侧入口。
 *
 * 为什么放在这里：轮盘（Z-Flow-Wheel）是独立 APK，它自己既没有 LSPosed 权限，
 * 也没有开小窗的能力。开小窗必须走本进程的 FreeformManager —— 这和侧边栏
 * 用的是同一条链路，只是入口从广播换成了 AIDL 直连。
 *
 * 与侧边栏那套的关键差别：这里**不经过 app 主进程**。之前轮盘点应用失败的
 * 根因是 binder 只在 app 进程变活跃时才由 UserService 投递，漏投就静默丢弃。
 * 直接把服务登记进 ServiceManager 后，轮盘拿到的是 system_server 里的实例本身，
 * 不存在「有没有连上」这个问题。
 */
object WheelLauncherService {

    /** 与 Z-Flow-Wheel 的 WheelLauncher.SERVICE_NAME 逐字一致。 */
    const val SERVICE_NAME = "zflow_wheel_launcher"

    @Volatile
    private var registered = false

    fun register() {
        if (registered) return
        runCatching {
            val service = WheelLauncherImpl()
            val sm = Class.forName("android.os.ServiceManager")
            val addService = sm.getMethod(
                "addService",
                String::class.java,
                IBinder::class.java
            )
            addService.invoke(null, SERVICE_NAME, service.asBinder())
            registered = true
            XLog.ls("diag:wheelLauncher registered as $SERVICE_NAME")
        }.onFailure {
            XLog.e("WheelLauncherService: register failed", it)
            XLog.ls("diag:wheelLauncher register FAILED ${it.javaClass.simpleName}: ${it.message}")
        }
    }
}
