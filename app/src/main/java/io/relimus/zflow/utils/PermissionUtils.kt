package io.relimus.zflow.utils

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.provider.Settings.SettingNotFoundException
import android.text.TextUtils.SimpleStringSplitter
import androidx.core.app.ActivityCompat
import io.relimus.zflow.service.KeepAliveService

/**
 * @date 2022/8/26
 * @author sunshine0523
 */
object PermissionUtils {

    fun checkNotificationListenerPermission(context: Context): Boolean {
        var enable = false
        val flat =
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        if (flat != null) {
            enable = flat.contains(context.packageName)
        }
        return enable
    }

    fun checkOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun isAccessibilitySettingsOn(context: Context): Boolean {
        val componentName = ComponentName(
            context,
            KeepAliveService::class.java
        )
    
        val expectedFull = componentName.flattenToString()
        val expectedShort = componentName.flattenToShortString()
        val expectedLegacy = context.packageName + "/io.relimus.zflow.service.KeepAliveService"
    
        val accessibilityEnabled = try {
            Settings.Secure.getInt(
                context.applicationContext.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED
            )
        } catch (_: SettingNotFoundException) {
            0
        }
    
        if (accessibilityEnabled != 1) {
            return false
        }
    
        val settingValue = Settings.Secure.getString(
            context.applicationContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
    
        val splitter = SimpleStringSplitter(':')
        splitter.setString(settingValue)
    
        while (splitter.hasNext()) {
            val enabledService = splitter.next()
    
            if (
                enabledService.equals(expectedFull, ignoreCase = true) ||
                enabledService.equals(expectedShort, ignoreCase = true) ||
                enabledService.equals(expectedLegacy, ignoreCase = true)
            ) {
                return true
            }
        }
    
        return false
    }
    
    fun checkPostNotificationPermission(activity: Activity) {
        if (ActivityCompat.checkSelfPermission(
                activity,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_DENIED
        ) {
            ActivityCompat.requestPermissions(
                activity,
                listOf(Manifest.permission.POST_NOTIFICATIONS).toTypedArray(),
                100
            )
        }
    }
}