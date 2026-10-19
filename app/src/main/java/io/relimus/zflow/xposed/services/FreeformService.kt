package io.relimus.zflow.xposed.services

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import io.relimus.zflow.app.ZFlow
import io.relimus.zflow.broadcast.StartFreeformReceiver
import io.relimus.zflow.providers.BlacklistProvider
import io.relimus.zflow.room.DatabaseRepository
import io.relimus.zflow.xposed.hook.utils.XLog

/**
 * 小窗请求的 App 侧接收端。
 *
 * 状态：目前小窗的创建请求来自 system_server（StartFreeformReceiver / 手势 hook），
 * App 进程内没有调用方直接向本 Service 发请求。为保证 Manifest 与实际行为一致，
 * 本类只保留 destroy 能力，ACTION_START_INTENT 一律拒绝，且不在 Manifest 中声明。
 *
 * 如需从 App 进程直接发起小窗，请改走 FreeformLauncher（内部走广播），
 * 不要重新开放 exported Service —— 那会让任意第三方应用伪造 taskId/component
 * 来驱动 system_server 的窗口管理。
 */
@Deprecated("不再作为 exported Service 使用；请使用 FreeformLauncher / StartFreeformReceiver")
class FreeformService : android.app.Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val proxy = ZFlow.me.freeformManagerProxy
        when {
            intent == null || !proxy.isConnected -> {
                stopSelf()
                return START_NOT_STICKY
            }
            intent.action == ACTION_DESTROY_FREEFORM -> {
                val displayId = intent.getIntExtra(EXTRA_DISPLAY_ID, -1)
                if (displayId >= 0) proxy.destroyWindow(displayId) else proxy.destroyAllWindows()
            }
            intent.action == ACTION_START_INTENT ->
                Log.w(TAG, "ignored legacy start intent: request must come via system_server")
            else -> Log.w(TAG, "ignored unknown action=${intent.action}")
        }
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "FreeformService"

        const val ACTION_START_INTENT = "io.relimus.zflow.action.start.intent"
        const val ACTION_DESTROY_FREEFORM = "io.relimus.zflow.action.destroy.freeform"

        const val EXTRA_DISPLAY_ID = "io.relimus.zflow.action.intent.display.id"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_PENDING_INTENT = "notification_content_intent"
    }
}
