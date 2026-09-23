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
    private var isReady = false

    // displayId -> taskId list
    private val displayTaskMap = mutableMapOf<Int, MutableList<Int>>()

    // packageName -> latest created/alive taskId
    private val packageLatestTaskMap = mutableMapOf<String, Int>()

    // taskId -> packageName
    private val taskPackageMap = mutableMapOf<Int, String>()

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
                            XLog.ls(
                                "NOTIFY_NEW_TASK task=$taskId pkg=$packageName " +
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
                val removedPackage = taskPackageMap.remove(removedTaskId)
                if (!removedPackage.isNullOrEmpty()) {
                    val latest = packageLatestTaskMap[removedPackage]
                    if (latest == removedTaskId) {
                        packageLatestTaskMap.remove(removedPackage)
                    }
                }

                displayTaskMap.entries.find { it.value.contains(removedTaskId) }?.let { entry ->
                    val managedDisplayId = entry.key
                    entry.value.remove(removedTaskId)
                    val window = getWindow(managedDisplayId)
                    if (entry.value.isEmpty()) {
                        if (window?.isNotificationTransitionActive() == true) {
                            // 通知中转页结束是正常流程；等待后续新建详情 Task，
                            // 不要在这里销毁 VirtualDisplay。
                            XLog.ls(
                                "NOTIFY_OLD_TASK_REMOVED task=$removedTaskId " +
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
        windowList.add(0, window)
        displayIdList.add(0, window.displayId)
        displayTaskMap[window.displayId] = mutableListOf()
    }

    fun removeWindow(displayId: Int) {
        val window = windowList.find { it.displayId == displayId }
        if (window != null) {
            windowList.remove(window)
            displayIdList.remove(displayId)
            displayTaskMap.remove(displayId)
        }
    }

    fun moveToTop(displayId: Int) {
        if (displayIdList.remove(displayId)) {
            displayIdList.add(0, displayId)
        }
    }

    fun getWindow(displayId: Int): FreeformWindow? = windowList.find { it.displayId == displayId }

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
        if (!isReady) {
            XLog.e("$TAG: Service not ready")
            return
        }
        runOnMainThread {
            try {
                refreshMaxFreeformWindows()
                Instances.iStatusBarService.collapsePanels()

                XLog.ls(
                    "MANAGER_CREATE component=$componentName user=$userId inputTask=$taskId " +
                        "pending=${pendingIntent != null}"
                )
                val existingWindow = findAnyWindow(componentName?.packageName)
                XLog.ls(
                    "MANAGER_EXISTING pkg=${componentName?.packageName} " +
                        "found=${existingWindow != null} " +
                        "display=${existingWindow?.displayId} destroyed=${existingWindow?.isDestroyed}"
                )
                // 【修复】忽略已销毁或 displayId 无效的窗口，让它们自然清理，
                // 创建新窗口而不是尝试复用无效实例。
                if (existingWindow != null && !existingWindow.isDestroyed && existingWindow.displayId >= 0) {
                    if (pendingIntent != null && existingWindow.launchPendingIntent(pendingIntent)) {
                        existingWindow.moveToTop()
                        return@runOnMainThread
                    }
                    if (existingWindow.isClosedToBack) {
                        existingWindow.restoreFromBack()
                        bringMiniWindowsToFront()
                        return@runOnMainThread
                    } else if (existingWindow.isFloating || existingWindow.isHidden) {
                        existingWindow.restoreToNormalView()
                        return@runOnMainThread
                    } else {
                        // 已有一个正常显示的窗口：同一应用只保留一个小窗，
                        // 直接把它提到最上层，不再创建重复窗口。
                        existingWindow.moveToTop()
                        return@runOnMainThread
                    }
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

                val traceId = XLog.newTraceId()
                XLog.d("$TAG: [$traceId] createWindow component=$componentName userId=$userId inputTaskId=$taskId resolvedTaskId=$resolvedTaskId")
                XLog.ls(
                    "WINDOW_CREATE trace=$traceId component=$componentName display=pending " +
                        "resolvedTask=$resolvedTaskId pending=${pendingIntent != null}"
                )

                FreeformWindow(
                    Instances.systemUiContext,
                    componentName,
                    userId,
                    resolvedTaskId,
                    config,
                    pendingIntent = pendingIntent,
                    allowTapOutsideToClose = false,
                    sourceRotation = sourceRotation,
                    sourceScreenWidth = sourceScreenWidth,
                    sourceScreenHeight = sourceScreenHeight,
                    traceId = traceId
                )

                bringMiniWindowsToFront()
            } catch (e: Exception) {
                XLog.e("$TAG: Failed to create window", e)
            }
        }
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
        if (!isReady) {
            XLog.e("$TAG: Service not ready")
            return
        }
        runOnMainThread {
            try {
                refreshMaxFreeformWindows()
                Instances.iStatusBarService.collapsePanels()

                XLog.ls(
                    "MANAGER_CREATE_MINI component=$componentName user=$userId inputTask=$taskId " +
                        "pending=${pendingIntent != null}"
                )
                val existing = findAnyWindow(componentName?.packageName)
                XLog.ls(
                    "MANAGER_EXISTING_MINI pkg=${componentName?.packageName} " +
                        "found=${existing != null} display=${existing?.displayId} " +
                        "destroyed=${existing?.isDestroyed}"
                )
                // 【修复】同 createWindow：忽略已销毁或 displayId 无效的窗口
                if (existing != null && !existing.isDestroyed && existing.displayId >= 0) {
                    if (pendingIntent != null && existing.launchPendingIntent(pendingIntent)) {
                        existing.moveToTop()
                        return@runOnMainThread
                    }
                    if (!existing.isClosedToBack && (existing.isFloating || existing.isHidden)) {
                        existing.moveToTop()
                        return@runOnMainThread
                    }
                    existing.realDestroy()
                }

                val packageName = componentName?.packageName
                val landscapeApp = isLandscapeApp(packageName)

                val resolvedTaskId = resolveBestTaskIdForPackage(
                    packageName,
                    taskId
                )

                val config = FreeformConfig(
                    freeformDpi = freeformDpi,
                    freeformSize = freeformSize / 100f,
                    freeformSizeLand = freeformSizeLand / 100f,
                    floatViewSize = floatViewSize / 100f,
                    dimAmount = dimAmount / 100f,
                    manualAdjustFreeformRotation = manualAdjustFreeformRotation,
                    defaultLandscape = landscapeApp,
                    dockStyle = dockStyle
                )

                val inheritedLocation = windowList.find {
                    (it.isFloating || it.isHidden) && !it.isDestroyed && !it.isClosedToBack
                }?.getMiniLocation()

                val traceId = XLog.newTraceId()
                XLog.d("$TAG: [$traceId] createMiniWindow component=$componentName userId=$userId inputTaskId=$taskId resolvedTaskId=$resolvedTaskId")

                FreeformWindow(
                    Instances.systemUiContext,
                    componentName,
                    userId,
                    resolvedTaskId,
                    config,
                    directToMini = true,
                    inheritedMiniLocation = inheritedLocation,
                    pendingIntent = pendingIntent,
                    allowTapOutsideToClose = false,
                    sourceRotation = sourceRotation,
                    sourceScreenWidth = sourceScreenWidth,
                    sourceScreenHeight = sourceScreenHeight,
                    traceId = traceId
                )
            } catch (e: Exception) {
                XLog.e("$TAG: Failed to create mini window", e)
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
        XLog.ls(
            "PENDING_SEND_REQUEST display=$displayId task=$taskId " +
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
                XLog.ls(
                    "PENDING_SEND_OK display=$displayId task=$taskId " +
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
