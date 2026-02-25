package io.relimus.zflow.xposed.services

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.TaskStackListener
import android.content.ComponentName
import android.content.Intent
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
import io.github.kyuubiran.ezxhelper.core.misc.paramTypes
import io.github.kyuubiran.ezxhelper.core.misc.params
import io.github.kyuubiran.ezxhelper.core.util.ClassUtil
import io.github.kyuubiran.ezxhelper.core.util.ObjectUtil
import io.relimus.zflow.BuildConfig
import io.relimus.zflow.bean.MotionEventBean
import io.relimus.zflow.xposed.hook.utils.XLog
import io.relimus.zflow.xposed.hook.HookReload
import io.relimus.zflow.xposed.IFreeformManager
import io.relimus.zflow.xposed.ui.config.FreeformConfig
import io.relimus.zflow.xposed.ui.window.FreeformWindow
import io.relimus.zflow.xposed.utils.Instances
import de.robv.android.xposed.XposedHelpers

/**
 * Core FreeformManager service running in system_server.
 * Implements IFreeformManager.Stub for AIDL communication with app.
 * Manages VirtualDisplay windows, input injection, and task management.
 */
@SuppressLint("StaticFieldLeak")
object FreeformManager : IFreeformManager.Stub() {
    private const val TAG = "FreeformManager"

    private val windowList = mutableListOf<FreeformWindow>()
    private val displayIdList = mutableListOf<Int>()
    private var isReady = false

    // 存储 displayId 对应的 taskId 列表
    private val displayTaskMap = mutableMapOf<Int, MutableList<Int>>()

    lateinit var activityManagerService: Any
        internal set

    // TaskStackListener 监听应用方向变化
    private val taskStackListener = object : TaskStackListener() {
        override fun onTaskCreated(taskId: Int, componentName: ComponentName?) {
            // 不需要处理，因为窗口创建时会自动关联 task
        }

        override fun onTaskRemovalStarted(taskInfo: ActivityManager.RunningTaskInfo) {
            // 如果是小窗内的任务被移除，真正销毁对应的窗口
            runOnMainThread {
                displayTaskMap.entries.find { it.value.contains(taskInfo.taskId) }?.let { entry ->
                    val displayId = entry.key
                    entry.value.remove(taskInfo.taskId)
                    // 如果这个 display 上没有任务了，真正销毁窗口
                    if (entry.value.isEmpty()) {
                        getWindow(displayId)?.realDestroy()
                    }
                }
            }
        }

        override fun onTaskDisplayChanged(taskId: Int, newDisplayId: Int) {
            runOnMainThread {
                // 记录 task 之前所在的 display
                val previousDisplayId = displayTaskMap.entries
                    .find { it.value.contains(taskId) }?.key

                // 更新 task 的 display 映射
                displayTaskMap.values.forEach { it.remove(taskId) }
                if (displayIdList.contains(newDisplayId)) {
                    displayTaskMap.getOrPut(newDisplayId) { mutableListOf() }.add(taskId)
                }

                // 当任务从虚拟显示器移动到主屏幕时，追踪该任务（用于阻止 relaunch）
                if (previousDisplayId != null && previousDisplayId != 0 && newDisplayId == 0) {
                    HookReload.trackedTaskIds.add(taskId)
                }

                // task 从 VirtualDisplay 移到默认屏幕（用户在桌面点击了同一个 App）
                // 窗口处于 mini/hidden/closedToBack 状态 → 销毁小窗，让 task 在默认屏幕打开
                if (previousDisplayId != null && newDisplayId == 0) {
                    val window = getWindow(previousDisplayId)
                    if (window != null && !window.isDestroyed
                        && (window.isClosedToBack || window.isFloating || window.isHidden)
                    ) {
                        window.realDestroy()
                    }
                }
            }
        }

        override fun onTaskRequestedOrientationChanged(taskId: Int, requestedOrientation: Int) {
            runOnMainThread {
                // 找到包含此 task 的 display
                displayTaskMap.entries.find { it.value.contains(taskId) }?.let { entry ->
                    getWindow(entry.key)?.setVirtualDisplayRotation(requestedOrientation)
                }
            }
        }

        override fun onActivityRequestedOrientationChanged(taskId: Int, requestedOrientation: Int) {
            runOnMainThread {
                // Android 10 及以上使用此回调
                displayTaskMap.entries.find { it.value.contains(taskId) }?.let { entry ->
                    getWindow(entry.key)?.setVirtualDisplayRotation(requestedOrientation)
                }
            }
        }
    }

    fun systemReady() {
        Instances.init(activityManagerService)
        isReady = true

        // 注册 TaskStackListener
        try {
            Instances.activityTaskManager.registerTaskStackListener(taskStackListener)
            XLog.d("$TAG: TaskStackListener registered")
        } catch (e: Exception) {
            XLog.e("$TAG: Failed to register TaskStackListener", e)
        }

        XLog.d("$TAG: System ready, FreeformManager initialized")
    }

    // Window management

