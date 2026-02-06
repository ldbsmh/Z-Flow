package com.sunshine.freeform.utils

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.WindowManager

/**
 * @date 2021/2/1
 * Service utility class - now uses FreeformManagerProxy for privileged operations.
 * @deprecated Most service access is now handled via Xposed-based FreeformManager.
 */
object ServiceUtils {
    lateinit var displayManager: DisplayManager
    lateinit var windowManager: WindowManager

    fun init(context: Context) {
        displayManager = context.getSystemService(DisplayManager::class.java)
        windowManager = context.getSystemService(WindowManager::class.java)
    }

    /**
     * Check if a service is running.
     *
     * @param mContext Context
     * @param serviceName Full service class name (e.g., net.loonggg.testbackstage.TestService)
     * @return true if running, false otherwise
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
