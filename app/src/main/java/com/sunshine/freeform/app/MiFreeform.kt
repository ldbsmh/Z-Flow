package com.sunshine.freeform.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.MutableLiveData
import com.google.android.material.color.DynamicColors
import com.sunshine.freeform.utils.ServiceUtils
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Application class - manages service initialization.
 * Uses direct ServiceManager access with Xposed hooks for privileged operations.
 */
class MiFreeform : Application() {
    val isRunning = MutableLiveData(false)

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
        initServices()
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        HiddenApiBypass.addHiddenApiExemptions("")
    }

    fun initServices() {
        try {
            ServiceUtils.init(this)
            isRunning.postValue(true)
        } catch (e: Exception) {
            addLog(TAG, "initServices", e)
            isRunning.postValue(false)
        }
    }

    fun execShell(command: String, useRoot: Boolean): Boolean {
        return try {
            val process = if (useRoot) {
                Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            } else {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            }
            process.waitFor() == 0
        } catch (e: Exception) {
            addLog(TAG, "execShell", e)
            false
        }
    }
}
