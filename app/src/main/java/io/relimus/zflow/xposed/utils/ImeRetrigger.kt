package io.relimus.zflow.xposed.utils

import android.os.Handler
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import android.os.ServiceManager
import de.robv.android.xposed.XposedHelpers
import io.relimus.zflow.xposed.hook.utils.XLog
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 输入法「重唤」：把虚拟屏的 IME 挂载状态重算一遍。
 *
 * 现场结论（Android 17 / SDK 37，日志 tag `ZFlowIme`）：
 * - 小窗内容跑在虚拟屏上，屏上的 IME 策略是 FALLBACK（键盘回落到主屏显示），
 *   这是 HookImeInsetsBridge 跨屏镜像的前提，不能改成 LOCAL；
 * - 任务用 moveRootTaskToDisplay 直接搬进虚拟屏，绕过了正常焦点流程，
 *   `DisplayContent.mImeLayeringTarget` 不会被算出来（实测一直是 null）。
 *   锚点为空时 App 的显示请求在 IMMS 侧被 `PHASE_SERVER_ALREADY_VISIBLE` 取消，
 *   键盘窗口永远 `mViewVisibility=GONE`——表现就是「输入框有光标，键盘不出来」；
 * - 实测有效的动作只有一步：`updateImeInputAndControlTarget(null)` 再设回目标、
 *   打上 `setLayoutNeeded()`、催一次 `requestTraversal()`，让 WMS 重算一遍 IME 目标。
 *   重排完成后键盘会自己出来，**不需要**调 `showCurrentInput*`
 *   （那些接口在锚点为空时全都返回 true 但毫无效果，之前几轮就是被这个 true 带偏的）。
 *
 * 所以这里只保留两个动作：
 * - [retrigger]：双击底栏中部，重算虚拟屏的 IME 状态，键盘随后自己出来。
 *   这是唯一稳定的唤出方式——自动触发（挂 IMMS 请求、任务进屏时提前修）都试过，
 *   要么时机不对要么副作用大，最终固定成手动双击这一个入口；
 * - [restoreToDefault]：退出小窗（展开成全屏 / 关闭）时清理虚拟屏的残留 IME 目标，
 *   让下一次进入小窗时状态是干净的。
 *
 * 已知局限：**展开回全屏后，全屏应用点输入框仍然唤不出键盘**。
 * 卡点在应用进程内部（`ImeTracker: onFailed at PHASE_CLIENT_VIEW_SERVED`），
 * 而 Android 17 已经删掉了 `IWindow.windowFocusChanged`，服务端无法再通知应用
 * 重新 startInput。三条被证伪的路子记在文件内 `restoreOnce` 下方的注释里。
 *
 * 所有调用都在 system_server 进程内完成（小窗本身就用 AMS.mContext 挂在这里），
 * `ServiceManager.getService` + `queryLocalInterface` 拿到的是服务实例本身，
 * 反射调用没有跨进程、权限和 hidden API 限制。
 */
object ImeRetrigger {

    /** 独立 tag：`adb logcat -s ZFlowIme` 就能只看这条链路。 */
    private const val DIAG_TAG = "ZFlowIme"

    /** 布局重排是异步的，调用完立刻读状态必然是旧值，等一会儿再看。 */
    private const val VERIFY_DELAY_MS = 350L

    /** hide 之后要留点时间让 IMMS 走完清理，否则紧跟着的 show 会被吃掉。 */
    private const val HIDE_SHOW_GAP_MS = 120L

    /** 连点节流：一轮要走几步、每步等 350ms，叠着跑会互相打断。 */
    private const val THROTTLE_MS = 1_500L

    /** 退出小窗后把输入法还给默认屏的两个时间点：任务刚落地时先试一次，布局稳定后再兜一次。 */
    private const val RESTORE_DELAY_MS = 150L
    private const val RESTORE_RETRY_MS = 700L

    /** 展开和关闭可能连在一起触发，这个窗口内的重复请求只做一次。 */
    private const val RESTORE_DEDUPE_MS = 1_200L

    /** WindowManager.LayoutParams.TYPE_INPUT_METHOD / _DIALOG */
    private const val TYPE_INPUT_METHOD = 2011
    private const val TYPE_INPUT_METHOD_DIALOG = 2012

    /** WindowManager.LayoutParams.TYPE_BASE_APPLICATION */
    private const val TYPE_BASE_APPLICATION = 1

    /** WindowManagerHidden.DISPLAY_IME_POLICY_FALLBACK_DISPLAY：键盘在默认屏显示。 */
    private const val IME_POLICY_FALLBACK_DISPLAY = 1

    /** 拆包装时的搜索范围：只在 inputmethod 包里往下钻，避免走进 Context 这类巨型对象图。 */
    private const val IME_PACKAGE = "com.android.server.inputmethod"

