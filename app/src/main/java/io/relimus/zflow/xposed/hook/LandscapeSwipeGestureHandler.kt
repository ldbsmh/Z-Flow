package io.relimus.zflow.xposed.hook

import android.content.Context
import android.graphics.Rect
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager

object LandscapeSwipeGestureHandler {

    /*
     * 当前设备的 Quickstep 在横屏时仍可能报告 ROTATION_0，
     * 实际 Surface 坐标映射符合 ROTATION_270。
     */
    private const val FALLBACK_LANDSCAPE_ROTATION =
        Surface.ROTATION_270

    private val locationScreenRect = Rect()
    private val convertedScreenRect = Rect()
    private val naturalRect = Rect()

    @Volatile
    private var active = false

    @Volatile
    private var parentUsesNaturalCoordinates = false

    @Volatile
    private var reportedRotation = Surface.ROTATION_0

    @Volatile
    private var effectiveRotation =
        FALLBACK_LANDSCAPE_ROTATION

    @Volatile
    private var realScreenWidth = 0

    @Volatile
    private var realScreenHeight = 0

    @Volatile
    private var naturalWidth = 0

    @Volatile
    private var naturalHeight = 0

    fun activate(context: Context) {
        active = true

        updateDisplayState(context)
        updateEffectiveRotation()

        clearHitRects()
    }

    fun reset() {
        active = false
        parentUsesNaturalCoordinates = false

        naturalWidth = 0
        naturalHeight = 0

        clearHitRects()
    }

    fun isActive(): Boolean = active

    fun debugState(): String {
        return buildString {
            append("active=")
            append(active)

            append(" parentNatural=")
            append(parentUsesNaturalCoordinates)

            append(" reportedRotation=")
            append(rotationName(reportedRotation))

            append(" effectiveRotation=")
            append(rotationName(effectiveRotation))

            append(" realScreen=")
            append(realScreenWidth)
            append('x')
            append(realScreenHeight)

            append(" natural=")
            append(naturalWidth)
            append('x')
            append(naturalHeight)

            append(" locationRect=")
            append(locationScreenRect)

            append(" convertedRect=")
            append(convertedScreenRect)

            append(" naturalRect=")
            append(naturalRect)
        }
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

        updateDisplayState(target.context)
        updateEffectiveRotation()

        naturalWidth = parent.width
        naturalHeight = parent.height

        /*
         * 横屏名单应用中，如果 Quickstep 根布局仍然宽小于高，
         * 表示它继续使用竖屏自然坐标系。
         */
        parentUsesNaturalCoordinates =
            parent.width < parent.height

        if (parentUsesNaturalCoordinates) {
            positionInNaturalCoordinates(
                target = target,
                parent = parent,
                marginRight = marginRight,
                marginTop = marginTop
            )
        } else {
            positionInPhysicalCoordinates(
                target = target,
                parent = parent,
                marginRight = marginRight,
                marginTop = marginTop
            )
        }

        updateHitRects(target)
    }

