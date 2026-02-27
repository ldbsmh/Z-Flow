package io.relimus.zflow.service

import android.annotation.SuppressLint
import android.content.Intent
import android.service.quicksettings.TileService
import io.relimus.zflow.ui.floating.FloatingActivity
import kotlinx.coroutines.DelicateCoroutinesApi

/**
 * @author sunshine
 * @date 2021/2/27
 * 通知栏快捷磁贴服务
 */
@DelicateCoroutinesApi
class QuickStartTileService : TileService() {

    override fun onClick() {
        super.onClick()
        startActivity(
            Intent(
                this,
                FloatingActivity::class.java
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        collapseStatusBar()
    }

    @SuppressLint("WrongConstant")
    private fun collapseStatusBar() {
        FreeformManagerProxy.collapseStatusBarPanel()
    }
}
