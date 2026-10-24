package io.relimus.zflow.xposed.ui.wheel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 角触发判定的行为约束。
 *
 * 这些用例锁住的是「真实拇指能不能命中」这件事，而不是精确的像素阈值：
 * 阈值本身会调，但下面几条语义必须一直成立。
 */
class CornerTriggerTest {

    private val w = 1080
    private val h = 2400

    @Test
    fun `down in top-left corner arms the trigger`() {
        val hit = CornerTrigger.onDown(30f, 120f, w, h)
        assertNotNull(hit)
        assertEquals(true, hit!!.isLeft)
        assertEquals(true, hit.isUpper)
    }

    @Test
    fun `down in bottom-right corner arms the trigger`() {
        val hit = CornerTrigger.onDown(w - 30f, h - 120f, w, h)
        assertNotNull(hit)
        assertEquals(false, hit!!.isLeft)
        assertEquals(false, hit.isUpper)
    }

    @Test
    fun `edge-middle is not a corner`() {
        assertNull(CornerTrigger.onDown(30f, h / 2f, w, h))
    }

    @Test
    fun `middle of screen is not a corner`() {
        assertNull(CornerTrigger.onDown(w / 2f, h / 2f, w, h))
    }

    @Test
    fun `inward horizontal swipe from corner fires`() {
        CornerTrigger.onDown(30f, 120f, w, h)
        val moved = CornerTrigger.onMove(200f, 140f)
        assertNotNull(moved)
        assertEquals(true, moved!!.isLeft)
    }

    @Test
    fun `outward swipe from corner does not fire`() {
        CornerTrigger.onDown(30f, 120f, w, h)
        assertNull(CornerTrigger.onMove(0f, 130f))
    }

    @Test
    fun `vertical swipe from corner does not fire`() {
        CornerTrigger.onDown(30f, 120f, w, h)
        assertNull(CornerTrigger.onMove(40f, 500f))
    }

    @Test
    fun `move without a prior corner down does nothing`() {
        CornerTrigger.reset()
        assertNull(CornerTrigger.onMove(500f, 500f))
    }

    @Test
    fun `reset clears the armed state`() {
        CornerTrigger.onDown(30f, 120f, w, h)
        CornerTrigger.reset()
        assertNull(CornerTrigger.onMove(200f, 140f))
    }

    /** 回归：原阈值 24px 在正常拇指按压下几乎不可能命中。 */
    @Test
    fun `thumb near the edge still hits`() {
        assertNotNull(CornerTrigger.onDown(60f, 200f, w, h))
        assertNotNull(CornerTrigger.onDown(w - 60f, h - 200f, w, h))
    }

    @Test
    fun `unknown screen size never arms`() {
        assertNull(CornerTrigger.onDown(0f, 0f, 0, 0))
    }
}
