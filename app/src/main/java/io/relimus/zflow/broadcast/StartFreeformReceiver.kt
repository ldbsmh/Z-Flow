package io.relimus.zflow.broadcast

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import io.relimus.zflow.service.FreeformRequestHandler
import io.relimus.zflow.xposed.services.FreeformService

/**
 * 小窗请求广播（io.relimus.zflow.start_freeform）的解析与分发。
 *
 * ## 鉴权
 * 不使用 action 名或包名做“校验”——那只是约定不是权限。改为让系统在
 * **拉起接收器之前**校验发送方：
 *  - 动态注册：[register] 带 [PERMISSION_DUMP]；
 *  - 静态声明：[FreeformRequestReceiver] 在 Manifest 上声明同一权限。
 *
 * android.permission.DUMP（signature|privileged）恰好覆盖全部实际发送方
 * （system_server / root / adb），而普通第三方应用签不出、拿不到。
 *
 * ## 背压
 * 这里同步完成参数解析后交给 [FreeformRequestHandler] 的有界单线程队列，
 * 不在这里读数据库、建窗口。
 */
class StartFreeformReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != null && intent.action != ACTION_START) {
            Log.w(TAG, "unexpected action=${intent.action}")
            return
        }
        dispatch(context, intent)
    }

    companion object {
        private const val TAG = "StartFreeformReceiver"

        /** 发送方必须持有的权限，见类注释。 */
        const val PERMISSION_DUMP = "android.permission.DUMP"

        const val ACTION_START = "io.relimus.zflow.start_freeform"

        const val EXTRA_PACKAGE_NAME = "packageName"
        const val EXTRA_ACTIVITY_NAME = "activityName"
        const val EXTRA_USER_ID = "userId"
        const val EXTRA_MINI_MODE = "mini_mode"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_SOURCE_ROTATION = "source_rotation"
        const val EXTRA_SOURCE_SCREEN_WIDTH = "source_screen_width"
        const val EXTRA_SOURCE_SCREEN_HEIGHT = "source_screen_height"

        const val SOURCE_POPUP = "popup"
        const val SOURCE_RECENT_SWIPE = "recent_swipe"
        const val SOURCE_NOTIFICATION = "notification"

        fun filter(): IntentFilter = IntentFilter(ACTION_START)

        /**
         * 统一注册入口：动态接收器一律带 [PERMISSION_DUMP]。
         * 返回注册成功的实例，失败（例如系统不允许）返回 null 而不是抛异常，
         * 避免保活服务因此崩溃。
         */
        fun register(context: Context): StartFreeformReceiver? {
            val receiver = StartFreeformReceiver()
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(
                        receiver,
                        filter(),
                        PERMISSION_DUMP,
                        null,
                        Context.RECEIVER_EXPORTED
                    )
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    context.registerReceiver(receiver, filter(), PERMISSION_DUMP, null)
                }
                receiver
            } catch (t: Throwable) {
                Log.e(TAG, "register freeform receiver failed", t)
                null
            }
        }

        /** 解析广播参数并交给统一出口。动态与静态接收器共用这一份逻辑。 */
        fun dispatch(context: Context, intent: Intent) {
            val appContext = context.applicationContext ?: context

            val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
            val activityName = intent.getStringExtra(EXTRA_ACTIVITY_NAME)
            val rawUserId = intent.getIntExtra(EXTRA_USER_ID, -1)
            val miniMode = intent.getBooleanExtra(EXTRA_MINI_MODE, false)
            val taskId = intent.getIntExtra(FreeformService.EXTRA_TASK_ID, -1)
            val source = intent.getStringExtra(EXTRA_SOURCE).orEmpty()

            val sourceRotation = intent.getIntExtra(EXTRA_SOURCE_ROTATION, -1)
            val sourceScreenWidth = intent.getIntExtra(EXTRA_SOURCE_SCREEN_WIDTH, 0)
            val sourceScreenHeight = intent.getIntExtra(EXTRA_SOURCE_SCREEN_HEIGHT, 0)

            val pendingIntent = intent.getParcelableExtra(
                FreeformService.EXTRA_PENDING_INTENT,
                PendingIntent::class.java
            )

            var target = Intent()
            if (!packageName.isNullOrEmpty() && !activityName.isNullOrEmpty()) {
                target = Intent(Intent.ACTION_MAIN)
                    .setComponent(ComponentName(packageName, activityName))
                    .setPackage(packageName)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
            }
            // 旧路径可能直接塞了一个完整的启动 Intent，优先使用它
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                ?.let { target = it }

            val userId = if (rawUserId >= 0) rawUserId else 0

            val queued = FreeformRequestHandler.enqueueFromBroadcast(
                context = appContext,
                target = target,
                packageName = packageName,
                userId = userId,
                taskId = taskId,
                miniMode = miniMode,
                pendingIntent = pendingIntent,
                source = source,
                sourceRotation = sourceRotation,
                sourceScreenWidth = sourceScreenWidth,
                sourceScreenHeight = sourceScreenHeight
            )
            if (!queued) {
                Log.w(TAG, "freeform request dropped (queue full / duplicate / no target)")
            }
        }
    }
}
