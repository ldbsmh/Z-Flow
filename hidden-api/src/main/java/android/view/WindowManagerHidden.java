package android.view;

import dev.rikka.tools.refine.RefineAs;

@RefineAs(WindowManager.class)
public interface WindowManagerHidden {
    int DISPLAY_IME_POLICY_LOCAL = 0;
    int DISPLAY_IME_POLICY_FALLBACK_DISPLAY = 1;
    int DISPLAY_IME_POLICY_HIDE = 2;

    default void setDisplayImePolicy(int displayId, int imePolicy) {
    }

    default int getDisplayImePolicy(int displayId) {
        return DISPLAY_IME_POLICY_FALLBACK_DISPLAY;
    }
}
