package io.relimus.zflow.xposed.ui.radial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * 覆盖轮盘尺寸预算：弧长间距、屏幕内约束、异常入参。
 */
class RadialLayoutSpecTest {

    private fun fit(
        width: Int = 1080,
        height: Int = 2400,
        density: Float = 2.75f,
        count: Int = 6,
        iconDp: Float = RadialLayoutSpec.BASE_ICON_DP,
    ) = RadialLayoutSpec.fit(
        widthPx = width,
        heightPx = height,
        density = density,
        itemCount = count,
        iconSizeDp = iconDp,
    )

    @Test
    fun iconsNeverOverlapOnTheArc() {
        for (count in 1..RadialLayoutSpec.MAX_ITEMS) {
            val metrics = fit(count = count)
            // 每环自己均分可用张角，所以最挤的是「格子最多 + 半径最小」的那环。
            val usable = RadialGeometry.DEFAULT_SWEEP_DEGREES - 6f
            metrics.shares.forEachIndexed { ring, perRing ->
                if (perRing <= 0) return@forEachIndexed
                val ringRadius = metrics.radius + ring * metrics.ringStep
                val slot = usable / perRing * PI.toFloat() / 180f
                val chord = 2f * ringRadius * sin(slot / 2f)
                assertTrue(
                    "count=$count ring=$ring shares=${metrics.shares} " +
                        "弦长 $chord 小于圆盘直径 ${metrics.iconDiameter}（格距 ${metrics.ringStep}）",
                    chord >= metrics.iconDiameter,
                )
            }
        }
    }

    /**
     * 13 个必须收敛到 3+4+6 的三层排布：这是 GestureEVO 的观感，
     * 也是「内少外多、等步长往外铺」在小屏上唯一不会挤在一起的分配。
     */
    @Test
    fun crowdedLayoutsFormThreeRingsOfThreeFourSix() {
        val metrics = fit(count = RadialLayoutSpec.MAX_ITEMS)
        assertEquals(3, metrics.rings)
        assertEquals(listOf(3, 4, 6), metrics.shares)
        assertTrue("环间距必须是一整个格距", metrics.ringStep > metrics.iconDiameter)

        val layout = RadialGeometry.layout(
            corner = RadialCorner.BOTTOM_LEFT,
            width = 1440f,
            height = 3200f,
            radius = metrics.radius,
            itemCount = RadialLayoutSpec.MAX_ITEMS,
            rings = metrics.rings,
            ringStep = metrics.ringStep,
            shares = metrics.shares.toIntArray(),
        )
        assertEquals(RadialLayoutSpec.MAX_ITEMS, layout.itemCenters.size)
        assertEquals(3, layout.itemRings.toSet().size)

        // 越往外圆心越远，且同一环的条目到圆心等距。
        val distances = layout.itemCenters.indices.map { index ->
            kotlin.math.hypot(
                layout.itemCenters[index].x - layout.origin.x,
                layout.itemCenters[index].y - layout.origin.y,
            )
        }
        val rings = layout.itemRings
        for (ring in 1..2) {
            val inner = distances[rings.indexOf(ring)]
            val outer = distances[rings.indexOf(ring - 1)]
            assertTrue("ring $ring 应比 ring ${ring - 1} 更远", inner > outer)
        }
    }

    /** 4~6 个用两环：内环至少 2 个，否则会出现「一个圆孤零零待在内圈」。 */
    @Test
    fun mediumLayoutsUseTwoRingsWithoutLonelyInnerItem() {
        for (count in 4..6) {
            val metrics = fit(count = count)
            assertEquals("count=$count", 2, metrics.rings)
            assertTrue("count=$count shares=${metrics.shares}", metrics.shares.first() >= 2)
            assertEquals(count, metrics.shares.sum())
        }
    }

    @Test
    fun fanStaysInsideTheScreen() {
        val metrics = fit()
        assertTrue("扇形超出屏幕: ${metrics.outerRadius}", metrics.outerRadius <= 1080f)
        assertTrue(metrics.outerRadius >= metrics.radius)
    }

