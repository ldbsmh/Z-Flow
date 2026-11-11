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
 * 不是在窗口前面加了一层消费层。TIS 的 `InputMonitor` 只是拿到一份事件副本，
 * 应用自己的窗口（在本例里就是桌面）**同时**也在收同一串事件。
 * 平时看不出问题，因为 TIS 一旦决定处理手势就会调用 `pilferPointers()`，
 * 由 InputDispatcher 给应用窗口补一个 ACTION_CANCEL，把后续事件独家转给监视方。
 *
 * 我们的 hook 是在 `TouchInteractionHandler.onInputEvent` 入口直接把方法短路掉，
 * 于是那条 `pilferPointers()` 永远不会执行 —— 结果是：桌面进程里我们自己的手
 * （`recents_animation_input_consumer`）没收到事件，可桌面窗口**照旧**收到了，
 * 用户在轮盘上滑动挑选时，后面的工作区就跟着一起滑。
 *
 * 所以这里在 `ACTION_DOWN` 命中触发区时，主动沿对象图找到 TIS 手里的
 * `InputMonitor` / `InputConsumer`，调用它的 `pilferPointers()`。
 *
 * 没有走「合成的 ACTION_CANCEL」那条路：那条路要求我们在应用处理完 DOWN 之后
 * 再补一条 CANCEL，时序上没法保证，而且之后仍会有 MOVE 落到已经完全没接触摸
 * 序列的视图树上。直接让 InputDispatcher 去发 CANCEL 才是干净的做法。
 *
 * 反射是沿对象图按**方法名**搜的，不写死字段名与类名：`InputConsumerController`
 * 在不同 Android 版本里的内部结构换过好几次，写死就会在某次系统升级后静默失效。
 */
internal object InputPilfer {

    /** 对象图搜索的深度上限。真实链路是 handler → controller → monitor，3 层足够。 */
    private const val MAX_DEPTH = 4

    /** 单次搜索访问的对象数上限，防止误入特别大的对象图。 */
    private const val MAX_NODES = 160

    /** 命中后调用哪个方法（前者是标准名，后者是部分 ROM 的转发方法）。 */
    private val CANDIDATE_METHODS = listOf("pilferPointers", "notifyPilferPointers")

    private class Node(val value: Any, val path: List<Field>, val depth: Int)

    private class Plan(
        val rootClass: Class<*>,
        val path: List<Field>,
        val method: Method,
        val owner: String,
    )

    private val fieldCache = HashMap<Class<*>, Array<Field>>()
    private val methodCache = HashMap<Class<*>, Method?>()

    private var plan: Plan? = null

    /** 只在第一次失败时喊一声，之后静默：这个调用点在每条手势的 DOWN 里。 */
    private var loggedFailure = false

    @Volatile
    private var loggedSuccess = false

    /**
     * 尝试把输入抢过来。
     *
     * @param root 输入回调的 `thisObject`（TIS / TouchInteractionHandler 等）
     * @return true 表示已经调用成功；调用方可以据此在日志里区分
     *         「桌面不动」和「反射没找到入口」两种情况
     */
    fun pilfer(root: Any?): Boolean {
        if (root == null) return false

        plan?.let { cached ->
            if (cached.rootClass.isInstance(root) && invoke(root, cached)) return true
            plan = null
        }

        val resolved = resolve(root)
        if (resolved == null) {
            if (!loggedFailure) {
                loggedFailure = true
                RadialTrace.log("pilfer: no pilferPointers() around ${root.javaClass.name}")
            }
            return false
        }
        plan = resolved
        val ok = invoke(root, resolved)
        if (ok && !loggedSuccess) {
            loggedSuccess = true
            RadialTrace.log("pilfer ok via ${resolved.owner}#${resolved.method.name} depth=${resolved.path.size}")
        }
        return ok
    }

    private fun invoke(root: Any, plan: Plan): Boolean {
        var node: Any = root
        for (field in plan.path) {
            node = try {
                field.get(node) ?: return false
            } catch (_: Throwable) {
                return false
            }
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

    private fun resolve(root: Any): Plan? {
        val visited = HashSet<Int>()
        val queue = ArrayDeque<Node>()
        queue.add(Node(root, emptyList(), 0))
        visited.add(System.identityHashCode(root))

        var visitedCount = 0
        while (queue.isNotEmpty() && visitedCount < MAX_NODES) {
            val current = queue.removeFirst()
            visitedCount += 1

            findPilferMethod(current.value)?.let { method ->
                return Plan(root.javaClass, current.path, method, current.value.javaClass.name)
            }

            if (current.depth >= MAX_DEPTH) continue

            for (field in fieldsOf(current.value.javaClass)) {
                if (Modifier.isStatic(field.modifiers)) continue
                if (!isTraversable(field.type)) continue
                val child = try {
                    field.isAccessible = true
                    field.get(current.value)
                } catch (_: Throwable) {
                    null
                } ?: continue
                if (!visited.add(System.identityHashCode(child))) continue
                queue.add(Node(child, current.path + field, current.depth + 1))
            }
        }
        return null
    }

    private fun findPilferMethod(value: Any): Method? {
        val type = value.javaClass
        synchronized(methodCache) {
            if (methodCache.containsKey(type)) return methodCache[type]
        }
        var found: Method? = null
        for (name in CANDIDATE_METHODS) {
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
            if (found != null) break
        }
        synchronized(methodCache) { methodCache[type] = found }
        return found
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
        if (type.isPrimitive || type.isArray) return false
        val name = type.name
        if (name.startsWith("java.lang.String")) return false
        if (name.startsWith("java.lang.Class")) return false
        if (name.startsWith("java.lang.reflect.")) return false
        if (name.startsWith("java.util.concurrent.locks.")) return false
        if (name == "java.lang.Thread" || name == "java.lang.ClassLoader") return false
        return true
    }
}
