package io.relimus.zflow.app

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.UserManager
import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.google.android.material.color.DynamicColors
import io.relimus.zflow.service.FreeformManagerProxy
import io.relimus.zflow.xposed.hook.utils.XLog
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Application class for Z-Flow
 * Now uses Xposed-based FreeformManager instead of Shizuku.
 */
class ZFlow : Application() {
    private val TAG = "ZFlowApp"

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
        // 说明：微信小窗标题栏重复、小窗顶部空白、Telegram 画中画三项修复
        // 已从本项目移出，改由独立模块 Z-Flow-Compat-Patch 提供。

        /** 桌面长按 → 打开小窗 入口开关 */
        const val KEY_POPUP_FREEFORM_ENABLE = "popup_freeform_enable"

        /** 屏蔽桌面长按 → 消息气泡（Android 17+）开关 */
        const val KEY_POPUP_BUBBLE_BLOCK = "popup_bubble_block"
    }

    override fun onCreate() {
        super.onCreate()
        me = this

        /*
         * 锁屏 / 开机阶段返回：此时用户存储还没解密，任何 getSharedPreferences 都会抛
         * IllegalStateException。实机日志里 PrismSpace 在解锁前被拉起就因此 FATAL；
         * 我们自己的进程同样会被 UID observer / provider 投递拽起来，不能在这个阶段
         * 碰用户存储。MainActivity 挂在 credential encrypted（默认）storage 上，
         * 锁屏时系统本来就不会拉起任何界面，所以这里只需要「什么都不做」。
         */
        if (!isUserUnlockedNow()) {
            XLog.d("$TAG: storage locked at onCreate, defer app init")
            return
        }

        DynamicColors.applyToActivitiesIfAvailable(this)

        // Check if FreeformManager is connected
        checkServiceConnection()
    }

    private fun isUserUnlockedNow(): Boolean = runCatching {
        getSystemService(UserManager::class.java)?.isUserUnlocked == true
    }.getOrDefault(false)

    /**
     * Check if FreeformManager service is connected via Xposed.
     * The binder link is established automatically when the app starts
     * via UserService in system_server.
     */
    private fun checkServiceConnection() {
        Handler(mainLooper).postDelayed({
            isRunning.postValue(
                FreeformManagerProxy.isConnected && FreeformManagerProxy.isServiceReady()
            )
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