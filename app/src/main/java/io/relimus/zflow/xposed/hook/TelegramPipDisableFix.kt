package io.relimus.zflow.xposed.hook

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import io.relimus.zflow.app.ZFlow
import io.relimus.zflow.providers.RemoteSettings
import io.relimus.zflow.xposed.hook.utils.XLog

/**
 * 关闭 Telegram 系应用的画中画（PiP）。
 *
 * ## 问题
 *
 * Telegram 播放视频时，上滑离开会进系统 PiP；此时再用任务卡上滑到右上角开小窗，
 * Z-Flow 把这个**已处于 PiP 的任务**整个搬到虚拟屏，PiP 状态一起带过去。
 * 虚拟屏上没有系统 UI，PiP 窗口退化成「角落里一个很小的画面 + 其余空白」。
 *
 * ## 为什么是「整个关掉」而不是「只在小窗里关」
 *
 * PiP 是在**主屏**上、用户上滑去最近任务的那一刻就进了的；等应用落到虚拟屏时
 * 已经处于 PiP。应用侧既无法预知「马上要被开小窗」，也没有退出的 API
 * （公开 SDK 里没有 `exitPictureInPictureMode`，Telegram 自己也从不用它）。
 *
 * 所以只能退而求其次：**让这些应用根本不进 PiP**。
 * 代价是主屏上也不再有小窗播放——用设置里的开关可以随时恢复。
 *
 * ## 为什么 hook 参数而不是 hook 进入调用
 *
 * Android 12+ 上 Telegram 走的是**系统自动进入**：
 *
 * ```
 * PipUtils.useAutoEnterInPictureInPictureMode()  →  return SDK_INT >= 31   // 恒 true
 * manualEnterPictureInPictureModeInternal()
 *     if (useAutoEnterInPictureInPictureMode()) return;      // ← 直接返回
 *     Activity.enterPictureInPictureMode(params)             // ← 在 API 31+ 永不执行
 * ```
 *
 * 也就是说在目标设备上 Telegram **压根不调用** `enterPictureInPictureMode`，
 * 拦它没有意义。唯一有效的开关是 `PictureInPictureParams` 里的
 * `autoEnterEnabled`——Telegram 自己在 `resetPictureInPictureParams` 里也是这么关的。
 *
 * 因此做法是：把 `setPictureInPictureParams` 收到的参数换成
 * `autoEnterEnabled = false` 的空参数。
 *
 * 另外仍然拦一刀 `enterPictureInPictureMode`，用于覆盖 API < 31 上的手动路径，
 * 属于兜底（在 API 31+ 上不会被走到，因此不存在状态机错位风险）。
 *
 * ## 只对 Telegram 系生效
 *
 * 不按包名判断（fork 的包名千奇百怪），而是检测进程里是否存在 Telegram 的
 * PiP 类 `org.telegram.messenger.pip.PipActivityHandler`。
 */
object TelegramPipDisableFix {

    private const val TAG = "TelegramPipDisable"

    /** 用 Telegrams 自己的类名做识别，覆盖官方版与各种 fork。 */
    private const val TELEGRAM_PIP_CLASS = "org.telegram.messenger.pip.PipActivityHandler"

    /** 空参数 + 关闭自动进入；构造一次复用。 */
    private val disabledParams: PictureInPictureParams by lazy {
        PictureInPictureParams.Builder()
            .setAutoEnterEnabled(false)
            .build()
    }

    fun init(classLoader: ClassLoader) {
        if (inited && hookGeneration == HookRegistry.generation) return
        hookGeneration = HookRegistry.generation
        inited = false

        runCatching {
            if (!isTelegramProcess(classLoader)) return
            install(classLoader)
        }.onFailure { XLog.e("$TAG: init failed", it) }

        inited = true
    }

    private var inited = false
    private var hookGeneration = -1L

    private fun isTelegramProcess(classLoader: ClassLoader): Boolean {
        return runCatching {
            XposedHelpers.findClass(TELEGRAM_PIP_CLASS, classLoader)
        }.getOrNull() != null
    }

    private fun install(classLoader: ClassLoader) {
        val activityClass = runCatching {
            XposedHelpers.findClass("android.app.Activity", classLoader)
        }.getOrNull() ?: return

        // 主手段：把 PiP 参数换成「不允许自动进入」
        HookRegistry.findAndHookMethod(
            activityClass,
            "setPictureInPictureParams",
            PictureInPictureParams::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (!isEnabled(activity)) return
                    param.args[0] = disabledParams
                }
            }
        )

        // 兜底：API < 31 上的手动进入路径（目标设备不会走到）
        HookRegistry.hookAll(
            activityClass,
            "enterPictureInPictureMode",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (!isEnabled(activity)) return
                    param.result = false
                }
            }
        )

        XLog.d("$TAG: installed (PiP disabled)")
    }

    private fun isEnabled(context: Context): Boolean {
        return RemoteSettings.isFeatureEnabled(context, ZFlow.KEY_HOOK_TELEGRAM_PIP_DISABLE, true)
    }
}
