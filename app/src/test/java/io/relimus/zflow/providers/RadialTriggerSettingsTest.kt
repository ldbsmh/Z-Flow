package io.relimus.zflow.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 触发区域的几何。
 *
 * 这块区现在是用户自己摆的，所以「设置改了，命中范围真的跟着改」必须能被测试覆盖 ——
 * 之前在真机上踩过一次「滑杆改了但热区没动」的坑，靠肉眼是看不出来的。
 */
class RadialTriggerSettingsTest {

    private val width = 1440f
    private val height = 3200f

    @Test
    fun defaultAreaCoversBothBottomCorners() {
        val config = RadialTriggerSettings.Config()
        assertNotNull(
            "左下角必须命中（默认镜像）",
            config.hit(40f, height - 40f, width, height),
        )
        assertNotNull(
            "右下角必须命中",
            config.hit(width - 40f, height - 40f, width, height),
        )
        assertNull(
            "屏幕中间不该命中",
            config.hit(width / 2f, height * 0.6f, width, height),
        )
    }

    /** 默认主区落在**右侧**：镜像只是把另一侧补齐，不该反过来。 */
    @Test
    fun defaultAreaSitsOnTheRightSide() {
        val config = RadialTriggerSettings.Config()
        assertTrue(
            "默认触发区中心应当在右半边，实际 ${config.area(width, height).centerX}",
            config.area(width, height).centerX > width / 2f,
        )
        val hit = config.hit(width - 40f, height - 40f, width, height)
        assertNotNull(hit)
        assertFalse("右下角命中时扇形依附右侧", hit!!.leftSide)
    }

    /** 关掉镜像、挪到右侧后，左侧必须完全不响应（把左边让给系统手势）。 */
    @Test
    fun mirrorOffKeepsOnlyTheConfiguredSide() {
        val config = RadialTriggerSettings.Config(
            centerXPercent = 92,
            centerYPercent = 95,
            widthPercent = 16,
            heightPercent = 12,
            mirror = false,
        )
        val hit = config.hit(width - 30f, height - 60f, width, height)
        assertNotNull(hit)
        assertFalse("右侧触发区应当依附右下角", hit!!.leftSide)
        assertNull(
            "镜像关闭后左侧不应命中",
            config.hit(30f, height - 60f, width, height),
        )
    }

    /** 圆心跟着触发区中心走：纵向位置改了，圆心内缩量同步变化。 */
    @Test
    fun originFollowsTheAreaCentre() {
        val bottom = RadialTriggerSettings.Config(centerYPercent = 96, heightPercent = 8)
        val raised = RadialTriggerSettings.Config(centerYPercent = 80, heightPercent = 8)

        val bottomHit = bottom.hit(60f, height * 0.96f, width, height)!!
        val raisedHit = raised.hit(60f, height * 0.80f, width, height)!!

        assertEquals(height - bottomHit.area.centerY, bottomHit.originInsetY, 0.01f)
        assertEquals(height - raisedHit.area.centerY, raisedHit.originInsetY, 0.01f)
        assertTrue(
            "触发区上移后圆心也要上移，bottom=${bottomHit.originInsetY} raised=${raisedHit.originInsetY}",
            raisedHit.originInsetY > bottomHit.originInsetY,
        )
    }

    /** 宽度加大后命中范围必须真的变大，而不是「只是画得更大」。 */
    @Test
    fun widerAreaHitsFurtherInside() {
        val narrow = RadialTriggerSettings.Config(
            centerXPercent = 10,
            centerYPercent = 95,
            widthPercent = 10,
            heightPercent = 10,
            mirror = false,
        )
        val wide = narrow.copy(widthPercent = 40)
        val probeX = width * 0.28f
        val probeY = height * 0.95f
        assertNull(narrow.hit(probeX, probeY, width, height))
        assertNotNull(wide.hit(probeX, probeY, width, height))
    }

    /** 区域被夹在屏幕内：中心贴边时不能出现负坐标。 */
    @Test
    fun areaIsClampedToTheScreen() {
        val config = RadialTriggerSettings.Config(
            centerXPercent = 0,
            centerYPercent = 100,
            widthPercent = 30,
            heightPercent = 20,
        )
        val area = config.area(width, height)
        assertEquals("贴左边时 left 必须夹到 0", 0f, area.left, 0.01f)
        assertEquals("宽度只有一半露在屏幕内", width * 0.15f, area.right, 0.01f)
        assertEquals(height, area.bottom, 0.01f)
        assertTrue(area.top >= 0f)
    }

    /** 居中且不贴边时，镜像区与主区重合：只留一块，避免同一点被数两次。 */
    @Test
    fun centredAreaWithoutMirrorOffsetIsNotDuplicated() {
        val config = RadialTriggerSettings.Config(centerXPercent = 50, mirror = true)
        assertEquals(1, config.areas(width, height).size)
    }

    @Test
    fun outOfRangeValuesAreSanitised() {
        val config = RadialTriggerSettings.Config(
            centerXPercent = -20,
            centerYPercent = 400,
            widthPercent = 0,
            heightPercent = 999,
        ).sanitized()
        assertEquals(RadialTriggerSettings.MIN_CENTER_X, config.centerXPercent)
        assertEquals(RadialTriggerSettings.MAX_CENTER_Y, config.centerYPercent)
        assertEquals(RadialTriggerSettings.MIN_WIDTH, config.widthPercent)
        assertEquals(RadialTriggerSettings.MAX_HEIGHT, config.heightPercent)
        assertNull("非有限坐标不应该命中", config.hit(Float.NaN, 10f, width, height))
    }
}
