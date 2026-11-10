package io.relimus.zflow.xposed.ui.radial

import io.relimus.zflow.providers.RadialTriggerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖轮盘手势状态机：热区判定、展开条件、一次手势只提交一次。
 */
class RadialGestureGateTest {

    private val width = 1080
    private val height = 2400
    private val hotspotW = 96f
    private val hotspotH = 96f
    private val anchorY = height - 8f
    private val both = RadialTriggerSettings.Side.BOTH

    @Test
    fun cornerHotspotCoversBothBottomCornersOnly() {
        assertEquals(
            RadialCorner.BOTTOM_LEFT,
            RadialGestureGate.cornerAt(20f, height - 20f, width, height, hotspotW, hotspotH, anchorY, both),
        )
        assertEquals(
            RadialCorner.BOTTOM_RIGHT,
            RadialGestureGate.cornerAt(width - 20f, height - 20f, width, height, hotspotW, hotspotH, anchorY, both),
        )
        assertNull(
            RadialGestureGate.cornerAt(width / 2f, height - 20f, width, height, hotspotW, hotspotH, anchorY, both)
        )
        assertNull(
            RadialGestureGate.cornerAt(20f, height / 2f, width, height, hotspotW, hotspotH, anchorY, both)
        )
    }

    /** 只开左侧：右下角必须完全不响应，把那个角让回系统手势。 */
    @Test
    fun sideSettingDisablesTheOtherCorner() {
        assertEquals(
            RadialCorner.BOTTOM_LEFT,
            RadialGestureGate.cornerAt(
                20f, height - 20f, width, height, hotspotW, hotspotH, anchorY,
                RadialTriggerSettings.Side.LEFT,
            ),
        )
        assertNull(
            RadialGestureGate.cornerAt(
                width - 20f, height - 20f, width, height, hotspotW, hotspotH, anchorY,
                RadialTriggerSettings.Side.LEFT,
            )
        )
        assertNull(
            RadialGestureGate.cornerAt(
                20f, height - 20f, width, height, hotspotW, hotspotH, anchorY,
                RadialTriggerSettings.Side.RIGHT,
            )
        )
        assertEquals(
            RadialCorner.BOTTOM_RIGHT,
            RadialGestureGate.cornerAt(
                width - 20f, height - 20f, width, height, hotspotW, hotspotH, anchorY,
                RadialTriggerSettings.Side.RIGHT,
            ),
        )
    }

    /**
     * 触发点上移后整条热区跟着走：原来贴底的位置失效，新高度的位置生效。
     *
     * 热区厚度不变，所以不会因为调高而把屏幕下半部分的上滑手势全部吃掉。
     */
    @Test
    fun raisedTriggerMovesTheHotspotBand() {
        val raisedAnchor = height * 0.8f
        assertNull(
            "贴底位置应当脱离热区",
            RadialGestureGate.cornerAt(20f, height - 20f, width, height, hotspotW, hotspotH, raisedAnchor, both),
        )
        assertEquals(
            RadialCorner.BOTTOM_LEFT,
            RadialGestureGate.cornerAt(20f, raisedAnchor, width, height, hotspotW, hotspotH, raisedAnchor, both),
        )
        assertNull(
            "热区带外（更上方）不应命中",
            RadialGestureGate.cornerAt(
                20f, raisedAnchor - hotspotH * 2f, width, height, hotspotW, hotspotH, raisedAnchor, both,
            ),
        )
    }

    @Test
    fun tapWithoutMovementDoesNotReveal() {
        val gate = RadialGestureGate(revealSlopPx = 24f)
        gate.arm(RadialCorner.BOTTOM_LEFT, 40f, height - 40f)
        assertFalse(gate.shouldReveal(44f, height - 44f))
        assertTrue(gate.isActive)
        assertFalse(gate.isRevealed)
    }

    @Test
    fun upwardSwipeBeyondSlopReveals() {
        val gate = RadialGestureGate(revealSlopPx = 24f)
        gate.arm(RadialCorner.BOTTOM_LEFT, 40f, height - 40f)
        assertTrue(gate.shouldReveal(120f, height - 220f))
        gate.markRevealed()
        assertTrue(gate.isRevealed)
    }

    @Test
    fun downwardOrSidewaysMovementDoesNotReveal() {
        val gate = RadialGestureGate(revealSlopPx = 24f)
        gate.arm(RadialCorner.BOTTOM_LEFT, 40f, height - 40f)
        assertFalse("向下不应展开", gate.shouldReveal(40f, height + 10f))
        assertFalse("向屏幕外左侧不应展开", gate.shouldReveal(-60f, height - 60f))
    }

