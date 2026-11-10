package io.relimus.zflow.xposed.ui.radial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖轮盘动画曲线。
 *
 * 动画最容易出的问题是「端点不精确」（首帧闪一下、末帧留着半个图标）和
 * 「回弹把值甩出可视范围」。这里逐帧跑一遍，端点与单调性都能直接断言。
 */
class RadialMotionTest {

    private fun frames(steps: Int = 60): List<RadialEntryFrame> =
        (0..steps).map { RadialMotion.entry(it / steps.toFloat()) }

    @Test
    fun entryStartsCollapsedAndInvisible() {
        val first = RadialMotion.entry(0f)
        assertEquals(0f, first.contentAlpha, 0.001f)
        assertEquals(0f, first.sweepProgress, 0.001f)
        assertEquals(0.92f, first.plateScale, 0.001f)
    }

    @Test
    fun entryEndsFullyExpandedAndSettled() {
        val last = RadialMotion.entry(1f)
        assertEquals(1f, last.contentAlpha, 0.001f)
        assertEquals(1f, last.sweepProgress, 0.001f)
        assertEquals(1f, last.plateScale, 0.002f)
        assertEquals(1f, last.iconScale, 0.002f)
    }

    @Test
    fun entrySweepIsMonotonic() {
        val sweeps = frames().map { it.sweepProgress }
        sweeps.zipWithNext { a, b -> assertTrue("展开进度回退: $a -> $b", b >= a - 0.0001f) }
        assertEquals(1f, sweeps.last(), 0.0001f)
    }

    @Test
    fun entryOvershootStaysWithinReasonableBounds() {
        frames().forEach { frame ->
            assertTrue("alpha 越界 ${frame.contentAlpha}", frame.contentAlpha in 0f..1f)
            assertTrue("sweep 越界 ${frame.sweepProgress}", frame.sweepProgress in 0f..1f)
            assertTrue("缩放过大 ${frame.iconScale}", frame.iconScale in 0.7f..1.06f)
            assertTrue("底板缩放过大 ${frame.plateScale}", frame.plateScale in 0.9f..1.03f)
        }
    }

    @Test
    fun exitRunsBackwardsFromFullyExpanded() {
        val start = RadialMotion.exit(0f)
        val end = RadialMotion.exit(1f)
        assertEquals(1f, start.contentAlpha, 0.001f)
        assertEquals(1f, start.sweepProgress, 0.001f)
        assertEquals(0f, end.contentAlpha, 0.001f)
        assertEquals(0f, end.sweepProgress, 0.001f)
        val sweeps = (0..40).map { RadialMotion.exit(it / 40f).sweepProgress }
        sweeps.zipWithNext { a, b -> assertTrue("收拢进度回退: $a -> $b", b <= a + 0.0001f) }
    }

    @Test
    fun iconStaggerRevealsOuterIconsFirst() {
        val outer = RadialMotion.iconProgress(0.35f, index = 0, count = 6)
        val inner = RadialMotion.iconProgress(0.35f, index = 5, count = 6)
        assertTrue("外侧图标应先出现", outer > inner)
        assertEquals(1f, RadialMotion.iconProgress(1f, 0, 6), 0.0001f)
        assertEquals(1f, RadialMotion.iconProgress(1f, 5, 6), 0.0001f)
        assertEquals(0f, RadialMotion.iconProgress(0f, 5, 6), 0.0001f)
    }

    @Test
    fun singleItemSkipsStagger() {
        assertEquals(0.4f, RadialMotion.iconProgress(0.4f, 0, 1), 0.0001f)
    }

    @Test
    fun cubicCurveIsClampedAtBothEnds() {
        val curve = CubicMotionCurve(0.22f, 0.61f, 0.36f, 1f)
        assertEquals(0f, curve.sample(-1f), 0f)
        assertEquals(1f, curve.sample(2f), 0f)
        assertTrue(curve.sample(0.5f) in 0f..1f)
    }
}
