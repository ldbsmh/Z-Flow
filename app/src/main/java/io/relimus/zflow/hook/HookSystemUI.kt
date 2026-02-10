package io.relimus.zflow.hook

import io.relimus.zflow.hook.utils.XLog
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder
import io.github.kyuubiran.ezxhelper.core.util.ClassUtil.loadClass
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createHooks

object HookSystemUI {

    fun init() {
        runCatching {
            hookDisplayLayout()
        }.onFailure {
            XLog.e("HookSystemUI init failed", it)
        }
    }

    private fun hookDisplayLayout() {
        val displayLayoutClazz = loadClass("com.android.wm.shell.common.DisplayLayout")
        var lastObj: Any? = null

        MethodFinder.fromClass(displayLayoutClazz)
            .filterByName("set")
            .toList()
            .createHooks {
                before {
                    val obj = it.args[0]
                    if (obj != null) {
                        lastObj = obj
                    } else {
                        it.args[0] = lastObj
                    }
                }
            }

        XLog.d("HookSystemUI: hook DisplayLayout.set success")
    }
}
