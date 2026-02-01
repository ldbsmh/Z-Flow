package com.sunshine.freeform.utils

import android.app.ActivityManager
import android.app.IActivityManager
import android.app.IActivityTaskManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.input.IInputManager
import android.os.ServiceManager
import android.view.IWindowManager
import android.view.WindowManager

/**
 * Service utilities - uses direct ServiceManager access.
 * Requires Xposed hook on InputManagerService to bypass permission checks.
 */
object ServiceUtils {
    lateinit var activityManager: IActivityManager
    lateinit var activityTaskManager: IActivityTaskManager
    lateinit var displayManager: DisplayManager
    lateinit var windowManager: WindowManager
    lateinit var iWindowManager: IWindowManager
    lateinit var inputManager: IInputManager

    private var initialized = false

    val isInitialized: Boolean
        get() = initialized

    fun init(context: Context) {
        if (initialized) return

        activityManager = IActivityManager.Stub.asInterface(
            ServiceManager.getService("activity")
        )
        activityTaskManager = IActivityTaskManager.Stub.asInterface(
            ServiceManager.getService("activity_task")
        )
        displayManager = context.getSystemService(DisplayManager::class.java)
        windowManager = context.getSystemService(WindowManager::class.java)
        iWindowManager = IWindowManager.Stub.asInterface(
            ServiceManager.getService("window")
        )
        inputManager = IInputManager.Stub.asInterface(
            ServiceManager.getService("input")
        )

        initialized = true
    }

    /**
     * 判断某个服务是否正在运行的方法
     *
     * @param mContext
     * @param serviceName 是包名+服务的类名（例如：net.loonggg.testbackstage.TestService）
     * @return true代表正在运行，false代表服务没有正在运行
     */
    fun isServiceWork(mContext: Context, serviceName: String): Boolean {
        var isWork = false
        val myAM = mContext
                .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val myList: List<ActivityManager.RunningServiceInfo> = myAM.getRunningServices(40)
        if (myList.isEmpty()) {
            return false
        }
        for (i in myList.indices) {
            val mName: String = myList[i].service.className
            myList[i].service.className
            if (mName == serviceName) {
                isWork = true
                break
            }
        }
        return isWork
    }
}