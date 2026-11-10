package io.relimus.zflow.xposed.ui.radial

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * 轮盘的尺寸预算。全部为像素单位，由 [RadialLayoutSpec.fit] 按屏幕与密度算出。
 *
 * [iconDiameter] 是**白色圆盘**的直径，里面再按 [ICON_INSET_RATIO] 放图标。
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
    /** 环数：条目多时排成两环，而不是把单环拉到手臂够不到的地方。 */
    val rings: Int = 1,
    /** 相邻环的半径差。 */
    val ringStep: Float = 0f,
)

/**
 * 自适应尺寸计算。
 *
 * 视觉基线对齐 GestureEVO：**圆盘大、间隙均匀**。所有条目共用同一套尺寸，
 * 因此约束只有三条：
 *
 * 1. 同环相邻格子的弧长间距 >= 圆盘直径 + [ICON_GAP_DP]；
 * 2. 外环边缘（半径 + 半个圆盘 + 内边距）不超过 [MAX_RADIUS_DP]，
 *    也就是「从底角伸手能舒服够到」的范围；
 * 3. 整个扇形必须落在屏幕内，超了等比缩小。
 *
 * 第 2 条压不住时先缩小圆盘（而不是把半径推到屏幕另一头）：
 * 半径过大时手指要划过半个屏幕才能选中，手感会明显变差。
 *
 * 排布策略：
 * - <= [SINGLE_RING_MAX] 个：单弧，一圈到底；
 * - 6 个：内外两环**角度对齐**（2 列 x 3 行，沿弧线弯过去的网格），
 *   蜂窝错排会让相邻两环的圆心距离接近圆盘直径，看起来是糊在一起的一团。
 */
internal object RadialLayoutSpec {

    /** 圆盘理想直径，与 GestureEVO 的圆形按钮尺度对齐。 */
    const val BASE_ICON_DP = 56f

    /** 图标在圆盘里占的比例：留出白边，视觉上更像 GestureEVO 的圆形按钮。 */
    const val ICON_INSET_RATIO = 0.62f

    /** 外缘允许的最大半径（含半个圆盘与内边距）。 */
    private const val MAX_RADIUS_DP = 250f

    /** 单条弧能舒服排下的最大条目数，再往上排两环。 */
    private const val SINGLE_RING_MAX = 5

    /** 最近的一个圆盘离角落的距离，避免整组贴着导航条。 */
    private const val MIN_RADIUS_DP = 96f

    /** 圆盘下限：再小就看不清、也点不准。 */
    private const val MIN_ICON_DP = 34f

    private const val ICON_GAP_DP = 10f
    private const val OUTER_PADDING_DP = 8f
    private const val SCREEN_PADDING_DP = 6f
    /**
     * 扇形圆心相对屏幕角落的内缩（dp）。
     *
     * 手势判定用的探针布局必须用同一个值：探针只借 layout 的圆心算极角，
     * 圆心若与真实布局差几十像素，同一个点上算出的角度会差好几度，
     * 底角横滑就有机会混进「在扇形内」。
     */
    internal const val CORNER_INSET_DP = 8f

    /** 容量上限：6 格按 2x3 排布，再往上加就只能靠缩圆盘，观感会崩。 */
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

        val iconDp = iconSizeDp.coerceIn(MIN_ICON_DP, 72f)
        val gap = ICON_GAP_DP * densitySafe
        val outerPadding = OUTER_PADDING_DP * densitySafe
        val screenPadding = SCREEN_PADDING_DP * densitySafe
        val inset = CORNER_INSET_DP * densitySafe

        val safeSweep = sweepDegrees.takeIf { it.isFinite() && it > 0f }
            ?.coerceIn(30f, 170f) ?: RadialGeometry.DEFAULT_SWEEP_DEGREES

        val rings = if (count > SINGLE_RING_MAX) 2 else 1
        val shares = RadialGeometry.ringShares(count, rings)
        // 与 RadialGeometry.layout 用同一份留角，否则算出来的半径会略微偏小。
        val safe = SAFE_DEGREES.coerceAtMost(safeSweep / 4f)

        /*
         * 内环半径的下限 = 各环「自己排得下」所需半径里最大的那个。
         * 外环半径 = 内环 + step，所以外环的需求要减去 step 再比较。
         */
        fun innerRadiusFor(diameter: Float): Float {
            val step = diameter + gap
            var need = 0f
            for (ring in shares.indices) {
                val perRing = shares[ring]
                if (perRing <= 0) continue
                val slotRadians = (safeSweep - safe * 2f) / perRing *
                    PI.toFloat() / 180f
                val chord = 2f * sin(slotRadians / 2f)
                if (chord <= 0.0001f) continue
                val ringNeed = (diameter + gap) / chord - ring * step
                need = max(need, ringNeed)
            }
            return max(need, MIN_RADIUS_DP * densitySafe)
        }

        val maxRadius = MAX_RADIUS_DP * densitySafe
        val minIcon = MIN_ICON_DP * densitySafe

        var diameter = iconDp * densitySafe
        var radius = 0f
        var step = 0f
        while (true) {
            step = diameter + gap
            radius = innerRadiusFor(diameter)
            val outerEdge = radius + (rings - 1) * step + diameter / 2f + outerPadding
            if (outerEdge <= maxRadius + 0.5f || diameter <= minIcon) break
            diameter -= 2f * densitySafe
        }
        diameter = diameter.coerceAtLeast(minIcon)

        // 圆心到最远圆盘外缘的距离必须落在屏幕内。
        val allowed = minOf(width, height) - screenPadding * 2f - inset
        val needed = radius + (rings - 1) * step + diameter / 2f + outerPadding
        var fitScale = 1f
        if (needed > allowed && allowed > 0f) {
            fitScale = (allowed / needed).coerceIn(0.55f, 1f)
            radius *= fitScale
            step *= fitScale
            diameter *= fitScale
        }

        val outerRadius = radius + (rings - 1) * step + diameter / 2f + outerPadding
        /*
         * 命中半径按半径比例给，而不是只按圆盘大小：
         * 手指从底角划出约三分之一半径就开始跟手选中，靠近角落则取消。
         */
        val enter = hitRadiusDp
            ?.takeIf { it.isFinite() && it > 0f }
            ?.let { it * densitySafe }
            ?: max(diameter * 1.4f, radius * 0.42f)
        // 迟滞半径取 0.62 倍圆盘直径：边界抖动会回落到原格，
        // 明确移动到邻格中心附近才切换。
        val keep = diameter * 0.62f

        return RadialMetrics(
            radius = radius,
            iconDiameter = diameter,
            outerRadius = outerRadius,
            enterRadius = enter,
            keepRadius = keep,
            originInsetX = inset,
            originInsetY = inset,
            fitScale = fitScale,
            rings = rings,
            ringStep = step,
        )
    }

    /** 与 [RadialGeometry] 保持一致的屏幕边缘留角，尺寸计算里要用同一份。 */
    private const val SAFE_DEGREES = 3f
}
