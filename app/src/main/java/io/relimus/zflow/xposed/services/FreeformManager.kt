package io.relimus.zflow.xposed.services

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.TaskStackListener
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceControl
import de.robv.android.xposed.XposedHelpers
import io.github.kyuubiran.ezxhelper.core.misc.paramTypes
import io.github.kyuubiran.ezxhelper.core.misc.params
import io.github.kyuubiran.ezxhelper.core.util.ClassUtil
import io.github.kyuubiran.ezxhelper.core.util.ObjectUtil
import io.relimus.zflow.BuildConfig
import io.relimus.zflow.bean.MotionEventBean
import io.relimus.zflow.utils.cast
import io.relimus.zflow.xposed.IFreeformManager
import io.relimus.zflow.xposed.hook.HookReload
import io.relimus.zflow.xposed.hook.utils.XLog
import io.relimus.zflow.xposed.ui.config.FreeformConfig
import io.relimus.zflow.xposed.ui.window.FreeformWindow
import io.relimus.zflow.xposed.utils.Instances

@SuppressLint("StaticFieldLeak")
object FreeformManager : IFreeformManager.Stub() {
    private const val TAG = "FreeformManager"

    private val windowList = mutableListOf<FreeformWindow>()
    private val displayIdList = mutableListOf<Int>()
    @Volatile
    private var isReady = false

    // displayId -> taskId list
    private val displayTaskMap = mutableMapOf<Int, MutableList<Int>>()

    // packageName -> latest created/alive taskId
    private val packageLatestTaskMap = mutableMapOf<String, Int>()

    // taskId -> packageName
    private val taskPackageMap = mutableMapOf<Int, String>()

    // taskId -> uptimeMillis 最近一次模块主动把任务迁回默认屏的时刻（用户主动展开）。
    // 用于在 onTaskDisplayChanged 弹回默认屏时区分「主动展开」与「系统意外弹回」。
    private val expectedMoveToDefaultAt = mutableMapOf<Int, Long>()
    // taskId -> uptimeMillis 最近一次「弹回拦截」的时刻。防系统反复弹回造成拦截-移回死循环。
    private val lastBounceDefendAt = mutableMapOf<Int, Long>()

    lateinit var activityManagerService: Any
        internal set

