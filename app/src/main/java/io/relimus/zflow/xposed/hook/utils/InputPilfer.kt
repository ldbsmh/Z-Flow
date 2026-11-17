package io.relimus.zflow.xposed.hook.utils

import io.relimus.zflow.xposed.ui.radial.RadialTrace
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 把这条输入流从「应用窗口」手里抢过来。
 *
 * 为什么需要它：Launcher 与 TIS（TouchInteractionService）之间是**监视**关系，
 * 不是在窗口前面加了一层消费层。TIS 的输入监视只是拿到一份事件副本，
 * 应用自己的窗口（在本例里就是桌面）**同时**也在收同一串事件。
 * 平时看不出问题，因为 TIS 一旦决定处理手势就会调 `pilferPointers()`，
 * 由 InputDispatcher 给应用窗口补一个 ACTION_CANCEL，把后续事件独家转给监视方。
 *
 * 我们的 hook 是在 `TouchInteractionHandler.onInputEvent` 入口把方法短路掉，
 * 于是那条 `pilferPointers()` 永远不会执行 —— 结果是：桌面进程里我们自己的手
 * 没收到后续事件，可桌面窗口**照旧**收到了，用户在轮盘上滑动挑选时，
 * 后面的工作区就跟着一起滑。
 *
 * ## 调用路径（Android 17 桌面实测）
 *
 * ```
 * TouchInteractionHandler
 *   └─ getInputResource(displayId) : InputResource
 *        └─ inputMonitorCompat : InputMonitorCompat
 *             └─ pilferPointers() → android.view.InputMonitor.pilferPointers()
 * ```
 *
 * **只有这一条路径**。以前还留过「在对象图里瞎搜 pilferPointers()」的兜底，
 * 那是错的：桌面的 `InputConsumerController` 根本没有这个方法（只有 `mToken` /
 * `mWindowManager` / `registerInputConsumer`），搜到的往往是别的类的同名方法，
 * 调了没效果却报告成功 —— 测试时完全分不清到底是抢到了还是没抢到。
 * 现在找不到入口就返回 false，让「桌面跟手」这个现象直接暴露出来。
 */
internal object InputPilfer {

    /** 只在 `InputMonitorCompat` / `InputMonitor` 上找这一个方法。 */
    private const val MONITOR_METHOD = "pilferPointers"

    /** 解析出来的调用计划：monitor 实例 + 它自己的 pilferPointers。 */
    private class Plan(val rootClass: Class<*>, val monitor: Any, val method: Method)

    private val fieldCache = HashMap<Class<*>, Array<Field>>()
    private val methodCache = HashMap<Pair<Class<*>, String>, Method?>()

    private var plan: Plan? = null

    /** 只在第一次失败时喊一声，之后静默：这个调用点在每条手势的 MOVE 里。 */
    private var loggedFailure = false

    @Volatile
    private var loggedSuccess = false

    /**
     * 尝试把输入抢过来。
     *
     * @param root 输入回调的 `thisObject`（TouchInteractionHandler）
     * @param displayId 当前手势所在的显示 id，`getInputResource(displayId)` 用
     * @return true 表示 pilferPointers 调用成功；false 时桌面会继续收事件
     */
    fun pilfer(root: Any?, displayId: Int = 0): Boolean {
        if (root == null) return false

        plan?.let { cached ->
            if (cached.rootClass.isInstance(root) && invoke(cached)) return true
            // monitor 已被 dispose / handler 重建：重解析一次。
            plan = null
        }

        val resolved = resolve(root, displayId) ?: run {
            if (!loggedFailure) {
                loggedFailure = true
                RadialTrace.log(
                    "pilfer unavailable: ${root.javaClass.name} 没有 getInputResource/" +
                        "InputMonitorCompat，桌面会继续收事件"
                )
            }
            return false
        }
        plan = resolved
        val ok = invoke(resolved)
        if (ok && !loggedSuccess) {
            loggedSuccess = true
            RadialTrace.log("pilfer ok via ${resolved.monitor.javaClass.name}#$MONITOR_METHOD")
        }
        return ok
    }

    private fun invoke(plan: Plan): Boolean = try {
        plan.method.invoke(plan.monitor)
        true
    } catch (_: Throwable) {
        false
    }

    /**
     * `TouchInteractionHandler.getInputResource(displayId)` → `InputResource`
     * → 其中的 `InputMonitorCompat` 字段。
     *
     * 方法名在本 ROM 上未被混淆；换了名字就会解析失败（直接表现为桌面跟手），
     * 不会像以前那样悄悄退到别处去。
     */
    private fun resolve(root: Any, displayId: Int): Plan? {
        val getter = try {
            root.javaClass.declaredMethods.firstOrNull {
                it.name == "getInputResource" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
        } catch (_: Throwable) {
            null
        } ?: return null

        val holder = try {
            getter.isAccessible = true
            getter.invoke(root, displayId)
        } catch (_: Throwable) {
            null
        } ?: return null

        val monitor = findMonitor(holder) ?: return null
        val method = findMethod(monitor.javaClass, MONITOR_METHOD) ?: return null
        return Plan(root.javaClass, monitor, method)
    }

    /** 取 `InputResource.inputMonitorCompat`（按类型认，不写死字段名）。 */
    private fun findMonitor(holder: Any): Any? {
        for (field in fieldsOf(holder.javaClass)) {
            if (Modifier.isStatic(field.modifiers)) continue
            if (!isMonitorType(field.type)) continue
            val value = try {
                field.isAccessible = true
                field.get(holder)
            } catch (_: Throwable) {
                null
            } ?: continue
            return value
        }
        return null
    }

    private fun findMethod(type: Class<*>, name: String): Method? {
        val cacheKey = type to name
        synchronized(methodCache) {
            if (methodCache.containsKey(cacheKey)) return methodCache[cacheKey]
        }
        var found: Method? = null
        var clazz: Class<*>? = type
        var depth = 0
        while (clazz != null && clazz != Any::class.java && depth < 5) {
            val method = try {
                clazz.declaredMethods.firstOrNull {
                    it.name == name && it.parameterCount == 0
                }
            } catch (_: Throwable) {
                null
            }
            if (method != null) {
                val accessible = try {
                    method.isAccessible = true
                    true
                } catch (_: Throwable) {
                    false
                }
                if (accessible) {
                    found = method
                    break
                }
            }
            clazz = clazz.superclass
            depth += 1
        }
        synchronized(methodCache) { methodCache[cacheKey] = found }
        return found
    }

    private fun isMonitorType(type: Class<*>): Boolean {
        val name = type.name
        return name == "android.view.InputMonitor" || name.endsWith("InputMonitorCompat")
    }

    private fun fieldsOf(type: Class<*>): Array<Field> {
        synchronized(fieldCache) {
            fieldCache[type]?.let { return it }
        }
        val collected = ArrayList<Field>(16)
        var clazz: Class<*>? = type
        var depth = 0
        while (clazz != null && clazz != Any::class.java && depth < 4) {
            try {
                collected.addAll(clazz.declaredFields)
            } catch (_: Throwable) {
                // 某些 ROM 的隐藏类会抛 SecurityException，跳过即可
            }
            clazz = clazz.superclass
            depth += 1
        }
        val array = collected.toTypedArray()
        synchronized(fieldCache) { fieldCache[type] = array }
        return array
    }
}
