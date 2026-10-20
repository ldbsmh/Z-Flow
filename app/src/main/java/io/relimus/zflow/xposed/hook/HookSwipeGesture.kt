package io.relimus.zflow.xposed.hook

import android.content.ComponentName
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.kyuubiran.ezxhelper.core.util.ClassUtil.loadClass
import io.github.kyuubiran.ezxhelper.core.util.ObjectUtil
import io.relimus.zflow.broadcast.StartFreeformReceiver
import io.relimus.zflow.providers.BlacklistProvider
import io.relimus.zflow.providers.LandscapeAppsProvider
import io.relimus.zflow.utils.cast
import io.relimus.zflow.xposed.hook.utils.XLog
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object HookSwipeGesture {

    private const val PROGRESS_SHOW_THRESHOLD = 3f
    private const val PROGRESS_HIDE_THRESHOLD = 2f

    private const val HINT_ANIM_DURATION = 150L
    private const val MINI_LAUNCH_DELAY_MS = 120L

    /**
     * 一次手势只允许产出一个窗口。
     *
     * Launcher3 在同一次上滑里可能把 runOnceAtState/onStateSetEndTarget 走两遍
     * （onSwipeUpAnimationComplete 与状态机各一次），且 taskId 在两次之间可能已经
     * 从 X 变成 X+1，导致下游按 "pkg|class|user|taskId" 去重拦不住。
     * 这里用一个与 taskId 无关的时间闸门兜底：
     * [MINI_LAUNCH_DEBOUNCE_MS] 内的重复提交直接丢弃，只认第一次。
     */
    private const val MINI_LAUNCH_DEBOUNCE_MS = 1_000L

    private const val DEBUG_TAG =
        "ZFlowSwipe-Diag"

    /*
     * 父类与子类的同一次 initStateCallbacks 调用可能均被 Hook。
     * 同一个 Handler 在该时间内只初始化一次。
     *
     * 32ms 太短：AbsSwipeUpHandler / LauncherSwipeHandlerV2 / FallbackSwipeHandler
     * 三个 Hook 入口之间存在帧间隔，一旦跨过 32ms 就会对同一个 handler 再走一次
     * beginGesture，gestureGeneration 递增，两代 runOnceAtState 回调各自成立，
     * 于是同一次上滑发出两次广播（表现为一次手势开两个小窗）。
     * 一个手势的完整生命周期远小于 600ms，取 600ms 只会合并同一次初始化。
     */
    private const val INIT_DEDUPLICATION_MS = 600L

    /*
     * 父类与子类的 updateSysUiFlags Hook 可能在同一调用链中触发。
     */
    private const val UPDATE_DEDUPLICATION_MS = 2L

    private const val UPDATE_HEARTBEAT_INTERVAL_MS =
        3_000L

    // 诊断日志总开关，默认关闭，避免刷屏
    private const val ENABLE_DIAGNOSTIC_LOGS = false

    @Volatile
    private var inited = false

    private var hookGeneration = -1L

    private var gestureStateClassRef:
        Class<*>? = null

    private var gestureEndTargetClassRef:
        Class<*>? = null

    private var hintView: View? = null
    private var dismissingHintView: View? = null

    @Volatile
    private var hintDismissed = false

    @Volatile
    private var gestureArmed = true

    @Volatile
    private var releaseInDropTarget = false

    @Volatile
    private var dropTargetShowing = false

    private val mainHandler =
        Handler(Looper.getMainLooper())

    private var pendingMiniLaunch: Runnable? = null

    /** 上次真正排入小窗启动的时间（uptimeMillis）；用于同手势重复提交的防抖。 */
    @Volatile
    private var lastMiniLaunchAt = 0L

    private var lastScreenWidth = 0
    private var lastScreenHeight = 0
    private var lastRotation = -1

    private enum class GestureDisplayMode {
        NONE,
        PORTRAIT,
        LANDSCAPE
    }

    @Volatile
    private var gestureDisplayMode =
        GestureDisplayMode.NONE

    private var gestureGeneration = 0L
    private var activeGestureHandler: Any? = null

    /*
     * 每个 Handler 当前注册的 generation。
     * WeakHashMap 不会长期持有已经废弃的 Handler。
     */
    private val registeredHandlerGenerations =
        WeakHashMap<Any, Long>()

    private val lastInitTimes =
        WeakHashMap<Any, Long>()

    private val lastUpdateTimes =
        WeakHashMap<Any, Long>()

    private val lastUpdateProgressBits =
        WeakHashMap<Any, Int>()

    private val hookedClassNames =
        Collections.synchronizedSet(
            HashSet<String>()
        )

    private val landscapeAppCache =
        ConcurrentHashMap<String, Boolean>()

    /** 横屏名单版本号；与 Provider 自增的版本号比对，决定缓存是否有效。 */
    @Volatile
    private var landscapeListVersion: Long? = null

    /** 上次校验横屏名单版本号的时间，语义同 [blacklistCheckedAt]。 */
    @Volatile
    private var landscapeCheckedAt = 0L

    private val unavailableErrorReported =
        AtomicBoolean(false)

    /** 名单缓存的版本校验间隔：窗口内直接命中本地值，不发 Binder。 */
    private const val CACHE_REVALIDATE_MS = 3_000L

    /** 缓存条目上限；被 hook 的进程长期存活，超出后整体清空重新填充。 */
    private const val CACHE_MAX_ENTRIES = 512

    private fun ConcurrentHashMap<String, Boolean>.putBounded(key: String, value: Boolean) {
        if (size >= CACHE_MAX_ENTRIES) clear()
        put(key, value)
    }

    private enum class ProgressZone {
        UNKNOWN,
        LOW,
        MIDDLE,
        HIGH
    }

    private var lastProgressZone =
        ProgressZone.UNKNOWN

    // 记录当前 progress zone 所属的 Handler，避免被不同 Handler 污染
    private var lastProgressZoneHandler: Any? = null

    private var lastUpdateHeartbeatTime = 0L
    private var updateCallbackCount = 0L
    private var inputDownCount = 0L

    /**
     * 标记当前手势已进入“可提交启动”状态。
     *
     * 在 Android 17 上，自绘的提示框（drop target）会正常显示（日志 SHOW_HINT_ADDED /
     * SHOW_HINT_POSITIONED 可见，dropTargetShowing=true 且 hintView 已 attach），
     * 但 Launcher3 的原始输入流不再把抬手 ACTION_UP 送到我们 Hook 的入口
     * （INPUT_UP 诊断永远不出现）。于是依赖 Action_UP 命中测试的 releaseInDropTarget
     * 恒为 false，handleGestureEnd 的 shouldLaunch 恒为 false，小窗永远不会启动。
     *
     * 因此把手势“是否提交”的判决从脆弱的 raw-motion 命中测试，
     * 改为：提示框确实显示出来了（用户上滑足够高，已展示 drop target），
     * 且本次手势没有中途被取消。抬手时若提示框正显示，即视为提交。
     */
    @Volatile
    private var hintCommittedThisGesture = false

    /**
     * resolveContext 解析出的可用 Context 缓存，避免每次手势结束后
     * 还要再反射一遍。
     */
    @Volatile
    private var cachedSwipeContext: Context? = null

    private fun debugLog(message: String) {
        if (!ENABLE_DIAGNOSTIC_LOGS) {
            return
        }
        XposedBridge.log(
            "[$DEBUG_TAG][${SystemClock.uptimeMillis()}] $message"
        )
    }

    /**
     * 关键节点日志。与 debugLog 一样受 ENABLE_DIAGNOSTIC_LOGS 控制，
     * 默认关闭。真实错误仍通过 reportUnavailable（XLog.e）输出到 LSPosed。
     */
    private fun lifecycleLog(message: String) {
        if (!ENABLE_DIAGNOSTIC_LOGS) {
            return
        }
        XposedBridge.log("[$DEBUG_TAG][LIFECYCLE] $message")
    }

    private fun reportUnavailable(
        operation: String,
        throwable: Throwable
    ) {
        if (
            !unavailableErrorReported.compareAndSet(
                false,
                true
            )
        ) {
            return
        }

        XposedBridge.log(
            "[ZFlowSwipe] unavailable at $operation: " +
                "${throwable.javaClass.name}: " +
                throwable.message.orEmpty()
        )

        XposedBridge.log(throwable)
    }

    private fun identity(value: Any?): String {
        if (value == null) {
            return "null"
        }

        return buildString {
            append(value.javaClass.name)
            append('@')
            append(
                Integer.toHexString(
                    System.identityHashCode(value)
                )
            )
        }
    }

    private fun progressZone(
        progress: Float
    ): ProgressZone {
        return when {
            !progress.isFinite() ->
                ProgressZone.UNKNOWN

            progress < PROGRESS_HIDE_THRESHOLD ->
                ProgressZone.LOW

            progress < PROGRESS_SHOW_THRESHOLD ->
                ProgressZone.MIDDLE

            else ->
                ProgressZone.HIGH
        }
    }

    private fun stateSnapshot(): String {
        val activeView = hintView

        return buildString {
            append("generation=")
            append(gestureGeneration)

            append(" activeHandler=")
            append(identity(activeGestureHandler))

            append(" armed=")
            append(gestureArmed)

            append(" dismissed=")
            append(hintDismissed)

            append(" mode=")
            append(gestureDisplayMode)

            append(" showing=")
            append(dropTargetShowing)

            append(" releaseInside=")
            append(releaseInDropTarget)

            append(" hintView=")
            append(identity(activeView))

            append(" hintAttached=")
            append(activeView?.isAttachedToWindow)

            append(" hintParent=")
            append(identity(activeView?.parent))

            append(" hintSize=")
            append(activeView?.width ?: -1)
            append('x')
            append(activeView?.height ?: -1)

            append(" hintXY=(")
            append(activeView?.x ?: Float.NaN)
            append(',')
            append(activeView?.y ?: Float.NaN)
            append(')')

            append(" dismissingView=")
            append(identity(dismissingHintView))

            append(" portrait={")
            append(
                PortraitSwipeGestureHandler
                    .debugState()
            )
            append('}')

            append(" landscape={")
            append(
                LandscapeSwipeGestureHandler
                    .debugState()
            )
            append('}')

            append(" pendingLaunch=")
            append(pendingMiniLaunch != null)

            append(" screen=")
            append(lastScreenWidth)
            append('x')
            append(lastScreenHeight)

            append(" rotation=")
            append(lastRotation)
        }
    }

    @Synchronized
    fun init() {
        if (inited && hookGeneration == HookRegistry.generation) {
            lifecycleLog("init() skipped: already inited")
            return
        }
        hookGeneration = HookRegistry.generation
        inited = false

        // 热重载时清理手势运行态，避免旧状态残留
        hintView = null
        dismissingHintView = null
        dropTargetShowing = false
        releaseInDropTarget = false
        hintCommittedThisGesture = false
        activeGestureHandler = null

        lifecycleLog("init() enter")

        try {
            hookSwipeGesture()
            inited = true
            lifecycleLog("init() SUCCESS")
        } catch (throwable: Throwable) {
            inited = false

            lifecycleLog(
                "init() FAILED: ${throwable.javaClass.name}: " +
                    throwable.message.orEmpty()
            )

            reportUnavailable(
                operation = "initialization",
                throwable = throwable
            )
        }
    }

    private fun hookSwipeGesture() {
        val absSwipeClass =
            loadOptionalClass(
                "com.android.quickstep.AbsSwipeUpHandler"
            )

        gestureStateClassRef =
            loadOptionalClass(
                "com.android.quickstep.GestureState"
            )

        gestureEndTargetClassRef =
            loadOptionalClass(
                "com.android.quickstep.GestureState\$GestureEndTarget"
            )

        hookTouchInteractionService()

        var initHookCount = 0
        var updateHookCount = 0

        absSwipeClass?.let {
            val absResult =
                hookSwipeHandlerClass(it)

            initHookCount += absResult.first
            updateHookCount += absResult.second
        }

        /*
         * 当前日志中实际使用的 Handler。
         */
        loadOptionalClass(
            "com.android.quickstep.LauncherSwipeHandlerV2"
        )?.let {
            val result =
                hookSwipeHandlerClass(it)

            initHookCount += result.first
            updateHookCount += result.second
        }

        /*
         * 非 Launcher 或后备手势场景。
         */
        loadOptionalClass(
            "com.android.quickstep.FallbackSwipeHandler"
        )?.let {
            val result =
                hookSwipeHandlerClass(it)

            initHookCount += result.first
            updateHookCount += result.second
        }

        if (updateHookCount <= 0) {
            lifecycleLog(
                "SWIPE_HANDLER_HOOKS init=$initHookCount update=$updateHookCount " +
                    "absSwipeClass=${absSwipeClass != null} " +
                    "gestureState=${gestureStateClassRef != null} " +
                    "gestureEndTarget=${gestureEndTargetClassRef != null}"
            )
            throw IllegalStateException(
                "No updateSysUiFlags method was hooked"
            )
        }

        lifecycleLog(
            "HOOKS_INSTALLED initHookCount=$initHookCount " +
                "updateHookCount=$updateHookCount"
        )

        debugLog(
            "HOOKS_INSTALLED " +
                "initHookCount=$initHookCount " +
                "updateHookCount=$updateHookCount"
        )
    }

    private fun loadOptionalClass(
        className: String
    ): Class<*>? {
        return try {
            loadClass(className)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 返回：
     *
     * first  = initStateCallbacks Hook 数量
     * second = updateSysUiFlags Hook 数量
     */
    private fun hookSwipeHandlerClass(
        handlerClass: Class<*>
    ): Pair<Int, Int> {
        if (
            !hookedClassNames.add(
                handlerClass.name
            )
        ) {
            return 0 to 0
        }

        val initUnhooksCount =
            HookRegistry.hookAll(
                handlerClass,
                "initStateCallbacks",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(
                        param: MethodHookParam
                    ) {
                        val handler =
                            param.thisObject
                                ?: return

                        try {
                            initializeHandlerFromInit(
                                handler = handler,
                                sourceClass =
                                    handlerClass.name
                            )
                        } catch (
                            throwable: Throwable
                        ) {
                            recoverFromGestureFailure()

                            reportUnavailable(
                                operation =
                                    "${handlerClass.name}.initStateCallbacks",
                                throwable =
                                    throwable
                            )
                        }
                    }
                }
            )

        val updateUnhooksCount =
            HookRegistry.hookAll(
                handlerClass,
                "updateSysUiFlags",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(
                        param: MethodHookParam
                    ) {
                        val handler =
                            param.thisObject
                                ?: return

                        try {
                            val progress =
                                readProgress(handler)

                            if (
                                !shouldProcessUpdate(
                                    handler = handler,
                                    progress = progress
                                )
                            ) {
                                return
                            }

                            /*
                             * 忽略所有非活动 Handler 的低/中进度更新。
                             * 只有非活动 Handler 自身已经达到显示阈值时，
                             * 才认为 initStateCallbacks Hook 漏掉了真正的新手势，
                             * 并接管该 Handler。
                             */
                            if (activeGestureHandler !== handler) {
                                if (progress >= PROGRESS_SHOW_THRESHOLD) {
                                    debugLog(
                                        "ADOPT_HANDLER_FROM_UPDATE " +
                                            "old=${identity(activeGestureHandler)} " +
                                            "new=${identity(handler)} " +
                                            "progress=$progress " +
                                            "sourceClass=${handlerClass.name}"
                                    )

                                    initializeGestureHandler(
                                        handler = handler,
                                        source = "updateFallback"
                                    )
                                } else {
                                    // 非活动 Handler 的低/中进度直接忽略
                                    return
                                }
                            }

                            /*
                             * 初始化后再次确认当前 Handler。
                             */
                            if (activeGestureHandler !== handler) {
                                return
                            }

                            handleUpdateSysUiFlags(
                                handler = handler,
                                progress = progress
                            )
                        } catch (
                            throwable: Throwable
                        ) {
                            recoverFromGestureFailure()

                            reportUnavailable(
                                operation =
                                    "${handlerClass.name}.updateSysUiFlags",
                                throwable =
                                    throwable
                            )
                        }
                    }
                }
            )

        lifecycleLog(
            "HOOK_CLASS class=${handlerClass.name} " +
                "initMethods=$initUnhooksCount " +
                "updateMethods=$updateUnhooksCount"
        )

        debugLog(
            "HOOK_CLASS " +
                "class=${handlerClass.name} " +
                "initMethods=$initUnhooksCount " +
                "updateMethods=$updateUnhooksCount"
        )

        return initUnhooksCount to
            updateUnhooksCount
    }

    private fun initializeHandlerFromInit(
        handler: Any,
        sourceClass: String
    ) {
        val now =
            SystemClock.uptimeMillis()

        val shouldInitialize =
            synchronized(lastInitTimes) {
                val lastTime =
                    lastInitTimes[handler] ?: 0L

                if (
                    now - lastTime <
                    INIT_DEDUPLICATION_MS
                ) {
                    false
                } else {
                    lastInitTimes[handler] = now
                    true
                }
            }

        if (!shouldInitialize) {
            debugLog(
                "DUPLICATE_INIT_IGNORED " +
                    "handler=${identity(handler)} " +
                    "sourceClass=$sourceClass"
            )
            return
        }

        initializeGestureHandler(
            handler = handler,
            source = "init:$sourceClass"
        )
    }

    private fun initializeGestureHandler(
        handler: Any,
        source: String
    ) {
        val generation =
            beginGesture(handler)

        val gestureStateClass =
            gestureStateClassRef
                ?: throw IllegalStateException(
                    "GestureState class unavailable"
                )

        val gestureEndTargetClass =
            gestureEndTargetClassRef
                ?: throw IllegalStateException(
                    "GestureEndTarget class unavailable"
                )

        val stateEndTargetSet =
            XposedHelpers.getStaticIntField(
                gestureStateClass,
                "STATE_END_TARGET_SET"
            )

        val homeTarget =
            gestureEndTargetClass
                .enumConstants
                ?.firstOrNull()
                ?: throw IllegalStateException(
                    "GestureEndTarget has no constants"
                )

        val gestureState =
            ObjectUtil.getObjectUntilSuperclass(
                handler,
                "mGestureState"
            ) ?: throw IllegalStateException(
                "mGestureState not found"
            )

        synchronized(registeredHandlerGenerations) {
            registeredHandlerGenerations[handler] =
                generation
        }

        XposedHelpers.callMethod(
            gestureState,
            "runOnceAtState",
            stateEndTargetSet,
            Runnable {
                val registeredGeneration =
                    synchronized(
                        registeredHandlerGenerations
                    ) {
                        registeredHandlerGenerations[
                            handler
                        ]
                    }

                if (
                    generation != gestureGeneration ||
                    registeredGeneration != generation ||
                    activeGestureHandler !== handler
                ) {
                    debugLog(
                        "STALE_GESTURE_END " +
                            "callbackGeneration=$generation " +
                            "currentGeneration=$gestureGeneration " +
                            "registeredGeneration=$registeredGeneration " +
                            "handler=${identity(handler)} " +
                            "activeHandler=${identity(activeGestureHandler)}"
                    )

                    return@Runnable
                }

                try {
                    handleGestureEnd(
                        handler = handler,
                        gestureState = gestureState,
                        homeTarget = homeTarget
                    )
                } catch (
                    throwable: Throwable
                ) {
                    recoverFromGestureFailure()

                    reportUnavailable(
                        operation = "gestureEnd",
                        throwable = throwable
                    )
                } finally {
                    synchronized(
                        registeredHandlerGenerations
                    ) {
                        val current =
                            registeredHandlerGenerations[
                                handler
                            ]

                        if (current == generation) {
                            registeredHandlerGenerations
                                .remove(handler)
                        }
                    }
                }
            }
        )

        debugLog(
            "HANDLER_INITIALIZED " +
                "source=$source " +
                "handler=${identity(handler)} " +
                "generation=$generation"
        )
    }

    private fun beginGesture(
        handler: Any
    ): Long {
        gestureGeneration += 1L
        activeGestureHandler = handler

        hintDismissed = false
        gestureArmed = true
        releaseInDropTarget = false
        hintCommittedThisGesture = false

        gestureDisplayMode =
            GestureDisplayMode.NONE

        lastProgressZone =
            ProgressZone.UNKNOWN
        lastProgressZoneHandler = handler  // 重置关联

        removeHint(
            immediate = true,
            reason = "beginGesture"
        )

        PortraitSwipeGestureHandler.reset()
        LandscapeSwipeGestureHandler.reset()

        debugLog(
            "BEGIN " +
                "handler=${identity(handler)} " +
                stateSnapshot()
        )

        return gestureGeneration
    }

    private fun shouldProcessUpdate(
        handler: Any,
        progress: Float
    ): Boolean {
        val now =
            SystemClock.uptimeMillis()

        val progressBits =
            progress.toRawBits()

        return synchronized(lastUpdateTimes) {
            val lastTime =
                lastUpdateTimes[handler] ?: 0L

            val lastBits =
                lastUpdateProgressBits[handler]

            val duplicate =
                lastBits == progressBits &&
                    now - lastTime <=
                    UPDATE_DEDUPLICATION_MS

            if (!duplicate) {
                lastUpdateTimes[handler] = now
                lastUpdateProgressBits[handler] =
                    progressBits
            }

            !duplicate
        }
    }

    private fun handleUpdateSysUiFlags(
        handler: Any,
        progress: Float
    ) {
        /*
         * 二次保护：非当前活动 Handler 不允许修改全局手势和提示框状态。
         */
        if (activeGestureHandler !== handler) {
            return
        }

        updateCallbackCount += 1L

        // 切换 Handler 时重置 zone 状态
        if (lastProgressZoneHandler !== handler) {
            lastProgressZoneHandler = handler
            lastProgressZone = ProgressZone.UNKNOWN
        }

        val now =
            SystemClock.uptimeMillis()

        val zone =
            progressZone(progress)

        if (zone != lastProgressZone) {
            debugLog(
                "PROGRESS_ZONE " +
                    "$lastProgressZone->$zone " +
                    "progress=$progress " +
                    "handler=${identity(handler)} " +
                    "isActiveHandler=true " +
                    stateSnapshot()
            )

            lastProgressZone = zone
        } else if (
            now - lastUpdateHeartbeatTime >=
            UPDATE_HEARTBEAT_INTERVAL_MS
        ) {
            lastUpdateHeartbeatTime = now

            debugLog(
                "UPDATE_HEARTBEAT " +
                    "progress=$progress " +
                    "zone=$zone " +
                    "callbackCount=$updateCallbackCount " +
                    "handler=${identity(handler)} " +
                    "isActiveHandler=true " +
                    stateSnapshot()
            )
        }

        if (!progress.isFinite()) {
            return
        }

        val existingHint =
            hintView

        if (
            existingHint != null &&
            !existingHint.isAttachedToWindow
        ) {
            debugLog(
                "DETACHED_HINT_DETECTED " +
                    "view=${identity(existingHint)}"
            )

            existingHint.animate().cancel()

            if (hintView === existingHint) {
                hintView = null
            }

            dropTargetShowing = false
        }

        val hintShowing =
            hintView?.isAttachedToWindow == true

        if (
            progress <
            PROGRESS_HIDE_THRESHOLD
        ) {
            hintDismissed = false
            gestureArmed = true
            releaseInDropTarget = false

            /*
             * 只处理活动提示框，不重复处理已经消失中的 View。
             */
            if (hintView != null) {
                removeHint(
                    immediate = false,
                    reason = "progressLow"
                )
            }

            return
        }

        if (
            progress <
            PROGRESS_SHOW_THRESHOLD
        ) {
            return
        }

        if (
            hintDismissed ||
            !gestureArmed
        ) {
            debugLog(
                "SHOW_BLOCKED_BY_STATE " +
                    "progress=$progress " +
                    stateSnapshot()
            )
            return
        }

        val task =
            getRunningTask(handler)

        if (task == null) {
            debugLog(
                "SHOW_BLOCKED_NO_TASK " +
                    "handler=${identity(handler)} " +
                    "progress=$progress"
            )

            if (hintShowing) {
                removeHint(
                    immediate = false,
                    reason = "noTask"
                )
            }

            return
        }

        val topComponent =
            runCatching {
                XposedHelpers.callMethod(
                    task,
                    "getTopComponent"
                ).cast<ComponentName?>()
            }.getOrNull()

        val userId =
            runCatching {
                val key =
                    XposedHelpers.getObjectField(
                        task,
                        "key"
                    )

                XposedHelpers.getIntField(
                    key,
                    "userId"
                )
            }.getOrDefault(0)

        val recentsView =
            ObjectUtil.getObjectUntilSuperclass(
                handler,
                "mRecentsView"
            )

        val context =
            recentsView?.let {
                XposedHelpers.callMethod(
                    it,
                    "getContext"
                ) as? Context
            }

        if (
            recentsView == null ||
            context == null
        ) {
            debugLog(
                "SHOW_BLOCKED_NO_RECENTS_CONTEXT " +
                    "handler=${identity(handler)}"
            )
            return
        }

        if (
            isBlacklisted(
                context = context,
                componentName = topComponent,
                userId = userId
            )
        ) {
            if (hintShowing) {
                removeHint(
                    immediate = false,
                    reason = "blacklisted"
                )
            }

            return
        }

        if (
            hintView?.isAttachedToWindow ==
            true
        ) {
            return
        }

        debugLog(
            "SHOW_REQUEST " +
                "handler=${identity(handler)} " +
                "progress=$progress " +
                "component=$topComponent"
        )

        showHint(handler)
    }

    private fun handleGestureEnd(
        handler: Any,
        gestureState: Any,
        homeTarget: Any
    ) {
        // Android 17：抬手 ACTION_UP 不再可靠地送达我们的输入 Hook，
        // releaseInDropTarget（raw-motion 命中测试）恒为 false。
        // 改以“提示框已展示（用户确实上滑到了提交区）”为准，
        // 同时保留 raw-motion 命中作为附加条件但不强制。
        //
        // ⚠️ 这两个标记必须**先读后清零**（下面的赋值就是消费动作）。
        // runOnceAtState 的回调在同一个手势里可能被触发两次，如果只在函数出口
        // 或 finally 里清理，第二次进来仍会读到 true 再发一次广播 —— 用户看到的就是
        // “一次上滑弹出两个小窗”。
        val commitByHint =
            hintCommittedThisGesture &&
                dropTargetShowing

        val shouldLaunch =
            commitByHint ||
                releaseInDropTarget

        hintCommittedThisGesture = false
        releaseInDropTarget = false

        lifecycleLog(
            "GESTURE_END " +
                "shouldLaunch=$shouldLaunch " +
                "commitByHint=$commitByHint " +
                "hintCommittedThisGesture=$hintCommittedThisGesture " +
                "dropTargetShowing=$dropTargetShowing " +
                "releaseInDropTarget=$releaseInDropTarget " +
                "component=${
                    runCatching {
                        getRunningTask(handler)
                            ?.let {
                                XposedHelpers.callMethod(
                                    it,
                                    "getTopComponent"
                                )
                            }
                    }.getOrNull()
                }"
        )

        debugLog(
            "GESTURE_END_ENTER " +
                "handler=${identity(handler)} " +
                "shouldLaunch=$shouldLaunch " +
                stateSnapshot()
        )

        hintDismissed = true
        gestureArmed = false
        releaseInDropTarget = false
        hintCommittedThisGesture = false

        removeHint(
            immediate = true,
            reason = "gestureEnd"
        )

        if (!shouldLaunch) {
            return
        }

        val task =
            getRunningTask(handler)
                ?: return

        val topComponent =
            XposedHelpers.callMethod(
                task,
                "getTopComponent"
            ).cast<ComponentName?>()
                ?: return

        val key =
            XposedHelpers.getObjectField(
                task,
                "key"
            )

        val userId =
            XposedHelpers.getIntField(
                key,
                "userId"
            )

        val taskId =
            resolveTaskId(
                task = task,
                key = key
            )

        val recentsView =
            ObjectUtil.getObjectUntilSuperclass(
                handler,
                "mRecentsView"
            ) ?: return

        val context =
            XposedHelpers.callMethod(
                recentsView,
                "getContext"
            ) as? Context
                ?: return

        if (
            isBlacklisted(
                context = context,
                componentName = topComponent,
                userId = userId
            )
        ) {
            return
        }

        debugLog(
            "LAUNCH_ACCEPTED " +
                "component=$topComponent " +
                "userId=$userId " +
                "taskId=$taskId"
        )

        XposedHelpers.callMethod(
            gestureState,
            "setEndTarget",
            homeTarget
        )

        scheduleMiniLaunch(
            context = context,
            topComponent = topComponent,
            userId = userId,
            taskId = taskId
        )
    }

    private fun showHint(handler: Any) {
        try {
            showHintInternal(handler)
        } catch (throwable: Throwable) {
            recoverFromGestureFailure()

            reportUnavailable(
                operation = "showHint",
                throwable = throwable
            )
        }
    }

    private fun showHintInternal(
        handler: Any
    ) {
        val recentsView =
            ObjectUtil.getObjectUntilSuperclass(
                handler,
                "mRecentsView"
            ).cast<View?>()

        if (recentsView == null) {
            debugLog(
                "SHOW_HINT_ABORT_NO_RECENTS_VIEW " +
                    "handler=${identity(handler)}"
            )
            return
        }

        val parent =
            (recentsView.rootView as? ViewGroup)
                ?: recentsView.parent
                    .cast<ViewGroup?>()

        if (parent == null) {
            debugLog(
                "SHOW_HINT_ABORT_NO_PARENT " +
                    "recents=${identity(recentsView)}"
            )
            return
        }

        val context =
            recentsView.context

        val task =
            getRunningTask(handler)

        val packageName =
            task?.let {
                runCatching {
                    XposedHelpers.callMethod(
                        it,
                        "getTopComponent"
                    ).cast<ComponentName?>()
                        ?.packageName
                }.getOrNull()
            }

        val landscapeListed =
            packageName != null &&
                isInLandscapeAppList(
                    context = context,
                    packageName = packageName
                )

        val requiredMode =
            if (landscapeListed) {
                GestureDisplayMode.LANDSCAPE
            } else {
                GestureDisplayMode.PORTRAIT
            }

        debugLog(
            "SHOW_HINT_MODE " +
                "package=$packageName " +
                "landscapeListed=$landscapeListed " +
                "requiredMode=$requiredMode " +
                "parent=${identity(parent)} " +
                "parentSize=${parent.width}x${parent.height}"
        )

        if (
            gestureDisplayMode !=
                GestureDisplayMode.NONE &&
            gestureDisplayMode !=
                requiredMode
        ) {
            removeHint(
                immediate = true,
                reason = "modeChanged"
            )

            PortraitSwipeGestureHandler.reset()
            LandscapeSwipeGestureHandler.reset()
        }

        val existing =
            hintView

        if (
            existing != null &&
            existing.isAttachedToWindow &&
            existing.parent === parent
        ) {
            activateDisplayMode(
                requiredMode,
                context
            )

            gestureDisplayMode =
                requiredMode

            dropTargetShowing = true

            existing.post {
                if (
                    existing.isAttachedToWindow &&
                    hintView === existing &&
                    dropTargetShowing
                ) {
                    positionCurrentHint(
                        existing,
                        parent,
                        requiredMode
                    )
                }
            }

            return
        }

        if (existing != null) {
            existing.animate().cancel()

            (existing.parent as? ViewGroup)
                ?.removeView(existing)

            if (hintView === existing) {
                hintView = null
            }
        }

        dismissingHintView?.let {
            it.animate().cancel()

            (it.parent as? ViewGroup)
                ?.removeView(it)
        }

        dismissingHintView = null

        val size =
            dp(context, 72f)

        val target =
            MiniWindowDropTargetView(
                context
            ).apply {
                background =
                    GradientDrawable().apply {
                        setColor(Color.WHITE)

                        cornerRadius =
                            dp(
                                context,
                                18f
                            ).toFloat()
                    }

                elevation =
                    dp(
                        context,
                        8f
                    ).toFloat()

                alpha = 0f
                scaleX = 0.92f
                scaleY = 0.92f
            }

        parent.addView(
            target,
            ViewGroup.LayoutParams(
                size,
                size
            )
        )

        activateDisplayMode(
            requiredMode,
            context
        )

        hintView = target
        gestureDisplayMode = requiredMode
        dropTargetShowing = true
        hintCommittedThisGesture = true

        debugLog(
            "SHOW_HINT_ADDED " +
                "target=${identity(target)} " +
                "parent=${identity(parent)} "
        )

        target.post {
            if (
                !target.isAttachedToWindow ||
                hintView !== target ||
                !dropTargetShowing
            ) {
                return@post
            }

            positionCurrentHint(
                target,
                parent,
                requiredMode
            )

            target.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(
                    HINT_ANIM_DURATION
                )
                .start()

            debugLog(
                "SHOW_HINT_POSITIONED " +
                    "mode=$requiredMode " +
                    "xy=(${target.x},${target.y})"
            )

            parent.postOnAnimation {
                if (
                    target.isAttachedToWindow &&
                    hintView === target &&
                    dropTargetShowing
                ) {
                    positionCurrentHint(
                        target,
                        parent,
                        requiredMode
                    )
                }
            }
        }
    }

    private fun activateDisplayMode(
        mode: GestureDisplayMode,
        context: Context
    ) {
        when (mode) {
            GestureDisplayMode.PORTRAIT -> {
                LandscapeSwipeGestureHandler.reset()

                if (
                    !PortraitSwipeGestureHandler
                        .isActive()
                ) {
                    PortraitSwipeGestureHandler
                        .activate()
                }
            }

            GestureDisplayMode.LANDSCAPE -> {
                PortraitSwipeGestureHandler.reset()

                if (
                    !LandscapeSwipeGestureHandler
                        .isActive()
                ) {
                    LandscapeSwipeGestureHandler
                        .activate(context)
                }
            }

            GestureDisplayMode.NONE -> Unit
        }
    }

    private fun positionCurrentHint(
        target: View,
        parent: ViewGroup,
        mode: GestureDisplayMode =
            gestureDisplayMode
    ) {
        val marginRight =
            dp(target.context, 16f)

        val marginTop =
            dp(target.context, 16f)

        when (mode) {
            GestureDisplayMode.PORTRAIT -> {
                PortraitSwipeGestureHandler
                    .positionTopRight(
                        target,
                        parent,
                        marginRight,
                        marginTop
                    )
            }

            GestureDisplayMode.LANDSCAPE -> {
                LandscapeSwipeGestureHandler
                    .positionTopRight(
                        target,
                        parent,
                        marginRight,
                        marginTop
                    )
            }

            GestureDisplayMode.NONE -> Unit
        }
    }

    private fun removeHint(
        immediate: Boolean = false,
        reason: String = "unspecified"
    ) {
        debugLog(
            "REMOVE_HINT " +
                "reason=$reason " +
                "immediate=$immediate"
        )

        val view =
            hintView

        hintView = null
        dropTargetShowing = false

        if (view == null) {
            if (immediate) {
                dismissingHintView?.let {
                    it.animate().cancel()

                    (it.parent as? ViewGroup)
                        ?.removeView(it)
                }

                dismissingHintView = null
            }

            return
        }

        view.animate().cancel()

        if (
            immediate ||
            !view.isAttachedToWindow
        ) {
            (view.parent as? ViewGroup)
                ?.removeView(view)

            if (
                dismissingHintView === view
            ) {
                dismissingHintView = null
            }

            return
        }

        val oldDismissing =
            dismissingHintView

        if (
            oldDismissing != null &&
            oldDismissing !== view
        ) {
            oldDismissing.animate().cancel()

            (oldDismissing.parent as? ViewGroup)
                ?.removeView(oldDismissing)
        }

        dismissingHintView = view

        view.animate()
            .alpha(0f)
            .scaleX(0.92f)
            .scaleY(0.92f)
            .setDuration(HINT_ANIM_DURATION)
            .withEndAction {
                (view.parent as? ViewGroup)
                    ?.removeView(view)

                if (
                    dismissingHintView === view
                ) {
                    dismissingHintView = null
                }
            }
            .start()
    }

    /**
     * 触摸输入入口 Hook。
     *
     * Android 17 起 Launcher3/quickstep 重构了输入分发：
     * TouchInteractionService 不再直接实现 onInputEvent，
     * 该回调被下沉到内部类（如 TouchInteractionService$TISBinder 的
     * InputEventReceiver、TouchInteractionInputConsumer 等）。
     * 因此按固定类名+方法名精确匹配会全部落空，抛
     * "TouchInteractionService.onInputEvent not found" 导致整个手势功能不可用。
     *
     * 这里改为多候选探测：
     *   1. 依次在若干候选类上尝试 hook onInputEvent / onMotionEvent；
     *   2. 全部落空时，退化为 hook 候选类中任意“单参且参数为 InputEvent/MotionEvent”
     *      的方法，兼容被混淆或改名的实现；
     *   3. 只要有一个安装成功就算成功，彻底失败才抛异常。
     */
    private fun hookTouchInteractionService() {
        val candidateClassNames =
            listOf(
                "com.android.quickstep.TouchInteractionService",
                "com.android.quickstep.TouchInteractionService\$TISBinder",
                "com.android.quickstep.inputconsumers.TouchInteractionInputConsumer",
                "com.android.quickstep.TouchInteractionInputConsumer",
                "com.android.quickstep.InputConsumerController",
                "com.android.quickstep.util.MotionPauseDetector"
            )

        val candidateMethodNames =
            listOf("onInputEvent", "onMotionEvent")

        val callback =
            object : XC_MethodHook() {
                override fun beforeHookedMethod(
                    param: MethodHookParam
                ) {
                    try {
                        handleInputEvent(param)
                    } catch (throwable: Throwable) {
                        recoverFromGestureFailure()

                        reportUnavailable(
                            operation = "onInputEvent",
                            throwable = throwable
                        )
                    }
                }
            }

        var installed = 0
        val hookedDescriptions = mutableListOf<String>()

        // 1) 按已知方法名精确匹配
        for (className in candidateClassNames) {
            val clazz = loadOptionalClass(className) ?: continue

            for (methodName in candidateMethodNames) {
                val unhooks =
                    runCatching {
                        HookRegistry.hookAll(
                            clazz,
                            methodName,
                            callback
                        )
                    }.getOrNull() ?: continue

                if (unhooks > 0) {
                    installed += unhooks
                    hookedDescriptions +=
                        "$className.$methodName($unhooks)"
                }
            }
        }

        // 2) 名称匹配全部落空时，按签名兜底（单参 InputEvent/MotionEvent）
        if (installed == 0) {
            for (className in candidateClassNames) {
                val clazz = loadOptionalClass(className) ?: continue

                val methods =
                    runCatching {
                        clazz.declaredMethods.filter { method ->
                            val params = method.parameterTypes
                            params.size == 1 &&
                                (
                                    InputEvent::class.java
                                        .isAssignableFrom(params[0]) ||
                                        MotionEvent::class.java
                                            .isAssignableFrom(params[0])
                                    )
                        }
                    }.getOrNull().orEmpty()

                for (method in methods) {
                    runCatching {
                        XposedBridge.hookMethod(method, callback)
                    }.onSuccess {
                        installed += 1
                        hookedDescriptions +=
                            "$className.${method.name}(signature)"
                    }
                }
            }
        }

        if (installed == 0) {
            // 彻底落空时，把候选类里所有方法名打出来，便于定位 Android 17 的真实入口
            val dump = StringBuilder()
            for (className in candidateClassNames) {
                val clazz = loadOptionalClass(className)
                if (clazz == null) {
                    dump.append("[$className => CLASS_NOT_FOUND] ")
                    continue
                }
                val names = runCatching {
                    clazz.declaredMethods.joinToString(",") { m ->
                        m.name + "(" +
                            m.parameterTypes.joinToString("|") { it.simpleName } +
                            ")"
                    }
                }.getOrDefault("<dump failed>")
                dump.append("[$className => $names] ")
            }
            lifecycleLog("INPUT_HOOK_DUMP $dump")

            throw IllegalStateException(
                "No touch input entry point was hooked " +
                    "(tried onInputEvent/onMotionEvent and " +
                    "single InputEvent/MotionEvent parameter methods)"
            )
        }

        lifecycleLog(
            "INPUT_HOOKS_INSTALLED count=$installed " +
                hookedDescriptions.joinToString(",")
        )
    }

    private fun handleInputEvent(
        param: XC_MethodHook.MethodHookParam
    ) {
        // Android 17 起入口方法参数可能声明为 InputEvent，
        // 也可能出现在非首位参数上，这里逐个参数寻找 MotionEvent。
        val event =
            param.args
                ?.firstOrNull { it is MotionEvent }
                as? MotionEvent
                ?: return

        val action =
            event.actionMasked

        if (
            action != MotionEvent.ACTION_DOWN &&
            action != MotionEvent.ACTION_MOVE &&
            action != MotionEvent.ACTION_UP &&
            action != MotionEvent.ACTION_CANCEL
        ) {
            return
        }

        // thisObject 不再保证是 TouchInteractionService 本身
        // （可能是 TISBinder / InputConsumer 等内部对象），
        // 因此从多个来源解析可用的 Context。
        val ctx = cachedSwipeContext
            ?: resolveContext(param.thisObject)

        if (ctx != null) {
            cachedSwipeContext = ctx
            updateSourceDisplayState(ctx)
        }

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                inputDownCount += 1L
                releaseInDropTarget = false

                /*
                 * ACTION_DOWN 表示开始了一条新的输入序列。
                 *
                 * 某些情况下，上一轮手势结束后，活动 Handler 不会再报告
                 * progress < PROGRESS_HIDE_THRESHOLD，导致状态一直停留在：
                 *
                 * gestureArmed = false
                 * hintDismissed = true
                 *
                 * 当前没有活动提示框时，可以安全解除上一轮锁定。
                 */
                if (
                    hintView == null &&
                    dismissingHintView == null &&
                    !dropTargetShowing
                ) {
                    hintDismissed = false
                    gestureArmed = true

                    // 清理过期命中矩形
                    PortraitSwipeGestureHandler.reset()
                    LandscapeSwipeGestureHandler.reset()
                    gestureDisplayMode = GestureDisplayMode.NONE
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val target =
                    hintView ?: return

                if (
                    !dropTargetShowing ||
                    !target.isAttachedToWindow
                ) {
                    return
                }

                when (gestureDisplayMode) {
                    GestureDisplayMode.PORTRAIT -> {
                        PortraitSwipeGestureHandler
                            .updateHitRect(target)
                    }

                    GestureDisplayMode.LANDSCAPE -> {
                        LandscapeSwipeGestureHandler
                            .updateHitRects(target)
                    }

                    GestureDisplayMode.NONE -> Unit
                }
            }

            MotionEvent.ACTION_UP -> {
                val target = hintView

                val inside =
                    if (
                        dropTargetShowing &&
                        target != null &&
                        target.isAttachedToWindow
                    ) {
                        when (gestureDisplayMode) {
                            GestureDisplayMode.PORTRAIT -> {
                                PortraitSwipeGestureHandler.updateHitRect(target)
                                PortraitSwipeGestureHandler.isPointerInside(event)
                            }

                            GestureDisplayMode.LANDSCAPE -> {
                                LandscapeSwipeGestureHandler.updateHitRects(target)
                                LandscapeSwipeGestureHandler.isPointerInside(event)
                            }

                            GestureDisplayMode.NONE -> false
                        }
                    } else {
                        false
                    }

                releaseInDropTarget = inside

                // 关键诊断：ACTION_UP 时抬手点与命中区域的关系。
                // 若 dropTargetShowing=false / target=null，说明提示框已被提前移除；
                // 若 inside=false 但坐标其实在 hitRect 内，说明坐标系不一致。
                lifecycleLog(
                    "INPUT_UP inside=$inside " +
                        "dropTargetShowing=$dropTargetShowing " +
                        "target=${identity(target)} " +
                        "targetAttached=${target?.isAttachedToWindow} " +
                        "mode=$gestureDisplayMode " +
                        "raw=(${event.rawX},${event.rawY}) " +
                        "local=(${event.x},${event.y}) " +
                        "pointerCount=${event.pointerCount} " +
                        "portraitHit=${PortraitSwipeGestureHandler.debugState()} " +
                        "downCount=$inputDownCount"
                )
            }

            MotionEvent.ACTION_CANCEL -> {
                releaseInDropTarget = false
            }
        }
    }

    /**
     * 从 Hook 到的对象上尽力解析出一个可用 Context。
     *
     * Android 17 的输入回调可能落在 TISBinder / InputConsumer 等内部对象上，
     * 它们本身不是 Context，需要顺着外层引用或已知字段找。
     */
    private fun resolveContext(target: Any?): Context? {
        if (target == null) {
            return null
        }

        if (target is Context) {
            return target
        }

        // 内部类持有的外部实例：this$0
        runCatching {
            val outer =
                XposedHelpers.getObjectField(target, "this$0")

            if (outer is Context) {
                return outer
            }
        }

        // 常见的 Context 字段名
        for (fieldName in listOf("mContext", "context", "mService")) {
            runCatching {
                val value =
                    XposedHelpers.getObjectField(target, fieldName)

                if (value is Context) {
                    return value
                }
            }
        }

        // 遍历声明字段，找第一个 Context
        runCatching {
            for (field in target.javaClass.declaredFields) {
                if (!Context::class.java.isAssignableFrom(field.type)) {
                    continue
                }

                field.isAccessible = true

                val value = field.get(target)

                if (value is Context) {
                    return value
                }
            }
        }

        return null
    }

    private fun recoverFromGestureFailure() {
        debugLog(
            "RECOVER_ENTER " +
                stateSnapshot()
        )

        gestureGeneration += 1L
        activeGestureHandler = null

        val activeView =
            hintView

        val dismissingView =
            dismissingHintView

        hintView = null
        dismissingHintView = null

        try {
            activeView?.animate()?.cancel()

            (activeView?.parent as? ViewGroup)
                ?.removeView(activeView)
        } catch (_: Throwable) {
        }

        try {
            if (
                dismissingView != null &&
                dismissingView !== activeView
            ) {
                dismissingView.animate().cancel()

                (dismissingView.parent as? ViewGroup)
                    ?.removeView(dismissingView)
            }
        } catch (_: Throwable) {
        }

        dropTargetShowing = false
        releaseInDropTarget = false
        hintCommittedThisGesture = false
        hintDismissed = false
        gestureArmed = true

        gestureDisplayMode =
            GestureDisplayMode.NONE

        lastProgressZoneHandler = null
        lastProgressZone = ProgressZone.UNKNOWN

        PortraitSwipeGestureHandler.reset()
        LandscapeSwipeGestureHandler.reset()

        debugLog(
            "RECOVER_FINISHED " +
                stateSnapshot()
        )
    }

    @Suppress("DEPRECATION")
    private fun updateSourceDisplayState(
        context: Context
    ) {
        try {
            val windowManager =
                context.getSystemService(
                    Context.WINDOW_SERVICE
                ) as WindowManager

            val metrics =
                android.util.DisplayMetrics()

            windowManager.defaultDisplay
                .getRealMetrics(metrics)

            lastScreenWidth =
                metrics.widthPixels

            lastScreenHeight =
                metrics.heightPixels

            lastRotation =
                windowManager.defaultDisplay.rotation
        } catch (_: Throwable) {
        }
    }

    private fun readProgress(
        handler: Any
    ): Float {
        val currentShift =
            ObjectUtil.getObjectUntilSuperclass(
                handler,
                "mCurrentShift"
            ) ?: return 0f

        return ObjectUtil.getObject(
            currentShift,
            "value"
        ).cast()
    }

    private fun getRunningTask(
        handler: Any
    ): Any? {
        val recentsView =
            ObjectUtil.getObjectUntilSuperclass(
                handler,
                "mRecentsView"
            ) ?: return null

        val taskView =
            XposedHelpers.callMethod(
                recentsView,
                "getRunningTaskView"
            ) ?: return null

        return if (
            Build.VERSION.SDK_INT >
            Build.VERSION_CODES.TIRAMISU
        ) {
            val containers =
                XposedHelpers.callMethod(
                    taskView,
                    "getTaskContainers"
                ) ?: return null

            val container =
                XposedHelpers.callMethod(
                    containers,
                    "get",
                    0
                ) ?: return null

            XposedHelpers.callMethod(
                container,
                "getTask"
            )
        } else {
            XposedHelpers.callMethod(
                taskView,
                "getTask"
            )
        }
    }

    private fun resolveTaskId(
        task: Any,
        key: Any
    ): Int {
        val fromTask =
            try {
                XposedHelpers.getIntField(
                    task,
                    "taskId"
                )
            } catch (_: Throwable) {
                -1
            }

        if (fromTask > 0) {
            return fromTask
        }

        val fromKeyId =
            try {
                XposedHelpers.getIntField(
                    key,
                    "id"
                )
            } catch (_: Throwable) {
                -1
            }

        if (fromKeyId > 0) {
            return fromKeyId
        }

        return try {
            XposedHelpers.getIntField(
                key,
                "taskId"
            )
        } catch (_: Throwable) {
            -1
        }
    }

    private val blacklistCache =
        ConcurrentHashMap<String, Boolean>()

    @Volatile
    private var blacklistVersion: Long? = null

    /** 上次向 Provider 校验版本号的时间；窗口内不再发 Binder，见 [isBlacklisted]。 */
    @Volatile
    private var blacklistCheckedAt = 0L

    private fun isBlacklisted(
        context: Context,
        componentName: ComponentName?,
        userId: Int
    ): Boolean {
        val packageName =
            componentName?.packageName
                ?: return false

        val cacheKey = "$packageName|$userId"

        return try {
            /*
             * 手势热路径（每次缩放/滑动回调都会走到这里）：
             * 只要缓存有效且距离上次版本校验还在 [CACHE_REVALIDATE_MS] 内，
             * 就直接返回本地结果，一次 Binder 都不发。
             * 超过窗口后才去取一次版本号，版本变了就整体失效。
             *
             * 代价是名单修改最迟 [CACHE_REVALIDATE_MS] 后才对手势生效，
             * 对“把某应用加入黑名单”这种低频操作可以接受；
             * 设置页写入后还会通过 [invalidateListCaches] 主动置零窗口立即生效。
             */
            val now = SystemClock.elapsedRealtime()
            if (blacklistVersion != null && now - blacklistCheckedAt < CACHE_REVALIDATE_MS) {
                blacklistCache[cacheKey]?.let { return it }
            }

            val version = providerVersion(
                context,
                BlacklistProvider.AUTHORITY,
                BlacklistProvider.METHOD_GET_VERSION
            )
            if (version != null && version == blacklistVersion) {
                blacklistCheckedAt = now
                blacklistCache[cacheKey]?.let { return it }
            } else if (version != null) {
                blacklistVersion = version
                blacklistCheckedAt = now
                blacklistCache.clear()
            }

            context.contentResolver.call(
                Uri.parse(
                    "content://${BlacklistProvider.AUTHORITY}"
                ),
                BlacklistProvider.METHOD_IS_BLACKLISTED,
                null,
                Bundle().apply {
                    putString(
                        BlacklistProvider.EXTRA_PACKAGE_NAME,
                        packageName
                    )

                    putInt(
                        BlacklistProvider.EXTRA_USER_ID,
                        userId
                    )
                }
            )?.getBoolean(
                BlacklistProvider.EXTRA_RESULT,
                false
            )?.also { blacklistCache.putBounded(cacheKey, it) } ?: false
        } catch (_: Throwable) {
            /*
             * 第三轮审查把 isBlacklisted 改成“查询失败 → fail-closed”，
             * 但 provider 查询失败时返回的是带 EXTRA_DENIED 的空 Bundle，
             * getBoolean 取不到值会落到默认 false —— 于是“拒绝”被当成了“放行”。
             *
             * 手势这边必须 fail-open：
             *  - Provider 不可用（Z-Flow 进程被杀、正在升级、LSPosed 作用域刚注入）
             *    是常态，若按“黑名单”处理，用户会周期性遇到“手势完全没反应”，
             *    而且没有任何提示，属于把安全策略的代价转嫁给了主功能。
             *  - 真正的黑名单校验还有一道在 App 进程内
             *    （FreeformRequestHandler / FreeformService 直接查 Room，失败才拒绝），
             *    所以这里放宽不会让黑名单应用被打开。
             *
             * 结论：hook 侧只负责“尽量过滤”，最终裁决留给 App 进程。
             */
            XLog.d("[ZFlowSwipe] blacklist query failed, fallback=${
                blacklistCache[cacheKey] ?: false
            } pkg=$packageName")
            blacklistCache[cacheKey] ?: false
        }
    }

    private fun isInLandscapeAppList(
        context: Context,
        packageName: String
    ): Boolean {
        return try {
            /*
             * 与黑名单同一套策略：时间窗口内的缓存直接命中，不发 Binder。
             * 窗口外的首次调用才校验一次版本号。
             */
            val now = SystemClock.elapsedRealtime()
            if (landscapeListVersion != null && now - landscapeCheckedAt < CACHE_REVALIDATE_MS) {
                landscapeAppCache[packageName]?.let { return it }
            }

            val version = providerVersion(
                context,
                LandscapeAppsProvider.AUTHORITY,
                LandscapeAppsProvider.METHOD_GET_VERSION
            )
            if (version != null && version == landscapeListVersion) {
                landscapeCheckedAt = now
                landscapeAppCache[packageName]?.let { return it }
            } else if (version != null) {
                landscapeListVersion = version
                landscapeCheckedAt = now
                landscapeAppCache.clear()
            }

            val result = context.contentResolver.call(
                Uri.parse("content://${LandscapeAppsProvider.AUTHORITY}"),
                LandscapeAppsProvider.METHOD_IS_LANDSCAPE,
                null,
                Bundle().apply {
                    putString(LandscapeAppsProvider.EXTRA_PACKAGE_NAME, packageName)
                }
            )?.getBoolean(LandscapeAppsProvider.EXTRA_RESULT, false) ?: false

            landscapeAppCache.putBounded(packageName, result)
            result
        } catch (_: Throwable) {
            // Provider 暂不可用时退回缓存值，缓存也为空则按“非横屏”处理
            landscapeAppCache[packageName] ?: false
        }
    }

    /**
     * 主动让两份名单缓存立即失效。
     *
     * 设置页写入后会经由 Provider 自增版本号；同进程内（设置页与 hook 同属本应用
     * 进程时）还可以直接调用这里把时间窗口清零，下一次判断立刻重新拉取，
     * 不必等 [CACHE_REVALIDATE_MS] 自然过期。
     */
    fun invalidateListCaches() {
        blacklistCheckedAt = 0L
        landscapeCheckedAt = 0L
    }

    /**
     * 读取 Provider 的名单版本号。
     *
     * 注意：`call()` 本身要跨进程，版本号的作用是省掉“取具体名单”的额外往返，
     * 不是把热路径变成纯本地。命中缓存时总开销从 2 次 Binder 降到 1 次轻量调用。
     * Provider 不支持该方法（返回 null）时返回 null，调用方退化为“每次都查”。
     */
    private fun providerVersion(
        context: Context,
        authority: String,
        method: String
    ): Long? = try {
        context.contentResolver.call(
            Uri.parse("content://$authority"),
            method,
            null,
            null
        )?.getLong("version", 0L)
    } catch (_: Throwable) {
        null
    }

    private fun scheduleMiniLaunch(
        context: Context,
        topComponent: ComponentName,
        userId: Int,
        taskId: Int
    ) {
        /*
         * 同一个手势的重复提交闸门。
         *
         * 为什么不能只靠 handleGestureEnd 里的标记：Launcher3 的
         * runOnceAtState(STATE_END_TARGET_SET) 回调在同一次上滑中会被
         * onSwipeUpAnimationComplete 与状态机各触发一次，两次之间
         * beginGesture 可能已经把 hintCommittedThisGesture 重新置为 true，
         * 于是“消费式读取”也拦不住第二次。
         *
         * 这里用与 taskId 无关的时间窗口做最后一道闸：上一个窗口内已经排过启动，
         * 本次直接丢弃（并清掉待发的回调，避免它延迟到窗口外再补一枪）。
         */
        val now = SystemClock.uptimeMillis()
        val last = lastMiniLaunchAt
        if (last != 0L && now - last < MINI_LAUNCH_DEBOUNCE_MS) {
            pendingMiniLaunch?.let { mainHandler.removeCallbacks(it) }
            pendingMiniLaunch = null
            XLog.w(
                "[ZFlowSwipe] duplicate mini launch suppressed " +
                    "(${now - last}ms < ${MINI_LAUNCH_DEBOUNCE_MS}ms) " +
                    "component=$topComponent taskId=$taskId"
            )
            return
        }
        lastMiniLaunchAt = now

        pendingMiniLaunch?.let {
            mainHandler.removeCallbacks(it)
        }

        pendingMiniLaunch =
            Runnable {
                try {
                    // 统一出口构造广播：必须带 StartFreeformReceiver.EXTRA_SENDER，
                    // 否则接收端会按“伪造请求”丢弃。
                    val intent = StartFreeformReceiver.newIntent(
                        context = context,
                        packageName = topComponent.packageName,
                        activityName = topComponent.className,
                        userId = userId,
                        miniMode = true,
                        taskId = taskId,
                        source = StartFreeformReceiver.SOURCE_RECENT_SWIPE,
                        sourceRotation = lastRotation,
                        sourceScreenWidth = lastScreenWidth,
                        sourceScreenHeight = lastScreenHeight
                    )

                    context.sendBroadcast(intent)

                    XLog.d(
                        "[ZFlowSwipe] broadcast sent component=$topComponent userId=$userId taskId=$taskId"
                    )
                } catch (
                    throwable: Throwable
                ) {
                    reportUnavailable(
                        operation =
                            "sendMiniWindowBroadcast",
                        throwable =
                            throwable
                    )
                } finally {
                    pendingMiniLaunch = null
                }
            }.also {
                mainHandler.postDelayed(
                    it,
                    MINI_LAUNCH_DELAY_MS
                )
            }
    }

    private fun dp(
        context: Context,
        value: Float
    ): Int {
        return (
            value *
                context.resources
                    .displayMetrics
                    .density +
                0.5f
            ).toInt()
    }

    private class MiniWindowDropTargetView(
        context: Context
    ) : View(context) {

        private val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color =
                    Color.rgb(80, 80, 80)

                style =
                    Paint.Style.STROKE

                strokeCap =
                    Paint.Cap.ROUND

                strokeJoin =
                    Paint.Join.ROUND
            }

        private val rect = RectF()

        init {
            setWillNotDraw(false)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val density =
                resources.displayMetrics.density

            paint.strokeWidth =
                3f * density

            val iconSize =
                26f * density

            val radius =
                2.5f * density

            val centerX =
                width / 2f

            val centerY =
                height / 2f

            rect.set(
                centerX - iconSize * 0.15f,
                centerY - iconSize * 0.65f,
                centerX + iconSize * 0.55f,
                centerY + iconSize * 0.05f
            )

            canvas.drawRoundRect(
                rect,
                radius,
                radius,
                paint
            )

            rect.set(
                centerX - iconSize * 0.55f,
                centerY - iconSize * 0.15f,
                centerX + iconSize * 0.15f,
                centerY + iconSize * 0.55f
            )

            canvas.drawRoundRect(
                rect,
                radius,
                radius,
                paint
            )
        }
    }
}
