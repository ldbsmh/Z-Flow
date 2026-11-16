package dev.local.sunwindowdemo.core;

import android.content.*;
import android.content.pm.PackageManager;
import android.os.Process;
import java.lang.reflect.Method;
import java.util.concurrent.Executors;

/** Own enabled preference projected to PM for the next system_server startup, not live ownership. */
public final class WindowBackendEligibility extends BroadcastReceiver {
    private static final String CLASS="dev.local.sunwindowdemo.core.WindowBackendEligibility";
    private static final java.util.concurrent.Executor WORKER=Executors.newSingleThreadExecutor();
    @Override public void onReceive(Context context,Intent intent) {
        if(!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())&&!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()))return;
        PendingResult pending=goAsync();
        WORKER.execute(()->{try{projectOwn(context);}catch(Exception failure){android.util.Log.e("NativeComplements","WINDOW_ELIGIBILITY_MIGRATION_FAILED",failure);}finally{pending.finish();}});
    }
    public static void refreshAsync(Context context){
        Context app=context.getApplicationContext();
        WORKER.execute(()->{try{projectOwn(app);}catch(Exception failure){android.util.Log.e("NativeComplements","WINDOW_ELIGIBILITY_SAVE_FAILED",failure);}});
    }
    public static synchronized void projectOwn(Context context){
        if(!ModuleIdentity.CURRENT.packageName().equals(context.getPackageName()))throw new SecurityException("Own eligibility only");
        if(Process.myUid()/100000!=0)return; // The system backend uses only primary-user projection.
        if(!context.getSystemService(android.os.UserManager.class).isUserUnlocked())return;
        new dev.local.sunwindowdemo.Config(context); // Complete legacy CE migration before projection.
        saveOwn(context,context.getSharedPreferences("corner",0).getBoolean("enabled",false));
    }
    /** Called on the settings worker before committing the matching enabled preference. */
    public static synchronized void saveEnabled(Context context,boolean enabled){
        boolean previous=context.getSharedPreferences("corner",0).getBoolean("enabled",false);
        try{saveOwn(context,enabled);}catch(RuntimeException failure){
            try{saveOwn(context,previous);}catch(RuntimeException rollback){failure.addSuppressed(rollback);}
            throw failure;
        }
        if(!context.getSharedPreferences("corner",0).edit().putBoolean("enabled",enabled).commit()){
            saveOwn(context,previous);
            throw new IllegalStateException("Enabled preference not persisted");
        }
    }
    private static void saveOwn(Context context,boolean enabled){
        if(!ModuleIdentity.CURRENT.packageName().equals(context.getPackageName()))throw new SecurityException("Own eligibility only");
        if(Process.myUid()/100000!=0)return;
        ComponentName marker=new ComponentName(context.getPackageName(),CLASS);
        int target=enabled?PackageManager.COMPONENT_ENABLED_STATE_ENABLED:PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        PackageManager pm=context.getPackageManager();
        if(pm.getComponentEnabledSetting(marker)!=target)
            pm.setComponentEnabledSetting(marker,target,PackageManager.DONT_KILL_APP|PackageManager.SYNCHRONOUS);
        if(pm.getComponentEnabledSetting(marker)!=target)throw new IllegalStateException("Eligibility projection not persisted");
    }
    /** -1 means old/unmigrated, 0 disabled, 1 enabled. Never read foreign CE preferences. */
    public static int readSystem(ClassLoader loader,String product)throws ReflectiveOperationException {
        ModuleIdentity.forPackage(product);
        Object pm=Class.forName("android.app.ActivityThread",false,loader).getDeclaredMethod("getPackageManager").invoke(null);
        Class<?> api=Class.forName("android.content.pm.IPackageManager",false,loader);
        ComponentName marker=new ComponentName(product,CLASS);
        Method info=api.getMethod("getReceiverInfo",ComponentName.class,long.class,int.class);
        long flags=PackageManager.MATCH_DISABLED_COMPONENTS|PackageManager.MATCH_DIRECT_BOOT_AWARE|PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
        Object result=info.invoke(pm,marker,flags,0);
        if(result==null)return -1;
        if(!(result instanceof android.content.pm.ActivityInfo receiver)||receiver.exported||!product.equals(receiver.packageName)||!CLASS.equals(receiver.name))
            throw new IllegalStateException("Unexpected eligibility marker");
        int setting=(Integer)api.getMethod("getComponentEnabledSetting",ComponentName.class,int.class).invoke(pm,marker,0);
        return switch(setting){
            case PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> -1;
            case PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> 1;
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> 0;
            default -> throw new IllegalStateException("Unknown eligibility state");
        };
    }
}
