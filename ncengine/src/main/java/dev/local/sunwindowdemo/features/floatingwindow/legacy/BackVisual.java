package dev.local.sunwindowdemo;

import android.content.*;
import android.graphics.RectF;
import android.os.*;
import android.view.SurfaceControl;
import io.github.libxposed.api.*;
import java.lang.reflect.*;

/** Optional Launcher preview handoff. Only SystemUI can read or claim a fresh task pose. */
final class BackVisual {
    static final String STATE=Wire.PKG+".BACK_VISUAL",CHANNEL=STATE+".v1";
    private static volatile Pose current;
    private static volatile IBinder remote;
    private static volatile int claimed=-1,systemUiUid=-1;
    private static volatile long claimedAt;
    record Pose(int task,RectF bounds,float radius,long time,SurfaceControl leash){}
    private static final Binder endpoint=new Binder(){
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
            if(code!=FIRST_CALL_TRANSACTION&&code!=FIRST_CALL_TRANSACTION+1)return super.onTransact(code,data,reply,flags);
            if(systemUiUid<0||getCallingUid()!=systemUiUid||reply==null)return false;
            data.enforceInterface(CHANNEL);int task=data.readInt();data.enforceNoDataAvail();
            Pose pose=current;boolean valid=pose!=null&&pose.task==task&&(code==FIRST_CALL_TRANSACTION+1||SystemClock.elapsedRealtime()-pose.time<600)&&pose.leash.isValid();
            if(valid&&code==FIRST_CALL_TRANSACTION+1){
                claimed=task;claimedAt=SystemClock.elapsedRealtime();
                try(SurfaceControl.Transaction tx=new SurfaceControl.Transaction()){tx.setAlpha(pose.leash,0).apply();}
                catch(RuntimeException stale){valid=false;claimed=-1;}
            }
            reply.writeNoException();reply.writeBoolean(valid);
            if(valid&&code==FIRST_CALL_TRANSACTION){reply.writeTypedObject(pose.bounds,0);reply.writeFloat(pose.radius);}
            return true;
        }
    };
    static void install(XposedModule module,ClassLoader loader){
        java.util.List<XposedInterface.HookHandle> hooks=new java.util.ArrayList<>();
        try{
            Class<?> type=Class.forName("com.android.quickstep.LauncherBackAnimationController",false,loader);
            Field target=field(type,"mBackTarget"),launcher=field(type,"mLauncher"),startRect=field(type,"mStartRect");
            Class<?> targetType=target.getType();Field id=field(targetType,"taskId"),leash=field(targetType,"leash");
            Method transform=type.getDeclaredMethod("applyTransform",RectF.class,float.class);
            Method finish=type.getDeclaredMethod("onCancelFinished");
            hooks.add(module.hook(transform).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();
                try{Object value=target.get(chain.getThisObject());
                    if(value!=null){int task=id.getInt(value);SurfaceControl surface=(SurfaceControl)leash.get(value);
                        RectF bounds=new RectF((RectF)chain.getArg(0));
                        if(surface!=null&&surface.isValid()&&!bounds.isEmpty()){
                            android.graphics.Rect start=(android.graphics.Rect)startRect.get(chain.getThisObject());
                            float visibleRadius=(Float)chain.getArg(1)*bounds.width()/Math.max(1,start.width());
                            current=new Pose(task,bounds,visibleRadius,SystemClock.elapsedRealtime(),surface);
                            if(claimed==task&&SystemClock.elapsedRealtime()-claimedAt<2500)
                                try(SurfaceControl.Transaction tx=new SurfaceControl.Transaction()){tx.setAlpha(surface,0).apply();}
                        }
                    }
                }catch(Throwable ignored){}return result;
            }));
            hooks.add(module.hook(finish).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object value=target.get(chain.getThisObject());
                boolean ours=value!=null&&claimed==id.getInt(value)&&SystemClock.elapsedRealtime()-claimedAt<2500;
                Object result=chain.proceed();
                if(ours){claimed=-1;current=null;
                    try{Object home=launcher.get(chain.getThisObject());
                        Class<?> state=Class.forName("com.android.launcher3.LauncherState",false,loader);
                        Object normal=state.getField("NORMAL").get(null);
                        boolean normalState=false;
                        for(Method candidate:home.getClass().getMethods())if(candidate.getName().equals("isInState")&&candidate.getParameterCount()==1&&candidate.getParameterTypes()[0].isInstance(normal)){
                            normalState=(Boolean)candidate.invoke(home,normal);break;
                        }
                        Object workspace=field(home.getClass(),"mWorkspace").get(home);
                        if(normalState&&!field(workspace.getClass(),"mOverlayShown").getBoolean(workspace)){
                            Object depth=field(home.getClass(),"mDepthController").get(home);
                            // Use the native state's depth, including its wallpaper zoom calculation.
                            Method set=null;for(Method candidate:depth.getClass().getMethods())if(candidate.getName().equals("setState")&&candidate.getParameterCount()==1&&candidate.getParameterTypes()[0].isInstance(normal)){
                                if(set==null||candidate.getParameterTypes()[0]==state)set=candidate;
                            }
                            if(set!=null)set.invoke(depth,normal);
                        }
                    }catch(Throwable unavailable){android.util.Log.w("NativeComplements","BACK_DEPTH_RESTORE_SKIPPED "+unavailable.getClass().getSimpleName());}
                }
                return result;
            }));
        }catch(Throwable unavailable){for(var hook:hooks)try{hook.unhook();}catch(Throwable ignored){}android.util.Log.w("NativeComplements","BACK_VISUAL_UNAVAILABLE");}
    }
    static void publish(Context c){
        try{systemUiUid=c.getPackageManager().getApplicationInfo(BackBridge.PACKAGE,0).uid;
            Bundle extras=new Bundle();extras.putBinder("channel",endpoint);
            c.sendBroadcast(new Intent(STATE).setPackage(BackBridge.PACKAGE).putExtras(extras),null,
                android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
        }catch(Exception ignored){}
    }
    static void receive(Context c,Intent i,int uid){
        try{if(STATE.equals(i.getAction())&&uid==c.getPackageManager().getApplicationInfo(LauncherGuard.PACKAGE,0).uid)
            remote=i.getExtras()==null?null:i.getExtras().getBinder("channel");
        }catch(Exception ignored){}
    }
    static Pose pose(int task){
        IBinder sink=remote;if(sink==null)return null;
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try{data.writeInterfaceToken(CHANNEL);data.writeInt(task);
            if(!sink.transact(IBinder.FIRST_CALL_TRANSACTION,data,reply,0))return null;
            reply.readException();if(!reply.readBoolean())return null;
            RectF bounds=reply.readTypedObject(RectF.CREATOR);float radius=reply.readFloat();
            return bounds==null?null:new Pose(task,bounds,radius,SystemClock.elapsedRealtime(),null);
        }catch(Exception ignored){return null;}finally{data.recycle();reply.recycle();}
    }
    static void claim(int task){
        IBinder sink=remote;if(sink==null)return;
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try{data.writeInterfaceToken(CHANNEL);data.writeInt(task);
            if(sink.transact(IBinder.FIRST_CALL_TRANSACTION+1,data,reply,0)){reply.readException();reply.readBoolean();}
        }catch(Exception ignored){}finally{data.recycle();reply.recycle();}
    }
    private static Field field(Class<?> type,String name)throws Exception{
        for(;type!=null;type=type.getSuperclass())try{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(name);
    }
}
