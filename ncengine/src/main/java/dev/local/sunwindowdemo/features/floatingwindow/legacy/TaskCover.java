package dev.local.sunwindowdemo;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.Surface;
import android.view.SurfaceControl;
import android.hardware.display.DisplayManager;
import android.view.Display;

/** A temporary sibling subtree. App buffers and lifecycle continue underneath it. */
final class TaskCover {
    TaskVisibilityGuard mask;
    private final FrameworkAccess a;
    private final Context iconContext;
    private final String pkg;
    private boolean iconDrawn;
    private Object task,parent;
    private SurfaceControl root,background,icon;
    private final RectF from,to;
    private float radiusFrom;
    private float radiusTo;
    private Rect screenBounds;
    private float screenRadius;
    private final int iconSize;
    private final float splashPx;
    private final float[] color;
    private long began=SystemClock.elapsedRealtime(),notBefore;
    private final long duration,fadeDuration;
    private long entryAt=-1,fadeAt=-1,hiddenAt=-1;
    private long frameClock=-1;
    private boolean closed,exiting;
    private float ownerAlpha;
    private boolean fadeIn;
    private TaskDecoration decoration;
    private Geometry.Placement decorationCanvas;
    private final RectF decorationBounds=new RectF();
    private float decorationDensity,decorationAlpha,exitAlpha=1;
    private long decorationAt=-1;
    private boolean decorationLandscape,decorationEntry,presentationVisible=true;
    private int preparedWidth,preparedHeight;
    private int reusedFrames,preparedFrames;
    private float preparedDensity,drawnAlpha;
    private boolean preparingReveal,gestureHold;
    private long motionAt=-1,gestureAt=-1;
    private float drawnRadius,exitCoverAlpha=1;
    @FunctionalInterface interface Reveal { void prepare() throws Exception; }
    private Reveal beforeReveal;
    TaskCover(FrameworkAccess a,Context context,Object task,Object parent,String pkg,
            RectF from,RectF to,float radiusFrom,float radiusTo,float ownerAlpha,boolean fadeIn)throws Exception{
        this.a=a;this.task=task;this.parent=parent;this.from=new RectF(from);this.to=new RectF(to);
        iconContext=context;this.pkg=pkg;
        refreshScreenCorners();
        this.radiusFrom=cornerRadius(from,screenBounds,screenRadius,radiusFrom);
        this.radiusTo=cornerRadius(to,screenBounds,screenRadius,radiusTo);
        this.ownerAlpha=ownerAlpha;this.fadeIn=fadeIn;
        float density=context.getResources().getDisplayMetrics().density;
        float shade=(context.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES?.09f:.94f;
        color=new float[]{shade,shade,shade};
        splashPx=162*density;
        iconSize=Math.max(48,Math.min(512,Math.round(splashPx)));
        float animationScale=android.provider.Settings.Global.getFloat(context.getContentResolver(),
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,1f);
        duration=Math.max(0,Math.round(320*animationScale));fadeDuration=Math.max(0,Math.round(150*animationScale));
        mask=new TaskVisibilityGuard(a,task);
        try{
            root=build("NativeComplements-cover-root",-1,(SurfaceControl)a.field(parent,"mSurfaceControl"));
            background=build("NativeComplements-cover",0,root);
            icon=build("NativeComplements-cover-icon",iconSize,root);
            paint(0,fadeIn?0:1);mask.hide();
        }catch(Throwable error){close();throw error;}
    }
    private void drawIcon()throws Exception{
        if(iconDrawn)return;
        // An unknown snapshot is not an explicit choice to use the application original.
        if(!dev.local.sunwindowdemo.features.iconlayers.IconAssets.ready())return;
        Drawable original;
        try{original=iconContext.getPackageManager().getApplicationIcon(pkg);}
        catch(android.content.pm.PackageManager.NameNotFoundException missing){original=iconContext.getPackageManager().getDefaultActivityIcon();}
        Drawable drawable=dev.local.sunwindowdemo.features.iconlayers.IconAssets.colored(iconContext,pkg);
        if(drawable==null)drawable=original;
        Surface canvasSurface=new Surface(icon);
        try{
            Canvas canvas=canvasSurface.lockCanvas(null);
            try{canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR);drawable.setBounds(0,0,iconSize,iconSize);drawable.draw(canvas);}
            finally{canvasSurface.unlockCanvasAndPost(canvas);}
        }finally{canvasSurface.release();}
        iconDrawn=true;
    }
    private SurfaceControl build(String name,int size,SurfaceControl parentSurface)throws Exception{
        Object b=Class.forName("android.view.SurfaceControl$Builder").getDeclaredConstructor().newInstance();
        a.call(b,"setName",new Class<?>[]{String.class},name);
        a.call(b,"setParent",new Class<?>[]{SurfaceControl.class},parentSurface);
        a.call(b,"setHidden",new Class<?>[]{boolean.class},true);
        if(size<0)a.call(b,"setContainerLayer");
        else if(size==0)a.call(b,"setColorLayer");
        else{
            a.call(b,"setBufferSize",new Class<?>[]{int.class,int.class},size,size);
            a.call(b,"setFormat",new Class<?>[]{int.class},PixelFormat.TRANSLUCENT);
        }
        return (SurfaceControl)a.call(b,"build");
    }
    void ownerAlpha(float alpha){ownerAlpha=alpha;}
    void beforeReveal(Reveal callback){beforeReveal=callback;}
    boolean canRetarget(){return !closed&&!exiting&&fadeAt<0&&mask.holding();}
    /** Move the existing opaque surfaces to the next task without replaying the splash fade. */
    void handoff(Object nextTask,Object nextParent,RectF bounds,float radius)throws Exception{
        if(!canRetarget())throw new IllegalStateException("cover already revealing");
        TaskVisibilityGuard nextMask=new TaskVisibilityGuard(a,nextTask);
        nextMask.hide();
        try{
            SurfaceControl parentSurface=(SurfaceControl)a.field(nextParent,"mSurfaceControl");
            SurfaceControl anchor=(SurfaceControl)a.field(nextTask,"mSurfaceControl");
            if(parentSurface==null||!parentSurface.isValid()||anchor==null||!anchor.isValid())
                throw new IllegalStateException("cover handoff surface detached");
            try(SurfaceControl.Transaction tx=new SurfaceControl.Transaction()){
                tx.reparent(root,parentSurface);
                a.call(tx,"setRelativeLayer",new Class<?>[]{SurfaceControl.class,SurfaceControl.class,int.class},root,anchor,1);
                tx.apply();
            }
            mask.release(0);
            mask=nextMask;task=nextTask;parent=nextParent;
            refreshScreenCorners();
            from.set(bounds);to.set(bounds);radiusFrom=radiusTo=cornerRadius(bounds,screenBounds,screenRadius,radius);
            began=SystemClock.elapsedRealtime();entryAt=fadeAt=hiddenAt=-1;
            fadeIn=false;notBefore=0;motionAt=gestureAt=-1;gestureHold=false;preparedWidth=preparedHeight=0;
            decoration=null;decorationAt=-1;exitAlpha=1;
            paint(1,1);
        }catch(Exception error){
            try{nextMask.release(0);}
            catch(Exception cleanup){error.addSuppressed(cleanup);android.util.Log.w("NativeComplements","COVER_HANDOFF_MASK_RELEASE_FAILED",cleanup);}
            throw error;
        }
    }
    void prepared(Geometry.Placement canvas,float density){
        preparedFrames++;
        preparedWidth=canvas.width();preparedHeight=canvas.height();preparedDensity=density;
    }
    boolean reusesPresentation(Geometry.Placement canvas){
        boolean reuse=!closed&&!preparingReveal&&presentationVisible&&drawnAlpha>=1&&mask.holding()
            &&preparedWidth==canvas.width()&&preparedHeight==canvas.height();
        if(reuse)reusedFrames++;
        return reuse;
    }
    float preparedDensity(){return preparedDensity;}
    String state(){
        return "closed="+closed+" exiting="+exiting+" holding="+mask.holding()+" fading="+(fadeAt>=0)
            +" alpha="+drawnAlpha+" reusedFrames="+reusedFrames+" preparedFrames="+preparedFrames+" from="+from+" to="+to
            +" gestureHold="+gestureHold+" rootValid="+(root!=null&&root.isValid())
            +" backgroundValid="+(background!=null&&background.isValid())+" iconValid="+(icon!=null&&icon.isValid());
    }
    private boolean continuousDim;
    void continuousDim(){continuousDim=true;}
    void entryDecorations(boolean entering){decorationEntry=entering;}
    boolean decorate(TaskDecoration value,Geometry.Placement canvas,float density,boolean landscape,float alpha,boolean structural)throws Exception{
        decoration=value;decorationCanvas=canvas;decorationDensity=density;decorationLandscape=landscape;decorationAlpha=alpha;
        return decorate(a.transaction(task),structural);
    }
    private boolean decorate(Object tx,boolean structural)throws Exception{
        if(decoration==null)return false;
        float scale=decorationBounds.width()/decorationCanvas.width();
        Geometry.Placement pose=new Geometry.Placement(decorationCanvas.width(),Math.max(1,Math.round(decorationBounds.height()/scale)),
            scale,decorationBounds.left,decorationBounds.top);
        MotionStyle.Decoration entry=decorationEntry?MotionStyle.entryDecoration(decorationProgress(),fadeIn):new MotionStyle.Decoration(1,1);
        float alpha=presentationVisible?decorationAlpha*exitAlpha:0;
        boolean drawn=decoration.update(task,parent,pose,decorationDensity,decorationLandscape,alpha*entry.handle(),continuousDim?(presentationVisible?1:0):alpha*entry.dim(),structural,tx);
        // Start once the app can present its decorations, including slow cold starts.
        if(decorationEntry&&drawn&&decorationAt<0)decorationAt=SystemClock.elapsedRealtime();
        return drawn;
    }
    private float decorationProgress(){
        return decorationAt<0?0:duration==0?1:Math.min(1,((frameClock<0?SystemClock.elapsedRealtime():frameClock)-decorationAt)/(float)duration);
    }
    private void refreshScreenCorners(){
        try{
            Object display=a.call(task,"getDisplayContent");
            Rect bounds=new Rect((Rect)a.call(display,"getBounds"));
            Display physical=iconContext.getSystemService(DisplayManager.class).getDisplay(a.integer(display,"getDisplayId"));
            if(physical==null)return;
            Context displayContext=iconContext.createDisplayContext(physical);
            Class<?> decorations=Class.forName("com.android.internal.policy.ScreenDecorationsUtils");
            float radius=(Float)decorations.getMethod("getWindowCornerRadius",Context.class).invoke(null,displayContext);
            screenBounds=bounds;
            screenRadius=Float.isFinite(radius)&&radius>0?radius:0;
        }catch(Exception|LinkageError error){
            android.util.Log.w("NativeComplements","COVER_SCREEN_CORNERS_UNAVAILABLE",error);
        }
    }
    /** Fullscreen uses the platform's window radius, which covers all physical display corners. */
    static float cornerRadius(RectF pose,Rect screen,float screenRadius,float windowRadius){
        boolean fullscreen=screen!=null&&Math.abs(pose.left-screen.left)<=1&&Math.abs(pose.top-screen.top)<=1
            &&Math.abs(pose.right-screen.right)<=1&&Math.abs(pose.bottom-screen.bottom)<=1;
        float radius=fullscreen?screenRadius:windowRadius;
        return Float.isFinite(radius)?Math.max(0,Math.min(Math.min(pose.width(),pose.height())/2,radius)):0;
    }
    void target(RectF bounds){
        if(exiting||gestureHold||to.equals(bounds))return;
        refreshScreenCorners();to.set(bounds);
        radiusTo=cornerRadius(to,screenBounds,screenRadius,radiusTo);
    }
    /** False means the caller must keep its normal non-cover gesture fallback. */
    boolean gesturePose(RectF bounds,float radius)throws Exception{
        if(!canRetarget()||!validPose(bounds,radius))return false;
        if(!gestureHold)gestureAt=SystemClock.elapsedRealtime();
        gestureHold=true;
        from.set(bounds);to.set(bounds);
        radiusFrom=radiusTo=cornerRadius(bounds,screenBounds,screenRadius,radius);
        // Preserve entry-fade opacity; only the SF frame clock advances that fade.
        paint(1,drawnAlpha);
        return true;
    }
    private static boolean validPose(RectF bounds,float radius){
        return bounds!=null&&Float.isFinite(bounds.left)&&Float.isFinite(bounds.top)
            &&Float.isFinite(bounds.right)&&Float.isFinite(bounds.bottom)&&bounds.width()>0&&bounds.height()>0
            &&Float.isFinite(radius)&&radius>=0;
    }
    /** Resume from the last displayed pose, without restarting the entry alpha ramp. */
    void finishGesture(RectF bounds,float radius){
        if(!canRetarget()||!validPose(bounds,radius))return;
        from.set(decorationBounds);radiusFrom=drawnRadius;
        refreshScreenCorners();to.set(bounds);radiusTo=cornerRadius(to,screenBounds,screenRadius,radius);
        gestureHold=false;gestureAt=-1;began=SystemClock.elapsedRealtime();
        motionAt=from.equals(to)&&radiusFrom==radiusTo?began-duration:began;
    }
    void resumeTo(RectF bounds,float radius){finishGesture(bounds,radius);}
    void delayEntry(long delay){notBefore=SystemClock.elapsedRealtime()+Math.max(0,delay);}
    boolean exiting(){return exiting;}
    long exit(){
        if(exiting)return 0;
        exiting=true;gestureHold=false;ownerAlpha=0;exitCoverAlpha=drawnAlpha;began=SystemClock.elapsedRealtime();
        from.set(decorationBounds);to.set(from);radiusFrom=radiusTo=drawnRadius;
        float dx=from.width()*.025f,dy=from.height()*.025f;to.inset(dx,dy);
        return fadeDuration;
    }
    boolean step(boolean ready,boolean visible)throws Exception{
        return step(ready,visible,SystemClock.elapsedRealtime());
    }
    boolean step(boolean ready,boolean visible,long now)throws Exception{
        frameClock=now;
        try{return stepFrame(ready,visible,now);}
        finally{frameClock=-1;}
    }
    private boolean stepFrame(boolean ready,boolean visible,long now)throws Exception{
        if(closed)return false;
        presentationVisible=visible;
        if(!visible){
            if(hiddenAt<0)hiddenAt=now;
            if(mask.holding())mask.hold();
            paint(1,0);return true;
        }
        if(hiddenAt>=0){
            long paused=now-hiddenAt;
            began+=paused;if(motionAt>=0)motionAt+=paused;if(gestureAt>=0)gestureAt+=paused;
            if(entryAt>=0)entryAt+=paused;if(fadeAt>=0)fadeAt+=paused;
            if(decorationAt>=0)decorationAt+=paused;
            if(notBefore>0)notBefore+=paused;
            hiddenAt=-1;
        }
        if(exiting){
            float q=fadeDuration==0?1:Math.min(1,(now-began)/(float)fadeDuration);
            if(q>=1){close();return false;}
            if(mask.holding())mask.hold();paint(1-(float)Math.pow(1-q,3),exitCoverAlpha*(1-q));return true;
        }
        if(gestureHold){
            if(now-gestureAt>=30000)throw new IllegalStateException("cover gesture timeout");
        }else if(now-began>=5000)throw new IllegalStateException("cover target frame timeout");
        if(fadeIn&&visible&&entryAt<0&&now>=notBefore)entryAt=now;
        long elapsed=fadeIn?(entryAt<0?0:now-entryAt):now-began;
        long motionElapsed=motionAt<0?elapsed:Math.max(0,now-motionAt);
        float p=duration==0?1:Math.min(1,motionElapsed/(float)duration);
        if(ready&&!gestureHold&&p==1&&(!fadeIn||elapsed>=fadeDuration)
            &&(!decorationEntry||decorationProgress()==1)&&fadeAt<0){
            preparingReveal=true;
            try{if(beforeReveal!=null)beforeReveal.prepare();}
            finally{preparingReveal=false;}
            mask.release(ownerAlpha);fadeAt=now;
        }
        if(fadeAt>=0&&now-fadeAt>=fadeDuration){close();return false;}
        if(mask.holding())mask.hold();
        float alpha=fadeAt<0?(fadeIn&&fadeDuration>0?Math.min(1,elapsed/(float)fadeDuration):1):1-(now-fadeAt)/(float)fadeDuration;
        paint(1-(float)Math.pow(1-p,3),visible?alpha:0);
        return true;
    }
    private void paint(float p,float alpha)throws Exception{
        drawIcon();
        if(exiting)exitAlpha=alpha;
        float x=from.left+(to.left-from.left)*p,y=from.top+(to.top-from.top)*p;
        float w=Math.max(1,from.width()+(to.width()-from.width())*p),h=Math.max(1,from.height()+(to.height()-from.height())*p);
        decorationBounds.set(x,y,x+w,y+h);
        float size=Math.min(splashPx,Math.min(w,h)*.405f)/iconSize;
        Rect pb=(Rect)a.call(parent,"getBounds");
        SurfaceControl anchor=(SurfaceControl)a.field(task,"mSurfaceControl");
        if(anchor==null||!anchor.isValid()||root==null||!root.isValid()||background==null||!background.isValid()||icon==null||!icon.isValid())throw new IllegalStateException("cover surface detached");
        try(SurfaceControl.Transaction tx=new SurfaceControl.Transaction()){
            a.call(tx,"setRelativeLayer",new Class<?>[]{SurfaceControl.class,SurfaceControl.class,int.class},root,anchor,1);
            tx.setLayer(background,0).setLayer(icon,1);
            tx.setPosition(root,x-pb.left,y-pb.top);
            a.call(tx,"setColor",new Class<?>[]{SurfaceControl.class,float[].class},background,color);
            a.call(tx,"setPosition",new Class<?>[]{SurfaceControl.class,float.class,float.class},background,0f,0f);
            a.call(tx,"setWindowCrop",new Class<?>[]{SurfaceControl.class,int.class,int.class},background,Math.round(w),Math.round(h));
            float radius=Math.min(Math.min(w,h)/2,radiusFrom+(radiusTo-radiusFrom)*p);
            a.call(tx,"setCornerRadius",new Class<?>[]{SurfaceControl.class,float.class},background,radius);
            a.call(tx,"setScale",new Class<?>[]{SurfaceControl.class,float.class,float.class},icon,size,size);
            a.call(tx,"setPosition",new Class<?>[]{SurfaceControl.class,float.class,float.class},icon,(w-iconSize*size)/2,(h-iconSize*size)/2);
            if(mask.holding())mask.inTransaction(tx,null);
            tx.setAlpha(root,alpha);
            a.call(tx,"show",new Class<?>[]{SurfaceControl.class},root);
            a.call(tx,"show",new Class<?>[]{SurfaceControl.class},background);
            a.call(tx,"show",new Class<?>[]{SurfaceControl.class},icon);
            decorate(tx,false);
            tx.apply();
            drawnAlpha=alpha;drawnRadius=radius;
        }
    }
    void close(){
        if(closed)return;closed=true;
        try{mask.release(ownerAlpha);}catch(Exception error){android.util.Log.w("NativeComplements","COVER_MASK_RELEASE_FAILED",error);}
        try(SurfaceControl.Transaction tx=new SurfaceControl.Transaction()){
            // Removing the container removes its whole background/icon subtree atomically.
            if(root!=null&&root.isValid())a.call(tx,"remove",new Class<?>[]{SurfaceControl.class},root);
            else{removeFallback(tx,icon);removeFallback(tx,background);}
            tx.apply();
        }catch(Exception error){
            android.util.Log.w("NativeComplements","COVER_ROOT_REMOVE_FAILED",error);
            // One bounded fallback: hide first, then detach each child even if its sibling is stale.
            try(SurfaceControl.Transaction tx=new SurfaceControl.Transaction()){
                removeFallback(tx,icon);removeFallback(tx,background);removeFallback(tx,root);
                tx.apply();
            }catch(Exception cleanup){android.util.Log.w("NativeComplements","COVER_REMOVE_FALLBACK_FAILED",cleanup);}
        }
        finally{
            release(icon,"icon");release(background,"background");release(root,"root");
            icon=background=root=null;
        }
    }
    private void removeFallback(SurfaceControl.Transaction tx,SurfaceControl surface){
        if(surface==null||!surface.isValid())return;
        try{a.call(tx,"hide",new Class<?>[]{SurfaceControl.class},surface);}
        catch(Exception error){android.util.Log.w("NativeComplements","COVER_FALLBACK_HIDE_FAILED",error);}
        try{a.call(tx,"remove",new Class<?>[]{SurfaceControl.class},surface);}
        catch(Exception error){android.util.Log.w("NativeComplements","COVER_FALLBACK_REMOVE_FAILED",error);}
    }
    private static void release(SurfaceControl surface,String name){
        if(surface==null)return;
        try{surface.release();}
        catch(RuntimeException error){android.util.Log.w("NativeComplements","COVER_SURFACE_RELEASE_FAILED "+name,error);}
    }
}
