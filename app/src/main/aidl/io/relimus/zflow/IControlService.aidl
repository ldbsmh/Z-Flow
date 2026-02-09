// IControlService.aidl
package io.relimus.zflow;

// Declare any non-default types here with import statements
import io.relimus.zflow.bean.MotionEventBean;
import io.relimus.zflow.callback.IOnRotationChangedListener;
import io.relimus.zflow.callback.IAppRunningListener;
import android.view.MotionEvent;

interface IControlService {
    boolean init();
    void pressBack(int displayId);
    void touch(in MotionEventBean motionEventBean);
    boolean moveStack(int displayId);
    boolean execShell(String command, boolean useRoot);
}