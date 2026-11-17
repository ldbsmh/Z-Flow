package dev.local.sunwindowdemo;

import android.os.Bundle;

/**
 * Z-Flow「小窗外观与尺寸 / 运行规则」里那几项，随每次 OPEN 请求一起下发。
 *
 * 为什么不让引擎自己去读 Z-Flow 的 SharedPreferences：引擎跑在 system_server，
 * 直接读对方应用目录在部分 ROM 上会被 SELinux 拦（旧实现也是因此改成 ContentProvider）。
 * 客户端本来就要读这些值，顺手放进请求里最省事，也让「引擎不依赖对方存储」这条边界成立。
 *
 * 没带设置时用这里的默认值，与接入前的硬编码常量完全一致，保证不开新功能就不改变现有表现。
 */
final class WindowSettings {
    /** 竖屏显示时窗口宽度占屏幕宽度的百分比（Z-Flow: freeform_size）。 */
    static final int DEFAULT_SIZE = 75;
    /** 横屏显示时的高度比例（Z-Flow: freeform_size_land）。 */
    static final int DEFAULT_SIZE_LAND = 90;
    /** 迷你窗宽度占逻辑画布的百分比（Z-Flow: freeform_float_view_size，默认取设置页的 20）。 */
    static final int DEFAULT_MINI = 20;
    /** 背景变暗百分比（Z-Flow: freeform_dimming_amount）。 */
    static final int DEFAULT_DIM = 20;
    /** 贴边把手露出的宽度百分比（Z-Flow: freeform_dock_style）。 */
    static final int DEFAULT_DOCK = 100;

    final int size, sizeLand, miniSize, dim, dockStyle;
    /** 该应用是否在「横屏应用名单」里：进小窗即按横屏布局。 */
    final boolean landscape;

    WindowSettings(int size, int sizeLand, int miniSize, int dim, int dockStyle, boolean landscape) {
        this.size = size;
        this.sizeLand = sizeLand;
        this.miniSize = miniSize;
        this.dim = dim;
        this.dockStyle = dockStyle;
        this.landscape = landscape;
    }

    static WindowSettings defaults() {
        return new WindowSettings(DEFAULT_SIZE, DEFAULT_SIZE_LAND, DEFAULT_MINI, DEFAULT_DIM, DEFAULT_DOCK, false);
    }

    /** 解析客户端下发的设置；缺项按默认值补，越界值夹到合法区间。 */
    static WindowSettings from(Bundle b) {
        if (b == null) return defaults();
        return new WindowSettings(
            Rules.clamp(b.getInt("size", DEFAULT_SIZE), 10, 100),
            Rules.clamp(b.getInt("sizeLand", DEFAULT_SIZE_LAND), 10, 100),
            Rules.clamp(b.getInt("miniSize", DEFAULT_MINI), 10, 50),
            Rules.clamp(b.getInt("dim", DEFAULT_DIM), 0, 100),
            Rules.clamp(b.getInt("dockStyle", DEFAULT_DOCK), 28, 100),
            b.getBoolean("landscape", false));
    }

    @Override public String toString() {
        return "size=" + size + " land=" + sizeLand + " mini=" + miniSize
            + " dim=" + dim + " dock=" + dockStyle + " landscape=" + landscape;
    }
}
