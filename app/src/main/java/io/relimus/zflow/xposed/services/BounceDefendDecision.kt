package io.relimus.zflow.xposed.services

/**
 * 纯逻辑：区分「用户主动展开」与「系统意外弹回」的时间判定。
 *
 * 与任何 Android/Xposed 依赖解耦，便于在纯 JVM 单元测试中直接验证。
 * 被 FreeformManager 用于 onTaskDisplayChanged 中判断一次弹回默认屏
 * 是否应当就地拦截移回（而非销毁重建）。
 */
object BounceDefendDecision {
    // 最近一次「主动展开」期望窗口：在此时间窗内弹回视为用户主动展开，放行不拦截。
    const val EXPECTED_MOVE_TO_DEFAULT_WINDOW_MS = 1500L
    // 弹回拦截冷却：两次拦截之间至少间隔该时长，防止系统反复弹回导致的拦截-移回死循环。
    const val BOUNCE_DEFEND_COOLDOWN_MS = 1200L

    /**
     * 给定「最近主动展开时刻」与「最近弹回拦截时刻」，判断当前是否可以拦截一次弹回。
     * 两者任一落在各自时间窗内都会否决拦截。
     */
    fun shouldAllowBounceDefend(
        now: Long,
        lastExpectedMoveToDefault: Long?,
        lastBounceDefend: Long?,
        expectedWindowMs: Long = EXPECTED_MOVE_TO_DEFAULT_WINDOW_MS,
        defendCooldownMs: Long = BOUNCE_DEFEND_COOLDOWN_MS
    ): Boolean {
        if (lastExpectedMoveToDefault != null &&
            now - lastExpectedMoveToDefault < expectedWindowMs
        ) {
            return false
        }
        if (lastBounceDefend != null &&
            now - lastBounceDefend < defendCooldownMs
        ) {
            return false
        }
        return true
    }
}