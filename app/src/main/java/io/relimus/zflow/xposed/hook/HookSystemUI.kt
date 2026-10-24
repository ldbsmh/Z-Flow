package io.relimus.zflow.xposed.hook

import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder
import io.github.kyuubiran.ezxhelper.core.util.ClassUtil.loadClass
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createHooks
import io.relimus.zflow.xposed.hook.utils.XLog
import java.util.Collections
import java.util.WeakHashMap

/**
 * SystemUI hooks for freeform display layout.
 * 不再包含旧的通知图标替换 Hook（方案 A 下原通知保持原样，无需替换图标）。
 */
object HookSystemUI {

    fun init() {
        if (inited && hookGeneration == HookRegistry.generation) return
        hookGeneration = HookRegistry.generation
        inited = false

        runCatching { hookDisplayLayout() }
            .onFailure { XLog.e("HookSystemUI display hook failed", it) }

        inited = true
    }

    private var inited = false
    private var hookGeneration = -1L

    private fun hookDisplayLayout() {
        val clazz = loadClass("com.android.wm.shell.common.DisplayLayout")
        // 每个 DisplayLayout 实例只回填自己的最后一次非空参数；
        // 全局单槽会把 A 屏的 DisplayInfo 填给 B 屏
        val lastByLayout = Collections.synchronizedMap(WeakHashMap<Any, Any>())
        MethodFinder.fromClass(clazz).filterByName("set").toList().createHooks {
            before {
                val obj = it.args[0]
                if (obj != null) lastByLayout[it.thisObject] = obj
                else it.args[0] = lastByLayout[it.thisObject]
            }
        }
    }
}