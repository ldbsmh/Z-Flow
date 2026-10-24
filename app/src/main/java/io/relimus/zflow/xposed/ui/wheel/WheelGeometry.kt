/*
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Ported from GestureEVO (https://github.com/LumiaGG/GestureEVO),
 * original files: gesture/ui/BaseActionView.kt (Ring/RingItem),
 *                 gesture/ui/GuideWheelGeometry.kt (shape + layout)
 * Copyright (C) LumiaGG and GestureEVO contributors
 * Modifications for Z-Flow, Copyright (C) ldbsmh
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3 of the License, or (at your option) any later version.
 */

package io.relimus.zflow.xposed.ui.wheel

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/** 轮盘上一项的相对坐标（相对轮盘中心，单位 px）。 */
data class RingItem(var x: Float, var y: Float)

/** 轮盘上单个格子的最终布局。 */
data class WheelSlot(
    val index: Int,
    val x: Float,
    val y: Float,
    val size: Float,
    val centerX: Float = x + size / 2f,
    val centerY: Float = y + size / 2f,
    val isCenterItem: Boolean = false,
    val ringIndex: Int = 0
)

/** 轮盘形状：由起始角/张角描述，并标注它属于哪个象限、是否朝上。 */
data class WheelShape(
    val startAngle: Float,
    val sweepAngle: Float,
    val isSemicircle: Boolean,
    val isUpper: Boolean,
    val quadrant: Int
)

/** 一次同心环分布的结果。 */
data class WheelDistribution(
    val positions: List<RingItem>,
    val sizes: List<Float>,
    val ringRadii: List<Float>,
    val ringIndices: List<Int>
)

/**
 * 一个环上的点位分布。
 *
 * 每项的圆心落在以 [radius] 为半径、从 [startAngle] 起扫过 [sweepAngle] 的圆弧上，
 * 项与项之间按“刚好不重叠”的弧长均匀排布（项直径 + [itemWarpOffset] 决定的角占位）。
 */
class WheelRing(
    val startAngle: Float,
    val sweepAngle: Float,
    val radius: Float,
    reverse: Boolean,
    itemDiameter: Float,
    itemWarpOffset: Float = 10f
) {
    val items: ArrayList<RingItem> = ArrayList()

    init {
        calculateItemPosition(itemDiameter + itemWarpOffset)
        if (reverse) items.reverse()
    }

    private fun calculateItemPosition(itemDiameter: Float) {
        if (radius <= 0f || itemDiameter <= 0f) return

        // 单项目在圆上占据的圆心角（弧长 ≈ 直径 → 弦长公式反解）
        val ratio = (itemDiameter / (2f * radius)).coerceIn(-1f, 1f)
        val radians = 2.0 * asin(ratio)
        val itemAngle = Math.toDegrees(radians)

        if (itemAngle <= 0.0 || sweepAngle == 0f) return

        val maxItemCount = (abs(sweepAngle) / itemAngle).toInt()
        if (maxItemCount <= 0) return

        // 用整角均分，避免最后一项被挤在边缘
        val anglePerItem = abs(sweepAngle) / maxItemCount
        val offsetPerItem = anglePerItem / 2

        for (i in 0 until maxItemCount) {
            val currentAngle = if (sweepAngle > 0) {
                startAngle + (offsetPerItem + i * anglePerItem)
            } else {
                startAngle - (offsetPerItem + i * anglePerItem)
            }
            val x = radius * cos(Math.toRadians(currentAngle.toDouble()))
            val y = radius * sin(Math.toRadians(currentAngle.toDouble()))
            // 屏幕 y 轴向下，取负保持几何直觉（与 GestureEVO 一致）
            items.add(RingItem(x.toFloat(), -y.toFloat()))
        }
    }
}

/**
 * 根据触发位置确定轮盘形态。
 *
 * 这是「从角落切换」的核心：
 *  - 垂直位置 < 25%（贴上角）或 > 75%（贴下角）→ 90° 角盘；
 *  - 其余位置 → 180° 半圆盘。
 *
 * @param isLeft 触发点是否在屏幕左侧
 * @param verticalPercent 触发点的垂直百分比（0=顶，100=底）
 */
fun resolveWheelShape(
    isLeft: Boolean,
    verticalPercent: Int,
    cornerThresholdPercent: Float = 25f
): WheelShape = when {
    verticalPercent < cornerThresholdPercent -> WheelShape(
        startAngle = if (isLeft) 270f else 180f,
        sweepAngle = 90f,
        isSemicircle = false,
        isUpper = true,
        quadrant = if (isLeft) 3 else 2
    )

    verticalPercent > 100f - cornerThresholdPercent -> WheelShape(
        startAngle = if (isLeft) 0f else 90f,
        sweepAngle = 90f,
        isSemicircle = false,
        isUpper = false,
        quadrant = if (isLeft) 0 else 1
    )

    else -> WheelShape(
        startAngle = if (isLeft) 270f else 90f,
        sweepAngle = 180f,
        isSemicircle = true,
        isUpper = verticalPercent < 50,
        quadrant = if (isLeft) 3 else 1
    )
}

/**
 * 同心环分布：中心一个主项，外圈逐层向外排。
 *
 * @param baseRadius 中心项直径，同时也是第一圈的半径基准
 * @param itemDiameter 普通项直径
 * @param includeCenterItem 是否包含中心项
 */
fun distributeConcentricSlots(
    itemCount: Int,
    startAngle: Float,
    sweepAngle: Float = 90f,
    baseRadius: Float,
    itemDiameter: Float,
    itemWarpOffset: Float = 0f,
    mirror: Boolean = false,
    includeCenterItem: Boolean = true
): WheelDistribution {
    val positions = mutableListOf<RingItem>()
    val sizes = mutableListOf<Float>()
    val radii = mutableListOf<Float>()
    val ringIndices = mutableListOf<Int>()

    var remaining = itemCount

    if (includeCenterItem && remaining > 0) {
        positions.add(RingItem(-baseRadius / 2f, -baseRadius / 2f))
        sizes.add(baseRadius)
        ringIndices.add(0)
        remaining--
    }

    var ringRadius = baseRadius
    var reverse = false
    var ringNumber = 1

    while (remaining > 0) {
        val ring = WheelRing(
            startAngle = startAngle,
            sweepAngle = sweepAngle,
            radius = ringRadius,
            reverse = reverse,
            itemDiameter = itemDiameter,
            itemWarpOffset = itemWarpOffset
        )
        if (ring.items.isEmpty()) break

        if (mirror) ring.items.reverse()

        radii.add(ringRadius)
        val take = minOf(remaining, ring.items.size)
        for (i in 0 until take) {
            val item = ring.items[i]
            positions.add(RingItem(item.x - itemDiameter / 2f, item.y - itemDiameter / 2f))
            sizes.add(itemDiameter)
            ringIndices.add(ringNumber)
        }
        remaining -= take
        ringRadius += itemDiameter * 1.3f
        reverse = !reverse
        ringNumber++
    }

    return WheelDistribution(positions, sizes, radii, ringIndices)
}
