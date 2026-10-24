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

    @Volatile
    private var retryScheduled = false

    fun publish(getContext: () -> Context) {
        runCatching {
            // systemReady 挂在 system_server 启动早期，此时 ServiceManager 可能还在
            // addService 的窗口内。第一次失败就静默重试几次，别让桥永久缺失。
            val impl = WheelLauncherImpl(getContext)
            val sm = Class.forName("android.os.ServiceManager")
            val addService = sm.getMethod("addService", String::class.java, IBinder::class.java)
            // 每次换成新的 stub 再登记，避免旧 binder 被系统缓存。
            addService.invoke(null, SERVICE_NAME, impl.asBinder())
            launcher = impl
            XLog.ls("diag:wheelBridge published as $SERVICE_NAME", tag = "ZFlowDiag")
        }.onFailure { e ->
            XLog.e("$TAG: publish failed (will retry)", e)
            scheduleRetry(getContext, 0)
        }
    }

    /** 退避重试：1s/4s/10s/30s/60s，五次都失败才放弃并留日志。 */
    private fun scheduleRetry(getContext: () -> Context, attempt: Int) {
        if (attempt >= RETRY_DELAYS_MS.size || retryScheduled) return
        retryScheduled = true
        val delay = RETRY_DELAYS_MS[attempt]
        val ok = runCatching {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                retryScheduled = false
                runCatching {
                    val impl = WheelLauncherImpl(getContext)
                    val sm = Class.forName("android.os.ServiceManager")
                    sm.getMethod("addService", String::class.java, IBinder::class.java)
                        .invoke(null, SERVICE_NAME, impl.asBinder())
                    launcher = impl
                    XLog.ls("diag:wheelBridge published on retry #${attempt + 1}", tag = "ZFlowDiag")
                }.onFailure {
                    // 回调里 retryScheduled 已复位，从这里续下一档。
                    scheduleRetry(getContext, attempt + 1)
                }
            }, delay)
            true
        }.getOrDefault(false)
        if (!ok) retryScheduled = false
    }

    private val RETRY_DELAYS_MS = longArrayOf(1_000L, 4_000L, 10_000L, 30_000L, 60_000L)

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
