package io.relimus.zflow.providers

import android.content.Context
import io.relimus.zflow.app.ZFlow

/**
 * 轮盘触发区域。
 *
 * 早期版本是「底角热区 + 高度百分比」：热区在水平方向永远是「贴边 22% 屏宽」，
 * 用户没法把触发位置挪到手指真正舒服的地方，也看不见自己设了什么。
 * 现在改成一块**可摆放的矩形**：中心点（横/纵）+ 宽 + 高，全部按屏幕百分比表示，
 * 再加一个「镜像到另一侧」开关 —— 打开就是左右两个角同时可用。
 *
 * 为什么用百分比而不是 dp：
 * 触发区是「手指够得着的位置」，它跟屏幕尺寸强相关而不是跟密度相关；
 * 百分比在 6.1" 和 6.8" 上都能落在同一个相对位置，dp 会让小屏上的区偏大。
 *
 * 这个文件里的几何全部是纯函数（不碰 Canvas / 屏幕），因此可以直接在 JVM 单测里
 * 覆盖「命中区随设置移动」「镜像区左右对称」「圆心与触发区同步上移」这些行为。
 */
object RadialTriggerSettings {

    const val KEY_CENTER_X = "radial_trigger_area_x"
    const val KEY_CENTER_Y = "radial_trigger_area_y"
    const val KEY_WIDTH = "radial_trigger_area_width"
    const val KEY_HEIGHT = "radial_trigger_area_height"
    const val KEY_MIRROR = "radial_trigger_area_mirror"

    /**
     * 默认落在**右下角**：右手单手握持时拇指的自然落点。
     *
     * 镜像打开后左下角同样可用，所以左手用户不需要改任何设置。
     */
    const val DEFAULT_CENTER_X = 91

    /**
     * 旧版本的默认值（左下角）。
     *
     * 老用户机器上已经存了 9，仅改 [DEFAULT_CENTER_X] 对他们不生效，
     * 因此 [read] 会把「还停在旧默认值」的配置迁移到新默认值；
     * 手动把触发区摆回 9 的极少数人会被一起迁走，再拖一次即可。
     */
    const val LEGACY_DEFAULT_CENTER_X = 9

    const val DEFAULT_CENTER_Y = 95
    const val DEFAULT_WIDTH = 20
    const val DEFAULT_HEIGHT = 12

    const val MIN_CENTER_X = 0
    const val MAX_CENTER_X = 100

    /**
     * 纵向位置只允许在下半屏。
     *
     * 扇形是从圆心朝**上**张开的，圆心以上必须留得下三层轮盘（约 330dp）。
     * 允许把触发区摆到屏幕上半部分，等于允许配出一个「按下去什么都没」的区域，
     * 这种「能配但没用」的选项比不给更糟。
     */
    const val MIN_CENTER_Y = 50
    const val MAX_CENTER_Y = 100

    /** 宽度下限 6%：再窄就只有二十来像素，滑进去的概率太低。 */
    const val MIN_WIDTH = 6
    const val MAX_WIDTH = 60
    const val MIN_HEIGHT = 4
    const val MAX_HEIGHT = 40

    /** 默认镜像：开箱即用是「两个底角都能起手」。 */
    const val DEFAULT_MIRROR = true

    /** 屏幕坐标系下的一块矩形（px）。 */
    data class Area(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val centerX: Float get() = (left + right) * 0.5f
        val centerY: Float get() = (top + bottom) * 0.5f
        val width: Float get() = right - left
        val height: Float get() = bottom - top

        fun contains(x: Float, y: Float): Boolean =
            x >= left && x <= right && y >= top && y <= bottom
    }

    /**
     * 一次命中的结果。
     *
     * [originInsetX] / [originInsetY] 直接就是轮盘圆心的内缩量：
     * 圆心放在触发区的中心，扇形再从这里朝屏幕内上方张开，
     * 于是「把触发区挪到哪，轮盘就从哪展开」，不需要再单独配一个锚点。
     */
    data class Hit(
        val area: Area,
        val leftSide: Boolean,
        val originInsetX: Float,
        val originInsetY: Float,
    )

