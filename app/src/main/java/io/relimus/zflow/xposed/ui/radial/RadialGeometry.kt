package io.relimus.zflow.xposed.ui.radial

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** 轮盘锚定的底角；决定扇形朝向与槽位角度基准。 */
enum class RadialCorner {
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
}

internal data class RadialPoint(val x: Float, val y: Float)

/**
 * 一次布局的结果。
 *
 * 纯数据，不含 Canvas / View：绘制、命中、动画都以它为准，
 * 因此几何可以在 JVM 单测里完整覆盖，不需要跑设备。
 *
 * [itemCenters] 与业务列表一一对应（索引 0 是最外侧、最靠近竖直线的那格），
 * [itemAngles] 是每个槽位的中心角（数学角，y 轴向上为正，单位度）。
 */
internal data class RadialLayout(
    val corner: RadialCorner,
    val origin: RadialPoint,
    val radius: Float,
    val sweepDegrees: Float,
    val itemCenters: List<RadialPoint>,
    val itemAngles: List<Float>,
) {
    val isEmpty: Boolean
        get() = itemCenters.isEmpty()
}

/**
 * 底角扇形布局与命中判定。
 *
 * 设计取舍（相对于常见的「逐项矩形包含」实现）：
 * - 布局只依赖圆心、半径、角度，条目数只改变角度不改变半径，增删条目不跳动；
 * - 命中采用「最近格心 + 进入半径」而不是「点是否落在图标矩形内」：
 *   圆形图标与方形判定框不匹配，四角会出现假命中、格间缝隙会丢选，
 *   最近格心判定天然无缝，且手指滑出弧线外仍然可以继续选；
 * - 提供 [RadialLayout] 之外的迟滞半径（keepRadius）给调用方，
 *   需要在相邻两格边界稳定时传入，不需要时传 0 即为「离开即换格」。
 */
internal object RadialGeometry {

    /** 扇形总张角。90° 会让最外侧一格贴住竖直线，留 6° 呼吸空间。 */
    const val DEFAULT_SWEEP_DEGREES = 84f

    /** 与屏幕边缘保持的最小角度，避免贴边格子被圆角/导航条裁掉。 */
    private const val SAFE_DEGREES = 3f

    /**
     * 生成扇形布局。
     *
     * @param corner 锚定角，决定扇形朝上还是朝左上
     * @param width 可用宽度（px），仅用于夹取圆心，保证不越界
     * @param height 可用高度（px），同上
     * @param radius 图标中心到角落圆心的距离（px）
     * @param itemCount 条目数；<= 0 时返回空布局而不是抛异常
     * @param sweepDegrees 扇形张角，调用方可以按条目数收窄
     * @param originInsetX 圆心相对屏幕角落的水平内缩（避开圆角）
     * @param originInsetY 圆心垂直内缩（避开手势条）
     */
    fun layout(
        corner: RadialCorner,
        width: Float,
        height: Float,
        radius: Float,
        itemCount: Int,
        sweepDegrees: Float = DEFAULT_SWEEP_DEGREES,
        originInsetX: Float = 0f,
        originInsetY: Float = 0f,
    ): RadialLayout {
        val safeWidth = width.takeIf { it.isFinite() && it > 0f } ?: 0f
        val safeHeight = height.takeIf { it.isFinite() && it > 0f } ?: 0f
        val insetX = originInsetX.takeIf { it.isFinite() } ?: 0f
        val insetY = originInsetY.takeIf { it.isFinite() } ?: 0f
        val origin = RadialPoint(
            x = if (corner == RadialCorner.BOTTOM_LEFT) insetX else safeWidth - insetX,
            y = safeHeight - insetY,
        )
        val sweep = sweepDegrees.takeIf { it.isFinite() && it > 0f }?.coerceAtMost(170f)
            ?: DEFAULT_SWEEP_DEGREES
        if (itemCount <= 0 || !radius.isFinite() || radius <= 0f) {
            return RadialLayout(corner, origin, radius.coerceAtLeast(0f), sweep, emptyList(), emptyList())
        }

        val safe = SAFE_DEGREES.coerceAtMost(sweep / 4f)
        val step = (sweep - safe * 2f) / itemCount
        val centers = ArrayList<RadialPoint>(itemCount)
        val angles = ArrayList<Float>(itemCount)

        for (index in 0 until itemCount) {
            // 索引 0 排在最外侧（最接近竖直线），与设置页的列表顺序一致。
            val slot = itemCount - 1 - index
            val angle = if (corner == RadialCorner.BOTTOM_LEFT) {
                -safe - (slot + 0.5f) * step
            } else {
                -180f + safe + (slot + 0.5f) * step
            }
            angles.add(angle)
            val radians = angle * PI.toFloat() / 180f
            centers.add(
                RadialPoint(
                    x = origin.x + cos(radians) * radius,
                    y = origin.y + sin(radians) * radius,
                )
            )
        }
        return RadialLayout(corner, origin, radius, sweep, centers, angles)
    }

    /**
     * 命中判定：返回被抓取的条目索引，未命中返回 null。
     *
     * 规则：
     * 1. [previous] 仍落在 [keepRadius] 内时保持不变（迟滞，防止边界抖动）；
     * 2. 否则取距离最近的一格，且必须落在 [enterRadius] 内才算命中；
     * 3. 不做「弧线收边」，手指偏离弧线也能接着选。
     *
     * 半径 <= 0 表示不做阈值判断（只在有迟滞需求时才需要 enterRadius）。
     */
    fun selection(
        layout: RadialLayout,
        x: Float,
        y: Float,
        previous: Int? = null,
        enterRadius: Float = 0f,
        keepRadius: Float = 0f,
    ): Int? {
        if (layout.isEmpty) return null
        if (!x.isFinite() || !y.isFinite()) return null
        if (previous != null && previous in layout.itemCenters.indices && keepRadius > 0f) {
            val center = layout.itemCenters[previous]
            if (hypot(x - center.x, y - center.y) <= keepRadius) return previous
        }
        var bestIndex = -1
        var bestDistance = Float.MAX_VALUE
        layout.itemCenters.forEachIndexed { index, center ->
            val distance = hypot(x - center.x, y - center.y)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        if (bestIndex < 0) return null
        return if (enterRadius > 0f && bestDistance > enterRadius) null else bestIndex
    }

    /** 手指相对圆心的极角（度），用于判断手势方向是否在扇形张开的一侧。 */
    fun polarDegrees(layout: RadialLayout, x: Float, y: Float): Float {
        if (layout.radius <= 0f || !x.isFinite() || !y.isFinite()) return Float.NaN
        return atan2(y - layout.origin.y, x - layout.origin.x) * 180f / PI.toFloat()
    }

    /** 手势是否落在扇形允许的角度范围内（含少量容差）。 */
    fun isWithinSweep(layout: RadialLayout, x: Float, y: Float, toleranceDegrees: Float = 8f): Boolean {
        val angle = polarDegrees(layout, x, y)
        if (angle.isNaN()) return false
        // 左角扇形角度落在 [-90-ε, 0+ε]；右角落在 [-180-ε, -90+ε]。
        return if (layout.corner == RadialCorner.BOTTOM_LEFT) {
            angle in (-90f - toleranceDegrees)..(0f + toleranceDegrees)
        } else {
            angle in (-180f - toleranceDegrees)..(-90f + toleranceDegrees)
        }
    }
}
