package io.relimus.zflow.app

import android.app.Application
import android.content.Context
import android.os.Handler
import androidx.lifecycle.MutableLiveData
import com.google.android.material.color.DynamicColors
import io.relimus.zflow.service.FreeformManagerProxy
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Application class for Z-Flow
 * Now uses Xposed-based FreeformManager instead of Shizuku.
 */
class ZFlow : Application() {
    val isRunning = MutableLiveData(false)

    /**
     * Client-side proxy for FreeformManager service running in system_server.
     */
    val freeformManagerProxy: FreeformManagerProxy
        get() = FreeformManagerProxy

    companion object {
        lateinit var me: ZFlow
        const val PACKAGE_NAME = "io.relimus.zflow"
        const val VERSION_PRIVACY = 1
        const val APP_SETTINGS_NAME = "app_settings"
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
        Handler(mainLooper).postDelayed({
            if (FreeformManagerProxy.isConnected) {
                isRunning.postValue(true)
            } else {
                isRunning.postValue(false)
            }
        }, 500)
    }

    /**
     * Retry connection check.
     * Called when UI wants to refresh status.
     */
    fun recheckConnection() {
        checkServiceConnection()
    }
}
