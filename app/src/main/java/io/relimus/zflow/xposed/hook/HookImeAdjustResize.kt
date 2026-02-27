package io.relimus.zflow.xposed.hook

import android.view.Display
import android.view.WindowManager
import io.github.kyuubiran.ezxhelper.core.finder.MethodFinder
import io.github.kyuubiran.ezxhelper.core.util.ClassUtil.loadClass
import io.github.kyuubiran.ezxhelper.core.util.ObjectUtil.getObject
import io.github.kyuubiran.ezxhelper.core.util.ObjectUtil.invokeMethodBestMatch
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory.`-Static`.createHooks
import io.relimus.zflow.utils.cast
import io.relimus.zflow.xposed.services.FreeformManager

object HookImeAdjustResize {

    private val sessionClass = loadClass("com.android.server.wm.Session")
    private val iWindowClass = loadClass("android.view.IWindow")

    fun init() {
        MethodFinder.fromClass(sessionClass)
            .filterByName("relayout")
            .toList()
            .createHooks {
                before { handleRelayout(it.thisObject, it.args) }
            }
    }

    private fun handleRelayout(session: Any?, args: Array<Any?>) {
        if (session == null) return

        val windowClient = args.firstOrNull { iWindowClass.isInstance(it) }
        val service = getObject(session, "mService").cast<Any>()
        val lock = getObject(service, "mGlobalLock").cast<Any>()

        val windowState = synchronized(lock) {
            try {
                invokeMethodBestMatch(service, "windowForClientLocked", null, session, windowClient, false)
            } catch (_: Exception) { }
        } ?: return

        val displayId = try {
            invokeMethodBestMatch(windowState, "getDisplayId").cast<Int>()
        } catch (_: Exception) { return }

        if (displayId == Display.DEFAULT_DISPLAY || !FreeformManager.isManagedDisplay(displayId)) return

        val windowAttrs = getObject(windowState, "mAttrs").cast<WindowManager.LayoutParams>()
        if (windowAttrs.type !in WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW..
            WindowManager.LayoutParams.LAST_APPLICATION_WINDOW) return

        windowAttrs.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        (args.firstOrNull { it is WindowManager.LayoutParams }.cast<WindowManager.LayoutParams?>())
            ?.let { it.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN }
    }
}
