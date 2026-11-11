package io.relimus.zflow.xposed.ui.radial

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import java.lang.ref.WeakReference

/**
 * 轮盘展开时把桌面糊掉。
 *
 * 这是 GestureEVO 那套观感的**本地等价物**。GestureEVO 能开窗口级 blur-behind，
 * 是因为它是个常驻的无障碍服务；Z-Flow 的轮盘跑在 Launcher 进程里（App 平时不在运行），
 * 加什么窗口类型都拿不到 blur —— 实测 `TYPE_SYSTEM_OVERLAY` 能挂上、日志 `alpha=1.0`，
 * 但画面一点都不糊。
 *
 * 所以这里换个对象：不模糊「窗口背后」，直接对**桌面自己的视图树**套
 * `RenderEffect` 高斯模糊（API 31+，HWUI 真模糊，不需要任何权限）。
 *
 * 三个关键好处：
 * 1. 糊的是桌面图标**本身**，不是盖在上面的一层膜，所以不存在「膜和图标边缘对不齐」；
 * 2. 轮盘挂在**独立 overlay 窗口**里，不在这棵被模糊的视图树里，圆盘和图标始终清晰；
 * 3. 桌面图标被糊成色块后，就不会再和轮盘里的图标「长得一样、分不清谁是谁」——
 *    这正是用户抱怨的错位重叠感的来源（桌面图标本身也是白圆底）。
 */
internal object RadialBackdrop {

    /**
     * 模糊半径（dp）。
     *
     * `RenderEffect.createBlurEffect` 的入参是 **sigma**：12dp 在 1440p（density 3.75）
     * 上约 45px，足以把图标和文字糊成色块，同时保留壁纸的明暗分布。
     * 再大就要多跑几轮降采样，手势跟手阶段会掉帧。
     */
    private const val BLUR_RADIUS_DP = 12f

    /**
     * 模糊最长存活时间。
     *
     * 正常路径靠 [clear] 释放（提交 / 取消 / 看门狗都会走到）；
     * 这里只是最后一道保险：万一某条退出路径被绕过，不能让桌面一直糊着。
     */
    private const val MAX_LIFETIME_MS = 12_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    private var targetRef: WeakReference<View>? = null
    private var appliedAt = 0L

    private val expiry = Runnable { clear("expired") }

    /**
     * 给桌面加高斯模糊。
     *
     * 只判断「有没有挂到窗口上」，**不判断 isShown**：Quickstep 接管手势期间会把
     * 桌面根视图临时置为不可见（等着决定回桌面还是进最近任务），
     * 用 isShown 判断会得到 `shown=false` 而白白放弃模糊 —— 第一次跑就是这样，
     * 桌面明明在前台却一次也没糊过。反过来误判的代价几乎为零：
     * 不可见窗口不参与合成，effect 不会真的被渲染。
     *
     * @return true 表示模糊已经挂到视图上；false 时由视图的重压暗兜底。
     */
    fun apply(density: Float, root: View?): Boolean {
        clear("reapply")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            RadialTrace.log("backdrop blur unavailable: sdk=${Build.VERSION.SDK_INT}")
            return false
        }
        if (root == null) {
            RadialTrace.log("backdrop blur unavailable: no root view")
            return false
        }
        if (!root.isAttachedToWindow) {
            RadialTrace.log("backdrop blur unavailable: root detached")
            return false
        }
        if (!root.isHardwareAccelerated) {
            RadialTrace.log("backdrop blur unavailable: not hardware accelerated")
            return false
        }
        val radius = (BLUR_RADIUS_DP * density).coerceAtLeast(1f)
        return runCatching {
            root.setRenderEffect(
                RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
            )
            targetRef = WeakReference(root)
            appliedAt = SystemClock.uptimeMillis()
            mainHandler.removeCallbacks(expiry)
            mainHandler.postDelayed(expiry, MAX_LIFETIME_MS)
            RadialTrace.log(
                "backdrop blur applied target=${root.javaClass.simpleName} radius=${radius.toInt()}px"
            )
            true
        }.getOrElse { throwable ->
            RadialTrace.log(
                "backdrop blur failed: ${throwable.javaClass.simpleName}:${throwable.message}"
            )
            targetRef = null
            false
        }
    }

    /** 撤掉模糊。可以在任何退出路径上重复调用。 */
    fun clear(reason: String = "normal") {
        mainHandler.removeCallbacks(expiry)
        val target = targetRef?.get()
        targetRef = null
        if (target == null) return
        runCatching { target.setRenderEffect(null) }
        val alive = SystemClock.uptimeMillis() - appliedAt
        RadialTrace.log("backdrop blur cleared reason=$reason after=${alive}ms")
    }
}
