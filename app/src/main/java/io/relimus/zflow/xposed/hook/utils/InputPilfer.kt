package io.relimus.zflow.xposed.hook.utils

import io.relimus.zflow.xposed.ui.radial.RadialTrace
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayDeque

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
 *   └─ mInputResourceDisplayModel : DisplayModel
 *        └─ displayResourceArray : SparseArray
 *             └─ [displayId] : TouchInteractionHandler$InputResource
 *                  └─ inputMonitorCompat : InputMonitorCompat
 *                       └─ pilferPointers() → android.view.InputMonitor.pilferPointers()
 * ```
 *
 * 之前这一版按方法名在对象图里找 `pilferPointers()`，注定找不到：
 * 桌面的 `InputConsumerController` 根本没有这个方法（只有 `mToken` / `mWindowManager`
 * / `registerInputConsumer`），真正的入口在 `InputMonitorCompat` 上。
 *
 * 现在按**类型**找（`InputMonitorCompat` 或 `android.view.InputMonitor`），
 * 再调它的无参 `pilferPointers()`。为此对象图搜索需要能穿透 `SparseArray`
 * 内部的 `Object[]`，否则会被卡在 `displayResourceArray` 这一层。
 *
 * 依然不写死字段名：路径是搜出来的、缓存起来复用，ROM 换了结构会重新搜。
 */
internal object InputPilfer {

    /**
     * 对象图搜索的深度上限。
     *
     * 实测路径是 4 层（handler → DisplayModel → SparseArray → InputResource），
     * 留一层余量给中间可能多出来的包装类。
     */
    private const val MAX_DEPTH = 5

    /** 单次搜索访问的对象数上限，防止误入特别大的对象图。只在首次解析时用一次。 */
    private const val MAX_NODES = 1200

    /** 数组展开的长度上限：只穿过 SparseArray 这类小容器，不碰大数组。 */
    private const val MAX_ARRAY_SIZE = 128

    /** 最后兜底：部分 ROM 会直接在 controller 上暴露这两个转发方法。 */
    private val FALLBACK_METHODS = listOf("pilferPointers", "notifyPilferPointers")

    /** 只用于规范 `InputMonitorCompat.pilferPointers()` 这一条。 */
    private const val MONITOR_METHOD = "pilferPointers"

    /** 从一个对象走到另一个对象的单步。 */
    private sealed class Step {
        abstract fun next(node: Any): Any?

        class FieldStep(val field: Field) : Step() {
            override fun next(node: Any): Any? = try {
                field.get(node)
            } catch (_: Throwable) {
                null
            }
        }

        class IndexStep(val index: Int) : Step() {
            override fun next(node: Any): Any? = (node as? Array<*>)?.getOrNull(index)
        }

        /** 直接调一个「从 root 取对象」的无参/单参 getter，参数在 resolve 时就已经确定。 */
        class GetterStep(val method: Method, val arg: Int) : Step() {
            override fun next(node: Any): Any? = try {
                method.invoke(node, arg)
            } catch (_: Throwable) {
                null
            }
        }

        /** 已经拿到持有者了，直接读字段（monitor 对象本身不随 root 变化）。 */
        class MonitorFieldStep(val field: Field, val holder: Any) : Step() {
            override fun next(node: Any): Any? = try {
                field.get(holder)
            } catch (_: Throwable) {
                null
            }
        }
    }

    private class Node(val value: Any, val path: List<Step>, val depth: Int)

    private class Plan(
        val rootClass: Class<*>,
        val path: List<Step>,
        val method: Method,
        val owner: String,
        /** 精确路径拿到的 monitor 实例：重放时优先用它，省掉每手势一次的反射取对象。 */
        val direct: Any? = null,
    )

    private val fieldCache = HashMap<Class<*>, Array<Field>>()

    /** key 是「类型 + 方法名」：同一个类型会分别查 pilferPointers 与 notifyPilferPointers。 */
    private val methodCache = HashMap<Pair<Class<*>, String>, Method?>()

    private var plan: Plan? = null

    /** 只在第一次失败时喊一声，之后静默：这个调用点在每条手势的 MOVE 里。 */
    private var loggedFailure = false

    @Volatile
    private var loggedSuccess = false

    /**
     * 尝试把输入抢过来。
     *
     * @param root 输入回调的 `thisObject`（TouchInteractionHandler 等），沿它搜入口
     * @param displayId 当前手势所在的显示 id，SparseArray 用这个下标取 InputResource
     * @return true 表示已经调用成功；调用方可以据此在日志里区分
     *         「桌面不动」和「找不到入口」两种情况
     */
    fun pilfer(root: Any?, displayId: Int = 0): Boolean {
        if (root == null) return false

        plan?.let { cached ->
            if (cached.rootClass.isInstance(root) && invoke(root, cached)) return true
            // 路径还在但对象已经换了（handler 重建 / monitor 被 dispose），重搜一次。
            plan = null
        }

        val resolved = resolve(root, displayId) ?: run {
            if (!loggedFailure) {
                loggedFailure = true
                RadialTrace.log("pilfer: no InputMonitor around ${root.javaClass.name}")
            }
            return false
        }
        plan = resolved
        val ok = invoke(root, resolved)
        if (ok && !loggedSuccess) {
            loggedSuccess = true
            RadialTrace.log(
                "pilfer ok via ${resolved.owner}#${resolved.method.name} depth=${resolved.path.size}"
            )
        }
        return ok
    }

    private fun invoke(root: Any, plan: Plan): Boolean {
        plan.direct?.let { monitor ->
            return try {
                plan.method.invoke(monitor)
                true
            } catch (_: Throwable) {
                // monitor 已被 dispose / handler 重建：交给调用方清缓存重搜。
                false
            }
        }

        var node: Any? = root
        for (step in plan.path) {
            node = step.next(node ?: return false) ?: return false
        }
        return try {
            plan.method.invoke(node)
            true
        } catch (throwable: Throwable) {
            if (!loggedFailure) {
                loggedFailure = true
                RadialTrace.log(
                    "pilfer invoke failed ${throwable.javaClass.simpleName}:${throwable.message}"
                )
            }
            false
        }
    }

    /**
     * 先在对象图里找 `InputMonitorCompat` / `InputMonitor`（正常路径），
     * 找不到再退回「任何带无参 pilferPointers() 的对象」。
     */
    private fun resolve(root: Any, displayId: Int): Plan? {
        findMonitor(root, displayId)?.let { return it }
        return findAnyPilferMethod(root)
    }

    /**
     * 精确路径：`TouchInteractionHandler.getInputResource(displayId)` 直接给出
     * `InputResource`，它的 `inputMonitorCompat` 字段就是要找的 monitor。
     *
     * 比全图搜索可靠得多 —— 后者要在几十个字段里撞对 `mInputResourceDisplayModel`，
     * 一旦撞不到就得靠 `MAX_NODES` 硬撑。方法名在本 ROM 上未被混淆
     * （`getInputResource`），换了名字也只是回到全图搜索，不会更糟。
     */
    private fun viaInputResource(root: Any, displayId: Int): Plan? {
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

        val slot = findMonitorField(holder) ?: return null
        val method = findMethod(slot.first.javaClass, MONITOR_METHOD) ?: return null
        return Plan(
            root.javaClass,
            listOf(
                Step.GetterStep(getter, displayId),
                Step.MonitorFieldStep(slot.second, slot.third),
            ),
            method,
            slot.first.javaClass.name,
            direct = slot.first,
        )
    }

    /** @return (monitor 实例, 它所在的字段, 字段的持有者) */
    private fun findMonitorField(holder: Any): Triple<Any, Field, Any>? {
        for (field in fieldsOf(holder.javaClass)) {
            if (Modifier.isStatic(field.modifiers)) continue
            if (!isMonitorType(field.type)) continue
            val value = try {
                field.isAccessible = true
                field.get(holder)
            } catch (_: Throwable) {
                null
            } ?: continue
            return Triple(value, field, holder)
        }
        return null
    }

    private fun findMonitor(root: Any, displayId: Int): Plan? {
        viaInputResource(root, displayId)?.let { return it }
        val found = walk(root) { value ->
            isMonitorType(value.javaClass) &&
                findMethod(value.javaClass, MONITOR_METHOD) != null
        } ?: return null
        val method = findMethod(found.value.javaClass, MONITOR_METHOD) ?: return null
        return Plan(root.javaClass, found.path, method, found.value.javaClass.name)
    }

    private fun findAnyPilferMethod(root: Any): Plan? {
        val found = walk(root) { value ->
            FALLBACK_METHODS.any { name -> findMethod(value.javaClass, name) != null }
        } ?: return null
        val method = FALLBACK_METHODS.firstNotNullOfOrNull { name ->
            findMethod(found.value.javaClass, name)
        } ?: return null
        return Plan(root.javaClass, found.path, method, found.value.javaClass.name)
    }

    /** 广度优先遍历对象图，返回第一个满足 [predicate] 的节点。 */
    private fun walk(root: Any, predicate: (Any) -> Boolean): Node? {
        val visited = HashSet<Int>()
        val queue = ArrayDeque<Node>()
        queue.add(Node(root, emptyList(), 0))
        visited.add(System.identityHashCode(root))

        var visitedCount = 0
        while (queue.isNotEmpty() && visitedCount < MAX_NODES) {
            val current = queue.removeFirst()
            visitedCount += 1

            if (predicate(current.value)) return current
            if (current.depth >= MAX_DEPTH) continue

            for (field in fieldsOf(current.value.javaClass)) {
                if (Modifier.isStatic(field.modifiers)) continue
                val type = field.type
                if (!isTraversable(type)) continue

                val child = try {
                    field.isAccessible = true
                    field.get(current.value)
                } catch (_: Throwable) {
                    null
                } ?: continue

                val step = Step.FieldStep(field)
                // SparseArray 的资源都放在内部的 Object[] 里，不展开就会卡在这一层。
                if (child is Array<*>) {
                    if (child.size > MAX_ARRAY_SIZE) continue
                    for (index in child.indices) {
                        val element = child[index] ?: continue
                        if (!visited.add(System.identityHashCode(element))) continue
                        queue.add(
                            Node(element, current.path + step + Step.IndexStep(index), current.depth + 1)
                        )
                    }
                    continue
                }

                if (!visited.add(System.identityHashCode(child))) continue
                queue.add(Node(child, current.path + step, current.depth + 1))
            }
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

    /** 只往下走「可能是对象容器」的类型：跳过基本类型、字符串、反射与线程相关的大对象。 */
    private fun isTraversable(type: Class<*>): Boolean {
        if (type.isPrimitive) return false
        if (type.isArray) return !type.componentType.isPrimitive
        val name = type.name
        if (name.startsWith("java.lang.String")) return false
        if (name.startsWith("java.lang.Class")) return false
        if (name.startsWith("java.lang.reflect.")) return false
        if (name.startsWith("java.util.concurrent.locks.")) return false
        if (name == "java.lang.Thread" || name == "java.lang.ClassLoader") return false
        // Context 会拖出整棵进程对象图（AMS / WMS 代理、资源缓存），一旦进去
        // MAX_NODES 立刻被吃光，而我们要找的东西永远在 InputResource 那一侧。
        if (android.content.Context::class.java.isAssignableFrom(type)) return false
        return true
    }
}
