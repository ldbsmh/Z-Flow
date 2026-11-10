package io.relimus.zflow.xposed.ui.radial

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * 轮盘条目。[icon] 已经是按圆盘内径预渲染好的圆形位图。
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
 * 视觉参照 GestureEVO：**没有扇形底板**，每个应用是一枚独立的白色圆形按钮，
 * 带柔和投影，按环错排。这样既不遮壁纸，条目多了也不会糊成一片。
 *
 * 关键取舍：整个轮盘是一个 View、一次 Canvas 绘制，没有子 View。
 * 1. 没有 measure/layout 开销，每帧只剩绘制本身；
 * 2. 动画由一条进度曲线驱动，退场可以直接从当前进度接管；
 * 3. 命中判定用纯几何（[RadialGeometry.selection]），不依赖 View 的矩形边界。
 *
 * 本 View **不消费触摸事件**：坐标由外部（Launcher 输入流）推入，
 * 因此它可以安全地放在悬浮窗里而不影响手势分发。
 */
internal class RadialMenuView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density

    /** 白色圆盘：不透明纯白，和 GestureEVO 的圆形按钮一致（半透明白在彩壁上会发灰）。 */
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    /** 圆盘下方的柔和投影（硬件加速下 setShadowLayer 不可靠，这里手绘一层） */
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, 0, 0, 0) }

    /**
     * 圆盘描边。
     *
     * 悬浮窗在部分 ROM 上被系统压到 80% 不透明度，浅色界面（聊天列表、白底设置页）
     * 里白圆盘和白底会糊成一片，加一圈极浅的描边才能看出边界。
     */
    private val chipStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = Color.argb(38, 0, 0, 0)
    }

    /** 选中光圈 */
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(255, 90, 160, 255)
        strokeWidth = 3f * density
    }

    /** 选中时的浅色高亮底 */
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 90, 160, 255)
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val iconDestination = RectF()

    private var layout: RadialLayout? = null
    private var items: List<RadialItem> = emptyList()

    /** 白色圆盘半径 */
    private var chipRadius = 0f

    /** 图标在位图里的半径（圆盘内缩一圈，形成白色描边） */
    private var iconRadius = 0f

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
        chipRadius = metrics.iconDiameter / 2f
        iconRadius = chipRadius * RadialLayoutSpec.ICON_INSET_RATIO
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
        // 不能因为入场动画还没开始（entryProgress == 0）就丢掉指针：
        // 展开那一帧 pointer 会先到，丢了就要用户再动一下才有选中。
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
        val iconScale = entry.iconScale * (exit?.plateScale ?: 1f)

        val count = items.size
        val shadowOffset = 4f * density
        for (index in items.indices) {
            if (index >= currentLayout.itemCenters.size) break
            val stagger = RadialMotion.iconProgress(entryProgress, index, count)
            val alpha = (contentAlpha * stagger * 255f).toInt().coerceIn(0, 255)
            if (alpha <= 4) continue

            val center = currentLayout.itemCenters[index]
            val isSelected = selectedIndex == index
            val scale = iconScale * (if (isSelected) 1f + 0.10f * selectionProgress else 1f)
            val radius = chipRadius * scale

            // 投影：一层半透明黑，轻微下移，模拟悬浮
            shadowPaint.alpha = (alpha * 0.18f).toInt().coerceIn(0, 255)
            canvas.drawCircle(center.x, center.y + shadowOffset * scale, radius * 1.04f, shadowPaint)

            chipPaint.alpha = alpha
            canvas.drawCircle(center.x, center.y, radius, chipPaint)

            chipStrokePaint.alpha = (alpha * 0.15f).toInt().coerceIn(0, 255)
            canvas.drawCircle(center.x, center.y, radius, chipStrokePaint)

            if (isSelected) {
                highlightPaint.alpha = (alpha * (0.25f + 0.35f * selectionProgress)).toInt()
                canvas.drawCircle(center.x, center.y, radius, highlightPaint)
                ringPaint.alpha = (alpha * (0.65f + 0.35f * selectionProgress)).toInt()
                canvas.drawCircle(center.x, center.y, radius - ringPaint.strokeWidth / 2f, ringPaint)
            }

            val icon = items[index].icon
            if (icon != null && !icon.isRecycled) {
                val iconR = iconRadius * scale
                iconDestination.set(
                    center.x - iconR,
                    center.y - iconR,
                    center.x + iconR,
                    center.y + iconR,
                )
                iconPaint.alpha = alpha
                canvas.drawBitmap(icon, null, iconDestination, iconPaint)
            }
        }
    }
}
