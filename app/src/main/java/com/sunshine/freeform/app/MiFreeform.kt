package com.sunshine.freeform.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.MutableLiveData
import com.google.android.material.color.DynamicColors
import com.sunshine.freeform.service.FreeformManagerProxy
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Application class for Mi-Freeform.
 * Now uses Xposed-based FreeformManager instead of Shizuku.
 */
class MiFreeform : Application() {
    val isRunning = MutableLiveData(false)

    /**
     * Client-side proxy for FreeformManager service running in system_server.
     */
    val freeformManagerProxy: FreeformManagerProxy
        get() = FreeformManagerProxy

    companion object {
        lateinit var me: MiFreeform
        private const val TAG = "MiFreeForm"
        const val PACKAGE_NAME = "com.sunshine.freeform"
        const val VERSION = 1
        const val VERSION_PRIVACY = 1
        const val APP_SETTINGS_NAME = "app_settings"

        private val log = StringBuilder()

        fun addLog(tag: String, functionName: String, e: Exception) {
            log.append("$tag,$functionName:${e.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        me = this

        DynamicColors.applyToActivitiesIfAvailable(this)

        // Check if FreeformManager is connected
        checkServiceConnection()
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        HiddenApiBypass.addHiddenApiExemptions("")
    }

    /**
     * Check if FreeformManager service is connected via Xposed.
     * The binder link is established automatically when the app starts
     * via UserService in system_server.
     */
    private fun checkServiceConnection() {
        // Give some time for the binder link to be established
        android.os.Handler(mainLooper).postDelayed({
            if (FreeformManagerProxy.isConnected) {
                isRunning.postValue(true)
            } else {
                isRunning.postValue(false)
            }
        }, 500)
    }

    /**
     * Check if the service is ready.
     * For compatibility with existing code that checked Shizuku status.
     */
    fun isServiceReady(): Boolean {
        return FreeformManagerProxy.isConnected && FreeformManagerProxy.isServiceReady
    }

    /**
     * Retry connection check.
     * Called when UI wants to refresh status.
     */
    fun recheckConnection() {
        checkServiceConnection()
    }

    interface ShizukuBindCallback {
        fun onBind()
    }

    @Deprecated("Use FreeformManagerProxy directly", ReplaceWith("FreeformManagerProxy"))
    fun initShizuku() {
        // No-op, service is connected via Xposed automatically
        recheckConnection()
    }

    @Deprecated("Use FreeformManagerProxy directly", ReplaceWith("FreeformManagerProxy"))
    fun initShizuku(callback: ShizukuBindCallback) {
        recheckConnection()
        if (FreeformManagerProxy.isConnected) {
            callback.onBind()
        }
    }

    @Deprecated("No longer needed with Xposed implementation")
    fun pingServiceBinder(): Boolean {
        return FreeformManagerProxy.isConnected
    }

    @Deprecated("Shell execution moved to FreeformManager")
    fun execShell(command: String, useRoot: Boolean): Boolean {
        // Shell execution is no longer supported in the same way
        // Tasks should be performed via FreeformManager AIDL methods
        return false
    }
}
