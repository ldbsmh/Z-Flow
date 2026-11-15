package io.relimus.zflow.service

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import io.relimus.zflow.bean.MotionEventBean

/**
 * 小窗后端的应用侧入口。
 *
 * 旧实现是 system_server 里的 VirtualDisplay + IFreeformManager AIDL；
 * 现在 system_server 侧只跑 [io.relimus.zflow.xposed.nativewin.ZFlowWindowModule]，
 * 由它暴露一个 [IBinder]（OpenPort），通过 ContentProvider `call` 交给应用进程。
 *
 * 协议与 [io.relimus.zflow.xposed.nativewin.OpenPort] 一一对应：
 *  OPEN(0) / OPEN_PENDING(1) / CLOSE(2) / STATUS(3) / OPEN_PENDING_INTENT(4)。
 */
object FreeformManagerProxy : IBinder.DeathRecipient {

    private const val TAG = "FreeformManagerProxy"
    private const val DESCRIPTOR = "io.relimus.zflow.nativewin.OPEN"
    private const val FIRST = IBinder.FIRST_CALL_TRANSACTION
    private const val OPEN = FIRST
    private const val OPEN_PENDING = FIRST + 1
    private const val CLOSE = FIRST + 2
    private const val STATUS = FIRST + 3
    private const val OPEN_PENDING_INTENT = FIRST + 4

    @Volatile
    private var port: IBinder? = null

    val isConnected: Boolean
        get() = port?.isBinderAlive == true

    /** system_server 侧通过 ContentProvider 回传的 binder。 */
    fun linkService(binder: IBinder) {
        val previous = port
        if (previous !== binder) runCatching { previous?.unlinkToDeath(this, 0) }
        port = binder
        runCatching { binder.linkToDeath(this, 0) }
        Log.d(TAG, "window backend linked")
    }

    override fun binderDied() {
        port = null
        Log.e(TAG, "window backend binder died")
    }

    private fun token(): String = "zflow:" + java.util.UUID.randomUUID()

    private fun call(code: Int, block: (Parcel) -> Unit): Parcel? {
        val binder = port?.takeIf { it.isBinderAlive } ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            block(data)
            if (!binder.transact(code, data, reply, 0)) return null
            reply.readException()
            reply
        } catch (t: Throwable) {
            Log.e(TAG, "window backend call failed code=$code", t)
            null
        } finally {
            data.recycle()
        }
    }

    /** 普通小窗：按组件/包名打开。 */
    fun createWindow(
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
        dockStyle: Int,
        imeDoubleTapRetrigger: Boolean
    ) = open(componentName, pendingIntent, taskId, mini = false)

    /** 迷你窗：先按普通小窗接管，再由后端切到迷你形态。 */
    fun createMiniWindow(
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
        dockStyle: Int,
        imeDoubleTapRetrigger: Boolean
    ) = open(componentName, pendingIntent, taskId, mini = true)

    private fun open(componentName: ComponentName?, pendingIntent: PendingIntent?, taskId: Int, mini: Boolean) {
        val pkg = componentName?.packageName ?: pendingIntent?.creatorPackage ?: return
        val intent = componentName?.let { Intent().setComponent(it) }
        val request = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().apply {
            if (pendingIntent != null) setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
            )
        }.toBundle()
        val code = if (pendingIntent != null) OPEN_PENDING_INTENT else OPEN
        val reply = call(code) { data ->
            data.writeString(pkg)
            data.writeString(if (mini) "mini:$pkg" else "pkg:$pkg")
            data.writeInt(if (mini) 1 else 0)
            data.writeTypedObject(request, 0)
            data.writeString(null)
            data.writeTypedObject(options, 0)
            data.writeInt(taskId)
        } ?: return
        reply.recycle()
    }

    fun destroyWindow(displayId: Int) = Unit

    /** 新后端没有 displayId 概念，全部关闭留给进程重启回收。 */
    fun destroyAllWindows() = Unit

    /** 关闭指定包名的小窗（设置页/悬浮窗入口用）。 */
    fun closePackage(packageName: String?) {
        if (packageName.isNullOrBlank()) return
        call(CLOSE) { data ->
            data.writeString("pkg:" + packageName)
            data.writeString(packageName)
        }?.recycle()
    }

    fun moveWindowToTop(displayId: Int) = Unit

    fun injectMotionEvent(event: MotionEventBean?, displayId: Int) = Unit

    fun injectKeyEvent(keyCode: Int, displayId: Int) = Unit

    fun moveTaskToDisplay(taskId: Int, displayId: Int) = Unit

    fun startActivityOnDisplay(componentName: ComponentName?, userId: Int, displayId: Int) {
        componentName?.let { createWindow(it, null, userId, -1, 0, 0, 0, 0, 0, false, -1, 0, 0, 0, false) }
    }

    fun sendPendingIntentOnDisplay(pendingIntent: PendingIntent?, displayId: Int, taskId: Int) {
        pendingIntent?.let { open(it.creatorPackage?.let { pkg -> ComponentName(pkg, "") }, it, taskId, mini = false) }
    }

    /** 按请求关闭：新后端按会话 token 关闭；这里退化为「关闭指定包名的窗口」。 */
    fun close(packageName: String?) = Unit

    fun collapseStatusBarPanel() = Unit

    fun getOpenWindowCount(): Int {
        val reply = call(STATUS) { } ?: return 0
        return try { reply.readInt(); reply.readInt() } catch (t: Throwable) { 0 } finally { reply.recycle() }
    }

    fun isServiceReady(): Boolean = isConnected
}
