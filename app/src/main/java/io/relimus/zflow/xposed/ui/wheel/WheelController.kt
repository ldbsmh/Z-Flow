/*
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Wheel launcher controller for Z-Flow.
 * Trigger geometry inspired by GestureEVO (https://github.com/LumiaGG/GestureEVO);
 * the hosting/launch path is Z-Flow specific.
 * Copyright (C) LumiaGG and GestureEVO contributors
 * Modifications for Z-Flow, Copyright (C) ldbsmh
 */

package io.relimus.zflow.xposed.ui.wheel

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.UserManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import io.relimus.zflow.room.DatabaseRepository
import io.relimus.zflow.service.FreeformManagerProxy
import io.relimus.zflow.service.FreeformRequestHandler
import io.relimus.zflow.xposed.hook.utils.XLog
import io.relimus.zflow.xposed.utils.Instances
import io.relimus.zflow.systemapi.UserHandle as ZUserHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 轮盘启动器：在角落滑入时弹出，选中的应用直接以小窗打开。
 *
 * 分工：
 *  - 触发判定在 [CornerTrigger]（由 HookSwipeGesture 的输入钩子喂坐标）；
 *  - 本类负责：取应用列表（过滤黑名单）、加载图标、创建 [WheelView]、
 *    用独立 overlay 窗口承载（需要接管触摸），并把选中项交给
 *    [FreeformRequestHandler.launchAsync] 开小窗。
 */
object WheelController {

    private const val TAG = "ZFlowWheel"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 轮盘视图的父容器；overlay 窗口本身常驻，不随轮盘收起销毁。 */
    private var host: FrameLayout? = null
    private var windowManager: WindowManager? = null
    private var loadingJob: Job? = null
    private var cachedEntries: List<WheelAppEntry> = emptyList()

    /** 最近一次弹出的形态，用于重新弹出时立刻出图（缓存列表也走同一条路径）。 */
    private var lastShape: WheelShape? = null

    /** 常驻 overlay 容器。窗口建好后不再移除，只增删内部视图。 */
    private var overlay: FrameLayout? = null

    /**
     * 手指从角落滑入时，屏幕那一角是空的：手指底下没有轮盘中心、也没有任何项。
     *
     * 轮盘中心按形态贴在被触发的那个角上（见 [WheelView.centerX]/[centerY]），
     * 而手指是从这个角**向内**开始滑的 —— 于是弹出瞬间热点精确地落在手指的
     * 出发点背后，手指前方什么都没有。用户只能瞎拖，多半停在任何项之外，
     * 抬手时命中 -1 → onCancel → 收起来。这就是「轮盘弹了但选不中」的全部原因。
     *
     * 修法：不挪轮盘（挪了就不再是「角落唤起」），而是给手指一个**起点项**。
     * 弹出时按滑动方向预选最靠近来向的第一个项（实际上就是「角上那个」），
     * 用户直接抬手即可打开它，想换目标再往里拖。这也让浮层自己接管手指：
     * 无论系统把它重定向到哪个窗口，未选中的抬手都按当前选中的项执行。
     */
    private var preSelectedIndex = -1

    /** 轮盘当前是否可见。 */
    val isShowing: Boolean
        get() = host != null

    /**
     * 弹出轮盘。
     *
     * @param shape 由触发角落决定的轮盘形态（四分之一盘 / 半盘）
     */
    fun show(context: Context, shape: WheelShape) {
        if (isShowing) return
        lastShape = shape

        val container = ensureOverlay(context) ?: return
        showWheel(context, container, shape)
    }

    /**
     * 取（必要时创建）常驻 overlay 容器。
     *
     * 窗口只加一次：TYPE_APPLICATION_OVERLAY 在部分 ROM 上 addView 要几百毫秒，
     * 而且每次 add/remove 都会让 launcher 的输入分发重新计算一遍窗口，
     * 连发几次就开始出现「滑动没反应」。常驻后每次弹出只是 addView 一个子 View。
     */
    private fun ensureOverlay(context: Context): FrameLayout? {
        overlay?.let {
            XLog.ls("diag:wheel reuse overlay", tag = "ZFlowDiag")
            return it
        }

        val wm = resolveWindowManager(context)
        if (wm == null) {
            XLog.ls("diag:wheel FAILED no usable WindowManager", tag = "ZFlowDiag")
            XLog.w("$TAG: no usable WindowManager, wheel aborted")
            return null
        }

        val container = FrameLayout(context)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            // 与 FreeformWindow 等现有浮层保持同一窗口类型。
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 需要接收触摸（滑动选择），但不抢焦点；
            // 必须能接返回键，否则轮盘弹出后除了选一个应用没有别的退出方式。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            wm.addView(container, params)
            XLog.ls(
                "diag:wheel addView ok type=${params.type} cached=${cachedEntries.size}",
                tag = "ZFlowDiag"
            )
        } catch (t: Throwable) {
            XLog.ls("diag:wheel addView FAILED ${t.javaClass.name}: ${t.message}", tag = "ZFlowDiag", e = t)
            XLog.e("$TAG: addView failed", t)
            return
        }

