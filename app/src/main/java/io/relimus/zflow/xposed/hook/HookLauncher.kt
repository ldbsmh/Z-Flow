package io.relimus.zflow.xposed.hook

import android.app.Activity
import android.app.AndroidAppHelper
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.UserHandle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.kyuubiran.ezxhelper.core.util.ClassUtil.loadClass
import io.relimus.zflow.R
import io.relimus.zflow.app.ZFlow
import io.relimus.zflow.broadcast.StartFreeformReceiver
import io.relimus.zflow.providers.BlacklistProvider
import io.relimus.zflow.utils.cast
import io.relimus.zflow.xposed.hook.utils.XLog
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap

object HookLauncher {

    private const val TAG = "HookLauncher"

    /** 桌面图标长按 → 打开小窗 入口 开关 */
    private const val KEY_POPUP_FREEFORM_ENABLE = "popup_freeform_enable"

    /** 屏蔽桌面图标长按 → 消息气泡（Android 17+）开关 */
    private const val KEY_POPUP_BUBBLE_BLOCK = "popup_bubble_block"

    @Volatile
    private var inited = false
    private var hookGeneration = -1L

    private var userContextRef: WeakReference<Context>? = null

    private val popupProxyInstances: MutableSet<Any> =
        Collections.newSetFromMap(WeakHashMap())

    @Synchronized
    fun init(classLoader: ClassLoader) {
        if (inited && hookGeneration == HookRegistry.generation) return
        hookGeneration = HookRegistry.generation
        inited = false

        popupProxyInstances.clear()

        runCatching {
            hookPopup(classLoader)
            inited = true
        }.onFailure {
            inited = false
            XLog.e("$TAG init failed", it)
        }
    }

    private fun hookPopup(classLoader: ClassLoader) {
        runCatching {
            val systemShortcutClazz =
                loadClass("com.android.launcher3.popup.SystemShortcut", classLoader)
            val installClazz =
                loadClass("com.android.launcher3.popup.SystemShortcut\$Install", classLoader)
            val bubbleClazz =
                loadClass("com.android.launcher3.popup.SystemShortcut\$BubbleShortcut", classLoader)
            val factoryClazz =
                loadClass("com.android.launcher3.popup.SystemShortcut\$Factory", classLoader)

            XposedHelpers.setStaticObjectField(
                systemShortcutClazz,
                "INSTALL",
                createPopupFactory(factoryClazz, installClazz, classLoader, isFreeformEntry = true)
            )

            XposedHelpers.setStaticObjectField(
                systemShortcutClazz,
                "BUBBLE_SHORTCUT",
                createPopupFactory(factoryClazz, bubbleClazz, classLoader, isFreeformEntry = false)
            )

            hookProxyMethod(systemShortcutClazz, "onClick")
            hookProxyMethod(installClazz, "onClick")
            hookProxyMethod(bubbleClazz, "onClick")
            hookProxyMethod(systemShortcutClazz, "setIconAndContentDescriptionFor")
            hookProxyMethod(installClazz, "setIconAndContentDescriptionFor")
            hookProxyMethod(bubbleClazz, "setIconAndContentDescriptionFor")
            hookProxyMethod(systemShortcutClazz, "setIconAndLabelFor")
            hookProxyMethod(installClazz, "setIconAndLabelFor")
            hookProxyMethod(bubbleClazz, "setIconAndLabelFor")
        }.onFailure {
            XLog.e("$TAG hookPopup failed", it)
        }
    }

    private fun createPopupFactory(
        factoryClazz: Class<*>,
        shortcutClazz: Class<*>,
        classLoader: ClassLoader,
        isFreeformEntry: Boolean
    ): Any {
        return Proxy.newProxyInstance(
            classLoader,
            arrayOf(factoryClazz)
        ) { _, method, args ->
            if (method.name != "getShortcut") return@newProxyInstance null

            val context = getUserContext()

            // 小窗入口：由开关控制
            if (isFreeformEntry) {
                if (!readEnabled(context, KEY_POPUP_FREEFORM_ENABLE, true)) {
                    return@newProxyInstance null
                }
            } else {
                // 消息气泡入口：由“屏蔽气泡”开关控制（默认屏蔽）
                if (readEnabled(context, KEY_POPUP_BUBBLE_BLOCK, true)) {
                    return@newProxyInstance null
                }
            }

            runCatching {
                val itemInfo = args.getOrNull(1) ?: return@runCatching null

                val componentName = XposedHelpers.callMethod(
                    itemInfo,
                    "getTargetComponent"
                ).cast<ComponentName?>()

                val userObj = runCatching {
                    XposedHelpers.getObjectField(itemInfo, "user")
                }.getOrNull()

                val userId = resolveUserId(userObj)
                val blocked = isBlacklisted(context, componentName, userId)

                if (blocked) {
                    return@newProxyInstance null
                }

                // 查找合适的构造函数：优先 3 参数（View, ItemInfo, ...），
                // 其次 2 参数（可能与 BubbleShortcut 的简化构造匹配）。
                val constructor = shortcutClazz.declaredConstructors
                    .firstOrNull { it.parameterTypes.size == 3 }
                    ?: shortcutClazz.declaredConstructors
                        .firstOrNull { it.parameterTypes.size == 2 }
                    ?: return@runCatching null

                constructor.isAccessible = true

                // 按构造函数实际参数个数从 args 中取值
                val ctorArgs = Array(constructor.parameterTypes.size) { i ->
                    args.getOrNull(i)
                }
                constructor.newInstance(*ctorArgs).also {
                    popupProxyInstances.add(it)
                }
            }.onFailure {
                XLog.e("$TAG popup proxy instance create failed", it)
            }.getOrNull()
        }
    }

