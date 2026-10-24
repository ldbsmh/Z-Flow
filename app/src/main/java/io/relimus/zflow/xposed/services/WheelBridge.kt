package io.relimus.zflow.xposed.services

import android.content.Context
import android.os.Binder
import android.os.IBinder
import io.relimus.zflow.xposed.hook.utils.XLog

/**
 * 系统级服务 `zflow_wheel`：轮盘项目通过它拿到 IWheelLauncher。
 *
 * 为什么不复用 FreeformManager 的投递通道：那条通道是「system_server 主动
 * 推给 Z-Flow 自己的 app 进程」，只能送到一个固定 UID。轮盘项目是**另一个
 * APK**，UidObserver 里那套 appUid 判断永远不匹配它，所以它拿不到 binder。
 * 之前日志里反复出现的 `FreeformManager not connected` 就是这么来的。
 *
 * 这里改成拉取：谁要用谁去 ServiceManager 取，binder 生命周期跟 system_server
 * 走，不存在「没人补投」的窗口期。
 */
object WheelBridge {

    private const val TAG = "WheelBridge"
    const val SERVICE_NAME = "zflow_wheel"

    private var launcher: WheelLauncherImpl? = null

    fun publish(getContext: () -> Context) {
        runCatching {
            val impl = WheelLauncherImpl(getContext)
            launcher = impl
            val sm = Class.forName("android.os.ServiceManager")
            val addService = sm.getMethod("addService", String::class.java, IBinder::class.java)
            addService.invoke(null, SERVICE_NAME, impl.asBinder())
            XLog.ls("diag:wheelBridge published as $SERVICE_NAME", tag = "ZFlowDiag")
        }.onFailure {
            XLog.e("$TAG: publish failed", it)
        }
    }

    /** 供 system_server 内部调用（例如小窗恢复），不经过 Binder。 */
    fun local(): WheelLauncherImpl? = launcher

    /** 校验调用方：只接受轮盘项目自己的 UID。 */
    fun isTrustedCaller(callingUid: Int, wheelUid: Int): Boolean {
        if (callingUid == 1000 || callingUid == 0) return true
        if (callingUid == wheelUid) return true
        val shared = Binder.getCallingUid() == callingUid
        if (!shared) XLog.w("$TAG: caller uid $callingUid differs from binder uid, rejecting")
        return false
    }
}
