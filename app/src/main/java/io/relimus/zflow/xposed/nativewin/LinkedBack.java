package io.relimus.zflow.xposed.nativewin;
package io.relimus.zflow.xposed.nativewin;

import android.content.Intent;
import android.os.*;
import android.view.MotionEvent;
import io.github.libxposed.api.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Route owned root Back to fade/linked return; preserve ordinary finish and inner pages. */
final class LinkedBack {
    private final FrameworkAccess a;
    private final WindowEngine engine;
    private final Consumer<String> log;
    private final Method callbackSetter,asInterface,backPressed,finishActivity,registerPointer;
    private final Class<?> pointerType;
    private final int invokeCode,cancelCode,startCode;
    private final String descriptor;
    private final Set<Object> observed=Collections.newSetFromMap(new IdentityHashMap<>());
    private final AtomicReference<Pending> pending=new AtomicReference<>();
    private record Pending(WindowEngine.BackRoot root,long committed,long expires,Object owner) {}

    static void install(XposedModule module,FrameworkAccess a,WindowEngine engine,
                        List<XposedInterface.HookHandle> handles,Consumer<String> log){
        List<XposedInterface.HookHandle> added=new ArrayList<>();
        try{
            LinkedBack route=new LinkedBack(a,engine,log);
            added.add(module.hook(route.callbackSetter).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object original=chain.getArg(0),callback=original;
                try{callback=route.wrap(original);}catch(Exception e){log.accept("LINKED_BACK_WRAP_FAILED "+FrameworkAccess.reason(e));}
                return callback==original?chain.proceed():chain.proceed(new Object[]{callback});
            }));
            added.add(module.hook(route.backPressed).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                if(route.consume((IBinder)chain.getArg(0),"root-back"))return null;
                return chain.proceed();
            }));
            added.add(module.hook(route.finishActivity).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                // A real result still belongs to Android. A switched, live Activity
                // must receive false so Activity.finish does not set mFinished=true.
                if((Integer)chain.getArg(1)==0&&chain.getArg(2)==null
                    &&route.consume((IBinder)chain.getArg(0),"root-finish"))return false;
                return chain.proceed();
            }));
            handles.addAll(added);log.accept("LINKED_ROOT_BACK_HOOK_READY hooks="+added.size());
        }catch(Throwable e){
            for(XposedInterface.HookHandle handle:added)try{handle.unhook();}catch(Throwable ignored){}
            log.accept("LINKED_ROOT_BACK_HOOK_UNAVAILABLE "+FrameworkAccess.reason(e));
        }
    }

    private LinkedBack(FrameworkAccess access,WindowEngine windows,Consumer<String> logger)throws Exception{
        a=access;engine=windows;log=logger;
        ClassLoader loader=a.task.getClassLoader();
        Class<?> callback=Class.forName("android.window.IOnBackInvokedCallback",false,loader);
        Class<?> stub=Class.forName("android.window.IOnBackInvokedCallback$Stub",false,loader);
        Class<?> builder=Class.forName("android.window.BackNavigationInfo$Builder",false,loader);
        callbackSetter=a.method(builder,"setOnBackInvokedCallback",callback);
        asInterface=a.method(stub,"asInterface",IBinder.class);
        descriptor=(String)a.field(callback,"DESCRIPTOR").get(null);
        invokeCode=a.field(stub,"TRANSACTION_onBackInvoked").getInt(null);
        cancelCode=a.field(stub,"TRANSACTION_onBackCancelled").getInt(null);
        startCode=a.field(stub,"TRANSACTION_onBackStarted").getInt(null);
        Class<?> client=Class.forName("com.android.server.wm.ActivityClientController",false,loader);
        backPressed=a.method(client,"onBackPressed",IBinder.class,Class.forName("android.app.IRequestFinishCallback",false,loader));
        finishActivity=a.method(client,"finishActivity",IBinder.class,int.class,Intent.class,int.class);
        if(backPressed.getReturnType()!=void.class||finishActivity.getReturnType()!=boolean.class
            ||invokeCode==cancelCode||invokeCode==startCode)throw new NoSuchMethodException("root Back signatures");
        a.method(a.activity,"forTokenLocked",IBinder.class);
        a.method(a.activity,"findMainWindow",boolean.class);
        Class<?> window=Class.forName("com.android.server.wm.WindowState",false,loader);
        Class<?> info=a.method(window,"getOnBackInvokedCallbackInfo").getReturnType();
        a.method(info,"getCallback");a.method(info,"isSystemCallback");a.method(window,"isVisible");
        a.field(window,"mActivityRecord");
        pointerType=Class.forName("android.view.WindowManagerPolicyConstants$PointerEventListener",false,loader);
        registerPointer=a.method(a.dc,"registerPointerEventListener",pointerType);
    }

    private Object wrap(Object callback)throws Exception{
        if(!(callback instanceof IInterface original))return callback;
        WindowEngine.BackRoot root=engine.linkedBackRoot();if(root==null)return callback;
        Object info=a.call(root.window(),"getOnBackInvokedCallbackInfo");if(info==null)return callback;
        Object registered=a.call(info,"getCallback");
        if(!(registered instanceof IInterface nativeCallback)||!nativeCallback.asBinder().equals(original.asBinder()))return callback;
        boolean system=a.bool(info,"isSystemCallback");
        if(!system)observe(root.display());
        IBinder target=original.asBinder();
        Binder relay=new Binder(){
            @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
                if(engine.backCaller(Binder.getCallingUid())){
                    if(code==startCode||code==cancelCode){
                        Pending ticket=pending.get();
                        if(ticket!=null&&ticket.root().activity()==root.activity())pending.compareAndSet(ticket,null);
                    }
                    if(code==invokeCode){
                        int position=data.dataPosition();
                        try{data.enforceInterface(descriptor);data.enforceNoDataAvail();}finally{data.setDataPosition(position);}
                        if(system&&route(root,null,"system-callback"))return true;
                        if(!system&&engine.sameBackRoot(root)){
                            long now=SystemClock.uptimeMillis();
                            Pending ticket=new Pending(root,now,now+1200,this);pending.set(ticket);
                            new Handler(Looper.getMainLooper()).postDelayed(()->pending.compareAndSet(ticket,null),1200);
                            log.accept("LINKED_BACK_COMMITTED task="+root.taskId()+" callback=app");
                        }
                    }
                }
                // Incoming Binder data can point into the driver's read-only mapping.
                // BinderProxy.transact rewrites the WorkSource header; it needs an
                // owned writable Parcel, including copied Binder object metadata.
                Parcel outgoing=Parcel.obtain(target);
                try{
                    outgoing.appendFrom(data,0,data.dataSize());outgoing.setDataPosition(0);
                    return target.transact(code,outgoing,reply,flags);
                }catch(RemoteException|RuntimeException e){
                    Pending ticket=pending.get();if(ticket!=null&&ticket.owner()==this)pending.compareAndSet(ticket,null);throw e;
                }finally{outgoing.recycle();}
            }
        };
        return asInterface.invoke(null,relay);
    }

    private synchronized void observe(Object display)throws Exception{
        if(observed.contains(display))return;
        Object listener=Proxy.newProxyInstance(pointerType.getClassLoader(),new Class<?>[]{pointerType},(proxy,method,args)->{
            if(method.getDeclaringClass()==Object.class)return switch(method.getName()){
                case "hashCode"->System.identityHashCode(proxy);
                case "equals"->proxy==args[0];
                default->"NativeComplements-linked-back";
            };
            if(args!=null&&args.length>0&&args[0] instanceof MotionEvent event&&event.getActionMasked()==MotionEvent.ACTION_DOWN){
                Pending ticket=pending.get();
                if(ticket!=null&&event.getEventTime()>=ticket.committed())pending.compareAndSet(ticket,null);
            }
            return null;
        });
        registerPointer.invoke(display,listener);observed.add(display);
    }

    private boolean consume(IBinder token,String source){
        Pending ticket=pending.get();if(ticket==null||Binder.getCallingUid()!=ticket.root().uid())return false;
        if(SystemClock.uptimeMillis()>ticket.expires()){pending.compareAndSet(ticket,null);return false;}
        // A page above the root may finish normally without consuming root Back.
        if(!engine.isBackRootToken(ticket.root(),token)||!pending.compareAndSet(ticket,null))return false;
        return route(ticket.root(),token,source);
    }
    private boolean route(WindowEngine.BackRoot root,IBinder token,String source){
        long identity=Binder.clearCallingIdentity();
        try{return engine.returnLinkedBack(root,token,source);}
        finally{Binder.restoreCallingIdentity(identity);}
    }
}
