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
    var dockStyle: Int = 100,
    /**
     * 双击底栏中部是否重唤输入法。
     *
     * 小窗里的应用点输入框唤不出键盘时，这是唯一稳定的自救入口；
     * 关掉后双击只保留「手动旋转画面」那部分行为（前提是
     * [manualAdjustFreeformRotation] 也开着）。
     *
     * 默认 true：这个功能本身就是为解决 IME 唤不出而加的，属于兜底能力。
     */
    var imeDoubleTapRetrigger: Boolean = true
)