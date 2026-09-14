package io.relimus.zflow.xposed.ui.window

import android.animation.ValueAnimator
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs

/**
 * hiddenView 拖拽处理：
 * 1. 点击 -> 展开
 * 2. 直接拖动
 * 3. 拖到底部 15% 松手 -> 关闭
 * 4. 否则 -> 回调给外部进行吸边/避让/回弹
 *
 * 要求 hiddenView 的 LayoutParams.gravity = Gravity.TOP or Gravity.START
 */
class HiddenViewDragCloseHandler(
    private val context: Context,
    private val hiddenView: View,
    private val windowManager: WindowManager,
    private val onClose: () -> Unit,
    private val onExpand: () -> Unit,
    private val onDragRelease: (currentX: Int, currentY: Int) -> Unit,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val floatingButtonWidth: Int,
    private val floatingButtonHeight: Int
) {

    companion object {
        private const val CLOSE_AREA_THRESHOLD = 0.85f
    }

    private var downRawX = 0f
    private var downRawY = 0f
    private var touchOffsetX = 0f
    private var touchOffsetY = 0f
    private var isDragging = false
    private var dragCloseEnabled = false

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    fun attach() {
        hiddenView.setOnTouchListener { _, event ->
            handleTouch(event)
            true
        }
    }

    private fun handleTouch(event: MotionEvent) {
        val lp = hiddenView.layoutParams as? WindowManager.LayoutParams ?: return

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                touchOffsetX = event.rawX - lp.x
                touchOffsetY = event.rawY - lp.y
                isDragging = false
                dragCloseEnabled = false
                hiddenView.animate().cancel()
            }

            MotionEvent.ACTION_MOVE -> {
                val totalDx = event.rawX - downRawX
                val totalDy = event.rawY - downRawY

                if (!isDragging && (abs(totalDx) > touchSlop || abs(totalDy) > touchSlop)) {
                    isDragging = true
                    hiddenView.animate()
                        .scaleX(1.08f)
                        .scaleY(1.08f)
                        .setDuration(80)
                        .start()
                }

                if (isDragging) {
                    val minX = -floatingButtonWidth / 2
                    val maxX = screenWidth - floatingButtonWidth / 2
                    val minY = 0
                    val maxY = screenHeight - floatingButtonHeight

                    val newX = (event.rawX - touchOffsetX).toInt().coerceIn(minX, maxX)
                    val newY = (event.rawY - touchOffsetY).toInt().coerceIn(minY, maxY)

                    lp.x = newX
                    lp.y = newY
                    try {
                        windowManager.updateViewLayout(hiddenView, lp)
                    } catch (_: Exception) {
                    }

                    val viewBottom = newY + floatingButtonHeight
                    val shouldClose = viewBottom >= screenHeight * CLOSE_AREA_THRESHOLD

                    if (shouldClose != dragCloseEnabled) {
                        dragCloseEnabled = shouldClose
                        hiddenView.animate()
                            .alpha(if (dragCloseEnabled) 0.55f else 1f)
                            .setDuration(60)
                            .start()
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDragging) {
                    onExpand.invoke()
                    restoreVisualState()
                    return
                }

                val currentX = lp.x
                val currentY = lp.y

                if (dragCloseEnabled) {
                    onClose.invoke()
                } else {
                    onDragRelease.invoke(currentX, currentY)
                }

                isDragging = false
                dragCloseEnabled = false
            }
        }
    }

    fun animateTo(targetX: Int, targetY: Int) {
        val lp = hiddenView.layoutParams as? WindowManager.LayoutParams ?: return
        val startX = lp.x
        val startY = lp.y

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                lp.x = (startX + (targetX - startX) * fraction).toInt()
                lp.y = (startY + (targetY - startY) * fraction).toInt()
                try {
                    windowManager.updateViewLayout(hiddenView, lp)
                } catch (_: Exception) {
                }
            }
            start()
        }

        hiddenView.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(180)
            .start()
    }

    private fun restoreVisualState() {
        hiddenView.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(100)
            .start()
    }

    fun detach() {
        hiddenView.setOnTouchListener(null)
    }
}