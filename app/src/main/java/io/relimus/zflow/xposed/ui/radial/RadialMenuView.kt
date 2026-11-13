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
 * 带柔和投影，按环逐层往外铺开（最多三层 3+4+6）。这样既不遮壁纸，
 * 条目多了也不会糊成一片。
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

    /**
     * 轮盘背景层。
     *
     * 画的是**以圆心为中心、向外衰减到完全透明的径向渐变**（抄 GestureEVO 的做法，
     * 它用 `GradientDrawable` 的 `RADIAL_GRADIENT` 加在窗口背景上）。渐变本身缓存成
     * shader，换格时只改 alpha，不重建。
     */
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 渐变 shader 的缓存 key：圆心 + 半径没变就不重建。 */
    private var scrimShader: android.graphics.RadialGradient? = null
    private var scrimKey: Triple<Float, Float, Float>? = null

    /** 桌面有没有真的被高斯模糊，决定渐变画多重。 */
    private var backdropBlurred = false

    /** 预览圆的白底、投影与描边，和格子上的圆盘同一套观感。 */
    private val previewPlatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val previewShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(56, 0, 0, 0)
    }
    private val previewStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = Color.argb(38, 0, 0, 0)
    }
    private val previewIconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val previewIconDestination = RectF()

    private val previewLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = PREVIEW_LABEL_SP * resources.displayMetrics.scaledDensity
        color = Color.WHITE
        // 背景被压暗后白字本来就清楚，投影只是防止压在亮色壁纸/图标上时糊掉。
        setShadowLayer(6f * density, 0f, 2f * density, Color.argb(170, 0, 0, 0))
    }

    private var layout: RadialLayout? = null
    private var items: List<RadialItem> = emptyList()

    /** 白色圆盘半径 */
    private var chipRadius = 0f

    /** 图标在位图里的半径（圆盘内缩一圈，形成白色描边） */
    private var iconRadius = 0f

    /** 圆心到最外缘的距离（含格子半径），预览圆要摆在它上面。 */
    private var outerRadius = 0f

    private var enterRadius = 0f
    private var keepRadius = 0f

    private var entryProgress = 0f
    private var exitProgress = 0f
    private var selectionProgress = 0f

    private var motionAnimator: ValueAnimator? = null
    private var selectionAnimator: ValueAnimator? = null

    /**
     * 入场动画是否已经落地。
     *
     * 落地之前不接受选择 —— 这是「先把轮盘完整唤出来，再选择，最后才打开应用」的
     * 第一道闸。轮盘还在从圆心往外长的时候，格子位置一直在动，这时候按手指位置
     * 判定选中，等于「上滑路过哪一格就选中哪一格」，松手就打开，误触全从这里来。
     */
    private var entrySettled = false

    /** 入场期间最后一条指针位置，等动画落地后补算一次选中。 */
    private var pendingPointerX = Float.NaN
    private var pendingPointerY = Float.NaN

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
        entrySettled = false
        pendingPointerX = Float.NaN
        pendingPointerY = Float.NaN
        chipRadius = metrics.iconDiameter / 2f
        iconRadius = chipRadius * RadialLayoutSpec.ICON_INSET_RATIO
        outerRadius = metrics.outerRadius
        enterRadius = metrics.enterRadius
        keepRadius = metrics.keepRadius
        invalidate()
    }

    /**
     * 告诉视图「桌面到底有没有被真的模糊」。
     *
     * 模糊在的时候渐变只需轻压（否则把模糊自己盖掉了，看起来还是一块板）；
     * 不在的时候得压重，靠遮罩保证桌面图标不会和轮盘里的图标混在一起。
     */
    fun setBackdropBlurred(blurred: Boolean) {
        if (backdropBlurred == blurred) return
        backdropBlurred = blurred
        // 颜色变了，shader 必须重建。
        scrimShader = null
        scrimKey = null
        invalidate()
    }

    /**
     * 后台线程算好的图标落位。
     *
     * 只在数量一致时生效：数量对不上说明这是上一轮窗口的结果，
     * 直接丢弃比画到错误的格子上安全。
     */
    fun applyIcons(icons: List<Bitmap?>) {
        if (icons.size != items.size) return
        items = items.mapIndexed { index, item ->
            val icon = icons[index]
            if (icon == null || icon.isRecycled) item else item.copy(icon = icon)
        }
        invalidate()
    }

    fun animateIn() {
        stopMotion()
        entrySettled = false
        pendingPointerX = Float.NaN
        pendingPointerY = Float.NaN
        if (!ValueAnimator.areAnimatorsEnabled()) {
            entryProgress = 1f
            exitProgress = 0f
            invalidate()
            settleEntry()
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
            addListener(object : AnimatorListenerAdapter() {
                private var canceled = false

                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    // 被 animateOut / detach 打断时不能「补算选中」，
                    // 否则收起动画的开头会跳出一个高亮。
                    if (!canceled) settleEntry()
                }
            })
            start()
        }
    }

    /**
     * 入场动画落地：从这里起才允许选择。
     *
     * 用入场期间缓存的最后一条指针位置补算一次，让「手指已经停在哪一格」的
     * 用户不必再抖一下手指；手指停在轮盘外面（快速上滑直接穿过去）就没有选中，
     * 松手时什么都不打开 —— 这正是误触要挡掉的情况。
     */
    private fun settleEntry() {
        entrySettled = true
        val x = pendingPointerX
        val y = pendingPointerY
        pendingPointerX = Float.NaN
        pendingPointerY = Float.NaN
        if (x.isFinite() && y.isFinite()) applyPointer(x, y)
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
        if (!entrySettled) {
            // 轮盘还在长出来：只记住位置，别急着判定选中（见 [entrySettled]）。
            pendingPointerX = x
            pendingPointerY = y
            return
        }
        applyPointer(x, y)
    }

    private fun applyPointer(x: Float, y: Float) {
        val currentLayout = layout ?: return
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
        /*
         * 最后一道保险：模糊挂在桌面视图树上，与轮盘窗口不是同一个对象。
         * 只要窗口是被外部摘掉的（退场动画被打断、进程状态异常），这里就得负责恢复，
         * 否则桌面会一直糊着，而且用户没有任何入口能让它恢复。
         */
        RadialBackdrop.clear("view detached")
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

        drawScrim(canvas, currentLayout, contentAlpha)
        drawChips(canvas, currentLayout, contentAlpha, iconScale)
        drawPreview(canvas, currentLayout, contentAlpha)
    }

    /**
     * 轮盘背景：圆心实色、向外衰减到完全透明的径向渐变（抄 GestureEVO）。
     *
     * 为什么不是「整屏压一层」：均匀压暗没有中间档位可用 ——
     * 压到 0.45 桌面图标还认得出来，和轮盘里的图标同屏打架；压到 0.85 又变成一块黑板，
     * 整个屏幕闷住。渐变把这两件事分开了：**圆心附近不透明**，轮盘底下的桌面图标
     * 被彻底遮住；**越往外越透**，屏幕上部仍能看到桌面，观感是一团围着轮盘的雾。
     *
     * 半径取屏幕长边、圆心跟着轮盘走，和 GestureEVO 的
     * `gradientRadius = max(width, height)` + `setGradientCenter()` 完全一致。
     */
    private fun drawScrim(canvas: Canvas, currentLayout: RadialLayout, contentAlpha: Float) {
        val radius = maxOf(width, height).toFloat()
        if (radius <= 0f) return
        val origin = currentLayout.origin
        val key = Triple(origin.x, origin.y, radius)
        if (scrimShader == null || scrimKey != key) {
            scrimShader = android.graphics.RadialGradient(
                origin.x,
                origin.y,
                radius,
                if (backdropBlurred) SCRIM_CENTER_BLURRED else SCRIM_CENTER_SOLID,
                Color.TRANSPARENT,
                android.graphics.Shader.TileMode.CLAMP,
            )
            scrimPaint.shader = scrimShader
            scrimKey = key
        }
        scrimPaint.alpha = (contentAlpha * 255f).toInt().coerceIn(0, 255)
        if (scrimPaint.alpha <= 0) return
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
    }

    private fun drawChips(
        canvas: Canvas,
        currentLayout: RadialLayout,
        contentAlpha: Float,
        iconScale: Float,
    ) {
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

    /**
     * 轮盘上方的放大预览（对齐 GestureEVO）。
     *
     * 位置取「轮盘最外缘之上 + 一段间距」，水平居中于屏幕：轮盘贴着左右下角，
     * 若跟着圆心走，左角触发时预览会贴左、右角触发时贴右，位置来回跳。
     * 居中则两次触发的预览落在同一处，扫一眼就能读到当前选中的是哪个应用。
     */
    private fun drawPreview(
        canvas: Canvas,
        currentLayout: RadialLayout,
        contentAlpha: Float,
    ) {
        val index = selectedIndex ?: return
        val item = items.getOrNull(index) ?: return
        if (width <= 0 || height <= 0) return

        val previewRadius = PREVIEW_RADIUS_DP * density
        val margin = 24f * density
        val centerX = (width / 2f).coerceIn(previewRadius + margin, width - previewRadius - margin)
        val aboveEdge = currentLayout.origin.y - outerRadius - previewRadius - PREVIEW_GAP_DP * density
        // 触发区被限制在下半屏，正常不会越界；小屏 + 大轮盘时兜一下。
        val centerY = aboveEdge.coerceAtLeast(previewRadius + margin)

        // 只在换格的那一下轻微缩放，读起来像「被按下去又弹起来」。
        val scale = 0.88f + 0.12f * selectionProgress
        val radius = previewRadius * scale
        val alpha = (contentAlpha * 255f).toInt().coerceIn(0, 255)
        if (alpha <= 4) return

        previewShadowPaint.alpha = (alpha * 0.22f).toInt()
        canvas.drawCircle(centerX, centerY + 5f * density * scale, radius * 1.05f, previewShadowPaint)

        previewPlatePaint.alpha = alpha
        canvas.drawCircle(centerX, centerY, radius, previewPlatePaint)

        previewStrokePaint.alpha = (alpha * 0.15f).toInt()
        canvas.drawCircle(centerX, centerY, radius, previewStrokePaint)

        val icon = item.icon
        if (icon != null && !icon.isRecycled) {
            val iconR = radius * PREVIEW_ICON_RATIO
            previewIconDestination.set(
                centerX - iconR,
                centerY - iconR,
                centerX + iconR,
                centerY + iconR,
            )
            previewIconPaint.alpha = alpha
            canvas.drawBitmap(icon, null, previewIconDestination, previewIconPaint)
        }

        val label = item.label
        if (label.isNotEmpty()) {
            previewLabelPaint.alpha = (alpha * 0.95f).toInt()
            val maxWidth = width - 2f * margin
            canvas.drawText(
                ellipsize(label, previewLabelPaint, maxWidth),
                centerX,
                centerY + radius + PREVIEW_LABEL_GAP_DP * density + previewLabelPaint.textSize,
                previewLabelPaint,
            )
        }
    }

    /** 应用名过长时截断成「前面一段…」，避免长名字横穿整个屏幕。 */
    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val suffix = "…"
        val suffixWidth = paint.measureText(suffix)
        var end = text.length
        while (end > 0 && paint.measureText(text, 0, end) + suffixWidth > maxWidth) {
            end -= 1
        }
        return if (end <= 0) suffix else text.substring(0, end) + suffix
    }

    companion object {
        /** 预览圆半径（dp）。92dp 直径，约为格子上的圆盘 1.6 倍。 */
        internal const val PREVIEW_RADIUS_DP = 46f

        /** 图标在预览圆里的占比。 */
        internal const val PREVIEW_ICON_RATIO = 0.80f

        /** 预览圆与轮盘最外缘之间的间距。 */
        private const val PREVIEW_GAP_DP = 18f

        private const val PREVIEW_LABEL_SP = 14f
        private const val PREVIEW_LABEL_GAP_DP = 10f

        /**
         * 桌面被真的模糊时，圆心处的颜色。
         *
         * 只需轻压一下让白圆盘浮起来 —— 压重了会把刚花代价糊出来的效果重新盖回去。
         */
        private const val SCRIM_CENTER_BLURRED = 0x8C0E0E12.toInt()

        /**
         * 模糊不可用时，圆心处的颜色。
         *
         * 近黑、几乎不透明：这种时候只能靠遮罩让桌面图标「看不见」，
         * 否则它会和轮盘里的图标互相干扰（两者都是白圆底）。
         */
        private const val SCRIM_CENTER_SOLID = 0xEB0E0E12.toInt()
    }
}
