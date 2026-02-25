package io.relimus.zflow.xposed.hook

import android.app.Activity
import android.app.AndroidAppHelper
import android.app.Application
import android.app.PendingIntent
import android.app.RemoteAction
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.drawable.Icon
import android.view.View
import java.lang.ref.WeakReference
import io.relimus.zflow.R
import io.relimus.zflow.xposed.hook.utils.XLog
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder
import io.github.kyuubiran.ezxhelper.core.util.ClassUtil.loadClass
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createHooks
import io.relimus.zflow.app.ZFlow
import io.relimus.zflow.ui.freeform.FreeformService
import io.relimus.zflow.xposed.hook.utils.cast

object HookLauncher {

    private var mUserContextRef: WeakReference<Context>? = null

    fun init() {
        runCatching {
            hookLauncherAfterQ()
        }.onFailure {
            XLog.d("HookLauncher init failed", it)
        }
    }

    private fun hookLauncherAfterQ() {
        val taskOverlayFactoryClazz = loadClass("com.android.quickstep.TaskOverlayFactory")

        MethodFinder.fromClass(taskOverlayFactoryClazz)
            .filterByName("getEnabledShortcuts")
            .toList()
            .createHooks {
                after {
                    val taskView = it.args[0].cast<View>()
                    val shortcuts = it.result.cast<MutableList<Any>>()
                    if (shortcuts.isEmpty()) return@after

                    val itemInfo = XposedHelpers.getObjectField(shortcuts[0], "mItemInfo")
                    val topComponent = XposedHelpers.callMethod(itemInfo, "getTargetComponent") as? ComponentName ?: return@after
                    val activity = taskView.context.getActivity() ?: return@after

                    val task = XposedHelpers.callMethod(taskView, "getTask") ?: return@after
                    val key = XposedHelpers.getObjectField(task, "key")
                    val userId = XposedHelpers.getIntField(key, "userId")
                    val taskId = resolveTaskId(task, key)

                    val remoteActionShortcutClazz = loadClass("com.android.launcher3.popup.RemoteActionShortcut")
                    val intent = Intent("io.relimus.zflow.start_freeform").apply {
                        setPackage("io.relimus.zflow")
                        putExtra("packageName", topComponent.packageName)
                        putExtra("activityName", topComponent.className)
                        putExtra("userId", userId)
                        putExtra(FreeformService.EXTRA_TASK_ID, taskId)
                    }

                    val userContext = getUserContext()
                    val action = RemoteAction(
                        Icon.createWithResource(userContext, R.drawable.tile_icon),
                        userContext.getString(R.string.recent_open_by_freeform),
                        "",
                        PendingIntent.getBroadcast(
                            AndroidAppHelper.currentApplication(),
                            0,
                            intent,
                            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                        )
                    )

                    val constructor = remoteActionShortcutClazz.constructors[0]
                    val shortcut = when (constructor.parameterCount) {
                        4 -> constructor.newInstance(action, activity, itemInfo, null)
                        3 -> constructor.newInstance(action, activity, itemInfo)
                        else -> {
                            XposedBridge.log("Z-Flow: unknown RemoteActionShortcut constructor: ${constructor.toGenericString()}")
                            null
                        }
                    }

                    shortcut?.let { s -> shortcuts.add(s) }
                }
            }
    }

    private fun getUserContext(): Context {
        return mUserContextRef?.get() ?: run {
            val activityThread = loadClass("android.app.ActivityThread")
            val currentActivityThread = activityThread.getMethod("currentActivityThread").invoke(null)
            val application = activityThread.getMethod("getApplication").invoke(currentActivityThread) as Application
            application.createPackageContext(
                ZFlow.PACKAGE_NAME,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
            ).also { mUserContextRef = WeakReference(it) }
        }
    }

    private fun Context.getActivity(): Activity? {
        if (this is Activity) return this
        if (this is ContextWrapper) return this.baseContext.getActivity()
        return null
    }

    private fun resolveTaskId(task: Any, key: Any): Int {
        val fromTask = try {
            XposedHelpers.getIntField(task, "taskId")
        } catch (_: Throwable) {
            -1
        }
        if (fromTask > 0) return fromTask

        val fromKeyId = try {
            XposedHelpers.getIntField(key, "id")
        } catch (_: Throwable) {
            -1
        }
        if (fromKeyId > 0) return fromKeyId

        return try {
            XposedHelpers.getIntField(key, "taskId")
        } catch (_: Throwable) {
            -1
        }
    }
}
