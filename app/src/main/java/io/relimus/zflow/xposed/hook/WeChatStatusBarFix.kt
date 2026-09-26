package io.relimus.zflow.xposed.hook

import android.content.Context
import android.view.Display
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import io.relimus.zflow.xposed.hook.utils.XLog

/**
 * 微信小窗双标题栏修复（per-app 补丁）。
 *
 * ## 问题
 *
 * 微信在小窗（虚拟屏）里顶部会出现两条相同的标题栏：上层可点击，下层点不动。
 * 反编译定位到 `ConversationListView` 的滚动偏移计算：
 *
 * ```java
 * int statusBarHeight = gl.h(context);     // ← 本补丁的 hook 目标
 * int actionBarTop = this.E;
 * if (actionBarTop <= 0 && !this.J) {
 *     actionBarTop = statusBarHeight;      // ActionBar 贴顶时按状态栏高度兜底
 * }
 * this.s = gl.a(context) + actionBarTop;   // scrollOffset
 * ...
 * setSelectionFromTop(1, this.s);          // 面板头部据此上移
 * ```
 *
 * 主屏上 ActionBar 被状态栏推下来（`E = 88`），兜底不触发，`s` 正确，
 * 任务栏面板完全藏住。虚拟屏上没有状态栏，ActionBar 永远贴顶（`E = 0`），
 * 兜底每次都注入一个不存在的状态栏高度 → `s` 偏大 136px →
 * 任务栏面板底部那条 bar 露在屏内。因为面板逻辑上已关闭，所以不接收触摸，
 * 看起来就是"重复了一层、还点不动"。
 *
 * ## 做法
 *
 * hook 微信的状态栏高度入口，**非主屏时返回 0**：
 *
 * - 虚拟屏：`E = 0` → 兜底拿到 0 → `s = gl.a` = ActionBar 高度 → 面板完全藏住
 * - 主屏：`E = 88 ≠ 0` → 兜底不触发 → 返回值用不到 → **正常使用零影响**
 *
 * ## 边界
 *
 * - 混淆名随微信版本变化。类/方法找不到时静默跳过，行为与修改前一致。
 * - 微信必须在 LSPosed 作用域内（`xposedscope` 已声明，仍需用户在管理器里勾选）。
 */
object WeChatStatusBarFix {

    private const val TAG = "WeChatStatusBarFix"

    /**
     * 微信状态栏高度取值入口。
     * 混淆名会随版本变化，按顺序尝试，命中即止。
     */
    private val CANDIDATE_CLASSES = listOf(
        "com.tencent.mm.ui.gl"
    )

    /** 目标方法签名：`h(Context) : Int` */
    private const val TARGET_METHOD = "h"

    fun init(classLoader: ClassLoader) {
        if (inited && hookGeneration == HookRegistry.generation) return
        hookGeneration = HookRegistry.generation
        inited = false

        runCatching { install(classLoader) }
            .onFailure { XLog.e("$TAG: init failed", it) }

        inited = true
    }

    private var inited = false
    private var hookGeneration = -1L

    private fun install(classLoader: ClassLoader) {
        for (className in CANDIDATE_CLASSES) {
            val clazz = runCatching { XposedHelpers.findClass(className, classLoader) }.getOrNull()
            if (clazz == null) {
                XLog.d("$TAG: class $className not found, skip")
                continue
            }

            val ok = runCatching {
                HookRegistry.findAndHookMethod(
                    clazz,
                    TARGET_METHOD,
                    Context::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) = patch(param)
                    }
                )
                true
            }.getOrDefault(false)

            if (ok) {
                XLog.d("$TAG: hooked $className#$TARGET_METHOD(Context)")
                return
            }
            XLog.d("$TAG: method $className#$TARGET_METHOD(Context) not found, skip")
        }

        XLog.d("$TAG: no target hooked")
    }

    /**
     * 非主屏（Z-Flow 小窗）上把状态栏高度置 0。
     * 无法判定所在显示设备时不动，保证安全。
     */
    private fun patch(param: XC_MethodHook.MethodHookParam) {
        val context = param.args.getOrNull(0) as? Context ?: return
        val displayId = runCatching { context.display?.displayId }.getOrNull() ?: return
        if (displayId == Display.DEFAULT_DISPLAY) return

        param.result = 0
    }
}