    private val taskStackListener = object : TaskStackListener() {
        override fun onTaskCreated(taskId: Int, componentName: ComponentName?) {
            // 所有回调统一切换到主线程，保证线程安全
            runOnMainThread {
                val packageName = componentName?.packageName
                XLog.d("$TAG: onTaskCreated taskId=$taskId component=$componentName")
                if (!packageName.isNullOrEmpty()) {
                    taskPackageMap[taskId] = packageName
                    packageLatestTaskMap[packageName] = taskId

                    val managedDisplayId = displayTaskMap.entries
                        .find { it.value.contains(taskId) }
                        ?.key

                    if (managedDisplayId != null) {
                        bindTaskToWindowIfMatch(taskId, managedDisplayId)
                    } else {
                        // QQ/HMS 等通知会先结束原 Splash/Router Task，再在默认屏
                        // 新建详情 Task。通知过渡期内，把同包名的新 Task 迁回原小窗。
                        val notificationWindow = windowList.firstOrNull {
                            !it.isDestroyed &&
                                it.componentName?.packageName == packageName &&
                                it.isNotificationTransitionActive()
                        }
                        if (notificationWindow != null) {
                            val targetDisplay = notificationWindow.displayId
                            XLog.d(
                                "$TAG: NOTIFY_NEW_TASK task=$taskId pkg=$packageName " +
                                    "display=${getTaskDisplayId(taskId)} target=$targetDisplay"
                            )
                            notificationWindow.bindTask(taskId)
                            if (targetDisplay >= 0 &&
                                getTaskDisplayId(taskId) != targetDisplay
                            ) {
                                moveTaskToDisplaySafely(taskId, targetDisplay)
                            }
                        }
                    }
                }
            }
        }

        override fun onTaskRemovalStarted(taskInfo: ActivityManager.RunningTaskInfo) {
            runOnMainThread {
                val displayId = try {
                    XposedHelpers.getIntField(taskInfo, "displayId")
                } catch (_: Exception) {
                    try {
                        XposedHelpers.callMethod(taskInfo, "getDisplayId") as Int
                    } catch (_: Exception) {
                        -1
                    }
                }

                val removedTaskId = taskInfo.taskId
                expectedMoveToDefaultAt.remove(removedTaskId)
                lastBounceDefendAt.remove(removedTaskId)
                val removedPackage = taskPackageMap.remove(removedTaskId)
                if (!removedPackage.isNullOrEmpty()) {
                    val latest = packageLatestTaskMap[removedPackage]
                    if (latest == removedTaskId) {
                        packageLatestTaskMap.remove(removedPackage)
                    }
                }

                val removedDisplayOwner = displayTaskMap.entries
                    .find { it.value.contains(removedTaskId) }
                    ?.let { "${it.key}->${it.value}" }
                XLog.ls(
                    "diag:onTaskRemovalStarted taskId=$removedTaskId pkg=$removedPackage " +
                        "owner=[$removedDisplayOwner]",
                    tag = "ZFlowDiag"
                )

                displayTaskMap.entries.find { it.value.contains(removedTaskId) }?.let { entry ->
                    val managedDisplayId = entry.key
                    entry.value.remove(removedTaskId)
                    val window = getWindow(managedDisplayId)
                    if (entry.value.isEmpty()) {
                        if (window?.isNotificationTransitionActive() == true) {
                            // 通知中转页结束是正常流程；等待后续新建详情 Task，
                            // 不要在这里销毁 VirtualDisplay。
                            XLog.d(
                                "$TAG: NOTIFY_OLD_TASK_REMOVED task=$removedTaskId " +
                                    "display=$managedDisplayId keepWindow=true"
                            )
                        } else {
                            window?.realDestroy()
                        }
                    }
                }
            }
        }

        override fun onTaskDisplayChanged(taskId: Int, newDisplayId: Int) {
            runOnMainThread {
                val previousDisplayId = displayTaskMap.entries
                    .find { it.value.contains(taskId) }
                    ?.key

                XLog.ls(
                    "diag:onTaskDisplayChanged taskId=$taskId fromDisp=$previousDisplayId " +
                        "toDisp=$newDisplayId managedDisplays=${displayIdList.joinToString(",")}",
                    tag = "ZFlowDiag"
                )

                // 意外弹回默认屏：系统未经模块请求把任务从托管虚拟屏拉回默认屏。
                // 就地拦截，把任务移回原虚拟屏，避免全屏闪现窗口期与后续窗口重建竞态。
                if (previousDisplayId != null && newDisplayId == 0 &&
                    shouldDefendBounceToDefault(taskId, previousDisplayId)
                ) {
                    lastBounceDefendAt[taskId] = SystemClock.uptimeMillis()
                    expectedMoveToDefaultAt.remove(taskId)
                    val moved = moveTaskToDisplaySafely(taskId, previousDisplayId)
                    // 拦截发生在移除之前，taskId 仍在该 display 的列表中，避免重复添加。
                    val bucket = displayTaskMap.getOrPut(previousDisplayId) { mutableListOf() }
                    if (!bucket.contains(taskId)) bucket.add(taskId)
                    bindTaskToWindowIfMatch(taskId, previousDisplayId)
                    XLog.ls(
                        "diag:bounceDefended taskId=$taskId fromDisp=$previousDisplayId " +
                            "movedBack=$moved",
                        tag = "ZFlowDiag"
                    )
                    XLog.d("$TAG: Defended unexpected bounce taskId=$taskId movedBack=$moved")
                    return@runOnMainThread
                }

                displayTaskMap.values.forEach { it.remove(taskId) }

                if (displayIdList.contains(newDisplayId)) {
                    displayTaskMap
                        .getOrPut(newDisplayId) { mutableListOf() }
                        .add(taskId)
                    bindTaskToWindowIfMatch(taskId, newDisplayId)
                }

                if (previousDisplayId != null && previousDisplayId != 0 && newDisplayId == 0) {
                    HookReload.suppressNextRelaunch(taskId)
                }

                if (previousDisplayId != null && newDisplayId == 0) {
                    val window = getWindow(previousDisplayId)
                    if (window != null && !window.isDestroyed &&
                        !window.isNotificationTransitionActive() &&
                        (window.isClosedToBack || window.isFloating || window.isHidden)
                    ) {
                        window.realDestroy()
                    } else if (window?.isNotificationTransitionActive() == true) {
                        XLog.d(
                            "$TAG: keeping display=$previousDisplayId alive during " +
                                "notification task transition"
                        )
                    }
                }
                XLog.d("$TAG: onTaskDisplayChanged taskId=$taskId previous=$previousDisplayId new=$newDisplayId")
            }
        }

        override fun onTaskRequestedOrientationChanged(taskId: Int, requestedOrientation: Int) {
            runOnMainThread {
                displayTaskMap.entries.find { it.value.contains(taskId) }?.let { entry ->
                    getWindow(entry.key)?.setVirtualDisplayRotation(requestedOrientation)
                }
            }
        }

        override fun onActivityRequestedOrientationChanged(taskId: Int, requestedOrientation: Int) {
            runOnMainThread {
                displayTaskMap.entries.find { it.value.contains(taskId) }?.let { entry ->
                    getWindow(entry.key)?.setVirtualDisplayRotation(requestedOrientation)
                }
            }
        }
    }

    fun systemReady() {
        try {
            Instances.init(activityManagerService)
            Instances.activityTaskManager.registerTaskStackListener(taskStackListener)
            isReady = true
            XLog.d("$TAG: systemReady completed")
        } catch (e: Throwable) {
            isReady = false
            XLog.e("$TAG: systemReady failed", e)
        }
    }

    fun addWindow(window: FreeformWindow) {
        if (window.isDestroyed || window.displayId < 0 || windowList.any { it === window }) return
        windowList.add(0, window)
        displayIdList.add(0, window.displayId)
        displayTaskMap.getOrPut(window.displayId) { mutableListOf() }
    }

    // Keep display-based callers selecting the current window on that display.
    fun removeWindow(displayId: Int) {
        getWindow(displayId)?.let { removeWindow(it) }
    }

    fun removeWindow(window: FreeformWindow) {
        // A failed constructor has displayId=-1 and may never have been registered.
        // A stale instance must not unregister a different window on a reused ID.
        if (!windowList.removeWindowIdentity(window)) return
        displayIdList.remove(window.displayId)
        if (windowList.none { it.displayId == window.displayId }) {
            displayTaskMap.remove(window.displayId)
        }
    }

