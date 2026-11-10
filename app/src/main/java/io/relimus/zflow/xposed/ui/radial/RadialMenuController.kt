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

    /** 不该出现在轮盘里的包名：模块自己、系统 UI、桌面。 */
    private val IGNORED_PACKAGES = setOf(
        "io.relimus.zflow",
        "com.android.systemui",
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher3",
    )

    private var view: RadialMenuView? = null
    private var hostRef: WeakReference<ViewGroup>? = null
    private var windowRef: WeakReference<android.view.WindowManager>? = null

    /** 实际生效的窗口类型，仅用于诊断（见 [attach] 里的选择顺序）。 */
    private var windowType = android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
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
        get() = view?.isAttachedToWindow == true

    /**
     * 上一次 [reveal] 失败的原因，仅用于诊断日志。
     *
     * 轮盘失败时一律静默退回原本手势，若不留这个字段，
     * 真机上只能看到「没反应」，无法判断是名单为空还是 Provider 被拒。
     */
    var lastFailureReason: String? = null
        private set

    /**
     * 展开轮盘。
     *
     * 轮盘**优先挂在自己的悬浮窗上**：桌面视图树只在桌面/最近任务位于前台时可见，
     * 用户从第三方应用里上滑时轮盘会被应用窗口挡住（表现为「手势有震动、
     * 小窗也能开，但什么也看不见」）。悬浮窗是 TYPE_APPLICATION_OVERLAY，
     * 不接收触摸，指针仍由调用方推入。
     *
     * @param parent 兜底父容器（桌面 DecorView），悬浮窗创建失败时才用。
     * @return false 表示无法展开（没有条目 / 两种挂载方式都失败），
     *         调用方应退回原本的手势行为。
     */
    fun reveal(
        context: Context,
        parent: ViewGroup?,
        corner: RadialCorner,
        widthPx: Int,
        heightPx: Int,
    ): Boolean {
        lastFailureReason = null
        dismiss(immediate = true)

        val favorites = loadFavorites(context)
        if (favorites.isEmpty()) {
            // loadFavorites 已经在内部把具体原因写进 lastFailureReason，
            // 这里只兜住「确实一条都没配」的情况。
            if (lastFailureReason == null) lastFailureReason = "no_favorites"
            return false
        }
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
            rings = fitted.rings,
            ringStep = fitted.ringStep,
        )
        if (placed.isEmpty) {
            lastFailureReason = "empty_layout count=$count"
            return false
        }

        val iconSize = (fitted.iconDiameter * RadialLayoutSpec.ICON_INSET_RATIO)
            .toInt()
            .coerceAtLeast(1)
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
        if (!attach(menu, parent)) return false
        menu.isClickable = false
        menu.isFocusable = false
        menu.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        menu.animateIn()

        view = menu
        items = loaded
        RadialTrace.log(
            "reveal ok corner=$corner count=$count screen=${widthPx}x${heightPx} " +
                "radius=${fitted.radius.toInt()} icon=${fitted.iconDiameter.toInt()} " +
                "origin=(${placed.origin.x.toInt()},${placed.origin.y.toInt()}) " +
                "host=${if (windowRef != null) "overlay($windowType)" else "viewgroup"}"
        )
        return true
    }

    /**
     * 把轮盘挂到屏幕上：优先独立悬浮窗，失败退回桌面视图树。
     *
     * 桌面进程持有 INTERNAL_SYSTEM_WINDOW，可以直接加 TYPE_APPLICATION_OVERLAY；
     * 万一某个 ROM 仍然拒绝，退回 DecorView 至少还能在桌面/最近任务上工作。
     */
    private fun attach(menu: RadialMenuView, parent: ViewGroup?): Boolean {
        val context = menu.context
        val windowManager = runCatching {
            context.getSystemService(android.view.WindowManager::class.java)
        }.getOrNull()

        if (windowManager != null) {
            /*
             * 窗口类型有讲究：先用 SYSTEM_OVERLAY，失败再退回 APPLICATION_OVERLAY。
             *
             * 桌面进程没有 SYSTEM_ALERT_WINDOW 这个 appop（也不该去要），
             * 而 APPLICATION_OVERLAY 在 appop 处于 default 状态时会被系统
             * 统一压到 80% 不透明度（dumpsys 里 mAttrs.alpha=0.8），
             * 白圆盘会透出下面的壁纸，看起来像一层毛玻璃。
             * SYSTEM_OVERLAY 属于系统窗口，不受这个 appop 约束；
             * 它本来就不接收触摸，正好符合「坐标由调用方推入」的设计。
             */
            for (type in intArrayOf(
                android.view.WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            )) {
                val params = buildParams(type)
                val added = runCatching { windowManager.addView(menu, params) }
                if (added.isSuccess) {
                    windowRef = WeakReference(windowManager)
                    hostRef = null
                    windowType = type
                    RadialTrace.log("overlay attached type=$type alpha=${params.alpha}")
                    return true
                }
                RadialTrace.log(
                    "overlay type=$type failed: ${added.exceptionOrNull()?.javaClass?.simpleName}:" +
                        "${added.exceptionOrNull()?.message}"
                )
            }
        }

        if (parent != null) {
            val attached = runCatching {
                parent.addView(
                    menu,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }.onFailure { throwable ->
                lastFailureReason = "add_view_failed ${throwable.javaClass.simpleName}:${throwable.message}"
                RadialTrace.log(lastFailureReason!!)
            }.isSuccess
            if (attached) {
                hostRef = WeakReference(parent)
                windowRef = null
                return true
            }
        }
        if (lastFailureReason == null) lastFailureReason = "no_host"
        return false
    }

    /** 构造铺满全屏、不接收触摸的浮层参数。 */
    private fun buildParams(type: Int): android.view.WindowManager.LayoutParams =
        android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            type,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            x = 0
            y = 0
            alpha = 1f
            setTitle("ZFlowRadial")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
            }
        }

    /** 推入手指位置（MotionEvent 的 raw 屏幕坐标）。 */    fun updatePointer(rawX: Float, rawY: Float) {
        val menu = view ?: return
        if (!menu.isAttachedToWindow) return
        // 悬浮窗与桌面视图树的原点不同，统一按轮盘自己（MATCH_PARENT 铺满）的位置换算。
        menu.getLocationOnScreen(hostLocation)
        menu.updatePointer(rawX - hostLocation[0], rawY - hostLocation[1])
    }

    /** 当前选中项；没有选中返回 null。不自动关闭，由调用方决定时机。 */
    fun currentItem(): RadialItem? = items.getOrNull(view?.currentSelection ?: -1)

    fun dismiss(immediate: Boolean = false, onFinished: (() -> Unit)? = null) {
        val menu = view
        val windowManager = windowRef?.get()
        view = null
        hostRef = null
        windowRef = null
        items = emptyList()
        if (menu == null) {
            onFinished?.invoke()
            return
        }
        val detach = {
            if (windowManager != null) {
                runCatching { windowManager.removeViewImmediate(menu) }
            } else {
                (menu.parent as? ViewGroup)?.removeView(menu)
            }
            Unit
        }
        if (immediate || !menu.isAttachedToWindow) {
            detach()
            onFinished?.invoke()
            return
        }
        menu.animateOut {
            detach()
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
     * 名单的进程内缓存，跨手势复用。
     *
     * 一次展开要读两份名单（轮盘应用 + 小窗收藏），每次都是跨进程 Binder；
     * 如果 Z-Flow 进程刚好被系统回收，第一次调用还要负责把它冷启动起来 ——
     * 那是几百毫秒级别的同步开销，而这个调用点正好落在 Launcher 的输入回调里，
     * 事件会在 InputDispatcher 侧堆积。命中缓存可以把这份成本摊到第一次之外。
     *
     * TTL 取 3 秒：既能让连续两次手势复用，又不至于让用户改完名单后长时间看不到变化。
     */
    private var cachedFavorites: List<Favorite> = emptyList()
    private var cachedFavoritesAt = 0L
    private const val FAVORITES_TTL_MS = 3_000L

    /**
     * 取轮盘条目，顺序为：用户在「轮盘应用」里挑的名单 → 小窗收藏应用 → 最近任务。
     *
     * 前两级是「用户明确指定过的」，第三级只是兜底：绝大多数用户从没打开过
     * 任何一列表（数据库里就是 0 行），纯收藏制在真机上的实际体验是
     * 「从底角上滑毫无反应」。把最近任务当兜底，保证首次使用就能看到东西；
     * 一旦用户配置了任一份名单，就严格按配置来。
     */
    private fun loadFavorites(context: Context): List<Favorite> {
        val now = android.os.SystemClock.uptimeMillis()
        if (cachedFavorites.isNotEmpty() && now - cachedFavoritesAt < FAVORITES_TTL_MS) {
            return cachedFavorites
        }
        val loaded = queryFavorites(context)
        if (loaded.isNotEmpty()) {
            cachedFavorites = loaded
            cachedFavoritesAt = now
        }
        return loaded
    }

    private fun queryFavorites(context: Context): List<Favorite> {
        val radial = loadProviderList(context, FreeformAppsProvider.METHOD_GET_RADIAL_APPS)
        if (radial.isNotEmpty()) return radial

        val configured = loadProviderList(context, FreeformAppsProvider.METHOD_GET_APPS)
        if (configured.isNotEmpty()) return configured

        val recents = loadRecent(context)
        if (recents.isNotEmpty()) {
            RadialTrace.log(
                "fallback: recent tasks count=${recents.size} " +
                    "reason=${lastFailureReason ?: "empty_favorites"}"
            )
            lastFailureReason = null
            return recents
        }
        if (lastFailureReason == null) lastFailureReason = "empty_favorites"
        return emptyList()
    }

    /** 最近任务里的应用（已去重、去自身），只取包名。 */
    private fun loadRecent(context: Context): List<Favorite> {
        val packages = runCatching {
            val am = context.getSystemService(android.app.ActivityManager::class.java)
                ?: return@runCatching emptyList()
            @Suppress("DEPRECATION")
            val tasks = am.getRecentTasks(
                RadialLayoutSpec.MAX_ITEMS * 4,
                android.app.ActivityManager.RECENT_IGNORE_UNAVAILABLE,
            )
            val seen = LinkedHashSet<String>()
            for (task in tasks) {
                val pkg = task?.baseIntent?.component?.packageName
                    ?: task?.topActivity?.packageName
                    ?: continue
                if (pkg == context.packageName || pkg in IGNORED_PACKAGES) continue
                seen.add(pkg)
                if (seen.size >= RadialLayoutSpec.MAX_ITEMS) break
            }
            seen.toList()
        }.getOrElse { throwable ->
            lastFailureReason = "recent_error ${throwable.javaClass.simpleName}"
            emptyList()
        }
        return packages.mapNotNull { pkg ->
            val resolved = resolveApp(context, pkg) ?: return@mapNotNull null
            Favorite(
                packageName = pkg,
                userId = android.os.Process.myUserHandle().hashCode(),
                label = resolved.first,
                icon = resolved.second,
            )
        }
    }

    /**
     * 从 App 进程的 Provider 读名单（`method` 决定读哪一份）。
     *
     * 失败（Provider 拒绝 / Binder 异常 / 名单为空）一律返回空列表：
     * 轮盘只是手势的一种呈现，宁可退回下一级来源，也不要在 Launcher 里抛异常。
     */
    private fun loadProviderList(context: Context, method: String): List<Favorite> {
        val bundle = runCatching {
            context.contentResolver.call(
                Uri.parse("content://${FreeformAppsProvider.AUTHORITY}"),
                method,
                null,
                null,
            )
        }.getOrElse { throwable ->
            lastFailureReason = "provider_error ${throwable.javaClass.simpleName}:${throwable.message}"
            return emptyList()
        }
        if (bundle == null) {
            lastFailureReason = "provider_null"
            return emptyList()
        }
        if (ProviderAccess.isDenied(bundle)) {
            // Provider 拒绝（Launcher UID 不在白名单）：必须与「用户没配收藏」区分开，
            // 否则真机上只会看到「没反应」。
            lastFailureReason = "provider_denied ${bundle.getString(ProviderAccess.EXTRA_REASON)}"
            return emptyList()
        }
        val keys = bundle.getStringArray(FreeformAppsProvider.EXTRA_KEYS)?.toList().orEmpty()
        if (keys.isEmpty()) {
            lastFailureReason = "empty_favorites"
            return emptyList()
        }

        return keys.take(RadialLayoutSpec.MAX_ITEMS).mapNotNull { key ->
            val parts = key.split('|')
            if (parts.size != 2) {
                RadialTrace.log("skip malformed key=$key")
                return@mapNotNull null
            }
            val packageName = parts[0]
            if (packageName.isEmpty()) return@mapNotNull null
            val userId = parts[1].toIntOrNull() ?: 0
            val resolved = resolveApp(context, packageName)
            if (resolved == null) {
                // 图标取不到不算失败：格子照画，用包名当标题。
                RadialTrace.log("icon unresolved pkg=$packageName")
            }
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
