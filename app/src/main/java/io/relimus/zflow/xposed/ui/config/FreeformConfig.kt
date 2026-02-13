package io.relimus.zflow.xposed.ui.config

/**
 * 小窗配置类 - 用于 system_server 中的 FreeformWindow
 */
data class FreeformConfig(
    var freeformDpi: Int = 320,
    var freeformSize: Float = 0.75f,
    var freeformSizeLand: Float = 0.9f,
    var floatViewSize: Float = 0.33f,
    var dimAmount: Float = 0.2f,
    var manualAdjustFreeformRotation: Boolean = false,
)
