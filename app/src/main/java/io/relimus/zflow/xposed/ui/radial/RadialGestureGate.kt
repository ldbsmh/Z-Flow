package io.relimus.zflow.xposed.ui.radial

/**
 * 轮盘手势的状态机。
 *
 * 从两个成熟实现里各取一条硬约束：
 * - 必须在「底角按下 + 明显上滑」之后才展开，单纯点一下底角不应该弹轮盘；
 * - 一次手势只允许提交一次，避免 Launcher 的输入链重复回调导致开两个小窗。
 *
 * 状态迁移：IDLE → ARMED（底角按下）→ REVEALED（越过阈值且落在扇形内）→ DONE（已提交/已取消）。
 * 任何异常路径都走 [reset]，保证下一个 ACTION_DOWN 是干净状态。
 */
internal class RadialGestureGate(
    private val revealSlopPx: Float,
) {

    enum class State {
        IDLE,
        ARMED,
        REVEALED,
        DONE,
    }

    var state: State = State.IDLE
        private set

    var corner: RadialCorner? = null
        private set

    var downX: Float = 0f
        private set

    var downY: Float = 0f
        private set

    val isIdle: Boolean
        get() = state == State.IDLE

    val isRevealed: Boolean
        get() = state == State.REVEALED

    /** 手势仍在进行中（已展开或可能展开），用于抑制 Launcher 原本的落点提示。 */
    val isActive: Boolean
        get() = state == State.ARMED || state == State.REVEALED

    fun arm(corner: RadialCorner?, x: Float, y: Float) {
        if (corner == null || !x.isFinite() || !y.isFinite()) {
            reset()
            return
        }
        this.corner = corner
        downX = x
        downY = y
        state = State.ARMED
    }

    /**
     * 是否应该展开轮盘：已 ARMED、位移超过阈值、且落在扇形张开的一侧。
     *
     * 第三个条件很关键：沿底边横扫（返回手势的方向）不应触发轮盘。
     */
    fun shouldReveal(layout: RadialLayout, x: Float, y: Float): Boolean {
        if (state != State.ARMED) return false
        if (!x.isFinite() || !y.isFinite()) return false
        val dx = x - downX
        val dy = y - downY
        if (dx * dx + dy * dy < revealSlopPx * revealSlopPx) return false
        /*
         * 垂直分量必须真的朝上，而且要有分量。
         *
         * 只看「dy < 0 + 极角在扇形内」是不够的：底角热区本身离屏幕角落有几十像素，
         * 横滑返回手势的 dy 只有 -5px 左右，极角算出来却是 -12°，
         * 正好落在扇形范围内 —— 真机上就是从底角往屏幕内横扫会误触发轮盘。
         * 要求垂直位移超过半个阈值，把这类近水平滑动挡在门外。
         */
        if (dy > -revealSlopPx * 0.5f) return false
        /*
         * 展开判定比最终选中判定松一些（20° 而不是 8°）。
         *
         * 展开只是一个「闸门」，真正的选中由扇形布局接管；这里收得太紧会出现
         * 「贴着屏幕左边缘往上滑」被判在扇形外，表现为底角上滑时灵时不灵。
         * 横向扫动依然会被角度范围挡住（水平一侧不加容差，见 isWithinSweep）。
         */
        return RadialGeometry.isWithinSweep(layout, x, y, toleranceDegrees = 20f)
    }

    /**
     * 位移是否已经越过「必须给结论」的门槛。
     *
     * 门槛之内继续扣着事件是安全的（手指刚动，没几个事件）；
     * 一旦越过门槛，就必须立刻在「展开」和「交还 Launcher」之间二选一，
     * 不能一直悬着 —— 悬着的代价见 [shouldAbandon]。
     */
    fun isPastDecisionThreshold(x: Float, y: Float): Boolean {
        if (state != State.ARMED) return false
        if (!x.isFinite() || !y.isFinite()) return false
        val dx = x - downX
        val dy = y - downY
        return dx * dx + dy * dy >= revealSlopPx * revealSlopPx
    }

    /**
     * 位移已经够大、但方向不属于轮盘（沿底边横滑返回 / 向下拖）。
     *
     * 这种情况必须立刻把事件还给 Launcher。早期版本在这里继续 `return true`
     * 把 MOVE 全吞了，而 DOWN 也早已被吞 —— Launcher 的
     * `recents_animation_input_consumer` 于是永远等不到这条手势的后续事件，
     * 5 秒后 InputDispatcher 报 `Input dispatching timed out`，
     * ANR 归到 system 进程，用户看到的是「进程 system 没有响应」。
     */
    fun shouldAbandon(layout: RadialLayout, x: Float, y: Float): Boolean {
        if (!isPastDecisionThreshold(x, y)) return false
        return !shouldReveal(layout, x, y)
    }

    fun markRevealed() {
        if (state == State.ARMED) state = State.REVEALED
    }

    /** 提交或取消；返回 true 表示这次调用是有效的终结（调用方据此执行动作）。 */
    fun finish(): Boolean {
        if (state == State.DONE || state == State.IDLE) return false
        state = State.DONE
        return true
    }

    fun reset() {
        state = State.IDLE
        corner = null
        downX = 0f
        downY = 0f
    }

    companion object {
        /**
         * 判断按下点属于哪个底角热区。
         *
         * [hotspotWidth]/[hotspotHeight] 是热区边长（px），按屏幕短边比例约束，
         * 避免在小屏上把整个底边都吃掉。
         */
        fun cornerAt(
            x: Float,
            y: Float,
            width: Int,
            height: Int,
            hotspotWidth: Float,
            hotspotHeight: Float,
        ): RadialCorner? {
            if (width <= 0 || height <= 0) return null
            if (!x.isFinite() || !y.isFinite()) return null
            if (y < height - hotspotHeight) return null
            return when {
                x <= hotspotWidth -> RadialCorner.BOTTOM_LEFT
                x >= width - hotspotWidth -> RadialCorner.BOTTOM_RIGHT
                else -> null
            }
        }
    }
}
