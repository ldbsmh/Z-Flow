package io.relimus.zflow.xposed.hook

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.relimus.zflow.xposed.hook.utils.XLog
import java.util.Collections
import java.util.WeakHashMap

/**
 * 全局 Hook 注册表，支持热重载。
 *
 * 所有直接创建的 Hook（通过 [hookAll] / [findAndHookMethod] / [hookMethod]）
 * 都会登记 Unhook，热重载时调用 [reset] 统一取消。
 *
 * 热重载重初始化判定使用单调递增的 [generation]：
 * 每个 Hook 在自身 init 时把 [generation] 记入自己的 hookGeneration，
 * 下次 init 时若相等说明该 Hook 已是最新，跳过；否则重新初始化。
 * 多个 Hook 互不干扰（不存在共享布尔被消费的问题）。
 *
 * 注意：经 ezxhelper DSL（createHook/createHooks）创建的 Hook 不暴露 Unhook，
 * 无法被本注册表取消；这些 Hook 依赖 LSPosed 进程重启完成热重载，
 * 但 generation 机制仍能保证它们在新进程中正确重新初始化。
 */
object HookRegistry {

    private const val TAG = "HookRegistry"

    private val allUnhooks = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<XC_MethodHook.Unhook, Boolean>())
    )

    /**
     * 当前代数。每次 reset 递增。
     * 各 Hook 应把此值记入自己的 hookGeneration，用于判断是否需要重新初始化。
     */
    @Volatile
    var generation: Long = 0L
        private set

    /** 登记一个已有的 Unhook。 */
    fun register(unhook: XC_MethodHook.Unhook) {
        if (unhook != null) allUnhooks.add(unhook)
    }

    /**
     * 包装 XposedBridge.hookAllMethods，自动存储 Unhook。
     * 返回 hook 数量。
     */
    fun hookAll(
        clazz: Class<*>,
        methodName: String,
        callback: XC_MethodHook
    ): Int {
        val unhooks = XposedBridge.hookAllMethods(clazz, methodName, callback)
        allUnhooks.addAll(unhooks)
        return unhooks.size
    }

    /**
     * 包装 XposedBridge.hookMethod，自动存储 Unhook。
     */
    fun hookMethod(
        method: java.lang.reflect.Method,
        callback: XC_MethodHook
    ): XC_MethodHook.Unhook {
        val unhook = XposedBridge.hookMethod(method, callback)
        allUnhooks.add(unhook)
        return unhook
    }

    /**
     * 包装 XposedHelpers.findAndHookMethod（可变参数版，末位为回调）。
     * 自动存储 Unhook。
     */
    fun findAndHookMethod(
        clazz: Class<*>,
        methodName: String,
        vararg parameterTypesAndCallback: Any?
    ): XC_MethodHook.Unhook {
        val unhook = XposedHelpers.findAndHookMethod(
            clazz, methodName, *parameterTypesAndCallback
        )
        allUnhooks.add(unhook)
        return unhook
    }

    /**
     * 重置：取消所有已登记钩子，并递增代数（触发各 Hook 重新初始化）。
     * 在 MainHook.handleLoadPackage 最前面调用。
     */
    fun reset() {
        val count = allUnhooks.size
        if (count > 0) {
            XLog.d("$TAG: unhooking $count hooks")
            synchronized(allUnhooks) {
                allUnhooks.forEach { unhook ->
                    runCatching { unhook.unhook() }.onFailure {
                        XLog.e("$TAG: unhook failed", it)
                    }
                }
                allUnhooks.clear()
            }
        }
        generation += 1L
        XLog.d("$TAG: reset completed, generation=$generation")
    }
}