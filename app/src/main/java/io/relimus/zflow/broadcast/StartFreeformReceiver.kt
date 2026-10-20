package io.relimus.zflow.broadcast

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import io.relimus.zflow.service.FreeformRequestHandler
import io.relimus.zflow.xposed.hook.utils.XLog
import io.relimus.zflow.xposed.services.FreeformService

/**
 * 小窗请求广播（io.relimus.zflow.start_freeform）的解析与分发。
 *
 * ## 鉴权（重要：不要再用权限保护这个接收器）
 *
 * 广播的发送方全部是**被 Hook 的宿主进程**：
 *  - 纯血 Launcher（桌面长按 SystemShortcut）
 *  - SystemUI（最近任务上滑手势）
 *  - 任意第三方应用进程（通知按钮）
 *
 * 这些进程都没有 `android.permission.DUMP`，它们在 JVM 层调用
 * `sendBroadcast` 是不会因权限而抛异常的——权限不满足时取消发生在
 * system_server（AMS.broadcastIntentLocked），表现为“静默丢弃 + 一条
 * Permission Denial 日志”。App 侧完全收不到，用户看到的就是：
 *  - 手势上滑没有小窗；
 *  - 桌面长按“打开小窗”菜单直接掉回系统默认行为（拉起应用商店等）。
 *
 * 所以这里用**显式标记 + 包名校验 + 载荷校验**做防护，而不是系统权限：
 *  - 只接受 [ACTION_START]；
 *  - 只接受带 [EXTRA_SENDER] 且值为 [TARGET_PACKAGE] 的广播
 *    （第三方随手 `sendBroadcast(action)` 伪造不了，需要知道内部标记）；
 *  - 目标 component 必须能解析出启动 Activity 或自带完整 Intent；
 *  - 需要更严格的场景（外部 ADB/自动化投递）见 [EXTRA_TOKEN]。
 *
 * 即使被伪造，最坏结果也只是“打开一个普通应用的窗口”，
 * 不会越权执行其它操作——真正的窗口管理仍在 system_server 侧，
 * 由 IFreeformManager 的调用方身份决定。
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

        const val TARGET_PACKAGE = "io.relimus.zflow"

        /**
         * @deprecated 曾经的广播权限。
         * 实际发送方（Launcher / SystemUI / 第三方应用进程）都不持有 DUMP，
         * 用它只会让所有入口被系统静默丢弃。保留常量仅为消除旧引用，
         * 不要再用它注册接收器或作为发送前置条件。
         */
        @Deprecated("DUMP 保护会丢弃所有真实发送方，改用 EXTRA_SENDER 标记校验")
        const val PERMISSION_DUMP = "android.permission.DUMP"

        const val ACTION_START = "io.relimus.zflow.start_freeform"

        /** 内部标记：表示这条广播来自 Z-Flow 自己的 Hook（非第三方伪造）。 */
        const val EXTRA_SENDER = "io.relimus.zflow.extra.sender"

        const val EXTRA_PACKAGE_NAME = "packageName"
        const val EXTRA_ACTIVITY_NAME = "activityName"
        const val EXTRA_USER_ID = "userId"
        const val EXTRA_MINI_MODE = "mini_mode"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_SOURCE_ROTATION = "source_rotation"
        const val EXTRA_SOURCE_SCREEN_WIDTH = "source_screen_width"
        const val EXTRA_SOURCE_SCREEN_HEIGHT = "source_screen_height"

        /** 预留给外部（ADB / 自动化）投递时的口令位，当前不校验值，仅占位。 */
        const val EXTRA_TOKEN = "io.relimus.zflow.extra.token"

        const val SOURCE_POPUP = "popup"
        const val SOURCE_RECENT_SWIPE = "recent_swipe"
        const val SOURCE_NOTIFICATION = "notification"

        fun filter(): IntentFilter = IntentFilter(ACTION_START)

        /**
         * 构造一条合法的请求广播。
         * 所有发送方都必须经过这里，避免漏带 [EXTRA_SENDER] 被接收端丢掉。
         */
        fun newIntent(
            packageName: String? = null,
            activityName: String? = null,
            userId: Int = 0,
            miniMode: Boolean = false,
            taskId: Int = -1,
            source: String = "",
            sourceRotation: Int = -1,
            sourceScreenWidth: Int = 0,
            sourceScreenHeight: Int = 0
        ): Intent = Intent(ACTION_START).apply {
            setPackage(TARGET_PACKAGE)
            putExtra(EXTRA_SENDER, TARGET_PACKAGE)
            if (packageName != null) putExtra(EXTRA_PACKAGE_NAME, packageName)
            if (activityName != null) putExtra(EXTRA_ACTIVITY_NAME, activityName)
            putExtra(EXTRA_USER_ID, userId)
            putExtra(EXTRA_MINI_MODE, miniMode)
            putExtra(FreeformService.EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_SOURCE, source)
            putExtra(EXTRA_SOURCE_ROTATION, sourceRotation)
            putExtra(EXTRA_SOURCE_SCREEN_WIDTH, sourceScreenWidth)
            putExtra(EXTRA_SOURCE_SCREEN_HEIGHT, sourceScreenHeight)
        }

        /**
         * 统一注册入口。
         *
         * 注意：[Context.RECEIVER_EXPORTED] 是必需的——广播来自 Launcher /
         * SystemUI / 第三方进程，NOT_EXPORTED 会让它们全部收不到。
         */
        fun register(context: Context): StartFreeformReceiver? {
            val receiver = StartFreeformReceiver()
            return try {
                context.registerReceiver(
                    receiver,
                    filter(),
                    null,
                    null,
                    Context.RECEIVER_EXPORTED
                )
                receiver
            } catch (t: Throwable) {
                XLog.e("$TAG register freeform receiver failed", t)
                null
            }
        }

        /** 解析广播参数并交给统一出口。动态与静态接收器共用这一份逻辑。 */
        fun dispatch(context: Context, intent: Intent) {
            val appContext = context.applicationContext ?: context

            // ---- 来源校验：没有内部标记的一律丢弃 ----
            val sender = intent.getStringExtra(EXTRA_SENDER)
            if (sender != TARGET_PACKAGE) {
                XLog.w("$TAG drop broadcast without sender marker (sender=$sender, source=${intent.getStringExtra(EXTRA_SOURCE)})")
                return
            }

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
                Log.w(TAG, "freeform request dropped (duplicate / no target / manager offline)")
            }
        }
    }
}