    /**
     * 读取 Z-Flow 的设置开关。
     * 在 Launcher 进程里读到的是 Z-Flow 应用的 SharedPreferences。
     */
    private fun readEnabled(
        context: Context,
        key: String,
        default: Boolean
    ): Boolean {
        return runCatching {
            val sp: SharedPreferences =
                context.getSharedPreferences(
                    ZFlow.APP_SETTINGS_NAME,
                    Context.MODE_PRIVATE
                )
            sp.getBoolean(key, default)
        }.getOrDefault(default)
    }


    private fun hookProxyMethod(clazz: Class<*>, methodName: String) {
        HookRegistry.hookAll(clazz, methodName, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!popupProxyInstances.contains(param.thisObject)) return
                handlePopupProxyMethod(param)
            }
        })
    }

    private fun handlePopupProxyMethod(param: XC_MethodHook.MethodHookParam) {
        // 仅处理“小窗打开”入口的实例（Install 的替代）。
        // 消息气泡（BubbleShortcut）即使未屏蔽也不走小窗逻辑，
        // 避免把气泡点击错误地转成打开小窗。
        val thisClass = param.thisObject?.javaClass?.name ?: return
        val isFreeformInstall =
            thisClass.contains("SystemShortcut\$Install")

        when (param.method.name) {
            "onClick" -> {
                if (!isFreeformInstall) return

                runCatching {
                    val thiz = param.thisObject
                    val itemInfo = XposedHelpers.getObjectField(thiz, "mItemInfo")
                    val componentName = XposedHelpers.callMethod(
                        itemInfo,
                        "getTargetComponent"
                    ).cast<ComponentName?>() ?: return

                    val userObj = runCatching {
                        XposedHelpers.getObjectField(itemInfo, "user")
                    }.getOrNull()
                    val userId = resolveUserId(userObj)

                    val intent = Intent("io.relimus.zflow.start_freeform").apply {
                        setPackage("io.relimus.zflow")
                        putExtra("packageName", componentName.packageName)
                        putExtra("activityName", componentName.className)
                        putExtra("userId", userId)
                        putExtra(
                            StartFreeformReceiver.EXTRA_SOURCE,
                            StartFreeformReceiver.SOURCE_POPUP
                        )
                    }

                    AndroidAppHelper.currentApplication().sendBroadcast(intent)

                    runCatching {
                        XposedHelpers.callMethod(thiz, "dismissTaskMenuView")
                    }

                    param.result = null
                }.onFailure {
                    XLog.e("$TAG popup onClick failed", it)
                }
            }

            "setIconAndContentDescriptionFor" -> {
                if (!isFreeformInstall) return

                runCatching {
                    val imageView = param.args[0] as ImageView
                    val context = getUserContext()
                    imageView.setImageDrawable(context.getDrawable(R.drawable.ic_popup_freeform))
                    imageView.contentDescription =
                        context.getString(R.string.popup_open_by_freeform)
                    param.result = null
                }.onFailure {
                    XLog.e("$TAG setIconAndContentDescriptionFor failed", it)
                }
            }

            "setIconAndLabelFor" -> {
                if (!isFreeformInstall) return

                runCatching {
                    val iconView = param.args[0] as View
                    val labelView = param.args[1] as TextView
                    val context = getUserContext()
                    val drawable = context.getDrawable(R.drawable.ic_popup_freeform)
                    if (iconView is ImageView) {
                        iconView.setImageDrawable(drawable)
                    } else {
                        iconView.background = drawable
                    }
                    labelView.text = context.getString(R.string.popup_open_by_freeform)
                    param.result = null
                }.onFailure {
                    XLog.e("$TAG setIconAndLabelFor failed", it)
                }
            }
        }
    }

    private fun getUserContext(): Context {
        return userContextRef?.get() ?: run {
            val activityThreadClass = loadClass("android.app.ActivityThread")
            val currentActivityThread =
                activityThreadClass.getMethod("currentActivityThread").invoke(null)
            val application =
                activityThreadClass.getMethod("getApplication")
                    .invoke(currentActivityThread)
                    .cast<Application>()

            application.createPackageContext(
                ZFlow.PACKAGE_NAME,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
            ).also {
                userContextRef = WeakReference(it)
            }
        }
    }

    private fun resolveUserId(userObj: Any?): Int {
        if (userObj == null) return 0

        runCatching {
            return XposedHelpers.callMethod(userObj, "getIdentifier") as Int
        }

        runCatching {
            return XposedHelpers.getIntField(userObj, "mHandle")
        }

        if (userObj is UserHandle) {
            runCatching {
                return userObj.hashCode()
            }
        }

        return 0
    }

    private fun isBlacklisted(context: Context, componentName: ComponentName?, userId: Int): Boolean {
        val packageName = componentName?.packageName ?: return false

        return runCatching {
            context.contentResolver.call(
                Uri.parse("content://${BlacklistProvider.AUTHORITY}"),
                BlacklistProvider.METHOD_IS_BLACKLISTED,
                null,
                Bundle().apply {
                    putString(BlacklistProvider.EXTRA_PACKAGE_NAME, packageName)
                    putInt(BlacklistProvider.EXTRA_USER_ID, userId)
                }
            )?.getBoolean(BlacklistProvider.EXTRA_RESULT, false) ?: false
        }.onFailure {
            XLog.e("$TAG isBlacklisted provider failed: pkg=$packageName userId=$userId", it)
        }.getOrDefault(false)
    }

    @Suppress("unused")
    private fun Context.getActivity(): Activity? {
        if (this is Activity) return this
        if (this is ContextWrapper) return this.baseContext.getActivity()
        return null
    }
}
