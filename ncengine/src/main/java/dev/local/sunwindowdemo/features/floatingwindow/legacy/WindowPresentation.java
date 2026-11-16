package dev.local.sunwindowdemo;

/** Presentation decisions only: no native objects, clocks, hooks or transactions. */
final class WindowPresentation {
    enum Route { UNMANAGED, RESTORING, NO_PLACEMENT, PRIORITY_HIDDEN, PRIORITY_SAME_TASK,
        SUSPENDED, START_MASK, ROTATION_MASK, PROBE_NATIVE, NATIVE_LEASH, COVER_REUSE, LIVE }
    record Facts(boolean managed,boolean attached,boolean restoring,boolean placement,
        boolean prioritySuspended,boolean priorityHideTask,boolean parked,boolean screenSuspended,
        boolean startMasked,boolean rotationHidden) {}
    /** Native leash/reuse probes run only after these gates, preserving the original read order. */
    static Route admission(boolean managed,boolean attached,boolean restoring,boolean placement){
        if(!managed||!attached)return Route.UNMANAGED;
        if(restoring)return Route.RESTORING;
        return placement?Route.PROBE_NATIVE:Route.NO_PLACEMENT;
    }
    static Route gate(Facts f){
        Route admission=admission(f.managed,f.attached,f.restoring,f.placement);
        if(admission!=Route.PROBE_NATIVE)return admission;
        if(f.prioritySuspended)return f.priorityHideTask||f.parked||f.screenSuspended?Route.PRIORITY_HIDDEN:Route.PRIORITY_SAME_TASK;
        if(f.parked||f.screenSuspended)return Route.SUSPENDED;
        if(f.startMasked)return Route.START_MASK;
        if(f.rotationHidden)return Route.ROTATION_MASK;
        return Route.PROBE_NATIVE;
    }
    static Route presentation(boolean nativeLeash,boolean reusableCover){
        return nativeLeash?Route.NATIVE_LEASH:reusableCover?Route.COVER_REUSE:Route.LIVE;
    }
    static boolean backing(boolean prioritySuspended,boolean parked,boolean screenSuspended){
        return !prioritySuspended&&!parked&&!screenSuspended;
    }
    static boolean hideOnlyHandle(boolean normal,boolean entering,boolean closing,boolean fullscreen){
        return normal&&!entering&&!closing&&!fullscreen;
    }
    static boolean adjustForIme(boolean normal,boolean resizing,boolean followingCover,boolean transitioning,boolean entering,boolean fullscreen){
        return normal&&!resizing&&!followingCover&&!transitioning&&!entering&&!fullscreen;
    }
    record Alpha(boolean prioritySuspended,boolean priorityHideTask,boolean parked,boolean screenSuspended,
        boolean restoring,boolean restoreForeground,boolean startMasked,boolean rotationMasked,
        boolean coverMasked,boolean coverExiting,float opacity,float rotationAlpha) {}
    static float alpha(Alpha a){
        if(a.prioritySuspended&&!a.priorityHideTask&&!a.screenSuspended&&!a.parked)return 1;
        return a.parked||a.restoring||a.priorityHideTask||a.screenSuspended||a.startMasked||a.coverMasked?0:a.opacity*a.rotationAlpha;
    }
    /** Cover release is not ordinary task alpha: foreground restoration deliberately reveals the owner. */
    static float coverReleaseAlpha(Alpha a){
        if(a.prioritySuspended&&!a.priorityHideTask&&!a.screenSuspended&&!a.parked)return 1;
        if(a.coverExiting||a.parked||a.priorityHideTask||a.screenSuspended)return 0;
        if(a.restoring)return a.restoreForeground?1:0;
        if(a.startMasked||a.rotationMasked)return 0;
        return a.opacity*a.rotationAlpha;
    }
    record Crop(int left,int top,int right,int bottom){ int height(){return bottom-top;} }
    static Crop crop(int width,int height,boolean main,int windowLeft,int windowTop,int windowRight,int windowBottom,
        int taskLeft,int taskTop,int taskRight,int taskBottom){
        int top=main&&windowLeft<=taskLeft&&windowRight>=taskRight&&windowBottom>=taskBottom
            &&windowTop>taskTop&&windowTop<taskBottom?windowTop-taskTop:0;
        return new Crop(0,top,width,height);
    }
    static float radius(Geometry.Placement placement,float density,float normalScale,float radiusFactor,int cropHeight){
        float visual=Math.min(density*MotionStyle.radius(placement.scale(),normalScale)*radiusFactor,
            Math.min(placement.width(),placement.height())*placement.scale()/2f);
        return Math.min(visual/placement.scale(),cropHeight/2f);
    }
}
