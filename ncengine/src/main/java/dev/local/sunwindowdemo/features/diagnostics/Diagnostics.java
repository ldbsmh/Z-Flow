package dev.local.sunwindowdemo.features.diagnostics;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import dev.local.sunwindowdemo.BuildConfig;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Safe hook API: no Binder, filesystem or raw exception inspection on the calling thread. */
public final class Diagnostics {
    public static final String APP = "app", SYSTEM = "system", SYSTEM_UI = "systemui", LAUNCHER = "launcher";
    public static final Uri URI = Uri.parse("content://" + BuildConfig.MODULE_PACKAGE + ".diagnostics");
    private static final ArrayBlockingQueue<String> QUEUE = new ArrayBlockingQueue<>(64);
    private static volatile Context application;
    private static volatile String processSource;
    private static volatile boolean enabled;
    private static volatile boolean observing;
    private static final java.util.concurrent.atomic.AtomicBoolean REFRESH_PENDING=new java.util.concurrent.atomic.AtomicBoolean();
    private static void requestRefresh(Context context){
        if(REFRESH_PENDING.compareAndSet(false,true))WORKER.execute(()->{
            try{refresh(context);}finally{REFRESH_PENDING.set(false);}
        });
    }
    private static final java.util.concurrent.ScheduledExecutorService WORKER=Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t=new Thread(r,"module-diagnostics");t.setDaemon(true);return t;
    });
    static { WORKER.scheduleWithFixedDelay(Diagnostics::flush,500,500,TimeUnit.MILLISECONDS); }
    private Diagnostics() {}
    /** Call once when a scoped module process obtains a usable Context. */
    public static void initialize(Context context, String source) { event(context, source, "process_loaded"); }
    public static void event(Context context, String source, String event) { event(context, source, event, ""); }
    /** exception is a whitelisted simple name, NOT getMessage(), toString(), a stack or class path. */
    public static void event(Context context, String source, String event, String exception) {
        if (context == null || source == null || event == null || exception == null
                || !DiagnosticsBuffer.valid(source, event, exception)) return;
        if (application == null) {
            Context app = context.getApplicationContext();
            processSource = source;
            application = app == null ? context : app;
        }
        // ponytail: fixed 64-event queue; overflow drops instead of blocking a host hook.
        QUEUE.offer(source + ":" + event + ":" + exception);
    }
    private static synchronized void refresh(Context context){
        try{
            Bundle state=context.getContentResolver().call(URI,"state",null,null);
            boolean next=state!=null&&state.getBoolean("recording");
            if(next&&!enabled){QUEUE.clear();QUEUE.offer(processSource+":process_loaded:");}
            enabled=next;
        }catch(RuntimeException unavailable){enabled=false;}
    }
    private static void flush() {
        Context context = application;
        if (context == null) return;
        try {
            if (!observing) {
                context.getContentResolver().registerContentObserver(URI,true,new android.database.ContentObserver(null){
                    @Override public void onChange(boolean self){ requestRefresh(context); }
                });
                observing=true;refresh(context);
            }
            StringBuilder batch = new StringBuilder();
            for (int i = 0; i < 32; i++) {
                String line = QUEUE.poll();
                if (line == null) break;
                if (enabled) batch.append(line).append('\n');
            }
            if (batch.length() != 0) context.getContentResolver().call(URI, "events", batch.toString(), null);
        } catch (RuntimeException ignored) {
            // Fail closed; never recursively diagnose failures or retain a retry backlog.
            QUEUE.clear(); enabled = false;
        }
    }
}
