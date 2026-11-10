package io.relimus.zflow.xposed.ui.radial

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * 轮盘条目。[icon] 已经是按 [RadialMetrics.iconDiameter] 预渲染好的圆形位图。
 */
internal data class RadialItem(
    val packageName: String,
    val userId: Int,
    val label: String,
    val icon: Bitmap?,
)

/**
 * 底角扇形轮盘。
 *
 * 关键取舍：整个轮盘是一个 View、一次 Canvas 绘制，没有子 View。
 * 相对「每个图标一个 View + 各自动属性动画」的做法，这里
 * 1. 没有 measure/layout 开销，每帧只剩绘制本身；
 * 2. 动画由一条进度曲线驱动，退场可以直接从当前进度接管；
 * 3. 命中判定用纯几何（[RadialGeometry.selection]），不依赖 View 的矩形边界。
 *
 * 本 View **不消费触摸事件**：坐标由外部（Launcher 输入流）推入，
 * 因此它可以安全地叠在 Launcher 视图树最上层而不影响手势分发。
 */
internal class RadialMenuView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val platePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val sectorPath = Path()
    private val arcBounds = RectF()
    private val iconDestination = RectF()

    private var layout: RadialLayout? = null
    private var items: List<RadialItem> = emptyList()

    private var iconRadius = 0f
    private var outerRadius = 0f
    private var enterRadius = 0f
    private var keepRadius = 0f

    private var entryProgress = 0f
    private var exitProgress = 0f
    private var selectionProgress = 0f

    private var motionAnimator: ValueAnimator? = null
    private var selectionAnimator: ValueAnimator? = null

    var selectedIndex: Int? = null
        private set

    /** 选中项变化回调，参数为索引（null 表示离开所有格子）。 */
    var onSelectionChanged: ((Int?) -> Unit)? = null

    init {
        platePaint.color = Color.argb(72, 24, 24, 26)
        cellPaint.color = Color.argb(38, 255, 255, 255)
        hubPaint.color = Color.argb(90, 255, 255, 255)
        ringPaint.color = Color.argb(235, 255, 255, 255)
        ringPaint.strokeWidth = 2.5f * density
    }

    val currentSelection: Int?
        get() = selectedIndex

    fun configure(
        layout: RadialLayout,
        metrics: RadialMetrics,
        items: List<RadialItem>,
    ) {
        this.layout = layout
        this.items = items
        entryProgress = 0f
        exitProgress = 0f
        selectionProgress = 0f
        selectedIndex = null
        iconRadius = metrics.iconDiameter / 2f
        outerRadius = metrics.outerRadius
        enterRadius = metrics.enterRadius
        keepRadius = metrics.keepRadius
        invalidate()
    }

    fun animateIn() {
        stopMotion()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            entryProgress = 1f
            exitProgress = 0f
            invalidate()
            return
        }
        exitProgress = 0f
        motionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = RadialMotion.ENTRY_DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                entryProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun animateOut(onFinished: (() -> Unit)? = null) {
        stopMotion()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            exitProgress = 1f
            invalidate()
            onFinished?.invoke()
            return
        }
        val start = exitProgress
        motionAnimator = ValueAnimator.ofFloat(start, 1f).apply {
            duration = (RadialMotion.EXIT_DURATION_MS * (1f - start)).toLong().coerceAtLeast(80L)
            interpolator = LinearInterpolator()
            addUpdateListener {
                exitProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onFinished?.invoke()
                }
            })
            start()
        }
    }

    /** 推入手指坐标（与 View 同一坐标系，调用方负责换算）。 */
    fun updatePointer(x: Float, y: Float) {
        val currentLayout = layout ?: return
        if (entryProgress <= 0f) return
        val next = RadialGeometry.selection(
            layout = currentLayout,
            x = x,
            y = y,
            previous = selectedIndex,
            enterRadius = enterRadius,
            keepRadius = keepRadius,
        )
        if (next == selectedIndex) return
        selectedIndex = next
        onSelectionChanged?.invoke(next)
        if (next == null) {
            selectionProgress = 0f
            invalidate()
            return
        }
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        animateSelection()
    }

    private fun animateSelection() {
        selectionAnimator?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            selectionProgress = 1f
            invalidate()
            return
        }
        selectionProgress = 0f
        selectionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = RadialMotion.SELECTION_DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                selectionProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopMotion() {
        motionAnimator?.cancel()
        motionAnimator = null
        selectionAnimator?.cancel()
        selectionAnimator = null
    }

    override fun onDetachedFromWindow() {
        stopMotion()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val currentLayout = layout ?: return
        if (currentLayout.isEmpty || items.isEmpty()) return

        val entry = RadialMotion.entry(entryProgress)
        val exit = if (exitProgress > 0f) RadialMotion.exit(exitProgress) else null
        val contentAlpha = (entry.contentAlpha * (exit?.contentAlpha ?: 1f)).coerceIn(0f, 1f)
        if (contentAlpha <= 0.01f) return
        val sweep = (entry.sweepProgress * (exit?.sweepProgress ?: 1f)).coerceIn(0f, 1f)
        if (sweep <= 0.001f) return
        val plateScale = entry.plateScale * (exit?.plateScale ?: 1f)
        val iconScale = entry.iconScale

        canvas.drawColor(alphaOf(Color.argb(26, 0, 0, 0), contentAlpha))

        val origin = currentLayout.origin
        val baseAngle = if (currentLayout.corner == RadialCorner.BOTTOM_LEFT) 0f else -180f
        val outer = outerRadius * plateScale
        val startAngle = baseAngle + (currentLayout.itemAngles.last() - baseAngle) * sweep
        val sweepAngle = (currentLayout.itemAngles.first() - currentLayout.itemAngles.last()) * sweep

        sectorPath.reset()
        arcBounds.set(origin.x - outer, origin.y - outer, origin.x + outer, origin.y + outer)
        sectorPath.moveTo(origin.x, origin.y)
        sectorPath.arcTo(arcBounds, startAngle, sweepAngle, false)
        sectorPath.close()
        platePaint.alpha = scaledAlpha(platePaint.color, contentAlpha)
        canvas.drawPath(sectorPath, platePaint)

        // 圆心锚点：让扇形看起来是从角落「长」出来的
        hubPaint.alpha = scaledAlpha(hubPaint.color, contentAlpha)
        canvas.drawCircle(origin.x, origin.y, 5f * density * plateScale, hubPaint)

        val count = items.size
        for (index in items.indices) {
            val angle = baseAngle + (currentLayout.itemAngles[index] - baseAngle) * sweep
            val radians = angle * Math.PI.toFloat() / 180f
            val centerX = origin.x + cos(radians) * currentLayout.radius * plateScale
            val centerY = origin.y + sin(radians) * currentLayout.radius * plateScale

            val stagger = RadialMotion.iconProgress(entryProgress, index, count)
            val alpha = (contentAlpha * stagger * 255f).toInt().coerceIn(0, 255)
            if (alpha <= 4) continue

            val isSelected = selectedIndex == index
            val scale = iconScale * (if (isSelected) 1f + 0.08f * selectionProgress else 1f)
            val radius = iconRadius * scale

            if (isSelected) {
                cellPaint.alpha = scaledAlpha(cellPaint.color, contentAlpha)
                canvas.drawCircle(centerX, centerY, radius + 8f * density, cellPaint)
                ringPaint.alpha = scaledAlpha(ringPaint.color, contentAlpha * (0.5f + 0.5f * selectionProgress))
                canvas.drawCircle(centerX, centerY, radius + 5f * density, ringPaint)
            }

            val icon = items[index].icon
            if (icon != null && !icon.isRecycled) {
                iconDestination.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
                iconPaint.alpha = alpha
                canvas.drawBitmap(icon, null, iconDestination, iconPaint)
            } else {
                cellPaint.alpha = alpha
                canvas.drawCircle(centerX, centerY, radius, cellPaint)
            }
        }
    }

    private fun scaledAlpha(color: Int, factor: Float): Int =
        (Color.alpha(color) * factor.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)

    private fun alphaOf(color: Int, factor: Float): Int {
        val scaled = scaledAlpha(color, factor)
        return Color.argb(scaled, Color.red(color), Color.green(color), Color.blue(color))
    }
}
