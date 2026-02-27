package io.relimus.zflow.xposed.ui.window

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import java.lang.reflect.Method
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
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
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
import java.lang.reflect.Field
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.core.graphics.drawable.toDrawable

/**
 * FreeformWindow runs in system_server with elevated privileges.
 * Creates VirtualDisplay and floating overlay window with higher z-order priority.
 * This solves the issue of window disappearing when system UI components appear.
 */
@SuppressLint("ClickableViewAccessibility")
class FreeformWindow(
    baseContext: Context,
    val componentName: ComponentName?,
    private val userId: Int,
    private val taskId: Int,
    private val config: FreeformConfig = FreeformConfig(),
    private val directToMini: Boolean = false,
    private val inheritedMiniLocation: IntArray? = null
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

        // 虚拟显示屏方向常量
        private const val VIRTUAL_DISPLAY_ROTATION_PORTRAIT = 1
        private const val VIRTUAL_DISPLAY_ROTATION_LANDSCAPE = 0

        // 速度预测阈值 (px/s)
        private const val VELOCITY_THRESHOLD = 3000f
        private const val SUNOS_EXPAND_DURATION = 320L
        private const val SUNOS_LEASH_RETRY_MAX = 8
        private const val SUNOS_LEASH_RETRY_DELAY = 16L
        private const val SUNOS_FINISH_HOLD_DELAY = 64L
        private const val MINI_TASK_MOVE_DELAY_MS = 120L
        private const val HIGH_REFRESH_THRESHOLD = 60.5f
        private const val VIRTUAL_DISPLAY_FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            (1 shl 10) // VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 << 10
        private val SUNOS_EXPAND_INTERPOLATOR = PathInterpolator(0.2f, 0f, 0f, 1f)
    }

    private val context: Context = CommonContextWrapper.createAppCompatContext(baseContext)
    private lateinit var binding: ViewFreeformFlymeBinding
    private lateinit var virtualDisplay: VirtualDisplay
    private lateinit var backgroundView: View

    var displayId: Int = -1
        private set

    private var windowLayoutParams = WindowManager.LayoutParams()
    private var backgroundLayoutParams = WindowManager.LayoutParams()

    // 屏幕方向 - 使用 Surface.ROTATION_* 常量
    private var screenRotation: Int = Instances.displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0

    // 虚拟显示屏方向 - 小窗内应用的方向
    private var virtualDisplayRotation = VIRTUAL_DISPLAY_ROTATION_PORTRAIT

    // 主线程 Handler
    private val mainHandler = Handler(Looper.getMainLooper())

    // 屏幕方向变化监听
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

    // 屏幕尺寸 - 根据方向返回正确的宽高
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

    // 窗口尺寸 (用于显示) - 动态计算，考虑小窗内应用方向
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

    // Margins - 根据屏幕方向动态计算
    private val cardHeightMargin: Float
        get() = if (screenIsPortrait()) (barHeight + freeformShadow) else 0f
    private val cardWidthMargin: Float
        get() = if (screenIsPortrait()) 0f else barHeight

    // 小窗内部尺寸 (VirtualDisplay)
    private var freeformScreenWidth = 0
    private var freeformScreenHeight = 0
    private var freeformDpi = 320

    // 当前窗口缩放后的尺寸
    private var freeformWidth = 0
    private var freeformHeight = 0

    private val effectiveFreeformSize: Float
        get() = config.freeformSize.coerceIn(0.1f, 1f)

    private val effectiveFreeformSizeLand: Float
        get() = config.freeformSizeLand.coerceIn(0.1f, 1f)

    // 缩放比例
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

    // 触摸事件转发 - 缓存实例避免每次分配
    private val touchMatrix = Matrix()
    private val setDisplayIdMethod: Method by lazy {
        MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    }

    // 触摸坐标缩放
    private var scaleX = 1f
    private var scaleY = 1f

    // 缩放阈值
    private var goFloatScale = 0.6f
    private var goFullScale = 0.9f

    // 悬浮模式尺寸
    private var hangUpViewWidth = 0
    private var hangUpViewHeight = 0

    // Bar 高度
    private val barHeight: Float = context.resources.getDimension(R.dimen.bottom_bar_height_flyme)
    private val freeformShadow: Float = context.resources.getDimension(R.dimen.freeform_shadow)
    private val freeformCornerRadius: Float = context.resources.getDimension(R.dimen.freeform_corner_radius)

    // 侧边栏按钮尺寸 - 缓存避免在动画回调中访问资源导致崩溃
    private val floatingButtonWidth: Int = context.resources.getDimension(R.dimen.floating_button_width).toInt()
    private val floatingButtonHeight: Int = context.resources.getDimension(R.dimen.floating_button_height).toInt()

    // 屏幕边距
    private val screenPaddingX: Int = context.resources.getDimension(R.dimen.freeform_screen_width_padding).roundToInt()
    private val screenPaddingY: Int = context.resources.getDimension(R.dimen.freeform_screen_height_padding).roundToInt()

    // 状态
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
            initFinish = true
        }
    }
    var isFloating = false
        private set
    private var isZoomOut = false
    var isHidden = false
        private set
    private var isAnimating = false
    private var isSurfaceExpandFinishing = false
    private var expandFreezeBitmap: Bitmap? = null
    private var expandFreezeDrawable: BitmapDrawable? = null

    // 弹簧动画实例
    private var springAnim: SpringAnimator? = null

    private fun cancelSpringAnimations() {
        springAnim?.cancel()
        springAnim = null
    }

    // 挂起位置，0：是否在左，1：是否在上
    private val hangUpPosition = booleanArrayOf(false, true)
    // 存储上一次的悬浮位置
    private var lastFloatViewLocation: IntArray = intArrayOf(-1, -1)

    // 侧边栏视图
    private lateinit var hiddenView: View

    // 触摸状态
    private var lastX = -1f
    private var lastY = -1f
    private var touchId = -1
    private var controlDownX = -1f
    private var controlDownY = -1f
    private var isControlBarDragging = false
    private val controlBarTouchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // 手势检测器 - 背景点击关闭
    private val backgroundGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (!isFloating && !isAnimating) {
                closeToBackWithAnimation()
            }
            return true
        }
    })

    // 手势检测器 - bar 双击切换小窗内应用方向
    private val middleGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            return true
        }

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
        } catch (e: Exception) {
            XLog.e("$TAG: Failed to initialize FreeformWindow", e)
        }
    }

    /**
     * 判断屏幕是否是竖屏
     */
    private fun screenIsPortrait(): Boolean {
        return screenRotation == Surface.ROTATION_0 || screenRotation == Surface.ROTATION_180
    }

    fun getImeInsetsMetrics(): ImeInsetsMetrics {
        val lpSnapshot = WindowManager.LayoutParams().apply {
            copyFrom(windowLayoutParams)
        }
        val isPortrait = screenIsPortrait()
        val bottomDecor = if (isPortrait) barHeight else 0f
        val topDecor = (cardHeightMargin - bottomDecor).coerceAtLeast(0f)

        return ImeInsetsMetrics(
            layoutParams = lpSnapshot,
            freeformScreenHeight = freeformScreenHeight,
            scaleY = mScaleY,
            topDecorRaw = topDecor,
            bottomDecorRaw = bottomDecor
        )
    }

    private fun initConfig() {
        // 从配置读取 DPI
        freeformDpi = if (config.freeformDpi > 50) config.freeformDpi else getScreenDpi()

        // 小窗内部尺寸
        freeformScreenHeight = (min(realScreenHeight, realScreenWidth) / WIDTH_HEIGHT_RATIO).roundToInt()
        freeformScreenWidth = (freeformScreenHeight * WIDTH_HEIGHT_RATIO).roundToInt()

        // 初始化悬浮模式尺寸
        initFloatViewSize()

        // 初始化小窗显示尺寸
        refreshFreeformSize()

        // 缩放阈值
        refreshActionScale()
    }

    /**
     * 初始化悬浮模式尺寸 - 参考 FreeformView.initFloatViewSize
     * 支持小窗内应用横屏时的尺寸计算
     * mini 状态下 cardViewMarginAnim 会将 margin 动画到 0，故使用简化公式
     */
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

    /**
     * 刷新小窗显示尺寸 - 参考 FreeformView.refreshFreeformSize
     * 修复宽高比计算问题
     */
    private fun refreshFreeformSize() {
        if (screenIsPortrait()) {
            // 竖屏状态，以宽度为基准计算，确保宽高比正确
            freeformWidth = (rootWidth * effectiveFreeformSize).roundToInt()
            val contentHeight = (freeformWidth - (freeformShadow * 2)) / WIDTH_HEIGHT_RATIO
            freeformHeight = (contentHeight + cardHeightMargin).roundToInt()
        } else {
            // 横屏状态
            freeformHeight = (rootWidth * effectiveFreeformSizeLand).roundToInt()
            freeformHeight += cardHeightMargin.roundToInt()
            freeformWidth = ((freeformHeight + cardWidthMargin) * WIDTH_HEIGHT_RATIO).roundToInt()
        }
        if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
            // 内部应用横屏时，小窗变成宽扁形态 - 移植自 FreeformView.kt
            if (screenIsPortrait()) {
                freeformWidth = (rootWidth - (rootWidth * 0.05)).roundToInt()
                freeformHeight = ((freeformWidth + (cardHeightMargin * 2)) * WIDTH_HEIGHT_RATIO) .roundToInt()
            } else {
                freeformWidth = (realScreenWidth / 2 + cardWidthMargin).roundToInt()
                freeformHeight = ((freeformWidth * WIDTH_HEIGHT_RATIO) * 0.95).roundToInt()
            }
        }
    }

    /**
     * 刷新缩放阈值 - 完全复刻 FreeformView.refreshActionScale
     * goFloatScale 基于 freeformHeight * 0.8
     */
    private fun refreshActionScale() {
        goFloatScale = (freeformHeight * 0.8f) / rootHeight
        goFullScale = (freeformHeight * 1.1f) / rootHeight
    }

    private fun getScreenDpi(): Int {
        return context.resources.displayMetrics.densityDpi
    }

    private fun resolveHighRefreshHint(): HighRefreshHint? {
        val defaultDisplay = Instances.displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val currentMode = defaultDisplay.mode ?: return null

        val bestMode = defaultDisplay.supportedModes
            .filter {
                it.physicalWidth == currentMode.physicalWidth &&
                    it.physicalHeight == currentMode.physicalHeight
            }
            .maxByOrNull { it.refreshRate } ?: currentMode

        if (bestMode.refreshRate <= HIGH_REFRESH_THRESHOLD) return null
        return HighRefreshHint(
            refreshRate = bestMode.refreshRate,
            modeId = bestMode.modeId
        )
    }

    private fun applyWindowRefreshHint(layoutParams: WindowManager.LayoutParams) {
        val hint = resolveHighRefreshHint() ?: return
        layoutParams.preferredRefreshRate = hint.refreshRate
        layoutParams.preferredDisplayModeId = hint.modeId
    }

    private fun applySurfaceRefreshHint(surface: Surface, refreshRate: Float) {
        if (refreshRate <= HIGH_REFRESH_THRESHOLD) return
        try {
            surface.setFrameRate(
                refreshRate,
                Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                Surface.CHANGE_FRAME_RATE_ALWAYS
            )
        } catch (e: Throwable) {
            XLog.w("$TAG: Failed to set surface frame rate hint, rate=$refreshRate", e)
        }
    }

    @SuppressLint("WrongConstant")
    private fun createVirtualDisplay(
        name: String,
        refreshRate: Float
    ): VirtualDisplay? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                val configBuilder = VirtualDisplayConfig.Builder(
                    name,
                    freeformScreenWidth,
                    freeformScreenHeight,
                    freeformDpi
                ).setFlags(VIRTUAL_DISPLAY_FLAGS)

                if (refreshRate > HIGH_REFRESH_THRESHOLD) {
                    configBuilder.setRequestedRefreshRate(refreshRate)
                }

                return Instances.displayManager.createVirtualDisplay(configBuilder.build())
            } catch (e: Throwable) {
                XLog.w("$TAG: createVirtualDisplay(config) failed, fallback to legacy API", e)
            }
        }

        return Instances.displayManager.createVirtualDisplay(
            name,
            freeformScreenWidth,
            freeformScreenHeight,
            freeformDpi,
            null,
            VIRTUAL_DISPLAY_FLAGS
        )
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

        // 创建背景 View (点击关闭)
        backgroundView = View(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                        backgroundGestureDetector.onTouchEvent(event)
                    }
                }
                true
            }
        }

        // 横屏时调整布局
        if (!screenIsPortrait()) {
            hangUpPosition[0] = true
            (binding.cardRoot.layoutParams.cast<ConstraintLayout.LayoutParams>()).apply {
                topMargin = 0
                bottomMargin = 0
                rightMargin = barHeight.roundToInt()
            }
        }

        // 背景 LayoutParams
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
        ).apply {
            dimAmount = config.dimAmount
        }

        // 窗口 LayoutParams
        windowLayoutParams = WindowManager.LayoutParams(
            rootWidth,
            rootHeight,
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
            x = 0
            y = 0
        }
        applyWindowRefreshHint(windowLayoutParams)

        // 横屏时调整窗口位置
        if (!screenIsPortrait()) {
            windowLayoutParams.apply {
                x = genCenterLocation()[0]
                y = genCenterLocation()[1]
            }
        }

        // 初始化缩放（需在动画前计算目标值）
        refreshScale()
        refreshTouchScale()
        val targetScaleX = mScaleX
        val targetScaleY = mScaleY

        // 添加视图（初始不可见）
        binding.freeformRoot.alpha = 0f
        binding.freeformRoot.scaleX = targetScaleX * 0.9f
        binding.freeformRoot.scaleY = targetScaleY * 0.9f
        Instances.windowManager.addView(backgroundView, backgroundLayoutParams)
        Instances.windowManager.addView(binding.root, windowLayoutParams)

        // 入场渐入动画 - 延迟到视图完全 attached 后执行
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

        // 设置 TextureView
        binding.textureView.surfaceTextureListener = this
        binding.textureView.alpha = 0f

        // 初始化控制栏（根据屏幕方向）
        initFloatBar()

        // 设置触摸处理
        setupTouchHandlers()
        setupControlBar()

        // 注册屏幕方向变化监听
        Instances.displayManager.registerDisplayListener(displayListener, mainHandler)

        // 禁用窗口移动动画
        setWindowNoUpdateAnimation()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initWindowAsMini() {
        // directToMini 可能与系统旋转切换并发，先同步当前物理屏幕方向
        screenRotation = Instances.displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: screenRotation

        val wrappedContext = CommonContextWrapper.createAppCompatContext(context)
        binding = ViewFreeformFlymeBinding.inflate(LayoutInflater.from(wrappedContext))
        binding.root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { cancelSpringAnimations() }
        })

        backgroundView = View(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                        backgroundGestureDetector.onTouchEvent(event)
                    }
                }
                true
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
        ).apply {
            dimAmount = 0f
        }

        val location = inheritedMiniLocation
            ?.takeIf { it.size >= 2 }
            ?.let { inherited ->
                // 继承停靠象限，坐标按当前方向归一化，避免沿用旧方向绝对值
                hangUpPosition[0] = inherited[0] <= 0
                hangUpPosition[1] = inherited[1] <= 0
                normalizeMiniLocation(intArrayOf(inherited[0], inherited[1]))
            }
            ?: normalizeMiniLocation(genFloatViewLocation())
        hangUpPosition[0] = location[0] <= 0
        hangUpPosition[1] = location[1] <= 0
        lastFloatViewLocation = location.copyOf()

        windowLayoutParams = WindowManager.LayoutParams(
            hangUpViewWidth,
            hangUpViewHeight,
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
            x = location[0]
            y = location[1]
        }
        applyWindowRefreshHint(windowLayoutParams)

        (binding.cardRoot.layoutParams.cast<ConstraintLayout.LayoutParams>()).apply {
            topMargin = 0
            bottomMargin = 0
            rightMargin = 0
        }
        binding.cardRoot.radius = freeformCornerRadius * (hangUpViewWidth / rootWidth.toFloat())

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

        binding.textureView.surfaceTextureListener = this
        binding.textureView.setOnTouchListener(FloatViewTouchListener())
        setupControlBar()
        Instances.displayManager.registerDisplayListener(displayListener, mainHandler)

        isFloating = true
        mScaleX = hangUpViewWidth / rootWidth.toFloat()
        mScaleY = hangUpViewHeight / rootHeight.toFloat()
        binding.freeformRoot.scaleX = 1f
        binding.freeformRoot.scaleY = 1f
        setWindowEnableUpdateAnimation()

        // 兜底校准：覆盖初始化期间可能遗漏的旋转变化
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
                windowLayoutParams.apply {
                    x = normalizedLocation[0]
                    y = normalizedLocation[1]
                }
                Instances.windowManager.updateViewLayout(binding.root, windowLayoutParams)
            }
        }
    }

    /**
     * 生成居中位置 - 参考 FreeformView.genCenterLocation
     * 支持小窗内应用横屏时的位置计算
     */
    private fun genCenterLocation(): IntArray {
        val center = intArrayOf(0, 0)
        if (!screenIsPortrait()) {
            center[0] = (freeformWidth - rootHeight + screenPaddingX) / 2
            if (!hangUpPosition[0]) {
                center[0] = (freeformWidth - rootHeight + screenPaddingX) / -2
            }
            if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
                center[0] = (freeformWidth - realScreenWidth + screenPaddingX) / 2
                if (!hangUpPosition[0]) {
                    center[0] = (freeformWidth - realScreenWidth + screenPaddingX) / -2
                }
            }
        }
        return center
    }

    /**
     * 初始化控制栏 - 参考 FreeformView.initFloatBar
     */
    private fun initFloatBar() {
        if (screenIsPortrait()) {
            binding.bottomBar.apply {
                root.layoutParams = ConstraintLayout.LayoutParams(
                    ConstraintLayout.LayoutParams.MATCH_PARENT,
                    barHeight.roundToInt(),
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
                    ConstraintLayout.LayoutParams.MATCH_PARENT,
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

    /**
     * 屏幕方向变化处理 - 参考 FreeformView.onScreenOrientationChanged
     */
    private fun onScreenOrientationChanged() {
        if (isDestroyed) return

        initFloatViewSize()
        refreshFreeformSize()
        initFloatBar()

        val location = genFloatViewLocation()
        lastFloatViewLocation = location

        refreshTouchScale()
        refreshActionScale()

        if (isFloating && !isHidden) {
            moveFloatViewLocation(location)
        } else if (isHidden) {
            moveHiddenViewLocation(location)
        } else {
            windowLayoutParams.apply {
                height = rootHeight
                width = rootWidth
            }
            (binding.cardRoot.layoutParams.cast<ConstraintLayout.LayoutParams>()).apply {
                topMargin = freeformShadow.roundToInt()
                bottomMargin = barHeight.roundToInt()
                rightMargin = 0
            }
            windowLayoutParams.apply {
                x = genCenterLocation()[0]
                y = genCenterLocation()[1]
            }
            if (!screenIsPortrait()) {
                (binding.cardRoot.layoutParams.cast<ConstraintLayout.LayoutParams>()).apply {
                    topMargin = 0
                    bottomMargin = 0
                    rightMargin = barHeight.roundToInt()
                }
            }
            resetScale()
            Instances.windowManager.updateViewLayout(binding.root, windowLayoutParams)
        }
    }

    /**
     * 重置缩放
     */
    private fun resetScale() {
        refreshTouchScale()
        refreshScale()
        refreshActionScale()
    }

    /**
     * 调整虚拟显示屏尺寸
     */
    private fun resizeVirtualDisplay() {
        if (!::virtualDisplay.isInitialized || isDestroyed) return
        try {
            virtualDisplay.resize(freeformScreenWidth, freeformScreenHeight, freeformDpi)
        } catch (e: Exception) {
            XLog.e("$TAG: Failed to resize VirtualDisplay", e)
        }
    }

    /**
     * 小窗内应用方向变化处理 - 参考 FreeformView.onFreeFormRotationChanged
     * 当小窗内应用从竖屏切换到横屏（或反之）时调用
     * 添加渐变过渡动画以平滑视觉切换
     */
    private fun onFreeFormRotationChanged() {
        if (isDestroyed || isAnimating) return
        isAnimating = true

        val tempHeight = max(freeformScreenHeight, freeformScreenWidth)
        val tempWidth = min(freeformScreenHeight, freeformScreenWidth)

        // 淡出动画
        ObjectAnimator.ofFloat(binding.freeformRoot, View.ALPHA, 1f, 0f).apply {
            duration = 100
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // 在淡出完成后执行尺寸变更
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
                    resizeVirtualDisplay()
                    applyLayoutCurrentState()

                    // 淡入动画
                    ObjectAnimator.ofFloat(binding.freeformRoot, View.ALPHA, 0f, 1f).apply {
                        duration = 150
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                isAnimating = false
                            }
                        })
                        start()
                    }
                }
            })
            start()
        }
    }

    private fun applyLayoutCurrentState() {
        if (isFloating || isHidden) {
            applyMiniLayout()
            return
        }
        applyNormalLayout()
    }

    private fun applyNormalLayout() {
        refreshScale()
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
            topMargin = 0
            bottomMargin = 0
            rightMargin = 0
        }
        binding.cardRoot.radius = freeformCornerRadius * (hangUpViewWidth / rootWidth.toFloat())

        applyWindowRefreshHint(windowLayoutParams)
        windowLayoutParams.apply {
            width = hangUpViewWidth
            height = hangUpViewHeight
            if (isHidden) {
                val hiddenOnRight = isHiddenOnRight()
                x = if (hiddenOnRight) {
                    miniLocation[0] + (hangUpViewWidth + screenPaddingX)
                } else {
                    miniLocation[0] - (hangUpViewWidth + screenPaddingX)
                }
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
            return (hiddenView.layoutParams.cast<WindowManager.LayoutParams?>())?.x?.let { it > 0 }
                ?: !hangUpPosition[0]
        }
        return !hangUpPosition[0]
    }

    private fun updateHiddenViewLayout(hiddenOnRight: Boolean, targetY: Int) {
        if (!::hiddenView.isInitialized || !hiddenView.isAttachedToWindow) return
        val hiddenLp = hiddenView.layoutParams.cast<WindowManager.LayoutParams?>() ?: return
        hiddenLp.x = (realScreenWidth - floatingButtonWidth) / 2 * if (hiddenOnRight) 1 else -1
        hiddenLp.y = targetY
        Instances.windowManager.updateViewLayout(hiddenView, hiddenLp)
    }

    /**
     * 获取当前 mini/hidden 状态下的悬浮位置
     * floating: 直接返回 windowLayoutParams 坐标
     * hidden: windowLayoutParams.x 已偏离屏幕，根据 hangUpPosition 重建 x
     */
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

    /**
     * 公开方法：设置虚拟显示屏方向
     * 供 FreeformManager 的 TaskStackListener 调用
     */
    fun setVirtualDisplayRotation(rotation: Int) {
        if (isDestroyed) return
        val tempRotation = when (rotation) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE -> VIRTUAL_DISPLAY_ROTATION_LANDSCAPE
            else -> VIRTUAL_DISPLAY_ROTATION_PORTRAIT
        }
        if (tempRotation != virtualDisplayRotation) {
            virtualDisplayRotation = tempRotation
            mainHandler.post {
                onFreeFormRotationChanged()
            }
        }
    }

    /**
     * 禁用更新过渡动画
     */
    private fun setWindowNoUpdateAnimation() {
        val classname = "android.view.WindowManager\$LayoutParams"
        runCatching {
            val layoutParamsClass: Class<*> = Class.forName(classname)
            val privateFlags: Field = layoutParamsClass.getField("privateFlags")
            val noAnim: Field = layoutParamsClass.getField("PRIVATE_FLAG_NO_MOVE_ANIMATION")
            var privateFlagsValue: Int = privateFlags.getInt(windowLayoutParams)
            val noAnimFlag: Int = noAnim.getInt(windowLayoutParams)
            privateFlagsValue = privateFlagsValue or noAnimFlag
            privateFlags.setInt(windowLayoutParams, privateFlagsValue)
        }
    }

    private fun setWindowEnableUpdateAnimation() {
        val classname = "android.view.WindowManager\$LayoutParams"
        runCatching {
            val layoutParamsClass: Class<*> = Class.forName(classname)
            val privateFlags: Field = layoutParamsClass.getField("privateFlags")
            val noAnim: Field = layoutParamsClass.getField("PRIVATE_FLAG_NO_MOVE_ANIMATION")
            var privateFlagsValue: Int = privateFlags.getInt(windowLayoutParams)
            val noAnimFlag: Int = noAnim.getInt(windowLayoutParams)
            privateFlagsValue = privateFlagsValue and noAnimFlag.inv()
            privateFlags.setInt(windowLayoutParams, privateFlagsValue)
        }
    }

    private fun setupTouchHandlers() {
        // TextureView 触摸 - 转发到虚拟显示屏
        binding.textureView.setOnTouchListener { _, event ->
            forwardMotionEvent(event)
            true
        }

        // root 触摸 - 背景点击
        binding.root.setOnTouchListener { _, event ->
            backgroundGestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun setupControlBar() {
        // middleView 触摸处理 - 竖屏拖拽缩放（垂直方向）
        binding.bottomBar.middleView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    handleDownEvent(v, event)
                }
                MotionEvent.ACTION_MOVE -> {
                    handleMoveEvent(v, event)
                }
                MotionEvent.ACTION_UP -> {
                    handleUpEvent(v, event)
                }
                MotionEvent.ACTION_CANCEL -> {
                    touchId = -1
                    isControlBarDragging = false
                }
            }
            true
        }

        // sideView 触摸处理 - 横屏拖拽缩放（水平方向）
        binding.bottomBar.sideView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    handleDownEvent(v, event)
                }
                MotionEvent.ACTION_MOVE -> {
                    handleMoveEvent(v, event)
                }
                MotionEvent.ACTION_UP -> {
                    handleUpEvent(v, event)
                }
                MotionEvent.ACTION_CANCEL -> {
                    touchId = -1
                    isControlBarDragging = false
                }
            }
            true
        }

        // 长按关闭
        binding.bottomBar.middleView.setOnLongClickListener {
            closeToBack()
            true
        }
        binding.bottomBar.sideView.setOnLongClickListener {
            closeToBack()
            true
        }
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
                    if (!isControlBarDragging) {
                        isControlBarDragging = abs(event.rawY - controlDownY) >= controlBarTouchSlop
                    }
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
                    if (!isControlBarDragging) {
                        isControlBarDragging = abs(event.rawX - controlDownX) >= controlBarTouchSlop
                    }
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
                if (isControlBarDragging) {
                    notifyToFloat()
                }
            }
            R.id.sideView -> {
                middleGestureDetector.onTouchEvent(event)
                if (isControlBarDragging) {
                    notifyToFloat()
                }
            }
        }
        touchId = -1
        isControlBarDragging = false
    }

    /**
     * 处理拖拽缩放
     * 根据小窗内应用方向(virtualDisplayRotation)选择乘/除，保持宽高比一致性
     * dy < 0 (向上拖动) -> 高度减少 -> 缩小 (竖屏)
     * dy > 0 (向下拖动) -> 高度增加 -> 放大 (竖屏)
     * dx < 0 (向左拖动) -> 宽度减少 -> 缩小 (横屏)
     * dx > 0 (向右拖动) -> 宽度增加 -> 放大 (横屏)
     */
    private fun handleToFloatScale(dx: Float, dy: Float) {
        if (isFloating || isAnimating) return


        val ratio =
            if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
                1 / WIDTH_HEIGHT_RATIO
            } else {
                WIDTH_HEIGHT_RATIO
            }

        if (dy != 0f) {
            val tempHeight = freeformHeight + dy
            val maxHeight = if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE
                && screenIsPortrait()) rootHeight * 1.15f else rootHeight * 0.9f
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

    /**
     * 判断缩放后的操作
     * 向上拖动缩小 -> mini 悬浮状态
     * 向下拖动放大 -> 移动到主屏幕
     * 完全复刻 FreeformView.notifyToFloat 的时序
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun notifyToFloat() {
        if (isZoomOut && !isAnimating) {
            val scaleX: Float = hangUpViewWidth / rootWidth.toFloat()
            val scaleY: Float = hangUpViewHeight / rootHeight.toFloat()

            // 进入 mini 模式时，继承已有 mini/hidden 窗口的位置后关闭它们
            if (mScaleY <= goFloatScale) {
                FreeformManager.getMiniWindowLocation(displayId)?.let { loc ->
                    lastFloatViewLocation = loc
                    hangUpPosition[0] = loc[0] <= 0
                    hangUpPosition[1] = loc[1] <= 0
                }
                FreeformManager.closeAllMiniWindows()
            }

            when {
                // 缩小到悬浮模式阈值 -> 进入 mini 悬浮状态
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
                                val windowCoordinate = intArrayOf(
                                    windowLayoutParams.x,
                                    windowLayoutParams.y
                                )

                                var location = genFloatViewLocation()
                                if (lastFloatViewLocation[0] != -1) {
                                    location = lastFloatViewLocation
                                }

                                AnimatorSet().apply {
                                    playTogether(
                                        moveViewAnim(windowCoordinate, location),
                                        ValueAnimator.ofFloat(config.dimAmount, 0f).apply {
                                            addUpdateListener {
                                                if (!backgroundView.isAttachedToWindow) return@addUpdateListener
                                                Instances.windowManager.updateViewLayout(
                                                    backgroundView,
                                                    backgroundLayoutParams.apply {
                                                        dimAmount = it.animatedValue.cast()
                                                    }
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
                                            // 延迟动画：在移动动画进行中更新窗口尺寸
                                            AnimatorSet().apply {
                                                duration = 100
                                                startDelay = 200
                                                addListener(
                                                    onEnd = {
                                                        if (!binding.root.isAttachedToWindow) return@addListener
                                                        mScaleX = scaleX
                                                        mScaleY = scaleY
                                                        binding.cardRoot.radius = freeformCornerRadius * (hangUpViewWidth / rootWidth.toFloat())
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
                // 放大到全屏阈值 -> 移动到主屏幕
                mScaleY >= goFullScale -> {
                    isAnimating = true
                    binding.freeformRoot.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    startSunOsSurfaceControlledExpand()
                }
                // 恢复原始大小
                else -> {
                    isAnimating = true
                    // 重新计算正确的尺寸
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

    /**
     * SunOS 风格放大全屏：使用 framework SurfaceControl 直接接管 task leash。
     * 时序：moveRootTaskToDisplay -> 获取 leash -> setMatrix/setPosition 逐帧过渡。
     */
    private fun startSunOsSurfaceControlledExpand() {
        isSurfaceExpandFinishing = false
        val activeTaskId = FreeformManager.getTopTaskIdOnDisplay(displayId).let {
            if (it > 0) it else taskId
        }
        if (activeTaskId <= 0) {
            XLog.w("$TAG: Invalid taskId for full expand, displayId=$displayId")
            finishAfterSurfaceExpand()
            return
        }

        val startRect = resolveExpandStartRect()
        val endRect = Rect(0, 0, realScreenWidth, realScreenHeight)
        val startCornerRadius = binding.cardRoot.radius
        prepareExpandFreezeLayer()

        val moved = FreeformManager.moveTaskFromDisplayToDefault(displayId, activeTaskId)
        if (!moved) {
            if (componentName != null) {
                FreeformManager.startActivityOnDisplay(componentName, userId, Display.DEFAULT_DISPLAY)
            }
            finishAfterSurfaceExpand()
            return
        }

        binding.textureView.setOnTouchListener(null)
        binding.bottomBar.root.alpha = 0f
        if (backgroundView.isAttachedToWindow) {
            try {
                Instances.windowManager.updateViewLayout(
                    backgroundView,
                    backgroundLayoutParams.apply { dimAmount = 0f }
                )
            } catch (e: Throwable) {
                XLog.w("$TAG: Failed to clear dim before full expand", e)
            }
        }

        startSunOsLeashAnimationWhenReady(
            activeTaskId,
            startRect,
            endRect,
            startCornerRadius,
            attempt = 0
        )
    }

    private fun startSunOsLeashAnimationWhenReady(
        activeTaskId: Int,
        startRect: Rect,
        endRect: Rect,
        startCornerRadius: Float,
        attempt: Int
    ) {
        val leash = FreeformManager.getTaskSurfaceForAnimation(activeTaskId)
        if (leash == null || !leash.isValid) {
            if (attempt >= SUNOS_LEASH_RETRY_MAX) {
                XLog.w("$TAG: Failed to get task leash, taskId=$activeTaskId")
                finishAfterSurfaceExpand()
                return
            }
            mainHandler.postDelayed(
                {
                    startSunOsLeashAnimationWhenReady(
                        activeTaskId,
                        startRect,
                        endRect,
                        startCornerRadius,
                        attempt + 1
                    )
                },
                SUNOS_LEASH_RETRY_DELAY
            )
            return
        }

        runSunOsSurfaceAnimator(leash, startRect, endRect, startCornerRadius)
    }

    private fun runSunOsSurfaceAnimator(
        leash: SurfaceControl,
        startRect: Rect,
        endRect: Rect,
        startCornerRadius: Float
    ) {
        applySunOsSurfaceTransform(leash, startRect, endRect, startCornerRadius, 0f)

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SUNOS_EXPAND_DURATION
            interpolator = SUNOS_EXPAND_INTERPOLATOR
            addUpdateListener {
                val fraction = (it.animatedValue.cast<Float>()).coerceIn(0f, 1f)
                applySunOsSurfaceTransform(leash, startRect, endRect, startCornerRadius, fraction)

                val overlayFadeFraction = ((fraction - 0.18f) / 0.82f).coerceIn(0f, 1f)
                val overlayAlpha = 1f - overlayFadeFraction
                binding.freeformRoot.alpha = overlayAlpha
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    applySunOsSurfaceTransform(leash, startRect, endRect, startCornerRadius, 1f)
                    clearSunOsSurfaceOverrides(leash)
                    finishAfterSurfaceExpand()
                }

                override fun onAnimationCancel(animation: Animator) {
                    clearSunOsSurfaceOverrides(leash)
                    finishAfterSurfaceExpand()
                }
            })
            start()
        }
    }

    private fun applySunOsSurfaceTransform(
        leash: SurfaceControl,
        startRect: Rect,
        endRect: Rect,
        startCornerRadius: Float,
        fraction: Float
    ) {
        if (!leash.isValid) return

        val clamped = fraction.coerceIn(0f, 1f)
        val endWidth = endRect.width().coerceAtLeast(1)
        val endHeight = endRect.height().coerceAtLeast(1)
        val startScaleX = startRect.width().coerceAtLeast(1).toFloat() / endWidth.toFloat()
        val startScaleY = startRect.height().coerceAtLeast(1).toFloat() / endHeight.toFloat()

        val currentScaleX = lerp(startScaleX, 1f, clamped)
        val currentScaleY = lerp(startScaleY, 1f, clamped)
        val currentX = lerp(startRect.left.toFloat(), endRect.left.toFloat(), clamped)
        val currentY = lerp(startRect.top.toFloat(), endRect.top.toFloat(), clamped)
        val currentCorner = lerp(startCornerRadius, 0f, clamped).coerceAtLeast(0f)
        val shouldApplyExplicitCrop = shouldApplySunOsExplicitCrop()

        try {
            SurfaceControl.Transaction().apply {
                setPosition(leash, currentX, currentY)
                setAlpha(leash, 1f)
                setLayer(leash, Int.MAX_VALUE)
                callTransactionMethod(this, "setMatrix", leash, currentScaleX, 0f, 0f, currentScaleY)
                if (shouldApplyExplicitCrop) {
                    if (!callTransactionMethod(this, "setWindowCrop", leash, endWidth, endHeight)) {
                        callTransactionMethod(this, "setCrop", leash, Rect(0, 0, endWidth, endHeight))
                    }
                } else {
                    clearTransactionCrop(this, leash)
                }
                callTransactionMethod(this, "setCornerRadius", leash, currentCorner)
                callTransactionMethod(this, "show", leash)
                apply()
            }
        } catch (e: Throwable) {
            XLog.e("$TAG: Failed to apply SunOS surface transform", e)
        }
    }

    private fun resolveExpandStartRect(): Rect {
        val width = (rootWidth * mScaleX).roundToInt().coerceAtLeast(1)
        val height = (rootHeight * mScaleY).roundToInt().coerceAtLeast(1)
        val centerX = (realScreenWidth / 2f) + windowLayoutParams.x
        val centerY = (realScreenHeight / 2f) + windowLayoutParams.y
        val left = (centerX - width / 2f).roundToInt()
        val top = (centerY - height / 2f).roundToInt()
        return Rect(left, top, left + width, top + height)
    }

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
            closeToBack()
        }, SUNOS_FINISH_HOLD_DELAY)
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float {
        return start + (end - start) * fraction
    }

    private fun shouldApplySunOsExplicitCrop(): Boolean {
        return !(screenIsPortrait() && virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE)
    }

    private fun clearSunOsSurfaceOverrides(leash: SurfaceControl) {
        if (!leash.isValid) return
        try {
            SurfaceControl.Transaction().apply {
                setPosition(leash, 0f, 0f)
                callTransactionMethod(this, "setMatrix", leash, 1f, 0f, 0f, 1f)
                callTransactionMethod(this, "setCornerRadius", leash, 0f)
                clearTransactionCrop(this, leash)
                callTransactionMethod(this, "show", leash)
                apply()
            }
        } catch (e: Throwable) {
            XLog.w("$TAG: Failed to clear SunOS surface overrides", e)
        }
    }

    private fun clearTransactionCrop(
        transaction: SurfaceControl.Transaction,
        leash: SurfaceControl
    ) {
        if (callTransactionMethod(transaction, "setWindowCrop", leash, null)) return
        if (callTransactionMethod(transaction, "setCrop", leash, null)) return

        // Fallback for old signatures that only accept width/height crop.
        val fallbackSize = max(realScreenWidth, realScreenHeight).coerceAtLeast(1)
        callTransactionMethod(transaction, "setWindowCrop", leash, fallbackSize, fallbackSize)
    }

    private fun callTransactionMethod(
        transaction: SurfaceControl.Transaction,
        methodName: String,
        vararg args: Any?
    ): Boolean {
        return try {
            XposedHelpers.callMethod(transaction, methodName, *args)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun prepareExpandFreezeLayer() {
        clearExpandFreezeLayer()
        if (!binding.textureView.isAvailable) return
        val bitmap = try {
            binding.textureView.bitmap
        } catch (e: Throwable) {
            XLog.w("$TAG: Failed to snapshot texture for expand freeze", e)
            null
        } ?: return

        if (bitmap.width <= 0 || bitmap.height <= 0 || binding.cardRoot.width <= 0 || binding.cardRoot.height <= 0) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return
        }

        val drawable = bitmap.toDrawable(context.resources).apply {
            setBounds(0, 0, binding.cardRoot.width, binding.cardRoot.height)
        }
        binding.cardRoot.overlay.add(drawable)
        binding.textureView.alpha = 0f
        expandFreezeBitmap = bitmap
        expandFreezeDrawable = drawable
    }

    private fun clearExpandFreezeLayer() {
        expandFreezeDrawable?.let { drawable ->
            runCatching { binding.cardRoot.overlay.remove(drawable) }
        }
        expandFreezeDrawable = null
        expandFreezeBitmap?.let { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        expandFreezeBitmap = null
        if (::binding.isInitialized && binding.textureView.isAttachedToWindow) {
            binding.textureView.alpha = 1f
        }
    }

    /**
     * 悬浮模式触摸监听 - 完整移植自 FreeformView.kt
     * 支持：拖动、点击恢复、拖到侧边变侧边栏、拖到顶部关闭
     */
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
            // 侧边栏视图的触摸处理
            if (isHidden) {
                handleHiddenTouch(event)
                return true
            }

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
                        if (binding.root.isAttachedToWindow) {
                            Instances.windowManager.updateViewLayout(binding.root, windowLayoutParams)
                        }

                        if (tryEnterHiddenFromMiniDrag(event)) {
                            return true
                        }

                        moveStartX = event.rawX
                        moveStartY = event.rawY
                    }
                }
                MotionEvent.ACTION_UP -> {
                    cleanupDeferredHiddenViewIfNeeded()
                    trackMovement(event)
                    velocityTracker?.computeCurrentVelocity(1000,
                        ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat())
                    val xVelocity = velocityTracker?.xVelocity ?: 0f
                    val yVelocity = velocityTracker?.yVelocity ?: 0f
                    velocityTracker?.recycle()
                    velocityTracker = null

                    if (isMoved) {
                        val nowY = event.rawY

                        val windowCoordinate = intArrayOf(
                            windowLayoutParams.x,
                            windowLayoutParams.y
                        )

                        // 拖到顶部关闭 - 向上滑出屏幕
                        if (nowY <= realScreenHeight * 0.1f) {
                            slideUpAndClose()
                            isMoved = false
                            return true
                        }

                        // 更新挂起位置 - 速度预测
                        if (abs(xVelocity) > VELOCITY_THRESHOLD) {
                            hangUpPosition[0] = xVelocity < 0
                        } else {
                            hangUpPosition[0] = windowCoordinate[0] <= 0
                        }
                        hangUpPosition[1] = windowCoordinate[1] <= 0

                        val location = genFloatViewLocation()

                        // Y 位置 - 速度预测
                        if (abs(yVelocity) > VELOCITY_THRESHOLD) {
                            location[1] = if (yVelocity < 0) {
                                (hangUpViewHeight - realScreenHeight + screenPaddingY) / 2
                            } else {
                                (realScreenHeight - hangUpViewHeight - screenPaddingY) / 2
                            }
                        } else {
                            location[1] = windowLayoutParams.y
                            if (nowY < realScreenHeight * 0.1f) {
                                location[1] = (hangUpViewHeight - realScreenHeight + screenPaddingY) / 2
                            }
                            if (nowY > realScreenHeight - realScreenHeight * 0.1f) {
                                location[1] = (realScreenHeight - hangUpViewHeight - screenPaddingY) / 2
                            }
                        }

                        var position = 0
                        // 拖到左边 -> 变成侧边栏
                        if (windowCoordinate[0] <= (realScreenWidth - screenPaddingX / 2) / -2) {
                            location[0] -= (hangUpViewWidth + screenPaddingX)
                            position = -1
                        }
                        // 拖到右边 -> 变成侧边栏
                        else if (windowCoordinate[0] >= (realScreenWidth - screenPaddingX / 2) / 2) {
                            location[0] += (hangUpViewWidth + screenPaddingX)
                            position = 1
                        }

                        if (position != 0) {
                            // 进入侧边栏 - 保留原始动画
                            AnimatorSet().apply {
                                playTogether(moveViewAnim(windowCoordinate, location))
                                addListener(
                                    onEnd = {
                                        isHidden = true
                                        ensureHiddenViewAttached(position, location[1])
                                        playHiddenRevealAnimation(position)
                                        isMoved = false
                                    }
                                )
                                duration = 300
                                interpolator = OvershootInterpolator(0.4f)
                                start()
                            }
                        } else {
                            // 正常吸边 - 弹簧动画
                            val targetLocation = location.copyOf()

                            springAnim = SpringAnimator(
                                onUpdate = { x, y ->
                                    windowLayoutParams.x = x.roundToInt()
                                    windowLayoutParams.y = y.roundToInt()
                                    if (binding.root.isAttachedToWindow) {
                                        Instances.windowManager.updateViewLayout(binding.root, windowLayoutParams)
                                    }
                                },
                                onEnd = {
                                    lastFloatViewLocation = targetLocation
                                    isMoved = false
                                }
                            ).also {
                                it.start(
                                    windowCoordinate[0].toFloat(), location[0].toFloat(), xVelocity,
                                    windowCoordinate[1].toFloat(), location[1].toFloat(), yVelocity
                                )
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

        private fun miniEdgeX(onLeft: Boolean): Int {
            return if (onLeft) {
                (realScreenWidth - hangUpViewWidth - screenPaddingX) / -2
            } else {
                (realScreenWidth - hangUpViewWidth - screenPaddingX) / 2
            }
        }

        private fun miniHiddenCollapseDistance(): Int {
            return hangUpViewWidth + screenPaddingX
        }

        private fun hiddenToMiniTriggerDistancePx(): Int {
            val density = context.resources.displayMetrics.density
            return (density * 10f).roundToInt().coerceAtLeast(8)
        }

        private fun miniHiddenCollapsedX(onLeft: Boolean): Int {
            val edgeX = miniEdgeX(onLeft)
            val direction = if (onLeft) -1 else 1
            return edgeX + miniHiddenCollapseDistance() * direction
        }

        private fun clampMiniY(targetY: Int): Int {
            val topY = (hangUpViewHeight - realScreenHeight + screenPaddingY) / 2
            val bottomY = (realScreenHeight - hangUpViewHeight - screenPaddingY) / 2
            return targetY.coerceIn(min(topY, bottomY), max(topY, bottomY))
        }

        private fun resolveHiddenInflateContext(): Context {
            return try {
                val moduleContext = context.createPackageContext(
                    BuildConfig.APPLICATION_ID,
                    Context.CONTEXT_IGNORE_SECURITY or Context.CONTEXT_INCLUDE_CODE
                )
                CommonContextWrapper.createAppCompatContext(moduleContext)
            } catch (e: Exception) {
                XLog.e("$TAG: Failed to create module context for inflation", e)
                context
            }
        }

        private fun ensureHiddenViewAttached(position: Int, targetY: Int) {
            val hiddenX = (realScreenWidth - floatingButtonWidth) / 2 * position
            val drawableRes = if (position == 1) {
                R.drawable.floating_button_bg_right
            } else {
                R.drawable.floating_button_bg
            }

            if (!::hiddenView.isInitialized || !hiddenView.isAttachedToWindow) {
                val inflateContext = resolveHiddenInflateContext()
                hiddenView = LayoutInflater.from(inflateContext)
                    .inflate(R.layout.view_floating_button, null, false)
                hiddenView.alpha = 0f
                hiddenView.setOnTouchListener(this@FloatViewTouchListener)
                hiddenView.findViewById<View>(R.id.backgroundView).background =
                    ContextCompat.getDrawable(inflateContext, drawableRes)
                Instances.windowManager.addView(hiddenView, WindowManager.LayoutParams().apply {
                    x = hiddenX
                    y = targetY
                    width = floatingButtonWidth
                    height = floatingButtonHeight
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
            hiddenView.setOnTouchListener(this@FloatViewTouchListener)
            hiddenView.findViewById<View>(R.id.backgroundView).background =
                ContextCompat.getDrawable(hiddenView.context, drawableRes)

            val hiddenLp = hiddenView.layoutParams.cast<WindowManager.LayoutParams?>() ?: return
            hiddenLp.x = hiddenX
            hiddenLp.y = targetY
            Instances.windowManager.updateViewLayout(hiddenView, hiddenLp)
        }

        private fun cleanupDeferredHiddenViewIfNeeded() {
            if (!hiddenViewDeferredRemoval) return
            hiddenViewDeferredRemoval = false
            if (!::hiddenView.isInitialized) return

            if (!isHidden && hiddenView.isAttachedToWindow) {
                hiddenView.setOnTouchListener(null)
                Instances.windowManager.removeView(hiddenView)
            }
        }

        private fun playHiddenRevealAnimation(position: Int) {
            if (!::hiddenView.isInitialized) return

            fun startRevealNow() {
                if (!::hiddenView.isInitialized || !hiddenView.isAttachedToWindow) return

                val fromInsideOffset = floatingButtonWidth.toFloat()
                val startTranslationX = if (position > 0) fromInsideOffset else -fromInsideOffset

                hiddenView.animate().cancel()
                hiddenView.alpha = 0f
                hiddenView.translationX = startTranslationX
                hiddenView.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setDuration(180)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }

            if (hiddenView.isAttachedToWindow) {
                startRevealNow()
                return
            }

            // addView 后首次可能尚未 attached，延后到下一帧启动 reveal，避免 alpha 卡在 0。
            hiddenView.post {
                if (!::hiddenView.isInitialized) return@post
                if (!isHidden) return@post
                if (hiddenView.isAttachedToWindow) {
                    startRevealNow()
                } else {
                    hiddenView.alpha = 1f
                    hiddenView.translationX = 0f
                }
            }
        }

        private fun snapHiddenBackToCollapsed(onLeft: Boolean) {
            val collapsedX = miniHiddenCollapsedX(onLeft)
            val fromX = windowLayoutParams.x
            if (fromX == collapsedX) return

            ValueAnimator.ofInt(fromX, collapsedX).apply {
                duration = 120
                interpolator = DecelerateInterpolator()
                addUpdateListener { animator ->
                    windowLayoutParams.x = animator.animatedValue.cast()
                    if (binding.root.isAttachedToWindow) {
                        Instances.windowManager.updateViewLayout(binding.root, windowLayoutParams)
                    }
                }
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
            if (binding.root.isAttachedToWindow) {
                Instances.windowManager.updateViewLayout(binding.root, windowLayoutParams)
            }

            isHidden = true
        }

        private fun switchHiddenToMiniWithRevealAnimation(hiddenOnRight: Boolean, targetY: Int) {
            val clampedY = clampMiniY(targetY)
            val onLeft = !hiddenOnRight
            val edgeX = miniEdgeX(onLeft)
            val revealX = miniHiddenCollapsedX(onLeft)

            hangUpPosition[0] = onLeft
            hangUpPosition[1] = clampedY <= 0
            lastFloatViewLocation = intArrayOf(edgeX, clampedY)

            windowLayoutParams.x = revealX
            windowLayoutParams.y = clampedY
            if (binding.root.isAttachedToWindow) {
                Instances.windowManager.updateViewLayout(binding.root, windowLayoutParams)
            }

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
            binding.freeformRoot.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(120)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        private fun tryEnterHiddenFromMiniDrag(event: MotionEvent): Boolean {
            val onLeft = windowLayoutParams.x <= 0
            val collapsedX = miniHiddenCollapsedX(onLeft)
            val reachedCollapsedEdge = if (onLeft) {
                windowLayoutParams.x <= collapsedX
            } else {
                windowLayoutParams.x >= collapsedX
            }
            if (!reachedCollapsedEdge) return false

            switchMiniToHiddenWithoutAnimation(if (onLeft) -1 else 1, windowLayoutParams.y)
            hiddenMoved = true
            moveStartX = event.rawX
            moveStartY = event.rawY
            isMoved = false
            return true
        }

        private fun handleHiddenTouch(event: MotionEvent) {
            if (!::hiddenView.isInitialized || !hiddenView.isAttachedToWindow) {
                isHidden = false
                hiddenViewDeferredRemoval = false
                return
            }
            val hiddenLp = hiddenView.layoutParams.cast<WindowManager.LayoutParams?>() ?: return

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelSpringAnimations()
                    moveStartX = event.rawX
                    moveStartY = event.rawY
                    hiddenMoved = false
                }

                MotionEvent.ACTION_MOVE -> {
                    movedX = event.rawX - moveStartX
                    movedY = event.rawY - moveStartY

                    if (abs(movedX) > minLong || abs(movedY) > minLong) {
                        hiddenMoved = true

                        val hiddenOnRight = hiddenLp.x > 0
                        val onLeft = !hiddenOnRight
                        val edgeX = miniEdgeX(onLeft)
                        val collapsedX = miniHiddenCollapsedX(onLeft)
                        val nextCollapsedX = windowLayoutParams.x + movedX.toInt()
                        val targetY = clampMiniY(windowLayoutParams.y + movedY.toInt())

                        val inwardDistance = if (hiddenOnRight) {
                            collapsedX - nextCollapsedX
                        } else {
                            nextCollapsedX - collapsedX
                        }
                        val draggingTowardMini =
                            inwardDistance >= hiddenToMiniTriggerDistancePx()

                        if (draggingTowardMini) {
                            switchHiddenToMiniWithRevealAnimation(hiddenOnRight, targetY)

                            isMoved = true
                            hiddenMoved = false
                            moveStartX = event.rawX
                            moveStartY = event.rawY
                            return
                        }

                        val targetX = nextCollapsedX
                            .coerceIn(min(edgeX, collapsedX), max(edgeX, collapsedX))
                        windowLayoutParams.x = targetX
                        windowLayoutParams.y = targetY
                        hiddenLp.y = targetY
                        Instances.windowManager.updateViewLayout(hiddenView, hiddenLp)
                        if (binding.root.isAttachedToWindow) {
                            Instances.windowManager.updateViewLayout(binding.root, windowLayoutParams)
                        }

                        hangUpPosition[0] = hiddenLp.x <= 0
                        hangUpPosition[1] = targetY <= 0
                        lastFloatViewLocation = intArrayOf(
                            miniEdgeX(hangUpPosition[0]),
                            targetY
                        )

                        moveStartX = event.rawX
                        moveStartY = event.rawY
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (!hiddenMoved) {
                        hiddenViewToFloatView()
                        hiddenMoved = false
                        return
                    }

                    val hiddenOnRight = hiddenLp.x > 0
                    val onLeft = !hiddenOnRight
                    snapHiddenBackToCollapsed(onLeft)
                    hiddenMoved = false
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (isHidden) {
                        val onLeft = (hiddenLp.x <= 0)
                        snapHiddenBackToCollapsed(onLeft)
                    }
                    hiddenMoved = false
                }
            }
        }
    }

    private val floatViewTouchListener = FloatViewTouchListener()

    // 悬浮模式点击手势 - 点击恢复到正常模式
    private val hangUpGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            floatViewToNormalView()
            return true
        }
    })

    /**
     * 生成悬浮视图位置
     */
    private fun genFloatViewLocation(): IntArray {
        return intArrayOf(
            if (hangUpPosition[0]) (realScreenWidth - hangUpViewWidth - screenPaddingX) / -2
            else (realScreenWidth - hangUpViewWidth - screenPaddingX) / 2,
            if (hangUpPosition[1]) (hangUpViewHeight - realScreenHeight + screenPaddingY) / 2
            else (realScreenHeight - hangUpViewHeight - screenPaddingY) / 2
        )
    }

    /**
     * 约束 mini 坐标到当前屏幕可用范围，避免旧方向坐标导致越界或偏移。
     */
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

    /**
     * 移动视图动画
     */
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

    private fun cardViewMarginAnim(
        topStartMargin: Int, bottomStartMargin: Int, rightStartMargin: Int,
        topEndMargin: Int, bottomEndMargin: Int, rightEndMargin: Int
    ): Animator {
        return AnimatorSet().apply {
            playTogether(
                ValueAnimator.ofInt(topStartMargin, topEndMargin).apply {
                    addUpdateListener {
                        binding.cardRoot.layoutParams = (binding.cardRoot.layoutParams.cast<ConstraintLayout.LayoutParams>()).apply {
                            topMargin = it.animatedValue.cast()
                        }
                    }
                },
                ValueAnimator.ofInt(bottomStartMargin, bottomEndMargin).apply {
                    addUpdateListener {
                        binding.cardRoot.layoutParams = (binding.cardRoot.layoutParams.cast<ConstraintLayout.LayoutParams>()).apply {
                            bottomMargin = it.animatedValue.cast()
                        }
                    }
                },
                ValueAnimator.ofInt(rightStartMargin, rightEndMargin).apply {
                    addUpdateListener {
                        binding.cardRoot.layoutParams = (binding.cardRoot.layoutParams.cast<ConstraintLayout.LayoutParams>()).apply {
                            rightMargin = it.animatedValue.cast()
                        }
                    }
                },
            )
        }
    }

    /**
     * 从侧边栏恢复到悬浮模式
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun hiddenViewToFloatView() {
        val windowCoordinate = intArrayOf(
            windowLayoutParams.x,
            windowLayoutParams.y
        )

        hangUpPosition[0] = windowCoordinate[0] <= 0
        hangUpPosition[1] = windowCoordinate[1] <= 0

        val location = intArrayOf(
            if (hangUpPosition[0]) (realScreenWidth - hangUpViewWidth - screenPaddingX) / -2
            else (realScreenWidth - hangUpViewWidth - screenPaddingX) / 2,
            -1
        )

        AnimatorSet().apply {
            playTogether(
                moveViewAnim(windowCoordinate, location),
                ObjectAnimator.ofFloat(binding.freeformRoot, View.ALPHA, binding.freeformRoot.alpha, 1f)
            )
            addListener(
                onStart = {
                    hiddenView.setOnTouchListener(null)
                    if (hiddenView.isAttachedToWindow) {
                        Instances.windowManager.removeView(hiddenView)
                    }
                    isHidden = false
                },
                onEnd = {
                    if (!isHidden) {
                        lastFloatViewLocation = intArrayOf(location[0], windowCoordinate[1])
                    }
                }
            )
            duration = 300
            interpolator = OvershootInterpolator(0.4f)
            start()
        }
    }

    /**
     * 移动悬浮视图位置
     */
    private fun moveFloatViewLocation(location: IntArray) {
        val windowCoordinate = intArrayOf(
            windowLayoutParams.x,
            windowLayoutParams.y
        )

        AnimatorSet().apply {
            playTogether(moveViewAnim(windowCoordinate, location))
            addListener(
                onStart = {
                    if (!binding.root.isAttachedToWindow) return@addListener
                    binding.freeformRoot.scaleY = 1f
                    binding.freeformRoot.scaleX = 1f
                    Instances.windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                        height = hangUpViewHeight
                        width = hangUpViewWidth
                    })
                }
            )
            duration = 350
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    /**
     * 移动侧边栏视图位置
     */
    private fun moveHiddenViewLocation(location: IntArray) {
        if (!::hiddenView.isInitialized) return

        val layoutParams = hiddenView.layoutParams.cast<WindowManager.LayoutParams>()
        val windowCoordinate = intArrayOf(layoutParams.x, layoutParams.y)

        val position = if (layoutParams.x > 0) {
            location[0] += (hangUpViewWidth + screenPaddingX)
            1
        } else {
            location[0] -= (hangUpViewWidth + screenPaddingX)
            -1
        }

        AnimatorSet().apply {
            playTogether(
                ValueAnimator.ofInt(windowCoordinate[0], (realScreenWidth - floatingButtonWidth) / 2 * position).apply {
                    addUpdateListener {
                        if (!hiddenView.isAttachedToWindow) return@addUpdateListener
                        Instances.windowManager.updateViewLayout(
                            hiddenView,
                            layoutParams.apply {
                                x = it.animatedValue.cast()
                            }
                        )
                    }
                },
                ValueAnimator.ofInt(windowCoordinate[1], location[1]).apply {
                    addUpdateListener {
                        if (!hiddenView.isAttachedToWindow) return@addUpdateListener
                        Instances.windowManager.updateViewLayout(
                            hiddenView,
                            layoutParams.apply {
                                y = it.animatedValue.cast()
                            }
                        )
                    }
                },
                moveViewAnim(
                    intArrayOf(windowLayoutParams.x, windowLayoutParams.y),
                    intArrayOf(location[0], location[1])
                )
            )
            duration = 350
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    /**
     * 从悬浮模式恢复到正常模式 - 完全复刻 FreeformView.floatViewToMiniView
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun floatViewToNormalView() {
        floatViewToNormalViewInternal()
    }

    /**
     * 公开方法：从 mini/hidden 状态恢复到正常模式
     * 供 FreeformManager 调用
     */
    fun restoreToNormalView() {
        if (isDestroyed) return
        if (isHidden) {
            hiddenViewToNormalView()
        } else if (isFloating) {
            floatViewToNormalViewInternal()
        }
    }

    /**
     * 从侧边栏直接恢复到正常模式
     * 避免经过 floating 中间状态导致动画衔接异常
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun hiddenViewToNormalView() {
        if (::hiddenView.isInitialized) {
            hiddenView.setOnTouchListener(null)
            if (hiddenView.isAttachedToWindow) {
                Instances.windowManager.removeView(hiddenView)
            }
        }
        isHidden = false
        binding.freeformRoot.alpha = 1f
        floatViewToNormalViewInternal()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun floatViewToNormalViewInternal() {
        cancelSpringAnimations()
        if (isAnimating || isDestroyed || isClosedToBack || !binding.root.isAttachedToWindow) return

        // 恢复普通模式时，关闭其他普通窗口（退到后台）
        FreeformManager.closeAllNormalWindows()

        binding.textureView.setOnTouchListener { _, motionEvent ->
            forwardMotionEvent(motionEvent)
            true
        }
        binding.root.setOnTouchListener { _, event ->
            backgroundGestureDetector.onTouchEvent(event)
            true
        }

        val windowCoordinate = intArrayOf(
            windowLayoutParams.x,
            windowLayoutParams.y
        )

        // 重新计算正常尺寸
        refreshFreeformSize()

        val restoreScaleX = freeformWidth / rootWidth.toFloat()
        val restoreScaleY = freeformHeight / rootHeight.toFloat()
        val center = genCenterLocation()

        // 快速减速动画组 - 移动窗口 + 恢复背景蒙版
        val fastDecelerateAnims = AnimatorSet().apply {
            playTogether(
                moveViewAnim(windowCoordinate, center),
                ValueAnimator.ofFloat(0f, config.dimAmount).apply {
                    addUpdateListener {
                        if (!backgroundView.isAttachedToWindow) return@addUpdateListener
                        Instances.windowManager.updateViewLayout(backgroundView, backgroundLayoutParams.apply {
                            dimAmount = it.animatedValue.cast()
                        })
                    }
                }
            )
            duration = 250
            interpolator = DecelerateInterpolator()
        }

        // 根据屏幕方向设置 margin
        var topMargin = 0f
        var bottomMargin = 0f
        var rightMargin = 0f
        if (screenIsPortrait()) {
            topMargin = freeformShadow
            bottomMargin = barHeight
        } else {
            rightMargin = barHeight
        }

        // 过冲动画组 - 恢复缩放 + 恢复 bottomBar + 恢复 margin
        val overshootAnims = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(binding.bottomBar.root, View.ALPHA, 1f),
                ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_X, mScaleX, restoreScaleX),
                ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_Y, mScaleY, restoreScaleY),
                cardViewMarginAnim(
                    (binding.cardRoot.layoutParams.cast<ConstraintLayout.LayoutParams>()).topMargin,
                    (binding.cardRoot.layoutParams.cast<ConstraintLayout.LayoutParams>()).bottomMargin,
                    (binding.cardRoot.layoutParams.cast<ConstraintLayout.LayoutParams>()).rightMargin,
                    topMargin.roundToInt(),
                    bottomMargin.roundToInt(),
                    rightMargin.roundToInt()
                )
            )
            duration = 300
            interpolator = DecelerateInterpolator()
            startDelay = 100
        }

        // 根动画
        AnimatorSet().apply {
            playTogether(fastDecelerateAnims, overshootAnims)
            addListener(
                onStart = {
                    isAnimating = true
                    binding.freeformRoot.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    backgroundView.visibility = View.VISIBLE
                    binding.freeformRoot.scaleX = mScaleX
                    binding.freeformRoot.scaleY = mScaleY
                    Instances.windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                        height = rootHeight
                        width = rootWidth
                    })
                    binding.cardRoot.radius = freeformCornerRadius
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
            if (binding.root.isAttachedToWindow) {
                reorderWindowInWms(binding.root)
            }
            if (isHidden && ::hiddenView.isInitialized && hiddenView.isAttachedToWindow) {
                reorderWindowInWms(hiddenView)
            }
            FreeformManager.moveToTop(displayId)
            if (!isFloating && !isHidden) {
                FreeformManager.bringMiniWindowsToFront()
            }
        } catch (e: Exception) {
            XLog.e("$TAG: Failed to move to top", e)
        }
    }

    /**
     * 通过 WMS 内部 API 将窗口的 WindowToken 移到 DisplayArea 顶部。
     * 在 SurfaceFlinger 层面原子操作，用户完全不可见。
     */
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
                XposedHelpers.callMethod(
                    XposedHelpers.getObjectField(wms, "mWindowPlacerLocked"),
                    "requestTraversal"
                )
            }
        } catch (e: Exception) {
            XLog.e("$TAG: Failed to reorder window in WMS", e)
        }
    }

    /**
     * 模拟 moveTaskToBack：移除视图，但保留 VirtualDisplay 和任务状态
     */
    fun closeToBack() {
        if (isDestroyed || isClosedToBack) return
        isClosedToBack = true
        cancelSpringAnimations()

        try {
            // 取消注册屏幕方向监听
            Instances.displayManager.unregisterDisplayListener(displayListener)

            // 移除侧边栏视图
            if (isHidden && ::hiddenView.isInitialized && hiddenView.isAttachedToWindow) {
                Instances.windowManager.removeView(hiddenView)
            }

            // 移除主视图和背景（但不释放 virtualDisplay！）
            if (binding.root.isAttachedToWindow) {
                Instances.windowManager.removeView(binding.root)
            }
            if (backgroundView.isAttachedToWindow) {
                Instances.windowManager.removeView(backgroundView)
            }
        } catch (e: Exception) {
            XLog.e("$TAG: Error moving window to back", e)
        }
    }

    /**
     * 恢复窗口：重新添加到 WindowManager
     */
    fun restoreFromBack() {
        if (!isClosedToBack || isDestroyed) return

        // 刷新屏幕方向（closeToBack 注销了 displayListener，期间方向变化不会被感知）
        screenRotation = Instances.displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
        if (!screenIsPortrait()) {
            hangUpPosition[0] = true
        }
        lastFloatViewLocation = intArrayOf(-1, -1)

        try {
            // 重新计算正常尺寸
            initFloatViewSize()
            refreshFreeformSize()
            refreshScale()
            refreshTouchScale()
            refreshActionScale()

            // 重置 windowLayoutParams 为正常尺寸
            windowLayoutParams.apply {
                width = rootWidth
                height = rootHeight
                x = genCenterLocation()[0]
                y = genCenterLocation()[1]
            }
            applyWindowRefreshHint(windowLayoutParams)

            // 重置 cardRoot margin
            (binding.cardRoot.layoutParams.cast<ConstraintLayout.LayoutParams>()).apply {
                if (screenIsPortrait()) {
                    topMargin = freeformShadow.roundToInt()
                    bottomMargin = barHeight.roundToInt()
                    rightMargin = 0
                } else {
                    topMargin = 0
                    bottomMargin = 0
                    rightMargin = barHeight.roundToInt()
                }
            }
            binding.cardRoot.radius = freeformCornerRadius

            // 重新初始化控制栏布局
            initFloatBar()

            // 重置动画状态，准备播放入场动画
            initFinish = false
            updateFrameCount = 0
            binding.lottieView.alpha = 1f
            binding.lottieView.playAnimation()
            binding.textureView.alpha = 0f

            // 重新添加视图（初始不可见）
            binding.freeformRoot.alpha = 0f
            binding.freeformRoot.scaleX = mScaleX * 0.9f
            binding.freeformRoot.scaleY = mScaleY * 0.9f
            Instances.windowManager.addView(backgroundView, backgroundLayoutParams.apply {
                    dimAmount = config.dimAmount
            })
            Instances.windowManager.addView(binding.root, windowLayoutParams)

            // 入场渐入动画 - 延迟到视图完全 attached 后执行
            val restoreTargetScaleX = mScaleX
            val restoreTargetScaleY = mScaleY
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

            // 重新注册监听
            Instances.displayManager.registerDisplayListener(displayListener, mainHandler)

            // 重新关联 Surface（如果 TextureView 已经有 SurfaceTexture）
            binding.textureView.surfaceTexture?.let { surfaceTexture ->
                if (::virtualDisplay.isInitialized) {
                    surfaceTexture.setDefaultBufferSize(freeformScreenWidth, freeformScreenHeight)
                    val virtualSurface = Surface(surfaceTexture)
                    applySurfaceRefreshHint(
                        virtualSurface,
                        resolveHighRefreshHint()?.refreshRate ?: 0f
                    )
                    virtualDisplay.surface = virtualSurface
                }
            }

            isClosedToBack = false
            isFloating = false
            isHidden = false

            // 重置视图状态（alpha/scale 由入场动画控制）
            binding.bottomBar.root.alpha = 1f
            backgroundView.visibility = View.VISIBLE

            // 设置触摸处理
            binding.textureView.setOnTouchListener { _, event ->
                forwardMotionEvent(event)
                true
            }

            // 重新设置控制栏事件
            setupControlBar()

            // 禁用窗口移动动画
            setWindowNoUpdateAnimation()

            // 检查 VirtualDisplay 上是否还有任务，如果没有则重新启动 activity
            if (!FreeformManager.hasTaskOnDisplay(displayId)) {
                if (componentName != null && userId >= 0) {
                    XLog.d("$TAG: No task on display, restarting activity")
                    FreeformManager.startActivityOnDisplay(componentName, userId, displayId)
                }
            }
        } catch (e: Exception) {
            XLog.e("$TAG: Error restoring window", e)
        }
    }

    /**
     * mini 窗口向上滑出屏幕后关闭
     */
    private fun slideUpAndClose() {
        if (isDestroyed || isClosedToBack || isAnimating) return
        isAnimating = true

        val targetY = -(realScreenHeight + hangUpViewHeight) / 2
        ValueAnimator.ofInt(windowLayoutParams.y, targetY).apply {
            addUpdateListener {
                if (!binding.root.isAttachedToWindow) return@addUpdateListener
                windowLayoutParams.y = it.animatedValue.cast()
                Instances.windowManager.updateViewLayout(binding.root, windowLayoutParams)
            }
            duration = 150
            interpolator = AccelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    isAnimating = false
                    closeToBack()
                }
            })
            start()
        }
    }

    /**
     * 带动画的关闭到后台
     */
    private fun closeToBackWithAnimation() {
        if (isDestroyed || isClosedToBack || isAnimating) return

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

    /**
     * 真正的销毁：在任务被手动移除或 App 退出时调用
     */
    fun realDestroy() {
        if (isDestroyed) return

        // 确保视图已移除
        if (!isClosedToBack) {
            closeToBack()
        }

        isDestroyed = true

        try {
            FreeformManager.removeWindow(displayId)
            if (::virtualDisplay.isInitialized) {
                virtualDisplay.release()
            }
        } catch (e: Exception) {
            XLog.e("$TAG: Error real destroying window", e)
        }
    }

    // TextureView.SurfaceTextureListener

    @SuppressLint("WrongConstant")
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        try {
            val highRefreshHint = resolveHighRefreshHint()
            if (highRefreshHint != null) {
                XLog.d("$TAG: High refresh hint selected, rate=${highRefreshHint.refreshRate}, modeId=${highRefreshHint.modeId}")
            } else {
                XLog.d("$TAG: High refresh hint unavailable, fallback to system default refresh")
            }
            val createdDisplay = createVirtualDisplay(
                "ZFlow@${System.currentTimeMillis()}",
                highRefreshHint?.refreshRate ?: 0f
            ) ?: run {
                XLog.e("$TAG: DisplayManager returned null VirtualDisplay")
                return
            }
            virtualDisplay = createdDisplay
            displayId = virtualDisplay.display.displayId

            // Configure IME policy
            try {
                val wmHidden = Refine.unsafeCast<WindowManagerHidden>(Instances.windowManager)
                wmHidden.setDisplayImePolicy(
                    displayId,
                    WindowManagerHidden.DISPLAY_IME_POLICY_FALLBACK_DISPLAY
                )
            } catch (e: Exception) {
                XLog.w("$TAG: Failed to set IME policy", e)
            }

            // Attach surface
            surface.setDefaultBufferSize(freeformScreenWidth, freeformScreenHeight)
            val virtualSurface = Surface(surface)
            applySurfaceRefreshHint(
                virtualSurface,
                highRefreshHint?.refreshRate ?: 0f
            )
            virtualDisplay.surface = virtualSurface

            if (directToMini) {
                binding.freeformRoot.scaleX = 1f
                binding.freeformRoot.scaleY = 1f
            }
            binding.textureView.postDelayed(initTimeoutRunnable, 1000)

            // Register with manager
            FreeformManager.addWindow(this)

            // Prefer migrating existing task to preserve in-app runtime state.
            if (taskId > 0) {
                val moveTaskAction: () -> Unit = moveTask@{
                    if (isDestroyed || isClosedToBack || !::virtualDisplay.isInitialized || displayId < 0) {
                        return@moveTask
                    }
                    XLog.d("$TAG: Move existing task to freeform, taskId=$taskId, displayId=$displayId")
                    FreeformManager.moveTaskToDisplay(taskId, displayId)
                }
                if (directToMini) {
                    XLog.d("$TAG: Delay mini task move for transition settle, taskId=$taskId, displayId=$displayId")
                    mainHandler.postDelayed(
                        moveTaskAction,
                        MINI_TASK_MOVE_DELAY_MS
                    )
                } else {
                    moveTaskAction()
                }
            } else if (componentName != null && userId >= 0) {
                XLog.d("$TAG: Start activity on freeform display, component=$componentName, displayId=$displayId")
                FreeformManager.startActivityOnDisplay(componentName, userId, displayId)
            }
        } catch (e: Exception) {
            XLog.e("$TAG: Failed to create VirtualDisplay", e)
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        if (isDestroyed || !::virtualDisplay.isInitialized) return
        try {
            virtualDisplay.resize(freeformScreenWidth, freeformScreenHeight, freeformDpi)
            surface.setDefaultBufferSize(freeformScreenWidth, freeformScreenHeight)
        } catch (e: Exception) {
            XLog.e("$TAG: Failed to resize VirtualDisplay", e)
        }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        binding.textureView.removeCallbacks(initTimeoutRunnable)
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        if (!initFinish) {
            ++updateFrameCount
            if (updateFrameCount > 2) {
                binding.lottieView.cancelAnimation()
                binding.lottieView.animate().alpha(0f).setDuration(200).start()
                binding.textureView.animate().alpha(1f).setDuration(200).start()
                initFinish = true
            }
        }
    }
}
