package io.relimus.zflow.providers

import android.content.Context
import io.relimus.zflow.app.ZFlow

/**
 * 轮盘触发点的位置配置。
 *
 * 两件事：**在哪一侧起手**（左 / 右 / 两侧）和**离屏幕底边多高**。
 *
 * 高度用百分比而不是 dp：不同机型的手势条高度、单手可达范围差别很大，
 * 百分比能让「抬到大概腕部舒服的位置」这件事在各机型上表现一致。
 */
object RadialTriggerSettings {

    const val KEY_SIDE = "radial_trigger_side"
    const val KEY_HEIGHT = "radial_trigger_height"

    const val SIDE_BOTH = "both"
    const val SIDE_LEFT = "left"
    const val SIDE_RIGHT = "right"

    /** 默认贴底：与系统手势条的起手区重合，用户不需要重新学起手位置。 */
    const val DEFAULT_HEIGHT_PERCENT = 0

    /** 上限 30%：再往上就进到应用内容区，普通点击会被大量误判成起手。 */
    const val MAX_HEIGHT_PERCENT = 30

    enum class Side {
        BOTH,
        LEFT,
        RIGHT,
        ;

        val allowsLeft: Boolean get() = this != RIGHT
        val allowsRight: Boolean get() = this != LEFT

        companion object {
            fun from(raw: String?): Side = when (raw) {
                SIDE_LEFT -> LEFT
                SIDE_RIGHT -> RIGHT
                else -> BOTH
            }
        }
    }

    data class Config(
        val side: Side = Side.BOTH,
        val heightPercent: Int = DEFAULT_HEIGHT_PERCENT,
    )

    /** 进程内读取（App 进程用）。被 hook 的进程请走 [RemoteSettings]。 */
    fun read(context: Context): Config {
        val prefs = context.getSharedPreferences(ZFlow.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
        return Config(
            side = Side.from(prefs.getString(KEY_SIDE, SIDE_BOTH)),
            heightPercent = prefs.getInt(KEY_HEIGHT, DEFAULT_HEIGHT_PERCENT)
                .coerceIn(0, MAX_HEIGHT_PERCENT),
        )
    }
}
