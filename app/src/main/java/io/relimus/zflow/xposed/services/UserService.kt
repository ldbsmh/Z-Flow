package io.relimus.zflow.xposed.services

import android.app.ActivityManagerHidden
import android.content.AttributionSource
import android.content.pm.IPackageManager
import android.os.Bundle
import android.os.ServiceManager
import io.relimus.zflow.BuildConfig
import io.relimus.zflow.hook.utils.XLog
import rikka.hidden.compat.ActivityManagerApis
import rikka.hidden.compat.adapter.UidObserverAdapter

/**
 * UserService runs in system_server to establish binder link with app process.
 * Uses UID observer pattern to detect when app becomes active and sends FreeformManager binder.
 */
object UserService {
    private const val TAG = "FreeformUserService"
    const val PROVIDER_AUTHORITY = "io.relimus.zflow.freeform.provider"

    private var appUid = -1

    private val uidObserver = object : UidObserverAdapter() {
        override fun onUidActive(uid: Int) {
            if (uid != appUid) return
            try {
                val provider = ActivityManagerApis.getContentProviderExternal(
                    PROVIDER_AUTHORITY,
                    0,
                    null,
                    null
                )
                if (provider == null) {
                    XLog.e("$TAG: Failed to get content provider")
                    return
                }
                val extras = Bundle()
                extras.putBinder("binder", FreeformManager)
                val attr = AttributionSource.Builder(1000).setPackageName("android").build()
                val reply = provider.call(attr, PROVIDER_AUTHORITY, "", null, extras)
                if (reply == null) {
                    XLog.e("$TAG: Failed to send binder to app")
                    return
                }
                XLog.d("$TAG: Send binder to app successfully")
            } catch (e: Throwable) {
                XLog.e("$TAG: Failed to send binder to app", e)
            }
        }
    }

    fun register(pms: IPackageManager) {
        XLog.d("$TAG: Initializing UserService")
        appUid = pms.getPackageUid(BuildConfig.APPLICATION_ID, 0L, 0)
        XLog.d("$TAG: App UID = $appUid")

        waitSystemService("activity")
        ActivityManagerApis.registerUidObserver(
            uidObserver,
            ActivityManagerHidden.UID_OBSERVER_ACTIVE,
            ActivityManagerHidden.PROCESS_STATE_UNKNOWN,
            null
        )
        XLog.d("$TAG: UID observer registered")
    }

    private fun waitSystemService(name: String) {
        while (ServiceManager.getService(name) == null) {
            Thread.sleep(1000)
        }
    }
}
