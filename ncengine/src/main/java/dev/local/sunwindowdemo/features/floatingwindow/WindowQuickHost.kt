package dev.local.sunwindowdemo

import android.app.NotificationManager
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Surface
import android.widget.Toast
import java.util.concurrent.Executors

/** Fixed quick-function catalog and its SystemUI-only executors. */
@Suppress("DEPRECATION")
class WindowQuickHost(
    private val context: Context,
    private val dismiss: Runnable,
    private val currentApp: Runnable,
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val wifi = context.getSystemService(WifiManager::class.java)
    private val cameras = context.getSystemService(CameraManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var lastVolume = 1
    private var torchCamera: String? = null
    private var torchOn = false
    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchCamera) torchOn = enabled
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            if (cameraId == torchCamera) torchOn = false
        }
    }

    init {
        torchCamera = findTorchCamera()
        runCatching { cameras?.registerTorchCallback(torchCallback, main) }
    }

    fun entries(): List<AppList.QuickEntry> = QuickActionCatalog.entries(context)

    private fun message(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    fun action(value: String) {
        try {
            when (value) {
                "wifi" -> {
                    val ok = wifi.setWifiEnabled(!wifi.isWifiEnabled)
                    if (!ok) message("Wi-Fi 切换未完成")
                }
                "dnd" -> notifications.setInterruptionFilter(
                    if (notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL)
                        NotificationManager.INTERRUPTION_FILTER_ALL
                    else NotificationManager.INTERRUPTION_FILTER_PRIORITY,
                )
                "rotation" -> {
                    val lock = Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) != 0
                    if (lock) Settings.System.putInt(
                        context.contentResolver,
                        Settings.System.USER_ROTATION,
                        context.display?.rotation ?: Surface.ROTATION_0,
                    )
                    Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (lock) 0 else 1)
                }
                "mute" -> {
                    val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (current > 0) lastVolume = current
                    audio.setStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        if (current > 0) 0 else lastVolume.coerceIn(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)),
                        0,
                    )
                }
                "flashlight" -> toggleTorch()
                "airplane" -> toggleAirplane()
                "autobrightness" -> {
                    val automatic = Settings.System.getInt(
                        context.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                    ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                    Settings.System.putInt(
                        context.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        if (automatic) Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL else Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
                    )
                }
                "batterysaver" -> toggleBatterySaver()
                "darkmode" -> {
                    val manager = context.getSystemService(UiModeManager::class.java)
                    val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                    manager.setNightMode(if (dark) UiModeManager.MODE_NIGHT_NO else UiModeManager.MODE_NIGHT_YES)
                }
                "current" -> currentApp.run()
                "screenshot" -> {
                    dismiss.run()
                    main.postDelayed({
                        worker.execute { if (!WindowTools.screenshot(context)) main.post { message("截屏请求未完成") } }
                    }, 240)
                }
                "record" -> {
                    dismiss.run()
                    main.postDelayed({
                        if (!WindowTools.record()) message("录屏入口暂未就绪，请先打开一次系统快捷设置")
                    }, 200)
                }
            }
        } catch (error: RuntimeException) {
            android.util.Log.w("NativeComplements", "QUICK_ACTION_FAILED $value", error)
            message("操作未完成，请稍后重试")
        }
    }

    private fun findTorchCamera(): String? {
        val manager = cameras ?: return null
        return try {
            val flash = manager.cameraIdList.filter { id ->
                runCatching {
                    manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }.getOrDefault(false)
            }
            flash.firstOrNull { id ->
                runCatching {
                    manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                }.getOrDefault(false)
            } ?: flash.firstOrNull()
        } catch (_: CameraAccessException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun toggleTorch() {
        val manager = cameras
        val id = torchCamera
        if (manager == null || id == null) {
            message("此设备没有可用闪光灯")
            return
        }
        try {
            manager.setTorchMode(id, !torchOn)
        } catch (_: CameraAccessException) {
            message("手电筒暂不可操作")
        } catch (_: RuntimeException) {
            message("手电筒暂不可操作")
        }
    }

    private fun toggleAirplane() {
        val enable = Settings.Global.getInt(context.contentResolver, "airplane_mode_on", 0) == 0
        val manager = connectivity
        if (manager != null) {
            try {
                val method = ConnectivityManager::class.java.getDeclaredMethod("setAirplaneMode", java.lang.Boolean.TYPE)
                method.isAccessible = true
                method.invoke(manager, enable)
                return
            } catch (_: Throwable) {
                // Fall through to the secure-settings path used by older frameworks.
            }
        }
        try {
            Settings.Global.putInt(context.contentResolver, "airplane_mode_on", if (enable) 1 else 0)
            context.sendBroadcast(Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", enable))
        } catch (_: RuntimeException) {
            message("飞行模式切换未完成")
        }
    }

    private fun toggleBatterySaver() {
        val manager = context.getSystemService(PowerManager::class.java)
        val enable = !manager.isPowerSaveMode
        try {
            val method = PowerManager::class.java.getDeclaredMethod("setPowerSaveModeEnabled", java.lang.Boolean.TYPE)
            method.isAccessible = true
            method.invoke(manager, enable)
            return
        } catch (_: Throwable) {
            // The setting is observed by BatterySaverController on the system side.
        }
        try {
            Settings.Global.putInt(context.contentResolver, "low_power_mode", if (enable) 1 else 0)
            context.sendBroadcast(Intent("android.os.action.POWER_SAVE_MODE_CHANGED"))
            main.postDelayed({ if (manager.isPowerSaveMode != enable) message("省电模式切换未完成") }, 650)
        } catch (_: RuntimeException) {
            message("省电模式切换未完成")
        }
    }

    override fun close() {
        runCatching { cameras?.unregisterTorchCallback(torchCallback) }
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
    }
}
