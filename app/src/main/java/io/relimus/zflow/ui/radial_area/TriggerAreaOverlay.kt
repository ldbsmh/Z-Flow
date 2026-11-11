package io.relimus.zflow.ui.radial_area

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import io.relimus.zflow.providers.RadialTriggerSettings

/**
 * 在**真实屏幕**上显示触发区的浮层。
 *
 * 设置页里那个「手机轮廓」预览能看出大致位置，但用户真正关心的是「我拇指落下去
 * 会不会碰到这块区」，只有按真实坐标画在屏幕上才说得清。所以只要有悬浮窗权限，
 * 就在编辑期间把区域一比一叠在真实屏幕上；没有权限时退回纯页内预览，不阻塞编辑。
 *
 * 浮层全程 FLAG_NOT_TOUCHABLE：它只是提示，不能挡住用户对设置页的操作。
 */
internal class TriggerAreaOverlay(context: Context) {

    private val appContext = context.applicationContext ?: context

    private val windowManager =
        appContext.getSystemService(WindowManager::class.java)

    private var view: TriggerAreaPreviewView? = null
    private var attached = false

    val available: Boolean
        get() = windowManager != null && Settings.canDrawOverlays(appContext)

    /** 浮层当前是否真的挂上去了（有权限也可能 addView 失败）。 */
    val isAttached: Boolean
        get() = attached

    /** 真实屏幕尺寸（含状态栏/导航栏），与 hook 侧 currentWindowMetrics 对齐。 */
    fun screenSize(): Pair<Float, Float>? = runCatching {
        val bounds = windowManager?.maximumWindowMetrics?.bounds ?: return@runCatching null
        bounds.width().toFloat() to bounds.height().toFloat()
    }.getOrNull()

    fun show(config: RadialTriggerSettings.Config): Boolean {
        val wm = windowManager ?: return false
        if (!available) return false
        if (attached) {
            update(config)
            return true
        }
        val overlayView = TriggerAreaPreviewView(appContext).apply {
            frame = false
            this.config = config
            screenSize = screenSize()
            isClickable = false
            isFocusable = false
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            alpha = 1f
            setTitle("ZFlowTriggerArea")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
            }
        }
        val added = runCatching { wm.addView(overlayView, params) }
        if (added.isFailure) return false
        view = overlayView
        attached = true
        return true
    }

    fun update(config: RadialTriggerSettings.Config) {
        view?.config = config
    }

    fun hide() {
        val wm = windowManager
        val overlayView = view
        view = null
        attached = false
        if (wm == null || overlayView == null) return
        runCatching { wm.removeViewImmediate(overlayView) }
    }
}
