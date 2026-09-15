package io.relimus.zflow.xposed.ui.window

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.BitmapDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceControl
import android.view.TextureView
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.WindowManagerHidden
import android.widget.ImageView
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.animation.addListener
import androidx.core.content.ContextCompat
import com.qauxv.ui.CommonContextWrapper
import de.robv.android.xposed.XposedHelpers
import dev.rikka.tools.refine.Refine
import io.relimus.zflow.BuildConfig
import io.relimus.zflow.R
import io.relimus.zflow.databinding.ViewFreeformFlymeBinding
import io.relimus.zflow.xposed.hook.utils.XLog
import io.relimus.zflow.utils.cast
import io.relimus.zflow.xposed.services.FreeformManager

import io.relimus.zflow.xposed.ui.config.FreeformConfig
import io.relimus.zflow.xposed.utils.Instances
import java.lang.reflect.Method
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.core.graphics.drawable.toDrawable

import android.view.WindowManager.LayoutParams as WMLayoutParams

/**
 * FreeformWindow runs in system_server with elevated privileges.
 * Creates VirtualDisplay and floating overlay window with higher z-order priority.
 * This solves the issue of window disappearing when system UI components appear.
 *
 * [Multi-window modification] 移除了排他性窗口关闭逻辑，现在允许同时存在多个实例。
 */
