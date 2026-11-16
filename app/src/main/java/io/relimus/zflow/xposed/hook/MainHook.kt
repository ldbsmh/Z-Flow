package io.relimus.zflow.xposed.hook

import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.kyuubiran.ezxhelper.xposed.EzXposed
import io.relimus.zflow.xposed.hook.utils.XLog

/**
 * Z-Flow 的功能分发入口。
 *
 * 这里只保留「按包名决定挂哪些 Hook」这一层，实际挂载放在 [dispatch] 里，
 * 因为它有两个调用方：
 *
 * 1. **经典入口**：`assets/xposed_init` + [IXposedHookLoadPackage]，老 LSPosed 走这条；
 * 2. **libxposed API 101 入口**：[ZFlowModule]，`META-INF/xposed/java_init.list`。
 *
 * 为什么必须两条都有：这个 APK 现在同时装了 NativeComplements 小窗引擎，
 * 它带 `META-INF/xposed/module.prop`。LSPosed 一旦看到这个文件，就把模块当成
 * 「新版模块」，**只读 java_init.list，忽略 assets/xposed_init** —— 于是
 * Z-Flow 原有的全部 Hook（上滑开小窗、轮盘、通知胶囊……）会一声不响地全停掉，
 * 现象就是「装了小窗内核之后，手势彻底没反应，日志里连一行都没有」。
 */
class MainHook : IXposedHookLoadPackage, IXposedHookZygoteInit {

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        EzXposed.initZygote(startupParam)
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        Log.i(TAG, "legacy entry pkg=${lpparam.packageName} process=${lpparam.processName}")

        runCatching { EzXposed.initHandleLoadPackage(lpparam) }
            .onFailure { XLog.e("EzXposed.initHandleLoadPackage failed", it) }

        dispatch(lpparam.packageName, lpparam.classLoader)
    }

    companion object {
        private const val TAG = "ZFlowMini"

        /**
         * 按包名分发 Hook。
         *
         * 两个入口理论上可能同时被调用，这里不做去重：每个 Hook 自己用
         * [HookRegistry.generation] 判重（`inited && hookGeneration == generation` 时直接返回），
         * 重复分发只会多走一次 reset + 重新挂载，不会挂两套回调。
         *
         * @param packageName 目标进程的包名（system_server 传 `"android"`）。
         * @param classLoader 目标进程的 ClassLoader，用来解析 ROM 内部类。
         */
        fun dispatch(packageName: String, classLoader: ClassLoader?) {
            Log.i(TAG, "dispatch pkg=$packageName loader=${classLoader?.javaClass?.simpleName}")

            // 热重载：取消所有旧钩子，并标记各 Hook 重新初始化
            HookRegistry.reset()

            when (packageName) {
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
                    val isLauncherLike =
                        packageName == "com.android.launcher3" ||
                            packageName == "com.google.android.apps.nexuslauncher"

                    if (isLauncherLike) {
                        if (classLoader != null) {
                            runCatching { HookLauncher.init(classLoader) }
                                .onFailure {
                                    XLog.e("HookLauncher.init failed", it)
                                }
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
}
