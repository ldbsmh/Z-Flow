package android.app;

import dev.rikka.tools.refine.RefineAs;

@RefineAs(ActivityManager.class)
public class ActivityManagerHidden {
    public static final int UID_OBSERVER_ACTIVE = 1 << 3;
    public static final int PROCESS_STATE_UNKNOWN = -1;
}
