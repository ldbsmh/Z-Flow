package io.relimus.zflow.xposed.hook

import android.content.Context
import android.content.Intent
import android.util.DisplayMetrics
import android.view.MotionEvent
import io.relimus.zflow.xposed.hook.utils.XLog
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 角滑触发：在屏幕角落按下、往屏幕内侧滑出一段距离，弹出轮盘。
 *
 * 判定放在这里，是因为只有 launcher 进程里的输入钩子能拿到原始 MotionEvent。
 * 但它**不创建任何窗口** —— 只发一条广播给轮盘项目（Z-Flow-Wheel）的无障碍
 * 服务，由那边用自己的 window token 挂 TYPE_ACCESSIBILITY_OVERLAY。
 *
 * 之前把 overlay 直接挂在 launcher 进程里，撞上了三件事：TYPE_APPLICATION_OVERLAY
 * 需要悬浮窗权限（launcher 没有）、WindowManager 实例未初始化、窗口增删触发
 * launcher 输入分发重算导致连发失效。把窗口挪到无障碍服务后这些全都不存在。
 */
object WheelTrigger {

    /** 与 Z-Flow-Wheel 的 WheelAccessibilityService.ACTION_TRIGGER 逐字一致。 */
    const val ACTION_TRIGGER = "io.relimus.zflowwheel.action.TRIGGER"
    const val TARGET_PACKAGE = "io.relimus.zflowwheel"

    /** 距离屏幕边缘多少像素以内算「角落起手」。1080p 上约 45dp。 */
    private const val EDGE_TOLERANCE_DP = 45f

    /** 从按下到触发，至少要往内侧滑多远（dp）。防误触。 */
    private const val MIN_TRAVEL_DP = 60f

    /** 滑动方向必须足够「朝内」：横向占比超过这个才算水平滑入。 */
    private const val MIN_INWARD_RATIO = 0.55f

    private var downX = 0f
    private var downY = 0f
    private var downScreenW = 0
    private var downScreenH = 0
    private var armed = false
    private var consumed = false

    fun onDown(context: Context, event: MotionEvent): Boolean {
        consumed = false
        armed = false
        val (sw, sh) = screenSize(context)
        if (sw <= 0 || sh <= 0) return false

        downX = event.rawX
        downY = event.rawY
        downScreenW = sw
        downScreenH = sh

        val density = context.resources.displayMetrics.density
        val tol = EDGE_TOLERANCE_DP * density
        val fromLeft = downX <= tol
        val fromRight = downX >= sw - tol
        armed = fromLeft || fromRight
        if (armed) {
            XLog.ls(
                "diag:corner down x=$downX y=$downY sw=$sw sh=$sh left=$fromLeft",
                tag = "ZFlowDiag"
            )
        }
        return armed
    }

    /** @return true 表示已触发并消费掉本次手势（后续 MOVE/UP 不再处理） */
    fun onMove(context: Context, event: MotionEvent): Boolean {
        if (!armed || consumed) return false

        val density = context.resources.displayMetrics.density
        val dx = event.rawX - downX
        val dy = event.rawY - downY

        // 往内滑：左侧角 x 增大，右侧角 x 减小。
        val fromLeft = downX <= downScreenW / 2f
        val inward = if (fromLeft) dx else -dx
        val travel = hypot(dx.toDouble(), dy.toDouble()).toFloat()

        if (travel < MIN_TRAVEL_DP * density) return false
        if (inward <= 0f || inward / travel < MIN_INWARD_RATIO) {
            // 方向不对（比如沿着边缘竖着滑），本次手势作废，避免中途又触发。
            armed = false
            return false
        }

        consumed = true
        armed = false
        XLog.ls(
            "diag:corner fire x=${event.rawX} y=${event.rawY} dx=$dx dy=$dy " +
                "left=$fromLeft travel=$travel",
            tag = "ZFlowDiag"
        )
        send(context, event.rawX, event.rawY, fromLeft, dy < 0)
        return true
    }

    fun onUp() {
        armed = false
        consumed = false
    }

    private fun send(context: Context, x: Float, y: Float, fromLeft: Boolean, upper: Boolean) {
        val intent = Intent(ACTION_TRIGGER).apply {
            setPackage(TARGET_PACKAGE)
            putExtra("x", x)
            putExtra("y", y)
            putExtra("sw", downScreenW)
            putExtra("sh", downScreenH)
            putExtra("edge", fromLeft)
            putExtra("upper", upper)
        }
        runCatching { context.sendBroadcast(intent) }
            .onFailure { XLog.e("WheelTrigger: send failed", it) }
    }

    private fun screenSize(context: Context): Pair<Int, Int> {
        val dm = DisplayMetrics()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        @Suppress("DEPRECATION")
        wm?.defaultDisplay?.getRealMetrics(dm)
        if (dm.widthPixels > 0) return dm.widthPixels to dm.heightPixels
        val r = context.resources.displayMetrics
        return r.widthPixels to r.heightPixels
    }
}