@SuppressLint("ClickableViewAccessibility")
class FreeformWindow(
    baseContext: Context,
    val componentName: ComponentName?,
    private val userId: Int,
    private val initialTaskId: Int,
    private val config: FreeformConfig = FreeformConfig(),
    private val directToMini: Boolean = false,
    private val inheritedMiniLocation: IntArray? = null,
    private val pendingIntent: PendingIntent? = null,
    private val allowTapOutsideToClose: Boolean = false,
    private val sourceRotation: Int = -1,
    private val sourceScreenWidth: Int = 0,
    private val sourceScreenHeight: Int = 0,
    private val traceId: String = "unknown"
) : TextureView.SurfaceTextureListener {

    data class ImeInsetsMetrics(
        val layoutParams: WindowManager.LayoutParams,
        val freeformScreenHeight: Int,
        val scaleY: Float,
        val topDecorRaw: Float,
        val bottomDecorRaw: Float
    )

    private data class HighRefreshHint(
        val refreshRate: Float,
        val modeId: Int
    )

    companion object {
        private const val TAG = "FreeformWindow"
        private const val WIDTH_HEIGHT_RATIO = 20f / 35f

        private const val VIRTUAL_DISPLAY_ROTATION_PORTRAIT = 1
        private const val VIRTUAL_DISPLAY_ROTATION_LANDSCAPE = 0

        private const val VELOCITY_THRESHOLD = 3000f
        private const val SUNOS_EXPAND_DURATION = 320L
        private const val SUNOS_LEASH_RETRY_MAX = 8
        private const val SUNOS_LEASH_RETRY_DELAY = 16L
        private const val SUNOS_FINISH_HOLD_DELAY = 64L
        private const val MINI_TASK_MOVE_DELAY_MS = 120L
        private const val TASK_MOVE_DELAY_MS = 300L
        private const val TASK_VERIFY_DELAY_MS = 1200L   // 增加到1.2秒
        private const val TASK_SECOND_VERIFY_DELAY_MS = 2200L  // 二次验证
        private const val HIGH_REFRESH_THRESHOLD = 60.5f

        private const val VIRTUAL_DISPLAY_FLAG_TRUSTED_HIDDEN = 1 shl 10

        private const val VIRTUAL_DISPLAY_FLAGS =
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE or
                VIRTUAL_DISPLAY_FLAG_TRUSTED_HIDDEN
        private val SUNOS_EXPAND_INTERPOLATOR = PathInterpolator(0.2f, 0f, 0f, 1f)

        private const val INIT_FRAME_TIMEOUT_MS = 3000L
        private const val INIT_MIN_FRAME_COUNT = 1
        private val activeWindows = mutableListOf<FreeformWindow>()

        private const val DIRECT_MINI_EXPAND_GUARD_MS = 2500L

        /** 最多同时存在的小窗数量，从设置读取 */
        @Synchronized
        fun registerWindow(window: FreeformWindow) {
            val limit = FreeformManager.maxFreeformWindows
            if (activeWindows.size >= limit) {
                val oldest = activeWindows.removeAt(0)
                if (oldest !== window) {
                    Handler(Looper.getMainLooper()).post {
                        if (!oldest.isDestroyed) {
                            oldest.realDestroy()
                        }
                    }
                } else {
                    activeWindows.add(0, oldest)
                    return
                }
            }
            if (!activeWindows.contains(window)) {
                activeWindows.add(window)
            }
        }

        @Synchronized
        fun unregisterWindow(window: FreeformWindow) {
            activeWindows.remove(window)
        }

        fun getNormalWindows(): List<FreeformWindow> {
            return activeWindows.filter { !it.isFloating && !it.isHidden && !it.isDestroyed }
        }

        /**
         * 按包名查找“存活”窗口。activeWindows 在构造时同步登记，
         * 因此能覆盖 VirtualDisplay 尚未就绪、还没加入 FreeformManager.windowList
         * 的初始化中窗口，用于去重，避免同一应用被快速点击多个通知时重复建窗。
         */
        @Synchronized
        fun findWindowByPackage(packageName: String?): FreeformWindow? {
            if (packageName.isNullOrEmpty()) return null
            return activeWindows.find {
                !it.isDestroyed && it.componentName?.packageName == packageName
            }
        }

        /**
         * 返回同侧所有存活小窗（mini / 贴边）的占用 Y 与高度。
         * 用于统一避让：无论被调用方是什么状态，都考虑其他所有状态。
         * Y 坐标与窗口高度在所有状态间可比（均为屏幕坐标）。
         */
        @Synchronized
        fun getOccupiedSlotsOnSameSide(self: FreeformWindow, desiredX: Int): List<Pair<Int, Int>> {
            // desiredX > 0 表示贴右，≤ 0 表示贴左
            val desiredRight = desiredX > 0
            val result = mutableListOf<Pair<Int, Int>>()
            for (w in activeWindows) {
                if (w === self || w.isDestroyed || w.isClosedToBack) continue
                // 判断是否同侧
                val sameSide = if (w.isHidden) {
                    // 贴边窗口：用 dock 侧边判断
                    val dockRight = w.isHiddenDockOnRight()
                    dockRight == desiredRight
                } else {
                    // mini 窗口：用 x 坐标判断
                    val x = w.windowLayoutParams.x
                    (x <= 0 && !desiredRight) || (x > 0 && desiredRight)
                }
                if (!sameSide) continue
                // 取 Y 和高度
                val y = if (w.isHidden) {
                    w.getHiddenDockScreenY() ?: w.windowLayoutParams.y
                } else {
                    w.windowLayoutParams.y
                }
                val h = if (w.isHidden) w.floatingButtonHeight else w.hangUpViewHeight
                result.add(y to h)
            }
            return result
        }
    }

    private var currentTaskId: Int = initialTaskId
    private val context: Context = CommonContextWrapper.createAppCompatContext(baseContext)
    private lateinit var binding: ViewFreeformFlymeBinding
    private lateinit var virtualDisplay: VirtualDisplay
    private var virtualSurface: Surface? = null
    private var hasAddedWindowToManager = false
    private var hasRequestedInitialTaskMove = false
    private var hasRequestedActivityLaunch = false
    private var notificationTransitionDeadline = 0L
    private var queuedNotificationIntent: PendingIntent? = null
    private var notificationIntentSent = false
    private var notificationLaunchGeneration = 0L
    private lateinit var backgroundView: View

    var displayId: Int = -1
        private set

    private var windowLayoutParams = WindowManager.LayoutParams()
    private var backgroundLayoutParams = WindowManager.LayoutParams()

    private var screenRotation: Int = resolveInitialScreenRotation()
    private var virtualDisplayRotation = VIRTUAL_DISPLAY_ROTATION_PORTRAIT

    private val mainHandler = Handler(Looper.getMainLooper())

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                val newRotation = Instances.displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: return
                if (newRotation != screenRotation) {
                    screenRotation = newRotation
                    mainHandler.post {
                        onScreenOrientationChanged()
                    }
                }
            }
        }
    }

    private val realScreenWidth: Int
        get() {
            val width = context.resources.displayMetrics.widthPixels
            val height = context.resources.displayMetrics.heightPixels
            return if (screenIsPortrait()) min(width, height) else max(width, height)
        }
    private val realScreenHeight: Int
        get() {
            val width = context.resources.displayMetrics.widthPixels
            val height = context.resources.displayMetrics.heightPixels
            return if (screenIsPortrait()) max(width, height) else min(width, height)
        }

    private val rootWidth: Int
        get() {
            var tmp = if (screenIsPortrait()) realScreenWidth else realScreenHeight
            if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
                tmp = realScreenWidth
            }
            return tmp
        }
    private val rootHeight: Int
        get() {
            var tmp = if (screenIsPortrait()) realScreenHeight else realScreenWidth
            if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
                tmp = ((rootWidth * WIDTH_HEIGHT_RATIO) + cardHeightMargin).roundToInt()
                if (!screenIsPortrait()) {
                    tmp = realScreenHeight
                }
            }
            return tmp
        }

    private val cardHeightMargin: Float
        get() = if (screenIsPortrait()) (barHeight + freeformShadow) else 0f
    private val cardWidthMargin: Float
        get() = if (screenIsPortrait()) 0f else barHeight

    private var freeformScreenWidth = 0
    private var freeformScreenHeight = 0
    private var freeformDpi = 320

    private var freeformWidth = 0
    private var freeformHeight = 0

    private val effectiveFreeformSize: Float
        get() = config.freeformSize.coerceIn(0.1f, 1f)

    private val effectiveFreeformSizeLand: Float
        get() = config.freeformSizeLand.coerceIn(0.1f, 1f)

    private var mScaleX = 1f
        set(value) {
            field = value
            binding.freeformRoot.scaleX = value
        }
    private var mScaleY = 1f
        set(value) {
            field = value
            binding.freeformRoot.scaleY = value
        }

    private val touchMatrix = Matrix()
    private val setDisplayIdMethod: Method by lazy {
        MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    }

    private var scaleX = 1f
    private var scaleY = 1f

    private var goFloatScale = 0.6f
    private var goFullScale = 0.9f

    private var hangUpViewWidth = 0
    private var hangUpViewHeight = 0

    private val barHeight: Float = context.resources.getDimension(R.dimen.bottom_bar_height_flyme)
    private val freeformShadow: Float = context.resources.getDimension(R.dimen.freeform_shadow)
    private val freeformCornerRadius: Float = context.resources.getDimension(R.dimen.freeform_corner_radius)
    private val freeformCornerRadiusLand: Float = context.resources.getDimension(R.dimen.freeform_corner_radius_landscape)
    private val effectiveCornerRadius: Float
        get() = if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE && screenIsPortrait()) freeformCornerRadiusLand else freeformCornerRadius

    private val floatingButtonWidth: Int = (60 * context.resources.displayMetrics.density).roundToInt()
    private val floatingButtonHeight: Int = (60 * context.resources.displayMetrics.density).roundToInt()

    private val screenPaddingX: Int = context.resources.getDimension(R.dimen.freeform_screen_width_padding).roundToInt()
    private val screenPaddingY: Int = context.resources.getDimension(R.dimen.freeform_screen_height_padding).roundToInt()

    var isDestroyed = false
        private set
    var isClosedToBack = false
        private set
    private var updateFrameCount = 0
    private var initFinish = false
    private var initTimeoutRunnable = Runnable {
        if (!initFinish) {
            binding.lottieView.cancelAnimation()
            binding.lottieView.animate().alpha(0f).setDuration(200).start()
            binding.textureView.animate().alpha(1f).setDuration(200).start()
        }
    }
    var isFloating = false
        private set
    private var isZoomOut = false
    var isHidden = false
        private set

    /** 返回本窗口当前占用的屏幕区域（Y 坐标 + 高度），用于统一避让 */
    fun occupiedSlot(): Pair<Int, Int> {
        if (isHidden) {
            val y = if (::hiddenView.isInitialized && hiddenView.isAttachedToWindow)
                hiddenView.layoutParams.cast<WindowManager.LayoutParams?>()?.y ?: windowLayoutParams.y
            else windowLayoutParams.y
            return y to floatingButtonHeight
        }
        return windowLayoutParams.y to hangUpViewHeight
    }
    private var isAnimating = false
    private var isSurfaceExpandFinishing = false
    private var expandFreezeBitmap: Bitmap? = null
    private var expandFreezeDrawable: BitmapDrawable? = null

    private var springAnim: SpringAnimator? = null
    private var dragCloseHandler: HiddenViewDragCloseHandler? = null
    private var isRemovingTaskByDragClose = false

    private var directMiniExpandGuardUntil = 0L

    private fun isDirectMiniExpandGuardActive(): Boolean {
        return directToMini && SystemClock.uptimeMillis() < directMiniExpandGuardUntil
    }

    // ===== VirtualDisplay 规格缓存 =====
    private var appliedVdWidth = -1
    private var appliedVdHeight = -1
    private var appliedVdDpi = -1

    private fun markVirtualDisplaySpecApplied() {
        appliedVdWidth = freeformScreenWidth
        appliedVdHeight = freeformScreenHeight
        appliedVdDpi = freeformDpi
    }
    // =================================

    private fun resolveInitialScreenRotation(): Int {
        if (sourceRotation in Surface.ROTATION_0..Surface.ROTATION_270) {
            return sourceRotation
        }
        if (sourceScreenWidth > 0 && sourceScreenHeight > 0) {
            return if (sourceScreenWidth > sourceScreenHeight) Surface.ROTATION_90 else Surface.ROTATION_0
        }
        return Instances.displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
    }

    private fun cancelSpringAnimations() {
        springAnim?.cancel()
        springAnim = null
    }

    private val hangUpPosition = booleanArrayOf(false, true)
    private var lastFloatViewLocation: IntArray = intArrayOf(-1, -1)
    private lateinit var hiddenView: View

    private var lastX = -1f
    private var lastY = -1f
    private var touchId = -1
    private var controlDownX = -1f
    private var controlDownY = -1f
    private var isControlBarDragging = false
    private val controlBarTouchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private val backgroundGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (allowTapOutsideToClose && !isFloating && !isAnimating) {
                closeToBackWithAnimation()
            }
            return allowTapOutsideToClose
        }
    })

    private val middleGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (config.manualAdjustFreeformRotation) {
                virtualDisplayRotation = if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_PORTRAIT) {
                    VIRTUAL_DISPLAY_ROTATION_LANDSCAPE
                } else {
                    VIRTUAL_DISPLAY_ROTATION_PORTRAIT
                }
                onFreeFormRotationChanged()
            }
            return true
        }
    })

    init {
        try {
            initConfig()
            if (directToMini) initWindowAsMini() else initWindow()
            registerWindow(this)
            XLog.d("$TAG: [$traceId] FreeformWindow initialized component=$componentName directToMini=$directToMini")
        } catch (e: Exception) {
            XLog.e("$TAG: [$traceId] Failed to initialize FreeformWindow", e)
            runCatching { realDestroy() }
        }
    }

    fun bindTask(taskId: Int) {
        if (taskId <= 0 || isDestroyed) return
        if (currentTaskId == taskId) return
        currentTaskId = taskId
        XLog.d("$TAG: [$traceId] bindTask taskId=$taskId")
    }

    fun getCurrentTaskId(): Int = currentTaskId

    fun isNotificationTransitionActive(): Boolean =
        SystemClock.uptimeMillis() < notificationTransitionDeadline

    fun launchPendingIntent(pendingIntent: PendingIntent?): Boolean {
        if (pendingIntent == null || isDestroyed || displayId < 0) return false

        // 每次点击都重置发送状态。旧实现第一次发送后该标志永久为 true，
        // 后续通知无法重新排队，是微信“无反应”的直接原因之一。
        notificationLaunchGeneration += 1L
        queuedNotificationIntent = pendingIntent
        notificationIntentSent = false
        notificationTransitionDeadline = SystemClock.uptimeMillis() + 5000L

        if (isClosedToBack) restoreFromBack()
        if (isFloating || isHidden) restoreToNormalView()

        val taskId = FreeformManager.getTopTaskIdOnDisplay(displayId)
            .takeIf { it > 0 && FreeformManager.isRootTaskAlive(it) }
            ?: currentTaskId.takeIf { it > 0 && FreeformManager.isRootTaskAlive(it) }
            ?: -1
        if (taskId > 0) {
            if (FreeformManager.getTaskDisplayId(taskId) != displayId) {
                FreeformManager.moveTaskToDisplaySafely(taskId, displayId)
            }
        } else if (componentName != null && userId >= 0) {
            // 微信等应用可能在读完通知后结束原 Task。已有窗口对象仍在，
            // 但虚拟屏已经没有任务；先重建 Launcher Task，否则队列永远等不到。
            FreeformManager.startActivityOnDisplay(componentName, userId, displayId)
        }
        scheduleQueuedNotificationLaunch(notificationLaunchGeneration)
        return true
    }

    private fun startActivityOnVirtualDisplayIfNeeded(): Boolean {
        if (hasRequestedActivityLaunch || componentName == null || userId < 0 || displayId < 0) {
            return false
        }
        hasRequestedActivityLaunch = true
        if (pendingIntent != null) {
            // 先在虚拟屏建立应用 Task。直接发送通知 PendingIntent 时，系统可能
            // 复用默认屏任务；等 Launcher Task 出现在虚拟屏后再定向进入详情页。
            notificationLaunchGeneration += 1L
            queuedNotificationIntent = pendingIntent
            notificationIntentSent = false
            FreeformManager.startActivityOnDisplay(componentName, userId, displayId)
            scheduleQueuedNotificationLaunch(notificationLaunchGeneration)
        } else {
            FreeformManager.startActivityOnDisplay(componentName, userId, displayId)
        }
        return true
    }

    private fun scheduleQueuedNotificationLaunch(generation: Long) {
        listOf(80L, 180L, 350L, 650L, 1000L, 1600L, 2400L).forEach { delay ->
            mainHandler.postDelayed({
                if (generation != notificationLaunchGeneration || notificationIntentSent ||
                    isDestroyed || isClosedToBack || displayId < 0
                ) {
                    return@postDelayed
                }
                val taskId = FreeformManager.getTopTaskIdOnDisplay(displayId)
                if (taskId <= 0) return@postDelayed
                val intent = queuedNotificationIntent ?: return@postDelayed
                bindTask(taskId)
                notificationIntentSent = true
                notificationTransitionDeadline = SystemClock.uptimeMillis() + 3500L
                FreeformManager.sendPendingIntentOnDisplay(intent, displayId, taskId)
                queuedNotificationIntent = null
                scheduleNotificationTaskRecovery()
            }, delay)
        }
    }

    /**
     * 某些应用的通知 PendingIntent 先进入中转页，随后自行启动详情页；
     * 后续 Activity 不继承 launchDisplayId。短时检查该应用最新任务并迁回
     * 当前虚拟屏，覆盖这种二段式通知跳转。
     */
    private fun scheduleNotificationTaskRecovery() {
        val packageName = componentName?.packageName ?: return
        listOf(250L, 600L, 1200L, 2200L).forEach { delay ->
            mainHandler.postDelayed({
                if (isDestroyed || isClosedToBack || displayId < 0) return@postDelayed
                // 通知详情可能新建独立 Task，优先跟踪该应用最新任务，
                // 而不是始终选中原 Launcher Task。
                val taskId = FreeformManager.getLatestAliveTaskIdForPackage(packageName)
                    .takeIf { it > 0 } ?: currentTaskId
                if (taskId <= 0) return@postDelayed
                val actualDisplay = FreeformManager.getTaskDisplayId(taskId)
                if (actualDisplay != displayId) {
                    XLog.w(
                        "$TAG: [$traceId] Notification task escaped to display=" +
                            "$actualDisplay; moving taskId=$taskId back to $displayId"
                    )
                    FreeformManager.moveTaskToDisplaySafely(taskId, displayId)
                } else {
                    bindTask(taskId)
                }
            }, delay)
        }
    }

    private fun screenIsPortrait(): Boolean = screenRotation == Surface.ROTATION_0 || screenRotation == Surface.ROTATION_180

    fun getImeInsetsMetrics(): ImeInsetsMetrics {
        val lpSnapshot = WindowManager.LayoutParams().apply { copyFrom(windowLayoutParams) }
        val isPortrait = screenIsPortrait()
        val bottomDecor = if (isPortrait) barHeight else 0f
        val topDecor = (cardHeightMargin - bottomDecor).coerceAtLeast(0f)
        return ImeInsetsMetrics(lpSnapshot, freeformScreenHeight, mScaleY, topDecor, bottomDecor)
    }

    private fun initConfig() {
        if (config.defaultLandscape || (directToMini && !screenIsPortrait())) {
            virtualDisplayRotation = VIRTUAL_DISPLAY_ROTATION_LANDSCAPE
        }

        // 强制 DPI 跟随物理屏幕
        freeformDpi = FreeformManager.getDefaultDisplayDpi()
        XLog.d("$TAG: [$traceId] VirtualDisplay DPI forced to $freeformDpi (physical screen DPI)")

        val baseHeight = (min(realScreenHeight, realScreenWidth) / WIDTH_HEIGHT_RATIO).roundToInt()
        val baseWidth = (baseHeight * WIDTH_HEIGHT_RATIO).roundToInt()

        var w = baseWidth
        var h = baseHeight
        if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
            w = baseHeight
            h = baseWidth
        }

        if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
            val display = Instances.displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            if (display != null) {
                val metrics = android.util.DisplayMetrics()
                display.getRealMetrics(metrics)
                val realW = max(metrics.widthPixels, metrics.heightPixels)
                val realH = min(metrics.widthPixels, metrics.heightPixels)
                if (realW > 0 && realH > 0) {
                    w = realW
                    h = realH
                    freeformDpi = FreeformManager.getDefaultDisplayDpi()
                }
            }
        }

        freeformScreenWidth = w
        freeformScreenHeight = h

        initFloatViewSize()
        refreshFreeformSize()
        refreshActionScale()
    }

    private fun initFloatViewSize() {
        hangUpViewHeight = (rootHeight * config.floatViewSize).roundToInt()
        hangUpViewWidth = (hangUpViewHeight * WIDTH_HEIGHT_RATIO).roundToInt()
        if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
            hangUpViewWidth = (realScreenHeight * config.floatViewSize).roundToInt()
            hangUpViewHeight = (hangUpViewWidth * WIDTH_HEIGHT_RATIO).roundToInt()
            if (!screenIsPortrait()) {
                hangUpViewWidth = (realScreenWidth * config.floatViewSize).roundToInt()
                hangUpViewHeight = (hangUpViewWidth * WIDTH_HEIGHT_RATIO).roundToInt()
            }
        }
    }

    private fun refreshFreeformSize() {
        if (screenIsPortrait()) {
            freeformWidth = (rootWidth * effectiveFreeformSize).roundToInt()
            val contentHeight = (freeformWidth - (freeformShadow * 2)) / WIDTH_HEIGHT_RATIO
            freeformHeight = (contentHeight + cardHeightMargin).roundToInt()
        } else {
            freeformHeight = (rootWidth * effectiveFreeformSizeLand).roundToInt()
            freeformHeight += cardHeightMargin.roundToInt()
            freeformWidth = ((freeformHeight + cardWidthMargin) * WIDTH_HEIGHT_RATIO).roundToInt()
        }
        if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
            if (screenIsPortrait()) {
                freeformWidth = (rootWidth - (rootWidth * 0.05)).roundToInt()
                freeformHeight = ((freeformWidth + (cardHeightMargin * 2)) * WIDTH_HEIGHT_RATIO).roundToInt()
            } else {
                freeformWidth = (realScreenWidth / 2 + cardWidthMargin).roundToInt()
                freeformHeight = ((freeformWidth * WIDTH_HEIGHT_RATIO) * 0.95).roundToInt()
            }
        }
    }

    private fun refreshActionScale() {
        goFloatScale = (freeformHeight * 0.8f) / rootHeight
        goFullScale = (freeformHeight * 1.1f) / rootHeight
    }

    // 不再使用用户自定义 DPI，使用物理屏幕 DPI
    private fun getScreenDpi(): Int = FreeformManager.getDefaultDisplayDpi()

    // 高刷新率暂时禁用，避免视频场景稳定性问题
    private fun resolveHighRefreshHint(): HighRefreshHint? = null

    private fun applyWindowRefreshHint(layoutParams: WindowManager.LayoutParams) {
        // 高刷新率提示暂时禁用
    }

    private fun applySurfaceRefreshHint(surface: Surface, refreshRate: Float) {
        // 高刷新率提示暂时禁用
    }

    @SuppressLint("WrongConstant")
    private fun createVirtualDisplay(name: String, refreshRate: Float): VirtualDisplay? {
        // 不再指定高刷新率
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                val builder = VirtualDisplayConfig.Builder(
                    name,
                    freeformScreenWidth,
                    freeformScreenHeight,
                    freeformDpi
                ).setFlags(VIRTUAL_DISPLAY_FLAGS)

                // 不设置刷新率
                val created = Instances.displayManager.createVirtualDisplay(builder.build())
                if (created != null) {
                    return created
                }
            } catch (e: Throwable) {
                XLog.w("$TAG: VirtualDisplayConfig API failed, fallback to legacy API", e)
            }
        }

        return try {
            Instances.displayManager.createVirtualDisplay(
                name,
                freeformScreenWidth,
                freeformScreenHeight,
                freeformDpi,
                null,
                VIRTUAL_DISPLAY_FLAGS
            )
        } catch (e: Throwable) {
            XLog.e("$TAG: Failed to create VirtualDisplay", e)
            null
        }
    }

    private fun refreshScale() {
        mScaleX = freeformWidth / rootWidth.toFloat()
        mScaleY = freeformHeight / rootHeight.toFloat()
    }

    private fun refreshTouchScale() {
        scaleX = (rootWidth - cardWidthMargin) / freeformScreenWidth.toFloat()
        scaleY = (rootHeight - cardHeightMargin) / freeformScreenHeight.toFloat()
    }

    private fun initWindow() {
        val wrappedContext = CommonContextWrapper.createAppCompatContext(context)
        binding = ViewFreeformFlymeBinding.inflate(LayoutInflater.from(wrappedContext))
        binding.root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { cancelSpringAnimations() }
        })

        backgroundView = View(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            if (allowTapOutsideToClose) {
                setOnTouchListener { _, event ->
                    backgroundGestureDetector.onTouchEvent(event)
                    true
                }
            }
        }

        if (!screenIsPortrait()) {
            hangUpPosition[0] = true
            (binding.cardRoot.layoutParams.cast<ConstraintLayout.LayoutParams>()).apply {
                topMargin = 0
                bottomMargin = 0
                rightMargin = barHeight.roundToInt()
            }
        }

        backgroundLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply { dimAmount = config.dimAmount }

        windowLayoutParams = WindowManager.LayoutParams(
            rootWidth, rootHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            x = 0; y = 0
        }
        applyWindowRefreshHint(windowLayoutParams)

        if (!screenIsPortrait()) {
            windowLayoutParams.apply {
                x = genCenterLocation()[0]
                y = genCenterLocation()[1]
            }
        }

        refreshScale()
        refreshTouchScale()
        val targetScaleX = mScaleX
        val targetScaleY = mScaleY

        binding.freeformRoot.alpha = 0f
        binding.freeformRoot.scaleX = targetScaleX * 0.9f
        binding.freeformRoot.scaleY = targetScaleY * 0.9f
        Instances.windowManager.addView(backgroundView, backgroundLayoutParams)
        Instances.windowManager.addView(binding.root, windowLayoutParams)

        isAnimating = true
        binding.root.post {
            if (!binding.root.isAttachedToWindow || isDestroyed) {
                isAnimating = false
                return@post
            }
            binding.freeformRoot.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(binding.freeformRoot, View.ALPHA, 0f, 1f),
                    ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_X, targetScaleX * 0.9f, targetScaleX),
                    ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_Y, targetScaleY * 0.9f, targetScaleY),
                )
                duration = 250
                interpolator = AccelerateDecelerateInterpolator()
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        binding.freeformRoot.setLayerType(View.LAYER_TYPE_NONE, null)
                        isAnimating = false
                    }
                })
                start()
            }
        }

        binding.textureView.isOpaque = false
        binding.textureView.surfaceTextureListener = this
        binding.textureView.alpha = 0f

        initFloatBar()
        setupTouchHandlers()
        setupControlBar()
        Instances.displayManager.registerDisplayListener(displayListener, mainHandler)
        setWindowNoUpdateAnimation()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initWindowAsMini() {
        screenRotation = resolveInitialScreenRotation()

        val wrappedContext = CommonContextWrapper.createAppCompatContext(context)
        binding = ViewFreeformFlymeBinding.inflate(LayoutInflater.from(wrappedContext))
        binding.root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { cancelSpringAnimations() }
        })

        backgroundView = View(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            if (allowTapOutsideToClose) {
                setOnTouchListener { _, event ->
                    backgroundGestureDetector.onTouchEvent(event)
                    true
                }
            }
        }

        backgroundLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply { dimAmount = 0f }

        val baseLocation = inheritedMiniLocation
            ?.takeIf { it.size >= 2 }
            ?.let { inherited ->
                hangUpPosition[0] = inherited[0] <= 0
                hangUpPosition[1] = inherited[1] <= 0
                normalizeMiniLocation(intArrayOf(inherited[0], inherited[1]))
            }
            ?: normalizeMiniLocation(genFloatViewLocation())

        val location = resolveNonOverlappingMiniLocation(baseLocation)
        hangUpPosition[0] = location[0] <= 0
        hangUpPosition[1] = location[1] <= 0
        lastFloatViewLocation = location.copyOf()

        windowLayoutParams = WindowManager.LayoutParams(
            hangUpViewWidth, hangUpViewHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            x = location[0]; y = location[1]
        }
        applyWindowRefreshHint(windowLayoutParams)

        (binding.cardRoot.layoutParams.cast<ConstraintLayout.LayoutParams>()).apply {
            topMargin = 0; bottomMargin = 0; rightMargin = 0
        }
        binding.cardRoot.radius = effectiveCornerRadius * (hangUpViewWidth / rootWidth.toFloat())

        initFloatBar()
        binding.bottomBar.root.alpha = 0f
        initFinish = false
        updateFrameCount = 0
        binding.lottieView.alpha = 1f
        binding.lottieView.playAnimation()
        binding.textureView.alpha = 0f
        binding.freeformRoot.alpha = 1f
        binding.freeformRoot.scaleX = 1f
        binding.freeformRoot.scaleY = 1f
        backgroundView.visibility = View.GONE
        refreshTouchScale()

        Instances.windowManager.addView(backgroundView, backgroundLayoutParams)
        Instances.windowManager.addView(binding.root, windowLayoutParams)

        binding.textureView.isOpaque = false
        binding.textureView.surfaceTextureListener = this
        binding.textureView.setOnTouchListener(FloatViewTouchListener())
        setupControlBar()
        Instances.displayManager.registerDisplayListener(displayListener, mainHandler)

        isFloating = true

        if (directToMini) {
            directMiniExpandGuardUntil = SystemClock.uptimeMillis() + DIRECT_MINI_EXPAND_GUARD_MS
        }

        mScaleX = hangUpViewWidth / rootWidth.toFloat()
        mScaleY = hangUpViewHeight / rootHeight.toFloat()
        binding.freeformRoot.scaleX = 1f
        binding.freeformRoot.scaleY = 1f
        setWindowEnableUpdateAnimation()

        binding.root.post {
            if (isDestroyed || !isFloating || !binding.root.isAttachedToWindow) return@post
            val latestRotation = Instances.displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: return@post
            if (latestRotation != screenRotation) {
                screenRotation = latestRotation
                onScreenOrientationChanged()
                return@post
            }
            val normalizedLocation = normalizeMiniLocation(intArrayOf(windowLayoutParams.x, windowLayoutParams.y))
            if (normalizedLocation[0] != windowLayoutParams.x || normalizedLocation[1] != windowLayoutParams.y) {
                hangUpPosition[0] = normalizedLocation[0] <= 0
                hangUpPosition[1] = normalizedLocation[1] <= 0
                lastFloatViewLocation = normalizedLocation.copyOf()
                windowLayoutParams.apply { x = normalizedLocation[0]; y = normalizedLocation[1] }
                Instances.windowManager.updateViewLayout(binding.root, windowLayoutParams)
            }
        }
    }

    private fun genCenterLocation(): IntArray {
        val center = intArrayOf(0, 0)
        if (!screenIsPortrait()) {
            center[0] = (freeformWidth - rootHeight + screenPaddingX) / 2
            if (!hangUpPosition[0]) center[0] = (freeformWidth - rootHeight + screenPaddingX) / -2
            if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
                center[0] = (freeformWidth - realScreenWidth + screenPaddingX) / 2
                if (!hangUpPosition[0]) center[0] = (freeformWidth - realScreenWidth + screenPaddingX) / -2
            }
        }
        return center
    }

    private fun initFloatBar() {
        if (screenIsPortrait()) {
            binding.bottomBar.apply {
                root.layoutParams = ConstraintLayout.LayoutParams(
                    ConstraintLayout.LayoutParams.MATCH_PARENT,
                    barHeight.roundToInt()
                ).apply {
                    topToBottom = R.id.cardRoot
                    startToEnd = ConstraintLayout.LayoutParams.UNSET
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                }
                middleView.visibility = View.VISIBLE
                sideView.visibility = View.GONE
            }
        } else {
            binding.bottomBar.apply {
                root.layoutParams = ConstraintLayout.LayoutParams(
                    barHeight.roundToInt(),
                    ConstraintLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    startToEnd = R.id.cardRoot
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                }
                middleView.visibility = View.GONE
                sideView.visibility = View.VISIBLE
            }
        }
    }

    private fun onScreenOrientationChanged() {
        if (isDestroyed) return

        initFloatViewSize()
        refreshFreeformSize()
        initFloatBar()
        val location = if (isFloating && !isHidden) resolveNonOverlappingMiniLocation(genFloatViewLocation()) else genFloatViewLocation()
        lastFloatViewLocation = location.copyOf()
        refreshTouchScale()
        refreshActionScale()
        if (isFloating && !isHidden) {
            moveFloatViewLocation(location)
        } else if (isHidden) {
            moveHiddenViewLocation(location)
        } else {
            windowLayoutParams.apply { height = rootHeight; width = rootWidth }
            (binding.cardRoot.layoutParams.cast<ConstraintLayout.LayoutParams>()).apply {
                topMargin = freeformShadow.roundToInt()
                bottomMargin = barHeight.roundToInt()
                rightMargin = 0
            }
            windowLayoutParams.apply { x = genCenterLocation()[0]; y = genCenterLocation()[1] }
            if (!screenIsPortrait()) {
                (binding.cardRoot.layoutParams.cast<ConstraintLayout.LayoutParams>()).apply {
                    topMargin = 0; bottomMargin = 0; rightMargin = barHeight.roundToInt()
                }
            }
            resetScale()
            Instances.windowManager.updateViewLayout(binding.root, windowLayoutParams)
        }
    }

    private fun resetScale() {
        refreshTouchScale()
        refreshScale()
        refreshActionScale()
    }

    private fun resizeVirtualDisplayIfNeeded(force: Boolean = false): Boolean {
        if (!::virtualDisplay.isInitialized || isDestroyed) {
            return false
        }

        val unchanged =
            appliedVdWidth == freeformScreenWidth &&
            appliedVdHeight == freeformScreenHeight &&
            appliedVdDpi == freeformDpi

        if (!force && unchanged) {
            return false
        }

        return try {
            virtualDisplay.resize(freeformScreenWidth, freeformScreenHeight, freeformDpi)
            markVirtualDisplaySpecApplied()
            true
        } catch (e: Throwable) {
            XLog.e("$TAG: Failed to resize VirtualDisplay", e)
            false
        }
    }

    private fun onFreeFormRotationChanged() {
        if (isDestroyed || isAnimating) return
        isAnimating = true
        val tempHeight = max(freeformScreenHeight, freeformScreenWidth)
        val tempWidth = min(freeformScreenHeight, freeformScreenWidth)
        ObjectAnimator.ofFloat(binding.freeformRoot, View.ALPHA, 1f, 0f).apply {
            duration = 100
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    initFloatViewSize()
                    if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_PORTRAIT) {
                        freeformScreenHeight = tempHeight
                        freeformScreenWidth = tempWidth
                    } else {
                        freeformScreenHeight = tempWidth
                        freeformScreenWidth = tempHeight
                    }
                    refreshFreeformSize()
                    refreshTouchScale()
                    refreshActionScale()
                    resizeVirtualDisplayIfNeeded()
                    applyLayoutCurrentState()
                    ObjectAnimator.ofFloat(binding.freeformRoot, View.ALPHA, 0f, 1f).apply {
                        duration = 150
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) { isAnimating = false }
                        })
                        start()
                    }
                }
            })
            start()
        }
    }

    private fun applyLayoutCurrentState() {
        if (isFloating || isHidden) applyMiniLayout() else applyNormalLayout()
    }

    private fun applyNormalLayout() {
        refreshScale()
        binding.cardRoot.radius = effectiveCornerRadius
        applyWindowRefreshHint(windowLayoutParams)
        Instances.windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
            width = rootWidth
            height = rootHeight
            x = genCenterLocation()[0]
            y = genCenterLocation()[1]
        })
    }

    private fun applyMiniLayout() {
        val miniLocation = resolveMiniLocationAfterFreeformRotation()
        hangUpPosition[0] = miniLocation[0] <= 0
        hangUpPosition[1] = miniLocation[1] <= 0
        lastFloatViewLocation = miniLocation.copyOf()
        mScaleX = hangUpViewWidth / rootWidth.toFloat()
        mScaleY = hangUpViewHeight / rootHeight.toFloat()
        binding.freeformRoot.scaleX = 1f
        binding.freeformRoot.scaleY = 1f
        (binding.cardRoot.layoutParams.cast<ConstraintLayout.LayoutParams>()).apply {
            topMargin = 0; bottomMargin = 0; rightMargin = 0
        }
        binding.cardRoot.radius = effectiveCornerRadius * (hangUpViewWidth / rootWidth.toFloat())
        applyWindowRefreshHint(windowLayoutParams)
        windowLayoutParams.apply {
            width = hangUpViewWidth
            height = hangUpViewHeight
            if (isHidden) {
                val hiddenOnRight = isHiddenOnRight()
                x = if (hiddenOnRight) miniLocation[0] + (hangUpViewWidth + screenPaddingX)
                    else miniLocation[0] - (hangUpViewWidth + screenPaddingX)
                y = miniLocation[1]
                updateHiddenViewLayout(hiddenOnRight, miniLocation[1])
            } else {
                x = miniLocation[0]
                y = miniLocation[1]
            }
        }
        Instances.windowManager.updateViewLayout(binding.root, windowLayoutParams)
    }

    private fun resolveMiniLocationAfterFreeformRotation(): IntArray {
        val baseLocation = when {
            lastFloatViewLocation[0] != -1 && lastFloatViewLocation[1] != -1 -> lastFloatViewLocation.copyOf()
            isHidden -> intArrayOf(
                if (hangUpPosition[0]) (realScreenWidth - hangUpViewWidth - screenPaddingX) / -2
                else (realScreenWidth - hangUpViewWidth - screenPaddingX) / 2,
                windowLayoutParams.y
            )
            else -> intArrayOf(windowLayoutParams.x, windowLayoutParams.y)
        }
        return normalizeMiniLocation(baseLocation)
    }

    private fun isHiddenOnRight(): Boolean {
        if (::hiddenView.isInitialized && hiddenView.isAttachedToWindow) {
            return (hiddenView.layoutParams.cast<WindowManager.LayoutParams?>())?.x?.let { it > 0 } ?: !hangUpPosition[0]
        }
        return !hangUpPosition[0]
    }

    private fun updateHiddenViewLayout(hiddenOnRight: Boolean, targetY: Int) {
        if (!::hiddenView.isInitialized || !hiddenView.isAttachedToWindow) return
        val hiddenLp = hiddenView.layoutParams.cast<WindowManager.LayoutParams?>() ?: return
        val rawHiddenY = centerToScreenTopLeftY(targetY, floatingButtonHeight)
        val resolvedHiddenY = resolveNonOverlappingHiddenDockY(hiddenOnRight, rawHiddenY)
        hiddenLp.x = calcDockHiddenX(if (hiddenOnRight) 1 else -1)
        hiddenLp.y = resolvedHiddenY
        Instances.windowManager.updateViewLayout(hiddenView, hiddenLp)
    }

    fun getMiniLocation(): IntArray {
        if (isHidden) {
            return intArrayOf(
                if (hangUpPosition[0]) (realScreenWidth - hangUpViewWidth - screenPaddingX) / -2
                else (realScreenWidth - hangUpViewWidth - screenPaddingX) / 2,
                windowLayoutParams.y
            )
        }
        return intArrayOf(windowLayoutParams.x, windowLayoutParams.y)
    }

    fun setVirtualDisplayRotation(rotation: Int) {
        if (isDestroyed) return

        val targetRotation = when (rotation) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE ->
                VIRTUAL_DISPLAY_ROTATION_LANDSCAPE

            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            ActivityInfo.SCREEN_ORIENTATION_BEHIND,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR,
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
            ActivityInfo.SCREEN_ORIENTATION_USER,
            ActivityInfo.SCREEN_ORIENTATION_FULL_USER,
            ActivityInfo.SCREEN_ORIENTATION_LOCKED -> {
                return
            }

            else -> VIRTUAL_DISPLAY_ROTATION_PORTRAIT
        }

        if (targetRotation == virtualDisplayRotation) {
            return
        }

        virtualDisplayRotation = targetRotation
        mainHandler.post { onFreeFormRotationChanged() }
    }

    private fun resolveCurrentTaskIdForRemoval(): Int {
        val topTaskId = FreeformManager.getTopTaskIdOnDisplay(displayId)
        return when {
            topTaskId > 0 && FreeformManager.isRootTaskAlive(topTaskId) -> topTaskId
            currentTaskId > 0 && FreeformManager.isRootTaskAlive(currentTaskId) -> currentTaskId
            else -> -1
        }
    }

    private fun closeAndRemoveTask() {
        if (isDestroyed || isRemovingTaskByDragClose) return
        isRemovingTaskByDragClose = true
        val targetTaskId = resolveCurrentTaskIdForRemoval()
        try {
            if (targetTaskId > 0) {
                val removed = FreeformManager.removeTask(targetTaskId)
                if (removed) {
                    mainHandler.postDelayed({
                        if (!isDestroyed) realDestroy()
                    }, 800)
                } else {
                    realDestroy()
                }
            } else {
                realDestroy()
            }
        } catch (e: Exception) {
            XLog.e("$TAG: Failed to remove task by drag-close", e)
            realDestroy()
        }
    }

    private fun setWindowNoUpdateAnimation() {
        runCatching {
            val layoutParamsClass = Class.forName("android.view.WindowManager\$LayoutParams")
            val privateFlags = layoutParamsClass.getField("privateFlags")
            val noAnim = layoutParamsClass.getField("PRIVATE_FLAG_NO_MOVE_ANIMATION")
            var privateFlagsValue = privateFlags.getInt(windowLayoutParams)
            privateFlagsValue = privateFlagsValue or noAnim.getInt(windowLayoutParams)
            privateFlags.setInt(windowLayoutParams, privateFlagsValue)
        }
    }

    private fun setWindowEnableUpdateAnimation() {
        runCatching {
            val layoutParamsClass = Class.forName("android.view.WindowManager\$LayoutParams")
            val privateFlags = layoutParamsClass.getField("privateFlags")
            val noAnim = layoutParamsClass.getField("PRIVATE_FLAG_NO_MOVE_ANIMATION")
            var privateFlagsValue = privateFlags.getInt(windowLayoutParams)
            privateFlagsValue = privateFlagsValue and noAnim.getInt(windowLayoutParams).inv()
            privateFlags.setInt(windowLayoutParams, privateFlagsValue)
        }
    }

    private fun setupTouchHandlers() {
        binding.textureView.setOnTouchListener { _, event -> forwardMotionEvent(event); true }
        if (allowTapOutsideToClose) {
            binding.root.setOnTouchListener { _, event -> backgroundGestureDetector.onTouchEvent(event); true }
        } else {
            binding.root.setOnTouchListener(null)
        }
    }

    private fun setupControlBar() {
        binding.bottomBar.middleView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> handleDownEvent(v, event)
                MotionEvent.ACTION_MOVE -> handleMoveEvent(v, event)
                MotionEvent.ACTION_UP -> handleUpEvent(v, event)
                MotionEvent.ACTION_CANCEL -> { touchId = -1; isControlBarDragging = false }
            }
            true
        }
        binding.bottomBar.sideView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> handleDownEvent(v, event)
                MotionEvent.ACTION_MOVE -> handleMoveEvent(v, event)
                MotionEvent.ACTION_UP -> handleUpEvent(v, event)
                MotionEvent.ACTION_CANCEL -> { touchId = -1; isControlBarDragging = false }
            }
            true
        }
        binding.bottomBar.middleView.setOnLongClickListener { closeToBack(); true }
        binding.bottomBar.sideView.setOnLongClickListener { closeToBack(); true }
    }

    private fun handleDownEvent(v: View, event: MotionEvent) {
        if (touchId == -1) touchId = v.id
        lastX = event.rawX
        lastY = event.rawY
        controlDownX = event.rawX
        controlDownY = event.rawY
        isControlBarDragging = false
        middleGestureDetector.onTouchEvent(event)
    }

    private fun handleMoveEvent(v: View, event: MotionEvent) {
        when (v.id) {
            R.id.middleView -> {
                if (touchId == R.id.middleView) {
                    if (!isControlBarDragging) isControlBarDragging = abs(event.rawY - controlDownY) >= controlBarTouchSlop
                    if (isControlBarDragging) {
                        val dy = event.rawY - lastY
                        handleToFloatScale(0f, dy)
                    }
                    lastX = event.rawX
                    lastY = event.rawY
                    middleGestureDetector.onTouchEvent(event)
                }
            }
            R.id.sideView -> {
                if (touchId == R.id.sideView) {
                    if (!isControlBarDragging) isControlBarDragging = abs(event.rawX - controlDownX) >= controlBarTouchSlop
                    if (isControlBarDragging) {
                        val dx = event.rawX - lastX
                        handleToFloatScale(dx, 0f)
                    }
                    lastX = event.rawX
                    lastY = event.rawY
                    middleGestureDetector.onTouchEvent(event)
                }
            }
        }
    }

    private fun handleUpEvent(v: View, event: MotionEvent) {
        when (v.id) {
            R.id.middleView -> {
                middleGestureDetector.onTouchEvent(event)
                if (isControlBarDragging) notifyToFloat()
            }
            R.id.sideView -> {
                middleGestureDetector.onTouchEvent(event)
                if (isControlBarDragging) notifyToFloat()
            }
        }
        touchId = -1
        isControlBarDragging = false
    }

    private fun handleToFloatScale(dx: Float, dy: Float) {
        if (isFloating || isAnimating) return
        val ratio = if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) 1 / WIDTH_HEIGHT_RATIO else WIDTH_HEIGHT_RATIO
        if (dy != 0f) {
            val tempHeight = freeformHeight + dy
            val maxHeight = if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE && screenIsPortrait()) rootHeight * 1.15f else rootHeight * 0.9f
            if (tempHeight >= hangUpViewHeight && tempHeight <= maxHeight) {
                freeformHeight += dy.roundToInt()
                if (screenIsPortrait()) {
                    val initRatio = if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
                        val w = (rootWidth - (rootWidth * 0.05)).roundToInt()
                        val h = ((w + (cardHeightMargin * 2)) * WIDTH_HEIGHT_RATIO).roundToInt()
                        w.toFloat() / h
                    } else {
                        val w = (rootWidth * effectiveFreeformSize).roundToInt()
                        val h = (((w - (freeformShadow * 2)) / WIDTH_HEIGHT_RATIO) + cardHeightMargin).roundToInt()
                        w.toFloat() / h
                    }
                    freeformWidth = (freeformHeight * initRatio).roundToInt()
                } else {
                    val contentHeight = freeformHeight - cardHeightMargin
                    val contentWidth = contentHeight * ratio
                    freeformWidth = (contentWidth + cardWidthMargin).roundToInt()
                }
                mScaleX = freeformWidth / rootWidth.toFloat()
                mScaleY = freeformHeight / rootHeight.toFloat()
                isZoomOut = true
            }
        } else if (dx != 0f) {
            val tempWidth = freeformWidth + dx
            if (tempWidth >= hangUpViewWidth && tempWidth <= rootWidth * 0.9) {
                freeformWidth += dx.roundToInt()
                if (!screenIsPortrait()) {
                    val initRatio = if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
                        val w = (realScreenWidth / 2 + cardWidthMargin).roundToInt()
                        val h = ((w * WIDTH_HEIGHT_RATIO) * 0.95).roundToInt()
                        h.toFloat() / w
                    } else {
                        val h = (rootWidth * effectiveFreeformSizeLand).roundToInt()
                        val w = ((h + cardWidthMargin) * WIDTH_HEIGHT_RATIO).roundToInt()
                        h.toFloat() / w
                    }
                    freeformHeight = (freeformWidth * initRatio).roundToInt()
                } else {
                    val contentWidth = freeformWidth - (freeformShadow * 2)
                    val contentHeight = contentWidth / ratio
                    freeformHeight = (contentHeight + cardHeightMargin).roundToInt()
                }
                mScaleX = freeformWidth / rootWidth.toFloat()
                mScaleY = freeformHeight / rootHeight.toFloat()
                isZoomOut = true
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun notifyToFloat() {
        if (isZoomOut && !isAnimating) {
            val scaleX: Float = hangUpViewWidth / rootWidth.toFloat()
            val scaleY: Float = hangUpViewHeight / rootHeight.toFloat()
            when {
                mScaleY <= goFloatScale -> {
                    isAnimating = true
                    binding.freeformRoot.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    AnimatorSet().apply {
                        playTogether(
                            ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_X, mScaleX, scaleX),
                            ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_Y, mScaleY, scaleY),
                            ObjectAnimator.ofFloat(binding.bottomBar.root, View.ALPHA, 0f),
                            cardViewMarginAnim(
                                (binding.cardRoot.layoutParams.cast<ConstraintLayout.LayoutParams>()).topMargin,
                                (binding.cardRoot.layoutParams.cast<ConstraintLayout.LayoutParams>()).bottomMargin,
                                (binding.cardRoot.layoutParams.cast<ConstraintLayout.LayoutParams>()).rightMargin,
                                0, 0, 0
                            )
                        )
                        addListener(
                            onStart = {
                                val windowCoordinate = intArrayOf(windowLayoutParams.x, windowLayoutParams.y)
                                var location = genFloatViewLocation()
                                if (lastFloatViewLocation[0] != -1) location = lastFloatViewLocation.copyOf()
                                location = resolveNonOverlappingMiniLocation(location)
                                hangUpPosition[0] = location[0] <= 0
                                hangUpPosition[1] = location[1] <= 0
                                lastFloatViewLocation = location.copyOf()
                                AnimatorSet().apply {
                                    playTogether(
                                        moveViewAnim(windowCoordinate, location),
                                        ValueAnimator.ofFloat(config.dimAmount, 0f).apply {
                                            addUpdateListener {
                                                if (!backgroundView.isAttachedToWindow) return@addUpdateListener
                                                Instances.windowManager.updateViewLayout(
                                                    backgroundView,
                                                    backgroundLayoutParams.apply { dimAmount = it.animatedValue.cast() }
                                                )
                                            }
                                        }
                                    )
                                    startDelay = 125
                                    duration = 400
                                    interpolator = OvershootInterpolator(0.5f)
                                    addListener(
                                        onStart = {
                                            backgroundView.visibility = View.GONE
                                            binding.textureView.setOnTouchListener(null)
                                            AnimatorSet().apply {
                                                duration = 100
                                                startDelay = 200
                                                addListener(
                                                    onEnd = {
                                                        if (!binding.root.isAttachedToWindow) return@addListener
                                                        mScaleX = scaleX
                                                        mScaleY = scaleY
                                                        binding.cardRoot.radius = effectiveCornerRadius * (hangUpViewWidth / rootWidth.toFloat())
                                                        Instances.windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                                                            height = hangUpViewHeight
                                                            width = hangUpViewWidth
                                                        })
                                                        binding.root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                                                            override fun onPreDraw(): Boolean {
                                                                binding.root.viewTreeObserver.removeOnPreDrawListener(this)
                                                                binding.freeformRoot.scaleX = 1f
                                                                binding.freeformRoot.scaleY = 1f
                                                                return true
                                                            }
                                                        })
                                                    }
                                                )
                                                start()
                                            }
                                            isFloating = true
                                        },
                                        onEnd = {
                                            binding.textureView.setOnTouchListener(floatViewTouchListener)
                                            setWindowEnableUpdateAnimation()
                                            binding.freeformRoot.setLayerType(View.LAYER_TYPE_NONE, null)
                                            binding.cardRoot.translationX = 0f
                                            binding.cardRoot.translationY = 0f
                                            isAnimating = false
                                        }
                                    )
                                    start()
                                }
                            }
                        )
                        duration = 200
                        start()
                    }
                }
                mScaleY >= goFullScale -> {
                    if (isDirectMiniExpandGuardActive()) {
                        isAnimating = false
                        isZoomOut = false
                        if (!isFloating && !isHidden) {
                            isFloating = true
                            backgroundView.visibility = View.GONE
                            binding.bottomBar.root.alpha = 0f
                            binding.textureView.setOnTouchListener(floatViewTouchListener)
                            setWindowEnableUpdateAnimation()
                            applyMiniLayout()
                        }
                        return
                    }

                    // 暂时禁用 SurfaceControl 动画，直接迁移
                    isAnimating = true
                    startSunOsSurfaceControlledExpand()
                }
                else -> {
                    isAnimating = true
                    refreshFreeformSize()
                    val targetScaleX = freeformWidth / rootWidth.toFloat()
                    val targetScaleY = freeformHeight / rootHeight.toFloat()
                    AnimatorSet().apply {
                        playTogether(
                            ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_X, mScaleX, targetScaleX),
                            ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_Y, mScaleY, targetScaleY),
                        )
                        duration = 300
                        interpolator = OvershootInterpolator(1.5f)
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                mScaleX = targetScaleX
                                mScaleY = targetScaleY
                                isAnimating = false
                            }
                        })
                        start()
                    }
                }
            }
            isZoomOut = false
        }
    }

    // 简化 Surface 动画，直接迁移 Task 到默认屏幕
    private fun startSunOsSurfaceControlledExpand() {
        isSurfaceExpandFinishing = false
        val activeTaskId = FreeformManager.getTopTaskIdOnDisplay(displayId).let {
            if (it > 0 && FreeformManager.isRootTaskAlive(it)) it
            else if (FreeformManager.isRootTaskAlive(currentTaskId)) currentTaskId
            else -1
        }
        if (activeTaskId > 0) {
            FreeformManager.moveTaskFromDisplayToDefault(displayId, activeTaskId)
        } else if (componentName != null) {
            FreeformManager.startActivityOnDisplay(componentName, userId, Display.DEFAULT_DISPLAY)
        }
        finishAfterSurfaceExpand()
    }

    private fun resolveExpandStartRect(): Rect = Rect(0, 0, realScreenWidth, realScreenHeight)

    private fun finishAfterSurfaceExpand() {
        if (isSurfaceExpandFinishing) return
        isSurfaceExpandFinishing = true

        mainHandler.postDelayed({
            if (isDestroyed || isClosedToBack) {
                clearExpandFreezeLayer()
                isAnimating = false
                return@postDelayed
            }

            clearExpandFreezeLayer()
            binding.freeformRoot.setLayerType(View.LAYER_TYPE_NONE, null)
            isAnimating = false

            if (isDirectMiniExpandGuardActive()) {
                if (!isFloating && !isHidden) {
                    isFloating = true
                    backgroundView.visibility = View.GONE
                    binding.bottomBar.root.alpha = 0f
                    binding.textureView.setOnTouchListener(floatViewTouchListener)
                    setWindowEnableUpdateAnimation()
                    applyMiniLayout()
                }
                return@postDelayed
            }

            closeToBack()
        }, SUNOS_FINISH_HOLD_DELAY)
    }

    private fun prepareExpandFreezeLayer() {
        clearExpandFreezeLayer()
        if (!binding.textureView.isAvailable) return
        val bitmap = try { binding.textureView.bitmap } catch (e: Throwable) { null } ?: return
        if (bitmap.width <= 0 || bitmap.height <= 0 || binding.cardRoot.width <= 0 || binding.cardRoot.height <= 0) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return
        }
        val drawable = bitmap.toDrawable(context.resources).apply { setBounds(0, 0, binding.cardRoot.width, binding.cardRoot.height) }
        binding.cardRoot.overlay.add(drawable)
        binding.textureView.alpha = 0f
        expandFreezeBitmap = bitmap
        expandFreezeDrawable = drawable
    }

    private fun clearExpandFreezeLayer() {
        expandFreezeDrawable?.let { runCatching { binding.cardRoot.overlay.remove(it) } }
        expandFreezeDrawable = null
        expandFreezeBitmap?.let { if (!it.isRecycled) it.recycle() }
        expandFreezeBitmap = null
        if (::binding.isInitialized && binding.textureView.isAttachedToWindow) binding.textureView.alpha = 1f
    }

    private fun centerToScreenTopLeftX(centerX: Int, viewWidth: Int): Int = realScreenWidth / 2 + centerX - viewWidth / 2
    private fun centerToScreenTopLeftY(centerY: Int, viewHeight: Int): Int = realScreenHeight / 2 + centerY - viewHeight / 2
    private fun screenTopLeftToCenterX(screenX: Int, viewWidth: Int): Int = screenX + viewWidth / 2 - realScreenWidth / 2
    private fun screenTopLeftToCenterY(screenY: Int, viewHeight: Int): Int = screenY + viewHeight / 2 - realScreenHeight / 2

    private fun getHiddenDockScreenY(): Int? {
        if (!::hiddenView.isInitialized || !hiddenView.isAttachedToWindow) return null
        return (hiddenView.layoutParams.cast<WindowManager.LayoutParams?>())?.y
    }
    private fun getHiddenDockScreenX(): Int? {
        if (!::hiddenView.isInitialized || !hiddenView.isAttachedToWindow) return null
        return (hiddenView.layoutParams.cast<WindowManager.LayoutParams?>())?.x
    }
    private fun isHiddenDockOnRight(): Boolean {
        val x = getHiddenDockScreenX() ?: return false
        return x >= realScreenWidth / 2
    }
    private fun clampHiddenDockY(screenY: Int): Int = screenY.coerceIn(0, realScreenHeight - floatingButtonHeight)

    private fun resolveNonOverlappingHiddenDockY(hiddenOnRight: Boolean, desiredScreenY: Int): Int {
        // 不重叠即可：紧贴占用区域的上下边界找位置，避免大步长导致的间隔过大
        val gap = (floatingButtonHeight * 0.26f).toInt().coerceAtLeast(16)
        // 同侧所有小窗（含 mini 与贴边 dock）的占用区域
        val occupied = synchronized(activeWindows) {
            activeWindows.filter { it !== this && !it.isDestroyed && !it.isClosedToBack &&
                ((it.isFloating && !it.isHidden) || it.isHidden) }
                .mapNotNull { window ->
                    val slot = window.occupiedSlot()
                    val y = slot.first
                    val h = slot.second
                    val windowRight = if (window.isHidden) {
                        val dockX = runCatching {
                            window.hiddenView.layoutParams.cast<WindowManager.LayoutParams?>()?.x
                        }.getOrNull() ?: window.windowLayoutParams.x
                        dockX > 0
                    } else {
                        window.windowLayoutParams.x > 0
                    }
                    if (windowRight == hiddenOnRight) (y to h) else null
                }
        }
        if (occupied.isEmpty()) return clampHiddenDockY(desiredScreenY)
        fun overlaps(candidateY: Int) = occupied.any { (oy, oh) ->
            candidateY < oy + oh && oy < candidateY + floatingButtonHeight
        }
        val clampedDesired = clampHiddenDockY(desiredScreenY)
        if (!overlaps(clampedDesired)) return clampedDesired

        // 候选：紧贴每个占用区域的上方 / 下方（再留少量间隙），
        // 从中选最接近期望位置且不重叠的一个
        val candidates = mutableListOf<Int>()
        for ((oy, oh) in occupied) {
            val below = clampHiddenDockY(oy + oh + gap)
            val above = clampHiddenDockY(oy - floatingButtonHeight - gap)
            candidates.add(below)
            candidates.add(above)
        }
        candidates.sortBy { abs(it - clampedDesired) }
        candidates.forEach { c ->
            if (!overlaps(c)) return c
        }
        // 兜底：从期望位置向上/向下逐点扫描
        var i = 1
        while (i * 8 <= realScreenHeight) {
            val down = clampHiddenDockY(clampedDesired + i * 8)
            if (!overlaps(down)) return down
            val up = clampHiddenDockY(clampedDesired - i * 8)
            if (!overlaps(up)) return up
            i++
        }
        return clampedDesired
    }

    private fun snapHiddenDockAfterDrag(currentScreenX: Int, currentScreenY: Int) {
        if (!::hiddenView.isInitialized || !hiddenView.isAttachedToWindow) return
        val hiddenOnRight = currentScreenX + floatingButtonWidth / 2 >= realScreenWidth / 2
        val targetX = calcDockHiddenX(if (hiddenOnRight) 1 else -1)
        val targetY = resolveNonOverlappingHiddenDockY(hiddenOnRight, currentScreenY)
        val miniCenterY = screenTopLeftToCenterY(targetY, floatingButtonHeight)
        val miniEdgeX = if (!hiddenOnRight) (realScreenWidth - hangUpViewWidth - screenPaddingX) / -2
                         else (realScreenWidth - hangUpViewWidth - screenPaddingX) / 2
        val miniCollapsedX = if (!hiddenOnRight) miniEdgeX - (hangUpViewWidth + screenPaddingX)
                             else miniEdgeX + (hangUpViewWidth + screenPaddingX)
        hangUpPosition[0] = !hiddenOnRight
        hangUpPosition[1] = miniCenterY <= 0
        lastFloatViewLocation = intArrayOf(miniEdgeX, miniCenterY)
        windowLayoutParams.x = miniCollapsedX
        windowLayoutParams.y = miniCenterY
        if (binding.root.isAttachedToWindow) Instances.windowManager.updateViewLayout(binding.root, windowLayoutParams)
        dragCloseHandler?.animateTo(targetX, targetY)
    }

    private fun miniStackSpacing(): Int = (hangUpViewHeight * 0.08f).roundToInt().coerceAtLeast((12 * context.resources.displayMetrics.density).roundToInt())
    private fun sameMiniSide(x1: Int, x2: Int): Boolean = (x1 <= 0 && x2 <= 0) || (x1 > 0 && x2 > 0)

    private fun resolveNonOverlappingMiniLocation(desiredLocation: IntArray): IntArray {
        val normalizedDesired = normalizeMiniLocation(desiredLocation.copyOf())
        val minY = (hangUpViewHeight - realScreenHeight + screenPaddingY) / 2
        val maxY = (realScreenHeight - hangUpViewHeight - screenPaddingY) / 2
        val topY = min(minY, maxY)
        val bottomY = max(minY, maxY)
        val spacing = miniStackSpacing()
        val step = hangUpViewHeight + spacing
        val desiredX = normalizedDesired[0]
        val desiredY = normalizedDesired[1]
        // desiredX > 0 表示贴右，≤ 0 表示贴左
        val desiredRight = desiredX > 0
        // 同侧所有小窗（含贴边 dock 与 mini）的占用区域
        val occupied = synchronized(activeWindows) {
            activeWindows.filter { window ->
                window !== this && !window.isDestroyed && !window.isClosedToBack &&
                    ((window.isFloating && !window.isHidden) || window.isHidden)
            }.mapNotNull { window ->
                val slot = window.occupiedSlot()
                val y = slot.first
                val h = slot.second
                // 判断同侧：贴边用 dock 侧边，mini 用 x>0
                val windowRight = if (window.isHidden) {
                    val dockX = runCatching {
                        window.hiddenView.layoutParams.cast<WindowManager.LayoutParams?>()?.x
                    }.getOrNull() ?: window.windowLayoutParams.x
                    dockX > 0
                } else {
                    window.windowLayoutParams.x > 0
                }
                if (windowRight == desiredRight) (y to h) else null
            }
        }
        if (occupied.isEmpty()) return normalizedDesired
        fun overlaps(candidateY: Int) = occupied.any { (oy, oh) ->
            candidateY < oy + oh && oy < candidateY + hangUpViewHeight
        }
        fun buildLocation(candidateY: Int): IntArray = normalizeMiniLocation(intArrayOf(desiredX, candidateY.coerceIn(topY, bottomY)))
        if (!overlaps(desiredY)) return normalizedDesired

        // 候选：紧贴每个占用区域的上方 / 下方，选最接近期望位置且不重叠的
        val candidates = mutableListOf<Int>()
        for ((oy, oh) in occupied) {
            val below = (oy + oh).coerceIn(topY, bottomY)
            val above = (oy - hangUpViewHeight).coerceIn(topY, bottomY)
            candidates.add(below)
            candidates.add(above)
        }
        candidates.sortBy { abs(it - desiredY) }
        for (c in candidates) {
            if (!overlaps(c)) return buildLocation(c)
        }
        // 兜底：步进扫描
        occupied.sortedByDescending { it.first }.forEach { (oy, _) ->
            val candidateY = oy + step
            if (candidateY <= bottomY && !overlaps(candidateY)) return buildLocation(candidateY)
        }
        occupied.sortedBy { it.first }.forEach { (oy, _) ->
            val candidateY = oy - step
            if (candidateY >= topY && !overlaps(candidateY)) return buildLocation(candidateY)
        }
        var scanY = topY
        while (scanY <= bottomY) {
            if (!overlaps(scanY)) return buildLocation(scanY)
            scanY += step
        }
        return normalizedDesired
    }

    @SuppressLint("InflateParams")
    private inner class FloatViewTouchListener : View.OnTouchListener {
        private var moveStartX = 0f
        private var moveStartY = 0f
        private var movedX = 0f
        private var movedY = 0f
        private var minLong = 1.1
        private var isMoved = false
        private var hiddenMoved = false
        private var hiddenViewDeferredRemoval = false
        private var velocityTracker: VelocityTracker? = null

        private fun trackMovement(event: MotionEvent) {
            val dx = event.rawX - event.x
            val dy = event.rawY - event.y
            event.offsetLocation(dx, dy)
            velocityTracker?.addMovement(event)
            event.offsetLocation(-dx, -dy)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View?, event: MotionEvent): Boolean {
            if (isHidden) return true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelSpringAnimations()
                    cleanupDeferredHiddenViewIfNeeded()
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain()
                    trackMovement(event)
                    moveStartX = event.rawX
                    moveStartY = event.rawY
                    hangUpGestureDetector.onTouchEvent(event)
                }
                MotionEvent.ACTION_MOVE -> {
                    trackMovement(event)
                    movedX = event.rawX - moveStartX
                    movedY = event.rawY - moveStartY
                    if (abs(movedX) > minLong || abs(movedY) > minLong) {
                        isMoved = true
                        windowLayoutParams.x += movedX.toInt()
                        windowLayoutParams.y += movedY.toInt()
                        if (binding.root.isAttachedToWindow) Instances.windowManager.updateViewLayout(binding.root, windowLayoutParams)
                        if (tryEnterHiddenFromMiniDrag(event)) return true
                        moveStartX = event.rawX
                        moveStartY = event.rawY
                    }
                }
                MotionEvent.ACTION_UP -> {
                    cleanupDeferredHiddenViewIfNeeded()
                    trackMovement(event)
                    velocityTracker?.computeCurrentVelocity(1000, ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat())
                    val xVelocity = velocityTracker?.xVelocity ?: 0f
                    val yVelocity = velocityTracker?.yVelocity ?: 0f
                    velocityTracker?.recycle()
                    velocityTracker = null
                    if (isMoved) {
                        val nowY = event.rawY
                        val windowCoordinate = intArrayOf(windowLayoutParams.x, windowLayoutParams.y)
                        if (abs(xVelocity) > VELOCITY_THRESHOLD) hangUpPosition[0] = xVelocity < 0
                        else hangUpPosition[0] = windowCoordinate[0] <= 0
                        hangUpPosition[1] = windowCoordinate[1] <= 0
                        var location = genFloatViewLocation()
                        if (abs(yVelocity) > VELOCITY_THRESHOLD) {
                            location[1] = if (yVelocity < 0) (hangUpViewHeight - realScreenHeight + screenPaddingY) / 2
                                          else (realScreenHeight - hangUpViewHeight - screenPaddingY) / 2
                        } else {
                            location[1] = windowLayoutParams.y
                            if (nowY < realScreenHeight * 0.1f) location[1] = (hangUpViewHeight - realScreenHeight + screenPaddingY) / 2
                            if (nowY > realScreenHeight - realScreenHeight * 0.1f) location[1] = (realScreenHeight - hangUpViewHeight - screenPaddingY) / 2
                        }
                        var position = 0
                        if (windowCoordinate[0] <= (realScreenWidth - screenPaddingX / 2) / -2) {
                            location[0] -= (hangUpViewWidth + screenPaddingX)
                            position = -1
                        } else if (windowCoordinate[0] >= (realScreenWidth - screenPaddingX / 2) / 2) {
                            location[0] += (hangUpViewWidth + screenPaddingX)
                            position = 1
                        }
                        if (position != 0) {
                            AnimatorSet().apply {
                                playTogether(moveViewAnim(windowCoordinate, location))
                                addListener(onEnd = {
                                    isHidden = true
                                    ensureHiddenViewAttached(position, location[1])
                                    playHiddenRevealAnimation(position)
                                    isMoved = false
                                })
                                duration = 300
                                interpolator = OvershootInterpolator(0.4f)
                                start()
                            }
                        } else {
                            // 松手回到 mini：mini（isFloating 且未贴边）也必须避让其它小窗
                            if (isFloating && !isHidden) {
                                location = resolveNonOverlappingMiniLocation(location)
                                hangUpPosition[0] = location[0] <= 0
                                hangUpPosition[1] = location[1] <= 0
                                lastFloatViewLocation = location.copyOf()
                            }
                            val targetLocation = location.copyOf()
                            springAnim = SpringAnimator(
                                onUpdate = { x, y ->
                                    windowLayoutParams.x = x.roundToInt()
                                    windowLayoutParams.y = y.roundToInt()
                                    if (binding.root.isAttachedToWindow) Instances.windowManager.updateViewLayout(binding.root, windowLayoutParams)
                                },
                                onEnd = { lastFloatViewLocation = targetLocation; isMoved = false }
                            ).also {
                                it.start(windowCoordinate[0].toFloat(), location[0].toFloat(), xVelocity,
                                         windowCoordinate[1].toFloat(), location[1].toFloat(), yVelocity)
                            }
                        }
                    } else {
                        hangUpGestureDetector.onTouchEvent(event)
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    cleanupDeferredHiddenViewIfNeeded()
                    velocityTracker?.recycle()
                    velocityTracker = null
                    isMoved = false
                    cancelSpringAnimations()
                }
            }
            return true
        }

        private fun miniEdgeX(onLeft: Boolean): Int = if (onLeft) (realScreenWidth - hangUpViewWidth - screenPaddingX) / -2
                                                     else (realScreenWidth - hangUpViewWidth - screenPaddingX) / 2
        private fun miniHiddenCollapseDistance(): Int = hangUpViewWidth + screenPaddingX
        private fun hiddenToMiniTriggerDistancePx(): Int = (context.resources.displayMetrics.density * 10f).roundToInt().coerceAtLeast(8)
        private fun miniHiddenCollapsedX(onLeft: Boolean): Int = miniEdgeX(onLeft) + miniHiddenCollapseDistance() * (if (onLeft) -1 else 1)
        private fun clampMiniY(targetY: Int): Int {
            val topY = (hangUpViewHeight - realScreenHeight + screenPaddingY) / 2
            val bottomY = (realScreenHeight - hangUpViewHeight - screenPaddingY) / 2
            return targetY.coerceIn(min(topY, bottomY), max(topY, bottomY))
        }
        private fun resolveHiddenInflateContext(): Context {
            return try {
                val moduleContext = context.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY or Context.CONTEXT_INCLUDE_CODE)
                CommonContextWrapper.createAppCompatContext(moduleContext)
            } catch (e: Exception) {
                XLog.e("$TAG: Failed to create module context for inflation", e)
                context
            }
        }
        private fun ensureHiddenViewAttached(position: Int, targetY: Int) {
            val hiddenX = calcDockHiddenX(position)
            val rawHiddenY = centerToScreenTopLeftY(targetY, floatingButtonHeight)
            val hiddenOnRight = position > 0
            val hiddenY = resolveNonOverlappingHiddenDockY(hiddenOnRight, rawHiddenY)
            if (!::hiddenView.isInitialized || !hiddenView.isAttachedToWindow) {
                val inflateContext = resolveHiddenInflateContext()
                hiddenView = LayoutInflater.from(inflateContext).inflate(R.layout.view_floating_button, null, false)
                hiddenView.alpha = 0f
                dragCloseHandler = HiddenViewDragCloseHandler(
                    context = inflateContext,
                    hiddenView = hiddenView,
                    windowManager = Instances.windowManager,
                    onClose = { closeAndRemoveTask() },
                    onExpand = { hiddenViewToFloatView() },
                    onDragRelease = { currentX, currentY -> snapHiddenDockAfterDrag(currentX, currentY) },
                    screenWidth = realScreenWidth,
                    screenHeight = realScreenHeight,
                    floatingButtonWidth = floatingButtonWidth,
                    floatingButtonHeight = floatingButtonHeight
                ).also { it.attach() }
                hiddenView.findViewById<View>(R.id.backgroundView).background = ContextCompat.getDrawable(inflateContext, R.drawable.floating_dock_bg)
                val iconView = hiddenView.findViewById<ImageView>(R.id.dockAppIcon)
                runCatching {
                    componentName?.packageName?.let { pkg ->
                        val pm = inflateContext.packageManager
                        val appInfo = pm.getApplicationInfo(pkg, 0)
                        iconView.setImageDrawable(pm.getApplicationIcon(appInfo))
                    }
                }.onFailure {
                    iconView.setImageDrawable(null)
                }
                Instances.windowManager.addView(hiddenView, WindowManager.LayoutParams().apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = hiddenX; y = hiddenY
                    width = floatingButtonWidth; height = floatingButtonHeight
                    type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    format = PixelFormat.TRANSLUCENT
                    flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                })
                return
            }
            hiddenView.visibility = View.VISIBLE
            dragCloseHandler?.detach()
            dragCloseHandler = HiddenViewDragCloseHandler(
                context = hiddenView.context,
                hiddenView = hiddenView,
                windowManager = Instances.windowManager,
                onClose = { closeAndRemoveTask() },
                onExpand = { hiddenViewToFloatView() },
                onDragRelease = { currentX, currentY -> snapHiddenDockAfterDrag(currentX, currentY) },
                screenWidth = realScreenWidth,
                screenHeight = realScreenHeight,
                floatingButtonWidth = floatingButtonWidth,
                floatingButtonHeight = floatingButtonHeight
            ).also { it.attach() }
            hiddenView.findViewById<View>(R.id.backgroundView).background = ContextCompat.getDrawable(hiddenView.context, R.drawable.floating_dock_bg)
            val iconView = hiddenView.findViewById<ImageView>(R.id.dockAppIcon)
            runCatching {
                componentName?.packageName?.let { pkg ->
                    val pm = hiddenView.context.packageManager
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    iconView.setImageDrawable(pm.getApplicationIcon(appInfo))
                }
            }.onFailure {
                iconView.setImageDrawable(null)
            }
            val hiddenLp = hiddenView.layoutParams.cast<WindowManager.LayoutParams?>() ?: return
            hiddenLp.gravity = Gravity.TOP or Gravity.START
            hiddenLp.x = hiddenX; hiddenLp.y = hiddenY
            hiddenLp.width = floatingButtonWidth; hiddenLp.height = floatingButtonHeight
            Instances.windowManager.updateViewLayout(hiddenView, hiddenLp)
        }
        private fun cleanupDeferredHiddenViewIfNeeded() {
            if (!hiddenViewDeferredRemoval) return
            hiddenViewDeferredRemoval = false
            if (!::hiddenView.isInitialized) return
            if (!isHidden && hiddenView.isAttachedToWindow) {
                dragCloseHandler?.detach()
                hiddenView.setOnTouchListener(null)
                Instances.windowManager.removeView(hiddenView)
            }
        }
        private fun playHiddenRevealAnimation(position: Int) {
            if (!::hiddenView.isInitialized) return
            fun startRevealNow() {
                if (!::hiddenView.isInitialized || !hiddenView.isAttachedToWindow) return
                val startTranslationX = if (position > 0) floatingButtonWidth.toFloat() else -floatingButtonWidth.toFloat()
                hiddenView.animate().cancel()
                hiddenView.alpha = 0f
                hiddenView.translationX = startTranslationX
                hiddenView.animate().alpha(1f).translationX(0f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
            }
            if (hiddenView.isAttachedToWindow) { startRevealNow(); return }
            hiddenView.post {
                if (!::hiddenView.isInitialized || !isHidden) return@post
                if (hiddenView.isAttachedToWindow) startRevealNow()
                else { hiddenView.alpha = 1f; hiddenView.translationX = 0f }
            }
        }
        private fun snapHiddenBackToCollapsed(onLeft: Boolean) {
            val collapsedX = miniHiddenCollapsedX(onLeft)
            if (windowLayoutParams.x == collapsedX) return
            ValueAnimator.ofInt(windowLayoutParams.x, collapsedX).apply {
                duration = 120
                interpolator = DecelerateInterpolator()
                addUpdateListener { windowLayoutParams.x = it.animatedValue.cast(); if (binding.root.isAttachedToWindow) Instances.windowManager.updateViewLayout(binding.root, windowLayoutParams) }
                start()
            }
        }
        private fun switchMiniToHiddenWithoutAnimation(position: Int, targetY: Int) {
            val clampedY = clampMiniY(targetY)
            val hiddenOnRight = position > 0
            val onLeft = !hiddenOnRight
            val edgeX = miniEdgeX(onLeft)
            val collapsedX = miniHiddenCollapsedX(onLeft)
            hangUpPosition[0] = onLeft
            hangUpPosition[1] = clampedY <= 0
            lastFloatViewLocation = intArrayOf(edgeX, clampedY)
            ensureHiddenViewAttached(position, clampedY)
            playHiddenRevealAnimation(position)
            hiddenViewDeferredRemoval = false
            windowLayoutParams.x = collapsedX
            windowLayoutParams.y = clampedY
            if (binding.root.isAttachedToWindow) Instances.windowManager.updateViewLayout(binding.root, windowLayoutParams)
            isHidden = true
        }
        private fun switchHiddenToMiniWithRevealAnimation(hiddenOnRight: Boolean, targetY: Int) {
            val clampedY = clampMiniY(targetY)
            val onLeft = !hiddenOnRight
            val edgeX = miniEdgeX(onLeft)
            val revealX = miniHiddenCollapsedX(onLeft)
            // dock→mini 转换时，用避让逻辑确定最终 mini 位置，避免与其他 mini 重叠
            val avoidedLocation = resolveNonOverlappingMiniLocation(intArrayOf(edgeX, clampedY))
            val finalY = avoidedLocation[1]
            hangUpPosition[0] = onLeft
            hangUpPosition[1] = finalY <= 0
            lastFloatViewLocation = avoidedLocation.copyOf()
            windowLayoutParams.x = revealX
            windowLayoutParams.y = finalY
            if (binding.root.isAttachedToWindow) Instances.windowManager.updateViewLayout(binding.root, windowLayoutParams)
            isHidden = false
            if (::hiddenView.isInitialized && hiddenView.isAttachedToWindow) {
                hiddenView.animate().cancel()
                hiddenView.alpha = 0f
                hiddenView.translationX = 0f
                hiddenViewDeferredRemoval = true
            } else {
                hiddenViewDeferredRemoval = false
            }
            binding.freeformRoot.animate().cancel()
            binding.freeformRoot.alpha = 0.9f
            binding.freeformRoot.scaleX = 0.96f
            binding.freeformRoot.scaleY = 0.96f
            binding.freeformRoot.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(120).setInterpolator(DecelerateInterpolator()).start()
        }
        private fun tryEnterHiddenFromMiniDrag(event: MotionEvent): Boolean {
            val onLeft = windowLayoutParams.x <= 0
            val collapsedX = miniHiddenCollapsedX(onLeft)
            val reachedCollapsedEdge = if (onLeft) windowLayoutParams.x <= collapsedX else windowLayoutParams.x >= collapsedX
            if (!reachedCollapsedEdge) return false
            switchMiniToHiddenWithoutAnimation(if (onLeft) -1 else 1, windowLayoutParams.y)
            hiddenMoved = true
            moveStartX = event.rawX
            moveStartY = event.rawY
            isMoved = false
            return true
        }
    }

    private val floatViewTouchListener = FloatViewTouchListener()
    private val hangUpGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            floatViewToNormalView()
            return true
        }
    })

    private fun calcDockHiddenX(position: Int): Int = if (position > 0) realScreenWidth - floatingButtonWidth / 2 else -floatingButtonWidth / 2

    private fun genFloatViewLocation(): IntArray = intArrayOf(
        if (hangUpPosition[0]) (realScreenWidth - hangUpViewWidth - screenPaddingX) / -2 else (realScreenWidth - hangUpViewWidth - screenPaddingX) / 2,
        if (hangUpPosition[1]) (hangUpViewHeight - realScreenHeight + screenPaddingY) / 2 else (realScreenHeight - hangUpViewHeight - screenPaddingY) / 2
    )

    private fun normalizeMiniLocation(location: IntArray): IntArray {
        val minX = (realScreenWidth - hangUpViewWidth - screenPaddingX) / -2
        val maxX = (realScreenWidth - hangUpViewWidth - screenPaddingX) / 2
        val topY = (hangUpViewHeight - realScreenHeight + screenPaddingY) / 2
        val bottomY = (realScreenHeight - hangUpViewHeight - screenPaddingY) / 2
        return intArrayOf(
            location[0].coerceIn(min(minX, maxX), max(minX, maxX)),
            location[1].coerceIn(min(topY, bottomY), max(topY, bottomY))
        )
    }

    private fun moveViewAnim(startCoordinate: IntArray, endCoordinate: IntArray): Animator {
        val animateX = endCoordinate[0] != -1
        val animateY = endCoordinate[1] != -1
        val sx = startCoordinate[0]; val sy = startCoordinate[1]
        val ex = endCoordinate[0]; val ey = endCoordinate[1]
        return ValueAnimator.ofFloat(0f, 1f).apply {
            addUpdateListener {
                if (!binding.root.isAttachedToWindow) return@addUpdateListener
                val f = it.animatedValue.cast<Float>()
                if (animateX) windowLayoutParams.x = (sx + (ex - sx) * f).roundToInt()
                if (animateY) windowLayoutParams.y = (sy + (ey - sy) * f).roundToInt()
                Instances.windowManager.updateViewLayout(binding.root, windowLayoutParams)
            }
        }
    }

    private fun cardViewMarginAnim(topStart: Int, bottomStart: Int, rightStart: Int, topEnd: Int, bottomEnd: Int, rightEnd: Int): Animator {
        return AnimatorSet().apply {
            playTogether(
                ValueAnimator.ofInt(topStart, topEnd).apply {
                    addUpdateListener { binding.cardRoot.layoutParams = (binding.cardRoot.layoutParams.cast<ConstraintLayout.LayoutParams>()).apply { topMargin = it.animatedValue.cast() } }
                },
                ValueAnimator.ofInt(bottomStart, bottomEnd).apply {
                    addUpdateListener { binding.cardRoot.layoutParams = (binding.cardRoot.layoutParams.cast<ConstraintLayout.LayoutParams>()).apply { bottomMargin = it.animatedValue.cast() } }
                },
                ValueAnimator.ofInt(rightStart, rightEnd).apply {
                    addUpdateListener { binding.cardRoot.layoutParams = (binding.cardRoot.layoutParams.cast<ConstraintLayout.LayoutParams>()).apply { rightMargin = it.animatedValue.cast() } }
                },
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun hiddenViewToFloatView() {
        val hiddenLp = if (::hiddenView.isInitialized) hiddenView.layoutParams.cast<WindowManager.LayoutParams?>() else null
        val currentMiniCenterY = if (hiddenLp != null) screenTopLeftToCenterY(hiddenLp.y, floatingButtonHeight) else windowLayoutParams.y
        val windowCoordinate = intArrayOf(windowLayoutParams.x, currentMiniCenterY)
        hangUpPosition[0] = windowCoordinate[0] <= 0
        hangUpPosition[1] = windowCoordinate[1] <= 0
        val location = intArrayOf(
            if (hangUpPosition[0]) (realScreenWidth - hangUpViewWidth - screenPaddingX) / -2 else (realScreenWidth - hangUpViewWidth - screenPaddingX) / 2,
            -1
        )
        AnimatorSet().apply {
            playTogether(moveViewAnim(windowCoordinate, location), ObjectAnimator.ofFloat(binding.freeformRoot, View.ALPHA, binding.freeformRoot.alpha, 1f))
            addListener(
                onStart = {
                    dragCloseHandler?.detach()
                    hiddenView.setOnTouchListener(null)
                    if (hiddenView.isAttachedToWindow) Instances.windowManager.removeView(hiddenView)
                    isHidden = false
                },
                onEnd = { if (!isHidden) lastFloatViewLocation = intArrayOf(location[0], windowCoordinate[1]) }
            )
            duration = 300
            interpolator = OvershootInterpolator(0.4f)
            start()
        }
    }

    private fun moveFloatViewLocation(location: IntArray) {
        val windowCoordinate = intArrayOf(windowLayoutParams.x, windowLayoutParams.y)
        AnimatorSet().apply {
            playTogether(moveViewAnim(windowCoordinate, location))
            addListener(onStart = {
                if (!binding.root.isAttachedToWindow) return@addListener
                binding.freeformRoot.scaleY = 1f
                binding.freeformRoot.scaleX = 1f
                Instances.windowManager.updateViewLayout(binding.root, windowLayoutParams.apply { height = hangUpViewHeight; width = hangUpViewWidth })
            })
            duration = 350
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    private fun moveHiddenViewLocation(location: IntArray) {
        if (!::hiddenView.isInitialized) return
        val layoutParams = hiddenView.layoutParams.cast<WindowManager.LayoutParams>()
        val windowCoordinate = intArrayOf(layoutParams.x, layoutParams.y)
        val position = if (layoutParams.x > 0) { location[0] += (hangUpViewWidth + screenPaddingX); 1 } else { location[0] -= (hangUpViewWidth + screenPaddingX); -1 }
        val rawTargetHiddenY = centerToScreenTopLeftY(location[1], floatingButtonHeight)
        val targetHiddenY = resolveNonOverlappingHiddenDockY(position > 0, rawTargetHiddenY)
        AnimatorSet().apply {
            playTogether(
                ValueAnimator.ofInt(windowCoordinate[0], calcDockHiddenX(position)).apply {
                    addUpdateListener { if (hiddenView.isAttachedToWindow) Instances.windowManager.updateViewLayout(hiddenView, layoutParams.apply { x = it.animatedValue.cast() }) }
                },
                ValueAnimator.ofInt(windowCoordinate[1], targetHiddenY).apply {
                    addUpdateListener { if (hiddenView.isAttachedToWindow) Instances.windowManager.updateViewLayout(hiddenView, layoutParams.apply { y = it.animatedValue.cast() }) }
                },
                moveViewAnim(intArrayOf(windowLayoutParams.x, windowLayoutParams.y), intArrayOf(location[0], location[1]))
            )
            duration = 350
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    private fun floatViewToNormalView() = floatViewToNormalViewInternal()

    fun restoreToNormalView() {
        if (isDestroyed) return
        if (isHidden) hiddenViewToNormalView()
        else if (isFloating) floatViewToNormalViewInternal()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun hiddenViewToNormalView() {
        if (::hiddenView.isInitialized) {
            dragCloseHandler?.detach()
            hiddenView.setOnTouchListener(null)
            if (hiddenView.isAttachedToWindow) Instances.windowManager.removeView(hiddenView)
        }
        isHidden = false
        binding.freeformRoot.alpha = 1f
        floatViewToNormalViewInternal()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun floatViewToNormalViewInternal() {
        cancelSpringAnimations()
        if (isAnimating || isDestroyed || isClosedToBack || !binding.root.isAttachedToWindow) return

        binding.textureView.setOnTouchListener { _, motionEvent -> forwardMotionEvent(motionEvent); true }
        if (allowTapOutsideToClose) {
            binding.root.setOnTouchListener { _, event -> backgroundGestureDetector.onTouchEvent(event); true }
        } else {
            binding.root.setOnTouchListener(null)
        }

        val windowCoordinate = intArrayOf(windowLayoutParams.x, windowLayoutParams.y)
        refreshFreeformSize()
        val restoreScaleX = freeformWidth / rootWidth.toFloat()
        val restoreScaleY = freeformHeight / rootHeight.toFloat()
        val center = genCenterLocation()

        val fastDecelerateAnims = AnimatorSet().apply {
            playTogether(
                moveViewAnim(windowCoordinate, center),
                ValueAnimator.ofFloat(0f, config.dimAmount).apply {
                    addUpdateListener {
                        if (!backgroundView.isAttachedToWindow) return@addUpdateListener
                        Instances.windowManager.updateViewLayout(backgroundView, backgroundLayoutParams.apply { dimAmount = it.animatedValue.cast() })
                    }
                }
            )
            duration = 250
            interpolator = DecelerateInterpolator()
        }

        var topMargin = 0f; var bottomMargin = 0f; var rightMargin = 0f
        if (screenIsPortrait()) { topMargin = freeformShadow; bottomMargin = barHeight }
        else { rightMargin = barHeight }

        val overshootAnims = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(binding.bottomBar.root, View.ALPHA, 1f),
                ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_X, mScaleX, restoreScaleX),
                ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_Y, mScaleY, restoreScaleY),
                cardViewMarginAnim(
                    (binding.cardRoot.layoutParams.cast<ConstraintLayout.LayoutParams>()).topMargin,
                    (binding.cardRoot.layoutParams.cast<ConstraintLayout.LayoutParams>()).bottomMargin,
                    (binding.cardRoot.layoutParams.cast<ConstraintLayout.LayoutParams>()).rightMargin,
                    topMargin.roundToInt(), bottomMargin.roundToInt(), rightMargin.roundToInt()
                )
            )
            duration = 300
            interpolator = DecelerateInterpolator()
            startDelay = 100
        }

        AnimatorSet().apply {
            playTogether(fastDecelerateAnims, overshootAnims)
            addListener(
                onStart = {
                    isAnimating = true
                    binding.freeformRoot.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    backgroundView.visibility = View.VISIBLE
                    binding.freeformRoot.scaleX = mScaleX
                    binding.freeformRoot.scaleY = mScaleY
                    Instances.windowManager.updateViewLayout(binding.root, windowLayoutParams.apply { height = rootHeight; width = rootWidth })
                    binding.cardRoot.radius = effectiveCornerRadius
                },
                onEnd = {
                    isAnimating = false
                    binding.freeformRoot.setLayerType(View.LAYER_TYPE_NONE, null)
                    binding.cardRoot.translationX = 0f
                    binding.cardRoot.translationY = 0f
                    refreshScale()
                }
            )
            start()
        }
        isFloating = false
        setWindowNoUpdateAnimation()
    }

    private fun forwardMotionEvent(event: MotionEvent) {
        if (isDestroyed || displayId < 0) return
        try {
            val newEvent = MotionEvent.obtain(event)
            touchMatrix.reset()
            touchMatrix.setScale(1f / scaleX, 1f / scaleY)
            newEvent.transform(touchMatrix)
            setDisplayIdMethod.invoke(newEvent, displayId)
            Instances.inputManager.injectInputEvent(newEvent, 0)
            newEvent.recycle()
        } catch (e: Exception) {
            XLog.e("$TAG: Failed to forward motion event", e)
        }
    }

    fun moveToTop() {
        if (isDestroyed) return
        try {
            if (binding.root.isAttachedToWindow) reorderWindowInWms(binding.root)
            if (isHidden && ::hiddenView.isInitialized && hiddenView.isAttachedToWindow) reorderWindowInWms(hiddenView)
            FreeformManager.moveToTop(displayId)
        } catch (e: Exception) {
            XLog.e("$TAG: Failed to move to top", e)
        }
    }

    private fun reorderWindowInWms(view: View) {
        val wms = Instances.iWindowManager
        val viewRootImpl = XposedHelpers.callMethod(view, "getViewRootImpl")
        val iWindow = XposedHelpers.getObjectField(viewRootImpl, "mWindow").cast<IBinder>()
        val globalLock = XposedHelpers.getObjectField(wms, "mGlobalLock")
        try {
            synchronized(globalLock) {
                val windowMap = XposedHelpers.getObjectField(wms, "mWindowMap").cast<HashMap<*, *>>()
                val windowState = windowMap[iWindow]
                val windowToken = XposedHelpers.callMethod(windowState, "getParent")
                val displayArea = XposedHelpers.callMethod(windowToken, "getParent")
                XposedHelpers.callMethod(displayArea, "positionChildAt", Int.MAX_VALUE, windowToken, false)
                XposedHelpers.callMethod(XposedHelpers.getObjectField(wms, "mWindowPlacerLocked"), "requestTraversal")
            }
        } catch (e: Exception) {
            XLog.e("$TAG: Failed to reorder window in WMS", e)
        }
    }

    fun closeToBack() {
        if (isDestroyed || isClosedToBack) {
            return
        }

        isClosedToBack = true
        cancelSpringAnimations()

        try {
            Instances.displayManager.unregisterDisplayListener(displayListener)
            if (::hiddenView.isInitialized && hiddenView.isAttachedToWindow) {
                dragCloseHandler?.detach()
                dragCloseHandler = null
                Instances.windowManager.removeView(hiddenView)
            }
            if (::binding.isInitialized) {
                binding.textureView.removeCallbacks(initTimeoutRunnable)
                if (binding.root.isAttachedToWindow) Instances.windowManager.removeView(binding.root)
            }
            if (::backgroundView.isInitialized && backgroundView.isAttachedToWindow) Instances.windowManager.removeView(backgroundView)
        } catch (e: Exception) {
            XLog.e("$TAG: Error moving window to back", e)
        }
    }

    fun restoreFromBack() {
        if (!isClosedToBack || isDestroyed) return
        screenRotation = Instances.displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
        if (!screenIsPortrait()) hangUpPosition[0] = true
        lastFloatViewLocation = intArrayOf(-1, -1)
        try {
            initFloatViewSize()
            refreshFreeformSize()
            refreshScale()
            refreshTouchScale()
            refreshActionScale()
            windowLayoutParams.apply { width = rootWidth; height = rootHeight; x = genCenterLocation()[0]; y = genCenterLocation()[1] }
            applyWindowRefreshHint(windowLayoutParams)
            (binding.cardRoot.layoutParams.cast<ConstraintLayout.LayoutParams>()).apply {
                if (screenIsPortrait()) { topMargin = freeformShadow.roundToInt(); bottomMargin = barHeight.roundToInt(); rightMargin = 0 }
                else { topMargin = 0; bottomMargin = 0; rightMargin = barHeight.roundToInt() }
            }
            binding.cardRoot.radius = effectiveCornerRadius
            initFloatBar()
            initFinish = false
            updateFrameCount = 0
            binding.lottieView.alpha = 1f
            binding.lottieView.playAnimation()
            binding.textureView.alpha = 0f
            binding.freeformRoot.alpha = 0f
            binding.freeformRoot.scaleX = mScaleX * 0.9f
            binding.freeformRoot.scaleY = mScaleY * 0.9f
            Instances.windowManager.addView(backgroundView, backgroundLayoutParams.apply { dimAmount = config.dimAmount })
            Instances.windowManager.addView(binding.root, windowLayoutParams)
            val restoreTargetScaleX = mScaleX
            val restoreTargetScaleY = mScaleY
            isAnimating = true
            binding.root.post {
                if (!binding.root.isAttachedToWindow || isDestroyed) { isAnimating = false; return@post }
                binding.freeformRoot.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(binding.freeformRoot, View.ALPHA, 0f, 1f),
                        ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_X, restoreTargetScaleX * 0.9f, restoreTargetScaleX),
                        ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_Y, restoreTargetScaleY * 0.9f, restoreTargetScaleY),
                    )
                    duration = 250
                    interpolator = AccelerateDecelerateInterpolator()
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            binding.freeformRoot.setLayerType(View.LAYER_TYPE_NONE, null)
                            isAnimating = false
                        }
                    })
                    start()
                }
            }
            Instances.displayManager.registerDisplayListener(displayListener, mainHandler)
            binding.textureView.surfaceTexture?.let { attachVirtualSurface(it) }
            isClosedToBack = false
            isFloating = false
            isHidden = false
            binding.bottomBar.root.alpha = 1f
            backgroundView.visibility = View.VISIBLE
            setupTouchHandlers()
            setupControlBar()
            setWindowNoUpdateAnimation()
            if (!FreeformManager.hasTaskOnDisplay(displayId)) {
                if (componentName != null && userId >= 0) {
                    FreeformManager.startActivityOnDisplay(componentName, userId, displayId)
                }
            }
        } catch (e: Exception) {
            XLog.e("$TAG: Error restoring window", e)
        }
    }

    private fun closeToBackWithAnimation() {
        if (isDestroyed || isClosedToBack || isAnimating) {
            return
        }
        isAnimating = true
        binding.freeformRoot.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_X, mScaleX, mScaleX * 0.9f),
                ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_Y, mScaleY, mScaleY * 0.9f),
                ObjectAnimator.ofFloat(binding.freeformRoot, View.ALPHA, 1f, 0f),
            )
            duration = 175
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    binding.freeformRoot.setLayerType(View.LAYER_TYPE_NONE, null)
                    isAnimating = false
                    closeToBack()
                }
            })
            start()
        }
    }

    fun realDestroy() {
        if (isDestroyed) {
            return
        }
        if (!isClosedToBack) closeToBack()
        isDestroyed = true
        try {
            if (::binding.isInitialized) binding.textureView.removeCallbacks(initTimeoutRunnable)
            mainHandler.removeCallbacks(initTimeoutRunnable)
            dragCloseHandler?.detach()
            dragCloseHandler = null
            releaseVirtualSurface()
            unregisterWindow(this)
            if (hasAddedWindowToManager) {
                FreeformManager.removeWindow(displayId)
                hasAddedWindowToManager = false
            }
            if (::virtualDisplay.isInitialized) virtualDisplay.release()
        } catch (e: Exception) {
            XLog.e("$TAG: Error real destroying window", e)
        }
    }

    // ========== Surface 生命周期 ==========
    private fun attachVirtualSurface(surfaceTexture: SurfaceTexture) {
        if (!::virtualDisplay.isInitialized || isDestroyed) return
        try {
            releaseVirtualSurface()
            surfaceTexture.setDefaultBufferSize(freeformScreenWidth, freeformScreenHeight)
            virtualSurface = Surface(surfaceTexture).also { surface ->
                // 高刷新率已禁用
                virtualDisplay.surface = surface
            }
        } catch (e: Throwable) {
            XLog.e("$TAG: Failed to attach virtual surface", e)
        }
    }

    private fun releaseVirtualSurface() {
        runCatching { if (::virtualDisplay.isInitialized) virtualDisplay.surface = null }
        virtualSurface?.let { runCatching { it.release() } }
        virtualSurface = null
    }
    // ========================================

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (isDestroyed || isClosedToBack) return
        try {
            // 高刷新率暂不应用
            if (!::virtualDisplay.isInitialized) {
                val createdDisplay = createVirtualDisplay("ZFlow@${System.currentTimeMillis()}", 0f)
                    ?: run { XLog.e("$TAG: DisplayManager returned null VirtualDisplay"); return }
                virtualDisplay = createdDisplay
                displayId = virtualDisplay.display.displayId
                markVirtualDisplaySpecApplied()
                XLog.d("$TAG: [$traceId] VirtualDisplay created displayId=$displayId size=${freeformScreenWidth}x$freeformScreenHeight dpi=$freeformDpi")
                try {
                    val wmHidden = Refine.unsafeCast<WindowManagerHidden>(Instances.windowManager)
                    wmHidden.setDisplayImePolicy(displayId, WindowManagerHidden.DISPLAY_IME_POLICY_FALLBACK_DISPLAY)
                } catch (_: Exception) {
                    // Device/ROM may not support this hidden API.
                }
                if (!hasAddedWindowToManager) {
                    FreeformManager.addWindow(this)
                    hasAddedWindowToManager = true
                }
            }
            attachVirtualSurface(surface)
            if (directToMini) {
                binding.freeformRoot.scaleX = 1f
                binding.freeformRoot.scaleY = 1f
            }
            binding.textureView.removeCallbacks(initTimeoutRunnable)
            binding.textureView.postDelayed(initTimeoutRunnable, INIT_FRAME_TIMEOUT_MS)
            if (!hasRequestedInitialTaskMove) {
                hasRequestedInitialTaskMove = true
                val taskMoveDelay = if (directToMini) MINI_TASK_MOVE_DELAY_MS else TASK_MOVE_DELAY_MS

                mainHandler.postDelayed(initialPlacement@{
                    if (isDestroyed || isClosedToBack || displayId < 0) return@initialPlacement
                    val hadTask = currentTaskId > 0
                    val moveRequested = if (hadTask) {
                        FreeformManager.moveTaskToDisplaySafely(currentTaskId, displayId)
                    } else {
                        false
                    }

                    XLog.d("$TAG: [$traceId] Initial task move request: taskId=$currentTaskId displayId=$displayId hadTask=$hadTask moveRequested=$moveRequested")

                    if (hadTask && pendingIntent != null) {
                        // 已有任务先迁入虚拟屏，再发送通知详情意图。
                        notificationLaunchGeneration += 1L
                        queuedNotificationIntent = pendingIntent
                        notificationIntentSent = false
                        scheduleQueuedNotificationLaunch(notificationLaunchGeneration)
                    } else if (!hadTask && componentName != null && userId >= 0) {
                        // 没有现有 Task，先在虚拟屏建立 Launcher Task。
                        startActivityOnVirtualDisplayIfNeeded()
                    }

                // 延迟验证迁移结果
                mainHandler.postDelayed({
                    if (isDestroyed || isClosedToBack || displayId < 0) {
                        return@postDelayed
                    }

                    val topTask = FreeformManager.getTopTaskIdOnDisplay(displayId)
                    val hasTask = FreeformManager.hasTaskOnDisplay(displayId)

                    XLog.d("$TAG: [$traceId] Verify task result (1st) requestedTask=$currentTaskId moveRequested=$moveRequested displayId=$displayId hasTask=$hasTask topTask=$topTask")

                    if (hasTask && topTask > 0) {
                        bindTask(topTask)
                    } else {
                        // A configuration rebuild can replace the original task ID. Resolve
                        // the replacement before considering a second launcher invocation.
                        val aliveTaskId = FreeformManager.resolveBestTaskIdForPackage(
                            componentName?.packageName,
                            currentTaskId
                        )
                        if (aliveTaskId > 0) {
                            bindTask(aliveTaskId)
                            val actualDisplay = FreeformManager.getTaskDisplayId(aliveTaskId)
                            if (actualDisplay != displayId) {
                                XLog.w("$TAG: [$traceId] Task migration still pending, retrying move taskId=$aliveTaskId actualDisplay=$actualDisplay")
                                FreeformManager.moveTaskToDisplaySafely(aliveTaskId, displayId)
                            }
                        } else if (componentName != null && userId >= 0) {
                            XLog.w("$TAG: [$traceId] No live task after first verify, starting fallback")
                            startActivityOnVirtualDisplayIfNeeded()
                        }
                    }

                    // 二次延迟验证，确保任务最终正确
                    mainHandler.postDelayed({
                        if (isDestroyed || isClosedToBack) return@postDelayed
                        val topTask2 = FreeformManager.getTopTaskIdOnDisplay(displayId)
                        val hasTask2 = FreeformManager.hasTaskOnDisplay(displayId)
                        XLog.d("$TAG: [$traceId] Verify task result (2nd) displayId=$displayId hasTask=$hasTask2 topTask=$topTask2")
                        if (hasTask2 && topTask2 > 0 && topTask2 != currentTaskId) {
                            bindTask(topTask2)
                        } else if (!hasTask2) {
                            val aliveTaskId2 = FreeformManager.resolveBestTaskIdForPackage(
                                componentName?.packageName,
                                currentTaskId
                            )
                            if (aliveTaskId2 > 0) {
                                bindTask(aliveTaskId2)
                                val actualDisplay2 = FreeformManager.getTaskDisplayId(aliveTaskId2)
                                XLog.w("$TAG: [$traceId] Task still alive after second verify taskId=$aliveTaskId2 actualDisplay=$actualDisplay2")
                                if (actualDisplay2 != displayId) {
                                    FreeformManager.moveTaskToDisplaySafely(aliveTaskId2, displayId)
                                }
                            } else if (componentName != null && userId >= 0) {
                                XLog.e("$TAG: [$traceId] No live task after second verify, starting final fallback")
                                startActivityOnVirtualDisplayIfNeeded()
                            }
                        }
                    }, TASK_SECOND_VERIFY_DELAY_MS)

                    }, TASK_VERIFY_DELAY_MS)
                }, taskMoveDelay)
            }
        } catch (e: Exception) {
            XLog.e("$TAG: [$traceId] Failed to create or attach VirtualDisplay", e)
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        if (isDestroyed || !::virtualDisplay.isInitialized) return
        try {
            surface.setDefaultBufferSize(freeformScreenWidth, freeformScreenHeight)
            resizeVirtualDisplayIfNeeded()
        } catch (e: Exception) {
            XLog.e("$TAG: Failed to handle texture size change", e)
        }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        if (::binding.isInitialized) binding.textureView.removeCallbacks(initTimeoutRunnable)
        releaseVirtualSurface()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        if (!initFinish) {
            ++updateFrameCount
            if (updateFrameCount >= INIT_MIN_FRAME_COUNT) {
                binding.textureView.removeCallbacks(initTimeoutRunnable)
                binding.lottieView.cancelAnimation()
                binding.lottieView.animate().alpha(0f).setDuration(200).start()
                binding.textureView.animate().alpha(1f).setDuration(200).start()
                initFinish = true
                if (directToMini) {
                    directMiniExpandGuardUntil = min(directMiniExpandGuardUntil, SystemClock.uptimeMillis() + 300L)
                }
            }
        }
    }
}
