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
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import io.relimus.zflow.room.DatabaseRepository
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

    private var host: FrameLayout? = null
    private var windowManager: WindowManager? = null
    private var loadingJob: Job? = null
    private var cachedEntries: List<WheelAppEntry> = emptyList()

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

        // 运行在 system_server 进程内，直接用模块缓存的系统 WindowManager；
        // 用 context.getSystemService 拿到的可能不是可用的系统实例。
        val wm = Instances.windowManager

        val container = FrameLayout(context)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            // 与 FreeformWindow 等现有浮层保持同一窗口类型。
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 需要接收触摸（滑动选择），但不抢焦点。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            wm.addView(container, params)
        } catch (t: Throwable) {
            XLog.e("$TAG: addView failed", t)
            return
        }

        host = container
        windowManager = wm

        if (cachedEntries.isNotEmpty()) {
            attachWheel(context, container, shape, cachedEntries)
            return
        }

        loadingJob?.cancel()
        loadingJob = scope.launch {
            val entries = loadEntries(context)
            cachedEntries = entries
            val current = host
            if (current === container) {
                attachWheel(context, current, shape, entries)
            }
        }
    }

    private fun attachWheel(
        context: Context,
        container: FrameLayout,
        shape: WheelShape,
        entries: List<WheelAppEntry>
    ) {
        if (entries.isEmpty()) {
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
            view.invalidate()
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
        lastContext = container.context
        runCatching {
            windowManager?.removeViewImmediate(container)
        }
        windowManager = null
    }

    /** 宿主销毁时清空缓存。 */
    fun clearCache() {
        cachedEntries = emptyList()
        hide()
    }
}
