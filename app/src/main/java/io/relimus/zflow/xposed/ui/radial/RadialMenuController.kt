package io.relimus.zflow.xposed.ui.radial

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import io.relimus.zflow.providers.FreeformAppsProvider
import io.relimus.zflow.providers.ProviderAccess
import java.lang.ref.WeakReference
import java.util.Collections

/**
 * 把轮盘接到 Launcher 视图树上，并负责取数据、取图标、交出选中项。
 *
 * 分工刻意做得比较薄：几何在 [RadialGeometry]，动画曲线在 [RadialMotion]，
 * 状态机在 [RadialGestureGate]，这里只做「取数据 + 挂视图 + 返回选中项」。
 * 任何一步失败都返回 null/false，让调用方安全退回 Launcher 原本的手势。
 */
internal object RadialMenuController {

    /** 图标缓存上限；被 hook 的 Launcher 进程长期存活，必须封顶。 */
    private const val ICON_CACHE_LIMIT = 24

    private var view: RadialMenuView? = null
    private var hostRef: WeakReference<ViewGroup>? = null
    private var items: List<RadialItem> = emptyList()
    private val hostLocation = IntArray(2)

    private val iconCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, Bitmap>(ICON_CACHE_LIMIT, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean {
                if (size > ICON_CACHE_LIMIT) {
                    eldest.value.recycle()
                    return true
                }
                return false
            }
        }
    )

    val isShowing: Boolean
        get() = view?.parent != null

    /**
     * 展开轮盘。
     *
     * @param parent 承载轮盘的父容器（Launcher 根视图）；轮盘不消费触摸，
     *               坐标由 [updatePointer] 推入。
     * @return false 表示无法展开（没有收藏 / Provider 拒绝 / 父视图不可用），
     *         调用方应退回原本的手势行为。
     */
    fun reveal(
        context: Context,
        parent: ViewGroup,
        corner: RadialCorner,
        widthPx: Int,
        heightPx: Int,
    ): Boolean {
        dismiss(immediate = true)

        val favorites = loadFavorites(context)
        if (favorites.isEmpty()) return false
        val count = favorites.size.coerceAtMost(RadialLayoutSpec.MAX_ITEMS)

        val fitted = RadialLayoutSpec.fit(
            widthPx = widthPx,
            heightPx = heightPx,
            density = context.resources.displayMetrics.density,
            itemCount = count,
        )
        val placed = RadialGeometry.layout(
            corner = corner,
            width = widthPx.toFloat(),
            height = heightPx.toFloat(),
            radius = fitted.radius,
            itemCount = count,
            originInsetX = fitted.originInsetX,
            originInsetY = fitted.originInsetY,
        )
        if (placed.isEmpty) return false

        val iconSize = fitted.iconDiameter.toInt().coerceAtLeast(1)
        val loaded = favorites.take(count).map { favorite ->
            RadialItem(
                packageName = favorite.packageName,
                userId = favorite.userId,
                label = favorite.label,
                icon = circularIcon(context, favorite, iconSize),
            )
        }

        val menu = RadialMenuView(context)
        menu.configure(placed, fitted, loaded)
        parent.addView(
            menu,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        menu.isClickable = false
        menu.isFocusable = false
        menu.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        menu.animateIn()

        view = menu
        hostRef = WeakReference(parent)
        items = loaded
        return true
    }

    /** 推入手指位置（MotionEvent 的 raw 屏幕坐标）。 */
    fun updatePointer(rawX: Float, rawY: Float) {
        val menu = view ?: return
        val host = hostRef?.get() ?: return
        if (!host.isAttachedToWindow || !menu.isAttachedToWindow) return
        host.getLocationOnScreen(hostLocation)
        menu.updatePointer(rawX - hostLocation[0], rawY - hostLocation[1])
    }

    /** 当前选中项；没有选中返回 null。不自动关闭，由调用方决定时机。 */
    fun currentItem(): RadialItem? = items.getOrNull(view?.currentSelection ?: -1)

    fun dismiss(immediate: Boolean = false, onFinished: (() -> Unit)? = null) {
        val menu = view
        view = null
        hostRef = null
        items = emptyList()
        if (menu == null) {
            onFinished?.invoke()
            return
        }
        val parent = menu.parent as? ViewGroup
        if (immediate || parent == null || !menu.isAttachedToWindow) {
            parent?.removeView(menu)
            onFinished?.invoke()
            return
        }
        menu.animateOut {
            (menu.parent as? ViewGroup)?.removeView(menu)
            onFinished?.invoke()
        }
    }

    /** 进程内缓存图标位图的总入口，便于热重载时清理。 */
    fun clearIconCache() {
        synchronized(iconCache) {
            iconCache.values.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
            iconCache.clear()
        }
    }

    // ===== 数据 =====

    private data class Favorite(
        val packageName: String,
        val userId: Int,
        val label: String,
        val icon: Drawable?,
    )

    /**
     * 从 App 进程的 Provider 读收藏名单。
     *
     * 失败（Provider 拒绝 / Binder 异常 / 名单为空）一律返回空列表：
     * 轮盘只是手势的一种呈现，宁可退回原本行为，也不要在 Launcher 里抛异常。
     */
    private fun loadFavorites(context: Context): List<Favorite> {
        val keys = runCatching {
            val bundle = context.contentResolver.call(
                Uri.parse("content://${FreeformAppsProvider.AUTHORITY}"),
                FreeformAppsProvider.METHOD_GET_APPS,
                null,
                null,
            ) ?: return emptyList()
            if (ProviderAccess.isDenied(bundle)) return emptyList()
            bundle.getStringArray(FreeformAppsProvider.EXTRA_KEYS)?.toList().orEmpty()
        }.getOrElse { return emptyList() }

        return keys.take(RadialLayoutSpec.MAX_ITEMS).mapNotNull { key ->
            val parts = key.split('|')
            if (parts.size != 2) return@mapNotNull null
            val packageName = parts[0]
            if (packageName.isEmpty()) return@mapNotNull null
            val userId = parts[1].toIntOrNull() ?: 0
            val resolved = resolveApp(context, packageName)
            Favorite(
                packageName = packageName,
                userId = userId,
                label = resolved?.first ?: packageName,
                icon = resolved?.second,
            )
        }
    }

    /**
     * 取应用名称与图标。
     *
     * 只用 PackageManager：收藏列表本质是「当前用户下的应用」，
     * 跨用户查询要走 LauncherApps 的系统接口，收益远小于复杂度。
     * 其它用户的应用按当前用户解析，图标顶多不精确，但不会影响启动链路。
     */
    private fun resolveApp(
        context: Context,
        packageName: String,
    ): Pair<String, Drawable?>? = runCatching {
        val pm = context.packageManager
        val info = pm.getApplicationInfo(packageName, 0)
        pm.getApplicationLabel(info).toString() to pm.getApplicationIcon(info)
    }.getOrNull()

    /** 把应用图标预渲染成圆形位图：绘制阶段只做一次 drawBitmap，不在每帧裁圆。 */
    private fun circularIcon(context: Context, favorite: Favorite, sizePx: Int): Bitmap? {
        val drawable = favorite.icon ?: return null
        val key = "${favorite.packageName}|${favorite.userId}|$sizePx"
        iconCache[key]?.takeIf { !it.isRecycled }?.let { return it }
        return runCatching {
            val raw = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val rawCanvas = Canvas(raw)
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(rawCanvas)

            val rounded = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val roundedCanvas = Canvas(rounded)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = BitmapShader(raw, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
            val half = sizePx / 2f
            roundedCanvas.drawCircle(half, half, half, paint)
            raw.recycle()
            iconCache[key] = rounded
            rounded
        }.getOrNull()
    }
}
