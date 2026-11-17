package dev.local.sunwindowdemo;

import android.app.KeyguardManager;
import android.app.ActivityOptions;
import android.content.*;
import android.content.res.Configuration;
import android.view.WindowManager;
import android.graphics.*;
import android.os.*;
import android.util.AtomicFile;
import android.view.Choreographer;
import android.view.animation.PathInterpolator;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import org.json.*;

/** Related freeform tasks share one presentation and a live host lease.
 * No global freeform override or system configuration writes. Explicit suggestion input is isolated.
 */
final class WindowEngine {
    final FrameworkAccess a;
    private final AndroidWindowPresentation presentation;
    private final Consumer<String> log;
    private final AtomicFile journal = new AtomicFile(dev.local.sunwindowdemo.core.ModuleIdentity.CURRENT.systemFile("sunwindow-demo5.json"));
    private final WindowOrientation orientations=new WindowOrientation(dev.local.sunwindowdemo.core.ModuleIdentity.CURRENT.systemFile("nativecomplements-window-orientation.json"));
    private final java.util.concurrent.atomic.AtomicBoolean orientationSaveQueued=new java.util.concurrent.atomic.AtomicBoolean();
    private boolean launchRoutingReady;
    void launchRoutingReady(){launchRoutingReady=true;}
    private Context context;
    private Object wms, root, lock, atm;
    private Handler handler;
    private Choreographer frames;
    private WindowAudio audio;
    private TaskCleanupGuard cleanup;
    void installCleanupGuard(io.github.libxposed.api.XposedModule module,ClassLoader loader){
        TaskCleanupGuard guard=new TaskCleanupGuard(log);
        if(guard.install(module,loader))cleanup=guard;
    }
    private Consumer<Intent> suggestionInput;
    private boolean visibleResumeReady;
    void visibleResumeReady(){visibleResumeReady=true;}
    private IBinder frameSink;
    private volatile int clientUid=-1,uiUid=-1;
    private IBinder uiLease;
    private long uiSeen;
    private boolean uiReady;
    private volatile boolean landscapeShareWindow,endOnClose,keepEdgeAwake;
    private volatile boolean autoMinimizeOnOpen=true;
    private WindowContinuation continuation;
    private boolean continuationReady;
    void continuationReady(){continuationReady=true;}
    record ShareLaunch(Object request,ActivityOptions options,IBinder lease,Object sourceTask,boolean nativeFullscreen) {}
    private record ShareStarted(Session target,Session displaced,Session previous,java.util.List<Session> previousPinned) {}
    private record ShareReused(Session target) {}
    final ThreadLocal<ShareLaunch> shareLaunch=new ThreadLocal<>();
    /** fullscreen: the topmost visible, unmanaged fullscreen app task captured before this start.
     * It equals task when that task holds focus, and differs when a focused floating window covers it. */
    record NativeForeground(Object task,String pkg,int userId,Object display,int mode,boolean owned,Object fullscreen) {}
    final ThreadLocal<NativeForeground> launchForeground=new ThreadLocal<>();
    NativeForeground nativeForeground()throws Exception{
        if(lock==null||root==null)return null;
        synchronized(lock){
            Object task=a.focused.invoke(root);if(task==null)return null;
            Object top=a.call(task,"topRunningActivity");if(top==null)return null;
            int mode=a.integer(task,"getWindowingMode");boolean owned=owns(task);
            Object display=a.call(task,"getDisplayContent");
            // Without any floating session the focused task is the fullscreen foreground; skip the scan.
            Object fullscreen=mode==1&&!owned?task:sessions.isEmpty()?null:visibleFullscreen(display);
            return new NativeForeground(task,(String)a.field(top,"packageName"),(Integer)a.field(task,"mUserId"),
                display,mode,owned,fullscreen);
        }
    }
    /** Topmost visible standard fullscreen leaf task on display that no session manages; null when Home or nothing is beneath. */
    private Object visibleFullscreen(Object display)throws Exception{
        if(display==null)return null;
        Object[] found={null};boolean[] done={false};
        a.call(display,"forAllTasks",new Class<?>[]{Consumer.class},(Consumer<Object>)t->{
            if(done[0])return;
            try{
                if(!a.bool(t,"isLeafTask")||owns(t)||!a.bool(t,"isVisibleRequested")||a.integer(t,"getWindowingMode")!=1)return;
                done[0]=true;
                if(a.integer(t,"getActivityType")==1&&a.call(t,"topRunningActivity")!=null)found[0]=t;
            }catch(Exception e){throw new IllegalStateException(e);}
        });
        return found[0];
    }
    private boolean keepNativeForeground(NativeForeground before,Object task,String pkg,int user,Object display){
        if(before==null)return false;
        if(WindowPolicy.nativeForeground(before.task!=null&&before.task==task,before.pkg,pkg,before.userId,user,
            before.display!=null&&before.display==display,before.mode,before.owned))return true;
        // A focused floating window may cover the real fullscreen app. Starting that exact
        // task must bring it forward natively instead of converting it into a window.
        return WindowPolicy.coveredFullscreen(before.fullscreen!=null&&before.fullscreen!=before.task&&before.fullscreen==task,
            before.display!=null&&before.display==display);
    }
    private String uiTouch="尚未收到角落触摸";
    private boolean controlUid(int uid){return uid>=0&&(uid==clientUid||uid==uiUid);}
    private volatile boolean hideRecents;
    private long frameSequence;
    static final String HISTORY=Wire.PKG+".HISTORY",HISTORY_QUERY=Wire.PKG+".HISTORY_QUERY";
    private final WindowHistory history=new WindowHistory();
    private final AtomicFile historyFile=new AtomicFile(dev.local.sunwindowdemo.core.ModuleIdentity.CURRENT.systemFile("sunwindow-hidden-tasks.json"));

    private final PathInterpolator modeCurve=new PathInterpolator(.2f,0,0,1);
    private final Binder controlChannel=new Binder(){
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws RemoteException {
            if(code==Wire.PREPARE_BACK||code==Wire.CANCEL_BACK||code==Wire.FINISH_BACK){
                if(uiUid<0||Binder.getCallingUid()!=uiUid)return false;
                data.enforceInterface(Wire.CHANNEL);
                if(code==Wire.CANCEL_BACK){String token=data.readString();data.enforceNoDataAvail();handler.post(()->cancelBackPreparation(token));return true;}
                if(code==Wire.FINISH_BACK){
                    String token=data.readString();data.enforceNoDataAvail();if(reply==null)return false;
                    try(android.view.SurfaceControl.Transaction mask=backFinishMask(token)){
                        reply.writeNoException();reply.writeTypedObject(mask,0);
                    }return true;
                }
                int task=data.readInt();String pkg=data.readString(),token=data.readString();
                android.graphics.RectF pose=data.readTypedObject(android.graphics.RectF.CREATOR);float radius=data.readFloat();
                android.view.SurfaceControl leash=data.readTypedObject(android.view.SurfaceControl.CREATOR);data.enforceNoDataAvail();
                boolean accepted=prepareBack(task,pkg,token,pose,radius,leash);
                if(leash!=null)leash.release();
                if(reply!=null){reply.writeNoException();reply.writeBoolean(accepted);}return true;
            }
            if(code!=Wire.MESSAGE)return super.onTransact(code,data,reply,flags);
            if(!controlUid(Binder.getCallingUid()))return false;
            data.enforceInterface(Wire.CHANNEL);
            Intent i=Intent.CREATOR.createFromParcel(data);data.enforceNoDataAvail();
            String op=i.getStringExtra("op");
            if(!java.util.Set.of("RESIZE","END_RESIZE","MOVE","RELEASE","DOCK","MINI","NORMAL","FULL","CLOSE","MINI_SIZE","MUTE","RESTORE_MINI","MINI_DRAG","MINI_DRAG_CANCEL","EDGE_DRAG","EDGE_DOCK","EDGE_BAR_DRAG","EDGE_BAR_DOCK","EDGE_DRAG_CANCEL").contains(op==null?"":op))return false;
            handler.post(()->enqueueControl(i));return true;
        }
    };
    private void enqueueControl(Intent i){
        if(i.getIntExtra("protocol",0)!=Wire.PROTOCOL)return;
        Session s=controlled(i);
        if(s==null||s.restoring)return;
        String op=i.getStringExtra("op");
        command(i);
    }
    private volatile Session active;
    /**
     * 同时挂起的迷你窗，按“最近使用”排序（末尾最新）。
     *
     * 以前这里只有一个 pinned，等于引擎只能“一个前台 + 一个挂着的迷你窗”；
     * 卡片上滑连开第二个应用时，前一个会被 finish 掉。Z-Flow 的
     * max_freeform_windows 允许 1~5 个，所以换成列表，容量由 [maxWindows] 约束。
     */
    private volatile java.util.List<Session> pinned=java.util.List.of();
    /** 同时存在的小窗上限（含前台窗），由打开请求携带，默认 2 与旧行为一致。 */
    private volatile int maxWindows=2;
    private BackPreparation backPreparation;
    private record BackPreparation(Object task,String token,TaskVisibilityGuard mask,TaskCover cover,IBinder lease){}
    private boolean prepareBack(int id,String pkg,String token,android.graphics.RectF pose,float radius,android.view.SurfaceControl leash){
        if(lock==null||handler==null||context==null||!Wire.allowedPackage(pkg)||!Wire.validToken(token))return false;
        synchronized(lock){try{
            if(!uiReady||uiLease==null||!uiLease.isBinderAlive()||SystemClock.elapsedRealtime()-uiSeen>3000
                ||normalOccupied()||pendingStart!=null
                ||screenBlocked())return false;
            Object task=find(id);if(task==null||task!=a.focused.invoke(root)||sessionFor(task)!=null||a.integer(task,"getWindowingMode")!=1)return false;
            ComponentName component=(ComponentName)a.field(task,"realActivity");
            if(component==null||!pkg.equals(component.getPackageName()))return false;
            cancelBackPreparation(null);
            Object parent=a.call(task,"getParent"),display=a.call(task,"getDisplayContent");
            Rect bounds=(Rect)a.call(display,"getBounds");
            boolean preview=validBackPose(pose,bounds)&&Float.isFinite(radius)&&radius>=0;
            // Apps without a Launcher back preview still morph from fullscreen.
            // Prepare the cover before native cancellation, preserving the same flash guard.
            RectF from=preview?new RectF(pose):new RectF(bounds);float corner=preview?radius:0;
            TaskCover cover=new TaskCover(a,context,task,parent,pkg,from,from,corner,corner,1,false);
            TaskVisibilityGuard mask=cover.mask;
            BackPreparation prepared=new BackPreparation(task,token,mask,cover,uiLease);backPreparation=prepared;
            mask.preserveBackdrop();
            a.call(root,"ensureActivitiesVisible");
            a.call(wms,"requestTraversal");
            if(leash!=null&&leash.isValid())try(android.view.SurfaceControl.Transaction tx=new android.view.SurfaceControl.Transaction()){
                // Keep our own reference after the incoming Parcelable is released.
                android.view.SurfaceControl owned=android.view.SurfaceControl.class.getDeclaredConstructor(android.view.SurfaceControl.class,String.class)
                    .newInstance(leash,"NativeComplements-back-preview");
                handler.postDelayed(owned::release,5000);mask.inTransaction(tx,owned);tx.apply();
            }
            handler.postDelayed(()->{synchronized(lock){if(backPreparation==prepared)cancelBackPreparation(token);}},6500);
            log.accept("BACK_PREPARED task="+id+" preview="+preview+" from="+from);return true;
        }catch(Throwable failure){cancelBackPreparation(token);log.accept("BACK_PREPARE_FAILED "+FrameworkAccess.reason(failure));return false;}}
    }
    private static boolean validBackPose(android.graphics.RectF p,Rect d){
        return p!=null&&Float.isFinite(p.left)&&Float.isFinite(p.top)&&Float.isFinite(p.right)&&Float.isFinite(p.bottom)
            &&p.width()>0&&p.height()>0&&p.width()<=d.width()*1.05f&&p.height()<=d.height()*1.05f
            &&p.right>d.left&&p.left<d.right&&p.bottom>d.top&&p.top<d.bottom;
    }
    private android.view.SurfaceControl.Transaction backFinishMask(String token){
        if(lock==null)return null;
        synchronized(lock){
            BackPreparation p=backPreparation;
            if(p==null||!p.token.equals(token)||p.lease!=uiLease||!p.lease.isBinderAlive()||!p.mask.holding())return null;
            android.view.SurfaceControl.Transaction mask=new android.view.SurfaceControl.Transaction();
            try{
                p.mask.inTransaction(mask,null);
                mask.addTransactionCommittedListener(handler::post,()->repairBackMask(p));
                return mask;
            }catch(RuntimeException error){mask.close();throw error;}
        }
    }
    private void repairBackMask(BackPreparation prepared){
        synchronized(lock){
            if(prepared.mask.holding())try{prepared.mask.hide();}catch(Exception ignored){}
            else{Session owner=sessionFor(prepared.task);TaskVisibilityGuard next=owner==null?null:presentationMask(owner);
                if(next!=null&&next.holding())prepared.mask.continueWith(next);
                else prepared.mask.afterTransaction(owner==null?1:presentationAlpha(owner));}
        }
    }
    private void cancelBackPreparation(String token){
        if(lock==null)return;
        synchronized(lock){BackPreparation p=backPreparation;if(p==null||token!=null&&!p.token.equals(token))return;
            backPreparation=null;
            if(p.cover!=null)p.cover.close();else try{p.mask.release();}catch(Exception ignored){}
            try{a.call(root,"ensureActivitiesVisible");a.call(wms,"requestTraversal");}
            catch(Exception error){log.accept("BACK_BACKDROP_RESTORE_FAILED "+FrameworkAccess.reason(error));}
            log.accept("BACK_PREPARATION_RELEASED");
        }
    }
    private boolean visible(Session s){return s!=null&&(s==active||pinned.contains(s));}
    private boolean isPinned(Session s){return s!=null&&pinned.contains(s);}
    private Session firstPinned(){java.util.List<Session> l=pinned;return l.isEmpty()?null:l.get(0);}
    /** 追加/刷新一个挂起的迷你窗；同一会话重复调用只更新顺序。 */
    private void addPinned(Session s){
        if(s==null)return;
        java.util.ArrayList<Session> next=new java.util.ArrayList<>(pinned);
        next.remove(s);next.add(s);
        pinned=java.util.List.copyOf(next);
    }
    private void removePinned(Session s){
        if(s==null||!pinned.contains(s))return;
        java.util.ArrayList<Session> next=new java.util.ArrayList<>(pinned);next.remove(s);
        pinned=java.util.List.copyOf(next);
    }
    private void clearPinned(){pinned=java.util.List.of();}
    /** 加入后按上限淘汰最旧的迷你窗（Z-Flow 语义：超出时自动关闭最早的小窗）。 */
    private void retainPinned(Session s,String why){
        addPinned(s);
        evictPinned(s,why);
    }
    /** 超出上限时淘汰最旧的迷你窗，[keep] 一定不会被淘汰。 */
    private void evictPinned(Session keep,String why){
        int limit=Math.max(1,maxWindows);
        while(pinned.size()+(active!=null?1:0)>limit){
            Session oldest=null;
            for(Session p:pinned)if(p!=keep){oldest=p;break;}
            if(oldest==null)break;
            removePinned(oldest);
            log.accept("MINI_EVICT task="+oldest.taskId+" limit="+limit);
            finishGroup(oldest,false,why);
        }
    }
    private boolean normalOccupied(){return active!=null&&!active.restoring&&"normal".equals(active.frame);}
    private boolean entryInputPending(Session s){
        return visible(s)&&!s.restoring&&!s.closing&&!s.fullTransition&&!s.screenSuspended&&!s.prioritySuspended&&!s.rotation.active()
            &&(s.entering||s.cover!=null&&!s.coverFullscreen);
    }
    /** 把「打开即迷你」翻译成一条标准的 MINI 控制指令，复用已有的形态切换。 */
    private Intent miniControl(Session s){
        return new Intent(Wire.COMMAND).putExtra("protocol",Wire.PROTOCOL).putExtra("op","MINI")
            .putExtra("taskId",s.taskId).putExtra("token",s.token).putExtra("hideRecents",hideRecents);
    }
    private void completeEntryInput(Session s)throws Exception{
        if(!visible(s)||s.restoring||s.closing||s.fullTransition||s.prioritySuspended||s.screenSuspended||entryInputPending(s))return;
        if("normal".equals(s.frame))focusExpanded(s);
        else settleAsMini(s);
        Intent pending=s.entryControl;s.entryControl=null;
        frameReport("ENTRY_INPUT_READY");
        if(pending!=null)handler.post(()->{if(visible(s)&&!s.restoring&&!s.closing)command(pending);});
    }
    /**
     * 出生即迷你的会话（卡片上滑）：补上 {@code state("mini")} 原本由 animate() 收尾做的那几步。
     *
     * 迷你窗不该持有焦点，否则桌面/最近任务会被它抢走，返回键也跟着乱；
     * 这几步失败不影响窗口本身，只记一条日志。
     */
    private void settleAsMini(Session s){
        try{
            synchronized(lock){
                // 迷你窗既不显示把手条，也不该让 dim 压暗屏幕；入场收尾可能把它设回来，这里兜底。
                if(s.barAlpha!=0){s.barAlpha=0;renderFrame(s);}
                a.call(s.task,"setFocusable",new Class<?>[]{boolean.class},false);
                a.call(wms,"updateFocusedWindowLocked",new Class<?>[]{int.class,boolean.class},0,true);
                a.call(root,"resumeFocusedTasksTopActivities");
                a.call(wms,"requestTraversal");
                log.accept("MINI_SETTLED task="+s.taskId+" bar="+s.barAlpha+" entering="+s.entering+" cover="+(s.cover!=null));
            }
        }catch(Throwable error){log.accept("MINI_SETTLE_FAILED "+FrameworkAccess.reason(error));}
    }
    private Session controlled(Intent i){
        int id=i.getIntExtra("taskId",-1);
        Session s=id<0?active:sessions.stream().filter(v->v.taskId==id).findFirst().orElse(null);
        return visible(s)&&s.token.equals(i.getStringExtra("token"))?s:null;
    }
    private void completeOpen(Intent request,boolean success){
        if(recoveryOpen!=null&&java.util.Objects.equals(recoveryOpen.getStringExtra("token"),request.getStringExtra("token"))){
            recoveryOpen=null;report("OPEN_WAIT_END");
        }
        ResultReceiver reply=request.getParcelableExtra("openReply",ResultReceiver.class);
        request.removeExtra("openReply");
        if(reply!=null){
            Bundle result=new Bundle();result.putBoolean("dispatched",request.getBooleanExtra("launchDispatched",false));result.putBoolean("superseded",request.getLongExtra("generation",-1)!=openGeneration);
            AndroidWindowLaunch ticket=pendingShortcutLaunch.get();
            result.putInt("taskId",ticket!=null&&ticket.state.generation==request.getLongExtra("generation",-1)?ticket.state.taskId:request.getIntExtra("taskId",-1));
            reply.send(success?1:0,result);
        }
    }
    private volatile java.util.List<Session> sessions=java.util.List.of();
    // A task is not owned yet during preflight. Keep that permission on this
    // thread only, then use the ordinary session lifetime after acquisition.
    private final ThreadLocal<Object> resizeCandidate=new ThreadLocal<>();
    private final java.util.Map<Object,Session> coverTasks=new java.util.IdentityHashMap<>();
    private volatile SwitchStart pendingStart;
    private volatile boolean recovering;
    final EntryTrace trace;
    private Session pendingRecovery;
    private final WindowRecoveryPolicy.Retry recoveryRetryState=new WindowRecoveryPolicy.Retry();
    private volatile int windowDim=Config.DEFAULT_WINDOW_DIM;
    private Runnable recoveryRetry;

    private Intent recoveryOpen;
    private int endedTaskId=-1;
    private volatile long openGeneration;
    private final java.util.concurrent.atomic.AtomicReference<AndroidWindowLaunch> pendingShortcutLaunch=
        new java.util.concurrent.atomic.AtomicReference<>();
    private volatile boolean refreshQueued;
    private String last = "后端正在初始化";
    private boolean attached;
    private volatile boolean hooksReady;
    void preflightReady(){hooksReady=true;}

    static final class Session {
        Session returnTo;
        LaunchCoverFlow launchFlow;
        boolean parked,restoreForeground,endOnClose,surfaceHidden;
        TaskVisibilityGuard startMask,rotationMask;
        TaskCover cover;boolean coverDrawn,coverFullscreen;
        final RotationGate rotation=new RotationGate();
        final java.util.Set<Object> rotationTransitions=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        boolean rotationProbeQueued;
        Object task, parent;
        int taskId, userId, mode, logicalShort, topState;
        int uid=-1;
        boolean miniLarge,edgeHidden,geometryDirty,refreshingGeometry,resizing;
        int edgeSide;
        float placementMargin;
        Rect safeBounds,miniBounds;
        Geometry.Placement miniPlacement;
        String lastRuntimeState;
        Rect lastSurfaceCrop;
        String pkg, token, component, baseId;
        String frame="normal";
        TaskDecoration decoration; volatile boolean presented;
        Point crop; boolean closing; int animationRev; float defaultScale;
        long nextStatus;
        Rect bounds;
        boolean alwaysOnTop, landscape, forceTranslucent, restoreTranslucent;
        Object orientationActivity;int orientationRequest;
        boolean orientationBaseline;
        boolean backRequested,backComplete,returning;
        boolean entering,entryStarted,entryFade,coverFollowing;
        Intent entryControl;
        Configuration originalConfig;
        String recoveryReason;
        long startedAt=SystemClock.elapsedRealtime();
        volatile boolean restoring;
        volatile boolean screenSuspended;
        volatile boolean prioritySuspended,priorityHideTask;
        boolean priorityWasFocusable;
        long priorityFocusUntil;
        IBinder lease;
        IBinder.DeathRecipient death;
        volatile Geometry.Placement placement;
        Geometry.Placement normalPlacement;
        volatile boolean transitioning,fullTransition,transitionHandle;
        volatile float radiusFactor=1,barAlpha=1,opacity=1;
        int displayW, displayH,displayRotation=-1;
        Geometry.Placement miniDragBase;float miniDragOpacity;
        Geometry.Placement edgeDragBase,edgeDragMini;int edgeDragSide;
        boolean edgeBarDragging;float edgeBarCenterX,edgeBarCenterY;
    }

    // Task objects and originals stay alive across switches. Only presentation changes.
    private static final int MAX_LINKED_TASKS=8;
    private record SwitchStart(Session from,Session target,boolean created,long generation) {}
    private record OwnedStart(Session target) {}
    WindowEngine(FrameworkAccess access, Consumer<String> logger) {
        a=access;log=logger;trace=new EntryTrace(a,logger);presentation=new AndroidWindowPresentation(this);
    }

