package io.relimus.zflow.xposed.ui.radial

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 轮盘的尺寸预算。全部为像素单位，由 [RadialLayoutSpec.fit] 按屏幕与密度算出。
 *
 * [iconDiameter] 是**白色圆盘**的直径，里面再按 [ICON_INSET_RATIO] 放图标。
 * [shares] 是每环的格子数（下标 0 = 最内环），绘制必须用它而不是重新分配，
 * 否则格数与半径对不上，环内就会挤在一起。
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
    /** 环数：条目多时逐层往外排，而不是把单环拉到手臂够不到的地方。 */
    val rings: Int = 1,
    /** 相邻环的半径差。 */
    val ringStep: Float = 0f,
    /** 每环格子数，内 → 外。 */
    val shares: List<Int> = emptyList(),
)

/**
 * 自适应尺寸计算。
 *
 * 视觉基线对齐 GestureEVO：**圆盘大、间隙均匀、逐层往外铺开**。
 *
 * 排布策略是「先决定每环放几个，再反推半径」：
 *
 * 1. 环数按条目数取 1 / 2 / 3（<=3 单弧、4~6 两环、7~13 三环）；
 * 2. 枚举所有「内少外多」的格数组合（如 13 个的 3+4+6），
 *    对每种组合算出满足「同环弦长 >= 圆盘 + 间隙」所需的最小内环半径，
 *    取最紧凑的那一种 —— 13 个自然收敛到 3+4+6，正是 GestureEVO 的三层观感；
 * 3. 外缘超过屏幕可用范围时先缩圆盘，缩到下限仍放不下才等比缩放。
 *
 * 第 2 步刻意不用「容量整除」的贪心：贪心在 4 个时会给出一环 1 个、
 * 二环 3 个的细长布局，内环孤零零一个圆看起来像画错了。
 */
internal object RadialLayoutSpec {

    /** 圆盘理想直径，与 GestureEVO 的圆形按钮尺度对齐。 */
    const val BASE_ICON_DP = 56f

    /** 图标在圆盘里占的比例：留出白边，视觉上更像 GestureEVO 的圆形按钮。 */
    const val ICON_INSET_RATIO = 0.62f

    /**
     * 允许的最大圆盘。三个环要靠「圆盘 + 间隙」当步长往外推，
     * 大圆盘 + 三环在手机上会直接顶到屏幕对角，因此封顶 72dp。
     */
    private const val MAX_ICON_DP = 72f

    /** 圆盘下限：再小就看不清、也点不准。 */
    private const val MIN_ICON_DP = 34f

    private const val ICON_GAP_DP = 10f
    private const val OUTER_PADDING_DP = 8f
    private const val SCREEN_PADDING_DP = 6f

    /**
     * 内环半径下限：轮盘要「浮」在触发点上方一段距离，而不是挤在手指边上。
     *
     * 取 108dp 是实机观感的分界：再小，第一个圆盘会贴着屏幕边缘，
     * 手指刚动就已经压在图标上，既看不清也容易误选。
     */
    private const val MIN_INNER_RADIUS_DP = 108f

    /**
     * 圆心相对屏幕角落的内缩（dp）。
     *
     * 手势判定用的探针布局必须用同一个值：探针只借 layout 的圆心算极角，
     * 圆心若与真实布局差几十像素，同一个点上算出的角度会差好几度，
     * 底角横滑就有机会混进「在扇形内」。
     */
    internal const val CORNER_INSET_DP = 8f

    /** 单弧能舒服排下的最大条目数，再往上分两环。 */
    private const val SINGLE_RING_MAX = 3

    /** 两环能舒服排下的最大条目数，再往上分三环。 */
    private const val DOUBLE_RING_MAX = 6

    /** 容量上限：三环 3+4+6 共 13 个。 */
    const val MAX_ITEMS = 13
    const val MIN_ITEMS = 1

    /** 与 [RadialGeometry] 保持一致的屏幕边缘留角，尺寸计算里要用同一份。 */
    private const val SAFE_DEGREES = 3f

    /** 条目数对应的环数。 */
    fun ringsFor(count: Int): Int = when {
        count <= SINGLE_RING_MAX -> 1
        count <= DOUBLE_RING_MAX -> 2
        else -> 3
    }

    private class Solution(
        val radius: Float,
        val step: Float,
        val shares: IntArray,
        val edge: Float,
    )