    fun moveToTop(displayId: Int) {
        if (displayIdList.remove(displayId)) {
            displayIdList.add(0, displayId)
        }
    }

    fun getWindow(displayId: Int): FreeformWindow? = windowList.find { it.displayId == displayId }

    private fun resolvedExistingState(w: FreeformWindow): String {
        return "destroyed=${w.isDestroyed} closed=${w.isClosedToBack} " +
            "floating=${w.isFloating} hidden=${w.isHidden} display=${w.displayId} " +
            "task=${w.getCurrentTaskId()}"
    }

    /**
     * 判断一次「任务从托管虚拟屏弹回默认屏」是否属于系统意外弹回、应当就地拦截移回，
     * 而不是当作用户主动展开/关闭而销毁窗口。
     *
     * 必须全部成立才算「意外弹回」：
     *  - 任务根任务仍存活（否则是正常任务被移除，无法也不需要拦截）；
     *  - 原虚拟屏上仍有未销毁的窗口；
     *  - 窗口处于 mini 显示态（isFloating 或 isHidden），而非已收起后台（isClosedToBack）；
     *  - 不是「通知中转页过渡」状态（该情况下保留窗口由 onTaskRemovalStarted 处理）；
     *  - 模块最近没有主动把该任务迁回默认屏（否则是用户主动展开/还原，应放行）；
     *  - 距上一次弹回拦截超过了冷却时间（防系统反复弹回导致拦截-移回死循环）。
     */
    private fun shouldDefendBounceToDefault(taskId: Int, previousDisplayId: Int): Boolean {
        if (taskId <= 0 || previousDisplayId <= 0) return false
        if (!isRootTaskAlive(taskId)) return false

        val window = getWindow(previousDisplayId) ?: return false
        if (window.isDestroyed || window.isClosedToBack) return false
        if (!window.isFloating && !window.isHidden) return false
        if (window.isNotificationTransitionActive()) return false

        val now = SystemClock.uptimeMillis()
        return BounceDefendDecision.shouldAllowBounceDefend(
            now,
            expectedMoveToDefaultAt[taskId],
            lastBounceDefendAt[taskId]
        )
    }

    private fun bindTaskToWindowIfMatch(taskId: Int, displayId: Int) {
        if (taskId <= 0 || displayId < 0) return
        val window = getWindow(displayId) ?: return
        if (window.isDestroyed) return

        val taskPackage = taskPackageMap[taskId]
        val windowPackage = window.componentName?.packageName

        if (!taskPackage.isNullOrEmpty() && taskPackage == windowPackage) {
            window.bindTask(taskId)
        }
    }

    fun isManagedDisplay(displayId: Int): Boolean = displayIdList.contains(displayId)

    fun getManagedDisplayIds(): IntArray = displayIdList.toIntArray()

    fun hasTaskOnDisplay(displayId: Int): Boolean {
        val taskList = displayTaskMap[displayId]
        return !taskList.isNullOrEmpty()
    }

    fun getTopTaskIdOnDisplay(displayId: Int): Int {
        return displayTaskMap[displayId]?.lastOrNull() ?: -1
    }

    fun getLatestAliveTaskIdForPackage(packageName: String?): Int {
        if (packageName.isNullOrEmpty()) return -1

        val mapped = packageLatestTaskMap[packageName] ?: return -1
        return if (isRootTaskAlive(mapped)) mapped else -1
    }

    fun resolveBestTaskIdForPackage(
        packageName: String?,
        fallbackTaskId: Int
    ): Int {
        // Recents supplies the exact task selected by the user. Prefer it over
        // package-level history because one package may own multiple tasks.
        if (fallbackTaskId > 0 && isRootTaskAlive(fallbackTaskId)) {
            return fallbackTaskId
        }

        val latestAliveTaskId = getLatestAliveTaskIdForPackage(packageName)
        if (latestAliveTaskId > 0) {
            return latestAliveTaskId
        }

        return -1
    }

