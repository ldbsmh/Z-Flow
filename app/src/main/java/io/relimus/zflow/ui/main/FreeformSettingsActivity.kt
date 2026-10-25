package io.relimus.zflow.ui.main

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.commit
import io.relimus.zflow.R

class FreeformSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_freeform_settings)

        // 主题是透明状态栏：裸 FrameLayout 会顶到屏幕上沿，
        // 按系统栏（含刘海）留出顶部安全距离，再多给 8dp 呼吸空间
        val container = findViewById<View>(R.id.fragment_container)
        val extraTop = (8 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top + extraTop, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val rootKey = intent.getStringExtra("root_key") ?: "pref_trigger_settings"

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(
                    R.id.fragment_container,
                    SettingFragment.newInstance(rootKey)
                )
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}