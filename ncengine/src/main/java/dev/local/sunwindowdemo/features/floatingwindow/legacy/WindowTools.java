package dev.local.sunwindowdemo;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.view.InputEvent;
import android.view.KeyEvent;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/** Optional SystemUI-only adapter. No system_server hooks or application scopes. */
final class WindowTools {
    private static Object factory,recordTile;
    private static Method create,click,setSpec;
    static void install(XposedInterface module,ClassLoader loader,Consumer<String> log){
        try{
            Class<?> type=Class.forName("com.android.systemui.qs.tileimpl.QSFactoryImpl",false,loader);
            Class<?> tile=Class.forName("com.android.systemui.qs.tileimpl.QSTileImpl",false,loader);
            Class<?> expandable=Class.forName("com.android.systemui.animation.Expandable",false,loader);
            Method make=type.getDeclaredMethod("createTile",String.class);
            Method press=tile.getDeclaredMethod("click",expandable),spec=tile.getDeclaredMethod("setTileSpec",String.class);
            if(!make.getReturnType().getName().equals("com.android.systemui.plugins.qs.QSTile")||press.getReturnType()!=void.class||spec.getReturnType()!=void.class)throw new NoSuchMethodException("recording tile shape");
            make.setAccessible(true);press.setAccessible(true);spec.setAccessible(true);
            module.hook(make).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();factory=chain.getThisObject();return result;
            });
            create=make;click=press;setSpec=spec;log.accept("WINDOW_RECORD_ADAPTER_READY");
        }catch(Throwable error){log.accept("WINDOW_RECORD_ADAPTER_UNAVAILABLE "+error.getClass().getSimpleName());}
    }
    static boolean record(){
        try{
            if(factory==null||create==null)return false;
            if(recordTile==null){recordTile=create.invoke(factory,"screenrecord");if(recordTile==null)return false;setSpec.invoke(recordTile,"screenrecord");}
            click.invoke(recordTile,new Object[]{null});return true;
        }catch(Exception error){android.util.Log.w("NativeComplements","WINDOW_RECORD_FAILED",error);return false;}
    }
    @android.annotation.SuppressLint("BlockedPrivateApi")
    static boolean screenshot(Context context){
        try{
            InputManager input=context.getSystemService(InputManager.class);
            Method inject=InputManager.class.getDeclaredMethod("injectInputEvent",InputEvent.class,int.class);inject.setAccessible(true);
            long now=SystemClock.uptimeMillis();
            boolean down=(Boolean)inject.invoke(input,new KeyEvent(now,now,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_SYSRQ,0),0);
            boolean up=(Boolean)inject.invoke(input,new KeyEvent(now,now,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_SYSRQ,0),0);
            return down&&up;
        }catch(Exception error){android.util.Log.w("NativeComplements","WINDOW_SCREENSHOT_FAILED",error);return false;}
    }
}
