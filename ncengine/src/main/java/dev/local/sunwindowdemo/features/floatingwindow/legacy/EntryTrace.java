package dev.local.sunwindowdemo;

import android.content.res.Configuration;
import android.os.IBinder;
import android.os.SystemClock;
import java.util.function.Consumer;

/** Bounded, system-side diagnostics for an explicitly requested launch, including
 * transactions built before WindowEngine owns the task. Never changes arguments.
 */
final class EntryTrace {
    private final FrameworkAccess a;
    private final Consumer<String> log;
    private volatile Object wmLock;
    private String pkg;
    private long request,until;
    private int remaining;
    EntryTrace(FrameworkAccess access,Consumer<String> logger){a=access;log=logger;}
    void attach(Object lock){wmLock=lock;}
    synchronized void arm(String packageName,long generation){
        pkg=packageName;request=generation;until=SystemClock.elapsedRealtime()+20000;remaining=100;
        log.accept("ENTRY_LAYOUT_TRACE_ARM request="+request+" package="+pkg+" budget=100 windowMs=20000");
    }
    synchronized void stop(){until=0;}
    private synchronized boolean matches(Object record)throws Exception {
        return record!=null&&remaining>0&&SystemClock.elapsedRealtime()<until
            &&pkg!=null&&pkg.equals(a.field(record,"packageName"))&&a.integer(record,"getUid")/100000==0;
    }
    private synchronized void emit(String message){
        if(remaining-- >0)log.accept("ENTRY_LAYOUT_"+message+" request="+request);
    }
    private Object record(IBinder token)throws Exception {
        return token==null?null:a.method(a.activity,"forTokenLocked",IBinder.class).invoke(null,token);
    }
    private String identity(Object record)throws Exception {
        Object task=a.call(record,"getTask");
        return " task="+(task==null?-1:a.field(task,"mTaskId"))+" package="+a.field(record,"packageName")
            +" record="+Integer.toHexString(System.identityHashCode(record))+" state="+a.call(record,"getState")
            +" relaunching="+a.bool(record,"isRelaunching");
    }
    private String config(Configuration c)throws Exception {
        if(c==null)return "null";
        Object w=a.field(c,"windowConfiguration");
        return "{mode="+a.integer(w,"getWindowingMode")+",bounds="+a.call(w,"getBounds")
            +",appBounds="+a.call(w,"getAppBounds")+",maxBounds="+a.call(w,"getMaxBounds")
            +",orientation="+c.orientation+",rotation="+a.integer(w,"getRotation")
            +",displayRotation="+a.integer(w,"getDisplayRotation")+",density="+c.densityDpi
            +",dp="+c.screenWidthDp+"x"+c.screenHeightDp+",smallest="+c.smallestScreenWidthDp+"}";
    }
    void transaction(IBinder token,String item,int index,Object before,Object after,boolean owned){
        if(wmLock==null)return;
        try{synchronized(wmLock){
            Object r=record(token);if(!matches(r))return;
            if(before instanceof Configuration c){
                emit("TX_CONFIG item="+item+" arg="+index+" owned="+owned+identity(r)
                    +" before="+config(c)+" outgoing="+config((Configuration)after));
            }else if(before!=null&&before.getClass().getName().equals("android.util.MergedConfiguration")){
                emit("TX_CONFIG item="+item+" arg="+index+" owned="+owned+identity(r)
                    +" before="+config((Configuration)a.call(before,"getMergedConfiguration"))
                    +" outgoing="+config((Configuration)a.call(after,"getMergedConfiguration")));
            }
        }}catch(Exception e){emit("TRACE_ERROR "+FrameworkAccess.reason(e));}
    }
    void state(String event,Object r){
        if(wmLock==null)return;
        try{synchronized(wmLock){if(matches(r))emit(event+identity(r)+" effective="+config(a.config(r,false)));}}
        catch(Exception e){emit("TRACE_ERROR "+FrameworkAccess.reason(e));}
    }
    void resumed(IBinder token){
        if(wmLock==null)return;
        try{synchronized(wmLock){state("SERVER_RESUMED",record(token));}}
        catch(Exception e){emit("TRACE_ERROR "+FrameworkAccess.reason(e));}
    }
}
