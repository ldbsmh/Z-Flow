/*
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Corner trigger detection for Z-Flow's wheel launcher.
 * Geometry conventions ported from GestureEVO (https://github.com/LumiaGG/GestureEVO),
 * `resolveWheelAdaptiveShape` (gesture/ui/GuideWheelGeometry.kt).
 * Copyright (C) LumiaGG and GestureEVO contributors
 * Modifications for Z-Flow, Copyright (C) ldbsmh
 */

package io.relimus.zflow.xposed.ui.wheel

import android.view.MotionEvent

/**
 * 角触发判定：从屏幕角落沿边滑入，即唤起轮盘。
 *
 * 与 GestureEVO 的差异（这是移植的关键适配）：
 * GestureEVO 是用户手动拖放一个矩形热区，落在哪个象限由**热区位置**决定；
 * 而 Z-Flow 能直接看到真实触摸，所以改为由**手指落点到滑动的实际轨迹**判定，
 * 更符合「从角落滑入」的直觉，也不需要额外配置界面。
 *
 * 判定条件（全部满足才算角触发）：
 *  1. 落点距屏幕左/右边缘 ≤ [edgeTolerance]；
 *  2. 落点距屏幕顶/底边缘 ≤ [cornerTolerance]（即真的在「角」上，而非边中段）；
 *  3. 滑动方向以「向内」为主：横向位移 > 纵向位移的 0.6 倍，且有足够位移；
 *  4. 手势未被其他逻辑消费（由调用方保证）。
 */
object CornerTrigger {

    /** 判定结果。 */
    data class Hit(
        /** 是否在左侧角落 */
        val isLeft: Boolean,
        /** 是否在上方角落 */
        val isUpper: Boolean,
        /** 触发点的垂直百分比（0=顶，100=底），用于决定轮盘形态 */
        val verticalPercent: Int
    )

    const val DEFAULT_EDGE_TOLERANCE_PX = 24f
    const val DEFAULT_CORNER_TOLERANCE_PX = 160f
    const val DEFAULT_MIN_SWIPE_PX = 30f

    private const val INWARD_DOMINANCE = 0.6f

    /** 当前手指落点（ACTION_DOWN 记录），供 MOVE/UP 复用。 */
    var downX: Float = 0f
        private set
    var downY: Float = 0f
        private set

    private var armedHit: Hit? = null

    fun reset() {
        downX = 0f
        downY = 0f
        armedHit = null
    }

    /**
     * 处理 ACTION_DOWN，记录落点并在落于角落时「预置」一次候选。
     *
     * @return 落点在角落范围内时返回 [Hit]，否则 null
     */
    fun onDown(
        x: Float,
        y: Float,
        screenWidth: Int,
        screenHeight: Int,
        edgeTolerance: Float = DEFAULT_EDGE_TOLERANCE_PX,
        cornerTolerance: Float = DEFAULT_CORNER_TOLERANCE_PX
    ): Hit? {
        reset()
        if (screenWidth <= 0 || screenHeight <= 0) return null
        downX = x
        downY = y

        val hit = locate(x, y, screenWidth, screenHeight, edgeTolerance, cornerTolerance)
            ?: return null
        armedHit = hit
        return hit
    }

    /**
     * 处理 ACTION_MOVE / ACTION_UP，判断这次滑动是否构成「从角落向内滑入」。
     *
     * @return 满足条件时返回 [Hit]，否则 null（调用方据此唤起轮盘并 [reset]）
     */
    fun onMove(
        x: Float,
        y: Float,
        minSwipePx: Float = DEFAULT_MIN_SWIPE_PX
    ): Hit? {
        val hit = armedHit ?: return null

        val dx = x - downX
        val dy = y - downY
        val absDx = kotlin.math.abs(dx)
        val absDy = kotlin.math.abs(dy)

        // 位移不足
        if (absDx < minSwipePx && absDy < minSwipePx) return null

        // 方向必须是「向内」：左侧角 → 向右滑；右侧角 → 向左滑
        val inwardDx = if (hit.isLeft) dx else -dx
        if (inwardDx <= 0f) return null

        // 横向必须占主导，否则是纵向滚动
        if (absDx < absDy * INWARD_DOMINANCE) return null

        return hit
    }

    /** 仅做位置判定，不涉及手势轨迹。 */
    fun locate(
        x: Float,
        y: Float,
        screenWidth: Int,
        screenHeight: Int,
        edgeTolerance: Float = DEFAULT_EDGE_TOLERANCE_PX,
        cornerTolerance: Float = DEFAULT_CORNER_TOLERANCE_PX
    ): Hit? {
        if (screenWidth <= 0 || screenHeight <= 0) return null

        val isLeft = when {
            x <= edgeTolerance -> true
            x >= screenWidth - edgeTolerance -> false
            else -> return null
        }

        val isUpper = when {
            y <= cornerTolerance -> true
            y >= screenHeight - cornerTolerance -> false
            else -> return null
        }

        val verticalPercent = ((y / screenHeight) * 100f).toInt().coerceIn(0, 100)
        return Hit(isLeft = isLeft, isUpper = isUpper, verticalPercent = verticalPercent)
    }

    /** 由命中结果得到轮盘形态。 */
    fun shapeFor(hit: Hit): WheelShape =
        resolveWheelShape(isLeft = hit.isLeft, verticalPercent = hit.verticalPercent)

    /** 便捷：从 MotionEvent 直接取坐标。 */
    fun onDown(event: MotionEvent, screenWidth: Int, screenHeight: Int): Hit? =
        onDown(event.rawX, event.rawY, screenWidth, screenHeight)

    fun onMove(event: MotionEvent): Hit? = onMove(event.rawX, event.rawY)
}
