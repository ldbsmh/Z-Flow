package com.sunshine.freeform.ui.freeform

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.os.IBinder
import com.sunshine.freeform.app.MiFreeform

/**
 * FreeformService - now delegates to FreeformManagerProxy for window creation in system_server.
 * The actual freeform window is created and managed by FreeformManager running in system_server,
 * which provides higher z-order priority for floating windows.
 */
class FreeformService: Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null)
            return START_NOT_STICKY

        val proxy = MiFreeform.me.freeformManagerProxy
        if (!proxy.isConnected) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_START_INTENT -> {
                val userId = intent.getIntExtra(Intent.EXTRA_USER, 0)
                // Support both EXTRA_COMPONENT_NAME and EXTRA_INTENT for compatibility
                var componentName = intent.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName::class.java)
                if (componentName == null) {
                    val innerIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    componentName = innerIntent?.component
                }
                val taskId = intent.getIntExtra(EXTRA_TASK_ID, -1)

                // 从 SharedPreferences 读取配置
                val sp = getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, MODE_PRIVATE)
                val freeformDpi = sp.getInt("freeform_scale", FreeformHelper.getScreenDpi(this))
                val freeformSize = sp.getInt("freeform_size", 75)
                val floatViewSize = sp.getInt("freeform_float_view_size", 25)
                val dimAmount = sp.getInt("freeform_dimming_amount", 20)

                proxy.createWindow(componentName, userId, taskId, freeformDpi, freeformSize, floatViewSize, dimAmount)
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
        const val SHELL = "com.android.shell"

        const val ACTION_START_INTENT = "com.sunshine.freeform.action.start.intent"
        const val ACTION_CALL_INTENT = "com.sunshine.freeform.action.call.intent"
        const val ACTION_DESTROY_FREEFORM = "com.sunshine.freeform.action.destroy.freeform"

        const val EXTRA_DISPLAY_ID = "com.sunshine.freeform.action.intent.display.id"
        const val EXTRA_TASK_ID = "task_id"
    }
}
