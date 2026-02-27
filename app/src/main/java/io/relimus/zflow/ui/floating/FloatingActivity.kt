package io.relimus.zflow.ui.floating

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import io.relimus.zflow.R
import io.relimus.zflow.app.ZFlow
import io.relimus.zflow.service.ForegroundService
import io.relimus.zflow.service.KeepAliveService
import io.relimus.zflow.utils.PermissionUtils

/**
 * 通过活动打开应用选择
 */
class FloatingActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_floating)

        val sp = getSharedPreferences(ZFlow.APP_SETTINGS_NAME, MODE_PRIVATE)
        when (sp.getInt("service_type", KeepAliveService.SERVICE_TYPE)) {
            KeepAliveService.SERVICE_TYPE -> {
                if (PermissionUtils.isAccessibilitySettingsOn(this)) {
                    sp.edit {
                        putBoolean(
                            "to_show_floating",
                            !sp.getBoolean("to_show_floating", false)
                        )
                    }
                } else {
                    Toast.makeText(this, getString(R.string.require_accessibility), Toast.LENGTH_SHORT).show()
                }
            }
            ForegroundService.SERVICE_TYPE -> {
                if (ForegroundService.isRunning) {
                    sp.edit {putBoolean("to_show_floating", !sp.getBoolean("to_show_floating", false))}
                } else {
                    startForegroundService(Intent(this, ForegroundService::class.java))
                    if (ForegroundService.isRunning) {
                        sp.edit { putBoolean("to_show_floating", !sp.getBoolean("to_show_floating", false))}
                    } else {
                        Toast.makeText(this, getString(R.string.require_foreground), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        finish()
    }
}