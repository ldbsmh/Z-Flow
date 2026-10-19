package io.relimus.zflow.broadcast

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Parcelable
import android.util.Log
import io.relimus.zflow.room.DatabaseRepository
import io.relimus.zflow.service.FreeformRequestHandler
import io.relimus.zflow.xposed.services.FreeformService

/**
 * 小窗启动广播接收端。
 *
 * 发送方是 Xposed 手势 hook / 桌面长按 hook（可能位于 system_server 或被 hook 的
 * 第三方进程），因此不能直接 startService 本应用的内部组件，统一由本接收器
 * 做校验后交给 [FreeformRequestHandler]。
 *
 * onReceive 在主线程串行派发，这里用 goAsync + 后台线程避免耗时读库卡住广播队列。
 */
class StartFreeformReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        val activityName = intent.getStringExtra(EXTRA_ACTIVITY_NAME)
        val userId = intent.getIntExtra(EXTRA_USER_ID, -1)
        val miniMode = intent.getBooleanExtra(EXTRA_MINI_MODE, false)
        val taskId = intent.getIntExtra(FreeformService.EXTRA_TASK_ID, -1)

        // ===== 读取来源方向信息 =====
        val sourceRotation = intent.getIntExtra(EXTRA_SOURCE_ROTATION, -1)
        val sourceScreenWidth = intent.getIntExtra(EXTRA_SOURCE_SCREEN_WIDTH, 0)
        val sourceScreenHeight = intent.getIntExtra(EXTRA_SOURCE_SCREEN_HEIGHT, 0)

        val parcelable = intent.getParcelableExtra(Intent.EXTRA_INTENT, Parcelable::class.java)

        var target = Intent()
        if (packageName != null && activityName != null) {
            target = Intent(Intent.ACTION_MAIN)
                .setComponent(ComponentName(packageName, activityName))
                .setPackage(packageName)
                .addCategory(Intent.CATEGORY_LAUNCHER)
        }
        if (parcelable is Intent) {
            target = parcelable
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val pendingIntent = intent.getParcelableExtra(
            FreeformService.EXTRA_PENDING_INTENT, android.app.PendingIntent::class.java
        )

        executor.execute runnable@{
            try {
                val effectiveUserId = if (userId >= 0) userId else 0
                val targetPackage = target.component?.packageName
                    ?: target.`package`
                    ?: packageName

                if (!targetPackage.isNullOrBlank() &&
                    DatabaseRepository(appContext).isBlacklisted(targetPackage, effectiveUserId)
                ) {
                    return@runnable
                }

                FreeformRequestHandler.handle(
                    context = appContext,
                    target = target,
                    userId = effectiveUserId,
                    taskId = taskId,
                    miniMode = miniMode,
                    pendingIntent = pendingIntent,
                    sourceRotation = sourceRotation,
                    sourceScreenWidth = sourceScreenWidth,
                    sourceScreenHeight = sourceScreenHeight
                )
            } catch (t: Throwable) {
                Log.w(TAG, "start freeform failed pkg=$packageName", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "StartFreeformReceiver"

        /**
         * 广播可能被连发（手势 + 桌面长按），单线程串行执行保证小窗创建顺序。
         */
        private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "start-freeform").apply { isDaemon = true }
        }

        const val ACTION_START = "io.relimus.zflow.start_freeform"

        const val EXTRA_PACKAGE_NAME = "packageName"
        const val EXTRA_ACTIVITY_NAME = "activityName"
        const val EXTRA_USER_ID = "userId"

        const val EXTRA_MINI_MODE = "mini_mode"
        const val EXTRA_SOURCE = "source"

        const val SOURCE_POPUP = "popup"
        const val SOURCE_RECENT_SWIPE = "recentSwipe"

        // ===== 来源方向信息常量 =====
        const val EXTRA_SOURCE_ROTATION = "sourceRotation"
        const val EXTRA_SOURCE_SCREEN_WIDTH = "sourceScreenWidth"
        const val EXTRA_SOURCE_SCREEN_HEIGHT = "sourceScreenHeight"
    }
}
