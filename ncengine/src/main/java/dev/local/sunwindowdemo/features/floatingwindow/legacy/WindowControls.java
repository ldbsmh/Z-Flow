package dev.local.sunwindowdemo;

import android.content.*;
import android.graphics.*;

import android.view.*;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import java.util.*;
import java.lang.reflect.*;

/** Trusted app overlays stay below native notification, status bar and IME windows. */
final class WindowControls {
    private final Context c,host;
    private final WindowManager wm;
    private final List<View> views=new ArrayList<>();
    private final List<WindowManager.LayoutParams> params=new ArrayList<>();
    private FrameLayout controlsRoot;
    private boolean separateWindows;
    private final Region normalOcclusion=new Region();
    private final List<Rect> unclippedBounds=new ArrayList<>();
    private boolean miniTouchActive;
    private String token,frame="";
    private int taskId=-1;
    private float x,y,w,h,scale,normalScale=.75f;
    private boolean appLandscape,systemBlocked;
    private String scaleZone="normal";
    private int barColor=0xfff0f2f2;
    private int logicalW,logicalH;
    private boolean landscape,dragging;
    private long sequence=-1;
    private boolean motionPosted,transitioning,fullTransition,nativeHandle,inputPending;
    private String motionOp;
    private float motionScale,motionX,motionY,barAlpha=1;
    private final Choreographer frames=Choreographer.getInstance();
    private final Choreographer.FrameCallback motionFrame=this::motionFrame;
    private final MiniMagnet miniMagnet=new MiniMagnet();
    private Geometry.Placement miniDragBase;
    private float miniDragX,miniDragY;
    private long miniFrameTime;
    private int hideHapticSide,displayW,displayH;
    private int edgeGesture;
    private boolean edgeBarDragging;
    private float edgeBarCenterX,edgeBarCenterY;
    private View edgeHoldView;
    private Runnable edgeHoldCallback;
    private int imeTop=Integer.MAX_VALUE;
    private WindowMetrics layoutMetrics;
    private View dismiss;
    private final Rect dismissBounds=new Rect();
    private boolean dismissEntered;
    private View menu;
    private LinearLayout menuActions;
    private Context moduleContext;
    private MiniAction sizeAction,muteAction;
    private boolean miniLarge,muted,edgeHidden;
    private int edgeSide;
    private float edgeCenterY;
    /** Clear each touch-only buffer explicitly; it must never become a visible backdrop. */
    private class ClearView extends FrameLayout {
        private boolean rejectedStream;
        ClearView(){super(c);setBackgroundColor(Color.TRANSPARENT);setWillNotDraw(false);}
        @Override protected void onDraw(Canvas canvas){canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR);}
        @Override public boolean dispatchTouchEvent(MotionEvent event){
            int action=event.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN){
                rejectedStream=blockedMiniDown(event);
                if(!rejectedStream&&"mini".equals(frame))miniTouchActive=true;
            }
            boolean result=rejectedStream||super.dispatchTouchEvent(event);
            if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){
                miniTouchActive=false;rejectedStream=false;
                post(()->{if(!views.isEmpty()){clipSeparateMini();changed();}});
            }
            return result;
        }
    }
    WindowControls(Context context){
        host=context;
        // SunOS uses APPLICATION_OVERLAY for pinned controls. A matching window
        // context keeps these touch regions below status/heads-up/shade windows.
        c=context.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);
        wm=c.getSystemService(WindowManager.class);
    }
    private int dp(float n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    void update(Intent i){
        if(i.getIntExtra("protocol",0)!=Wire.PROTOCOL)return;
        long serial=i.getLongExtra("sequence",0);if(serial<=sequence)return;sequence=serial;
        if(!"active".equals(i.getStringExtra("state"))){hide();return;}
        inputPending=i.getBooleanExtra("inputPending",false);
        if(!i.getBooleanExtra("presented",false)&&!inputPending){hide();return;}
        nativeHandle=i.getBooleanExtra("nativeHandle",false);
        barColor=i.getIntExtra("barColor",0xfff0f2f2);
        String next=i.getStringExtra("frame"),key=i.getStringExtra("token");
        if(!Wire.validToken(key))return;
        float nx=i.getFloatExtra("x",0),ny=i.getFloatExtra("y",0),nw=i.getFloatExtra("width",0),nh=i.getFloatExtra("height",0);
        if(!Float.isFinite(nx)||!Float.isFinite(ny)||!Float.isFinite(nw)||!Float.isFinite(nh)||nw<=0||nh<=0)return;
        transitioning=i.getBooleanExtra("transitioning",false);
        fullTransition=i.getBooleanExtra("fullTransition",false);
        barAlpha=i.getFloatExtra("barAlpha",1);
        // Keep the normal handle visible through normal/mini animation, fading with progress.
        if(transitioning&&i.getBooleanExtra("transitionHandle",false))next="normal";
        boolean rebuild=!Objects.equals(token,key)||taskId!=i.getIntExtra("taskId",-1)||views.isEmpty();
        if(rebuild){hide();token=key;}
        taskId=i.getIntExtra("taskId",-1);
        Rect display=wm.getCurrentWindowMetrics().getBounds();
        if(miniDragBase!=null&&(!"mini".equals(next)||i.getBooleanExtra("edgeHidden",false)||transitioning||systemBlocked
                ||display.width()!=displayW||display.height()!=displayH
                ||i.getIntExtra("logicalW",1200)!=miniDragBase.width()||i.getIntExtra("logicalH",2133)!=miniDragBase.height()))cancelMiniDrag();
        if(edgeGesture!=0&&(!"mini".equals(next)||!i.getBooleanExtra("edgeHidden",false)||transitioning||systemBlocked
                ||display.width()!=displayW||display.height()!=displayH
                ||i.getIntExtra("logicalW",1200)!=logicalW||i.getIntExtra("logicalH",2133)!=logicalH))cancelEdgeDrag();
        displayW=display.width();displayH=display.height();
        frame=next;
        miniLarge=i.getBooleanExtra("miniLarge",false);muted=i.getBooleanExtra("muted",false);
        edgeHidden=i.getBooleanExtra("edgeHidden",false);edgeSide=i.getIntExtra("edgeSide",1);
        edgeCenterY=i.getFloatExtra("edgeCenterY",ny+nh/2);
        edgeBarDragging=i.getBooleanExtra("edgeBarDragging",false);
        edgeBarCenterX=i.getFloatExtra("edgeBarCenterX",0);edgeBarCenterY=i.getFloatExtra("edgeBarCenterY",edgeCenterY);
        if(!"mini".equals(frame)||edgeHidden||transitioning||systemBlocked)hideMenu();
        normalScale=i.getFloatExtra("normalScale",.75f);appLandscape=i.getBooleanExtra("appLandscape",false);
        if(!dragging||transitioning&&!inputPending){x=nx;y=ny;w=nw;h=nh;scale=i.getFloatExtra("scale",1);}
        logicalW=i.getIntExtra("logicalW",1200);logicalH=i.getIntExtra("logicalH",2133);
        landscape=display.width()>display.height();
        if(rebuild)build();
        if(transitioning&&!inputPending||systemBlocked){for(View v:views)v.setVisibility(View.GONE);changed();}
        else if(!dragging||edgeGesture==2)layout();
    }
    private void build(){
        if(!separateWindows)buildControlsRoot();
        {
            final String boundToken=token;
            View mini=new ClearView(){
                final Paint edgePaint=new Paint(Paint.ANTI_ALIAS_FLAG);
                @Override protected void onDraw(Canvas cv){
                    super.onDraw(cv);
                    if(edgeHidden&&edgeGesture!=1){
                        edgePaint.setColor(0xfff0f2f2);
                        float left=edgeBarDragging?(getWidth()-dp(4))/2f:edgeSide<0?dp(12):getWidth()-dp(16),top=(getHeight()-dp(48))/2f;
                        cv.drawRoundRect(left,top,left+dp(4),top+dp(48),dp(2),dp(2),edgePaint);
                    }
                }
            };mini.setContentDescription("迷你窗：单击还原，双击全屏，长按调整大小或静音，拖出侧边隐藏；侧边白条点击或向内拖动还原");
            GestureDetector taps=new GestureDetector(c,new GestureDetector.SimpleOnGestureListener(){
                @Override public boolean onDown(MotionEvent e){return true;}
                @Override public boolean onSingleTapConfirmed(MotionEvent e){if(Objects.equals(boundToken,token)&&"mini".equals(frame)){if(menu!=null)hideMenu();else send("NORMAL",0,0,0);}return true;}
                @Override public boolean onDoubleTap(MotionEvent e){if(Objects.equals(boundToken,token)&&"mini".equals(frame)){hideMenu();send("FULL",0,0,0);}return true;}
                @Override public void onLongPress(MotionEvent e){
                    if(Objects.equals(boundToken,token)&&"mini".equals(frame)&&!dragging&&!transitioning&&!systemBlocked){
                        mini.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        if(menu==null)showMenu();else hideMenu();
                    }
                }
            });
            mini.setOnTouchListener(new View.OnTouchListener(){
                float downX,downY,baseX,baseY,endX,endY,barBaseX,barBaseY;
                boolean moving,edgeTouch,edgePressed,edgeMoving;
                Geometry.Placement basePlacement;
                View edgeView;
                final Runnable edgeHold=()->{
                    clearEdgeHold();
                    if(!edgePressed||edgeMoving||!edgeHidden||transitioning||systemBlocked||!Objects.equals(boundToken,token))return;
                    edgeMoving=true;edgeGesture=2;dragging=true;hideMenu();
                    edgeView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    scheduleMotion("EDGE_BAR_DRAG",0,barBaseX,barBaseY);
                };
                public boolean onTouch(View v,MotionEvent e){
                    if(!Objects.equals(boundToken,token)||!"mini".equals(frame)||transitioning||systemBlocked){clearEdgeHold();edgePressed=false;return true;}
                    if(e.getActionMasked()==MotionEvent.ACTION_DOWN){
                        clearEdgeHold();
                        edgeTouch=edgeHidden;edgePressed=edgeTouch;edgeMoving=false;edgeView=v;
                        if(edgeTouch){
                            downX=e.getRawX();downY=e.getRawY();baseX=endX=x;baseY=endY=y;
                            basePlacement=new Geometry.Placement(logicalW,logicalH,scale,x,y);
                            WindowManager.LayoutParams p=params.get(0);barBaseX=p.x+(edgeSide<0?dp(14):p.width-dp(14));barBaseY=p.y+p.height/2f;
                            edgeHoldView=v;edgeHoldCallback=edgeHold;v.postDelayed(edgeHold,ViewConfiguration.getLongPressTimeout());return true;
                        }
                    }
                    if(edgeTouch){
                        float dx=e.getRawX()-downX,dy=e.getRawY()-downY;
                        float slop=ViewConfiguration.get(c).getScaledTouchSlop();
                        if(edgeMoving&&edgeGesture==0){clearEdgeHold();edgePressed=false;return true;}
                        if(e.getActionMasked()==MotionEvent.ACTION_MOVE){
                            if(Math.hypot(dx,dy)>slop)clearEdgeHold();
                            if(!edgeMoving&&dx*(edgeSide<0?1:-1)>slop){
                                edgeMoving=true;edgeGesture=1;dragging=true;hideMenu();v.invalidate();
                                hideHapticSide=Geometry.hideSide(basePlacement,baseX,displayW);
                            }
                            if(edgeMoving){
                                if(edgeGesture==2)scheduleMotion("EDGE_BAR_DRAG",0,barBaseX+dx,barBaseY+dy);
                                else{endX=baseX+dx;endY=baseY+dy;hideFeedback(v,basePlacement,endX);scheduleMotion("EDGE_DRAG",0,endX,endY);}
                            }
                        }else if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){
                            clearEdgeHold();edgePressed=false;
                            if(edgeMoving){
                                boolean up=e.getActionMasked()==MotionEvent.ACTION_UP,bar=edgeGesture==2;
                                float px=bar?barBaseX+dx:baseX+dx,py=bar?barBaseY+dy:baseY+dy;
                                stopEdgeDrag();send(up?(bar?"EDGE_BAR_DOCK":"EDGE_DOCK"):"EDGE_DRAG_CANCEL",0,px,py);
                            }else if(e.getActionMasked()==MotionEvent.ACTION_UP&&Math.hypot(dx,dy)<slop)send("RESTORE_MINI",0,0,0);
                        }
                        return true;
                    }
                    if(e.getActionMasked()==MotionEvent.ACTION_OUTSIDE){hideMenu();return true;}
                    if(e.getActionMasked()==MotionEvent.ACTION_DOWN){
                        moving=false;dragging=false;downX=e.getRawX();downY=e.getRawY();baseX=x;baseY=y;endX=x;endY=y;
                        basePlacement=new Geometry.Placement(logicalW,logicalH,scale,x,y);taps.onTouchEvent(e);return true;
                    }
                    // A display/configuration interruption cancels the captured stream until its next DOWN.
                    if(moving&&miniDragBase==null)return true;
                    if(e.getActionMasked()==MotionEvent.ACTION_MOVE){
                        if(!moving&&Math.hypot(e.getRawX()-downX,e.getRawY()-downY)>ViewConfiguration.get(c).getScaledTouchSlop()){
                            moving=true;dragging=true;MotionEvent cancel=MotionEvent.obtain(e);cancel.setAction(MotionEvent.ACTION_CANCEL);taps.onTouchEvent(cancel);cancel.recycle();hideMenu();showDismiss();
                            miniDragBase=basePlacement;miniMagnet.reset();miniFrameTime=System.nanoTime();hideHapticSide=0;
                        }
                        if(moving){endX=baseX+e.getRawX()-downX;endY=baseY+e.getRawY()-downY;updateMiniDrag(v,e.getRawX(),e.getRawY(),endX,endY);}else taps.onTouchEvent(e);
                        return true;
                    }
                    if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){
                        if(moving){
                            boolean up=e.getActionMasked()==MotionEvent.ACTION_UP;
                            if(up){endX=baseX+e.getRawX()-downX;endY=baseY+e.getRawY()-downY;updateMiniDrag(v,e.getRawX(),e.getRawY(),endX,endY);}
                            boolean close=up&&miniMagnet.latched();
                            if(close){prepareMiniMotion();flushMotion();}
                            stopMiniDrag();send(close?"CLOSE":up?"DOCK":"MINI_DRAG_CANCEL",0,endX,endY);
                        }else taps.onTouchEvent(e);
                        return true;
                    }
                    return true;
                }
            });add(mini);
        }
        {
            for(int n=0;n<4;n++){
                View outside=new ClearView();
                GestureDetector closeTaps=new GestureDetector(c,new GestureDetector.SimpleOnGestureListener(){
                    @Override public boolean onDown(MotionEvent e){return true;}
                    @Override public boolean onSingleTapUp(MotionEvent e){if(!new Config(c).doubleClose())send("CLOSE",0,0,0);return true;}
                    @Override public boolean onDoubleTap(MotionEvent e){if(new Config(c).doubleClose())send("CLOSE",0,0,0);return true;}
                });
                outside.setOnTouchListener((v,e)->{closeTaps.onTouchEvent(e);return true;});add(outside);
            }
            View bar=new ClearView(){final Paint p=new Paint(3);@Override protected void onDraw(Canvas cv){
                super.onDraw(cv);p.setColor(barColor);p.setAlpha(Math.round(255*Math.max(0,Math.min(1,barAlpha))));
                if(nativeHandle)return;
                MotionStyle.Bar b=MotionStyle.bar(scale);float length=dp(b.length()),thickness=dp(b.thickness());
                float gap=dp(b.margin());float radius=thickness/2;
                if(landscape)cv.drawRoundRect(gap,(getHeight()-length)/2,gap+thickness,(getHeight()+length)/2,radius,radius,p);
                else cv.drawRoundRect((getWidth()-length)/2,gap,(getWidth()+length)/2,gap+thickness,radius,radius,p);
            }};
            bar.setContentDescription("小窗缩放白条：向内推缩小，向外拉全屏");
            bar.setOnTouchListener(new View.OnTouchListener(){
                float start,startScale;int axis;
                public boolean onTouch(View v,MotionEvent e){
                    if(fullTransition||transitioning&&!inputPending)return true;
                    if(e.getActionMasked()==MotionEvent.ACTION_DOWN){dragging=true;start=landscape?e.getRawX():e.getRawY();startScale=scale;scaleZone=landscape?"normal":GestureMath.release(scale,normalScale,appLandscape,landscape);axis=landscape?logicalW:logicalH;return true;}
                    if(e.getActionMasked()==MotionEvent.ACTION_MOVE){float delta=(landscape?e.getRawX():e.getRawY())-start;
                        if(landscape){String next=GestureMath.horizontalRelease(delta,dp(48));if(!next.equals(scaleZone)){scaleZone=next;if(!"normal".equals(next))v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);}v.setAlpha("normal".equals(next)?1:.65f);
                            float maximum=Math.min((displayW-dp(8))/(float)logicalW,(displayH-dp(8))/(float)logicalH);
                            sendMoveScale(GestureMath.horizontalScale(startScale,delta,dp(48),maximum));return true;}
                        sendMoveScale(GestureMath.clampScale(GestureMath.dragScale(startScale,delta,axis),normalScale,appLandscape,landscape));return true;}
                    if(e.getActionMasked()==MotionEvent.ACTION_UP){
                        float delta=(landscape?e.getRawX():e.getRawY())-start;
                        float end=GestureMath.clampScale(GestureMath.dragScale(startScale,delta,axis),normalScale,appLandscape,landscape);
                        frames.removeFrameCallback(motionFrame);motionPosted=false;motionOp=null;dragging=false;
                        // One final sample + commit, rather than stale MOVE, RESIZE and RELEASE messages.
                        v.setAlpha(1);
                        if(landscape){String next=GestureMath.horizontalRelease(delta,dp(48));send("full".equals(next)?"FULL":"mini".equals(next)?"MINI":"NORMAL",0,0,0);}
                        else if(token!=null)Wire.control(c,"END_RESIZE",token,taskId,end,0,0);return true;
                    }
                    if(e.getActionMasked()==MotionEvent.ACTION_CANCEL){dragging=false;v.setAlpha(1);frames.removeFrameCallback(motionFrame);motionPosted=false;motionOp=null;send("NORMAL",0,0,0);return true;}
                    return true;
                }
            });add(bar);
        }

    }
    private void postMotion(){if(!motionPosted){motionPosted=true;frames.postFrameCallback(motionFrame);}}
    private void motionFrame(long time){
        motionPosted=false;
        if(miniDragBase!=null){miniMagnet.advance(time-miniFrameTime);miniFrameTime=time;prepareMiniMotion();}
        flushMotion();
        if(miniDragBase!=null&&miniMagnet.animating())postMotion();
    }
    private void scheduleMotion(String op,float value,float px,float py){
        motionOp=op;motionScale=value;motionX=px;motionY=py;postMotion();
    }
    private void updateMiniDrag(View v,float px,float py,float rawX,float rawY){
        miniDragX=rawX;miniDragY=rawY;
        miniMagnet.target(dismissBounds.left,dismissBounds.top,dismissBounds.right,dismissBounds.bottom,px,py);
        highlight(miniMagnet.latched());
        if(miniMagnet.latched())hideHapticSide=0;else hideFeedback(v,miniDragBase,rawX);
        postMotion();
    }
    private void hideFeedback(View v,Geometry.Placement base,float rawX){
        int side=Geometry.hideSide(base,rawX,displayW);
        if(side!=0&&hideHapticSide==0)v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        hideHapticSide=side;
    }
    private void prepareMiniMotion(){
        if(miniDragBase==null)return;
        float progress=miniMagnet.progress();
        float cx=miniDragX+miniDragBase.width()*miniDragBase.scale()/2,cy=miniDragY+miniDragBase.height()*miniDragBase.scale()/2;
        motionOp="MINI_DRAG";motionScale=progress;
        motionX=cx+(dismissBounds.exactCenterX()-cx)*progress;
        motionY=cy+(dismissBounds.exactCenterY()-cy)*progress;
    }
    private void clearMotion(){frames.removeFrameCallback(motionFrame);motionPosted=false;motionOp=null;}
    private void stopMiniDrag(){
        clearMotion();miniDragBase=null;miniMagnet.reset();miniFrameTime=0;hideHapticSide=0;dragging=false;hideDismiss();
    }
    private void cancelMiniDrag(){
        if(miniDragBase==null)return;
        stopMiniDrag();if(token!=null)Wire.control(c,"MINI_DRAG_CANCEL",token,taskId,0,0,0);
    }
    private void clearEdgeHold(){
        if(edgeHoldView!=null&&edgeHoldCallback!=null)edgeHoldView.removeCallbacks(edgeHoldCallback);
        edgeHoldView=null;edgeHoldCallback=null;
    }
    private void stopEdgeDrag(){clearEdgeHold();clearMotion();edgeGesture=0;hideHapticSide=0;dragging=false;edgeBarDragging=false;}
    private void cancelEdgeDrag(){
        clearEdgeHold();if(edgeGesture==0)return;
        stopEdgeDrag();if(token!=null)Wire.control(c,"EDGE_DRAG_CANCEL",token,taskId,0,0,0);
    }
    private void flushMotion(){
        if(motionOp==null)return;String op=motionOp;motionOp=null;
        if(token!=null)Wire.control(c,op,token,taskId,motionScale,motionX,motionY);
    }
    private void sendMove(String op,float px,float py){scheduleMotion(op,0,px,py);}
    private void sendMoveScale(float s){
        // Track the drag locally for zone feedback; the native transaction draws the bar.
        float cx=x+w/2,cy=y+h/2;scale=s;w=logicalW*s;h=logicalH*s;x=cx-w/2;y=cy-h/2;
        String next=GestureMath.release(s,normalScale,appLandscape,landscape);
        if(!landscape&&!next.equals(scaleZone)){
            scaleZone=next;if(!"normal".equals(next)&&views.size()>5)views.get(5).performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        // The touch stream remains captured; the visible bar moves in the task transaction.
        barAlpha=1;scheduleMotion("RESIZE",s,0,0);
    }
    private void send(String op,float value,float x,float y){flushMotion();if(token!=null)Wire.control(c,op,token,taskId,value,x,y);}
    Region contentOcclusion(){
        Region region=new Region();
        if(token==null||systemBlocked||!"normal".equals(frame)||views.isEmpty())return region;
        region.set(new Rect((int)Math.floor(x),(int)Math.floor(y),(int)Math.ceil(x+w),(int)Math.ceil(y+h)));
        if(views.size()>5&&views.get(5).getVisibility()==View.VISIBLE){
            WindowManager.LayoutParams p=params.get(5);region.op(p.x,p.y,p.x+p.width,p.y+p.height,Region.Op.UNION);
        }
        return region;
    }
    void normalOcclusion(Region region){
        if(normalOcclusion.equals(region))return;
        normalOcclusion.set(region);
        if(!miniTouchActive)clipSeparateMini();
        changed();
    }
    static Rect largestUncovered(Rect bounds,Region occlusion){
        Region remaining=new Region(bounds);remaining.op(occlusion,Region.Op.DIFFERENCE);
        RegionIterator iterator=new RegionIterator(remaining);Rect part=new Rect(),largest=new Rect();long area=0;
        while(iterator.next(part)){
            long next=(long)part.width()*part.height();if(next>area){area=next;largest.set(part);}
        }
        return largest;
    }
    private boolean blockedMiniDown(MotionEvent event){
        return "mini".equals(frame)&&event.getActionMasked()==MotionEvent.ACTION_DOWN
            &&normalOcclusion.contains((int)Math.floor(event.getRawX()),(int)Math.floor(event.getRawY()));
    }
    private void clipSeparateMini(){
        if(controlsRoot!=null||!"mini".equals(frame)||miniTouchActive||systemBlocked||transitioning&&!inputPending)return;
        if(!views.isEmpty()&&!unclippedBounds.isEmpty())applyRect(0,unclippedBounds.get(0));
    }
    private void add(View v){
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(1,1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.LEFT;p.setFitInsetsTypes(0);p.setTitle("NativeComplements-controls");
        p.dimAmount=0;p.windowAnimations=0;
        if(views.isEmpty()){
            p.flags|=WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
            p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        SystemUiHost.trust(p);
        if(controlsRoot==null)wm.addView(v,p);
        else{
            v.setVisibility(View.GONE);
            controlsRoot.addView(v,new FrameLayout.LayoutParams(1,1,Gravity.TOP|Gravity.LEFT));
        }
        views.add(v);params.add(p);unclippedBounds.add(new Rect());
    }
    private void buildControlsRoot(){
        try{
            Class<?> listener=Class.forName("android.view.ViewTreeObserver$OnComputeInternalInsetsListener");
            Class<?> info=Class.forName("android.view.ViewTreeObserver$InternalInsetsInfo");
            Method setTouchable=info.getMethod("setTouchableInsets",int.class);
            Field regionField=info.getField("touchableRegion");
            int regionMode=info.getField("TOUCHABLE_INSETS_REGION").getInt(null);
            FrameLayout root=new ClearView(){
                @Override public boolean dispatchTouchEvent(MotionEvent event){
                    if(event.getActionMasked()==MotionEvent.ACTION_OUTSIDE){hideMenu();return true;}
                    return super.dispatchTouchEvent(event);
                }
            };
            Object callback=Proxy.newProxyInstance(listener.getClassLoader(),new Class<?>[]{listener},(proxy,method,args)->{
                if(method.getName().equals("onComputeInternalInsets")){
                    setTouchable.invoke(args[0],regionMode);
                    fillTouchRegion((Region)regionField.get(args[0]));
                    return null;
                }
                if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
                if(method.getName().equals("equals"))return proxy==args[0];
                return "NativeComplements controls insets";
            });
            ViewTreeObserver.class.getMethod("addOnComputeInternalInsetsListener",listener).invoke(root.getViewTreeObserver(),callback);
            WindowManager.LayoutParams p=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                |WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,PixelFormat.TRANSLUCENT);
            p.gravity=Gravity.TOP|Gravity.LEFT;p.setFitInsetsTypes(0);p.setTitle("NativeComplements-controls");
            p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            p.windowAnimations=0;SystemUiHost.trust(p);
            wm.addView(root,p);controlsRoot=root;
            android.util.Log.i("NativeComplements","CONTROLS_ROOT_READY windows=1");
        }catch(ReflectiveOperationException|RuntimeException error){
            separateWindows=true;
            android.util.Log.w("NativeComplements","Combined controls unavailable; using separate windows",error);
        }
    }
    void fillTouchRegion(Region region){
        region.setEmpty();
        if(systemBlocked||transitioning&&!inputPending)return;
        for(View view:views)if(view.getVisibility()==View.VISIBLE)
            region.op(view.getLeft(),view.getTop(),view.getRight(),view.getBottom(),Region.Op.UNION);
        if("mini".equals(frame)&&!miniTouchActive)region.op(normalOcclusion,Region.Op.DIFFERENCE);
    }
    private void rect(int index,float x,float y,float w,float h){
        if(index>=views.size())return;
        WindowMetrics metrics=layoutMetrics==null?wm.getCurrentWindowMetrics():layoutMetrics;
        int avoid=index==0&&"mini".equals(frame)?0:WindowInsets.Type.displayCutout();
        // Only normal-window controls avoid the camera; mini content and edge handles
        // may reach the physical edge. Portrait controls still leave system bars usable.
        if(!landscape||(index!=0&&index!=5))avoid|=WindowInsets.Type.systemBars();
        Insets bars=avoid==0?Insets.NONE:metrics.getWindowInsets().getInsetsIgnoringVisibility(avoid);
        float right=Math.min(x+w,metrics.getBounds().width()-bars.right);
        float bottom=Math.min(y+h,Math.min(imeTop,metrics.getBounds().height()-bars.bottom));
        x=Math.max(x,bars.left);y=Math.max(y,bars.top);w=Math.max(0,right-x);h=Math.max(0,bottom-y);
        // Outside-dismiss overlays leave the keyboard, status bar and navigation bar usable.
        Rect bounds=new Rect(Math.round(x),Math.round(y),Math.round(x+Math.max(0,w)),Math.round(y+Math.max(0,h)));
        unclippedBounds.get(index).set(bounds);applyRect(index,bounds);
    }
    private void applyRect(int index,Rect bounds){
        WindowManager.LayoutParams p=params.get(index);View view=views.get(index);
        if(controlsRoot==null&&"mini".equals(frame)&&miniTouchActive)return;
        Rect clipped=bounds;
        if(controlsRoot==null&&"mini".equals(frame)&&!miniTouchActive){
            // ponytail: fallback keeps the largest free rectangle; exact disconnected regions need more windows.
            clipped=largestUncovered(bounds,normalOcclusion);
        }
        int nx=clipped.left,ny=clipped.top,nw=Math.max(1,clipped.width()),nh=Math.max(1,clipped.height());
        int oldFlags=p.flags;
        if(clipped.isEmpty())p.flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else p.flags&=~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        view.setVisibility(!clipped.isEmpty()?View.VISIBLE:View.GONE);
        if(p.x!=nx||p.y!=ny||p.width!=nw||p.height!=nh||oldFlags!=p.flags){
            p.x=nx;p.y=ny;p.width=nw;p.height=nh;
            if(controlsRoot==null)wm.updateViewLayout(view,p);
            else{
                FrameLayout.LayoutParams child=new FrameLayout.LayoutParams(nw,nh,Gravity.TOP|Gravity.LEFT);
                child.leftMargin=nx;child.topMargin=ny;view.setLayoutParams(child);
            }
        }
    }
    private void layout(){
        if(systemBlocked){for(View v:views)v.setVisibility(View.GONE);changed();return;}
        layoutMetrics=wm.getCurrentWindowMetrics();
        Rect d=layoutMetrics.getBounds();
        if("mini".equals(frame)){
            for(int n=1;n<views.size();n++)views.get(n).setVisibility(View.GONE);
            if(edgeHidden){
                Insets insets=landscape?Insets.NONE:layoutMetrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars());
                int width=dp(48),height=dp(104);
                if(edgeBarDragging)rect(0,edgeBarCenterX-width/2f,edgeBarCenterY-height/2f,width,height);
                else rect(0,edgeSide<0?insets.left:d.width()-insets.right-width,
                    Math.max(insets.top,Math.min(edgeCenterY-height/2,d.height()-insets.bottom-height)),width,height);
                views.get(0).setSystemGestureExclusionRects(List.of(new Rect(0,0,width,height)));
                views.get(0).invalidate();
            }else{views.get(0).setSystemGestureExclusionRects(List.of());rect(0,x,y,w,h);views.get(0).invalidate();layoutMenu();}
            changed();return;
        }
        // The cover is a SurfaceControl, not an input window. Reuse the compact
        // mini hit surface to stop entry touches reaching the underlying app.
        if(inputPending){views.get(0).setSystemGestureExclusionRects(List.of());rect(0,x,y,w,h);}
        else if(!views.isEmpty())views.get(0).setVisibility(View.GONE);
        String closeDescription=new Config(c).doubleClose()?"双击空白处关闭小窗":"单击空白处关闭小窗";
        for(int n=1;n<=4;n++)views.get(n).setContentDescription(closeDescription);
        rect(1,0,0,d.width(),Math.max(0,y));rect(2,0,y,Math.max(0,x),h);
        rect(3,x+w,y,Math.max(0,d.width()-x-w),h);rect(4,0,y+h,d.width(),Math.max(0,d.height()-y-h));
        views.get(5).setContentDescription(landscape?"小窗白条：左滑迷你，右滑全屏":"小窗白条：上滑迷你，下滑全屏");
        MotionStyle.Bar b=MotionStyle.bar(scale);
        float length=dp(b.length()),thickness=dp(b.thickness()),margin=dp(b.margin());
        float touchLength=Math.max(dp(120),length+dp(64)),touchDepth=dp(72);
        if(landscape)rect(5,x+w-dp(12),y+h/2-touchLength/2,touchDepth,touchLength);
        else rect(5,x+w/2-touchLength/2,y+h-dp(12),touchLength,touchDepth);
        if(!nativeHandle&&views.size()>5)views.get(5).invalidate();
        changed();
    }
    int[] protectedRects(){
        int[] bounds=unoccludedProtectedRects();
        if(bounds.length!=4||!"mini".equals(frame))return bounds;
        Rect visible=largestUncovered(new Rect(bounds[0],bounds[1],bounds[2],bounds[3]),normalOcclusion);
        return visible.isEmpty()?new int[0]:new int[]{visible.left,visible.top,visible.right,visible.bottom};
    }
    private int[] unoccludedProtectedRects(){
        if(systemBlocked||transitioning&&!inputPending)return new int[0];
        // Keep the existing one-rectangle GestureGate contract. Protect the
        // compact buttons, rather than the potentially wide landscape preview.
        if(edgeHidden&&!params.isEmpty()&&views.get(0).getVisibility()==View.VISIBLE){
            WindowManager.LayoutParams p=params.get(0);return new int[]{p.x,p.y,p.x+p.width,p.y+p.height};
        }
        if(menuActions!=null&&!params.isEmpty()){
            WindowManager.LayoutParams p=params.get(0);
            int width=menuActions.getLayoutParams().width,height=menuActions.getLayoutParams().height;
            int left=p.x+(p.width-width)/2,top=p.y+(p.height-height)/2;
            return new int[]{left,top,left+width,top+height};
        }
        WindowManager.LayoutParams p=views.size()>5&&views.get(5).getVisibility()==View.VISIBLE?params.get(5):null;
        return p==null?new int[0]:new int[]{p.x,p.y,p.x+p.width,p.y+p.height};
    }
    // SunOS pinned_window_overlay.xml: 42dp light circular actions, 18dp
    // original vector icons, 20dp spacing; mute first, then scale.
    private class MiniAction extends ImageButton {
        final boolean sound;
        MiniAction(boolean sound){
            super(c);this.sound=sound;setPadding(0,0,0,0);setMinimumWidth(0);setMinimumHeight(0);
            setScaleType(ImageView.ScaleType.FIT_CENTER);setImageTintList(null);
            setBackground(new RippleDrawable(ColorStateList.valueOf(0x1f06080a),
                moduleContext.getDrawable(R.drawable.nc_pinned_window_button_shape),null));
            refresh();
        }
        void refresh(){
            boolean selected=sound?muted:miniLarge;
            int icon=sound?(selected?R.drawable.nc_pinned_window_unmute:R.drawable.nc_pinned_window_mute)
                :(selected?R.drawable.nc_pinned_window_scale_small:R.drawable.nc_pinned_window_scale_large);
            // These IDs belong to the module APK, not the SystemUI host resources.
            setImageDrawable(moduleContext.getDrawable(icon));
            setContentDescription(sound?(selected?"取消静音":"静音"):(selected?"缩小迷你窗":"放大迷你窗"));
        }
    }
    private void showMenu(){
        if(token==null||!"mini".equals(frame)||transitioning||systemBlocked||menu!=null||views.isEmpty())return;
        try{if(moduleContext==null)moduleContext=c.createPackageContext(Wire.PKG,0);}
        catch(android.content.pm.PackageManager.NameNotFoundException e){android.util.Log.e("NativeComplements","MINI_MENU_RESOURCES_UNAVAILABLE",e);return;}
        final String bound=token;
        FrameLayout cover=new FrameLayout(c);
        cover.setBackground(moduleContext.getDrawable(R.drawable.nc_pinned_window_menu_shape));
        cover.setClipToOutline(true);cover.setOnClickListener(v->hideMenu());
        menuActions=new LinearLayout(c);menuActions.setGravity(Gravity.CENTER);
        sizeAction=new MiniAction(false);muteAction=new MiniAction(true);
        menuActions.addView(muteAction);menuActions.addView(sizeAction);
        cover.addView(menuActions,new FrameLayout.LayoutParams(1,1,Gravity.CENTER));
        sizeAction.setOnClickListener(v->{if(Objects.equals(bound,token)){v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);hideMenu();send("MINI_SIZE",0,0,0);}});
        muteAction.setOnClickListener(v->{if(Objects.equals(bound,token)){v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);hideMenu();send("MUTE",0,0,0);}});
        menu=cover;
        ((FrameLayout)views.get(0)).addView(cover,new FrameLayout.LayoutParams(-1,-1));
        layoutMenu();changed();
    }
    private void layoutMenu(){
        if(menu==null||menuActions==null||params.isEmpty())return;
        WindowManager.LayoutParams p=params.get(0);
        boolean horizontal=p.width>p.height;
        Rect display=wm.getCurrentWindowMetrics().getBounds();
        float growth=Math.min(1.55f,Math.max(1f,scale/.28f));
        int gap=Math.round(dp(20)*growth);
        int major=Math.min(horizontal?p.width:p.height,(horizontal?display.width():display.height())/2);
        int minor=Math.min(horizontal?p.height:p.width,(horizontal?display.height():display.width())/2);
        int side=Math.max(1,Math.min(Math.round(dp(42)*growth),Math.min(minor-dp(8),(major-gap)/2)));
        menuActions.setOrientation(horizontal?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);
        for(int i=0;i<2;i++){
            LinearLayout.LayoutParams action=new LinearLayout.LayoutParams(side,side);
            if(i==0){if(horizontal)action.rightMargin=gap;else action.bottomMargin=gap;}
            View button=menuActions.getChildAt(i);button.setLayoutParams(action);
            int padding=Math.round(side*12f/42);button.setPadding(padding,padding,padding,padding);
        }
        menuActions.setLayoutParams(new FrameLayout.LayoutParams(horizontal?side*2+gap:side,horizontal?side:side*2+gap,Gravity.CENTER));
        sizeAction.refresh();muteAction.refresh();
    }
    private void hideMenu(){
        if(menu==null)return;View old=menu;menu=null;menuActions=null;sizeAction=null;muteAction=null;
        if(old.getParent() instanceof android.view.ViewGroup parent)parent.removeView(old);
        changed();
    }
    private void changed(){
        if(controlsRoot!=null)controlsRoot.requestLayout();
        if(host instanceof SystemUiHost service)service.controlsChanged();
    }
    void systemOverlay(boolean blocked){
        if(systemBlocked==blocked)return;systemBlocked=blocked;
        if(blocked){
            clearEdgeHold();clearMotion();
            if(edgeGesture!=0)cancelEdgeDrag();
            else if(miniDragBase!=null)cancelMiniDrag();
            else if(dragging&&token!=null)Wire.control(c,"NORMAL",token,taskId,0,0,0);
            dragging=false;hideMenu();hideDismiss();for(View v:views)v.setVisibility(View.GONE);changed();
        }else if(!views.isEmpty()&&(!transitioning||inputPending))layout();
    }
    static boolean dismissContains(int left,int top,int right,int bottom,float px,float py){
        return MiniMagnet.contains(left,top,right,bottom,px,py,1);
    }
    private int dismissDimension(String name,float fallbackDp){
        int id=c.getResources().getIdentifier(name,"dimen","com.android.systemui");
        try{if(id!=0)return c.getResources().getDimensionPixelSize(id);}catch(android.content.res.Resources.NotFoundException ignored){}
        return dp(fallbackDp);
    }
    private android.graphics.drawable.Drawable dismissDrawable(String name){
        int id=c.getResources().getIdentifier(name,"drawable","com.android.systemui");
        try{return id==0?null:c.getResources().getDrawable(id,c.getTheme()).mutate();}
        catch(android.content.res.Resources.NotFoundException ignored){return null;}
    }
    private void showDismiss(){
        if(dismiss!=null)return;
        // Target SystemUI Bubble DismissView: 96dp circle, 32dp icon, 50dp + nav bottom.
        int size=dismissDimension("floating_dismiss_background_size",96);
        int iconSize=dismissDimension("floating_dismiss_icon_size",32);
        WindowMetrics metrics=wm.getCurrentWindowMetrics();
        Insets nav=metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars());
        int left=(metrics.getBounds().width()-size)/2;
        int top=metrics.getBounds().height()-nav.bottom-dismissDimension("floating_dismiss_bottom_margin",50)-size;
        dismissBounds.set(left,top,left+size,top+size);dismissEntered=false;
        android.graphics.drawable.Drawable background=dismissDrawable("floating_dismiss_background");
        android.graphics.drawable.Drawable icon=dismissDrawable("floating_dismiss_ic_close");
        dismiss=new View(c){
            final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas cv){
                float cx=getWidth()/2f,cy=getHeight()/2f,radius=Math.min(getWidth(),getHeight())/2f;
                if(background!=null){background.setBounds(0,0,getWidth(),getHeight());background.draw(cv);}
                else{paint.setStyle(Paint.Style.FILL);paint.setColor(0xee34303a);cv.drawCircle(cx,cy,radius,paint);}
                if(dismissEntered){paint.setStyle(Paint.Style.FILL);paint.setColor(0x338b3d46);cv.drawCircle(cx,cy,radius,paint);}
                if(icon!=null){int l=(getWidth()-iconSize)/2,t=(getHeight()-iconSize)/2;icon.setBounds(l,t,l+iconSize,t+iconSize);icon.draw(cv);}
                else{
                    float half=iconSize*7f/24;paint.setColor(Color.WHITE);paint.setStrokeWidth(iconSize*2f/24);
                    cv.drawLine(cx-half,cy-half,cx+half,cy+half,paint);cv.drawLine(cx-half,cy+half,cx+half,cy-half,paint);
                }
            }
        };
        dismiss.setContentDescription("松手关闭小窗");
        dismiss.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
            if(v!=dismiss)return;
            int[] xy=new int[2];v.getLocationOnScreen(xy);
            dismissBounds.set(xy[0],xy[1],xy[0]+v.getWidth(),xy[1]+v.getHeight());
        });
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(size,size,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.LEFT;p.x=left;p.y=top;p.setFitInsetsTypes(0);p.setTitle("NativeComplements-dismiss");p.windowAnimations=0;
        try{SystemUiHost.trust(p);wm.addView(dismiss,p);}catch(RuntimeException e){dismiss=null;dismissBounds.setEmpty();}
    }
    private void highlight(boolean entered){
        if(dismiss==null)return;
        if(entered&&!dismissEntered)dismiss.performHapticFeedback(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE);
        if(entered!=dismissEntered){dismissEntered=entered;dismiss.invalidate();}
    }
    private void hideDismiss(){
        if(dismiss!=null){try{wm.removeViewImmediate(dismiss);}catch(RuntimeException ignored){}dismiss=null;}
        dismissEntered=false;dismissBounds.setEmpty();
    }
    private void hideViews(){
        hideMenu();
        if(controlsRoot!=null){try{wm.removeViewImmediate(controlsRoot);}catch(RuntimeException ignored){}controlsRoot=null;}
        else for(View v:views)try{wm.removeViewImmediate(v);}catch(RuntimeException ignored){}
        views.clear();params.clear();unclippedBounds.clear();miniTouchActive=false;hideDismiss();
    }
    void hide(){cancelEdgeDrag();cancelMiniDrag();clearMotion();hideViews();frame="";token=null;dragging=false;changed();}
    void close(){send("CLOSE",0,0,0);hide();}
    void imeTop(int top){if(imeTop==top)return;imeTop=top;if(!views.isEmpty()&&!transitioning&&!dragging)layout();}
}
