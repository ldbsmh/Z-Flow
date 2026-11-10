package io.relimus.zflow.xposed.ui.radial

import kotlin.math.min
import kotlin.math.sin
import kotlin.math.PI

/**
 * 轮盘的尺寸预算。全部为像素单位，由 [RadialLayoutSpec.fit] 按屏幕与密度算出。
 */
internal data class RadialMetrics(
    val radius: Float,
    val iconDiameter: Float,
    val outerRadius: Float,
    val enterRadius: Float,
    val keepRadius: Float,
    val originInsetX: Float,
    val originInsetY: Float,
    val fitScale: Float,
)

/**
 * 自适应尺寸计算。
 *
 * 相对「半径写死、图标变大就互相压住」的做法，这里显式保证两件事：
 * 1. 相邻图标的弧长间距 >= 图标直径 + 间隙，条目多时自动外推半径；
 * 2. 整个扇形（半径 + 半个图标 + 内边距）必须落在屏幕内，超了就等比缩。
 * 两个约束冲突时先保屏幕内，宁可略微压缩间距也不让图标出屏。
 */
internal object RadialLayoutSpec {

    const val BASE_ICON_DP = 46f
    const val BASE_RADIUS_DP = 168f
    private const val ICON_GAP_DP = 6f
    private const val OUTER_PADDING_DP = 8f
    private const val SCREEN_PADDING_DP = 6f
    private const val CORNER_INSET_DP = 8f

    /** 容量上限：超过 6 格在 84° 上会挤成一团，且操作距离过远。 */
    const val MAX_ITEMS = 6
    const val MIN_ITEMS = 1

    fun fit(
        widthPx: Int,
        heightPx: Int,
        density: Float,
        itemCount: Int,
        iconSizeDp: Float = BASE_ICON_DP,
        sweepDegrees: Float = RadialGeometry.DEFAULT_SWEEP_DEGREES,
        hitRadiusDp: Float? = null,
    ): RadialMetrics {
        val densitySafe = density.takeIf { it.isFinite() && it > 0f } ?: 1f
        val count = itemCount.coerceIn(MIN_ITEMS, MAX_ITEMS)
        val width = widthPx.coerceAtLeast(1).toFloat()
        val height = heightPx.coerceAtLeast(1).toFloat()

        val iconDp = iconSizeDp.coerceIn(32f, 72f)
        val icon = iconDp * densitySafe
        val gap = ICON_GAP_DP * densitySafe
        val outerPadding = OUTER_PADDING_DP * densitySafe
        val screenPadding = SCREEN_PADDING_DP * densitySafe
        val inset = CORNER_INSET_DP * densitySafe

        val safeSweep = sweepDegrees.takeIf { it.isFinite() && it > 0f }
            ?.coerceIn(30f, 170f) ?: RadialGeometry.DEFAULT_SWEEP_DEGREES
        val stepRadians = (safeSweep - 6f) / count * PI.toFloat() / 180f
        // 弦长 = 2R·sin(θ/2)，反过来由目标弦长求最小半径。
        val minRadiusForSpacing =
            if (stepRadians > 0.0001f) (icon + gap) / (2f * sin(stepRadians / 2f)) else Float.MAX_VALUE

        val desiredRadius = BASE_RADIUS_DP * densitySafe
        var radius = maxOf(desiredRadius, minRadiusForSpacing)
        var diameter = icon

        // 圆心到最远图标外缘的距离必须落在屏幕内。
        val allowed = min(width, height) - screenPadding * 2f - inset
        val needed = radius + diameter / 2f + outerPadding
        var fitScale = 1f
        if (needed > allowed && allowed > 0f) {
            fitScale = (allowed / needed).coerceIn(0.55f, 1f)
            radius *= fitScale
            diameter *= fitScale
        }

        val enter = hitRadiusDp
            ?.takeIf { it.isFinite() && it > 0f }
            ?.let { it * densitySafe }
            ?: maxOf(diameter * 1.75f, gap * 3f)
        // 迟滞半径取 0.62 倍图标直径：略大于「两格中线」，
        // 边界抖动会回落到原格，明确移动到邻格中心附近才切换。
        val keep = diameter * 0.62f

        return RadialMetrics(
            radius = radius,
            iconDiameter = diameter,
            outerRadius = radius + diameter / 2f + outerPadding,
            enterRadius = enter,
            keepRadius = keep,
            originInsetX = inset,
            originInsetY = inset,
            fitScale = fitScale,
        )
    }
}
