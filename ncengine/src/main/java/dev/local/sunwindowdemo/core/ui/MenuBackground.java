package dev.local.sunwindowdemo.core.ui;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.WindowManager;
import dev.local.sunwindowdemo.R;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/** Optional native background blur confined to this view's rounded bounds. */
public final class MenuBackground implements View.OnAttachStateChangeListener {
    private final View view;
    private WindowManager manager;
    private Drawable blur;
    private boolean enabled, listening;
    private int color;
    private float radius;
    private final Consumer<Boolean> listener = available -> draw(available && enabled);

    public static void bind(View view, boolean enabled, int color, float radius) {
        Object tag = view.getTag(R.id.menu_background);
        MenuBackground background = tag instanceof MenuBackground ? (MenuBackground) tag : new MenuBackground(view);
        if (background.color == color && background.radius == radius && background.enabled == enabled) return;
        background.enabled = enabled; background.color = color; background.radius = radius;
        background.refresh();
    }

    private MenuBackground(View view) {
        this.view = view;
        view.setTag(R.id.menu_background, this);
        view.addOnAttachStateChangeListener(this);
    }

    private void refresh() {
        boolean available = false;
        if (view.isAttachedToWindow() && enabled) {
            try {
                manager = view.getRootView().getContext().getSystemService(WindowManager.class);
                if (!listening) { listening = true; manager.addCrossWindowBlurEnabledListener(listener); }
                available = manager.isCrossWindowBlurEnabled();
            } catch (RuntimeException unavailable) { stopListening(); }
        } else stopListening();
        draw(available && enabled);
    }

    @android.annotation.SuppressLint("BlockedPrivateApi")
    private void draw(boolean available) {
        if (available && view.isHardwareAccelerated()) {
            try {
                if (blur == null) {
                    Method rootMethod = View.class.getDeclaredMethod("getViewRootImpl");
                    rootMethod.setAccessible(true);
                    Object root = rootMethod.invoke(view);
                    blur = (Drawable) root.getClass().getMethod("createBackgroundBlurDrawable").invoke(root);
                }
                Class<?> type = blur.getClass();
                type.getMethod("setBlurRadius", int.class).invoke(blur, Math.round(24 * view.getResources().getDisplayMetrics().density));
                type.getMethod("setCornerRadius", float.class).invoke(blur, radius);
                type.getMethod("setColor", int.class).invoke(blur, (color & 0x00ffffff) | 0xb8000000);
                view.setBackground(blur);
                return;
            } catch (ReflectiveOperationException | RuntimeException unavailable) { releaseBlur(); }
        } else releaseBlur();
        GradientDrawable solid = new GradientDrawable();
        solid.setColor(Color.rgb(Color.red(color), Color.green(color), Color.blue(color)));
        solid.setCornerRadius(radius);
        view.setBackground(solid);
    }

    private void releaseBlur() {
        if (blur != null) {
            try { blur.getClass().getMethod("setBlurRadius", int.class).invoke(blur, 0); }
            catch (ReflectiveOperationException | RuntimeException ignored) { }
            blur = null;
        }
    }
    private void stopListening() {
        if (listening && manager != null) {
            try { manager.removeCrossWindowBlurEnabledListener(listener); } catch (RuntimeException ignored) { }
        }
        listening = false;
    }
    @Override public void onViewAttachedToWindow(View v) { refresh(); }
    @Override public void onViewDetachedFromWindow(View v) { stopListening(); releaseBlur(); }
}
