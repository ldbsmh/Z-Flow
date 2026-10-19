package io.relimus.zflow.xposed.services

import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.os.IBinder
import io.relimus.zflow.app.ZFlow
import io.relimus.zflow.broadcast.StartFreeformReceiver
import io.relimus.zflow.room.DatabaseRepository
import io.relimus.zflow.service.FreeformManagerProxy
import io.relimus.zflow.xposed.hook.utils.XLog

/**
 * 小窗请求的 App 侧入口。
 *
 * 三条实际调用链（都在本应用进程或 system_server 内发起）：
 * 1. Xposed 手势 / 桌面长按 → 广播 `io.relimus.zflow.start_freeform`
 *    → [StartFreeformReceiver] → startService(本类)
 * 2. 悬浮窗选择器 / 全部应用列表 → startService(本类)
 * 3. 通知钩子 → startService(本类)
 *
 * 本类只做“转发”：真正创建窗口的是 system_server 里的 [FreeformManager]，
 * 这里通过 [FreeformManagerProxy] 走 AIDL。
 *
 * 安全约束：本类在 Manifest 中声明为 `exported="false"`，
 * 外部应用无法伪造 taskId / component 来驱动 system_server 的窗口管理。
 * 需要从 system_server 或其他进程投递请求时请走 [StartFreeformReceiver] 广播
 * （广播带签名级校验），不要重新开放 exported。
 */
class FreeformService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val proxy = ZFlow.me.freeformManagerProxy
        if (!proxy.isConnected) {
            // system_server 侧尚未连上（Xposed 未激活或开机未完成），
            // 直接丢弃请求，避免后续 NPE。
            XLog.w("FreeformService: FreeformManager not connected, drop action=${intent.action}")
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_START_INTENT -> handleStartIntent(intent, proxy)
            ACTION_DESTROY_FREEFORM -> {
                val displayId = intent.getIntExtra(EXTRA_DISPLAY_ID, -1)
                if (displayId >= 0) proxy.destroyWindow(displayId) else proxy.destroyAllWindows()
            }
            else -> XLog.w("FreeformService: ignored action=${intent.action}")
        }

        stopSelf()
        return START_NOT_STICKY
    }

    private fun handleStartIntent(
        intent: Intent,
        proxy: FreeformManagerProxy
    ) {
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

        val sourceRotation =
            intent.getIntExtra(StartFreeformReceiver.EXTRA_SOURCE_ROTATION, -1)
        val sourceScreenWidth =
            intent.getIntExtra(StartFreeformReceiver.EXTRA_SOURCE_SCREEN_WIDTH, 0)
        val sourceScreenHeight =
            intent.getIntExtra(StartFreeformReceiver.EXTRA_SOURCE_SCREEN_HEIGHT, 0)

        val packageName = componentName?.packageName
        if (packageName.isNullOrBlank()) {
            XLog.w("FreeformService: missing target component, action=${intent.action}")
            return
        }

        // 兜底再查一次黑名单：广播入口已查过，但悬浮窗 / 通知入口不一定查。
        // isBlacklisted 是同步调用，本 Service 由 Binder 线程执行，不阻塞主线程。
        if (DatabaseRepository(this).isBlacklisted(packageName, userId)) {
            XLog.d("FreeformService: $packageName user=$userId is blacklisted")
            return
        }

        val sp = getSharedPreferences(ZFlow.APP_SETTINGS_NAME, MODE_PRIVATE)
        // DPI 由 system_server 中的 FreeformManager 强制跟随物理屏幕，
        // 这里只保留配置项传参，实际不影响 VirtualDisplay DPI。
        val freeformDpi = sp.getInt("freeform_scale", resources.displayMetrics.densityDpi)
        val freeformSize = sp.getInt("freeform_size", 75)
        val freeformSizeLand = sp.getInt("freeform_size_land", 90)
        val floatViewSize = sp.getInt("freeform_float_view_size", 25)
        val dimAmount = sp.getInt("freeform_dimming_amount", 20)
        val manualAdjustFreeformRotation = sp.getBoolean("manual_adjust_freeform_rotation", false)
        val dockStyle = sp.getInt("freeform_dock_style", 100)

        XLog.d(
            "FreeformService: create component=$componentName userId=$userId taskId=$taskId " +
                "miniMode=$miniMode rotation=$sourceRotation screen=${sourceScreenWidth}x$sourceScreenHeight"
        )

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

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START_INTENT = "io.relimus.zflow.action.start.intent"
        const val ACTION_DESTROY_FREEFORM = "io.relimus.zflow.action.destroy.freeform"

        const val EXTRA_DISPLAY_ID = "io.relimus.zflow.action.intent.display.id"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_PENDING_INTENT = "notification_content_intent"
    }
}
