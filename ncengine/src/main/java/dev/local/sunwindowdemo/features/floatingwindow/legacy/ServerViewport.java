package dev.local.sunwindowdemo;

import android.content.res.Configuration;
import android.os.IBinder;
import android.graphics.Rect;
import io.github.libxposed.api.*;
import java.lang.reflect.*;
import java.util.*;

/** One logical viewport for the owned task, exported consistently from system_server.
 * Native WM keeps its freeform task and transitions. Only outgoing copies are changed;
 * unrelated activities and WM's cached reports remain native. A process override is
 * mapped only when its configuration source is an activity in the owned task.
 */
final class ServerViewport {
    private final FrameworkAccess a;
    private final Class<?> merged;
    private final Constructor<?> mergedCopy;
    private ServerViewport(FrameworkAccess access)throws Exception{
        a=access;merged=Class.forName("android.util.MergedConfiguration");
        mergedCopy=merged.getDeclaredConstructor(merged);mergedCopy.setAccessible(true);
        a.method(merged,"getOverrideConfiguration");a.method(merged,"setOverrideConfiguration",Configuration.class);
        a.method(merged,"getGlobalConfiguration");a.method(merged,"setGlobalConfiguration",Configuration.class);
        Class<?> windowConfig=Class.forName("android.app.WindowConfiguration");
        a.method(windowConfig,"getDisplayRotation");a.method(windowConfig,"setDisplayRotation",int.class);
        a.method(windowConfig,"setRotation",int.class);a.method(windowConfig,"setMaxBounds",Rect.class);
        a.method(a.activity,"forTokenLocked",IBinder.class);a.method(a.activity,"getTask");
        a.method(a.task,"forAllWindows",java.util.function.Consumer.class,boolean.class);
        Class<?> state=Class.forName("android.view.InsetsState"),source=Class.forName("android.view.InsetsSource");
        a.method(state,"setDisplayFrame",Rect.class);a.method(state,"setDisplayCutout",android.view.DisplayCutout.class);
        a.field(android.view.DisplayCutout.class,"NO_CUTOUT");a.method(source,"setVisible",boolean.class);
    }
    private Configuration configuration(Configuration original,int mode)throws Exception{
        Configuration copy=new Configuration(original);
        // SunOS popup is floating without multi-window restrictions. Stock Android
        // has no popup mode: map only the client identity, never native task state.
        // WeChat compatibility trial: retain the original demo's floating client
        // identity instead of the fullscreen facade. Other apps keep mode 1.
        Object window=a.field(copy,"windowConfiguration");
        a.call(window,"setWindowingMode",new Class<?>[]{int.class},mode);
        Rect bounds=(Rect)a.call(window,"getBounds");
        if(!bounds.isEmpty()){
            // The app's logical display follows its task, not the physical display rotation.
            boolean landscape=bounds.width()>bounds.height();
            copy.orientation=landscape?Configuration.ORIENTATION_LANDSCAPE:Configuration.ORIENTATION_PORTRAIT;
            int rotation=a.integer(window,"getDisplayRotation");
            if(rotation<0||(rotation%2==1)!=landscape)rotation=landscape?1:0;
            a.call(window,"setDisplayRotation",new Class<?>[]{int.class},rotation);
            a.call(window,"setRotation",new Class<?>[]{int.class},rotation);
            a.call(window,"setMaxBounds",new Class<?>[]{Rect.class},new Rect(bounds));
        }
        return copy;
    }
    private Configuration processConfiguration(Configuration original,int mode)throws Exception{
        // A real display-wide configuration must never be rewritten. WPC's task-derived
        // override carries freeform mode; the caller separately verifies its source task.
        return a.integer(a.field(original,"windowConfiguration"),"getWindowingMode")==5
            ?configuration(original,mode):original;
    }
    private Object merged(Object original,int mode)throws Exception{
        Object copy=mergedCopy.newInstance(original);
        a.call(copy,"setGlobalConfiguration",new Class<?>[]{Configuration.class},processConfiguration((Configuration)a.call(copy,"getGlobalConfiguration"),mode));
        a.call(copy,"setOverrideConfiguration",new Class<?>[]{Configuration.class},configuration((Configuration)a.call(copy,"getOverrideConfiguration"),mode));
        return copy;
    }
    @FunctionalInterface interface InsetsMapper { Object map(Object source)throws Exception; }
    /** The native Binder out object keeps its identity; the owner mapper copies WM's state before mutation. */
    static void mapInsets(FrameworkAccess a,Object outgoing,InsetsMapper mapper)throws Exception{
        if(outgoing==null)return;
        Object mapped=mapper.map(outgoing);
        if(mapped!=outgoing)a.call(outgoing,"set",new Class<?>[]{outgoing.getClass(),boolean.class},mapped,true);
    }
    static void install(XposedModule module,FrameworkAccess a,WindowEngine engine,List<XposedInterface.HookHandle> handles)throws Exception{
        ServerViewport policy=new ServerViewport(a);
        String[] names={"LaunchActivityItem","ActivityRelaunchItem","ActivityConfigurationChangeItem","MoveToDisplayItem","WindowStateResizeItem"};
        for(String name:names){
            Class<?> type=Class.forName("android.app.servertransaction."+name);
            boolean window=name.equals("WindowStateResizeItem");int installed=0;
            for(Constructor<?> constructor:type.getDeclaredConstructors()){
                Class<?>[] types=constructor.getParameterTypes();
                if(types.length==0||!(window?types[0].getName().equals("android.view.IWindow"):types[0]==IBinder.class))continue;
                int lastConfiguration=-1;
                for(int i=1;i<types.length;i++)if(types[i]==Configuration.class)lastConfiguration=i;
                final int overrideIndex=lastConfiguration;
                constructor.setAccessible(true);
                handles.add(module.hook(constructor).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    Object owner=window?engine.clientOwner(chain.getArg(0)):null;
                    boolean owned=window?owner!=null:engine.ownsActivityToken((IBinder)chain.getArg(0));
                    boolean entry=name.equals("LaunchActivityItem")||name.equals("ActivityRelaunchItem");
                    if(!owned&&!entry)return chain.proceed();
                    int mode=!owned?1:window?engine.clientMode(owner):engine.clientMode(chain.getArg(0),true);
                    Object[] args=new Object[types.length];
                    for(int i=0;i<args.length;i++){
                        args[i]=chain.getArg(i);
                        // Launch carries both process and activity config. Mapping only the
                        // activity left Application resources with the native freeform value.
                        if(owned&&types[i]==Configuration.class&&args[i]!=null)args[i]=i==overrideIndex
                            ?policy.configuration((Configuration)args[i],mode):policy.processConfiguration((Configuration)args[i],mode);
                        else if(owned&&types[i]==policy.merged&&args[i]!=null)args[i]=policy.merged(args[i],mode);
                        else if(owned&&types[i].getName().equals("android.view.InsetsState")&&args[i]!=null)
                            args[i]=window?engine.ownerInsets(owner,args[i]):engine.clientInsets(chain.getArg(0),args[i]);
                        if(entry&&(types[i]==Configuration.class||types[i]==policy.merged))
                            engine.trace.transaction((IBinder)chain.getArg(0),name,i,chain.getArg(i),args[i],owned);
                    }
                    return chain.proceed(args);
                }));installed++;
            }
            if(installed==0)throw new NoSuchMethodException(name+" outgoing constructor");
        }
        Class<?> process=Class.forName("com.android.server.wm.WindowProcessController",false,a.task.getClassLoader());
        Field configActivity=a.field(process,"mConfigActivityRecord");
        Class<?> item=Class.forName("android.app.servertransaction.ConfigurationChangeItem");
        Field itemConfig=a.field(item,"mConfiguration"),itemDevice=a.field(item,"mDeviceId");
        Constructor<?> newItem=item.getDeclaredConstructor(Configuration.class,int.class);newItem.setAccessible(true);
        Method schedule=a.method(process,"scheduleClientTransactionItem",Class.forName("android.app.IApplicationThread"),Class.forName("android.app.servertransaction.ClientTransactionItem"));
        handles.add(module.hook(schedule).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
            Object outgoing=chain.getArg(1),source=configActivity.get(chain.getThisObject());
            if(!engine.idle()&&item.isInstance(outgoing)&&source!=null&&engine.owns(a.call(source,"getTask"))){
                Configuration original=(Configuration)itemConfig.get(outgoing);
                Configuration mapped=policy.processConfiguration(original,engine.activityClientMode(source));
                if(mapped!=original)return chain.proceed(new Object[]{chain.getArg(0),newItem.newInstance(mapped,itemDevice.getInt(outgoing))});
            }
            return chain.proceed();
        }));
        Class<?> wms=Class.forName("com.android.server.wm.WindowManagerService",false,a.task.getClassLoader());
        Class<?> resultType=Class.forName("android.view.WindowRelayoutResult");
        Field resultConfig=a.field(resultType,"mergedConfiguration"),resultInsets=a.field(resultType,"insetsState");
        int installed=0,added=0;
        for(Method method:wms.getDeclaredMethods())if(method.getName().equals("relayoutWindow")||method.getName().equals("addWindow")){
            boolean initial=method.getName().equals("addWindow");
            Class<?>[] types=method.getParameterTypes();int client=-1,result=-1;
            if(initial&&(method.getReturnType()!=int.class||types.length!=9
                ||!types[0].getName().equals("com.android.server.wm.Session")
                ||!types[1].getName().equals("android.view.IWindow")||types[2]!=android.view.WindowManager.LayoutParams.class
                ||types[3]!=int.class||types[4]!=int.class||types[5]!=int.class||types[6]!=int.class
                ||!types[7].getName().equals("android.view.InputChannel")||types[8]!=resultType))continue;
            for(int i=0;i<types.length;i++){
                if(types[i].getName().equals("android.view.IWindow"))client=i;
                if(types[i]==resultType)result=i;
            }
            if(client<0||result<0)continue;
            final int clientIndex=client,resultIndex=result;method.setAccessible(true);
            handles.add(module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object value=chain.proceed();
                if(value instanceof Integer&&(Integer)value<0)return value;
                Object resultObject=chain.getArg(resultIndex);
                Object owner=resultObject==null?null:engine.clientOwner(chain.getArg(clientIndex));
                if(owner!=null){
                    // addWindow fills Insets before the client's first layout, but not mergedConfiguration.
                    // Never map its empty configuration into a fabricated fullscreen report.
                    if(!initial){
                        Object outgoing=resultConfig.get(resultObject);
                        a.call(outgoing,"setTo",new Class<?>[]{policy.merged},policy.merged(outgoing,engine.clientMode(owner)));
                    }
                    mapInsets(a,resultInsets.get(resultObject),insets->engine.ownerInsets(owner,insets));
                }
                return value;
            }));if(initial)added++;else installed++;
        }
        if(installed!=1||added!=1)throw new NoSuchMethodException("expected one addWindow and relayoutWindow output, found "+added+"/"+installed);
        // Direct insets-control notifications bypass resize transactions and relayout.
        Class<?> proxy=Class.forName("android.view.IWindow$Stub$Proxy");
        Class<?> state=Class.forName("android.view.InsetsState");
        Class<?> controls=Class.forName("android.view.InsetsSourceControl$Array");
        Method notify=a.method(proxy,"insetsControlChanged",state,controls);
        handles.add(module.hook(notify).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
            Object owner=engine.clientOwner(chain.getThisObject());
            if(owner==null)return chain.proceed();
            return chain.proceed(new Object[]{engine.ownerInsets(owner,chain.getArg(0)),chain.getArg(1)});
        }));
        module.log(4,"SunWindowDemo5","VIEWPORT_READY system-side launch/process/activity/add/resize/relayout logical-display");
    }
}
