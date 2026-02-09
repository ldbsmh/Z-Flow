// IFreeformManager.aidl
package io.relimus.zflow.xposed;

import android.content.ComponentName;
import io.relimus.zflow.bean.MotionEventBean;

interface IFreeformManager {
    // Version info
    String getVersionName();
    int getVersionCode();
    int getUid();

    // Window management
    void createWindow(in ComponentName componentName, int userId, int taskId, int freeformDpi, int freeformSize, int floatViewSize, int dimAmount);
    void destroyWindow(int displayId);
    void destroyAllWindows();
    void moveWindowToTop(int displayId);

    // Input injection (runs in system_server)
    void injectMotionEvent(in MotionEventBean event, int displayId);
    void injectKeyEvent(int keyCode, int displayId);

    // Task management
    void moveTaskToDisplay(int taskId, int displayId);
    void startActivityOnDisplay(in ComponentName componentName, int userId, int displayId);

    // Status
    void collapseStatusBarPanel();
    int getOpenWindowCount();

    // Service status
    boolean isServiceReady();
}
