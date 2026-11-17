package io.relimus.zflow.xposed.ui.radial

/**
 * 入场的可测采样结果。
 *
 * 视图只负责把 [RadialEntryFrame] 画出来，曲线本身与 View 无关，
 * 因此可以在单测里逐帧断言（参照物：相邻帧必须单调、端点必须精确）。
 */
internal data class RadialEntryFrame(
    /** 整体不透明度 */
    val contentAlpha: Float,
    /** 扇形展开进度：0 = 收在角落，1 = 完全展开 */
    val sweepProgress: Float,
    /** 底板缩放（含极小的回弹） */
    val plateScale: Float,
    /** 图标缩放（含 2% 回弹） */
    val iconScale: Float,
)

internal data class RadialExitFrame(
    val contentAlpha: Float,
    val sweepProgress: Float,
    val plateScale: Float,
)

/**
 * 轮盘的入场 / 退场 / 错峰曲线。
 *
 * 与「用几个 ObjectAnimator 各自动 x/y」的做法相比，这里把整段动画压成
 * 一条 0..1 的进度曲线：所有视觉参数都是进度的纯函数，好处是
 * 1. 逐帧可测（见 RadialMotionTest）；
 * 2. 退场可以从当前帧接管，不需要读回 View 的中间态；
 * 3. 系统关闭动画时直接取 p = 1 即可，不需要额外的降级分支。
 */
internal object RadialMotion {

    const val ENTRY_DURATION_MS = 340L
    const val EXIT_DURATION_MS = 200L
    const val SELECTION_DURATION_MS = 120L

    /** 展开段占入场总时长的比例，剩余时间用于回弹收尾。 */
    private const val EXPAND_FRACTION = 0.42f

    private val fade = CubicMotionCurve(0.33f, 0f, 0.67f, 1f)

    /** 展开：先快后慢，手感上像扇子被甩开。 */
    private val expand = CubicMotionCurve(0.22f, 0.61f, 0.36f, 1f)

    /** 回弹：轻微过冲后回到 1。 */
    private val rebound = CubicMotionCurve(0.18f, 0.86f, 0.32f, 1f)

    private val exitCurve = CubicMotionCurve(0.4f, 0f, 0.7f, 0.42f)

    fun entry(linearProgress: Float): RadialEntryFrame {
        val progress = linearProgress.coerceIn(0f, 1f)
        if (progress <= EXPAND_FRACTION) {
            val phase = progress / EXPAND_FRACTION
            val expanded = expand.sample(phase)
            return RadialEntryFrame(
                contentAlpha = fade.sample(phase),
                sweepProgress = expanded,
                plateScale = 0.92f + 0.08f * expanded,
                iconScale = 0.74f + 0.30f * expanded,
            )
        }
        val phase = (progress - EXPAND_FRACTION) / (1f - EXPAND_FRACTION)
        val settle = rebound.sample(phase)
        return RadialEntryFrame(
            contentAlpha = 1f,
            sweepProgress = 1f,
            plateScale = 1.02f - 0.02f * settle,
            iconScale = 1.04f - 0.04f * settle,
        )
    }

    fun exit(linearProgress: Float): RadialExitFrame {
        val progress = linearProgress.coerceIn(0f, 1f)
        val collapse = exitCurve.sample(progress)
        val content = smoothStep((progress / 0.72f).coerceIn(0f, 1f))
        return RadialExitFrame(
            contentAlpha = 1f - content,
            sweepProgress = 1f - collapse,
            plateScale = 1f - 0.10f * collapse,
        )
    }

    fun selection(linearProgress: Float): Float =
        smoothStep(linearProgress.coerceIn(0f, 1f))

    /**
     * 单个图标的入场进度：按索引错峰，越靠外的图标越先出现。
     *
     * [baseProgress] 是整体入场进度；返回该图标自己的 0..1 进度。
     * 错峰总跨度不超过入场进度的 45%，否则最后一个图标会明显迟滞。
     */
    fun iconProgress(baseProgress: Float, index: Int, count: Int): Float {
        if (count <= 1) return baseProgress.coerceIn(0f, 1f)
        val safeIndex = index.coerceIn(0, count - 1)
        val span = (0.10f * count).coerceAtMost(0.45f)
        val delay = span * safeIndex / (count - 1)
        return ((baseProgress - delay) / (1f - span)).coerceIn(0f, 1f)
    }

    private fun smoothStep(value: Float): Float = value * value * (3f - 2f * value)
}

/**
 * 时间到取值的三次贝塞尔求值（同 CSS cubic-bezier）。
 *
 * 横轴（时间）夹在 0..1，纵轴保留过冲 —— 这是回弹手感的关键，
 * 用二分法反查参数 t，20 次迭代对 60fps 的精度完全够用。
 */
internal class CubicMotionCurve(
    private val x1: Float,
    private val y1: Float,
    private val x2: Float,
    private val y2: Float,
) {
    fun sample(linearProgress: Float): Float {
        val x = linearProgress.coerceIn(0f, 1f)
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        var low = 0f
        var high = 1f
        repeat(20) {
            val middle = (low + high) * 0.5f
            if (coordinate(middle, x1, x2) < x) low = middle else high = middle
        }
        return coordinate((low + high) * 0.5f, y1, y2)
    }

    private fun coordinate(parameter: Float, first: Float, second: Float): Float {
        val remainder = 1f - parameter
        return 3f * remainder * remainder * parameter * first +
            3f * remainder * parameter * parameter * second +
            parameter * parameter * parameter
    }
}
