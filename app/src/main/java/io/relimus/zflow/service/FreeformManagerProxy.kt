package io.relimus.zflow.service

import android.app.PendingIntent
import android.content.ComponentName
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import io.relimus.zflow.app.ZFlow
import io.relimus.zflow.bean.MotionEventBean
import io.relimus.zflow.utils.cast
import io.relimus.zflow.xposed.IFreeformManager
import io.relimus.zflow.xposed.hook.utils.XLog
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Client-side proxy for IFreeformManager.
 * Provides IPC access to the FreeformManager service running in system_server.
 *
 * 所有方法都委托给 [service]；binder 断开后 [service] 为 null，
 * 各方法返回安全的默认值（与 AIDL 声明一致）。
 */
object FreeformManagerProxy : IFreeformManager, IBinder.DeathRecipient {
    private const val TAG = "FreeformManagerProxy"

    private class ServiceProxy(private val obj: IFreeformManager) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            return try {
                method.invoke(obj, *(args ?: emptyArray()))
            } catch (e: java.lang.reflect.InvocationTargetException) {
                Log.e(TAG, "Service method failed: ${method.name}", e.targetException ?: e)
                defaultValue(method.returnType)
            } catch (e: Throwable) {
                Log.e(TAG, "Error calling ${method.name}", e)
                defaultValue(method.returnType)
            }
        }

        private fun defaultValue(type: Class<*>): Any? = when (type) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> -1
            Long::class.javaPrimitiveType -> -1L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            Void.TYPE -> null
            else -> null
        }
    }

    @Volatile
    private var service: IFreeformManager? = null

    val isConnected: Boolean
        get() = service != null

    /** 最近一次失败后主动补链的时刻，用于退避，避免连点时把 provider 刷爆。 */
    @Volatile
    private var lastRelinkAt = 0L
    private const val RELINK_MIN_INTERVAL_MS = 2_000L

    /**
     * 没连上时主动向 system_server 要一次 binder。
     *
     * 为什么需要这一步：binder 由 system_server 侧的 UserService 经
     * [io.relimus.zflow.providers.ServiceProvider] 投递进来，而它只在 app 进程
     * **变成活跃**（UID observer）时投。下面的场景全都会漏：
     *
     *  - 进程被 LMKD 杀掉后没被杀干净、或 UserService 因为加密存储锁住而没起；
     *  - 用户「强停」后重开，UID 一直算活跃，不再触发 onUidActive；
     *  - 补投之间正好撞上 2s 去抖窗口。
     *
     * 漏掉的后果很隐蔽：轮盘能弹、点得动，但每个请求都只打一行
     * 「FreeformManager not connected」然后静默丢弃 —— 表现成「点了没反应」。
     *
     * 这里直接用 system_server 的 identity 去 call 一个 provider 无副作用：
     * provider 侧只做 linkService，重复投递幂等。
     */
    /** 诊断走 LSPosed 日志（app 主进程同样可用），与其它诊断行统一。 */
    private fun diag(msg: String) {
        XLog.ls(msg, tag = "ZFlowDiag")
    }

    fun requestRelink() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRelinkAt < RELINK_MIN_INTERVAL_MS) return
        lastRelinkAt = now
        runCatching {
            val uri = android.net.Uri.parse(
                "content://${ZFlow.PACKAGE_NAME}.freeform.provider"
            )
            ZFlow.me.contentResolver.call(uri, "", null, Bundle())
            diag("diag:relinkRequested connected=$isConnected")
        }.onFailure {
            diag("diag:relinkRequested FAILED ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    fun linkService(binder: IBinder) {
        service = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(IFreeformManager::class.java),
            ServiceProxy(IFreeformManager.Stub.asInterface(binder))
        ).cast<IFreeformManager>()
        binder.linkToDeath(this, 0)
        Log.d(TAG, "Service linked successfully")
    }

    override fun binderDied() {
        service = null
        Log.e(TAG, "Service binder died")
    }

    // ---- IFreeformManager：全部转发给 service，未连接时返回 AIDL 默认值 ----

    override fun getVersionName(): String? = service?.versionName

    override fun getVersionCode(): Int = service?.versionCode ?: -1

    override fun getUid(): Int = service?.uid ?: -1

    override fun createWindow(
        componentName: ComponentName?,
        pendingIntent: PendingIntent?,
        userId: Int,
        taskId: Int,
        freeformDpi: Int,
        freeformSize: Int,
        freeformSizeLand: Int,
        floatViewSize: Int,
        dimAmount: Int,
        manualAdjustFreeformRotation: Boolean,
        sourceRotation: Int,
        sourceScreenWidth: Int,
        sourceScreenHeight: Int,
        dockStyle: Int
    ) {
        service?.createWindow(
            componentName, pendingIntent, userId, taskId,
            freeformDpi, freeformSize, freeformSizeLand, floatViewSize,
            dimAmount, manualAdjustFreeformRotation, sourceRotation,
            sourceScreenWidth, sourceScreenHeight, dockStyle
        )
    }

    override fun createMiniWindow(
        componentName: ComponentName?,
        pendingIntent: PendingIntent?,
        userId: Int,
        taskId: Int,
        freeformDpi: Int,
        freeformSize: Int,
        freeformSizeLand: Int,
        floatViewSize: Int,
        dimAmount: Int,
        manualAdjustFreeformRotation: Boolean,
        sourceRotation: Int,
        sourceScreenWidth: Int,
        sourceScreenHeight: Int,
        dockStyle: Int
    ) {
        service?.createMiniWindow(
            componentName, pendingIntent, userId, taskId,
            freeformDpi, freeformSize, freeformSizeLand, floatViewSize,
            dimAmount, manualAdjustFreeformRotation, sourceRotation,
            sourceScreenWidth, sourceScreenHeight, dockStyle
        )
    }

    override fun destroyWindow(displayId: Int) = service?.destroyWindow(displayId) ?: Unit

    override fun destroyAllWindows() = service?.destroyAllWindows() ?: Unit

    override fun moveWindowToTop(displayId: Int) = service?.moveWindowToTop(displayId) ?: Unit

    override fun injectMotionEvent(event: MotionEventBean?, displayId: Int) =
        service?.injectMotionEvent(event, displayId) ?: Unit

    override fun injectKeyEvent(keyCode: Int, displayId: Int) =
        service?.injectKeyEvent(keyCode, displayId) ?: Unit

    override fun moveTaskToDisplay(taskId: Int, displayId: Int) =
        service?.moveTaskToDisplay(taskId, displayId) ?: Unit

    override fun startActivityOnDisplay(componentName: ComponentName?, userId: Int, displayId: Int) =
        service?.startActivityOnDisplay(componentName, userId, displayId) ?: Unit

    override fun sendPendingIntentOnDisplay(pendingIntent: PendingIntent?, displayId: Int, taskId: Int) =
        service?.sendPendingIntentOnDisplay(pendingIntent, displayId, taskId) ?: Unit

    override fun collapseStatusBarPanel() = service?.collapseStatusBarPanel() ?: Unit

    override fun getOpenWindowCount(): Int = service?.openWindowCount ?: 0

    override fun isServiceReady(): Boolean = service?.isServiceReady == true

    override fun asBinder(): IBinder? = service?.asBinder()
}