    /**
     * 贴着屏幕左边缘往上滑（角度约 -101°）也要能展开。
     *
     * 这是真实手感里最常见的起手方式；按 8° 容差会被判在扇形外，
     * 表现为「底角上滑时灵时不灵」。
     */
    @Test
    fun edgeHuggingUpwardSwipeReveals() {
        val gate = RadialGestureGate(revealSlopPx = 24f)
        gate.arm(RadialCorner.BOTTOM_LEFT, 40f, height - 40f)
        assertTrue(gate.shouldReveal(2f, height - 240f))
    }

    /** 右角同理：角度约 -101° 落在 (-200, -70) 内，应当展开。 */
    @Test
    fun rightCornerEdgeHuggingSwipeReveals() {
        val gate = RadialGestureGate(revealSlopPx = 24f)
        gate.arm(RadialCorner.BOTTOM_RIGHT, width - 40f, height - 40f)
        assertTrue(gate.shouldReveal(width - 2f, height - 240f))
    }

    @Test
    fun commitHappensOncePerGesture() {
        val gate = RadialGestureGate(revealSlopPx = 24f)
        gate.arm(RadialCorner.BOTTOM_RIGHT, width - 40f, height - 40f)
        assertTrue(gate.finish())
        assertFalse("重复回调不应再次提交", gate.finish())
        assertEquals(RadialGestureGate.State.DONE, gate.state)
    }

    @Test
    fun idleGestureCannotCommit() {
        val gate = RadialGestureGate(revealSlopPx = 24f)
        assertFalse(gate.finish())
        assertTrue(gate.isIdle)
    }

    @Test
    fun armWithNullCornerResets() {
        val gate = RadialGestureGate(revealSlopPx = 24f)
        gate.arm(RadialCorner.BOTTOM_LEFT, 10f, 10f)
        gate.arm(null, 10f, 10f)
        assertTrue(gate.isIdle)
        assertNull(gate.corner)
    }

    @Test
    fun resetClearsResidualState() {
        val gate = RadialGestureGate(revealSlopPx = 24f)
        gate.arm(RadialCorner.BOTTOM_LEFT, 40f, height - 40f)
        gate.markRevealed()
        gate.finish()
        gate.reset()
        assertTrue(gate.isIdle)
        assertFalse(gate.isActive)
    }

    /**
     * 手指刚动（未超过 slop）时还不需要给结论，继续暂扣是安全的。
     */
    @Test
    fun tinyMovementStaysUndecided() {
        val gate = RadialGestureGate(revealSlopPx = 24f)
        gate.arm(RadialCorner.BOTTOM_LEFT, 40f, height - 40f)
        assertFalse(gate.isPastDecisionThreshold(44f, height - 44f))
        assertFalse(gate.shouldAbandon(44f, height - 44f))
    }

    /**
     * 从底角横滑是系统返回手势，位移一旦超过阈值就必须交还给 Launcher。
     *
     * 早期版本在这里继续吞 MOVE，导致 Launcher 的输入 consumer 等不到后续事件，
     * InputDispatcher 5 秒后把 ANR 记到 system 进程上。
     */
    @Test
    fun sidewaysSwipeFromCornerAbandons() {
        val gate = RadialGestureGate(revealSlopPx = 24f)
        gate.arm(RadialCorner.BOTTOM_LEFT, 40f, height - 40f)
        val x = -60f
        val y = height - 60f
        assertTrue(gate.isPastDecisionThreshold(x, y))
        assertFalse("方向不符时不应展开", gate.shouldReveal(x, y))
        assertTrue("必须放手，不能继续吞事件", gate.shouldAbandon(x, y))
    }

    /** 从底角往下拖同理：位移够大、方向不符，必须放手。 */
    @Test
    fun downwardDragFromCornerAbandons() {
        val gate = RadialGestureGate(revealSlopPx = 24f)
        gate.arm(RadialCorner.BOTTOM_LEFT, 40f, height - 40f)
        assertTrue(gate.shouldAbandon(40f, height + 10f))
    }

    /** 已经展开的手势不再走「放弃」分支，事件继续由轮盘接管。 */
    @Test
    fun revealedGateIsPastDecisionThreshold() {
        val gate = RadialGestureGate(revealSlopPx = 24f)
        gate.arm(RadialCorner.BOTTOM_LEFT, 40f, height - 40f)
        gate.markRevealed()
        assertFalse(gate.isPastDecisionThreshold(120f, height - 220f))
        assertFalse(gate.shouldAbandon(120f, height - 220f))
    }
}
