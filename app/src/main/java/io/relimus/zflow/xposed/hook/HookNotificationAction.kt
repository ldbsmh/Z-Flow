package io.relimus.zflow.xposed.hook

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.service.notification.StatusBarNotification
import android.view.View
import android.widget.ImageView
import de.robv.android.xposed.XposedHelpers
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder
import io.github.kyuubiran.ezxhelper.core.util.ClassUtil.loadClass
import io.github.kyuubiran.ezxhelper.core.util.ObjectUtil
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createHooks
import io.relimus.zflow.providers.RemoteSettings
import io.relimus.zflow.xposed.hook.utils.XLog

/**
 * SystemUI 通知行注入“打开小窗”按钮（方案 A）。
 *
 * 借鉴 MoreBubbleButton：不取消原通知，只借用系统气泡按钮。
 * - 对【勾选了接管】的应用，强制显示气泡按钮（shouldShowBubbleButton → true）；
 * - 拦截气泡按钮点击（BubblesManager.expandStackAndSelectBubble /
 *   onUserChangedBubble），改为发送广播打开 Z-Flow 小窗，而不是展开气泡。
 *
 * 效果：通知保持原应用身份（发布者未变），折叠/单条都显示原应用，
 * 只有被勾选应用的通知多出一个“小窗”按钮。未勾选应用不受影响。
 */
object HookNotificationAction {

    private const val TAG = "HookNotificationAction"

    fun init() {
        if (inited && hookGeneration == HookRegistry.generation) return
        hookGeneration = HookRegistry.generation
        inited = false

        runCatching { hookShouldShowBubbleButton() }
            .onFailure { XLog.e("$TAG shouldShowBubbleButton hook failed", it) }
        runCatching { hookApplyBubbleAction() }
            .onFailure { XLog.e("$TAG applyBubbleAction hook failed", it) }
        runCatching { hookExpandStackAndSelectBubble() }
            .onFailure { XLog.e("$TAG expandStack hook failed", it) }
        runCatching { hookOnUserChangedBubble() }
            .onFailure { XLog.e("$TAG onUserChanged hook failed", it) }

        inited = true
    }

    private var inited = false
    private var hookGeneration = -1L

    /**
     * 强制被勾选应用的通知显示气泡（实际用作“打开小窗”）按钮。
     */
    private fun hookShouldShowBubbleButton() {
        val clazz = loadClass(
            "com.android.systemui.statusbar.notification.row.NotificationContentView"
        )
        MethodFinder.fromClass(clazz)
            .filterByName("shouldShowBubbleButton")
            .filterEmptyParam()
            .toList()
            .takeIf { it.isNotEmpty() }
            ?.createHooks {
                before { param ->
                    val thisObj = param.thisObject
                    val context = (thisObj as? android.view.View)?.context ?: return@before
                    // 总开关：notify_freeform 为关时不显示按钮
                    if (!RemoteSettings.isNotificationFreeformEnabled(context)) {
                        return@before
                    }
                    val packageName = resolveNotificationPackage(context, thisObj) ?: return@before
                    if (RemoteSettings.isNotificationEnabled(context, packageName)) {
                        param.result = true
                    }
                }
            }
    }

    /**
     * 拦截气泡展开点击 → 打开小窗。
     */
    private fun hookExpandStackAndSelectBubble() {
        val clazz = loadClass("com.android.systemui.wmshell.BubblesManager")
        MethodFinder.fromClass(clazz)
            .filterByName("expandStackAndSelectBubble")
            .filterByParamCount(1)
            .toList()
            .takeIf { it.isNotEmpty() }
            ?.createHooks {
                before { param ->
                    val entry = param.args.getOrNull(0) ?: return@before
                    val context = resolveContext(param.thisObject) ?: return@before
                    val sbn = resolveEntrySbn(entry) ?: return@before
                    val packageName = sbn.packageName

                    if (RemoteSettings.isNotificationEnabled(context, packageName)) {
                        launchFreeform(context, sbn)
                        param.result = null
                    }
                }
            }
    }

    /**
     * 用户在通知上切换气泡时，若为勾选应用则改为打开小窗。
     */
    private fun hookOnUserChangedBubble() {
        val clazz = loadClass("com.android.systemui.wmshell.BubblesManager")
        MethodFinder.fromClass(clazz)
            .filterByName("onUserChangedBubble")
            .filterByParamCount(2)
            .toList()
            .takeIf { it.isNotEmpty() }
            ?.createHooks {
                before { param ->
                    val enabled = param.args.getOrNull(1) as? Boolean ?: return@before
                    if (!enabled) return@before
                    val entry = param.args.getOrNull(0) ?: return@before
                    val context = resolveContext(param.thisObject) ?: return@before
                    val sbn = resolveEntrySbn(entry) ?: return@before
                    val packageName = sbn.packageName

                    if (RemoteSettings.isNotificationEnabled(context, packageName)) {
                        launchFreeform(context, sbn)
                        param.result = null
                    }
                }
            }
    }

