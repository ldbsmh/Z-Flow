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
    var defaultLandscape: Boolean = false,
    /**
     * 贴边把手露出比例（百分比），范围 28 ~ 100。
     * 数值 = 露出的应用图标宽度占把手完整宽度的比例。
     */
    var dockStyle: Int = 100
)