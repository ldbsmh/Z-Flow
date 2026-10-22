package io.relimus.zflow.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import io.relimus.zflow.app.ZFlow
import io.relimus.zflow.broadcast.StartFreeformReceiver
import io.relimus.zflow.room.DatabaseRepository
import io.relimus.zflow.xposed.hook.utils.XLog
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 小窗请求的统一出口。
 *
 * 所有入口（Xposed 手势/桌面长按广播、静态广播接收器、悬浮窗选择器、通知钩子、
 * FreeformService）最终都走这里，直接调用 [FreeformManagerProxy] 转发到
 * system_server 的 [io.relimus.zflow.xposed.services.FreeformManager]。
 *
 * ## 队列策略（背压）
 * 窗口创建涉及跨进程窗口管理，不能并发执行，因此固定单线程串行。
 * 但队列**有界**：客户端伪造或手势连发时，超出 [MAX_PENDING] 的请求直接丢弃并记日志，
 * 而不是无限堆积 Intent / PendingResult 把进程内存吃光。
 *
 * 另外对相同 (component, userId, taskId) 的请求做**在途合并**：
 * 同一目标在小窗已经排队/创建时，重复请求不会再排一个。
 *
 * ## 线程模型
 * 除 [enqueueFromBroadcast] 外均为调用方线程；内部只做一次同步 DB count
 * 和一次 SharedPreferences 读取（进程内已缓存），耗时在毫秒级。
 */
object FreeformRequestHandler {

    private const val TAG = "FreeformRequest"

    /** 队列上限：正常用户操作远达不到，只有异常连发才会触发丢弃。 */
    private const val MAX_PENDING = 16

    private val requestGate = RequestGate()

    @Volatile
    var duplicateWindowMs = 1_200L

