package io.relimus.zflow.xposed.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖 BounceDefendDecision.shouldAllowBounceDefend 的纯时间判定逻辑。
 * 该判定用于区分「用户主动展开（应放行）」与「系统意外弹回（应拦截移回）」，
 * 以及防止系统反复弹回导致的拦截-移回死循环。
 */
class BounceDefendDecisionTest {

    private val now = 10_000L

    @Test
    fun noPreviousEventsAllowsDefend() {
        assertTrue(BounceDefendDecision.shouldAllowBounceDefend(now, null, null))
    }

    @Test
    fun expectedMoveToDefaultBlocksDefendWithinWindow() {
        // 用户主动展开后立刻弹回（在 1500ms 期望窗内）——应放行，不拦截。
        assertFalse(
            BounceDefendDecision.shouldAllowBounceDefend(now, lastExpectedMoveToDefault = now - 100L, lastBounceDefend = null)
        )
    }

    @Test
    fun expectedMoveToDefaultExpiredAllowsDefend() {
        // 期望窗过后再弹回——视为新的意外弹回，可拦截。
        assertTrue(
            BounceDefendDecision.shouldAllowBounceDefend(now, lastExpectedMoveToDefault = now - 5000L, lastBounceDefend = null)
        )
    }

    @Test
    fun recentBounceDefendBlocksDefendWithinCooldown() {
        // 刚拦截过一次（1200ms 冷却内）又弹回——冷却中，跳过避免死循环。
        assertFalse(
            BounceDefendDecision.shouldAllowBounceDefend(now, null, lastBounceDefend = now - 200L)
        )
    }

    @Test
    fun bounceDefendCooldownExpiredAllowsDefend() {
        assertTrue(
            BounceDefendDecision.shouldAllowBounceDefend(now, null, lastBounceDefend = now - 5000L)
        )
    }

    @Test
    fun bothEventsRespectedShortestGateWins() {
        // 期望未过期 → 放行（false）；即使冷却也没用上。
        assertFalse(
            BounceDefendDecision.shouldAllowBounceDefend(now, now - 100L, now - 500L)
        )
        // 期望过期但冷却未过 → 跳过（false）。
        assertFalse(
            BounceDefendDecision.shouldAllowBounceDefend(now, now - 5000L, now - 100L)
        )
        // 两者都过期 → 允许拦截。
        assertTrue(
            BounceDefendDecision.shouldAllowBounceDefend(now, now - 5000L, now - 5000L)
        )
    }

    @Test
    fun configurableWindowAllowsCustomTiming() {
        // 自定义期望窗为 50ms：100ms 前主动展开已过期 → 允许拦截。
        assertTrue(
            BounceDefendDecision.shouldAllowBounceDefend(
                now,
                lastExpectedMoveToDefault = now - 100L,
                lastBounceDefend = null,
                expectedWindowMs = 50L
            )
        )
        // 同一时刻但期望窗为 200ms：仍处于期望窗内 → 应放行（不拦截）。
        assertFalse(
            BounceDefendDecision.shouldAllowBounceDefend(
                now,
                lastExpectedMoveToDefault = now - 100L,
                lastBounceDefend = null,
                expectedWindowMs = 200L
            )
        )
    }
}