package io.relimus.zflow.xposed.hook

import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup

object PortraitSwipeGestureHandler {

    private val hitRect = Rect()

    @Volatile
    private var active = false

    fun activate() {
        active = true
        hitRect.setEmpty()
    }

    fun reset() {
        active = false
        hitRect.setEmpty()
    }

    fun isActive(): Boolean = active

    fun debugState(): String {
        return "active=$active hitRect=$hitRect"
    }

    fun positionTopRight(
        target: View,
        parent: ViewGroup,
        marginRight: Int,
        marginTop: Int
    ) {
        if (
            !target.isAttachedToWindow ||
            target.width <= 0 ||
            target.height <= 0 ||
            parent.width <= 0 ||
            parent.height <= 0
        ) {
            return
        }

        val visibleFrame = Rect()
        parent.getWindowVisibleDisplayFrame(visibleFrame)

        val parentLocation = IntArray(2)
        parent.getLocationOnScreen(parentLocation)

        val targetLocation = IntArray(2)
        target.getLocationOnScreen(targetLocation)

        val desiredScreenX = if (!visibleFrame.isEmpty) {
            visibleFrame.right - target.width - marginRight
        } else {
            parentLocation[0] +
                parent.width -
                target.width -
                marginRight
        }

        val desiredScreenY = if (!visibleFrame.isEmpty) {
            visibleFrame.top + marginTop
        } else {
            parentLocation[1] + marginTop
        }

        target.translationX +=
            (desiredScreenX - targetLocation[0]).toFloat()

        target.translationY +=
            (desiredScreenY - targetLocation[1]).toFloat()

        updateHitRect(target)
    }

    fun updateHitRect(
        view: View,
        extra: Int = dp(view, 32f)
    ) {
        if (
            !view.isAttachedToWindow ||
            view.width <= 0 ||
            view.height <= 0
        ) {
            hitRect.setEmpty()
            return
        }

        val location = IntArray(2)
        view.getLocationOnScreen(location)

        hitRect.set(
            location[0] - extra,
            location[1] - extra,
            location[0] + view.width + extra,
            location[1] + view.height + extra
        )
    }

    fun isPointerInside(event: MotionEvent): Boolean {
        if (!active || hitRect.isEmpty) {
            return false
        }

        if (
            event.rawX.isFinite() &&
            event.rawY.isFinite() &&
            hitRect.contains(
                event.rawX.toInt(),
                event.rawY.toInt()
            )
        ) {
            return true
        }

        return event.x.isFinite() &&
            event.y.isFinite() &&
            hitRect.contains(
                event.x.toInt(),
                event.y.toInt()
            )
    }

    private fun dp(
        view: View,
        value: Float
    ): Int {
        return (
            value *
                view.resources.displayMetrics.density +
                0.5f
            ).toInt()
    }
}
