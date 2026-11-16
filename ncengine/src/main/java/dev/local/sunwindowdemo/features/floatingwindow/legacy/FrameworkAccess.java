package dev.local.sunwindowdemo;

import android.content.res.Configuration;
import android.graphics.*;
import android.view.SurfaceControl;
import java.lang.reflect.*;
import java.util.*;

/** Reflect exact target signatures; never invoke an arbitrary same-name overload. */
final class FrameworkAccess {
    final Class<?> task, wc, activity, tda, dc;
    final int invisibleFlag,noAnimationFlag;
    final Method systemReady, prepare, changed, freeform, lookup, focused, recentVisible, systemBars;
    final Method requestTransition,finishLayers;
    private Method playingTransition,freezerLeash;
    private Field surfaceFreezer;
    Method overlayVisibilityChanged;
    Field overlayHidingWindows;
    /* Lookups run on Binder, animation and display threads, often under the WM lock.
     * Lock-free caches keyed by identity, without building a String per call. */
    private record MethodKey(Class<?> owner,String name,Class<?>[] args){
        @Override public boolean equals(Object o){
            return o instanceof MethodKey k&&k.owner==owner&&k.name.equals(name)&&Arrays.equals(k.args,args);
        }
        @Override public int hashCode(){return (owner.hashCode()*31+name.hashCode())*31+Arrays.hashCode(args);}
    }
    private record FieldKey(Class<?> owner,String name){}
    private static final Class<?>[] NO_TYPES=new Class<?>[0];
    private static final Object[] NO_ARGS=new Object[0];
    private final Map<MethodKey, Method> methods = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<FieldKey, Field> fields = new java.util.concurrent.ConcurrentHashMap<>();
    FrameworkAccess(ClassLoader loader) throws Exception {
        task = Class.forName("com.android.server.wm.Task", false, loader);
        wc = Class.forName("com.android.server.wm.WindowContainer", false, loader);
        activity = Class.forName("com.android.server.wm.ActivityRecord", false, loader);
        tda = Class.forName("com.android.server.wm.TaskDisplayArea", false, loader);
        dc = Class.forName("com.android.server.wm.DisplayContent", false, loader);
        Class<?> wms = Class.forName("com.android.server.wm.WindowManagerService", false, loader);
        Class<?> root = Class.forName("com.android.server.wm.RootWindowContainer", false, loader);
        systemReady = method(wms, "systemReady");
        prepare = method(task, "prepareSurfaces");
        changed = method(task, "onConfigurationChanged", Configuration.class);
        freeform = method(task, "computeFreeformBounds", Rect.class, Configuration.class);
        lookup = method(root, "anyTaskForId", int.class, int.class);
        focused = method(root, "getTopDisplayFocusedLeafTask");
        for (Method m : new Method[]{systemReady, prepare, changed, freeform})
            if (m.getReturnType() != void.class) throw new NoSuchMethodException(m.toString());
        method(task, "setWindowingMode", int.class);
        method(task, "setBounds", Rect.class);
        method(task, "setAlwaysOnTop", boolean.class);
        method(task, "isRootTask"); method(task, "isLeafTask");
        method(task, "supportsFreeformInDisplayArea", tda);
        method(tda, "isValidWindowingMode", int.class, activity, task);
        method(wms, "requestTraversal");
        method(wc, "getRelativePosition", Point.class);
        method(wc, "assignChildLayers", SurfaceControl.Transaction.class);
        method(task,"topRunningActivity");method(activity,"getRequestedOrientation");
        field(activity,"packageName");method(root,"getDefaultDisplay");
        method(task,"getRootActivity");method(activity,"attachedToProcess");method(activity,"isRelaunching");
        method(activity,"getState");method(activity,"relaunchActivityLocked",boolean.class,int.class);
        for(String name:new String[]{"intent","finishing","resultTo","results","newIntents","launchedFromUid","launchedFromPackage"})field(activity,name);
        method(dc,"getImeWindow");field(dc,"mCurrentFocus");
        method(wc,"forAllActivities",java.util.function.Consumer.class);method(activity,"getUid");
        method(wc, "getPendingTransaction"); method(wc, "getBounds");
        method(wc, "getRelativeDisplayRotation");
        method(field(wc,"mTransitionController").getType(),"setNoAnimation",wc);
        method(wc, "isAttached"); method(wc, "getDisplayContent");
        method(dc, "getStableRect", Rect.class);
        method(field(wc,"mTransitionController").getType(),"setNoAnimation",wc);
        Class<?> insetsController=method(dc,"getInsetsStateController").getReturnType();
        Class<?> insetsState=method(insetsController,"getRawInsetsState").getReturnType();
        method(insetsState,"calculateInsets",Rect.class,Rect.class,int.class,boolean.class);
        field(dc, "mDisplayId");
        method(wc,"forAllTasks",java.util.function.Consumer.class);
        method(task,"moveToFront",String.class);
        method(task,"moveTaskToBack",task);
        Class<?> transition=Class.forName("com.android.server.wm.Transition",false,loader);
        finishLayers=method(transition,"assignLayersForFinishTransaction",wc,SurfaceControl.Transaction.class);
        if(!Modifier.isStatic(finishLayers.getModifiers())||finishLayers.getReturnType()!=void.class)
            throw new NoSuchMethodException(finishLayers.toString());
        method(transition,"collect",wc);method(transition,"setNoAnimation",wc);
        method(transition,"addFlag",int.class);field(transition,"mType");
        method(transition,"getFlags");
        requestTransition=method(field(wc,"mTransitionController").getType(),"requestStartTransition",
            transition,task,Class.forName("android.window.RemoteTransition",false,loader),List.class);
        invisibleFlag=field(android.view.WindowManager.class,"TRANSIT_FLAG_INVISIBLE").getInt(null);
        if(invisibleFlag==0)throw new IllegalStateException("invalid native no-animation flag");
        method(task,"moveTaskToBackInner",task,transition);
        method(task,"updateSurfaceSize",SurfaceControl.Transaction.class);
        method(task,"isVisibleRequested");
        method(android.app.ActivityOptions.class,"setLaunchTaskId",int.class);
        Class<?> snapshot=field(wms,"mTaskSnapshotController").getType();method(snapshot,"recordSnapshot",task);
        method(task,"setFocusable",boolean.class);method(task,"isFocusable");
        method(activity,"moveFocusableActivityToTop",String.class);
        method(dc,"setFocusedApp",activity);method(wms,"updateFocusedWindowLocked",int.class,boolean.class);
        Class<?> fragment=Class.forName("com.android.server.wm.TaskFragment",false,loader);
        method(fragment,"canBeResumed",activity);method(fragment,"getVisibility",activity);
        method(fragment,"shouldSleepOrShutDownActivities");method(fragment,"getTask");
        Class<?> transitionInfo=Class.forName("android.window.TransitionInfo",false,loader);
        noAnimationFlag=field(transitionInfo,"FLAG_NO_ANIMATION").getInt(null);
        if(noAnimationFlag!=262144)throw new IllegalStateException("unexpected no-animation flag");
        method(transition,"onTransactionReady",int.class,SurfaceControl.Transaction.class);
        method(transition,"buildFinishTransaction",SurfaceControl.Transaction.class,transitionInfo,
            java.lang.reflect.Array.newInstance(dc,0).getClass());
        for(String name:new String[]{"mTargets","mChanges","mStartTransaction"})field(transition,name);
        method(wc,"isDescendantOf",wc);method(transitionInfo,"getChanges");
        Class<?> transitionChange=Class.forName("android.window.TransitionInfo$Change",false,loader);
        method(transitionChange,"getLeash");method(transitionChange,"getFlags");method(transitionChange,"setFlags",int.class);
        field(Class.forName("com.android.server.wm.Transition$ChangeInfo",false,loader),"mContainer");
        method(SurfaceControl.Transaction.class,"addTransactionCommittedListener",java.util.concurrent.Executor.class,
            SurfaceControl.TransactionCommittedListener.class);
        method(task,"setForceTranslucent",boolean.class);method(task,"isForceTranslucent");
        method(task,"isForceOpaque");
        method(android.app.ActivityOptions.class,"setAvoidMoveToFront");
        method(root,"resumeFocusedTasksTopActivities");method(root,"ensureActivitiesVisible");
        field(task,"mLastSurfaceSize");
        field(task,"mLastTimeMoved");
        for (String name : new String[]{"getBaseIntent", "getParent", "getDisplayArea", "getActivityType", "getWindowingMode",
                "getConfiguration", "getRequestedOverrideConfiguration", "getBounds", "isAlwaysOnTop",
                "isOverrideBoundsAllowed", "isOverrideWindowingModeAllowed"}) method(task, name);
        method(task, "onRequestedOverrideConfigurationChanged", Configuration.class);
        Class<?> compatController=field(activity,"mAppCompatController").getType();
        Class<?> sizeCompat=method(compatController,"getSizeCompatModePolicy").getReturnType();
        method(sizeCompat,"getAppCompatDisplayInsets");method(sizeCompat,"clearSizeCompatMode");
        field(method(sizeCompat,"getAppCompatDisplayInsets").getReturnType(),"mIsFloating");
        Class<?> winConfig = Class.forName("android.app.WindowConfiguration", false, loader);
        method(winConfig, "setAlwaysOnTop", int.class); method(winConfig, "getWindowingMode"); method(winConfig, "getBounds");
        field(winConfig, "mAlwaysOnTop"); field(Configuration.class, "windowConfiguration");
        method(SurfaceControl.Transaction.class, "setScale", SurfaceControl.class, float.class, float.class);
        method(SurfaceControl.Transaction.class, "setPosition", SurfaceControl.class, float.class, float.class);
        method(SurfaceControl.Transaction.class,"setWindowCrop",SurfaceControl.class,int.class,int.class);
        method(SurfaceControl.Transaction.class,"setCornerRadius",SurfaceControl.class,float.class);
        method(SurfaceControl.Transaction.class,"setCrop",SurfaceControl.class,Rect.class);
        for (String name : new String[]{"mContext", "mRoot", "mGlobalLock", "mAtmService"}) field(wms, name);
        for (String name : new String[]{"mTaskId", "mUserId", "realActivity"}) field(task, name);
        for (String name : new String[]{"mSurfaceControl", "mSurfaceAnimator", "mTransitionController"}) field(wc, name);
        method(field(wc, "mSurfaceAnimator").getType(), "hasLeash");
        method(field(wc, "mTransitionController").getType(), "inTransition", wc);
        // Optional on a framework fork: absence keeps the previous conservative guard.
        try{playingTransition=method(field(wc,"mTransitionController").getType(),"inPlayingTransition",wc);}
        catch(ReflectiveOperationException ignored){}
        try{surfaceFreezer=field(wc,"mSurfaceFreezer");freezerLeash=method(surfaceFreezer.getType(),"hasLeash");}
        catch(ReflectiveOperationException ignored){surfaceFreezer=null;freezerLeash=null;}
        TaskDecoration.preflight(this);
        recentVisible=method(Class.forName("com.android.server.wm.RecentTasks",false,loader),"isVisibleRecentTask",task);
        Class<?> win=Class.forName("com.android.server.wm.WindowState",false,loader);
        try{
            Field hiding=field(wms,"mHidingNonSystemOverlayWindows");
            Method changed=method(wms,"updateNonSystemOverlayWindowsVisibilityIfNeeded",win,boolean.class);
            if(!List.class.isAssignableFrom(hiding.getType())||changed.getReturnType()!=void.class)
                throw new NoSuchMethodException("native overlay visibility signature");
            method(win,"getOwningUid");method(win,"getDisplayContent");method(win,"isVisible");
            overlayHidingWindows=hiding;overlayVisibilityChanged=changed;
        }catch(Exception ignored){overlayHidingWindows=null;overlayVisibilityChanged=null;}
        Class<?> insets=Class.forName("com.android.server.wm.InsetsPolicy",false,loader);
        systemBars=method(insets,"updateSystemBars",win,int.class,int.class,boolean.class);
        field(insets,"mDisplayContent");method(win,"getTask");method(dc,"getDisplayPolicy");
        method(win,"getFrame");method(field(win,"mLastReportedConfiguration").getType(),"getMergedConfiguration");
        Class<?> policy=Class.forName("com.android.server.wm.DisplayPolicy",false,loader);
        field(policy,"mTopFullscreenOpaqueWindowState");
        Class<?> atm = field(wms, "mAtmService").getType();
        method(field(atm,"mTaskChangeNotificationController").getType(),"notifyTaskStackChanged");
        method(atm,"deferWindowLayout");method(atm,"continueWindowLayout");
        field(atm, "mSupportsMultiWindow"); field(atm, "mSupportsFreeformWindowManagement");
    }
    Method method(Class<?> owner, String name, Class<?>... args) throws Exception {
        MethodKey key = new MethodKey(owner, name, args.length == 0 ? NO_TYPES : args);
        Method found = methods.get(key);
        if (found != null) return found;
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try {
                found = type.getDeclaredMethod(name, args); found.setAccessible(true);
                // Racing threads resolve the same Method; keep the first.
                Method raced = methods.putIfAbsent(new MethodKey(owner, name, key.args() == NO_TYPES ? NO_TYPES : args.clone()), found);
                return raced == null ? found : raced;
            } catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(owner.getName() + "#" + name + Arrays.toString(args));
    }
    Field field(Class<?> owner, String name) throws Exception {
        FieldKey key = new FieldKey(owner, name);
        Field found = fields.get(key);
        if (found != null) return found;
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try {
                found = type.getDeclaredField(name); found.setAccessible(true);
                Field raced = fields.putIfAbsent(key, found);
                return raced == null ? found : raced;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(owner.getName() + "#" + name);
    }
    Object field(Object owner, String name) throws Exception { return field(owner.getClass(), name).get(owner); }
    Object call(Object owner, String name) throws Exception { return method(owner.getClass(), name, NO_TYPES).invoke(owner, NO_ARGS); }
    Object call(Object owner, String name, Class<?>[] types, Object... args) throws Exception {
        return method(owner.getClass(), name, types).invoke(owner, args);
    }
    boolean bool(Object owner, String name) throws Exception { return (Boolean) call(owner, name); }
    int integer(Object owner, String name) throws Exception { return (Integer) call(owner, name); }
    Configuration config(Object owner, boolean requested) throws Exception {
        return (Configuration) call(owner, requested ? "getRequestedOverrideConfiguration" : "getConfiguration");
    }
    Object windowConfig(Object owner, boolean requested) throws Exception { return field(config(owner, requested), "windowConfiguration"); }
    void transform(Object target, float scale, float x, float y) throws Exception {
        Object surface = field(target, "mSurfaceControl");
        if (!(surface instanceof SurfaceControl) || !((SurfaceControl) surface).isValid())
            throw new IllegalStateException("surface unavailable");
        Object transaction = transaction(target);
        SurfaceControl.Transaction tx = (SurfaceControl.Transaction) transaction;
        tx.setScale((SurfaceControl) surface, scale, scale);
        tx.setPosition((SurfaceControl) surface, x, y);
    }
    private SurfaceControl.Transaction renderTransaction,frameTransaction;
    boolean inFrame(){return frameTransaction!=null;}
    void beginFrame(){
        if(frameTransaction!=null)throw new IllegalStateException("nested surface frame");
        if(renderTransaction==null)renderTransaction=new SurfaceControl.Transaction();
        frameTransaction=renderTransaction;
    }
    Object transaction(Object task) throws Exception {return frameTransaction!=null?frameTransaction:call(task,"getPendingTransaction");}
    void endFrame(boolean commit){
        SurfaceControl.Transaction t=frameTransaction;frameTransaction=null;
        if(t==null)return;
        if(commit){
            try{t.apply();}catch(RuntimeException e){t.close();renderTransaction=null;throw e;}
        }else{t.close();renderTransaction=null;}
    }
    boolean animating(Object target) throws Exception {
        return bool(field(target, "mSurfaceAnimator"), "hasLeash")
            || (Boolean) call(field(target, "mTransitionController"), "inTransition", new Class<?>[]{wc}, target);
    }
    boolean entryBusy(Object target,boolean allowTransition)throws Exception {
        return WindowPolicy.entryBusy(!allowTransition&&animating(target),integer(target,"getRelativeDisplayRotation"));
    }
    /** Collection alone does not own the surface. SunOS pauses presentation only for a real leash/playback. */
    boolean presentationLeashed(Object target) throws Exception {
        return bool(field(target,"mSurfaceAnimator"),"hasLeash")
            ||surfaceFreezer!=null&&freezerLeash!=null&&(Boolean)freezerLeash.invoke(surfaceFreezer.get(target))
            ||(playingTransition!=null?(Boolean)playingTransition.invoke(field(target,"mTransitionController"),target):animating(target));
    }
    void presentation(Object target,SurfaceControl.Transaction tx,Geometry.Placement p,Rect parent,Rect crop,float radius,float alpha)throws Exception{
        SurfaceControl surface=(SurfaceControl)field(target,"mSurfaceControl");
        if(surface==null||!surface.isValid())return;
        // One atomic state at both native handoff endpoints; never repair (0,0) a frame later.
        tx.setScale(surface,p.scale(),p.scale());
        tx.setPosition(surface,p.x()-parent.left,p.y()-parent.top);
        tx.setCrop(surface,crop);
        call(tx,"setCornerRadius",new Class<?>[]{SurfaceControl.class,float.class},surface,radius);
        tx.setAlpha(surface,alpha);
    }
    void opacity(Object target,float alpha) throws Exception {
        ((SurfaceControl.Transaction)transaction(target)).setAlpha((SurfaceControl)field(target,"mSurfaceControl"),alpha);
    }
    void style(Object target,int width,int height,float radius) throws Exception {
        Object surface=field(target,"mSurfaceControl"),transaction=transaction(target);
        call(transaction,"setWindowCrop",new Class<?>[]{SurfaceControl.class,int.class,int.class},surface,width,height);
        call(transaction,"setCornerRadius",new Class<?>[]{SurfaceControl.class,float.class},surface,radius);
    }
    void style(Object target,Rect crop,float radius) throws Exception {
        Object surface=field(target,"mSurfaceControl"),transaction=transaction(target);
        ((SurfaceControl.Transaction)transaction).setCrop((SurfaceControl)surface,crop);
        call(transaction,"setCornerRadius",new Class<?>[]{SurfaceControl.class,float.class},surface,radius);
    }
    static String reason(Throwable e) {
        while (e instanceof InvocationTargetException && e.getCause() != null) e = e.getCause();
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
