package dev.local.sunwindowdemo.core;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import java.lang.reflect.Method;

/** Own PM component state persists one startup choice without cross-app files or root writes. */
public final class WindowBackendSelection extends BroadcastReceiver {
    public static final ComponentName MARKER=new ComponentName(ModuleIdentity.FULL_PACKAGE,"dev.local.sunwindowdemo.core.WindowBackendSelection");
    public WindowBackendSelection() {}
    @Override public void onReceive(Context context,Intent intent) {} // No filter and not exported; data marker only.
    public static String read(Context context) {
        int state=context.getPackageManager().getComponentEnabledSetting(MARKER);
        return selected(state);
    }
    public static String selected(int state) {
        return switch(state){
            case PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> ""; // Preserve existing installations until explicitly chosen.
            case PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> ModuleIdentity.WINDOW_PACKAGE;
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> ModuleIdentity.FULL_PACKAGE;
            default -> throw new IllegalStateException("Unknown window backend choice");
        };
    }
    public static void save(Context context,String product) {
        if(!ModuleIdentity.FULL_PACKAGE.equals(context.getPackageName())||android.os.Process.myUid()/100000!=0)
            throw new SecurityException("Only the main user's full app selects the window backend");
        ModuleIdentity.forPackage(product);
        int state=ModuleIdentity.WINDOW_PACKAGE.equals(product)?PackageManager.COMPONENT_ENABLED_STATE_ENABLED:PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        context.getPackageManager().setComponentEnabledSetting(MARKER,state,PackageManager.DONT_KILL_APP|PackageManager.SYNCHRONOUS);
        if(!product.equals(read(context)))throw new IllegalStateException("Window backend choice not persisted");
    }
    /** Fixed user 0: system_server must not adopt another user's pending UI choice. */
    public static String readSystem(ClassLoader loader)throws ReflectiveOperationException {
        Class<?> thread=Class.forName("android.app.ActivityThread",false,loader);
        Object pm=thread.getDeclaredMethod("getPackageManager").invoke(null);
        Class<?> api=Class.forName("android.content.pm.IPackageManager",false,loader);
        Method info=api.getMethod("getReceiverInfo",ComponentName.class,long.class,int.class);
        long flags=PackageManager.MATCH_DISABLED_COMPONENTS|PackageManager.MATCH_DIRECT_BOOT_AWARE|PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
        Object marker=info.invoke(pm,MARKER,flags,0);
        if(marker==null)return ""; // Full app absent/old: retain first-claim compatibility, not a fabricated selection.
        if(!(marker instanceof android.content.pm.ActivityInfo receiver)||!MARKER.getPackageName().equals(receiver.packageName)
                ||!MARKER.getClassName().equals(receiver.name)||receiver.exported)throw new IllegalStateException("Unexpected backend marker");
        Method setting=api.getMethod("getComponentEnabledSetting",ComponentName.class,int.class);
        return selected((Integer)setting.invoke(pm,MARKER,0));
    }
}
