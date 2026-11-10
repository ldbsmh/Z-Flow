package io.relimus.zflow.xposed.ui.radial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖轮盘几何：布局朝向、槽位顺序、最近格命中与迟滞。
 *
 * 这些断言的意义在于把「轮盘长什么样」从设备上搬到 JVM：
 * 布局错一位、左右角镜像错、命中半径写错都能在这里直接失败。
 */
class RadialGeometryTest {

    private val width = 1080f
    private val height = 2400f
    private val radius = 480f

    private fun layout(
        corner: RadialCorner = RadialCorner.BOTTOM_LEFT,
        count: Int = 6,
        sweep: Float = RadialGeometry.DEFAULT_SWEEP_DEGREES,
    ) = RadialGeometry.layout(
        corner = corner,
        width = width,
        height = height,
        radius = radius,
        itemCount = count,
        sweepDegrees = sweep,
    )

    @Test
    fun leftCornerFansUpAndRight() {
        val result = layout()
        assertEquals(6, result.itemCenters.size)
        result.itemCenters.forEach { center ->
            assertTrue("图标必须在圆心右上方", center.x > result.origin.x && center.y < result.origin.y)
        }
        assertEquals(0f, result.origin.x, 0.001f)
        assertEquals(height, result.origin.y, 0.001f)
    }

    @Test
    fun rightCornerFansUpAndLeft() {
        val left = layout(RadialCorner.BOTTOM_LEFT)
        val right = layout(RadialCorner.BOTTOM_RIGHT)
        assertEquals(6, right.itemCenters.size)
        right.itemCenters.forEach { center ->
            assertTrue(center.x < right.origin.x && center.y < right.origin.y)
        }
        // 镜像后同一索引到屏幕中轴的距离近似对称
        val leftDistance = left.itemCenters[0].x
        val rightDistance = width - right.itemCenters[0].x
        assertEquals(leftDistance, rightDistance, 0.5f)
    }

    @Test
    fun firstItemSitsAtTheOuterEndOfTheFan() {
        val result = layout(count = 6)
        // 索引 0 应最接近竖直线，索引 last 最接近底边
        assertTrue(result.itemAngles[0] < result.itemAngles[1])
        assertTrue(result.itemAngles[1] < result.itemAngles.last())
        assertTrue(result.itemAngles[0] > -90f)
        assertTrue(result.itemAngles.last() < 0f)
    }

    @Test
    fun everyItemStaysInsideTheRequestedSweep() {
        val result = layout(count = 6, sweep = 84f)
        result.itemAngles.forEach { angle ->
            assertTrue("角度越界: $angle", angle in -84.001f..(-6f))
        }
    }

    @Test
    fun spacingIsUniformForSameCount() {
        val result = layout(count = 4)
        val gaps = result.itemAngles.zipWithNext { a, b -> b - a }
        gaps.forEach { gap -> assertEquals(gaps.first(), gap, 0.01f) }
    }

    @Test
    fun emptyOrInvalidLayoutsDoNotThrow() {
        assertTrue(layout(count = 0).isEmpty)
        assertTrue(layout(count = -3).isEmpty)
        val zeroRadius = RadialGeometry.layout(
            corner = RadialCorner.BOTTOM_LEFT,
            width = width,
            height = height,
            radius = 0f,
            itemCount = 4,
        )
        assertTrue(zeroRadius.isEmpty)
        assertNull(RadialGeometry.polarDegrees(zeroRadius, 10f, 10f).takeIf { !it.isNaN() })
    }

    @Test
    fun selectionPicksNearestCenter() {
        val result = layout(count = 6)
        result.itemCenters.forEachIndexed { index, center ->
            assertEquals(index, RadialGeometry.selection(result, center.x, center.y, enterRadius = 200f))
        }
    }

    @Test
    fun selectionRejectsPointsOutsideEnterRadius() {
        val result = layout(count = 6)
        val far = RadialGeometry.selection(
            layout = result,
            x = width / 2f,
            y = height / 2f,
            enterRadius = 120f,
        )
        assertNull(far)
    }

    @Test
    fun keepRadiusHoldsPreviousSlotNearTheBoundary() {
        val result = layout(count = 6)
        val first = result.itemCenters[0]
        val second = result.itemCenters[1]
        // 取偏向第二格 60% 处：无迟滞时最近格已是第二格，有迟滞时仍保持第一格。
        val midX = first.x + (second.x - first.x) * 0.6f
        val midY = first.y + (second.y - first.y) * 0.6f
        val withoutHysteresis = RadialGeometry.selection(result, midX, midY, previous = 0, enterRadius = 400f)
        val withHysteresis = RadialGeometry.selection(
            layout = result,
            x = midX,
            y = midY,
            previous = 0,
            enterRadius = 400f,
            keepRadius = 1_000f,
        )
        assertEquals(1, withoutHysteresis)
        assertEquals(0, withHysteresis)
    }

    @Test
    fun sweepCheckRejectsAlongEdgeSwipes() {
        val result = layout(RadialCorner.BOTTOM_LEFT)
        // 底边向右横扫：极角约为 0，仍在容差内，但明显向下/向左的点必须被排除。
        assertTrue(RadialGeometry.isWithinSweep(result, 300f, height - 40f))
        assertTrue(RadialGeometry.isWithinSweep(result, 300f, height - 600f))
        assertTrue(!RadialGeometry.isWithinSweep(result, 300f, height + 120f))
        assertTrue(!RadialGeometry.isWithinSweep(result, -400f, height - 100f))
    }
}
