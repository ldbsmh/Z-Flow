package io.relimus.zflow.xposed.ui.radial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.ceil
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
            // 多环时按「每环最多几格」算间距，内环才是最挤的那个。
            val perRing = ceil(count.toDouble() / metrics.rings).toInt()
            val step = (RadialGeometry.DEFAULT_SWEEP_DEGREES - 6f) / perRing * PI.toFloat() / 180f
            val chord = 2f * metrics.radius * sin(step / 2f)
            assertTrue(
                "count=$count rings=${metrics.rings} 弧长间距 $chord 小于圆盘直径 ${metrics.iconDiameter}",
                chord >= metrics.iconDiameter,
            )
        }
    }

    @Test
    fun crowdedLayoutsUseTwoAlignedRings() {
        val metrics = fit(count = RadialLayoutSpec.MAX_ITEMS)
        assertEquals(2, metrics.rings)
        assertTrue("外环必须比内环更远", metrics.ringStep > metrics.iconDiameter)

        val layout = RadialGeometry.layout(
            corner = RadialCorner.BOTTOM_LEFT,
            width = 1440f,
            height = 3200f,
            radius = metrics.radius,
            itemCount = RadialLayoutSpec.MAX_ITEMS,
            rings = metrics.rings,
            ringStep = metrics.ringStep,
        )
        assertEquals(RadialLayoutSpec.MAX_ITEMS, layout.itemCenters.size)
        assertEquals(2, layout.itemRings.toSet().size)

        val inner = layout.itemCenters[0]
        val outer = layout.itemCenters[layout.itemRings.indexOf(1)]
        val innerDistance = kotlin.math.hypot(inner.x - layout.origin.x, inner.y - layout.origin.y)
        val outerDistance = kotlin.math.hypot(outer.x - layout.origin.x, outer.y - layout.origin.y)
        assertTrue(outerDistance > innerDistance)
        // 两环角度对齐：外环第一格与内环第一格在同一根半径上（2x3 网格）。
        assertEquals(layout.itemAngles[0], layout.itemAngles[layout.itemRings.indexOf(1)], 0.001f)
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
        assertTrue(metrics.fitScale >= 0.55f)
        assertTrue(metrics.outerRadius <= 320f)
    }

    @Test
    fun radiusStaysWithinThumbReach() {
        // 6 格是最挤的情况：外环边缘必须被拉回舒适半径，而不是外推到 300dp。
        val metrics = fit(count = RadialLayoutSpec.MAX_ITEMS, width = 1440, height = 3200, density = 3.75f)
        assertTrue(
            "外环半径应限制在 250dp 附近，实际 ${metrics.outerRadius / 3.75f}dp",
            metrics.outerRadius / 3.75f <= 255f,
        )
        // 2026-11 用户反馈「轮盘太小」，圆盘基准从 46dp 提到 56dp：
        // 6 格仍必须保住这个尺寸，不允许为了收边把圆盘压到 40dp 以下。
        assertTrue(
            "圆盘不应小于 40dp，实际 ${metrics.iconDiameter / 3.75f}dp",
            metrics.iconDiameter / 3.75f >= 40f,
        )
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
