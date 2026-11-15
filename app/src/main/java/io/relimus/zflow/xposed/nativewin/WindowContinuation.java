package io.relimus.zflow.xposed.nativewin;

import android.content.Context;
import android.os.*;
import io.github.libxposed.api.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Consumer;

/** Only opted-in offscreen mini tasks retain lifecycle; all real lockscreen surfaces stay native. */
final class WindowContinuation {
    private final Context context;
    private final Consumer<String> log;
    private final Map<Integer,PowerManager.WakeLock> locks=new HashMap<>();
    private final Map<Integer,Long> renewed=new HashMap<>();
    private final Object exemptions;
    private final Method allow;
    private final int reason;
    WindowContinuation(Context context,Consumer<String> log)throws Exception{
        this.context=context;this.log=log;
        Class<?> type=Class.forName("android.os.PowerExemptionManager");
        allow=type.getDeclaredMethod("addToTemporaryAllowList",String.class,int.class,String.class,long.class);
        exemptions=context.getSystemService(type);
        reason=type.getField("REASON_OTHER").getInt(null);
        if(exemptions==null)throw new IllegalStateException("power exemption service");
    }
    void update(Map<Integer,String> desired){
        for(int uid:new ArrayList<>(locks.keySet()))if(!desired.containsKey(uid)){
            PowerManager.WakeLock wake=locks.remove(uid);renewed.remove(uid);
            try{if(wake.isHeld())wake.release();}catch(RuntimeException ignored){}
            log.accept("EDGE_CONTINUATION_RELEASE uid="+uid);
        }
        long now=SystemClock.elapsedRealtime();
        for(var entry:desired.entrySet()){
            int uid=entry.getKey();if(uid<10000||now-renewed.getOrDefault(uid,-90000L)<30000)continue;
            PowerManager.WakeLock wake=locks.get(uid);
            try{
                allow.invoke(exemptions,entry.getValue(),reason,"NativeComplements edge-hidden",60000L);
                if(wake==null){
                    wake=context.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"NativeComplements:edge:"+uid);
                    wake.setReferenceCounted(false);locks.put(uid,wake);
                }
                // Bounded even if the controller or handler stops unexpectedly.
                wake.acquire(90000);renewed.put(uid,now);
                log.accept("EDGE_CONTINUATION_RENEW uid="+uid);
            }catch(Exception|LinkageError failure){
                if(wake!=null&&wake.isHeld())wake.release();locks.remove(uid);renewed.put(uid,now);
                log.accept("EDGE_CONTINUATION_POWER_FAILED "+FrameworkAccess.reason(failure));
            }
        }
    }
    static void install(XposedModule module,FrameworkAccess a,WindowEngine engine,Consumer<String> log){
        List<XposedInterface.HookHandle> hooks=new ArrayList<>();
        try{
            Class<?> fragment=Class.forName("com.android.server.wm.TaskFragment",false,a.task.getClassLoader());
            Set<Method> sleep=new HashSet<>(),possible=new HashSet<>();
            for(Class<?> type:new Class<?>[]{fragment,a.task}){
                sleep.add(a.method(type,"shouldSleepActivities"));possible.add(a.method(type,"sleepIfPossible",boolean.class));
            }
            Method visible=a.method(a.activity,"shouldBeVisibleUnchecked");
            for(Method method:sleep)if(method.getReturnType()!=boolean.class)throw new NoSuchMethodException("sleep predicate");
            for(Method method:possible)if(method.getReturnType()!=boolean.class)throw new NoSuchMethodException("sleep completion");
            if(visible.getReturnType()!=boolean.class)throw new NoSuchMethodException("visibility predicate");
            a.field(Class.forName("com.android.server.wm.ActivityTaskManagerService",false,a.task.getClassLoader()),"mShuttingDown");
            for(Method method:sleep)hooks.add(module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                if(engine.continueHiddenTask(chain.getThisObject()))return false;return chain.proceed();
            }));
            for(Method method:possible)hooks.add(module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                if(engine.continueHiddenTask(chain.getThisObject()))return true;return chain.proceed();
            }));
            hooks.add(module.hook(visible).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                boolean nativeVisible=(Boolean)chain.proceed();return nativeVisible||engine.continueHiddenActivity(chain.getThisObject());
            }));
            // Callers may already contain ART-inlined visibility checks.
            module.deoptimize(a.method(a.activity,"updateAndCheckVisibility",boolean.class,boolean.class));
            engine.continuationReady();log.accept("EDGE_CONTINUATION_READY hooks="+hooks.size());
        }catch(Throwable failure){
            for(var hook:hooks)try{hook.unhook();}catch(Throwable ignored){}
            log.accept("EDGE_CONTINUATION_UNAVAILABLE "+FrameworkAccess.reason(failure));
        }
    }
}