    /** 拆包装的最大深度（Impl -> ZeroJankProxy -> ServiceImpl）。 */
    private const val MAX_UNWRAP_DEPTH = 4

    /** View.VISIBLE */
    private const val VISIBLE = 0

    /** Display.DEFAULT_DISPLAY */
    private const val DEFAULT_DISPLAY = 0

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * 专门用来摸 IMMS 的后台线程。
     *
     * 调 IMMS 的方法都要先抢 `ImfLock`，而 `android.imms` 线程持锁期间会回调 WMS 去要
     * global lock。一旦调用方自己还握着 global lock，或者把这条阻塞路径挂在主线程上，
     * 都会把 system_server 卡死（主线程卡住 = 整个系统卡住，60s 后 Watchdog 直接杀进程）。
     * 所以这类调用一律扔到这里，既不持锁、也不占主线程。
     */
    private val worker by lazy {
        Handler(android.os.HandlerThread("ZFlowIme", android.os.Process.THREAD_PRIORITY_BACKGROUND).apply { start() }.looper)
    }

    /** 上一次真正执行的时间，用来做连点节流。 */
    private var lastRunAt = 0L

    /** 上一次触发归还默认屏的时间，展开与关闭连着触发时用来去重。 */
    private var lastRestoreAt = 0L

    // ========== 对外入口 ==========

