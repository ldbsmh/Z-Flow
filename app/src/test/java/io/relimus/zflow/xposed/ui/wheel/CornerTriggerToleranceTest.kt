package io.relimus.zflow.xposed.ui.wheel

import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 落点宽容量级的回归保护。
 *
 * 只在拿不到真机手势时防止阈值被改回去：最初的 24px 在 1080p 上不足 8dp，
 * 1080x2400 的屏幕上 60px 的落点都算「没压到边缘」，那次线上就是不触发的。
 */
class CornerTriggerToleranceTest {

    private val w = 1080
    private val h = 2400

    @Test
    fun `edge tolerance covers a real thumb`() {
        // 60px ≈ 20dp @ 480dpi，正常拇指压边就是落在这个范围
        assertNotNull(CornerTrigger.onDown(60f, 120f, w, h))
        assertNotNull(CornerTrigger.onDown(w - 60f, 120f, w, h))
    }

    @Test
    fun `corner tolerance survives status bar and gesture bar`() {
        // 顶部 200px / 底部 200px 仍应算作角落
        assertNotNull(CornerTrigger.onDown(40f, 200f, w, h))
        assertNotNull(CornerTrigger.onDown(40f, h - 200f, w, h))
    }

    @Test
    fun `tolerances stay sane`() {
        // 防止有人把它们改回个位数；也防止放大到吃掉半屏
        val edge = CornerTrigger.DEFAULT_EDGE_TOLERANCE_PX
        val corner = CornerTrigger.DEFAULT_CORNER_TOLERANCE_PX
        assert(edge in 40f..200f) { "edgeTolerance=$edge 不在合理区间" }
        assert(corner in 160f..400f) { "cornerTolerance=$corner 不在合理区间" }
        assert(corner < h / 2f) { "cornerTolerance 吞掉了半屏" }
    }
}
