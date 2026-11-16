package dev.local.sunwindowdemo;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import io.github.libxposed.api.XposedModule;
import java.util.function.Consumer;

/**
 * Z-Flow 侧的“伴随功能”接入点。
 *
 * 上游该文件按产品变体实现弹幕通知、快捷建议、边缘防误触、图标适配等伴随能力；
 * 这里只并入小窗内核所需的最小实现：所有伴随宿主均不启用，接口保持非空但安全空转，
 * 避免 SystemUiHost 因空指针跳过环境同步。
 */
final class ProductFeatures {
    private ProductFeatures() {}
    static void installServer(XposedModule module, ClassLoader loader, Consumer<String> note){}
    static void installSystemUi(XposedModule module, ClassLoader loader, Consumer<String> note){}
    static void installNotifications(XposedModule module, ClassLoader loader, Consumer<String> note){}
    static void connectSystemUi(Application app, Consumer<String> note){}
    static void installLauncher(XposedModule module, ClassLoader loader, Consumer<String> note){}
    static void connectLauncher(Context context){}
    static Consumer<Intent> createInput(FrameworkAccess access, Context context, Object root, Object lock,
                                        Handler handler, Consumer<String> log){
        log.accept("SUGGESTIONS_INPUT_ABSENT");
        return null;
    }
    static HostExtras hostExtras(Context context){return new EmptyHostExtras();}

    /** 小窗内核只需要该接口存在，用于跳过/透传环境信息。 */
    private static final class EmptyHostExtras implements HostExtras {
        public void startCompanions(){}
        public void startBarrage(){}
        public void closeBarrage(){}
        public void notification(android.service.notification.NotificationListenerService source,
                                 android.service.notification.StatusBarNotification notification){}
        public void notificationRemoved(android.service.notification.StatusBarNotification notification){}
        public boolean rotationSuggestion(Runnable click, Runnable restore){return false;}
        public void publishRotationSettings(){}
        public boolean blocksEdgeTouch(float x, float y){return false;}
        public void environment(String foreground, boolean systemFocus, String overlayTitle, boolean keyboard,
                                boolean screenshotVisible, boolean panelShown, boolean openWaitShown, int imeTop){}
        public void edgeEnvironment(boolean systemOverlay, boolean keyboard){}
        public void protectedRects(int[] rectangles){}
        public void close(){}
    }
}