    /**
     * 退出小窗（展开成全屏 / 关闭）时，把输入法状态还给默认屏。
     *
     * 小窗期间虚拟屏接管了 IME 目标；任务搬回默认屏之后，WMS 上这块屏的目标还是旧的，
     * IMMS 那边也还挂着虚拟屏的窗口，于是全屏应用点输入框时请求被当成「已经显示」吃掉，
     * 键盘不出来。这里等任务真正落地之后，清掉虚拟屏的残留目标，
     * 再用默认屏的当前焦点重建一次 IME 目标。
     */
    fun restoreToDefault(reason: String) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastRestoreAt < RESTORE_DEDUPE_MS) {
            diag("restore($reason) 忽略：距上次触发 ${now - lastRestoreAt}ms")
            return
        }
        lastRestoreAt = now
        handler.postDelayed({ restoreOnce(reason) }, RESTORE_DELAY_MS)
        handler.postDelayed({ restoreOnce("$reason-retry") }, RESTORE_RETRY_MS)
    }

    private fun restoreOnce(reason: String) {
        runCatching {
            val wms = windowManagerService() ?: run {
                diag("restore($reason) 放弃：拿不到 window 服务")
                return
            }
            val lock = objectField(wms, "mGlobalLock")

            // 锁内做完之后还要在锁外摸 IMMS 的屏，用这个变量把 DisplayContent 带出去。
            var touched: Any? = null

            fun work() {
                // 1. 虚拟屏上残留的 IME 目标清掉，别让 IMMS 还盯着已经没人用的屏。
                displayContents(wms).forEach { dc ->
                    val id = displayIdOf(dc)
                    if (id == DEFAULT_DISPLAY) return@forEach
                    if (objectField(dc, "mImeInputTarget") == null) return@forEach
                    val r = callFirst(dc, "updateImeInputAndControlTarget", listOf(null))
                    diag("restore($reason) 清虚拟屏 display=$id -> $r")
                }

                // 2. 默认屏用当前焦点重建 IME 目标，等价于「重新认一次输入框」。
                val default = displayContentOf(wms, DEFAULT_DISPLAY)
                if (default == null) {
                    diag("restore($reason) 找不到默认屏")
                    return
                }
                val focus = objectField(default, "mCurrentFocus")
                val clear = callFirst(default, "updateImeInputAndControlTarget", listOf(null))
                val reset = if (focus != null) {
                    callFirst(default, "updateImeInputAndControlTarget", listOf(focus, null))
                } else {
                    "焦点为空，跳过重建"
                }
                val layout = callFirst(default, "setLayoutNeeded", emptyList<Any?>())
                val traversal = requestTraversal(wms, default)

                diag(
                    "restore($reason) 默认屏 clear=$clear reset=$reset layout=$layout traversal=$traversal " +
                        stateOf(default)
                )

                // 只记下来，真正的 IMMS 调用留到锁外 —— 见下面第 3 步。
                touched = default
            }

            if (lock != null) {
                synchronized(lock) { runCatching { work() }.getOrElse { diag("restore($reason) 异常: ${describe(it)}") } }
            } else {
                work()
            }

            // 3. IMMS 侧「已显示」的残留状态也要清掉，否则新的显示请求会被
            //    PHASE_SERVER_ALREADY_VISIBLE 直接取消（日志里就是这么失败的）。
            //
            //    这一步**绝不能**放在上面那个 synchronized 里面，也不能在主线程做：
            //    IMMS 的方法都要先拿 ImfLock，而 android.imms 线程持着 ImfLock 时会回调
            //    WMS（`isImeInputTargetStaleForUpdate`）去要 global lock。
            //    锁内调用 = 「持 WMS 锁等 ImfLock」对上「持 ImfLock 等 WMS 锁」，
            //    标准的 ABBA 死锁，直接卡死整个 system_server（Watchdog 68s 后杀进程重启）。
            touched?.let { dc ->
                worker.post {
                    runCatching { diag("restore($reason) stateReset=[${resetImeVisibility(wms, dc)}]") }
                        .onFailure { diag("restore($reason) stateReset 异常: ${describe(it)}") }
                }
            }
        }.onFailure { diag("restore($reason) 异常: ${describe(it)}") }
    }

    /**
     * 已知局限：这一步修不好「展开后键盘唤不出」。
     *
     * 试过但被证伪的路子，记录下来免得以后重复踩：
     *
     * 1. 重放焦点变化。想在 `IWindow` 上调 `windowFocusChanged(false/true)`
     *    让应用自己重新 `startInput`，但 Android 17 的 `IWindow$Stub$Proxy`
     *    里**没有任何** `windowFocusChanged` 重载（反射枚举为空），API 已删除；
     * 2. 纠正 `ClientState.mSelfReportedDisplayId`。`dumpsys input_method`
     *    里确实能看到应用还挂着虚拟屏的历史 ClientState，但 Android 17 改成了
     *    per-display 会话模型：IMMS 里根本没有 `mCurClient` 这个字段，
     *    默认屏那条 ClientState 也存在且屏号是对的（应用发出的
     *    `onRequestShow ... displayId 0` 就是证据）；
     * 3. 服务端强推显示。`showCurrentInput*` / `hideSoftInputFromServerForTest`
     *    全都能调通，但请求最终死在应用进程内部：
     *    `ImeTracker: onFailed at PHASE_CLIENT_VIEW_SERVED`。
     *
     * 根因：`FreeformManager.moveTaskFromDisplayToDefault` 调了
     * `HookReload.suppressNextRelaunch`，跨屏迁移时故意不让 Activity 重建，
     * 于是应用进程的 window / ViewRootImpl / IME 客户端状态和系统记录长期错位。
     * 真正能修的是放开那次重建（代价：展开瞬间闪一下、页面状态可能重载），
     * 目前按「保持展开不闪」取舍，接受这个缺陷。
     */

    /**
     * 把 IMMS 的「键盘已显示」状态纠正过来。
     *
     * 小窗退出后常见的坏状态：WMS 上 IME 窗口是 GONE、`mHasSurface=false`，
     * 而 IMMS 的 `mImeWindowVis` / `mInputShown` 还停在「已显示」。
     * 这时 App 发出的每个显示请求都会被 `PHASE_SERVER_ALREADY_VISIBLE` 取消，
     * 表现就是「全屏应用点输入框，键盘怎么都不出来」——
     * `dumpsys input_method` 的 tracker 历史里能直接看到这条 CANCEL。
     *
     * 这里不硬写字段，走系统的隐藏入口让 IMMS 自己把状态刷回 hidden，
     * 下一次点输入框就是一次干净的显示请求。
     *
     * **调用约束（踩过一次系统卡死，务必守住）**：只能在 [worker] 上、且**不持有**
     * WMS 的 `WindowManagerGlobalLock` 时调用。IMMS 的每个入口都先拿 `ImfLock`，
     * 而 `android.imms` 线程持着 `ImfLock` 时会回调 WMS 的
     * `isImeInputTargetStaleForUpdate` 去拿 global lock；持锁调用就是 ABBA 死锁，
     * 主线程卡住 60s 后 Watchdog 杀 system_server 重启（现场见
     * `/data/anr/anr_2026-11-08-18-51-19-167`：main 持 lock 等 ImfLock，
     * android.imms 持 ImfLock 等 lock）。
     */
    private fun resetImeVisibility(wms: Any, dc: Any): String {
        if (imeWindowShown(wms)) return "键盘实际可见，跳过"
        val immsImpl = inputMethodService() ?: return "拿不到 input_method 服务"
        val imms = unwrapInputMethodService(immsImpl) ?: immsImpl
        val token = collectTokens(dc).firstOrNull()?.second

        val results = ArrayList<String>()
        results.add(
            "serverForTest=" + invokeFirst(
                imms,
                listOf("hideSoftInputFromServerForTest" to arrayOf<Any?>())
            )
        )
        if (token != null) {
            results.add(
                "byWindow=" + invokeFirst(
                    imms,
                    listOf(
                        "hideCurrentInputInternal" to arrayOf<Any?>(token, null),
                        "hideCurrentInputLocked" to arrayOf<Any?>(token, false, null, 0, 0),
                        "hideSoftInputFromWindow" to arrayOf<Any?>(token, 0, null)
                    )
                )
            )
        } else {
            results.add("byWindow=没有可用窗口 token")
        }
        return results.joinToString(" ")
    }

    /**
     * 双击底栏：修状态 + 必要时重推显示。
     * 调用方（双击手势）不关心结果，排查只看 logcat。
     */
    fun retrigger(origin: String = "unknown") {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastRunAt < THROTTLE_MS) {
            diag("忽略：距上次 ${now - lastRunAt}ms，节流中")
            return
        }
        lastRunAt = now
        runCatching { retriggerInner(origin) }
            .onFailure { diag("origin=$origin 异常: ${describe(it)}") }
    }

    private fun retriggerInner(origin: String) {
        diag("===== origin=$origin =====")

        val wms = windowManagerService()
        if (wms == null) {
            diag("放弃：拿不到 window 服务")
            return
        }

        val dc = managedDisplayContent(wms)
        if (dc == null) {
            diag("放弃：找不到小窗所在虚拟屏")
            return
        }
        val displayId = displayIdOf(dc)
        diag("小窗屏 display=$displayId ${stateOf(dc)}")
        diag("键盘 ${imeWindowState(wms)}")

        // 1. 状态修复：策略 + 锚点 + 布局。这一步实测就能把键盘带出来。
        val repaired = repairState(wms, dc, displayId)
        diag("状态修复 -> $repaired")

        // 2. 给它一帧多的时间；已经出来了就不再做多余的请求。
        handler.postDelayed({
            if (imeWindowShown(wms)) {
                diag("修复后键盘已自行显示，不再重推")
                return@postDelayed
            }
            diag("修复后键盘仍未显示，开始重推 ${imeWindowState(wms)}")
            pushDisplay(wms, dc, displayId)
        }, VERIFY_DELAY_MS)
    }

    /** 修完状态还没出来时，才轮到请求侧：焦点抖动 → show → hide→show。 */
    private fun pushDisplay(wms: Any, dc: Any, displayId: Int) {
        val immsImpl = inputMethodService()
        if (immsImpl == null) {
            diag("放弃重推：拿不到 input_method 服务")
            return
        }
        val imms = unwrapInputMethodService(immsImpl) ?: immsImpl
        diag("imms=${imms.javaClass.simpleName}")

        val tokens = collectTokens(dc)
        if (tokens.isEmpty()) {
            diag("放弃重推：没有可用窗口 token")
            return
        }
        diag("候选 token=${tokens.size} 个：${tokens.joinToString { it.first }}")

        val steps = listOf(
            Step("S1 show") {
                tokens.forEach { diag("  show -> ${showIme(imms, it.second)}") }
            },
            Step("S2 hide->show") {
                tokens.forEach { diag("  hide -> ${hideIme(imms, it.second)}") }
                handler.postDelayed({
                    tokens.forEach { diag("  show -> ${showIme(imms, it.second)}") }
                }, HIDE_SHOW_GAP_MS)
            }
        )
        runStep(wms, steps, 0)
    }

    private fun runStep(wms: Any, steps: List<Step>, index: Int) {
        if (index >= steps.size) {
            diag("都试完，键盘 ${imeWindowState(wms)} —— 仍未显示")
            return
        }
        val step = steps[index]
        val result = runCatching { step.action(); "ok" }.getOrElse { describe(it) }
        diag("${step.name} 执行 $result")

        handler.postDelayed({
            val shown = imeWindowShown(wms)
            diag("${step.name} 之后 键盘 ${imeWindowState(wms)}")
            if (shown) {
                diag("${step.name} 生效，停止后续尝试")
                return@postDelayed
            }
            runStep(wms, steps, index + 1)
        }, VERIFY_DELAY_MS)
    }

    // ========== 状态修复（核心） ==========

    /**
     * 策略校正 + 锚点重算 + 请求布局。三步都走系统方法，失败只记日志不中断。
     */
    private fun repairState(wms: Any, dc: Any, displayId: Int): String {
        ensureFallbackPolicy(wms, dc, displayId)
        val anchor = ensureImeLayeringTarget(wms, dc)
        return "$anchor ${stateOf(dc)}"
    }

    /**
     * 把小窗屏的 IME 策略钉成「键盘回落到默认屏」。
     *
     * 策略为 LOCAL 时 WMS 认为键盘该长在虚拟屏里，而虚拟屏上根本没有 IME 窗口，
     * 请求就此落空；FALLBACK 才会把「请求输入法可见」转到真正承载键盘的默认屏。
     * 策略存在 display 缓存表里，显示变化时会被整表重建，所以每次都要校正。
     */
    private fun ensureFallbackPolicy(wms: Any, dc: Any, displayId: Int) {
        val current = imePolicyOf(dc)
        if (current == IME_POLICY_FALLBACK_DISPLAY) return

        val method = findMethod(wms, "setDisplayImePolicy", 2)
        if (method == null) {
            diag("  setDisplayImePolicy 不存在，跳过（policy=$current）")
            return
        }
        val r = runCatching { method.invoke(wms, displayId, IME_POLICY_FALLBACK_DISPLAY) }
        diag("  setDisplayImePolicy($displayId, FALLBACK) -> ${r.fold({ "ok" }, { describe(it) })}")
        // 策略变了必须让 WMS 重算 IME 控制目标，否则这块屏还挂在 App 窗口上。
        runCatching { findMethod(dc, "updateImeControlTarget", 1)?.invoke(dc, true) }
        diag("  policy 现在=${imePolicyOf(dc)}")
    }

    /**
     * 重算 `mImeLayeringTarget`（键盘挂载锚点）。
     *
     * 只调系统方法、不写字段——直接写字段能被读到，但下一次焦点/布局计算就会被清回
     * null，说明它由 WMS 的计算结果说了算，外部硬塞无效。所以这里的目的不是「把值塞进去」，
     * 而是「逼 WMS 自己算一遍」：先置空再设回，同一个值会被去重判断吃掉。
     */
    private fun ensureImeLayeringTarget(wms: Any, dc: Any): String {
        val existing = objectField(dc, "mImeLayeringTarget")
        if (existing != null) return "锚点已存在(${brief(existing)})"

        val lock = objectField(wms, "mGlobalLock")
        val anchor = objectField(dc, "mImeInputTarget") ?: objectField(dc, "mCurrentFocus")

        fun fixInsideLock(): String {
            val clear = callFirst(dc, "updateImeInputAndControlTarget", listOf(null))
            val reset = callFirst(dc, "updateImeInputAndControlTarget", listOf(anchor, null))
            val layout = callFirst(dc, "setLayoutNeeded", emptyList<Any?>())
            val traversal = requestTraversal(wms, dc)
            return "clear=$clear reset=$reset layout=$layout traversal=$traversal " +
                "layer=${brief(objectField(dc, "mImeLayeringTarget"))}"
        }

        return if (lock != null) {
            synchronized(lock) { runCatching { fixInsideLock() }.getOrElse { describe(it) } }
        } else {
            runCatching { fixInsideLock() }.getOrElse { describe(it) }
        }
    }

    /** 催一次布局：`mWindowPlacerLocked` 上优先，没有就退回 DisplayContent / RootWindowContainer。 */
    private fun requestTraversal(wms: Any, dc: Any): String {
        val targets = listOfNotNull(
            objectField(wms, "mWindowPlacerLocked")?.let { "placer" to it },
            "dc" to dc,
            objectField(wms, "mRoot")?.let { "root" to it },
            objectField(wms, "mRootWindowContainer")?.let { "root" to it }
        )
        for ((label, target) in targets) {
            val r = callFirst(target, "requestTraversal", emptyList<Any?>())
            if (r.endsWith(" ok")) return "$label::$r"
        }
        return "无可用 requestTraversal"
    }

    /**
     * 按方法名调用，重载逐个试。参数按「候选值 → null」的顺序试。
     *
     * 注意 0 参方法必须用空数组调用：`invoke(target, null)` 在 Kotlin 里会被当成
     * 「一个 null 参数」，直接 IllegalArgumentException "expected 0, got 1"
     * ——之前 `setLayoutNeeded` / `requestTraversal` 一直"失败"就是这个原因。
     */
    private fun callFirst(target: Any, name: String, candidates: List<Any?>): String {
        val byName = allMethodsOf(target).filter { it.name == name }
        if (byName.isEmpty()) return "$name 无此方法"

        var last = ""
        for (method in byName) {
            val argSets = if (method.parameterCount == 0) {
                listOf(emptyArray<Any?>())
            } else {
                candidates.map { arrayOf<Any?>(it) } + listOf(arrayOf<Any?>(null))
            }
            for (args in argSets) {
                val r = runCatching { method.invoke(target, *args) }
                if (r.isSuccess) {
                    val types = method.parameterTypes.joinToString(",") { it.simpleName }
                    return "$name($types) ok"
                }
                last = describe(r.exceptionOrNull()!!)
            }
        }
        return "$name 失败($last)"
    }

    // ========== 请求侧（兜底） ==========

    private class Step(val name: String, val action: () -> Unit)

    /**
     * 请求显示键盘。
     *
     * Android 17 已经删掉了 `IInputMethodManager.showSoftInput`（整个 AIDL 里都没有），
     * 真正的入口变成 IMMS 内部的 `showCurrentInput*`。第二个参数是个内部 `Token`，
     * 拿不到就传 null——它对「重新显示当前输入法」不是必需的。
     */
    private fun showIme(imms: Any, token: IBinder): String = invokeFirst(
        imms,
        listOf(
            "showCurrentInputInternal" to arrayOf<Any?>(token, null, true),
            "showCurrentInputInternal" to arrayOf<Any?>(token, null, false),
            "showCurrentInputLocked" to arrayOf<Any?>(token, null, 0, 0, false),
            "showCurrentInput" to arrayOf<Any?>(token),
            "showSoftInput" to arrayOf<Any?>(token, 0, null)
        )
    )

    private fun hideIme(imms: Any, token: IBinder): String = invokeFirst(
        imms,
        listOf(
            "hideCurrentInputInternal" to arrayOf<Any?>(token, null),
            "hideCurrentInputLocked" to arrayOf<Any?>(token, false, null, 0, 0),
            "hideSoftInputFromServerForTest" to arrayOf<Any?>(),
            "hideSoftInputFromWindow" to arrayOf<Any?>(token, 0, null)
        )
    )

    /**
     * 按顺序尝试「方法名 + 参数个数」，命中即返回。
     * 方法存在但抛异常时直接把异常带出来——那说明签名对上了、参数或状态不对，
     * 比一句「无匹配方法」有用得多。
     */
    private fun invokeFirst(target: Any, attempts: List<Pair<String, Array<Any?>>>): String {
        val tried = ArrayList<String>()
        for ((name, args) in attempts) {
            val method = findMethod(target, name, args.size) ?: continue
            tried.add("$name/${args.size}")
            val result = runCatching { method.invoke(target, *args) }
            if (result.isSuccess) {
                val ret = result.getOrNull()
                return "$name/${args.size} ✓" + if (ret != null) " (ret=$ret)" else ""
            }
            return "$name/${args.size} 抛异常: ${describe(result.exceptionOrNull()!!)}"
        }
        return "无匹配方法（试过 ${tried.joinToString().ifEmpty { "0 个签名" }}）"
    }

    private fun findMethod(target: Any, name: String, argCount: Int): Method? =
        allMethodsOf(target).firstOrNull { it.name == name && it.parameterCount == argCount }

    // ========== 目标窗口 / 状态 ==========

    /**
     * 找小窗所在的虚拟屏：优先「有 IME 输入目标且不是默认屏」的那块。
     * 任务可能刚被搬过去、输入目标还没算出来，这时退回「有应用窗口的默认屏之外的屏」。
     */
    private fun managedDisplayContent(wms: Any): Any? {
        val all = displayContents(wms)
        all.firstOrNull { displayIdOf(it) != 0 && objectField(it, "mImeInputTarget") != null }
            ?.let { return it }
        return all.firstOrNull { dc ->
            displayIdOf(dc) != 0 &&
                objectField(dc, "mCurrentFocus")?.let { windowType(it) == TYPE_BASE_APPLICATION } == true
        }
    }

    /**
     * 收集候选窗口 token：输入目标 > 挂载锚点 > 当前焦点。
     * 多给几个是无害的，IMMS 的 show 内部会 checkFocus，不是焦点的窗口直接被拒。
     */
    private fun collectTokens(dc: Any): List<Pair<String, IBinder>> {
        val out = ArrayList<Pair<String, IBinder>>()

        fun add(source: String, value: Any?, asWindowState: Boolean = false) {
            val binder = if (asWindowState) windowToken(value) else tokenOf(value)
            if (binder == null) return
            if (out.none { it.second == binder }) out.add(source to binder)
        }

        add("mImeInputTarget", objectField(dc, "mImeInputTarget"))
        add("mImeLayeringTarget", objectField(dc, "mImeLayeringTarget"), asWindowState = true)
        add("mCurrentFocus", objectField(dc, "mCurrentFocus"), asWindowState = true)
        return out
    }

    /** 从一个对象上抠出 IWindow/IBinder：InputTarget、WindowState、IInterface 都吃。 */
    private fun tokenOf(value: Any?): IBinder? {
        if (value == null) return null
        if (value is IBinder) return value
        if (value is IInterface) return value.asBinder()

        runCatching { XposedHelpers.callMethod(value, "getIWindow") }.getOrNull()
            ?.let { v -> ((v as? IBinder) ?: (v as? IInterface)?.asBinder())?.let { return it } }

        for (f in allFields(value.javaClass)) {
            if (Modifier.isStatic(f.modifiers) || f.isSynthetic) continue
            val v = readField(f, value) ?: continue
            when (v) {
                is IBinder -> return v
                is IInterface -> return v.asBinder()
            }
            if (v.javaClass.name.contains("WindowState")) windowToken(v)?.let { return it }
        }
        return null
    }

    /** WindowState -> `mClient` 的 binder，也就是 IME 认的那个 window token。 */
    private fun windowToken(windowState: Any?): IBinder? {
        val client = objectField(windowState, "mClient") ?: return null
        return (client as? IInterface)?.asBinder()
    }

    /**
     * 键盘窗口当前状态。
     *
     * 判据注意：键盘跑在主屏（FALLBACK 策略），所以「显示」看的是
     * `mHasSurface && mViewVisibility == VISIBLE`，不要拿「在不在目标屏上」当条件
     * ——那样永远判负，之前几轮误判就是这么来的。
     */
    private fun imeWindowState(wms: Any): String {
        val map = objectField(wms, "mWindowMap") as? Map<*, *> ?: return "窗口表不可用"
        val parts = ArrayList<String>()
        for (w in map.values) {
            val type = windowType(w)
            if (type != TYPE_INPUT_METHOD && type != TYPE_INPUT_METHOD_DIALOG) continue
            val attrs = objectField(w, "mAttrs")
            val pkg = objectField(attrs, "packageName")?.toString() ?: "?"
            parts.add(
                "display=${windowDisplayId(w)} pkg=$pkg " +
                    "viewVis=${intField(w, "mViewVisibility", -1)} " +
                    "surface=${hasSurface(w)} shown=${isWindowVisible(w)}"
            )
        }
        return if (parts.isEmpty()) "无 IME 窗口" else parts.joinToString(" | ")
    }

    private fun imeWindowShown(wms: Any): Boolean {
        val map = objectField(wms, "mWindowMap") as? Map<*, *> ?: return false
        return map.values.any { w ->
            val type = windowType(w)
            (type == TYPE_INPUT_METHOD || type == TYPE_INPUT_METHOD_DIALOG) && isWindowVisible(w)
        }
    }

    /** 小窗屏上的 IME 相关字段，一行看完。 */
    private fun stateOf(dc: Any): String =
        "policy=${imePolicyOf(dc)} " +
            "layer=${brief(objectField(dc, "mImeLayeringTarget"))} " +
            "input=${brief(objectField(dc, "mImeInputTarget"))} " +
            "imeWin=${brief(objectField(dc, "mImeWindow"))} " +
            "focus=${brief(objectField(dc, "mCurrentFocus"))}"

    private fun imePolicyOf(dc: Any): Int {
        val byMethod = runCatching { XposedHelpers.callMethod(dc, "getImePolicy") as Int }.getOrNull()
        return byMethod ?: intField(dc, "mImePolicy", -1)
    }

    private fun hasSurface(windowState: Any?): Boolean =
        windowState != null && runCatching { XposedHelpers.getBooleanField(windowState, "mHasSurface") }
            .getOrElse { false }

    private fun isWindowVisible(windowState: Any?): Boolean {
        if (windowState == null) return false
        return hasSurface(windowState) && intField(windowState, "mViewVisibility", 4) == VISIBLE
    }

    private fun windowType(windowState: Any?): Int {
        val attrs = objectField(windowState, "mAttrs") ?: return -1
        return intField(attrs, "type", -1)
    }

    /** WindowState 上没有 `mDisplayId` 字段，只有 `getDisplayId()`。 */
    private fun windowDisplayId(windowState: Any?): Int {
        if (windowState == null) return -1
        return runCatching { XposedHelpers.callMethod(windowState, "getDisplayId") as Int }.getOrElse { -1 }
    }

    private fun displayContentOf(wms: Any, displayId: Int): Any? =
        displayContents(wms).firstOrNull { displayIdOf(it) == displayId }

    private fun displayContents(wms: Any?): List<Any> {
        val out = ArrayList<Any>()
        val root = objectField(wms, "mRoot") ?: objectField(wms, "mRootWindowContainer") ?: return out
        val children = objectField(root, "mChildren")
        if (children is Iterable<*>) {
            children.filterIsInstance<Any>().forEach { child ->
                collectDisplays(child, out, 0)
            }
        }
        return out.distinct()
    }

    /** DisplayContent 可能挂在 RootWindowContainer 下，也可能在中间的容器里，递归两层够用。 */
    private fun collectDisplays(node: Any?, out: MutableList<Any>, depth: Int) {
        if (node == null || depth > 2) return
        if (node.javaClass.name.contains("DisplayContent")) {
            out.add(node)
            return
        }
        val children = objectField(node, "mChildren") as? Iterable<*> ?: return
        children.filterIsInstance<Any>().forEach { collectDisplays(it, out, depth + 1) }
    }

    private fun displayIdOf(displayContent: Any?): Int {
        if (displayContent == null) return -1
        val byMethod = runCatching { XposedHelpers.callMethod(displayContent, "getDisplayId") as Int }.getOrNull()
        return byMethod ?: intField(displayContent, "mDisplayId", -1)
    }

    // ========== 反射工具 ==========

    private fun windowManagerService(): Any? = resolveService(
        serviceName = "window",
        descriptors = arrayOf("android.view.IWindowManager"),
        stubClassNames = arrayOf("android.view.IWindowManager\$Stub")
    )

    private fun inputMethodService(): Any? = resolveService(
        serviceName = "input_method",
        descriptors = arrayOf(
            "android.view.inputmethod.IInputMethodManager",
            "com.android.internal.view.IInputMethodManager"
        ),
        stubClassNames = arrayOf(
            "android.view.inputmethod.IInputMethodManager\$Stub",
            "com.android.internal.view.IInputMethodManager\$Stub"
        )
    )

    /**
     * 取真正的 InputMethodManagerService。
     *
     * Android 17 的结构是三层：
     *   IInputMethodManagerImpl(AIDL 外壳，只做权限校验)
     *     └─ mCallback: ZeroJankProxy(排队防卡顿包装)
     *          └─ InputMethodManagerServiceImpl(真正的实现)
     * 所以按「包名 + 类名」在 inputmethod 包里做限深搜索，比猜字段名稳。
     */
    private fun unwrapInputMethodService(service: Any): Any? {
        fun walk(node: Any, depth: Int, seen: MutableSet<Any>): Any? {
            if (depth > MAX_UNWRAP_DEPTH || !seen.add(node)) return null
            val cls = node.javaClass
            if (cls.name.contains("InputMethodManagerService")) return node
            for (f in allFields(cls)) {
                if (f.isSynthetic || Modifier.isStatic(f.modifiers)) continue
                if (f.type.isPrimitive || f.type == String::class.java) continue
                val value = readField(f, node) ?: continue
                if (!value.javaClass.name.startsWith(IME_PACKAGE)) continue
                walk(value, depth + 1, seen)?.let { return it }
            }
            return null
        }
        return walk(service, 0, java.util.Collections.newSetFromMap(java.util.IdentityHashMap()))
    }

    /**
     * 取本进程内的系统服务实例。
     *
     * 两条路，先走不带类解析的那条：
     * 1. `binder.queryLocalInterface(descriptor)`——服务端 Binder 在描述符对得上时
     *    直接把 self 返回，这就是服务对象本身，不用 `Class.forName` 解析内部类；
     * 2. `Stub.asInterface(binder)`——退回标准 AIDL 入口。
     */
    private fun resolveService(
        serviceName: String,
        descriptors: Array<String>,
        stubClassNames: Array<String>
    ): Any? {
        val binder = runCatching { ServiceManager.getService(serviceName) }
            .getOrElse {
                diag("ServiceManager.getService($serviceName) 抛异常: ${describe(it)}")
                return null
            }
        if (binder == null) {
            diag("ServiceManager.getService($serviceName) 返回 null")
            return null
        }

        for (descriptor in descriptors) {
            val local = runCatching { binder.queryLocalInterface(descriptor) }
                .getOrElse {
                    diag("  queryLocalInterface($descriptor) 抛异常: ${describe(it)}")
                    continue
                }
            if (local != null) return local
        }

        for (stubName in stubClassNames) {
            val stub = runCatching { Class.forName(stubName) }.getOrElse { continue }
            val asInterface = runCatching { stub.getMethod("asInterface", IBinder::class.java) }
                .getOrElse { continue }
            val service = runCatching { asInterface.invoke(null, binder) }.getOrNull()
            if (service != null) return service
        }
        diag("getService($serviceName) 全部取法失败")
        return null
    }

    private fun objectField(obj: Any?, name: String): Any? =
        obj?.let { runCatching { XposedHelpers.getObjectField(it, name) }.getOrNull() }

    private fun intField(obj: Any?, name: String, default: Int): Int =
        obj?.let { runCatching { XposedHelpers.getIntField(it, name) }.getOrElse { default } } ?: default

    private fun allMethodsOf(target: Any): List<Method> {
        val out = ArrayList<Method>()
        var c: Class<*>? = target.javaClass
        while (c != null && c != Any::class.java) {
            c.declaredMethods.forEach { m ->
                if (!m.isSynthetic) {
                    runCatching { m.isAccessible = true }
                    out.add(m)
                }
            }
            c = c.superclass
        }
        return out
    }

    private fun allFields(cls: Class<*>): List<Field> {
        val out = ArrayList<Field>()
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            c.declaredFields.forEach { out.add(it) }
            c = c.superclass
        }
        return out
    }

    private fun readField(f: Field, target: Any): Any? =
        runCatching { f.isAccessible = true; f.get(target) }.getOrNull()

    /** 只打类名和包名，避免把 WindowState.toString() 的一大坨全刷出来。 */
    private fun brief(obj: Any?): String {
        if (obj == null) return "null"
        val pkg = objectField(obj, "mAttrs")?.let { objectField(it, "packageName") }?.toString()
        return pkg?.let { "${obj.javaClass.simpleName}($it)" } ?: obj.javaClass.simpleName
    }

    private fun describe(t: Throwable): String {
        val cause = t.cause ?: t
        return "${cause.javaClass.simpleName}: ${cause.message}"
    }

    private fun diag(msg: String) = XLog.ls(msg, DIAG_TAG)
}
