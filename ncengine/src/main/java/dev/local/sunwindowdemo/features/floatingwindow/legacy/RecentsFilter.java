package dev.local.sunwindowdemo;

import android.content.Intent;
import android.os.*;
import io.github.libxposed.api.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;

/** Filters historical cards while preserving native running-task animation identity. */
final class RecentsFilter {
    private final Method plan,contains,tasks,reload;
    private final Field applied;
    private final Consumer<String> log;
    private final Set<Object> views=Collections.newSetFromMap(new WeakHashMap<>());
    private final Handler main=new Handler(Looper.getMainLooper());
    private Set<Integer> hidden=Set.of();

    RecentsFilter(ClassLoader cl,Consumer<String> logger) throws Exception {
        log=logger;Class<?> rv=Class.forName("com.android.quickstep.views.RecentsView",false,cl);
        Class<?> group=Class.forName("com.android.quickstep.util.GroupTask",false,cl);
        plan=rv.getDeclaredMethod("applyLoadPlan",List.class,int.class);plan.setAccessible(true);
        contains=group.getDeclaredMethod("containsTask",int.class);tasks=group.getDeclaredMethod("getTasks");
        reload=rv.getDeclaredMethod("reloadIfNeeded");reload.setAccessible(true);
        applied=rv.getDeclaredField("mAppliedTaskListChangeId");applied.setAccessible(true);
    }
    void install(XposedModule module){
        List<XposedInterface.HookHandle> hooks=new ArrayList<>();
        try{
            hooks.add(module.hook(plan).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                List<?> original=(List<?>)chain.getArg(0);
                views.add(chain.getThisObject());
                if(hidden.isEmpty()||original==null)return chain.proceed();
                List<Object> filtered=new ArrayList<>();
                for(Object group:original){
                    boolean own=false;
                    if(((List<?>)tasks.invoke(group)).size()==1)for(int id:hidden)if((Boolean)contains.invoke(group,id)){own=true;break;}
                    if(!own)filtered.add(group);
                }
                return chain.proceed(new Object[]{filtered,chain.getArg(1)});
            }));
            // Keep the native running-task stub: it anchors recents/home animation identity.
            log.accept("RECENTS_READY hooks=1");
        }catch(Throwable e){for(XposedInterface.HookHandle h:hooks)try{h.unhook();}catch(Throwable ignored){}throw e;}
    }
    void configure(Intent i){
        int[] ids=i.getIntArrayExtra("hiddenTaskIds");Set<Integer> next=new HashSet<>();
        if(ids!=null)for(int id:ids)if(id>=0)next.add(id);
        if(next.equals(hidden))return;hidden=Set.copyOf(next);
        main.post(()->{
            for(Object view:new ArrayList<>(views))try{
                applied.setInt(view,-1);reload.invoke(view);
            }catch(Exception e){log.accept("RECENTS_REFRESH_FAILED "+e.getClass().getSimpleName());}
            log.accept("RECENTS_FILTER task="+hidden);
        });
    }
}
