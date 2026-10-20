package io.relimus.zflow.xposed.hook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.kyuubiran.ezxhelper.xposed.EzXposed
import io.relimus.zflow.xposed.hook.utils.XLog

class MainHook : IXposedHookLoadPackage, IXposedHookZygoteInit {

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        EzXposed.initZygote(startupParam)
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        EzXposed.initHandleLoadPackage(lpparam)

        // 热重载：取消所有旧钩子，并标记各 Hook 重新初始化
        HookRegistry.reset()

        when (lpparam.packageName) {
            "android" -> {
                // 这些 Hook 都跑在 system_server 里。
                // 任何一个在初始化阶段抛异常，异常都会冒泡出 handleLoadPackage，
                // 直接导致 system_server 崩溃 → 系统软重启（开机循环）。
                // 这里逐个 runCatching 兜底：单个 Hook 不兼容时只禁用该功能，
                // 绝不让它带崩系统进程。
                runCatching { HookFramework.init() }
                    .onFailure { XLog.e("HookFramework.init failed", it) }

                runCatching { HookSystem.init() }
                    .onFailure { XLog.e("HookSystem.init failed", it) }

                runCatching { HookImeInsetsBridge.init() }
                    .onFailure { XLog.e("HookImeInsetsBridge.init failed", it) }

                runCatching { HookImeAdjustResize.init() }
                    .onFailure { XLog.e("HookImeAdjustResize.init failed", it) }

                runCatching { HookReload.init() }
                    .onFailure { XLog.e("HookReload.init failed", it) }

                runCatching { HookPredictiveBack.init() }
                    .onFailure { XLog.e("HookPredictiveBack.init failed", it) }
            }

            "io.relimus.zflow" -> {
                HookMyself.init()
            }

            "com.android.systemui" -> {
                HookSystemUI.init()
                HookNotificationAction.init()
            }

            else -> {
                // Match supported package identities, not arbitrary name substrings.
                // A separate process still has the same packageName.
                val pkg = lpparam.packageName
                val isLauncherLike =
                    pkg == "com.android.launcher3" ||
                        pkg == "com.google.android.apps.nexuslauncher"

                if (isLauncherLike) {
                    runCatching { HookLauncher.init(lpparam.classLoader) }
                        .onFailure {
                            XLog.e("HookLauncher.init failed", it)
                        }

                    runCatching { HookSwipeGesture.init() }
                        .onFailure {
                            XLog.e("HookSwipeGesture.init failed", it)
                        }
                }
            }
        }
    }
}
