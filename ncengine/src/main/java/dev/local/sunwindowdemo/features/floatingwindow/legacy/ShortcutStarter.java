package dev.local.sunwindowdemo;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** The real LauncherApps PendingIntent, with the user's sender admission and native verdict. */
final class ShortcutStarter {
    @android.annotation.SuppressLint({"BlockedPrivateApi","SoonBlockedPrivateApi"})
    static android.os.IBinder sender(PendingIntent pending)throws Exception{
        Object target=PendingIntent.class.getDeclaredMethod("getTarget").invoke(pending);
        return ((android.os.IInterface)target).asBinder();
    }
    // System-server only, exact API37 method recorded in launch-input-members.json.
    @android.annotation.SuppressLint({"BlockedPrivateApi","SoonBlockedPrivateApi"})
    static int send(PendingIntent pending,Context context,Intent fillIn,PendingIntent.OnFinished finished,
            Handler handler,ActivityOptions options)throws Exception{
        options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS);
        Method send=PendingIntent.class.getDeclaredMethod("sendAndReturnResult",Context.class,int.class,Intent.class,
            PendingIntent.OnFinished.class,Handler.class,String.class,Bundle.class);
        if(send.getReturnType()!=int.class)throw new NoSuchMethodException("shortcut start verdict");
        try{return (Integer)send.invoke(pending,context,0,fillIn,finished,handler,null,options.toBundle());}
        catch(InvocationTargetException error){
            if(error.getCause() instanceof Exception cause)throw cause;
            throw error;
        }
    }
}
