package com.sunshine.freeform.hook.utils

import android.annotation.SuppressLint
import android.app.IActivityManager
import android.app.IActivityTaskManager
import android.content.Context
import android.content.pm.IPackageManager
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.input.IInputManager
import android.os.ServiceManager
import android.os.UserManager
import android.view.IWindowManager
import android.view.WindowManager
import de.robv.android.xposed.XposedHelpers

/**
 * System service instances for use in system_server context.
 * Initialized via Xposed hook in ActivityManagerService.systemReady()
 */
@SuppressLint("StaticFieldLeak")
object Instances {

    private var initialized = false

    lateinit var windowManager: WindowManager
        private set
    lateinit var iWindowManager: IWindowManager
        private set
    lateinit var inputManager: IInputManager
        private set
    lateinit var displayManager: DisplayManager
        private set
    lateinit var activityManager: IActivityManager
        private set
    lateinit var activityTaskManager: IActivityTaskManager
        private set
    lateinit var packageManager: PackageManager
        private set
    lateinit var userManager: UserManager
        private set
    lateinit var iPackageManager: IPackageManager
        private set

    private lateinit var activityManagerService: Any

    lateinit var systemContext: Context
        private set

    val systemUiContext: Context
        get() = XposedHelpers.getObjectField(activityManagerService, "mUiContext") as Context

    val isInitialized: Boolean
        get() = initialized

    /**
     * Initialize all system service instances.
     * Must be called from system_server context (e.g., in ActivityManagerService.systemReady hook)
     */
    fun init(ams: Any) {
        if (initialized) return

        activityManagerService = ams
        systemContext = XposedHelpers.getObjectField(ams, "mContext") as Context

        // Window services
        windowManager = systemContext.getSystemService(WindowManager::class.java)
        iWindowManager = IWindowManager.Stub.asInterface(ServiceManager.getService("window"))

        // Input service
        inputManager = IInputManager.Stub.asInterface(ServiceManager.getService("input"))

        // Display service
        displayManager = systemContext.getSystemService(DisplayManager::class.java)

        // Activity services
        activityManager = IActivityManager.Stub.asInterface(ServiceManager.getService("activity"))
        activityTaskManager = IActivityTaskManager.Stub.asInterface(ServiceManager.getService("activity_task"))

        // Package services
        packageManager = systemContext.packageManager
        iPackageManager = IPackageManager.Stub.asInterface(ServiceManager.getService("package"))

        // User service
        userManager = systemContext.getSystemService(UserManager::class.java)

        initialized = true
        XLog.d("Instances initialized in system_server context")
    }
}