    fun addWindow(window: FreeformWindow) {
        windowList.add(0, window)
        displayIdList.add(0, window.displayId)
        // 初始化 display 对应的 task 列表
        displayTaskMap[window.displayId] = mutableListOf()
    }

    fun removeWindow(displayId: Int) {
        val window = windowList.find { it.displayId == displayId }
        if (window != null) {
            windowList.remove(window)
            displayIdList.remove(displayId)
            // 清理 displayTaskMap
            displayTaskMap.remove(displayId)
        }
    }

    fun moveToTop(displayId: Int) {
        if (displayIdList.remove(displayId)) {
            displayIdList.add(0, displayId)
        }
    }

    fun getWindow(displayId: Int): FreeformWindow? = windowList.find { it.displayId == displayId }

    fun isManagedDisplay(displayId: Int): Boolean {
        return displayIdList.contains(displayId)
    }

    fun getManagedDisplayIds(): IntArray {
        return displayIdList.toIntArray()
    }

    /**
     * 检查指定 displayId 上是否有任务
     */
    fun hasTaskOnDisplay(displayId: Int): Boolean {
        val taskList = displayTaskMap[displayId]
        return !taskList.isNullOrEmpty()
    }

    fun getTopTaskIdOnDisplay(displayId: Int): Int {
        return displayTaskMap[displayId]?.lastOrNull() ?: -1
    }

    /**
     * 将指定 display 上的前台 task 移到默认屏幕。
     * 优先使用 displayTaskMap 中最后一个 task（最新映射），fallback 到传入 taskId。
     */
    fun moveTaskFromDisplayToDefault(displayId: Int, fallbackTaskId: Int): Boolean {
        val taskIdToMove = displayTaskMap[displayId]?.lastOrNull() ?: fallbackTaskId
        if (taskIdToMove <= 0) return false
        return try {
            Instances.activityTaskManager.moveRootTaskToDisplay(taskIdToMove, Display.DEFAULT_DISPLAY)
            Instances.activityManager.moveTaskToFront(taskIdToMove, 0)
            HookReload.trackedTaskIds.add(taskIdToMove)
            true
        } catch (e: Exception) {
            XLog.e("$TAG: Failed to move task $taskIdToMove from display $displayId to default", e)
            false
        }
    }

