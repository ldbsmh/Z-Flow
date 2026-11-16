package dev.local.sunwindowdemo;

import android.content.*;
import android.os.IBinder;
import android.view.InputEvent;
import android.view.InputDevice;
import android.view.MotionEvent;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Prevents the launcher swipe monitor from claiming explicitly owned corner streams. */
public final class LauncherGuard extends XposedModule {
    static final String PACKAGE="com.google.android.apps.nexuslauncher";
    static final String STATE=Wire.PKG+".GUARD_STATE";
    static final String QUERY=Wire.PKG+".QUERY_GUARD_STATE";
    private final GestureGate gate=new GestureGate();
    private boolean eligible,attempted;
    private Context context;
    private BroadcastReceiver receiver,historyReceiver;
    private int claims;
    private RecentsFilter recents;
    private IBinder visualLease;
    @Override public void onModuleLoaded(ModuleLoadedParam p){eligible=!p.isSystemServer()&&(PACKAGE.equals(p.getProcessName())||p.getProcessName().startsWith(PACKAGE+":"));}
    @Override public synchronized void onPackageLoaded(PackageLoadedParam p){
        if(!eligible||attempted||!PACKAGE.equals(p.getPackageName()))return;attempted=true;
        ProductFeatures.installLauncher(this,p.getDefaultClassLoader(),this::note);
        List<XposedInterface.HookHandle> handles=new ArrayList<>();
        try{
            ClassLoader loader=p.getDefaultClassLoader();
            Class<?> handler=Class.forName("com.android.quickstep.TouchInteractionHandler",false,loader);
            Class<?> service=Class.forName("com.android.quickstep.TouchInteractionService",false,loader);
            Method event=handler.getDeclaredMethod("onInputEvent",InputEvent.class);
            Method displayId=InputEvent.class.getDeclaredMethod("getDisplayId");displayId.setAccessible(true);
            Method create=service.getDeclaredMethod("onCreate"),destroy=service.getDeclaredMethod("onDestroy");
            if(event.getReturnType()!=void.class||create.getReturnType()!=void.class||destroy.getReturnType()!=void.class)throw new NoSuchMethodException("signature");
            handles.add(hook(create).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();
                try{connect((Context)chain.getThisObject());}catch(Throwable failure){gate.disable();note("GUARD_STATE_FAILED "+failure.getClass().getSimpleName());}
                return result;
            }));
            handles.add(hook(destroy).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                disconnect();return chain.proceed();
            }));
            handles.add(hook(event).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object input=chain.getArg(0);
                if(input instanceof MotionEvent){
                    MotionEvent e=(MotionEvent)input;
                    boolean finger=e.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)&&e.getToolType(0)==MotionEvent.TOOL_TYPE_FINGER;
                    if(gate.event(e.getActionMasked(),e.getRawX(),e.getRawY(),finger,(Integer)displayId.invoke(e),e.getDeviceId(),e.getDownTime())){
                        if(e.getActionMasked()==MotionEvent.ACTION_DOWN&&claims++<30)note("GUARD_OWNED corner-stream");
                        return null;
                    }
                    return chain.proceed();
                }
                return chain.proceed();
            }));
            note("GUARD_READY hooks=3");
            BackVisual.install(this,loader);
            try{recents=new RecentsFilter(loader,this::note);recents.install(this);}
            catch(Throwable failure){recents=null;note("RECENTS_DISABLED "+failure.getClass().getSimpleName());}

        }catch(Throwable failure){
            for(XposedInterface.HookHandle h:handles)try{h.unhook();}catch(Throwable ignored){}
            disconnect();note("GUARD_DISABLED "+failure.getClass().getSimpleName());
        }
    }
    private void connect(Context service){
        disconnect();context=service.getApplicationContext();
        ProductFeatures.connectLauncher(context);
        BackVisual.publish(context);
        receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent intent){
            if(!STATE.equals(intent.getAction()))return;
            try{if(getSentFromUid()!=context.getPackageManager().getApplicationInfo(BackBridge.PACKAGE,0).uid)return;}catch(Exception e){return;}
            if(!intent.getBooleanExtra("enabled",false)){gate.disable();note("GUARD_STATE disabled");return;}
            android.os.Bundle extras=intent.getExtras();
            IBinder lease=extras==null?null:extras.getBinder("lease");
            if(lease==null){gate.disable();return;}
            if(lease!=visualLease){visualLease=lease;BackVisual.publish(context);}
            boolean accepted=gate.update(intent.getIntExtra("width",0),intent.getIntExtra("height",0),intent.getIntArrayExtra("rects"),intent.getIntArrayExtra("handleRects"),lease::isBinderAlive);
            note(accepted?"GUARD_STATE enabled":"GUARD_STATE rejected-geometry");
        }};
        context.registerReceiver(receiver,new IntentFilter(STATE),null,null,Context.RECEIVER_EXPORTED);
        historyReceiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){
            if(getSentFromUid()==1000&&i.getIntExtra("protocol",0)==Wire.PROTOCOL&&recents!=null)recents.configure(i);
        }};
        context.registerReceiver(historyReceiver,new IntentFilter(WindowEngine.HISTORY),null,null,Context.RECEIVER_EXPORTED);
        context.sendBroadcast(new Intent(WindowEngine.HISTORY_QUERY).setPackage("android"),null,
            android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
        context.sendBroadcast(new Intent(QUERY).setPackage(BackBridge.PACKAGE),null,android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }
    private void disconnect(){gate.disable();visualLease=null;if(context!=null&&receiver!=null)try{context.unregisterReceiver(receiver);}catch(RuntimeException ignored){}if(context!=null&&historyReceiver!=null)try{context.unregisterReceiver(historyReceiver);}catch(RuntimeException ignored){}context=null;receiver=null;historyReceiver=null;}
    private void note(String s){try{log(4,"CornerGuard",s);}catch(Throwable ignored){}}
}
