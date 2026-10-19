package io.relimus.zflow.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import io.relimus.zflow.app.ZFlow
import io.relimus.zflow.broadcast.StartFreeformReceiver
import io.relimus.zflow.room.DatabaseRepository
import io.relimus.zflow.xposed.hook.utils.XLog

/**
 * 小窗请求的统一出口。
 *
 * 所有入口（Xposed 手势广播、桌面长按广播、悬浮窗选择器、通知钩子）
 * 最终都走这里，直接调用 [FreeformManagerProxy] 转发到 system_server 的
 * [io.relimus.zflow.xposed.services.FreeformManager]，不再经过可被外部
 * startService 的组件。
 *
 * 调用线程：除 [handleBroadcast] 外均为调用方线程；内部只做一次同步 DB count
 * 和一次 SharedPreferences 读取（已进程内缓存），耗时在毫秒级。
 */
object FreeformRequestHandler {

    private const val TAG = "FreeformRequest"

    // ========== 入口 1：Intent（Service / 悬浮窗 / 通知钩子） ==========

    /**
     * 从已构造好的 startService Intent 中解析参数并创建窗口。
     * `action` 只认 [io.relimus.zflow.xposed.services.FreeformService.ACTION_START_INTENT]。
     */
    fun handleIntent(context: Context, intent: Intent): Boolean {
        val action = intent.action
        val proxy = ZFlow.me.freeformManagerProxy

        if (action == io.relimus.zflow.xposed.services.FreeformService.ACTION_DESTROY_FREEFORM) {
            if (!proxy.isConnected) return false
            val displayId = intent.getIntExtra(
                io.relimus.zflow.xposed.services.FreeformService.EXTRA_DISPLAY_ID, -1
            )
            if (displayId >= 0) proxy.destroyWindow(displayId) else proxy.destroyAllWindows()
            return true
        }

        var componentName = intent.getParcelableExtra(
            Intent.EXTRA_COMPONENT_NAME, ComponentName::class.java
        )
        val innerIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        if (componentName == null) componentName = innerIntent?.component

        return create(
            context = context,
            componentName = componentName,
            pendingIntent = intent.getParcelableExtra(
                io.relimus.zflow.xposed.services.FreeformService.EXTRA_PENDING_INTENT,
                PendingIntent::class.java
            ),
            userId = intent.getIntExtra(Intent.EXTRA_USER, 0),
            taskId = intent.getIntExtra(
                io.relimus.zflow.xposed.services.FreeformService.EXTRA_TASK_ID, -1
            ),
            miniMode = intent.getBooleanExtra(StartFreeformReceiver.EXTRA_MINI_MODE, false),
            sourceRotation = intent.getIntExtra(
                StartFreeformReceiver.EXTRA_SOURCE_ROTATION, -1
            ),
            sourceScreenWidth = intent.getIntExtra(
                StartFreeformReceiver.EXTRA_SOURCE_SCREEN_WIDTH, 0
            ),
            sourceScreenHeight = intent.getIntExtra(
                StartFreeformReceiver.EXTRA_SOURCE_SCREEN_HEIGHT, 0
            )
        )
    }

    /**
     * 兼容旧签名：广播接收器解析完字段后直接调用。
     */
    fun handle(
        context: Context,
        target: Intent,
        userId: Int,
        taskId: Int,
        miniMode: Boolean,
        pendingIntent: PendingIntent?,
        sourceRotation: Int,
        sourceScreenWidth: Int,
        sourceScreenHeight: Int
    ): Boolean = create(
        context = context,
        componentName = target.component,
        pendingIntent = pendingIntent,
        userId = userId,
        taskId = taskId,
        miniMode = miniMode,
        sourceRotation = sourceRotation,
        sourceScreenWidth = sourceScreenWidth,
        sourceScreenHeight = sourceScreenHeight
    )

    // ========== 入口 2：悬浮窗 / 列表直接点选 ==========

    /**
     * 悬浮窗选择器与全部应用列表使用：给定包名 + Activity 直接开小窗。
     * 这些调用点原本 startService 后立刻关闭悬浮窗，这里改成同步转发，
     * 结果一致但少一次组件启动（在 Android 12+ 后台启动限制下更可靠）。
     */
    fun launch(
        context: Context,
        packageName: String,
        activityName: String?,
        userId: Int
    ): Boolean {
        val component = activityName?.let { ComponentName(packageName, it) }
        if (component == null) {
            XLog.w("$TAG launch: empty activityName for $packageName")
            return false
        }
        return create(
            context = context,
            componentName = component,
            pendingIntent = null,
            userId = userId,
            taskId = -1,
            miniMode = false,
            sourceRotation = -1,
            sourceScreenWidth = 0,
            sourceScreenHeight = 0
        )
    }

    // ========== 核心 ==========

    fun create(
        context: Context,
        componentName: ComponentName?,
        pendingIntent: PendingIntent?,
        userId: Int,
        taskId: Int,
        miniMode: Boolean,
        sourceRotation: Int,
        sourceScreenWidth: Int,
        sourceScreenHeight: Int
    ): Boolean {
        val packageName = componentName?.packageName
        if (packageName.isNullOrBlank()) {
            XLog.w("$TAG create: missing component")
            return false
        }

        val proxy = ZFlow.me.freeformManagerProxy
        if (!proxy.isConnected) {
            XLog.w("$TAG create: FreeformManager not connected (LSPosed 未激活或系统服务未就绪)")
            return false
        }

        if (DatabaseRepository(context).isBlacklisted(packageName, userId)) {
            XLog.d("$TAG create: $packageName user=$userId blacklisted")
            return false
        }

        val sp = context.getSharedPreferences(ZFlow.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
        val freeformDpi = sp.getInt("freeform_scale", 0).takeIf { it > 0 }
            ?: context.resources.displayMetrics.densityDpi
        val freeformSize = sp.getInt("freeform_size", 75)
        val freeformSizeLand = sp.getInt("freeform_size_land", 90)
        val floatViewSize = sp.getInt("freeform_float_view_size", 25)
        val dimAmount = sp.getInt("freeform_dimming_amount", 20)
        val manualAdjustFreeformRotation = sp.getBoolean("manual_adjust_freeform_rotation", false)
        val dockStyle = sp.getInt("freeform_dock_style", 100)

        XLog.d(
            "$TAG create component=$componentName userId=$userId taskId=$taskId " +
                "miniMode=$miniMode rotation=$sourceRotation " +
                "screen=${sourceScreenWidth}x$sourceScreenHeight"
        )

        if (miniMode) {
            proxy.createMiniWindow(
                componentName, pendingIntent, userId, taskId,
                freeformDpi, freeformSize, freeformSizeLand, floatViewSize,
                dimAmount, manualAdjustFreeformRotation,
                sourceRotation, sourceScreenWidth, sourceScreenHeight, dockStyle
            )
        } else {
            proxy.createWindow(
                componentName, pendingIntent, userId, taskId,
                freeformDpi, freeformSize, freeformSizeLand, floatViewSize,
                dimAmount, manualAdjustFreeformRotation,
                sourceRotation, sourceScreenWidth, sourceScreenHeight, dockStyle
            )
        }
        return true
    }
}
