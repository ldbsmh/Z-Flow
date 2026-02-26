package io.relimus.zflow.xposed.hook

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import de.robv.android.xposed.XposedHelpers
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder
import io.github.kyuubiran.ezxhelper.core.util.ClassUtil.loadClass
import io.github.kyuubiran.ezxhelper.core.util.ObjectUtil
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createHook
import io.relimus.zflow.ui.freeform.FreeformService
import io.relimus.zflow.xposed.hook.utils.XLog
import io.relimus.zflow.xposed.hook.utils.cast

object HookSwipeGesture {

    private const val TAG = "HookSwipeGesture"
    private const val PROGRESS_THRESHOLD = 3f

    fun init() {
        runCatching {
            hookSwipeGesture()
        }.onFailure {
            XLog.d("$TAG init failed", it)
        }
    }

    private fun hookSwipeGesture() {
        val absSwipeClass = loadClass("com.android.quickstep.AbsSwipeUpHandler")
        val gestureStateClass = loadClass("com.android.quickstep.GestureState")
        val gestureEndTargetClass = loadClass("com.android.quickstep.GestureState\$GestureEndTarget")

        MethodFinder.fromClass(absSwipeClass)
            .filterByName("initStateCallbacks")
            .first()
            .createHook {
                after {
                    val handler = it.thisObject
                    runCatching {
                        val stateEndTargetSet = XposedHelpers.getStaticIntField(
                            gestureStateClass, "STATE_END_TARGET_SET"
                        )
                        val homeTarget = gestureEndTargetClass.enumConstants?.firstOrNull()
                            ?: return@runCatching
                        val gestureState = ObjectUtil.getObjectUntilSuperclass(handler, "mGestureState")

                        XposedHelpers.callMethod(
                            gestureState,
                            "runOnceAtState",
                            stateEndTargetSet,
                            Runnable {
                                runCatching {
                                    if (readProgress(handler) <= PROGRESS_THRESHOLD) return@Runnable

                                    val task = getRunningTask(handler) ?: return@Runnable
                                    val topComponent = XposedHelpers.callMethod(task, "getTopComponent")
                                        as? ComponentName ?: return@Runnable
                                    val key = XposedHelpers.getObjectField(task, "key")
                                    val userId = XposedHelpers.getIntField(key, "userId")
                                    val taskId = resolveTaskId(task, key)

                                    val recentsView = ObjectUtil.getObjectUntilSuperclass(handler, "mRecentsView")
                                    val context = XposedHelpers.callMethod(recentsView, "getContext") as Context
                                    val broadcastIntent = Intent("io.relimus.zflow.start_freeform").apply {
                                        setPackage("io.relimus.zflow")
                                        putExtra("packageName", topComponent.packageName)
                                        putExtra("activityName", topComponent.className)
                                        putExtra("userId", userId)
                                        putExtra(FreeformService.EXTRA_TASK_ID, taskId)
                                        putExtra("miniMode", true)
                                    }
                                    PendingIntent.getBroadcast(
                                        context,
                                        topComponent.hashCode(),
                                        broadcastIntent,
                                        PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                                    ).send()

                                    XposedHelpers.callMethod(gestureState, "setEndTarget", homeTarget)
                                }.onFailure { e ->
                                    XLog.e("$TAG: Failed to launch freeform", e)
                                }
                            }
                        )
                    }.onFailure { e ->
                        XLog.e("$TAG: initStateCallbacks hook failed", e)
                    }
                }
            }
    }

    private fun readProgress(handler: Any): Float {
        val shift = ObjectUtil.getObjectUntilSuperclass(handler, "mCurrentShift") ?: return 0f
        return ObjectUtil.getObject(shift, "value").cast<Float>()
    }

    private fun getRunningTask(handler: Any): Any? {
        val recentsView = ObjectUtil.getObjectUntilSuperclass(handler, "mRecentsView") ?: return null
        val taskView = XposedHelpers.callMethod(recentsView, "getRunningTaskView") ?: return null
        return if (Build.VERSION.SDK_INT > Build.VERSION_CODES.TIRAMISU) {
            val containers = XposedHelpers.callMethod(taskView, "getTaskContainers") ?: return null
            val container = XposedHelpers.callMethod(containers, "get", 0) ?: return null
            XposedHelpers.callMethod(container, "getTask")
        } else {
            XposedHelpers.callMethod(taskView, "getTask")
        }
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
