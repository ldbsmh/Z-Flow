package io.relimus.zflow.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.content.edit
import io.relimus.zflow.R
import io.relimus.zflow.app.ZFlow
import io.relimus.zflow.broadcast.StartFreeformReceiver
import io.relimus.zflow.utils.cast

/**
 * 通过无障碍进行保活。
 *
 * 悬浮按钮 / 应用选择浮层的全部逻辑已移至 [FloatingWindowController]，
 * 本类只负责：无障碍服务生命周期、1x1 保活层、以及配置变更时
 * 在无障碍模式与前台服务模式之间切换。
 */
@SuppressLint("AccessibilityPolicy")
class KeepAliveService : AccessibilityService(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var sp: SharedPreferences
    private lateinit var windowManager: WindowManager
    private lateinit var floatingController: FloatingWindowController

    private var startFreeformReceiver = StartFreeformReceiver()

    // 无障碍 1x1 保活 View
    private var aliveView: View? = null

    // 启动状态
    private var connected = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val connectTimeoutRunnable = Runnable {
        if (!connected) {
            Toast.makeText(
                this,
                getString(R.string.accessibility_start_timeout),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        isServiceRunning = false
        connected = false
        sp = getSharedPreferences(ZFlow.APP_SETTINGS_NAME, MODE_PRIVATE)
        mainHandler.postDelayed(connectTimeoutRunnable, 3000)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        connected = true
        isServiceRunning = true
        instance = this
        mainHandler.removeCallbacks(connectTimeoutRunnable)

        sp.registerOnSharedPreferenceChangeListener(this)

        // 切换为无障碍模式
        if (sp.getInt("service_type", SERVICE_TYPE) != SERVICE_TYPE) {
            sp.edit { putInt("service_type", SERVICE_TYPE) }
        }

        stopService(Intent(this, ForegroundService::class.java))

        registerReceiver(
            startFreeformReceiver,
            IntentFilter("io.relimus.zflow.start_freeform"),
            RECEIVER_EXPORTED
        )

        windowManager = getSystemService(WINDOW_SERVICE).cast()

        // 添加 1x1 无障碍保活层
        addAliveOverlayView()

        floatingController = FloatingWindowController(this)
        floatingController.attach()

        Toast.makeText(this, getString(R.string.accessibility_start), Toast.LENGTH_SHORT).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // 当前仅用于保活和悬浮功能，不处理具体无障碍事件
    }

    override fun onInterrupt() {
        // no-op
    }

    override fun onDestroy() {
        super.onDestroy()

        connected = false
        isServiceRunning = false
        instance = null
        mainHandler.removeCallbacks(connectTimeoutRunnable)

        if (::floatingController.isInitialized) floatingController.detach()
        removeAliveOverlayView()

        runCatching { sp.unregisterOnSharedPreferenceChangeListener(this) }
        runCatching { unregisterReceiver(startFreeformReceiver) }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        // 悬浮窗相关 key 由 FloatingWindowController 自行监听；
        // 这里只处理模式切换。
        if (key == "service_type" &&
            sp.getInt("service_type", SERVICE_TYPE) == SERVICE_TYPE
        ) {
            stopService(Intent(this, ForegroundService::class.java))
        }
    }

    /**
     * 添加 1x1 无障碍保活层
     */
    private fun addAliveOverlayView() {
        removeAliveOverlayView()

        val tempView = View(this)
        val lp = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            gravity = Gravity.START or Gravity.TOP
            width = 1
            height = 1
            packageName = this@KeepAliveService.packageName
        }

        try {
            windowManager.addView(tempView, lp)
            aliveView = tempView
        } catch (_: Throwable) {
            aliveView = null
            Toast.makeText(
                this,
                getString(R.string.accessibility_keep_alive_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * 移除 1x1 无障碍保活层
     */
    private fun removeAliveOverlayView() {
        try {
            aliveView?.let { windowManager.removeView(it) }
        } catch (_: Throwable) {
        }
        aliveView = null
    }

    companion object {
        const val SERVICE_TYPE = 0

        @Volatile
        var instance: KeepAliveService? = null
            private set

        @Volatile
        var isServiceRunning = false
            private set
    }
}