    @Test
    fun smallScreensShrinkTheFan() {
        // 半径封顶后 360px 宽的屏已经放得下（不再需要等比缩），
        // 换一台真正装不下的机器：320x480 @2x。
        val metrics = fit(width = 320, height = 480, density = 2f)
        assertTrue("小屏应触发等比缩放", metrics.fitScale < 1f)
        assertTrue(metrics.fitScale >= 0.5f)
        assertTrue(metrics.outerRadius <= 320f)
    }

    /**
     * 三层是容量的上限而不是「越大越好」：13 个时外缘仍要落在屏幕内，
     * 且圆盘不能为了收边缩到看不清图标。
     */
    @Test
    fun threeRingsStillFitAGrownWheel() {
        val metrics = fit(count = RadialLayoutSpec.MAX_ITEMS, width = 1440, height = 3200, density = 3.75f)
        assertTrue(
            "圆盘不应小于 40dp，实际 ${metrics.iconDiameter / 3.75f}dp",
            metrics.iconDiameter / 3.75f >= 40f,
        )
        assertTrue(
            "外缘必须落在屏幕内，实际 ${metrics.outerRadius / 3.75f}dp",
            metrics.outerRadius <= 1440f,
        )
        // 轮盘要「浮」在触发点上方：内环圆心离角落至少 100dp。
        assertTrue(
            "内环半径过小会贴着手指，实际 ${metrics.radius / 3.75f}dp",
            metrics.radius / 3.75f >= 100f,
        )
    }

    /** 触发点上移后，圆心跟着上移，向上可用空间变小也不会越界。 */
    @Test
    fun raisedTriggerLiftsTheOrigin() {
        val base = RadialLayoutSpec.fit(
            widthPx = 1440,
            heightPx = 3200,
            density = 3.75f,
            itemCount = 5,
        )
        val raised = RadialLayoutSpec.fit(
            widthPx = 1440,
            heightPx = 3200,
            density = 3.75f,
            itemCount = 5,
            triggerOffsetYPx = 320f,
        )
        assertEquals(base.originInsetX, raised.originInsetX, 0.001f)
        assertEquals(base.originInsetY + 320f, raised.originInsetY, 0.001f)
    }

    @Test
    fun itemCountIsClampedToTheSupportedRange() {
        val max = fit(count = 99)
        val min = fit(count = 0)
        assertEquals(fit(count = RadialLayoutSpec.MAX_ITEMS).radius, max.radius, 0.001f)
        assertEquals(fit(count = RadialLayoutSpec.MIN_ITEMS).radius, min.radius, 0.001f)
    }

    @Test
    fun hitRadiusCanBeOverriddenBySettings() {
        val default = fit()
        val custom = RadialLayoutSpec.fit(
            widthPx = 1080,
            heightPx = 2400,
            density = 2.75f,
            itemCount = 6,
            hitRadiusDp = 120f,
        )
        assertTrue(custom.enterRadius > 0f)
        assertEquals(120f * 2.75f, custom.enterRadius, 0.5f)
    }

    @Test
    fun hysteresisSitsJustPastHalfAnIcon() {
        val metrics = fit()
        // 迟滞半径略大于「两格中线」，即手指越过中线约 55% 才换格：
        // 足够吸收边界抖动，又不会让邻格变得难以选中。
        assertTrue(
            "keepRadius=${metrics.keepRadius} icon=${metrics.iconDiameter}",
            metrics.keepRadius in (metrics.iconDiameter * 0.55f)..(metrics.iconDiameter * 0.8f),
        )
    }

    @Test
    fun invalidInputsStillProduceUsableMetrics() {
        val metrics = RadialLayoutSpec.fit(
            widthPx = 0,
            heightPx = 0,
            density = 0f,
            itemCount = 3,
        )
        assertTrue(metrics.radius.isFinite() && metrics.radius > 0f)
        assertTrue(metrics.iconDiameter.isFinite() && metrics.iconDiameter > 0f)
    }
}
