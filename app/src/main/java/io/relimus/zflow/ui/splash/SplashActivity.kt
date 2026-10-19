package io.relimus.zflow.ui.splash

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.relimus.zflow.R
import io.relimus.zflow.app.ZFlow
import io.relimus.zflow.databinding.ActivitySplashBinding
import io.relimus.zflow.service.ForegroundService
import io.relimus.zflow.service.KeepAliveService
import io.relimus.zflow.ui.main.MainActivity
import io.relimus.zflow.ui.permission.PermissionActivity
import io.relimus.zflow.utils.PermissionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 启动页：隐私协议确认 → 启动常驻服务 → 检查悬浮窗权限 → 进入主界面。
 *
 * 相比旧实现的两处修正：
 * 1. 后台延时任务跑在 lifecycleScope 里，Activity 销毁（比如用户立刻返回）
 *    时协程会随之取消，不会再去操作已销毁的界面；
 * 2. 前台服务的启动放在 try-catch 中：用户关闭后台启动权限时
 *    startForegroundService 会抛 ForegroundServiceStartNotAllowedException，
 *    旧代码会直接崩溃。
 */
@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private lateinit var viewModel: SplashViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel = ViewModelProvider(this)[SplashViewModel::class.java]

        if (viewModel.getIntSp("version_privacy", -1) < ZFlow.VERSION_PRIVACY) {
            showPrivacyDialog()
        } else {
            toCheckPermission()
        }
    }

    private fun showPrivacyDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.privacy_title))
            .setMessage(getString(R.string.privacy_message))
            .setPositiveButton(getString(R.string.agree)) { _, _ ->
                viewModel.putIntSp("version_privacy", ZFlow.VERSION_PRIVACY)
                toCheckPermission()
            }
            .setNegativeButton(getString(R.string.reject)) { _, _ ->
                finish()
            }
            .setCancelable(false)
            .create()
            .show()
    }

    private fun toCheckPermission() {
        // 权限检查会读 SharedPreferences 并启动服务，放在后台执行避免掉帧
        lifecycleScope.launch {
            // 服务启动必须在主线程（会绑定 Activity 的过程），但异常要吞掉
            if (viewModel.getIntSp("service_type", KeepAliveService.SERVICE_TYPE) ==
                ForegroundService.SERVICE_TYPE && !ForegroundService.isRunning
            ) {
                startForegroundServiceSafely()
            }
            val granted = withContext(Dispatchers.IO) {
                PermissionUtils.checkOverlayPermission(this@SplashActivity)
            }
            delay(SPLASH_DELAY_MS)
            showNext(granted)
        }
    }

    private fun startForegroundServiceSafely() {
        try {
            startForegroundService(Intent(this, ForegroundService::class.java))
        } catch (e: Exception) {
            // 后台启动被系统限制（Android 12+ 后台启动限制 / 用户关闭自启动）
            android.util.Log.w(TAG, "startForegroundService failed", e)
        }
    }

    private fun showNext(granted: Boolean) {
        if (isFinishing || isDestroyed) return
        val target = if (granted) MainActivity::class.java else PermissionActivity::class.java
        val intent = Intent(this, target)
        if (viewModel.getBooleanSp("hide_from_recent", false)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        finish()
    }

    companion object {
        private const val TAG = "SplashActivity"
        private const val SPLASH_DELAY_MS = 500L
    }
}
