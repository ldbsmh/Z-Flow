package dev.local.sunwindowdemo;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Process;
import android.service.notification.StatusBarNotification;
import android.view.Display;
import io.github.libxposed.api.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;

/** Redirect only the activity send inside a real native notification-body click. */
final class NotificationWindow {
    private record Click(StatusBarNotification notice,PendingIntent pending) {}
    private final ThreadLocal<Click> current=new ThreadLocal<>();
    private final Field runEntry,runPending,runAnimate,startEntry,startPending,startFill,startDisplay,sbn;
    private final Method run,start;
    private NotificationWindow(ClassLoader loader,Consumer<String> log)throws Exception{
        String base="com.android.systemui.statusbar.phone.StatusBarNotificationActivityStarter";
        Class<?> entry=Class.forName("com.android.systemui.statusbar.notification.collection.NotificationEntry",false,loader);
        Class<?> runner=clickAdapter(loader,base,entry,true);
        Class<?> sender=clickAdapter(loader,base,entry,false);
        run=runner.getDeclaredMethod("run");start=sender.getDeclaredMethod("startPendingIntent",Class.forName("android.view.RemoteAnimationAdapter",false,loader));
        if(run.getReturnType()!=void.class||start.getReturnType()!=int.class)throw new NoSuchMethodException("notification click signatures");
        runEntry=field(runner,"f$1",entry);runPending=field(runner,"f$3",PendingIntent.class);runAnimate=field(runner,"f$5",boolean.class);
        startEntry=field(sender,"f$5",entry);startPending=field(sender,"f$3",PendingIntent.class);
        startFill=field(sender,"f$4",Intent.class);startDisplay=field(sender,"f$2",int.class);
        sbn=field(entry,"mSbn",StatusBarNotification.class);
    }
    private static Class<?> clickAdapter(ClassLoader loader,String base,Class<?> entry,boolean runner)throws Exception{
        for(int number:runner?new int[]{7,6}:new int[]{10,12})try{
            Class<?> type=Class.forName(base+"$$ExternalSyntheticLambda"+number,false,loader);
            field(type,runner?"f$1":"f$5",entry);
            field(type,"f$3",PendingIntent.class);
            if(runner){field(type,"f$5",boolean.class);type.getDeclaredMethod("run");}
            else{field(type,"f$4",Intent.class);field(type,"f$2",int.class);type.getDeclaredMethod("startPendingIntent",Class.forName("android.view.RemoteAnimationAdapter",false,loader));}
            return type;
        }catch(ClassNotFoundException|NoSuchFieldException|NoSuchMethodException mismatch){ }
        throw new NoSuchMethodException("notification click adapter");
    }
    private static Field field(Class<?> owner,String name,Class<?> type)throws Exception{
        Field f=owner.getDeclaredField(name);if(f.getType()!=type||Modifier.isStatic(f.getModifiers()))throw new NoSuchFieldException(name);
        f.setAccessible(true);return f;
    }
    static boolean contentLaunch(Notification n,PendingIntent pending){
        return n!=null&&pending!=null&&pending.isActivity()&&pending.equals(n.contentIntent)
            &&(n.flags&Notification.FLAG_GROUP_SUMMARY)==0&&n.fullScreenIntent==null
            &&!Notification.CATEGORY_CALL.equals(n.category)&&!Notification.CATEGORY_ALARM.equals(n.category);
    }
    static boolean backgroundTarget(String notificationPackage,String foregroundPackage){
        return notificationPackage!=null&&foregroundPackage!=null&&!notificationPackage.equals(foregroundPackage);
    }
    private Click eligible(Object entry,PendingIntent pending)throws Exception{
        SystemUiHost host=SystemUiHost.live;if(host==null||!host.notificationWindowReady()||entry==null)return null;
        StatusBarNotification notice=(StatusBarNotification)sbn.get(entry);
        if(notice==null||!contentLaunch(notice.getNotification(),pending)||!Process.myUserHandle().equals(notice.getUser())
            ||!backgroundTarget(notice.getPackageName(),host.notificationForegroundPackage())
            ||!Process.myUserHandle().equals(pending.getCreatorUserHandle())
            ||Wire.PKG.equals(notice.getPackageName())||BackBridge.PACKAGE.equals(notice.getPackageName())||"android".equals(notice.getPackageName()))return null;
        return new Click(notice,pending);
    }
    static void install(XposedModule module,ClassLoader loader,Consumer<String> log){
        List<XposedInterface.HookHandle> hooks=new ArrayList<>();
        try{
            NotificationWindow route=new NotificationWindow(loader,log);
            hooks.add(module.hook(route.run).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object owner=chain.getThisObject();Click previous=route.current.get(),selected=null;boolean animated=false;
                try{
                    selected=route.eligible(route.runEntry.get(owner),(PendingIntent)route.runPending.get(owner));
                    if(selected!=null){animated=route.runAnimate.getBoolean(owner);route.runAnimate.setBoolean(owner,false);route.current.set(selected);}
                }catch(Exception error){log.accept("NOTIFICATION_WINDOW_NATIVE "+error.getClass().getSimpleName());}
                try{return chain.proceed();}
                finally{if(selected!=null){try{route.runAnimate.setBoolean(owner,animated);}catch(Exception error){log.accept("NOTIFICATION_WINDOW_RESTORE "+error.getClass().getSimpleName());}finally{if(previous==null)route.current.remove();else route.current.set(previous);}}}
            }));
            hooks.add(module.hook(route.start).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                try{
                    Click selected=route.current.get();Object owner=chain.getThisObject();SystemUiHost host=SystemUiHost.live;
                    if(selected!=null&&host!=null&&host.notificationWindowReady()&&route.startDisplay.getInt(owner)==Display.DEFAULT_DISPLAY
                        &&backgroundTarget(selected.notice.getPackageName(),host.notificationForegroundPackage())
                        &&selected.pending.equals(route.startPending.get(owner))
                        &&selected.notice.equals(route.sbn.get(route.startEntry.get(owner)))){
                        host.openNotification(selected.notice,selected.pending,(Intent)route.startFill.get(owner));
                        log.accept("NOTIFICATION_WINDOW_REQUEST package="+selected.notice.getPackageName());return 0;
                    }
                }catch(Exception error){log.accept("NOTIFICATION_WINDOW_NATIVE "+error.getClass().getSimpleName());}
                return chain.proceed();
            }));
            module.deoptimize(route.run);
            log.accept("NOTIFICATION_WINDOW_READY hooks="+hooks.size());
        }catch(Throwable error){for(XposedInterface.HookHandle h:hooks)try{h.unhook();}catch(Throwable ignored){}log.accept("NOTIFICATION_WINDOW_UNAVAILABLE "+error.getClass().getSimpleName());}
    }
}
