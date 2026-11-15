package io.relimus.zflow.service

import android.annotation.SuppressLint
import android.service.quicksettings.TileService

/**
 * 快捷设置磁贴。
 *
 * 旧实现由 system_server 里的 FreeformManager 折叠通知面板；新后端不再提供该能力，
 * 磁贴保留为入口占位（点击无系统级副作用），真正的开窗入口是悬浮窗/手势/桌面长按。
 */
class QuickStartTileService : TileService() {

    @SuppressLint("WrongConstant")
    override fun onClick() {
        super.onClick()
    }
}
