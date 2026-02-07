/*
 * Based on QAuxiliary - An Xposed module
 * https://github.com/cinit/QAuxiliary
 *
 * Modified for Flyme-Freeform project
 */
package com.qauxv.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.view.ContextThemeWrapper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.sunshine.freeform.R;
import com.qauxv.util.SavedInstanceStatePatchedClassReferencer;

import io.github.kyuubiran.ezxhelper.xposed.EzXposed;

import java.util.Objects;

/**
 * Creates a context wrapper with correct module ClassLoader for system_server use.
 * Required for inflating layouts with AppCompat/Material themes in Xposed hooks.
 */
public class CommonContextWrapper extends ContextThemeWrapper {

    /**
     * Creates a new context wrapper with the specified theme with correct module ClassLoader.
     *
     * @param base  the base context
     * @param theme the resource ID of the theme to be applied on top of the base context's theme
     */
    public CommonContextWrapper(@NonNull Context base, int theme) {
        this(base, theme, null);
    }

    /**
     * Creates a new context wrapper with the specified theme with correct module ClassLoader.
     *
     * @param base          the base context
     * @param theme         the resource ID of the theme to be applied on top of the base context's theme
     * @param configuration the configuration to override the base one
     */
    public CommonContextWrapper(@NonNull Context base, int theme,
                                @Nullable Configuration configuration) {
        super(base, theme);
        if (configuration != null) {
            mOverrideResources = base.createConfigurationContext(configuration).getResources();
        }
        EzXposed.addModuleAssetPath(getResources());
    }

    private ClassLoader mXref = null;
    private Resources mOverrideResources;

    @NonNull
    @Override
    public ClassLoader getClassLoader() {
        if (mXref == null) {
            mXref = new SavedInstanceStatePatchedClassReferencer(
                    CommonContextWrapper.class.getClassLoader());
        }
        return mXref;
    }

    @Nullable
    private static Configuration recreateNighModeConfig(@NonNull Context base, int uiNightMode) {
        Objects.requireNonNull(base, "base is null");
        Configuration baseConfig = base.getResources().getConfiguration();
        if ((baseConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK) == uiNightMode) {
            return null;
        }
        Configuration conf = new Configuration();
        conf.uiMode = uiNightMode | (baseConfig.uiMode & ~Configuration.UI_MODE_NIGHT_MASK);
        return conf;
    }

    @NonNull
    @Override
    public Resources getResources() {
        if (mOverrideResources == null) {
            return super.getResources();
        } else {
            return mOverrideResources;
        }
    }

    public static boolean isAppCompatContext(@NonNull Context context) {
        if (!checkContextClassLoader(context)) {
            return false;
        }
        TypedArray a = context.obtainStyledAttributes(androidx.appcompat.R.styleable.AppCompatTheme);
        try {
            return a.hasValue(androidx.appcompat.R.styleable.AppCompatTheme_windowActionBar);
        } finally {
            a.recycle();
        }
    }

    private static final int[] MATERIAL_CHECK_ATTRS = {com.google.android.material.R.attr.colorPrimaryVariant};

    public static boolean isMaterialDesignContext(@NonNull Context context) {
        if (!isAppCompatContext(context)) {
            return false;
        }
        @SuppressLint("ResourceType") TypedArray a = context.obtainStyledAttributes(MATERIAL_CHECK_ATTRS);
        try {
            return a.hasValue(0);
        } finally {
            a.recycle();
        }
    }

    public static boolean checkContextClassLoader(@NonNull Context context) {
        try {
            ClassLoader cl = context.getClassLoader();
            if (cl == null) {
                return false;
            }
            return cl.loadClass(AppCompatActivity.class.getName()) == AppCompatActivity.class;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @NonNull
    public static Context createAppCompatContext(@NonNull Context base) {
        if (isAppCompatContext(base)) {
            return base;
        }
        return new CommonContextWrapper(base, R.style.Theme_MiFreeform,
                recreateNighModeConfig(base, base.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK));
    }

    @NonNull
    public static Context createMaterialDesignContext(@NonNull Context base) {
        if (isMaterialDesignContext(base)) {
            return base;
        }
        return createAppCompatContext(base);
    }
}
