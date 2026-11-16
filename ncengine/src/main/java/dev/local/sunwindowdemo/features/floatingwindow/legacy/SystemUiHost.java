package dev.local.sunwindowdemo;


import android.app.KeyguardManager;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Color;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.os.Handler;
import android.os.Looper;
import android.view.*;

import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;

public final class SystemUiHost extends ContextWrapper implements ComponentCallbacks {
    static final int OVERLAY_TYPE=2017;
    // Called only inside hooked SystemUI (INTERNAL_SYSTEM_WINDOW), with exact API37 preflight.
    @android.annotation.SuppressLint("BlockedPrivateApi")
    static void trust(WindowManager.LayoutParams p){
        try{WindowManager.LayoutParams.class.getDeclaredMethod("setTrustedOverlay").invoke(p);}
        catch(Exception e){throw new IllegalStateException("trusted overlay preflight",e);}
    }
    static volatile SystemUiHost live;
    private boolean settingsReady;
    boolean ready(){return settingsReady&&!cleaned;}
    private final java.util.concurrent.ExecutorService settingsWorker=java.util.concurrent.Executors.newSingleThreadExecutor();
    private SystemUiHost(Context c){super(c);}
    // The target framework defines TYPE_STATUS_BAR_SUB_PANEL (2017); the public SDK IntDef omits it.
    @android.annotation.SuppressLint("WrongConstant")
    private static Context overlayContext(Context display){return display.createWindowContext(OVERLAY_TYPE,null);}
    static void start(Context c){
        if(live!=null)return;
        trust(new WindowManager.LayoutParams());
        Context display=c.createDisplayContext(c.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(0));
        Context window=overlayContext(display);
        SystemUiHost host=new SystemUiHost(new android.view.ContextThemeWrapper(window,android.R.style.Theme_DeviceDefault_DayNight));
        live=host;
        try{host.initialize();}catch(Throwable failure){
            try{host.cleanup();}catch(Throwable cleanupFailure){failure.addSuppressed(cleanupFailure);}
            finally{if(live==host)live=null;connected=false;}
            android.util.Log.e("NativeComplements","HOST_START_FAILED",failure);
        }
    }
    private final BroadcastReceiver settingsReceiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){
        try{if(getSentFromUid()==getPackageManager().getApplicationInfo(Wire.PKG,0).uid){
            loadSettings();
        }}catch(Exception ignored){}
    }};
    private final BroadcastReceiver queryReceiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){
        try{int uid=getSentFromUid();if(uid==getPackageManager().getApplicationInfo(LauncherGuard.PACKAGE,0).uid||uid==getApplicationInfo().uid)publish(true);}catch(Exception ignored){}
    }};
    private int settingsFailures;
    private void loadSettings(){settingsWorker.execute(()->{
        try{android.os.Bundle b=getContentResolver().call(android.net.Uri.parse("content://"+ConfigProvider.AUTHORITY),"read",null,null);
            if(b==null)throw new IllegalStateException("empty settings");
            ConfigProvider.copy(b,config.prefs);
            if(settingsFailures>0)android.util.Log.i("NativeComplements","SETTINGS_READ_RECOVERED");
            settingsFailures=0;
            main.post(()->{settingsReady=true;reloadSettings();});
        }catch(Exception e){
            // The module process may be stopped (force-stop/stay-stopped, task cleaners). The host keeps the
            // last complete snapshot already copied into its own preferences, including the enabled switch.
            boolean cached;
            try{cached=!config.prefs.getAll().isEmpty();}catch(RuntimeException locked){cached=false;}
            if(settingsFailures++==0)android.util.Log.w("NativeComplements","SETTINGS_READ_FAILED cached="+cached,e);
            boolean usable=cached;long delay=Math.min(60_000L,3000L<<Math.min(settingsFailures-1,5));
            main.post(()->{if(!cleaned&&!settingsReady&&usable){settingsReady=true;reloadSettings();}});
            main.postDelayed(()->{if(!cleaned)loadSettings();},delay);
        }
    });}
    /* STATUS carries foreground/IME/overlay state used by menu rules, barrage and suggestions,
     * so it keeps 750ms while interactive. Screen off has nothing to show: stop until SCREEN_ON/USER_PRESENT. */
    private boolean polling;
    private final Runnable poll=new Runnable(){public void run(){
        if(cleaned||!getSystemService(android.os.PowerManager.class).isInteractive()){polling=false;return;}
        if(delegatedToStandalone)updateCompanionEnvironment();
        Wire.send(SystemUiHost.this,"STATUS");main.postDelayed(this,750);
    }};
    private void resumePoll(){if(cleaned||polling)return;polling=true;main.post(poll);}
    private void reloadSettings(){
        if(cleaned)return;hideFan();removeHot();if(!config.enabled()){cancelOpenWait();hidePanel();}
        if(panel instanceof FrameLayout outside&&outside.getChildCount()>0)AppList.blur(outside.getChildAt(0),config.menuBlur());
        if(!config.enabled()&&controls!=null){
            if("active".equals(backendState)||"recovery".equals(backendState))Wire.send(this,"RESTORE");
            hideControls();
        }
        refresh();Wire.send(this,"STATUS");
    }
    private final Runnable reload=this::reloadSettings;
    private final android.os.Binder lease=new android.os.Binder();
    private int[] published,publishedHandles;
    private boolean screenshotVisible,systemFocus,immersive;
    private String systemOverlayTitle="";
    private android.window.OnBackInvokedDispatcher panelDispatcher;
    private final android.window.OnBackInvokedCallback panelBack=this::backPanel;
    private android.window.OnBackInvokedDispatcher openWaitDispatcher;
    private final android.window.OnBackInvokedCallback openWaitBack=this::cancelOpenWait;
    private void syncControlsOcclusion(){
        if(controls!=null&&miniControls!=null)miniControls.normalOcclusion(controls.contentOcclusion());
    }
    void controlsChanged(){main.post(()->{syncControlsOcclusion();publish(false);});}
    private int publishedWidth,publishedHeight;
    private final CornerGesture cornerGesture=new CornerGesture();
    private volatile CornerGesture.Area cornerArea;
    private long cornerUiDown;
    boolean cornerInput(MotionEvent event,Runnable pilfer){
        CornerGesture.Area area=cornerArea;
        int stage=cornerGesture.event(event.getActionMasked(),event.getX(),event.getY(),
            event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)&&event.getToolType(0)==MotionEvent.TOOL_TYPE_FINGER,
            event.getPointerCount(),Display.DEFAULT_DISPLAY,event.getDeviceId(),event.getDownTime(),event.getEventTime(),area);
        if(stage==CornerGesture.PASS)return false;
        if(stage==CornerGesture.CLAIM){
            try{pilfer.run();}catch(RuntimeException failed){cornerGesture.abandon();main.post(this::hideFan);throw failed;}
        }
        if(stage!=CornerGesture.TRACK){
            boolean isRight=cornerGesture.right();float x=event.getX(),y=event.getY();long down=event.getDownTime();
            main.post(()->cornerUi(stage,isRight,x,y,down,area));
        }
        return true;
    }
    private void cornerUi(int stage,boolean isRight,float x,float y,long down,CornerGesture.Area area){
        if(stage==CornerGesture.BEGIN){cornerUiDown=down;hideFan();}
        if(cornerUiDown!=down)return;
        View hot=isRight?right:left;
        if(cleaned||area==null||!area.equals(cornerArea)||!allowed()){
            hideFan();if(hot instanceof HotspotView view)view.mark(0);return;
        }
        if(stage==CornerGesture.BEGIN&&hot instanceof HotspotView view)view.mark(1);
        if(stage==CornerGesture.CLAIM&&hot!=null&&fan==null){touchLog("斜向滑动达标，接管输入");showFan(isRight,hot);}
        if(fan!=null&&(stage==CornerGesture.CLAIM||stage==CornerGesture.MOVE||stage==CornerGesture.FINISH)){
            int hit=fan.pointer(x,y);if(hit!=lastSelection){lastSelection=hit;if(hit>=0)fan.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);}
        }
        if(stage==CornerGesture.FINISH||stage==CornerGesture.END||stage==CornerGesture.CANCEL){
            String target=stage==CornerGesture.FINISH&&fan!=null?fan.picked():null;
            hideFan();if(hot instanceof HotspotView view)view.mark(0);
            if(target!=null){if(target.isEmpty())showPanel();else launch(target,true);}
        }
    }
    void cancelCornerInput(){main.post(()->{hideFan();removeHot();});}
    private boolean publishedEnabled;
    private boolean publishedDrop,publishedImmersive;
    private int activeTask=-1,pinnedTask=-1,publishedHidden=-2;
    private final android.os.Binder dropChannel=new android.os.Binder(){
        @Override protected boolean onTransact(int code,android.os.Parcel data,android.os.Parcel reply,int flags) throws android.os.RemoteException {
            if(code<android.os.IBinder.FIRST_CALL_TRANSACTION||code>android.os.IBinder.FIRST_CALL_TRANSACTION+3)return super.onTransact(code,data,reply,flags);
            try{if(android.os.Binder.getCallingUid()!=getPackageManager().getApplicationInfo(BackBridge.PACKAGE,0).uid)return false;}
            catch(Exception e){return false;}
            data.enforceInterface(BackBridge.CHANNEL);
            if(code==android.os.IBinder.FIRST_CALL_TRANSACTION+3){
                boolean shown=data.readBoolean();data.enforceNoDataAvail();
                main.post(()->{screenshotVisible=shown;updateOverlayPriority();if(shown){hideFan();removeHot();}else refresh();});return true;
            }
            return false; // Retired gesture Binder operations never open or admit a task.
        }
    };
    static volatile boolean connected;
    private final Handler main=new Handler(Looper.getMainLooper());
    private Config config;
    private WindowManager wm;
    private View left,right;
    private FanView fan;
    private int lastSelection=-1;
    private View panel;
    private View openWait;
    private ImageView openWaitIcon;
    private final OpenWaitState openWaitState=new OpenWaitState();
    private WindowControls controls,miniControls;
    private void hideControls(){if(controls!=null)controls.hide();if(miniControls!=null)miniControls.hide();}
    private boolean normalOccupied;
    private boolean dropAvailable(){return WindowHostPolicy.defaultSlot(backendState,normalOccupied);}
    private volatile HostExtras extras;
    private WindowQuickHost quick;
    private View menuContent;
    private int suggestionImeTop=Integer.MAX_VALUE;
    void suggestionNotification(android.service.notification.NotificationListenerService source,android.service.notification.StatusBarNotification notification){
        main.post(()->{if(extras!=null)extras.notification(source,notification);});
    }
    void suggestionNotificationRemoved(android.service.notification.StatusBarNotification notification){main.post(()->{if(extras!=null)extras.notificationRemoved(notification);});}
    boolean rotationSuggestion(Runnable click,Runnable restore){return extras!=null&&extras.rotationSuggestion(click,restore);}
    void publishRotationSettings(){if(extras!=null)extras.publishRotationSettings();}
    boolean blocksEdgeTouch(float x,float y){return extras!=null&&extras.blocksEdgeTouch(x,y);}
    private volatile boolean backendReady;
    private volatile String backendState="";
    // ponytail: one SystemUI process, bounded strings only across product ClassLoaders; never a control channel.
    private static final String COMPANION_ENVIRONMENT="dev.nativecomplements.window.companion.environment.v1";
    private boolean delegatedToStandalone;
    private long ownerEnvironmentAt;
    private final BroadcastReceiver backendReceiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){
        if(getSentFromUid()!=1000||i.getIntExtra("protocol",0)!=Wire.PROTOCOL)return;
        Wire.acceptBackend(i);receiveFrame(i);
    }};
    private void receiveFrame(Intent i){
        if(cleaned||controls==null)return;
        backendState=i.getStringExtra("state");
        backendReady=!"conflict".equals(backendState)&&!i.getBooleanExtra("ownershipConflict",false);
        normalOccupied=i.getBooleanExtra("normalOccupied",true);
        if(!dev.local.sunwindowdemo.core.ModuleIdentity.CURRENT.standalone())
            delegatedToStandalone=!backendReady&&i.getBooleanExtra("delegatedToStandalone",false)
                &&dev.local.sunwindowdemo.core.ModuleIdentity.WINDOW_PACKAGE.equals(i.getStringExtra("backendOwner"));
        if(dev.local.sunwindowdemo.core.ModuleIdentity.CURRENT.standalone()){
            if(!backendReady||i.hasExtra("environmentError"))ownerEnvironmentAt=0;
            else if(i.hasExtra("foreground"))ownerEnvironmentAt=android.os.SystemClock.elapsedRealtime();
        }
        if(!backendReady){
            cancelOpenWait();hideFan();hidePanel();hideControls();removeHot();
            // No owner environment is available: fail closed for shared overlays and edge admission.
            foreground="";systemFocus=true;keyboard=true;updateOverlayPriority();publish(false);return;
        }
        updateOpenWait(i);
        if(i.hasExtra("foreground")){foreground=i.getStringExtra("foreground");keyboard=i.getBooleanExtra("imeVisible",false);systemFocus=i.getBooleanExtra("systemOverlay",false);
            systemOverlayTitle=i.getStringExtra("systemOverlayTitle");
            suggestionImeTop=i.getIntExtra("imeTop",Integer.MAX_VALUE);
            updateOverlayPriority();controls.imeTop(suggestionImeTop);miniControls.imeTop(suggestionImeTop);refresh();}
        if(i.hasExtra("immersive"))immersive=i.getBooleanExtra("immersive",false);
        activeTask="active".equals(backendState)?i.getIntExtra("taskId",-1):-1;
        Intent pinnedFrame="active".equals(backendState)?i.getParcelableExtra("pinnedFrame",Intent.class):null;
        pinnedTask=pinnedFrame==null?-1:pinnedFrame.getIntExtra("taskId",-1);
        publish(false);
        try{if(!config.enabled()||!getSystemService(android.os.PowerManager.class).isInteractive()||getSystemService(KeyguardManager.class).isKeyguardLocked())hideControls();else {controls.update(i);
            Intent mini=i.getParcelableExtra("pinnedFrame",Intent.class);
            syncControlsOcclusion();
            if(mini==null)miniControls.hide();else miniControls.update(mini);
            syncControlsOcclusion();
        }}catch(RuntimeException e){hideControls();android.util.Log.e("NativeComplements","overlay controls failed",e);}
        if(i.getStringExtra("event")!=null&&(i.getStringExtra("event").endsWith("FAILED")||"BLOCKED".equals(i.getStringExtra("event"))))Toast.makeText(this,WindowPolicy.failureMessage(i.getStringExtra("report")),Toast.LENGTH_SHORT).show();
    }
    private List<AppCatalog.App> all=List.of();
    private String foreground="";
    private boolean keyboard,cleaned;
    private long lastOpen;
    static volatile String lastTouch="尚未收到角落触摸";
    private int touchLogs;
    private void touchLog(String message){lastTouch=message;if(touchLogs++<50)android.util.Log.i("CornerLauncher",message);}
    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener=(p,k)->{main.removeCallbacks(reload);main.post(reload);};
    private final BroadcastReceiver screenReceiver=new BroadcastReceiver(){
        public void onReceive(Context c,Intent i){
            if(Intent.ACTION_SCREEN_ON.equals(i.getAction())){resumePoll();return;}
            cancelOpenWait();hideFan();hidePanel();removeHot();if(controls!=null)hideControls();refresh();Wire.send(SystemUiHost.this,"STATUS");
            if(Intent.ACTION_SCREEN_OFF.equals(i.getAction())){main.removeCallbacks(poll);polling=false;}else resumePoll();
        }
    };
    private final BroadcastReceiver packageReceiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){loadApps();}};
    private int catalogGeneration;
    private final Runnable iconsReloaded=()->main.post(()->{if(!cleaned)loadApps();});
    private void initialize() {
        cleaned=false;connected=true;live=this;
        Config.hostPreferences=getSharedPreferences(Config.HOST_PREFERENCES,Context.MODE_PRIVATE);
        config=new Config(this);wm=getSystemService(WindowManager.class);
        controls=new WindowControls(this);miniControls=new WindowControls(this);
        extras=ProductFeatures.hostExtras(this);
        extras.startCompanions();
        quick=new WindowQuickHost(this,()->{hidePanel();hideFan();},()->{hidePanel();hideFan();floatCurrent(0,true);});
        extras.startBarrage();
        Wire.listen(this::receiveFrame);
        registerReceiver(backendReceiver,new IntentFilter(Wire.RESULT),null,main,Context.RECEIVER_EXPORTED);
        Wire.send(this,"STATUS");
        config.prefs.registerOnSharedPreferenceChangeListener(prefListener);
        IntentFilter screen=new IntentFilter(Intent.ACTION_SCREEN_OFF);screen.addAction(Intent.ACTION_USER_PRESENT);screen.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenReceiver,screen,Context.RECEIVER_NOT_EXPORTED);
        IntentFilter pkgs=new IntentFilter(Intent.ACTION_PACKAGE_ADDED);pkgs.addAction(Intent.ACTION_PACKAGE_REMOVED);pkgs.addAction(Intent.ACTION_PACKAGE_CHANGED);pkgs.addDataScheme("package");
        registerReceiver(packageReceiver,pkgs,Context.RECEIVER_NOT_EXPORTED);
        registerReceiver(settingsReceiver,settingsFilter(),null,main,Context.RECEIVER_EXPORTED);
        registerReceiver(queryReceiver,new IntentFilter(LauncherGuard.QUERY),null,main,Context.RECEIVER_EXPORTED);
        getApplicationContext().registerComponentCallbacks(this);
        dev.local.sunwindowdemo.features.iconlayers.IconAssets.onReload(iconsReloaded);
        dev.local.sunwindowdemo.features.iconlayers.IconAssets.connect(getApplicationContext());
        loadApps();loadSettings();resumePoll();
    }
    private void loadApps() {
        int generation=++catalogGeneration;
        new Thread(()->{
            List<AppCatalog.App> apps=AppCatalog.load(this);
            main.post(()->{
                if(!connected||cleaned||generation!=catalogGeneration)return;all=apps;
                if(menuContent!=null)AppList.updateApps(menuContent,apps);
                if(fan!=null)fan.updateApps(apps);
                if(openWaitIcon!=null)for(AppCatalog.App app:apps)if(app.pkg.equals(openWaitState.packageName())){openWaitIcon.setImageDrawable(app.icon);break;}
                refresh();
            });
        },"window-app-catalog").start();
    }
    private void updateOverlayPriority(){
        for(View overlay:new View[]{panel,openWait})if(overlay!=null&&overlay.getLayoutParams() instanceof WindowManager.LayoutParams p){
            int mask=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            int next=screenshotVisible||systemFocus?p.flags|mask:p.flags&~mask;
            if(next!=p.flags){p.flags=next;try{wm.updateViewLayout(overlay,p);}catch(RuntimeException ignored){}}
        }
        updateCompanionEnvironment();
        if(controls!=null)controls.systemOverlay(screenshotVisible||systemFocus);
        if(miniControls!=null)miniControls.systemOverlay(screenshotVisible||systemFocus);
    }
    private void updateCompanionEnvironment(){
        if(extras==null)return;
        if(!delegatedToStandalone){
            extras.environment(foreground,systemFocus,systemOverlayTitle,keyboard,screenshotVisible,panel!=null,openWait!=null,suggestionImeTop);
            extras.edgeEnvironment(screenshotVisible||systemFocus,keyboard);
            return;
        }
        try{
            boolean awake=getSystemService(android.os.PowerManager.class).isInteractive();
            KeyguardManager keyguard=getSystemService(KeyguardManager.class);
            if(!awake||keyguard==null||keyguard.isKeyguardLocked())throw new IllegalStateException("screen blocked");
            String encoded=System.getProperty(COMPANION_ENVIRONMENT,"");
            if(encoded.length()>8192)throw new IllegalArgumentException("environment size");
            org.json.JSONObject snapshot=new org.json.JSONObject(encoded);
            long age=android.os.SystemClock.elapsedRealtime()-snapshot.getLong("at");
            if(age<0||age>2500)throw new IllegalStateException("stale owner environment");
            org.json.JSONArray values=snapshot.getJSONArray("rects");
            if(values.length()>256||values.length()%4!=0)throw new IllegalArgumentException("protected rectangles");
            android.graphics.Rect bounds=wm.getCurrentWindowMetrics().getBounds();
            int[] rectangles=new int[values.length()];
            for(int n=0;n<rectangles.length;n+=4){
                android.graphics.Rect rect=new android.graphics.Rect(values.getInt(n),values.getInt(n+1),values.getInt(n+2),values.getInt(n+3));
                if(rect.isEmpty()||!bounds.contains(rect))throw new IllegalArgumentException("protected rectangle bounds");
                rectangles[n]=rect.left;rectangles[n+1]=rect.top;rectangles[n+2]=rect.right;rectangles[n+3]=rect.bottom;
            }
            boolean blocked=snapshot.getBoolean("system")||screenshotVisible;
            extras.environment(snapshot.getString("foreground"),blocked,snapshot.getString("title"),snapshot.getBoolean("keyboard"),
                screenshotVisible||snapshot.getBoolean("screenshot"),snapshot.getBoolean("panel"),snapshot.getBoolean("waiting"),snapshot.getInt("imeTop"));
            extras.edgeEnvironment(blocked||snapshot.getBoolean("screenshot")||snapshot.getBoolean("panel")||snapshot.getBoolean("waiting"),snapshot.getBoolean("keyboard"));
            extras.protectedRects(rectangles);
        }catch(Exception unavailable){
            extras.environment("",true,"",true,true,false,false,Integer.MAX_VALUE);
            extras.edgeEnvironment(true,true);extras.protectedRects(new int[0]);
        }
    }
    private void shareCompanionEnvironment(int[] rectangles){
        if(!dev.local.sunwindowdemo.core.ModuleIdentity.CURRENT.standalone())return;
        try{
            if(cleaned||!backendReady||ownerEnvironmentAt==0||!getSystemService(android.os.PowerManager.class).isInteractive()){
                System.clearProperty(COMPANION_ENVIRONMENT);return;
            }
            android.graphics.Rect bounds=wm.getCurrentWindowMetrics().getBounds();
            org.json.JSONArray clipped=new org.json.JSONArray();
            for(int n=0;n+3<rectangles.length&&clipped.length()<256;n+=4){
                android.graphics.Rect rect=new android.graphics.Rect(rectangles[n],rectangles[n+1],rectangles[n+2],rectangles[n+3]);
                if(rect.intersect(bounds)){clipped.put(rect.left);clipped.put(rect.top);clipped.put(rect.right);clipped.put(rect.bottom);}
            }
            org.json.JSONObject snapshot=new org.json.JSONObject().put("at",ownerEnvironmentAt).put("foreground",foreground==null?"":foreground)
                .put("system",systemFocus).put("title",systemOverlayTitle==null?"":systemOverlayTitle).put("keyboard",keyboard)
                .put("screenshot",screenshotVisible).put("panel",panel!=null).put("waiting",openWait!=null).put("imeTop",suggestionImeTop).put("rects",clipped);
            String encoded=snapshot.toString();
            if(encoded.length()<=8192)System.setProperty(COMPANION_ENVIRONMENT,encoded);else System.clearProperty(COMPANION_ENVIRONMENT);
        }catch(Exception unavailable){System.clearProperty(COMPANION_ENVIRONMENT);}
    }
    private boolean allowed() {
        if(config==null||!settingsReady||!WindowHostPolicy.cornerConfigured(config.enabled(),backendReady,cleaned,screenshotVisible,systemFocus)
            ||!getSystemService(android.os.PowerManager.class).isInteractive())return false;
        KeyguardManager kg=getSystemService(KeyguardManager.class);
        return !Rules.blocked(kg==null||kg.isKeyguardLocked(),
            getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE,config.landscapeOff(),
            keyboard,config.keyboardOff(),config.blocked().contains(foreground));
    }
    private int dp(float value){return (int)(value*getResources().getDisplayMetrics().density+.5f);}
    private int bottom(){
        WindowMetrics metrics=wm.getCurrentWindowMetrics();
        WindowInsets insets=metrics.getWindowInsets();
        // In immersive landscape do not reserve a hidden navigation bar's full height.
        return metrics.getBounds().width()>metrics.getBounds().height()
            ?insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            :insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom;
    }
    private WindowManager.LayoutParams params(int width,int height,int flags) {
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(width,height,OVERLAY_TYPE,flags,PixelFormat.TRANSLUCENT);
        p.setFitInsetsTypes(0);p.setTitle("NativeComplements-window-menu");trust(p);return p;
    }
    private void refresh() {
        if(!allowed()){hideFan();if(panel!=null&&!(screenshotVisible||systemFocus))hidePanel();removeHot();return;}
        if(!dev.local.sunwindowdemo.core.ModuleIdentity.CURRENT.standalone()&&BackBridge.cornerReady){if(left==null)addHot(false);if(right==null)addHot(true);}
        else removeHot();
        publish(false);
    }
    private void addHot(boolean isRight) {
        HotspotView hot=new HotspotView(this,isRight,config.showHeat());
        hot.setContentDescription(isRight?"右侧角落启动热区":"左侧角落启动热区");
        hot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        WindowManager.LayoutParams p=params(dp(config.heat()),dp(config.heat()),WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        // Cover the actual display corner, not a square above the navigation bar.
        p.gravity=Gravity.BOTTOM|(isRight?Gravity.RIGHT:Gravity.LEFT);p.y=0;
        p.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        // Visual indicator only: native InputMonitor observes the app's real touch stream.
        hot.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->publish(false));
        try {wm.addView(hot,p);if(isRight)right=hot;else left=hot;}
        catch(RuntimeException failure){android.util.Log.w("CornerLauncher","hot-zone-add-failed");}
    }
    private void showFan(boolean isRight,View hot) {
        if(panel!=null||openWait!=null||!allowed())return;
        List<ShortcutTarget> selected=selectedTargets();
        List<ShortcutTarget> shortcuts=selected.subList(0,Math.min(config.fanCount(),selected.size()));
        dev.local.sunwindowdemo.features.diagnostics.Diagnostics.event(this,"systemui","fan_shown");
        fan=new FanView(this,shortcuts,isRight,dp(config.icon()),dp(config.radius()),bottom(),config.selectionDistance());lastSelection=-1;
        WindowManager.LayoutParams p=params(-1,-1,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        p.windowAnimations=0;
        try{wm.addView(fan,p);hot.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);}
        catch(RuntimeException failure){fan=null;touchLog("菜单窗口创建失败："+failure.getClass().getSimpleName());}
    }
    private void hideFan(){if(fan!=null){remove(fan);fan=null;}lastSelection=-1;}
    private void remove(View v){try{if(wm!=null)wm.removeViewImmediate(v);}catch(RuntimeException ignored){}}
    private void removeHot(){cornerArea=null;cornerGesture.abandon();if(left!=null){remove(left);left=null;}if(right!=null){remove(right);right=null;}publish(false);}
    void publish(boolean force){
        int[] rects=new int[8];int count=0;
        boolean enabled=!dev.local.sunwindowdemo.core.ModuleIdentity.CURRENT.standalone()&&allowed()&&panel==null&&openWait==null;
        boolean dropEnabled=false;
        if(enabled)for(View v:new View[]{left,right})if(v!=null&&v.isAttachedToWindow()&&v.isLaidOut()){
            int[] location=new int[2];v.getLocationOnScreen(location);
            rects[count++]=location[0];rects[count++]=location[1];rects[count++]=location[0]+v.getWidth();rects[count++]=location[1]+v.getHeight();
        }
        android.graphics.Rect cornerBounds=wm==null?new android.graphics.Rect():wm.getCurrentWindowMetrics().getBounds();
        cornerArea=enabled&&count==8&&BackBridge.cornerReady
            ?new CornerGesture.Area(cornerBounds.width(),cornerBounds.height(),dp(config.heat()),dp(config.distance()),ViewConfiguration.getLongPressTimeout()):null;

        int[] handles=controls==null?new int[0]:controls.protectedRects();
        int[] miniHandles=miniControls==null?new int[0]:miniControls.protectedRects();
        int primaryLength=handles.length;handles=java.util.Arrays.copyOf(handles,primaryLength+miniHandles.length);
        System.arraycopy(miniHandles,0,handles,primaryLength,miniHandles.length);
        rects=java.util.Arrays.copyOf(rects,count);
        if(extras!=null){
            int[] protectedAreas=java.util.Arrays.copyOf(rects,count+handles.length);
            System.arraycopy(handles,0,protectedAreas,count,handles.length);
            if(!delegatedToStandalone)extras.protectedRects(protectedAreas);
            shareCompanionEnvironment(protectedAreas);
            if(delegatedToStandalone)updateCompanionEnvironment();
        }
        enabled=(enabled&&count>0)||handles.length>0;
        android.graphics.Rect bounds=wm==null?new android.graphics.Rect():wm.getCurrentWindowMetrics().getBounds();
        int hidden=config!=null&&config.hideRecents()?activeTask:-1;
        if(!force&&publishedImmersive==immersive&&publishedHidden==hidden&&publishedDrop==dropEnabled&&publishedEnabled==enabled&&publishedWidth==bounds.width()&&publishedHeight==bounds.height()&&java.util.Arrays.equals(published,rects)&&java.util.Arrays.equals(publishedHandles,handles))return;
        publishedDrop=dropEnabled;publishedHidden=hidden;publishedImmersive=immersive;
        publishedEnabled=enabled;publishedWidth=bounds.width();publishedHeight=bounds.height();published=rects.clone();publishedHandles=handles.clone();
        Intent state=new Intent(LauncherGuard.STATE).setPackage(LauncherGuard.PACKAGE).putExtra("enabled",enabled)
            .putExtra("width",bounds.width()).putExtra("height",bounds.height()).putExtra("rects",rects);
        state.putExtra("handleRects",handles).putExtra("immersive",immersive).putExtra("hiddenTask",hidden).putExtra("dropEnabled",dropEnabled).putExtra("density",getResources().getDisplayMetrics().density);
        android.os.Bundle extras=new android.os.Bundle();extras.putBinder("lease",lease);extras.putBinder("dropChannel",dropChannel);state.putExtras(extras);
        sendBroadcast(state,null,android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
        sendBroadcast(new Intent(state).setPackage(BackBridge.PACKAGE),null,android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }
    private List<ShortcutTarget> selectedTargets(){
        List<ShortcutTarget> selected=new ArrayList<>();
        for(String key:config.targets()){
            ShortcutTarget target=ShortcutTarget.fromKey(this,key,all);
            if(target!=null)selected.add(target);
        }
        return selected;
    }
    private void saveTargets(List<ShortcutTarget> targets,java.util.function.Consumer<Boolean> done){
        ArrayList<String> keys=new ArrayList<>(),labels=new ArrayList<>();for(ShortcutTarget target:targets){keys.add(target.key());labels.add(target.label.length()>200?target.label.substring(0,200):target.label);}
        View savingPanel=panel;
        settingsWorker.execute(()->{
            try{Config.validateTargets(keys,labels);
                android.os.Bundle extras=new android.os.Bundle();extras.putStringArrayList("targets",keys);extras.putStringArrayList("labels",labels);
                android.os.Bundle result=getContentResolver().call(android.net.Uri.parse("content://"+ConfigProvider.AUTHORITY),"targets",null,extras);
                if(result==null||!result.getBoolean("saved"))throw new IllegalStateException("settings save");
                android.os.Bundle snapshot=getContentResolver().call(android.net.Uri.parse("content://"+ConfigProvider.AUTHORITY),"read",null,null);
                if(snapshot==null)throw new IllegalStateException("settings read after save");
                ConfigProvider.copy(snapshot,config.prefs);
                main.post(()->{if(!cleaned&&panel==savingPanel)done.accept(true);});
            }catch(Exception e){main.post(()->{if(!cleaned&&panel==savingPanel){done.accept(false);}});}
        });
    }
    private void showPanel() {
        if(panel!=null||openWait!=null||!allowed())return;
        if(all.isEmpty()){Toast.makeText(this,"应用列表正在加载，请稍后重试",Toast.LENGTH_SHORT).show();return;}
        FrameLayout outside=new FrameLayout(this){
            @Override public boolean dispatchKeyEvent(KeyEvent e){if(e.getKeyCode()==KeyEvent.KEYCODE_BACK){if(e.getAction()==KeyEvent.ACTION_UP)backPanel();return true;}return super.dispatchKeyEvent(e);}
        };
        outside.setBackgroundColor(Color.TRANSPARENT);outside.setOnClickListener(v->hidePanel());
        View content=AppList.create(this,all,selectedTargets(),target->{hidePanel();launch(target.key(),false);},this::saveTargets,
            quick==null?List.of():quick.entries(),this::quickAction);menuContent=content;
        AppList.blur(content,config.menuBlur());
        content.setOnClickListener(v->{});
        WindowMetrics metrics=wm.getCurrentWindowMetrics();
        android.graphics.Rect display=metrics.getBounds();
        Geometry.Placement placement=AppList.placement(this,metrics);
        FrameLayout.LayoutParams card=new FrameLayout.LayoutParams(Math.round(placement.width()*placement.scale()),
            Math.round(placement.height()*placement.scale()),Gravity.TOP|Gravity.LEFT);
        card.leftMargin=Math.round(placement.x()-display.left);card.topMargin=Math.round(placement.y()-display.top);outside.addView(content,card);
        WindowManager.LayoutParams p=params(-1,-1,WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        p.flags|=WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        p.windowAnimations=0;panel=outside;
        outside.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            public void onViewAttachedToWindow(View v){panelDispatcher=v.findOnBackInvokedDispatcher();if(panelDispatcher!=null)panelDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,panelBack);}
            public void onViewDetachedFromWindow(View v){if(panelDispatcher!=null){panelDispatcher.unregisterOnBackInvokedCallback(panelBack);panelDispatcher=null;}}
        });
        outside.setAlpha(0);
        try{wm.addView(outside,p);outside.animate().alpha(1).setDuration(150).start();updateOverlayPriority();publish(false);}
        catch(RuntimeException failure){panel=null;menuContent=null;Toast.makeText(this,"应用面板打开失败",Toast.LENGTH_SHORT).show();}
    }
    private void backPanel(){if(menuContent==null||!AppList.back(menuContent))hidePanel();}
    private void hidePanel(){if(panel!=null){InputMethodManager imm=getSystemService(InputMethodManager.class);if(imm!=null)imm.hideSoftInputFromWindow(panel.getWindowToken(),0);remove(panel);panel=null;menuContent=null;updateOverlayPriority();publish(false);}}
    private void quickAction(String id){if(quick!=null)quick.action(id);}
    private IntentFilter settingsFilter(){IntentFilter filter=new IntentFilter(ConfigProvider.CHANGED);return filter;}
    private void updateOpenWait(Intent frame){
        if(!frame.hasExtra("waitingOpen"))return;
        String token=frame.getStringExtra("waitingToken"),pkg=frame.getStringExtra("waitingPackage");
        boolean waiting=frame.getBooleanExtra("waitingOpen",false);
        boolean valid=Wire.validToken(token)&&Wire.allowedPackage(pkg);
        boolean permitted=waiting&&valid&&ready()&&config.enabled()&&getSystemService(android.os.PowerManager.class).isInteractive()
            &&!getSystemService(KeyguardManager.class).isKeyguardLocked();
        OpenWaitState.Change change=openWaitState.update(true,waiting,valid,permitted,token,pkg);
        if(change==OpenWaitState.Change.KEEP)return;
        removeOpenWait();
        if(change==OpenWaitState.Change.HIDE)return;
        FrameLayout outside=new FrameLayout(this){
            @Override public boolean dispatchKeyEvent(KeyEvent event){
                if(event.getKeyCode()==KeyEvent.KEYCODE_BACK){if(event.getAction()==KeyEvent.ACTION_UP)cancelOpenWait();return true;}
                return super.dispatchKeyEvent(event);
            }
        };
        outside.setOnClickListener(v->cancelOpenWait());
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setGravity(Gravity.CENTER);card.setPadding(dp(32),dp(28),dp(32),dp(28));
        boolean dark=(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable();
        background.setColor(dark?0xff171717:0xfff0f0f0);background.setCornerRadius(dp(24));card.setBackground(background);card.setOnClickListener(v->{});
        ImageView icon=new ImageView(this);openWaitIcon=icon;String label="";
        for(AppCatalog.App app:all)if(pkg.equals(app.pkg)){icon.setImageDrawable(app.icon);label=app.label;break;}
        icon.setContentDescription(label);card.addView(icon,new LinearLayout.LayoutParams(dp(64),dp(64)));
        ProgressBar progress=new ProgressBar(this);LinearLayout.LayoutParams indicator=new LinearLayout.LayoutParams(dp(28),dp(28));indicator.topMargin=dp(20);card.addView(progress,indicator);
        TextView text=new TextView(this);text.setText("正在打开…");text.setTextSize(14);text.setTextColor(dark?Color.WHITE:Color.BLACK);
        LinearLayout.LayoutParams caption=new LinearLayout.LayoutParams(-2,-2);caption.topMargin=dp(12);card.addView(text,caption);
        outside.addView(card,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
        outside.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            public void onViewAttachedToWindow(View view){openWaitDispatcher=view.findOnBackInvokedDispatcher();if(openWaitDispatcher!=null)openWaitDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,openWaitBack);}
            public void onViewDetachedFromWindow(View view){if(openWaitDispatcher!=null){openWaitDispatcher.unregisterOnBackInvokedCallback(openWaitBack);openWaitDispatcher=null;}}
        });
        WindowManager.LayoutParams params=params(-1,-1,WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        params.setTitle("NativeComplements-opening");params.windowAnimations=0;openWait=outside;
        try{wm.addView(outside,params);updateOverlayPriority();}
        catch(RuntimeException failure){hideOpenWait();}
        main.postDelayed(()->{if(openWaitState.timeoutMatches(token))cancelOpenWait();},13000);
    }
    private void removeOpenWait(){
        if(openWait!=null){remove(openWait);openWait=null;}
        openWaitIcon=null;updateOverlayPriority();
    }
    private void hideOpenWait(){openWaitState.hide();removeOpenWait();}
    private void cancelOpenWait(){
        OpenWaitState.Request request=openWaitState.cancel();removeOpenWait();
        if(request!=null)Wire.send(this,"CANCEL_OPEN",request.pkg(),-1,request.token(),false);
    }
    boolean notificationWindowReady(){
        if(!ready()||!WindowHostPolicy.notificationConfigured(config.enabled(),config.notificationWindow(),backendReady,backendState))return false;
        // Preserve the native short circuit: do not ask Keyguard while the screen is off.
        if(!getSystemService(android.os.PowerManager.class).isInteractive())return false;
        return !getSystemService(KeyguardManager.class).isKeyguardLocked();
    }
    String notificationForegroundPackage(){
        try{
            // A normal window and a mini window can both sit above the fullscreen app.
            var tasks=getSystemService(android.app.ActivityManager.class).getRunningTasks(3);
            if(tasks.isEmpty())return null;
            // Skip both floating window tasks to get the real fullscreen foreground app
            for(var task:tasks){
                if(task.topActivity==null)continue;
                if(task.taskId>0&&(task.taskId==activeTask||task.taskId==pinnedTask))continue; // Skip floating windows
                return task.topActivity.getPackageName();
            }
            return null;
        }catch(RuntimeException unavailable){return null;}
    }
    void openNotification(android.service.notification.StatusBarNotification notice,android.app.PendingIntent pending,Intent fill){
        if(Looper.myLooper()!=main.getLooper()){main.post(()->openNotification(notice,pending,fill));return;}
        cancelOpenWait();hideFan();hidePanel();
        ResultReceiver reply=new ResultReceiver(main){
            private boolean completed;
            // Runs in SystemUI, whose actual permission is checked below. The
            // module APK's manifest does not describe this host UID's grants.
            @android.annotation.SuppressLint("MissingPermission")
            protected void onReceiveResult(int result,Bundle data){
            if(completed)return;completed=true;
            if(result==1||data!=null&&data.getBoolean("superseded"))return;
            try{
                if(data!=null&&data.getBoolean("dispatched")){
                    int task=data.getInt("taskId",-1);
                    if(task>=0&&checkSelfPermission(android.Manifest.permission.REORDER_TASKS)==android.content.pm.PackageManager.PERMISSION_GRANTED){
                        getSystemService(android.app.ActivityManager.class).moveTaskToFront(task,0);return;
                    }
                    throw new IllegalStateException("notification target unavailable after dispatch");
                }
                android.app.ActivityOptions options=android.app.ActivityOptions.makeBasic();
                options.setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS);
                pending.send(SystemUiHost.this,0,fill,null,null,null,options.toBundle());
            }catch(Exception error){android.util.Log.w("NativeComplements","NOTIFICATION_WINDOW_FAILED "+error.getClass().getSimpleName());Toast.makeText(SystemUiHost.this,"通知页面打开失败，请从应用内查看",Toast.LENGTH_LONG).show();}
        }};
        try{Wire.openNotification(this,notice.getPackageName(),pending,fill,reply);}
        catch(RuntimeException error){reply.send(0,null);}
    }
    private void floatCurrent(int attempt,boolean quiet){
        if(!ready()||!WindowHostPolicy.slotConfigured(config.enabled(),backendReady,dropAvailable())||getSystemService(KeyguardManager.class).isKeyguardLocked()){
            Toast.makeText(this,"请先启用小窗控制，并留出默认小窗位置",Toast.LENGTH_SHORT).show();return;
        }
        try{
            var tasks=getSystemService(android.app.ActivityManager.class).getRunningTasks(1);
            if(tasks.isEmpty()||tasks.get(0).topActivity==null)return;
            var task=tasks.get(0);String pkg=task.topActivity.getPackageName();
            if(Wire.PKG.equals(pkg)&&attempt<20){main.postDelayed(()->floatCurrent(attempt+1,quiet),32);return;}
            if(!WindowHostPolicy.currentTarget(android.app.ActivityManager.RunningTaskInfo.class.getField("displayId").getInt(task),Wire.PKG.equals(pkg),
                "com.android.systemui".equals(pkg)||BackBridge.PACKAGE.equals(pkg),LauncherGuard.PACKAGE.equals(pkg),Wire.allowedPackage(pkg))){
                if(!quiet)Toast.makeText(this,"请先打开要转换的小窗应用",Toast.LENGTH_SHORT).show();return;
            }
            if(activeTask>0&&task.taskId==activeTask){if(!quiet)Toast.makeText(this,"当前应用已经是小窗",Toast.LENGTH_SHORT).show();return;}
            cancelOpenWait();Wire.send(this,"GESTURE",pkg,task.taskId,Wire.token(),false);
        }catch(Exception error){Toast.makeText(this,"当前应用转换失败，请稍后重试",Toast.LENGTH_SHORT).show();}
    }
    private void launch(String key,boolean wakeStep) {
        if(!allowed())return;
        long now=android.os.SystemClock.elapsedRealtime();if(now-lastOpen<500)return;lastOpen=now;
        ShortcutTarget target=ShortcutTarget.fromKey(this,key,all);if(target==null)return;
        if(target.quick){hidePanel();quickAction(target.action);return;}
        cancelOpenWait();
        main.postDelayed(()->{
            if(!allowed())return;
            if(!backendReady){Wire.send(this,"STATUS");Toast.makeText(this,"后端尚未就绪，请先在模块内预检",Toast.LENGTH_LONG).show();return;}
            Wire.openApp(this,target.pkg,wakeStep);
        },180);
    }
    @Override public void onConfigurationChanged(Configuration c){cancelOpenWait();hideFan();hidePanel();removeHot();refresh();Wire.send(this,"STATUS");}
    @Override public void onLowMemory(){}
    private void cleanup(){if(cleaned)return;dev.local.sunwindowdemo.features.iconlayers.IconAssets.removeOnReload(iconsReloaded);cancelOpenWait();if(extras!=null)extras.closeBarrage();if(quick!=null){quick.close();quick=null;}if(extras!=null)extras.close();settingsWorker.shutdownNow();try{getApplicationContext().unregisterComponentCallbacks(this);}catch(RuntimeException ignored){}for(BroadcastReceiver receiver:new BroadcastReceiver[]{settingsReceiver,queryReceiver,backendReceiver,screenReceiver,packageReceiver})try{unregisterReceiver(receiver);}catch(RuntimeException ignored){}if(controls!=null){controls.close();}if(miniControls!=null)miniControls.close();Wire.listen(null);cleaned=true;connected=false;if(live==this)live=null;main.removeCallbacksAndMessages(null);hideFan();hidePanel();removeHot();publish(true);if(config!=null)config.prefs.unregisterOnSharedPreferenceChangeListener(prefListener);}
}
