package io.relimus.zflow.xposed.nativewin;

import android.app.*;
import android.content.*;
import android.os.*;
import java.util.UUID;

final class Wire {
    static final String PKG = "dev.nativecomplements.module";
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
        sendRequest(context,new Intent(COMMAND).setPackage("android").putExtra("protocol",PROTOCOL).putExtra("op","OPEN")
            .putExtra("package",pkg).putExtra("taskId",-1).putExtra("token",token())
            .putExtra("wakeStep",wakeStep).putExtra("hideRecents",new Config(context).hideRecents()));
    }
    static void openNotification(Context context,String pkg,PendingIntent launch,Intent fill,ResultReceiver reply){
        if(!allowedPackage(pkg)||launch==null||!launch.isActivity())throw new IllegalArgumentException("notification activity");
        Intent request=new Intent(COMMAND).setPackage("android").putExtra("protocol",PROTOCOL).putExtra("op","OPEN")
            .putExtra("package",pkg).putExtra("taskId",-1).putExtra("token",token())
            .putExtra("hideRecents",new Config(context).hideRecents()).putExtra("shortcutLaunch",launch)
            .putExtra("shortcutUser",launch.getCreatorUserHandle()).putExtra("openReply",reply).putExtra("notificationLaunch",true);
        if(fill!=null)request.putExtra("launchFillIn",new Intent(fill));
        sendRequest(context,request);
    }
    static void inputDigits(Context context,String digits,int delay,ResultReceiver receiver){
        Intent request=new Intent(COMMAND).setPackage("android").putExtra("protocol",PROTOCOL).putExtra("op","OTP_INPUT")
            .putExtra("token",token()).putExtra("digits",digits).putExtra("delay",delay).putExtra("inputReply",receiver);
        sendRequest(context,request);
    }
    private static void sendRequest(Context context,Intent i){
        Bundle b = new Bundle(); b.putBinder("lease", LEASE); if(listener!=null)b.putBinder("frameChannel",FRAMES); i.putExtras(b);
        context.sendBroadcast(i, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }
    static void send(Context c, String op) { send(c, op, PKG, -1, null, false); }
    // 长侧滑返回 / 预测性返回预览由 SystemUI 的原生实现负责，新后端不再接管。
    static boolean prepareBack(int task,String pkg,String token,Object pose,android.view.SurfaceControl leash){return false;}
    static void cancelBack(String token){}
    static android.view.SurfaceControl.Transaction finishBack(String token){return null;}
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
    // 原「小窗承载测试」通知依赖 Demo 入口 Activity，新后端不提供。

}
