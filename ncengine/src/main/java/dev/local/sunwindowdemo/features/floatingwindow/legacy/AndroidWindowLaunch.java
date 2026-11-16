package dev.local.sunwindowdemo;

import android.os.IBinder;

/** One native PendingIntent send. The engine owns this ticket's monitor and AtomicReference lifetime. */
final class AndroidWindowLaunch {
    final WindowNavigation.Launch state;
    final IBinder sender;
    volatile Object task;
    WindowEngine.NativeForeground foreground;
    AndroidWindowLaunch(WindowNavigation.Launch state,IBinder sender){this.state=state;this.sender=sender;}
    boolean accepts(long current,IBinder originatingSender,Object sourceTask){
        if(state.generation!=current)return false;
        boolean sameSender=sender!=null&&sender.equals(originatingSender);
        return state.accepts(current,sameSender,!sameSender&&task!=null&&task==sourceTask);
    }
    boolean targetAllowed(String pkg,int user){return state.targetAllowed(pkg,user,Wire.allowedPackage(pkg));}
}
