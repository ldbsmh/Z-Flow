/*
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Wheel view for Z-Flow. Layout algorithm ported from GestureEVO
 * (https://github.com/LumiaGG/GestureEVO, gesture/ui/MainActionView.kt).
 * Copyright (C) LumiaGG and GestureEVO contributors
 * Modifications for Z-Flow, Copyright (C) ldbsmh
 */

package io.relimus.zflow.xposed.ui.wheel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/** 轮盘里的一项：图标 + 名称 + 点击负载。 */
data class WheelAppEntry(
    val packageName: String,
    val activityName: String,
    val label: String,
    val userId: Int,
    val icon: Drawable?
)

/**
 * 轮盘视图：按 [WheelShape] 自适应成角盘/半圆，手指拖动选中，抬手执行。
 *
 * 交互语义与 GestureEVO 一致：
 *  - 手指落点或移动到某个选区内即高亮；
 *  - 抬手时若有选中项则回调 [onPick]，否则回调 [onCancel]。
 */
@SuppressLint("ViewConstructor")
class WheelView(
    context: Context,
    private val entries: List<WheelAppEntry>,
    private val shape: WheelShape,
    private val onPick: (WheelAppEntry) -> Unit,
    private val onCancel: () -> Unit
) : View(context) {

    private val density = resources.displayMetrics.density

    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 32, 32, 36)
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 10f * density
    }
    private val iconRect = RectF()

    private var slots: List<WheelSlot> = emptyList()
    private var itemSizePx = 0f
    private var selectedIndex = -1

    /** 轮盘整体半径，供外部摆放本 View。 */
    var wheelRadius: Float = 0f
        private set

    init {
        // 允许在圆外的点击穿透给下层（由宿主窗口决定最终行为）
        setWillNotDraw(false)
    }

    /** 在 view 尺寸确定后计算布局。 */
    // ---- 圆心：由触发角落决定的轮盘形态贴到对应边角 ----

    private var viewW = 0
    private var viewH = 0

    // quadrant: 0=左下 1=右下 2=右上 3=左上。半圆态（isSemicircle）水平贴边、竖直居中。
    private fun isLeftSide(): Boolean = shape.quadrant >= 2

    private fun centerX(): Float =
        if (!isLeftSide()) viewW.toFloat() else 0f

    private fun centerY(): Float =
        if (shape.isSemicircle) viewH / 2f
        else if (shape.isUpper) 0f
        else viewH.toFloat()

    fun layoutWheel(viewWidth: Int, viewHeight: Int) {
        viewW = viewWidth
        viewH = viewHeight
        if (entries.isEmpty()) return

        itemSizePx = 48f * density
        val centerSize = 56f * density
        val gap = 10f * density

        val itemCount = (entries.size - 1).coerceAtLeast(0)
        if (itemCount == 0) {
            slots = listOf(
                WheelSlot(
                    index = 0, x = -centerSize / 2f, y = -centerSize / 2f,
                    size = centerSize, isCenterItem = true, ringIndex = 0
                )
            )
            wheelRadius = centerSize / 2f
            return
        }

        val dist = distributeConcentricSlots(
            itemCount = itemCount,
            startAngle = shape.startAngle,
            sweepAngle = shape.sweepAngle,
            baseRadius = centerSize,
            itemDiameter = itemSizePx,
            itemWarpOffset = gap,
            mirror = false,
            includeCenterItem = false
        )

        val built = ArrayList<WheelSlot>(entries.size)
        built.add(
            WheelSlot(
                index = 0, x = -centerSize / 2f, y = -centerSize / 2f,
                size = centerSize, isCenterItem = true, ringIndex = 0
            )
        )
        for (i in dist.positions.indices) {
            val p = dist.positions[i]
            built.add(
                WheelSlot(
                    index = i + 1,
                    x = p.x,
                    y = p.y,
                    size = dist.sizes[i],
                    ringIndex = dist.ringIndices[i]
                )
            )
        }
        slots = built

        // 半径取所有项到中心的最大距离
        wheelRadius = built.maxOf { s ->
            hypot(s.centerX.toDouble(), s.centerY.toDouble()).toFloat() + s.size / 2f
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (slots.isEmpty()) return

        val cx = centerX()
        val cy = centerY()

        // 背景圆
        canvas.drawCircle(cx, cy, wheelRadius + 12f * density, ringPaint)

        for (slot in slots) {
            val dx = cx + slot.centerX
            val dy = cy + slot.centerY
            val r = slot.size / 2f

            if (slot.index == selectedIndex) {
                canvas.drawCircle(dx, dy, r + 4f * density, highlightPaint)
            }
            canvas.drawCircle(dx, dy, r, centerPaint)

            val icon = entries[slot.index].icon
            if (icon != null) {
                val pad = r * 0.25f
                iconRect.set(dx - r + pad, dy - r + pad, dx + r - pad, dy + r - pad)
                val left = iconRect.left.toInt()
                val top = iconRect.top.toInt()
                val right = iconRect.right.toInt()
                val bottom = iconRect.bottom.toInt()
                icon.setBounds(left, top, right, bottom)
                icon.draw(canvas)
            }
        }

        // 选中项名称
        val sel = entries.getOrNull(selectedIndex)
        if (sel != null) {
            canvas.drawText(sel.label, cx, cy + wheelRadius + 26f * density, labelPaint)
        }
    }

    /** 命中测试：返回触点所在的项下标，未命中返回 -1。 */
    private fun hitTest(px: Float, py: Float): Int {
        val cx = centerX()
        val cy = centerY()
        var best = -1
        var bestDist = Float.MAX_VALUE
        for (slot in slots) {
            val dx = px - (cx + slot.centerX)
            val dy = py - (cy + slot.centerY)
            val d = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (d <= slot.size * 0.75f && d < bestDist) {
                best = slot.index
                bestDist = d
            }
        }
        return best
    }

    fun selectAt(px: Float, py: Float) {
        val idx = hitTest(px, py)
        if (idx != selectedIndex) {
            selectedIndex = idx
            invalidate()
        }
    }

    fun commitSelection() {
        val entry = entries.getOrNull(selectedIndex)
        if (entry != null) onPick(entry) else onCancel()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                selectAt(event.x, event.y)
                return true
            }

            MotionEvent.ACTION_UP -> {
                selectAt(event.x, event.y)
                commitSelection()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                onCancel()
                return true
            }
        }
        return true
    }
}