        // 全屏容器只用来承载轮盘；点在轮盘之外应当收起，而不是把触摸吞掉。
        container.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                XLog.ls("diag:wheel dismiss outside", tag = "ZFlowDiag")
                hide()
                true
            } else {
                false
            }
        }

        overlay = container
        windowManager = wm
        return container
    }

    /** 在常驻容器里挂上轮盘视图；列表已有缓存时同步出图。 */
    private fun showWheel(context: Context, container: FrameLayout, shape: WheelShape) {
        host = container

        if (cachedEntries.isNotEmpty()) {
            attachWheel(context, container, shape, cachedEntries)
        } else {
            loadingJob?.cancel()
            loadingJob = scope.launch {
                val entries = loadEntries(context)
                cachedEntries = entries
                if (host === container) {
                    val current = lastShape ?: shape
                    attachWheel(context, container, current, entries)
                }
            }
        }

        // 补链放在出图之后：轮盘已经可见，用户不会等待 binder 握手。
        scope.launch(Dispatchers.IO) { FreeformManagerProxy.requestRelink() }
    }

    /**
     * 取一个可用的 WindowManager。
     *
     * 轮盘是从 launcher 进程里的输入钩子唤起的，**不在 system_server**。
     * 之前直接读 [Instances.windowManager]，那是 system_server 侧的
     * lateinit，只在 ActivityManagerService.systemReady 之后才被赋值，
     * 在 launcher 进程里永远没有被初始化 —— 于是每次弹轮盘都抛
     * `lateinit property windowManager has not been initialized`。
     *
     * 现在先按 context 自己取，取不到再退回模块全局实例。
     * 窗口类型同样按进程走：非 system_server 进程不能用 SYSTEM_ALERT_WINDOW
     * 之外的类型，这里统一用 TYPE_APPLICATION_OVERLAY（与 FreeformWindow 一致）。
     */
    private fun resolveWindowManager(context: Context): WindowManager? {
        runCatching {
            context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        }.getOrNull()?.let { return it }

        return runCatching { Instances.windowManager }.getOrNull()
    }

    private fun attachWheel(
        context: Context,
        container: FrameLayout,
        shape: WheelShape,
        entries: List<WheelAppEntry>
    ) {
        if (entries.isEmpty()) {
            XLog.ls("diag:wheel entries empty, aborted", tag = "ZFlowDiag")
            XLog.w("$TAG: no launchable apps, wheel aborted")
            hide()
            return
        }

        val view = WheelView(
            context = context,
            entries = entries,
            shape = shape,
            onPick = { entry ->
                val ctx = container.context
                lastContext = ctx
                hide()
                openInFreeform(ctx, entry)
            },
            onCancel = { hide() }
        )

        container.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        container.post {
            view.layoutWheel(container.width, container.height)
            preSelectedIndex = view.preselectNearestToHint()
            view.setSelectedIndex(preSelectedIndex)
            view.invalidate()
            XLog.ls(
                "diag:wheel shown entries=${entries.size} pre=$preSelectedIndex " +
                    "size=${container.width}x${container.height}",
                tag = "ZFlowDiag"
            )
        }
    }

    /** 打开小窗，复用已验证的异步入口（避免主线程查库）。 */
    private fun openInFreeform(context: Context, entry: WheelAppEntry) {
        FreeformRequestHandler.launchAsync(
            context = context,
            packageName = entry.packageName,
            activityName = entry.activityName,
            userId = entry.userId
        ) { ok ->
            XLog.ls(
                "diag:wheelLaunch pkg=${entry.packageName} ok=$ok",
                tag = "ZFlowDiag"
            )
        }
    }

    private var lastContext: Context? = null

    private suspend fun loadEntries(context: Context): List<WheelAppEntry> =
        withContext(Dispatchers.IO) {
            val result = ArrayList<WheelAppEntry>()
            try {
                val repository = DatabaseRepository(context)
                val blacklisted = repository.getAllBlacklistByFlow()
                    .first()
                    .orEmpty()
                    .map { "${it.packageName}|${it.userId}" }
                    .toHashSet()

                val launcherApps =
                    context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
                val userManager =
                    context.getSystemService(Context.USER_SERVICE) as UserManager

                userManager.userProfiles.forEach { userHandle ->
                    launcherApps.getActivityList(null, userHandle).forEach { app ->
                        val userId = ZUserHandle.getUserId(app.user, app.applicationInfo.uid)
                        val pkg = app.applicationInfo.packageName
                        if ("$pkg|$userId" in blacklisted) return@forEach

                        val icon: Drawable? = runCatching {
                            app.getBadgedIcon(0)
                        }.getOrNull()

                        result.add(
                            WheelAppEntry(
                                packageName = pkg,
                                activityName = app.componentName.className,
                                label = app.label?.toString() ?: pkg,
                                userId = userId,
                                icon = icon
                            )
                        )
                    }
                }
                result.sortBy { it.label }
            } catch (t: Throwable) {
                XLog.e("$TAG: load entries failed", t)
            }
            result
        }

    /** 隐藏并释放轮盘。 */
    fun hide() {
        loadingJob?.cancel()
        loadingJob = null
        val container = host ?: return
        host = null
        preSelectedIndex = -1
        lastContext = container.context
        // 只摘轮盘视图，overlay 窗口留着，下次弹出不用重新 addView。
        runCatching { container.removeAllViews() }
    }

    /** 窗口彻底销毁时用；进程活着就不再建窗口，所以只在这里 removeView。 */
    fun destroyOverlay() {
        hide()
        val container = overlay ?: return
        overlay = null
        runCatching { windowManager?.removeViewImmediate(container) }
        windowManager = null
    }

    /** 宿主销毁时清空缓存。 */
    fun clearCache() {
        cachedEntries = emptyList()
        destroyOverlay()
    }
}
