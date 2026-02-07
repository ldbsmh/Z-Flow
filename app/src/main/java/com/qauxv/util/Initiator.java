/*
 * Based on QAuxiliary - An Xposed module
 * https://github.com/cinit/QAuxiliary
 *
 * Modified for Flyme-Freeform project
 */
package com.qauxv.util;

public class Initiator {
    private static ClassLoader sHostClassLoader;

    private Initiator() {
        throw new AssertionError("No instance for you!");
    }

    public static void init(ClassLoader classLoader) {
        sHostClassLoader = classLoader;
    }

    public static ClassLoader getPluginClassLoader() {
        return Initiator.class.getClassLoader();
    }

    public static ClassLoader getHostClassLoader() {
        return sHostClassLoader;
    }
}
