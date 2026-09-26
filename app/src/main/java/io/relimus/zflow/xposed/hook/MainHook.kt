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

        // 通用补丁：非主屏上把框架 status_bar_height 归零。
        // 见 HookStatusBarDimenBridge 的类注释。
        runCatching { HookStatusBarDimenBridge.init(lpparam.classLoader) }
            .onFailure { XLog.e("HookStatusBarDimenBridge.init failed", it) }

        when (lpparam.packageName) {
            "android" -> {
                HookFramework.init()
                HookSystem.init()
                HookImeInsetsBridge.init()
                HookImeAdjustResize.init()
                HookReload.init()
                HookPredictiveBack.init()
            }

            "io.relimus.zflow" -> {
                HookMyself.init()
            }

            "com.android.systemui" -> {
                HookSystemUI.init()
                HookNotificationAction.init()
            }

            "com.tencent.mm" -> {
                // 微信小窗双标题栏修复：非主屏时状态栏高度归零。
                // 见 WeChatStatusBarFix 的类注释。
                runCatching { WeChatStatusBarFix.init(lpparam.classLoader) }
                    .onFailure { XLog.e("WeChatStatusBarFix.init failed", it) }
            }

            else -> {
                // Android 17 的 quickstep / Launcher 可能换包名或跑在独立进程，
                // 这里放宽匹配，凡包含 launcher / quickstep 的包都尝试注入。
                val pkg = lpparam.packageName
                val isLauncherLike =
                    pkg == "com.android.launcher3" ||
                        pkg == "com.google.android.apps.nexuslauncher" ||
                        pkg.contains("launcher", ignoreCase = true) ||
                        pkg.contains("quickstep", ignoreCase = true)

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
