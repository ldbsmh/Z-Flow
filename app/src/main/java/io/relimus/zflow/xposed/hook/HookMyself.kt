package io.relimus.zflow.xposed.hook

import de.robv.android.xposed.XC_MethodHook
import io.github.kyuubiran.ezxhelper.core.util.ClassUtil.loadClass
import io.relimus.zflow.xposed.hook.utils.XLog

object HookMyself {

    fun init() {
        if (inited && hookGeneration == HookRegistry.generation) return
        hookGeneration = HookRegistry.generation
        inited = false

        runCatching {
            val hookTestClazz = loadClass("io.relimus.zflow.xposed.hook.utils.HookTest")

            HookRegistry.findAndHookMethod(
                hookTestClazz,
                "checkXposed",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        param.result = true
                    }
                }
            )

            XLog.d("HookMyself: hook checkXposed success")
        }.onFailure {
            XLog.e("HookMyself init failed", it)
        }

        inited = true
    }

    private var inited = false
    private var hookGeneration = -1L
}