    /** 单线程 + 有界队列：超出上限的请求由调用线程自己执行 CallerRuns 会阻塞主线程，故直接拒绝。 */
    val executor: ThreadPoolExecutor = ThreadPoolExecutor(
        1, 1,
        30L, TimeUnit.SECONDS,
        LinkedBlockingQueue(MAX_PENDING),
        { r -> Thread(r, "freeform-request").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    ).apply { allowCoreThreadTimeOut(true) }

    // ========== 入口 1：广播（system_server / hook 进程） ==========

    /**
     * 广播入口：解析 → 排队 → 后台串行创建。
     *
     * @return false 表示被丢弃（队列已满或重复请求），调用方只需记日志。
     */
    fun enqueueFromBroadcast(
        context: Context,
        target: Intent,
        packageName: String?,
        userId: Int,
        taskId: Int,
        miniMode: Boolean,
        pendingIntent: PendingIntent?,
        source: String,
        sourceRotation: Int,
        sourceScreenWidth: Int,
        sourceScreenHeight: Int,
        onComplete: (() -> Unit)? = null
    ): Boolean {
        val resolvedComponent = target.component ?: resolveLauncherComponent(context, packageName)
        val pkg = resolvedComponent?.packageName ?: packageName
        if (pkg.isNullOrBlank()) {
            XLog.w("$TAG [source=$source] broadcast: missing target package")
            return false
        }

        /*
         * 去重键**故意不含 taskId**。
         *
         * 同一次上滑的重复提交之间只隔几十毫秒，而 getRunningTask 读到的 taskId
         * 在两次调用之间可能已经从 X 变成 X+1（最近任务正在切栈），
         * 带上 taskId 就等于把“同一个请求”算成两个不同请求，窗口会开两个。
         * 用户对同一个应用连点两次的场景由 [duplicateWindowMs] 的短窗口兜住。
         */
        val key = RequestGate.key(pkg, userId)
        val now = android.os.SystemClock.elapsedRealtime()
        if (!requestGate.acquire(key, now, duplicateWindowMs)) {
            XLog.w("$TAG [source=$source] duplicate request ignored $key")
            return false
        }

        XLog.i(
            "$TAG [source=$source] enqueue component=$resolvedComponent user=$userId " +
                "taskId=$taskId mini=$miniMode rotation=$sourceRotation"
        )
        XLog.ls(
            "in:enqueue source=$source component=$resolvedComponent user=$userId " +
                "taskId=$taskId mini=$miniMode rotation=$sourceRotation",
            tag = "ZFlowDiag"
        )

        return try {
            executor.execute {
                try {
                    val ok = create(
                        context = context,
                        componentName = resolvedComponent,
                        pendingIntent = pendingIntent,
                        userId = userId,
                        taskId = taskId,
                        miniMode = miniMode,
                        sourceRotation = sourceRotation,
                        sourceScreenWidth = sourceScreenWidth,
                        sourceScreenHeight = sourceScreenHeight
                    )
                    if (!ok) {
                        XLog.w("$TAG [source=$source] create rejected for $resolvedComponent user=$userId")
                    }
                } catch (t: Throwable) {
                    XLog.e("$TAG [source=$source] worker failed", t)
                } finally {
                    requestGate.complete(key)
                    onComplete?.invoke()
                }
            }
            true
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            requestGate.complete(key, retryable = true)
            XLog.w("$TAG [source=$source] queue full (${executor.queue.size}), drop $key")
            false
        } catch (t: Throwable) {
            requestGate.complete(key, retryable = true)
            XLog.e("$TAG [source=$source] enqueue failed", t)
            false
        }
    }

    /** 广播只给了包名时，回退到该包的启动 Activity。 */
    private fun resolveLauncherComponent(context: Context, packageName: String?): ComponentName? {
        if (packageName.isNullOrBlank()) return null
        return try {
            context.packageManager.getLaunchIntentForPackage(packageName)?.component
        } catch (t: Throwable) {
            XLog.w("$TAG resolve launcher failed for $packageName")
            null
        }
    }

    // ========== 入口 2：Intent（FreeformService / 通知钩子） ==========

    /**
     * 从已构造好的 startService Intent 中解析参数并创建窗口。
     * `action` 只认 [io.relimus.zflow.xposed.services.FreeformService.ACTION_START_INTENT]。
     */
    fun handleIntent(context: Context, intent: Intent): Boolean {
        val action = intent.action
        val proxy = FreeformManagerProxy

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

    // ========== 入口 3：悬浮窗 / 列表直接点选 ==========

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

        val proxy = FreeformManagerProxy
        if (!proxy.isConnected) {
            XLog.w("$TAG create: FreeformManager not connected (LSPosed 未激活或系统服务未就绪)")
            return false
        }

        // 说明：hook 侧只做“尽量过滤”，最终裁决在这里。
        // 这里必须真的 fail-closed，同时把原因写进 LSPosed 日志，
        // 否则数据库异常时会表现为“点了没反应且查不到原因”。
        if (DatabaseRepository(context).isBlacklistedOrBlock(packageName, userId)) {
            XLog.w("$TAG create: $packageName user=$userId blacklisted or db unavailable")
            return false
        }

        val sp = context.getSharedPreferences(ZFlow.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
        // 与 system_server 侧一致：DPI 实际跟随物理屏幕，这里仅作占位传递。
        val freeformDpi = sp.getInt("freeform_scale", context.resources.displayMetrics.densityDpi)
        val freeformSize = sp.getInt("freeform_size", 75)
        val freeformSizeLand = sp.getInt("freeform_size_land", 90)
        val floatViewSize = sp.getInt("freeform_float_view_size", 25)
        val dimAmount = sp.getInt("freeform_dimming_amount", 20)
        val manualAdjustFreeformRotation = sp.getBoolean("manual_adjust_freeform_rotation", false)
        val dockStyle = sp.getInt("freeform_dock_style", 100)

        XLog.i(
            "$TAG create component=$componentName userId=$userId taskId=$taskId " +
                "miniMode=$miniMode rotation=$sourceRotation " +
                "screen=${sourceScreenWidth}x$sourceScreenHeight"
        )
        XLog.ls(
            "in:create component=$componentName user=$userId taskId=$taskId " +
                "mini=$miniMode rotation=$sourceRotation " +
                "screen=${sourceScreenWidth}x$sourceScreenHeight",
            tag = "ZFlowDiag"
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
