package dev.local.sunwindowdemo;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import io.github.libxposed.api.*;
import java.lang.reflect.*;
import java.util.*;

/** SystemUI host bridge: screenshot priority handoff and host lifecycle. */
public final class BackBridge extends XposedModule {
    static final String PACKAGE="com.android.systemui";
    static final String CHANNEL=Wire.PKG+".backLong.v1";
    private boolean eligible,attempted;

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
        ProductFeatures.installNotifications(this,p.getDefaultClassLoader(),this::note);
        // 通知点击开小窗由 Z-Flow 自己的通知钩子负责（再叠加会一次点击开两个窗口），
        // 这里不安装上游的通知接管。
        note("NOTIFICATION_WINDOW_SKIPPED host-owns-entry");
        installScreenshotHooks(p.getDefaultClassLoader());
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
                    }},new IntentFilter(SystemUiHost.STATE),null,main,Context.RECEIVER_EXPORTED);
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
