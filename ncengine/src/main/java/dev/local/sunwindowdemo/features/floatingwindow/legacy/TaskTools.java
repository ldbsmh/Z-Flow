package dev.local.sunwindowdemo;
import android.app.*;
import android.content.*;
import java.util.*;

final class TaskTools {
    static int liveDiagnostic=-1;
    static boolean diagnostic(Intent base){return base!=null&&base.getComponent()!=null
        &&Wire.PKG.equals(base.getComponent().getPackageName())
        &&(Wire.PKG+".DiagnosticActivity").equals(base.getComponent().getClassName())&&Wire.validToken(base.getIdentifier());}
    static void openDiagnostic(Activity c){
        for(ActivityManager.AppTask t:c.getSystemService(ActivityManager.class).getAppTasks())try{
            if(diagnostic(t.getTaskInfo().baseIntent)){t.moveToFront();return;}
        }catch(RuntimeException ignored){}
        c.startActivity(new Intent(c,DiagnosticActivity.class).setIdentifier(Wire.token()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    static void trimIdle(Activity c){
        List<ActivityManager.AppTask> all=c.getSystemService(ActivityManager.class).getAppTasks();int keep=liveDiagnostic;
        if(keep<0)for(ActivityManager.AppTask t:all)try{ActivityManager.RecentTaskInfo i=t.getTaskInfo();if(diagnostic(i.baseIntent))keep=Math.max(keep,i.taskId);}catch(RuntimeException ignored){}
        for(ActivityManager.AppTask t:all)try{
            ActivityManager.RecentTaskInfo i=t.getTaskInfo();Intent b=i.baseIntent;
            if(i.taskId==c.getTaskId()||i.taskId==keep||b==null||b.getComponent()==null)continue;
            boolean ownMain=Wire.PKG.equals(b.getComponent().getPackageName())&&(Wire.PKG+".MainActivity").equals(b.getComponent().getClassName());
            if(ownMain||diagnostic(b))t.finishAndRemoveTask();
        }catch(RuntimeException ignored){}
    }
}
