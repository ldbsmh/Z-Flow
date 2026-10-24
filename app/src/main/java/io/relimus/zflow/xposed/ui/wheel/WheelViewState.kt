/*
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Ported from GestureEVO (https://github.com/LumiaGG/GestureEVO),
 * original file: gesture/config/ActionViewState.kt
 * Copyright (C) LumiaGG and GestureEVO contributors
 * Modifications for Z-Flow, Copyright (C) ldbsmh
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3 of the License, or (at your option) any later version.
 */

package io.relimus.zflow.xposed.ui.wheel

/**
 * 轮盘的角度状态：起始角与张角决定轮盘是四分之一盘、半圆盘还是整圆。
 *
 * 象限编号沿用 GestureEVO 的约定（右手坐标系，y 轴向下）：
 * ```
 *   3 | 0        (startAngle 0° 指向 +x)
 *   --+--
 *   2 | 1
 * ```
 * 即单个象限 → 90° 角盘；相邻两个 → 180° 半圆；四象限全开 → 360° 整圆。
 */
class WheelViewState {
    var startAngle: Float = 0f
    var sweepAngle: Float = 90f
    var centerX: Int = 0
    var centerY: Int = 0
    var mirror: Boolean = false

    fun setAngleByQuadrant(quadrant: List<Boolean>) {
        when (quadrant.count { it }) {
            4 -> {
                startAngle = 0f
                sweepAngle = 360f
            }

            2 -> {
                startAngle = when {
                    quadrant[0] && quadrant[1] -> 0f
                    quadrant[1] && quadrant[2] -> 90f
                    quadrant[2] && quadrant[3] -> 180f
                    quadrant[0] && quadrant[3] -> 270f
                    else -> 90f * quadrant.indexOf(true)
                }
                sweepAngle = 180f
            }

            1 -> {
                // 单象限即「角落」形态：90° 角盘。
                startAngle = 90f * quadrant.indexOf(true)
                sweepAngle = 90f
            }

            3 -> {
                startAngle = 90f * ((quadrant.indexOf(false) + 1) % 4)
                sweepAngle = 270f
            }

            else -> {
                startAngle = 0f
                sweepAngle = 0f
            }
        }
    }
}
