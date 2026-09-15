// IFreeformManager.aidl
package io.relimus.zflow.xposed;

import android.app.PendingIntent;
import android.content.ComponentName;
import io.relimus.zflow.bean.MotionEventBean;

interface IFreeformManager {
    // Version info
    String getVersionName();
    int getVersionCode();
    int getUid();

    // Window management
    void createWindow(in ComponentName componentName, in PendingIntent pendingIntent,
                      int userId, int taskId,
                      int freeformDpi, int freeformSize, int freeformSizeLand,
                      int floatViewSize, int dimAmount, boolean manualAdjustFreeformRotation,
                      int sourceRotation, int sourceScreenWidth, int sourceScreenHeight);
                      
    void createMiniWindow(in ComponentName componentName, in PendingIntent pendingIntent,
                          int userId, int taskId,
                          int freeformDpi, int freeformSize, int freeformSizeLand,
                          int floatViewSize, int dimAmount, boolean manualAdjustFreeformRotation,
                          int sourceRotation, int sourceScreenWidth, int sourceScreenHeight);
                          
    void destroyWindow(int displayId);
    void destroyAllWindows();
    void moveWindowToTop(int displayId);

    // Input injection (runs in system_server)
    void injectMotionEvent(in MotionEventBean event, int displayId);
    void injectKeyEvent(int keyCode, int displayId);

    // Task management
    void moveTaskToDisplay(int taskId, int displayId);
    void startActivityOnDisplay(in ComponentName componentName, int userId, int displayId);
    void sendPendingIntentOnDisplay(in PendingIntent pendingIntent, int displayId, int taskId);

    // Status
    void collapseStatusBarPanel();
    int getOpenWindowCount();

    // Service status
    boolean isServiceReady();
}