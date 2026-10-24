package io.relimus.zflow.xposed.utils

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.IActivityTaskManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.input.IInputManager
import android.os.ServiceManager
import android.view.IWindowManager
import android.view.WindowManager
import com.android.internal.statusbar.IStatusBarService
import io.github.kyuubiran.ezxhelper.core.util.ObjectUtil
import io.relimus.zflow.utils.cast

/**
 * System service singleton references.
 * These are initialized from ActivityManagerService context in system_server.
 */
@SuppressLint("StaticFieldLeak")
object Instances {
    lateinit var windowManager: WindowManager
        private set
    lateinit var iWindowManager: IWindowManager
        private set
    lateinit var inputManager: IInputManager
        private set
    lateinit var displayManager: DisplayManager
        private set
    lateinit var activityTaskManager: IActivityTaskManager
        private set
    lateinit var activityManager: ActivityManager
        private set
    lateinit var iStatusBarService: IStatusBarService
        private set

    lateinit var systemContext: Context
        private set

    lateinit var systemUiContext: Context
        private set

    fun init(activityManagerService: Any) {
        systemContext = requireNotNull(
            ObjectUtil.getObject(activityManagerService, "mContext") as? Context
        ) { "ActivityManagerService.mContext is unavailable" }
        // Resolve before FreeformManager becomes ready, not during window construction.
        // A ROM mismatch is reported by systemReady instead of crashing a later request.
        systemUiContext = requireNotNull(
            ObjectUtil.getObject(activityManagerService, "mUiContext") as? Context
        ) { "ActivityManagerService.mUiContext is unavailable" }

        windowManager = systemContext.getSystemService(WindowManager::class.java)
        iWindowManager = IWindowManager.Stub.asInterface(ServiceManager.getService("window"))
        inputManager = IInputManager.Stub.asInterface(ServiceManager.getService("input"))
        displayManager = systemContext.getSystemService(DisplayManager::class.java)
        activityTaskManager = IActivityTaskManager.Stub.asInterface(ServiceManager.getService("activity_task"))
        activityManager = systemContext.getSystemService(ActivityManager::class.java)
        iStatusBarService = IStatusBarService.Stub.asInterface(ServiceManager.getService("statusbar"))
    }
}
