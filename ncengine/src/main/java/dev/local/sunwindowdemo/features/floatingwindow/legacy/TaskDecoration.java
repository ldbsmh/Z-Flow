package dev.local.sunwindowdemo;

import android.graphics.Rect;
import android.view.SurfaceControl;

/** Fixed SunOS handle and a non-interactive backdrop below its task, in one transaction. */
final class TaskDecoration {
    private static final int EDGE_BAR_COLOR=0xfff0f2f2;
    private float dimAmount=Config.DEFAULT_WINDOW_DIM/100f;
    void dimAmount(int percent){dimAmount=Rules.clamp(percent,0,100)/100f;}
    private final FrameworkAccess a;
    private SurfaceControl bar,dim,backing,backingParent;
    private int backingWidth,backingHeight;
    private boolean backingShown;
    private boolean drawn,dimReady,dimEnabled=true;
    void enableDim(){dimEnabled=true;}
    /**
     * 出生即迷你的会话不会经过 MINI 指令，也就没有把 dim 收掉的那一步。
     * 迷你窗只占一小块，dim 覆盖的是整块 parent，等于给整个屏幕压一层暗色，
     * 所以这一类会话从第一次布局起就不该有 dim。
     */
    void disableDim(){dimEnabled=false;}
    void transferDimTo(TaskDecoration next,Object task)throws Exception{
        if(dim==null){next.enableDim();return;}
        if(next.dim!=null&&next.dim!=dim){tx(a.transaction(task),"remove",new Class<?>[]{SurfaceControl.class},next.dim);next.dim.release();}
        next.dim=dim;next.dimReady=dimReady;next.dimEnabled=true;
        if(dim!=null){
            Object t=a.transaction(task);
            tx(t,"reparent",new Class<?>[]{SurfaceControl.class,SurfaceControl.class},dim,(SurfaceControl)a.field(a.call(task,"getParent"),"mSurfaceControl"));
            tx(t,"setRelativeLayer",new Class<?>[]{SurfaceControl.class,SurfaceControl.class,int.class},dim,(SurfaceControl)a.field(task,"mSurfaceControl"),-1);
            tx(t,"setAlpha",new Class<?>[]{SurfaceControl.class,float.class},dim,dimAmount);
            tx(t,"show",new Class<?>[]{SurfaceControl.class},dim);
        }
        dim=null;dimReady=false;dimEnabled=false;
    }
    private int color;
    int color(){return EDGE_BAR_COLOR;}
    private Rect parentBounds;
    TaskDecoration(FrameworkAccess access){a=access;}
    static void preflight(FrameworkAccess a) throws Exception {
        Class<?> b=Class.forName("android.view.SurfaceControl$Builder");
        b.getDeclaredConstructor();
        a.method(b,"setName",String.class);a.method(b,"setParent",SurfaceControl.class);
        a.method(b,"setColorLayer");a.method(b,"setHidden",boolean.class);a.method(b,"build");
        Class<?> t=SurfaceControl.Transaction.class;
        a.method(t,"setRelativeLayer",SurfaceControl.class,SurfaceControl.class,int.class);
        a.method(t,"setLayer",SurfaceControl.class,int.class);
        a.method(t,"setColor",SurfaceControl.class,float[].class);
        a.method(t,"setAlpha",SurfaceControl.class,float.class);
        a.method(t,"show",SurfaceControl.class);a.method(t,"hide",SurfaceControl.class);
        a.method(t,"remove",SurfaceControl.class);a.method(t,"reparent",SurfaceControl.class,SurfaceControl.class);
        Class<?> controller=a.field(a.wc,"mTransitionController").getType();
        a.method(controller,"isPlaying");a.method(controller,"isCollecting");
        a.field(controller,"mBuildingTransitionLayers");
        a.method(a.task,"getTopVisibleAppMainWindow");
        Class<?> w=Class.forName("com.android.server.wm.WindowState",false,a.task.getClassLoader());
        a.method(w,"isDrawn");a.method(w,"isVisible");
    }
    private void tx(Object t,String name,Class<?>[] types,Object... args) throws Exception {a.call(t,name,types,args);}
    private SurfaceControl layer(Object parent,String name)throws Exception{
        Object builder=Class.forName("android.view.SurfaceControl$Builder").getDeclaredConstructor().newInstance();
        a.call(builder,"setName",new Class<?>[]{String.class},name);
        a.call(builder,"setParent",new Class<?>[]{SurfaceControl.class},a.field(parent,"mSurfaceControl"));
        a.call(builder,"setColorLayer");a.call(builder,"setHidden",new Class<?>[]{boolean.class},true);
        return (SurfaceControl)a.call(builder,"build");
    }
    /** Fill only transparent app pixels. The task owns crop, corners, alpha, transform and visibility. */
    void updateBacking(Object task,int width,int height,boolean enabled)throws Exception{
        if(!enabled){
            if(backing!=null&&backingShown){tx(a.transaction(task),"hide",new Class<?>[]{SurfaceControl.class},backing);backingShown=false;}
            return;
        }
        SurfaceControl surface=(SurfaceControl)a.field(task,"mSurfaceControl");
        if(surface==null||!surface.isValid()||width<=0||height<=0){removeBacking(a.transaction(task));return;}
        Object t=a.transaction(task);
        if(backing!=null&&(backingParent!=surface||!backing.isValid()))removeBacking(t);
        if(backing==null){
            backing=layer(task,"NativeComplements-task-backing");backingParent=surface;
            tx(t,"setLayer",new Class<?>[]{SurfaceControl.class,int.class},backing,Integer.MIN_VALUE);
            tx(t,"setColor",new Class<?>[]{SurfaceControl.class,float[].class},backing,new float[]{1,1,1});
        }
        if(backingWidth!=width||backingHeight!=height){
            tx(t,"setWindowCrop",new Class<?>[]{SurfaceControl.class,int.class,int.class},backing,width,height);
            backingWidth=width;backingHeight=height;
        }
        if(!backingShown){tx(t,"show",new Class<?>[]{SurfaceControl.class},backing);backingShown=true;}
    }
    private void removeBacking(Object t)throws Exception{
        if(backing!=null){tx(t,"remove",new Class<?>[]{SurfaceControl.class},backing);backing.release();}
        backing=null;backingParent=null;backingWidth=backingHeight=0;backingShown=false;
    }
    /** Reapply native sibling ordering after Shell playback; never invent a topmost Z. */
    void order(Object task,Object parent,Object t) throws Exception {
        Object controller=a.field(task,"mTransitionController");
        if(a.bool(controller,"isPlaying")||a.bool(controller,"isCollecting")
            ||(Boolean)a.field(controller,"mBuildingTransitionLayers"))return;
        // Plain assignChildLayers skips root tasks with Shell transitions enabled.
        // The native finish helper enables ordering and rewrites every sibling's
        // cached layer, so a former fullscreen task cannot retain a stale higher Z.
        a.finishLayers.invoke(null,parent,t);
    }
    boolean update(Object task,Object parent,Geometry.Placement p,float density,boolean landscape,float alpha,boolean structural) throws Exception {
        return update(task,parent,p,density,landscape,alpha,alpha,structural,a.transaction(task));
    }
    boolean update(Object task,Object parent,Geometry.Placement p,float density,boolean landscape,float alpha,float dimAlpha,boolean structural,Object t) throws Exception {
        if(structural||parentBounds==null){
            order(task,parent,t);
            Object win=a.call(task,"getTopVisibleAppMainWindow");
            drawn=win!=null&&a.bool(win,"isDrawn")&&a.bool(win,"isVisible");
            parentBounds=new Rect((Rect)a.call(parent,"getBounds"));
        }
        // Page navigation can replace the main window before its first buffer. That
        // gates the handle, not an already established task backdrop.
        dimReady|=drawn;
        if(bar!=null&&(!drawn||alpha<=0))tx(t,"hide",new Class<?>[]{SurfaceControl.class},bar);
        if(dim!=null&&(!dimEnabled||!dimReady||dimAlpha<=0))tx(t,"hide",new Class<?>[]{SurfaceControl.class},dim);
        if(!drawn&&(!dimEnabled||!dimReady||dimAlpha<=0))return false;
        SurfaceControl surface=(SurfaceControl)a.field(task,"mSurfaceControl");
        if(drawn&&alpha>0){
        if(bar==null)bar=layer(parent,"SunWindow-handle");
        if(color!=EDGE_BAR_COLOR){color=EDGE_BAR_COLOR;tx(t,"setColor",new Class<?>[]{SurfaceControl.class,float[].class},bar,
            new float[]{((color>>16)&255)/255f,((color>>8)&255)/255f,(color&255)/255f});}
        MotionStyle.Bar b=MotionStyle.bar(p.scale());
        float length=b.length()*density,thickness=b.thickness()*density,gap=b.margin()*density;
        Rect pb=parentBounds;
        float x=landscape?p.x()+p.width()*p.scale()+gap:p.x()+(p.width()*p.scale()-length)/2;
        float y=landscape?p.y()+(p.height()*p.scale()-length)/2:p.y()+p.height()*p.scale()+gap;
        tx(t,"setRelativeLayer",new Class<?>[]{SurfaceControl.class,SurfaceControl.class,int.class},bar,surface,1);
        tx(t,"setPosition",new Class<?>[]{SurfaceControl.class,float.class,float.class},bar,x-pb.left,y-pb.top);
        tx(t,"setWindowCrop",new Class<?>[]{SurfaceControl.class,int.class,int.class},bar,
            Math.max(1,Math.round(landscape?thickness:length)),Math.max(1,Math.round(landscape?length:thickness)));
        tx(t,"setCornerRadius",new Class<?>[]{SurfaceControl.class,float.class},bar,thickness/2);
        tx(t,"setAlpha",new Class<?>[]{SurfaceControl.class,float.class},bar,Math.max(0,Math.min(1,alpha)));
        tx(t,"show",new Class<?>[]{SurfaceControl.class},bar);
        }
        if(!dimEnabled||!dimReady||dimAlpha<=0)return drawn;
        // Keep the dim layer below the task, preserving its brightness and
        // leaving native system/IME windows above it.
        if(dim==null){
            dim=layer(parent,"NativeComplements-dimmer");
            tx(t,"setColor",new Class<?>[]{SurfaceControl.class,float[].class},dim,new float[]{0,0,0});
        }
        tx(t,"setRelativeLayer",new Class<?>[]{SurfaceControl.class,SurfaceControl.class,int.class},dim,surface,-1);
        tx(t,"setPosition",new Class<?>[]{SurfaceControl.class,float.class,float.class},dim,0f,0f);
        tx(t,"setWindowCrop",new Class<?>[]{SurfaceControl.class,int.class,int.class},dim,parentBounds.width(),parentBounds.height());
        tx(t,"setAlpha",new Class<?>[]{SurfaceControl.class,float.class},dim,dimAmount*Math.max(0,Math.min(1,dimAlpha)));
        tx(t,"show",new Class<?>[]{SurfaceControl.class},dim);
        return drawn;
    }
    void hideHandle(Object task)throws Exception{
        if(bar!=null)tx(a.transaction(task),"hide",new Class<?>[]{SurfaceControl.class},bar);
    }
    void hide(Object task) throws Exception {
        Object t=a.transaction(task);
        if(bar!=null)tx(t,"hide",new Class<?>[]{SurfaceControl.class},bar);
        if(dim!=null)tx(t,"hide",new Class<?>[]{SurfaceControl.class},dim);
    }
    void remove(Object task) throws Exception {
        removeBacking(a.transaction(task));
        if(bar!=null){tx(a.transaction(task),"remove",new Class<?>[]{SurfaceControl.class},bar);bar.release();bar=null;color=0;}
        if(dim!=null){tx(a.transaction(task),"remove",new Class<?>[]{SurfaceControl.class},dim);dim.release();dim=null;}
    }
    void dispose(){
        if(bar==null&&dim==null&&backing==null)return;
        try(SurfaceControl.Transaction t=new SurfaceControl.Transaction()){
            if(backing!=null)tx(t,"remove",new Class<?>[]{SurfaceControl.class},backing);
            if(bar!=null)tx(t,"remove",new Class<?>[]{SurfaceControl.class},bar);
            if(dim!=null)tx(t,"remove",new Class<?>[]{SurfaceControl.class},dim);
            t.apply();
        }catch(Exception ignored){}finally{
            if(backing!=null){backing.release();backing=null;}
            backingParent=null;backingWidth=backingHeight=0;backingShown=false;
            if(bar!=null){bar.release();bar=null;color=0;}
            if(dim!=null){dim.release();dim=null;}
        }
    }
    /** Undo our relative-layer override using current WM ordering after restoration. */
    void restoreLayer(Object task) throws Exception {
        order(task,a.call(task,"getParent"),a.transaction(task));
    }
}
