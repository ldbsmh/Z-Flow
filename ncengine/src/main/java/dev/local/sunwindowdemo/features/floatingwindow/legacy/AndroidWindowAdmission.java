package dev.local.sunwindowdemo;

import android.content.ComponentName;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;

/** Android task sampling and rollback snapshot only. Caller holds the WM lock; never mutates a task. */
final class AndroidWindowAdmission {
    private final FrameworkAccess a;
    private final Object atm;
    private final ThreadLocal<Object> resizeCandidate;
    AndroidWindowAdmission(FrameworkAccess access,Object atm,ThreadLocal<Object> resizeCandidate){
        a=access;this.atm=atm;this.resizeCandidate=resizeCandidate;
    }
    static void require(WindowAdmission.Rejection rejection){
        if(rejection==null)return;
        if(rejection==WindowAdmission.Rejection.WAIT)throw new WindowEngine.EntryNotReady();
        throw new IllegalStateException(rejection.message);
    }
    void validate(Object task,boolean allowTransition)throws Exception{
        require(WindowAdmission.opaque(a.bool(task,"isForceOpaque")));
        boolean multi=(Boolean)a.field(atm,"mSupportsMultiWindow");
        require(WindowAdmission.capability(multi,multi&&(Boolean)a.field(atm,"mSupportsFreeformWindowManagement")));
        boolean root=a.bool(task,"isRootTask"),leaf=root&&a.bool(task,"isLeafTask"),attached=leaf&&a.bool(task,"isAttached");
        require(WindowAdmission.hierarchy(new WindowAdmission.Hierarchy(root,leaf,attached,attached&&a.tda.isInstance(a.call(task,"getParent")))));
        Object display=a.call(task,"getDisplayContent");
        int displayId=display==null?-1:(Integer)a.field(display,"mDisplayId");
        int userId=display!=null&&displayId==0?(Integer)a.field(task,"mUserId"):-1;
        require(WindowAdmission.displayUser(display!=null,displayId,userId));
        require(WindowAdmission.activityType(a.integer(task,"getActivityType")));
        require(WindowAdmission.busy(a.entryBusy(task,allowTransition)));
        Object area=a.call(task,"getDisplayArea"),previous=resizeCandidate.get();resizeCandidate.set(task);
        try{
            boolean freeform=(Boolean)a.call(task,"supportsFreeformInDisplayArea",new Class<?>[]{a.tda},area);
            boolean valid=freeform&&(Boolean)a.call(area,"isValidWindowingMode",new Class<?>[]{int.class,a.activity,a.task},5,null,task);
            boolean bounds=valid&&a.bool(task,"isOverrideBoundsAllowed"),mode=bounds&&a.bool(task,"isOverrideWindowingModeAllowed");
            require(WindowAdmission.resize(new WindowAdmission.Resize(freeform,valid,bounds,mode)));
        }finally{if(previous==null)resizeCandidate.remove();else resizeCandidate.set(previous);}
    }
    WindowEngine.Session snapshot(Object task,String pkg,String token,boolean linked,TaskVisibilityGuard preparedMask)throws Exception{
        if(task==null)require(WindowAdmission.Rejection.MISSING);
        WindowEngine.Session s=new WindowEngine.Session();s.task=task;s.taskId=(Integer)a.field(task,"mTaskId");
        s.userId=(Integer)a.field(task,"mUserId");s.pkg=pkg;s.token=token;
        ComponentName component=(ComponentName)a.field(task,"realActivity");
        require(WindowAdmission.identity(pkg,component==null?null:component.getPackageName()));s.component=component.flattenToString();
        Object top=a.call(task,"topRunningActivity");if(top!=null)s.uid=a.integer(top,"getUid");
        Intent base=(Intent)a.call(task,"getBaseIntent");s.baseId=base==null?null:base.getIdentifier();
        require(WindowAdmission.diagnostic(Wire.PKG.equals(pkg),token,s.baseId));
        Point crop=(Point)a.field(task,"mLastSurfaceSize");s.crop=new Point(crop.x,crop.y);
        require(WindowAdmission.crop(s.crop.x,s.crop.y));validate(task,linked);
        boolean inherited=false;
        if(linked){int mode=a.integer(task,"getWindowingMode");inherited=WindowAdmission.inherited(true,mode,mode==5&&a.bool(task,"isAlwaysOnTop"));}
        if(!inherited){
            int mode=a.integer(task,"getWindowingMode");boolean alwaysOnTop=mode==1&&a.bool(task,"isAlwaysOnTop");
            require(WindowAdmission.ownership(false,mode,alwaysOnTop,mode==1&&!alwaysOnTop&&a.bool(task,"isFocusable")));
        }
        Object requested=a.windowConfig(task,true);s.originalConfig=new Configuration(a.config(task,true));
        s.mode=a.integer(requested,"getWindowingMode");s.topState=(Integer)a.field(requested,"mAlwaysOnTop");
        s.bounds=new Rect((Rect)a.call(requested,"getBounds"));
        if(inherited){
            // Save a local fullscreen endpoint for a direct launch that inherited its source's mode.
            s.originalConfig=new Configuration();Object original=a.field(s.originalConfig,"windowConfiguration");
            a.call(original,"setWindowingMode",new Class<?>[]{int.class},1);a.call(original,"setAlwaysOnTop",new Class<?>[]{int.class},2);
            s.mode=1;s.topState=2;s.bounds.setEmpty();
        }
        require(WindowAdmission.override(s.mode,s.bounds.isEmpty(),s.topState));
        s.alwaysOnTop=false;s.parent=a.call(task,"getParent");
        s.forceTranslucent=preparedMask!=null?preparedMask.originalTranslucent():a.bool(task,"isForceTranslucent");s.restoreTranslucent=true;
        Rect display=(Rect)a.call(a.call(task,"getDisplayContent"),"getBounds");s.logicalShort=Math.min(display.width(),display.height());
        s.orientationActivity=top;
        return s;
    }
}
