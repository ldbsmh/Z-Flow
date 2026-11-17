package dev.local.sunwindowdemo;

import android.app.*;
import android.content.*;
import android.os.*;
import java.util.UUID;

final class Wire {
    static final String PKG = dev.local.sunwindowdemo.core.ModuleIdentity.CURRENT.packageName();
    static final String COMMAND = PKG + ".COMMAND", RESULT = PKG + ".RESULT";
    static final String PERMISSION = PKG + ".CONTROL";
    static final int PROTOCOL = 16;
    static final Binder LEASE = new Binder();
    static final String CHANNEL = PKG + ".frames.v16";
    static final int MESSAGE = IBinder.FIRST_CALL_TRANSACTION;
    static final int PREPARE_BACK=MESSAGE+1,CANCEL_BACK=MESSAGE+2,FINISH_BACK=MESSAGE+3;
    private static volatile IBinder endpoint;
    private static java.util.function.Consumer<Intent> listener;
    private static Intent pendingFrame;
    private static boolean framePosted;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Binder FRAMES = new Binder(){
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws RemoteException {
            if(code!=MESSAGE)return super.onTransact(code,data,reply,flags);
            if(Binder.getCallingUid()!=1000)return false;
            data.enforceInterface(CHANNEL);
            Intent frame=Intent.CREATOR.createFromParcel(data);data.enforceNoDataAvail();
            if(frame.getIntExtra("protocol",0)!=PROTOCOL)return false;
            synchronized(Wire.class){
                pendingFrame=frame;
                if(!framePosted){framePosted=true;main.post(Wire::deliverFrame);}
            }
            return true;
        }
    };
    private static void deliverFrame(){
        Intent frame;
        synchronized(Wire.class){frame=pendingFrame;pendingFrame=null;framePosted=false;}
        if(listener!=null&&frame!=null)listener.accept(frame);
    }
    static void listen(java.util.function.Consumer<Intent> sink){listener=sink;if(sink==null){endpoint=null;synchronized(Wire.class){pendingFrame=null;}}}
    // Called only after the RESULT sender UID and protocol have been checked.
    static void acceptBackend(Intent i){Bundle b=i.getExtras();if(b!=null)endpoint=b.getBinder("controlChannel");}
    static boolean transmit(IBinder target,Intent i){
        if(target==null)return false;
        Parcel data=Parcel.obtain();
        try{data.writeInterfaceToken(CHANNEL);i.writeToParcel(data,0);return target.transact(MESSAGE,data,null,IBinder.FLAG_ONEWAY);}
        catch(RemoteException e){return false;}finally{data.recycle();}
    }
    static String token() { return "sunwindow5:" + UUID.randomUUID(); }
    // Checked on Binder input/control paths; compile once instead of on every String.matches.
    private static final java.util.regex.Pattern TOKEN=java.util.regex.Pattern.compile("sunwindow5:[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    private static final java.util.regex.Pattern PACKAGE=java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
    static boolean validToken(String value) {
        return value != null && TOKEN.matcher(value).matches();
    }
    static boolean allowedPackage(String value) {
        return value != null && PACKAGE.matcher(value).matches();
    }
    static void send(Context context, String op, String pkg, int taskId, String token, boolean landscape) {
        Intent i = new Intent(COMMAND).setPackage("android").putExtra("protocol", PROTOCOL)
            .putExtra("op", op).putExtra("package", pkg).putExtra("taskId", taskId)
            .putExtra("token", token).putExtra("landscape", landscape).putExtra("hideRecents",new Config(context).hideRecents());
        sendRequest(context,i);
    }
    static void openIntent(Context context,String pkg,Intent original,ResultReceiver reply){
        if(!allowedPackage(pkg)||original==null||!pkg.equals(original.getPackage()))throw new IllegalArgumentException("suggestion target");
        Intent target=new Intent(original).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ActivityOptions options=ActivityOptions.makeBasic();
        // This capability is created only by an explicit suggestion-button click.
        options.setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS);
        PendingIntent launch=PendingIntent.getActivity(context,UUID.randomUUID().hashCode(),target,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_ONE_SHOT,options.toBundle());
        Intent request=new Intent(COMMAND).setPackage("android").putExtra("protocol",PROTOCOL).putExtra("op","OPEN")
            .putExtra("package",pkg).putExtra("taskId",-1).putExtra("token",token())
            .putExtra("hideRecents",new Config(context).hideRecents()).putExtra("shortcutLaunch",launch).putExtra("shortcutUser",android.os.Process.myUserHandle());
        request.putExtra("openReply",reply);sendRequest(context,request);
    }
    static void openApp(Context context,String pkg,boolean wakeStep){
        openApp(context,pkg,-1,wakeStep,false);
    }
    /**
     * 打开窗口的客户端入口。
     *
     * @param taskId 目标任务；-1 表示当前前台任务（上游行为）。
     * @param mini   打开后直接进入迷你窗形态。多任务卡片上滑用的就是它：
     *               引擎在入场结束后把这条请求当成一次 MINI 控制指令执行，
     *               因此不需要客户端自己算时机再补一条指令。
     */
    /** 设置页改了小窗外观：通知引擎立刻刷新能立即生效的部分（变暗），其余下次开窗生效。 */
    static void reloadSettings(Context context,android.os.Bundle windowSettings){
        if(windowSettings==null)return;
        Intent i=new Intent(COMMAND).setPackage("android").putExtra("protocol",PROTOCOL).putExtra("op","SETTINGS")
            .putExtra("windowSettings",windowSettings).putExtra("hideRecents",new Config(context).hideRecents());
        sendRequest(context,i);
    }
    static void openApp(Context context,String pkg,int taskId,boolean wakeStep,boolean mini){
        openApp(context,pkg,taskId,wakeStep,mini,0);
    }
    /**
     * @param maxWindows 同时存在的小窗上限（含前台窗）；0 表示沿用引擎默认值。
     *                   由 Z-Flow 的「最多同时小窗数量」设置下发。
     */
    static void openApp(Context context,String pkg,int taskId,boolean wakeStep,boolean mini,int maxWindows){
        openApp(context,pkg,taskId,wakeStep,mini,maxWindows,null);
    }
    /**
     * @param maxWindows     同时存在的小窗上限（含前台窗）；0 表示沿用引擎默认值。
     * @param windowSettings Z-Flow 的外观设置（尺寸/迷你比例/变暗/贴边样式/横屏），可为 null。
     */
    static void openApp(Context context,String pkg,int taskId,boolean wakeStep,boolean mini,int maxWindows,android.os.Bundle windowSettings){
        Intent i=new Intent(COMMAND).setPackage("android").putExtra("protocol",PROTOCOL).putExtra("op","OPEN")
            .putExtra("package",pkg).putExtra("taskId",taskId).putExtra("token",token())
            .putExtra("wakeStep",wakeStep).putExtra("hideRecents",new Config(context).hideRecents());
        if(mini)i.putExtra("mini",true);
        if(maxWindows>=1)i.putExtra("maxWindows",maxWindows);
        if(windowSettings!=null)i.putExtra("windowSettings",windowSettings);
        sendRequest(context,i);
    }
    static void openNotification(Context context,String pkg,PendingIntent launch,Intent fill,ResultReceiver reply){
        openNotification(context,pkg,launch,fill,reply,null);
    }
    static void openNotification(Context context,String pkg,PendingIntent launch,Intent fill,ResultReceiver reply,android.os.Bundle windowSettings){
        if(!allowedPackage(pkg)||launch==null||!launch.isActivity())throw new IllegalArgumentException("notification activity");
        Intent request=new Intent(COMMAND).setPackage("android").putExtra("protocol",PROTOCOL).putExtra("op","OPEN")
            .putExtra("package",pkg).putExtra("taskId",-1).putExtra("token",token())
            .putExtra("hideRecents",new Config(context).hideRecents()).putExtra("shortcutLaunch",launch)
            .putExtra("shortcutUser",launch.getCreatorUserHandle()).putExtra("openReply",reply).putExtra("notificationLaunch",true);
        if(fill!=null)request.putExtra("launchFillIn",new Intent(fill));
        if(windowSettings!=null)request.putExtra("windowSettings",windowSettings);
        sendRequest(context,request);
    }
    static void inputDigits(Context context,String digits,int delay,ResultReceiver receiver){
        Intent request=new Intent(COMMAND).setPackage("android").putExtra("protocol",PROTOCOL).putExtra("op","OTP_INPUT")
            .putExtra("token",token()).putExtra("digits",digits).putExtra("delay",delay).putExtra("inputReply",receiver);
        sendRequest(context,request);
    }
    private static void sendRequest(Context context,Intent i){
        if(BackBridge.PACKAGE.equals(context.getPackageName())){
            Config config=new Config(context);
            i.putExtra("hostReady",SystemUiHost.live!=null&&SystemUiHost.live.ready()).putExtra("lastTouch",SystemUiHost.lastTouch)
                .putExtra("landscapeShareWindow",config.enabled()&&config.landscapeShareWindow())
                .putExtra("endOnClose",config.endOnClose()).putExtra("keepEdgeAwake",config.enabled()&&config.keepEdgeAwake())
                .putExtra("windowDim",config.windowDim()).putExtra("autoMinimizeOnOpen",config.autoMinimizeOnOpen());
        }
        Bundle b = new Bundle(); b.putBinder("lease", LEASE); if(listener!=null)b.putBinder("frameChannel",FRAMES); i.putExtras(b);
        context.sendBroadcast(i, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }
    static void send(Context c, String op) { send(c, op, PKG, -1, null, false); }
    static void requestStatus(Context context,String requestId){
        if(requestId==null||requestId.isEmpty()||requestId.length()>128)throw new IllegalArgumentException("status request id");
        sendRequest(context,new Intent(COMMAND).setPackage("android").putExtra("protocol",PROTOCOL)
            .putExtra("op","STATUS").putExtra("package",PKG).putExtra("requestId",requestId));
    }
    static boolean prepareBack(int task,String pkg,String token,BackVisual.Pose pose,android.view.SurfaceControl leash){
        IBinder sink=endpoint;if(sink==null)return false;
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try{data.writeInterfaceToken(CHANNEL);data.writeInt(task);data.writeString(pkg);data.writeString(token);
            data.writeTypedObject(pose==null?null:pose.bounds(),0);data.writeFloat(pose==null?0:pose.radius());
            data.writeTypedObject(leash,0);
            if(!sink.transact(PREPARE_BACK,data,reply,0))return false;
            reply.readException();return reply.readBoolean();
        }catch(Exception e){return false;}finally{data.recycle();reply.recycle();}
    }
    static void cancelBack(String token){
        IBinder sink=endpoint;if(sink==null)return;Parcel data=Parcel.obtain();
        try{data.writeInterfaceToken(CHANNEL);data.writeString(token);sink.transact(CANCEL_BACK,data,null,IBinder.FLAG_ONEWAY);}
        catch(Exception ignored){}finally{data.recycle();}
    }
    static android.view.SurfaceControl.Transaction finishBack(String token){
        IBinder sink=endpoint;if(sink==null)return null;
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try{data.writeInterfaceToken(CHANNEL);data.writeString(token);
            if(!sink.transact(FINISH_BACK,data,reply,0))return null;
            reply.readException();return reply.readTypedObject(android.view.SurfaceControl.Transaction.CREATOR);
        }catch(Exception e){return null;}finally{data.recycle();reply.recycle();}
    }
    static void back(Context c,String pkg,int task,String token){
        sendRequest(c,new Intent(COMMAND).setPackage("android").putExtra("protocol",PROTOCOL).putExtra("op","GESTURE")
            .putExtra("package",pkg).putExtra("taskId",task).putExtra("token",token).putExtra("predictiveEntry",true)
            .putExtra("hideRecents",new Config(c).hideRecents()));
    }
    static void control(Context c,String op,String token,float value,float x,float y) { control(c,op,token,-1,value,x,y); }
    static void control(Context c,String op,String token,int taskId,float value,float x,float y) {
        Intent i=new Intent(COMMAND).setPackage("android").putExtra("protocol",PROTOCOL)
            .putExtra("op",op).putExtra("token",token).putExtra("taskId",taskId).putExtra("scale",value).putExtra("x",x).putExtra("y",y);
        if(transmit(endpoint,i))return;
        endpoint=null;
        c.sendBroadcast(i,null,BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }
    static void notification(Context c, boolean active) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (!active) { nm.cancel(5); return; }
        nm.createNotificationChannel(new NotificationChannel("test", "小窗测试恢复", NotificationManager.IMPORTANCE_LOW));
        Intent i = new Intent(Intent.ACTION_MAIN).setPackage(PKG)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent p = PendingIntent.getActivity(c, 5, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            nm.notify(5, new Notification.Builder(c, "test")
                .setSmallIcon(android.R.drawable.ic_menu_revert).setContentTitle("小窗承载测试进行中")
                .setContentText("点击恢复原窗口；小窗无定时退出")
                .setContentIntent(p).setOngoing(true).build());
        } catch (SecurityException ignored) { /* The launcher entry still provides recovery. */ }
    }
}
