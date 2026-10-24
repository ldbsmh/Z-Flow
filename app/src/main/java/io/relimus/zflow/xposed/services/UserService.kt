package io.relimus.zflow.xposed.services

import android.app.ActivityManagerHidden
import android.content.AttributionSource
import android.content.pm.IPackageManager
import android.os.Bundle
import android.os.ServiceManager
import io.relimus.zflow.BuildConfig
import io.relimus.zflow.xposed.hook.utils.XLog
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

    // 上一次投递的时间，用于去抖：onUidStateChanged 触发非常频繁，不能每次都走 Binder 投递。
    @Volatile
    private var lastDeliverAt = 0L
    private const val MIN_DELIVER_INTERVAL_MS = 2_000L

    /**
     * 向 app 进程投递 FreeformManager binder。
     *
     * 关键是**幂等可重入**：app 被强行停止后进程 UID 不变、binder 已随旧进程失效，
     * 但系统不会再为同一个 UID 触发一次 onUidActive（UID 一直被认为是活跃的）。
     * 只在 onUidActive 投递会导致「强停后重开 → isConnected 永远为 false →
     * 所有小窗入口报『Z-Flow 服务未就绪』」。
     */
    private fun deliverBinder() {
        lastDeliverAt = android.os.SystemClock.elapsedRealtime()
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

    private val uidObserver = object : UidObserverAdapter() {
        override fun onUidActive(uid: Int) {
            if (uid != appUid) return
            XLog.d("$TAG: onUidActive uid=$uid, delivering binder")
            deliverBinder()
        }

        /**
         * 进程状态每次变化都补投一次 binder，覆盖 onUidActive 漏掉的路径：
         * app 强停后重启、进程被系统回收后重建等。app 侧 [FreeformManagerProxy.linkService]
         * 是幂等的（重新建 proxy 并 linkToDeath），重复投递无副作用。
         */
        override fun onUidStateChanged(
            uid: Int,
            procState: Int,
            procStateSeq: Long,
            capability: Int
        ) {
            if (uid != appUid) return
            // 只做时间间隔去抖，**不**按 procState 值去重：
            // 强停后重启的进程状态可能与上次完全相同（例如都是 CACHED），
            // 按值去重会把真正需要的那次补投也挡掉，兜底就失效了。
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastDeliverAt < MIN_DELIVER_INTERVAL_MS) return
            XLog.d("$TAG: onUidStateChanged uid=$uid state=$procState, delivering binder")
            deliverBinder()
        }
    }

    fun register(pms: IPackageManager) {
        XLog.d("$TAG: Initializing UserService")
        // shortcut: 仅支持主用户(user 0)，工作资料内安装时无法投递；跨用户方案待实际需求再加
        appUid = pms.getPackageUid(BuildConfig.APPLICATION_ID, 0L, 0)
        XLog.d("$TAG: App UID = $appUid")

        waitActivityService()
        ActivityManagerApis.registerUidObserver(
            uidObserver,
            // ACTIVE 覆盖首次变活跃；PROCSTATE 覆盖进程状态每次变化——
            // app 强停后重启时 UID 一直“活跃”，只有状态变化能再次触发投递。
            ActivityManagerHidden.UID_OBSERVER_ACTIVE or
                ActivityManagerHidden.UID_OBSERVER_PROCSTATE,
            ActivityManagerHidden.PROCESS_STATE_UNKNOWN,
            null
        )
        XLog.d("$TAG: UID observer registered")
    }

    private fun waitActivityService() {
        // 异常 ROM 上无限等待会卡死投递线程；等不到就放弃，后续 UID 观察不会注册
        val deadline = android.os.SystemClock.elapsedRealtime() + 120_000L
        while (ServiceManager.getService("activity") == null) {
            if (android.os.SystemClock.elapsedRealtime() > deadline) {
                XLog.e("$TAG: activity service not published after 120s, give up waiting")
                return
            }
            Thread.sleep(1000)
        }
    }
}