    fun fit(
        widthPx: Int,
        heightPx: Int,
        density: Float,
        itemCount: Int,
        iconSizeDp: Float = BASE_ICON_DP,
        sweepDegrees: Float = RadialGeometry.DEFAULT_SWEEP_DEGREES,
        hitRadiusDp: Float? = null,
        /** 触发点相对屏幕底边的抬升（px），圆心跟着一起上移。 */
        triggerOffsetYPx: Float = 0f,
    ): RadialMetrics {
        val densitySafe = density.takeIf { it.isFinite() && it > 0f } ?: 1f
        val count = itemCount.coerceIn(MIN_ITEMS, MAX_ITEMS)
        val width = widthPx.coerceAtLeast(1).toFloat()
        val height = heightPx.coerceAtLeast(1).toFloat()

        val iconDp = iconSizeDp.coerceIn(MIN_ICON_DP, MAX_ICON_DP)
        val gap = ICON_GAP_DP * densitySafe
        val outerPadding = OUTER_PADDING_DP * densitySafe
        val screenPadding = SCREEN_PADDING_DP * densitySafe
        val inset = CORNER_INSET_DP * densitySafe
        val triggerOffset = triggerOffsetYPx.takeIf { it.isFinite() && it > 0f } ?: 0f

        val safeSweep = sweepDegrees.takeIf { it.isFinite() && it > 0f }
            ?.coerceIn(30f, 170f) ?: RadialGeometry.DEFAULT_SWEEP_DEGREES
        val safe = SAFE_DEGREES.coerceAtMost(safeSweep / 4f)
        // 各环共用的可用张角（弧度），弦长约束都基于它。
        val usable = (safeSweep - safe * 2f) * PI.toFloat() / 180f

        val rings = ringsFor(count)
        val minPerRing = if (count >= rings * 2) 2 else 1
        val candidates = candidateShares(count, rings, minPerRing)
        val minInnerBase = MIN_INNER_RADIUS_DP * densitySafe

        /** 给定圆盘直径，求最紧凑且放得下的排布；返回 null 表示一个组合都不成立。 */
        fun solve(diameter: Float): Solution? {
            val step = diameter + gap
            val minInner = max(minInnerBase, step * 1.05f)
            var best: Solution? = null
            for (shares in candidates) {
                // 每种组合所需的内环半径：各环需求减去自己已经外移的距离。
                var need = minInner
                for (ring in shares.indices) {
                    val perRing = shares[ring]
                    if (perRing <= 1) continue
                    val required = step / (2f * sin(usable / (2f * perRing)))
                    need = max(need, required - ring * step)
                }
                val edge = need + (rings - 1) * step + diameter / 2f + outerPadding
                if (best == null || need < best.radius - 0.5f) {
                    best = Solution(need, step, shares, edge)
                }
            }
            return best
        }

        // 圆心到最远圆盘外缘的距离必须落在屏幕内（触发点上移后向上空间会变小）。
        val allowedX = width - inset - screenPadding
        val allowedY = height - inset - triggerOffset - screenPadding
        val allowed = min(allowedX, allowedY)

        val minIcon = MIN_ICON_DP * densitySafe
        var diameter = iconDp * densitySafe
        var solution = solve(diameter)
        while (solution != null && solution.edge > allowed + 0.5f && diameter > minIcon) {
            diameter -= 2f * densitySafe
            solution = solve(diameter)
        }
        var radius = solution?.radius ?: minInnerBase
        var step = solution?.step ?: (diameter + gap)
        var shares = solution?.shares ?: IntArray(rings) { count / rings }
        var edge = solution?.edge ?: (radius + (rings - 1) * step + diameter / 2f + outerPadding)

        var fitScale = 1f
        if (edge > allowed && allowed > 0f) {
            fitScale = (allowed / edge).coerceIn(0.5f, 1f)
            radius *= fitScale
            step *= fitScale
            diameter *= fitScale
            edge *= fitScale
        }

        /*
         * 命中半径按半径比例给，而不是只按圆盘大小：
         * 三环时内环与触发点之间隔着一整圈，手指要划过一段才开始跟手选中。
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
            outerRadius = edge,
            enterRadius = enter,
            keepRadius = keep,
            originInsetX = inset,
            originInsetY = inset + triggerOffset,
            fitScale = fitScale,
            rings = rings,
            ringStep = step,
            shares = shares.toList(),
        )
    }

    /**
     * 所有「内少外多」的格数组合。
     *
     * 只用内环 <= 中环 <= 外环这一条约束：反过来的组合（内环塞满、外环空着）
     * 在视觉上是反的，算出来的半径也不会更小。
     */
    private fun candidateShares(count: Int, rings: Int, minPerRing: Int): List<IntArray> {
        val result = ArrayList<IntArray>()
        val current = IntArray(rings)
        fun walk(ring: Int, remaining: Int, previous: Int) {
            if (ring == rings - 1) {
                if (remaining < previous || remaining < minPerRing) return
                current[ring] = remaining
                result.add(current.copyOf())
                return
            }
            val tail = rings - ring - 1
            val lower = max(previous, minPerRing)
            val upper = remaining - tail * lower
            for (value in lower..upper) {
                current[ring] = value
                walk(ring + 1, remaining - value, value)
            }
        }
        walk(0, count, minPerRing)
        // 极端情况下（比如 7 个三环且 minPerRing=2）组合表可能为空，退化为均分。
        if (result.isEmpty()) {
            val base = count / rings
            result.add(IntArray(rings) { if (it == rings - 1) count - base * (rings - 1) else base })
        }
        return result
    }
}
