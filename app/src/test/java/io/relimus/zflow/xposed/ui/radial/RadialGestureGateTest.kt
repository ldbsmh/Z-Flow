package io.relimus.zflow.xposed.ui.radial

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

    private fun layout() = RadialGeometry.layout(
        corner = RadialCorner.BOTTOM_LEFT,
        width = width.toFloat(),
        height = height.toFloat(),
        radius = 460f,
        itemCount = 6,
    )

    @Test
    fun cornerHotspotCoversBothBottomCornersOnly() {
        assertEquals(
            RadialCorner.BOTTOM_LEFT,
            RadialGestureGate.cornerAt(20f, height - 20f, width, height, hotspotW, hotspotH),
        )
        assertEquals(
            RadialCorner.BOTTOM_RIGHT,
            RadialGestureGate.cornerAt(width - 20f, height - 20f, width, height, hotspotW, hotspotH),
        )
        assertNull(RadialGestureGate.cornerAt(width / 2f, height - 20f, width, height, hotspotW, hotspotH))
        assertNull(RadialGestureGate.cornerAt(20f, height / 2f, width, height, hotspotW, hotspotH))
    }

    @Test
    fun tapWithoutMovementDoesNotReveal() {
        val gate = RadialGestureGate(revealSlopPx = 24f)
        gate.arm(RadialCorner.BOTTOM_LEFT, 40f, height - 40f)
        assertFalse(gate.shouldReveal(layout(), 44f, height - 44f))
        assertTrue(gate.isActive)
        assertFalse(gate.isRevealed)
    }

    @Test
    fun upwardSwipeBeyondSlopReveals() {
        val gate = RadialGestureGate(revealSlopPx = 24f)
        gate.arm(RadialCorner.BOTTOM_LEFT, 40f, height - 40f)
        assertTrue(gate.shouldReveal(layout(), 120f, height - 220f))
        gate.markRevealed()
        assertTrue(gate.isRevealed)
    }

    @Test
    fun downwardOrSidewaysMovementDoesNotReveal() {
        val gate = RadialGestureGate(revealSlopPx = 24f)
        gate.arm(RadialCorner.BOTTOM_LEFT, 40f, height - 40f)
        assertFalse("向下不应展开", gate.shouldReveal(layout(), 40f, height + 10f))
        assertFalse("向屏幕外左侧不应展开", gate.shouldReveal(layout(), -60f, height - 60f))
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
}