    void attach(Object service) {
        if (attached) return;
        try {
            wms = service; context = (Context) a.field(wms, "mContext");
            dev.local.sunwindowdemo.features.diagnostics.Diagnostics.initialize(context,"system");
            root = a.field(wms, "mRoot"); lock = a.field(wms, "mGlobalLock"); atm = a.field(wms, "mAtmService");
            trace.attach(lock);
            HandlerThread worker = new HandlerThread("SunWindowDemo5",android.os.Process.THREAD_PRIORITY_DISPLAY); worker.start();
            handler = new Handler(worker.getLooper());
            if(cleanup!=null)cleanup.connect(context,handler,this::cleanupVisible);
            handler.post(()->{
                try{dev.local.sunwindowdemo.features.iconlayers.IconAssets.connect(context);}
                catch(RuntimeException unavailable){log.accept("ICON_ASSETS_UNAVAILABLE "+unavailable.getClass().getSimpleName());}
                audio=WindowAudio.create(context,handler,log);
                suggestionInput=ProductFeatures.createInput(a,context,root,lock,handler,log);
                try{frames=(Choreographer)Choreographer.class.getDeclaredMethod("getSfInstance").invoke(null);}
                catch(Exception e){frames=Choreographer.getInstance();log.accept("SF_CLOCK_FALLBACK "+e.getClass().getSimpleName());}
            });
            context.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    if (!Wire.COMMAND.equals(i.getAction())) return;
                    // Permission is enforced by registerReceiver; validate the originating UID too.
                    try {
                        int expected = context.getPackageManager().getApplicationInfo(Wire.PKG, 0).uid;
                        clientUid=expected;uiUid=context.getPackageManager().getApplicationInfo(BackBridge.PACKAGE,0).uid;
                        if(!controlUid(getSentFromUid()))return;
                        Bundle extras=i.getExtras();
                        if(getSentFromUid()==uiUid&&i.getIntExtra("protocol",0)==Wire.PROTOCOL&&extras!=null){
                            uiLease=extras.getBinder("lease");uiSeen=SystemClock.elapsedRealtime();
                            uiReady=i.getBooleanExtra("hostReady",false);
                            landscapeShareWindow=i.getBooleanExtra("landscapeShareWindow",false);
                            autoMinimizeOnOpen=i.getBooleanExtra("autoMinimizeOnOpen",true);
                            endOnClose=i.getBooleanExtra("endOnClose",false);keepEdgeAwake=i.getBooleanExtra("keepEdgeAwake",false);
                            windowDim=Rules.clamp(i.getIntExtra("windowDim",Config.DEFAULT_WINDOW_DIM),0,100);
                            handler.post(WindowEngine.this::updateContinuation);
                            uiTouch=i.getStringExtra("lastTouch");
                            if(extras.getBinder("frameChannel")!=null)frameSink=extras.getBinder("frameChannel");
                        }
                    } catch (Exception e) { return; }
                    String op=i.getStringExtra("op");
                    if("RESIZE".equals(op)||"END_RESIZE".equals(op)||"MOVE".equals(op)||"RELEASE".equals(op)||"DOCK".equals(op))enqueueControl(i);
                    else command(i);
                }
            }, new IntentFilter(Wire.COMMAND), null, handler, Context.RECEIVER_EXPORTED);
            context.registerReceiver(new BroadcastReceiver(){public void onReceive(Context c,Intent i){
                try{if(getSentFromUid()==context.getPackageManager().getApplicationInfo(LauncherGuard.PACKAGE,0).uid)pushHistory();}
                catch(Exception ignored){}
            }},new IntentFilter(HISTORY_QUERY),null,handler,Context.RECEIVER_EXPORTED);
            try{continuation=new WindowContinuation(context,log);}catch(Exception e){log.accept("EDGE_CONTINUATION_UNAVAILABLE "+FrameworkAccess.reason(e));}
            attached = true;
            IntentFilter screen=new IntentFilter(Intent.ACTION_SCREEN_OFF);
            screen.addAction(Intent.ACTION_SCREEN_ON);screen.addAction(Intent.ACTION_USER_PRESENT);
            context.registerReceiver(new BroadcastReceiver(){
                @Override public void onReceive(Context c,Intent i){
                    if(Intent.ACTION_SCREEN_OFF.equals(i.getAction()))cancelRecoveryOpen(null);
                    if(screenBlocked())synchronized(lock){
                        // A sleeping display may stop delivering vsync callbacks entirely.
                        for(Session s:new java.util.ArrayList<>(coverTasks.values()))if(s.cover!=null)try{s.cover.step(false,false);}
                        catch(Exception error){log.accept("COVER_SUSPEND_FAILED "+FrameworkAccess.reason(error));endCover(s);}
                    }
                    for(Session s:sessions)if(visible(s)&&!s.restoring){
                        try{synchronized(lock){updateScreenState(s);}}catch(Exception e){failure(s,e);}
                    }
                }
            },screen,null,handler,Context.RECEIVER_NOT_EXPORTED);
            handler.post(() -> {
                try{orientations.load();}catch(Exception error){log.accept("ORIENTATION_MEMORY_LOAD_FAILED "+FrameworkAccess.reason(error));}
                loadHistory();historyTick();pushHistory();
                note("READY: Android " + Build.VERSION.SDK_INT + "；仅系统框架；默认无窗口改动\n" + capabilities());
                if (journal.getBaseFile().exists()) recover("初始化恢复");
            });
        } catch (Throwable e) { log.accept("ATTACH_FAILED " + FrameworkAccess.reason(e)); }
    }

    private volatile String staticCapabilities;
    private volatile long staticCapabilitiesAt;
    private String capabilities() {
        try {
            String fixed=staticCapabilities;long now=SystemClock.elapsedRealtime();
            // ATMS support flags change only through settings that normally need a reboot; refresh every 30s
            // instead of taking the WM lock for each 750ms heartbeat.
            if(fixed==null||now-staticCapabilitiesAt>30000)synchronized (lock) {
                fixed="multiWindow=" + a.field(atm, "mSupportsMultiWindow")
                    + "\nfreeform=" + a.field(atm, "mSupportsFreeformWindowManagement");
                if(attached){staticCapabilities=fixed;staticCapabilitiesAt=now;}
            }
            return fixed + "\n恢复记录=" + journal.getBaseFile().exists();
        } catch (Exception e) { return "能力读取失败：" + FrameworkAccess.reason(e); }
    }

    private void command(Intent i) {
        try {
            String op = i.getStringExtra("op");
            if(i.getIntExtra("protocol",0)==Wire.PROTOCOL&&i.hasExtra("hideRecents")){
                boolean next=i.getBooleanExtra("hideRecents",false);
                if(next!=hideRecents){hideRecents=next;saveHistory();notifyRecents();}
            }
            // Heartbeats republish recents history only when it changed; explicit queries always publish.
            if ("STATUS".equals(op)) {
                pushHistory(false);
                String requestId=i.getIntExtra("protocol",0)==Wire.PROTOCOL?i.getStringExtra("requestId"):null;
                report("STATUS",requestId);return;
            }
            if(i.getIntExtra("protocol",0)!=Wire.PROTOCOL) throw new IllegalStateException("请重启加载 0.2.0 后端");
            if ("OTP_INPUT".equals(op)) {
                if(suggestionInput!=null)suggestionInput.accept(i);
                else{ResultReceiver receiver=i.getParcelableExtra("inputReply",ResultReceiver.class);if(receiver!=null)receiver.send(0,null);}
                return;
            }
            if ("CANCEL_OPEN".equals(op)) { cancelRecoveryOpen(i.getStringExtra("token"));return; }
            if ("GESTURE".equals(op)) { openGeneration++;cancelRecoveryOpen(null);cancelStart("gesture");trace.stop();gestureStart(new Intent(i).putExtra("generation",openGeneration));return; }
            if ("OPEN".equals(op)) { openGeneration++;cancelRecoveryOpen(null);cancelStart("open");trace.stop();open(new Intent(i).putExtra("generation",openGeneration),0); return; }
            if ("CLOSE".equals(op)||"NORMAL".equals(op)||"MINI".equals(op)||"FULL".equals(op)||"RESIZE".equals(op)||"END_RESIZE".equals(op)||"RELEASE".equals(op)||"MOVE".equals(op)||"DOCK".equals(op)||"MINI_SIZE".equals(op)||"MUTE".equals(op)||"RESTORE_MINI".equals(op)||"MINI_DRAG".equals(op)||"MINI_DRAG_CANCEL".equals(op)||"EDGE_DRAG".equals(op)||"EDGE_DOCK".equals(op)||"EDGE_BAR_DRAG".equals(op)||"EDGE_BAR_DOCK".equals(op)||"EDGE_DRAG_CANCEL".equals(op)) {
                control(i); return;
            }
            if ("RESTORE".equals(op)) {
                openGeneration++;cancelRecoveryOpen(null);cancelStart("manual-restore");trace.stop();recoveryRetryState.resetWaits();
                // An explicit recovery must also escape a stalled native close queue.
                Session s=active;if(s!=null){s.restoring=true;s.animationRev++;s.closing=false;}
                // An already pending queue must receive the explicit recovery intent
                // too; otherwise its boot-time reason keeps missing tasks blocked.
                synchronized(lock){if(recovering)for(Session pending:sessions)pending.recoveryReason="手动恢复";}
                recover("手动恢复");return;
            }
            if ("CANVAS".equals(op)) {
                Session s = active;
                if (s == null || s.restoring || !s.token.equals(i.getStringExtra("token")))
                    throw new IllegalStateException("没有对应测试任务");
                s.landscape = i.getBooleanExtra("landscape", false);
                refresh(s); note("CANVAS：逻辑画布已切换"); return;
            }
            if (!"START".equals(op)) return;
            if(waitForRecovery(i,()->command(i)))return;
            if (Build.VERSION.SDK_INT != 37) throw new IllegalStateException("首版仅验证 Android 17 / API 37");
            requireUnlocked("先解锁屏幕");
            String pkg = i.getStringExtra("package"), token = i.getStringExtra("token");
            if (!Wire.allowedPackage(pkg) || !Wire.validToken(token)) throw new IllegalArgumentException("目标或标识不匹配");
            // 卡片上滑开小窗的入口标记：`adb logcat -s SunWindowDemo5` 能直接看到,
            // 用来区分「请求根本没到引擎」和「到了但被准入拒绝」。
            if(i.getBooleanExtra("mini",false))android.util.Log.i("SunWindowDemo5",
                "OPEN_MINI task="+i.getIntExtra("taskId",-1)+" pkg="+pkg);
            Bundle extras = i.getExtras();
            // Z-Flow 的「最多同时小窗数量」随请求下发（1~5）。引擎不读对方 SP，
            // 只认这次请求的上限；没带就保持默认 2，与旧行为一致。
            int requestedMax=i.getIntExtra("maxWindows",0);
            if(requestedMax>=1&&requestedMax<=8)maxWindows=requestedMax;
            IBinder lease = uiLease;
            if (lease == null || !lease.isBinderAlive()) throw new IllegalStateException("控制进程已退出");
            Session previous=active;java.util.List<Session> previousPinned=pinned;
            Session displaced=normalOccupied()?active:null;
            rememberOrientation(previous);
            Session s;
            synchronized (lock) {
                int requestTask=i.getIntExtra("taskId",-1);
                Session retained=sessionFor(taskForOpen(requestTask,pkg));
                if(retained!=null){
                    if(!presentExisting(retained,false)){if(waitStart(i))return;throw new EntryNotReady();}
                    // 多任务卡片上滑：这个应用已经有窗口了，把它收成迷你窗，而不是留在默认窗。
                    if(i.getBooleanExtra("mini",false))queueMini(retained);
                    completeOpen(i,true);return;
                }
                try{s = snapshot(requestTask, pkg, token,i.getBooleanExtra("allowInheritedFreeform",false));}
                catch(EntryNotReady pending){if(waitStart(i))return;throw pending;}
                if(i.hasExtra("entryBegan"))log.accept("ENTRY_READY task="+s.taskId+" waitedMs="+(SystemClock.elapsedRealtime()-i.getLongExtra("entryBegan",0)));
                if(i.getBooleanExtra("morphEntry",false)){
                    s.landscape=WindowPolicy.currentLandscape(a.config(s.task,false).orientation,s.orientationRequest,s.landscape);
                    log.accept("CONVERT_ORIENTATION task="+s.taskId+" landscape="+s.landscape);
                }
            }
            trace.arm(pkg,openGeneration);
            s.entryFade=i.getBooleanExtra("predictiveEntry",false)||!i.getBooleanExtra("morphEntry",false);
            bindLease(s,lease);
            try {
                save(s); // Durable original state BEFORE the first task mutation.
                synchronized (lock) {
                    if (find(s.taskId) != s.task || !identity(s.task, s) || !lease.isBinderAlive())
                        throw new IllegalStateException("准备期间任务或控制进程已改变");
                    validate(s.task);
                    a.call(atm,"deferWindowLayout");
                    try {
                        /*
                         * 卡片上滑：一开始就按迷你窗布局。
                         *
                         * 之前是「按默认窗入场，入场结束后再发一条 MINI」，用户看到的是
                         * 一个完整默认窗先出来、约 1 秒后缩成迷你窗。现在把形态在第一次
                         * 布局之前就定好，入场动画直接长到迷你尺寸，没有二次变形。
                         */
                        boolean startMini=i.getBooleanExtra("mini",false);
                        if(startMini)s.frame="mini";
                        prepareGeometry(s); // Bounds hook must know the canvas before switching mode.
                        if(startMini){
                            // 出生即迷你的窗口要避开已经挂着的迷你窗，否则完全重叠。
                            s.placement=cascadeMini(s,s.placement);s.miniPlacement=s.placement;
                        }
                        s.decoration=new TaskDecoration(a);
                        if(startMini)s.decoration.disableDim();
                        s.entering=true;s.transitioning=true;s.opacity=0;s.barAlpha=0;
                        if(previous!=null&&displaced!=null){
                            previous.parked=true;previous.opacity=0;a.opacity(previous.task,0);
                            a.call(previous.task,"setFocusable",new Class<?>[]{boolean.class},false);
                            if(previous.decoration!=null)previous.decoration.hide(previous.task);
                        }
                        active = s;
                        // 前台窗已被新窗口取代，旧的保持迷你形态挂着；超过上限时淘汰最旧的。
                        if(previous!=null&&displaced==null&&previous!=s)retainPinned(previous,"超出小窗上限");
                        frameReport("ENTRY_TOUCH_GUARD");
                        a.call(a.field(s.task,"mTransitionController"),"setNoAnimation",new Class<?>[]{a.wc},s.task);
                        BackPreparation prepared=backPreparation;
                        if(prepared!=null&&prepared.task==s.task&&prepared.token.equals(s.token)&&prepared.lease==lease){
                            prepared.mask.adoptBackdrop();
                            backPreparation=null;
                            if(prepared.cover!=null){
                                s.entryFade=false;s.cover=prepared.cover;s.coverDrawn=false;s.coverFullscreen=false;
                                s.cover.entryDecorations(true);
                                s.cover.beforeReveal(()->renderFrame(s));
                                s.cover.resumeTo(visualBounds(s.placement),a.config(s.task,false).densityDpi/160f*MotionStyle.radius(s.placement.scale(),s.defaultScale));
                                coverTasks.put(s.task,s);pollCover(s,s.cover);
                            }else s.startMask=prepared.mask;
                        }
                        beginCover(s,s.entryFade?s.placement:new Geometry.Placement(s.displayW,s.displayH,1,0,0),s.placement,false);
                        a.opacity(s.task,0);
                        a.call(s.task,"setForceTranslucent",new Class<?>[]{boolean.class},true);
                        if(!a.bool(s.task,"isForceTranslucent"))throw new IllegalStateException("系统未保留小窗下方的内容");
                        a.call(s.task, "setWindowingMode", new Class<?>[]{int.class}, 5);
                        if (a.integer(s.task, "getWindowingMode") != 5) throw new IllegalStateException("系统未进入 freeform");
                        a.call(s.task, "setAlwaysOnTop", new Class<?>[]{boolean.class}, true);
                        refresh(s);
                        a.call(s.task,"moveToFront",new Class<?>[]{String.class},"SunWindowDemo8.2");
                        a.call(root,"ensureActivitiesVisible");
                        a.call(root,"resumeFocusedTasksTopActivities");
                    } finally { a.call(atm,"continueWindowLayout"); }
                }
                history.windowed(new WindowHistory.Entry(s.taskId,s.userId,s.pkg,s.component,s.baseId));saveHistory();
                notifyRecents();
                settleNewDefault(displaced,s);
                completeOpen(i,true);
                note("ACTIVE：小窗已开启，无定时退出");
                if(SystemClock.elapsedRealtime()>=s.nextStatus) {s.nextStatus=SystemClock.elapsedRealtime()+1000;report("TICK");}
            handler.postDelayed(() -> tick(s), WindowLifecycle.Pause.LIVE.delay);
            } catch (Throwable e) {
                completeOpen(i,false);
                synchronized(lock){
                    active=previous;pinned=previousPinned;
                    if(previous!=null){
                        previous.parked=false;previous.opacity=1;
                        a.call(previous.task,"setFocusable",new Class<?>[]{boolean.class},"normal".equals(previous.frame));
                        renderFrame(previous);a.call(root,"ensureActivitiesVisible");a.call(root,"resumeFocusedTasksTopActivities");
                    }
                }
                if(sessions.contains(s))finishGroup(s,false,"启动未完成");
                else lease.unlinkToDeath(s.death,0);
                throw e;
            }
        } catch (Throwable e) { cancelBackPreparation(i.getStringExtra("token"));completeOpen(i,false);note("BLOCKED：" + FrameworkAccess.reason(e) + "\n" + capabilities()); }
    }

    private void open(Intent request,int attempt) {
        String pkg=request.getStringExtra("package");
        final long generation=request.getLongExtra("generation",-1);
        try {
            if(generation!=openGeneration){clearShortcutLaunch(generation);completeOpen(request,false);return;}
            long began=request.getLongExtra("began",0);
            if(began==0){began=SystemClock.elapsedRealtime();request.putExtra("began",began);}
            if(SystemClock.elapsedRealtime()-began>12000)throw new IllegalStateException("等待应用/转场超时，phase="+request.getStringExtra("phase"));
            if(!Wire.allowedPackage(pkg)||Wire.PKG.equals(pkg))throw new IllegalArgumentException("请选择可启动应用");
            requireUnlocked("请先解锁");
            if(waitForRecovery(request,()->open(request,attempt+1)))return;
            boolean shortcut=request.hasExtra("shortcutLaunch");
            android.app.PendingIntent shortcutLaunch=request.getParcelableExtra("shortcutLaunch",android.app.PendingIntent.class);
            if(shortcut&&(shortcutLaunch==null||!shortcutLaunch.isActivity()||!android.os.Process.myUserHandle().equals(request.getParcelableExtra("shortcutUser",UserHandle.class))))
                throw new IllegalArgumentException("shortcut identity");
            final int shortcutUser=shortcut?(Integer)a.call(request.getParcelableExtra("shortcutUser",UserHandle.class),"getIdentifier"):-1;
            if(!shortcut){
                // Package opens intentionally select the main task before retained mini-programs.
                Session retained=null;
                synchronized(lock){for(Session s:sessions)if(pkg.equals(s.pkg)&&live(s)){retained=s;break;}}
                if(retained!=null){
                    if(!presentExisting(retained,request.getBooleanExtra("wakeStep",false))){
                        waitOpen(request,attempt,"wait-session",retained.taskId);return;
                    }
                    request.putExtra("taskId",retained.taskId);completeOpen(request,true);report("REOPEN");return;
                }
            }
            final Object[] candidate={null};final long[] newest={Long.MIN_VALUE};
            if(shortcut){
                if(request.getBooleanExtra("launched",false)){
                    AndroidWindowLaunch ticket=pendingShortcutLaunch.get();
                    if(ticket==null||!ticket.state.currentRequest(generation,pkg,shortcutUser))
                        throw new IllegalStateException("快捷功能启动请求已失效");
                    if(ticket.state.dispatched&&ticket.state.taskId>=0)synchronized(lock){
                        Object task=find(ticket.state.taskId);
                        ComponentName component=task==null?null:(ComponentName)a.field(task,"realActivity");
                        if(task!=null&&(task!=ticket.task||(Integer)a.field(task,"mUserId")!=ticket.state.userId
                            ||component==null||!component.getPackageName().equals(ticket.state.targetPackage)
                            ||!a.bool(task,"isRootTask")||!a.bool(task,"isLeafTask")))
                            throw new IllegalStateException("快捷功能目标任务已改变");
                        candidate[0]=task;
                    }
                }
            }else synchronized(lock){a.call(root,"forAllTasks",new Class<?>[]{Consumer.class},(Consumer<Object>)task->{
                try{ComponentName component=(ComponentName)a.field(task,"realActivity");
                    if(component!=null&&pkg.equals(component.getPackageName())&&(Integer)a.field(task,"mUserId")==0
                        &&a.bool(task,"isRootTask")&&a.bool(task,"isLeafTask")){
                        long moved=(Long)a.field(task,"mLastTimeMoved");if(moved>newest[0]){newest[0]=moved;candidate[0]=task;}
                    }
                }catch(Exception e){throw new IllegalStateException(e);}
            });}
            int id=candidate[0]==null?-1:(Integer)a.field(candidate[0],"mTaskId");
            if(!request.getBooleanExtra("launched",false)&&candidate[0]!=null)synchronized(lock){
                if(WindowPolicy.morphEntry(shortcut,candidate[0]==a.focused.invoke(root),
                    a.bool(candidate[0],"isVisibleRequested"),a.integer(candidate[0],"getWindowingMode"))){
                    // Already on screen: convert the current page without sending MAIN again.
                    request.putExtra("morphEntry",true).putExtra("launched",true);
                    log.accept("OPEN_CONVERT task="+id+" package="+pkg);
                }
            }
            if(!request.getBooleanExtra("launched",false)){
                ActivityOptions options=ActivityOptions.makeBasic();a.call(options,"setAvoidMoveToFront");
                if(shortcut||request.hasExtra("openReply"))options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS);
                request.putExtra("launched",true);openPhase(request,"launch",id);trace.arm(pkg,openGeneration);
                if(shortcut){
                    // The system-issued shortcut PendingIntent preserves its real intent stack.
                    // Do not replace it with the launcher activity or force it into the old task.
                    if(!launchRoutingReady)throw new IllegalStateException("原始跳转任务识别尚未就绪");
                    String foregroundPackage=null;
                    if(request.getBooleanExtra("notificationLaunch",false))synchronized(lock){
                        Object focused=a.focused.invoke(root),top=focused==null?null:a.call(focused,"topRunningActivity");
                        if(top!=null)foregroundPackage=(String)a.field(top,"packageName");
                    }
                    String expected=request.getBooleanExtra("notificationLaunch",false)?null:pkg;
                    IBinder sender=ShortcutStarter.sender(shortcutLaunch);
                    AndroidWindowLaunch ticket=new AndroidWindowLaunch(new WindowNavigation.Launch(generation,pkg,
                        expected,shortcutUser,SystemClock.elapsedRealtime(),foregroundPackage),sender);
                    // Every PendingIntent jump (notification or suggestion) compares its bound target with the
                    // pre-send fullscreen foreground: landing in that exact task keeps it native.
                    ticket.foreground=nativeForeground();
                    pendingShortcutLaunch.set(ticket);
                    int result=ShortcutStarter.send(shortcutLaunch,context,request.getParcelableExtra("launchFillIn",Intent.class),(pi,intent,code,data,extras)->{
                        synchronized(ticket){
                            if(pendingShortcutLaunch.get()==ticket&&ticket.state.generation==openGeneration)ticket.state.dispatched(openGeneration);
                        }
                    },handler,options);
                    if(result<0||result>3)throw new IllegalStateException("快捷功能启动未获系统接受，result="+result);
                    request.putExtra("launchDispatched",true);
                }else{
                    Intent launch=context.getPackageManager().getLaunchIntentForPackage(pkg);
                    if(launch==null)throw new IllegalStateException("没有启动入口");
                    if(id>=0)a.call(options,"setLaunchTaskId",new Class<?>[]{int.class},id);
                    context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),options.toBundle());
                }
                handler.postDelayed(()->open(request,attempt+1),32);return;
            }
            if(shortcut){
                AndroidWindowLaunch ticket=pendingShortcutLaunch.get();
                if(ticket==null||ticket.state.generation!=generation)throw new IllegalStateException("快捷功能启动请求已失效");
                if(!ticket.state.dispatched){waitOpen(request,attempt,"wait-shortcut",-1);return;}
            }
            // A delegated launch may finish its intermediate task before opening the target.
            // Keep observing this exact send until the common bounded launch deadline.
            if(candidate[0]==null){waitOpen(request,attempt,"wait-task",-1);return;}
            AndroidWindowLaunch resolved=pendingShortcutLaunch.get();
            if(shortcut&&resolved!=null&&resolved.state.keepNativeForeground()){
                // The notification publisher may differ from the actual PendingIntent target.
                // Compare with the foreground before send, not with the task the send just raised.
                synchronized(lock){
                    a.call(candidate[0],"moveToFront",new Class<?>[]{String.class},"NativeComplements-notification-native");
                    a.call(root,"ensureActivitiesVisible");a.call(root,"resumeFocusedTasksTopActivities");
                }
                completeOpen(request,true);clearShortcutLaunch(generation);
                log.accept("NOTIFICATION_NATIVE_FOREGROUND package="+resolved.state.targetPackage+" task="+id);return;
            }
            synchronized(lock){if(a.entryBusy(candidate[0],false)){
                waitOpen(request,attempt,"wait-native",id);return;}
            }
            request.putExtra("taskId",id);
            Session retained=sessionFor(candidate[0]);
            if(retained!=null&&!retained.restoring){
                if(!presentExisting(retained,false)){
                    waitOpen(request,attempt,"wait-session",id);return;
                }
                completeOpen(request,true);clearShortcutLaunch(generation);report("INTENT_REUSED");return;
            }
            String actual=shortcut&&resolved!=null&&resolved.state.targetPackage!=null?resolved.state.targetPackage:pkg;
            clearShortcutLaunch(generation);
            openPhase(request,"start",id);command(new Intent(request).putExtra("package",actual).putExtra("op","START")
                .putExtra("taskId",id).putExtra("fadeEntry",true).putExtra("allowInheritedFreeform",shortcut));
        }catch(Throwable e){completeOpen(request,false);clearShortcutLaunch(generation);note("OPEN_FAILED："+pkg+"："+FrameworkAccess.reason(e));}
    }
    private void clearShortcutLaunch(long generation){
        AndroidWindowLaunch ticket=pendingShortcutLaunch.get();
        if(ticket!=null&&ticket.state.generation==generation)pendingShortcutLaunch.compareAndSet(ticket,null);
    }
    private boolean recoveryPending(){return recovering||anyRestoring()||unrecoveredJournal();}
    /** Retain this exact request through one bounded recovery; never redispatch its PendingIntent. */
    private boolean waitForRecovery(Intent request,Runnable resume)throws Exception{
        if(!request.hasExtra("generation"))request.putExtra("generation",openGeneration);
        if(request.getLongExtra("generation",-1)!=openGeneration){completeOpen(request,false);return true;}
        if(!recoveryPending())return false;
        if(screenBlocked()||uiLease==null||!uiLease.isBinderAlive())throw new IllegalStateException("打开请求已取消");
        long now=SystemClock.elapsedRealtime();
        if(!request.hasExtra("recoveryBegan")){
            request.putExtra("recoveryBegan",now);recoveryOpen=request;recoveryRetryState.resetWaits();
            log.accept("OPEN_AUTO_RECOVERY_BEGIN package="+request.getStringExtra("package"));
            recover("自动恢复后续接");report("OPEN_RECOVERING");
        }
        if(now-request.getLongExtra("recoveryBegan",now)>=4000)
            throw new IllegalStateException("等待旧窗口恢复超时");
        handler.postDelayed(resume,64);return true;
    }
    private void cancelRecoveryOpen(String token){
        Intent request=recoveryOpen;
        if(request==null||token!=null&&!token.equals(request.getStringExtra("token")))return;
        if(request.getLongExtra("generation",-1)==openGeneration)openGeneration++;
        clearShortcutLaunch(request.getLongExtra("generation",-1));cancelBackPreparation(request.getStringExtra("token"));
        completeOpen(request,false);
    }
    private void fillOpenWait(Intent result){
        Intent request=recoveryOpen;
        boolean waiting=request!=null&&request.getLongExtra("generation",-1)==openGeneration;
        result.putExtra("waitingOpen",waiting);
        if(waiting)result.putExtra("waitingPackage",request.getStringExtra("package")).putExtra("waitingToken",request.getStringExtra("token"));
    }
    Object shortcutLaunchTicket(Object originating,Object source){
        AndroidWindowLaunch ticket=pendingShortcutLaunch.get();
        if(ticket!=null&&ticket.state.generation!=openGeneration){pendingShortcutLaunch.compareAndSet(ticket,null);return null;}
        if(ticket==null)return null;
        try{
            IBinder sender=originating instanceof IBinder binder?binder:null;
            Object sourceTask=source==null?null:a.call(source,"getTask");
            return ticket.accepts(openGeneration,sender,sourceTask)?ticket:null;
        }catch(Exception error){log.accept("WINDOW_LAUNCH_MATCH_FAILED "+FrameworkAccess.reason(error));return null;}
    }
    /** The existing start-result hook calls this only after a successful native start.
     * Keep the last matching activity in a shortcut's real intent stack, including
     * singleTask/onNewIntent reuse; never guess from the package's recent tasks. */
    void shortcutActivityStarted(Object value,Object started){
        if(!(value instanceof AndroidWindowLaunch ticket)||started==null||lock==null)return;
        try{synchronized(lock){
            if(pendingShortcutLaunch.get()!=ticket||ticket.state.generation!=openGeneration)return;
            Object task=a.call(started,"getTask");
            if(task==null||(Integer)a.field(task,"mUserId")!=ticket.state.userId)return;
            ComponentName component=(ComponentName)a.field(task,"realActivity");
            if(component==null||!ticket.targetAllowed(component.getPackageName(),(Integer)a.field(task,"mUserId"))
                ||Wire.PKG.equals(component.getPackageName())||BackBridge.PACKAGE.equals(component.getPackageName())
                ||LauncherGuard.PACKAGE.equals(component.getPackageName())||a.integer(task,"getActivityType")!=1)return;
            synchronized(ticket){
                if(pendingShortcutLaunch.get()!=ticket||ticket.state.generation!=openGeneration)return;
                ticket.task=task;ticket.state.bind((Integer)a.field(task,"mTaskId"),component.getPackageName());
                ticket.state.nativeTarget=keepNativeForeground(ticket.foreground,task,ticket.state.targetPackage,ticket.state.userId,a.call(task,"getDisplayContent"));
            }
            log.accept("WINDOW_LAUNCH_BOUND generation="+ticket.state.generation+" source="+ticket.state.hint+" package="+ticket.state.targetPackage+" task="+ticket.state.taskId);
        }}catch(Exception e){log.accept("SHORTCUT_TASK_BIND_FAILED "+FrameworkAccess.reason(e));}
    }
    /** One bounded retry step of open(): record the phase, then poll again in 32ms. */
    private void waitOpen(Intent request,int attempt,String phase,int id){
        openPhase(request,phase,id);handler.postDelayed(()->open(request,attempt+1),32);
    }
    private void openPhase(Intent request,String phase,int id){
        if(phase.equals(request.getStringExtra("phase")))return;request.putExtra("phase",phase);
        log.accept("OPEN_PHASE package="+request.getStringExtra("package")+" phase="+phase+" task="+id);
    }

    void appResumed(IBinder token){
        if(lock==null)return;
        try{synchronized(lock){
            Object record=a.method(a.activity,"forTokenLocked",IBinder.class).invoke(null,token);
            if(record!=null){
                dev.local.sunwindowdemo.features.iconlayers.IconAssets.started((String)a.field(record,"packageName"));
                Object task=a.call(record,"getTask");Session s=sessionFor(task);
                if(cleanup!=null){
                    if(visible(s)&&!s.parked&&!s.restoring)cleanup.acquired(s.pkg,s.userId,s.uid);
                    else if(s==null&&task!=null&&a.bool(task,"isVisibleRequested")){
                        int uid=a.integer(record,"getUid");
                        cleanup.resumed((String)a.field(record,"packageName"),uid/100000,uid);
                    }
                }
            }
        }}catch(Exception error){log.accept("ICON_LAUNCH_OBSERVER_FAILED "+FrameworkAccess.reason(error));}
    }
    /** Resolve source-less app-context starts only for the real foreground caller. */
    private Object launchSource(Object request,Object source,int realCallingUid)throws Exception{
        if(source!=null)return source;
        Object focused=a.focused.invoke(root),caller=focused==null?null:a.call(focused,"topRunningActivity");
        return caller!=null&&WindowPolicy.sameWindowCaller((Integer)a.field(request,"launchedFromUid"),realCallingUid,
            a.integer(caller,"getUid"),(String)a.field(request,"launchedFromPackage"),(String)a.field(caller,"packageName"))?caller:null;
    }
    ShareLaunch prepareInstaller(Object request,Object source,ActivityOptions options,int realCallingUid)throws Exception{
        if(lock==null||context==null||request==null||!ShareWindow.installerIntent((Intent)a.field(request,"intent")))return null;
        synchronized(lock){
            source=launchSource(request,source,realCallingUid);
            Object task=source==null?null:a.call(source,"getTask");Session owner=sessionFor(task);
            if(owner==null||owner.restoring||owner.closing||recovering||screenBlocked()
                ||!a.bool(task,"isVisibleRequested"))return null;
            log.accept("INSTALLER_NATIVE_FULLSCREEN source="+owner.taskId);
            return new ShareLaunch(request,ShareWindow.installerOptions(options),null,task,true);
        }
    }
    ShareLaunch prepareShare(Object request,Object source,ActivityOptions options,int realCallingUid)throws Exception{
        if(lock==null||handler==null||context==null||request==null||!landscapeShareWindow
            ||!uiReady||uiLease==null||!uiLease.isBinderAlive()||SystemClock.elapsedRealtime()-uiSeen>3000)return null;
        synchronized(lock){
            if(recovering||pendingStart!=null||anyRestoring()
                ||screenBlocked())return null;
            source=launchSource(request,source,realCallingUid);if(source==null)return null;
            if((Boolean)a.call(request,"isResolverOrDelegateActivity")||a.integer(request,"getUid")<10000)return null;
            String pkg=(String)a.field(request,"packageName");
            if(!Wire.allowedPackage(pkg)||Wire.PKG.equals(pkg)||BackBridge.PACKAGE.equals(pkg)
                ||LauncherGuard.PACKAGE.equals(pkg)||!UserHandle.getUserHandleForUid(a.integer(request,"getUid")).equals(android.os.Process.myUserHandle()))return null;
            Intent intent=(Intent)a.field(request,"intent");boolean chooser=(Boolean)a.call(source,"isResolverOrDelegateActivity");
            String sourcePkg=(String)a.field(source,"packageName");
            if(!chooser&&(a.integer(source,"getUid")<10000||Wire.PKG.equals(sourcePkg)||BackBridge.PACKAGE.equals(sourcePkg)||LauncherGuard.PACKAGE.equals(sourcePkg)))return null;
            if(pkg.equals(a.call(context.getPackageManager(),"getPermissionControllerPackageName")))return null;
            if(!ShareWindow.eligibleIntent(intent,chooser)||(intent.getFlags()&Intent.FLAG_ACTIVITY_CLEAR_TASK)!=0||pkg.equals(a.field(source,"packageName")))return null;
            Object sourceTask=a.call(source,"getTask");
            if(sourceTask==null||sourceTask!=a.focused.invoke(root)||!a.bool(sourceTask,"isVisibleRequested")
                ||a.integer(sourceTask,"getWindowingMode")!=1)return null;
            Object display=a.call(sourceTask,"getDisplayContent");Rect bounds=(Rect)a.call(display,"getBounds");
            if((Integer)a.field(display,"mDisplayId")!=0||a.integer(sourceTask,"getActivityType")!=1)return null;
            if(options!=null){int mode=(Integer)a.call(options,"getLaunchWindowingMode");if(mode!=0&&mode!=1)return null;}
            NativeForeground before=launchForeground.get();
            if(before!=null&&keepNativeForeground(before,before.task,pkg,
                a.integer(request,"getUid")/100000,display))return null;
            boolean horizontal=initialLandscape(request,pkg,0);
            Geometry.Placement p=Geometry.place(Math.min(bounds.width(),bounds.height()),horizontal,0,0,bounds.width(),bounds.height(),0);
            ActivityOptions next=ShareWindow.launchOptions(options,new Rect(0,0,p.width(),p.height()));
            log.accept("SHARE_WINDOW_PREPARE package="+pkg+" landscape="+horizontal);
            return new ShareLaunch(request,next,uiLease,sourceTask,false);
        }
    }
    private Object startShare(Object request,Object started,ShareLaunch launch)throws Exception{
        Object task=a.call(started,"getTask");
        if(task==null||!launch.lease.isBinderAlive())return null;
        Session retained=sessionFor(task);
        if(retained!=null){
            // Native ActivityStarter has already delivered the real Intent/grants/result chain.
            // Re-present that exact task; never substitute the package's launcher page.
            if(retained.launchFlow==null||!retained.launchFlow.continuing(SystemClock.elapsedRealtime()))retained.launchFlow=new LaunchCoverFlow(SystemClock.elapsedRealtime());
            else retained.launchFlow.changed(SystemClock.elapsedRealtime());
            if("normal".equals(retained.frame)&&!retained.parked)beginCover(retained,retained.placement,retained.placement,false);
            return new ShareReused(retained);
        }
        String pkg=(String)a.field(request,"packageName");Session s;
        try{s=snapshot((Integer)a.field(task,"mTaskId"),pkg,Wire.token(),true);}
        catch(Exception error){
            // An unsupported target keeps its native launch rather than an unmanaged freeform task.
            if(a.integer(task,"getWindowingMode")==5&&!owns(task)){
                releaseToFullscreen(task);
            }
            throw error;
        }
        s.entryFade=true;
        s.launchFlow=new LaunchCoverFlow(SystemClock.elapsedRealtime());
        try{bindAndSave(s,launch.lease);}catch(Exception error){releaseToFullscreen(task);throw error;}
        Session previous=active;java.util.List<Session> previousPinned=pinned;Session displaced=normalOccupied()?previous:null;
        if(displaced==null){if(previous!=null)addPinned(previous);active=null;}
        try{
            ++openGeneration;trace.arm(pkg,openGeneration);
            maskAndActivate(s,task,null);
            log.accept("SHARE_WINDOW_ACQUIRED task="+s.taskId+" package="+pkg);
            return new ShareStarted(s,displaced,previous,previousPinned);
        }catch(Exception error){
            rollbackDefault(s,previous,previousPinned);retire(s);throw error;
        }
    }
    private void presentShared(Session target,int attempt){
        try{
            if(!sessions.contains(target)||target.restoring)return;
            if(presentExisting(target,false)){report("SHARE_WINDOW_REUSED");return;}
            if(attempt<100)handler.postDelayed(()->presentShared(target,attempt+1),32);
            else log.accept("SHARE_WINDOW_REUSE_PENDING task="+target.taskId);
        }catch(Exception error){log.accept("SHARE_WINDOW_REUSE_FAILED "+FrameworkAccess.reason(error));}
    }
    /** Shared by share and linked-task acquisition: hide the native launch, present the session, suppress the native animation. */
    private void maskAndActivate(Session s,Object task,Geometry.Placement placement)throws Exception{
        s.startMask=new TaskVisibilityGuard(a,task);s.startMask.hide();
        activate(s,placement);
        a.call(a.field(task,"mTransitionController"),"setNoAnimation",new Class<?>[]{a.wc},task);
    }
    /** True while any retained session is still being restored to its original task state. */
    private boolean anyRestoring(){return sessions.stream().anyMatch(s->s.restoring);}
    /** True while the keyguard is showing or the screen is not interactive. */
    private boolean screenBlocked(){
        return context.getSystemService(KeyguardManager.class).isKeyguardLocked()
            ||!context.getSystemService(PowerManager.class).isInteractive();
    }
    /** Shared entry precondition: opening or starting a window requires an unlocked keyguard. */
    private void requireUnlocked(String message){
        KeyguardManager keyguard=context.getSystemService(KeyguardManager.class);
        if(keyguard==null||keyguard.isKeyguardLocked())throw new IllegalStateException(message);
    }
    /** A recovery journal with no live session means a previous window still awaits restoration. */
    private boolean unrecoveredJournal(){return sessions.isEmpty()&&journal.getBaseFile().exists();}
    /** Links the control lease death recipient that recovers this session if the controller exits. */
    private void bindLease(Session s,IBinder lease)throws Exception{
        s.lease=lease;
        s.death=()->handler.post(()->{if(sessions.contains(s))recover("控制进程退出");});
        lease.linkToDeath(s.death,0);
    }
    /** Shared by task acquisition: bind the control lease and persist the original state before any task mutation. */
    private void bindAndSave(Session s,IBinder lease)throws Exception{
        bindLease(s,lease);
        try{save(s);}catch(Exception error){lease.unlinkToDeath(s.death,0);throw error;}
        if(cleanup!=null)cleanup.acquired(s.pkg,s.userId,s.uid);
    }

    /** Sample on the engine worker. The cleanup hook never acquires the WM lock. */
    private java.util.Set<TaskCleanupGuard.Key> cleanupVisible(java.util.Set<TaskCleanupGuard.Key> keys){
        java.util.Set<TaskCleanupGuard.Key> present=new java.util.HashSet<>();
        boolean screen=context.getSystemService(PowerManager.class).isInteractive()
            &&!context.getSystemService(KeyguardManager.class).isKeyguardLocked();
        synchronized(lock){
            for(Session s:sessions)if(visible(s)&&!s.parked&&!s.restoring&&!s.closing&&s.edgeHidden)
                present.add(new TaskCleanupGuard.Key(s.pkg,s.userId));
            if(screen)try{
                a.call(root,"forAllTasks",new Class<?>[]{Consumer.class},(Consumer<Object>)task->{
                    try{
                        if(!a.bool(task,"isLeafTask")||!a.bool(task,"isVisibleRequested"))return;
                        Session managed=sessionFor(task);
                        if(managed!=null&&(managed.parked||managed.screenSuspended
                            ||managed.restoring&&!managed.restoreForeground))return;
                        Object top=a.call(task,"topRunningActivity");
                        if(top!=null){
                            var key=new TaskCleanupGuard.Key((String)a.field(top,"packageName"),a.integer(top,"getUid")/100000);
                            if(keys.contains(key))present.add(key);
                        }
                    }catch(Exception error){throw new IllegalStateException(error);}
                });
            }catch(Exception error){throw new IllegalStateException("cleanup visibility unavailable",error);}
        }
        return present;
    }
    /** One native-fullscreen exit for a freeform task the module does not (or no longer) manage. */
    private void releaseToFullscreen(Object task)throws Exception{
        a.call(task,"setWindowingMode",new Class<?>[]{int.class},1);
        a.call(task,"setBounds",new Class<?>[]{Rect.class},new Rect());
        try{restoreFloatingCompat(task);}
        catch(Exception error){log.accept("FULLSCREEN_COMPAT_RELEASE_FAILED "+FrameworkAccess.reason(error));}
    }
    void shareFinished(ShareLaunch launch,Object starter){
        try{synchronized(lock){
            Object started=a.field(starter,"mLastStartActivityRecord");
            Object task=started==null?null:a.call(started,"getTask");
            if(task==null||owns(task)||a.integer(task,"getWindowingMode")!=5)return;
            ComponentName component=(ComponentName)a.field(task,"realActivity");
            if(component==null||!component.getPackageName().equals(a.field(launch.request,"packageName")))return;
            // A failed/unsupported acquisition must not leave a raw freeform task behind.
            releaseToFullscreen(task);
            log.accept("SHARE_WINDOW_NATIVE_FALLBACK task="+a.field(task,"mTaskId"));
        }}catch(Exception error){log.accept("SHARE_WINDOW_FALLBACK_FAILED "+FrameworkAccess.reason(error));}
    }
    private void rollbackDefault(Session failed,Session previous,java.util.List<Session> previousPinned)throws Exception{
        active=previous;pinned=previousPinned;
        if(previous==null)return;
        previous.parked=false;previous.opacity=1;previous.entering=false;previous.transitioning=false;
        if(failed.decoration!=null&&previous.decoration!=null)failed.decoration.transferDimTo(previous.decoration,previous.task);
        endCover(previous);a.call(previous.task,"setFocusable",new Class<?>[]{boolean.class},"normal".equals(previous.frame));
        renderFrame(previous);a.call(previous.task,"moveToFront",new Class<?>[]{String.class},"NativeComplements-rollback");
        a.call(root,"ensureActivitiesVisible");a.call(root,"resumeFocusedTasksTopActivities");
    }
    private void shareResult(ShareStarted ticket,boolean success){
        handler.post(()->{try{synchronized(lock){
            Session s=ticket.target;
            if(!sessions.contains(s)||s.restoring)return;
            if(!success){
                rollbackDefault(s,ticket.previous,ticket.previousPinned);retire(s);return;
            }
            if(s.startMask!=null)s.startMask.transfer();
            a.call(s.task,"moveToFront",new Class<?>[]{String.class},"NativeComplements-share");
            a.call(root,"ensureActivitiesVisible");a.call(root,"resumeFocusedTasksTopActivities");
            focusExpanded(s);renderFrame(s);
            settleNewDefault(ticket.displaced,s);
            history.windowed(new WindowHistory.Entry(s.taskId,s.userId,s.pkg,s.component,s.baseId));saveHistory();notifyRecents();
            handler.postDelayed(()->tick(s),250);report("SHARE_WINDOW_ACTIVE");
        }}catch(Throwable error){recover("分享小窗启动失败："+FrameworkAccess.reason(error));}});
    }
    /** Runs before handleStartResult requests Shell playback. Convert the related
     * task now; never restore the source to fullscreen to acquire its child. */
    /** Preserve a managed task at native task selection, before a proxy's fullscreen mode is inherited. */
    ActivityOptions ownedLaunchOptions(Object request,Object source,Object target,ActivityOptions options)throws Exception{
        if(request==null||target==null||recovering||lock==null)return null;
        synchronized(lock){
            Session owner=sessionFor(target);
            if(owner==null||owner!=active||owner.restoring||owner.closing||owner.fullTransition||owner.prioritySuspended||owner.screenSuspended
                ||owner.parked||!"normal".equals(owner.frame)||screenBlocked()||owner.placement==null
                ||!a.bool(target,"isAttached")||find(owner.taskId)!=target)return null;
            Intent intent=(Intent)a.field(request,"intent");
            if(!ShareWindow.eligibleIntent(intent,false)||(intent.getFlags()&Intent.FLAG_ACTIVITY_CLEAR_TASK)!=0)return null;
            if(!ownedLaunch(a.integer(request,"getUid"),owner.uid,
                (Integer)a.field(target,"mUserId"),owner.userId,
                a.call(request,"getDisplayContent"),a.call(target,"getDisplayContent")))return null;
            if(options!=null){int mode=(Integer)a.call(options,"getLaunchWindowingMode");if(mode!=0&&mode!=1&&mode!=5)return null;}
            // Ownership is the exact Task object, never a package/recent-task/time-window guess.
            // This does not admit the proxy or relax sameWindowCaller for unowned tasks.
            log.accept("OWNED_LAUNCH_KEEP task="+owner.taskId+" sourceOwned="+(source!=null&&sessionFor(a.call(source,"getTask"))!=null));
            return ShareWindow.launchOptions(options,new Rect(0,0,owner.placement.width(),owner.placement.height()));
        }
    }
    private static boolean ownedLaunch(int targetUid,int ownerUid,int targetUser,int ownerUser,Object requestDisplay,Object targetDisplay){
        return WindowPolicy.ownedLaunch(targetUid,ownerUid,targetUser,ownerUser,targetDisplay!=null,requestDisplay==null||requestDisplay==targetDisplay);
    }
    private void preserveOwnedTask(Session s)throws Exception{
        if(a.integer(s.task,"getWindowingMode")==5)return;
        a.call(atm,"deferWindowLayout");
        try{
            a.call(s.task,"setWindowingMode",new Class<?>[]{int.class},5);
            a.call(s.task,"setAlwaysOnTop",new Class<?>[]{boolean.class},true);
            a.call(s.task,"setBounds",new Class<?>[]{Rect.class},new Rect(0,0,s.placement.width(),s.placement.height()));
            a.call(wms,"requestTraversal");
            log.accept("OWNED_LAUNCH_RESTORED task="+s.taskId);
        }finally{a.call(atm,"continueWindowLayout");}
    }
    Object activityStarting(Object source,Object requestActivity,Object started,int flags,int realCallingUid){
        if(requestActivity==null||started==null||handler==null||lock==null||(flags&Intent.FLAG_ACTIVITY_CLEAR_TASK)!=0)return null;
        Session created=null;
        try{synchronized(lock){
            ShareLaunch share=shareLaunch.get();
            if(share!=null&&share.request==requestActivity)return share.nativeFullscreen?null:startShare(requestActivity,started,share);
            Session s=active;
            if(s==null||recovering||s.restoring||s.closing||s.fullTransition||s.prioritySuspended||s.screenSuspended
                ||context.getSystemService(KeyguardManager.class).isKeyguardLocked()
                ||pendingStart!=null||!"normal".equals(s.frame))return null;
            // Native system interactions keep their own dialog/picker presentation,
            // including starts originating inside an existing floating application.
            if(!ShareWindow.eligibleIntent((Intent)a.field(requestActivity,"intent"),false))return null;
            Object sourceTask=source==null?null:a.call(source,"getTask"),target=a.call(started,"getTask");
            if(target==null)return null;
            if(target==s.task){
                if(sourceTask==s.task&&s.launchFlow!=null&&s.launchFlow.continuing(SystemClock.elapsedRealtime()))s.launchFlow.changed(SystemClock.elapsedRealtime());
                if(ownedLaunch(a.integer(requestActivity,"getUid"),s.uid,
                    (Integer)a.field(target,"mUserId"),s.userId,a.call(requestActivity,"getDisplayContent"),a.call(target,"getDisplayContent"))){
                    preserveOwnedTask(s);return new OwnedStart(s);
                }
                return null;
            }
            if(source!=null){if(sourceTask!=s.task)return null;}
            else{
                Object caller=a.call(s.task,"topRunningActivity");
                if(caller==null||!a.bool(s.task,"isVisibleRequested"))return null;
                if(!WindowPolicy.sameWindowCaller((Integer)a.field(requestActivity,"launchedFromUid"),realCallingUid,
                    a.integer(caller,"getUid"),(String)a.field(requestActivity,"launchedFromPackage"),(String)a.field(caller,"packageName")))return null;
            }
            if((Integer)a.field(target,"mUserId")!=s.userId||a.call(target,"getDisplayContent")!=a.call(s.task,"getDisplayContent"))return null;
            // Only ordinary application tasks become linked windows; Home, Recents, Assistant and Dream stay native.
            if(a.integer(target,"getActivityType")!=1)return null;
            ComponentName component=(ComponentName)a.field(target,"realActivity");
            if(component==null||!Wire.allowedPackage(component.getPackageName())||Wire.PKG.equals(component.getPackageName()))return null;
            if(keepNativeForeground(launchForeground.get(),target,(String)a.field(requestActivity,"packageName"),
                (Integer)a.field(target,"mUserId"),a.call(target,"getDisplayContent")))return null;
            Session next=sessionFor(target);
            boolean fresh=next==null;
            if(fresh){
                if(sessions.size()>=MAX_LINKED_TASKS)throw new IllegalStateException("关联小窗数量已达上限");
                next=snapshot((Integer)a.field(target,"mTaskId"),component.getPackageName(),s.token,true);
                next.returnTo=s;
                bindAndSave(next,s.lease);
                created=next;
            }else if(next.restoring)return null;
            next.launchFlow=s.launchFlow!=null&&s.launchFlow.continuing(SystemClock.elapsedRealtime())?s.launchFlow:new LaunchCoverFlow(SystemClock.elapsedRealtime());
            next.launchFlow.changed(SystemClock.elapsedRealtime());
            SwitchStart ticket=new SwitchStart(s,next,fresh,openGeneration);
            pendingStart=ticket;
            maskAndActivate(next,target,s.placement);
            handler.postDelayed(()->{
                if(pendingStart==ticket){cancelStart("start-timeout");recover("关联任务启动超时");}
            },10000);
            log.accept("LINKED_START from="+s.taskId+" to="+next.taskId+" retained="+sessions.size()+" created="+fresh);
            return ticket;
        }}catch(Exception e){
            SwitchStart failed=pendingStart;cancelStart("start-failed");
            if(created!=null||failed!=null)handler.post(()->recover("关联任务接管失败"));
            log.accept("LINKED_START_FAILED "+FrameworkAccess.reason(e));return null;
        }
    }
    void activityStartResult(Object value,boolean success){
        if(value instanceof OwnedStart owned){
            Session s=owned.target;
            if(success)handler.post(()->{try{synchronized(lock){
                if(s==active&&!s.restoring&&!s.closing&&!s.fullTransition&&!s.parked&&!s.screenSuspended&&!s.prioritySuspended
                    &&!screenBlocked()&&s.placement!=null&&a.bool(s.task,"isAttached")&&find(s.taskId)==s.task){
                    preserveOwnedTask(s);refresh(s);frameReport("OWNED_LAUNCH");
                }
            }}catch(Exception error){failure(s,error);}});
            return;
        }
        if(value instanceof ShareReused reused){
            if(success)handler.post(()->presentShared(reused.target,0));
            return;
        }
        if(value instanceof ShareStarted shared){shareResult(shared,success);return;}
        if(!(value instanceof SwitchStart ticket))return;
        handler.post(()->{
            try{synchronized(lock){
                if(pendingStart!=ticket)return;
                pendingStart=null;
                Session next=ticket.target;
                if(!success||ticket.generation!=openGeneration||!next.lease.isBinderAlive()){
                    activate(ticket.from,next.placement);
                    if(ticket.created)retire(next);
                    return;
                }
                if(next.startMask!=null)next.startMask.transfer();
                a.call(next.task,"moveToFront",new Class<?>[]{String.class},"NativeComplements-linked");
                a.call(root,"ensureActivitiesVisible");a.call(root,"resumeFocusedTasksTopActivities");
                focusExpanded(next);renderFrame(next);
                if(ticket.created){
                    history.windowed(new WindowHistory.Entry(next.taskId,next.userId,next.pkg,next.component,next.baseId));
                    saveHistory();notifyRecents();handler.postDelayed(()->tick(next),250);
                }
                report("LINKED_ACTIVE");
            }}catch(Throwable e){recover("关联小窗切换失败："+FrameworkAccess.reason(e));}
        });
    }
    private void cancelStart(String reason){
        SwitchStart ticket=pendingStart;pendingStart=null;
        if(ticket==null)return;
        try{synchronized(lock){
            if(ticket.target.startMask!=null)ticket.target.startMask.transfer();
            if(active==ticket.target)renderFrame(ticket.target);
        }}catch(Exception e){log.accept("LINKED_CANCEL_FAILED "+FrameworkAccess.reason(e));}
        log.accept("LINKED_CANCEL "+reason);
    }
    private Session sessionFor(Object task){
        if(task==null)return null;
        for(Session s:sessions)if(s.task==task)return s;
        return null;
    }
    private boolean live(Session s)throws Exception{
        return s!=null&&!s.restoring&&find(s.taskId)==s.task&&a.call(s.task,"topRunningActivity")!=null;
    }
    private Session returnTarget(Session from)throws Exception{
        if(from.returnTo==null)return null;
        java.util.Set<Session> seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for(Session s=from.returnTo;s!=null&&seen.add(s);s=s.returnTo)if(live(s))return s;
        for(Session s:sessions)if(s!=from&&s.token.equals(from.token)&&live(s))return s;
        return null;
    }
    /** Every package open and original-intent reuse goes through the same presentation path. */
    private boolean presentExisting(Session next,boolean step)throws Exception{
        synchronized(lock){
            if(!live(next)||next.closing||next.fullTransition||next.returning||next.entering||next.transitioning||pendingStart!=null)return false;
            if(cleanup!=null)cleanup.acquired(next.pkg,next.userId,next.uid);
            if(updateOrientation(next)&&visible(next))refresh(next);
            String target=WindowNavigation.wakeForm(next.frame,next.edgeHidden,step);
            if(visible(next)){
                if("restore-mini".equals(target)){changeForm(next,target);return true;}
                if("mini".equals(next.frame))changeForm(next,"normal");
            }else{
                Session previous=active;
                Session displaced=normalOccupied()&&previous!=next&&!previous.token.equals(next.token)?previous:null;
                // 同一任务组里另一个窗口被叫到前台时，它自己的迷你窗要让位，
                // 但不能顺手清掉其它应用的迷你窗。
                for(Session other:new java.util.ArrayList<>(pinned)){
                    if(other==next||!other.token.equals(next.token))continue;
                    other.parked=true;other.animationRev++;other.opacity=0;
                    a.call(other.task,"setFocusable",new Class<?>[]{boolean.class},false);
                    if(other.decoration!=null)other.decoration.hide(other.task);
                    renderFrame(other);removePinned(other);
                }
                removePinned(next);
                if(previous!=null&&previous!=next&&"mini".equals(previous.frame)&&!previous.token.equals(next.token)){
                    active=null;retainPinned(previous,"超出小窗上限");
                }
                java.util.List<Session> previousPinned=pinned;
                try{activate(next,next.placement);settleNewDefault(displaced,next);}
                catch(Exception error){rollbackDefault(next,previous,previousPinned);next.parked=true;next.opacity=0;renderFrame(next);throw error;}
            }
            a.call(next.task,"setAlwaysOnTop",new Class<?>[]{boolean.class},true);
            a.call(next.task,"moveToFront",new Class<?>[]{String.class},"NativeComplements-present");
            a.call(root,"ensureActivitiesVisible");a.call(root,"resumeFocusedTasksTopActivities");
            if(!next.transitioning&&!next.entering&&!entryInputPending(next))focusExpanded(next);
            renderFrame(next);rememberOrientation(next);
            log.accept("WINDOW_PRESENT task="+next.taskId+" frame="+next.frame+" landscape="+next.landscape);
            return true;
        }
    }
    private void activate(Session next,Geometry.Placement placement)throws Exception{
        if(recovering||next.restoring||!sessions.contains(next))throw new IllegalStateException("关联任务已退出");
        if(screenBlocked())throw new IllegalStateException("解锁后再切换小窗");
        if(cleanup!=null)cleanup.acquired(next.pkg,next.userId,next.uid);
        next.screenSuspended=false;
        Session previous=active;
        boolean continuousDim=previous!=null&&previous!=next&&"normal".equals(previous.frame)&&"normal".equals(next.frame)
            &&previous.decoration!=null&&!previous.screenSuspended;
        if(next.decoration==null)next.decoration=new TaskDecoration(a);
        if(continuousDim)previous.decoration.transferDimTo(next.decoration,next.task);
        else next.decoration.enableDim();
        boolean continueCover=previous!=null&&previous!=next&&next.launchFlow!=null&&next.launchFlow.active()
            &&previous.launchFlow==next.launchFlow&&previous.cover!=null&&previous.cover.canRetarget();
        long entryDelay=previous!=null&&previous!=next&&!previous.parked&&!continueCover?exitCover(previous):0;
        endCover(next);
        if(previous!=null&&previous!=next){
            rememberOrientation(previous);
            previous.animationRev++;previous.transitioning=false;previous.entering=false;
            previous.fullTransition=false;previous.parked=true;previous.opacity=0;
            a.call(previous.task,"setFocusable",new Class<?>[]{boolean.class},false);
            if(previous.decoration!=null)previous.decoration.hide(previous.task);
            a.opacity(previous.task,0);
        }
        next.parked=false;next.closing=false;next.returning=false;next.backRequested=false;next.backComplete=false;
        next.frame="normal";next.edgeHidden=false;next.edgeBarDragging=false;
        next.animationRev++;next.entering=true;next.entryStarted=false;next.entryFade=true;
        next.transitioning=true;next.fullTransition=false;next.presented=false;next.opacity=0;next.barAlpha=0;
        active=next;
        a.call(atm,"deferWindowLayout");
        try{
            prepareGeometry(next);
            if(next.decoration==null)next.decoration=new TaskDecoration(a);
            if(continueCover){
                TaskCover cover=previous.cover;
                try{
                    float density=a.config(next.task,false).densityDpi/160f;
                    cover.handoff(next.task,next.parent,visualBounds(next.placement),density*MotionStyle.radius(next.placement.scale(),next.defaultScale));
                    previous.cover=null;coverTasks.remove(previous.task);
                    next.cover=cover;next.coverDrawn=false;next.coverFullscreen=false;
                    prepareCover(next,cover);pollCover(next,cover);
                    log.accept("COVER_CHAIN_HANDOFF from="+previous.taskId+" to="+next.taskId);
                }catch(Exception error){
                    endCover(previous);beginCover(next,next.placement,next.placement,false);
                    log.accept("COVER_CHAIN_REPLACED "+FrameworkAccess.reason(error));
                }
            }else beginCover(next,next.placement,next.placement,false);
            // A retained parent can already have a valid buffer and need no relayout.
            Object top=a.call(next.task,"topRunningActivity");
            Object main=top==null?null:a.call(top,"findMainWindow",new Class<?>[]{boolean.class},false);
            if(main!=null&&a.bool(main,"isDrawn")&&coverConfigurationMatches(next,main))next.coverDrawn=true;
            if(next.cover!=null){next.cover.delayEntry(entryDelay);if(continuousDim)next.cover.continuousDim();}
            frameReport("LINKED_TOUCH_GUARD");
            a.opacity(next.task,0);
            a.call(next.task,"setFocusable",new Class<?>[]{boolean.class},true);
            a.call(next.task,"setForceTranslucent",new Class<?>[]{boolean.class},true);
            a.call(next.task,"setWindowingMode",new Class<?>[]{int.class},5);
            a.call(next.task,"setAlwaysOnTop",new Class<?>[]{boolean.class},true);
            a.call(next.task,"setBounds",new Class<?>[]{Rect.class},new Rect(0,0,next.placement.width(),next.placement.height()));
            if(a.integer(next.task,"getWindowingMode")!=5)throw new IllegalStateException("关联任务未进入 freeform");
            a.call(wms,"requestTraversal");
        }finally{a.call(atm,"continueWindowLayout");}
    }
    private boolean returnFromTask(Session s){return returnFromTask(s,false);}
    private boolean returnFromTask(Session s,boolean beforePlayback){
        if(active==s&&s.returning)return true;
        if(active!=s||recovering||s.restoring||s.closing||pendingStart!=null)return false;
        try{synchronized(lock){
            Session target=returnTarget(s);if(target==null)return false;
            Geometry.Placement placement=s.placement;
            Runnable select=new Runnable(){public void run(){
                try{synchronized(lock){
                    if(active!=s||recovering)return;
                    if(screenBlocked()){
                        handler.postDelayed(this,250);return;
                    }
                    if(!live(target))throw new IllegalStateException("主应用任务已结束");
                    boolean ended=!live(s);
                    s.parked=true;s.returning=false;s.closing=false;s.fullTransition=false;
                    // A live mini-program remains parked in freeform. Its own close
                    // button/activity finish, or closing the whole group, owns cleanup.
                    activate(target,placement);
                    a.call(target.task,"moveToFront",new Class<?>[]{String.class},"NativeComplements-return");
                    a.call(root,"ensureActivitiesVisible");a.call(root,"resumeFocusedTasksTopActivities");
                    focusExpanded(target);renderFrame(target);
                    if(ended)retire(s);
                    log.accept("LINKED_RETURN from="+s.taskId+" to="+target.taskId+" retained="+!ended);
                    report("LINKED_RETURN");
                }}catch(Throwable e){recover("关联小窗返回失败："+FrameworkAccess.reason(e));}
            }};
            exitCover(s);
            s.returning=true;s.closing=true;s.fullTransition=true;s.entering=false;
            if(beforePlayback||!live(s)){
                s.parked=true;s.opacity=0;if(s.decoration!=null)s.decoration.hideHandle(s.task);
                a.opacity(s.task,0);
            }
            frameReport("LINKED_RETURN_COVER");select.run();
            return true;
        }}catch(Exception e){log.accept("LINKED_RETURN_FAILED "+FrameworkAccess.reason(e));return false;}
    }
    private void retire(Session s){
        if(s==active||s.restoring)return;
        s.restoring=true;s.parked=true;s.animationRev++;
        restoreRetired(s,0);
    }
    private void restoreRetired(Session s,int attempt){
        if(recovering||!sessions.contains(s))return;
        try{synchronized(lock){
            if(restoreTask(s,false)){forget(s);report("LINKED_REMOVED");return;}
        }
            if(attempt>=100){recover("关联任务恢复超时");return;}
            handler.postDelayed(()->restoreRetired(s,attempt+1),100);
        }catch(Throwable e){recover("关联任务恢复失败："+FrameworkAccess.reason(e));}
    }

    private float coverRadius(Session s,Geometry.Placement p)throws Exception{
        return a.config(s.task,false).densityDpi/160f*MotionStyle.radius(p.scale(),s.defaultScale);
    }
    /** The white handle owns the preview; native content stays masked until the final geometry is drawn. */
    private boolean entryGesture(Session s,Intent i,String op)throws Exception{
        if(s.cover==null||!s.cover.canRetarget()||s.placement==null)return false;
        if(!WindowControl.entryRequest(op))return false;
        synchronized(lock){
            boolean starting=!s.coverFollowing;
            s.entryControl=null;s.entering=false;s.entryStarted=true;s.opacity=1;s.barAlpha=1;
            s.animationRev++;s.transitioning=false;s.resizing="RESIZE".equals(op);s.coverFollowing=true;
            Geometry.Placement p=s.placement;
            if("RESIZE".equals(op)||"END_RESIZE".equals(op)){
                float scale=i.getFloatExtra("scale",p.scale());
                s.placement=WindowControl.resize(p,scale,s.defaultScale,s.landscape,s.displayW>s.displayH);
            }
            s.cover.gesturePose(visualBounds(s.placement),coverRadius(s,s.placement));
            renderFrame(s);
            if(starting){
                if(s.launchFlow!=null)s.launchFlow.finish();
                log.accept("ENTRY_GESTURE_BEGIN task="+s.taskId);
            }
            if("RESIZE".equals(op)){frameReport("ENTRY_GESTURE");return true;}
            String target=WindowControl.form(op);
            if(target==null)target=GestureMath.release(s.placement.scale(),s.defaultScale,s.landscape,s.displayW>s.displayH);
            s.resizing=false;
            if("full".equals(target)||"closed".equals(target)){
                s.coverFollowing=false;s.cover.finishGesture(visualBounds(s.placement),coverRadius(s,s.placement));
                openGeneration++;cancelStart(target);trace.stop();
            }
            log.accept("ENTRY_GESTURE_COMMIT task="+s.taskId+" target="+target);
            changeForm(s,target);return true;
        }
    }
    private void control(Intent i) throws Exception {
        Session s=controlled(i);
        if(s==null||s.restoring)throw new IllegalStateException("小窗已结束");
        synchronized(lock){updatePriorityState(s);if(s.prioritySuspended)return;}
        String op=i.getStringExtra("op");
        if(entryInputPending(s)){
            if(entryGesture(s,i,op))return;
            if(WindowControl.entryRequest(op)){
                // A cover already revealing cannot be revived; retain only the final request.
                s.entryControl=new Intent(i);return;
            }
        }
        if(WindowControl.cancelsLaunch(op)){
            openGeneration++;cancelStart(op);trace.stop();
        }
        WindowControl.Route route=WindowControl.route(op,controlFacts(s));
        if(route==WindowControl.Route.IGNORE)return;
        if(route==WindowControl.Route.MUTE){
            if(audio==null||!audio.setMuted(s.uid,!audio.isMuted(s.uid)))note("MUTE_FAILED：应用静音暂不可用");
            else report("MUTE");
            return;
        }
        if(route==WindowControl.Route.RESTORE_MINI){restoreMini(s);return;}
        if(route==WindowControl.Route.MINI_SIZE){resizeMini(s);return;}
        if(route==WindowControl.Route.EDGE){edgeControl(s,i,op);return;}
        if(route==WindowControl.Route.MINI_DRAG){
            synchronized(lock){
                if("MINI_DRAG_CANCEL".equals(op))endMiniDrag(s,true);
                else{
                    Geometry.Placement p=s.placement;if(p==null)return;
                    if(s.miniDragBase==null){s.miniDragBase=p;s.miniDragOpacity=s.opacity;}
                    Geometry.Placement base=s.miniDragBase;
                    float progress=i.getFloatExtra("scale",0),cx=i.getFloatExtra("x",p.x()+p.width()*p.scale()/2),cy=i.getFloatExtra("y",p.y()+p.height()*p.scale()/2);
                    WindowControl.Drag drag=WindowControl.drag(base,s.miniDragOpacity,progress,cx,cy);if(drag==null)return;
                    s.placement=drag.placement();s.opacity=drag.opacity();
                }
                renderFrame(s);
            }
            frameReport(op);return;
        }
        s.resizing="RESIZE".equals(op);
        s.animationRev++;s.transitioning=false;s.radiusFactor=1;
        if("CLOSE".equals(op))s.miniDragBase=null;
        if("DOCK".equals(op))endMiniDrag(s,false);
        String form=WindowControl.form(op);if(form!=null){changeForm(s,form);return;}
        Geometry.Placement p=s.placement;
        if(p==null)return;
        synchronized(lock){
            if("RESIZE".equals(op)||"END_RESIZE".equals(op)) {
                s.barAlpha=1;
                float scale=i.getFloatExtra("scale",p.scale());
                s.placement=WindowControl.resize(p,scale,s.defaultScale,s.landscape,s.displayW>s.displayH);
                if("END_RESIZE".equals(op)){
                    String target=GestureMath.release(s.placement.scale(),s.defaultScale,s.landscape,s.displayW>s.displayH);
                    changeForm(s,target);
                    return;
                }
            } else if("RELEASE".equals(op)) {
                String target=GestureMath.release(p.scale(),s.defaultScale,s.landscape,s.displayW>s.displayH);
                changeForm(s,target);return;
            } else if("MOVE".equals(op)||"DOCK".equals(op)) {
                if(!"mini".equals(s.frame)||s.edgeHidden)return;
                float x=i.getFloatExtra("x",p.x()),y=i.getFloatExtra("y",p.y());
                if(!Float.isFinite(x)||!Float.isFinite(y))throw new IllegalArgumentException("position");
                Rect safe=s.miniBounds==null?safeArea(a.call(s.task,"getDisplayContent"),false):new Rect(s.miniBounds);
                float margin=s.placementMargin;
                WindowControl.Dock moved=WindowControl.move(p,x,y,controlSafe(safe,margin),"DOCK".equals(op),s.displayW);
                if(moved.hiddenSide()!=0){hideMini(s,p,moved.hiddenSide(),y,safe,margin);return;}
                Geometry.Placement destination=moved.placement();
                if("DOCK".equals(op)){s.miniPlacement=destination;animate(s,p,destination,false,0,0);return;}
                s.placement=destination;
            }
            renderFrame(s);
        }
        if("RESIZE".equals(op)||"MOVE".equals(op))frameReport(op);else report(op);
    }

    private void endMiniDrag(Session s,boolean cancel){
        Geometry.Placement base=s.miniDragBase,current=s.placement;if(base==null||current==null)return;
        s.placement=WindowControl.endDrag(base,current,cancel);
        s.opacity=s.miniDragOpacity;s.miniDragBase=null;
    }

    private static WindowControl.Facts controlFacts(Session s){
        return new WindowControl.Facts(s.frame,s.closing,s.fullTransition,s.transitioning,s.entering,s.edgeHidden);
    }
    private static WindowControl.Safe controlSafe(Rect safe,float margin){return new WindowControl.Safe(safe.left,safe.top,safe.right,safe.bottom,margin);}
    private void edgeControl(Session s,Intent i,String op)throws Exception{
        if(WindowControl.route(op,controlFacts(s))!=WindowControl.Route.EDGE)return;
        synchronized(lock){
            Geometry.Placement p=s.placement;if(p==null||s.miniPlacement==null)return;
            if("EDGE_DRAG_CANCEL".equals(op)){
                WindowControl.Edge current=new WindowControl.Edge(s.placement,s.miniPlacement,s.edgeSide,s.edgeHidden,s.edgeBarDragging,s.edgeBarCenterX,s.edgeBarCenterY);
                WindowControl.Edge base=s.edgeDragBase==null?null:new WindowControl.Edge(s.edgeDragBase,s.edgeDragMini,s.edgeDragSide,s.edgeHidden,false,0,0);
                WindowControl.Edge cancelled=WindowControl.cancelEdge(current,base);
                s.placement=cancelled.placement();s.miniPlacement=cancelled.mini();s.edgeSide=cancelled.side();
                s.edgeDragBase=null;s.edgeBarDragging=cancelled.barDragging();renderFrame(s);frameReport(op);return;
            }
            if(s.edgeDragBase==null){s.edgeDragBase=p;s.edgeDragMini=s.miniPlacement;s.edgeDragSide=s.edgeSide;}
            float x=i.getFloatExtra("x",p.x()),y=i.getFloatExtra("y",p.y());
            if(!Float.isFinite(x)||!Float.isFinite(y))return;
            Rect safe=s.miniBounds==null?safeArea(a.call(s.task,"getDisplayContent"),false):new Rect(s.miniBounds);
            float margin=s.placementMargin;
            if("EDGE_BAR_DRAG".equals(op)){
                s.edgeBarDragging=true;s.edgeBarCenterX=x;s.edgeBarCenterY=y;
            }else if("EDGE_BAR_DOCK".equals(op)){
                WindowControl.Edge docked=WindowControl.edgeBarDock(s.miniPlacement,x,y,controlSafe(safe,margin),s.displayW);
                s.miniPlacement=docked.mini();s.edgeSide=docked.side();s.placement=docked.placement();
                s.edgeDragBase=null;s.edgeBarDragging=docked.barDragging();
            }else{
                if("EDGE_DOCK".equals(op)){s.edgeDragBase=null;s.edgeBarDragging=false;s.animationRev++;}
                WindowControl.Dock moved=WindowControl.edgeMove(p,x,y,controlSafe(safe,margin),"EDGE_DOCK".equals(op),s.displayW);
                Geometry.Placement destination=moved.placement();
                if("EDGE_DOCK".equals(op)){
                    if(moved.hiddenSide()!=0){hideMini(s,p,moved.hiddenSide(),destination.y(),safe,margin);return;}
                    s.edgeHidden=false;s.miniPlacement=destination;animate(s,p,destination,false,0,0);return;
                }
                s.placement=destination;
            }
            renderFrame(s);frameReport(op);
        }
    }

    record BackRoot(Object activity,Object window,Object display,int uid,int taskId) {}
    boolean backCaller(int uid){return uid==1000||uid>=0&&uid==uiUid;}
    BackRoot linkedBackRoot(){
        Session s=active;
        if(s==null||lock==null||handler==null)return null;
        try{synchronized(lock){
            if(active!=s||recovering||s.restoring||s.closing||s.transitioning||s.fullTransition||s.parked
                ||s.prioritySuspended||s.screenSuspended||pendingStart!=null||!"normal".equals(s.frame)||!s.lease.isBinderAlive()
                ||context.getSystemService(KeyguardManager.class).isKeyguardLocked()
                ||!context.getSystemService(PowerManager.class).isInteractive()||s.returnTo!=null&&returnTarget(s)==null)return null;
            Object top=a.call(s.task,"topRunningActivity"),first=a.call(s.task,"getRootActivity");
            if(top==null||top!=first||(Boolean)a.field(top,"finishing"))return null;
            Object display=a.call(s.task,"getDisplayContent");
            Object focus=display==null?null:a.field(display,"mCurrentFocus");
            if(focus==null||a.call(focus,"getTask")!=s.task||a.field(focus,"mActivityRecord")!=top
                ||a.call(top,"findMainWindow",new Class<?>[]{boolean.class},false)!=focus)return null;
            Object ime=a.call(display,"getImeWindow");if(ime!=null&&a.bool(ime,"isVisible"))return null;
            return new BackRoot(top,focus,display,a.integer(top,"getUid"),s.taskId);
        }}catch(Exception e){log.accept("LINKED_BACK_ROOT_FAILED "+FrameworkAccess.reason(e));return null;}
    }
    boolean sameBackRoot(BackRoot expected){
        BackRoot current=linkedBackRoot();
        return current!=null&&current.activity()==expected.activity()&&current.taskId()==expected.taskId();
    }
    boolean isBackRootToken(BackRoot expected,IBinder token){
        if(lock==null||token==null)return false;
        try{synchronized(lock){return sameBackRoot(expected)
            &&a.method(a.activity,"forTokenLocked",IBinder.class).invoke(null,token)==expected.activity();}}
        catch(Exception e){return false;}
    }
    boolean returnLinkedBack(BackRoot expected,IBinder token,String source){
        if(lock==null)return false;
        try{synchronized(lock){
            if(!sameBackRoot(expected)||token!=null&&!isBackRootToken(expected,token))return false;
            Session s=active;
            if(s.returnTo!=null){if(!returnFromTask(s))return false;}
            else close(s);
            log.accept("WINDOW_BACK_INTERCEPTED task="+s.taskId+" source="+source+" linked="+(s.returnTo!=null)+" retained=true");return true;
        }}catch(Exception e){log.accept("LINKED_BACK_INTERCEPT_FAILED "+FrameworkAccess.reason(e));return false;}
    }

    boolean suppressLinkedBackPreview(){
        // Only a focused root Back exits our presentation. Internal pages, dialogs
        // and the IME keep their native callbacks and predictive animations.
        BackRoot root=linkedBackRoot();
        if(root!=null)log.accept("WINDOW_BACK_PREVIEW_SUPPRESSED task="+root.taskId());
        return root!=null;
    }
    boolean backToClose(Object task){
        Session s=active;if(s==null||s.task!=task||s.restoring||s.closing)return false;
        boolean back=false;
        for(StackTraceElement frame:Thread.currentThread().getStackTrace()){
            String n=frame.getMethodName();
            if(n.contains("onBackPressed")||n.contains("moveActivityTaskToBack")){back=true;break;}
        }
        if(!back)return false;
        log.accept("CLOSE_REQUEST source=application-back task="+s.taskId);
        try{a.call(a.field(task,"mTransitionController"),"setNoAnimation",new Class<?>[]{a.wc},task);}
        catch(Exception e){log.accept("BACK_CLOSE_NO_ANIMATION_FAILED "+FrameworkAccess.reason(e));}
        handler.post(()->{if(active==s&&!s.restoring&&!returnFromTask(s))close(s);});return true;
    }
    private void close(Session s){
        if(!visible(s)||s.restoring||s.closing)return;
        log.accept("CLOSE_REQUEST source=control-or-switch task="+s.taskId);
        rememberOrientation(s);
        s.endOnClose=endOnClose;
        s.closing=true;s.fullTransition=true;s.entering=false;
        handler.post(WindowEngine.this::updateContinuation);
        visibility(s,false);
    }
    /** All handle/gesture/button form requests share these endpoints and their slot rules. */
    private void changeForm(Session s,String target)throws Exception{
        switch(target){
            case "closed" -> close(s);
            case "full" -> fullscreen(s);
            case "restore-mini" -> restoreMini(s);
            case "normal","mini" -> state(s,target);
            default -> throw new IllegalArgumentException("unknown window form");
        }
    }
    private void visibility(Session s,boolean show){visibility(s,show,null);}
    private void visibility(Session s,boolean show,Runnable afterHide){
        int revision=++s.animationRev;s.transitioning=true;
        Geometry.Placement target=s.placement;
        float startAlpha=show?1:s.opacity,startBar=show?1:s.barAlpha;
        float durationScale=android.provider.Settings.Global.getFloat(context.getContentResolver(),
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,1f);
        final long duration=Math.max(0,Math.round(240_000_000L*durationScale));
        report(show?"APPEARING":"CLOSING");
        frames.postFrameCallback(new Choreographer.FrameCallback(){
            long began=-1,lastFrame=-1,maxGap;int callbacks;
            public void doFrame(long now){
                if(!visible(s)||s.restoring||revision!=s.animationRev)return;
                try{
                    synchronized(lock){
                        if(s.prioritySuspended||s.screenSuspended||s.rotation.active()||a.presentationLeashed(s.task)){
                            if(began>=0&&lastFrame>=0)began+=now-lastFrame;
                            lastFrame=now;frames.postFrameCallback(this);return;
                        }
                        if(began<0)began=now;
                        if(lastFrame>=0)maxGap=Math.max(maxGap,now-lastFrame);lastFrame=now;
                        float t=duration==0?1:Math.min(1,(float)(now-began)/duration);
                        float progress=modeCurve.getInterpolation(show?t:1-t);
                        MotionStyle.Visibility sample=MotionStyle.visibility(target,progress);
                        s.placement=show?target:sample.placement();s.opacity=startAlpha*sample.alpha();s.barAlpha=startBar*sample.alpha();
                        renderFrame(s);callbacks++;
                        frameReport(show?"APPEARING":"CLOSING");
                        if(t<1){frames.postFrameCallback(this);return;}
                    }
                    log.accept("VISIBILITY_END show="+show+" callbacks="+callbacks+" maxCallbackGapMs="+(maxGap/1000000f));
                    if(show){s.entering=false;s.transitioning=false;s.placement=target;completeEntryInput(s);report("APPEARED");}
                    else if(afterHide!=null)afterHide.run();
                    else finishGroup(s,false,"小窗退出动画完成");
                }catch(Throwable e){failure(s,e);}
            }
        });
    }
    /** 是否已经有别的、仍然活着的迷你窗（决定新默认窗要不要把旧窗留着）。 */
    private boolean anyLiveMiniBesides(Session except){
        for(Session p:pinned){
            if(p==except)continue;
            try{if(live(p)&&"mini".equals(p.frame))return true;}catch(Exception ignored){}
        }
        return false;
    }
    // A destination replaces its occupant; the source never swaps the other slot.
    private void settleNewDefault(Session previous,Session next)throws Exception{
        if(previous==null||previous==next||previous.token.equals(next.token))return;
        WindowControl.Previous decision=WindowControl.previous(true,true,false,previous.frame,autoMinimizeOnOpen,
            !"mini".equals(previous.frame)&&anyLiveMiniBesides(previous));
        if(decision==WindowControl.Previous.RETAIN_MINI){
            previous.parked=false;previous.opacity=1;addPinned(previous);renderFrame(previous);return;
        }
        if(decision==WindowControl.Previous.MINIMIZE){
            endCover(previous);
            previous.parked=false;previous.opacity=1;previous.entering=false;previous.closing=false;
            addPinned(previous);state(previous,"mini");
        }else finishGroup(previous,false,"替换默认窗");
    }
    private Session occupySlot(Session s,String target){
        return switch(WindowControl.slot(target,s==active,isPinned(s))){
            // 迷你窗被提到前台：前台若是另一个迷你窗，它继续挂着；只有默认窗才让位。
            case EXPAND_PINNED -> {
                Session displaced=active;active=s;removePinned(s);
                if(displaced!=null&&displaced!=s&&"mini".equals(displaced.frame)){addPinned(displaced);yield null;}
                yield displaced;
            }
            // active 收成迷你窗只是多挂一个，不需要让出别人的位置。
            case MINIMIZE_ACTIVE -> null;
            case KEEP -> null;
        };
    }
    private void state(Session s,String target) throws Exception {
        if(s.entering)return;
        if("normal".equals(target)&&s.decoration!=null)s.decoration.enableDim();
        Session displaced=occupySlot(s,target);
        if(displaced!=null)finishGroup(displaced,false,"替换"+("mini".equals(target)?"迷你窗":"默认窗"));
        float startBar="mini".equals(s.frame)?0:s.barAlpha;
        if("mini".equals(s.frame)&&!s.edgeHidden)s.miniPlacement=s.placement;
        s.edgeHidden=false;s.frame=target;s.animationRev++;s.fullTransition=false;s.radiusFactor=1;
        Geometry.Placement start=s.placement;
        if(start==null){refresh(s);report("STATE");return;}
        Rect safe=new Rect();
        synchronized(lock){safe.set(safeArea(a.call(s.task,"getDisplayContent"),false));}
        float margin=s.placementMargin;
        Geometry.Placement end=normalForIme(s);
        if("mini".equals(target)) {
            end=WindowControl.miniTarget(start,s.miniPlacement,s.defaultScale,controlSafe(safe,margin),s.miniLarge);
            end=cascadeMini(s,end);
            s.miniPlacement=end;
        }
        // Change focus after the surface morph; avoid starting a competing native transition first.
        animate(s,start,end,false,startBar,"mini".equals(target)?0:1);
    }
    private void hideMini(Session s,Geometry.Placement start,int side,float y,Rect safe,float margin)throws Exception{
        s.miniPlacement=WindowControl.hiddenMini(start,side,y,controlSafe(safe,margin));
        s.edgeHidden=true;s.edgeSide=side;
        animate(s,start,Geometry.offscreen(s.miniPlacement,side,0,s.displayW,margin),false,0,0);
        log.accept("MINI_EDGE_HIDE task="+s.taskId+" side="+side);
    }
    private void restoreMini(Session s)throws Exception{
        Geometry.Placement end=s.miniPlacement;
        if(end==null)return;
        s.animationRev++;s.edgeHidden=false;
        animate(s,s.placement,end,false,0,0);
        log.accept("MINI_EDGE_RESTORE task="+s.taskId+" side="+s.edgeSide);
    }
    private void resizeMini(Session s)throws Exception{
        synchronized(lock){
            Geometry.Placement start=s.placement;if(start==null)return;
            Rect safe=safeArea(a.call(s.task,"getDisplayContent"),false);
            float margin=s.placementMargin;
            s.miniLarge=!s.miniLarge;s.animationRev++;s.radiusFactor=1;
            Geometry.Placement end=Geometry.resizeMini(start,s.defaultScale,safe.left,safe.top,safe.right,safe.bottom,margin,s.miniLarge);
            s.miniPlacement=end;animate(s,start,end,false,0,0);
        }
    }
    private void fullscreen(Session s) throws Exception {
        rememberOrientation(s);
        Geometry.Placement start=s.placement;
        Rect bounds;
        synchronized(lock){bounds=new Rect((Rect)a.call(a.call(s.task,"getDisplayContent"),"getBounds"));}
        // Keep the native foreground identity until the morph finishes. The endpoint
        // restores focus/configuration together instead of starting a second transition now.
        // Preserve aspect ratio while expanding. Real fullscreen configuration is restored at
        // the endpoint; Android owns the final app relayout (especially orientation changes).
        float scale=Math.min((float)bounds.width()/start.width(),(float)bounds.height()/start.height());
        Geometry.Placement end=new Geometry.Placement(start.width(),start.height(),scale,
            bounds.centerX()-start.width()*scale/2,bounds.centerY()-start.height()*scale/2);
        beginCover(s,start,new Geometry.Placement(bounds.width(),bounds.height(),1,bounds.left,bounds.top),true);
        s.fullTransition=true;s.animationRev++;
        animate(s,start,end,true,"mini".equals(s.frame)?0:s.barAlpha,0);
    }
    private void animate(Session s,Geometry.Placement start,Geometry.Placement end,boolean full,float startBar,float endBar) {
        int revision=s.animationRev;s.transitioning=true;
        s.transitionHandle=startBar>0||endBar>0;
        float durationScale=android.provider.Settings.Global.getFloat(context.getContentResolver(),
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,1f);
        boolean modeChange=startBar!=endBar;
        final long duration=Math.max(0,Math.round((full||modeChange?320:336)*1_000_000L*durationScale));
        final android.animation.TimeInterpolator curve=full||modeChange?modeCurve:new android.view.animation.AccelerateDecelerateInterpolator();
        frames.postFrameCallback(new Choreographer.FrameCallback(){
            long began=-1,lastFrame=-1;
            long previousDraw=-1,maxGap,maxWork;int callbacks,nativeWaits;
            public void doFrame(long time){
                if(!visible(s)||s.restoring||s.animationRev!=revision)return;
                long callbackStart=SystemClock.elapsedRealtimeNanos();
                try{
                    synchronized(lock){
                        if(s.prioritySuspended||s.screenSuspended||s.rotation.active()||a.presentationLeashed(s.task)){
                            nativeWaits++;
                            if(began>=0&&lastFrame>=0)began+=time-lastFrame;
                            lastFrame=time;frames.postFrameCallback(this);return;
                        }
                    }
                    if(began<0)began=time;
                    lastFrame=time;
                    float t=duration==0?1:Math.min(1,(float)(time-began)/duration);
                    float f=t==1?1:curve.getInterpolation(t);
                    long workStart=SystemClock.elapsedRealtimeNanos();
                    synchronized(lock){
                        s.placement=MotionStyle.interpolate(start,end,f);
                        s.radiusFactor=full?1-f:1;s.barAlpha=startBar+(endBar-startBar)*f;
                        if(s.coverFollowing&&s.cover!=null){
                            s.cover.gesturePose(visualBounds(s.placement),coverRadius(s,s.placement));
                            if(t==1){s.cover.finishGesture(visualBounds(s.placement),coverRadius(s,s.placement));s.coverFollowing=false;}
                        }
                        renderFrame(s);
                    }
                    callbacks++;maxWork=Math.max(maxWork,SystemClock.elapsedRealtimeNanos()-workStart);
                    if(previousDraw>=0)maxGap=Math.max(maxGap,time-previousDraw);previousDraw=time;
                    frameReport("STATE");
                    if(t<1)frames.postFrameCallback(this);
                    else{
                        log.accept("ANIMATION_END frame="+s.frame+" full="+full+" callbacks="+callbacks
                            +" nativeWaits="+nativeWaits+" maxCallbackGapMs="+(maxGap/1000000f)+" maxWorkMs="+(maxWork/1000000f));
                        s.transitioning=false;
                        if(full)finishGroup(s,true,"小窗动画进入全屏");
                        else{
                            synchronized(lock){
                                a.call(s.task,"setFocusable",new Class<?>[]{boolean.class},!"mini".equals(s.frame));
                                if(modeChange&&!"mini".equals(s.frame))focusExpanded(s);
                                // Home/recents must see the real foreground task. Resume
                                // the visible mini separately without changing task focus.
                                a.call(wms,"updateFocusedWindowLocked",new Class<?>[]{int.class,boolean.class},0,true);
                                a.call(root,"resumeFocusedTasksTopActivities");resumeMini(s);a.call(wms,"requestTraversal");
                            }
                            report("STATE_END");
                        }
                    }
                }catch(Throwable e){failure(s,e);}
                finally{
                    long total=SystemClock.elapsedRealtimeNanos()-callbackStart;
                    if(total>8_333_333L)log.accept("ANIMATION_SLOW frame="+s.frame+" full="+full+" totalMs="+(total/1_000_000f));
                }
            }
        });
    }

    private Object find(int taskId) throws Exception {
        // MATCH_TASK_IN_STACKS_ONLY=0: never resurrect a recent/background task.
        return a.lookup.invoke(root, taskId, 0);
    }
    /**
     * 按任务号取任务，供开窗入口使用。
     *
     * 先按老规矩只在栈里找（不复活后台任务）。找不到时再看一眼 recent 列表：
     * 多任务卡片上滑开小窗时，被滑的那个任务已经不在任何栈上、只存在于 recent，
     * 只用 `MATCH_TASK_IN_STACKS_ONLY` 会取到 null，表现就是「松手后什么都没发生」。
     *
     * 放宽到 recent 之后仍然要校验包名：任务号可能被回收复用，认错任务比开不出来更糟。
     */
    private Object taskForOpen(int taskId,String pkg)throws Exception{
        if(taskId<0)return a.focused.invoke(root);
        Object task=find(taskId);
        if(task!=null)return task;
        // MATCH_TASK_IN_STACKS_OR_RECENT_TASKS=1：只认 recent 里的现存任务，不做 restore。
        task=a.lookup.invoke(root,taskId,1);
        if(task==null)return null;
        ComponentName real=(ComponentName)a.field(task,"realActivity");
        if(pkg!=null&&(real==null||!pkg.equals(real.getPackageName()))){
            log.accept("OPEN_TASK_REJECTED task="+taskId+" want="+pkg+" real="+(real==null?"?":real.getPackageName()));
            return null;
        }
        log.accept("OPEN_TASK_FROM_RECENTS task="+taskId+" pkg="+pkg);
        return task;
    }
    /** 把「打开即迷你」排进会话：入场还没结束就挂成待执行指令，否则立刻补一条 MINI。 */
    private void queueMini(Session s){
        if(s==null||s.restoring||s.closing)return;
        Intent mini=miniControl(s);
        if(entryInputPending(s)){s.entryControl=mini;return;}
        s.entryControl=null;
        handler.post(()->{
            if(!visible(s)||s.restoring||s.closing)return;
            try{command(mini);}catch(Throwable error){log.accept("MINI_OPEN_FAILED "+FrameworkAccess.reason(error));}
        });
    }

    private Session snapshot(int taskId,String pkg,String token)throws Exception{return snapshot(taskId,pkg,token,false);}
    private Session snapshot(int taskId, String pkg, String token,boolean linked) throws Exception {
        Object task = taskForOpen(taskId,pkg);
        BackPreparation prepared=backPreparation;
        TaskVisibilityGuard mask=prepared!=null&&prepared.task==task&&prepared.token.equals(token)?prepared.mask:null;
        Session s=new AndroidWindowAdmission(a,atm,resizeCandidate).snapshot(task,pkg,token,linked,mask);
        Object top=s.orientationActivity;
        s.landscape=initialLandscape(top,pkg,s.userId);
        s.orientationActivity=top;
        s.orientationRequest=top==null?-1:a.integer(top,"getRequestedOrientation");
        s.orientationBaseline=true;
        log.accept("INITIAL_ORIENTATION task="+s.taskId+" landscape="+s.landscape+" request="+s.orientationRequest);
        return s;
    }

    private void validate(Object task) throws Exception {validate(task,false);}
    static final class EntryNotReady extends IllegalStateException {
        EntryNotReady(){super(WindowAdmission.Rejection.WAIT.message);}
    }
    /** Only before lease/journal/task mutation; superseded requests never retry. */
    private boolean waitStart(Intent request){
        long now=SystemClock.elapsedRealtime(),began=request.getLongExtra("entryBegan",now);
        if(now-began>=1800)return false;
        if(!request.hasExtra("entryBegan"))log.accept("ENTRY_WAIT task="+request.getIntExtra("taskId",-1));
        Intent retry=new Intent(request).putExtra("entryBegan",began);long generation=openGeneration;
        handler.postDelayed(()->{
            if(generation!=openGeneration){cancelBackPreparation(retry.getStringExtra("token"));completeOpen(retry,false);return;}
            command(retry);
        },32);
        return true;
    }
    private void validate(Object task,boolean allowTransition) throws Exception {
        new AndroidWindowAdmission(a,atm,resizeCandidate).validate(task,allowTransition);
    }

    private boolean identity(Object task, Session s) throws Exception {
        if (task == null || (Integer) a.field(task, "mTaskId") != s.taskId || (Integer) a.field(task, "mUserId") != s.userId) return false;
        // A live task may change its root component/base intent after a document or payment return.
        // The in-memory task object, id and user are stronger identity than mutable activity metadata.
        if(s.task!=null&&WindowPolicy.sameLiveTask(task!=null&&task==s.task,(Integer)a.field(task,"mTaskId"),s.taskId,(Integer)a.field(task,"mUserId"),s.userId))return true;
        Intent base = (Intent) a.call(task, "getBaseIntent");
        ComponentName real = (ComponentName) a.field(task, "realActivity");
        return base != null && java.util.Objects.equals(s.baseId,base.getIdentifier()) && real != null
            && s.component.equals(real.flattenToString()) && s.pkg.equals(real.getPackageName());
    }

    private boolean initialLandscape(Object activity,String pkg,int userId)throws Exception{
        if(activity==null)return false;
        android.content.pm.ActivityInfo entry=(android.content.pm.ActivityInfo)a.field(activity,"info");
        Intent launch=context.getPackageManager().getLaunchIntentForPackage(pkg);
        int launcher=entry.screenOrientation;
        if(launch!=null&&launch.getComponent()!=null){
            try{launcher=context.getPackageManager().getActivityInfo(launch.getComponent(),0).screenOrientation;}
            catch(android.content.pm.PackageManager.NameNotFoundException missing){return false;}
        }
        Boolean remembered=orientations.get(userId,pkg,entry.name);
        return WindowPolicy.initialLandscape(entry.screenOrientation,launcher,a.integer(activity,"getRequestedOrientation"),remembered);
    }
    private void rememberOrientation(Session s){
        if(s==null||s.restoring||s.orientationActivity==null||handler==null)return;
        try{
            android.content.pm.ActivityInfo info=(android.content.pm.ActivityInfo)a.field(s.orientationActivity,"info");
            if(!orientations.remember(s.userId,s.pkg,info.name,s.landscape)||!orientationSaveQueued.compareAndSet(false,true))return;
            handler.post(()->{
                orientationSaveQueued.set(false);
                try{orientations.flush();}catch(Exception error){log.accept("ORIENTATION_MEMORY_SAVE_FAILED "+FrameworkAccess.reason(error));}
            });
        }catch(Exception error){log.accept("ORIENTATION_MEMORY_UPDATE_FAILED "+FrameworkAccess.reason(error));}
    }
    void orientationRequested(Object activity,int request){
        if(lock==null)return;
        try{synchronized(lock){
            Session s=sessionFor(a.call(activity,"getTask"));
            if(s==null||s.restoring||s.placement==null||a.call(s.task,"topRunningActivity")!=activity)return;
            // A fresh in-window request is meaningful even when it repeats the pre-entry value.
            s.orientationBaseline=false;s.orientationActivity=activity;s.orientationRequest=request;
            s.landscape=WindowPolicy.landscape(request,s.landscape);
            rememberOrientation(s);
        }}catch(Exception error){log.accept("APP_ORIENTATION_OBSERVE_FAILED "+FrameworkAccess.reason(error));}
    }
    private boolean updateOrientation(Session s)throws Exception{
        Object activity=a.call(s.task,"topRunningActivity");if(activity==null)return false;
        int request=a.integer(activity,"getRequestedOrientation");
        boolean unchangedEntry=s.orientationBaseline&&activity==s.orientationActivity&&request==s.orientationRequest;
        if(!unchangedEntry)s.orientationBaseline=false;
        boolean next=unchangedEntry?s.landscape:WindowPolicy.landscape(request,s.landscape);
        s.orientationActivity=activity;s.orientationRequest=request;
        if(next!=s.landscape){
            log.accept("APP_ORIENTATION task="+s.taskId+" request="+request+" landscape="+next+" activity="+activity);
            s.landscape=next;
        }
        rememberOrientation(s);
        // A native transition can defer refresh; retry until the actual canvas matches.
        return s.placement!=null&&(s.placement.width()>s.placement.height())!=s.landscape;
    }
    private Rect safeArea(Object display) throws Exception {return safeArea(display,true);}
    private Rect safeArea(Object display,boolean avoidCutout) throws Exception {
        Rect bounds=(Rect)a.call(display,"getBounds"),safe=new Rect(bounds);
        boolean horizontal=bounds.width()>bounds.height();
        if(!horizontal&&avoidCutout)a.call(display,"getStableRect",new Class<?>[]{Rect.class},safe);
        int types=avoidCutout?android.view.WindowInsets.Type.displayCutout():0;
        if(!horizontal)types|=android.view.WindowInsets.Type.systemBars();
        if(types==0)return safe;
        Object state=a.call(a.call(display,"getInsetsStateController"),"getRawInsetsState");
        Insets insets=(Insets)a.call(state,"calculateInsets",new Class<?>[]{Rect.class,Rect.class,int.class,boolean.class},bounds,bounds,types,true);
        safe.left=Math.max(safe.left,bounds.left+insets.left);safe.top=Math.max(safe.top,bounds.top+insets.top);
        safe.right=Math.min(safe.right,bounds.right-insets.right);safe.bottom=Math.min(safe.bottom,bounds.bottom-insets.bottom);
        return safe;
    }
    private void prepareGeometry(Session s) throws Exception {
            Object display = a.call(s.task, "getDisplayContent");
            Rect displayBounds = (Rect) a.call(display, "getBounds");
            Rect safe = safeArea(display);
            Rect mini = safeArea(display,false);
            int margin = Math.round(a.config(display, false).densityDpi / 160f * (displayBounds.width()>displayBounds.height()?4:12));
            Geometry.Placement p = Geometry.place(s.logicalShort, s.landscape,
                safe.left, safe.top, safe.right, safe.bottom, margin);
            s.defaultScale=p.scale();s.normalPlacement=p;
            s.miniPlacement=Geometry.miniTopRight(p,p.scale(),mini.left,mini.top,mini.right,mini.bottom,margin,s.miniLarge);
            s.placement="mini".equals(s.frame)?s.miniPlacement:p;
            s.displayW=displayBounds.width();s.displayH=displayBounds.height();s.displayRotation=a.integer(display,"getRotation");
            s.safeBounds=new Rect(safe);s.miniBounds=mini;s.placementMargin=margin;
    }
    Geometry.Placement normalForIme(Session s)throws Exception{
        Geometry.Placement normal=s.normalPlacement;
        if(normal==null||s.displayW>s.displayH)return normal;
        Object display=a.call(s.task,"getDisplayContent");Rect bounds=(Rect)a.call(display,"getBounds");
        Object state=a.call(a.call(display,"getInsetsStateController"),"getRawInsetsState");
        Insets ime=(Insets)a.call(state,"calculateInsets",new Class<?>[]{Rect.class,Rect.class,int.class,boolean.class},bounds,bounds,android.view.WindowInsets.Type.ime(),false);
        return Geometry.avoidIme(normal,s.displayW,s.displayH,ime.bottom);
    }
    private void refresh(Session s) throws Exception {refresh(s,false);}
    private void refresh(Session s,boolean surfaceOnly) throws Exception {
        synchronized (lock) {
            if (!visible(s) || s.restoring) return;
            if (find(s.taskId) != s.task || !identity(s.task, s) || a.call(s.task, "getParent") != s.parent)
                throw new IllegalStateException("任务身份或父层发生变化");
            if (s.refreshingGeometry||s.closing||s.fullTransition||a.presentationLeashed(s.task)) return;
            s.refreshingGeometry=true;
            try{
            if (a.integer(s.task, "getWindowingMode") != 5) throw new IllegalStateException("任务已退出 freeform");
            ComponentName real=(ComponentName)a.field(s.task,"realActivity");Intent base=(Intent)a.call(s.task,"getBaseIntent");
            if(real!=null&&s.pkg.equals(real.getPackageName())&&base!=null&&(!s.component.equals(real.flattenToString())||!java.util.Objects.equals(s.baseId,base.getIdentifier()))){
                s.component=real.flattenToString();s.baseId=base.getIdentifier();save(s);
                history.windowed(new WindowHistory.Entry(s.taskId,s.userId,s.pkg,s.component,s.baseId));saveHistory();
                log.accept("LIVE_IDENTITY_UPDATED task="+s.taskId);
            }
            Geometry.Placement previous=s.placement,oldMini=s.miniPlacement;
            boolean completingMorph=s.transitioning;
            Rect oldSafe=s.safeBounds==null?null:new Rect(s.safeBounds);
            Rect oldMiniSafe=s.miniBounds==null?null:new Rect(s.miniBounds);
            float oldMargin=s.placementMargin;
            int oldW=s.displayW,oldH=s.displayH,oldRotation=s.displayRotation;
            prepareGeometry(s);
            boolean rotated=oldRotation>=0&&oldRotation!=s.displayRotation;
            if(previous!=null&&(previous.width()!=s.placement.width()||previous.height()!=s.placement.height())&&!rotated&&!s.rotation.active())
                beginCover(s,previous,s.placement,false);
            boolean unchanged=!rotated&&previous!=null&&oldW==s.displayW&&oldH==s.displayH
                &&java.util.Objects.equals(oldSafe,s.safeBounds)
                &&java.util.Objects.equals(oldMiniSafe,s.miniBounds)
                &&previous.width()==s.placement.width()&&previous.height()==s.placement.height();
            if(unchanged){s.placement=previous;if(oldMini!=null)s.miniPlacement=oldMini;}
            else{
                Rect safe=s.miniBounds;
                if(oldMiniSafe!=null&&previous!=null){
                    Geometry.Placement mini="mini".equals(s.frame)&&!s.edgeHidden?previous:oldMini;
                    if(rotated){
                        s.resizing=false;s.edgeHidden=false;s.edgeSide=0;
                        if(s.miniDragBase!=null)s.opacity=s.miniDragOpacity;
                        s.miniDragBase=null;s.edgeDragBase=null;s.edgeBarDragging=false;
                    }
                    else if(mini!=null)s.miniPlacement=Geometry.remapMini(mini,s.normalPlacement,s.defaultScale,
                        oldMiniSafe.left,oldMiniSafe.top,oldMiniSafe.right,oldMiniSafe.bottom,oldMargin,
                        safe.left,safe.top,safe.right,safe.bottom,s.placementMargin,s.miniLarge);
                    s.placement="mini".equals(s.frame)?s.miniPlacement:s.normalPlacement;
                    if(s.placement==null)s.placement=previous;
                    if(s.edgeHidden)s.placement=Geometry.offscreen(s.miniPlacement,s.edgeSide,0,s.displayW,s.placementMargin);
                }
                if(s.coverFollowing){
                    if(s.cover!=null)s.cover.finishGesture(visualBounds(s.placement),coverRadius(s,s.placement));
                    s.coverFollowing=false;s.entryControl=null;
                }
                s.animationRev++;s.transitioning=false;s.fullTransition=false;s.radiusFactor=1;s.barAlpha="mini".equals(s.frame)?0:1;
                if(s.entering){s.entryStarted=false;s.presented=false;s.transitioning=true;s.opacity=0;s.barAlpha=0;}
            }
            s.geometryDirty=false;
            if(!unchanged&&completingMorph&&!s.entering&&!surfaceOnly){
                a.call(s.task,"setFocusable",new Class<?>[]{boolean.class},!"mini".equals(s.frame));
                if(!"mini".equals(s.frame))focusExpanded(s);
                resumeMini(s);
            }
            Geometry.Placement p=s.placement;
            Rect logical = new Rect(0, 0, p.width(), p.height());
            if (!logical.equals(a.call(s.task, "getBounds"))){
                if(surfaceOnly){s.geometryDirty=true;configurationChanged(s.task);return;}
                a.call(s.task, "setBounds", new Class<?>[]{Rect.class}, logical);
            }
            if(s.cover!=null&&!s.coverFullscreen)s.cover.target(visualBounds(s.placement));
            // Check actual post-configuration geometry, not only the requested rectangle.
            Rect actual = (Rect) a.call(s.task, "getBounds");
            if (actual.width() != p.width() || actual.height() != p.height())
                throw new IllegalStateException("逻辑画布被系统重写：" + actual);
            apply(s);
            if(!surfaceOnly){a.call(root,"ensureActivitiesVisible");a.call(wms,"requestTraversal");}
            }finally{s.refreshingGeometry=false;}
        }
    }

    private static RectF visualBounds(Geometry.Placement p){return new RectF(p.x(),p.y(),p.x()+p.width()*p.scale(),p.y()+p.height()*p.scale());}
    /**
     * 多个迷你窗都出生在右上角的同一个位置，必须错开。
     *
     * 优先向下堆叠，碰到安全区底部就换到左边一列。只处理“出生即迷你”的新窗口；
     * 用户自己拖动过之后的窗口不再被自动挪位。
     */
    private Geometry.Placement cascadeMini(Session s,Geometry.Placement base){
        Rect b=s.miniBounds;
        if(base==null||b==null)return base;
        java.util.List<RectF> used=new java.util.ArrayList<>();
        for(Session other:pinned){
            if(other==s||other.placement==null)continue;
            used.add(visualBounds(other.placement));
        }
        if(used.isEmpty())return base;
        float margin=s.placementMargin;
        float w=base.width()*base.scale(),h=base.height()*base.scale();
        float gap=Math.max(margin,8f);
        float left=b.left+margin,top=b.top+margin,right=b.right-margin,bottom=b.bottom-margin;
        float x=base.x(),y=base.y();
        RectF probe=new RectF();
        for(int attempt=0;attempt<32;attempt++){
            probe.set(x,y,x+w,y+h);
            boolean clash=false;
            for(RectF r:used)if(RectF.intersects(probe,r)){clash=true;break;}
            if(!clash)break;
            y+=h+gap;
            if(y+h>bottom){y=top;x-=w+gap;if(x<left)break;}
        }
        if(x==base.x()&&y==base.y())return base;
        Geometry.Placement moved=new Geometry.Placement(base.width(),base.height(),base.scale(),
            Math.max(left,Math.min(x,right-w)),Math.max(top,Math.min(y,bottom-h)));
        log.accept("MINI_CASCADE task="+s.taskId+" x="+Math.round(moved.x())+" y="+Math.round(moved.y()));
        return moved;
    }

    private long exitCover(Session s){
        if(s.cover!=null&&s.cover.exiting())return 0;
        if(s.placement==null||s.prioritySuspended||s.screenSuspended)return 0;
        try{
            if(s.cover!=null&&!s.cover.mask.holding())endCover(s);
            if(s.cover==null)beginCover(s,s.placement,s.placement,false);
            return s.cover==null?0:s.cover.exit();
        }catch(Throwable error){log.accept("COVER_EXIT_SKIPPED "+FrameworkAccess.reason(error));return 0;}
    }
    private void beginCover(Session s,Geometry.Placement from,Geometry.Placement to,boolean fullscreen){
        if(from==null||to==null||s.prioritySuspended)return;
        try{
            TaskCover previous=s.cover;
            boolean sameMode=s.coverFullscreen==fullscreen;
            s.coverDrawn=false;s.coverFullscreen=fullscreen;
            if(previous!=null&&sameMode&&previous.canRetarget()){
                previous.target(visualBounds(to));return;
            }
            float density=a.config(s.task,false).densityDpi/160f;
            float radius=a.integer(s.task,"getWindowingMode")==1?0:density*MotionStyle.radius(from.scale(),s.defaultScale);
            TaskCover cover=new TaskCover(a,context,s.task,s.parent,s.pkg,visualBounds(from),visualBounds(to),radius,fullscreen?0:density*MotionStyle.radius(to.scale(),s.defaultScale),coverReleaseAlpha(s),s.entering&&s.entryFade&&!fullscreen);
            s.cover=cover;prepareCover(s,cover);
            if(previous!=null){
                log.accept("COVER_REPLACED task="+s.taskId+" old="+previous.state());
                previous.ownerAlpha(0);previous.close();previous.mask.continueWith(cover.mask);cover.mask.hide();
            }
            pollCover(s,cover);
            log.accept("COVER_BEGIN task="+s.taskId+" from="+visualBounds(from)+" to="+visualBounds(to)+" fullscreen="+fullscreen);
        }catch(Throwable error){log.accept("COVER_SKIPPED "+FrameworkAccess.reason(error));endCover(s);}
    }
    private void prepareCover(Session s,TaskCover cover)throws Exception{
        cover.beforeReveal(()->{
            renderFrame(s);
            if(s.launchFlow!=null)s.launchFlow.finish();
        });
        cover.entryDecorations(s.entering&&!s.coverFullscreen);
        if(s.startMask!=null&&s.startMask.holding()){
            s.startMask.transfer();s.startMask.continueWith(cover.mask);s.startMask=null;
        }
        coverTasks.put(s.task,s);
    }
    private boolean coverConfigurationMatches(Session s,Object window)throws Exception{
        if(((WindowManager.LayoutParams)a.field(window,"mAttrs")).type==WindowManager.LayoutParams.TYPE_APPLICATION_STARTING)return false;
        if(a.integer(s.task,"getWindowingMode")!=(s.coverFullscreen?1:5))return false;
        Configuration reported=(Configuration)a.call(a.field(window,"mLastReportedConfiguration"),"getMergedConfiguration");
        Object wc=a.field(reported,"windowConfiguration"),display=a.call(s.task,"getDisplayContent");
        if(display==null||a.integer(wc,"getWindowingMode")!=(s.coverFullscreen?1:5)
            ||a.integer(wc,"getRotation")!=a.integer(display,"getRotation"))return false;
        Rect actual=(Rect)a.call(wc,"getBounds"),expected=s.coverFullscreen?(Rect)a.call(display,"getBounds"):new Rect(0,0,s.placement.width(),s.placement.height());
        return actual.width()==expected.width()&&actual.height()==expected.height();
    }
    void drawingFinished(Object window,int seq){
        if(lock==null)return;
        try{synchronized(lock){
            Object task=a.call(window,"getTask");Session s=coverTasks.get(task);
            if(s==null||s.cover==null||a.call(task,"getTopVisibleAppMainWindow")!=window
                ||seq<(Integer)a.field(window,"mSyncSeqId"))return;
            if(coverConfigurationMatches(s,window))s.coverDrawn=true;
        }}catch(Throwable error){log.accept("COVER_DRAW_CHECK_FAILED "+FrameworkAccess.reason(error));}
    }
    private void pollCover(Session s,TaskCover cover){
        frames.postFrameCallback(new Choreographer.FrameCallback(){
            long previous=-1,maxGap,maxWork;int callbacks;
            public void doFrame(long frameTime){
                long started=SystemClock.elapsedRealtimeNanos();
                synchronized(lock){
                    if(s.cover!=cover)return;
                    try{
                        cover.ownerAlpha(coverReleaseAlpha(s));
                        boolean attached=a.bool(s.task,"isAttached");
                        if(!attached||s.parked&&!cover.exiting()||s.restoring&&!s.restoreForeground){endCover(s);return;}
                        Object main=a.call(s.task,"getTopVisibleAppMainWindow");
                        // A hot/onNewIntent launch can reuse an already drawn window without
                        // another finishDrawing callback. Native draw state and current config
                        // still have to match; a starting snapshot never counts as app content.
                        boolean drawn=main!=null&&a.bool(main,"isDrawn")&&coverConfigurationMatches(s,main);
                        if(drawn)s.coverDrawn=true;
                        boolean ready=s.coverDrawn&&drawn&&a.bool(main,"isVisible")&&!a.presentationLeashed(s.task);
                        if(s.coverFullscreen){
                            Object display=a.call(s.task,"getDisplayContent");if(display!=null)cover.target(new RectF((Rect)a.call(display,"getBounds")));
                        }else ready&=!s.entering&&!s.transitioning&&!s.rotation.active();
                        updatePriorityState(s);
                        if(s.cover!=cover)return;
                        boolean visible=!s.prioritySuspended&&context.getSystemService(PowerManager.class).isInteractive()&&!context.getSystemService(KeyguardManager.class).isKeyguardLocked();
                        long frameClock=SystemClock.elapsedRealtime()-Math.max(0,System.nanoTime()-frameTime)/1_000_000L;
                        if(s.launchFlow!=null&&s.launchFlow.active()&&!cover.exiting()){
                            s.launchFlow.visibility(visible,frameClock);
                            if(visible){
                                if(s.launchFlow.timedOut(frameClock)&&!ready)throw new IllegalStateException("launch chain frame timeout");
                                ready=s.launchFlow.ready(ready,frameClock);
                            }else ready=false;
                        }
                        boolean continuing=cover.step(ready,visible,frameClock);
                        if(s.priorityHideTask){a.opacity(s.task,0);surfaceVisible(s,false);}
                        callbacks++;maxWork=Math.max(maxWork,SystemClock.elapsedRealtimeNanos()-started);
                        if(previous>=0)maxGap=Math.max(maxGap,frameTime-previous);previous=frameTime;
                        if(!continuing){
                            log.accept("COVER_END task="+s.taskId+" callbacks="+callbacks+" maxGapMs="+(maxGap/1_000_000f)
                                +" maxWorkMs="+(maxWork/1_000_000f)+" state="+cover.state());
                            if(cover.exiting())s.barAlpha=0;s.cover=null;coverTasks.remove(s.task);
                            s.coverFollowing=false;
                            if(visible(s)&&!s.restoring){renderFrame(s);completeEntryInput(s);}return;
                        }
                        frames.postFrameCallback(this);
                    }catch(Throwable error){
                        log.accept("COVER_RELEASED task="+s.taskId+" "+FrameworkAccess.reason(error)+" state="+cover.state());
                        s.opacity=0;endCover(s);handler.post(()->{if(sessions.contains(s)&&!s.restoring)recover("遮罩目标未就绪");});
                    }
                }
            }
        });
    }
    private float coverReleaseAlpha(Session s){return WindowPresentation.coverReleaseAlpha(presentationState(s));}
    private void endCover(Session s){
        TaskCover cover=s.cover;s.cover=null;s.coverFollowing=false;coverTasks.remove(s.task);
        if(cover!=null){
            cover.ownerAlpha(coverReleaseAlpha(s));cover.close();
            // Releasing the cover mask restores its surface alpha. Reapply the
            // live owner immediately so parked/background tasks stay hidden.
            try{
                if(s.restoring&&!s.restoreForeground)a.opacity(s.task,0);
                else if(sessions.contains(s)&&!s.restoring){renderFrame(s);completeEntryInput(s);}
            }catch(Throwable error){log.accept("COVER_OWNER_REAPPLY_FAILED "+FrameworkAccess.reason(error));}
        }
    }

    private boolean matches(Object task,WindowHistory.Entry e) throws Exception {
        if(task==null||e==null||(Integer)a.field(task,"mTaskId")!=e.taskId()||(Integer)a.field(task,"mUserId")!=e.userId())return false;
        ComponentName c=(ComponentName)a.field(task,"realActivity");Intent intent=(Intent)a.call(task,"getBaseIntent");
        return c!=null&&e.component().equals(c.flattenToString())&&intent!=null&&java.util.Objects.equals(e.baseId(),intent.getIdentifier());
    }
    private void loadHistory(){
        if(!historyFile.getBaseFile().exists())return;
        try{byte[] bytes=historyFile.readFully();if(bytes.length>262144)throw new IOException("history size");
            JSONObject doc=new JSONObject(new String(bytes,StandardCharsets.UTF_8));hideRecents=doc.optBoolean("enabled",false);
            JSONArray entries=doc.getJSONArray("tasks");if(entries.length()>1024)throw new IOException("history count");
            for(int n=0;n<entries.length();n++){JSONObject o=entries.getJSONObject(n);
                if(o.getInt("id")<0||o.getInt("user")!=0||!Wire.allowedPackage(o.getString("pkg")))continue;
                history.windowed(new WindowHistory.Entry(o.getInt("id"),o.getInt("user"),o.getString("pkg"),o.getString("component"),o.isNull("base")?null:o.getString("base")));
            }
        }catch(Exception e){log.accept("HISTORY_LOAD_FAILED "+FrameworkAccess.reason(e));}
    }
    private void saveHistory(){
        FileOutputStream stream=null;
        try{JSONArray entries=new JSONArray();for(WindowHistory.Entry e:history.entries())entries.put(new JSONObject()
                .put("id",e.taskId()).put("user",e.userId()).put("pkg",e.pkg()).put("component",e.component()).put("base",e.baseId()==null?JSONObject.NULL:e.baseId()));
            byte[] bytes=new JSONObject().put("version",1).put("enabled",hideRecents).put("tasks",entries).toString().getBytes(StandardCharsets.UTF_8);
            stream=historyFile.startWrite();stream.write(bytes);historyFile.finishWrite(stream);
        }catch(Exception e){if(stream!=null)historyFile.failWrite(stream);log.accept("HISTORY_SAVE_FAILED "+FrameworkAccess.reason(e));}
    }
    private int[] pushedHidden;
    private void pushHistory(){pushHistory(true);}
    private void pushHistory(boolean force){
        if(context==null)return;
        int[] hidden=hiddenIds();
        synchronized(this){
            if(!force&&java.util.Arrays.equals(hidden,pushedHidden))return;
            pushedHidden=hidden;
        }
        Intent state=new Intent(HISTORY).setPackage(LauncherGuard.PACKAGE).putExtra("protocol",Wire.PROTOCOL)
            .putExtra("hiddenTaskIds",hidden);
        // Callers can still own the WM lock. Snapshot now, but acquire AMS only
        // after this window operation unwinds, as the result broadcasts do.
        handler.post(()->{
            try{context.sendBroadcast(state,null,android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());}
            catch(Exception e){log.accept("HISTORY_PUBLISH_FAILED "+FrameworkAccess.reason(e));}
        });
    }
    private int[] hiddenIds(){
        if(!hideRecents)return new int[0];
        java.util.ArrayList<Integer> ids=new java.util.ArrayList<>();
        synchronized(lock){for(WindowHistory.Entry e:history.entries())try{Object task=find(e.taskId());if(task==null||hideRecent(task))ids.add(e.taskId());}catch(Exception ignored){}}
        return ids.stream().mapToInt(Integer::intValue).toArray();
    }
    private void historyTick(){
        try{synchronized(lock){
            if(recovering&&recoveryRetryState.needsReadinessCheck()&&pendingRecovery!=null&&recoveryStateReady(pendingRecovery)){
                if(recoveryRetryState.becameReady())recover("系统就绪后恢复");
            }
        }}catch(Exception error){log.accept("RECOVERY_READINESS_FAILED "+FrameworkAccess.reason(error));}
        boolean changed=false;
        for(WindowHistory.Entry e:history.entries())try{synchronized(lock){
            Object task=find(e.taskId());
            if(task==null){Object recent=a.lookup.invoke(root,e.taskId(),1);if(recent==null){history.removed(e.taskId());changed=true;}continue;}
            if(!matches(task,e)){history.removed(e.taskId());changed=true;continue;}
            boolean ours=owns(task);
            Object win=a.call(task,"getTopVisibleAppMainWindow");
            boolean focused=a.focused.invoke(root)==task;
            boolean ready=!ours&&focused&&a.integer(task,"getWindowingMode")==1&&a.bool(task,"isVisibleRequested")&&!a.animating(task)
                &&win!=null&&a.bool(win,"isDrawn")&&a.bool(win,"isVisible");
            if(ready){
                Configuration reported=(Configuration)a.call(a.field(win,"mLastReportedConfiguration"),"getMergedConfiguration");
                Rect expected=(Rect)a.call(task,"getBounds");Rect actual=(Rect)a.call(a.field(reported,"windowConfiguration"),"getBounds");
                Rect frame=(Rect)a.call(win,"getFrame");
                // Letterboxed fullscreen apps may have a smaller app frame. Require the
                // client to report fullscreen mode and its frame to fit the actual task.
                Object reportedWindow=a.field(reported,"windowConfiguration");
                ready=a.integer(reportedWindow,"getWindowingMode")==1&&!frame.isEmpty()
                    &&expected.contains(frame)&&!actual.isEmpty();
            }
            if(!history.observeFullscreen(e.taskId(),ready,SystemClock.elapsedRealtime()))continue;
            try{a.call(a.field(wms,"mTaskSnapshotController"),"recordSnapshot",new Class<?>[]{a.task},task);}
            catch(Exception ex){log.accept("SNAPSHOT_REFRESH_FAILED task="+e.taskId()+" "+FrameworkAccess.reason(ex));}
            if(history.fullscreenReady(e.taskId(),false,true,true,true,false)){
                history.clearFullscreen(e.taskId());changed=true;log.accept("HISTORY_FULLSCREEN_READY task="+e.taskId());
            }
        }}catch(Exception ex){log.accept("HISTORY_CHECK_FAILED task="+e.taskId()+" "+FrameworkAccess.reason(ex));}
        if(changed){saveHistory();notifyRecents();}
        handler.postDelayed(this::historyTick,400);
    }
    private void notifyRecents(){
        pushHistory();
        try{synchronized(lock){a.call(a.field(atm,"mTaskChangeNotificationController"),"notifyTaskStackChanged");}}
        catch(Exception e){log.accept("RECENTS_REFRESH_FAILED "+FrameworkAccess.reason(e));}
    }
    boolean hideRecent(Object task){
        if(!hideRecents)return false;
        try{
            if(!owns(task)&&a.integer(task,"getWindowingMode")==1&&a.focused.invoke(root)==task)return false;
            return matches(task,history.get((Integer)a.field(task,"mTaskId")));
        }catch(Exception e){return false;}
    }
    boolean preserveImmersive(Object policy,Object focused){
        Session s=active;if(s==null||s.restoring||focused==null)return false;
        try{
            Object display=a.field(policy,"mDisplayContent");
            if(display!=a.call(s.task,"getDisplayContent"))return false;
            Object dp=a.call(display,"getDisplayPolicy");
            Object background=a.field(dp,"mTopFullscreenOpaqueWindowState");
            if(background==null||a.call(background,"getTask")==s.task)return false;
            android.view.WindowManager.LayoutParams attrs=(android.view.WindowManager.LayoutParams)a.field(focused,"mAttrs");
            boolean screenshot="com.android.systemui".equals(attrs.packageName)&&attrs.getTitle()!=null&&attrs.getTitle().toString().contains("Screenshot");
            if(focused!=background&&!owns(a.call(focused,"getTask"))&&!screenshot)return false;
            // Respect all other native multi-window demands; suppress only our freeform force.
            for(Object child:(java.util.List<?>)a.field(s.parent,"mChildren")){
                if(owns(child)||!a.task.isInstance(child)||!a.bool(child,"isVisible"))continue;
                int mode=a.integer(child,"getWindowingMode");if(mode!=1&&mode!=2)return false;
            }
            return true;
        }catch(Exception e){return false;}
    }
    private void gestureStart(Intent request){
        try{
            if(waitForRecovery(request,()->gestureStart(request)))return;
            if(normalOccupied())throw new IllegalStateException("默认窗已被占用");
            synchronized(lock){
                Object task=find(request.getIntExtra("taskId",-1));
                if(task==null)throw new IllegalStateException("手势任务已结束");
                ComponentName component=(ComponentName)a.field(task,"realActivity");
                if(component==null||!component.getPackageName().equals(request.getStringExtra("package")))throw new IllegalStateException("手势任务已变化");
            }
            command(new Intent(request).putExtra("op","START").putExtra("morphEntry",true));
        }catch(Exception e){cancelBackPreparation(request.getStringExtra("token"));completeOpen(request,false);note("GESTURE_FAILED："+FrameworkAccess.reason(e));}
    }
    boolean owns(Object task) { Session s=sessionFor(task);return s!=null&&!s.restoring; }
    /** No floating session: session-scoped hooks keep the native result without any reflection. */
    boolean idle(){return sessions.isEmpty();}
    /** Cheap gate before the resize hooks resolve a task: only a preflight candidate or a session can change the result. */
    boolean mayAllowResize(){return !sessions.isEmpty()||resizeCandidate.get()!=null;}
    boolean allowResize(Object task){return task!=null&&(task==resizeCandidate.get()||owns(task));}
    private void focusExpanded(Session s)throws Exception {
        if(active!=s||s.restoring||s.closing||s.prioritySuspended||updatePriorityState(s)
            ||SystemClock.uptimeMillis()<s.priorityFocusUntil||context.getSystemService(KeyguardManager.class).isKeyguardLocked())return;
        Object top=a.call(s.task,"topRunningActivity");if(top==null)return;
        // Same native focus path used by setFocusedTask, without requesting an
        // extra TO_FRONT transition for an already always-on-top freeform task.
        a.call(top,"moveFocusableActivityToTop",new Class<?>[]{String.class},"NativeComplements-expand");
        Object display=a.call(s.task,"getDisplayContent");
        a.call(display,"setFocusedApp",new Class<?>[]{a.activity},top);
        a.call(wms,"updateFocusedWindowLocked",new Class<?>[]{int.class,boolean.class},0,true);
        log.accept("MINI_EXPAND_FOCUS task="+s.taskId+" window="+a.field(display,"mCurrentFocus"));
    }
    private void repairNormalFocus(Session s){
        try{synchronized(lock){
            if(active!=s||!"normal".equals(s.frame)||!s.presented||s.restoring||s.closing||s.parked
                ||s.prioritySuspended||s.screenSuspended||s.transitioning||s.fullTransition||s.cover!=null||s.rotation.active()
                ||pendingStart!=null||!s.lease.isBinderAlive()||a.animating(s.task)
                ||screenBlocked())return;
            Object display=a.call(s.task,"getDisplayContent"),focus=display==null?null:a.field(display,"mCurrentFocus");
            if(focus==null)return;
            Object background=a.call(focus,"getTask");if(background==null||background==s.task)return;
            android.view.WindowManager.LayoutParams attrs=(android.view.WindowManager.LayoutParams)a.field(focus,"mAttrs");
            if(!WindowPolicy.mayRestoreNormalFocus(attrs.type,(attrs.flags&android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)!=0,
                a.integer(background,"getWindowingMode"))||a.animating(background))return;
            focusExpanded(s);renderFrame(s);frameReport("NORMAL_FOCUS_REPAIRED");
            log.accept("NORMAL_FOCUS_REPAIRED task="+s.taskId+" backgroundTask="+a.field(background,"mTaskId"));
        }}catch(Exception|LinkageError error){log.accept("NORMAL_FOCUS_REPAIR_UNAVAILABLE "+FrameworkAccess.reason(error));}
    }
    boolean keepVisibleResumed(Object fragment,Object resuming){
        if(sessions.isEmpty())return false;
        Session s;
        try{s=sessionFor(a.call(fragment,"getTask"));}catch(Exception e){return false;}
        if(s==null||!WindowLifecycle.resumeCandidate(new WindowLifecycle.Resume(visible(s),s.restoring,s.closing,s.fullTransition,s.parked,s.prioritySuspended))||s.screenSuspended&&!continueHidden(s)||lock==null
            ||!("mini".equals(s.frame)||"normal".equals(s.frame)))return false;
        try{synchronized(lock){
            if(!visible(s)||a.call(fragment,"getTask")!=s.task||!s.lease.isBinderAlive()
                ||context.getSystemService(KeyguardManager.class).isKeyguardLocked()&&!continueHidden(s)
                ||a.bool(fragment,"shouldSleepOrShutDownActivities"))return false;
            Object top=a.call(fragment,"topRunningActivity");
            // Only retain a live visible top Activity. Native pause still owns
            // sleep, shutdown, occlusion, same-task page changes and teardown.
            boolean present=top!=null,finishing=present&&(Boolean)a.field(top,"finishing");
            boolean shown=present&&!finishing&&a.bool(top,"isVisibleRequested"),attached=shown&&a.bool(top,"attachedToProcess");
            int visibility=attached?(Integer)a.call(fragment,"getVisibility",new Class<?>[]{a.activity},resuming):-1;
            return WindowLifecycle.retainTop(new WindowLifecycle.Top(present,finishing,shown,attached,visibility));
        }}catch(Exception e){return false;}
    }
    boolean retainMiniActivity(Object activity,Object resuming){
        if(sessions.isEmpty())return false;
        try{
            Session s=sessionFor(a.call(activity,"getTask"));
            if(!visible(s)||!"mini".equals(s.frame))return false;
            Object fragment=a.call(activity,"getTaskFragment");
            return keepVisibleResumed(fragment,resuming)&&visible(s)
                &&a.call(fragment,"topRunningActivity")==activity;
        }catch(Exception e){return false;}
    }
    boolean shouldResumeMiniActivity(Object activity,Object resuming){
        if(!retainMiniActivity(activity,resuming))return false;
        try{
            // These are the remaining native shouldBeResumed checks. Do not
            // bypass pause completion, same-Activity guards or process compat.
            return (Boolean)a.call(activity,"shouldMakeActive",new Class<?>[]{a.activity},resuming)
                &&a.bool(activity,"canResumeByCompat");
        }catch(Exception e){return false;}
    }
    // Native Task resume is gated on focusability, also used to choose Home and
    // recents' running task. Keep that gate intact. A visible mini can use the
    // same native fragment lifecycle path without becoming the focused task.
    private void resumeMini(Session s){
        if(s==null||!WindowLifecycle.resumeMini(visibleResumeReady,visible(s),s.frame,s.transitioning,s.backRequested,pendingStart!=null))return;
        try{
            Object top=a.call(s.task,"topRunningActivity");if(top==null)return;
            String state=String.valueOf(a.call(top,"getState"));
            if(!WindowLifecycle.resumableState(state))return;
            Object fragment=a.call(top,"getTaskFragment");
            if(!shouldResumeMiniActivity(top,null))return;
            a.call(fragment,"resumeTopActivity",new Class<?>[]{a.activity,ActivityOptions.class,boolean.class},null,null,false);
        }catch(Exception e){
            // This optional continuation must not trigger fullscreen recovery.
            visibleResumeReady=false;log.accept("MINI_RESUME_UNAVAILABLE "+FrameworkAccess.reason(e));
        }
    }
    boolean ownsActivityToken(IBinder token)throws Exception{
        // A provisional share launch owns its request before any session exists.
        if(lock==null||sessions.isEmpty()&&shareLaunch.get()==null)return false;
        synchronized(lock){Object record=a.method(a.activity,"forTokenLocked",IBinder.class).invoke(null,token);
            ShareLaunch provisional=shareLaunch.get();
            return record!=null&&(owns(a.call(record,"getTask"))||provisional!=null&&!provisional.nativeFullscreen&&provisional.request==record);}
    }
    int clientMode(Object client,boolean activityToken)throws Exception{
        synchronized(lock){
            if(activityToken){
                Object record=a.method(a.activity,"forTokenLocked",IBinder.class).invoke(null,(IBinder)client);
                return record==null?1:WindowPolicy.clientMode((String)a.field(record,"packageName"));
            }
            if(client instanceof IInterface)return clientMode(clientOwner(client));
            return 1;
        }
    }
    int activityClientMode(Object record)throws Exception{return WindowPolicy.clientMode((String)a.field(record,"packageName"));}
    /** The live session whose task holds this client window, from one traversal that stops matching
     * once found; null when no session owns it. Hooks that map one outgoing object call this once. */
    Object clientOwner(Object client)throws Exception{
        if(sessions.isEmpty()||lock==null||!(client instanceof IInterface))return null;
        IBinder token=((IInterface)client).asBinder();Session[] found={null};
        synchronized(lock){for(Session s:sessions){
            if(s.restoring||found[0]!=null)continue;
            a.call(s.task,"forAllWindows",new Class<?>[]{Consumer.class,boolean.class},(Consumer<Object>)window->{
                if(found[0]!=null)return;
                try{if(((IInterface)a.field(window,"mClient")).asBinder().equals(token))found[0]=s;}
                catch(Exception e){throw new IllegalStateException(e);}
            },false);
        }}
        return found[0];
    }
    int clientMode(Object owner){return WindowPolicy.clientMode(owner instanceof Session s?s.pkg:null);}
    boolean ownsClientWindow(Object client)throws Exception{return clientOwner(client)!=null;}
    Object clientInsets(Object client,Object original)throws Exception{
        if(original==null||!(client instanceof IInterface))return original;
        return ownerInsets(clientOwner(client),original);
    }
    /** Map outgoing InsetsState for an already resolved owner (see clientOwner). */
    Object ownerInsets(Object owner,Object original)throws Exception{
        if(original==null||!(owner instanceof Session s))return original;
        synchronized(lock){
            if(s.restoring||s.placement==null)return original;
            Class<?> type=original.getClass();Object copy=type.getDeclaredConstructor(type,boolean.class).newInstance(original,true);
            Geometry.Placement p="normal".equals(s.frame)?normalForIme(s):s.placement;
            // The client is a local fullscreen viewport, not the physical display.
            a.call(copy,"setDisplayFrame",new Class<?>[]{Rect.class},new Rect(0,0,p.width(),p.height()));
            a.call(copy,"setDisplayCutout",new Class<?>[]{android.view.DisplayCutout.class},a.field(android.view.DisplayCutout.class,"NO_CUTOUT").get(null));
            int localBars=android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout();
            int count=a.integer(copy,"sourceSize");
            for(int n=0;n<count;n++){
                Object source=a.call(copy,"sourceAt",new Class<?>[]{int.class},n);
                int sourceType=a.integer(source,"getType");
                if((sourceType&localBars)!=0){
                    a.call(source,"setVisible",new Class<?>[]{boolean.class},false);
                    a.call(source,"setFrame",new Class<?>[]{Rect.class},new Rect());
                    a.call(source,"setVisibleFrame",new Class<?>[]{Rect.class},(Object)null);
                    continue;
                }
                if(sourceType!=android.view.WindowInsets.Type.ime())continue;
                Rect frame=(Rect)a.call(source,"getFrame");
                a.call(source,"setFrame",new Class<?>[]{Rect.class},logicalInsets(frame,p));
                Rect visible=(Rect)a.call(source,"getVisibleFrame");
                if(visible!=null)a.call(source,"setVisibleFrame",new Class<?>[]{Rect.class},logicalInsets(visible,p));
            }
            return copy;
        }
    }
    private static Rect logicalInsets(Rect r,Geometry.Placement p){
        return new Rect(Math.round((r.left-p.x())/p.scale()),Math.round((r.top-p.y())/p.scale()),
            Math.round((r.right-p.x())/p.scale()),Math.round((r.bottom-p.y())/p.scale()));
    }
    // Both WindowState and ActivityRecord input sinks obey the same task state.
    boolean blockContentTouch(Object container){
        if(sessions.isEmpty())return false;
        try{Session s=sessionFor(a.call(container,"getTask"));
            WindowLifecycle.Input input=WindowLifecycle.input(s!=null,s==null?0:priorityState(s));
            if(input!=WindowLifecycle.Input.STATE)return input==WindowLifecycle.Input.BLOCK;
            return WindowLifecycle.blocksContent(new WindowLifecycle.Content(s.parked,s.restoring,s.prioritySuspended,s.screenSuspended,
                s.rotation.active(),s.closing,s.frame,s.startMask!=null&&s.startMask.holding()));
        }catch(Exception e){return false;}
    }
    /* SystemUI's outside-dismiss overlays arrive asynchronously (frame IPC, addView, first draw)
     * and are GONE during non-entry transitions. Until they cover the screen again, tasks behind a
     * normal window would take the touch. Gate those background tasks here, in the same input
     * update that presents the window, for the entry/transition plus a short grace period. */
    private static final long TOUCH_GUARD_GRACE_MS=250,TOUCH_GUARD_MAX_MS=3000;
    private volatile boolean touchGuarded;
    private volatile Session touchGuardOwner;
    private volatile long touchGuardSince,touchGuardUntil;
    private static boolean normalShown(Session s){
        return s!=null&&!s.restoring&&!s.closing&&!s.fullTransition&&!s.parked&&!s.screenSuspended&&!s.prioritySuspended&&"normal".equals(s.frame);
    }
    private static boolean entryGuard(Session s){
        return normalShown(s)&&(s.entering||s.transitioning||s.cover!=null&&!s.coverFullscreen);
    }
    /** Called with every frame sent to SystemUI: start the cap timer, or the grace period once the guard ends. */
    private void trackTouchGuard(){
        if(handler==null)return;
        Session owner=active;
        boolean guarded=entryGuard(owner);long now=SystemClock.uptimeMillis();
        if(WindowPolicy.startsTouchGuard(owner==touchGuardOwner,guarded,touchGuarded)){
            touchGuardSince=now;touchGuardUntil=0;
            handler.postDelayed(this::refreshTouchGuard,TOUCH_GUARD_MAX_MS+16);
        }else if(!guarded&&touchGuarded){
            touchGuardUntil=now+TOUCH_GUARD_GRACE_MS;handler.postDelayed(this::refreshTouchGuard,TOUCH_GUARD_GRACE_MS+16);
        }
        touchGuardOwner=normalShown(owner)?owner:null;
        touchGuarded=guarded;
    }
    /** Re-run native input-window population so an expired guard releases the background. */
    private void refreshTouchGuard(){
        if(lock==null)return;
        try{synchronized(lock){a.call(wms,"requestTraversal");}}
        catch(Exception error){log.accept("TOUCH_GUARD_REFRESH_FAILED "+FrameworkAccess.reason(error));}
    }
    /** WindowState.canReceiveTouchInput: false for other tasks on the display while a normal window enters. */
    boolean blockBackgroundTouch(Object window){
        Session s=active;
        if(!normalShown(s))return false;
        long now=SystemClock.uptimeMillis();
        // Before the first frame of this guard reports, the guard has just started.
        boolean reported=touchGuardOwner==s;
        if(!WindowPolicy.backgroundTouchGuard(true,entryGuard(s),reported&&touchGuarded,touchGuardSince,
            reported?touchGuardUntil:0,now,TOUCH_GUARD_MAX_MS))return false;
        try{
            if(priorityState(s)!=0)return false;
            Object task=a.call(window,"getTask");
            return task!=null&&task!=s.task&&sessionFor(task)==null
                &&a.call(task,"getDisplayContent")==a.call(s.task,"getDisplayContent");
        }catch(Exception e){return false;}
    }
    private boolean suppressTransition(Session s){
        return s.restoring?!s.restoreForeground||s.fullTransition:!s.fullTransition||s.closing;
    }
    // Before native display collection: hide before it can capture/reparent the old geometry.
    void displayTransitionCollecting(Object transition,Object container){
        if(lock==null||handler==null||!a.dc.isInstance(container))return;
        try{synchronized(lock){
            for(Session s:sessions){
            if(!visible(s)||s.restoring||s.parked||s.closing||s.fullTransition
                ||a.call(s.task,"getDisplayContent")!=container)continue;
            if(!(Boolean)a.call(transition,"isCollecting")||!s.rotationTransitions.add(transition))continue;
            beginRotation(s);
            try{a.call(transition,"addTransitionEndedListener",new Class<?>[]{Runnable.class},(Runnable)()->handler.post(()->{
                synchronized(lock){s.rotationTransitions.remove(transition);queueRotationProbe(s,0);}
            }));}catch(Exception e){s.rotationTransitions.remove(transition);log.accept("ROTATION_END_LISTENER_UNAVAILABLE "+FrameworkAccess.reason(e));}
            }
        }}catch(Throwable e){log.accept("ROTATION_HIDE_FAILED "+FrameworkAccess.reason(e));}
    }
    // Also covers display reconfiguration outside a Shell transition. Signature is optional/preflighted.
    void displayConfigurationChanging(Object display,Configuration next){
        if(lock==null||handler==null||next==null)return;
        try{synchronized(lock){
            for(Session s:sessions){
            if(!visible(s)||s.restoring||s.parked||s.closing||s.fullTransition
                ||a.call(s.task,"getDisplayContent")!=display)continue;
            Object old=a.windowConfig(display,true),changed=a.field(next,"windowConfiguration");
            int from=a.integer(old,"getRotation"),to=a.integer(changed,"getRotation");
            Rect before=(Rect)a.call(old,"getBounds"),after=(Rect)a.call(changed,"getBounds");
            if(from!=to&&from>=0&&to>=0||!before.equals(after)&&!after.isEmpty())beginRotation(s);
            }
        }}catch(Throwable e){log.accept("ROTATION_CONFIG_GUARD_FAILED "+FrameworkAccess.reason(e));}
    }
    private void beginRotation(Session s){
        if(s.prioritySuspended)return;
        try{
            if("normal".equals(s.frame)&&s.placement!=null&&s.cover==null)beginCover(s,s.placement,s.placement,false);
            if(s.rotationMask==null||!s.rotationMask.holding()){
                TaskVisibilityGuard previous=s.rotationMask,next=new TaskVisibilityGuard(a,s.task);
                next.hide();s.rotationMask=next;
                if(previous!=null)previous.continueWith(next);
            }else s.rotationMask.hide();
            s.rotation.begin(SystemClock.elapsedRealtime());
            s.geometryDirty=true;
            if(s.decoration!=null)s.decoration.hide(s.task);
            frameReport("ROTATION_HIDDEN");queueRotationProbe(s,32);
            log.accept("ROTATION_HIDDEN task="+s.taskId+" frame="+s.frame);
        }catch(Throwable e){rotationFailure(s,e);}
    }
    private void queueRotationProbe(Session s,long delay){
        if(s.rotationProbeQueued||!s.rotation.active()||s.rotation.fading())return;
        s.rotationProbeQueued=true;
        handler.postDelayed(()->{synchronized(lock){s.rotationProbeQueued=false;probeRotation(s);}},delay);
    }
    private WindowPresentation.Alpha presentationState(Session s){
        return new WindowPresentation.Alpha(s.prioritySuspended,s.priorityHideTask,s.parked,s.screenSuspended,
            s.restoring,s.restoreForeground,s.startMask!=null&&s.startMask.holding(),
            s.rotationMask!=null&&s.rotationMask.holding(),s.cover!=null&&s.cover.mask.holding(),
            s.cover!=null&&s.cover.exiting(),s.opacity,s.rotation.alpha(SystemClock.elapsedRealtime()));
    }
    float presentationAlpha(Session s){return WindowPresentation.alpha(presentationState(s));}
    private TaskVisibilityGuard presentationMask(Session s){
        if(s.rotationMask!=null&&s.rotationMask.holding())return s.rotationMask;
        if(s.startMask!=null&&s.startMask.holding())return s.startMask;
        return s.cover!=null&&s.cover.mask.holding()?s.cover.mask:s.startMask;
    }
    private void probeRotation(Session s){
        if(!s.rotation.active()||s.rotation.fading())return;
        try{
            if(!sessions.contains(s)||s.restoring){s.rotation.clear();return;}
            if(find(s.taskId)!=s.task||!a.bool(s.task,"isAttached")){
                endRotation(s,false);return;
            }
            long now=SystemClock.elapsedRealtime();
            if(s.prioritySuspended||s.screenSuspended||!context.getSystemService(PowerManager.class).isInteractive()
                ||context.getSystemService(KeyguardManager.class).isKeyguardLocked()){
                s.rotation.defer(now);queueRotationProbe(s,250);return;
            }
            boolean overlap=s.startMask!=null&&s.startMask.holding();
            Object display=a.call(s.task,"getDisplayContent");
            if(display==null){endRotation(s,false);return;}
            Object controller=a.field(s.task,"mTransitionController");
            boolean nativeBusy=a.presentationLeashed(s.task)
                ||(Boolean)a.call(controller,"inTransition",new Class<?>[]{a.wc},display);
            boolean changing=a.bool(display,"isRotationChanging");
            Rect bounds=new Rect((Rect)a.call(display,"getBounds")),safe=safeArea(display);
            int rotation=a.integer(display,"getRotation");
            boolean ready=!overlap&&!nativeBusy&&!changing&&s.rotationTransitions.isEmpty();
            // A missing/aborted callback must never leave a live task transparent forever.
            if(s.rotation.expired(now)&&!overlap){
                log.accept("ROTATION_TIMEOUT_VISIBLE task="+s.taskId);
                endRotation(s,true);return;
            }
            String sample=rotation+"/"+bounds+"/"+safe;
            if(!s.rotation.sample(now,ready,sample)){queueRotationProbe(s,32);return;}
            if(visible(s)){
                refresh(s);
                Geometry.Placement p=s.placement;
                if(s.geometryDirty||p==null||!new Rect(0,0,p.width(),p.height()).equals(a.call(s.task,"getBounds"))){
                    queueRotationProbe(s,32);return;
                }
            }
            // Restore wrapper alphas with the task still at zero. The first visible frame
            // uses the refreshed geometry; its native logical canvas never flashes onscreen.
            if(s.rotationMask!=null)s.rotationMask.transfer();
            int generation=s.rotation.startFade(SystemClock.elapsedRealtime());
            renderFrame(s);frameReport("ROTATION_APPEARING");
            frames.postFrameCallback(new Choreographer.FrameCallback(){
                public void doFrame(long time){
                    synchronized(lock){
                        if(!s.rotation.active()||s.rotation.generation()!=generation)return;
                        try{
                            if(!sessions.contains(s)||s.restoring){s.rotation.clear();return;}
                            long clock=SystemClock.elapsedRealtime();
                            if(s.prioritySuspended||s.screenSuspended||a.presentationLeashed(s.task)){
                                beginRotation(s);return;
                            }
                            renderFrame(s);frameReport("ROTATION_APPEARING");
                            if(s.rotation.alpha(clock)<1){frames.postFrameCallback(this);return;}
                            s.rotation.clear();renderFrame(s);report("ROTATION_VISIBLE");
                        }catch(Throwable e){rotationFailure(s,e);}
                    }
                }
            });
        }catch(Throwable e){rotationFailure(s,e);}
    }
    private void endRotation(Session s,boolean visible){
        s.rotation.clear();s.rotationTransitions.clear();
        if(s.rotationMask!=null){
            try{if(s.rotationMask.holding())s.rotationMask.transfer();
                s.rotationMask.afterTransaction(visible?presentationAlpha(s):0);
            }catch(Throwable e){log.accept("ROTATION_RELEASE_FAILED "+FrameworkAccess.reason(e));}
        }
        if(visible)try{
            // Clear the pending zero as well as the immediate surface. A native leash
            // can prevent apply() from rewriting the pending transaction after timeout.
            a.opacity(s.task,presentationAlpha(s));
            if(!a.presentationLeashed(s.task)){refresh(s);renderFrame(s);}
            else try(android.view.SurfaceControl.Transaction t=new android.view.SurfaceControl.Transaction()){
                android.view.SurfaceControl surface=(android.view.SurfaceControl)a.field(s.task,"mSurfaceControl");
                if(surface!=null&&surface.isValid())t.setAlpha(surface,presentationAlpha(s)).apply();
            }
            frameReport("ROTATION_VISIBLE");
        }catch(Throwable e){log.accept("ROTATION_VISIBLE_FAILED "+FrameworkAccess.reason(e));}
    }
    void rotationFailure(Session s,Throwable e){
        log.accept("ROTATION_GUARD_FAILED "+FrameworkAccess.reason(e));
        endRotation(s,true);
    }
    void collected(Object transition,Object container){
        if(sessions.isEmpty())return;
        for(Session s:sessions)if(suppressTransition(s)&&belongsTo(container,s.task)){
            try{a.call(transition,"setNoAnimation",new Class<?>[]{a.wc},container);}
            catch(Exception e){log.accept("NO_ANIMATION_FAILED "+FrameworkAccess.reason(e));}
            return;
        }
    }
    private boolean belongsTo(Object container,Object task){
        try{return container==task||container!=null&&(Boolean)a.call(container,"isDescendantOf",new Class<?>[]{a.wc},task);}
        catch(Exception e){return false;}
    }
    void transitionReady(Object transition){
        if(lock==null||sessions.size()<2)return;
        try{synchronized(lock){
            Session s=active;
            if(s==null||s.restoring||s.closing||s.fullTransition||pendingStart!=null)return;
            if(a.call(s.task,"topRunningActivity")==null)returnFromTask(s,true);
        }}catch(Exception e){log.accept("LINKED_READY_FAILED "+FrameworkAccess.reason(e));}
    }
    // Native mode/bounds are already freeform when Shell receives this transaction.
    // Keep retained tasks invisible and repair any late Shell alpha/position reset.
    void transitionBuilt(Object transition,Object info,android.view.SurfaceControl.Transaction finish){
        if(lock==null||sessions.isEmpty()&&backPreparation==null)return;
        try{synchronized(lock){
            java.util.List<?> targets=(java.util.List<?>)a.field(transition,"mTargets");
            java.util.List<?> changes=(java.util.List<?>)a.call(info,"getChanges");
            if(targets.size()!=changes.size())return;
            android.view.SurfaceControl.Transaction start=(android.view.SurfaceControl.Transaction)a.field(transition,"mStartTransaction");
            BackPreparation prepared=backPreparation;
            if(prepared!=null)for(int i=0;i<targets.size();i++){
                Object container=a.field(targets.get(i),"mContainer"),change=changes.get(i);
                if(!belongsTo(container,prepared.task))continue;
                android.view.SurfaceControl leash=(android.view.SurfaceControl)a.call(change,"getLeash");
                prepared.mask.inTransaction(start,leash);prepared.mask.inTransaction(finish,leash);
                Runnable repair=()->repairBackMask(prepared);
                start.addTransactionCommittedListener(handler::post,repair::run);finish.addTransactionCommittedListener(handler::post,repair::run);
            }
            Session expanded=active;
            if(expanded!=null&&"normal".equals(expanded.frame)){
                // buildFinishTransaction runs after collection on this framework.
                // Listener failure must never abort the surface-mask/animation loop.
                if((Boolean)a.call(transition,"isCollecting")){
                    try{a.call(transition,"addTransitionEndedListener",new Class<?>[]{Runnable.class},(Runnable)()->handler.post(()->repairNormalFocus(expanded)));}
                    catch(Exception error){log.accept("FOCUS_END_LISTENER_SKIPPED "+FrameworkAccess.reason(error));}
                }
            }
            java.util.ArrayList<Object> steadyMiniChanges=new java.util.ArrayList<>();
            for(Session s:sessions){
                if(!suppressTransition(s))continue;
                TaskVisibilityGuard mask=presentationMask(s);
                boolean touched=false;
                for(int i=0;i<targets.size();i++){
                    Object container=a.field(targets.get(i),"mContainer"),change=changes.get(i);
                    if(!belongsTo(container,s.task))continue;
                    if(container==s.task&&steadyMiniChange(s,transition,change,targets))steadyMiniChanges.add(change);
                    a.call(change,"setFlags",new Class<?>[]{int.class},a.integer(change,"getFlags")|a.noAnimationFlag);
                    android.view.SurfaceControl leash=(android.view.SurfaceControl)a.call(change,"getLeash");
                    if(mask!=null&&mask.holding()){
                        mask.inTransaction(start,leash);mask.inTransaction(finish,leash);
                    }else if(s.parked&&leash!=null&&leash.isValid()){
                        start.setAlpha(leash,0);finish.setAlpha(leash,0);
                        // Restore wrapper leashes after commit; the task itself stays masked.
                        finish.addTransactionCommittedListener(handler::post,()->{
                            synchronized(lock){
                                try(android.view.SurfaceControl.Transaction tx=new android.view.SurfaceControl.Transaction()){
                                    if(leash.isValid())tx.setAlpha(leash,1);
                                    Session owner=sessionFor(s.task);
                                    android.view.SurfaceControl surface=(android.view.SurfaceControl)a.field(s.task,"mSurfaceControl");
                                    if(owner!=null&&surface!=null&&surface.isValid())
                                        tx.setAlpha(surface,presentationAlpha(owner));
                                    tx.apply();
                                }catch(Exception e){failure(s,e);}
                            }
                            repairTransition(s,mask);
                        });
                    }
                    touched=true;
                }
                if(s.parked||mask!=null&&mask.holding()){
                    android.view.SurfaceControl surface=(android.view.SurfaceControl)a.field(s.task,"mSurfaceControl");
                    if(surface!=null&&surface.isValid()){start.setAlpha(surface,0);finish.setAlpha(surface,0);}
                }
                if(touched&&!s.restoring&&!s.parked&&!s.screenSuspended&&!s.prioritySuspended&&s.placement!=null
                    &&(mask==null||!mask.holding())){
                    // Native finish resets task position/scale/crop even for a no-animation
                    // foreground transition. Put the owned presentation into that same
                    // transaction instead of allowing an identity frame before a later tick.
                    Rect parent=(Rect)a.call(s.parent,"getBounds");
                    Rect crop=s.lastSurfaceCrop==null?new Rect(0,0,s.placement.width(),s.placement.height()):s.lastSurfaceCrop;
                    float radius=surfaceRadius(s,s.placement,crop);
                    a.presentation(s.task,finish,s.placement,parent,crop,radius,presentationAlpha(s));
                }
                // A display-only target also receives task alpha=0 above. Repair its
                // late commits even when the task was not an independent Shell change.
                if(touched||mask!=null&&mask.holding()){
                    start.addTransactionCommittedListener(handler::post,()->repairTransition(s,mask));
                    finish.addTransactionCommittedListener(handler::post,()->repairTransition(s,mask));
                }
            }
            // TransitionInfo.getChanges returns its actual ArrayList; onTransitionReady
            // marshals this same info after this hook. Native mTargets/completion remain
            // intact. Shell must not reset a stationary mini to its logical (0,0) bounds
            // merely because another application is opening/closing in the foreground.
            // Keep at least one unowned change, including every dependent child change.
            if(!steadyMiniChanges.isEmpty()&&steadyMiniChanges.size()<changes.size()){
                changes.removeAll(steadyMiniChanges);
                log.accept("MINI_STEADY_TRANSITION_RETAINED count="+steadyMiniChanges.size());
            }
        }}catch(Throwable e){log.accept("LINKED_TX_FAILED "+FrameworkAccess.reason(e));}
    }
    private boolean steadyMiniChange(Session s,Object transition,Object change,java.util.List<?> targets){
        try{
            int transitionType=(Integer)a.field(transition,"mType");
            boolean foregroundTransition=transitionType>=1&&transitionType<=4;
            boolean busy=s.entering||s.closing||s.restoring||s.parked||s.prioritySuspended||s.screenSuspended||s.transitioning||s.fullTransition
                ||s.geometryDirty||pendingStart!=null||s.startMask!=null&&s.startMask.holding()
                ||a.bool(a.field(s.task,"mSurfaceAnimator"),"hasLeash");
            boolean dependent=false;
            for(Object target:targets){
                Object container=a.field(target,"mContainer");
                if(container!=s.task&&belongsTo(container,s.task)){dependent=true;break;}
            }
            Object display=a.call(s.task,"getDisplayContent"),top=a.call(s.task,"topRunningActivity");
            Rect bounds=(Rect)a.call(s.task,"getBounds"),displayBounds=display==null?null:(Rect)a.call(display,"getBounds");
            boolean geometry=s.placement!=null&&displayBounds!=null&&top!=null&&s.displayW==displayBounds.width()&&s.displayH==displayBounds.height()
                &&new Rect(0,0,s.placement.width(),s.placement.height()).equals(bounds)
                &&java.util.Objects.equals(s.safeBounds,safeArea(display))
                &&java.util.Objects.equals(s.miniBounds,safeArea(display,false))
                &&(s.orientationBaseline&&top==s.orientationActivity||WindowPolicy.landscape(a.integer(top,"getRequestedOrientation"),s.landscape)==s.landscape)
                &&(s.placement.width()>s.placement.height())==s.landscape
                &&a.call(change,"getStartAbsBounds").equals(a.call(change,"getEndAbsBounds"));
            return WindowPolicy.steadyMiniTransition(visible(s)&&"mini".equals(s.frame)&&foregroundTransition,busy,
                a.integer(change,"getMode"),geometry,a.integer(change,"getStartRotation")==a.integer(change,"getEndRotation"),
                dependent,a.call(change,"getSnapshot")!=null);
        }catch(Exception|LinkageError unavailable){return false;}
    }
    private void repairTransition(Session s,TaskVisibilityGuard mask){
        try{synchronized(lock){
            Session owner=sessionFor(s.task);
            if(mask!=null){
                TaskVisibilityGuard next=owner==null?null:presentationMask(owner);
                if(next!=null&&next!=mask&&next.holding())mask.continueWith(next);
                else mask.afterTransaction(owner==null?1:presentationAlpha(owner));
            }
            if(owner!=null&&!owner.restoring&&!a.animating(owner.task))renderFrame(owner);
        }}catch(Throwable e){if(sessions.contains(s))failure(s,e);else log.accept("LINKED_LATE_TX_FAILED "+FrameworkAccess.reason(e));}
    }
    void beforeBackInner(Object task,Object transition){
        Session s=sessionFor(task);if(s==null||!s.backRequested||s.restoreForeground)return;
        if(transition!=null){collected(transition,task);requesting(transition,task);}
    }
    void requesting(Object transition,Object task){
        Session s=sessionFor(task);if(s==null||!s.backRequested||s.restoreForeground||transition==null)return;
            try{
                // Task.moveTaskToBack creates TRANSIT_TO_BACK=4 for this request.
                // The task is already transparent. Shell can finish this invisible
                // transition without constructing a second closing animation.
                // Do not disable a shared opening transition from another task.
                if((Integer)a.field(transition,"mType")==4&&(a.integer(transition,"getFlags")&a.invisibleFlag)==0){
                    a.call(transition,"addFlag",new Class<?>[]{int.class},a.invisibleFlag);
                    log.accept("CLOSE_TRANSITION_INVISIBLE task="+s.taskId+" flag="+a.invisibleFlag);
                }
            }catch(Exception e){log.accept("CLOSE_TRANSITION_FLAG_FAILED "+FrameworkAccess.reason(e));}
    }
    void afterBackInner(Object task){
        Session s=sessionFor(task);if(s==null||!s.backRequested||s.restoreForeground)return;
        s.backComplete=true;
        log.accept("CLOSE_BACK_COMPLETE task="+s.taskId);
    }
    void bounds(Object task, Rect bounds) {
        Session s=sessionFor(task);
        if(s!=null&&!s.restoring&&s.placement!=null)bounds.set(0,0,s.placement.width(),s.placement.height());
    }
    void prepared(Object task) {
        Session s=sessionFor(task);if(s==null||lock==null)return;
        try{synchronized(lock){
            if(!s.restoring&&!s.parked&&!s.refreshingGeometry&&!a.presentationLeashed(task)){
                Object display=a.call(task,"getDisplayContent");
                Rect bounds=display==null?null:(Rect)a.call(display,"getBounds");
                // A native surface traversal may safely update position/scale. It
                // must not change Activity bounds, focus, or visibility mid-traversal.
                if(!s.transitioning&&(s.geometryDirty||bounds!=null&&(s.displayW!=bounds.width()||s.displayH!=bounds.height()))){
                    Geometry.Placement p=s.placement;
                    Rect canvas=(Rect)a.call(task,"getBounds");
                    int expectedWidth=s.landscape?Math.round(s.logicalShort*(16f/9f)):s.logicalShort;
                    int expectedHeight=s.landscape?s.logicalShort:Math.round(s.logicalShort*(16f/9f));
                    if(p!=null&&canvas.equals(new Rect(0,0,expectedWidth,expectedHeight)))refresh(s,true);
                    else configurationChanged(task);
                }
            }
            apply(s);
        }}catch(Throwable e){failure(s,e);}
    }
    // Serialize every frame with native WM traversal, including future control callers.
    private void renderFrame(Session s) throws Exception {
        synchronized(lock){
            a.beginFrame();boolean success=false;
            try{apply(s);success=true;}finally{a.endFrame(success);}
        }
    }
    private void surfaceVisible(Session s,boolean visible)throws Exception{presentation.visible(s,visible);}
    private void apply(Session s) throws Exception {
        boolean managed=sessions.contains(s);
        WindowPresentation.Route admission=WindowPresentation.admission(managed,managed&&a.bool(s.task,"isAttached"),s.restoring,s.placement!=null);
        if(admission==WindowPresentation.Route.RESTORING){
            if(!s.restoreForeground&&s.startMask!=null)s.startMask.hold();
            return;
        }
        if(admission!=WindowPresentation.Route.PROBE_NATIVE)return;
        updatePriorityState(s);
        if(s.decoration!=null)s.decoration.updateBacking(s.task,s.placement.width(),s.placement.height(),
            WindowPresentation.backing(s.prioritySuspended,s.parked,s.screenSuspended));
        WindowPresentation.Route route=WindowPresentation.gate(new WindowPresentation.Facts(true,true,false,true,
            s.prioritySuspended,s.priorityHideTask,s.parked,s.screenSuspended,
            s.startMask!=null&&s.startMask.holding(),s.rotation.active()&&!s.rotation.fading()));
        presentation.apply(s,route);
    }
    void updateDecoration(Session s,Geometry.Placement p,float density,boolean structural)throws Exception{
        if(s.prioritySuspended){if(s.decoration!=null)s.decoration.hide(s.task);return;}
        if(s.decoration!=null){
            boolean wasPresented=s.presented;
            s.decoration.dimAmount(windowDim);
            float decorationAlpha=s.barAlpha*s.rotation.alpha(SystemClock.elapsedRealtime());
            boolean drawn=s.cover==null?s.decoration.update(s.task,s.parent,p,density,s.displayW>s.displayH,decorationAlpha,structural)
                :s.cover.decorate(s.decoration,p,density,s.displayW>s.displayH,decorationAlpha,structural);
            s.presented=wasPresented||drawn;
            if(s.presented&&!wasPresented)handler.post(()->{
                if(!visible(s)||s.restoring||s.closing)return;
                log.accept("PRESENTED task="+s.taskId+" elapsedMs="+(SystemClock.elapsedRealtime()-s.startedAt));
                if(s.entering&&!s.entryStarted){
                    s.entryStarted=true;
                    /*
                     * 出生即迷你的会话（卡片上滑）没有后续的 MINI 指令，这里就是它唯一
                     * 的「入场结束」。barAlpha 同时驱动把手条和 dim 层：给 1 会让整块
                     * 屏幕按 dimAmount 变暗（迷你窗太小，暗区全露在外面），用户看到的就是
                     * 「小窗以外全黑了而且点不动」。所以按最终形态取，迷你窗一律 0。
                     */
                    if(s.cover!=null){s.entering=false;s.transitioning=false;s.opacity=1;s.barAlpha="mini".equals(s.frame)?0:1;try{synchronized(lock){renderFrame(s);}}catch(Exception e){failure(s,e);}}
                    else visibility(s,true);
                }
                else report("PRESENTED");
            });
        }
    }
    void imePlacementChanged(Session s){handler.post(()->{if(visible(s))frameReport("IME_PLACEMENT");});}
    void presentationCropChanged(Session s,Rect crop,Object main){
        if(!crop.equals(s.lastSurfaceCrop)){
            s.lastSurfaceCrop=new Rect(crop);
            log.accept("VISIBLE_WINDOW_CROP task="+s.taskId+" crop="+crop+" main="+main);
        }
    }
    private float surfaceRadius(Session s,Geometry.Placement p,Rect crop)throws Exception{
        return WindowPresentation.radius(p,a.config(s.task,false).densityDpi/160f,s.defaultScale,s.radiusFactor,crop.height());
    }
    void configurationChanged(Object task) {
        Session changed=sessionFor(task);
        if(changed!=null&&!changed.refreshingGeometry)changed.geometryDirty=true;
        if (!owns(task) || handler == null || refreshQueued) return;
        refreshQueued = true;
        handler.post(() -> {
            refreshQueued = false;
            for(Session s:sessions)if(visible(s)&&!s.restoring)try{refresh(s);}catch(Throwable e){failure(s,e);}
        });
    }
    private void failure(Session s, Throwable e) {
        // Disarm transform hooks before posting recovery; never throw into a WM caller.
        s.restoring = true;
        if (handler != null) handler.post(() -> { if (sessions.contains(s)) recover("适配异常：" + FrameworkAccess.reason(e)); });
    }
    private boolean continueHidden(Session s){
        if(s==null)return false;
        return WindowLifecycle.continuation(new WindowLifecycle.Continuation(continuationReady,continuation!=null,keepEdgeAwake,
            visible(s),s.restoring,s.closing,s.parked,s.fullTransition,s.prioritySuspended,s.edgeHidden,s.frame,s.uid))
            &&s.lease!=null&&s.lease.isBinderAlive();
    }
    boolean continueHiddenTask(Object fragment){
        try{synchronized(lock){return !(Boolean)a.field(atm,"mShuttingDown")&&continueHidden(sessionFor(a.call(fragment,"getTask")));}}
        catch(Exception e){return false;}
    }
    boolean continueHiddenActivity(Object record){
        try{synchronized(lock){Object task=a.call(record,"getTask");Session s=sessionFor(task);
            return !(Boolean)a.field(atm,"mShuttingDown")&&continueHidden(s)&&a.call(task,"topRunningActivity")==record
                &&!(Boolean)a.field(record,"finishing")&&a.bool(record,"attachedToProcess");}}
        catch(Exception e){return false;}
    }
    private void updateContinuation(){
        if(continuation==null||lock==null)return;
        java.util.Map<Integer,String> desired=new java.util.HashMap<>();
        synchronized(lock){for(Session s:sessions)if(continueHidden(s))desired.put(s.uid,s.pkg);}
        continuation.update(desired);
    }
    private final java.util.concurrent.atomic.AtomicBoolean priorityQueued=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean priorityReportQueued=new java.util.concurrent.atomic.AtomicBoolean();
    void priorityWindowsChanged(){
        if(handler==null||lock==null||sessions.isEmpty()||!priorityQueued.compareAndSet(false,true))return;
        handler.post(()->{
            priorityQueued.set(false);
            try{synchronized(lock){
                boolean changed=false;
                for(Session s:sessions)if(!s.restoring&&updatePriorityState(s)){renderFrame(s);changed=true;}
                if(changed){a.call(wms,"updateFocusedWindowLocked",new Class<?>[]{int.class,boolean.class},0,true);
                    a.call(wms,"requestTraversal");}
            }}catch(Exception error){log.accept("SYSTEM_PRIORITY_FAILED "+FrameworkAccess.reason(error));}
        });
    }
    // 0=native exception/no match; 1=other UID inside this task; 2=external task/window.
    static int systemPriority(Object display,Object task,int uid,Object windowDisplay,Object windowTask,int windowUid,boolean visible){
        return WindowLifecycle.priority(display!=null&&display==windowDisplay,task==windowTask,uid,windowUid,visible);
    }
    private int priorityState(Session s)throws Exception{
        if(a.overlayHidingWindows==null||wms==null)return 0;
        Object display=a.call(s.task,"getDisplayContent");int result=0;
        for(Object window:(java.util.List<?>)a.overlayHidingWindows.get(wms)){
            result=Math.max(result,systemPriority(display,s.task,s.uid,a.call(window,"getDisplayContent"),
                a.call(window,"getTask"),a.integer(window,"getOwningUid"),a.bool(window,"isVisible")));
            if(result==2)break;
        }
        return result;
    }
    private boolean updatePriorityState(Session s)throws Exception{
        WindowLifecycle.Priority priority=WindowLifecycle.priority(priorityState(s));boolean blocked=priority.blocked(),hide=priority.hide();
        if(!priority.changed(s.prioritySuspended,s.priorityHideTask)){
            if(blocked&&a.bool(s.task,"isFocusable")==hide)
                a.call(s.task,"setFocusable",new Class<?>[]{boolean.class},!hide);
            return false;
        }
        boolean wasBlocked=s.prioritySuspended;
        if(blocked&&!wasBlocked)s.priorityWasFocusable=a.bool(s.task,"isFocusable");
        s.prioritySuspended=blocked;s.priorityHideTask=hide;
        // A permission Activity inside a mini needs native key focus, unlike an external installer.
        boolean focusable=priority.focusable(s.priorityWasFocusable,s.parked,s.frame);
        if(a.bool(s.task,"isFocusable")!=focusable)
            a.call(s.task,"setFocusable",new Class<?>[]{boolean.class},focusable);
        if(blocked){
            s.entryControl=null;
            if(s.decoration!=null)s.decoration.hide(s.task);
            if(!hide){
                // A permission Activity can share the task: never keep its parent's alpha mask.
                if(s.startMask!=null){s.startMask.release(1);s.startMask=null;}
                if(s.rotationMask!=null){s.rotationMask.release(1);s.rotationMask=null;s.rotation.clear();}
                if(s.cover!=null){
                    TaskCover cover=s.cover;s.cover=null;s.coverFollowing=false;coverTasks.remove(s.task);
                    cover.ownerAlpha(1);cover.close();
                }
            }
        }else s.priorityFocusUntil=SystemClock.uptimeMillis()+1000;
        log.accept((blocked?"SYSTEM_PRIORITY_SUSPEND":"SYSTEM_PRIORITY_RESUME")+" task="+s.taskId+" hideTask="+hide);
        // apply()/focus/input can see the change before the optional after-hook worker does.
        if(handler!=null&&priorityReportQueued.compareAndSet(false,true))handler.post(()->{
            priorityReportQueued.set(false);report("SYSTEM_PRIORITY_CHANGED");
        });
        return true;
    }
    // Screen/keyguard changes suspend presentation, never restore the fullscreen
    // configuration. Keep frame, placement, return chain and recovery journal.
    private void updateScreenState(Session s)throws Exception {
        boolean blocked=!context.getSystemService(PowerManager.class).isInteractive()
            ||context.getSystemService(KeyguardManager.class).isKeyguardLocked();
        if(s.screenSuspended==blocked)return;
        s.screenSuspended=blocked;
        if(!blocked){
            a.call(root,"ensureActivitiesVisible");a.call(root,"resumeFocusedTasksTopActivities");
        }
        if(a.bool(s.task,"isAttached")){renderFrame(s);a.call(wms,"requestTraversal");}
        log.accept((blocked?"SCREEN_SUSPEND":"SCREEN_RESUME")+" task="+s.taskId+" frame="+s.frame);
        report(blocked?"SCREEN_SUSPEND":"SCREEN_RESUME");
    }
    // Optional diagnostics must never enter the task recovery path.
    private void traceVisibleRuntime(Session s,Object display){
        try{
            Object top=a.call(s.task,"topRunningActivity");
            Object current=a.field(display,"mCurrentFocus");
            Object focused=a.focused.invoke(root);
            String runtime=s.frame+"/"+a.call(top,"getState")+"/input="+(current!=null&&a.call(current,"getTask")==s.task)
                +"/focusedTask="+(focused==null?-1:(Integer)a.field(focused,"mTaskId"));
            if(!runtime.equals(s.lastRuntimeState)){
                s.lastRuntimeState=runtime;
                log.accept("VISIBLE_RUNTIME task="+s.taskId+" state="+runtime+" focusable="+a.bool(s.task,"isFocusable"));
            }
        }catch(Exception|LinkageError e){
            if(!"diagnostic-unavailable".equals(s.lastRuntimeState))
                log.accept("VISIBLE_RUNTIME_UNAVAILABLE "+FrameworkAccess.reason(e));
            s.lastRuntimeState="diagnostic-unavailable";
        }
    }
    private void tick(Session s) {
        WindowLifecycle.Tick admission=WindowLifecycle.tick(sessions.contains(s),s.restoring,recovering,visible(s));
        if(admission==WindowLifecycle.Tick.STOP)return;
        try {
            if(admission==WindowLifecycle.Tick.HIDDEN){
                synchronized(lock){if(find(s.taskId)!=s.task||a.call(s.task,"topRunningActivity")==null){retire(s);return;}apply(s);}
                handler.postDelayed(()->tick(s),WindowLifecycle.Pause.HIDDEN.delay);return;
            }
            if (!s.lease.isBinderAlive()) { s.closing=true; recover("SystemUI 控制进程退出"); return; }
            synchronized (lock) {
                if(find(s.taskId)!=s.task||a.call(s.task,"topRunningActivity")==null){
                    if(sessions.size()>1&&!s.closing&&returnFromTask(s)){
                        if(active==s&&!s.restoring)handler.postDelayed(()->tick(s),250);
                    }else finishGroup(s,false,"应用任务已结束");
                    return;
                }
                updateScreenState(s);
                if(updatePriorityState(s))renderFrame(s);
                handler.post(this::updateContinuation);
                WindowLifecycle.Pause pause=WindowLifecycle.pause(s.prioritySuspended,s.screenSuspended);
                if(pause==WindowLifecycle.Pause.PRIORITY){renderFrame(s);a.call(wms,"requestTraversal");handler.postDelayed(()->tick(s),pause.delay);return;}
                if(pause==WindowLifecycle.Pause.SCREEN){
                    if(continueHidden(s)){resumeMini(s);traceVisibleRuntime(s,a.call(s.task,"getDisplayContent"));}
                    handler.postDelayed(()->tick(s),pause.delay);return;
                }
                Object display = a.call(s.task, "getDisplayContent");
                if (display == null) throw new IllegalStateException("任务已离开显示器");
                if(!a.animating(s.task))resumeMini(s);
                repairNormalFocus(s);traceVisibleRuntime(s,display);
                Rect b = (Rect) a.call(display, "getBounds");
                if ((!s.transitioning&&updateOrientation(s)) || s.displayW != b.width() || s.displayH != b.height()) { refresh(s); }
                else if (!s.transitioning && !a.animating(s.task)) { apply(s); a.call(wms, "requestTraversal"); }
            }
            if(SystemClock.elapsedRealtime()>=s.nextStatus) {s.nextStatus=SystemClock.elapsedRealtime()+1000;report("TICK");}
            handler.postDelayed(() -> tick(s), WindowLifecycle.Pause.LIVE.delay);
        } catch (Throwable e) { failure(s, e); }
    }

    private JSONObject encode(Session s)throws Exception{
        JSONObject o=new JSONObject().put("version",4).put("taskId",s.taskId).put("userId",s.userId)
            .put("package",s.pkg).put("token",s.token).put("component",s.component)
            .put("baseId",s.baseId==null?JSONObject.NULL:s.baseId).put("cropX",s.crop.x).put("cropY",s.crop.y)
            .put("mode",s.mode).put("bounds",s.bounds.flattenToString()).put("alwaysOnTop",s.alwaysOnTop)
            .put("topState",s.topState).put("forceTranslucent",s.forceTranslucent);
        if(s.originalConfig!=null){Parcel parcel=Parcel.obtain();try{
            s.originalConfig.writeToParcel(parcel,0);
            o.put("originalConfig",android.util.Base64.encodeToString(parcel.marshall(),android.util.Base64.NO_WRAP));
        }finally{parcel.recycle();}}
        return o;
    }
    private void writeJournal(java.util.List<Session> list)throws Exception{
        if(list.isEmpty()){journal.delete();return;}
        JSONArray entries=new JSONArray();for(Session s:list)entries.put(encode(s));
        byte[] bytes=new JSONObject().put("version",5).put("sessions",entries).toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>262144)throw new IOException("恢复记录过大");
        FileOutputStream stream=null;
        try{stream=journal.startWrite();stream.write(bytes);journal.finishWrite(stream);}
        catch(Exception e){if(stream!=null)journal.failWrite(stream);throw e;}
    }
    private void save(Session s)throws Exception{
        java.util.ArrayList<Session> next=new java.util.ArrayList<>(sessions);
        if(!next.contains(s))next.add(s);
        writeJournal(next);sessions=java.util.List.copyOf(next);
        handler.post(WindowEngine.this::updateContinuation);
    }
    private Session decode(JSONObject o)throws Exception{
        Session s=new Session();int version=o.getInt("version");
        s.taskId=o.getInt("taskId");s.userId=o.getInt("userId");
        s.pkg=o.getString("package");s.token=o.getString("token");s.component=o.getString("component");
        s.baseId=version==1?s.token:o.isNull("baseId")?null:o.getString("baseId");
        s.crop=version==1?null:new Point(o.getInt("cropX"),o.getInt("cropY"));
        if(o.has("originalConfig")){
            byte[] data=android.util.Base64.decode(o.getString("originalConfig"),android.util.Base64.NO_WRAP);
            Parcel parcel=Parcel.obtain();try{parcel.unmarshall(data,0,data.length);parcel.setDataPosition(0);
                s.originalConfig=Configuration.CREATOR.createFromParcel(parcel);
            }finally{parcel.recycle();}
        }
        s.mode=o.getInt("mode");s.bounds=Rect.unflattenFromString(o.getString("bounds"));
        s.topState=o.getInt("topState");s.alwaysOnTop=o.getBoolean("alwaysOnTop");
        s.forceTranslucent=version>=3&&o.getBoolean("forceTranslucent");s.restoreTranslucent=version>=3;
        if(version<1||version>4||!Wire.allowedPackage(s.pkg)||!Wire.validToken(s.token)
            ||ComponentName.unflattenFromString(s.component)==null
            ||!s.pkg.equals(ComponentName.unflattenFromString(s.component).getPackageName())||s.userId!=0||s.taskId<0
            ||(s.mode!=0&&s.mode!=1)||s.bounds==null||!s.bounds.isEmpty()||s.alwaysOnTop||(s.topState!=0&&s.topState!=2))
            throw new IOException("恢复记录不匹配");
        return s;
    }
    private java.util.List<Session> readJournal()throws Exception{
        byte[] bytes=journal.readFully();if(bytes.length>262144)throw new IOException("恢复记录过大");
        JSONObject doc=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
        if(doc.getInt("version")!=5)return java.util.List.of(decode(doc));
        JSONArray entries=doc.getJSONArray("sessions");
        if(entries.length()==0||entries.length()>MAX_LINKED_TASKS)throw new IOException("恢复任务数量异常");
        java.util.ArrayList<Session> result=new java.util.ArrayList<>();java.util.Set<Integer> ids=new java.util.HashSet<>();
        for(int n=0;n<entries.length();n++){
            Session s=decode(entries.getJSONObject(n));
            if(!ids.add(s.taskId))throw new IOException("恢复任务重复");
            result.add(s);
        }
        return java.util.List.copyOf(result);
    }
    private void forget(Session s)throws Exception{
        // 已经退出的会话不能继续挂在迷你窗列表里，否则控制条会留在屏幕上。
        removePinned(s);
        java.util.ArrayList<Session> next=new java.util.ArrayList<>(sessions);next.remove(s);
        writeJournal(next);sessions=java.util.List.copyOf(next);
        handler.post(WindowEngine.this::updateContinuation);
        if(audio!=null&&s.uid>=0)handler.post(()->{
            if(sessions.stream().noneMatch(other->other.uid==s.uid))audio.setMuted(s.uid,false);
        });
        // Successful foreground restoration already verified native fullscreen mode.
        // Release its history now: waiting for focused/drawn polling lets a quick
        // swipe into recents hide this task again as soon as it loses focus.
        // Background restores keep their history, including parked linked tasks.
        if(s.restoreForeground){
            history.removed(s.taskId);history.clearFullscreen(s.taskId);
            saveHistory();notifyRecents();
            log.accept("HISTORY_FULLSCREEN_RELEASED task="+s.taskId+" source=foreground-restore");
        }
        s.rotation.clear();s.rotationTransitions.clear();
        if(s.rotationMask!=null){
            if(s.rotationMask.holding())s.rotationMask.transfer();
            s.rotationMask.afterTransaction(1);
        }
        if(s.cover!=null&&!s.restoreForeground)endCover(s);
        if(s.decoration!=null)s.decoration.dispose();
        if(s.lease!=null&&s.death!=null)try{s.lease.unlinkToDeath(s.death,0);}catch(RuntimeException ignored){}
        endedTaskId=s.taskId;
    }
    private void holdRestoreMask(Session s,Object task)throws Exception{
        if(s.startMask==null||!s.startMask.holding()){
            TaskVisibilityGuard previous=s.startMask;
            TaskVisibilityGuard mask=new TaskVisibilityGuard(a,task);
            mask.hide();s.startMask=mask;
            if(previous!=null)previous.continueWith(mask);
        }else s.startMask.hold();
    }
    /** A non-resizable activity first launched in freeform freezes that viewport.
     * Restoring its Task alone does not release Android's activity size-compat cache.
     * Use the native reset only for floating caches, preserving pre-existing full-screen letterboxing. */
    void restoreFloatingCompat(Object task)throws Exception{
        a.call(task,"forAllActivities",new Class<?>[]{Consumer.class},(Consumer<Object>)activity->{
            try{
                Object policy=a.call(a.field(activity,"mAppCompatController"),"getSizeCompatModePolicy");
                Object insets=a.call(policy,"getAppCompatDisplayInsets");
                if(insets==null||!(Boolean)a.field(insets,"mIsFloating"))return;
                a.call(policy,"clearSizeCompatMode");
                Object remaining=a.call(policy,"getAppCompatDisplayInsets");
                if(remaining!=null&&(Boolean)a.field(remaining,"mIsFloating"))
                    throw new IllegalStateException("浮动页面兼容配置尚未释放");
                log.accept("FULLSCREEN_COMPAT_RELEASED task="+a.field(task,"mTaskId")
                    +" page="+a.field(activity,"mActivityComponent")+" bounds="+a.call(a.windowConfig(activity,false),"getBounds"));
            }catch(Exception e){throw new IllegalStateException(e);}
        });
    }
    private boolean recoveryStateReady(Session s)throws Exception{
        if(s.task!=null||!context.getSystemService(UserManager.class).isUserUnlocked())return false;
        Object recents=a.field(atm,"mRecentTasks");
        // Called under the WM lock: only inspect the native loaded flag. Calling
        // the loader here could invert its per-user load lock with the WM lock.
        return (Boolean)a.call(recents,"isRecentTasksLoaded",new Class<?>[]{int.class},s.userId);
    }
    private boolean releaseStaleRecovery(Session s,String reason)throws Exception{
        history.removed(s.taskId);history.clearFullscreen(s.taskId);saveHistory();notifyRecents();
        log.accept("RECOVERY_STALE_RELEASED task="+s.taskId+" reason="+reason);return true;
    }
    private boolean restoreTask(Session s,boolean foreground)throws Exception{
        boolean stateReady=recoveryStateReady(s);
        Object task=find(s.taskId);boolean attached=task!=null;
        if(task==null)task=a.lookup.invoke(root,s.taskId,1);
        WindowRecoveryPolicy.Eligibility eligibility=WindowRecoveryPolicy.eligibility(s.task!=null,stateReady,task!=null,
            task!=null&&identity(task,s),s.task==task,s.baseId!=null);
        switch(eligibility){
            case WAIT: return false;
            case COMPLETE: return true; // A live task already finished; unknown boot recents must still wait.
            case RELEASE_STALE: return releaseStaleRecovery(s,task==null?"missing-after-recents-loaded":"task-id-reused");
            case REJECT: throw new IllegalStateException("恢复任务身份已改变");
            case RESTORE: break;
        }
        boolean surfaceAttached=attached&&a.bool(task,"isAttached");
        if(surfaceAttached){
            Object display=a.call(task,"getDisplayContent");
            if(display==null||(Integer)a.field(display,"mDisplayId")!=0||!a.bool(task,"isRootTask")
                ||!a.bool(task,"isLeafTask")||!a.tda.isInstance(a.call(task,"getParent")))
                throw new IllegalStateException("恢复任务层级已改变");
            if(a.integer(task,"getRelativeDisplayRotation")!=0)return false;
            if(s.task==null)s.task=task;
            if(!foreground)holdRestoreMask(s,task);
        }
        if(a.animating(task))return false;
        if(surfaceAttached){
            if(!foreground&&!s.backRequested&&a.call(task,"topRunningActivity")!=null){
                a.call(task,"setAlwaysOnTop",new Class<?>[]{boolean.class},false);
                a.call(task,"setFocusable",new Class<?>[]{boolean.class},true);
                a.call(a.field(task,"mTransitionController"),"setNoAnimation",new Class<?>[]{a.wc},task);
                // moveTaskToBack may invoke its inner callback synchronously or queue it.
                // Register the request before either path can enter the transition hooks.
                s.backRequested=true;s.backComplete=false;
                try{
                    if(!(Boolean)a.call(task,"moveTaskToBack",new Class<?>[]{a.task},task))
                        throw new IllegalStateException("系统未允许任务退到后台");
                }catch(Exception e){s.backRequested=false;throw e;}
                return false;
            }
            // A queued request returning true does not mean the task is already behind Home.
            // Keep freeform geometry and the mask until the native move actually completes.
            if(!foreground&&(s.backRequested&&!s.backComplete||a.bool(task,"isVisibleRequested")))return false;
        }
        a.call(atm,"deferWindowLayout");
        try{
            if(s.decoration!=null&&surfaceAttached)s.decoration.remove(task);
            // Task.setAlwaysOnTop reorders through getParent(). A finished task
            // can survive in native recents without a parent; restore its saved
            // override below, never call hierarchy mutators on that detached task.
            if(surfaceAttached){
                a.call(task,"setFocusable",new Class<?>[]{boolean.class},true);
                if(foreground)a.call(task,"moveToFront",new Class<?>[]{String.class},"NativeComplements-fullscreen-endpoint");
            }
            if(s.restoreTranslucent)a.call(task,"setForceTranslucent",new Class<?>[]{boolean.class},s.forceTranslucent);
            if(surfaceAttached)a.call(task,"setAlwaysOnTop",new Class<?>[]{boolean.class},s.alwaysOnTop);
            if(surfaceAttached){
                a.call(task,"setWindowingMode",new Class<?>[]{int.class},s.mode);
                a.call(task,"setBounds",new Class<?>[]{Rect.class},new Rect(s.bounds));
            }
            Configuration restored=new Configuration(s.originalConfig!=null?s.originalConfig:a.config(task,true));
            a.call(a.field(restored,"windowConfiguration"),"setAlwaysOnTop",new Class<?>[]{int.class},s.topState);
            a.call(task,"onRequestedOverrideConfigurationChanged",new Class<?>[]{Configuration.class},restored);
            restoreFloatingCompat(task);
            Object requested=a.windowConfig(task,true);
            if(a.integer(requested,"getWindowingMode")!=s.mode||!s.bounds.equals(a.call(requested,"getBounds"))
                ||(Integer)a.field(requested,"mAlwaysOnTop")!=s.topState)throw new IllegalStateException("原请求配置尚未恢复");
            if(surfaceAttached){
                if(a.integer(task,"getWindowingMode")!=1)throw new IllegalStateException("原全屏模式尚未恢复");
                Point pos=new Point();a.call(task,"getRelativePosition",new Class<?>[]{Point.class},pos);
                a.transform(task,1f,pos.x,pos.y);
                Point cache=(Point)a.field(task,"mLastSurfaceSize");cache.set(-1,-1);
                a.call(task,"updateSurfaceSize",new Class<?>[]{android.view.SurfaceControl.Transaction.class},a.call(task,"getPendingTransaction"));
                if(cache.x<0||cache.y<0){a.style(task,0,0,0);cache.set(0,0);}
                a.call(a.call(task,"getPendingTransaction"),"setCornerRadius",new Class<?>[]{android.view.SurfaceControl.class,float.class},a.field(task,"mSurfaceControl"),0f);
                new TaskDecoration(a).restoreLayer(task);
                if(s.startMask!=null){
                    if(s.startMask.holding())s.startMask.release();
                    s.startMask.afterTransaction(1);
                }
                s.rotation.clear();
                if(s.rotationMask!=null){
                    if(s.rotationMask.holding())s.rotationMask.release();
                    s.rotationMask.afterTransaction(1);
                }
                surfaceVisible(s,true);a.opacity(task,1f);
            }
            a.call(root,"ensureActivitiesVisible");a.call(root,"resumeFocusedTasksTopActivities");a.call(wms,"requestTraversal");
            return true;
        }finally{a.call(atm,"continueWindowLayout");}
    }
    private void finishGroup(Session selected,boolean foreground,String why){
        java.util.ArrayList<Session> group=new java.util.ArrayList<>();
        for(Session s:sessions)if(s.token.equals(selected.token))group.add(s);
        group.remove(selected);group.add(selected);
        try{synchronized(lock){
            // 被关掉的窗口若占着前台位，让最早挂着的迷你窗接替；只清理同一任务组的其它迷你窗。
            if(active!=null&&active.token.equals(selected.token)){
                Session promote=null;
                for(Session p:pinned)if(!p.token.equals(selected.token)){promote=p;break;}
                active=promote;removePinned(promote);
            }
            for(Session p:new java.util.ArrayList<>(pinned))if(p.token.equals(selected.token))removePinned(p);
            for(Session s:group){
                rememberOrientation(s);
                s.restoreForeground=foreground&&s==selected;s.restoring=true;s.animationRev++;
                s.entering=false;s.transitioning=false;s.recoveryReason=why;s.backRequested=false;
                if(s.task!=null&&a.bool(s.task,"isAttached")){
                    if(s.decoration!=null)s.decoration.hide(s.task);
                    if(!s.restoreForeground)holdRestoreMask(s,s.task);
                    a.opacity(s.task,0);
                }
            }
        }
            restoreGroup(group,0);report("SLOT_RESTORING");
        }catch(Throwable e){recover("窗口恢复失败："+FrameworkAccess.reason(e));}
    }
    private void restoreGroup(java.util.List<Session> group,int attempt){
        if(recovering)return;
        try{synchronized(lock){
            Session next=null;for(Session s:group)if(sessions.contains(s)){next=s;break;}
            if(next==null){handler.post(()->endClosedApplications(group));report("SLOT_CLOSED");return;}
            if(restoreTask(next,next.restoreForeground)){forget(next);handler.post(()->restoreGroup(group,0));return;}
            if(attempt>=100){note("RECOVERY_PENDING：窗口恢复待完成");return;}
            handler.postDelayed(()->restoreGroup(group,attempt+1),100);
        }}catch(Throwable e){note("RECOVERY_FAILED："+FrameworkAccess.reason(e));}
    }
    private void endClosedApplications(java.util.List<Session> group){
        for(Session s:group)if(s.endOnClose){
            s.endOnClose=false;
            try{
                // A different live window is an explicit reason to keep this UID running.
                if(s.uid<10000||s.userId!=0||sessions.stream().anyMatch(other->other.uid==s.uid))continue;
                android.content.pm.ApplicationInfo info=context.getPackageManager().getApplicationInfo(s.pkg,0);
                if(info.uid!=s.uid||(info.flags&android.content.pm.ApplicationInfo.FLAG_PERSISTENT)!=0)continue;
                synchronized(lock){Object focused=a.focused.invoke(root);Object top=focused==null?null:a.call(focused,"topRunningActivity");
                    if(top!=null&&a.integer(top,"getUid")==s.uid){log.accept("CLOSE_END_APP_CANCELLED foreground="+s.pkg);continue;}}
                Object manager=context.getSystemService(android.app.ActivityManager.class);
                a.call(manager,"forceStopPackageAsUser",new Class<?>[]{String.class,int.class},s.pkg,s.userId);
                log.accept("CLOSE_END_APP package="+s.pkg+" uid="+s.uid);
            }catch(Exception e){log.accept("CLOSE_END_APP_FAILED "+FrameworkAccess.reason(e));}
        }
    }
    private void recover(String why){
        try{synchronized(lock){
            // A new open can recheck an existing recovery. Keep one retry queue,
            // otherwise a leftover callback could restore the newly opened task.
            if(recoveryRetry!=null){handler.removeCallbacks(recoveryRetry);recoveryRetry=null;}
            if(!recovering){
                cancelStart("recovery");
                if(sessions.isEmpty()){
                    if(!journal.getBaseFile().exists()){note("IDLE：无需恢复");return;}
                    sessions=readJournal();
                }
                Session selected=active;
                java.util.ArrayList<Session> ordered=new java.util.ArrayList<>(sessions);
                if(selected!=null){ordered.remove(selected);ordered.add(selected);}
                for(Session s:ordered){
                    s.restoreForeground=s==selected&&("小窗动画进入全屏".equals(why)||"手动恢复".equals(why));
                    s.restoring=true;s.animationRev++;s.entering=false;s.transitioning=false;s.opacity=0;
                    s.backRequested=false;s.recoveryReason=why;
                    if(s.task!=null&&a.bool(s.task,"isAttached")){
                        if(s.decoration!=null)s.decoration.hide(s.task);
                        if(!s.restoreForeground)holdRestoreMask(s,s.task);
                        a.opacity(s.task,0);
                    }
                }
                sessions=java.util.List.copyOf(ordered);recovering=true;active=null;clearPinned();recoveryRetryState.begin();
            }
            if(sessions.isEmpty()){
                recovering=false;pendingRecovery=null;recoveryRetryState.resetWaits();journal.delete();notifyRecents();note("RESTORED："+why);return;
            }
            Session s=sessions.get(0);pendingRecovery=s;
            if(restoreTask(s,s.restoreForeground)){
                forget(s);pendingRecovery=null;recoveryRetryState.resetWaits();
                handler.post(recoveryRetry=()->recover(why));return;
            }
            if(!recoveryRetryState.failed()){note("RECOVERY_PENDING：保留全部恢复记录，可手动恢复");return;}
            handler.postDelayed(recoveryRetry=()->recover(why),100);
        }}catch(Throwable e){note("RECOVERY_FAILED："+FrameworkAccess.reason(e)+"；保留全部恢复记录");}
    }

    private String describe(Session s) {
        Geometry.Placement p = s.placement;
        return "taskId=" + s.taskId + " package=" + s.pkg + "\n"
            + (p == null ? "等待转场结束" : "逻辑=" + p.width() + "×" + p.height()
                + "；显示=" + Math.round(p.width() * p.scale()) + "×" + Math.round(p.height() * p.scale())
                + "；scale=" + p.scale())
            + "\n运行时长=" + (SystemClock.elapsedRealtime()-s.startedAt)/1000 + " 秒（无定时退出）"
            + "\n窗口配置=系统统一下发；普通应用无需作用域";
    }
    private void note(String value) { last = value; log.accept(value); report(value.split("：", 2)[0]); }
    private boolean backgroundImmersive(Session s){
        if(s==null||s.restoring)return false;
        try{synchronized(lock){Object display=a.call(s.task,"getDisplayContent");Object policy=a.call(display,"getDisplayPolicy");
            Object background=a.field(policy,"mTopFullscreenOpaqueWindowState");
            return background!=null&&a.call(background,"getTask")!=s.task
                &&(a.integer(background,"getRequestedVisibleTypes")&android.view.WindowInsets.Type.systemBars())==0;
        }}catch(Exception e){return false;}
    }
    private void fillFrame(Intent result,Session s){
        if(s==null||s.restoring||s.placement==null)return;
        if(s.prioritySuspended){result.putExtra("systemOverlay",true).putExtra("presented",false).putExtra("inputPending",false);return;}
        Geometry.Placement p=s.placement;
        result.putExtra("token",s.token).putExtra("package",s.pkg).putExtra("taskId",s.taskId)
            .putExtra("frame",s.frame).putExtra("x",p.x()).putExtra("y",p.y())
            .putExtra("width",p.width()*p.scale()).putExtra("height",p.height()*p.scale())
            .putExtra("scale",p.scale()).putExtra("normalScale",s.defaultScale).putExtra("appLandscape",s.landscape).putExtra("logicalW",p.width()).putExtra("logicalH",p.height())
            .putExtra("transitioning",s.transitioning||s.rotation.active()||s.cover!=null).putExtra("fullTransition",s.fullTransition)
            .putExtra("transitionHandle",s.transitionHandle).putExtra("presented",s.presented&&!s.rotation.active()).putExtra("inputPending",entryInputPending(s)).putExtra("nativeHandle",true)
            .putExtra("barAlpha",s.barAlpha).putExtra("barColor",s.decoration==null?0xfff0f2f2:s.decoration.color()).putExtra("miniLarge",s.miniLarge)
            .putExtra("edgeHidden",s.edgeHidden).putExtra("edgeSide",s.edgeSide)
            .putExtra("edgeBarDragging",s.edgeBarDragging).putExtra("edgeBarCenterX",s.edgeBarCenterX).putExtra("edgeBarCenterY",s.edgeBarCenterY)
            .putExtra("edgeCenterY",s.miniPlacement==null?p.y()+p.height()*p.scale()/2:s.miniPlacement.y()+s.miniPlacement.height()*s.miniPlacement.scale()/2)
            .putExtra("muted",audio!=null&&audio.isMuted(s.uid));
        if(s.safeBounds!=null)result.putExtra("safeLeft",s.safeBounds.left).putExtra("safeTop",s.safeBounds.top)
            .putExtra("safeRight",s.safeBounds.right).putExtra("safeBottom",s.safeBounds.bottom).putExtra("placementMargin",s.placementMargin);
    }
    private void fillVisibleFrames(Intent result){
        result.putExtra("normalOccupied",normalOccupied());
        // 多个迷你窗同时挂着时，每个都要有自己的控制条；SystemUI 侧按 taskId 复用窗口。
        java.util.ArrayList<Intent> frames=new java.util.ArrayList<>();
        for(Session mini:pinned){
            if(mini.restoring)continue;
            Intent frame=new Intent().putExtra("protocol",Wire.PROTOCOL).putExtra("state","active")
                .putExtra("sequence",frameSequence);fillFrame(frame,mini);
            frames.add(frame);
        }
        result.putParcelableArrayListExtra("pinnedFrames",frames);
        // 旧宿主只认单个 pinnedFrame：保留第一个，避免升级时序里控制条整体消失。
        if(!frames.isEmpty())result.putExtra("pinnedFrame",frames.get(0));
    }
    private void frameReport(String event){
        Intent i=new Intent(Wire.RESULT).putExtra("protocol",Wire.PROTOCOL).putExtra("event",event)
            .putExtra("state","active").putExtra("sequence",++frameSequence);
        fillFrame(i,active);fillVisibleFrames(i);fillOpenWait(i);trackTouchGuard();
        if(!Wire.transmit(frameSink,i))frameSink=null;
    }
    private void environment(Intent out){
        synchronized(lock){try{
            Object focused=a.focused.invoke(root);String foreground="";
            if(focused!=null){Object top=a.call(focused,"topRunningActivity");if(top!=null)foreground=(String)a.field(top,"packageName");}
            out.putExtra("foreground",foreground);
            Object display=a.call(root,"getDefaultDisplay");
            Object ime=a.call(display,"getImeWindow");boolean visible=ime!=null&&a.bool(ime,"isVisible");
            Rect bounds=(Rect)a.call(display,"getBounds");
            Object state=a.call(a.call(display,"getInsetsStateController"),"getRawInsetsState");
            Insets insets=(Insets)a.call(state,"calculateInsets",new Class<?>[]{Rect.class,Rect.class,int.class,boolean.class},bounds,bounds,android.view.WindowInsets.Type.ime(),false);
            out.putExtra("imeVisible",visible).putExtra("imeTop",visible&&insets.bottom>0?bounds.bottom-insets.bottom:Integer.MAX_VALUE);
            Object focus=a.field(display,"mCurrentFocus");boolean system=false;
            if(focus!=null){android.view.WindowManager.LayoutParams attrs=(android.view.WindowManager.LayoutParams)a.field(focus,"mAttrs");
                String title=String.valueOf(attrs.getTitle());system=BackBridge.PACKAGE.equals(attrs.packageName)&&!title.startsWith("NativeComplements");
                out.putExtra("systemOverlayTitle",title);}
            boolean priority=sessions.stream().anyMatch(s->visible(s)&&s.prioritySuspended);
            out.putExtra("systemOverlay",system||priority);
        }catch(Exception e){out.putExtra("environmentError",FrameworkAccess.reason(e));}}
    }
    private boolean capabilityKnown,capabilityMultiWindow,capabilityFreeform;
    private static boolean validCapabilityRequest(String event,String requestId){
        return "STATUS".equals(event)&&requestId!=null&&!requestId.isEmpty()&&requestId.length()<=128;
    }
    /** Only a correlated STATUS probes current ATM flags; heartbeat cache is never an enable verdict. */
    void fillCapabilities(Intent out,String event,String requestId){
        boolean fresh=validCapabilityRequest(event,requestId);
        if(fresh){
            capabilityKnown=capabilityMultiWindow=capabilityFreeform=false;
            if(lock!=null&&atm!=null&&a!=null)try{synchronized(lock){
                Object multi=a.field(atm,"mSupportsMultiWindow"),freeform=a.field(atm,"mSupportsFreeformWindowManagement");
                if(multi instanceof Boolean m&&freeform instanceof Boolean f){
                    capabilityMultiWindow=m;capabilityFreeform=f;capabilityKnown=true;
                }
            }}catch(Exception|LinkageError error){log.accept("WINDOW_CAPABILITY_UNAVAILABLE "+FrameworkAccess.reason(error));}
            out.putExtra("requestId",requestId);
        }
        out.putExtra("capabilitiesKnown",capabilityKnown)
            .putExtra("supportsMultiWindow",capabilityMultiWindow).putExtra("supportsFreeform",capabilityFreeform)
            .putExtra("preflightReady",attached&&hooksReady).putExtra("ownershipConflict",false);
    }
    private void report(String event){report(event,null);}
    private void report(String event,String requestId) {
        if (context == null) return;
        Session s = active;
        String state = s == null ? journal.getBaseFile().exists() ? "recovery" : "idle" : s.restoring ? "recovery" : "active";
        String report = last + "\n\n" + capabilities() + (s == null ? "" : "\n" + describe(s));
        Session pending = pendingRecovery;
        Intent result = new Intent(Wire.RESULT).setPackage(Wire.PKG)
            .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY).putExtra("event", event)
            .putExtra("state", state).putExtra("report", report).putExtra("protocol",Wire.PROTOCOL)
            .putExtra("hasJournal",journal.getBaseFile().exists()).putExtra("endedTaskId",endedTaskId);
        fillCapabilities(result,event,requestId);
        result.putExtra("sequence",++frameSequence);
        result.putExtra("hostConnected",uiReady&&uiLease!=null&&uiLease.isBinderAlive()&&SystemClock.elapsedRealtime()-uiSeen<3000);
        result.putExtra("lastTouch",uiTouch);
        environment(result);
        Bundle transport=new Bundle();transport.putBinder("controlChannel",controlChannel);result.putExtras(transport);
        fillFrame(result,s);fillVisibleFrames(result);fillOpenWait(result);trackTouchGuard();
        result.putExtra("immersive",backgroundImmersive(s));
        if ("recovery".equals(state) && pending != null)
            result.putExtra("recoverPackage", pending.pkg).putExtra("recoverToken", pending.token)
                .putExtra("recoverComponent", pending.component);
        handler.post(() -> {
            try { context.sendBroadcast(result, Wire.PERMISSION,
                android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
                context.sendBroadcast(new Intent(result).setPackage(BackBridge.PACKAGE),null,
                    android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()); }
            catch (Throwable e) { log.accept("RESULT_SEND_FAILED " + FrameworkAccess.reason(e)); }
        });
    }
}
