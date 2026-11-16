package dev.local.sunwindowdemo;

import android.view.SurfaceControl;
import java.util.LinkedHashSet;
import java.util.Set;

/** A short-lived surface mask, not a change to task/window configuration.
 * Call under the WM lock. Once transferred/released, stale callbacks do nothing.
 */
final class TaskVisibilityGuard {
    private final FrameworkAccess a;
    final Object task;
    private final SurfaceControl surface;
    private volatile boolean finished;
    private final Set<SurfaceControl> leashes=new LinkedHashSet<>();
    private Boolean backdropOriginal;

    TaskVisibilityGuard(FrameworkAccess access,Object task)throws Exception {
        a=access;this.task=task;
        surface=(SurfaceControl)a.field(task,"mSurfaceControl");
        if(surface==null||!surface.isValid())throw new IllegalStateException("handoff surface unavailable");
    }

    boolean holding(){return !finished;}

    // Alpha alone does not change WM occlusion. Preserve the native underlying
    // activities before cancelling Back, while its launch-behind is still live.
    void preserveBackdrop()throws Exception {
        if(finished)throw new IllegalStateException("mask already released");
        if(backdropOriginal==null)backdropOriginal=a.bool(task,"isForceTranslucent");
        a.call(task,"setForceTranslucent",new Class<?>[]{boolean.class},true);
    }

    boolean originalTranslucent()throws Exception {
        return backdropOriginal!=null?backdropOriginal:a.bool(task,"isForceTranslucent");
    }

    // Call only after the Session has durably saved the original value.
    void adoptBackdrop(){backdropOriginal=null;}

    private void restoreBackdrop()throws Exception {
        if(backdropOriginal==null)return;
        a.call(task,"setForceTranslucent",new Class<?>[]{boolean.class},backdropOriginal);
        backdropOriginal=null;
    }

    void hide()throws Exception {
        if(finished)return;
        hold();
        commit(0);
    }

    void hold()throws Exception {
        if(finished)return;
        if(!surface.isValid()||a.field(task,"mSurfaceControl")!=surface)
            throw new IllegalStateException("handoff surface changed");
        // Keep the pending WM transaction consistent with the immediate mask.
        pending(0);
    }

    // Shell can reparent an Activity leash outside its task. Mask that exact
    // leash too, and restore it when ownership changes; never mask a display root.
    void inTransaction(SurfaceControl.Transaction transaction,SurfaceControl leash){
        if(finished)return;
        if(leash!=null&&leash.isValid()&&leash!=surface){
            leashes.add(leash);
        }
        for(SurfaceControl held:leashes)if(held.isValid())transaction.setAlpha(held,0);
        if(surface.isValid())transaction.setAlpha(surface,0);
    }

    void transfer()throws Exception {
        if(finished)return;
        restoreBackdrop();
        restoreLeashes(0);finished=true;
    }

    void release()throws Exception {release(1);}
    void release(float ownerAlpha)throws Exception {
        if(finished)return;
        // Restore only the exact surfaces we changed, never a replacement task.
        float alpha=Float.isFinite(ownerAlpha)?Math.max(0,Math.min(1,ownerAlpha)):0;
        if(surface.isValid())pending(alpha);
        restoreBackdrop();
        restoreLeashes(alpha);
        finished=true;
    }

    // A queued Shell transaction may commit after cancellation/timeout. Repair
    // its stale alpha=0 then; an acquired session supplies its current opacity.
    void afterTransaction(float ownerAlpha){
        if(!finished)return;
        restoreLeashes(ownerAlpha);
    }

    void continueWith(TaskVisibilityGuard next){
        if(!finished||next==this||!next.holding())return;
        // A newer handoff owns the same task. Let it restore any old detached
        // leashes too, instead of releasing them underneath the new mask.
        next.leashes.addAll(leashes);
        SurfaceControl.Transaction transaction=new SurfaceControl.Transaction();
        try{
            for(SurfaceControl leash:next.leashes)if(leash.isValid())transaction.setAlpha(leash,0);
            next.inTransaction(transaction,null);transaction.apply();
        }finally{transaction.close();}
    }

    private void restoreLeashes(float alpha){
        SurfaceControl.Transaction transaction=new SurfaceControl.Transaction();
        try{
            for(SurfaceControl leash:leashes)if(leash.isValid())transaction.setAlpha(leash,1);
            // Some leash wrappers refer to the primary Surface. Write it last.
            if(surface.isValid())transaction.setAlpha(surface,alpha);
            transaction.apply();
        }finally{transaction.close();}
    }

    private void pending(float alpha)throws Exception {
        a.call(a.call(task,"getPendingTransaction"),"setAlpha",
            new Class<?>[]{SurfaceControl.class,float.class},surface,alpha);
    }

    private void commit(float alpha){
        SurfaceControl.Transaction transaction=new SurfaceControl.Transaction();
        try{transaction.setAlpha(surface,alpha).apply();}finally{transaction.close();}
    }
}
