package io.relimus.zflow.xposed.nativewin;
package io.relimus.zflow.xposed.nativewin;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.Surface;
import android.view.SurfaceControl;

/** A temporary sibling surface. App buffers and lifecycle continue underneath it. */
final class TaskCover {
    final TaskVisibilityGuard mask;
    private final FrameworkAccess a;
    private final Context iconContext;
    private final String pkg;
    private boolean iconDrawn;
    private final Object task,parent;
    private SurfaceControl background,icon;
    private final RectF from,to;
    private final float radiusFrom;
    private float radiusTo;
    private final int iconSize;
    private final float splashPx;
    private final float[] color;
    private long began=SystemClock.elapsedRealtime(),notBefore;
    private final long duration,fadeDuration;
    private long entryAt=-1,fadeAt=-1,hiddenAt=-1;
    private long frameClock=-1;
    private boolean closed,exiting;
    private float ownerAlpha;
    private final boolean fadeIn;
    private TaskDecoration decoration;
    private Geometry.Placement decorationCanvas;
    private final RectF decorationBounds=new RectF();
    private float decorationDensity,decorationAlpha,exitAlpha=1;
    private long decorationAt=-1;
    private boolean decorationLandscape,decorationEntry,presentationVisible=true;
    private int preparedWidth,preparedHeight;
    private int reusedFrames,preparedFrames;
    private float preparedDensity,drawnAlpha;
    private boolean preparingReveal;
    @FunctionalInterface interface Reveal { void prepare() throws Exception; }
    private Reveal beforeReveal;
    TaskCover(FrameworkAccess a,Context context,Object task,Object parent,String pkg,
            RectF from,RectF to,float radiusFrom,float radiusTo,float ownerAlpha,boolean fadeIn)throws Exception{
        this.a=a;this.task=task;this.parent=parent;this.from=new RectF(from);this.to=new RectF(to);
        iconContext=context;this.pkg=pkg;
        this.radiusFrom=radiusFrom;this.radiusTo=radiusTo;this.ownerAlpha=ownerAlpha;this.fadeIn=fadeIn;
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
            background=build("NativeComplements-cover",0);
            icon=build("NativeComplements-cover-icon",iconSize);
            paint(0,fadeIn?0:1);mask.hide();
        }catch(Throwable error){close();throw error;}
    }
    private void drawIcon()throws Exception{
        if(iconDrawn)return;
        // An unknown snapshot is not an explicit choice to use the application original.
        Drawable drawable=null;
        if(drawable==null){
            try{drawable=iconContext.getPackageManager().getApplicationIcon(pkg);}
            catch(android.content.pm.PackageManager.NameNotFoundException missing){drawable=iconContext.getPackageManager().getDefaultActivityIcon();}
        }
        Surface canvasSurface=new Surface(icon);
        try{
            Canvas canvas=canvasSurface.lockCanvas(null);
            try{canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR);drawable.setBounds(0,0,iconSize,iconSize);drawable.draw(canvas);}
            finally{canvasSurface.unlockCanvasAndPost(canvas);}
        }finally{canvasSurface.release();}
        iconDrawn=true;
    }
    private SurfaceControl build(String name,int size)throws Exception{
        Object b=Class.forName("android.view.SurfaceControl$Builder").getDeclaredConstructor().newInstance();
        a.call(b,"setName",new Class<?>[]{String.class},name);
        a.call(b,"setParent",new Class<?>[]{SurfaceControl.class},a.field(parent,"mSurfaceControl"));
        a.call(b,"setHidden",new Class<?>[]{boolean.class},true);
        if(size==0)a.call(b,"setColorLayer");
        else{
            a.call(b,"setBufferSize",new Class<?>[]{int.class,int.class},size,size);
            a.call(b,"setFormat",new Class<?>[]{int.class},PixelFormat.TRANSLUCENT);
        }
        return (SurfaceControl)a.call(b,"build");
    }
    void ownerAlpha(float alpha){ownerAlpha=alpha;}
    void beforeReveal(Reveal callback){beforeReveal=callback;}
    boolean canRetarget(){return !closed&&!exiting&&fadeAt<0&&mask.holding();}
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
    void target(RectF bounds){if(!exiting)to.set(bounds);}
    void resumeTo(RectF bounds,float radius){to.set(bounds);radiusTo=radius;began=SystemClock.elapsedRealtime();}
    void delayEntry(long delay){notBefore=SystemClock.elapsedRealtime()+Math.max(0,delay);}
    boolean exiting(){return exiting;}
    long exit(){
        if(exiting)return 0;
        exiting=true;ownerAlpha=0;began=SystemClock.elapsedRealtime();
        from.set(to);float dx=from.width()*.025f,dy=from.height()*.025f;to.inset(dx,dy);
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
            began+=paused;if(entryAt>=0)entryAt+=paused;if(fadeAt>=0)fadeAt+=paused;
            if(decorationAt>=0)decorationAt+=paused;
            if(notBefore>0)notBefore+=paused;
            hiddenAt=-1;
        }
        if(exiting){
            float q=fadeDuration==0?1:Math.min(1,(now-began)/(float)fadeDuration);
            if(q>=1){close();return false;}
            if(mask.holding())mask.hold();paint(1-(float)Math.pow(1-q,3),visible?1-q:0);return true;
        }
        if(now-began>=5000)throw new IllegalStateException("cover target frame timeout");
        if(fadeIn&&visible&&entryAt<0&&now>=notBefore)entryAt=now;
        long elapsed=fadeIn?(entryAt<0?0:now-entryAt):now-began;
        float p=duration==0?1:Math.min(1,elapsed/(float)duration);
        if(ready&&p==1&&(!decorationEntry||decorationProgress()==1)&&fadeAt<0){
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
        if(anchor==null||!anchor.isValid()||background==null||!background.isValid()||icon==null||!icon.isValid())throw new IllegalStateException("cover surface detached");
        try(SurfaceControl.Transaction tx=new SurfaceControl.Transaction()){
            a.call(tx,"setRelativeLayer",new Class<?>[]{SurfaceControl.class,SurfaceControl.class,int.class},background,anchor,1);
            a.call(tx,"setRelativeLayer",new Class<?>[]{SurfaceControl.class,SurfaceControl.class,int.class},icon,background,1);
            a.call(tx,"setColor",new Class<?>[]{SurfaceControl.class,float[].class},background,color);
            a.call(tx,"setPosition",new Class<?>[]{SurfaceControl.class,float.class,float.class},background,x-pb.left,y-pb.top);
            a.call(tx,"setWindowCrop",new Class<?>[]{SurfaceControl.class,int.class,int.class},background,Math.round(w),Math.round(h));
            a.call(tx,"setCornerRadius",new Class<?>[]{SurfaceControl.class,float.class},background,Math.min(Math.min(w,h)/2,radiusFrom+(radiusTo-radiusFrom)*p));
            a.call(tx,"setScale",new Class<?>[]{SurfaceControl.class,float.class,float.class},icon,size,size);
            a.call(tx,"setPosition",new Class<?>[]{SurfaceControl.class,float.class,float.class},icon,x-pb.left+(w-iconSize*size)/2,y-pb.top+(h-iconSize*size)/2);
            if(mask.holding())mask.inTransaction(tx,null);
            tx.setAlpha(background,alpha).setAlpha(icon,alpha);
            a.call(tx,"show",new Class<?>[]{SurfaceControl.class},background);
            a.call(tx,"show",new Class<?>[]{SurfaceControl.class},icon);
            decorate(tx,false);
            tx.apply();
            drawnAlpha=alpha;
        }
    }
    void close(){
        if(closed)return;closed=true;
        try{mask.release(ownerAlpha);}catch(Exception ignored){}
        try(SurfaceControl.Transaction tx=new SurfaceControl.Transaction()){
            if(icon!=null&&icon.isValid())a.call(tx,"remove",new Class<?>[]{SurfaceControl.class},icon);
            if(background!=null&&background.isValid())a.call(tx,"remove",new Class<?>[]{SurfaceControl.class},background);
            tx.apply();
        }catch(Exception ignored){}
        finally{if(icon!=null){icon.release();icon=null;}if(background!=null){background.release();background=null;}}
    }
}
