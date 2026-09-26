package io.relimus.zflow.xposed.ui.window

import android.content.Context
import android.graphics.PixelFormat
import android.os.ServiceManager
import android.view.Gravity
import android.view.IWindowManager
import android.view.View
import android.view.WindowManager
import io.github.kyuubiran.ezxhelper.core.util.ObjectUtil.getObject
import io.github.kyuubiran.ezxhelper.core.util.ObjectUtil.invokeMethodBestMatch
import io.relimus.zflow.xposed.hook.utils.XLog
import io.relimus.zflow.xposed.utils.Instances

/**
 * 最小验证：给 Z-Flow 的虚拟屏挂一条「状态栏 inset」。
 *
 * ## 要验证什么
 *
 * 起点读书与微信在小窗里的顶部异常，根因是同一个：
 * **虚拟屏上没有任何 inset 源，应用却按「顶部有状态栏」来布局**。
 *
 *   - 起点：按 `android:dimen/status_bar_height` 预留一截，等状态栏覆盖
 *   - 微信：`ConversationListView.updateScrollOffset()` 在 ActionBar 贴顶时
 *           用状态栏高度兜底，算出过大的 scrollOffset，任务栏面板少收 136px
 *
 * 本类只验证**技术通路**：在虚拟屏上加一个声明 `statusBars` inset 的窗口，
 * 看 (1) 该屏是否出现 statusBars inset 源，(2) 微信的双标题栏是否消失。
 *
 * ## 做法
 *
 * 不自己拼 `InsetsFrameProvider`（id 在 Android 17 上是动态生成的，猜不得），
 * 而是**直接借用主屏真实状态栏窗口的 `LayoutParams.providedInsets`** ——
 * 拿到的就是系统认的那一份，id / type / frame 全部正确。
 *
 * ## 边界
 *
 * 窗口全透明、NOT_FOCUSABLE、NOT_TOUCHABLE，不绘制任何内容、不参与输入。
 * 任何一步失败都静默跳过并打日志，行为与修改前一致。
 */
object VirtualStatusBarController {

    /** 总开关。验证完成后按需关闭。 */
    private const val ENABLED = true

    /**
     * 状态栏底色。0 = 全透明，只验证 inset 通路。
     * 若要看视觉效果，可临时改成 0x33000000 之类的半透明色。
     */
    private const val TINT = 0

    private const val TAG = "VirtualStatusBar"

    /** 每个虚拟屏已挂上的窗口视图；key = displayId */
    private val attached = HashMap<Int, View>()

    /**
     * 在 [displayId] 上挂一条状态栏 inset。
     * 幂等：重复调用不会重复添加。
     */
    fun attach(displayId: Int) {
        if (!ENABLED) return
        if (displayId <= 0) return

        runCatching {
            if (attached.containsKey(displayId)) return

            val display = Instances.displayManager.getDisplay(displayId) ?: run {
                XLog.d("$TAG: display $displayId not found")
                return
            }

            val providedInsets = copyProvidedInsetsFromSystemStatusBar()
            if (providedInsets == null) {
                XLog.d("$TAG: cannot read system status bar providedInsets, skip")
                return
            }

            val height = resolveStatusBarHeight()
            if (height <= 0) {
                XLog.d("$TAG: status bar height is 0, skip")
                return
            }

            val context = Instances.systemContext.createDisplayContext(display)
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: run {
                XLog.d("$TAG: no WindowManager for display $displayId")
                return
            }

            val view = View(context).apply {
                if (TINT != 0) setBackgroundColor(TINT)
            }

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                height,
                WindowManager.LayoutParams.TYPE_STATUS_BAR,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                if (!setProvidedInsets(this, providedInsets)) {
                    XLog.d("$TAG: LayoutParams.providedInsets unavailable, skip")
                    return
                }
            }

            wm.addView(view, lp)
            attached[displayId] = view

            XLog.d(
                "$TAG: attached virtual status bar to display=$displayId " +
                    "height=$height providers=${providedInsets.size}"
            )
        }.onFailure {
            XLog.e("$TAG: attach failed display=$displayId", it)
        }
    }

    /** 摘掉 [displayId] 上的状态栏窗口。 */
    fun detach(displayId: Int) {
        val view = attached.remove(displayId) ?: return
        runCatching {
            val display = Instances.displayManager.getDisplay(displayId) ?: return
            val context = Instances.systemContext.createDisplayContext(display)
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
            wm.removeViewImmediate(view)
            XLog.d("$TAG: detached virtual status bar from display=$displayId")
        }.onFailure {
            XLog.e("$TAG: detach failed display=$displayId", it)
        }
    }

    /**
     * 借用主屏真实状态栏窗口的 `providedInsets`。
     *
     * 路径：WindowManagerService → mRoot → DisplayContent(0)
     *      → DisplayPolicy → mStatusBar(WindowState) → mAttrs.providedInsets
     *
     * system_server 内 `ServiceManager.getService("window")` 拿到的就是
     * WindowManagerService 本体（queryLocalInterface 命中），可直接反射其字段。
     */
    private fun copyProvidedInsetsFromSystemStatusBar(): Array<Any>? {
        return runCatching {
            val wms = IWindowManager.Stub.asInterface(ServiceManager.getService("window"))
            val root = getObject(wms, "mRoot") ?: return null
            val displayContent = invokeMethodBestMatch(root, "getDisplayContent", null, 0) ?: return null
            val policy = getObject(displayContent, "getDisplayPolicy") ?: return null
            val statusBarWindow = getObject(policy, "mStatusBar") ?: return null
            val attrs = getObject(statusBarWindow, "mAttrs") ?: return null

            val field = runCatching {
                attrs.javaClass.getField("providedInsets")
            }.getOrElse {
                attrs.javaClass.getDeclaredField("providedInsets").apply { isAccessible = true }
            }

            @Suppress("UNCHECKED_CAST")
            (field.get(attrs) as? Array<Any>)?.takeIf { it.isNotEmpty() }
        }.onFailure {
            XLog.e("$TAG: read system status bar insets failed", it)
        }.getOrNull()
    }

    private fun setProvidedInsets(
        lp: WindowManager.LayoutParams,
        providedInsets: Array<Any>
    ): Boolean {
        return runCatching {
            val field = WindowManager.LayoutParams::class.java
                .getDeclaredField("providedInsets")
                .apply { isAccessible = true }
            field.set(lp, providedInsets)
        }.isSuccess
    }

    /** 状态栏高度，与系统同源（`android:dimen/status_bar_height`）。 */
    private fun resolveStatusBarHeight(): Int {
        return runCatching {
            val res = Instances.systemContext.resources
            val id = res.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) res.getDimensionPixelSize(id) else 0
        }.getOrDefault(0)
    }
}
