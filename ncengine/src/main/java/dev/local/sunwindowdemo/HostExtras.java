package dev.local.sunwindowdemo;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/** Product-owned companions. The window product has no optional host dependencies. */
interface HostExtras extends AutoCloseable {
    void startCompanions();
    void startBarrage();
    void closeBarrage();
    void notification(NotificationListenerService source, StatusBarNotification notification);
    void notificationRemoved(StatusBarNotification notification);
    boolean rotationSuggestion(Runnable click, Runnable restore);
    void publishRotationSettings();
    boolean blocksEdgeTouch(float x, float y);
    void environment(String foreground, boolean systemFocus, String overlayTitle, boolean keyboard,
                     boolean screenshotVisible, boolean panelShown, boolean openWaitShown, int imeTop);
    void edgeEnvironment(boolean systemOverlay, boolean keyboard);
    void protectedRects(int[] rectangles);
    @Override void close();
}
