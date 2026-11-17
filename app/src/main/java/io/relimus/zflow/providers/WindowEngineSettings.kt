package io.relimus.zflow.providers

import android.content.Context
import dev.local.sunwindowdemo.Engine
import io.relimus.zflow.app.ZFlow
import io.relimus.zflow.xposed.hook.utils.XLog

/**
 * NC 引擎的原生小窗设置（上游 `Config.java` 的 `corner` 偏好）。
 *
 * 为什么不直接让引擎读 `app_settings`：
 * - 引擎运行在 system_server / SystemUI，读本应用目录在部分 ROM 上会被 SELinux 拦；
 * - 它自带的设置通道是 `corner` 偏好——SystemUI 宿主启动时通过
 *   `content://io.relimus.zflow.settings`（`ConfigProvider`）把这份偏好整份拷进
 *   自己进程，之后只有收到 `SETTINGS_CHANGED` 广播才会重新拉取。
 *
 * 所以这里做“镜像”：设置页写 `app_settings`（Preference 框架要求），再把同一个值
 * 写进 `corner` 并发广播。键名与引擎 `Config` 完全一致，引擎侧读取逻辑不用改。
 *
 * 搬运范围：只搬引擎里真正生效、且 Z-Flow 没有对应能力的 7 项。
 * 没搬的——角落扇区几何参数（Z-Flow 用自己的轮盘）、快捷应用名单（扇区面板内可改）、
 * 通知接管（引擎没安装该 Hook，实际是死配置）。
 */
object WindowEngineSettings {

    /** 引擎总开关。关掉后 Z-Flow 不再把开窗请求交给引擎，调用方回退经典虚拟屏内核。 */
    const val KEY_ENGINE_ENABLED = "nc_engine_enabled"

    /** 开新窗时把上一个默认窗收成迷你窗；关掉则直接关闭。 */
    const val KEY_AUTO_MINIMIZE = "nc_auto_minimize"

    /** 关掉最后一个窗口时结束应用进程（force-stop）。 */
    const val KEY_END_ON_CLOSE = "nc_end_on_close"

    /** 贴边迷你窗保活应用进程。 */
    const val KEY_KEEP_EDGE_AWAKE = "nc_keep_edge_awake"

    /** 双击空白处关闭小窗（默认单击）。 */
    const val KEY_DOUBLE_CLOSE = "nc_double_close"

    /** 小窗任务不在桌面最近任务里出现。与「从最近任务隐藏 Z-Flow 自己」不是一回事。 */
    const val KEY_HIDE_WINDOW_RECENTS = "nc_hide_window_recents"

    /** 横屏下分享目标以窗口打开。 */
    const val KEY_LANDSCAPE_SHARE = "nc_landscape_share_window"

    /** 需要镜像的键；设置页的 SP 监听据此判断是否要同步。 */
    val ALL_KEYS: Set<String> = setOf(
        KEY_ENGINE_ENABLED,
        KEY_AUTO_MINIMIZE,
        KEY_END_ON_CLOSE,
        KEY_KEEP_EDGE_AWAKE,
        KEY_DOUBLE_CLOSE,
        KEY_HIDE_WINDOW_RECENTS,
        KEY_LANDSCAPE_SHARE,
    )

    /** 引擎读取的偏好文件名（`Config.HOST_PREFERENCES`，宿主集成后落在本应用目录）。 */
    private const val CORNER_PREFS = "corner"

    private const val TAG = "WindowEngineSettings"

    /** app_settings 键 / corner 键 / 默认值。默认值必须与引擎 `Config` 一致。 */
    private val MIRROR = arrayOf(
        Triple(KEY_ENGINE_ENABLED, "enabled", true),
        Triple(KEY_AUTO_MINIMIZE, "autoMinimizeOnOpen", true),
        Triple(KEY_END_ON_CLOSE, "endOnClose", false),
        Triple(KEY_KEEP_EDGE_AWAKE, "keepEdgeAwake", false),
        Triple(KEY_DOUBLE_CLOSE, "doubleClose", false),
        Triple(KEY_HIDE_WINDOW_RECENTS, "hideRecents", false),
        Triple(KEY_LANDSCAPE_SHARE, "landscapeShareWindow", true),
    )

    fun enabled(context: Context): Boolean =
        appSettings(context).getBoolean(KEY_ENGINE_ENABLED, true)

    /**
     * 把 7 个开关镜像到 `corner`，然后让 SystemUI 重读快照。
     *
     * 用 `apply()` 而不是 `commit()`：`ConfigProvider` 就运行在本进程，
     * 内存里的值改完立刻可读，不必为一次开关在主线程等磁盘。
     */
    fun sync(context: Context) {
        val settings = appSettings(context)
        val edit = context.getSharedPreferences(CORNER_PREFS, Context.MODE_PRIVATE).edit()
        for ((key, engineKey, def) in MIRROR) edit.putBoolean(engineKey, settings.getBoolean(key, def))
        edit.apply()
        notifyChanged(context)
    }

    private fun notifyChanged(context: Context) {
        runCatching { Engine.notifySettingsChanged(context) }
            .onFailure { XLog.w("$TAG: notify engine settings failed: ${it.message}") }
    }

    private fun appSettings(context: Context) =
        context.getSharedPreferences(ZFlow.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
}