    // ==================== 图标替换（反编译确认真实 Hook 点） ====================

    /**
     * 反编译确认：SystemUI 在 NotificationContentView.applyBubbleAction(View) 里
     * 给气泡按钮 ImageView（findViewById(0x1020280)）setImageDrawable 系统图标
     * （bubble_ic_create_bubble / bubble_ic_stop_bubble）。
     * 这里在 after 里把图标换成 Z-Flow 的“小窗”图标（ic_popup_freeform），
     * 与桌面长按图标以小窗打开一致。
     */
    private fun hookApplyBubbleAction() {
        val clazz = loadClass(
            "com.android.systemui.statusbar.notification.row.NotificationContentView"
        )
        MethodFinder.fromClass(clazz)
            .filterByName("applyBubbleAction")
            .toList()
            .takeIf { it.isNotEmpty() }
            ?.createHooks {
                after { param ->
                    val rootView = param.args.getOrNull(0) as? View ?: return@after
                    val context = rootView.context ?: return@after

                    // 该通知的应用是否被勾选
                    val packageName = resolveNotificationPackage(context, param.thisObject)
                        ?: return@after
                    if (!RemoteSettings.isNotificationEnabled(context, packageName)) return@after

                    // 找到气泡按钮 ImageView（id 与 applyBubbleAction smali 一致）
                    val imageView = rootView.findViewById(0x1020280) as? ImageView ?: return@after

                    val icon = loadFreeformIcon(context) ?: return@after
                    runCatching { imageView.setImageDrawable(icon) }
                        .onFailure { XLog.e("$TAG set freeform icon failed", it) }
                }
            }
    }

    /**
     * 从 Z-Flow 的 APK 加载“小窗”图标（ic_popup_freeform）。
     * 跨进程：SystemUI 不能直接引用 Z-Flow 的资源 id，
     * 需通过 createPackageContext 获取资源再加载。
     */
    private fun loadFreeformIcon(context: Context): Drawable? {
        return runCatching {
            val pkgContext = context.createPackageContext(
                "io.relimus.zflow",
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
            )
            val resId = pkgContext.resources.getIdentifier(
                "ic_popup_freeform", "drawable", "io.relimus.zflow"
            )
            if (resId == 0) null else pkgContext.getDrawable(resId)
        }.getOrNull()
    }

    // ==================== 工具 ====================

    private fun resolveNotificationPackage(context: Context, contentView: Any): String? {
        return runCatching {
            val row = ObjectUtil.getObjectUntilSuperclass(contentView, "mContainingNotification")
                ?: return null
            val adapter = ObjectUtil.getObjectUntilSuperclass(row, "mEntryAdapter")
                ?: return null
            val sbn = XposedHelpers.callMethod(adapter, "getSbn") as? StatusBarNotification
                ?: return null
            sbn.packageName
        }.getOrNull()
    }

    private fun resolveEntrySbn(entry: Any): StatusBarNotification? {
        return runCatching {
            ObjectUtil.getObjectUntilSuperclass(entry, "mSbn") as? StatusBarNotification
        }.getOrNull()
    }

    private fun resolveContext(target: Any?): Context? {
        if (target == null) return null
        if (target is Context) return target
        runCatching { return XposedHelpers.getObjectField(target, "mContext") as? Context }
        runCatching {
            for (f in target.javaClass.declaredFields) {
                if (Context::class.java.isAssignableFrom(f.type)) {
                    f.isAccessible = true
                    val v = f.get(target)
                    if (v is Context) return v
                }
            }
        }
        return null
    }

    private fun launchFreeform(context: Context, sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        runCatching {
            val contentIntent: PendingIntent? = sbn.notification.contentIntent
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: return
            val component = launchIntent.component ?: return

            val intent = Intent("io.relimus.zflow.action.start.intent").apply {
                setClassName(
                    "io.relimus.zflow",
                    "io.relimus.zflow.xposed.services.FreeformService"
                )
                setPackage("io.relimus.zflow")
                putExtra(Intent.EXTRA_COMPONENT_NAME, component)
                putExtra(Intent.EXTRA_INTENT, launchIntent)
                if (contentIntent != null) {
                    putExtra("notification_content_intent", contentIntent)
                }
            }
            context.startService(intent)
        }.onFailure {
            XLog.e("$TAG launchFreeform failed: pkg=$packageName", it)
        }
    }
}