    fun isRootTaskAlive(taskId: Int): Boolean {
        if (taskId <= 0) return false
        return try {
            val wms = XposedHelpers.getObjectField(activityManagerService, "mWindowManager")
            val root = XposedHelpers.getObjectField(wms, "mRoot")
            findTaskInRoot(root, taskId) != null
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 把任意 taskId 解析为它所属的 **root task id**。
     *
     * Android 17 起 Task 层级更深（Task -> TaskFragment -> Task），
     * anyTaskForId() 会返回非 root 的叶子 task，而
     * ActivityTaskManagerService.moveRootTaskToDisplay() 只接受 root task id，
     * 传入叶子 id 会抛：
     *   IllegalArgumentException: moveRootTaskToTaskDisplayArea: Unknown rootTaskId=N
     *
     * 因此迁移前必须先归一到 root task id。解析失败时返回原值。
     */
    fun resolveRootTaskId(taskId: Int): Int {
        if (taskId <= 0) return taskId
        return try {
            val wms = XposedHelpers.getObjectField(activityManagerService, "mWindowManager")
            val root = XposedHelpers.getObjectField(wms, "mRoot")
            val task = findTaskInRoot(root, taskId) ?: return taskId

            val rootTask = runCatching {
                XposedHelpers.callMethod(task, "getRootTask")
            }.getOrNull() ?: return taskId

            val rootTaskId = runCatching {
                XposedHelpers.getIntField(rootTask, "mTaskId")
            }.getOrElse {
                runCatching {
                    XposedHelpers.callMethod(rootTask, "getRootTaskId") as Int
                }.getOrDefault(taskId)
            }

            if (rootTaskId > 0) rootTaskId else taskId
        } catch (_: Throwable) {
            taskId
        }
    }

    fun getTaskDisplayId(taskId: Int): Int {
        if (taskId <= 0) return -1
        return try {
            val wms = XposedHelpers.getObjectField(activityManagerService, "mWindowManager")
            val root = XposedHelpers.getObjectField(wms, "mRoot")
            val task = findTaskInRoot(root, taskId) ?: return -1

            try {
                XposedHelpers.callMethod(task, "getDisplayId") as Int
            } catch (_: Throwable) {
                val displayContent = XposedHelpers.callMethod(task, "getDisplayContent")
                XposedHelpers.getIntField(displayContent, "mDisplayId")
            }
        } catch (_: Throwable) {
            -1
        }
    }

    /**
     * 迁移 Task 到指定 Display，只发起请求，不同步验证结果。
     * 返回 true 表示请求已成功提交，不保证最终迁移成功。
     */
    fun moveTaskToDisplaySafely(taskId: Int, displayId: Int): Boolean {
        if (taskId <= 0 || displayId < 0) {
            XLog.w("$TAG: Reject move taskId=$taskId displayId=$displayId")
            return false
        }

        if (!isRootTaskAlive(taskId)) {
            XLog.w("$TAG: Task is not alive taskId=$taskId targetDisplay=$displayId")
            return false
        }

        // Android 17 起 anyTaskForId() 可能返回叶子 task，而
        // moveRootTaskToDisplay() 只接受 root task id，必须先归一。
        val rootTaskId = resolveRootTaskId(taskId)

        val currentDisplayId = getTaskDisplayId(rootTaskId)
        if (currentDisplayId == displayId) {
            XLog.d("$TAG: Task already on target display taskId=$rootTaskId displayId=$displayId")
            return true
        }

        XLog.ls(
            "diag:MoveTaskReq taskId=$taskId rootTaskId=$rootTaskId " +
                "fromDisp=$currentDisplayId toDisp=$displayId",
            tag = "ZFlowDiag"
        )

        return try {
            XLog.d(
                "$TAG: Request task move taskId=$taskId rootTaskId=$rootTaskId " +
                    "from=$currentDisplayId to=$displayId"
            )
            // Moving an existing fullscreen task changes its display bounds. Some
            // apps finish or recreate their root activity during that transition.
            HookReload.suppressNextRelaunch(rootTaskId)
            if (rootTaskId != taskId) {
                HookReload.suppressNextRelaunch(taskId)
            }
            Instances.activityTaskManager.moveRootTaskToDisplay(rootTaskId, displayId)
            // 只表示请求已成功提交，不同步判断最终状态。
            true
        } catch (e: Throwable) {
            HookReload.cancelRelaunchSuppression(rootTaskId)
            HookReload.cancelRelaunchSuppression(taskId)
            XLog.e(
                "$TAG: Failed to request task move taskId=$taskId rootTaskId=$rootTaskId " +
                    "from=$currentDisplayId to=$displayId",
                e
            )
            false
        }
    }

    fun moveTaskFromDisplayToDefault(displayId: Int, fallbackTaskId: Int): Boolean {
        val mappedTaskId = displayTaskMap[displayId]
            ?.lastOrNull { isRootTaskAlive(it) }

        val taskIdToMove = when {
            mappedTaskId != null && mappedTaskId > 0 -> mappedTaskId
            isRootTaskAlive(fallbackTaskId) -> fallbackTaskId
            else -> -1
        }

        if (taskIdToMove <= 0) {
            return false
        }

        // 同 moveTaskToDisplaySafely：迁移接口只接受 root task id
        val rootTaskId = resolveRootTaskId(taskIdToMove)

        val currentDisplayId = getTaskDisplayId(rootTaskId)

        // 用户主动展开/还原到默认屏：记录期望迁移，供 onTaskDisplayChanged 区分。
        // 同时以原始 taskId 与 rootTaskId 冗余记录，让监听回调任一带都能命中。
        expectedMoveToDefaultAt[taskIdToMove] = SystemClock.uptimeMillis()
        expectedMoveToDefaultAt[rootTaskId] = SystemClock.uptimeMillis()

        return try {
            if (currentDisplayId == Display.DEFAULT_DISPLAY) {
                HookReload.suppressNextRelaunch(rootTaskId)
                if (isRootTaskAlive(rootTaskId)) {
                    Instances.activityManager.moveTaskToFront(rootTaskId, 0)
                }
                true
            } else {
                HookReload.suppressNextRelaunch(rootTaskId)
                Instances.activityTaskManager.moveRootTaskToDisplay(
                    rootTaskId,
                    Display.DEFAULT_DISPLAY
                )
                if (isRootTaskAlive(rootTaskId)) {
                    Instances.activityManager.moveTaskToFront(rootTaskId, 0)
                }
                true
            }
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            if (e is IllegalArgumentException &&
                message.contains("to its current taskDisplayArea")
            ) {
                if (isRootTaskAlive(rootTaskId)) {
                    Instances.activityManager.moveTaskToFront(rootTaskId, 0)
                }
                HookReload.suppressNextRelaunch(rootTaskId)
                return true
            }

            HookReload.cancelRelaunchSuppression(rootTaskId)
            XLog.e(
                "$TAG: Failed to move task $taskIdToMove (root=$rootTaskId) " +
                    "from display $displayId to default",
                e
            )
            false
        }
    }

    fun removeTask(taskId: Int): Boolean {
        if (taskId <= 0) return false

        return try {
            XposedHelpers.callMethod(Instances.activityTaskManager, "removeTask", taskId)
            true
        } catch (e: Throwable) {
            XLog.e("$TAG: Failed to remove task $taskId", e)
            false
        }
    }

    fun getTaskSurfaceForAnimation(taskId: Int): SurfaceControl? {
        if (taskId <= 0) return null
        return try {
            val wms = XposedHelpers.getObjectField(activityManagerService, "mWindowManager")
            val globalLock = XposedHelpers.getObjectField(wms, "mGlobalLock")
            synchronized(globalLock) {
                val root = XposedHelpers.getObjectField(wms, "mRoot")
                val task = findTaskInRoot(root, taskId) ?: return null

                val surfaceFromMethod = try {
                    XposedHelpers.callMethod(task, "getSurfaceControl").cast<SurfaceControl?>()
                } catch (_: Throwable) {
                    null
                }
                if (surfaceFromMethod != null && surfaceFromMethod.isValid) {
                    return surfaceFromMethod
                }

                val surfaceFromField = try {
                    XposedHelpers.getObjectField(task, "mSurfaceControl").cast<SurfaceControl?>()
                } catch (_: Throwable) {
                    null
                }
                if (surfaceFromField != null && surfaceFromField.isValid) {
                    return surfaceFromField
                }
                null
            }
        } catch (e: Throwable) {
            XLog.e("$TAG: Failed to get task surface for taskId=$taskId", e)
            null
        }
    }

    private fun findTaskInRoot(root: Any, taskId: Int): Any? {
        try {
            return XposedHelpers.callMethod(root, "anyTaskForId", taskId)
        } catch (_: Throwable) {
        }
        try {
            return XposedHelpers.callMethod(root, "anyTaskForId", taskId, 0)
        } catch (_: Throwable) {
        }
        try {
            return XposedHelpers.callMethod(root, "anyTaskForId", taskId, 0, null, false)
        } catch (_: Throwable) {
        }
        try {
            return XposedHelpers.callMethod(root, "getTask", taskId)
        } catch (_: Throwable) {
        }
        // Android 17：部分构建改用带 Predicate 的 getTask/anyTaskForId 重载，
        // 上面按参数个数的调用会全部落空，这里按名称+参数个数兜底反射。
        try {
            for (method in root.javaClass.methods) {
                if (method.name != "anyTaskForId" && method.name != "getTask") continue
                val params = method.parameterTypes
                if (params.size != 1) continue
                if (params[0] != Int::class.javaPrimitiveType) continue
                method.isAccessible = true
                val result = method.invoke(root, taskId)
                if (result != null) return result
            }
        } catch (_: Throwable) {
        }
        return null
    }

    fun findAnyWindow(packageName: String?): FreeformWindow? {
        if (packageName == null) return null
        // 先查已就绪窗口；再查正在初始化（VirtualDisplay 尚未回调、还没进入
        // windowList）的窗口，避免同一应用多个通知被快速点击时重复建窗。
        return windowList.find {
            !it.isDestroyed && it.componentName?.packageName == packageName
        } ?: FreeformWindow.findWindowByPackage(packageName)
    }

    fun bringMiniWindowsToFront() {
        windowList.filter { it.isFloating || it.isHidden }.toList().reversed().forEach { it.moveToTop() }
    }

    /** 最多同时小窗数量（从 Z-Flow 设置读取，缓存） */
    @Volatile
    var maxFreeformWindows: Int = 2
        private set

    fun refreshMaxFreeformWindows() {
        // 通过 ContentProvider 从 Z-Flow 进程读取设置（与 BlacklistProvider
        // 相同模式，跨进程可靠）。system_server 直接 createPackageContext
        // 读 SP 在部分 ROM 上会被 SELinux 拦截。
        maxFreeformWindows = runCatching {
            val uri = Uri.parse(
                "content://io.relimus.zflow.notification.provider"
            )
            val bundle = Instances.systemContext.contentResolver.call(
                uri,
                "get_max_freeform_windows",
                null,
                Bundle()
            )
            (bundle?.getInt("maxFreeform", 2) ?: 2).coerceIn(1, 5)
        }.onFailure {
            XLog.e("$TAG: refreshMaxFreeformWindows failed", it)
        }.getOrDefault(2)
    }

    fun getMiniWindowLocation(excludeDisplayId: Int): IntArray? {
        return windowList.find {
            (it.isFloating || it.isHidden) &&
                !it.isDestroyed &&
                !it.isClosedToBack &&
                it.displayId != excludeDisplayId
        }?.getMiniLocation()
    }

    fun closeAllMiniWindows() {
        windowList.filter { it.isFloating || it.isHidden }.forEach { it.closeToBack() }
    }

    fun closeAllNormalWindows() {
        windowList.filter { !it.isFloating && !it.isHidden && !it.isClosedToBack }
            .forEach { it.closeToBack() }
    }

    override fun getVersionName(): String = BuildConfig.VERSION_NAME

    override fun getVersionCode(): Int = BuildConfig.VERSION_CODE

    override fun getUid(): Int = Process.myUid()

    override fun createWindow(
        componentName: ComponentName?,
        pendingIntent: PendingIntent?,
        userId: Int,
        taskId: Int,
        freeformDpi: Int,
        freeformSize: Int,
        freeformSizeLand: Int,
        floatViewSize: Int,
        dimAmount: Int,
        manualAdjustFreeformRotation: Boolean,
        sourceRotation: Int,
        sourceScreenWidth: Int,
        sourceScreenHeight: Int,
        dockStyle: Int
    ) {
        createWindowInternal(
            componentName, pendingIntent, userId, taskId,
            freeformDpi, freeformSize, freeformSizeLand, floatViewSize,
            dimAmount, manualAdjustFreeformRotation, sourceRotation, sourceScreenWidth,
            sourceScreenHeight, dockStyle,
            directToMini = false
        )
    }

    override fun createMiniWindow(
        componentName: ComponentName?,
        pendingIntent: PendingIntent?,
        userId: Int,
        taskId: Int,
        freeformDpi: Int,
        freeformSize: Int,
        freeformSizeLand: Int,
        floatViewSize: Int,
        dimAmount: Int,
        manualAdjustFreeformRotation: Boolean,
        sourceRotation: Int,
        sourceScreenWidth: Int,
        sourceScreenHeight: Int,
        dockStyle: Int
    ) {
        createWindowInternal(
            componentName, pendingIntent, userId, taskId,
            freeformDpi, freeformSize, freeformSizeLand, floatViewSize,
            dimAmount, manualAdjustFreeformRotation, sourceRotation, sourceScreenWidth,
            sourceScreenHeight, dockStyle,
            directToMini = true
        )
    }

    private fun createWindowInternal(
        componentName: ComponentName?,
        pendingIntent: PendingIntent?,
        userId: Int,
        taskId: Int,
        freeformDpi: Int,
        freeformSize: Int,
        freeformSizeLand: Int,
        floatViewSize: Int,
        dimAmount: Int,
        manualAdjustFreeformRotation: Boolean,
        sourceRotation: Int,
        sourceScreenWidth: Int,
        sourceScreenHeight: Int,
        dockStyle: Int,
        directToMini: Boolean
    ) {
        if (!isReady) {
            XLog.e("$TAG: Service not ready")
            return
        }
        // These window requests bind notification/task recovery to one package.
        // Do not apply this rule to sendPendingIntentOnDisplay: a creator package
        // is not generally the destination of an arbitrary/delegated PendingIntent.
        val targetPackage = componentName?.packageName
        if (pendingIntent != null && targetPackage != null && pendingIntent.creatorPackage != targetPackage) {
            XLog.w("$TAG: Reject PendingIntent creator mismatch for package-scoped window")
            return
        }
        runOnMainThread {
            try {
                refreshMaxFreeformWindows()
                Instances.iStatusBarService.collapsePanels()

                XLog.ls(
                    "diag:createWindowInternal directToMini=$directToMini component=$targetPackage " +
                        "user=$userId inputTaskId=$taskId pendingIntent=${pendingIntent != null} " +
                        "windowCount=${windowList.size}",
                    tag = "ZFlowDiag"
                )
                val existingWindow = findAnyWindow(componentName?.packageName)
                if (existingWindow != null && !existingWindow.isDestroyed && existingWindow.displayId >= 0) {
                    if (pendingIntent != null && existingWindow.launchPendingIntent(pendingIntent)) {
                        existingWindow.moveToTop()
                        XLog.ls(
                            "diag:reuse window display=${existingWindow.displayId} " +
                                "action=relaunch-pendingIntent after=${resolvedExistingState(existingWindow)}",
                            tag = "ZFlowDiag"
                        )
                        return@runOnMainThread
                    }
                    if (directToMini) {
                        if (!existingWindow.isClosedToBack && (existingWindow.isFloating || existingWindow.isHidden)) {
                            existingWindow.moveToTop()
                            XLog.ls(
                                "diag:reuse window display=${existingWindow.displayId} " +
                                    "action=mini-movetop after=${resolvedExistingState(existingWindow)}",
                                tag = "ZFlowDiag"
                            )
                            return@runOnMainThread
                        }
                        XLog.ls(
                            "diag:reuse window display=${existingWindow.displayId} " +
                                "action=mini-destroy replace existing=${resolvedExistingState(existingWindow)}",
                            tag = "ZFlowDiag"
                        )
                        existingWindow.realDestroy()
                    } else {
                        when {
                            existingWindow.isClosedToBack -> {
                                existingWindow.restoreFromBack()
                                bringMiniWindowsToFront()
                                XLog.ls(
                                    "diag:reuse window display=${existingWindow.displayId} action=restore-from-back",
                                    tag = "ZFlowDiag"
                                )
                            }
                            existingWindow.isFloating || existingWindow.isHidden -> {
                                existingWindow.restoreToNormalView()
                                XLog.ls(
                                    "diag:reuse window display=${existingWindow.displayId} action=restore-normal(mini->full)",
                                    tag = "ZFlowDiag"
                                )
                            }
                            else -> {
                                existingWindow.moveToTop()
                                XLog.ls(
                                    "diag:reuse window display=${existingWindow.displayId} action=movetop",
                                    tag = "ZFlowDiag"
                                )
                            }
                        }
                        return@runOnMainThread
                    }
                } else {
                    XLog.ls(
                        "diag:no-existing-window directToMini=$directToMini existing=$existingWindow " +
                            "destroyed=${existingWindow?.isDestroyed} " +
                            "display=${existingWindow?.displayId}",
                        tag = "ZFlowDiag"
                    )
                }

                val resolvedTaskId = resolveBestTaskIdForPackage(
                    componentName?.packageName,
                    taskId
                )

                // DPI 将在 FreeformWindow 内部强制跟随物理屏幕
                val config = FreeformConfig(
                    freeformDpi = freeformDpi, // 传入但最终会被覆盖
                    freeformSize = freeformSize / 100f,
                    freeformSizeLand = freeformSizeLand / 100f,
                    floatViewSize = floatViewSize / 100f,
                    dimAmount = dimAmount / 100f,
                    manualAdjustFreeformRotation = manualAdjustFreeformRotation,
                    defaultLandscape = isLandscapeApp(componentName?.packageName),
                    dockStyle = dockStyle
                )

                val inheritedLocation = if (directToMini) {
                    windowList.find {
                        (it.isFloating || it.isHidden) && !it.isDestroyed && !it.isClosedToBack
                    }?.getMiniLocation()
                } else null

                val traceId = XLog.newTraceId()
                XLog.d("$TAG: [$traceId] createWindow directToMini=$directToMini component=$componentName userId=$userId inputTaskId=$taskId resolvedTaskId=$resolvedTaskId")

                FreeformWindow(
                    Instances.systemUiContext,
                    componentName,
                    userId,
                    resolvedTaskId,
                    config,
                    directToMini = directToMini,
                    inheritedMiniLocation = inheritedLocation,
                    pendingIntent = pendingIntent,
                    allowTapOutsideToClose = false,
                    sourceRotation = sourceRotation,
                    sourceScreenWidth = sourceScreenWidth,
                    sourceScreenHeight = sourceScreenHeight,
                    traceId = traceId
                )

                if (!directToMini) bringMiniWindowsToFront()
            } catch (e: Exception) {
                XLog.e("$TAG: Failed to create window", e)
            }
        }
    }

    override fun destroyWindow(displayId: Int) {
        runOnMainThread {
            getWindow(displayId)?.closeToBack()
        }
    }

    override fun destroyAllWindows() {
        runOnMainThread {
            windowList.toList().forEach { it.realDestroy() }
        }
    }

    override fun moveWindowToTop(displayId: Int) {
        runOnMainThread {
            getWindow(displayId)?.moveToTop()
        }
    }

    override fun injectMotionEvent(event: MotionEventBean?, displayId: Int) {
        if (event == null) return
        runOnMainThread {
            try {
                val count = event.xArray.size
                val pointerProperties = Array(count) { i ->
                    MotionEvent.PointerProperties().apply {
                        id = i
                        toolType = MotionEvent.TOOL_TYPE_FINGER
                    }
                }
                val pointerCoords = Array(count) { i ->
                    MotionEvent.PointerCoords().apply {
                        x = event.xArray[i]
                        y = event.yArray[i]
                        pressure = 1f
                        size = 1f
                    }
                }

                val motionEvent = MotionEvent.obtain(
                    SystemClock.uptimeMillis(),
                    SystemClock.uptimeMillis(),
                    event.action,
                    count,
                    pointerProperties,
                    pointerCoords,
                    0,
                    0,
                    1f,
                    1f,
                    -1,
                    0,
                    InputDevice.SOURCE_TOUCHSCREEN,
                    0
                )
                ObjectUtil.invokeMethod(
                    obj = motionEvent,
                    methodName = "setDisplayId",
                    paramTypes = paramTypes(Int::class.javaPrimitiveType),
                    params = params(displayId)
                )
                Instances.inputManager.injectInputEvent(motionEvent, 0)
                motionEvent.recycle()
            } catch (e: Exception) {
                XLog.e("$TAG: Failed to inject motion event", e)
            }
        }
    }

    override fun injectKeyEvent(keyCode: Int, displayId: Int) {
        runOnMainThread {
            try {
                val downTime = SystemClock.uptimeMillis()
                val downEvent = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0)
                ObjectUtil.invokeMethod(
                    obj = downEvent,
                    methodName = "setSource",
                    paramTypes = paramTypes(Int::class.javaPrimitiveType),
                    params = params(InputDevice.SOURCE_KEYBOARD)
                )
                ObjectUtil.invokeMethod(
                    obj = downEvent,
                    methodName = "setDisplayId",
                    paramTypes = paramTypes(Int::class.javaPrimitiveType),
                    params = params(displayId)
                )
                Instances.inputManager.injectInputEvent(downEvent, 0)

                val upEvent = KeyEvent(
                    downTime,
                    SystemClock.uptimeMillis(),
                    KeyEvent.ACTION_UP,
                    keyCode,
                    0
                )
                ObjectUtil.invokeMethod(
                    obj = upEvent,
                    methodName = "setSource",
                    paramTypes = paramTypes(Int::class.javaPrimitiveType),
                    params = params(InputDevice.SOURCE_KEYBOARD)
                )
                ObjectUtil.invokeMethod(
                    obj = upEvent,
                    methodName = "setDisplayId",
                    paramTypes = paramTypes(Int::class.javaPrimitiveType),
                    params = params(displayId)
                )
                Instances.inputManager.injectInputEvent(upEvent, 0)
            } catch (e: Exception) {
                XLog.e("$TAG: Failed to inject key event", e)
            }
        }
    }

    override fun moveTaskToDisplay(taskId: Int, displayId: Int) {
        runOnMainThread {
            moveTaskToDisplaySafely(taskId, displayId)
        }
    }

    override fun startActivityOnDisplay(componentName: ComponentName?, userId: Int, displayId: Int) {
        if (componentName == null || userId < 0 || displayId < 0) {
            return
        }

        runOnMainThread {
            try {
                // 尽量使用标准 Launcher Intent，避免传入非启动 Activity
                val intent = buildLaunchIntent(componentName) ?: run {
                    XLog.e("$TAG: Cannot build launch intent for $componentName")
                    return@runOnMainThread
                }

                val options = ActivityOptions.makeBasic().apply {
                    launchDisplayId = displayId
                }.toBundle()

                val userHandle = ClassUtil.newInstance(
                    clz = UserHandle::class.java,
                    paramTypes = paramTypes(Int::class.javaPrimitiveType),
                    params = params(userId)
                ).cast<UserHandle>()

                ObjectUtil.invokeMethod(
                    obj = Instances.systemContext,
                    methodName = "startActivityAsUser",
                    paramTypes = paramTypes(
                        Intent::class.java,
                        Bundle::class.java,
                        UserHandle::class.java
                    ),
                    params = params(intent, options, userHandle)
                )
                XLog.d("$TAG: startActivityOnDisplay component=$componentName display=$displayId")
            } catch (e: Throwable) {
                XLog.e("$TAG: Failed to start activity $componentName on display=$displayId", e)
            }
        }
    }

    override fun sendPendingIntentOnDisplay(
        pendingIntent: PendingIntent?,
        displayId: Int,
        taskId: Int
    ) {
        XLog.d(
            "$TAG: PENDING_SEND_REQUEST display=$displayId task=$taskId " +
                "creator=${pendingIntent?.creatorPackage} activity=${pendingIntent?.isActivity}"
        )
        if (pendingIntent == null || displayId < 0) return

        runOnMainThread {
            try {
                val activityOptions = ActivityOptions.makeBasic().apply {
                    launchDisplayId = displayId
                    if (taskId > 0) {
                        // setLaunchTaskId 是隐藏 API；system_server 中通过反射调用，
                        // 将通知详情固定到已经迁入虚拟屏的应用 Task。
                        runCatching {
                            XposedHelpers.callMethod(this, "setLaunchTaskId", taskId)
                        }.onFailure {
                            XLog.w("$TAG: setLaunchTaskId unavailable taskId=$taskId", it)
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        pendingIntentBackgroundActivityStartMode =
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    }
                }
                val options = activityOptions.toBundle()
                pendingIntent.send(
                    Instances.systemContext,
                    0,
                    null,
                    null,
                    null,
                    null,
                    options
                )
                XLog.d(
                    "$TAG: PENDING_SEND_OK display=$displayId task=$taskId " +
                        "creator=${pendingIntent.creatorPackage}"
                )
                XLog.d(
                    "$TAG: sendPendingIntentOnDisplay display=$displayId taskId=$taskId " +
                        "creator=${pendingIntent.creatorPackage}"
                )
            } catch (e: PendingIntent.CanceledException) {
                XLog.e("$TAG: Notification contentIntent was canceled", e)
            } catch (e: Throwable) {
                XLog.e("$TAG: Failed to send notification contentIntent on display=$displayId", e)
            }
        }
    }

    override fun collapseStatusBarPanel() {
        runOnMainThread {
            try {
                Instances.iStatusBarService.collapsePanels()
            } catch (e: Exception) {
                XLog.e("$TAG: Failed to collapse status bar", e)
            }
        }
    }

    override fun getOpenWindowCount(): Int = windowList.size

    override fun isServiceReady(): Boolean = isReady

    private fun runOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            Handler(Looper.getMainLooper()).post(action)
        }
    }