    private fun positionInPhysicalCoordinates(
        target: View,
        parent: ViewGroup,
        marginRight: Int,
        marginTop: Int
    ) {
        val visibleFrame = Rect()
        parent.getWindowVisibleDisplayFrame(visibleFrame)

        val parentLocation = IntArray(2)
        parent.getLocationOnScreen(parentLocation)

        val targetLocation = IntArray(2)
        target.getLocationOnScreen(targetLocation)

        val desiredScreenX = if (!visibleFrame.isEmpty) {
            visibleFrame.right -
                target.width -
                marginRight
        } else if (realScreenWidth > 0) {
            realScreenWidth -
                target.width -
                marginRight
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
    }

    private fun positionInNaturalCoordinates(
        target: View,
        parent: ViewGroup,
        marginRight: Int,
        marginTop: Int
    ) {
        target.translationX = 0f
        target.translationY = 0f

        naturalWidth = parent.width
        naturalHeight = parent.height

        val maxX =
            (naturalWidth - target.width)
                .coerceAtLeast(0)

        val maxY =
            (naturalHeight - target.height)
                .coerceAtLeast(0)

        /*
         * 当前设备：
         *
         * 自然竖屏右下角
         *      ↓ ROTATION_270
         * 物理横屏右上角
         */
        val targetNaturalX =
            naturalWidth -
                target.width -
                marginTop

        val targetNaturalY =
            naturalHeight -
                target.height -
                marginRight

        target.x =
            targetNaturalX
                .coerceIn(0, maxX)
                .toFloat()

        target.y =
            targetNaturalY
                .coerceIn(0, maxY)
                .toFloat()
    }

    fun updateHitRects(
        view: View,
        extra: Int = dp(view, 32f)
    ) {
        if (
            !view.isAttachedToWindow ||
            view.width <= 0 ||
            view.height <= 0
        ) {
            clearHitRects()
            return
        }

        updateDisplayState(view.context)
        updateEffectiveRotation()

        val location = IntArray(2)
        view.getLocationOnScreen(location)

        locationScreenRect.set(
            location[0] - extra,
            location[1] - extra,
            location[0] + view.width + extra,
            location[1] + view.height + extra
        )

        val parent =
            view.parent as? ViewGroup

        if (
            !parentUsesNaturalCoordinates ||
            parent == null ||
            parent.width <= 0 ||
            parent.height <= 0
        ) {
            naturalRect.setEmpty()
            convertedScreenRect.set(locationScreenRect)
            return
        }

        naturalWidth = parent.width
        naturalHeight = parent.height

        naturalRect.set(
            view.x.toInt() - extra,
            view.y.toInt() - extra,
            (view.x + view.width).toInt() + extra,
            (view.y + view.height).toInt() + extra
        )

        when (effectiveRotation) {
            Surface.ROTATION_90 -> {
                convertedScreenRect.set(
                    naturalHeight - naturalRect.bottom,
                    naturalRect.left,
                    naturalHeight - naturalRect.top,
                    naturalRect.right
                )
            }

            Surface.ROTATION_270 -> {
                convertedScreenRect.set(
                    naturalRect.top,
                    naturalWidth - naturalRect.right,
                    naturalRect.bottom,
                    naturalWidth - naturalRect.left
                )
            }

            Surface.ROTATION_180 -> {
                convertedScreenRect.set(
                    naturalWidth - naturalRect.right,
                    naturalHeight - naturalRect.bottom,
                    naturalWidth - naturalRect.left,
                    naturalHeight - naturalRect.top
                )
            }

            else -> {
                convertedScreenRect.set(naturalRect)
            }
        }
    }

    fun isPointerInside(event: MotionEvent): Boolean {
        if (!active) {
            return false
        }

        val rawX = event.rawX
        val rawY = event.rawY
        val localX = event.x
        val localY = event.y

        if (
            contains(locationScreenRect, rawX, rawY) ||
            contains(convertedScreenRect, rawX, rawY) ||
            contains(locationScreenRect, localX, localY) ||
            contains(convertedScreenRect, localX, localY)
        ) {
            return true
        }

        if (
            contains(naturalRect, rawX, rawY) ||
            contains(naturalRect, localX, localY)
        ) {
            return true
        }

        val rawNatural =
            screenToNatural(rawX, rawY)

        if (
            contains(
                naturalRect,
                rawNatural.first,
                rawNatural.second
            )
        ) {
            return true
        }

        val localNatural =
            screenToNatural(localX, localY)

        return contains(
            naturalRect,
            localNatural.first,
            localNatural.second
        )
    }

    private fun screenToNatural(
        screenX: Float,
        screenY: Float
    ): Pair<Float, Float> {
        if (!parentUsesNaturalCoordinates) {
            return screenX to screenY
        }

        return when (effectiveRotation) {
            Surface.ROTATION_90 -> {
                screenY to
                    (naturalHeight - screenX)
            }

            Surface.ROTATION_270 -> {
                (naturalWidth - screenY) to
                    screenX
            }

            Surface.ROTATION_180 -> {
                (naturalWidth - screenX) to
                    (naturalHeight - screenY)
            }

            else -> {
                screenX to screenY
            }
        }
    }

    private fun updateEffectiveRotation() {
        effectiveRotation =
            when (reportedRotation) {
                Surface.ROTATION_90,
                Surface.ROTATION_270 -> {
                    reportedRotation
                }

                else -> {
                    FALLBACK_LANDSCAPE_ROTATION
                }
            }
    }

    @Suppress("DEPRECATION")
    private fun updateDisplayState(context: Context) {
        try {
            val windowManager =
                context.getSystemService(
                    Context.WINDOW_SERVICE
                ) as WindowManager

            val metrics = DisplayMetrics()

            windowManager.defaultDisplay
                .getRealMetrics(metrics)

            realScreenWidth =
                metrics.widthPixels

            realScreenHeight =
                metrics.heightPixels

            reportedRotation =
                windowManager.defaultDisplay.rotation
        } catch (_: Throwable) {
            // 保留上次结果和 ROTATION_270 回退。
        }
    }

    private fun clearHitRects() {
        locationScreenRect.setEmpty()
        convertedScreenRect.setEmpty()
        naturalRect.setEmpty()
    }

    private fun contains(
        rect: Rect,
        x: Float,
        y: Float
    ): Boolean {
        if (
            rect.isEmpty ||
            !x.isFinite() ||
            !y.isFinite()
        ) {
            return false
        }

        return rect.contains(
            x.toInt(),
            y.toInt()
        )
    }

    private fun rotationName(rotation: Int): String {
        return when (rotation) {
            Surface.ROTATION_0 ->
                "ROTATION_0"

            Surface.ROTATION_90 ->
                "ROTATION_90"

            Surface.ROTATION_180 ->
                "ROTATION_180"

            Surface.ROTATION_270 ->
                "ROTATION_270"

            else ->
                "UNKNOWN($rotation)"
        }
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
