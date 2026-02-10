package io.relimus.zflow.ui.splash

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.relimus.zflow.R
import io.relimus.zflow.app.ZFlow
import io.relimus.zflow.databinding.ActivitySplashBinding
import io.relimus.zflow.service.ForegroundService
import io.relimus.zflow.service.KeepAliveService
import io.relimus.zflow.ui.main.MainActivity
import io.relimus.zflow.ui.permission.PermissionActivity
import io.relimus.zflow.utils.PermissionUtils
import io.relimus.zflow.utils.ServiceUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private lateinit var viewModel: SplashViewModel
    private lateinit var binding: ActivitySplashBinding
    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel = ViewModelProvider(this)[SplashViewModel::class.java]

        if (viewModel.getIntSp("version_privacy", -1) < ZFlow.VERSION_PRIVACY) {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.privacy_title))
                .setMessage(getString(R.string.privacy_message))
                .setPositiveButton(getString(R.string.agree)) {_, _ ->
                    viewModel.putIntSp("version_privacy", ZFlow.VERSION_PRIVACY)

                    toCheckPermission()
                }
                .setNegativeButton(getString(R.string.reject)) {_, _ ->
                    finish()
                }
                .setCancelable(false)
                .create().show()
        } else {
            toCheckPermission()
        }
    }

    private fun toCheckPermission() {
        if (checkPermission()) {
            //移除了引导界面
            showMain()
        } else {
            showPermission()
        }
    }

    /**
     * 检查Z-Flow所需要的权限
     */
    private fun checkPermission(): Boolean {
        when(viewModel.getIntSp("service_type", KeepAliveService.SERVICE_TYPE)) {
            ForegroundService.SERVICE_TYPE -> {
                if (!ServiceUtils.isServiceWork(this, "io.relimus.zflow.service.ForegroundService")) {
                    startForegroundService(Intent(this, ForegroundService::class.java))
                }
            }
        }
        return PermissionUtils.checkOverlayPermission(this)
    }

    private fun showMain() {
        scope.launch(Dispatchers.IO) {
            Thread.sleep(500)
            withContext(Dispatchers.Main) {
                if (viewModel.getBooleanSp("hide_from_recent", false)) {
                    startActivity(Intent(this@SplashActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
                } else {
                    startActivity(Intent(this@SplashActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                finish()
            }
        }
    }

    private fun showPermission() {
        scope.launch(Dispatchers.IO) {
            Thread.sleep(500)
            withContext(Dispatchers.Main) {
                startActivity(Intent(this@SplashActivity, PermissionActivity::class.java))
                finish()
            }
        }
    }
}