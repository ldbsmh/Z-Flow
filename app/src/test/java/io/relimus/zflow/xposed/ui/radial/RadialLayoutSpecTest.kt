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
            val step = (RadialGeometry.DEFAULT_SWEEP_DEGREES - 6f) / count * PI.toFloat() / 180f
            val chord = 2f * metrics.radius * sin(step / 2f)
            assertTrue(
                "count=$count 弧长间距 $chord 小于图标直径 ${metrics.iconDiameter}",
                chord >= metrics.iconDiameter,
            )
        }
    }

    @Test
    fun fanStaysInsideTheScreen() {
        val metrics = fit()
        val reach = metrics.radius + metrics.iconDiameter / 2f
        assertTrue("扇形超出屏幕: $reach", reach <= 1080f)
        assertTrue(metrics.outerRadius >= metrics.radius)
    }

    @Test
    fun smallScreensShrinkTheFan() {
        val metrics = fit(width = 360, height = 640, density = 1.5f)
        assertTrue("小屏应触发等比缩放", metrics.fitScale < 1f)
        assertTrue(metrics.fitScale >= 0.55f)
        assertTrue(metrics.radius + metrics.iconDiameter / 2f <= 360f)
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
        assertTrue(custom.enterRadius > default.enterRadius)
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
