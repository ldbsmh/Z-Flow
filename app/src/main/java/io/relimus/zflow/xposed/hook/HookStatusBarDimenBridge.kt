package io.relimus.zflow.xposed.hook

import android.app.Activity
import android.content.res.Resources
import android.view.Display
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import io.relimus.zflow.app.ZFlow
import io.relimus.zflow.providers.RemoteSettings
import io.relimus.zflow.xposed.hook.utils.XLog

/**
 * 通用补丁：在小窗（虚拟屏）上把「框架 status_bar_height」归零。
 *
 * ## 解决什么
 *
 * 一批应用会直接读框架尺寸来预留状态栏高度，与运行时 inset 无关：
 *
 * ```java
 * int id = ctx.getResources().getIdentifier("status_bar_height", "dimen", "android");
 * return ctx.getResources().getDimensionPixelSize(id);     // ← 本补丁的 hook 目标
 * ```
 *
 * 真机上这截被系统状态栏覆盖，看不出问题；虚拟屏上没有状态栏，
 * 这截就裸露成一条与内容无关的空白（用户俗称「宽额头」）。
 * 起点读书即为此类。
 *
 * ## 做法
 *
 * hook `Resources.getDimensionPixelSize(int)`，当入参是 `android:dimen/status_bar_height`
 * **且当前不在主屏**时返回 0。
 *
 * 相比 hook 具体应用的混淆方法，这里用的是**框架 API，跨版本稳定**，
 * 对任何"读框架状态栏尺寸"的应用都生效，不需要逐个逆向。
 *
 * ## 边界
 *
 * - 只对加入了 LSPosed 作用域的应用生效（把目标包名加进 `xposedscope`，
 *   并在 LSPosed 管理器里勾选）。
 * - 只覆盖 `getDimensionPixelSize` 这一条路径。用 `getDimension` /
 *   `getDimensionPixelOffset` / 硬编码 dp 的应用不受影响，需另作处理。
 * - 主屏行为完全不变（`currentDisplayId == DEFAULT_DISPLAY` 时直接返回）。
 */
object HookStatusBarDimenBridge {

    private const val TAG = "HookStatusBarDimen"

    /** 当前所在显示设备；由 Activity.onResume 更新。默认主屏 → 不改任何行为。 */
    @Volatile
    private var currentDisplayId: Int = Display.DEFAULT_DISPLAY

    /** 开关状态；由 Activity.onResume 刷新。默认 true，与设置页默认值一致。 */
    @Volatile
    private var featureEnabled: Boolean = true

    /** `android:dimen/status_bar_height` 在本进程资源里的 id；-1 = 尚未解析。 */
    @Volatile
    private var statusBarHeightResId: Int = -1

    fun init(classLoader: ClassLoader) {
        if (inited && hookGeneration == HookRegistry.generation) return
        hookGeneration = HookRegistry.generation
        inited = false

        runCatching { hookActivityResume(classLoader) }
            .onFailure { XLog.e("$TAG: hook Activity.onResume failed", it) }

        runCatching { hookGetDimensionPixelSize() }
            .onFailure { XLog.e("$TAG: hook Resources failed", it) }

        inited = true
        XLog.d("$TAG: init done")
    }

    private var inited = false
    private var hookGeneration = -1L

    /**
     * 记录应用当前所在显示设备。
     * 每个 Activity 进入前台时刷新一次，避免每次取尺寸都去查 Display。
     */
    private fun hookActivityResume(classLoader: ClassLoader) {
        val activityClass = runCatching {
            XposedHelpers.findClass("android.app.Activity", classLoader)
        }.getOrNull() ?: return

        HookRegistry.findAndHookMethod(
            activityClass,
            "onResume",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    val displayId = runCatching { activity.display?.displayId }
                        .getOrNull() ?: return
                    currentDisplayId = displayId

                    // onResume 时刷新开关（频率低，读取带缓存，失败按 default 兜底）
                    featureEnabled = RemoteSettings.isFeatureEnabled(
                        activity,
                        ZFlow.KEY_HOOK_STATUSBAR_DIMEN,
                        true
                    )
                }
            }
        )
    }

    private fun hookGetDimensionPixelSize() {
        val resourcesClass = runCatching {
            XposedHelpers.findClass("android.content.res.Resources", null)
        }.getOrNull() ?: return

        HookRegistry.hookAll(
            resourcesClass,
            "getDimensionPixelSize",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!featureEnabled) return
                    if (currentDisplayId == Display.DEFAULT_DISPLAY) return

                    val res = param.thisObject as? Resources ?: return
                    val id = param.args.getOrNull(0) as? Int ?: return
                    if (id != resolveStatusBarHeightResId(res)) return

                    param.result = 0
                }
            }
        )
    }

    /** 解析并缓存 `android:dimen/status_bar_height` 的 id。 */
    private fun resolveStatusBarHeightResId(res: Resources): Int {
        val cached = statusBarHeightResId
        if (cached >= 0) return cached

        val id = runCatching {
            res.getIdentifier("status_bar_height", "dimen", "android")
        }.getOrDefault(0)

        statusBarHeightResId = id
        return id
    }
}
