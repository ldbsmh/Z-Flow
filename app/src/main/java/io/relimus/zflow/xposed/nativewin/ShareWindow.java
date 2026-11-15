package io.relimus.zflow.xposed.nativewin;
package io.relimus.zflow.xposed.nativewin;

import android.app.ActivityOptions;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import io.github.libxposed.api.*;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/** Optional early native routing: keep the real Intent, grants, result chain and start verdict. */
final class ShareWindow {
    // Runs inside system_server; the optional hook preflights this exact API37 member.
    @android.annotation.SuppressLint({"BlockedPrivateApi","SoonBlockedPrivateApi"})
    static ActivityOptions launchOptions(ActivityOptions original,Rect bounds)throws Exception{
        ActivityOptions copy=original==null?ActivityOptions.makeBasic():(ActivityOptions)
            ActivityOptions.class.getDeclaredMethod("fromBundle",Bundle.class).invoke(null,original.toBundle());
        ActivityOptions.class.getDeclaredMethod("setLaunchWindowingMode",int.class).invoke(copy,5);
        copy.setLaunchBounds(new Rect(bounds));return copy;
    }
    // Also runs only in system_server after install() preflights these API37 members.
    @android.annotation.SuppressLint({"BlockedPrivateApi","SoonBlockedPrivateApi"})
    static ActivityOptions installerOptions(ActivityOptions original)throws Exception{
        ActivityOptions copy=original==null?ActivityOptions.makeBasic():(ActivityOptions)
            ActivityOptions.class.getDeclaredMethod("fromBundle",Bundle.class).invoke(null,original.toBundle());
        ActivityOptions.class.getDeclaredMethod("setLaunchWindowingMode",int.class).invoke(copy,1);
        copy.setLaunchBounds(null);
        ActivityOptions.class.getDeclaredMethod("setLaunchTaskId",int.class).invoke(copy,-1);
        ActivityOptions.class.getDeclaredMethod("setLaunchRootTask",Class.forName("android.window.WindowContainerToken")).invoke(copy,new Object[]{null});
        ActivityOptions.class.getDeclaredMethod("setLaunchTaskFragmentToken",android.os.IBinder.class).invoke(copy,new Object[]{null});
        return copy;
    }
    static boolean installerIntent(Intent intent){
        return intent!=null&&("application/vnd.android.package-archive".equalsIgnoreCase(intent.getType())
            ||"android.intent.action.INSTALL_PACKAGE".equals(intent.getAction())
            ||"android.content.pm.action.CONFIRM_INSTALL".equals(intent.getAction()));
    }
    static boolean eligibleIntent(Intent intent,boolean chooser){
        if(intent==null)return false;
        // Installers also arrive as VIEW(content://..., APK MIME), not only INSTALL_PACKAGE.
        if(installerIntent(intent))return false;
        String action=intent.getAction();
        if(intent.hasCategory(Intent.CATEGORY_HOME)||Intent.ACTION_CHOOSER.equals(action)
            ||Intent.ACTION_OPEN_DOCUMENT.equals(action)||Intent.ACTION_OPEN_DOCUMENT_TREE.equals(action)
            ||Intent.ACTION_CREATE_DOCUMENT.equals(action)||Intent.ACTION_GET_CONTENT.equals(action)
            ||Intent.ACTION_PICK.equals(action)||Intent.ACTION_PICK_ACTIVITY.equals(action)
            ||"android.provider.action.PICK_IMAGES".equals(action)
            ||Intent.ACTION_DIAL.equals(action)||Intent.ACTION_CALL.equals(action)
            ||"android.intent.action.CALL_EMERGENCY".equals(action)||"android.intent.action.CALL_PRIVILEGED".equals(action)
            ||"android.content.pm.action.REQUEST_PERMISSIONS".equals(action)
            ||"android.intent.action.INSTALL_PACKAGE".equals(action)||"android.intent.action.UNINSTALL_PACKAGE".equals(action)
            ||action!=null&&action.startsWith("android.settings."))return false;
        String scheme=intent.getData()==null?null:intent.getData().getScheme();
        return !"tel".equalsIgnoreCase(scheme)&&!"voicemail".equalsIgnoreCase(scheme);
    }
    static void install(XposedModule module,FrameworkAccess a,WindowEngine engine,Consumer<String> log){
        java.util.List<XposedInterface.HookHandle> handles=new java.util.ArrayList<>();
        try{
            ClassLoader loader=a.task.getClassLoader();
            String[] names={"com.android.server.wm.ActivityRecord","com.android.server.wm.ActivityRecord",
                "android.service.voice.IVoiceInteractionSession","com.android.internal.app.IVoiceInteractor",null,
                "android.app.ActivityOptions","com.android.server.wm.Task","com.android.server.wm.TaskFragment",
                "com.android.server.wm.BackgroundActivityStartController$BalVerdict","com.android.server.uri.NeededUriGrants",null,
                "com.android.server.wm.Transition",null,null};
            Class<?>[] parameters=new Class<?>[names.length];
            for(int i=0;i<names.length;i++)parameters[i]=names[i]==null?(i==12?boolean.class:int.class):Class.forName(names[i],false,loader);
            Class<?> starter=Class.forName("com.android.server.wm.ActivityStarter",false,loader);
            Method method;
            int taskArgument=6;
            try{method=a.method(starter,"startActivityUnchecked",parameters);}
            catch(NoSuchMethodException newer){
                Class<?>[] next=new Class<?>[16];
                System.arraycopy(parameters,0,next,0,6);
                next[6]=Class.forName("com.android.server.wm.TaskDisplayArea",false,loader);
                System.arraycopy(parameters,6,next,7,8);
                next[15]=Class.forName("com.android.server.wm.ActivityCallerState$CallerInfo",false,loader);
                method=a.method(starter,"startActivityUnchecked",next);parameters=next;taskArgument=7;
            }
            final int taskIndex=taskArgument,flagsIndex=taskArgument+4,argumentCount=parameters.length;
            if(method.getReturnType()!=int.class)throw new NoSuchMethodException("share start result");
            a.field(starter,"mLastStartActivityRecord");
            a.method(a.activity,"isResolverOrDelegateActivity");
            a.method(android.content.pm.PackageManager.class,"getPermissionControllerPackageName");
            a.method(ActivityOptions.class,"fromBundle",Bundle.class);
            a.method(ActivityOptions.class,"getLaunchWindowingMode");a.method(ActivityOptions.class,"setLaunchWindowingMode",int.class);
            a.method(ActivityOptions.class,"setLaunchTaskId",int.class);
            a.method(ActivityOptions.class,"setLaunchRootTask",Class.forName("android.window.WindowContainerToken",false,loader));
            a.method(ActivityOptions.class,"setLaunchTaskFragmentToken",android.os.IBinder.class);
            java.lang.reflect.Field starting=a.field(starter,"mStartActivity");
            Method targetTask=a.method(starter,"computeTargetTask");
            if(targetTask.getReturnType()!=a.task)throw new NoSuchMethodException("handoff target task");
            Method reusable=a.method(starter,"resolveReusableTask",boolean.class);
            if(reusable.getReturnType()!=a.task)throw new NoSuchMethodException("handoff reusable task");
            handles.add(module.hook(reusable).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();WindowEngine.ShareLaunch selected=engine.shareLaunch.get();
                // Shared affinities must not move the caller's fullscreen task into freeform.
                return selected!=null&&starting.get(chain.getThisObject())==selected.request()&&result==selected.sourceTask()?null:result;
            }));
            handles.add(module.hook(targetTask).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                WindowEngine.ShareLaunch selected=engine.shareLaunch.get();
                // Use native setNewTask without injecting NEW_TASK into the Intent:
                // that flag would cancel startActivityForResult before task selection.
                if(selected!=null&&starting.get(chain.getThisObject())==selected.request())return null;
                return chain.proceed();
            }));
            handles.add(module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                WindowEngine.NativeForeground previousForeground=engine.launchForeground.get();
                try{engine.launchForeground.set(engine.nativeForeground());}
                catch(Exception error){engine.launchForeground.remove();log.accept("WINDOW_FOREGROUND_SNAPSHOT_FAILED "+FrameworkAccess.reason(error));}
                try{
                WindowEngine.ShareLaunch selected=null;
                if(chain.getArg(taskIndex)==null&&chain.getArg(taskIndex+1)==null){
                    try{
                        selected=engine.prepareInstaller(chain.getArg(0),chain.getArg(1),(ActivityOptions)chain.getArg(5),(Integer)chain.getArg(flagsIndex));
                        if(selected==null)selected=engine.prepareShare(chain.getArg(0),chain.getArg(1),(ActivityOptions)chain.getArg(5),(Integer)chain.getArg(flagsIndex));
                    }
                    catch(Exception error){log.accept("SHARE_WINDOW_NATIVE "+FrameworkAccess.reason(error));}
                }
                if(selected==null)return chain.proceed();
                WindowEngine.ShareLaunch previous=engine.shareLaunch.get();engine.shareLaunch.set(selected);
                Object[] args=new Object[argumentCount];for(int i=0;i<args.length;i++)args[i]=chain.getArg(i);args[5]=selected.options();
                try{return chain.proceed(args);}
                finally{
                    engine.shareFinished(selected,chain.getThisObject());
                    if(previous==null)engine.shareLaunch.remove();else engine.shareLaunch.set(previous);
                }
                }finally{if(previousForeground==null)engine.launchForeground.remove();else engine.launchForeground.set(previousForeground);}
            }));
            log.accept("SHARE_WINDOW_READY inter-app source/result/URI chain preserved");
        }catch(Throwable error){for(XposedInterface.HookHandle handle:handles)try{handle.unhook();}catch(Throwable ignored){}log.accept("SHARE_WINDOW_UNAVAILABLE "+FrameworkAccess.reason(error));}
    }
}
