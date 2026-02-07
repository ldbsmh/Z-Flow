/*
 * Based on QAuxiliary - An Xposed module
 * https://github.com/cinit/QAuxiliary
 *
 * Modified for Flyme-Freeform project
 */
package com.qauxv.util;

import android.content.Context;

import java.util.Objects;

public class SavedInstanceStatePatchedClassReferencer extends ClassLoader {

    private static final ClassLoader mBootstrap = Context.class.getClassLoader();
    private final ClassLoader mBaseReferencer;
    private final ClassLoader mHostReferencer;

    public SavedInstanceStatePatchedClassReferencer(ClassLoader referencer) {
        super(mBootstrap);
        mBaseReferencer = Objects.requireNonNull(referencer);
        mHostReferencer = Initiator.getHostClassLoader();
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        try {
            return mBootstrap.loadClass(name);
        } catch (ClassNotFoundException ignored) {}
        if (mHostReferencer != null) {
            try {
                if ("androidx.lifecycle.ReportFragment".equals(name)) {
                    return mHostReferencer.loadClass(name);
                }
            } catch (ClassNotFoundException ignored) {}
        }
        return mBaseReferencer.loadClass(name);
    }
}
