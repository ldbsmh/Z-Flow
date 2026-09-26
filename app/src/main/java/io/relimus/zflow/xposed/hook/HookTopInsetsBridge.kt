package io.relimus.zflow.xposed.hook

import android.graphics.Rect
import android.view.Display
import android.view.WindowInsets
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder
import io.github.kyuubiran.ezxhelper.core.util.ClassUtil.loadClass
import io.github.kyuubiran.ezxhelper.core.util.ObjectUtil.invokeMethodBestMatch
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createHooks
import io.relimus.zflow.utils.cast
import io.relimus.zflow.xposed.hook.utils.XLog
import io.relimus.zflow.xposed.services.FreeformManager

/**
 * 归零小窗（虚拟屏）内应用窗口的顶部 inset，消除“宽额头”。
 *
 * 背景：
 * 应用窗口在虚拟屏上按全屏尺寸布局，顶部 inset（状态栏 / 刘海）由应用自行
 * 处理——DecorView 依据 inset 给内容加 padding，并在该区域绘制
 * statusBarColor 或窗口背景。真机上这条被系统状态栏覆盖，看不出问题；
 * 虚拟屏没有系统状态栏窗口，于是这条状态栏高度的空白裸露出来，
 * 表现为小窗顶部一块与内容无关的空白。
 *
 * 做法：
 * 在小窗内应用窗口取 dispatch inset 时，把 STATUS_BAR 与 DISPLAY_CUTOUT
 * 两个 source 的 frame 清空并置为不可见，使应用算出的顶部 inset 为 0，
 * 内容从 y=0 铺满。
 *
 * 作用点选在「下发给窗口的 dispatch inset」而非虚拟屏属性本身，
 * 因此只影响小窗里的应用窗口，主屏与其它显示设备不受影响。
 *
 * 所有类名 / 方法名 / 字段名查找都做了防御：目标 ROM 上不存在时静默跳过，
 * 此时行为与修改前完全一致，不引入回归。
 */
object HookTopInsetsBridge {

    /** 总开关。设备上若发现异常，改为 false 即可整体关闭。 */
    private const val ENABLED = true

    private const val TAG = "HookTopInsetsBridge"

    private val insetsStateControllerClass: Class<*>? =
        runCatching { loadClass("com.android.server.wm.InsetsStateController") }.getOrNull()
    private val insetsSourceClass: Class<*>? =
        runCatching { loadClass("android.view.InsetsSource") }.getOrNull()

    /** 逐个尝试候选字段名，取第一个存在的静态 int。 */
    private fun findStaticIntField(clazz: Class<*>?, vararg names: String): Int? {
        if (clazz == null) return null
        for (name in names) {
            val value = runCatching { XposedHelpers.getStaticIntField(clazz, name) }.getOrNull()
            if (value != null) return value
        }
        return null
    }

    private val statusBarSourceId: Int? =
        findStaticIntField(insetsSourceClass, "ID_STATUS_BAR", "ID_STATUS_BAR_SOURCE")

    private val displayCutoutSourceId: Int? =
        findStaticIntField(insetsSourceClass, "ID_DISPLAY_CUTOUT", "ID_CUTOUT")

    private val statusBarType: Int = WindowInsets.Type.statusBars()
    private val displayCutoutType: Int = WindowInsets.Type.displayCutout()

    /** 已记录过日志的 displayId，避免每次 dispatch 都打日志。 */
    private val loggedDisplayIds = mutableSetOf<Int>()

    fun init() {
        if (inited && hookGeneration == HookRegistry.generation) return
        hookGeneration = HookRegistry.generation
        inited = false

        // 任何一步失败都不应影响 MainHook 中后续 Hook 的初始化
        runCatching {
            if (ENABLED) hookInsetDispatch()
        }.onFailure {
            XLog.e("$TAG: init failed", it)
        }

        inited = true
    }

    private var inited = false
    private var hookGeneration = -1L

    private fun hookInsetDispatch() {
        val clazz = insetsStateControllerClass ?: run {
            XLog.d("$TAG: InsetsStateController not found, skip")
            return
        }

        val methods = runCatching {
            MethodFinder.fromClass(clazz)
                .filterByName("getInsetsForDispatch")
                .toList()
        }.getOrDefault(emptyList())

        if (methods.isEmpty()) {
            XLog.d("$TAG: no getInsetsForDispatch on InsetsStateController, skip")
            return
        }

        methods.createHooks {
            after { patchTopInsetsIfNeeded(it) }
        }
        XLog.d("$TAG: hooked getInsetsForDispatch (${methods.size} overloads)")
    }

    /**
     * 把托管小窗屏上的应用窗口顶部 inset 清零。
     * 只处理参数为具体窗口、且该窗口位于托管虚拟屏上的调用。
     */
    private fun patchTopInsetsIfNeeded(param: XC_MethodHook.MethodHookParam) {
        val windowState = param.args.getOrNull(0) ?: return

        val displayId = runCatching {
            invokeMethodBestMatch(windowState, "getDisplayId").cast<Int?>()
        }.getOrNull() ?: return

        // 主屏不动；只处理 Z-Flow 自己托管的虚拟屏
        if (displayId == Display.DEFAULT_DISPLAY) return
        if (!FreeformManager.isManagedDisplay(displayId)) return

        val dispatchState = param.result ?: return

        // 复制一份再改，避免污染 WM 内部共享状态
        val patchedState = runCatching {
            XposedHelpers.newInstance(dispatchState.javaClass, dispatchState)
        }.getOrNull() ?: return

        var touched = false
        statusBarSourceId?.let { id ->
            if (zeroSource(patchedState, id, statusBarType)) touched = true
        }
        displayCutoutSourceId?.let { id ->
            if (zeroSource(patchedState, id, displayCutoutType)) touched = true
        }

        if (!touched) return

        param.result = patchedState

        if (loggedDisplayIds.add(displayId)) {
            XLog.d(
                "$TAG: zeroed top insets for display=$displayId " +
                    "statusBar=$statusBarSourceId cutout=$displayCutoutSourceId"
            )
        }
    }

    /**
     * 清空指定 source 的 frame 并置为不可见。
     * source 不存在时返回 false（说明本来就没有这块 inset，无需处理）。
     */
    private fun zeroSource(state: Any, sourceId: Int, sourceType: Int): Boolean {
        val source = runCatching { invokeMethodBestMatch(state, "peekSource", null, sourceId) }.getOrNull()
            ?: runCatching { invokeMethodBestMatch(state, "getSource", null, sourceId) }.getOrNull()
            ?: runCatching {
                invokeMethodBestMatch(state, "getOrCreateSource", null, sourceId, sourceType)
            }.getOrNull()
            ?: return false

        runCatching { invokeMethodBestMatch(source, "setFrame", null, Rect()) }
        runCatching { invokeMethodBestMatch(source, "setVisibleFrame", null, Rect()) }
        runCatching { invokeMethodBestMatch(source, "setVisible", null, false) }
        return true
    }
}
