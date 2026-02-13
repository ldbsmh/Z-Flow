package io.relimus.zflow.xposed.hook

import io.relimus.zflow.xposed.hook.utils.XLog
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.kyuubiran.ezxhelper.xposed.EzXposed

class MainHook : IXposedHookLoadPackage, IXposedHookZygoteInit {

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        EzXposed.initZygote(startupParam)
        HookFramework.initZygote()
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        EzXposed.initHandleLoadPackage(lpparam)

        when (lpparam.packageName) {
            "android" -> {
                XLog.d("MainHook: init HookFramework for android (system_server)")
                HookFramework.init()
                // Initialize system_server hooks for FreeformManager
                HookSystem.init()
                HookReload.init()
            }
            "io.relimus.zflow" -> {
                XLog.d("MainHook: init HookMyself")
                HookMyself.init()
            }
            "com.android.systemui" -> {
                XLog.d("MainHook: init HookSystemUI")
                HookSystemUI.init()
            }
            "com.android.launcher3" -> {
                HookLauncher.init()
            }
        }
    }
}
