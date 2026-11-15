package io.relimus.zflow.xposed.hook

import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createHook
import io.relimus.zflow.xposed.hook.utils.XLog

/**
 * 框架层通用 Hook。
 *
 * 旧实现把每个小窗放进独立 VirtualDisplay，需要放行 `isCallerAllowedToLaunchOnDisplay`；
 * 新后端直接把真实任务接管成系统 freeform 小窗，不再需要这条放行，
 * 这里只保留初始化/热重载的骨架。
 */
object HookFramework {

    private const val TAG = "HookFramework"

    private var inited = false
    private var hookGeneration = -1L

    fun init() {
        if (inited && hookGeneration == HookRegistry.generation) return
        hookGeneration = HookRegistry.generation
        XLog.d("$TAG: framework hooks initialized (no display whitelist needed)")
        inited = true
    }
}
