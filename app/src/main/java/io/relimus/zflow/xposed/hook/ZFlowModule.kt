package io.relimus.zflow.xposed.hook

import android.util.Log
import io.github.kyuubiran.ezxhelper.core.ClassLoaderProvider
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Z-Flow 的 libxposed API 101 入口。
 *
 * 和经典入口 [MainHook] 做同一件事（[MainHook.dispatch]），只是换了生命周期回调。
 * 为什么必须再加这一个入口：APK 里现在带着 NativeComplements 小窗引擎的
 * `META-INF/xposed/module.prop`，LSPosed 见到它就按「新版模块」处理，**只读
 * `META-INF/xposed/java_init.list`，完全忽略 `assets/xposed_init`**。
 * 结果是小窗引擎活得很好，Z-Flow 自己的 Hook（上滑开小窗、轮盘、通知）全部静默失效，
 * 而且日志里一行报错都没有 —— 因为函数根本没被调用。
 *
 * 另外顺手把 ezxhelper 的 ClassLoader 补上：hook 代码里用 `ClassUtil.loadClass(name)`
 * 解析 ROM 内部类，它回退到 [ClassLoaderProvider]；不设置就只剩系统 ClassLoader，
 * 桌面里的类一个都找不到。
 */
class ZFlowModule : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        Log.i(
            TAG,
            "module loaded process=${param.processName} systemServer=${param.isSystemServer}"
        )
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        val loader = param.classLoader
        ClassLoaderProvider.classLoader = loader
        MainHook.dispatch("android", loader)
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        val packageName = param.packageName
        // system_server 走 onSystemServerStarting，这里再分发一次会重复挂载。
        if (packageName == "android") return

        val loader = param.defaultClassLoader
        ClassLoaderProvider.classLoader = loader

        runCatching { MainHook.dispatch(packageName, loader) }
            .onFailure { Log.e(TAG, "dispatch failed pkg=$packageName", it) }
    }

    private companion object {
        private const val TAG = "ZFlowMini"
    }
}
