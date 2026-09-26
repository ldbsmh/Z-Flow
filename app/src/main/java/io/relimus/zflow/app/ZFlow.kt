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

        // ===== 兼容性修复开关 =====
        // 这两个 key 同时被以下位置引用，改动需一致：
        //   - res/xml/settings.xml（开关项）
        //   - NotificationSettingsProvider（跨进程白名单）
        //   - WeChatStatusBarFix / HookStatusBarDimenBridge（读取并生效）
        const val KEY_HOOK_WECHAT_STATUSBAR = "hook_wechat_statusbar_fix"
        const val KEY_HOOK_STATUSBAR_DIMEN = "hook_statusbar_dimen"
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
        Handler(mainLooper).postDelayed({
            isRunning.postValue(FreeformManagerProxy.isConnected)
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