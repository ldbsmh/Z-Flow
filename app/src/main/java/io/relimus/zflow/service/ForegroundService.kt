package io.relimus.zflow.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import io.relimus.zflow.R
import io.relimus.zflow.app.ZFlow
import io.relimus.zflow.ui.floating.FloatingActivity
import io.relimus.zflow.utils.cast

/**
 * 前台服务保活模式。
 *
 * 悬浮按钮 / 应用选择浮层的全部逻辑已移至 [FloatingWindowController]，
 * 本类只负责：前台通知、Service 生命周期、以及启动时校验当前
 * 保活模式是否确实是前台服务。
 */
class ForegroundService : Service(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var sp: SharedPreferences
    private lateinit var floatingController: FloatingWindowController

    override fun onCreate() {
        super.onCreate()

        sp = getSharedPreferences(ZFlow.APP_SETTINGS_NAME, MODE_PRIVATE)
        sp.registerOnSharedPreferenceChangeListener(this)

        if (sp.getInt("service_type", KeepAliveService.SERVICE_TYPE) != SERVICE_TYPE) {
            // 不是前台服务模式，关闭自己
            stopSelf()
            return
        }

        isRunning = true
        floatingController = FloatingWindowController(this)
        floatingController.attach()
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE).cast<NotificationManager>()
        val notificationIntent = Intent(this, FloatingActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this.applicationContext, CHANNEL_ID)

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.foreground_notification_name),
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)

        builder.setContentIntent(pendingIntent)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setContentTitle(getString(R.string.foreground_notification_title))
            .setContentText(getString(R.string.foreground_notification_text))
            .setSmallIcon(R.drawable.tile_icon)
            .setWhen(System.currentTimeMillis())
        val notification = builder.build()
        notification.flags = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(3, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(3, notification)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        if (::floatingController.isInitialized) floatingController.detach()
        runCatching { sp.unregisterOnSharedPreferenceChangeListener(this) }
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        // 悬浮窗相关 key 由 FloatingWindowController 自行监听
        if (key == "service_type" &&
            sp.getInt("service_type", KeepAliveService.SERVICE_TYPE) != SERVICE_TYPE
        ) {
            stopSelf()
        }
    }

    companion object {
        private const val CHANNEL_ID = "CHANNEL_ID_SUNSHINE_FREEFORM_FOREGROUND"
        const val SERVICE_TYPE = 1

        @Volatile
        var isRunning = false
            private set
    }
}
