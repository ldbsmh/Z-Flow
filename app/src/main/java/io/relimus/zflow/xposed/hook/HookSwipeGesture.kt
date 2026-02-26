package io.relimus.zflow.xposed.hook

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.XposedHelpers
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder
import io.github.kyuubiran.ezxhelper.core.util.ClassUtil.loadClass
import io.github.kyuubiran.ezxhelper.core.util.ObjectUtil
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createHook
import io.relimus.zflow.broadcast.StartFreeformReceiver
import io.relimus.zflow.ui.freeform.FreeformService
import io.relimus.zflow.xposed.hook.utils.XLog
import io.relimus.zflow.xposed.hook.utils.cast

object HookSwipeGesture {

    private const val TAG = "HookSwipeGesture"
    private const val PROGRESS_THRESHOLD = 3f
    private const val HINT_ANIM_DURATION = 150L
    private const val MINI_LAUNCH_DELAY_MS = 120L
    private const val TARGET_PACKAGE = "io.relimus.zflow"

    private var hintViewRef: WeakReference<TextView>? = null
    private var hintDismissed = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingMiniLaunch: Runnable? = null

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
                    hintDismissed = false
                    pendingMiniLaunch?.let(mainHandler::removeCallbacks)
                    pendingMiniLaunch = null
                    removeHint(immediate = true)
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
                                    hintDismissed = true
                                    removeHint(immediate = true)
                                    if (readProgress(handler) <= PROGRESS_THRESHOLD) return@Runnable

                                    val task = getRunningTask(handler) ?: return@Runnable
                                    val topComponent = XposedHelpers.callMethod(task, "getTopComponent")
                                        as? ComponentName ?: return@Runnable
                                    val key = XposedHelpers.getObjectField(task, "key")
                                    val userId = XposedHelpers.getIntField(key, "userId")
                                    val taskId = resolveTaskId(task, key)

                                    val recentsView = ObjectUtil.getObjectUntilSuperclass(handler, "mRecentsView")
                                    val context = XposedHelpers.callMethod(recentsView, "getContext") as Context
                                    XposedHelpers.callMethod(gestureState, "setEndTarget", homeTarget)
                                    scheduleMiniLaunch(context, topComponent, userId, taskId)
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

        runCatching {
            MethodFinder.fromClass(absSwipeClass)
                .filterByName("updateSysUiFlags")
                .first()
                .createHook {
                    after {
                        val handler = it.thisObject
                        val progress = readProgress(handler)
                        if (progress >= PROGRESS_THRESHOLD && hintViewRef?.get() == null && !hintDismissed) {
                            showHint(handler)
                        } else if (progress < PROGRESS_THRESHOLD && hintViewRef?.get() != null) {
                            removeHint()
                        }
                    }
                }
        }.onFailure {
            XLog.d("$TAG: updateSysUiFlags hook failed", it)
        }
    }

    private fun showHint(handler: Any) {
        runCatching {
            val recentsView = ObjectUtil.getObjectUntilSuperclass(handler, "mRecentsView")
                as? View ?: return
            val parent = recentsView.parent as? ViewGroup ?: return
            val context = recentsView.context
            val dp = context.resources.displayMetrics.density

            val tv = TextView(context).apply {
                text = "松手切换为小窗"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
                val hPad = (16 * dp).toInt()
                val vPad = (8 * dp).toInt()
                setPadding(hPad, vPad, hPad, vPad)
                background = GradientDrawable().apply {
                    setColor(0xCC000000.toInt())
                    cornerRadius = 48 * dp
                }
                alpha = 0f
            }

            val lp = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (48 * dp).toInt()
            }

            parent.addView(tv, lp)
            tv.post {
                tv.translationX = (parent.width - tv.width) / 2f
                tv.animate().alpha(1f).setDuration(HINT_ANIM_DURATION).start()
            }
            hintViewRef = WeakReference(tv)
        }.onFailure {
            XLog.d("$TAG: showHint failed", it)
        }
    }

    private fun removeHint() {
        removeHint(immediate = false)
    }

    private fun removeHint(immediate: Boolean) {
        val view = hintViewRef?.get() ?: return
        hintViewRef = null
        val removeNow = Runnable {
            view.animate().cancel()
            (view.parent as? ViewGroup)?.removeView(view)
        }
        if (immediate || !view.isAttachedToWindow) {
            removeNow.run()
            return
        }

        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .setDuration(HINT_ANIM_DURATION)
            .withEndAction(removeNow)
            .start()
        view.postDelayed(removeNow, HINT_ANIM_DURATION + 80L)
    }

    private fun scheduleMiniLaunch(
        context: Context,
        topComponent: ComponentName,
        userId: Int,
        taskId: Int
    ) {
        pendingMiniLaunch?.let(mainHandler::removeCallbacks)
        pendingMiniLaunch = Runnable {
            try {
                val targetIntent = Intent(Intent.ACTION_MAIN).apply {
                    setComponent(topComponent)
                    setPackage(topComponent.packageName)
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val serviceIntent = Intent().apply {
                    setClassName(TARGET_PACKAGE, FreeformService::class.java.name)
                    action = FreeformService.ACTION_START_INTENT
                    putExtra(Intent.EXTRA_INTENT, targetIntent)
                    putExtra(Intent.EXTRA_COMPONENT_NAME, topComponent)
                    putExtra(Intent.EXTRA_USER, userId)
                    putExtra(FreeformService.EXTRA_TASK_ID, taskId)
                    putExtra(StartFreeformReceiver.EXTRA_MINI_MODE, true)
                }
                context.startService(serviceIntent)
            } catch (e: Throwable) {
                XLog.e("$TAG: Direct service launch failed", e)
            } finally {
                pendingMiniLaunch = null
            }
        }.also {
            mainHandler.postDelayed(it, MINI_LAUNCH_DELAY_MS)
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
