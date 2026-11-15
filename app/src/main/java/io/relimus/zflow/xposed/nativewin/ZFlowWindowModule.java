package io.relimus.zflow.xposed.nativewin;

import android.content.Context;
import android.graphics.Rect;
import android.os.IBinder;
import io.github.libxposed.api.*;
import java.util.*;

public final class ZFlowWindowModule extends XposedModule {
    /** 系统界面进程：旧实现里由它承载悬浮菜单与长侧滑，新后端不再与它交互。 */
    public static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    /** Pixel 桌面：旧实现会接管桌面最近任务上滑，新后端不需要。 */
    public static final String LAUNCHER_PACKAGE = "com.google.android.apps.nexuslauncher";

    private boolean eligible, attempted;
    @Override public void onModuleLoaded(ModuleLoadedParam p) {
        eligible = p.isSystemServer();
        note("MODULE_LOADED process=" + p.getProcessName() + " systemServer=" + eligible + ");
    }
    /** system_server：建立小窗后端（FrameworkAccess + WindowEngine + 打开端口）。 */
    /** 新版 API 入口：Android 12+ 的 system_server 在此挂 Hook。 */
    @Override public void onSystemServerStarting(SystemServerStartingParam p) {
        note("SYSTEM_SERVER_START eligible=" + eligible + " attempted=" + attempted);
        if (!eligible || attempted) return;
        attempted = true;
        handles.clear();
        try {
            FrameworkAccess a = new FrameworkAccess(p.getClassLoader());
            WindowEngine engine = new WindowEngine(a, this::note);
            OpenPort port = new OpenPort(engine);
            engine.openPort = port;
            WindowContinuation.install(this, a, engine, this::note);
            handles.add(hook(a.systemReady).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                note("WMS_READY_SEEN");
                engine.attach(chain.getThisObject());
                try { port.client((Context) a.field(chain.getThisObject(), "mContext")); } catch (Throwable unavailable) { note("OPEN_PORT_UID_UNKNOWN"); }
                io.relimus.zflow.xposed.services.UserService.INSTANCE.setWindowBackend(port);
                return result;
            }));
            handles.add(hook(a.prepare).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed(); engine.prepared(chain.getThisObject()); return result;
            }));
            handles.add(hook(a.changed).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed(); engine.configurationChanged(chain.getThisObject()); return result;
            }));
            handles.add(hook(a.freeform).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (engine.owns(chain.getThisObject())) { engine.bounds(chain.getThisObject(), (Rect) chain.getArg(0)); return null; }
                return chain.proceed();
            }));
            handles.add(hook(a.recentVisible).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (engine.hideRecent(chain.getArg(0))) return false;
                return chain.proceed();
            }));
            handles.add(hook(a.systemBars).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if ((Boolean) chain.getArg(3) && engine.preserveImmersive(chain.getThisObject(), chain.getArg(0))) {
                    return chain.proceed(new Object[]{chain.getArg(0), chain.getArg(1), chain.getArg(2), false});
                }
                return chain.proceed();
            }));
            handles.add(hook(a.method(a.task, "moveTaskToBack", a.task)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (engine.backToClose(chain.getThisObject())) return true;
                return chain.proceed();
            }));
            Class<?> window = Class.forName("com.android.server.wm.WindowState", false, p.getClassLoader());
            try {
                java.lang.reflect.Method drawn = a.method(window, "finishDrawing", android.view.SurfaceControl.Transaction.class, int.class);
                if (drawn.getReturnType() != boolean.class) throw new NoSuchMethodException("finishDrawing");
                handles.add(hook(drawn).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Object result = chain.proceed(); engine.drawingFinished(chain.getThisObject(), (Integer) chain.getArg(1)); return result;
                }));
                note("TASK_COVER_DRAW_HOOK_READY");
            } catch (Exception e) { note("TASK_COVER_DRAW_HOOK_UNAVAILABLE " + FrameworkAccess.reason(e)); }
            handles.add(hook(a.method(window, "canReceiveTouchInput")).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (engine.blockContentTouch(chain.getThisObject()) || engine.blockBackgroundTouch(chain.getThisObject())) return false;
                return chain.proceed();
            }));
            try {
                java.lang.reflect.Method keys = a.method(window, "canReceiveKeys", boolean.class);
                if (keys.getReturnType() != boolean.class) throw new NoSuchMethodException("canReceiveKeys");
                handles.add(hook(keys).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    if (engine.blockContentTouch(chain.getThisObject())) return false;
                    return chain.proceed();
                }));
                engine.visibleResumeReady();
                note("VISIBLE_MULTI_RESUME_READY nonfocusableMini=true");
            } catch (Exception e) { note("VISIBLE_MULTI_RESUME_UNAVAILABLE " + FrameworkAccess.reason(e)); }
            handles.add(hook(a.method(window, "canAffectSystemUiFlags")).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (!engine.idle() && engine.owns(a.call(chain.getThisObject(), "getTask"))) return false;
                return chain.proceed();
            }));
            Class<?> transition = Class.forName("com.android.server.wm.Transition", false, p.getClassLoader());
            Class<?> fragment = Class.forName("com.android.server.wm.TaskFragment", false, p.getClassLoader());
            handles.add(hook(a.method(fragment, "canBeResumed", a.activity)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                boolean nativeResult = (Boolean) chain.proceed();
                return nativeResult || engine.keepVisibleResumed(chain.getThisObject(), chain.getArg(0));
            }));
            handles.add(hook(a.method(transition, "onTransactionReady", int.class, android.view.SurfaceControl.Transaction.class))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    engine.transitionReady(chain.getThisObject()); return chain.proceed();
                }));
            handles.add(hook(a.requestTransition).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                engine.requesting(chain.getArg(0), chain.getArg(1)); return chain.proceed();
            }));
            handles.add(hook(a.method(a.task, "moveTaskToBackInner", a.task, transition)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                engine.beforeBackInner(chain.getThisObject(), chain.getArg(1));
                Object result = chain.proceed();
                engine.afterBackInner(chain.getThisObject());
                return result;
            }));
            try {
                Class<?> adapter = Class.forName("android.window.BackAnimationAdapter", false, p.getClassLoader());
                handles.add(hook(a.method(adapter, "isAnimatable", int.class)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    if (engine.suppressLinkedBackPreview()) return false;
                    return chain.proceed();
                }));
            } catch (Exception e) { note("LINKED_BACK_PREVIEW_HOOK_UNAVAILABLE " + FrameworkAccess.reason(e)); }
            LinkedBack.install(this, a, engine, handles, this::note);
            note("HOOKS_READY count=" + handles.size() + " backend=NativeComplements");
        } catch (Throwable e) {
            for (XposedInterface.HookHandle h : handles) try { h.unhook(); } catch (Throwable ignored) { }
            handles.clear();
            note("DISABLED signature-preflight " + FrameworkAccess.reason(e));
        }
    }

    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();

    private void note(String message) {
        try { android.util.Log.i("ZFlowWindow", message); } catch (Throwable ignored) { }
        try { log(4, "ZFlowWindow", message); } catch (Throwable ignored) { }
    }
}
