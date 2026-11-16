package dev.local.sunwindowdemo;

import android.graphics.Rect;
import io.github.libxposed.api.*;
import java.util.*;

public final class SunWindowModule extends XposedModule {
    private boolean eligible, attempted;
    @Override public void onModuleLoaded(ModuleLoadedParam p) {
        eligible = p.isSystemServer();
        note("MODULE_LOADED process=" + p.getProcessName() + " systemServer=" + eligible + " version=" + BuildConfig.MODULE_VERSION_NAME);
    }
    @Override public void onSystemServerStarting(SystemServerStartingParam p) {
        note("SYSTEM_SERVER_START eligible=" + eligible + " attempted=" + attempted);
        if (!eligible || attempted) return;
        attempted = true;
        ProductFeatures.installServer(this,p.getClassLoader(),this::note);
        XposedInterface.HookHandle startup=null;
        try{
            ClassLoader loader=p.getClassLoader();
            Class<?> wms=Class.forName("com.android.server.wm.WindowManagerService",false,loader);
            java.lang.reflect.Method main=wms.getDeclaredMethod("main",android.content.Context.class,
                Class.forName("com.android.server.input.InputManagerService",false,loader),boolean.class,
                Class.forName("com.android.server.policy.WindowManagerPolicy",false,loader),
                Class.forName("com.android.server.wm.ActivityTaskManagerService",false,loader));
            if(!java.lang.reflect.Modifier.isStatic(main.getModifiers())||main.getReturnType()!=wms)throw new NoSuchMethodException("WMS startup");
            final boolean[] selected={false};
            startup=hook(main).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                if(!selected[0]){selected[0]=true;
                    try{
                        int eligibility=dev.local.sunwindowdemo.core.WindowBackendEligibility.readSystem(loader,Wire.PKG);
                        String owner=dev.local.sunwindowdemo.core.WindowBackendOwnership.claimEligible(Wire.PKG,eligibility);
                        boolean delegated=eligibility==0&&dev.local.sunwindowdemo.core.ModuleIdentity.FULL_PACKAGE.equals(Wire.PKG);
                        note("WINDOW_BACKEND_SELECTED eligibility="+eligibility+" owner="+owner);
                        if(!Wire.PKG.equals(owner))WindowBackendConflict.install(this,loader,owner,delegated,this::note);
                        else installBackend(p);
                    }catch(Throwable unavailable){note("WINDOW_STARTUP_UNAVAILABLE "+FrameworkAccess.reason(unavailable));}
                }
                return chain.proceed();
            });
            java.lang.reflect.Method caller=Class.forName("com.android.server.SystemServer",false,loader)
                .getDeclaredMethod("startOtherServices",Class.forName("com.android.server.utils.TimingsTraceAndSlog",false,loader));
            if(!deoptimize(caller))throw new IllegalStateException("WMS caller deoptimize");
            note("WINDOW_STARTUP_READY");
        }catch(Throwable unavailable){
            if(startup!=null)try{startup.unhook();}catch(Throwable ignored){}
            note("WINDOW_STARTUP_UNAVAILABLE "+FrameworkAccess.reason(unavailable));
        }
    }
    private void installBackend(SystemServerStartingParam p){
        List<XposedInterface.HookHandle> handles = new ArrayList<>();
        try {
            FrameworkAccess a = new FrameworkAccess(p.getClassLoader());
            WindowEngine engine = new WindowEngine(a, this::note);
            engine.installCleanupGuard(this, p.getClassLoader());
            WindowContinuation.install(this,a,engine,this::note);
            handles.add(hook(a.systemReady).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed(); note("WMS_READY_SEEN"); engine.attach(chain.getThisObject()); return result;
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
                if(engine.hideRecent(chain.getArg(0)))return false;
                return chain.proceed();
            }));
            handles.add(hook(a.systemBars).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if((Boolean)chain.getArg(3)&&engine.preserveImmersive(chain.getThisObject(),chain.getArg(0))){
                    return chain.proceed(new Object[]{chain.getArg(0),chain.getArg(1),chain.getArg(2),false});
                }
                return chain.proceed();
            }));
            handles.add(hook(a.method(a.task,"moveTaskToBack",a.task)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                if(engine.backToClose(chain.getThisObject()))return true;
                return chain.proceed();
            }));
            Class<?> window=Class.forName("com.android.server.wm.WindowState",false,p.getClassLoader());
            if(a.overlayVisibilityChanged!=null)try{
                handles.add(hook(a.overlayVisibilityChanged).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    Object result=chain.proceed();engine.priorityWindowsChanged();return result;
                }));
                note("SYSTEM_PRIORITY_HOOK_READY");
            }catch(Exception error){note("SYSTEM_PRIORITY_HOOK_UNAVAILABLE "+FrameworkAccess.reason(error));}
            else note("SYSTEM_PRIORITY_HOOK_UNAVAILABLE native-list");
            try{
                java.lang.reflect.Method drawn=a.method(window,"finishDrawing",android.view.SurfaceControl.Transaction.class,int.class);
                if(drawn.getReturnType()!=boolean.class)throw new NoSuchMethodException("finishDrawing(Transaction,int)");
                handles.add(hook(drawn).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    Object result=chain.proceed();engine.drawingFinished(chain.getThisObject(),(Integer)chain.getArg(1));return result;
                }));
                note("TASK_COVER_DRAW_HOOK_READY");
            }catch(Exception e){note("TASK_COVER_DRAW_HOOK_UNAVAILABLE "+FrameworkAccess.reason(e));}
            handles.add(hook(a.method(window,"canReceiveTouchInput")).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                // Owned-task gate, then the entry gate for tasks behind a normal window.
                if(engine.blockContentTouch(chain.getThisObject())||engine.blockBackgroundTouch(chain.getThisObject()))return false;
                return chain.proceed();
            }));
            // Mini tasks must stay nonfocusable for native Home/recents task
            // selection. Preflight the native fragment resume path separately;
            // window key eligibility is not task focus eligibility.
            try{
                java.lang.reflect.Method keys=a.method(window,"canReceiveKeys",boolean.class);
                if(keys.getReturnType()!=boolean.class)throw new NoSuchMethodException("canReceiveKeys(boolean)");
                handles.add(hook(keys).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    if(engine.blockContentTouch(chain.getThisObject()))return false;
                    return chain.proceed();
                }));
                Class<?> visibleFragment=Class.forName("com.android.server.wm.TaskFragment",false,p.getClassLoader());
                java.lang.reflect.Method resume=a.method(visibleFragment,"resumeTopActivity",a.activity,android.app.ActivityOptions.class,boolean.class);
                if(resume.getReturnType()!=boolean.class)throw new NoSuchMethodException("resumeTopActivity(ActivityRecord,ActivityOptions,boolean)");
                engine.visibleResumeReady();note("VISIBLE_MULTI_RESUME_READY nonfocusableMini=true");
            }catch(Exception e){note("VISIBLE_MULTI_RESUME_UNAVAILABLE "+FrameworkAccess.reason(e));}
            // Input sinks are separate surfaces, not WindowStates. Alpha zero and
            // setFocusable(false) do not stop a parked task's sink intercepting touches.
            // Preserve native calculation first so reactivation restores native input.
            try{
                Class<?> sink=Class.forName("com.android.server.wm.ActivityRecordInputSink",false,p.getClassLoader());
                java.lang.reflect.Field record=a.field(sink,"mActivityRecord");
                java.lang.reflect.Method wrapper=a.method(sink,"getInputWindowHandleWrapper",boolean.class);
                java.lang.reflect.Method inputConfig=a.method(wrapper.getReturnType(),"setInputConfigMasked",int.class,int.class);
                handles.add(hook(wrapper).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    Object result=chain.proceed();
                    if(engine.blockContentTouch(record.get(chain.getThisObject())))
                        // NOT_TOUCHABLE=8, verified against the target framework's native sink.
                        inputConfig.invoke(result,8,8);
                    return result;
                }));
                note("PARKED_INPUT_SINK_HOOK_READY");
            }catch(Exception e){note("PARKED_INPUT_SINK_HOOK_UNAVAILABLE "+FrameworkAccess.reason(e));}
            // SunOS WindowState.canAffectSystemUiFlags excludes popup windows. The
            // native frame here is logical (0,0), so it must not color the real bars.
            handles.add(hook(a.method(window,"canAffectSystemUiFlags")).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                if(!engine.idle()&&engine.owns(a.call(chain.getThisObject(),"getTask")))return false;
                return chain.proceed();
            }));
            Class<?> transition=Class.forName("com.android.server.wm.Transition",false,p.getClassLoader());
            Class<?> fragment=Class.forName("com.android.server.wm.TaskFragment",false,p.getClassLoader());
            handles.add(hook(a.method(fragment,"canBeResumed",a.activity)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                boolean nativeResult=(Boolean)chain.proceed();
                return nativeResult||engine.keepVisibleResumed(chain.getThisObject(),chain.getArg(0));
            }));
            // SunOS ActivityRecord uses isFocusableOrPopUpView in BOTH lifecycle
            // decisions. Keep task/input focus native, but admit only a live,
            // visible owned mini at these two lifecycle predicates.
            List<XposedInterface.HookHandle> lifecycleHooks=new ArrayList<>();
            try{
                java.lang.reflect.Method resume=a.method(a.activity,"shouldBeResumed",a.activity);
                java.lang.reflect.Method pause=a.method(a.activity,"shouldPauseActivity",a.activity);
                for(java.lang.reflect.Method check:new java.lang.reflect.Method[]{resume,pause,
                    a.method(a.activity,"shouldMakeActive",a.activity),a.method(a.activity,"canResumeByCompat")})
                    if(check.getReturnType()!=boolean.class)throw new NoSuchMethodException("mini lifecycle boolean predicate");
                a.method(a.activity,"getTaskFragment");
                lifecycleHooks.add(hook(resume).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    boolean nativeResult=(Boolean)chain.proceed();
                    return nativeResult||engine.shouldResumeMiniActivity(chain.getThisObject(),chain.getArg(0));
                }));
                lifecycleHooks.add(hook(pause).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    boolean nativeResult=(Boolean)chain.proceed();
                    return nativeResult&&!engine.retainMiniActivity(chain.getThisObject(),chain.getArg(0));
                }));
                // The target ART may inline these small predicates into native
                // visibility/relaunch callers. Interpret those callers so the
                // paired hooks also apply to previously compiled framework code.
                for(java.lang.reflect.Method caller:new java.lang.reflect.Method[]{
                    a.method(a.activity,"makeActiveIfNeeded",a.activity),
                    a.method(a.activity,"shouldResumeActivity",a.activity),
                    a.method(a.activity,"relaunchActivityLocked",boolean.class,int.class)})
                    note("MINI_LIFECYCLE_DEOPT method="+caller.getName()+" result="+deoptimize(caller));
                handles.addAll(lifecycleHooks);note("MINI_LIFECYCLE_READY resumeAndPause=true taskFocus=false");
            }catch(Throwable e){
                for(XposedInterface.HookHandle handle:lifecycleHooks)try{handle.unhook();}catch(Throwable ignored){}
                note("MINI_LIFECYCLE_UNAVAILABLE "+FrameworkAccess.reason(e));
            }
            handles.add(hook(a.method(transition,"onTransactionReady",int.class,android.view.SurfaceControl.Transaction.class))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    engine.transitionReady(chain.getThisObject());return chain.proceed();
                }));
            handles.add(hook(a.method(transition,"buildFinishTransaction",android.view.SurfaceControl.Transaction.class,
                Class.forName("android.window.TransitionInfo",false,p.getClassLoader()),java.lang.reflect.Array.newInstance(a.dc,0).getClass()))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    Object result=chain.proceed();
                    engine.transitionBuilt(chain.getThisObject(),chain.getArg(1),(android.view.SurfaceControl.Transaction)chain.getArg(0));
                    return result;
                }));
            handles.add(hook(a.method(transition,"collect",a.wc)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                engine.displayTransitionCollecting(chain.getThisObject(),chain.getArg(0));
                Object result=chain.proceed();engine.collected(chain.getThisObject(),chain.getArg(0));return result;
            }));
            handles.add(hook(a.method(a.task,"moveTaskToBackInner",a.task,transition)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                engine.beforeBackInner(chain.getThisObject(),chain.getArg(1));
                Object result=chain.proceed();engine.afterBackInner(chain.getThisObject());return result;
            }));
            handles.add(hook(a.requestTransition).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                engine.requesting(chain.getArg(0),chain.getArg(1));return chain.proceed();
            }));
            // The target framework asks this before creating predictive-back leashes.
            // Suppress only an owned root's remote preview; keep internal app callbacks.
            // This is optional so a missing preview API cannot disable window management.
            try{
                Class<?> adapter=Class.forName("android.window.BackAnimationAdapter",false,p.getClassLoader());
                handles.add(hook(a.method(adapter,"isAnimatable",int.class))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                        if(engine.suppressLinkedBackPreview())return false;
                        return chain.proceed();
                    }));
            }catch(Exception e){note("LINKED_BACK_PREVIEW_HOOK_UNAVAILABLE "+FrameworkAccess.reason(e));}
            LinkedBack.install(this,a,engine,handles,this::note);
            // SunOS forces resizability at the system level. Scope that behavior
            // to acquired tasks and the current preflight candidate; keep app
            // manifests and the system-wide developer setting untouched.
            List<XposedInterface.HookHandle> resizeHooks=new ArrayList<>();
            try{
                java.lang.reflect.Method taskResize;
                try{taskResize=a.method(a.task,"isResizeable",boolean.class);}
                catch(NoSuchMethodException newer){taskResize=a.method(a.task,"isResizeable");}
                java.lang.reflect.Method activityResize=a.method(a.activity,"isResizeable");
                if(taskResize.getReturnType()!=boolean.class||activityResize.getReturnType()!=boolean.class)
                    throw new NoSuchMethodException("scoped resize signatures");
                resizeHooks.add(hook(taskResize).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    if(engine.mayAllowResize()&&engine.allowResize(chain.getThisObject()))return true;
                    return chain.proceed();
                }));
                resizeHooks.add(hook(activityResize).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    if(engine.mayAllowResize()&&engine.allowResize(a.call(chain.getThisObject(),"getTask")))return true;
                    return chain.proceed();
                }));
                handles.addAll(resizeHooks);note("SCOPED_RESIZE_HOOK_READY hooks="+resizeHooks.size());
            }catch(Throwable e){
                for(XposedInterface.HookHandle handle:resizeHooks)try{handle.unhook();}catch(Throwable ignored){}
                note("SCOPED_RESIZE_HOOK_UNAVAILABLE "+FrameworkAccess.reason(e));
            }
            // Early display geometry changes must be hidden before native rotation snapshots.
            // Optional: a different framework disables only this extra observation point.
            try{
                handles.add(hook(a.method(a.dc,"onRequestedOverrideConfigurationChanged",android.content.res.Configuration.class))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                        engine.displayConfigurationChanging(chain.getThisObject(),(android.content.res.Configuration)chain.getArg(0));
                        return chain.proceed();
                    }));
                note("ROTATION_VISIBILITY_HOOK_READY");
            }catch(Exception e){note("ROTATION_VISIBILITY_HOOK_UNAVAILABLE "+FrameworkAccess.reason(e));}
            ServerViewport.install(this,a,engine,handles);
            try{
                handles.add(hook(a.method(a.activity,"setRequestedOrientation",int.class))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                        Object result=chain.proceed();engine.orientationRequested(chain.getThisObject(),(Integer)chain.getArg(0));return result;
                    }));
            }catch(Exception error){note("APP_ORIENTATION_OBSERVER_UNAVAILABLE "+FrameworkAccess.reason(error));}
            // Diagnostics are optional: missing diagnostic callbacks must not disable windows.
            try{
                handles.add(hook(a.method(a.activity,"finishRelaunching")).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    Object result=chain.proceed();engine.trace.state("SERVER_FINISH_RELAUNCH",chain.getThisObject());return result;
                }));
            }catch(Exception e){note("ENTRY_LAYOUT_CALLBACK_UNAVAILABLE "+FrameworkAccess.reason(e));}
            try{
                handles.add(hook(a.method(a.activity,"activityResumedLocked",android.os.IBinder.class,boolean.class)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    Object result=chain.proceed();
                    android.os.IBinder token=(android.os.IBinder)chain.getArg(0);
                    engine.trace.resumed(token);engine.appResumed(token);return result;
                }));
            }catch(Exception e){note("ACTIVITY_RESUME_OBSERVER_UNAVAILABLE "+FrameworkAccess.reason(e));}
            ShareWindow.install(this,a,engine,this::note);
            // Target Android 17 signature, checked against the connected device's
            // services.jar. Configure related tasks before handleStartResult requests Shell playback; preserve flags.
            Class<?> starter=Class.forName("com.android.server.wm.ActivityStarter",false,p.getClassLoader());
            java.lang.reflect.Field sourceRecord=a.field(starter,"mSourceRecord");
            java.lang.reflect.Field lastStarted=a.field(starter,"mLastStartActivityRecord");
            java.lang.reflect.Field realCallingUid=a.field(starter,"mRealCallingUid");
            java.lang.reflect.Field activityIntent=a.field(a.activity,"intent");
            // Optional exact-PendingIntent attribution. Missing members affect explicit jumps only.
            java.lang.reflect.Field[] launchOrigin=new java.lang.reflect.Field[2];
            try{
                Class<?> requestType=Class.forName("com.android.server.wm.ActivityStarter$Request",false,p.getClassLoader());
                launchOrigin[0]=a.field(starter,"mRequest");
                launchOrigin[1]=a.field(requestType,"originatingPendingIntent");
                android.app.PendingIntent.class.getDeclaredMethod("getTarget");
                engine.launchRoutingReady();note("WINDOW_LAUNCH_IDENTITY_READY");
            }catch(Exception error){note("WINDOW_LAUNCH_IDENTITY_UNAVAILABLE "+FrameworkAccess.reason(error));}
            Class<?>[] resultParameters={a.activity,android.app.ActivityOptions.class,int.class,boolean.class,
                Class.forName("android.window.RemoteTransition"),transition,int.class};
            java.lang.reflect.Method startResult;
            try{startResult=a.method(starter,"handleStartResult",resultParameters);}
            catch(NoSuchMethodException newer){
                resultParameters=java.util.Arrays.copyOf(resultParameters,8);
                resultParameters[7]=Class.forName("com.android.server.wm.ActivityCallerState$CallerInfo",false,p.getClassLoader());
                startResult=a.method(starter,"handleStartResult",resultParameters);
            }
            handles.add(hook(startResult)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    Object ticket=null;
                    int code=(Integer)chain.getArg(2);
                    Object shortcutTicket=null;
                    if(code>=0&&code<=3&&launchOrigin[0]!=null&&launchOrigin[1]!=null)try{
                        Object request=launchOrigin[0].get(chain.getThisObject());
                        shortcutTicket=engine.shortcutLaunchTicket(request==null?null:launchOrigin[1].get(request),sourceRecord.get(chain.getThisObject()));
                    }catch(Exception error){note("WINDOW_LAUNCH_OBSERVE_FAILED "+FrameworkAccess.reason(error));}
                    if(code>=0&&code<=3){
                        try{
                            android.content.Intent intent=(android.content.Intent)activityIntent.get(chain.getArg(0));
                            if(shortcutTicket==null)ticket=engine.activityStarting(sourceRecord.get(chain.getThisObject()),chain.getArg(0),lastStarted.get(chain.getThisObject()),intent==null?0:intent.getFlags(),realCallingUid.getInt(chain.getThisObject()));
                        }catch(Exception e){note("HANDOFF_OBSERVE_FAILED "+FrameworkAccess.reason(e));}
                    }
                    boolean success=false;
                    try{
                        Object result=chain.proceed();success=result!=null;
                        if(success&&shortcutTicket!=null)try{
                            Object started=lastStarted.get(chain.getThisObject());
                            // Reused singleTask starts can return an existing task while
                            // the incoming request record itself was never attached.
                            if((started==null||a.call(started,"getTask")==null)&&a.task.isInstance(result))
                                started=a.call(result,"topRunningActivity");
                            engine.shortcutActivityStarted(shortcutTicket,started);
                        }catch(Exception e){note("SHORTCUT_OBSERVE_FAILED "+FrameworkAccess.reason(e));}
                        return result;
                    }finally{engine.activityStartResult(ticket,success);}
                }));
            engine.preflightReady();
            note("HOOKS_READY count="+handles.size()+" version="+BuildConfig.MODULE_VERSION_NAME+"; system scopes only");
        } catch (Throwable e) {
            for (XposedInterface.HookHandle h : handles) try { h.unhook(); } catch (Throwable ignored) { }
            note("DISABLED signature-preflight " + FrameworkAccess.reason(e));
        }
    }
    private void note(String message) {
        try { android.util.Log.i("SunWindowDemo5", message); } catch (Throwable ignored) { }
        try { log(4, "SunWindowDemo5", message); } catch (Throwable ignored) { }
    }
}
