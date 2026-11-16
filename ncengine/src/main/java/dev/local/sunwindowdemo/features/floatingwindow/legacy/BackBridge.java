package dev.local.sunwindowdemo;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import io.github.libxposed.api.*;
import java.lang.reflect.*;
import java.util.*;

/** SystemUI host, corner input adapter, screenshot priority and independent edge-touch safety. */
public final class BackBridge extends XposedModule {
    static final String PACKAGE="com.android.systemui";
    static final String CHANNEL=Wire.PKG+".backLong.v1";
    private boolean eligible,attempted;
    static volatile boolean cornerReady;

    private volatile boolean preserveBars;
    private boolean screenshotShown;
    private final Set<View> screenshotViews=Collections.newSetFromMap(new WeakHashMap<>());
    private Context context;
    private volatile IBinder sink;
    private final Handler main=new Handler(Looper.getMainLooper());
    @Override public void onModuleLoaded(ModuleLoadedParam p){eligible=!p.isSystemServer()&&PACKAGE.equals(p.getProcessName());}
    @Override public synchronized void onPackageLoaded(PackageLoadedParam p){
        if(!eligible||attempted||!PACKAGE.equals(p.getPackageName()))return;attempted=true;
        try{
            hook(Instrumentation.class.getDeclaredMethod("callApplicationOnCreate",Application.class))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    Object result=chain.proceed();Application app=(Application)chain.getArg(0);
                    if(PACKAGE.equals(app.getPackageName()))main.post(()->{
                        ProductFeatures.connectSystemUi(app,this::note);
                        try{connect(app);}catch(Exception e){note("HOST_FAILED "+e);}
                    });
                    return result;
                });
        }catch(Exception e){note("HOST_BOOT_HOOK_FAILED "+e);}
        ProductFeatures.installSystemUi(this,p.getDefaultClassLoader(),this::note);
        // Corner support is optional; missing pilfer ABI must not disable the independent blocker.
        installEdgeTouchAdmission(p.getDefaultClassLoader());
        ProductFeatures.installNotifications(this,p.getDefaultClassLoader(),this::note);
        WindowTools.install(this,p.getDefaultClassLoader(),this::note);
        // 通知点击开小窗由 Z-Flow 自己的通知钩子负责（再叠加会一次点击开两个窗口），
        // 这里不安装上游的通知接管。
        note("NOTIFICATION_WINDOW_SKIPPED host-owns-entry");
        installScreenshotHooks(p.getDefaultClassLoader());
    }
    // A transparent blocker consumes application touches, but native InputMonitor also sees DOWN.
    // Optional target-verified admission gate: leave the outer receiver's finishInputEvent intact.
    // Runs only inside hooked SystemUI; the hidden getter is optional and API37-preflighted.
    @android.annotation.SuppressLint("BlockedPrivateApi")
    private void installEdgeTouchAdmission(ClassLoader cl){
        try{
            Class<?> displayHandler=Class.forName("com.android.systemui.navigationbar.gestural.DisplayBackGestureHandlerImpl",false,cl);
            Class<?> adapter=field(displayHandler,"onInputEvent").getType();
            if(adapter.isInterface())adapter=Class.forName("com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler$$ExternalSyntheticLambda20",false,cl);
            Class<?> handler=Class.forName("com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler",false,cl);
            Field owner=field(adapter,"f$0"),displays=field(handler,"mDisplayBackGestureHandlers");
            Method invoke=adapter.getDeclaredMethod("invoke",Object.class);
            Method cancel=handler.getDeclaredMethod("cancelGesture",MotionEvent.class);
            Method eventDisplay=MotionEvent.class.getDeclaredMethod("getDisplayId");
            Class<?> unitClass=Class.forName("kotlin.Unit",false,cl);
            Field instance=unitClass.getField("INSTANCE");
            if(owner.getType()!=handler||displays.getType()!=Map.class||invoke.getReturnType()!=Object.class
                ||cancel.getReturnType()!=void.class||eventDisplay.getReturnType()!=int.class||Modifier.isStatic(invoke.getModifiers())
                ||Modifier.isStatic(cancel.getModifiers())||instance.getType()!=unitClass||!Modifier.isStatic(instance.getModifiers()))
                throw new NoSuchMethodException("edge admission signature mismatch");
            Object unit=instance.get(null);if(unit==null)throw new IllegalStateException("missing Unit.INSTANCE");
            Field cornerCompat=null,cornerMonitor=null;Method cornerPilfer=null;
            if(!dev.local.sunwindowdemo.core.ModuleIdentity.CURRENT.standalone())try{
                Class<?> compatType=Class.forName("com.android.systemui.shared.system.InputMonitorCompat",false,cl);
                Class<?> monitorType=Class.forName("android.view.InputMonitor");
                Field compat=field(displayHandler,"inputMonitorCompat"),monitor=field(compatType,"mInputMonitor");
                Method pilfer=monitorType.getDeclaredMethod("pilferPointers");
                if(compat.getType()!=compatType||monitor.getType()!=monitorType||pilfer.getReturnType()!=void.class)
                    throw new NoSuchMethodException("corner input monitor signature mismatch");
                pilfer.setAccessible(true);cornerCompat=compat;cornerMonitor=monitor;cornerPilfer=pilfer;
            }catch(Throwable unavailable){note("CORNER_INPUT_UNAVAILABLE "+unavailable.getClass().getSimpleName());}
            final Field compat=cornerCompat,monitor=cornerMonitor;final Method pilfer=cornerPilfer;
            invoke.setAccessible(true);cancel.setAccessible(true);eventDisplay.setAccessible(true);
            java.util.concurrent.atomic.AtomicBoolean active=new java.util.concurrent.atomic.AtomicBoolean(true);
            hook(invoke).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object argument=chain.getArg(0);
                if(cornerReady&&argument instanceof MotionEvent event)try{
                    if((Integer)eventDisplay.invoke(event)==Display.DEFAULT_DISPLAY){
                        SystemUiHost host=SystemUiHost.live;
                        Object map=displays.get(owner.get(chain.getThisObject()));
                        Object display=map instanceof Map<?,?> handlers?handlers.get(Display.DEFAULT_DISPLAY):null;
                        if(host!=null&&display!=null&&host.cornerInput(event,()->{
                            try{pilfer.invoke(monitor.get(compat.get(display)));}
                            catch(Exception error){throw new IllegalStateException("corner pilfer",error);}
                        }))return unit;
                    }
                }catch(Throwable failure){
                    cornerReady=false;note("CORNER_INPUT_DISABLED "+failure.getClass().getSimpleName());
                    SystemUiHost host=SystemUiHost.live;if(host!=null)host.cancelCornerInput();
                }
                if(active.get()&&argument instanceof MotionEvent event&&event.getActionMasked()==MotionEvent.ACTION_DOWN
                    &&event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)){
                    try{
                        if((Integer)eventDisplay.invoke(event)!=Display.DEFAULT_DISPLAY)return chain.proceed();
                        SystemUiHost host=SystemUiHost.live;
                        if(host!=null&&host.blocksEdgeTouch(event.getX(),event.getY())){
                            Object nativeHandler=owner.get(chain.getThisObject()),map=displays.get(nativeHandler);
                            if(map instanceof Map<?,?> handlers&&handlers.get(Display.DEFAULT_DISPLAY)!=null){
                                cancel.invoke(nativeHandler,event);return unit;
                            }
                        }
                    }catch(Throwable failure){active.set(false);note("EDGE_BACK_ADMISSION_DISABLED "+failure.getClass().getSimpleName());}
                }
                return chain.proceed();
            });
            cornerReady=pilfer!=null;
            if(cornerReady)note("CORNER_INPUT_READY native monitor; pilfer only after diagonal threshold");
            note("EDGE_BACK_ADMISSION_READY");
        }catch(Throwable failure){note("EDGE_BACK_ADMISSION_UNAVAILABLE "+failure.getClass().getSimpleName());}
    }
    private Runnable reconnect;
    private synchronized void connect(Context c){
        if(context!=null&&SystemUiHost.live!=null)return;
        if(reconnect!=null){main.removeCallbacks(reconnect);reconnect=null;}
        Context app=c.getApplicationContext();
        try{
            if(context==null){
                BroadcastReceiver visual=new BroadcastReceiver(){public void onReceive(Context c,Intent i){BackVisual.receive(app,i,getSentFromUid());}};
                app.registerReceiver(visual,new IntentFilter(BackVisual.STATE),null,main,Context.RECEIVER_EXPORTED);
                try{
                    app.registerReceiver(new BroadcastReceiver(){public void onReceive(Context c,Intent i){
                        if(getSentFromUid()==android.os.Process.myUid())configure(i);
                    }},new IntentFilter(LauncherGuard.STATE),null,main,Context.RECEIVER_EXPORTED);
                }catch(RuntimeException failure){app.unregisterReceiver(visual);throw failure;}
                context=app;
            }
            SystemUiHost.start(app);
            if(SystemUiHost.live==null)throw new IllegalStateException("host initialization failed");
            SystemUiHost.live.publish(true);note("BACK_CONNECTED");
        }catch(RuntimeException failure){
            note("BACK_CONNECT_RETRY "+failure.getClass().getSimpleName());
            reconnect=()->connect(app);main.postDelayed(reconnect,3000);
        }
    }
    private synchronized void configure(Intent i){
        Bundle b=i.getExtras();sink=b==null?null:b.getBinder("dropChannel");
        preserveBars=i.getBooleanExtra("immersive",false);
    }
    private void installScreenshotHooks(ClassLoader cl){
        List<XposedInterface.HookHandle> hooks=new ArrayList<>();
        try{
            Class<?> controller=Class.forName("com.android.systemui.screenshot.ScreenshotController",false,cl);
            Class<?> screenshotWindow=Class.forName("com.android.systemui.screenshot.ScreenshotWindow",false,cl);
            Method handle=null;
            for(Method m:controller.getDeclaredMethods())if(m.getName().equals("handleScreenshot")&&m.getParameterCount()==3
                &&m.getParameterTypes()[0].getName().equals("com.android.systemui.screenshot.ScreenshotData")){
                if(handle!=null)throw new NoSuchMethodException("ambiguous screenshot entry");handle=m;
            }
            if(handle==null)throw new NoSuchMethodException("screenshot entry");
            hooks.add(hook(handle).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object screenshot=field(controller,"window").get(chain.getThisObject());
                Window w=(Window)field(screenshotWindow,"window").get(screenshot);watchScreenshot(w);
                return chain.proceed();
            }));
            hooks.add(hook(screenshotWindow.getDeclaredMethod("removeWindow")).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();Window w=(Window)field(screenshotWindow,"window").get(chain.getThisObject());
                View v=w.peekDecorView();if(v==null||!v.isAttachedToWindow())screenshot(false);return result;
            }));
            note("SCREENSHOT_READY hooks=2");
        }catch(Throwable e){for(XposedInterface.HookHandle h:hooks)try{h.unhook();}catch(Throwable ignored){}note("SCREENSHOT_DISABLED "+e.getClass().getSimpleName());}
    }
    private synchronized void watchScreenshot(Window w){
        connect(w.getContext());View v=w.getDecorView();screenshot(true);
        if(screenshotViews.add(v))v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            public void onViewAttachedToWindow(View view){screenshot(true);preserveScreenshotBars(w);view.post(()->preserveScreenshotBars(w));}
            public void onViewDetachedFromWindow(View view){screenshot(false);}
        });
        if(v.isAttachedToWindow())preserveScreenshotBars(w);
        main.postDelayed(()->{if(!v.isAttachedToWindow())screenshot(false);},1500);
    }
    private void preserveScreenshotBars(Window w){
        if(!preserveBars)return;WindowInsetsController c=w.getInsetsController();
        if(c!=null){c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);c.hide(WindowInsets.Type.systemBars());}
    }
    private synchronized void screenshot(boolean shown){
        if(screenshotShown==shown)return;screenshotShown=shown;if(sink==null)return;
        Parcel data=Parcel.obtain();try{data.writeInterfaceToken(CHANNEL);data.writeBoolean(shown);
            sink.transact(IBinder.FIRST_CALL_TRANSACTION+3,data,null,IBinder.FLAG_ONEWAY);note("SCREENSHOT_PRIORITY shown="+shown);
        }catch(RemoteException e){note("SCREENSHOT_SIGNAL_FAILED");}finally{data.recycle();}
    }
    private static Field field(Class<?> c,String name) throws Exception {for(;c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(name);}
    private void note(String s){try{log(4,"BackLong",s);android.util.Log.i("BackLong",s);}catch(Throwable ignored){}}
}
