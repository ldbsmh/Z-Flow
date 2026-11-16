package dev.local.sunwindowdemo;

import android.graphics.Rect;

/** Native presentation execution. Caller owns the WM lock and pending/frame transaction. */
final class AndroidWindowPresentation {
    private final WindowEngine owner;
    AndroidWindowPresentation(WindowEngine owner){this.owner=owner;}
    void apply(WindowEngine.Session s,WindowPresentation.Route route)throws Exception{
        FrameworkAccess a=owner.a;
        switch(route){
            case PRIORITY_HIDDEN,PRIORITY_SAME_TASK -> {
                if(s.cover!=null){s.cover.ownerAlpha(s.priorityHideTask?0:1);s.cover.step(false,false);}
                if(s.decoration!=null)s.decoration.hide(s.task);
                // Holding the cover mask writes alpha: hide the native task LAST.
                if(route==WindowPresentation.Route.PRIORITY_HIDDEN){a.opacity(s.task,0);visible(s,false);return;}
                visible(s,true);a.opacity(s.task,1);
                Rect parent=(Rect)a.call(s.parent,"getBounds");Geometry.Placement p=s.placement;
                a.transform(s.task,p.scale(),p.x()-parent.left,p.y()-parent.top);return;
            }
            case SUSPENDED -> {
                visible(s,false);a.opacity(s.task,0);if(s.decoration!=null)s.decoration.hide(s.task);return;
            }
            case START_MASK -> {
                visible(s,true);s.startMask.hold();if(s.decoration!=null)s.decoration.hide(s.task);return;
            }
            case ROTATION_MASK -> {
                visible(s,true);
                try{if(s.rotationMask!=null)s.rotationMask.hold();}
                catch(Throwable error){owner.rotationFailure(s,error);}
                if(s.decoration!=null)s.decoration.hide(s.task);return;
            }
            case PROBE_NATIVE -> { visible(s,true); }
            default -> throw new IllegalArgumentException("non-executable presentation route: "+route);
        }
        // Never probe a leash or cover before the suspension/mask gates above.
        boolean leashed=a.presentationLeashed(s.task);
        boolean reusable=!leashed&&s.cover!=null&&s.cover.reusesPresentation(s.placement);
        switch(WindowPresentation.presentation(leashed,reusable)){
            case NATIVE_LEASH -> {
                if(s.entering||s.closing)a.opacity(s.task,0);
                if(s.cover==null&&s.decoration!=null){
                    if(WindowPresentation.hideOnlyHandle("normal".equals(s.frame),s.entering,s.closing,s.fullTransition))s.decoration.hideHandle(s.task);
                    else s.decoration.hide(s.task);
                }
            }
            case COVER_REUSE -> {
                s.cover.mask.hold();
                owner.updateDecoration(s,s.placement,s.cover.preparedDensity(),!s.presented&&!a.inFrame()&&!s.backRequested);
            }
            case LIVE -> live(s);
            default -> throw new IllegalStateException("unexpected native presentation route");
        }
    }
    void visible(WindowEngine.Session s,boolean visible)throws Exception{
        FrameworkAccess a=owner.a;
        if(visible&&!s.surfaceHidden)return;
        // Alpha zero leaves embedded input channels touchable; native hide covers the subtree.
        a.call(a.transaction(s.task),visible?"show":"hide",new Class<?>[]{android.view.SurfaceControl.class},a.field(s.task,"mSurfaceControl"));
        s.surfaceHidden=!visible;
    }
    private void live(WindowEngine.Session s)throws Exception{
        FrameworkAccess a=owner.a;
        if(WindowPresentation.adjustForIme("normal".equals(s.frame),s.resizing,s.coverFollowing,s.transitioning,s.entering,s.fullTransition)){
            Geometry.Placement adjusted=owner.normalForIme(s);
            if(adjusted!=null&&!adjusted.equals(s.placement)){s.placement=adjusted;owner.imePlacementChanged(s);}
        }
        Rect parent=(Rect)a.call(s.parent,"getBounds");Geometry.Placement p=s.placement;
        a.transform(s.task,p.scale(),p.x()-parent.left,p.y()-parent.top);
        float density=a.config(s.task,false).densityDpi/160f;
        Object main=a.call(s.task,"getTopVisibleAppMainWindow");
        WindowPresentation.Crop crop;
        if(main==null)crop=WindowPresentation.crop(p.width(),p.height(),false,0,0,0,0,0,0,0,0);
        else{
            Rect window=(Rect)a.call(main,"getFrame"),bounds=(Rect)a.call(s.task,"getBounds");
            crop=WindowPresentation.crop(p.width(),p.height(),true,window.left,window.top,window.right,window.bottom,
                bounds.left,bounds.top,bounds.right,bounds.bottom);
        }
        Rect nativeCrop=new Rect(crop.left(),crop.top(),crop.right(),crop.bottom());
        a.style(s.task,nativeCrop,WindowPresentation.radius(p,density,s.defaultScale,s.radiusFactor,crop.height()));
        owner.presentationCropChanged(s,nativeCrop,main);
        a.opacity(s.task,owner.presentationAlpha(s));
        if(s.cover!=null)s.cover.prepared(p,density);
        owner.updateDecoration(s,p,density,!a.inFrame()&&!s.backRequested);
    }
}
