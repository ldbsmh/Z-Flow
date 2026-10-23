package android.app;

import dev.rikka.tools.refine.RefineAs;

@RefineAs(ActivityManager.class)
public class ActivityManagerHidden {
    public static final int UID_OBSERVER_ACTIVE = 1 << 3;
    /** 进程状态变化也会回调 onUidStateChanged（用于进程重启后重新投递 binder）。 */
    public static final int UID_OBSERVER_PROCSTATE = 1 << 0;
    public static final int PROCESS_STATE_UNKNOWN = -1;
}
