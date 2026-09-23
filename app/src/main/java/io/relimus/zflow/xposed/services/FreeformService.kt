package io.relimus.zflow.xposed.services

import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.os.IBinder
import android.util.Log
import io.relimus.zflow.app.ZFlow
import io.relimus.zflow.broadcast.StartFreeformReceiver
import io.relimus.zflow.room.DatabaseRepository
import io.relimus.zflow.xposed.hook.utils.XLog

/**
 * FreeformService - now delegates to FreeformManagerProxy for window creation in system_server.
 * The actual freeform window is created and managed by FreeformManager running in system_server,
 * which provides higher z-order priority for floating windows.
 */
class FreeformService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            return START_NOT_STICKY
        }

        val proxy = ZFlow.me.freeformManagerProxy
        if (!proxy.isConnected) {
            stopSelf()
            return START_NOT_STICKY
        }


        when (intent.action) {
            ACTION_START_INTENT -> {
                val userId = intent.getIntExtra(Intent.EXTRA_USER, 0)

                var componentName =
                    intent.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName::class.java)
                if (componentName == null) {
                    val innerIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    componentName = innerIntent?.component
                }

                val pendingIntent = intent.getParcelableExtra(
                    EXTRA_PENDING_INTENT,
                    PendingIntent::class.java
                )
                val taskId = intent.getIntExtra(EXTRA_TASK_ID, -1)
                val miniMode = intent.getBooleanExtra(StartFreeformReceiver.EXTRA_MINI_MODE, false)

                val sourceRotation = intent.getIntExtra(StartFreeformReceiver.EXTRA_SOURCE_ROTATION, -1)
                val sourceScreenWidth = intent.getIntExtra(StartFreeformReceiver.EXTRA_SOURCE_SCREEN_WIDTH, 0)
                val sourceScreenHeight = intent.getIntExtra(StartFreeformReceiver.EXTRA_SOURCE_SCREEN_HEIGHT, 0)

                val packageName = componentName?.packageName
                if (!packageName.isNullOrBlank()) {
                    val repository = DatabaseRepository(this)
                    if (repository.isBlacklisted(packageName, userId)) {
                        stopSelf()
                        return START_NOT_STICKY
                    }
                }

                val sp = getSharedPreferences(ZFlow.APP_SETTINGS_NAME, MODE_PRIVATE)
                // 注意：DPI 现在由 system_server 中的 FreeformManager 强制跟随物理屏幕，
                // 这里只保留配置项以便界面显示，实际不会影响 VirtualDisplay DPI。
                val freeformDpi = sp.getInt("freeform_scale", resources.displayMetrics.densityDpi)
                val freeformSize = sp.getInt("freeform_size", 75)
                val freeformSizeLand = sp.getInt("freeform_size_land", 90)
                val floatViewSize = sp.getInt("freeform_float_view_size", 25)
                val dimAmount = sp.getInt("freeform_dimming_amount", 20)
                val manualAdjustFreeformRotation = sp.getBoolean("manual_adjust_freeform_rotation", false)
                val dockStyle = sp.getInt("freeform_dock_style", 100)

                XLog.d("FreeformService: request action=${intent.action} component=$componentName userId=$userId taskId=$taskId miniMode=$miniMode dpi=$freeformDpi rotation=$sourceRotation screen=${sourceScreenWidth}x$sourceScreenHeight")

                if (miniMode) {
                    proxy.createMiniWindow(
                        componentName,
                        pendingIntent,
                        userId,
                        taskId,
                        freeformDpi,
                        freeformSize,
                        freeformSizeLand,
                        floatViewSize,
                        dimAmount,
                        manualAdjustFreeformRotation,
                        sourceRotation,
                        sourceScreenWidth,
                        sourceScreenHeight,
                        dockStyle
                    )
                } else {
                    proxy.createWindow(
                        componentName,
                        pendingIntent,
                        userId,
                        taskId,
                        freeformDpi,
                        freeformSize,
                        freeformSizeLand,
                        floatViewSize,
                        dimAmount,
                        manualAdjustFreeformRotation,
                        sourceRotation,
                        sourceScreenWidth,
                        sourceScreenHeight,
                        dockStyle
                    )
                }
            }

            ACTION_DESTROY_FREEFORM -> {
                val displayId = intent.getIntExtra(EXTRA_DISPLAY_ID, -1)
                if (displayId >= 0) {
                    proxy.destroyWindow(displayId)
                } else {
                    proxy.destroyAllWindows()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    companion object {
        const val ACTION_START_INTENT = "io.relimus.zflow.action.start.intent"
        const val ACTION_DESTROY_FREEFORM = "io.relimus.zflow.action.destroy.freeform"

        const val EXTRA_DISPLAY_ID = "io.relimus.zflow.action.intent.display.id"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_PENDING_INTENT = "notification_content_intent"
    }
}