package io.relimus.zflow.xposed.hook

import android.graphics.Rect
import android.view.Display
import android.view.WindowManager
import android.view.WindowInsets
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder
import io.github.kyuubiran.ezxhelper.core.util.ClassUtil.loadClass
import io.github.kyuubiran.ezxhelper.core.util.ObjectUtil
import io.github.kyuubiran.ezxhelper.core.util.ObjectUtil.getObject
import io.github.kyuubiran.ezxhelper.core.util.ObjectUtil.invokeMethodBestMatch
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createHooks
import io.relimus.zflow.utils.cast
import io.relimus.zflow.xposed.hook.utils.XLog
import io.relimus.zflow.xposed.services.FreeformManager
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object HookImeInsetsBridge {

    private const val TAG = "HookImeInsetsBridge"

    /**
     * 这些类在 system_server 里按需加载。
     *
     * 注意：**不要在对象静态初始化时直接 loadClass / getStaticIntField**。
     * 本对象是在 `MainHook.handleLoadPackage`（system_server 进程）里被触发的，
     * 静态初始化抛出的异常会直接冒泡出 handleLoadPackage，导致 system_server
     * 崩溃重启（表现为「软重启 / 开机循环」）。因此这里一律用 lazy + runCatching，
     * 把不兼容版本降级为「功能不可用」而不是「系统崩溃」。
     */
    private val imeInsetsSourceProviderClass: Class<*>? by lazy {
        runCatching { loadClass("com.android.server.wm.ImeInsetsSourceProvider") }.getOrNull()
    }
    private val insetsPolicyClass: Class<*>? by lazy {
        runCatching { loadClass("com.android.server.wm.InsetsPolicy") }.getOrNull()
    }
    private val insetsSourceClass: Class<*>? by lazy {
        runCatching { loadClass("android.view.InsetsSource") }.getOrNull()
    }

    private val imeInsetsType: Int by lazy { WindowInsets.Type.ime() }

    /**
     * IME 的 inset 源 id，按系统版本解析：
     *
     * - Android 14 (UPSIDE_DOWN_CAKE) 及以后：`InsetsSource` 引入 `ID_IME`
     *   （`InsetsSource.createId(null, 0, ime())`），用它作为源的唯一 id。
     * - Android 13 (TIRAMISU) 及以前：`InsetsSource` **没有** `ID_IME` 常量，
     *   `InsetsState` 用内部类型 `ITYPE_IME`（= 19）作为数组下标，
     *   此时源 id 就等于 `ITYPE_IME`。
     *
     * 旧代码无条件 `getStaticIntField(insetsSourceClass, "ID_IME")`，在 A13 上抛
     * `NoSuchFieldError` 并把 system_server 带崩。这里改为运行时探测，缺失即回退。
     */
    private val imeSourceId: Int by lazy { resolveImeSourceId() }

    private fun resolveImeSourceId(): Int {
        insetsSourceClass?.let { cls ->
            runCatching { XposedHelpers.getStaticIntField(cls, "ID_IME") }
                .getOrNull()
                ?.let { return it }
        }

        runCatching {
            XposedHelpers.getStaticIntField(loadClass("android.view.InsetsState"), "ITYPE_IME")
        }.getOrNull()?.let { return it }

        // ITYPE_IME 的历史稳定值，作为最后兜底。
        return 19
    }

    private data class ImeSourceState(val visible: Boolean, val frame: Rect)
    private data class AppliedImeState(val visible: Boolean, val height: Int)

    private val lastAppliedStateByDisplay = mutableMapOf<Int, AppliedImeState>()
    private val lastObservedSourceStateByDisplay = mutableMapOf<Int, AppliedImeState>()

    fun init() {
        if (inited && hookGeneration == HookRegistry.generation) return
        hookGeneration = HookRegistry.generation
        inited = false

        runCatching {
            hookProviderMethods()
            hookInsetsPolicyAdjustVisibility()
        }.onFailure {
            // 绝不让异常逃出 handleLoadPackage：system_server 里那等于软重启。
            XLog.e("$TAG: init failed", it)
        }

        inited = true
    }

    private var inited = false
    private var hookGeneration = -1L

    private fun hookProviderMethods() {
        val providerClass = imeInsetsSourceProviderClass ?: run {
            XLog.w("$TAG: ImeInsetsSourceProvider unavailable, skip provider hooks")
            return
        }

        listOf(
            "updateSourceFrame", "setServerVisible", "setClientVisible",
            "onSourceChanged", "updateVisibility", "scheduleShowImePostLayout"
        ).forEach { methodName ->
            runCatching {
                MethodFinder.fromClass(providerClass)
                    .filterByName(methodName)
                    .toList()
                    .takeIf { it.isNotEmpty() }
                    ?.createHooks {
                        after { handleImeSourceProviderUpdated(it.thisObject) }
                    }
            }
        }
    }

    private fun hookInsetsPolicyAdjustVisibility() {
        val policyClass = insetsPolicyClass ?: run {
            XLog.w("$TAG: InsetsPolicy unavailable, skip adjustVisibilityForIme hook")
            return
        }

        runCatching {
            MethodFinder.fromClass(policyClass)
                .filterByName("adjustVisibilityForIme")
                .toList()
                .createHooks {
                    after { patchImeDispatchStateIfNeeded(it) }
                }
        }
    }

    private fun handleImeSourceProviderUpdated(provider: Any?) {
        if (provider == null) return

        val sourceDisplayContent = ObjectUtil.getObjectUntilSuperclass(provider, "mDisplayContent") ?: return
        val sourceDisplayId = invokeMethodBestMatch(sourceDisplayContent, "getDisplayId").cast<Int>()
        if (sourceDisplayId != Display.DEFAULT_DISPLAY) return

        val sourceBounds = (XposedHelpers.callMethod(sourceDisplayContent, "getBounds").cast<Rect?>())
            ?.let { Rect(it) } ?: return
        if (sourceBounds.height() <= 0) return

        val sourceImeState = readImeSourceState(provider) ?: return
        val managedDisplayIds = FreeformManager.getManagedDisplayIds()
        if (managedDisplayIds.isEmpty()) return

        pruneStateForInactiveDisplays(managedDisplayIds)

        val sourceImeHeight = if (sourceImeState.visible) sourceImeState.frame.height().coerceAtLeast(0) else 0
        val observedState = AppliedImeState(sourceImeState.visible, sourceImeHeight)
        if (lastObservedSourceStateByDisplay[sourceDisplayId] == observedState) return
        lastObservedSourceStateByDisplay[sourceDisplayId] = observedState

        val wms = ObjectUtil.getObjectUntilSuperclass(sourceDisplayContent, "mWmService") ?: return
        val root = getObject(wms, "mRoot") ?: return
        val focusedDisplayId = getObject(root, "mTopFocusedDisplayId").cast<Int>()

        val targetDisplayIds = if (focusedDisplayId != Display.DEFAULT_DISPLAY && FreeformManager.isManagedDisplay(focusedDisplayId)) {
            intArrayOf(focusedDisplayId)
        } else {
            managedDisplayIds.filter { it != 0 }.toIntArray()
        }
        if (targetDisplayIds.isEmpty()) return

        for (displayId in targetDisplayIds) {
            if (displayId == Display.DEFAULT_DISPLAY) continue
            mirrorImeToDisplay(sourceDisplayContent, displayId, sourceBounds, sourceImeState)
        }
    }

    private fun mirrorImeToDisplay(
        sourceDisplayContent: Any,
        targetDisplayId: Int,
        sourceBounds: Rect,
        sourceImeState: ImeSourceState
    ) {
        val wms = ObjectUtil.getObjectUntilSuperclass(sourceDisplayContent, "mWmService") ?: return
        val root = getObject(wms, "mRoot") ?: return
        val targetDisplayContent = invokeMethodBestMatch(root, "getDisplayContent", null, targetDisplayId) ?: return

        val targetBounds = (XposedHelpers.callMethod(targetDisplayContent, "getBounds").cast<Rect?>())
            ?.let { Rect(it) } ?: return
        if (targetBounds.height() <= 0) return

        val targetImeHeight = resolveTargetImeHeightForDisplay(targetDisplayId, sourceBounds, targetBounds, sourceImeState)
        val nextState = AppliedImeState(sourceImeState.visible, targetImeHeight)
        if (lastAppliedStateByDisplay[targetDisplayId] == nextState) return

        val insetsStateController = invokeMethodBestMatch(targetDisplayContent, "getInsetsStateController")!!
        val imeProvider = invokeMethodBestMatch(insetsStateController, "getImeSourceProvider")
        val targetFrame = Rect(targetBounds.left, targetBounds.bottom - targetImeHeight, targetBounds.right, targetBounds.bottom)

        applyImeState(imeProvider, targetDisplayContent, targetFrame, sourceImeState.visible)
        lastAppliedStateByDisplay[targetDisplayId] = nextState
    }

    private fun resolveTargetImeHeightForDisplay(
        targetDisplayId: Int,
        sourceBounds: Rect,
        targetBounds: Rect,
        sourceImeState: ImeSourceState
    ): Int {
        val sourceImeHeight = if (sourceImeState.visible) sourceImeState.frame.height().coerceAtLeast(0) else 0
        if (sourceImeHeight <= 0) return 0

        val window = FreeformManager.getWindow(targetDisplayId) ?: return 0
        val imeMetrics = window.getImeInsetsMetrics()
        val lp = imeMetrics.layoutParams
        val freeformScreenHeight = imeMetrics.freeformScreenHeight
        val mScaleY = imeMetrics.scaleY
        if (lp.height <= 0 || mScaleY <= 0f || freeformScreenHeight <= 0) return 0

        val rootTop = sourceBounds.top + ((sourceBounds.height() - lp.height) / 2) + lp.y
        val scaledHeight = (lp.height * mScaleY).roundToInt().coerceAtLeast(1)
        val scaledTop = rootTop + ((lp.height - scaledHeight) / 2f).roundToInt()
        val scaledBottom = scaledTop + scaledHeight

        val topDecorOnSource = (imeMetrics.topDecorRaw * mScaleY).roundToInt().coerceAtLeast(0)
        val bottomDecorOnSource = (imeMetrics.bottomDecorRaw * mScaleY).roundToInt().coerceAtLeast(0)

        val contentTop = (scaledTop + topDecorOnSource).coerceAtMost(scaledBottom)
        val contentBottom = (scaledBottom - bottomDecorOnSource).coerceAtLeast(contentTop)
        val contentHeightOnSource = (contentBottom - contentTop).coerceAtLeast(1)

        val overlapOnSource = max(0, min(contentBottom, sourceImeState.frame.bottom) - max(contentTop, sourceImeState.frame.top))
        if (overlapOnSource <= 0) return 0

        return (overlapOnSource.toFloat() * freeformScreenHeight.toFloat() / contentHeightOnSource.toFloat())
            .roundToInt()
            .coerceIn(0, targetBounds.height())
    }

    private fun applyImeState(imeProvider: Any?, targetDisplayContent: Any, frame: Rect, visible: Boolean) {
        if (imeProvider != null) {
            invokeMethodBestMatch(imeProvider, "updateSourceFrame", null, Rect(frame))
            invokeMethodBestMatch(imeProvider, "setServerVisible", null, visible)
            invokeMethodBestMatch(imeProvider, "setClientVisible", null, visible)

            val source = XposedHelpers.callMethod(imeProvider, "getSource")
            if (source != null) {
                invokeMethodBestMatch(source, "setFrame", null, Rect(frame))
                invokeMethodBestMatch(source, "setVisible", null, visible)
            }
            invokeMethodBestMatch(imeProvider, "onSourceChanged")
        }

        applyImeStateToRawInsets(targetDisplayContent, frame, visible)

        val insetsStateController = invokeMethodBestMatch(targetDisplayContent, "getInsetsStateController")
        if (insetsStateController != null) {
            invokeMethodBestMatch(insetsStateController, "notifyInsetsChanged")
        }

        runCatching { invokeMethodBestMatch(targetDisplayContent, "updateImeInputAndControlTarget", null, false) }
        runCatching { invokeMethodBestMatch(targetDisplayContent, "updateImeInputAndControlTarget") }
        invokeMethodBestMatch(targetDisplayContent, "setLayoutNeeded")

        val wms = ObjectUtil.getObjectUntilSuperclass(targetDisplayContent, "mWmService")!!
        val placer = getObject(wms, "mWindowPlacerLocked")!!
        invokeMethodBestMatch(placer, "requestTraversal")
    }

    private fun applyImeStateToRawInsets(targetDisplayContent: Any, frame: Rect, visible: Boolean) {
        val insetsStateController = invokeMethodBestMatch(targetDisplayContent, "getInsetsStateController") ?: return
        val insetsState = invokeMethodBestMatch(insetsStateController, "getRawInsetsState") ?: return
        val imeSource = resolveOrCreateImeSource(insetsState) ?: return
        invokeMethodBestMatch(imeSource, "setFrame", null, Rect(frame))
        invokeMethodBestMatch(imeSource, "setVisibleFrame", null, Rect(frame))
        invokeMethodBestMatch(imeSource, "setVisible", null, visible)
    }

    /**
     * 取 IME 的 InsetsSource，兼容两套 API：
     * - Android 14+：`getOrCreateSource(id, type)` / `peekSource(id)`
     * - Android 13-：无 `getOrCreateSource`，用 `getSource(ITYPE_IME)`
     *
     * 注意：`invokeMethodBestMatch` 找不到方法时**抛 NoSuchMethodException**
     * （不是返回 null），所以每次尝试都要 runCatching。
     */
    private fun resolveOrCreateImeSource(insetsState: Any): Any? {
        runCatching { invokeMethodBestMatch(insetsState, "getOrCreateSource", null, imeSourceId, imeInsetsType) }
            .getOrNull()?.let { return it }
        return runCatching { invokeMethodBestMatch(insetsState, "getSource", null, imeSourceId) }
            .getOrNull()
    }

    /** 只读地取 IME 源，不创建。兼容同上。 */
    private fun resolveImeSource(insetsState: Any): Any? {
        runCatching { invokeMethodBestMatch(insetsState, "peekSource", null, imeSourceId) }
            .getOrNull()?.let { return it }
        runCatching { invokeMethodBestMatch(insetsState, "getOrCreateSource", null, imeSourceId, imeInsetsType) }
            .getOrNull()?.let { return it }
        return runCatching { invokeMethodBestMatch(insetsState, "getSource", null, imeSourceId) }
            .getOrNull()
    }

    private fun patchImeDispatchStateIfNeeded(param: XC_MethodHook.MethodHookParam) {
        val windowState = param.args.getOrNull(0) ?: return
        val dispatchState = param.result ?: return

        val displayId = invokeMethodBestMatch(windowState, "getDisplayId").cast<Int?>() ?: return
        if (displayId == Display.DEFAULT_DISPLAY || !FreeformManager.isManagedDisplay(displayId)) return

        val attrs = getObject(windowState, "mAttrs").cast<WindowManager.LayoutParams?>()
        val windowType = attrs?.type ?: 0
        if (windowType !in WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW..
            WindowManager.LayoutParams.LAST_APPLICATION_WINDOW) return

        val desiredImeState = lastAppliedStateByDisplay[displayId] ?: return
        if (!desiredImeState.visible || desiredImeState.height <= 0) return

        val displayContent = XposedHelpers.callMethod(windowState, "getDisplayContent") ?: return
        val displayBounds = (XposedHelpers.callMethod(displayContent, "getBounds").cast<Rect?>())
            ?.let { Rect(it) } ?: return
        if (displayBounds.height() <= 0) return

        val targetImeHeight = desiredImeState.height.coerceIn(0, displayBounds.height())
        if (targetImeHeight <= 0) return

        val targetFrame = Rect(displayBounds.left, displayBounds.bottom - targetImeHeight, displayBounds.right, displayBounds.bottom)
        val patchedState = runCatching { XposedHelpers.newInstance(dispatchState.javaClass, dispatchState) }.getOrNull() ?: dispatchState

        val imeSource = resolveImeSource(patchedState) ?: return

        invokeMethodBestMatch(imeSource, "setFrame", null, Rect(targetFrame))
        invokeMethodBestMatch(imeSource, "setVisibleFrame", null, Rect(targetFrame))
        invokeMethodBestMatch(imeSource, "setVisible", null, true)

        if (patchedState !== dispatchState) {
            param.result = patchedState
        }
    }

    private fun readImeSourceState(provider: Any): ImeSourceState? {
        val source = XposedHelpers.callMethod(provider, "getSource") ?: return null
        val frame = invokeMethodBestMatch(source, "getFrame").cast<Rect?>() ?: return null
        val visible = invokeMethodBestMatch(source, "isVisible").cast<Boolean?>() ?: return null
        return ImeSourceState(visible = visible, frame = Rect(frame))
    }

    private fun pruneStateForInactiveDisplays(managedDisplayIds: IntArray) {
        if (managedDisplayIds.isEmpty()) {
            lastAppliedStateByDisplay.clear()
            return
        }
        val managedSet = managedDisplayIds.toSet()
        lastAppliedStateByDisplay.keys.removeAll { it !in managedSet }
    }
}
