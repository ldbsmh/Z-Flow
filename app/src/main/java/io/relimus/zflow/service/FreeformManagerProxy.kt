package io.relimus.zflow.service

import android.app.PendingIntent
import android.content.ComponentName
import android.os.IBinder
import android.util.Log
import io.relimus.zflow.bean.MotionEventBean
import io.relimus.zflow.utils.cast
import io.relimus.zflow.xposed.IFreeformManager
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
