package com.sunshine.freeform.hook

import com.sunshine.freeform.hook.utils.XLog
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder
import io.github.kyuubiran.ezxhelper.core.util.ClassUtil.loadClass
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createHook

object HookMyself {

    fun init(classLoader: ClassLoader) {
        runCatching {
            val hookTestClazz = loadClass("com.sunshine.freeform.hook.utils.HookTest", classLoader)

            MethodFinder.fromClass(hookTestClazz)
                .filterByName("checkXposed")
                .filterEmptyParam()
                .first()
                .createHook {
                    returnConstant(true)
                }

            XLog.d("HookMyself: hook checkXposed success")
        }.onFailure {
            XLog.e("HookMyself init failed", it)
        }
    }
}
