package io.relimus.zflow.service

import android.content.ComponentName
import android.os.IBinder
import android.util.Log
import io.relimus.zflow.bean.MotionEventBean
import io.relimus.zflow.xposed.IFreeformManager
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Client-side proxy for IFreeformManager.
 * Provides IPC access to the FreeformManager service running in system_server.
 */
object FreeformManagerProxy : IFreeformManager, IBinder.DeathRecipient {
    private const val TAG = "FreeformManagerProxy"

    private class ServiceProxy(private val obj: IFreeformManager) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            return try {
                val result = method.invoke(obj, *(args ?: emptyArray()))
                Log.d(TAG, "Called service method: ${method.name}")
                result
            } catch (e: Exception) {
                Log.e(TAG, "Error calling ${method.name}", e)
                null
            }
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
        ) as IFreeformManager
        binder.linkToDeath(this, 0)
        Log.d(TAG, "Service linked successfully")
    }

    override fun binderDied() {
        service = null
        Log.e(TAG, "Service binder died")
    }

    // IFreeformManager implementations - delegate to service

    override fun getVersionName(): String? {
        return service?.versionName
    }

    override fun getVersionCode(): Int {
        return service?.versionCode ?: -1
    }

    override fun getUid(): Int {
        return service?.uid ?: -1
    }

    override fun createWindow(componentName: ComponentName?, userId: Int, taskId: Int, freeformDpi: Int, freeformSize: Int, floatViewSize: Int, dimAmount: Int) {
        service?.createWindow(componentName, userId, taskId, freeformDpi, freeformSize, floatViewSize, dimAmount)
    }

    override fun createMiniWindow(componentName: ComponentName?, userId: Int, taskId: Int, freeformDpi: Int, freeformSize: Int, floatViewSize: Int, dimAmount: Int) {
        service?.createMiniWindow(componentName, userId, taskId, freeformDpi, freeformSize, floatViewSize, dimAmount)
    }

    override fun destroyWindow(displayId: Int) {
        service?.destroyWindow(displayId)
    }

    override fun destroyAllWindows() {
        service?.destroyAllWindows()
    }

    override fun moveWindowToTop(displayId: Int) {
        service?.moveWindowToTop(displayId)
    }

    override fun injectMotionEvent(event: MotionEventBean?, displayId: Int) {
        service?.injectMotionEvent(event, displayId)
    }

    /**
     * Convenience method to inject motion event using displayId from the event itself.
     */
    fun injectMotionEvent(event: MotionEventBean?) {
        event?.let {
            service?.injectMotionEvent(it, it.displayId)
        }
    }

    override fun injectKeyEvent(keyCode: Int, displayId: Int) {
        service?.injectKeyEvent(keyCode, displayId)
    }

    override fun moveTaskToDisplay(taskId: Int, displayId: Int) {
        service?.moveTaskToDisplay(taskId, displayId)
    }

    override fun startActivityOnDisplay(componentName: ComponentName?, userId: Int, displayId: Int) {
        service?.startActivityOnDisplay(componentName, userId, displayId)
    }

    override fun collapseStatusBarPanel() {
        service?.collapseStatusBarPanel()
    }

    override fun getOpenWindowCount(): Int {
        return service?.openWindowCount ?: 0
    }

    override fun isServiceReady(): Boolean {
        return service?.isServiceReady == true
    }

    override fun asBinder(): IBinder? {
        return service?.asBinder()
    }
}
