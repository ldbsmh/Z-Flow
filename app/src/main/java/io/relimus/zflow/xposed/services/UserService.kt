package io.relimus.zflow.xposed.services

import android.app.ActivityManagerHidden
import android.content.AttributionSource
import android.content.pm.IPackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.ServiceManager
import io.relimus.zflow.BuildConfig
import io.relimus.zflow.xposed.hook.utils.XLog
import rikka.hidden.compat.ActivityManagerApis
import rikka.hidden.compat.adapter.UidObserverAdapter

/**
 * 在 system_server 里把「小窗后端」的 binder 投递给 Z-Flow 应用进程。
 *
 * 新后端的 binder 不再管理虚拟屏（旧实现是 VirtualDisplay + IFreeformManager AIDL），
 * 而是一个只负责「打开 / 关闭 / 状态」的入口：
 * [io.relimus.zflow.xposed.nativewin.OpenPort]。窗口本身由 system_server 直接把
 * 目标应用的**真实任务**接管成系统 freeform 小窗。
 *
 * 投递必须是幂等可重入的：app 被停掉后进程 UID 不变、binder 已随旧进程失效，
 * 系统不会再触发 onUidActive，因此这里额外用 PROCSTATE 变化兜底。
 */
object UserService {
    private const val TAG = "ZFlowUserService"
    const val PROVIDER_AUTHORITY = "io.relimus.zflow.freeform.provider"

    /** 由 [io.relimus.zflow.xposed.nativewin.ZFlowWindowModule] 在 systemReady 后设置。 */
    @Volatile
    var windowBackend: IBinder? = null
        private set

    /** Java 侧（libxposed 模块）的入口：登记窗口后端并立即尝试投递。 */
    fun setWindowBackend(binder: IBinder?) {
        windowBackend = binder
        if (binder != null) runCatching { deliverBinder() }
    }

    private var appUid = -1

    @Volatile
    private var lastDeliverAt = 0L
    private const val MIN_DELIVER_INTERVAL_MS = 2_000L

    private fun deliverBinder() {
        val binder = windowBackend ?: return
        lastDeliverAt = android.os.SystemClock.elapsedRealtime()
        try {
            // 4 个参数：authority、userId、taskId、processName
            val provider = ActivityManagerApis.getContentProviderExternal(
                PROVIDER_AUTHORITY,
                0,
                null,
                null,
            )
            if (provider == null) {
                XLog.e("$TAG: Failed to get content provider")
                return
            }
            val extras = Bundle().apply { putBinder("binder", binder) }
            val attr = AttributionSource.Builder(1000).setPackageName("android").build()
            val reply = provider.call(attr, PROVIDER_AUTHORITY, "", null, extras)
            if (reply == null) {
                XLog.e("$TAG: Failed to send window backend binder to app")
                return
            }
            XLog.d("$TAG: window backend binder delivered")
        } catch (e: Throwable) {
            XLog.e("$TAG: Failed to send window backend binder", e)
        }
    }

    private val uidObserver = object : UidObserverAdapter() {
        override fun onUidActive(uid: Int) {
            if (uid != appUid) return
            deliverBinder()
        }

        override fun onUidStateChanged(
            uid: Int,
            procState: Int,
            procStateSeq: Long,
            capability: Int
        ) {
            if (uid != appUid) return
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastDeliverAt < MIN_DELIVER_INTERVAL_MS) return
            deliverBinder()
        }
    }

    fun register(pms: IPackageManager) {
        XLog.d("$TAG: Initializing UserService")
        appUid = pms.getPackageUid(BuildConfig.APPLICATION_ID, 0L, 0)
        waitActivityService()
        ActivityManagerApis.registerUidObserver(
            uidObserver,
            // ACTIVE 覆盖首次变活跃；PROCSTATE 覆盖进程状态每次变化——
            // app 强停后重启时 UID 一直"活跃"，只有状态变化能再次触发投递。
            ActivityManagerHidden.UID_OBSERVER_ACTIVE or
                ActivityManagerHidden.UID_OBSERVER_PROCSTATE,
            ActivityManagerHidden.PROCESS_STATE_UNKNOWN,
            null,
        )
        XLog.d("$TAG: UID observer registered, appUid=$appUid")
    }

    private fun waitActivityService() {
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
