package io.relimus.zflow.xposed.ui.radial

import kotlin.math.abs

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
     * 是否应该展开轮盘：已 ARMED、位移超过阈值、且方向是「斜向上」。
     *
     * 方向判定**只看位移向量**，不看轮盘圆心的极角。
     *
     * 之前用极角判定（指针相对圆心的角度是否落在扇形张角内），在「触发点高度 > 0」
     * 时会错杀：圆心已经上移到屏幕中段，手指从底边起手时还在圆心下方，
     * 位移明明是向上的，算出来的极角却指向下方（正角度），于是被判成
     * `abandon: direction rejected` —— 表现为「把触发点调高后，从底角上滑反而没反应」。
     *
     * 两条约束替代它：
     * 1. 垂直分量必须真的朝上，并且超过半个阈值（挡住近水平滑动）；
     * 2. 水平分量不能压倒垂直分量（挡住沿底边横划的返回手势）。
     */
    fun shouldReveal(x: Float, y: Float): Boolean {
        if (state != State.ARMED) return false
        if (!x.isFinite() || !y.isFinite()) return false
        val dx = x - downX
        val dy = y - downY
        if (dx * dx + dy * dy < revealSlopPx * revealSlopPx) return false
        /*
         * 垂直分量必须真的朝上，而且要有分量。
         *
         * 只看「dy < 0」是不够的：底角区域的横滑返回手势 dy 只有 -5px 左右，
         * 加上热区本身有厚度，横扫很容易混进来。要求垂直位移超过半个阈值。
         */
        if (dy > -revealSlopPx * 0.5f) return false
        // 纯横扫（水平位移远大于垂直位移）交给系统返回手势，不抢。
        if (abs(dx) > abs(dy) * MAX_HORIZONTAL_RATIO) return false
        return true
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
    fun shouldAbandon(x: Float, y: Float): Boolean {
        if (!isPastDecisionThreshold(x, y)) return false
        return !shouldReveal(x, y)
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
         * 水平位移允许是垂直位移的几倍。
         *
         * 2.5 倍对应「约 68° 的斜滑」：真实使用里从底角斜向上划通常在 30°～60°，
         * 再平就属于沿底边横扫（系统返回手势），不该被轮盘截胡。
         */
        private const val MAX_HORIZONTAL_RATIO = 2.5f
    }
}