    data class Config(
        val centerXPercent: Int = DEFAULT_CENTER_X,
        val centerYPercent: Int = DEFAULT_CENTER_Y,
        val widthPercent: Int = DEFAULT_WIDTH,
        val heightPercent: Int = DEFAULT_HEIGHT,
        val mirror: Boolean = DEFAULT_MIRROR,
    ) {

        /** 用户配置的这块区（未镜像）。 */
        fun area(widthPx: Float, heightPx: Float): Area {
            val w = widthPx.takeIf { it.isFinite() && it > 0f } ?: 1f
            val h = heightPx.takeIf { it.isFinite() && it > 0f } ?: 1f
            val halfW = w * widthPercent / 200f
            val halfH = h * heightPercent / 200f
            val cx = w * centerXPercent / 100f
            val cy = h * centerYPercent / 100f
            return Area(
                left = (cx - halfW).coerceAtLeast(0f),
                top = (cy - halfH).coerceAtLeast(0f),
                right = (cx + halfW).coerceAtMost(w),
                bottom = (cy + halfH).coerceAtMost(h),
            )
        }

        /** 镜像区：以屏幕中轴线对称。 */
        fun mirrored(widthPx: Float, heightPx: Float): Area {
            val base = area(widthPx, heightPx)
            return Area(
                left = (widthPx - base.right).coerceAtLeast(0f),
                top = base.top,
                right = (widthPx - base.left).coerceAtMost(widthPx),
                bottom = base.bottom,
            )
        }

        fun areas(widthPx: Float, heightPx: Float): List<Area> {
            val primary = area(widthPx, heightPx)
            if (!mirror) return listOf(primary)
            val flipped = mirrored(widthPx, heightPx)
            // 居中且不贴边时两块区可能重合：只留一块，避免同一个点被数两次。
            if (flipped.left == primary.left && flipped.right == primary.right) {
                return listOf(primary)
            }
            return listOf(primary, flipped)
        }

        /** 命中判定；返回扇形要依附的那一侧和圆心内缩量。 */
        fun hit(x: Float, y: Float, widthPx: Float, heightPx: Float): Hit? {
            if (!x.isFinite() || !y.isFinite()) return null
            if (widthPx <= 0f || heightPx <= 0f) return null
            val hit = areas(widthPx, heightPx).firstOrNull { it.contains(x, y) } ?: return null
            val leftSide = hit.centerX <= widthPx / 2f
            val insetX = if (leftSide) hit.centerX else widthPx - hit.centerX
            return Hit(
                area = hit,
                leftSide = leftSide,
                originInsetX = insetX.coerceAtLeast(0f),
                originInsetY = (heightPx - hit.centerY).coerceAtLeast(0f),
            )
        }

        fun sanitized(): Config = Config(
            centerXPercent = centerXPercent.coerceIn(MIN_CENTER_X, MAX_CENTER_X),
            centerYPercent = centerYPercent.coerceIn(MIN_CENTER_Y, MAX_CENTER_Y),
            widthPercent = widthPercent.coerceIn(MIN_WIDTH, MAX_WIDTH),
            heightPercent = heightPercent.coerceIn(MIN_HEIGHT, MAX_HEIGHT),
            mirror = mirror,
        )
    }

    /** 进程内读取（App 进程用）。被 hook 的进程请走 [RemoteSettings]。 */
    fun read(context: Context): Config {
        val prefs = context.getSharedPreferences(ZFlow.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
        val storedX = prefs.getInt(KEY_CENTER_X, DEFAULT_CENTER_X)
        return Config(
            // 老用户存的是旧默认值（左下角）：跟着新默认值一起换到右侧，
            // 否则「改了默认值」只对新装用户生效，升级上来的人毫无变化。
            centerXPercent = if (storedX == LEGACY_DEFAULT_CENTER_X) DEFAULT_CENTER_X else storedX,
            centerYPercent = prefs.getInt(KEY_CENTER_Y, DEFAULT_CENTER_Y),
            widthPercent = prefs.getInt(KEY_WIDTH, DEFAULT_WIDTH),
            heightPercent = prefs.getInt(KEY_HEIGHT, DEFAULT_HEIGHT),
            mirror = prefs.getBoolean(KEY_MIRROR, DEFAULT_MIRROR),
        ).sanitized()
    }

    fun write(context: Context, config: Config) {
        val safe = config.sanitized()
        context.getSharedPreferences(ZFlow.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_CENTER_X, safe.centerXPercent)
            .putInt(KEY_CENTER_Y, safe.centerYPercent)
            .putInt(KEY_WIDTH, safe.widthPercent)
            .putInt(KEY_HEIGHT, safe.heightPercent)
            .putBoolean(KEY_MIRROR, safe.mirror)
            .apply()
    }
}