    /**
     * 获取物理默认屏幕的 DPI，用于 VirtualDisplay，保证与系统一致。
     */
    fun getDefaultDisplayDpi(): Int {
        val display = Instances.displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            val metrics = android.util.DisplayMetrics()
            display.getRealMetrics(metrics)
            if (metrics.densityDpi > 0) {
                return metrics.densityDpi
            }
        }
        return Instances.systemContext.resources.displayMetrics.densityDpi
    }

    /**
     * 构建标准的 Launcher Intent，避免传入内部 Activity。
     */
    private fun buildLaunchIntent(componentName: ComponentName): Intent? {
        val packageName = componentName.packageName
        val pm = Instances.systemContext.packageManager
        val launcherIntent = pm.getLaunchIntentForPackage(packageName)
        if (launcherIntent != null) {
            return launcherIntent.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
        }
        // 后备：直接使用 component
        return Intent().apply {
            component = componentName
            `package` = packageName
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * 查询应用是否被勾选为横屏小窗
     */
    private fun isLandscapeApp(packageName: String?): Boolean {
        if (packageName.isNullOrEmpty()) return false
        return try {
            Instances.systemUiContext.contentResolver.call(
                Uri.parse("content://io.relimus.zflow.landscape.provider"),
                "is_landscape_app",
                null,
                Bundle().apply { putString("package_name", packageName) }
            )?.getBoolean("result", false) ?: false
        } catch (_: Exception) {
            false
        }
    }
}