    /**
     * 通过 framework 内部 RootWindowContainer 查找 Task SurfaceControl。
     * 用于 SunOS 风格的 SurfaceControl 过渡动画。
     */
    fun getTaskSurfaceForAnimation(taskId: Int): SurfaceControl? {
        if (taskId <= 0) return null
        return try {
            val wms = XposedHelpers.getObjectField(activityManagerService, "mWindowManager")
            val globalLock = XposedHelpers.getObjectField(wms, "mGlobalLock")
            synchronized(globalLock) {
                val root = XposedHelpers.getObjectField(wms, "mRoot")
                val task = findTaskInRoot(root, taskId) ?: return null

                val surfaceFromMethod = try {
                    XposedHelpers.callMethod(task, "getSurfaceControl") as? SurfaceControl
                } catch (_: Throwable) {
                    null
                }
                if (surfaceFromMethod != null && surfaceFromMethod.isValid) {
                    return surfaceFromMethod
                }

                val surfaceFromField = try {
                    XposedHelpers.getObjectField(task, "mSurfaceControl") as? SurfaceControl
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
        return null
    }

    /**
     * 查找指定应用的任意可用窗口（包括后台窗口）
     */
    fun findAnyWindow(packageName: String?): FreeformWindow? {
        if (packageName == null) return null
        return windowList.find {
            !it.isDestroyed && it.componentName?.packageName == packageName
        }
    }

    /**
     * 将所有 mini/hidden 窗口提升到最顶层，确保其在普通窗口之上
     */
    fun bringMiniWindowsToFront() {
        // 使用 toList() 创建快照避免并发修改异常
        // 使用 reversed() 保持正确的 Z-order（后添加的在上层）
        windowList.filter { it.isFloating || it.isHidden }.toList().reversed().forEach { it.moveToTop() }
    }

    /**
     * 获取当前 mini/hidden 窗口的悬浮位置（排除指定 displayId）
     */
    fun getMiniWindowLocation(excludeDisplayId: Int): IntArray? {
        return windowList.find {
            (it.isFloating || it.isHidden) && !it.isDestroyed && !it.isClosedToBack
                    && it.displayId != excludeDisplayId
        }?.getMiniLocation()
    }

    /**
     * 关闭所有 mini/hidden 状态的窗口（退到后台）
     */
    fun closeAllMiniWindows() {
        windowList.filter { it.isFloating || it.isHidden }.forEach { it.closeToBack() }
    }

    /**
     * 关闭所有普通状态的窗口（退到后台）
     */
    fun closeAllNormalWindows() {
        windowList.filter { !it.isFloating && !it.isHidden && !it.isClosedToBack }.forEach { it.closeToBack() }
    }

    // IFreeformManager implementation

    override fun getVersionName(): String = BuildConfig.VERSION_NAME

    override fun getVersionCode(): Int = BuildConfig.VERSION_CODE

    override fun getUid(): Int = Process.myUid()

    override fun createWindow(componentName: ComponentName?, userId: Int, taskId: Int, freeformDpi: Int, freeformSize: Int, floatViewSize: Int, dimAmount: Int) {
        if (!isReady) {
            XLog.e("$TAG: Service not ready")
            return
        }
        runOnMainThread {
            try {
                Instances.iStatusBarService.collapsePanels()

                // 1. 检查是否已经有一个处于后台的同名应用窗口
                val existingWindow = findAnyWindow(componentName?.packageName)
                if (existingWindow != null) {
                    if (existingWindow.isClosedToBack) {
                        // 如果在后台，恢复它
                        closeAllNormalWindows()
                        existingWindow.restoreFromBack()
                        bringMiniWindowsToFront()
                        return@runOnMainThread
                    } else if (existingWindow.isFloating || existingWindow.isHidden) {
                        // 如果是 mini/hidden 状态，恢复到正常模式
                        closeAllNormalWindows()
                        existingWindow.restoreToNormalView()
                        return@runOnMainThread
                    }
                }

                // 2. 如果不存在，关闭当前普通状态窗口，创建新窗口
                closeAllNormalWindows()

                // Build config from parameters
                val config = FreeformConfig(
                    freeformDpi = freeformDpi,
                    freeformSize = freeformSize / 100f,
                    floatViewSize = floatViewSize / 100f,
                    dimAmount = dimAmount / 100f
                )
                // Pass componentName, userId, taskId to FreeformWindow
                // Activity will be started in onSurfaceTextureAvailable after VirtualDisplay is created
                FreeformWindow(Instances.systemUiContext, componentName, userId, taskId, config)

                // 将 mini 窗口提升到最顶层
                bringMiniWindowsToFront()
            } catch (e: Exception) {
                XLog.e("$TAG: Failed to create window", e)
            }
        }
    }

    override fun createMiniWindow(componentName: ComponentName?, userId: Int, taskId: Int, freeformDpi: Int, freeformSize: Int, floatViewSize: Int, dimAmount: Int) {
        if (!isReady) {
            XLog.e("$TAG: Service not ready")
            return
        }
        runOnMainThread {
            try {
                Instances.iStatusBarService.collapsePanels()

                // 同应用已有可见 mini/hidden 窗口 → 直接提到前台
                val existing = findAnyWindow(componentName?.packageName)
                if (existing != null && !existing.isDestroyed) {
                    if (!existing.isClosedToBack && (existing.isFloating || existing.isHidden)) {
                        existing.moveToTop()
                        return@runOnMainThread
                    }
                    existing.realDestroy()
                }

                // 继承已有 mini 窗口位置
                val inheritedLocation = windowList.find {
                    (it.isFloating || it.isHidden) && !it.isDestroyed && !it.isClosedToBack
                }?.getMiniLocation()

                closeAllMiniWindows()
                closeAllNormalWindows()

                val config = FreeformConfig(
                    freeformDpi = freeformDpi,
                    freeformSize = freeformSize / 100f,
                    floatViewSize = floatViewSize / 100f,
                    dimAmount = dimAmount / 100f
                )
                FreeformWindow(
                    Instances.systemUiContext, componentName, userId, taskId, config,
                    directToMini = true,
                    inheritedMiniLocation = inheritedLocation
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
                    0, 0, 1f, 1f,
                    -1, 0,
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

                val upEvent = KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0)
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
            try {
                Instances.activityTaskManager.moveRootTaskToDisplay(taskId, displayId)
                Instances.activityManager.moveTaskToFront(taskId, 0)
            } catch (e: Exception) {
                XLog.e("$TAG: Failed to move task $taskId", e)
            }
        }
    }

    override fun startActivityOnDisplay(componentName: ComponentName?, userId: Int, displayId: Int) {
        if (componentName == null) return
        runOnMainThread {
            try {
                val intent = Intent().apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    component = componentName
                    `package` = componentName.packageName
                    action = Intent.ACTION_VIEW
                }
                val options = ActivityOptions.makeBasic().apply {
                    launchDisplayId = displayId
                    ObjectUtil.invokeMethod(
                        obj = this,
                        methodName = "setCallerDisplayId",
                        paramTypes = paramTypes(Int::class.javaPrimitiveType),
                        params = params(displayId)
                    )
                }.toBundle()
                val userHandle = ClassUtil.newInstance(
                    clz = UserHandle::class.java,
                    paramTypes = paramTypes(Int::class.javaPrimitiveType),
                    params = params(userId)
                ) as UserHandle

                ObjectUtil.invokeMethod(
                    obj = Instances.systemContext,
                    methodName = "startActivityAsUser",
                    paramTypes = paramTypes(Intent::class.java, Bundle::class.java, UserHandle::class.java),
                    params = params(intent, options, userHandle)
                )
            } catch (e: Exception) {
                XLog.e("$TAG: Failed to start activity $componentName", e)
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
}
