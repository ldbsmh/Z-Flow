package io.relimus.zflow.service

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.view.Display
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.net.toUri
import io.relimus.zflow.R
import io.relimus.zflow.ui.floating.ChooseAppFloatingView
import io.relimus.zflow.xposed.services.FreeformService
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 侧边悬浮按钮 + 应用选择浮层的全部 UI 逻辑。
 *
 * 原先 KeepAliveService（无障碍）和 ForegroundService（前台服务）
 * 各自维护了一份逐行雷同的实现，行为已出现细微分歧。
 * 现在两个 Service 只负责保活方式本身，悬浮窗相关状态与手势统一到这里。
 *
 * 使用方需在 onServiceConnected / onCreate 中调用 [attach]，
 * 在 onDestroy 中调用 [detach]。
 */
class FloatingWindowController(
    private val context: Context,
    private val onTapFloating: (() -> Unit)? = null
) : SharedPreferences.OnSharedPreferenceChangeListener,
    View.OnTouchListener,
    GestureDetector.OnGestureListener,
    ChooseAppFloatingView.OnWindowRemoveCallback {

    private companion object {
        const val SCROLL = 1
    }

    private val sp: SharedPreferences =
        context.getSharedPreferences(io.relimus.zflow.app.ZFlow.APP_SETTINGS_NAME, Context.MODE_PRIVATE)

    private lateinit var windowManager: WindowManager
    private lateinit var windowLayoutParams: WindowManager.LayoutParams
    private lateinit var gestureDetector: GestureDetector
    private lateinit var displayManager: DisplayManager
    private lateinit var defaultDisplay: Display
    private lateinit var floatView: View
    private lateinit var chooseAppFloatingView: ChooseAppFloatingView

    private var config = FloatingConfig(-1, 0, 0, 1f)
    private var isShowingFloating = false
    private var isShowingChooseApp = false
    private var touchMode = 0
    private var lastY = -1f
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenRotation = 0
    private var displayRotation = Surface.ROTATION_0
    private var attached = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val newRotation = defaultDisplay.rotation
            if (newRotation == displayRotation) return
            displayRotation = newRotation

            val tempRotation = if (
                displayRotation == Surface.ROTATION_0 ||
                displayRotation == Surface.ROTATION_180
            ) {
                Configuration.ORIENTATION_PORTRAIT
            } else {
                Configuration.ORIENTATION_LANDSCAPE
            }

            if (tempRotation == screenRotation) return
            screenRotation = tempRotation
            updateScreenSize()
            removeFloating()
            initConfig()
            runCatching { chooseAppFloatingView.onScreenRotationChanged(screenRotation) }
        }
    }

    fun attach() {
        if (attached) return
        attached = true

        sp.registerOnSharedPreferenceChangeListener(this)

        displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, null)
        defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY)

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowLayoutParams = WindowManager.LayoutParams()
        gestureDetector = GestureDetector(context, this)

        screenWidth = context.resources.displayMetrics.widthPixels
        screenHeight = context.resources.displayMetrics.heightPixels
        screenRotation = context.resources.configuration.orientation
        displayRotation = defaultDisplay.rotation

        initConfig()
        chooseAppFloatingView = ChooseAppFloatingView(context, config.positionX, this)
        context.startService(Intent(context, FreeformService::class.java))
    }

    fun detach() {
        if (!attached) return
        attached = false

        if (isShowingFloating) removeFloating()
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        runCatching { sp.unregisterOnSharedPreferenceChangeListener(this) }
        runCatching { context.stopService(Intent(context, FreeformService::class.java)) }
    }

    private fun updateScreenSize() {
        val metrics = context.resources.displayMetrics
        if (screenRotation == Configuration.ORIENTATION_PORTRAIT) {
            screenHeight = max(metrics.widthPixels, metrics.heightPixels)
            screenWidth = min(metrics.widthPixels, metrics.heightPixels)
        } else {
            screenWidth = max(metrics.widthPixels, metrics.heightPixels)
            screenHeight = min(metrics.widthPixels, metrics.heightPixels)
        }
    }

    // ---- SharedPreferences ----

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        when (key) {
            "show_floating" -> {
                if (getBooleanSp(key) && !isShowingFloating && !isShowingChooseApp) {
                    initConfig()
                } else {
                    removeFloating()
                }
            }

            "floating_position_x" -> {
                removeFloating()
                initConfig()
                chooseAppFloatingView.showPositionX = config.positionX
            }

            // Activity 传来的打开小窗监听
            "to_show_floating" -> {
                if (!isShowingChooseApp) {
                    removeFloating()
                    isShowingChooseApp = true
                    chooseAppFloatingView.showFloatingView()
                }
            }

            "floating_alpha" -> {
                config.alpha = getIntSp("floating_alpha", 10) / 10f
                if (isShowingFloating) {
                    runCatching {
                        windowManager.updateViewLayout(
                            floatView,
                            windowLayoutParams.apply { alpha = config.alpha }
                        )
                    }
                }
            }
        }
    }

    private fun getBooleanSp(key: String): Boolean = sp.getBoolean(key, false)
    private fun getIntSp(key: String, default: Int): Int = sp.getInt(key, default)
    private fun setIntSp(key: String, value: Int) = sp.edit { putInt(key, value) }

    // ---- 悬浮按钮 ----

    private fun initConfig() {
        config = getFloatingConfig()
        if (getBooleanSp("show_floating") && !isShowingFloating && !isShowingChooseApp) {
            showFloating()
        }
    }

    private fun getFloatingConfig() = FloatingConfig(
        getIntSp("floating_position_x", -1),
        getIntSp("floating_position_portrait_y", 0),
        getIntSp("floating_position_landscape_y", 0),
        getIntSp("floating_alpha", 10) / 10f
    )

    private fun showFloating() {
        floatView = if (config.positionX == 1) {
            LayoutInflater.from(context)
                .inflate(R.layout.view_floating_button_right, FrameLayout(context), false)
        } else {
            LayoutInflater.from(context)
                .inflate(R.layout.view_floating_button_left, FrameLayout(context), false)
        }

        floatView.findViewById<View>(R.id.root).setOnTouchListener(this)

        val buttonWidth = context.resources.getDimension(R.dimen.floating_button_width).toInt()
        val buttonHeight = context.resources.getDimension(R.dimen.floating_button_height).toInt()

        if (!Settings.canDrawOverlays(context)) {
            runCatching {
                Toast.makeText(
                    context,
                    context.getString(R.string.request_overlay_permission),
                    Toast.LENGTH_LONG
                ).show()
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        "package:${context.packageName}".toUri()
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure {
                Toast.makeText(
                    context,
                    context.getString(R.string.request_overlay_permission_fail),
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        windowManager.addView(floatView, windowLayoutParams.apply {
            x = (screenWidth - buttonWidth) / 2 * config.positionX
            y = if (screenRotation == Configuration.ORIENTATION_PORTRAIT) {
                config.positionPortraitY
            } else {
                config.positionLandscapeY
            }
            width = buttonWidth
            height = buttonHeight
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = flags or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            windowAnimations = android.R.style.Animation_Dialog
            alpha = config.alpha
        })

        isShowingFloating = true
    }

    private fun removeFloating() {
        runCatching { windowManager.removeViewImmediate(floatView) }
        isShowingFloating = false
    }

    // ---- 手势 ----

    private fun handleMove(dy: Float) {
        if (screenRotation == Configuration.ORIENTATION_PORTRAIT) {
            config.positionPortraitY = max(
                screenHeight / -2,
                min(screenHeight / 2, config.positionPortraitY + dy.roundToInt())
            )
        } else {
            config.positionLandscapeY = max(
                screenHeight / -2,
                min(screenHeight / 2, config.positionLandscapeY + dy.roundToInt())
            )
        }

        windowManager.updateViewLayout(
            floatView,
            windowLayoutParams.apply {
                y = max(screenHeight / -2, min(screenHeight / 2, y + dy.roundToInt()))
            }
        )
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> lastY = event.rawY

            MotionEvent.ACTION_MOVE -> {
                if (touchMode == SCROLL) {
                    handleMove(event.rawY - lastY)
                    lastY = event.rawY
                }
            }

            MotionEvent.ACTION_UP -> {
                if (touchMode == SCROLL) {
                    if (screenRotation == Configuration.ORIENTATION_PORTRAIT) {
                        setIntSp("floating_position_portrait_y", config.positionPortraitY)
                    } else {
                        setIntSp("floating_position_landscape_y", config.positionLandscapeY)
                    }
                } else {
                    v.performClick()
                }
                touchMode = 0
            }
        }
        return true
    }

    override fun onDown(e: MotionEvent) = false
    override fun onShowPress(e: MotionEvent) {}
    override fun onLongPress(e: MotionEvent) {}
    override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float) = false

    override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
        touchMode = SCROLL
        return false
    }

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        onTapFloating?.invoke()
        chooseAppFloatingView.showFloatingView()
        removeFloating()
        isShowingChooseApp = true
        return false
    }

    override fun onChooseAppWindowRemove() {
        isShowingChooseApp = false
        if (getBooleanSp("show_floating") && !isShowingFloating) {
            showFloating()
        }
    }

    data class FloatingConfig(
        // 垂直按钮横坐标，-1左，1右
        var positionX: Int,
        // 竖屏状态悬浮按钮纵坐标
        var positionPortraitY: Int,
        var positionLandscapeY: Int,
        // 侧边栏透明度
        var alpha: Float
    )
}
